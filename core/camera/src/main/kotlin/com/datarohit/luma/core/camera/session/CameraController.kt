package com.datarohit.luma.core.camera.session

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
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
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * Production Camera2 session: one open camera, a preview surface, a small YUV analysis stream and an
 * optional RAW stream for bursts. All camera callbacks run on a dedicated thread; public suspend
 * functions are serialised.
 */
class CameraController(
    private val manager: CameraManager,
    /** Shared with the video pipeline so mode switches reuse the open device. */
    val devices: CameraDevicePool = CameraDevicePool(manager),
) {

    private val cameraThread = HandlerThread("luma-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val executor = Executor { cameraHandler.post(it) }
    private val imageThread = HandlerThread("luma-images").apply { start() }
    private val imageHandler = Handler(imageThread.looper)
    private val mutex = Mutex()

    private var device: CameraDevice? = null
    private var deviceId: String? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var analysisReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null
    @Volatile private var jpegSink: CompletableDeferred<Pair<ByteArray, Long>>? = null
    private var descriptor: CameraDescriptor? = null
    private var spec: SessionSpec? = null
    @Volatile private var controls = Controls()
    @Volatile private var latest: TotalCaptureResult? = null
    @Volatile private var burstSink: BurstSink? = null
    private val analyzer = PreviewAnalyzer()
    private val resultCounter = AtomicInteger()

    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    val state: StateFlow<CameraState> = _state.asStateFlow()
    private val _frameInfo = MutableStateFlow<FrameInfo?>(null)
    val frameInfo: StateFlow<FrameInfo?> = _frameInfo.asStateFlow()
    private val _analysis = MutableStateFlow<AnalysisStats?>(null)
    val analysis: StateFlow<AnalysisStats?> = _analysis.asStateFlow()

    /** Optional extra consumer of each analysis frame (scopes); runs on the image thread before close. */
    @Volatile var analysisListener: ((Image) -> Unit)? = null

    val latestResult: TotalCaptureResult? get() = latest
    val currentDescriptor: CameraDescriptor? get() = descriptor
    val currentSpec: SessionSpec? get() = spec

    /** Opens (or reuses) the camera and configures a session streaming into [surface]. */
    suspend fun start(descriptor: CameraDescriptor, spec: SessionSpec, surface: Surface, controls: Controls) = mutex.withLock {
        this.controls = controls
        if (_state.value is CameraState.Streaming && this.spec == spec && previewSurface === surface && session != null) {
            // Same streams into the same surface: only the request changes (avoids a costly reconfigure).
            runCatching { repeat() }.onSuccess { return@withLock }
        }
        try {
            _state.value = CameraState.Opening
            closeSessionLocked()
            if (deviceId != spec.cameraId || device == null) {
                parkDeviceLocked()
                device = devices.acquire(spec.cameraId, exclusive = true, timeoutMs = OPEN_TIMEOUT_MS)
                deviceId = spec.cameraId
            }
            val dev = checkNotNull(device)
            this.descriptor = descriptor
            this.spec = spec
            previewSurface = surface
            val analysis = ImageReader.newInstance(spec.analysisSize.width, spec.analysisSize.height, ImageFormat.YUV_420_888, 3)
            analysis.setOnImageAvailableListener({ reader -> onAnalysisImage(reader) }, imageHandler)
            analysisReader = analysis
            val raw = spec.rawSize?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, RAW_READER_DEPTH).also { reader ->
                    reader.setOnImageAvailableListener({ r -> onRawImage(r) }, imageHandler)
                }
            }
            rawReader = raw
            val jpeg = spec.jpegSize?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).also { reader ->
                    reader.setOnImageAvailableListener({ r -> onJpegImage(r) }, imageHandler)
                }
            }
            jpegReader = jpeg
            val maxRes = spec.jpegMaxResolution && jpeg != null
            fun OutputConfiguration.mode(max: Boolean) = apply {
                if (maxRes) addSensorPixelModeUsed(if (max) CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION else CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
            }
            val outputs = buildList {
                add(OutputConfiguration(surface).mode(false))
                add(OutputConfiguration(analysis.surface).mode(false))
                raw?.let { r -> add(OutputConfiguration(r.surface).apply { spec.rawPhysicalId?.let { setPhysicalCameraId(it) } }.mode(false)) }
                jpeg?.let { r -> add(OutputConfiguration(r.surface).mode(true)) }
            }
            session = dev.configure(outputs)
            repeat()
            _state.value = CameraState.Streaming(spec)
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            closeSessionLocked()
            deviceId?.let(devices::close)
            parkDeviceLocked()
            _state.value = CameraState.Failed(describeError(e))
        }
    }

    /** Stops streaming. The device stays open in [devices] for the next session (photo or video). */
    suspend fun stop() = mutex.withLock {
        closeSessionLocked()
        parkDeviceLocked()
        _state.value = CameraState.Closed
    }

    fun release() {
        cameraHandler.post {
            runCatching { session?.close() }
            devices.closeAll()
            analysisReader?.close()
            rawReader?.close()
            jpegReader?.close()
            cameraThread.quitSafely()
            imageThread.quitSafely()
        }
    }

    /** Applies new live controls to the repeating request. Triggers AF when a focus point is set. */
    fun setControls(new: Controls) {
        val old = controls
        controls = new
        cameraHandler.post {
            runCatching {
                repeat()
                val newPoint = new.focusMode == FocusMode.LOCKED_AREA && (new.focusPoint != old.focusPoint || old.focusMode != FocusMode.LOCKED_AREA)
                if (newPoint) triggerAf()
                // Leaving a touch lock: cancel the trigger so continuous AF resumes cleanly.
                if (old.focusMode == FocusMode.LOCKED_AREA && new.focusMode != FocusMode.LOCKED_AREA) triggerAf(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            }.onFailure { Log.w(TAG, "setControls failed", it) }
        }
    }

    /**
     * Captures a RAW burst. [onFrame] runs on the image thread for each frame (paired with its
     * result) and must copy what it needs; the image is closed afterwards.
     */
    suspend fun captureBurst(plan: BurstPlan, onFrame: (RawFrame) -> Unit): Int = mutex.withLock {
        val s = session ?: error("camera not streaming")
        val raw = rawReader ?: error("RAW stream not configured")
        val dev = device ?: error("camera closed")
        val currentSpec = spec ?: error("no session")
        val c = controls

        if (plan.keepAf && c.focusMode == FocusMode.CONTINUOUS) lockAf(s, dev)

        val sink = BurstSink(plan.frames, currentSpec, onFrame)
        burstSink = sink
        try {
            val requests = (0 until plan.frames).map {
                dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(checkNotNull(previewSurface))
                    addTarget(raw.surface)
                    applyCommon(this, c, forCapture = true)
                    if (plan.exposureNs != null && plan.iso != null) {
                        set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNs)
                        set(CaptureRequest.SENSOR_SENSITIVITY, plan.iso)
                        set(CaptureRequest.SENSOR_FRAME_DURATION, plan.exposureNs.coerceAtLeast(MIN_FRAME_NS))
                    } else {
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                    setTag(BURST_TAG)
                }.build()
            }
            s.captureBurstRequests(requests, executor, sink.callback)
            val perFrameMs = ((plan.exposureNs ?: 50_000_000L).coerceAtLeast(MIN_FRAME_NS) / 1_000_000L) + 40L
            withTimeoutOrNull(3_000L + perFrameMs * plan.frames) { sink.done.await() }
            return sink.delivered.get()
        } finally {
            burstSink = null
            sink.drain()
            if (plan.keepAf && c.focusMode == FocusMode.CONTINUOUS) runCatching { unlockAf(s, dev) }
        }
    }

    /** Captures one HAL-processed JPEG still (with AF lock in continuous mode). */
    suspend fun captureStill(jpegOrientation: Int, quality: Int): StillCapture = mutex.withLock {
        val s = session ?: error("camera not streaming")
        val reader = jpegReader ?: error("JPEG stream not configured")
        val dev = device ?: error("camera closed")
        val c = controls
        if (c.focusMode == FocusMode.CONTINUOUS) lockAf(s, dev)
        val image = CompletableDeferred<Pair<ByteArray, Long>>()
        jpegSink = image
        val result = CompletableDeferred<TotalCaptureResult>()
        try {
            val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                // With Luma's neutral stream the preview must not receive this HAL-toned frame.
                if (c.isp == IspMode.CAMERA) addTarget(checkNotNull(previewSurface))
                addTarget(reader.surface)
                applyCommon(this, c, forCapture = true)
                IspTuning.applyHalStill(this, checkNotNull(descriptor).characteristics, c.isp)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                set(CaptureRequest.JPEG_QUALITY, quality.coerceIn(1, 100).toByte())
                if (spec?.jpegMaxResolution == true) set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }.build()
            s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                    result.complete(r)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    result.completeExceptionally(IllegalStateException("capture failed (${failure.reason})"))
                }
            }, cameraHandler)
            val r = withTimeout(STILL_TIMEOUT_MS) { result.await() }
            val (bytes, _) = withTimeout(STILL_TIMEOUT_MS) { image.await() }
            return StillCapture(bytes, r, reader.width, reader.height)
        } finally {
            jpegSink = null
            if (c.focusMode == FocusMode.CONTINUOUS) runCatching { unlockAf(s, dev) }
        }
    }

    // ------------------------------------------------------------------------------------------

    @Volatile private var paused = false

    /**
     * Pauses / resumes the repeating preview without touching the session. While a camera streams,
     * Android's CameraServiceProxy caps the app's display refresh rate to [30, 60] Hz; pausing behind a
     * full sheet lets the UI run at the panel's full rate, and resuming is immediate.
     */
    fun setPaused(on: Boolean) {
        paused = on
        cameraHandler.post {
            runCatching { if (on) session?.stopRepeating() else repeat() }.onFailure { Log.w(TAG, "pause", it) }
        }
    }

    private fun repeat() {
        if (paused) return
        val s = session ?: return
        val dev = device ?: return
        val surface = previewSurface ?: return
        val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            analysisReader?.let { addTarget(it.surface) }
            applyCommon(this, controls, forCapture = false)
        }.build()
        s.setSingleRepeatingRequest(request, executor, previewCallback)
    }

    private fun applyCommon(b: CaptureRequest.Builder, c: Controls, forCapture: Boolean) {
        val d = descriptor ?: return
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        // Every request that feeds the viewfinder carries the same ISP tuning, so no frame changes look.
        IspTuning.applyStream(b, d.characteristics, c.isp)
        if (!forCapture) {
            val modes = d.characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet().orEmpty()
            val stab = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (c.stabilizePreview && stab in modes) stab else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
        if (d.zoomRange.upper > d.zoomRange.lower) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoomRatio.coerceIn(d.zoomRange.lower, d.zoomRange.upper))
        val region = c.focusPoint?.let { meteringRegion(d, it) }
        when (c.focusMode) {
            FocusMode.CONTINUOUS -> b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            FocusMode.LOCKED_AREA -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                region?.let { b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(it)) }
            }
            FocusMode.MANUAL -> {
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                b.set(CaptureRequest.LENS_FOCUS_DISTANCE, c.focusDiopters.coerceIn(0f, d.minFocusDiopters))
            }
        }
        region?.let { b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(it)) }
        val manualExposure = c.manualExposureNs
        val manualIso = c.manualIso
        if (manualExposure != null && manualIso != null && d.supportsManualSensor) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposure)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, if (forCapture) manualExposure.coerceAtLeast(MIN_FRAME_NS)
            else manualExposure.coerceAtLeast(PREVIEW_FRAME_NS))
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.exposureCompensation.coerceIn(d.compensationRange.lower, d.compensationRange.upper))
            b.set(CaptureRequest.CONTROL_AE_LOCK, c.aeLock)
            if (Build.VERSION.SDK_INT >= 36) {
                // Priority modes (HAL AE solves the other parameter), spec section 8 Pro mode.
                when {
                    manualIso != null && d.supportsIsoPriority -> {
                        b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
                        b.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                    }
                    manualExposure != null && d.supportsShutterPriority -> {
                        b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
                        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposure)
                    }
                    else -> b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF)
                }
            }
        }
        b.set(CaptureRequest.FLASH_MODE, if (c.flash == FlashMode.TORCH && d.hasFlash) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        val kelvin = c.whiteBalanceKelvin
        if (kelvin != null && d.supportsCctWhiteBalance && Build.VERSION.SDK_INT >= 36) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_CCT)
            val range = d.cctRange
            b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TEMPERATURE, if (range != null) kelvin.coerceIn(range.lower, range.upper) else kelvin)
            b.set(CaptureRequest.COLOR_CORRECTION_COLOR_TINT, c.whiteBalanceTint.coerceIn(-50, 50))
        } else {
            b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        }
        val ois = d.characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (ois?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    /** Maps an upright display point to a metering rectangle on the (zoomed) active array. */
    private fun meteringRegion(d: CameraDescriptor, p: ViewPoint): MeteringRectangle {
        var vx = p.x.coerceIn(0f, 1f)
        val vy = p.y.coerceIn(0f, 1f)
        if (!d.facingBack) vx = 1f - vx  // front preview is mirrored
        val (sx, sy) = when (d.sensorOrientation) {
            90 -> vy to 1f - vx
            180 -> 1f - vx to 1f - vy
            270 -> 1f - vy to vx
            else -> vx to vy
        }
        val a = d.activeArray
        val half = (minOf(a.width(), a.height()) * 0.06f).roundToInt()
        val cx = (sx * a.width()).roundToInt()
        val cy = (sy * a.height()).roundToInt()
        val rect = Rect(
            (cx - half).coerceIn(0, a.width() - 1), (cy - half).coerceIn(0, a.height() - 1),
            (cx + half).coerceIn(1, a.width()), (cy + half).coerceIn(1, a.height()),
        )
        return MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun triggerAf(trigger: Int = CameraMetadata.CONTROL_AF_TRIGGER_START) {
        val s = session ?: return
        val dev = device ?: return
        val surface = previewSurface ?: return
        val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyCommon(this, controls, forCapture = false)
            set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        }.build()
        s.capture(request, null, cameraHandler)
    }

    private suspend fun lockAf(s: CameraCaptureSession, dev: CameraDevice) {
        val surface = previewSurface ?: return
        val locked = CompletableDeferred<Unit>()
        val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyCommon(this, controls, forCapture = false)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        }.build()
        afWaiter = locked
        s.capture(request, null, cameraHandler)
        withTimeoutOrNull(AF_LOCK_TIMEOUT_MS) { locked.await() }
        afWaiter = null
    }

    private fun unlockAf(s: CameraCaptureSession, dev: CameraDevice) {
        val surface = previewSurface ?: return
        val request = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            applyCommon(this, controls, forCapture = false)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        }.build()
        s.capture(request, null, cameraHandler)
    }

    @Volatile private var afWaiter: CompletableDeferred<Unit>? = null

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            latest = result
            afWaiter?.let { w ->
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) w.complete(Unit)
            }
            if (resultCounter.incrementAndGet() % 3 == 0) _frameInfo.value = result.toFrameInfo()
        }
    }

    private fun TotalCaptureResult.toFrameInfo() = FrameInfo(
        activePhysicalId = get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID),
        zoomRatio = get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1f,
        exposureNs = get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
        iso = get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
        frameDurationNs = get(CaptureResult.SENSOR_FRAME_DURATION) ?: 0L,
        focusDiopters = get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f,
        afState = get(CaptureResult.CONTROL_AF_STATE) ?: 0,
        aeState = get(CaptureResult.CONTROL_AE_STATE) ?: 0,
        physicalCrop = if (Build.VERSION.SDK_INT >= 35) get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION) else null,
        aperture = get(CaptureResult.LENS_APERTURE) ?: 0f,
        focalLengthMm = get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f,
    )

    private fun onAnalysisImage(reader: ImageReader) {
        try {
            val image = reader.acquireLatestImage() ?: return
            image.use {
                analysisListener?.invoke(it)
                analyzer.analyze(it)?.let { stats -> _analysis.value = stats }
            }
        } catch (e: IllegalStateException) {
            // Reader closed during a session switch; the frame is stale anyway.
        }
    }

    private fun onJpegImage(reader: ImageReader) {
        try {
            reader.acquireNextImage()?.use { image ->
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                jpegSink?.complete(bytes to image.timestamp)
            }
        } catch (e: IllegalStateException) {
            // Reader closed during a session switch.
        }
    }

    private fun onRawImage(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage() ?: return
        } catch (e: IllegalStateException) {
            return
        }
        val sink = burstSink
        if (sink == null) image.close() else sink.offerImage(image)
    }

    /** Pairs RAW images with their capture results by sensor timestamp. */
    private inner class BurstSink(val expected: Int, val spec: SessionSpec, val onFrame: (RawFrame) -> Unit) {
        val done = CompletableDeferred<Unit>()
        val delivered = AtomicInteger()
        private val images = ConcurrentHashMap<Long, Image>()
        private val results = ConcurrentHashMap<Long, TotalCaptureResult>()
        private val failures = AtomicInteger()

        val callback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                latest = result
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                imageHandler.post {
                    results[ts] = result
                    tryPair(ts)
                }
            }

            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                imageHandler.post {
                    failures.incrementAndGet()
                    checkDone()
                }
            }
        }

        fun offerImage(image: Image) {
            images[image.timestamp] = image
            tryPair(image.timestamp)
        }

        private fun tryPair(ts: Long) {
            val image = images[ts] ?: return
            val result = results[ts] ?: return
            images.remove(ts)
            results.remove(ts)
            try {
                val physicalResult = spec.rawPhysicalId?.let { result.physicalCameraTotalResults[it] }
                val source = spec.rawPhysicalId
                    ?: result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    ?: spec.cameraId
                val plane = image.planes[0]
                onFrame(
                    RawFrame(
                        index = delivered.get(), buffer = plane.buffer, rowStride = plane.rowStride,
                        width = image.width, height = image.height, timestampNs = ts,
                        result = physicalResult ?: result, sourceCameraId = source, image = image,
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "frame consumer failed", e)
            } finally {
                image.close()
                delivered.incrementAndGet()
                checkDone()
            }
        }

        private fun checkDone() {
            if (delivered.get() + failures.get() >= expected) done.complete(Unit)
        }

        fun drain() {
            imageHandler.post {
                images.values.forEach { it.close() }
                images.clear()
                results.clear()
            }
        }
    }

    private fun closeSessionLocked() {
        runCatching { session?.close() }
        session = null
        // Readers are closed on the image thread so no listener is mid-read when a buffer goes away.
        val analysis = analysisReader
        val raw = rawReader
        val jpeg = jpegReader
        analysisReader = null
        rawReader = null
        jpegReader = null
        imageHandler.post {
            analysis?.close()
            raw?.close()
            jpeg?.close()
        }
        latest = null
    }

    /** Forgets the device without closing it; it stays open in the pool. */
    private fun parkDeviceLocked() {
        device = null
        deviceId = null
    }

    init {
        devices.addLossListener { id, message -> cameraHandler.post { device?.takeIf { it.id == id }?.let { onDeviceLost(it, message) } } }
    }

    private fun onDeviceLost(camera: CameraDevice, message: String) {
        if (device === camera) {
            device = null
            deviceId = null
            session = null
            _state.value = CameraState.Failed(message)
        }
    }

    private suspend fun CameraDevice.configure(outputs: List<OutputConfiguration>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("Session configuration failed"))
                    }
                }))
        }

    private fun describeError(e: Exception): String = when (e) {
        is SecurityException -> "Camera permission is not granted"
        is CameraAccessException -> when (e.reason) {
            CameraAccessException.CAMERA_DISABLED -> "Camera is disabled by device policy"
            CameraAccessException.CAMERA_IN_USE, CameraAccessException.MAX_CAMERAS_IN_USE -> "Camera is in use by another app"
            else -> "Camera unavailable (${e.reason})"
        }
        else -> e.message ?: e.toString()
    }

    private companion object {
        const val TAG = "LumaCamera"
        const val OPEN_TIMEOUT_MS = 5_000L
        const val AF_LOCK_TIMEOUT_MS = 900L
        const val RAW_READER_DEPTH = 6
        const val MIN_FRAME_NS = 33_333_333L
        const val PREVIEW_FRAME_NS = 33_333_333L
        const val BURST_TAG = "luma-burst"
        const val STILL_TIMEOUT_MS = 4_000L
    }
}
