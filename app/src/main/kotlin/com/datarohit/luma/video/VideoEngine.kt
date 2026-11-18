package com.datarohit.luma.video

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.DynamicRangeProfiles
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.util.Size
import kotlinx.coroutines.flow.first
import com.datarohit.luma.device.DeviceCapabilities
import com.datarohit.luma.device.HighSpeedOption
import com.datarohit.luma.gl.Composition
import com.datarohit.luma.gl.GlRenderer
import com.datarohit.luma.gl.InputSpec
import com.datarohit.luma.gl.Tile
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class VideoMode { STANDARD, PRO, PORTRAIT, SLOW_MOTION, HYPERLAPSE, DUAL, MULTI }

/** Everything that decides the session shape. */
data class VideoPlan(
    val mode: VideoMode,
    val config: VideoConfig,
    val facingBack: Boolean,
    val backId: String,
    val frontId: String?,
    val slowMoFps: Int = 240,
    val hyperlapse: HyperlapseSpeed = HyperlapseSpeed.AUTO,
    val dualLayout: DualLayout = DualLayout.PIP,
    val separateFiles: Boolean = false,
    val combo: MultiCombo? = null,
) {
    val cameraId: String get() = if (facingBack || frontId == null) backId else frontId
    /** Camera → encoder without the GPU: 10-bit HDR and high-speed recording. */
    /**
     * Camera → encoder without the GPU: only high-speed slow motion and HDR10+ (dynamic metadata Luma cannot
     * author). HLG and HDR10 are rendered by Luma's colour engine into 10-bit BT.2020 surfaces.
     */
    val direct: Boolean get() = mode == VideoMode.SLOW_MOTION ||
        ((mode == VideoMode.STANDARD || mode == VideoMode.PRO) && config.profile == ColorProfile.HDR10_PLUS)
    /** HDR rendered on the GPU. */
    val gpuHdr: Boolean get() = !direct && config.profile.hdr
    val log: Boolean get() = config.profile == ColorProfile.LOG && !direct
    /** HAL tuning of the stream: neutral scene-log for every GPU-rendered plan (Log needs it too). */
    val isp: com.datarohit.luma.core.camera.session.IspMode
        get() = if (!direct && mode != VideoMode.SLOW_MOTION && (config.engine || log || gpuHdr)) com.datarohit.luma.core.camera.session.IspMode.NEUTRAL_LOG
        else com.datarohit.luma.core.camera.session.IspMode.CAMERA
    val inputCount: Int get() = when (mode) {
        VideoMode.DUAL -> 2
        VideoMode.MULTI -> combo?.sources?.size ?: 1
        else -> 1
    }
}

/** What a running recording needs to keep updated. */
class ActiveRecording(
    val recorders: List<Recorder>,
    val encoderIds: List<Int>,
    val compositions: List<Composition>,
    val startedAtMs: Long,
    val direct: Boolean,
    val baseRotation: Float,
)

/**
 * Builds video sessions and recordings from a [VideoPlan]: picks stream sizes, frame rates, codecs and
 * dynamic-range profiles from the capabilities the phone reports, then wires camera → GPU → encoders
 * (or camera → encoder directly for HDR and slow motion).
 */
