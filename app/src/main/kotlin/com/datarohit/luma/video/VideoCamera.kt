package com.datarohit.luma.video

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.datarohit.luma.core.camera.session.FocusMode
import com.datarohit.luma.core.camera.session.FrameInfo
import com.datarohit.luma.core.camera.session.ViewPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** One camera output of a video session. */
data class StreamOut(
    val surface: Surface,
    /** Bind to a physical camera of a logical multi-camera. */
    val physicalId: String? = null,
    /** 10-bit dynamic range profile (DynamicRangeProfiles), null = STANDARD. */
    val dynamicRange: Long? = null,
    /** Included in the repeating request only while recording (direct encoder surfaces). */
    val recordOnly: Boolean = false,
)

/** Measured delivery of the primary stream: real frame rate and frames lost against the requested rate. */
data class FrameTiming(val fps: Float = 0f, val frames: Long = 0, val dropped: Int = 0, val maxGapMs: Float = 0f)

/** One camera device and its outputs. */
data class DeviceSetup(val cameraId: String, val outputs: List<StreamOut>, val analysis: Size? = null)

data class VideoControls(
    val zoomRatio: Float = 1f,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val focusPoint: ViewPoint? = null,
    val focusDiopters: Float = 0f,
    val exposureNs: Long? = null,
    val iso: Int? = null,
    val evIndex: Int = 0,
    val kelvin: Int? = null,
    /** Tint -50..50 (API 36 COLOR_CORRECTION_COLOR_TINT). */
    val tint: Float = 0f,
    val fps: Int = 30,
    val ois: Boolean = true,
    val eis: Boolean = true,
    /** NEUTRAL_LOG: no HAL sharpening, minimal NR, scene-log tone curve (Luma's GPU colour engine renders). */
    val isp: com.datarohit.luma.core.camera.session.IspMode = com.datarohit.luma.core.camera.session.IspMode.CAMERA,
    val torch: Boolean = false,
)

/**
 * Camera2 sessions for video modes: single camera through the GPU, direct-to-encoder (HDR), constrained
 * high speed, concurrent cameras (front + back), and physical streams of one logical camera.
 */
