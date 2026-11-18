package com.datarohit.luma.core.camera.lens

import kotlin.math.abs
import kotlin.math.hypot

/** One back-facing lens as Luma can reach it through Camera2. All values come from camera characteristics. */
data class LensSpec(
    val cameraId: String,
    val physicalId: String?,
    val focalLengthMm: Float,
    val sensorWidthMm: Float,
    val sensorHeightMm: Float,
    val pixelArrayWidth: Int,
    val pixelArrayHeight: Int,
    val activeWidth: Int,
    val activeHeight: Int,
    val aperture: Float,
    val minimumFocusDistanceM: Float?,
    val hasOis: Boolean,
    val rawWidth: Int,
    val rawHeight: Int,
    val maxResolutionRawWidth: Int = 0,
    val maxResolutionRawHeight: Int = 0,
) {
    val id: String get() = physicalId ?: cameraId

    val equivalentFocalLengthMm: Float
        get() {
            val activeWidthMm = sensorWidthMm * activeWidth / pixelArrayWidth
            val activeHeightMm = sensorHeightMm * activeHeight / pixelArrayHeight
            return focalLengthMm * FULL_FRAME_DIAGONAL_MM / hypot(activeWidthMm, activeHeightMm)
        }

    val rawMegapixels: Double get() = rawWidth.toLong() * rawHeight / 1e6
    val maxResolutionRawMegapixels: Double get() = maxResolutionRawWidth.toLong() * maxResolutionRawHeight / 1e6

    fun canFocusAt(distanceM: Float): Boolean = minimumFocusDistanceM == null || distanceM >= minimumFocusDistanceM

    private companion object {
        const val FULL_FRAME_DIAGONAL_MM = 43.2666f
    }
}

enum class LensRole { ULTRA_WIDE, WIDE, TELEPHOTO }

/** How a requested field of view is produced. */
enum class SourceKind {
    /** A lens at its native field of view (requests within [LensCatalog.OPTICAL_SNAP] snap to it). */
    OPTICAL,

    /** A crop that still contains at least the output's pixel count in captured pixels. */
    SENSOR_CROP,

    /** A crop that has to be upsampled to reach the output size. */
    DIGITAL,
}

data class ZoomSource(
    val lens: LensSpec,
    val zoom: Float,
    val crop: Float,
    val kind: SourceKind,
    val usesMaximumResolution: Boolean,
    val capturedMegapixels: Double,
)

class LensCatalog(lenses: List<LensSpec>) {

    init {
        require(lenses.isNotEmpty()) { "LensCatalog needs at least one lens" }
    }

    val lenses: List<LensSpec> = lenses.sortedBy { it.equivalentFocalLengthMm }

    /** The 1× reference: the lens closest to a 24 mm field of view, larger sensor first on ties. */
    val main: LensSpec = lenses.minWith(
        compareBy<LensSpec> { abs(it.equivalentFocalLengthMm - MAIN_REFERENCE_MM) }
            .thenByDescending { it.sensorWidthMm * it.sensorHeightMm },
    )

    fun nativeZoom(lens: LensSpec): Float = lens.equivalentFocalLengthMm / main.equivalentFocalLengthMm

    fun role(lens: LensSpec): LensRole = when {
        lens == main -> LensRole.WIDE
        nativeZoom(lens) < 1f -> LensRole.ULTRA_WIDE
        else -> LensRole.TELEPHOTO
    }

    val minimumZoom: Float get() = nativeZoom(lenses.first())

    /**
     * Resolves a requested zoom (relative to [main]) to the lens that captures it.
     *
     * Picks the longest lens whose native field of view still contains the request and that can focus at
     * [subjectDistanceM] when it is known. Requests within [OPTICAL_SNAP] of a native factor snap to it,
     * so a "3×" preset uses the 3x module without an upsampling crop.
     */
    fun resolve(zoom: Float, outputMegapixels: Double, subjectDistanceM: Float? = null): ZoomSource {
        val requested = zoom.coerceAtLeast(minimumZoom)
        val candidates = lenses
            .filter { nativeZoom(it) <= requested * (1f + OPTICAL_SNAP) }
            .filter { subjectDistanceM == null || it.canFocusAt(subjectDistanceM) }
            .ifEmpty { lenses.filter { nativeZoom(it) <= requested * (1f + OPTICAL_SNAP) } }
            .ifEmpty { listOf(lenses.first()) }
        val lens = candidates.maxBy { nativeZoom(it) }
        val native = nativeZoom(lens)

        if (abs(requested / native - 1f) <= OPTICAL_SNAP) {
            return ZoomSource(lens, native, 1f, SourceKind.OPTICAL, false, lens.rawMegapixels)
        }

        val crop = requested / native
        val binned = lens.rawMegapixels / (crop * crop)
        val maxResolution = lens.maxResolutionRawMegapixels / (crop * crop)
        return when {
            maxResolution >= outputMegapixels -> ZoomSource(lens, requested, crop, SourceKind.SENSOR_CROP, true, maxResolution)
            binned >= outputMegapixels -> ZoomSource(lens, requested, crop, SourceKind.SENSOR_CROP, false, binned)
            else -> ZoomSource(lens, requested, crop, SourceKind.DIGITAL, false, maxOf(binned, maxResolution))
        }
    }

    companion object {
        const val OPTICAL_SNAP = 0.05f
        private const val MAIN_REFERENCE_MM = 24f
    }
}
