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

    private var handle: Long

    init {
        ImagingEngine.ensureLoaded()
        val ints = intArrayOf(
            metadata.sensorWidth, metadata.sensorHeight, metadata.cfa,
            metadata.shadingColumns, metadata.shadingRows,
            crop.left, crop.top, crop.width(), crop.height(),
        )
        val floats = floatArrayOf(
            metadata.blackLevel[0], metadata.blackLevel[1], metadata.blackLevel[2], metadata.blackLevel[3],
            metadata.whiteLevel,
            metadata.asShotNeutral[0], metadata.asShotNeutral[1], metadata.asShotNeutral[2],
            metadata.noiseScale, metadata.noiseOffset,
        )
        handle = nativeCreate(ints, floats, metadata.shadingGains)
        check(handle != 0L) { "native burst allocation failed" }
    }

    /** Crop actually used (CFA-aligned, clamped). */
    val crop: Rect
        get() {
            val c = nativeCrop(requireHandle())
            return Rect(c[0], c[1], c[0] + c[2], c[1] + c[3])
        }

    val frameCount: Int get() = nativeCrop(requireHandle())[4]

    /** Adds a RAW_SENSOR plane (direct buffer, 2 bytes per pixel). */
    fun addFrame(buffer: ByteBuffer, rowStrideBytes: Int, brightnessScale: Float = 1f): Boolean =
        nativeAddFrame(requireHandle(), buffer, rowStrideBytes, brightnessScale)

    fun outputSize(options: RenderOptions): Pair<Int, Int> {
        val c = crop
        return when {
            // Must match the engine's super-resolution grid (Burst::output_size).
            options.superResolution > 1.01f && options.merge && frameCount > 1 ->
                (maxOf(2, Math.round(c.width() * options.superResolution)) and 1.inv()) to
                    (maxOf(2, Math.round(c.height() * options.superResolution)) and 1.inv())
            options.outputWidth > 0 && options.outputHeight > 0 -> options.outputWidth to options.outputHeight
            else -> c.width() to c.height()
        }
    }

    /** Renders into an RGBA_8888 bitmap of [outputSize]. */
    fun process(color: ColorCalibration, options: RenderOptions, bitmap: Bitmap): RenderStats {
        val colorArray = FloatArray(54).also { a ->
            listOf(color.colorMatrix1, color.colorMatrix2, color.forwardMatrix1, color.forwardMatrix2,
                color.calibration1, color.calibration2).forEachIndexed { i, m -> m.copyInto(a, i * 9) }
        }
        val colorInts = intArrayOf(color.illuminant1, color.illuminant2, if (color.hasSecond) 1 else 0, if (color.hasForward) 1 else 0)
        val of = floatArrayOf(
            options.exposureEv, options.localTone, options.shadows, options.highlights, options.contrast,
            options.saturation, options.sharpen, options.lumaDenoise, options.chromaDenoise,
            options.whiteBalanceKelvin, options.whiteBalanceTint, options.superResolution,
        )
        val oi = intArrayOf(options.outputWidth, options.outputHeight, options.look.id, options.space.id,
            if (options.merge) 1 else 0, options.threads)
        val stats = nativeProcess(requireHandle(), colorArray, colorInts, of, oi, bitmap)
            ?: error("native processing failed")
        return RenderStats.fromArray(stats)
    }

    /** Merged mosaic of the crop as little-endian RAW16 (for DNG), valid after [process]. */
    fun mergedMosaic(): ByteBuffer? {
        val c = crop
        val buffer = ByteBuffer.allocateDirect(c.width() * c.height() * 2).order(ByteOrder.nativeOrder())
        return if (nativeMergedMosaic(requireHandle(), buffer)) buffer else null
    }

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    private fun requireHandle(): Long {
        check(handle != 0L) { "burst already closed" }
        return handle
    }

    private companion object {
        @JvmStatic external fun nativeCreate(ints: IntArray, floats: FloatArray, shading: FloatArray?): Long
        @JvmStatic external fun nativeAddFrame(handle: Long, buffer: ByteBuffer, rowStrideBytes: Int, brightness: Float): Boolean
        @JvmStatic external fun nativeProcess(
            handle: Long, color: FloatArray, colorInts: IntArray, optionsF: FloatArray, optionsI: IntArray, bitmap: Bitmap,
        ): FloatArray?
        @JvmStatic external fun nativeMergedMosaic(handle: Long, buffer: ByteBuffer): Boolean
        @JvmStatic external fun nativeCrop(handle: Long): IntArray
        @JvmStatic external fun nativeRelease(handle: Long)
    }
}
