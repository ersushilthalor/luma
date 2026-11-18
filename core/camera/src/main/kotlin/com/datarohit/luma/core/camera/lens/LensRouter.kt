package com.datarohit.luma.core.camera.lens

import kotlin.math.abs

/**
 * How a requested zoom is captured on this device (decision TD-21, measured 2026-11-18 on SM-S948B):
 *
 * - A session on the logical back camera that contains a RAW stream stays on the main sensor at every zoom.
 * - Physical RAW streams of inactive lenses have no autofocus and no auto white balance.
 * - The ultra-wide is also a public standalone camera with full 3A.
 *
 * So Luma takes RAW from the ultra-wide (standalone camera) below 1×, RAW from the main sensor up to the
 * first telephoto, and HAL-processed stills from the logical camera (free to switch lenses) beyond that.
 */
enum class CaptureRoute {
    /** Standalone ultra-wide camera, RAW, processed by Luma. */
    ULTRA_WIDE_RAW,
    /** Logical camera pinned to the main sensor by its RAW stream, processed by Luma. */
    MAIN_RAW,
    /** Logical camera without RAW: the HAL picks the lens and processes the image. */
    HAL_PROCESSED,
}

data class RouteDecision(
    val route: CaptureRoute,
    /** Camera to open. */
    val cameraId: String,
    /** CONTROL_ZOOM_RATIO for that camera. */
    val cameraZoomRatio: Float,
    /** Requested zoom relative to the main lens. */
    val zoom: Float,
    /** GPU / post crop beyond the camera's own zoom range (1 = none). */
    val digitalZoom: Float = 1f,
) {
    val usesRaw: Boolean get() = route != CaptureRoute.HAL_PROCESSED
}

class LensRouter(
    private val logicalCameraId: String,
    private val logicalZoomRange: ClosedFloatingPointRange<Float>,
    /** Standalone ultra-wide camera ID, if the device lists one with RAW. */
    private val ultraWideCameraId: String?,
    /** Native zoom of the ultra-wide relative to the main lens (e.g. 0.58). */
    private val ultraWideNativeZoom: Float,
    private val ultraWideZoomRange: ClosedFloatingPointRange<Float>,
    /** Native zoom of the shortest telephoto (e.g. 2.89), or null when there is none. */
    private val firstTelephotoZoom: Float?,
    private val logicalHasRaw: Boolean,
    /** Extra digital zoom Luma adds beyond the logical camera's maximum (e.g. 10 → 100× on a 10× range). */
    private val digitalLimit: Float = 1f,
) {
    val minimumZoom: Float =
        if (ultraWideCameraId != null) minOf(ultraWideNativeZoom, logicalZoomRange.start) else logicalZoomRange.start
    val maximumZoom: Float = logicalZoomRange.endInclusive * digitalLimit.coerceAtLeast(1f)

    /** Zoom at which Luma hands over to the HAL-processed route (5 % below the first telephoto). */
    val halThreshold: Float? = firstTelephotoZoom?.let { it * (1f - SNAP) }

    fun route(requested: Float): RouteDecision {
        val z = requested.coerceIn(minimumZoom, maximumZoom)
        if (z < 1f - SNAP / 2 && ultraWideCameraId != null) {
            val ratio = (z / ultraWideNativeZoom).coerceIn(ultraWideZoomRange.start, ultraWideZoomRange.endInclusive)
            return RouteDecision(CaptureRoute.ULTRA_WIDE_RAW, ultraWideCameraId, ratio, z)
        }
        val threshold = halThreshold
        // Below 1× without a standalone ultra-wide, a RAW session would stay on the main sensor.
        val hal = !logicalHasRaw || (threshold != null && z >= threshold) || z < 1f - SNAP / 2
        val camera = z.coerceIn(logicalZoomRange.start, logicalZoomRange.endInclusive)
        return RouteDecision(
            if (hal) CaptureRoute.HAL_PROCESSED else CaptureRoute.MAIN_RAW,
            logicalCameraId,
            camera,
            z,
            digitalZoom = (z / camera).coerceAtLeast(1f),
        )
    }

    /**
     * Focus Enhancer macro route: the ultra-wide (closest focus) cropped to the requested field of view, so the
     * framing stays the same as on the main lens. Null without a standalone ultra-wide.
     */
    fun macro(requested: Float): RouteDecision? {
        val uw = ultraWideCameraId ?: return null
        val z = requested.coerceIn(minimumZoom, maximumZoom)
        val ratio = (z / ultraWideNativeZoom).coerceIn(ultraWideZoomRange.start, ultraWideZoomRange.endInclusive)
        return RouteDecision(CaptureRoute.ULTRA_WIDE_RAW, uw, ratio, z)
    }

    /** True when moving between the two zooms needs a different session (a camera or stream change). */
    fun needsReconfigure(from: Float, to: Float): Boolean {
        val a = route(from)
        val b = route(to)
        return a.route != b.route || a.cameraId != b.cameraId
    }

    companion object {
        const val SNAP = 0.05f

        fun isNear(a: Float, b: Float): Boolean = abs(a / b - 1f) <= SNAP
    }
}
