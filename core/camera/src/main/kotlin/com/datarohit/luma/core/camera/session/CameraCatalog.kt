package com.datarohit.luma.core.camera.session

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import com.datarohit.luma.core.camera.lens.LensCatalog
import com.datarohit.luma.core.camera.lens.LensSpec

/** Cameras Luma opens: the first logical (or plain) back camera and the first front camera. */
class CameraCatalog(private val manager: CameraManager) {

    val back: CameraDescriptor? by lazy { pick(CameraCharacteristics.LENS_FACING_BACK) }
    val front: CameraDescriptor? by lazy { pick(CameraCharacteristics.LENS_FACING_FRONT) }

    /** Lenses behind the back camera (physical cameras of the logical camera, or the camera itself). */
    val backLenses: LensCatalog? by lazy {
        val d = back ?: return@lazy null
        val ids = d.physicalIds.ifEmpty { listOf(d.cameraId) }
        val specs = ids.mapNotNull { id -> runCatching { lensSpec(d.cameraId, id.takeIf { d.physicalIds.isNotEmpty() }, manager.getCameraCharacteristics(id)) }.getOrNull() }
        specs.takeIf { it.isNotEmpty() }?.let(::LensCatalog)
    }

    fun characteristics(id: String): CameraCharacteristics = manager.getCameraCharacteristics(id)

    /**
     * Public back cameras other than [back] that support RAW (on the S26 Ultra: the ultra-wide, ID 2).
     * Opening one directly gives full 3A on that lens, unlike a physical stream of the logical camera.
     */
    val standaloneBack: List<CameraDescriptor> by lazy {
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        ids.filter { it != back?.cameraId }.mapNotNull { id ->
            val c = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: return@mapNotNull null
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            describe(id, c)?.takeIf { it.rawSize != null }
        }
    }

    /** 35 mm equivalent focal length of a camera (diagonal), or null when the metadata is incomplete. */
    fun equivalentFocalMm(id: String): Float? = runCatching {
        val c = manager.getCameraCharacteristics(id)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return@runCatching null
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@runCatching null
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return@runCatching null
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return@runCatching null
        val w = physical.width * active.width() / pixels.width
        val h = physical.height * active.height() / pixels.height
        focal * 43.2666f / kotlin.math.hypot(w, h)
    }.getOrNull()

    fun aperture(id: String): Float? = runCatching {
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()
    }.getOrNull()

    /** Descriptor for any camera ID (public or a physical camera ID), or null if it cannot be described. */
    fun descriptorFor(id: String): CameraDescriptor? = runCatching { describe(id, manager.getCameraCharacteristics(id)) }.getOrNull()

    private fun pick(facing: Int): CameraDescriptor? {
        val ids = runCatching { manager.cameraIdList.toList() }.getOrDefault(emptyList())
        val candidates = ids.mapNotNull { id ->
            runCatching { id to manager.getCameraCharacteristics(id) }.getOrNull()
        }.filter { (_, c) -> c.get(CameraCharacteristics.LENS_FACING) == facing }
        val (id, chars) = candidates.firstOrNull { (_, c) -> c.physicalCameraIds.isNotEmpty() }
            ?: candidates.firstOrNull() ?: return null
        return describe(id, chars)
    }

    private fun describe(id: String, c: CameraCharacteristics): CameraDescriptor? {
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val preview = map.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
            ?.maxByOrNull { it.width * it.height } ?: Size(1440, 1080)
        val analysis = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width * 3 == it.height * 4 && it.width <= 640 }
            ?.maxByOrNull { it.width * it.height } ?: Size(640, 480)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val raw = if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW in caps)
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height } else null
        var cct = false
        var cctRange: Range<Int>? = null
        var isoPriority = false
        var shutterPriority = false
        if (Build.VERSION.SDK_INT >= 36) {
            cct = c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES)?.contains(CameraMetadata.COLOR_CORRECTION_MODE_CCT) == true
            cctRange = c.get(CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE)
            val priorities = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)?.toSet().orEmpty()
            isoPriority = CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY in priorities
            shutterPriority = CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY in priorities
        }
        return CameraDescriptor(
            cameraId = id,
            facingBack = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK,
            characteristics = c,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, preview.width, preview.height),
            zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f),
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            compensationRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0),
            compensationStep = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f,
            minFocusDiopters = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            physicalIds = c.physicalCameraIds.sorted(),
            previewSize = preview,
            analysisSize = analysis,
            rawSize = raw,
            supportsCctWhiteBalance = cct,
            supportsIsoPriority = isoPriority,
            supportsShutterPriority = shutterPriority,
            supportsManualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps,
            cctRange = cctRange,
            hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
        )
    }

    private fun lensSpec(logicalId: String, physicalId: String?, c: CameraCharacteristics): LensSpec? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val pixels = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val raw = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
        val minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.takeIf { it > 0f }
        return LensSpec(
            cameraId = logicalId,
            physicalId = physicalId,
            focalLengthMm = focal,
            sensorWidthMm = physical.width,
            sensorHeightMm = physical.height,
            pixelArrayWidth = pixels.width,
            pixelArrayHeight = pixels.height,
            activeWidth = active.width(),
            activeHeight = active.height(),
            aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f,
            minimumFocusDistanceM = minFocus?.let { 1f / it },
            hasOis = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            rawWidth = raw?.width ?: 0,
            rawHeight = raw?.height ?: 0,
        )
    }
}
