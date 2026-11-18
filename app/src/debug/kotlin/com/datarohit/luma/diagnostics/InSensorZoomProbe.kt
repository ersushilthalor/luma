package com.datarohit.luma.diagnostics

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt

/**
 * Debug research: does Qualcomm's in-sensor zoom deliver full-resolution (non-binned) RAW crops, and can the crop
 * be placed off-centre? If so, tiles of the 50MP / 200MP readout can be stitched into a real high-resolution image.
 * Per RAW frame: binning factor reported, green-channel detail, and the normalised correlation of the tile with
 * each quadrant of a 1× RAW frame (tells which part of the scene the tile shows).
 */
class InSensorZoomProbe(private val manager: CameraManager, private val log: (String) -> Unit) {

    private val thread = HandlerThread("luma-isz").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private class Raw(val w: Int, val h: Int, val px: ShortArray, val binning: Any?, val lens: String?, val crop: Rect?)

    @SuppressLint("MissingPermission")
    suspend fun run(id: String) {
        var device: CameraDevice? = null
        val preview = ImageReader.newInstance(1920, 1440, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        preview.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
        val c = manager.getCameraCharacteristics(id)
        val rawSize = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width * it.height }
        val raw = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        try {
            for (insensor in listOf(false, true)) {
                val dev = withTimeout(5_000) { open(id) }
                device = dev
                val params = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                if (insensor) params.set(CaptureRequest.Key(KEY_INSENSOR, Int::class.javaObjectType), 1)
                val s = withTimeout(6_000) { configure(dev, listOf(OutputConfiguration(preview.surface), OutputConfiguration(raw.surface)), params.build()) }
                suspend fun shoot(label: String, zoom: Float, crop: Rect?): Raw? {
                    val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(preview.surface)
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                        crop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                        if (insensor) set(CaptureRequest.Key(KEY_INSENSOR, Int::class.javaObjectType), 1)
                    }
                    s.setSingleRepeatingRequest(b.build(), executor, object : CameraCaptureSession.CaptureCallback() {})
                    delay(1_500)
                    val got = CompletableDeferred<Raw>()
                    var meta: TotalCaptureResult? = null
                    raw.setOnImageAvailableListener({ r ->
                        r.acquireNextImage()?.use { img ->
                            val p = img.planes[0]
                            val sb = p.buffer.asShortBuffer()
                            val stride = p.rowStride / 2
                            val out = ShortArray(img.width * img.height)
                            for (y in 0 until img.height) { sb.position(y * stride); sb.get(out, y * img.width, img.width) }
                            val m = meta
                            got.complete(Raw(img.width, img.height, out, m?.get(CaptureResult.SENSOR_RAW_BINNING_FACTOR_USED),
                                m?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID), m?.get(CaptureResult.SCALER_CROP_REGION)))
                        }
                    }, handler)
                    val cb = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(raw.surface)
                        set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                        crop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                        if (insensor) set(CaptureRequest.Key(KEY_INSENSOR, Int::class.javaObjectType), 1)
                    }
                    s.capture(cb.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { meta = result }
                    }, handler)
                    return runCatching { withTimeout(8_000) { got.await() } }.onFailure { log("ISZ $label ERROR ${it.message}") }.getOrNull()
                }
                val tag = if (insensor) "insensor" else "plain"
                val full = shoot("$tag 1x", 1f, null) ?: continue
                log("ISZ [$tag 1x] binning=${full.binning} lens=${full.lens} detail=%.1f".format(detail(full)))
                val half = Rect(0, 0, active.width() / 2, active.height() / 2)
                for ((name, crop) in listOf(
                    "2x centre" to null,
                    "2x crop TL" to half,
                    "2x crop BR" to Rect(active.width() / 2, active.height() / 2, active.width(), active.height()),
                )) {
                    val z = shoot("$tag $name", if (crop == null) 2f else 1f, crop) ?: continue
                    val corr = listOf("TL", "TR", "BL", "BR").zip(quadrantCorrelations(full, z)).joinToString(" ") { "${it.first}=%.2f".format(it.second) }
                    log("ISZ [$tag $name] binning=${z.binning} lens=${z.lens} crop=${z.crop} detail=%.1f matches: $corr".format(detail(z)))
                }
                s.close()
                dev.close()
                device = null
                delay(500)
            }
        } catch (e: Exception) {
            log("ISZ ERROR ${e.message ?: e}")
        } finally {
            runCatching { device?.close() }
            delay(300)
            preview.close()
            raw.close()
            thread.quitSafely()
        }
    }

    /** Mean |Laplacian| on the green sites (2-pixel steps) of the central half of a RAW frame. */
    private fun detail(r: Raw): Double {
        var sum = 0L
        var n = 0
        for (y in r.h / 4 until 3 * r.h / 4 step 2) for (x in r.w / 4 until 3 * r.w / 4 step 2) {
            val i = y * r.w + x
            fun v(j: Int) = r.px[j].toInt() and 0xFFFF
            sum += kotlin.math.abs(4 * v(i) - v(i - 2) - v(i + 2) - v(i - 2 * r.w) - v(i + 2 * r.w))
            n++
        }
        return sum.toDouble() / n
    }

    /** NCC of the tile (downsampled 2x) against each quadrant of the 1x frame, on 2x2-block luma. */
    private fun quadrantCorrelations(full: Raw, tile: Raw): List<Double> {
        val gw = 128
        val gh = 96
        fun grid(r: Raw, x0: Int, y0: Int, w: Int, h: Int): DoubleArray = DoubleArray(gw * gh) { k ->
            val gx = k % gw
            val gy = k / gw
            val x = (x0 + (gx + 0.5) * w / gw).toInt() and 1.inv()
            val y = (y0 + (gy + 0.5) * h / gh).toInt() and 1.inv()
            val i = y * r.w + x
            ((r.px[i].toInt() and 0xFFFF) + (r.px[i + 1].toInt() and 0xFFFF) + (r.px[i + r.w].toInt() and 0xFFFF) + (r.px[i + r.w + 1].toInt() and 0xFFFF)).toDouble()
        }
        val t = grid(tile, 0, 0, tile.w - 2, tile.h - 2)
        val qw = full.w / 2
        val qh = full.h / 2
        return listOf(0 to 0, qw to 0, 0 to qh, qw to qh).map { (x, y) -> ncc(t, grid(full, x, y, qw - 2, qh - 2)) }
    }

    private fun ncc(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in a.indices) {
            num += (a[i] - ma) * (b[i] - mb)
            da += (a[i] - ma) * (a[i] - ma)
            db += (b[i] - mb) * (b[i] - mb)
        }
        return num / sqrt(da * db + 1e-9)
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

    private companion object {
        const val KEY_INSENSOR = "org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom"
    }
}
