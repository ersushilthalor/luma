package com.datarohit.luma.core.camera.diagnostics

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.datarohit.luma.core.camera.inventory.formatValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase B (measurement subset) of the hardware validation plan. Opens only Luma's own camera
 * sessions. Every image is closed as soon as its size and timestamp are recorded; nothing is
 * stored, encoded to disk, or written to shared media.
 */
class CaptureBehaviorProbe(private val manager: CameraManager, private val log: (String) -> Unit) {

    private val executor: Executor = Executors.newSingleThreadExecutor()
    private val readerThread = HandlerThread("luma-diagnostics-readers").apply { start() }
    private val readerHandler = Handler(readerThread.looper)

    suspend fun run(cameraId: String): JSONObject {
        val chars = manager.getCameraCharacteristics(cameraId)
        val map = requireNotNull(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val analysis = requireNotNull(smallYuv(map)) { "no small YUV size" }
        val raw = map.largest(ImageFormat.RAW_SENSOR)
        val report = JSONObject().put("schema", 1).put("phase", "B").put("cameraId", cameraId)
            .put("generatedAt", Instant.now().toString())

        val device = withTimeout(OPEN_TIMEOUT_MS) { openCamera(cameraId) }
        try {
            report.put("repeatingRaw", step("repeating RAW + YUV") { repeatingRaw(device, analysis, raw) })
            report.put("zoomSweep", step("zoom sweep") { zoomSweep(device, analysis, chars) })
            report.put("manualControl", step("manual exposure") { manualControl(device, analysis) })
            report.put("burstControl", step("per-frame burst") { burstControl(device, analysis) })
            report.put("physicalRawStreams", step("physical RAW streams") { physicalRaw(device, analysis, chars) })
            report.put("jpegR", step("JPEG_R still") { jpegR(device, analysis, map.largest(ImageFormat.JPEG_R)) })
        } finally {
            device.close()
            readerThread.quitSafely()
        }
        return report
    }

    private suspend fun step(name: String, block: suspend () -> JSONObject): JSONObject {
        log("B: $name")
        return try {
            withTimeout(STEP_TIMEOUT_MS) { block() }
        } catch (e: Exception) {
            log("B: $name failed: $e")
            JSONObject().put("error", e.toString())
        }
    }

    private suspend fun repeatingRaw(device: CameraDevice, analysis: Size, raw: Size?): JSONObject {
        raw ?: return JSONObject().put("skipped", "no RAW_SENSOR output")
        val yuv = CountingReader(analysis, ImageFormat.YUV_420_888)
        val rawReader = CountingReader(raw, ImageFormat.RAW_SENSOR)
        val session = device.configure(listOf(OutputConfiguration(yuv.surface), OutputConfiguration(rawReader.surface)))
        val results = ResultCollector()
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(yuv.surface)
                addTarget(rawReader.surface)
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
            }.build()
            session.setSingleRepeatingRequest(request, executor, results)
            delay(WARMUP_MS)
            yuv.reset(); rawReader.reset(); results.reset()
            delay(MEASURE_MS)
            session.stopRepeating()
            return JSONObject()
                .put("rawSize", raw.toString())
                .put("yuv", yuv.stats())
                .put("raw", rawReader.stats())
                .put("captureFailures", results.failures)
                .put("results", results.count)
                .put("sampleResult", results.latest?.let(::resultJson))
        } finally {
            session.close(); yuv.close(); rawReader.close()
        }
    }

    private suspend fun zoomSweep(device: CameraDevice, analysis: Size, chars: CameraCharacteristics): JSONObject {
        val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: return JSONObject().put("skipped", "no zoom ratio")
        val yuv = CountingReader(analysis, ImageFormat.YUV_420_888)
        val session = device.configure(listOf(OutputConfiguration(yuv.surface)))
        val results = ResultCollector()
        val steps = JSONArray()
        try {
            for (zoom in ZOOM_STEPS.filter { it in range.lower..range.upper }) {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(yuv.surface)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }.build()
                session.setSingleRepeatingRequest(request, executor, results)
                delay(ZOOM_SETTLE_MS)
                steps.put(JSONObject().put("requestedZoom", zoom).put("result", results.latest?.let(::resultJson)))
            }
            session.stopRepeating()
        } finally {
            session.close(); yuv.close()
        }
        return JSONObject().put("steps", steps).put("note", "Lens choice depends on the scene in front of the camera at test time")
    }

    private suspend fun manualControl(device: CameraDevice, analysis: Size): JSONObject {
        val yuv = CountingReader(analysis, ImageFormat.YUV_420_888)
        val session = device.configure(listOf(OutputConfiguration(yuv.surface)))
        val results = ResultCollector()
        val cases = JSONArray()
        try {
            for ((zoom, iso) in listOf(1f to 400, 3f to 371, 3f to 1600, 5f to 203, 5f to 1600)) {
                val exposure = 10_000_000L
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(yuv.surface)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                }.build()
                session.setSingleRepeatingRequest(request, executor, results)
                delay(MANUAL_SETTLE_MS)
                cases.put(JSONObject()
                    .put("zoom", zoom).put("requestedExposureNs", exposure).put("requestedIso", iso)
                    .put("result", results.latest?.let(::resultJson)))
            }
            session.stopRepeating()
        } finally {
            session.close(); yuv.close()
        }
        return JSONObject().put("cases", cases)
    }

    private suspend fun burstControl(device: CameraDevice, analysis: Size): JSONObject {
        val yuv = CountingReader(analysis, ImageFormat.YUV_420_888)
        val session = device.configure(listOf(OutputConfiguration(yuv.surface)))
        val exposures = listOf(4_000_000L, 16_000_000L, 4_000_000L, 16_000_000L, 8_000_000L, 8_000_000L)
        try {
            val requests = exposures.map { exposure ->
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(yuv.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
                    set(CaptureRequest.SENSOR_SENSITIVITY, 200)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, 33_333_333L)
                }.build()
            }
            val collector = ResultCollector(expected = requests.size)
            session.captureBurstRequests(requests, executor, collector)
            collector.awaitAll()
            return JSONObject()
                .put("requestedExposureNs", JSONArray(exposures))
                .put("resultExposureNs", JSONArray(collector.all.map { it.get(CaptureResult.SENSOR_EXPOSURE_TIME) }))
                .put("resultIso", JSONArray(collector.all.map { it.get(CaptureResult.SENSOR_SENSITIVITY) }))
                .put("frameNumbers", JSONArray(collector.all.map { it.frameNumber }))
                .put("captureFailures", collector.failures)
        } finally {
            session.close(); yuv.close()
        }
    }

    private suspend fun physicalRaw(device: CameraDevice, analysis: Size, chars: CameraCharacteristics): JSONObject {
        val out = JSONObject()
        for (physical in chars.physicalCameraIds.sorted()) {
            val physicalMap = manager.getCameraCharacteristics(physical).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val raw = physicalMap?.largest(ImageFormat.RAW_SENSOR)
            if (raw == null) { out.put(physical, JSONObject().put("skipped", "no RAW size")); continue }
            out.put(physical, try {
                withTimeout(PHYSICAL_TIMEOUT_MS) { physicalRawFor(device, analysis, physical, raw) }
            } catch (e: Exception) {
                JSONObject().put("error", e.toString())
            })
        }
        return out
    }

    private suspend fun physicalRawFor(device: CameraDevice, analysis: Size, physical: String, raw: Size): JSONObject {
        val yuv = CountingReader(analysis, ImageFormat.YUV_420_888)
        val rawReader = CountingReader(raw, ImageFormat.RAW_SENSOR)
        val rawOutput = OutputConfiguration(rawReader.surface).apply { setPhysicalCameraId(physical) }
        val session = device.configure(listOf(OutputConfiguration(yuv.surface), rawOutput))
        val results = ResultCollector()
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(yuv.surface)
                addTarget(rawReader.surface)
            }.build()
            session.setSingleRepeatingRequest(request, executor, results)
            delay(PHYSICAL_MEASURE_MS)
            session.stopRepeating()
            return JSONObject()
                .put("rawSize", raw.toString())
                .put("raw", rawReader.stats())
                .put("yuv", yuv.stats())
                .put("captureFailures", results.failures)
                .put("activePhysicalId", results.latest?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID))
                .put("physicalResultIds", JSONArray(results.latest?.physicalCameraTotalResults?.keys?.sorted().orEmpty()))
        } finally {
            session.close(); yuv.close(); rawReader.close()
        }
    }

    private suspend fun jpegR(device: CameraDevice, analysis: Size, size: Size?): JSONObject {
        size ?: return JSONObject().put("skipped", "JPEG_R not in the app-visible stream map")
        val yuv = CountingReader(analysis, ImageFormat.YUV_420_888)
        val still = CountingReader(size, ImageFormat.JPEG_R)
        val session = device.configure(listOf(OutputConfiguration(yuv.surface), OutputConfiguration(still.surface)))
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(yuv.surface)
                addTarget(still.surface)
            }.build()
            val collector = ResultCollector(expected = 1)
            session.captureSingleRequest(request, executor, collector)
            collector.awaitAll()
            delay(STILL_DRAIN_MS)
            return JSONObject().put("size", size.toString()).put("images", still.stats())
                .put("captureFailures", collector.failures)
        } finally {
            session.close(); yuv.close(); still.close()
        }
    }

    private suspend fun openCamera(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (cont.isActive) cont.resume(camera) else camera.close()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                if (cont.isActive) cont.resumeWithException(IllegalStateException("camera error $error"))
            }
        }
        try {
            manager.openCamera(id, executor, callback)
        } catch (e: SecurityException) {
            cont.resumeWithException(e)
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
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("session configuration failed"))
                    }
                }))
        }

    /** Counts frames and closes every image immediately. */
    private inner class CountingReader(size: Size, format: Int) {
        private val reader = ImageReader.newInstance(size.width, size.height, format, READER_DEPTH)
        private val timestamps = mutableListOf<Long>()
        private var lastSize = ""
        val surface: Surface get() = reader.surface

        init {
            reader.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.use { image ->
                    synchronized(timestamps) {
                        timestamps += image.timestamp
                        lastSize = "${image.width}x${image.height}"
                    }
                }
            }, readerHandler)
        }

        fun reset() = synchronized(timestamps) { timestamps.clear() }

        fun stats(): JSONObject = synchronized(timestamps) {
            val fps = if (timestamps.size > 1) (timestamps.size - 1) * 1e9 / (timestamps.last() - timestamps.first()) else 0.0
            val gaps = timestamps.zipWithNext { a, b -> b - a }
            JSONObject().put("frames", timestamps.size).put("fps", "%.2f".format(java.util.Locale.ROOT, fps))
                .put("maxGapMs", (gaps.maxOrNull() ?: 0L) / 1e6).put("imageSize", lastSize)
        }

        fun close() = reader.close()
    }

    private class ResultCollector(private val expected: Int = 0) : CameraCaptureSession.CaptureCallback() {
        val all = mutableListOf<TotalCaptureResult>()
        var latest: TotalCaptureResult? = null
            private set
        var count = 0
            private set
        var failures = 0
            private set
        private val done = kotlinx.coroutines.CompletableDeferred<Unit>()

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            synchronized(this) {
                latest = result; count++
                if (expected > 0) all += result
            }
            checkDone()
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            synchronized(this) { failures++ }
            checkDone()
        }

        private fun checkDone() {
            if (expected > 0 && synchronized(this) { all.size + failures } >= expected) done.complete(Unit)
        }

        fun reset() = synchronized(this) { count = 0; failures = 0 }

        suspend fun awaitAll() = done.await()
    }

    private fun resultJson(r: CaptureResult): JSONObject {
        val json = JSONObject()
        fun <T> put(name: String, key: CaptureResult.Key<T>) {
            r.get(key)?.let { json.put(name, formatValue(it)) }
        }
        put("activePhysicalId", CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        if (Build.VERSION.SDK_INT >= 35) put("activePhysicalCrop", CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION)
        put("zoomRatio", CaptureResult.CONTROL_ZOOM_RATIO)
        put("exposureNs", CaptureResult.SENSOR_EXPOSURE_TIME)
        put("iso", CaptureResult.SENSOR_SENSITIVITY)
        put("frameDurationNs", CaptureResult.SENSOR_FRAME_DURATION)
        put("aeState", CaptureResult.CONTROL_AE_STATE)
        put("afState", CaptureResult.CONTROL_AF_STATE)
        put("focusDistanceDpt", CaptureResult.LENS_FOCUS_DISTANCE)
        put("oisMode", CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
        put("noiseProfile", CaptureResult.SENSOR_NOISE_PROFILE)
        put("dynamicBlackLevel", CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        put("dynamicWhiteLevel", CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
        put("rawBinningFactorUsed", CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED)
        put("neutralColorPoint", CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        put("colorCorrectionGains", CaptureResult.COLOR_CORRECTION_GAINS)
        put("rollingShutterSkewNs", CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
        r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)?.let { map ->
            var max = 0f
            for (row in 0 until map.rowCount) for (col in 0 until map.columnCount) for (ch in 0 until 4) {
                max = maxOf(max, map.getGainFactor(ch, col, row))
            }
            json.put("lensShadingMap", "${map.columnCount}x${map.rowCount}, max gain %.3f".format(java.util.Locale.ROOT, max))
        }
        return json
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 5_000L
        const val STEP_TIMEOUT_MS = 30_000L
        const val PHYSICAL_TIMEOUT_MS = 8_000L
        const val WARMUP_MS = 1_000L
        const val MEASURE_MS = 5_000L
        const val ZOOM_SETTLE_MS = 1_500L
        const val MANUAL_SETTLE_MS = 1_000L
        const val PHYSICAL_MEASURE_MS = 2_000L
        const val STILL_DRAIN_MS = 1_000L
        const val READER_DEPTH = 4
        val ZOOM_STEPS = listOf(0.6f, 1f, 2f, 2.85f, 3f, 4.9f, 5f, 10f)
    }
}
