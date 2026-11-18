package com.datarohit.luma.diagnostics

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
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
import android.util.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Debug-only research probe: can an unmodified phone give an app the sensor's full-resolution (QCFA remosaic)
 * readout through Qualcomm vendor session parameters? Reads candidate vendor characteristics by name, then
 * captures JPEGs with different session parameters and reports the real size and centre-crop detail.
 */
class QcfaProbe(private val manager: CameraManager, private val log: (String) -> Unit) {

    private val thread = HandlerThread("luma-qcfa").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private val charKeys = listOf(
        "org.codeaurora.qcamera3.quadra_cfa.is_qcfa_sensor" to Byte::class.javaObjectType,
        "org.codeaurora.qcamera3.quadra_cfa.qcfa_dimension" to IntArray::class.java,
        "org.codeaurora.qcamera3.sensor_meta_data.sensor_mode_info" to IntArray::class.java,
        "org.quic.camera2.sensormode.info.SensorModeTable" to IntArray::class.java,
        "org.codeaurora.qcamera3.platformCapabilities.IFEMaxLineWidth" to Int::class.javaObjectType,
        "org.quic.camera.HWResourceInfo.maxIFELineWidth" to Int::class.javaObjectType,
        "org.quic.camera2.customhfrfps.info.CustomHFRFpsTable" to IntArray::class.java,
    )

    private val resultKeys = listOf(
        "org.codeaurora.qcamera3.sensor_meta_data.current_mode" to Int::class.javaObjectType,
        "org.quic.camera2.sensormode.info.SensorModeIndex" to Int::class.javaObjectType,
        "org.codeaurora.qcamera3.sensor_meta_data.sensor_mode_index" to Int::class.javaObjectType,
    )

    suspend fun run(id: String) {
        try {
            val c = manager.getCameraCharacteristics(id)
            for ((name, type) in charKeys) {
                val v = runCatching { c.get(CameraCharacteristics.Key(name, type)) }
                log("QCFA cam=$id char $name = ${v.getOrNull()?.let(::show) ?: v.exceptionOrNull()?.javaClass?.simpleName ?: "null"}")
            }
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
            val tl = Rect(0, 0, active.width() / 2, active.height() / 2)
            val br = Rect(active.width() / 2, active.height() / 2, active.width(), active.height())
            val isz = mapOf("org.codeaurora.qcamera3.sessionParameters.EnableInsensorZoom" to 1)
            val cases = listOf(
                Case("1x full", emptyMap(), emptyMap(), Size(4080, 3060)),
                Case("tile TL plain", emptyMap(), emptyMap(), Size(4080, 3060), crop = tl),
                Case("tile TL insensor", isz, emptyMap(), Size(4080, 3060), crop = tl),
                Case("tile BR insensor", isz, emptyMap(), Size(4080, 3060), crop = br),
                Case("centre 2x insensor", isz, emptyMap(), Size(4080, 3060), zoom = 2f),
            )
            for (case in cases) capture(id, case)
        } finally {
            thread.quitSafely()
        }
    }

    data class Case(val label: String, val session: Map<String, Int>, val request: Map<String, Int>, val jpeg: Size, val zoom: Float = 1f, val crop: Rect? = null)

