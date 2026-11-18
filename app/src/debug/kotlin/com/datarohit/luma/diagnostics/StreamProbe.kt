package com.datarohit.luma.diagnostics

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RecommendedStreamConfigurationMap
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Debug-only measurements of what the camera actually delivers (not what it advertises):
 * 4K frame rates per camera and stream setup, and whether high-resolution sizes outside the public
 * stream maps can be configured. Frames are discarded immediately.
 */
class StreamProbe(private val manager: CameraManager, private val log: (String) -> Unit) {

    private val thread = HandlerThread("luma-probe").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    suspend fun runIsp(ids: List<String>) {
        try {
            for (id in ids) isp(id)
        } finally {
            thread.quitSafely()
        }
    }

    suspend fun run(ids: List<String>) {
        try {
            for (id in ids) {
                val c = manager.getCameraCharacteristics(id)
                describe(id, c)
                for (variant in Variant.entries) measure(id, c, Size(3840, 2160), 60, variant)
                measure(id, c, Size(3840, 2160), 30, Variant.PLAIN)
                hires(id, c)
            }
        } finally {
            thread.quitSafely()
        }
    }

    private fun describe(id: String, c: CameraCharacteristics) {
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().joinToString(" ") { "[${it.lower},${it.upper}]" }
        log("PROBE cam=$id aeRanges=$ranges")
        val rec = runCatching { c.getRecommendedStreamConfigurationMap(RecommendedStreamConfigurationMap.USECASE_RECORD) }.getOrNull()
        if (rec != null) {
            val sizes = rec.getOutputSizes(ImageFormat.PRIVATE).orEmpty().filter { it.width >= 1920 }.joinToString(" ") { s ->
                val d = runCatching { rec.getOutputMinFrameDuration(ImageFormat.PRIVATE, s) }.getOrDefault(0L)
                "${s.width}x${s.height}@${if (d > 0) 1_000_000_000L / d else 0}"
            }
            log("PROBE cam=$id recommendedRecord=$sizes")
        } else log("PROBE cam=$id recommendedRecord=none")
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val uhr = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR in caps
        val maxArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION)
        val maxMap = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        val maxRaw = maxMap?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }
        val maxJpeg = maxMap?.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
        val binning = c.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR)
        val vendor = c.keys.map { it.name }.filter { it.contains("highres", true) || it.contains("HighRes", true) }
        log("PROBE cam=$id uhr=$uhr maxArray=$maxArray maxRaw=$maxRaw maxJpeg=$maxJpeg binning=$binning vendorHighresKeys=${vendor.size} ${vendor.take(4)}")
    }

    enum class Variant { PLAIN, USE_CASE, STABILIZED, WITH_ANALYSIS }

    @SuppressLint("MissingPermission")
    private suspend fun measure(id: String, c: CameraCharacteristics, size: Size, fps: Int, v: Variant) {
        val readers = mutableListOf<ImageReader>()
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            val main = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 4,
                HardwareBuffer.USAGE_VIDEO_ENCODE or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
            readers += main
            main.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
            val outs = mutableListOf(OutputConfiguration(main.surface).apply {
                if (v == Variant.USE_CASE || v == Variant.STABILIZED) streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
            })
            if (v == Variant.WITH_ANALYSIS) {
                val a = ImageReader.newInstance(640, 360, ImageFormat.YUV_420_888, 3)
                a.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
                readers += a
                outs += OutputConfiguration(a.surface)
            }
            val dev = withTimeout(5_000) { open(id) }
            device = dev
            val s = withTimeout(5_000) { configure(dev, outs) }
            session = s
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            readers.forEach { b.addTarget(it.surface) }
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            if (v == Variant.STABILIZED) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION)
            val stamps = ArrayList<Long>()
            val durations = ArrayList<Long>()
            var active = ""
            s.setSingleRepeatingRequest(b.build(), executor, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                    r.get(CaptureResult.SENSOR_TIMESTAMP)?.let(stamps::add)
                    r.get(CaptureResult.SENSOR_FRAME_DURATION)?.let(durations::add)
                    active = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: ""
                }
            })
            delay(3_000)
            s.stopRepeating()
            val t = synchronized(stamps) { stamps.toList() }.drop(15)
            val gaps = t.zipWithNext { a, b2 -> b2 - a }
            val mean = if (gaps.isNotEmpty()) gaps.average() / 1e6 else 0.0
            val max = (gaps.maxOrNull() ?: 0L) / 1e6
            val fd = durations.drop(15).takeIf { it.isNotEmpty() }?.average()?.div(1e6) ?: 0.0
            log("PROBE cam=$id ${size.width}x${size.height}@$fps ${v.name} frames=${t.size} meanGap=%.2fms (%.1f fps) maxGap=%.1fms frameDuration=%.2fms active=$active"
                .format(mean, if (mean > 0) 1000 / mean else 0.0, max, fd))
        } catch (e: Exception) {
            log("PROBE cam=$id ${size.width}x${size.height}@$fps ${v.name} ERROR ${e.message ?: e}")
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            delay(300)
            readers.forEach { runCatching { it.close() } }
        }
    }

    /** Tries the private high-resolution RAW sizes seen in vendor metadata. Expected to be refused. */
    @SuppressLint("MissingPermission")
    private suspend fun hires(id: String, c: CameraCharacteristics) {
        for (size in listOf(Size(16320, 12240), Size(8160, 6120))) {
            for (format in listOf(ImageFormat.RAW_SENSOR, ImageFormat.JPEG)) {
                var device: CameraDevice? = null
                var reader: ImageReader? = null
                try {
                    reader = ImageReader.newInstance(size.width, size.height, format, 1)
                    val dev = withTimeout(5_000) { open(id) }
                    device = dev
                    val s = withTimeout(5_000) { configure(dev, listOf(OutputConfiguration(reader.surface))) }
                    log("PROBE cam=$id hires ${size.width}x${size.height} fmt=$format CONFIGURED")
                    s.close()
                } catch (e: Exception) {
                    log("PROBE cam=$id hires ${size.width}x${size.height} fmt=$format refused: ${(e.message ?: e.toString()).take(140)}")
                } finally {
                    runCatching { device?.close() }
                    delay(200)
                    runCatching { reader?.close() }
                }
            }
        }
    }

    /**
     * Takes one HAL JPEG per size (after 3A settles on a small preview stream) and reports its real
     * dimensions, latency and the fine-detail energy of a 1024 px centre crop at 100 %, so an upscaled
     * output can be told apart from a genuine full-sensor readout.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureHires(ids: List<String>, dir: java.io.File) {
        dir.mkdirs()
        try {
            for (id in ids) for (size in listOf(Size(4080, 3060), Size(8160, 6120), Size(16320, 12240))) {
                var device: CameraDevice? = null
                val readers = mutableListOf<ImageReader>()
                try {
                    val preview = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
                    preview.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
                    val jpeg = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
                    readers += preview
                    readers += jpeg
                    val got = kotlinx.coroutines.CompletableDeferred<ByteArray>()
                    jpeg.setOnImageAvailableListener({ r ->
                        r.acquireNextImage()?.use { img ->
                            val b = img.planes[0].buffer
                            got.complete(ByteArray(b.remaining()).also { b.get(it) })
                        }
                    }, handler)
                    val dev = withTimeout(5_000) { open(id) }
                    device = dev
                    val s = withTimeout(5_000) { configure(dev, listOf(OutputConfiguration(preview.surface), OutputConfiguration(jpeg.surface))) }
                    val rb = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(preview.surface) }
                    s.setSingleRepeatingRequest(rb.build(), executor, object : CameraCaptureSession.CaptureCallback() {})
                    delay(1_500)
                    val cb = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(jpeg.surface)
                        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    }
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    var lens = ""
                    s.capture(cb.build(), object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                            lens = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: ""
                        }
                    }, handler)
                    val bytes = withTimeout(20_000) { got.await() }
                    val ms = android.os.SystemClock.elapsedRealtime() - t0
                    s.close()
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    val name = "hires-cam$id-${size.width}x${size.height}.jpg"
                    java.io.File(dir, name).writeBytes(bytes)
                    val detail = detailEnergy(bytes, o.outWidth, o.outHeight)
                    log("HIRES cam=$id req=${size.width}x${size.height} got=${o.outWidth}x${o.outHeight} bytes=${bytes.size} ms=$ms lens=$lens detail=%.2f file=$name".format(detail))
                } catch (e: Exception) {
                    log("HIRES cam=$id req=${size.width}x${size.height} ERROR ${(e.message ?: e.toString()).take(140)}")
                } finally {
                    runCatching { device?.close() }
                    delay(300)
                    readers.forEach { runCatching { it.close() } }
                }
            }
        } finally {
            thread.quitSafely()
        }
    }

    /** Mean absolute Laplacian of the luma of a 1024x1024 centre crop at full resolution. */
    private fun detailEnergy(bytes: ByteArray, w: Int, h: Int): Double {
        val side = minOf(1024, w, h)
        val dec = android.graphics.BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        val r = android.graphics.Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
        val bmp = dec.decodeRegion(r, null)
        dec.recycle()
        val px = IntArray(side * side)
        bmp.getPixels(px, 0, side, 0, 0, side, side)
        bmp.recycle()
        fun y(i: Int): Int { val c = px[i]; return (((c shr 16) and 255) * 54 + ((c shr 8) and 255) * 183 + (c and 255) * 19) shr 8 }
        var sum = 0L
        var n = 0
        for (yy in 1 until side - 1) for (xx in 1 until side - 1) {
            val i = yy * side + xx
            sum += kotlin.math.abs(4 * y(i) - y(i - 1) - y(i + 1) - y(i - side) - y(i + side))
            n++
        }
        return sum.toDouble() / n
    }

    /**
     * ISP A/B: which post-processing the HAL lets an app switch off, and what each change does to a 1080p
     * YUV stream of a static scene (Y percentiles, edge energy, frame-to-frame noise).
     */
    @SuppressLint("MissingPermission")
    suspend fun isp(id: String) {
        val c = manager.getCameraCharacteristics(id)
        val edge = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList()
        val nr = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList()
        val tm = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList()
        val pts = c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS)
        val hot = c.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.toList()
        val shading = c.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)?.toList()
        val scenes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.toList()
        val ext = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES)?.map { it.mode }
        log("ISP cam=$id edge=$edge nr=$nr tonemap=$tm maxPts=$pts hotPixel=$hot shading=$shading scenes=$scenes extScenes=$ext")
        val minimalNr = if (nr?.contains(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL) == true) CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL else CameraMetadata.NOISE_REDUCTION_MODE_OFF
        fun neutral(b: CaptureRequest.Builder) {
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
            b.set(CaptureRequest.NOISE_REDUCTION_MODE, minimalNr)
        }
        fun curve(f: (Float) -> Float): android.hardware.camera2.params.TonemapCurve {
            val n = (pts ?: 32).coerceAtMost(64)
            val a = FloatArray(n * 2) { i ->
                val x = (i / 2).toFloat() / (n - 1)
                if (i % 2 == 0) x else f(x).coerceIn(0f, 1f)
            }
            return android.hardware.camera2.params.TonemapCurve(a, a, a)
        }
        val logCurve = curve { x -> kotlin.math.ln(1f + 48f * x) / kotlin.math.ln(49f) }
        val variants = listOf<Pair<String, (CaptureRequest.Builder) -> Unit>>(
            "default" to { _ -> },
            "edgeOff+nrMin" to { b -> neutral(b) },
            "edgeOff+nrOff" to { b ->
                b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            },
            "neutral+linearCurve" to { b ->
                neutral(b)
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, curve { it })
            },
            "neutral+logCurve" to { b ->
                neutral(b)
                b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
                b.set(CaptureRequest.TONEMAP_CURVE, logCurve)
            },
            "default+hqTonemap" to { b -> b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY) },
        )
        var device: CameraDevice? = null
        val reader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 4)
        try {
            val dev = withTimeout(5_000) { open(id) }
            device = dev
            val s = withTimeout(5_000) { configure(dev, listOf(OutputConfiguration(reader.surface))) }
            for ((name, apply) in variants) {
                val frames = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
                val sampling = java.util.concurrent.atomic.AtomicBoolean(false)
                reader.setOnImageAvailableListener({ r ->
                    r.acquireLatestImage()?.use { img ->
                        if (sampling.get()) {
                            val p0 = img.planes[0]
                            val out = ByteArray(img.width * img.height)
                            val buf = p0.buffer
                            for (y in 0 until img.height) {
                                buf.position(y * p0.rowStride)
                                buf.get(out, y * img.width, img.width)
                            }
                            frames.offer(out)
                        }
                    }
                }, handler)
                val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
                }
                apply(b)
                var applied = ""
                s.setSingleRepeatingRequest(b.build(), executor, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                        applied = "edge=${r.get(CaptureResult.EDGE_MODE)} nr=${r.get(CaptureResult.NOISE_REDUCTION_MODE)} tm=${r.get(CaptureResult.TONEMAP_MODE)} " +
                            "iso=${r.get(CaptureResult.SENSOR_SENSITIVITY)} exp=${(r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1000}us"
                    }
                })
                delay(1_800)
                sampling.set(true)
                val got = mutableListOf<ByteArray>()
                while (got.size < 8) got += withTimeout(3_000) { kotlinx.coroutines.runInterruptible { frames.take() } }
                sampling.set(false)
                s.stopRepeating()
                log("ISP cam=$id $name: ${stats(got, 1920, 1080)} | $applied")
            }
            s.close()
        } catch (e: Exception) {
            log("ISP cam=$id ERROR ${e.message ?: e}")
        } finally {
            runCatching { device?.close() }
            delay(300)
            reader.close()
        }
    }

    /** Y p1/p50/p99, mean |Laplacian| (detail + sharpening) and frame-to-frame RMS difference (temporal noise). */
    private fun stats(frames: List<ByteArray>, w: Int, h: Int): String {
        val f = frames.last()
        val hist = IntArray(256)
        for (v in f) hist[v.toInt() and 255]++
        fun pct(p: Double): Int {
            var acc = 0L
            val t = (f.size * p).toLong()
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= t) return i
            }
            return 255
        }
        fun g(a: ByteArray, j: Int) = a[j].toInt() and 255
        var lap = 0L
        var n = 0
        for (y in h / 4 until 3 * h / 4 step 2) for (x in w / 4 until 3 * w / 4 step 2) {
            val i = y * w + x
            lap += kotlin.math.abs(4 * g(f, i) - g(f, i - 1) - g(f, i + 1) - g(f, i - w) - g(f, i + w))
            n++
        }
        var d2 = 0.0
        var m = 0
        for (k in 1 until frames.size) {
            for (y in h / 4 until 3 * h / 4 step 4) for (x in w / 4 until 3 * w / 4 step 4) {
                val i = y * w + x
                val d = g(frames[k - 1], i) - g(frames[k], i)
                d2 += (d * d).toDouble()
                m++
            }
        }
        return "Y p1=${pct(0.01)} p50=${pct(0.5)} p99=${pct(0.99)} lap=%.2f tnoise=%.2f".format(lap.toDouble() / n, kotlin.math.sqrt(d2 / m / 2))
    }

    /** Every non-android.* key an app can see (characteristics, request, result) for each camera. */
    fun vendorKeys(ids: List<String>) {
        for (id in ids) {
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val chars = c.keys.map { it.name }.filterNot { it.startsWith("android.") }
            val req = c.availableCaptureRequestKeys.map { it.name }.filterNot { it.startsWith("android.") }
            val res = c.availableCaptureResultKeys.map { it.name }.filterNot { it.startsWith("android.") }
            val sessionKeys = c.availableSessionKeys.map { it.name }
            log("VENDOR cam=$id chars=${chars.size} request=${req.size} result=${res.size} sessionKeys=$sessionKeys")
            (chars + req + res).distinct().filter { it.contains("res", true) || it.contains("mode", true) || it.contains("pixel", true) }.take(40)
                .forEach { log("VENDOR cam=$id key $it") }
        }
        thread.quitSafely()
    }

    @SuppressLint("MissingPermission")
    private suspend fun open(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { if (cont.isActive) cont.resume(camera) else camera.close() }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); if (cont.isActive) cont.resumeWithException(IllegalStateException("disconnected")) }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (cont.isActive) cont.resumeWithException(IllegalStateException("error $error")) }
        })
    }

    private suspend fun configure(dev: CameraDevice, outs: List<OutputConfiguration>): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        try {
            dev.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) { if (cont.isActive) cont.resume(session) else session.close() }
                override fun onConfigureFailed(session: CameraCaptureSession) { if (cont.isActive) cont.resumeWithException(IllegalStateException("onConfigureFailed")) }
            }))
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
}
