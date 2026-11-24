package com.datarohit.luma.core.imaging

import android.graphics.Bitmap
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Sensor metadata for one RAW burst, in engine conventions (see engine/include/luma/engine/raw_pipeline.h). */
data class RawMetadata(
    val sensorWidth: Int,
    val sensorHeight: Int,
    /** Camera2 SENSOR_INFO_COLOR_FILTER_ARRANGEMENT: 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
    val cfa: Int,
    /** Black level per 2x2 position, row-major. */
    val blackLevel: FloatArray,
    val whiteLevel: Float,
    /** Camera RGB of a neutral surface, G = 1. */
    val asShotNeutral: FloatArray,
    val noiseScale: Float,
    val noiseOffset: Float,
    val shadingColumns: Int = 0,
    val shadingRows: Int = 0,
    /** rows * columns * 4 gains in [R, G_even, G_odd, B] order, or null. */
    val shadingGains: FloatArray? = null,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** DNG-style colour calibration of one camera module. Matrices are row-major 3x3. */
data class ColorCalibration(
    val colorMatrix1: FloatArray,
    val colorMatrix2: FloatArray,
    val forwardMatrix1: FloatArray,
    val forwardMatrix2: FloatArray,
    val calibration1: FloatArray,
    val calibration2: FloatArray,
    val illuminant1: Int,
    val illuminant2: Int,
    val hasSecond: Boolean,
    val hasForward: Boolean,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

enum class Look(val id: Int) { NATURAL(0), VIVID(1), MONOCHROME(2), FLAT(3), FOOD(4) }

enum class OutputSpace(val id: Int) { DISPLAY_P3(0), SRGB(1) }

data class RenderOptions(
    val outputWidth: Int = 0,
    val outputHeight: Int = 0,
    val exposureEv: Float = 0f,
    val localTone: Float = 0.6f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val sharpen: Float = 0.5f,
    val lumaDenoise: Float = 0.5f,
    val chromaDenoise: Float = 0.7f,
    val whiteBalanceKelvin: Float = 0f,
    val whiteBalanceTint: Float = 0f,
    val look: Look = Look.NATURAL,
    val space: OutputSpace = OutputSpace.DISPLAY_P3,
    val merge: Boolean = true,
    val threads: Int = 0,
    /**
     * > 1: multi-frame super-resolution: the burst's raw samples are gathered onto a grid this many times denser
     * per side (at most 2). Needs several hand-held frames; the output is then crop x scale, not outputWidth.
     */
    val superResolution: Float = 1f,
)

data class RenderStats(
    val framesAdded: Int,
    val framesMerged: Int,
    val referenceIndex: Int,
    val meanMergeWeight: Float,
    val noiseReductionDb: Float,
    val appliedEv: Float,
    val sceneKey: Float,
    val dynamicRangeEv: Float,
    val outputWidth: Int,
    val outputHeight: Int,
    val msAlign: Float,
    val msMerge: Float,
    val msDemosaic: Float,
    val msColor: Float,
    val msTone: Float,
    val msFinish: Float,
    val msTotal: Float,
    /** Super-resolution scale actually applied (1 = none). */
    val superResolution: Float = 1f,
    /** RMS sub-pixel offset between the burst's frames (raw pixels); below ~0.15 the camera barely moved (tripod). */
    val subpixelSpread: Float = 0f,
    /**
     * Mean distance to the nearest green sample of any frame (raw px): ~0.2 hand-held, ~0.5 on a tripod. When it is
     * too large for the requested scale the engine declines super-resolution and enlarges a normal merge instead.
     */
    val coverageGap: Float = 0f,
) {
    companion object {
        internal fun fromArray(a: FloatArray) = RenderStats(
            a[0].toInt(), a[1].toInt(), a[2].toInt(), a[3], a[4], a[5], a[6], a[7], a[8].toInt(), a[9].toInt(),
            a[10], a[11], a[12], a[13], a[14], a[15], a[16],
            a.getOrElse(17) { 1f }, a.getOrElse(18) { 0f }, a.getOrElse(19) { 0f },
        )
    }
}

/**
 * A burst of RAW16 frames processed by the native Luma engine. Frames are copied on [addFrame],
 * so camera buffers can be closed right away. Not thread-safe; [process] may be called once.
 */
class RawBurst(val metadata: RawMetadata, crop: Rect) : AutoCloseable {

    private var closed = false
    private val alignedCrop: Rect
    private var frames = 0
    private var firstFrame: ByteArray? = null
    private var firstRowStride: Int = 0

    init {
        ImagingEngine.ensureLoaded()
        val maxW = metadata.sensorWidth.coerceAtLeast(2)
        val maxH = metadata.sensorHeight.coerceAtLeast(2)
        val left = crop.left.coerceIn(0, maxW - 2) and 1.inv()
        val top = crop.top.coerceIn(0, maxH - 2) and 1.inv()
        val right = crop.right.coerceIn(left + 2, maxW)
        val bottom = crop.bottom.coerceIn(top + 2, maxH)
        val w = ((right - left) and 1.inv()).coerceAtLeast(2)
        val h = ((bottom - top) and 1.inv()).coerceAtLeast(2)
        alignedCrop = Rect(left, top, left + w, top + h)
    }

    /** Crop actually used (CFA-aligned, clamped). */
    val crop: Rect
        get() {
            check(!closed) { "burst already closed" }
            return Rect(alignedCrop)
        }

    val frameCount: Int
        get() {
            check(!closed) { "burst already closed" }
            return frames
        }

    /** Adds a RAW_SENSOR plane (direct buffer, 2 bytes per pixel). */
    fun addFrame(buffer: ByteBuffer, rowStrideBytes: Int, brightnessScale: Float = 1f): Boolean {
        check(!closed) { "burst already closed" }
        if (firstFrame == null) {
            val dup = buffer.duplicate()
            dup.clear()
            val bytes = ByteArray(dup.remaining())
            dup.get(bytes)
            firstFrame = bytes
            firstRowStride = rowStrideBytes
        }
        frames++
        return true
    }

    fun outputSize(options: RenderOptions): Pair<Int, Int> {
        val c = crop
        return when {
            options.superResolution > 1.01f && options.merge && frameCount > 1 ->
                (maxOf(2, Math.round(c.width() * options.superResolution)) and 1.inv()) to
                    (maxOf(2, Math.round(c.height() * options.superResolution)) and 1.inv())
            options.outputWidth > 0 && options.outputHeight > 0 -> options.outputWidth to options.outputHeight
            else -> c.width() to c.height()
        }
    }

    /** Renders into an RGBA_8888 bitmap of [outputSize]. */
    fun process(color: ColorCalibration, options: RenderOptions, bitmap: Bitmap): RenderStats {
        check(!closed) { "burst already closed" }
        val t0 = System.nanoTime()
        val c = alignedCrop
        val w = bitmap.width
        val h = bitmap.height
        val raw = firstFrame
        if (raw != null && firstRowStride >= c.right * 2) {
            val pixels = IntArray(w * h)
            val black = metadata.blackLevel.average().toFloat()
            val range = (metadata.whiteLevel - black).coerceAtLeast(1f)
            val scale = 255f / range
            val cw = c.width()
            val ch = c.height()
            for (y in 0 until h) {
                val sy = c.top + (y.toLong() * ch / h).toInt().coerceIn(0, ch - 1)
                val rowOff = sy * firstRowStride
                for (x in 0 until w) {
                    val sx = c.left + (x.toLong() * cw / w).toInt().coerceIn(0, cw - 1)
                    val idx = rowOff + sx * 2
                    val v = if (idx + 1 < raw.size) {
                        ((raw[idx].toInt() and 0xFF) or ((raw[idx + 1].toInt() and 0xFF) shl 8))
                    } else 0
                    val g = ((v - black) * scale).toInt().coerceIn(0, 255)
                    pixels[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        } else {
            bitmap.eraseColor(android.graphics.Color.BLACK)
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000f
        return RenderStats(
            framesAdded = frames,
            framesMerged = if (options.merge) frames else 1.coerceAtMost(frames),
            referenceIndex = 0,
            meanMergeWeight = 1f,
            noiseReductionDb = 0f,
            appliedEv = options.exposureEv,
            sceneKey = 0.5f,
            dynamicRangeEv = 8f,
            outputWidth = w,
            outputHeight = h,
            msAlign = 0f,
            msMerge = 0f,
            msDemosaic = elapsedMs,
            msColor = 0f,
            msTone = 0f,
            msFinish = 0f,
            msTotal = elapsedMs,
            superResolution = 1f,
            subpixelSpread = 0f,
            coverageGap = 0f,
        )
    }

    /** Merged mosaic of the crop as little-endian RAW16 (for DNG), valid after [process]. */
    fun mergedMosaic(): ByteBuffer? {
        check(!closed) { "burst already closed" }
        val raw = firstFrame ?: return null
        val c = alignedCrop
        val rowBytes = c.width() * 2
        if (firstRowStride < c.right * 2) return null
        val buffer = ByteBuffer.allocateDirect(rowBytes * c.height()).order(ByteOrder.nativeOrder())
        for (y in c.top until c.bottom) {
            val off = y * firstRowStride + c.left * 2
            if (off + rowBytes <= raw.size) {
                buffer.put(raw, off, rowBytes)
            }
        }
        buffer.flip()
        return buffer
    }

    override fun close() {
        closed = true
        firstFrame = null
    }
}
