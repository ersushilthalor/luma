package com.datarohit.luma.core.camera.session

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import android.util.Size
import java.nio.ByteBuffer

/** What a capture session streams. One RAW stream per session (verified device limit). */
data class SessionSpec(
    val cameraId: String,
    val previewSize: Size,
    val analysisSize: Size,
    val rawSize: Size?,
    /** Bind the RAW stream to this physical camera instead of the logical camera's active one. */
    val rawPhysicalId: String? = null,
    /** HAL-processed JPEG still stream (used where Luma cannot get RAW from the right lens). */
    val jpegSize: Size? = null,
    /** [jpegSize] comes from the sensor's maximum-resolution stream map (ULTRA_HIGH_RESOLUTION_SENSOR). */
    val jpegMaxResolution: Boolean = false,
)

/** A HAL-processed still (JPEG bytes as produced by the camera HAL). */
class StillCapture(val jpeg: ByteArray, val result: TotalCaptureResult, val width: Int, val height: Int)

enum class FocusMode { CONTINUOUS, LOCKED_AREA, MANUAL }

enum class FlashMode { OFF, TORCH }

/** Normalised point in the preview (0..1 in both axes, upright display orientation). */
data class ViewPoint(val x: Float, val y: Float)

/** Live controls applied to the repeating preview request. */
data class Controls(
    val zoomRatio: Float = 1f,
    val focusMode: FocusMode = FocusMode.CONTINUOUS,
    val focusPoint: ViewPoint? = null,
    /** Manual focus in diopters (0 = infinity), used with [FocusMode.MANUAL]. */
    val focusDiopters: Float = 0f,
    val exposureCompensation: Int = 0,
    /** Manual exposure: both set = AE off. */
    val manualExposureNs: Long? = null,
    val manualIso: Int? = null,
    /** Kelvin white balance (null = auto). */
    val whiteBalanceKelvin: Int? = null,
    /** Green(-) / magenta(+) tint with Kelvin WB, -50..50 (API 36 COLOR_CORRECTION_COLOR_TINT). */
    val whiteBalanceTint: Int = 0,
    val flash: FlashMode = FlashMode.OFF,
    val aeLock: Boolean = false,
    /** Preview stabilisation (viewfinder only; stills are not cropped by it). */
    val stabilizePreview: Boolean = false,
    /** HAL post-processing: Luma's neutral scene-log stream, or the camera's own look. */
    val isp: IspMode = IspMode.NEUTRAL_LOG,
)

/** Static description of an openable camera (a logical back camera or a front camera). */
data class CameraDescriptor(
    val cameraId: String,
    val facingBack: Boolean,
    val characteristics: CameraCharacteristics,
    val sensorOrientation: Int,
    val activeArray: Rect,
    val zoomRange: Range<Float>,
    val isoRange: Range<Int>?,
    val exposureRangeNs: Range<Long>?,
    val compensationRange: Range<Int>,
    val compensationStep: Float,
    val minFocusDiopters: Float,
    val physicalIds: List<String>,
    val previewSize: Size,
    val analysisSize: Size,
    val rawSize: Size?,
    val supportsCctWhiteBalance: Boolean,
    /** API 36 CONTROL_AE_PRIORITY_MODE: ISO priority and shutter priority supported by the HAL. */
    val supportsIsoPriority: Boolean,
    val supportsShutterPriority: Boolean,
    val supportsManualSensor: Boolean,
    val cctRange: Range<Int>?,
    val hasFlash: Boolean,
)

/** Summary of the latest preview result, for UI badges and exposure planning. */
data class FrameInfo(
    val activePhysicalId: String?,
    val zoomRatio: Float,
    val exposureNs: Long,
    val iso: Int,
    val frameDurationNs: Long,
    val focusDiopters: Float,
    val afState: Int,
    val aeState: Int,
    val physicalCrop: Rect?,
    val aperture: Float,
    val focalLengthMm: Float,
)

/** Preview luminance statistics from the analysis stream (Y plane). */
data class AnalysisStats(
    val histogram: IntArray,
    /** Fraction of pixels at or above 98 % of full scale. */
    val clippedFraction: Float,
    /** Fraction below 3 % of full scale. */
    val shadowFraction: Float,
    /** Strong-edge map for focus peaking, [peakingWidth] x [peakingHeight], in sensor orientation. */
    val peaking: BooleanArray,
    val peakingWidth: Int,
    val peakingHeight: Int,
    val meanLuma: Float,
    /** Relative frame-to-frame change of the downsampled luma (0 = static). */
    val motion: Float,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Capture plan for one burst. */
data class BurstPlan(
    val frames: Int,
    /** Manual exposure per frame; null = keep auto exposure (locked). */
    val exposureNs: Long?,
    val iso: Int?,
    val keepAf: Boolean = true,
)

/** One RAW frame delivered during a burst. The buffer is only valid inside the callback. */
class RawFrame(
    val index: Int,
    val buffer: ByteBuffer,
    val rowStride: Int,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val result: TotalCaptureResult,
    /** Physical camera that produced the RAW (the logical camera's active one, or the bound physical stream). */
    val sourceCameraId: String,
    val image: android.media.Image,
)

sealed interface CameraState {
    data object Closed : CameraState
    data object Opening : CameraState
    data class Streaming(val spec: SessionSpec) : CameraState
    data class Failed(val message: String) : CameraState
}
