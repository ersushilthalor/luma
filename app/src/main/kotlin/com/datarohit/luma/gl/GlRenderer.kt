package com.datarohit.luma.gl

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Global colour-engine configuration (see [GlRenderer.engine]). */
data class EngineConfig(
    /** Luma's colour engine renders (otherwise the HAL's output passes through unchanged). */
    val enabled: Boolean = true,
    /** The camera streams Luma scene-log (IspMode.NEUTRAL_LOG). */
    val inputLog: Boolean = false,
    /** Linear gain before tone mapping: 2^(highlight headroom EV) when AE was asked to underexpose. */
    val gain: Float = 1f,
    val look: ColorLook = ColorLook.NATURAL,
    val grain: Boolean = true,
    /** Local tone mapping strength (0 = off). */
    val localTone: Float = 0f,
    /** Detail lift (the HAL's edge enhancement is off on Luma's stream). */
    val sharpen: Float = 0f,
)

/** A camera stream entering the renderer. [rotated] = the sensor buffer is landscape and shown upright portrait. */
data class InputSpec(val size: Size, val rotated: Boolean = true)

/** Where one input is drawn, in normalised output coordinates (y down). */
data class Tile(
    val input: Int, val x: Float = 0f, val y: Float = 0f, val w: Float = 1f, val h: Float = 1f, val border: Boolean = false,
    /** Centre crop (digital zoom) of this tile's input; 1 = none. */
    val zoom: Float = 1f,
)

/** How an output (display or encoder) is composed. */
data class Composition(
    val tiles: List<Tile> = listOf(Tile(0)),
    /** Extra rotation of the content in degrees (landscape recording, horizon lock). */
    val rotationDeg: Float = 0f,
    /** Keep a constant crop that fits any rotation (360° horizon lock). */
    val rotationSafeCrop: Boolean = false,
    /** Anamorphic desqueeze factor (1 = none). */
    val desqueeze: Float = 1f,
    /** Apply the LUT to this output. */
    val lut: Boolean = false,
    /** Apply spatial effects / grade. */
    val effects: Boolean = true,
    /** Draw monitoring overlays (display only). */
    val monitors: Boolean = false,
    /** Colour engine output for this target: [TONE_NONE], [TONE_FILMIC] or [TONE_LOG]. */
    val tone: Int = GlRenderer.TONE_FILMIC,
    /** Letterbox instead of crop (anamorphic preview). */
    val fit: Boolean = false,
)

/**
 * GPU frame pipeline. Camera streams arrive as SurfaceTextures; every new frame of input 0 renders the
 * preview (with monitoring overlays) and each encoder surface (optionally with the LUT burnt in).
 * All GL work happens on one thread.
 */
class GlRenderer {

