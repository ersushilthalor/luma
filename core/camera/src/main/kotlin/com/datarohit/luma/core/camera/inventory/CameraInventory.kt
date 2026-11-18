package com.datarohit.luma.core.camera.inventory

import android.graphics.Rect
import android.util.Range
import android.util.Size
import android.util.SizeF
import kotlin.math.hypot

data class CameraInventory(
    val device: DeviceIdentity,
    val cameras: List<CameraSummary>,
    val rawCharacteristics: Map<String, List<CharacteristicEntry>>,
    val restrictedKeys: Map<String, List<String>>,
)

data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
    val release: String,
    val buildFingerprint: String,
    val securityPatch: String,
)

enum class CameraListing { PUBLIC, HIDDEN_PHYSICAL }

enum class LensFacing { BACK, FRONT, EXTERNAL, UNKNOWN }

data class CameraSummary(
    val id: String,
    val listing: CameraListing,
    val logicalParentIds: List<String>,
    val facing: LensFacing,
    val hardwareLevel: String,
    val capabilities: List<String>,
    val physicalCameraIds: List<String>,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val sensorPhysicalSizeMm: SizeF?,
    val pixelArray: Size?,
    val pixelArrayMaximumResolution: Size?,
    val activeArray: Rect?,
    val binningFactor: Size?,
    val colorFilterArrangement: String?,
    val whiteLevel: Int?,
    val minimumFocusDistanceDiopters: Float?,
    val focusDistanceCalibration: String?,
    val opticalStabilization: Boolean,
    val zoomRatioRange: Range<Float>?,
    val sensitivityRange: Range<Int>?,
    val exposureTimeRangeNs: Range<Long>?,
    val streams: StreamInventory,
    val extensions: List<String>,
) {
    val supportsRaw: Boolean get() = "RAW" in capabilities
    val isLogicalMultiCamera: Boolean get() = physicalCameraIds.isNotEmpty()
    val isUltraHighResolution: Boolean get() = "ULTRA_HIGH_RESOLUTION_SENSOR" in capabilities

    val minimumFocusDistanceMeters: Float?
        get() = minimumFocusDistanceDiopters?.takeIf { it > 0f && focusDistanceCalibration != "UNCALIBRATED" }?.let { 1f / it }

    val equivalentFocalLengthMm: Float?
        get() {
            val focal = focalLengthsMm.minOrNull() ?: return null
            val physical = sensorPhysicalSizeMm ?: return null
            val pixels = pixelArray ?: return null
            val active = activeArray ?: return null
            val activeWidthMm = physical.width * active.width() / pixels.width
            val activeHeightMm = physical.height * active.height() / pixels.height
            val diagonal = hypot(activeWidthMm, activeHeightMm)
            if (diagonal <= 0f) return null
            return focal * FULL_FRAME_DIAGONAL_MM / diagonal
        }

    private companion object {
        const val FULL_FRAME_DIAGONAL_MM = 43.2666f
    }
}

data class StreamInventory(
    val rawSensor: List<Size>,
    val rawSensorMaximumResolution: List<Size>,
    val jpeg: List<Size>,
    val jpegHighResolution: List<Size>,
    val jpegMaximumResolution: List<Size>,
    val yuv: List<Size>,
    val yuvHighResolution: List<Size>,
    val heic: List<Size>,
    val jpegR: List<Size>,
    val outputFormats: List<String>,
)

data class CharacteristicEntry(val key: String, val value: String)
