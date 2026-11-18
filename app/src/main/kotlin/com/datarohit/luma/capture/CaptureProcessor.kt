package com.datarohit.luma.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.Size
import com.datarohit.luma.core.camera.session.BurstPlan
import com.datarohit.luma.core.camera.session.CameraController
import com.datarohit.luma.core.camera.session.StillCapture
import com.datarohit.luma.core.imaging.Camera2Raw
import com.datarohit.luma.core.imaging.OutputSpace
import com.datarohit.luma.core.imaging.RawBurst
import com.datarohit.luma.core.imaging.RenderOptions
import com.datarohit.luma.core.imaging.RenderStats
import com.datarohit.luma.core.storage.MediaStoreWriter
import com.datarohit.luma.core.storage.PhotoMetadata
import com.datarohit.luma.core.storage.PhotoSink
import com.datarohit.luma.settings.AppSettings
import com.datarohit.luma.settings.AspectRatio
import com.datarohit.luma.settings.PhotoFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.roundToInt

/** Output classes (spec RULE-3). */
enum class OutputClass(val label: String) {
    NATIVE_RAW("Native RAW"),
    MERGED_RAW("Merged RAW"),
    PROCESSED("Processed"),
}

/** Everything fixed at shutter time. */
data class CaptureRequestSpec(
    val mode: CaptureMode,
    val plan: CapturePlan,
    val settings: AppSettings,
    /** Requested zoom relative to the main lens. */
    val zoom: Float,
    /** CONTROL_ZOOM_RATIO used on the opened camera (crop fallback when no physical crop is reported). */
    val cameraZoomRatio: Float,
    val rotationDegrees: Int,
    val whiteBalanceKelvin: Int?,
    /** Human-readable lens description for a (physical) camera ID. */
    val lensDescription: (String) -> String,
    /** 35 mm equivalent focal length of a (physical) camera ID at its native field of view. */
    val equivalentFocalMm: (String) -> Float,
    /** Source label shown in the viewfinder at shutter time (optical / digital and lens). */
    val sourceLabel: String,
    /** GPU effect applied to the upright photo (portrait bokeh, food ring, Pro tint), null = none. */
    val effect: com.datarohit.luma.gl.ShadeParams? = null,
    /** Mode name recorded in the summary (Portrait, Food, ...). */
    val modeLabel: String = mode.label,
    /** How the chosen resolution tier is produced on the capturing lens. */
    val resolution: ResolutionPlan? = null,
)

/** RAW frames captured and copied; ready for background processing. */
class CapturedBurst(
    val spec: CaptureRequestSpec,
    val burst: RawBurst,
    val characteristics: CameraCharacteristics,
    val firstResult: TotalCaptureResult,
    val sourceCameraId: String,
    val rawSize: Size,
    val dngCopy: ByteBuffer?,
    val time: ZonedDateTime,
)

data class ProcessedPhoto(
    val jpegUri: Uri,
    val dngUri: Uri?,
    val thumbnail: Bitmap,
    val stats: RenderStats?,
    val summary: String,
    val outputClass: OutputClass,
)