    @SuppressLint("MissingPermission")
    private suspend fun capture(id: String, case: Case) {
        var device: CameraDevice? = null
        val preview = ImageReader.newInstance(1920, 1440, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        preview.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
        val jpeg = ImageReader.newInstance(case.jpeg.width, case.jpeg.height, ImageFormat.JPEG, 1)
        try {
            val got = CompletableDeferred<ByteArray>()
            jpeg.setOnImageAvailableListener({ r ->
                r.acquireNextImage()?.use { img ->
                    val b = img.planes[0].buffer
                    got.complete(ByteArray(b.remaining()).also { b.get(it) })
                }
            }, handler)
            val dev = withTimeout(5_000) { open(id) }
            device = dev
            fun CaptureRequest.Builder.vendor(params: Map<String, Int>): List<String> = params.mapNotNull { (k, v) ->
                runCatching { set(CaptureRequest.Key(k, Int::class.javaObjectType), v) }.exceptionOrNull()?.let { "$k:${it.javaClass.simpleName}" }
            }
            val sessionParams = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val errs = sessionParams.vendor(case.session)
            val s = withTimeout(6_000) {
                configure(dev, listOf(OutputConfiguration(preview.surface), OutputConfiguration(jpeg.surface)), sessionParams.build())
            }
            val rb = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.surface)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, case.zoom)
                case.crop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                vendor(case.session)
                vendor(case.request)
            }
            s.setSingleRepeatingRequest(rb.build(), executor, object : CameraCaptureSession.CaptureCallback() {})
            delay(1_800)
            var mode = ""
            var cropResult: Rect? = null
            val cb = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpeg.surface)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, case.zoom)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                case.crop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                vendor(case.session)
                vendor(case.request)
            }
            s.capture(cb.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                    cropResult = r.get(CaptureResult.SCALER_CROP_REGION)
                    mode = resultKeys.joinToString(" ") { (k, t) -> "${k.substringAfterLast('.')}=${runCatching { r.get(CaptureResult.Key(k, t)) }.getOrNull()}" } +
                        " lens=${r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)}"
                }
            }, handler)
            val bytes = withTimeout(25_000) { got.await() }
            s.close()
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            val detail = detail(bytes, o.outWidth, o.outHeight, case.zoom * (if (case.crop != null) 2f else 1f))
            val g = lumaGrid(bytes)
            val where = baseline?.let { base -> listOf("TL", "TR", "BL", "BR").zip(quadrants(base).map { ncc(g, it) }).joinToString(" ") { "${it.first}=%.2f".format(it.second) } } ?: ""
            if (baseline == null) baseline = bytes
            mode += " crop=$cropResult matches: $where"
            log("QCFA cam=$id [${case.label}] got=${o.outWidth}x${o.outHeight} bytes=${bytes.size} detail=%.2f $mode${if (errs.isNotEmpty()) " setErrors=$errs" else ""}".format(detail))
        } catch (e: Exception) {
            log("QCFA cam=$id [${case.label}] ERROR ${(e.message ?: e.toString()).take(160)}")
        } finally {
            runCatching { device?.close() }
            delay(400)
            preview.close()
            jpeg.close()
        }
    }

    /**
     * Fine-detail energy (mean |Laplacian| of luma) over the same scene area in every case: a centre region one
     * sixth of the frame side (zoom-compensated), resampled to 512 px, so outputs of different sizes compare.
     */
    private fun detail(bytes: ByteArray, w: Int, h: Int, zoom: Float): Double {
        val side = (minOf(w, h) / 6f * zoom).toInt().coerceAtMost(minOf(w, h))
        val dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        val r = Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
        val opts = BitmapFactory.Options().apply { inSampleSize = (side / 512).coerceAtLeast(1) }
        val bmp = dec.decodeRegion(r, opts)
        dec.recycle()
        val n = minOf(bmp.width, bmp.height)
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        bmp.recycle()
        fun y(i: Int): Int { val c = px[i]; return (((c shr 16) and 255) * 54 + ((c shr 8) and 255) * 183 + (c and 255) * 19) shr 8 }
        var sum = 0L
        var cnt = 0
        for (yy in 1 until n - 1) for (xx in 1 until n - 1) {
            val i = yy * n + xx
            sum += kotlin.math.abs(4 * y(i) - y(i - 1) - y(i + 1) - y(i - n) - y(i + n))
            cnt++
        }
        return sum.toDouble() / cnt
    }

    private var baseline: ByteArray? = null

    /** 128x96 luma grid of a whole JPEG (sub-sampled decode). */
    private fun lumaGrid(bytes: ByteArray): DoubleArray {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 8 })
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 128, 96, true)
        val px = IntArray(128 * 96).also { small.getPixels(it, 0, 128, 0, 0, 128, 96) }
        return DoubleArray(px.size) { i -> val c = px[i]; (((c shr 16) and 255) * 0.2126 + ((c shr 8) and 255) * 0.7152 + (c and 255) * 0.0722) }
    }

    /** The four quadrants of a full frame, each as a 128x96 luma grid. */
    private fun quadrants(bytes: ByteArray): List<DoubleArray> {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })
        val w = bmp.width / 2
        val h = bmp.height / 2
        return listOf(0 to 0, w to 0, 0 to h, w to h).map { (x, y) ->
            val q = android.graphics.Bitmap.createScaledBitmap(android.graphics.Bitmap.createBitmap(bmp, x, y, w, h), 128, 96, true)
            val px = IntArray(128 * 96).also { q.getPixels(it, 0, 128, 0, 0, 128, 96) }
            DoubleArray(px.size) { i -> val c = px[i]; (((c shr 16) and 255) * 0.2126 + ((c shr 8) and 255) * 0.7152 + (c and 255) * 0.0722) }
        }
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
        return num / kotlin.math.sqrt(da * db + 1e-9)
    }

    private fun show(v: Any): String = when (v) {
        is IntArray -> v.take(48).joinToString(",", "[", if (v.size > 48) ",…](${v.size})" else "]")
        else -> v.toString()
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