class VideoCamera(
    private val manager: CameraManager,
    /** Shared with the photo controller: devices stay open across photo/video mode switches. */
    private val devices: com.datarohit.luma.core.camera.session.CameraDevicePool,
) {

    private val thread = HandlerThread("luma-video-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mutex = Mutex()

    private class Open(
        val setup: DeviceSetup,
        val device: CameraDevice,
        val chars: CameraCharacteristics,
        var session: CameraCaptureSession? = null,
        var analysis: ImageReader? = null,
    )

    private var open = listOf<Open>()
    private var highSpeed = false
    @Volatile private var controls = VideoControls()
    @Volatile private var recording = false
    private var lastFocusPoint: ViewPoint? = null

    private val _frameInfo = MutableStateFlow<FrameInfo?>(null)
    val frameInfo: StateFlow<FrameInfo?> = _frameInfo.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _timing = MutableStateFlow(FrameTiming())
    /** Sensor-timestamp based delivery statistics of the primary stream since the session started. */
    val timing: StateFlow<FrameTiming> = _timing.asStateFlow()
    private var lastTs = 0L
    private var frameCount = 0L
    private var dropped = 0
    private var maxGap = 0L
    private val recent = LongArray(30)
    private var lastLens: String? = null

    /**
     * Called on the camera thread when the logical camera's active physical lens changes, with the sensor
     * timestamp of the first frame from the new lens.
     */
    @Volatile var onLensChange: ((String?, Long) -> Unit)? = null

    init {
        devices.addLossListener { id, message -> if (open.any { it.setup.cameraId == id }) _error.value = message }
    }

    /** Receives analysis frames of the first device (closed by the caller's handler after return). */
    @Volatile var analysisSink: ((Image) -> Unit)? = null

    val isOpen: Boolean get() = open.isNotEmpty()

    /**
     * Opens every device in [setups] and configures its session. [highSpeedFps] > 0 builds a constrained
     * high-speed session (single device, at most two outputs).
     */
    @SuppressLint("MissingPermission") // Callers start sessions only after the CAMERA permission is granted.
    suspend fun start(setups: List<DeviceSetup>, controls: VideoControls, highSpeedFps: Int = 0) = mutex.withLock {
        closeAllLocked()
        _error.value = null
        this.controls = controls
        highSpeed = highSpeedFps > 0
        resetTiming()
        val opened = mutableListOf<Open>()
        try {
            setups.forEachIndexed { i, setup ->
                val device = devices.acquire(setup.cameraId, exclusive = i == 0)
                opened += Open(setup, device, manager.getCameraCharacteristics(setup.cameraId))
            }
            if (setups.size > 1) {
                val configs = opened.associate { o -> o.setup.cameraId to sessionConfig(o, dummy = true) }
                val ok = runCatching { manager.isConcurrentSessionConfigurationSupported(configs) }.getOrDefault(true)
                check(ok) { "This camera combination cannot stream at the same time on this phone" }
            }
            for (o in opened) o.session = configure(o)
            open = opened
            repeatAll()
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            opened.forEach { o -> runCatching { o.session?.close() }; devices.close(o.setup.cameraId); o.analysis?.close() }
            open = emptyList()
            _error.value = describe(e)
            throw e
        }
    }

    suspend fun stop() = mutex.withLock { closeAllLocked() }

    fun setControls(c: VideoControls) {
        val old = controls
        controls = c
        handler.post {
            runCatching {
                repeatAll()
                if (c.focusMode == FocusMode.LOCKED_AREA && c.focusPoint != null && (c.focusPoint != old.focusPoint)) triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_START)
                if (old.focusMode == FocusMode.LOCKED_AREA && c.focusMode != FocusMode.LOCKED_AREA) triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }.onFailure { Log.w(TAG, "setControls", it) }
        }
    }

    /** Starts or stops sending frames to record-only (direct encoder) outputs. */
    fun setRecording(on: Boolean) {
        recording = on
        handler.post { runCatching { repeatAll() }.onFailure { Log.w(TAG, "recording toggle", it) } }
    }

    fun release() {
        handler.post {
            open.forEach { o -> runCatching { o.session?.close() }; o.analysis?.close() }
            open = emptyList()
            devices.closeAll()
            thread.quitSafely()
        }
    }

    /** True when the camera's timestamps use CLOCK_BOOTTIME. */
    fun timestampsBoottime(cameraId: String): Boolean = runCatching {
        manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    }.getOrDefault(true)

    // ------------------------------------------------------------------------------------------

    private fun sessionConfig(o: Open, dummy: Boolean = false): SessionConfiguration {
        val outputs = o.setup.outputs.map { out ->
            OutputConfiguration(out.surface).apply {
                out.physicalId?.let { setPhysicalCameraId(it) }
                out.dynamicRange?.let { dynamicRangeProfile = it }
            }
        }.toMutableList()
        if (!dummy && o.setup.analysis != null && !highSpeed) {
            val reader = ImageReader.newInstance(o.setup.analysis.width, o.setup.analysis.height, ImageFormat.YUV_420_888, 3)
            reader.setOnImageAvailableListener({ r ->
                try {
                    r.acquireLatestImage()?.use { img -> analysisSink?.invoke(img) }
                } catch (_: IllegalStateException) {
                }
            }, handler)
            o.analysis = reader
            outputs += OutputConfiguration(reader.surface)
        }
        val type = if (highSpeed) SessionConfiguration.SESSION_HIGH_SPEED else SessionConfiguration.SESSION_REGULAR
        return SessionConfiguration(type, outputs, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) = Unit
            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
        })
    }

    private suspend fun configure(o: Open): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val base = sessionConfig(o)
        val config = SessionConfiguration(base.sessionType, base.outputConfigurations, executor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (cont.isActive) cont.resume(session) else session.close()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (cont.isActive) cont.resumeWithException(IllegalStateException("This stream combination is not supported by the camera"))
            }
        })
        o.device.createCaptureSession(config)
    }

    @Volatile private var paused = false

    /** Pauses / resumes the repeating requests (see CameraController.setPaused). Never while recording. */
    fun setPaused(on: Boolean) {
        if (on && recording) return
        paused = on
        handler.post {
            runCatching { if (on) open.forEach { it.session?.stopRepeating() } else repeatAll() }.onFailure { Log.w(TAG, "pause", it) }
        }
    }

    private fun repeatAll() {
        if (paused) return
        open.forEachIndexed { i, o ->
            val s = o.session ?: return@forEachIndexed
            val targets = o.setup.outputs.filter { !it.recordOnly || recording }.map { it.surface } +
                listOfNotNull(o.analysis?.surface)
            if (targets.isEmpty()) return@forEachIndexed
            val builder = o.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            targets.forEach(builder::addTarget)
            apply(builder, o, primary = i == 0)
            if (highSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
                s.setRepeatingBurst(s.createHighSpeedRequestList(builder.build()), callback(i == 0), handler)
            } else {
                s.setSingleRepeatingRequest(builder.build(), executor, callback(i == 0))
            }
        }
    }

    private fun triggerAf(trigger: Int) {
        val o = open.firstOrNull() ?: return
        val s = o.session ?: return
        if (highSpeed) return
        val b = o.device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        o.setup.outputs.filter { !it.recordOnly || recording }.forEach { b.addTarget(it.surface) }
        apply(b, o, primary = true)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        s.capture(b.build(), null, handler)
    }

    private fun apply(b: CaptureRequest.Builder, o: Open, primary: Boolean) {
        val c = controls
        val ch = o.chars
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        com.datarohit.luma.core.camera.session.IspTuning.applyStream(b, ch, c.isp)
        val zoomRange = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        if (primary && zoomRange != null && zoomRange.upper > zoomRange.lower) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper))
        }
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val fpsRange = if (highSpeed) Range(c.fps, c.fps) else ranges.firstOrNull { it.lower == c.fps && it.upper == c.fps }
            ?: ranges.filter { it.upper == c.fps }.maxByOrNull { it.lower } ?: ranges.filter { it.upper <= c.fps }.maxByOrNull { it.upper }
        fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        // Focus.
        val minFocus = ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val region = if (primary) c.focusPoint?.let { meteringRegion(ch, it) } else null
        when {
            c.focusMode == FocusMode.MANUAL && minFocus > 0f && primary -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDiopters.coerceIn(0f, minFocus))
            }
            c.focusMode == FocusMode.LOCKED_AREA && region != null -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            else -> b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        region?.let { b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        // Exposure.
        val iso = c.iso
        val exp = c.exposureNs
        val manual = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (primary && iso != null && exp != null && manual && !highSpeed) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / c.fps)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            val comp = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            if (comp != null) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evIndex.coerceIn(comp.lower, comp.upper))
            if (primary && Build.VERSION.SDK_INT >= 36 && !highSpeed) {
                val priorities = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)?.toSet().orEmpty()
                when {
                    exp != null && CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY in priorities -> {
                        b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
                    }
                    iso != null && CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY in priorities -> {
                        b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
                        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    }
                    else -> b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF)
                }
            }
        }
        // White balance: Kelvin + tint through the API 36 CCT colour correction mode.
        val kelvin = c.kelvin
        val cct = Build.VERSION.SDK_INT >= 36 &&
            ch.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)?.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT) == true
        if (kelvin != null && cct) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
            val range = ch.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
            b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, if (range != null) kelvin.coerceIn(range.lower, range.upper) else kelvin)
            b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, c.tint.roundToInt().coerceIn(-50, 50))
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        // Stabilisation.
        val oisModes = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet().orEmpty()
        if (CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, if (c.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        }
        val eisModes = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
        if (!highSpeed) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, when {
                !c.eis -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in eisModes -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
                CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            })
        }
        val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (flash && primary) b.set(CaptureRequest.FLASH_MODE, if (c.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
    }

    private fun meteringRegion(ch: CameraCharacteristics, p: ViewPoint): MeteringRectangle? {
        val a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val front = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val vx = if (front) 1f - p.x else p.x
        val vy = p.y
        val (sx, sy) = when (orientation) {
            90 -> vy to 1f - vx
            180 -> 1f - vx to 1f - vy
            270 -> 1f - vy to vx
            else -> vx to vy
        }
        val half = (minOf(a.width(), a.height()) * 0.06f).roundToInt()
        val cx = (sx * a.width()).roundToInt()
        val cy = (sy * a.height()).roundToInt()
        return MeteringRectangle(
            Rect((cx - half).coerceIn(0, a.width() - 1), (cy - half).coerceIn(0, a.height() - 1), (cx + half).coerceIn(1, a.width()), (cy + half).coerceIn(1, a.height())),
            MeteringRectangle.METERING_WEIGHT_MAX - 1,
        )
    }

    private var counter = 0

    private fun resetTiming() {
        lastTs = 0L; frameCount = 0L; dropped = 0; maxGap = 0L; lastLens = null
        recent.fill(0L)
        _timing.value = FrameTiming()
    }

    /** Frame pacing from sensor timestamps: a gap above 1.5 frame periods counts the missing frames. */
    private fun track(r: TotalCaptureResult) {
        val ts = r.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val lens = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        if (lens != lastLens) {
            if (lastLens != null) onLensChange?.invoke(lens, ts)
            lastLens = lens
        }
        if (lastTs > 0 && ts > lastTs) {
            val gap = ts - lastTs
            val period = 1_000_000_000L / controls.fps.coerceAtLeast(1)
            // The first frames after start may settle; count losses only once the stream runs.
            if (frameCount > 10) {
                if (gap > period * 3 / 2) dropped += ((gap + period / 2) / period - 1).toInt()
                maxGap = maxOf(maxGap, gap)
            }
            recent[(frameCount % recent.size).toInt()] = gap
        }
        lastTs = ts
        frameCount++
        if (frameCount % 15 == 0L && frameCount > recent.size) {
            val mean = recent.average()
            _timing.value = FrameTiming(if (mean > 0) (1e9 / mean).toFloat() else 0f, frameCount, dropped, maxGap / 1e6f)
        }
    }

    private fun callback(primary: Boolean) = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
            if (primary) track(r)
            if (!primary || counter++ % 4 != 0) return
            _frameInfo.value = FrameInfo(
                activePhysicalId = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
                zoomRatio = r.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1f,
                exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                frameDurationNs = r.get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
                focusDiopters = r.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
                afState = r.get(CaptureResult.CONTROL_AF_STATE) ?: 0,
                aeState = r.get(CaptureResult.CONTROL_AE_STATE) ?: 0,
                physicalCrop = null,
                aperture = r.get(CaptureResult.LENS_APERTURE) ?: 0f,
                focalLengthMm = r.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f,
            )
        }
    }

    private fun closeAllLocked() {
        open.forEach { o ->
            // The device stays open in the shared pool for the next session.
            runCatching { o.session?.close() }
            val reader = o.analysis
            handler.post { reader?.close() }
        }
        open = emptyList()
        recording = false
    }

    private fun describe(e: Exception): String = when (e) {
        is CameraAccessException -> "Camera unavailable (${e.reason})"
        is SecurityException -> "Camera permission is not granted"
        else -> e.message ?: e.toString()
    }

    private companion object {
        const val TAG = "LumaVideoCamera"
    }
}