    private val thread = HandlerThread("luma-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var idle: EGLSurface
    private lateinit var shader: FrameShader
    private lateinit var shader2d: FrameShader

    private class Input(val spec: InputSpec, val texture: Int, val st: SurfaceTexture, val surface: Surface) {
        val matrix = FloatArray(16).also { System.arraycopy(FrameShader.IDENTITY4, 0, it, 0, 16) }
        var hasFrame = false
        var timestamp = 0L
        val uprightW get() = if (spec.rotated) spec.size.height else spec.size.width
        val uprightH get() = if (spec.rotated) spec.size.width else spec.size.height
    }

    private class Target(
        val id: Int, val surface: EGLSurface, val width: Int, val height: Int, var composition: Composition,
        @Volatile var keepEvery: Int, val syntheticFps: Int,
    ) {
        var frames = 0L
        var kept = 0L
        var firstTs = -1L
    }

    private var inputs: List<Input> = emptyList()
    private var display: EGLSurface? = null
    private var displayW = 0
    private var displayH = 0
    private val targets = mutableListOf<Target>()
    private var nextTargetId = 1
    private var lutTexture = 0
    private var lutSize = 2
    private var pendingSnapshot: Snapshot? = null

    @Volatile var shade: ShadeParams = ShadeParams()
    @Volatile var displayComposition: Composition = Composition(monitors = true)

    private val _fps = MutableStateFlow(0f)
    val fps: StateFlow<Float> = _fps.asStateFlow()
    private var fpsWindowStart = 0L
    private var fpsFrames = 0

    // Lens continuity: mean colour of input 0 per frame; a lens change computes a gain that matches the new
    // lens to the old one and fades it out, so white balance, tint and exposure never jump.
    private class Sample(val ts: Long, val rgb: FloatArray)
    private val history = ArrayDeque<Sample>()
    private var pendingSwitch = NO_SWITCH
    private val gain = floatArrayOf(1f, 1f, 1f)
    private var gainTs = 0L
    private val balance = floatArrayOf(1f, 1f, 1f)
    private var measureFbo = 0
    private var measureTex = 0
    // Two pixel-pack buffers: frame N is read asynchronously and consumed at frame N+1, so the colour
    // measurement never stalls the GL thread waiting for the GPU (which costs frames at 4K60).
    private val pbo = IntArray(2)
    private val pboTs = LongArray(2) { -1L }
    private var pboIndex = 0

    // Delivery statistics of the drawn stream (reset when an encoder is added).
    private var lastDrawnTs = 0L
    private var drawnFrames = 0L
    private var droppedFrames = 0L
    private var maxGapNs = 0L
    private var glNsTotal = 0L
    private var glNsMax = 0L
    @Volatile var expectedFrameNs: Long = 33_333_333L
    @Volatile var continuity: Boolean = true

    /**
     * Global colour engine settings. [EngineConfig.inputLog] says the camera streams Luma scene-log
     * (IspMode.NEUTRAL_LOG); with it off, inputs are the HAL's display-referred output and pass through.
     */
    @Volatile var engine: EngineConfig = EngineConfig()
    private val profileTextures = HashMap<ColorLook, Int>()
    private lateinit var denoiser: TemporalDenoiser
    private lateinit var localMap: LocalToneMap
    private var ltmActive = false

    /**
     * Temporal noise reduction: maximum history weight (0 = off) and the relative sensor noise at mid grey.
     * Set from the live ISO; active only with Luma's engine on a single camera stream.
     */
    @Volatile var tnrStrength: Float = 0f
    @Volatile var tnrNoise: Float = 0.01f
    private var tnrActive = false
    private var maskTexture = 0

    /** Uploads the latest subject mask (person segmentation) for Portrait; null clears it. */
    fun setSubjectMask(width: Int, height: Int, data: ByteArray?) = handler.post {
        if (data == null) {
            if (maskTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(maskTexture), 0)
            maskTexture = 0
            return@post
        }
        egl.makeCurrent(idle)
        if (maskTexture == 0) maskTexture = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(data))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    /** Next frame of input 0 rendered upright through the colour engine (for segmentation), long side [maxSide]. */
    fun snapshotRendered(maxSide: Int, callback: (Bitmap?) -> Unit) = handler.post {
        pendingSnapshot = Snapshot(0, maxSide, callback, rendered = true)
    }
    private var frameSeed = 0f

    // Session transitions: the last displayed frame is held, then cross-faded into the new stream.
    private var frozenFbo = 0
    private var frozenTex = 0
    private var frozenW = 0
    private var frozenH = 0
    private var freezeState = FREEZE_NONE
    private var freezeSinceNs = 0L
    private var fadeStartNs = 0L

    /** Called on the GL thread after every frame of input 0 (sensor timestamp). */
    @Volatile var onFrame: ((Long) -> Unit)? = null

    init {
        runBlocking {
            egl = EglCore()
            idle = egl.createPbuffer(16, 16)
            egl.makeCurrent(idle)
            shader = FrameShader(external = true)
            shader2d = FrameShader(external = false)
            denoiser = TemporalDenoiser()
            localMap = LocalToneMap()
            lutTexture = createLutTexture(LutLibrary.generate("identity", 2) { r, g, b -> floatArrayOf(r, g, b) })
        }
    }

    // ------------------------------------------------------------------------------------------
    // Configuration (blocking, callable from any thread except the GL thread)

    /** Replaces the camera inputs. Returns one Surface per spec, to hand to Camera2 outputs. */
    fun configureInputs(specs: List<InputSpec>): List<Surface> = runBlocking {
        releaseInputs()
        if (::denoiser.isInitialized) denoiser.reset()
        inputs = specs.map { spec ->
            val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(tex)
            st.setDefaultBufferSize(spec.size.width, spec.size.height)
            val input = Input(spec, tex, st, Surface(st))
            st.setOnFrameAvailableListener({ onFrameAvailable(input) }, handler)
            input
        }
        inputs.map { it.surface }
    }

    fun setDisplay(surface: Surface?, width: Int, height: Int) = runBlocking {
        display?.let {
            egl.makeCurrent(idle)
            egl.releaseSurface(it)
        }
        display = null
        if (surface != null && surface.isValid) {
            display = runCatching { egl.createWindowSurface(surface) }.onFailure { Log.w(TAG, "display surface", it) }.getOrNull()
            // The viewfinder must never block the GL thread on vsync: with swap interval 0 a late preview frame
            // replaces the queued one instead of stalling, so the encoder (drawn first) always gets every frame.
            display?.let { d ->
                egl.makeCurrent(d)
                android.opengl.EGL14.eglSwapInterval(android.opengl.EGL14.eglGetCurrentDisplay(), 0)
                egl.makeCurrent(idle)
            }
            displayW = width
            displayH = height
        }
    }

    /**
     * Adds an encoder input surface. [keepEvery] > 1 drops frames (hyperlapse); with [syntheticFps] > 0 the
     * kept frames get evenly spaced timestamps at that rate. Returns a handle.
     */
    fun addEncoder(surface: Surface, width: Int, height: Int, composition: Composition, keepEvery: Int = 1, syntheticFps: Int = 0): Int = runBlocking {
        val s = encoderSurface(surface, composition.tone)
        val t = Target(nextTargetId++, s, width, height, composition, keepEvery.coerceAtLeast(1), syntheticFps)
        targets += t
        resetStats()
        t.id
    }

    /**
     * HDR targets get a 10-bit (RGBA1010102) BT.2020 HLG / PQ surface; if the driver refuses a combination the
     * next best one is used and logged (10-bit untagged, then 8-bit).
     */
    private fun encoderSurface(surface: Surface, tone: Int): EGLSurface {
        val space = when (tone) {
            TONE_HLG -> EglCore.COLORSPACE_BT2020_HLG
            TONE_PQ -> EglCore.COLORSPACE_BT2020_PQ
            else -> 0
        }
        if (space == 0) return egl.createWindowSurface(surface)
        for ((tenBit, cs) in listOf(true to space, true to 0, false to space)) {
            val s = runCatching { egl.createWindowSurface(surface, tenBit, cs) }.getOrNull() ?: continue
            val ok = runCatching { egl.makeCurrent(s); egl.makeCurrent(idle) }.isSuccess
            if (ok) {
                Log.i(TAG, "HDR encoder surface: ${if (tenBit && egl.supportsTenBit) "10-bit" else "8-bit"} colourspace 0x${Integer.toHexString(cs)}")
                return s
            }
            egl.releaseSurface(s)
        }
        return egl.createWindowSurface(surface)
    }

    fun updateEncoder(id: Int, composition: Composition) = handler.post { targets.firstOrNull { it.id == id }?.composition = composition }

    fun setKeepEvery(id: Int, keepEvery: Int) = handler.post { targets.firstOrNull { it.id == id }?.keepEvery = keepEvery.coerceAtLeast(1) }

    fun removeEncoder(id: Int) = runBlocking {
        val t = targets.firstOrNull { it.id == id } ?: return@runBlocking
        Log.i(TAG, "encoder ${t.width}x${t.height}: drew $drawnFrames frames, lost $droppedFrames, max gap %.1f ms, GL time avg %.2f ms max %.2f ms"
            .format(java.util.Locale.ROOT, maxGapNs / 1e6, if (drawnFrames > 0) glNsTotal / drawnFrames / 1e6 else 0.0, glNsMax / 1e6))
        targets.remove(t)
        egl.makeCurrent(idle)
        egl.releaseSurface(t.surface)
    }

    fun setLut(lut: Lut3d?) = handler.post {
        egl.makeCurrent(idle)
        if (lutTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(lutTexture), 0)
        val l = lut ?: LutLibrary.generate("identity", 2) { r, g, b -> floatArrayOf(r, g, b) }
        lutTexture = createLutTexture(l)
        lutSize = l.size
    }

    /** Builds (once) the 33³ RGB16F profile texture for [look] on the GL thread. */
    fun prepareLook(look: ColorLook) = handler.post {
        if (profileTextures.containsKey(look)) return@post
        egl.makeCurrent(idle)
        profileTextures[look] = createFloatLutTexture(ColorProfiles.build(look))
    }

    /**
     * Signals that frames from [sensorTimestampNs] on come from another lens (logical multi-camera switch),
     * or, with 0, that the next frame does (a session restart onto another camera).
     */
    fun lensChanged(sensorTimestampNs: Long) = handler.post {
        pendingSwitch = sensorTimestampNs
        if (::denoiser.isInitialized) denoiser.reset()
    }

    /** Holds the current viewfinder image until [sessionStarted]'s first frame, then cross-fades. */
    fun freeze() = handler.post {
        if (display == null || inputs.firstOrNull()?.hasFrame != true || displayW <= 0 || displayH <= 0) return@post
        try {
            egl.makeCurrent(idle)
            if (frozenFbo == 0 || frozenW != displayW || frozenH != displayH) {
                releaseFrozen()
                val fbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
                val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, displayW, displayH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
                frozenFbo = fbo
                frozenTex = tex
                frozenW = displayW
                frozenH = displayH
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, frozenFbo)
            drawComposition(displayComposition, frozenW, frozenH)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            freezeState = FREEZE_HOLD
            freezeSinceNs = System.nanoTime()
        } catch (e: Exception) {
            Log.w(TAG, "freeze", e)
            freezeState = FREEZE_NONE
        }
    }

    /** The new session is configured: its first frame starts the cross-fade from the held image. */
    fun sessionStarted() = handler.post { if (freezeState == FREEZE_HOLD) freezeState = FREEZE_ARMED }

    /** Reads the next frame of [input] upright, scaled so its long side is at most [maxSide]. */
    fun snapshot(input: Int, maxSide: Int, callback: (Bitmap?) -> Unit) = handler.post {
        pendingSnapshot = Snapshot(input, maxSide, callback)
    }

    fun release() {
        handler.post {
            runCatching {
                releaseInputs()
                targets.forEach { egl.releaseSurface(it.surface) }
                targets.clear()
                display?.let { egl.releaseSurface(it) }
                shader.release()
                shader2d.release()
                denoiser.release()
                denoiser.releaseProgram()
                localMap.release()
                localMap.releasePrograms()
                releaseFrozen()
                egl.releaseSurface(idle)
                egl.release()
            }
            thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------------------------------

    private class Snapshot(val input: Int, val maxSide: Int, val callback: (Bitmap?) -> Unit, val rendered: Boolean = false)

    private fun onFrameAvailable(input: Input) {
        if (input !in inputs) return
        try {
            egl.makeCurrent(idle)
            input.st.updateTexImage()
            input.st.getTransformMatrix(input.matrix)
            input.timestamp = input.st.timestamp
            input.hasFrame = true
        } catch (e: Exception) {
            Log.w(TAG, "updateTexImage", e)
            return
        }
        if (input !== inputs.firstOrNull()) return
        val t0 = System.nanoTime()
        frameSeed = (frameSeed + 17.31f) % 997f
        if (lastDrawnTs > 0 && input.timestamp > lastDrawnTs) {
            val gap = input.timestamp - lastDrawnTs
            if (gap > expectedFrameNs * 3 / 2) droppedFrames += (gap + expectedFrameNs / 2) / expectedFrameNs - 1
            maxGapNs = maxOf(maxGapNs, gap)
        }
        lastDrawnTs = input.timestamp
        drawnFrames++
        if (continuity && inputs.size == 1) runCatching { updateContinuity(input) }.onFailure { Log.w(TAG, "continuity", it) }
        val e = engine
        val tnrNow = e.enabled && e.inputLog && inputs.size == 1 && tnrStrength > 0.01f
        if (tnrNow != tnrActive) Log.i(TAG, "temporal denoise ${if (tnrNow) "on (strength %.2f, noise %.3f)".format(java.util.Locale.ROOT, tnrStrength, tnrNoise) else "off"}")
        tnrActive = tnrNow
        if (tnrActive) {
            runCatching {
                denoiser.process(input.texture, input.matrix, input.spec.size.width, input.spec.size.height, e.inputLog, tnrStrength, tnrNoise)
            }.onFailure {
                Log.w(TAG, "temporal denoise", it)
                tnrActive = false
            }
        }
        ltmActive = e.enabled && e.inputLog && inputs.size == 1 && e.localTone > 0f
        if (ltmActive) {
            runCatching {
                localMap.update(input.texture, input.matrix, e.inputLog, if (tnrActive) denoiser.output else 0, input.spec.size.width, input.spec.size.height)
            }.onFailure {
                Log.w(TAG, "local tone map", it)
                ltmActive = false
            }
        }
        countFps()
        // Recording first: the encoder gets its frame before any display work can delay it.
        drawTargets(input.timestamp)
        drawDisplay()
        pendingSnapshot?.let { s ->
            pendingSnapshot = null
            s.callback(runCatching { readInput(s.input, s.maxSide, s.rendered) }.onFailure { Log.w(TAG, "snapshot", it) }.getOrNull())
        }
        val dt = System.nanoTime() - t0
        glNsTotal += dt
        glNsMax = maxOf(glNsMax, dt)
        onFrame?.invoke(input.timestamp)
    }

    private fun resetStats() {
        lastDrawnTs = 0L
        drawnFrames = 0L
        droppedFrames = 0L
        maxGapNs = 0L
        glNsTotal = 0L
        glNsMax = 0L
    }

    private fun drawDisplay() {
        val d = display ?: return
        val now = System.nanoTime()
        if (freezeState == FREEZE_HOLD) {
            // Keep presenting the held image (the last swapped buffer) until the new session runs.
            if (now - freezeSinceNs < FREEZE_TIMEOUT_NS) return
            freezeState = FREEZE_NONE
        }
        if (freezeState == FREEZE_ARMED) {
            freezeState = FREEZE_FADE
            fadeStartNs = now
        }
        try {
            egl.makeCurrent(d)
            drawComposition(displayComposition, displayW, displayH)
            if (freezeState == FREEZE_FADE) {
                val t = ((now - fadeStartNs).toFloat() / FADE_NS).coerceIn(0f, 1f)
                val alpha = 1f - t * t * (3f - 2f * t)
                if (alpha <= 0.01f || frozenTex == 0) {
                    freezeState = FREEZE_NONE
                } else {
                    GLES30.glViewport(0, 0, displayW, displayH)
                    GLES30.glEnable(GLES30.GL_BLEND)
                    GLES30.glBlendColor(0f, 0f, 0f, alpha)
                    GLES30.glBlendFunc(GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ONE_MINUS_CONSTANT_ALPHA)
                    val geom = geometry(frozenW.toFloat() / frozenH, displayW.toFloat() / displayH, 0f, false, 1f, flipY = false)
                    shader2d.draw(frozenTex, FrameShader.IDENTITY4, geom, displayW, displayH, ShadeParams(), lutTexture, lutSize, monitors = false)
                    GLES30.glDisable(GLES30.GL_BLEND)
                }
            }
            egl.swap(d)
        } catch (e: Exception) {
            Log.w(TAG, "display draw", e)
        }
    }

    private fun drawTargets(timestamp: Long) {
        for (t in targets) {
            val n = t.frames++
            if (n % t.keepEvery != 0L) continue
            if (t.firstTs < 0) t.firstTs = timestamp
            val pts = if (t.syntheticFps > 0) t.firstTs + t.kept * 1_000_000_000L / t.syntheticFps else timestamp
            t.kept++
            try {
                egl.makeCurrent(t.surface)
                drawComposition(t.composition, t.width, t.height)
                egl.setPresentationTime(t.surface, pts)
                egl.swap(t.surface)
            } catch (e: Exception) {
                Log.w(TAG, "encoder draw", e)
            }
        }
    }

    private fun drawComposition(c: Composition, outW: Int, outH: Int) {
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, 0, outW, outH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val p = if (c.effects) shade else ShadeParams(lutMix = shade.lutMix, peaking = shade.peaking, peakColor = shade.peakColor,
            peakThreshold = shade.peakThreshold, zebraIre = shade.zebraIre, falseColor = shade.falseColor)
        val params = if (c.lut) p else p.copy(lutMix = 0f)
        for (tile in c.tiles) {
            val input = inputs.getOrNull(tile.input) ?: continue
            if (!input.hasFrame) continue
            val x = (tile.x * outW).toInt()
            val w = (tile.w * outW).toInt().coerceAtLeast(1)
            val h = (tile.h * outH).toInt().coerceAtLeast(1)
            val y = outH - (tile.y * outH).toInt() - h
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            if (tile.border) {
                val b = (minOf(outW, outH) * 0.006f).toInt().coerceAtLeast(2)
                GLES30.glScissor(x - b, y - b, w + 2 * b, h + 2 * b)
                GLES30.glClearColor(1f, 1f, 1f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            }
            GLES30.glScissor(x, y, w, h)
            GLES30.glViewport(x, y, w, h)
            val geom = geometry(input.uprightW.toFloat() / input.uprightH, w.toFloat() / h, c.rotationDeg, c.rotationSafeCrop, c.desqueeze, flipY = false, fit = c.fit)
            if (tile.zoom > 1f) for (i in intArrayOf(0, 1, 3, 4)) geom[i] /= tile.zoom
            // Detail lift is defined per source pixel: a smaller output (the viewfinder) gets proportionally less,
            // so preview and 4K file look equally crisp.
            val stage = stageFor(c)?.let { st -> st.copy(sharpen = st.sharpen * (w.toFloat() * tile.zoom / input.uprightW).coerceIn(0.3f, 1f)) }
            if (tnrActive && tile.input == 0 && stage != null) {
                // Denoised linear history, laid out in the camera texture's own space (identity transform).
                shader2d.draw(denoiser.output, FrameShader.IDENTITY4, geom, w, h, params, lutTexture, lutSize, c.monitors,
                    balance = balance, engine = stage.copy(inputLinear = true), mask = maskTexture)
            } else {
                shader.draw(input.texture, input.matrix, geom, w, h, params, lutTexture, lutSize, c.monitors,
                    balance = if (tile.input == 0 && inputs.size == 1) balance else null, engine = stage, mask = if (tile.input == 0) maskTexture else 0)
            }
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    private fun readInput(index: Int, maxSide: Int, rendered: Boolean = false): Bitmap? {
        val input = inputs.getOrNull(index) ?: return null
        if (!input.hasFrame) return null
        val scale = min(1f, maxSide.toFloat() / maxOf(input.uprightW, input.uprightH))
        val w = (input.uprightW * scale).toInt() and 1.inv()
        val h = (input.uprightH * scale).toInt() and 1.inv()
        val fbo = IntArray(1)
        val tex = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glGenTextures(1, tex, 0)
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[0], 0)
            GLES30.glViewport(0, 0, w, h)
            val geom = geometry(w.toFloat() / h, w.toFloat() / h, 0f, false, 1f, flipY = true)
            val stage = if (rendered) stageFor(Composition(tone = TONE_FILMIC))?.copy(localTexture = 0, sharpen = 0f, grain = 0f) else null
            shader.draw(input.texture, input.matrix, geom, w, h, ShadeParams(), lutTexture, lutSize, monitors = false, engine = stage)
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            buf.rewind()
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buf) }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(1, tex, 0)
        }
    }

    /**
     * Mean colour of the centre of input 0: a 32x32 render read back through a pixel-pack buffer. Returns
     * the previous frame's mean and timestamp (null until two frames have been measured).
     */
    private fun measure(input: Input): Pair<Long, FloatArray>? {
        if (measureFbo == 0) {
            GLES30.glGenBuffers(2, pbo, 0)
            for (b in pbo) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b)
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, MEASURE * MEASURE * 4, null, GLES30.GL_STREAM_READ)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            measureFbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
            measureTex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, measureTex)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, MEASURE, MEASURE, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, measureFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, measureTex, 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, measureFbo)
        GLES30.glViewport(0, 0, MEASURE, MEASURE)
        val geom = geometry(input.uprightW.toFloat() / input.uprightH, 1f, 0f, false, 1f, flipY = false)
        shader.draw(input.texture, input.matrix, geom, MEASURE, MEASURE, ShadeParams(), lutTexture, lutSize, monitors = false)
        val write = pboIndex
        val read = 1 - pboIndex
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[write])
        GLES30.glReadPixels(0, 0, MEASURE, MEASURE, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        pboTs[write] = input.timestamp
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        pboIndex = read
        var result: Pair<Long, FloatArray>? = null
        if (pboTs[read] >= 0) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[read])
            val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, MEASURE * MEASURE * 4, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            if (mapped != null) {
                // Means in the space the balance gain is applied in: linear light for the scene-log stream.
                val table = if (engine.enabled && engine.inputLog) LOG_TO_LINEAR else CODE_TO_UNIT
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (i in 0 until MEASURE * MEASURE) {
                    r += table[mapped.get(i * 4).toInt() and 0xFF]
                    g += table[mapped.get(i * 4 + 1).toInt() and 0xFF]
                    b += table[mapped.get(i * 4 + 2).toInt() and 0xFF]
                }
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                val n = (MEASURE * MEASURE).toDouble()
                result = pboTs[read] to floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
            }
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        return result
    }

    private fun updateContinuity(input: Input) {
        val (ts, m) = measure(input) ?: return
        val sw = pendingSwitch
        if (sw != NO_SWITCH && (sw <= 0L || ts >= sw)) {
            pendingSwitch = NO_SWITCH
            val before = history.lastOrNull { sw <= 0L || it.ts < sw }
            if (before != null) matchTo(before.rgb, m, ts)
            history.clear()
        }
        history.addLast(Sample(ts, m))
        while (history.size > 12) history.removeFirst()
        val k = if (gainTs > 0L) kotlin.math.exp(-((input.timestamp - gainTs).coerceAtLeast(0L) / 1e9) / DECAY_S).toFloat() else 0f
        for (c in 0..2) balance[c] = 1f + (gain[c] - 1f) * k
    }

    /** Gain that makes [after] (new lens) match how [before] (old lens) was shown, then fades out. */
    private fun matchTo(before: FloatArray, after: FloatArray, ts: Long) {
        fun luma(v: FloatArray) = 0.2126f * v[0] + 0.7152f * v[1] + 0.0722f * v[2]
        if (luma(before) < 0.04f || luma(after) < 0.04f) return
        for (c in 0..2) {
            // The old frames were displayed with the then-current balance applied.
            val shown = before[c] * balance[c]
            gain[c] = ((shown + 0.01f) / (after[c] + 0.01f)).coerceIn(MIN_GAIN, MAX_GAIN)
        }
        gainTs = ts
        Log.i(TAG, "lens continuity gain %.3f %.3f %.3f".format(java.util.Locale.ROOT, gain[0], gain[1], gain[2]))
    }

    private fun stageFor(c: Composition): EngineStage? {
        val e = engine
        if (!e.enabled || !e.inputLog || c.tone == TONE_NONE) return null
        val gain = e.gain
        return EngineStage(
            tone = c.tone,
            inputLog = true,
            gain = gain,
            white = gain,
            shoulderK = ColorProfiles.shoulderFor(gain),
            midOut = ColorProfiles.MID_OUT,
            profileTexture = profileTextures[e.look] ?: 0,
            profileSize = ColorProfiles.SIZE,
            grain = if (e.grain) ColorProfiles.grain(e.look) else 0f,
            seed = frameSeed,
            localTexture = if (ltmActive && c.tone == TONE_FILMIC) localMap.output else 0,
            localStrength = e.localTone,
            localDetail = 1f + 0.4f * e.localTone,
            sharpen = e.sharpen,
        )
    }

    private fun createFloatLutTexture(lut: Lut3d): Int {
        val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        val buf = ByteBuffer.allocateDirect(lut.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(lut.data).position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB16F, lut.size, lut.size, lut.size, 0, GLES30.GL_RGB, GLES30.GL_FLOAT, buf)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        return tex
    }

    private fun releaseFrozen() {
        if (frozenFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(frozenFbo), 0)
        if (frozenTex != 0) GLES30.glDeleteTextures(1, intArrayOf(frozenTex), 0)
        frozenFbo = 0
        frozenTex = 0
    }

    private fun countFps() {
        val now = System.nanoTime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        fpsFrames++
        val elapsed = now - fpsWindowStart
        if (elapsed >= 500_000_000L) {
            _fps.value = fpsFrames * 1e9f / elapsed
            fpsFrames = 0
            fpsWindowStart = now
        }
    }

    private fun releaseInputs() {
        inputs.forEach { input ->
            input.st.setOnFrameAvailableListener(null)
            input.surface.release()
            input.st.release()
            GLES30.glDeleteTextures(1, intArrayOf(input.texture), 0)
        }
        inputs = emptyList()
    }

    private fun createLutTexture(lut: Lut3d): Int {
        val tex = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        val bytes = ByteBuffer.allocateDirect(lut.data.size).order(ByteOrder.nativeOrder())
        for (v in lut.data) bytes.put((v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte())
        bytes.rewind()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, lut.size, lut.size, lut.size, 0, GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, bytes)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        return tex
    }

    private inline fun <T> runBlocking(crossinline block: () -> T): T {
        check(Thread.currentThread() !== thread) { "blocking call on the GL thread" }
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        handler.post {
            result = runCatching {
                if (::egl.isInitialized) egl.makeCurrent(idle)
                block()
            }
            latch.countDown()
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "GL thread timeout" }
        return result!!.getOrThrow()
    }

    companion object {
        private const val TAG = "LumaGl"
        const val TONE_NONE = 0
        const val TONE_FILMIC = 1
        const val TONE_LOG = 2
        /** Scene-referred BT.2020 HLG (reference white at 75 % signal). */
        const val TONE_HLG = 3
        /** BT.2020 PQ (HDR10), diffuse white at 203 nits. */
        const val TONE_PQ = 4
        private val CODE_TO_UNIT = FloatArray(256) { it / 255f }
        private val LOG_TO_LINEAR = FloatArray(256) { com.datarohit.luma.core.camera.session.SceneLog.decode(it / 255f) }
        private const val MEASURE = 32
        private const val NO_SWITCH = Long.MIN_VALUE
        private const val MIN_GAIN = 0.8f
        private const val MAX_GAIN = 1.25f
        /** Time constant of the continuity fade: the new lens's own colour is reached in about 1.5 s. */
        private const val DECAY_S = 0.5
        private const val FREEZE_NONE = 0
        private const val FREEZE_HOLD = 1
        private const val FREEZE_ARMED = 2
        private const val FREEZE_FADE = 3
        private const val FREEZE_TIMEOUT_NS = 3_000_000_000L
        private const val FADE_NS = 220_000_000L

        /**
         * Column-major 3x3 mapping centred output UV to centred source UV, filling the output (crop, never
         * letterbox), with rotation [deg], optional rotation-safe crop and anamorphic desqueeze.
         */
        fun geometry(srcAspect: Float, outAspect: Float, deg: Float, safe: Boolean, desqueeze: Float, flipY: Boolean, fit: Boolean = false): FloatArray {
            val a = srcAspect * desqueeze
            val r = Math.toRadians(deg.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            val k = if (safe) {
                min(a, 1f) / sqrt(outAspect * outAspect + 1f)
            } else {
                // Largest scale at which the rotated output rectangle fits inside the source.
                val hx = outAspect / 2f
                val hy = 0.5f
                val ex = abs(c) * hx + abs(s) * hy
                val ey = abs(s) * hx + abs(c) * hy
                if (fit) kotlin.math.max((a / 2f) / ex, 0.5f / ey) else min((a / 2f) / ex, 0.5f / ey)
            }
            val fy = if (flipY) -1f else 1f
            // x_s = (k / a) * (c * outAspect * u - s * v * fy) ; y_s = k * (s * outAspect * u + c * v * fy)
            return floatArrayOf(
                k / a * c * outAspect, k * s * outAspect, 0f,
                -k / a * s * fy, k * c * fy, 0f,
                0f, 0f, 1f,
            )
        }
    }
}
