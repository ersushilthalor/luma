package com.datarohit.luma.core.camera.lens

import com.datarohit.luma.core.camera.inventory.CameraInventory
import com.datarohit.luma.core.camera.inventory.CameraSummary
import com.datarohit.luma.core.camera.inventory.LensFacing

/**
 * Back-facing lenses reachable through the first back logical camera (one entry per physical camera),
 * or the public back cameras when the device has no logical camera.
 */
fun CameraInventory.backLensSpecs(): List<LensSpec> {
    val byId = cameras.associateBy { it.id }
    val logical = cameras.firstOrNull { it.facing == LensFacing.BACK && it.isLogicalMultiCamera }
    val members = logical?.physicalCameraIds
        ?.mapNotNull { byId[it] }
        ?.mapNotNull { it.toLensSpec(cameraId = logical.id, physicalId = it.id) }
    if (!members.isNullOrEmpty()) return members
    return cameras
        .filter { it.facing == LensFacing.BACK && !it.isLogicalMultiCamera }
        .mapNotNull { it.toLensSpec(cameraId = it.id, physicalId = null) }
}

private fun CameraSummary.toLensSpec(cameraId: String, physicalId: String?): LensSpec? {
    val focal = focalLengthsMm.minOrNull() ?: return null
    val physical = sensorPhysicalSizeMm ?: return null
    val pixels = pixelArray ?: return null
    val active = activeArray ?: return null
    val raw = streams.rawSensor.firstOrNull()
    val maxResRaw = streams.rawSensorMaximumResolution.firstOrNull()
    return LensSpec(
        cameraId = cameraId,
        physicalId = physicalId,
        focalLengthMm = focal,
        sensorWidthMm = physical.width,
        sensorHeightMm = physical.height,
        pixelArrayWidth = pixels.width,
        pixelArrayHeight = pixels.height,
        activeWidth = active.width(),
        activeHeight = active.height(),
        aperture = apertures.firstOrNull() ?: 0f,
        minimumFocusDistanceM = minimumFocusDistanceMeters,
        hasOis = opticalStabilization,
        rawWidth = raw?.width ?: 0,
        rawHeight = raw?.height ?: 0,
        maxResolutionRawWidth = maxResRaw?.width ?: 0,
        maxResolutionRawHeight = maxResRaw?.height ?: 0,
    )
}
