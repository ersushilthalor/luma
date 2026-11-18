package com.datarohit.luma.diagnostics

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Debug-only sanity check of the public maximum-resolution API (Camera2, API 31+), for every rear logical and
 * physical camera: the ULTRA_HIGH_RESOLUTION_SENSOR capability, every RAW_SENSOR / JPEG / HEIC / YUV_420_888 size
 * in the default and the maximum-resolution stream maps, and, where a larger maximum-resolution size exists, one
 * dedicated still request containing only that surface with SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION (no preview).
 * Frames stay in memory; only their sizes are reported.
 */
class UhrProbe(private val manager: CameraManager, private val log: (String) -> Unit) {

    private val thread = HandlerThread("luma-uhr").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val formats = listOf(ImageFormat.RAW_SENSOR to "RAW_SENSOR", ImageFormat.JPEG to "JPEG", ImageFormat.HEIC to "HEIC",
        ImageFormat.YUV_420_888 to "YUV_420_888")

    /** Camera to open + optional physical id behind it. */
    private data class Target(val open: String, val physical: String?) {
        val name get() = if (physical == null) open else "$open/$physical"
    }

    suspend fun run() {
        try {
            val targets = mutableListOf<Target>()
            for (id in manager.cameraIdList) {
                val c = manager.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
                targets += Target(id, null)
                c.physicalCameraIds.sorted().forEach { targets += Target(id, it) }
            }
            for (t in targets) report(t)
            for (t in targets) {
                val c = manager.getCameraCharacteristics(t.physical ?: t.open)
                val uhr = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) == true
                val max = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                val def = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (!uhr || max == null) continue
                for ((fmt, name) in formats.take(2)) {
                    val big = max.getOutputSizes(fmt)?.maxByOrNull { it.width.toLong() * it.height } ?: continue
                    val defBig = def?.getOutputSizes(fmt)?.maxByOrNull { it.width.toLong() * it.height }
                    if (defBig != null && big.width.toLong() * big.height <= defBig.width.toLong() * defBig.height) continue
                    runCatching { dedicatedStill(t, fmt, name, big) }.onFailure { log("  ${t.name} $name ${big}: capture FAILED $it") }
                }
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun report(t: Target) {
        val c = manager.getCameraCharacteristics(t.physical ?: t.open)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val uhr = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps
        val remosaic = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING in caps
        val pixelModeKey = c.availableCaptureRequestKeys.any { it.name == CaptureRequest.SENSOR_PIXEL_MODE.name }
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val activeMax = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE_MAXIMUM_RESOLUTION)
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val pixelsMax = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
        val binning = c.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)
        log("UHR ${t.name}: ULTRA_HIGH_RESOLUTION_SENSOR=$uhr REMOSAIC_REPROCESSING=$remosaic SENSOR_PIXEL_MODE key=$pixelModeKey " +
            "pixelArray=$pixels active=$active pixelArrayMax=$pixelsMax activeMax=$activeMax binning=$binning")
        fun sizes(map: StreamConfigurationMap?, label: String) {
            if (map == null) { log("  $label: (none)"); return }
            for ((fmt, name) in formats) {
                val s = map.getOutputSizes(fmt)?.sortedByDescending { it.width.toLong() * it.height }.orEmpty()
                log("  $label $name: " + if (s.isEmpty()) "-" else s.joinToString(" "))
            }
        }
        sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), "default")
        sizes(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION), "maxRes")
    }

    /** One request, one surface: the maximum-resolution size, SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, no preview. */
    @SuppressLint("MissingPermission")
    private suspend fun dedicatedStill(t: Target, fmt: Int, name: String, size: Size) {
        val dev = open(t.open)
        val reader = ImageReader.newInstance(size.width, size.height, fmt, 1)
        try {
            val got = CompletableDeferred<Size>()
            reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { got.complete(Size(it.width, it.height)) } }, handler)
            val out = OutputConfiguration(reader.surface).apply {
                t.physical?.let { setPhysicalCameraId(it) }
                addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            }
            val session = configure(dev, listOf(out), req.build())
            try {
                val result = CompletableDeferred<TotalCaptureResult>()
                session.capture(req.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) { result.complete(res) }
                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                        result.completeExceptionally(IllegalStateException("capture failed reason ${f.reason}"))
                    }
                }, handler)
                val res = withTimeout(15_000) { result.await() }
                val img = withTimeout(15_000) { got.await() }
                log("  ${t.name} $name requested $size (SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION, single surface): delivered $img, " +
                    "result pixelMode=${res.get(CaptureResult.SENSOR_PIXEL_MODE)} rawBinning=${res.get(CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED)}")
            } finally {
                session.close()
            }
        } finally {
            reader.close()
            dev.close()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) else camera.close() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); if (cont.isActive) cont.resumeWithException(IllegalStateException("disconnected")) }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (cont.isActive) cont.resumeWithException(IllegalStateException("error $error")) }
        })
    }

    private suspend fun configure(dev: CameraDevice, outs: List<OutputConfiguration>, params: CaptureRequest): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            try {
                val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { if (cont.isActive) cont.resume(session) else session.close() }
                    override fun onConfigureFailed(session: CameraCaptureSession) { if (cont.isActive) cont.resumeWithException(IllegalStateException("onConfigureFailed")) }
                })
                cfg.sessionParameters = params
                dev.createCaptureSession(cfg)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
}