class VideoEngine(
    private val context: Context,
    private val caps: DeviceCapabilities,
    private val renderer: GlRenderer,
    val camera: VideoCamera,
    private val rates: RateStore,
) {
    var plan: VideoPlan? = null
        private set
    var active: ActiveRecording? = null
        private set
    private var lastControls = VideoControls()

    // ------------------------------------------------------------------------------------------
    // Options

    fun sizesFor(cameraId: String): List<Size> =
        caps.cameras[cameraId]?.videoSizes.orEmpty().map { it.size }.filter { it.width in setOf(7680, 3840, 1920, 1280) }.distinct()

    /**
     * Frame rates offered at [size]: the camera must hold a fixed AE range [f, f] (so the rate is locked,
     * not variable) and the encoder must accept size × rate. Rates above the stream map's advertised
     * minimum frame duration are offered too, unless a measurement on this phone already showed the sensor
     * cannot sustain them; the first session at such a rate is measured ([verifyRate]).
     */
    fun fpsFor(cameraId: String, size: Size, codec: VideoCodec): List<Int> {
        if (caps.cameras[cameraId] == null) return emptyList()
        val advertised = advertisedFps(cameraId, size)
        val ranges = aeRanges(cameraId)
        return CANDIDATE_FPS.filter { f ->
            val locked = if (ranges.isEmpty()) f <= advertised else ranges.any { it.lower == f && it.upper == f }
            locked && caps.encoderSupports(codec.mime, size, f) && (f <= advertised || !rates.failed(cameraId, size, f))
        }
    }

    private fun advertisedFps(cameraId: String, size: Size): Int =
        caps.cameras[cameraId]?.videoSizes?.firstOrNull { it.size == size }?.maxFps ?: 30

    /** True when the plan runs above the advertised rate and has not been measured on this phone yet. */
    fun needsRateCheck(p: VideoPlan): Boolean {
        if (p.mode == VideoMode.SLOW_MOTION || p.inputCount > 1) return false
        val size = streamSize(p.cameraId, p.config)
        return p.config.fps > advertisedFps(p.cameraId, size) && !rates.verified(p.cameraId, size, p.config.fps)
    }

    /**
     * Measures the running session from sensor timestamps (about 1.5 s) and records whether the requested
     * rate is sustained. Returns false when it is not, so the caller can fall back.
     */
    suspend fun verifyRate(p: VideoPlan): Boolean {
        val size = streamSize(p.cameraId, p.config)
        val t = kotlinx.coroutines.withTimeoutOrNull(4_000) { camera.timing.first { it.frames >= 75 } } ?: return true
        val ok = t.fps >= p.config.fps * 0.93f
        Log.i(TAG, "rate check ${p.cameraId} ${size.width}x${size.height}@${p.config.fps}: measured %.1f fps, dropped ${t.dropped} -> ${if (ok) "sustained" else "not sustained"}".format(Locale.ROOT, t.fps))
        rates.record(p.cameraId, size, p.config.fps, ok)
        return ok
    }

    /** Lens summary for the UI: which frame rates each back lens of [cameraId] can lock at 4K / 1080p. */
    fun rateSummary(cameraId: String, codec: VideoCodec): String {
        val sizes = sizesFor(cameraId)
        return sizes.joinToString(" · ") { s -> sizeShort(s) + " " + fpsFor(cameraId, s, codec).joinToString("/") }
    }

    private fun sizeShort(s: Size) = when {
        s.width >= 7680 -> "8K"
        s.width >= 3840 -> "4K"
        s.width >= 1920 -> "1080p"
        else -> "${s.height}p"
    }

    private val aeRangeCache = java.util.concurrent.ConcurrentHashMap<String, List<android.util.Range<Int>>>()

    private fun aeRanges(cameraId: String) = aeRangeCache.getOrPut(cameraId) {
        runCatching {
            context.getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    fun codecs(): List<VideoCodec> = VideoCodec.entries.filter { c -> caps.encoders.any { it.mime == c.mime } }

    fun profiles(cameraId: String, codec: VideoCodec): List<ColorProfile> {
        val cam = caps.cameras[cameraId] ?: return listOf(ColorProfile.SDR)
        val tenBitEncoder = codec == VideoCodec.APV || caps.encoders.any { it.mime == codec.mime && it.tenBit }
        val hevcProfiles = caps.encoders.filter { it.mime == MediaFormat.MIMETYPE_VIDEO_HEVC }.flatMap { it.profiles }.toSet()
        return buildList {
            add(ColorProfile.SDR)
            if (cam.supportsLog) add(ColorProfile.LOG)
            // HLG and HDR10 are rendered by the GPU from the scene-log stream (no camera HDR profile needed).
            if (tenBitEncoder && (cam.supportsLog || cam.supportsHlg)) add(ColorProfile.HLG)
            if (tenBitEncoder && cam.supportsLog && (codec != VideoCodec.HEVC || MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 in hevcProfiles)) add(ColorProfile.HDR10)
            if (tenBitEncoder && cam.supportsHdr10Plus && (codec != VideoCodec.HEVC || MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus in hevcProfiles)) add(ColorProfile.HDR10_PLUS)
        }
    }

    fun slowMoOptions(cameraId: String): List<HighSpeedOption> =
        caps.cameras[cameraId]?.highSpeed.orEmpty().filter { it.fps >= 120 }
            .groupBy { it.fps }.map { (_, v) -> v.filter { it.size.width <= 1920 }.maxByOrNull { it.size.width } ?: v.first() }
            .sortedBy { it.fps }

    /** Normalises a plan to what the hardware supports (size, fps, codec, profile). */
    fun sanitize(plan: VideoPlan): VideoPlan {
        val id = plan.cameraId
        var c = plan.config
        val sizes = sizesFor(id)
        if (sizes.isNotEmpty() && c.size !in sizes) c = c.copy(size = sizes.firstOrNull { it.width <= c.size.width } ?: sizes.last())
        val codecs = codecs()
        if (codecs.isNotEmpty() && c.codec !in codecs) c = c.copy(codec = if (VideoCodec.HEVC in codecs) VideoCodec.HEVC else codecs.first())
        val fps = fpsFor(id, c.size, c.codec)
        if (fps.isNotEmpty() && c.fps !in fps) c = c.copy(fps = fps.lastOrNull { it <= c.fps } ?: fps.first())
        val profiles = profiles(id, c.codec)
        if (c.profile !in profiles) c = c.copy(profile = ColorProfile.SDR)
        if (plan.mode !in setOf(VideoMode.STANDARD, VideoMode.PRO) && c.profile.hdr) c = c.copy(profile = ColorProfile.SDR)
        if (plan.mode in setOf(VideoMode.DUAL, VideoMode.MULTI)) c = c.copy(size = Size(1920, 1080), fps = 30)
        // Portrait Video renders a 40-tap masked bokeh per pixel: 1080p keeps it smooth.
        if (plan.mode == VideoMode.PORTRAIT && c.size.width > 1920) c = c.copy(size = Size(1920, 1080), fps = minOf(c.fps, 30))
        return plan.copy(config = c)
    }

    // ------------------------------------------------------------------------------------------
    // Preview

    suspend fun startPreview(p: VideoPlan, controls: VideoControls) {
        stopRecordingIfAny()
        camera.stop()
        plan = p
        lastControls = controls
        val setups = previewSetups(p, extra = null)
        camera.start(setups.first, controls.copy(fps = setups.second, isp = p.isp), highSpeedFps = if (p.mode == VideoMode.SLOW_MOTION) setups.second else 0)
    }

    /**
     * True when [b] can run on [a]'s camera session unchanged: same camera, stream size, rate and colour
     * path, single input, GPU pipeline. Switching between such modes (Video, Pro Video, Portrait Video,
     * Hyperlapse) then only changes the GPU composition, with no camera reconfiguration.
     */
    fun canReuse(a: VideoPlan, b: VideoPlan): Boolean =
        camera.isOpen && a.cameraId == b.cameraId && !a.direct && !b.direct && a.inputCount == 1 && b.inputCount == 1 &&
            a.mode != VideoMode.SLOW_MOTION && b.mode != VideoMode.SLOW_MOTION && a.isp == b.isp &&
            streamSize(a.cameraId, a.config) == streamSize(b.cameraId, b.config) && a.config.fps == b.config.fps

    /** Takes over [p] for the running session (see [canReuse]). */
    fun adopt(p: VideoPlan) {
        plan = p
    }

    suspend fun stop() {
        stopRecordingIfAny()
        camera.stop()
        plan = null
    }

    /** Builds device setups (and the frame rate) for [p]; [extra] is a direct encoder surface. */
    private fun previewSetups(p: VideoPlan, extra: StreamOut?): Pair<List<DeviceSetup>, Int> {
        return when (p.mode) {
            VideoMode.SLOW_MOTION -> {
                val opt = slowMoOptions(p.backId).firstOrNull { it.fps == p.slowMoFps } ?: slowMoOptions(p.backId).lastOrNull()
                    ?: error("This camera has no high-speed video modes")
                val inputs = renderer.configureInputs(listOf(InputSpec(opt.size)))
                listOf(DeviceSetup(p.backId, listOfNotNull(StreamOut(inputs[0]), extra))) to opt.fps
            }
            VideoMode.DUAL, VideoMode.MULTI -> {
                val combo = p.combo ?: error("No camera combination available")
                val size = if (combo.sources.size <= 2) Size(1920, 1080) else Size(1280, 720)
                val inputs = renderer.configureInputs(combo.sources.map { InputSpec(size) })
                val setups = if (combo.kind == ComboKind.PHYSICAL) {
                    listOf(DeviceSetup(combo.sources.first().deviceId, combo.sources.mapIndexed { i, s -> StreamOut(inputs[i], physicalId = s.physicalId) }))
                } else {
                    combo.sources.mapIndexed { i, s -> DeviceSetup(s.deviceId, listOf(StreamOut(inputs[i]))) }
                }
                setups to 30
            }
            else -> {
                val id = p.cameraId
                val size = streamSize(id, p.config)
                val profile = if (p.direct) cameraProfile(p.config.profile) else null
                val previewProfile = profile?.let { previewProfileFor(id, it) }
                val inputs = renderer.configureInputs(listOf(InputSpec(size)))
                val outs = listOfNotNull(StreamOut(inputs[0], dynamicRange = previewProfile), extra)
                listOf(DeviceSetup(id, outs, analysis = if (p.direct || !analysisAffordable(size, p.config.fps)) null else Size(640, 360))) to p.config.fps
            }
        }
    }

    /**
     * The 640×360 analysis stream (scopes, histogram) is an extra ISP output. Measured on the logical back
     * camera: at 4K60 it costs frames, so Luma keeps it only up to the pixel rate of 4K30.
     */
    fun analysisAffordable(size: Size, fps: Int): Boolean = size.width.toLong() * size.height * fps <= 3840L * 2160 * 30

    private fun streamSize(id: String, c: VideoConfig): Size {
        val opts = caps.cameras[id]?.videoSizes.orEmpty()
        // The rate itself is validated by fpsFor / verifyRate, not by the advertised minimum frame duration.
        return opts.firstOrNull { it.size == c.size }?.size
            ?: opts.filter { it.maxFps >= c.fps }.maxByOrNull { it.size.width }?.size
            ?: Size(1920, 1080)
    }

    private fun cameraProfile(p: ColorProfile): Long? = when (p) {
        ColorProfile.HLG -> DynamicRangeProfiles.HLG10
        ColorProfile.HDR10 -> DynamicRangeProfiles.HDR10
        ColorProfile.HDR10_PLUS -> DynamicRangeProfiles.HDR10_PLUS
        else -> null
    }

    private fun previewProfileFor(id: String, profile: Long): Long {
        val allowed = caps.cameras[id]?.profileConstraints?.get(profile).orEmpty()
        return if (allowed.isEmpty() || DynamicRangeProfiles.STANDARD in allowed) DynamicRangeProfiles.STANDARD else profile
    }

    // ------------------------------------------------------------------------------------------
    // Recording

    /**
     * Starts recording. [quadrant] is the device orientation (0/90/180/270) at start; [rollDeg] the
     * continuous device roll for horizon lock.
     */
    suspend fun startRecording(
        controls: VideoControls,
        quadrant: Int,
        rollDeg: Float,
        storageTree: Uri?,
        audioAllowed: Boolean,
        lutBurn: Boolean,
    ): ActiveRecording {
        val p = plan ?: error("No video session")
        check(active == null) { "Already recording" }
        lastControls = controls
        val time = ZonedDateTime.now()
        val base = "LUMA_" + time.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT))
        fun target(suffix: String): VideoTarget =
            if (storageTree != null) SafVideoTarget(context, storageTree, "$base$suffix.mp4")
            else MediaStoreVideoTarget(context, "$base$suffix.mp4", time.toInstant().toEpochMilli())
        val boottime = camera.timestampsBoottime(p.cameraId)
        val audio = if (p.config.audio && audioAllowed && p.mode != VideoMode.SLOW_MOTION && p.mode != VideoMode.HYPERLAPSE)
            AudioSpec(p.config.audioSource, p.config.audioGainDb) else null
        val front = !p.facingBack && p.mode !in setOf(VideoMode.DUAL, VideoMode.MULTI)

        if (p.direct) {
            val (setups, fps) = previewSetups(p, extra = null)
            val main = setups.first()
            val streamSize = renderer.let { _ ->
                if (p.mode == VideoMode.SLOW_MOTION) slowMoOptions(p.backId).firstOrNull { it.fps == fps }?.size ?: Size(1920, 1080)
                else streamSize(p.cameraId, p.config)
            }
            val sensor = sensorOrientation(p.cameraId)
            val hint = if (front) (sensor - quadrant + 360) % 360 else (sensor + quadrant) % 360
            val spec = encoderSpec(p, streamSize, if (p.mode == VideoMode.SLOW_MOTION) fps else p.config.fps, captureRate = if (p.mode == VideoMode.SLOW_MOTION) fps else null)
            val mapping = if (p.mode == VideoMode.SLOW_MOTION) TimeMapping(timeScale = fps / 30.0) else TimeMapping()
            val rec = Recorder(context, target(""), spec, audio, mapping, hint, boottime)
            try {
                rec.start()
                val profile = cameraProfile(p.config.profile)
                val withEncoder = main.copy(outputs = main.outputs + StreamOut(rec.inputSurface, dynamicRange = profile))
                camera.stop()
                camera.start(listOf(withEncoder), controls.copy(fps = fps, isp = com.datarohit.luma.core.camera.session.IspMode.CAMERA), highSpeedFps = if (p.mode == VideoMode.SLOW_MOTION) fps else 0)
            } catch (e: Exception) {
                rec.abort()
                runCatching { startPreview(p, controls) }
                throw e
            }
            return ActiveRecording(listOf(rec), emptyList(), emptyList(), System.currentTimeMillis(), direct = true, baseRotation = 0f).also { active = it }
        }

        // GPU path.
        val landscape = quadrant == 90 || quadrant == 270
        val baseRotation = if (front) quadrant.toFloat() else -quadrant.toFloat()
        val horizon = p.config.stabilization == Stabilization.HORIZON
        val rotation = if (horizon) baseRotation + rollCorrection(rollDeg, quadrant, front) else baseRotation
        val outputs = mutableListOf<Pair<Size, Composition>>()
        val merged = !(p.separateFiles && p.inputCount > 1)
        val baseSize = p.config.size
        var w = if (landscape) baseSize.width else baseSize.height
        var h = if (landscape) baseSize.height else baseSize.width
        val desq = if (p.config.desqueeze > 1f && p.mode in setOf(VideoMode.STANDARD, VideoMode.PRO)) p.config.desqueeze else 1f
        if (desq > 1f && p.config.bakeDesqueeze) h = align16((w / (w.toFloat() / h * desq)).toInt())
        w = align16(w)
        h = align16(h)
        val effects = p.mode == VideoMode.PORTRAIT
        if (merged) {
            outputs += Size(w, h) to Composition(
                tiles = tilesFor(p, w.toFloat() / h),
                rotationDeg = rotation,
                rotationSafeCrop = horizon,
                desqueeze = if (p.config.bakeDesqueeze) desq else 1f,
                lut = lutBurn,
                effects = effects,
                tone = toneFor(p),
            )
        } else {
            repeat(p.inputCount) { i ->
                outputs += Size(w, h) to Composition(tiles = listOf(Tile(i)), rotationDeg = rotation, lut = lutBurn, effects = false, tone = toneFor(p))
            }
        }
        val recorders = mutableListOf<Recorder>()
        val ids = mutableListOf<Int>()
        try {
            outputs.forEachIndexed { i, (size, comp) ->
                val spec = encoderSpec(p, size, p.config.fps, captureRate = null)
                val rec = Recorder(context, target(if (outputs.size > 1) "_${i + 1}" else ""), spec, if (i == 0) audio else null,
                    TimeMapping(), orientationHint = 0, clockBoottime = boottime)
                recorders += rec
                val keep = if (p.mode == VideoMode.HYPERLAPSE) hyperlapseFactor(p.hyperlapse, 0f) else 1
                ids += renderer.addEncoder(rec.inputSurface, size.width, size.height, comp, keepEvery = keep,
                    syntheticFps = if (p.mode == VideoMode.HYPERLAPSE) 30 else 0)
                rec.start()
            }
        } catch (e: Exception) {
            ids.forEach { renderer.removeEncoder(it) }
            recorders.forEach { it.abort() }
            throw e
        }
        renderer.expectedFrameNs = 1_000_000_000L / p.config.fps.coerceAtLeast(1)
        camera.setRecording(true)
        return ActiveRecording(recorders, ids, outputs.map { it.second }, System.currentTimeMillis(), direct = false, baseRotation = baseRotation)
            .also { active = it }
    }

    /** Encoder rendering: scene log for Luma Log, Luma's display rendering otherwise (or the HAL look). */
    private fun toneFor(p: VideoPlan): Int = when {
        p.gpuHdr && p.config.profile == ColorProfile.HLG -> GlRenderer.TONE_HLG
        p.gpuHdr && p.config.profile == ColorProfile.HDR10 -> GlRenderer.TONE_PQ
        p.log -> GlRenderer.TONE_LOG
        p.config.engine -> GlRenderer.TONE_FILMIC
        else -> GlRenderer.TONE_NONE
    }

    /** Horizon lock: keeps the content level while recording (GPU path). */
    fun updateRoll(rollDeg: Float, quadrant: Int) {
        val a = active ?: return
        val p = plan ?: return
        if (a.direct || p.config.stabilization != Stabilization.HORIZON) return
        val front = !p.facingBack
        a.encoderIds.forEachIndexed { i, id ->
            renderer.updateEncoder(id, a.compositions[i].copy(rotationDeg = a.baseRotation + rollCorrection(rollDeg, quadrant, front)))
        }
    }

    fun setHyperlapseRate(keepEvery: Int) {
        active?.encoderIds?.forEach { renderer.setKeepEvery(it, keepEvery) }
    }

    suspend fun stopRecording(): List<RecordingResult> {
        val a = active ?: return emptyList()
        active = null
        val p = plan
        camera.timing.value.let { t -> Log.i(TAG, "camera delivery: %.1f fps, ${t.frames} frames, lost ${t.dropped}, max gap %.1f ms".format(Locale.ROOT, t.fps, t.maxGapMs)) }
        camera.setRecording(false)
        a.encoderIds.forEach { renderer.removeEncoder(it) }
        if (a.direct) runCatching { camera.stop() }
        val results = a.recorders.mapNotNull { rec ->
            runCatching { rec.stop() }.onFailure { Log.w(TAG, "stop", it) }.getOrNull()
        }
        if (a.direct && p != null) runCatching { startPreview(p, lastControls) }.onFailure { Log.w(TAG, "preview restart", it) }
        if (results.isEmpty()) error("Recording failed: no file was written")
        return results
    }

    private suspend fun stopRecordingIfAny() {
        if (active != null) runCatching { stopRecording() }
    }

    // ------------------------------------------------------------------------------------------

    private fun encoderSpec(p: VideoPlan, size: Size, fps: Int, captureRate: Int?): EncoderSpec {
        val c = p.config
        val hdr = c.profile.hdr
        val mime = c.codec.mime
        val info = caps.encoder(mime, tenBit = hdr) ?: caps.encoder(mime)
        val profile = when {
            c.codec == VideoCodec.H264 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh.takeIf { info?.profiles?.contains(it) == true }
            c.codec == VideoCodec.HEVC -> when (c.profile) {
                ColorProfile.HLG -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                ColorProfile.HDR10 -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                ColorProfile.HDR10_PLUS -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                else -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            }.takeIf { !hdr || info?.profiles?.contains(it) == true } ?: if (hdr) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 else null
            else -> null
        }
        val transfer = when (c.profile) {
            ColorProfile.HLG -> MediaFormat.COLOR_TRANSFER_HLG
            ColorProfile.HDR10, ColorProfile.HDR10_PLUS -> MediaFormat.COLOR_TRANSFER_ST2084
            else -> null
        }
        return EncoderSpec(
            mime = mime,
            codecName = info?.name,
            width = size.width,
            height = size.height,
            fps = fps,
            bitrate = c.bitrate(info?.bitrateMax ?: 0, size).let { if (captureRate != null) (it.toLong() * captureRate / 30 / 2).toInt().coerceAtMost(info?.bitrateMax?.takeIf { m -> m > 0 } ?: Int.MAX_VALUE) else it },
            profile = profile,
            colorStandard = if (hdr) MediaFormat.COLOR_STANDARD_BT2020 else null,
            colorTransfer = if (hdr) transfer else null,
            captureRate = captureRate,
        )
    }

    /** GPU crop beyond the camera's zoom range (recording follows it live). */
    @Volatile private var digitalZoom = 1f

    fun setDigitalZoom(dz: Float) {
        if (kotlin.math.abs(dz - digitalZoom) < 1e-3f) return
        digitalZoom = dz
        val a = active ?: return
        if (a.direct) return
        a.encoderIds.forEachIndexed { i, id ->
            val c = a.compositions[i]
            if (c.tiles.size == 1) renderer.updateEncoder(id, c.copy(tiles = listOf(c.tiles[0].copy(zoom = dz))))
        }
    }

    private fun tilesFor(p: VideoPlan, aspect: Float): List<Tile> {
        val n = p.inputCount
        val landscape = aspect > 1f
        return when {
            n <= 1 -> listOf(Tile(0, zoom = digitalZoom))
            p.mode == VideoMode.DUAL && p.dualLayout == DualLayout.PIP -> {
                val w = 0.32f
                val h = w * aspect * (if (landscape) 9f / 16f else 16f / 9f)
                listOf(Tile(0), Tile(1, x = 1f - w - 0.04f, y = 0.04f * aspect.coerceAtMost(1f), w = w, h = h, border = true))
            }
            n == 2 -> if (landscape) listOf(Tile(0, 0f, 0f, 0.5f, 1f), Tile(1, 0.5f, 0f, 0.5f, 1f))
            else listOf(Tile(0, 0f, 0f, 1f, 0.5f), Tile(1, 0f, 0.5f, 1f, 0.5f))
            n == 3 -> if (landscape) listOf(Tile(0, 0f, 0f, 0.5f, 1f), Tile(1, 0.5f, 0f, 0.5f, 0.5f), Tile(2, 0.5f, 0.5f, 0.5f, 0.5f))
            else listOf(Tile(0, 0f, 0f, 1f, 0.5f), Tile(1, 0f, 0.5f, 0.5f, 0.5f), Tile(2, 0.5f, 0.5f, 0.5f, 0.5f))
            else -> listOf(Tile(0, 0f, 0f, 0.5f, 0.5f), Tile(1, 0.5f, 0f, 0.5f, 0.5f), Tile(2, 0f, 0.5f, 0.5f, 0.5f), Tile(3, 0.5f, 0.5f, 0.5f, 0.5f))
        }
    }

    /** Display composition for the current plan (preview is always portrait). */
    fun displayTiles(p: VideoPlan, aspect: Float): List<Tile> = tilesFor(p, aspect)

    private fun sensorOrientation(id: String): Int = runCatching {
        context.getSystemService(android.hardware.camera2.CameraManager::class.java).getCameraCharacteristics(id)
            .get(CameraCharacteristics.SENSOR_ORIENTATION)
    }.getOrNull() ?: 90

    private fun align16(v: Int) = ((v + 8) / 16 * 16).coerceAtLeast(16)

    companion object {
        private const val TAG = "LumaVideoEngine"
        val CANDIDATE_FPS = listOf(24, 25, 30, 50, 60, 120)

        /** Content rotation that cancels the device roll away from the recording quadrant. */
        fun rollCorrection(rollDeg: Float, quadrant: Int, front: Boolean): Float {
            var d = rollDeg - quadrant
            while (d > 180f) d -= 360f
            while (d < -180f) d += 360f
            return if (front) d else -d
        }

        /** Hyperlapse frame decimation. Auto adapts to measured motion (rad/s). */
        fun hyperlapseFactor(speed: HyperlapseSpeed, angularSpeed: Float): Int = when (speed) {
            HyperlapseSpeed.AUTO -> when {
                angularSpeed > 0.5f -> 8
                angularSpeed > 0.15f -> 16
                else -> 32
            }
            else -> speed.factor
        }
    }
}
