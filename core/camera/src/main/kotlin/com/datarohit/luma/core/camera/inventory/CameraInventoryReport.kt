package com.datarohit.luma.core.camera.inventory

import android.util.Size
import java.util.Locale

object CameraInventoryReport {

    fun render(inventory: CameraInventory): String = buildString {
        val device = inventory.device
        appendLine("# Luma camera inventory")
        appendLine("device: ${device.manufacturer} ${device.model} (${device.device})")
        appendLine("android: ${device.release} / API ${device.sdkInt} / patch ${device.securityPatch}")
        appendLine("build: ${device.buildFingerprint}")
        appendLine()

        inventory.cameras.forEach { camera ->
            appendLine("## camera ${camera.id} [${camera.listing}] ${camera.facing}")
            if (camera.logicalParentIds.isNotEmpty()) appendLine("logical parents: ${camera.logicalParentIds}")
            if (camera.isLogicalMultiCamera) appendLine("physical cameras: ${camera.physicalCameraIds}")
            appendLine("hardware level: ${camera.hardwareLevel}")
            appendLine("capabilities: ${camera.capabilities.joinToString()}")
            appendLine("focal lengths mm: ${camera.focalLengthsMm}  apertures: ${camera.apertures}")
            camera.equivalentFocalLengthMm?.let { appendLine("35mm-equivalent focal length: %.1f mm".format(Locale.ROOT, it)) }
            appendLine("sensor physical size mm: ${camera.sensorPhysicalSizeMm}")
            appendLine("pixel array: ${camera.pixelArray.describe()}  max-resolution pixel array: ${camera.pixelArrayMaximumResolution.describe()}")
            appendLine("active array: ${camera.activeArray}  binning factor: ${camera.binningFactor}")
            appendLine("CFA: ${camera.colorFilterArrangement}  white level: ${camera.whiteLevel}")
            appendLine(
                "minimum focus: ${camera.minimumFocusDistanceDiopters} dpt " +
                    "(${camera.minimumFocusDistanceMeters?.let { "%.3f m".format(Locale.ROOT, it) } ?: "n/a"}) " +
                    "calibration: ${camera.focusDistanceCalibration}",
            )
            appendLine("OIS: ${camera.opticalStabilization}  zoom ratio range: ${camera.zoomRatioRange}")
            appendLine("ISO range: ${camera.sensitivityRange}  exposure range ns: ${camera.exposureTimeRangeNs}")
            appendLine("output formats: ${camera.streams.outputFormats.joinToString()}")
            appendLine("RAW_SENSOR: ${camera.streams.rawSensor.describe()}")
            appendLine("RAW_SENSOR (max-resolution mode): ${camera.streams.rawSensorMaximumResolution.describe()}")
            appendLine("JPEG: ${camera.streams.jpeg.take(TOP_SIZES).describe()}")
            appendLine("JPEG high-resolution: ${camera.streams.jpegHighResolution.describe()}")
            appendLine("JPEG (max-resolution mode): ${camera.streams.jpegMaximumResolution.describe()}")
            appendLine("YUV_420_888: ${camera.streams.yuv.take(TOP_SIZES).describe()}")
            appendLine("YUV_420_888 high-resolution: ${camera.streams.yuvHighResolution.describe()}")
            appendLine("HEIC: ${camera.streams.heic.take(TOP_SIZES).describe()}")
            appendLine("JPEG_R: ${camera.streams.jpegR.take(TOP_SIZES).describe()}")
            appendLine("extensions: ${camera.extensions.ifEmpty { listOf("none") }.joinToString()}")
            appendLine()
        }

        appendLine("# Characteristics withheld without CAMERA permission")
        inventory.restrictedKeys.forEach { (id, keys) -> appendLine("$id: ${keys.size} keys") }
        appendLine()

        appendLine("# Full characteristics")
        inventory.rawCharacteristics.forEach { (id, entries) ->
            appendLine("## camera $id")
            entries.forEach { appendLine("${it.key} = ${it.value}") }
            appendLine()
        }
    }

    private const val TOP_SIZES = 4

    private fun Size?.describe(): String =
        this?.let { "${it.width}x${it.height} (%.1f MP)".format(Locale.ROOT, it.width.toLong() * it.height / 1_000_000.0) } ?: "n/a"

    private fun List<Size>.describe(): String = if (isEmpty()) "none" else joinToString { it.describe() }
}
