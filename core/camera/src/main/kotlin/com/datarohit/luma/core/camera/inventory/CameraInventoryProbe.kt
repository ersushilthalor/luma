package com.datarohit.luma.core.camera.inventory

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size

/**
 * Read-only enumeration of every camera the framework exposes, including physical cameras that are
 * hidden from [CameraManager.getCameraIdList] but reachable through a logical camera.
 *
 * Without the CAMERA permission the framework withholds the keys reported in
 * [CameraInventory.restrictedKeys]; request the permission first for a complete inventory.
 */
class CameraInventoryProbe(private val cameraManager: CameraManager) {

    fun collect(): CameraInventory {
        val publicIds = cameraManager.cameraIdList.toList()
        val characteristics = linkedMapOf<String, CameraCharacteristics>()
        publicIds.forEach { id -> characteristics[id] = cameraManager.getCameraCharacteristics(id) }

        val parents = linkedMapOf<String, MutableList<String>>()
        publicIds.forEach { logicalId ->
            characteristics.getValue(logicalId).physicalCameraIds.forEach { physicalId ->
                parents.getOrPut(physicalId) { mutableListOf() } += logicalId
                if (physicalId !in characteristics) {
                    runCatching { cameraManager.getCameraCharacteristics(physicalId) }
                        .onSuccess { characteristics[physicalId] = it }
                }
            }
        }

        val cameras = characteristics.map { (id, chars) ->
            summarize(
                id = id,
                chars = chars,
                listing = if (id in publicIds) CameraListing.PUBLIC else CameraListing.HIDDEN_PHYSICAL,
                parents = parents[id].orEmpty(),
            )
        }

        return CameraInventory(
            device = DeviceIdentity(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                device = Build.DEVICE,
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE,
                buildFingerprint = Build.FINGERPRINT,
                securityPatch = Build.VERSION.SECURITY_PATCH,
            ),
            cameras = cameras,
            rawCharacteristics = characteristics.mapValues { (_, chars) -> dumpAll(chars) },
            restrictedKeys = characteristics.mapValues { (_, chars) ->
                chars.keysNeedingPermission.map { it.name }.sorted()
            },
        )
    }

    private fun summarize(
        id: String,
        chars: CameraCharacteristics,
        listing: CameraListing,
        parents: List<String>,
    ): CameraSummary {
        val defaultMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)

        return CameraSummary(
            id = id,
            listing = listing,
            logicalParentIds = parents,
            facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
                CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraMetadata.LENS_FACING_EXTERNAL -> LensFacing.EXTERNAL
                else -> LensFacing.UNKNOWN
            },
            hardwareLevel = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.map(::capabilityName).orEmpty(),
            physicalCameraIds = chars.physicalCameraIds.sorted(),
            focalLengthsMm = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList().orEmpty(),
            sensorPhysicalSizeMm = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE),
            pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE),
            pixelArrayMaximumResolution = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION),
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
            binningFactor = chars.get(CameraCharacteristics.SENSOR_INFO_BINNING_FACTOR),
            colorFilterArrangement = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
                ?.let(::colorFilterName),
            whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL),
            minimumFocusDistanceDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
            focusDistanceCalibration = chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
                ?.let(::focusCalibrationName),
            opticalStabilization = ois?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            zoomRatioRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
            sensitivityRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureTimeRangeNs = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            streams = StreamInventory(
                rawSensor = defaultMap.sizes(ImageFormat.RAW_SENSOR),
                rawSensorMaximumResolution = maxResMap.sizes(ImageFormat.RAW_SENSOR),
                jpeg = defaultMap.sizes(ImageFormat.JPEG),
                jpegHighResolution = defaultMap.highResolutionSizes(ImageFormat.JPEG),
                jpegMaximumResolution = maxResMap.sizes(ImageFormat.JPEG),
                yuv = defaultMap.sizes(ImageFormat.YUV_420_888),
                yuvHighResolution = defaultMap.highResolutionSizes(ImageFormat.YUV_420_888),
                heic = defaultMap.sizes(ImageFormat.HEIC),
                jpegR = defaultMap.sizes(ImageFormat.JPEG_R),
                outputFormats = defaultMap?.outputFormats?.map(::imageFormatName).orEmpty(),
            ),
            extensions = supportedExtensions(id),
        )
    }

    private fun supportedExtensions(id: String): List<String> =
        runCatching { cameraManager.getCameraExtensionCharacteristics(id).supportedExtensions }
            .getOrDefault(emptyList())
            .map(::extensionName)

    private fun dumpAll(chars: CameraCharacteristics): List<CharacteristicEntry> =
        chars.keys
            .map { key -> CharacteristicEntry(key.name, formatValue(chars.get(key))) }
            .sortedBy { it.key }

    private fun StreamConfigurationMap?.sizes(format: Int): List<Size> =
        this?.takeIf { format in it.outputFormats }?.getOutputSizes(format)?.sortedByArea().orEmpty()

    private fun StreamConfigurationMap?.highResolutionSizes(format: Int): List<Size> =
        this?.takeIf { format in it.outputFormats }?.getHighResolutionOutputSizes(format)?.sortedByArea().orEmpty()

    private fun Array<Size>.sortedByArea(): List<Size> = sortedByDescending { it.width.toLong() * it.height }
}