class CaptureProcessor(
    private val controller: CameraController,
    private val characteristicsOf: (String) -> CameraCharacteristics,
    private val sink: PhotoSink,
    /** Person segmentation for portrait stills (upright bitmap in, mask in the same orientation out). */
    private val segment: (Bitmap) -> com.datarohit.luma.portrait.SubjectMask? = { null },
) {

    /** Runs the burst and copies frames into native memory. Returns null if nothing arrived. */
    suspend fun capture(spec: CaptureRequestSpec): CapturedBurst? {
        var burst: RawBurst? = null
        var chars: CameraCharacteristics? = null
        var first: TotalCaptureResult? = null
        var source = ""
        var size = Size(0, 0)
        var dng: ByteBuffer? = null
        val time = ZonedDateTime.now()
        val delivered = try {
            controller.captureBurst(BurstPlan(spec.plan.frames, spec.plan.exposureNs, spec.plan.iso)) { frame ->
                if (burst == null) {
                    source = frame.sourceCameraId
                    val c = characteristicsOf(source)
                    chars = c
                    first = frame.result
                    size = Size(frame.width, frame.height)
                    val meta = Camera2Raw.metadata(c, frame.result, frame.width, frame.height)
                    burst = RawBurst(meta, cropFor(spec, frame.result, c, frame.width, frame.height))
                    if (spec.settings.saveDng) dng = packedCopy(frame.buffer, frame.rowStride, frame.width, frame.height)
                }
                // Frames from a different physical camera (a lens switch mid-burst) cannot be merged.
                if (frame.sourceCameraId == source) burst!!.addFrame(frame.buffer, frame.rowStride)
            }
        } catch (e: Exception) {
            burst?.close()
            throw e
        }
        val b = burst ?: return null
        if (delivered == 0 || b.frameCount == 0) {
            b.close()
            return null
        }
        return CapturedBurst(spec, b, chars!!, first!!, source, size, dng, time)
    }

    /** Renders, encodes and stores one RAW burst. Runs on the processing thread. */
    fun process(c: CapturedBurst): ProcessedPhoto {
        c.burst.use { burst ->
            val s = c.spec.settings
            val crop = burst.crop
            val options = RenderOptions(
                exposureEv = c.spec.plan.processingEv,
                localTone = s.localTone,
                sharpen = s.sharpness,
                lumaDenoise = s.noiseReduction,
                chromaDenoise = (0.4f + s.noiseReduction * 0.6f).coerceIn(0f, 1f),
                whiteBalanceKelvin = c.spec.whiteBalanceKelvin?.toFloat() ?: 0f,
                look = s.look,
                space = s.outputSpace,
                merge = c.spec.plan.merge,
                superResolution = superResolutionScale(s.photoResolution, crop.width(), crop.height(), c.spec.plan.merge, burst.frameCount),
            )
            val colorSpace = ColorSpace.get(if (s.outputSpace == OutputSpace.SRGB) ColorSpace.Named.SRGB else ColorSpace.Named.DISPLAY_P3)
            val (outW, outH) = burst.outputSize(options)
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888, false, colorSpace)
            try {
                val stats = burst.process(Camera2Raw.calibration(c.characteristics), options, bitmap)
                val finished = finish(bitmap, c.spec)
                val r = c.firstResult
                val base = MediaStoreWriter.baseName(c.time)
                val cropFactor = c.rawSize.width.toFloat() / crop.width().coerceAtLeast(1)
                val focal35 = (c.spec.equivalentFocalMm(c.sourceCameraId) * cropFactor).roundToInt()
                val rawClass = if (stats.framesMerged > 1) OutputClass.MERGED_RAW else OutputClass.NATIVE_RAW
                val megapixels = finished.bitmap.width.toLong() * finished.bitmap.height / 1e6
                val summary = buildString {
                    append(OutputClass.PROCESSED.label).append(" by Luma from ")
                    append(if (stats.framesMerged > 1) "${stats.framesMerged}-frame ${rawClass.label}" else rawClass.label)
                    append(" · ").append(c.spec.sourceLabel)
                    append(" · %.1f MP".format(Locale.ROOT, megapixels))
                    val cropMp = crop.width().toLong() * crop.height() / 1e6
                    val superRes = stats.superResolution > 1.01f
                    val detailMp = if (superRes) bitmap.width.toLong() * bitmap.height / 1e6 else cropMp
                    if (superRes) {
                        append(" (multi-frame super-resolution %.2fx from %.1f MP)".format(Locale.ROOT, stats.superResolution, cropMp))
                    }
                    if (megapixels > detailMp * 1.05) {
                        append(" (enlarged from %.1f MP".format(Locale.ROOT, detailMp))
                        if (options.superResolution > 1.01f && !superRes) append(": camera too still for multi-frame super-resolution")
                        append(")")
                    }
                    append(" · ").append(c.spec.modeLabel).append(": ").append(c.spec.plan.reason)
                    if (c.spec.effect != null) append(" · GPU effect")
                    append(" · $RENDER_VERSION")
                }
                val meta0 = metadata(c.spec, c.time, r, focal35, cropFactor, summary, c.spec.lensDescription(c.sourceCameraId))
                val meta = if (finished.upright) meta0.copy(exifOrientation = MediaStoreWriter.exifOrientation(0)) else meta0
                val jpeg = save(finished.bitmap, s, meta, base)
                val dngUri = if (s.saveDng) runCatching { writeDng(c, burst, meta, base, stats) }
                    .onFailure { Log.w(TAG, "DNG failed", it) }.getOrNull() else null
                val thumb = thumbnail(finished.bitmap, if (finished.upright) 0 else c.spec.rotationDegrees)
                Log.i(TAG, "saved ${jpeg.displayName}: $summary | ${stats.msTotal.roundToInt()} ms")
                if (finished.bitmap !== bitmap) finished.bitmap.recycle()
                return ProcessedPhoto(jpeg.uri, dngUri, thumb, stats, summary, OutputClass.PROCESSED)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /** Captures and stores a HAL-processed still (telephoto route). */
    suspend fun captureProcessedByHal(spec: CaptureRequestSpec, activeLensId: String?): ProcessedPhoto {
        val time = ZonedDateTime.now()
        val still: StillCapture = controller.captureStill(spec.rotationDegrees, spec.settings.jpegQuality)
        return saveHalStill(spec, still, time, activeLensId)
    }

    fun saveHalStill(spec: CaptureRequestSpec, still: StillCapture, time: ZonedDateTime, activeLensId: String?): ProcessedPhoto {
        val r = still.result
        val lens = r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) ?: activeLensId ?: ""
        val summary = "${OutputClass.PROCESSED.label} by the camera HAL · ${spec.sourceLabel} · " +
            "Luma cannot read autofocused RAW from this lens on this device (TD-21)"
        val focal35 = (spec.equivalentFocalMm(lens) * (spec.zoom / nativeZoomOf(spec, lens)).coerceAtLeast(1f)).roundToInt()
        val meta = metadata(spec, time, r, focal35, 1f, summary, spec.lensDescription(lens)).copy(srgb = true)
        val wanted = spec.resolution?.from?.size
        val shortfall = wanted != null && spec.resolution.needsHighResStream && still.width.toLong() * still.height < wanted.width.toLong() * wanted.height
        if (shortfall) Log.w(TAG, "camera returned ${still.width}x${still.height}, requested native ${wanted!!.width}x${wanted.height}")
        val digital = (spec.zoom / spec.cameraZoomRatio).coerceAtLeast(1f)
        val resized = ResolutionCatalog.outputSize(spec.settings.photoResolution, still.width, still.height) != Size(still.width, still.height)
        val needsPixels = spec.effect != null || spec.settings.photoFormat == PhotoFormat.HEIF || resized || digital > 1.01f
        if (needsPixels) {
            // Decode upright, apply effect / scale, re-encode.
            val decoded = BitmapFactory.decodeByteArray(still.jpeg, 0, still.jpeg.size) ?: error("HAL JPEG decode failed")
            val orientation = runCatching {
                android.media.ExifInterface(java.io.ByteArrayInputStream(still.jpeg)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
            }.getOrDefault(1)
            val degrees = when (orientation) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
            var upright = rotate(decoded, degrees)
            if (upright !== decoded) decoded.recycle()
            if (digital > 1.01f) {
                // Beyond the camera's zoom range: centre crop of the telephoto frame, then the tier size below.
                val cw = (upright.width / digital).toInt().coerceAtLeast(16)
                val ch = (upright.height / digital).toInt().coerceAtLeast(16)
                val cropped = Bitmap.createBitmap(upright, (upright.width - cw) / 2, (upright.height - ch) / 2, cw, ch)
                if (cropped !== upright) upright.recycle()
                upright = cropped
            }
            val finished = finish(upright, spec.copy(rotationDegrees = 0))
            val item = save(finished.bitmap, spec.settings, meta.copy(exifOrientation = MediaStoreWriter.exifOrientation(0)), MediaStoreWriter.baseName(time))
            val thumb = thumbnail(finished.bitmap, 0)
            if (finished.bitmap !== upright) finished.bitmap.recycle()
            upright.recycle()
            return ProcessedPhoto(item.uri, null, thumb, null, summary, OutputClass.PROCESSED)
        }
        // Keep the HAL's encoded bytes (no re-encode); only Luma's EXIF fields are added.
        val item = sink.saveEncodedJpeg(still.jpeg, still.width, still.height, meta, MediaStoreWriter.baseName(time))
        val thumb = halThumbnail(still.jpeg)
        return ProcessedPhoto(item.uri, null, thumb, null, summary, OutputClass.PROCESSED)
    }

    private class Finished(val bitmap: Bitmap, val upright: Boolean)

    /** Applies the GPU effect (upright) and the output resolution. */
    private fun finish(bitmap: Bitmap, spec: CaptureRequestSpec): Finished {
        var out = bitmap
        var upright = false
        spec.effect?.let { fx ->
            val rotated = rotate(bitmap, spec.rotationDegrees)
            // Portrait: segment the finished photo itself (a downscaled copy), so the mask fits this exact frame.
            val mask = if (fx.useMask) {
                val s = 512f / maxOf(rotated.width, rotated.height)
                val small = Bitmap.createScaledBitmap(rotated, (rotated.width * s).roundToInt().coerceAtLeast(1), (rotated.height * s).roundToInt().coerceAtLeast(1), true)
                segment(small).also { small.recycle() }?.takeIf { it.coverage > 0.02f }
            } else null
            out = com.datarohit.luma.gl.StillEffects.apply(rotated, if (mask == null) fx.copy(useMask = false) else fx, mask = mask)
            if (rotated !== bitmap) rotated.recycle()
            upright = true
        }
        val target = ResolutionCatalog.outputSize(spec.settings.photoResolution, out.width, out.height)
        // Already at the tier (e.g. the super-resolved grid, within a few percent): keep every captured pixel.
        val atTier = kotlin.math.abs(target.width.toLong() * target.height / (out.width.toDouble() * out.height) - 1.0) < 0.04
        if (!atTier && (target.width != out.width || target.height != out.height)) {
            // Lanczos-3 in the native engine: sharp enlargement / area-correct reduction, multithreaded.
            val t0 = android.os.SystemClock.elapsedRealtime()
            val space = out.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            val dst = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888, false, space)
            if (!com.datarohit.luma.core.imaging.ImagingEngine.resample(out, dst)) {
                dst.recycle()
                error("resampling to ${target.width}x${target.height} failed")
            }
            Log.i(TAG, "resampled ${out.width}x${out.height} -> ${target.width}x${target.height} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            if (out !== bitmap) out.recycle()
            out = dst
        }
        return Finished(out, upright)
    }

    /** Stores [bitmap] as JPEG or HEIF per settings. */
    fun save(bitmap: Bitmap, s: AppSettings, meta: PhotoMetadata, base: String) =
        if (s.photoFormat == PhotoFormat.HEIF) {
            // Very large frames can exceed the HEVC still encoder; JPEG then keeps the shot.
            runCatching { sink.saveHeif(bitmap, s.jpegQuality, meta, base) }
                .getOrElse { e -> Log.w(TAG, "HEIF failed at ${bitmap.width}x${bitmap.height}, saving JPEG", e); sink.saveJpeg(bitmap, s.jpegQuality, meta, base) }
        } else {
            sink.saveJpeg(bitmap, s.jpegQuality, meta, base)
        }

    private fun rotate(b: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return b
        val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun halThumbnail(jpeg: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
        val small = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("HAL JPEG decode failed")
        val orientation = runCatching {
            android.media.ExifInterface(java.io.ByteArrayInputStream(jpeg)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        }.getOrDefault(1)
        val degrees = when (orientation) { 6 -> 90; 3 -> 180; 8 -> 270; else -> 0 }
        return thumbnail(small, degrees)
    }

    private fun nativeZoomOf(spec: CaptureRequestSpec, lens: String): Float =
        (spec.equivalentFocalMm(lens) / MAIN_EQUIVALENT_MM).coerceAtLeast(0.1f)

    private fun metadata(
        spec: CaptureRequestSpec, time: ZonedDateTime, r: CaptureResult, focal35: Int, cropFactor: Float, summary: String, lens: String,
    ) = PhotoMetadata(
        captureTime = time,
        exifOrientation = MediaStoreWriter.exifOrientation(spec.rotationDegrees),
        exposureNs = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: spec.plan.exposureNs,
        iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: spec.plan.iso,
        aperture = r.get(CaptureResult.LENS_APERTURE) ?: 0f,
        focalLengthMm = r.get(CaptureResult.LENS_FOCAL_LENGTH) ?: 0f,
        focalLength35mm = focal35,
        digitalZoom = cropFactor,
        lensModel = lens,
        software = "Luma $APP_VERSION",
        description = "Luma: $summary",
        whiteBalanceManual = spec.whiteBalanceKelvin != null,
        srgb = spec.settings.outputSpace == OutputSpace.SRGB,
    )

    private fun writeDng(c: CapturedBurst, burst: RawBurst, meta: PhotoMetadata, base: String, stats: RenderStats): Uri {
        val crop = burst.crop
        val full = crop.width() == c.rawSize.width && crop.height() == c.rawSize.height
        val merged = if (full && stats.framesMerged > 1) burst.mergedMosaic() else null
        val pixels = merged ?: c.dngCopy ?: error("no RAW copy kept")
        pixels.rewind()
        val creator = DngCreator(c.characteristics, c.firstResult)
        try {
            creator.setOrientation(meta.exifOrientation)
            creator.setDescription(
                if (merged != null) "Luma ${OutputClass.MERGED_RAW.label} (${stats.framesMerged} frames)"
                else "Luma ${OutputClass.NATIVE_RAW.label} (single frame, full sensor)",
            )
            val dngMeta = meta.copy(description = if (merged != null) "Luma: Merged RAW" else "Luma: Native RAW")
            return sink.saveDng(base, dngMeta) { out -> creator.writeByteBuffer(out, c.rawSize, pixels, 0) }.uri
        } finally {
            creator.close()
        }
    }

    fun thumbnail(bitmap: Bitmap, rotation: Int): Bitmap {
        val scale = 320f / maxOf(bitmap.width, bitmap.height)
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        if (rotation % 360 == 0) return small
        val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(small, 0, 0, small.width, small.height, m, true)
    }

    companion object {
        private const val TAG = "LumaCapture"
        const val APP_VERSION = "0.2.0"
        /** Rendering version recorded in every output (spec COL-3). */
        const val RENDER_VERSION = "render v1"
        private const val MAIN_EQUIVALENT_MM = 23f
        /**
         * Super-resolution scale per side for a burst rendered at [tier] from a [cropW] x [cropH] RAW crop: the
         * tier's size, at most 2x per side (the rest is enlarged afterwards); 1 below the crop or without a merge.
         */
        fun superResolutionScale(tier: com.datarohit.luma.settings.PhotoResolution, cropW: Int, cropH: Int, merge: Boolean, frames: Int): Float {
            if (!merge || frames < 4 || cropW <= 0) return 1f
            val wanted = ResolutionCatalog.outputSize(tier, cropW, cropH).width.toFloat() / cropW
            return if (wanted > 1.05f) wanted.coerceAtMost(2f) else 1f
        }

        /** RAW crop for the requested zoom and aspect ratio, in RAW pixel coordinates. */
        fun cropFor(spec: CaptureRequestSpec, result: CaptureResult, chars: CameraCharacteristics, rawW: Int, rawH: Int): Rect {
            val active = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, rawW, rawH)
            val sx = rawW.toFloat() / active.width()
            val sy = rawH.toFloat() / active.height()
            val hal: Rect? = if (Build.VERSION.SDK_INT >= 35)
                result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION) else null
            var base = if (hal != null && hal.width() > 0) {
                Rect((hal.left * sx).roundToInt(), (hal.top * sy).roundToInt(), (hal.right * sx).roundToInt(), (hal.bottom * sy).roundToInt())
            } else {
                centered(rawW, rawH, spec.cameraZoomRatio.coerceAtLeast(1f))
            }
            base = applyAspect(base, spec.settings.aspect)
            return Rect(base.left and 1.inv(), base.top and 1.inv(), base.right and 1.inv(), base.bottom and 1.inv())
        }

        fun centered(w: Int, h: Int, factor: Float): Rect {
            val cw = (w / factor).roundToInt()
            val ch = (h / factor).roundToInt()
            return Rect((w - cw) / 2, (h - ch) / 2, (w - cw) / 2 + cw, (h - ch) / 2 + ch)
        }

        /** Centre-crops a landscape sensor rectangle to the aspect ratio. */
        fun applyAspect(r: Rect, aspect: AspectRatio): Rect {
            val target = aspect.widthOverHeight
            val current = r.width().toFloat() / r.height()
            return if (current > target) {
                val w = (r.height() * target).roundToInt()
                Rect(r.centerX() - w / 2, r.top, r.centerX() - w / 2 + w, r.bottom)
            } else {
                val h = (r.width() / target).roundToInt()
                Rect(r.left, r.centerY() - h / 2, r.right, r.centerY() - h / 2 + h)
            }
        }

        fun packedCopy(src: ByteBuffer, rowStride: Int, width: Int, height: Int): ByteBuffer {
            val out = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
            val row = ByteArray(width * 2)
            val s = src.duplicate()
            for (y in 0 until height) {
                s.position(y * rowStride)
                s.get(row)
                out.put(row)
            }
            out.rewind()
            return out
        }
    }
}