internal fun hardwareLevelName(level: Int?): String = when (level) {
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    null -> "UNREPORTED"
    else -> "UNKNOWN($level)"
}

internal fun capabilityName(capability: Int): String = when (capability) {
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_REMOSAIC_REPROCESSING -> "REMOSAIC_REPROCESSING"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT -> "DYNAMIC_RANGE_TEN_BIT"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_STREAM_USE_CASE -> "STREAM_USE_CASE"
    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_COLOR_SPACE_PROFILES -> "COLOR_SPACE_PROFILES"
    else -> "CAPABILITY_$capability"
}

internal fun colorFilterName(arrangement: Int): String = when (arrangement) {
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> "RGGB"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> "GRBG"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> "GBRG"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> "BGGR"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB -> "RGB"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO -> "MONO"
    CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR -> "NIR"
    else -> "CFA_$arrangement"
}

internal fun focusCalibrationName(calibration: Int): String = when (calibration) {
    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED -> "UNCALIBRATED"
    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_APPROXIMATE -> "APPROXIMATE"
    CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED -> "CALIBRATED"
    else -> "CALIBRATION_$calibration"
}

internal fun extensionName(extension: Int): String = when (extension) {
    CameraExtensionCharacteristics.EXTENSION_AUTOMATIC -> "AUTOMATIC"
    CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH -> "FACE_RETOUCH"
    CameraExtensionCharacteristics.EXTENSION_BOKEH -> "BOKEH"
    CameraExtensionCharacteristics.EXTENSION_HDR -> "HDR"
    CameraExtensionCharacteristics.EXTENSION_NIGHT -> "NIGHT"
    else -> "EXTENSION_$extension"
}

internal fun imageFormatName(format: Int): String = when (format) {
    ImageFormat.RAW_SENSOR -> "RAW_SENSOR"
    ImageFormat.RAW_PRIVATE -> "RAW_PRIVATE"
    ImageFormat.RAW10 -> "RAW10"
    ImageFormat.RAW12 -> "RAW12"
    ImageFormat.JPEG -> "JPEG"
    ImageFormat.JPEG_R -> "JPEG_R"
    ImageFormat.HEIC -> "HEIC"
    ImageFormat.YUV_420_888 -> "YUV_420_888"
    ImageFormat.YCBCR_P010 -> "YCBCR_P010"
    ImageFormat.PRIVATE -> "PRIVATE"
    ImageFormat.Y8 -> "Y8"
    ImageFormat.DEPTH16 -> "DEPTH16"
    ImageFormat.DEPTH_JPEG -> "DEPTH_JPEG"
    ImageFormat.DEPTH_POINT_CLOUD -> "DEPTH_POINT_CLOUD"
    else -> "0x" + format.toString(16)
}

internal fun formatValue(value: Any?): String = when (value) {
    null -> "null"
    is IntArray -> value.contentToString()
    is LongArray -> value.contentToString()
    is FloatArray -> value.contentToString()
    is DoubleArray -> value.contentToString()
    is BooleanArray -> value.contentToString()
    is ByteArray -> value.contentToString()
    is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
    is Collection<*> -> value.joinToString(prefix = "[", postfix = "]") { formatValue(it) }
    else -> value.toString()
}
