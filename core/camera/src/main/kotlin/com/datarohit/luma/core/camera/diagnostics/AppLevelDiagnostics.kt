package com.datarohit.luma.core.camera.diagnostics

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Size
import com.datarohit.luma.core.camera.inventory.capabilityName
import com.datarohit.luma.core.camera.inventory.extensionName
import com.datarohit.luma.core.camera.inventory.formatValue
import com.datarohit.luma.core.camera.inventory.hardwareLevelName
import com.datarohit.luma.core.camera.inventory.imageFormatName
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Phase A of the hardware validation plan: what an ordinary app sees, without opening any camera.
 * Stream-combination support is queried through [CameraManager.getCameraDeviceSetup] (API 35), which
 * does not open the device.
 */
class AppLevelDiagnostics(private val manager: CameraManager) {

    suspend fun collect(): JSONObject {
        val publicIds = manager.cameraIdList.toList()
        val characteristics = linkedMapOf<String, CameraCharacteristics>()
        publicIds.forEach { characteristics[it] = manager.getCameraCharacteristics(it) }
        publicIds.forEach { id ->
            characteristics.getValue(id).physicalCameraIds.forEach { physical ->
                if (physical !in characteristics) {
                    runCatching { manager.getCameraCharacteristics(physical) }.onSuccess { characteristics[physical] = it }
                }
            }
        }

        return JSONObject()
            .put("schema", 1)
            .put("phase", "A")
            .put("generatedAt", Instant.now().toString())
            .put("device", deviceJson())
            .put("cameraIdList", JSONArray(publicIds))
            .put("cameras", JSONObject().apply {
                characteristics.forEach { (id, chars) -> put(id, cameraJson(id, chars, id in publicIds)) }
            })
            .put("concurrentCameraIds", JSONArray(manager.concurrentCameraIds.map { JSONArray(it.sorted()) }))
            .put("sessionQueries", JSONObject().apply {
                publicIds.forEach { id -> put(id, sessionQueries(id, characteristics)) }
            })
            .put("availabilityEvents", availabilityEvents())
    }

    private fun cameraJson(id: String, chars: CameraCharacteristics, listed: Boolean): JSONObject {
        val keyNames = chars.keys.map { it.name }
        return JSONObject()
            .put("listedInCameraIdList", listed)
            .put("facing", chars.get(CameraCharacteristics.LENS_FACING))
            .put("hardwareLevel", hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
            .put("capabilities", JSONArray(chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.map(::capabilityName).orEmpty()))
            .put("physicalCameraIds", JSONArray(chars.physicalCameraIds.sorted()))
            .put("keysNeedingPermission", keyNames(chars.keysNeedingPermission))
            .put("vendorCharacteristicKeys", JSONArray(keyNames.filterNot { it.startsWith("android.") }.sorted()))
            .put("requestKeys", keyNames(chars.availableCaptureRequestKeys))
            .put("resultKeys", keyNames(chars.availableCaptureResultKeys))
            .put("sessionKeys", keyNames(chars.availableSessionKeys))
            .put("physicalRequestKeys", keyNames(chars.availablePhysicalCameraRequestKeys))
            .put("sessionConfigurationQueryVersion", runCatching {
                if (Build.VERSION.SDK_INT >= 35) chars.get(CameraCharacteristics.INFO_SESSION_CONFIGURATION_QUERY_VERSION) else null
            }.getOrNull())
            .put("streams", streamsJson(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)))
            .put("streamsMaximumResolution", streamsJson(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)))
            .put("extensions", extensionsJson(id))
            .put("characteristics", JSONObject().apply {
                chars.keys.sortedBy { it.name }.forEach { key -> put(key.name, formatValue(chars.get(key))) }
            })
    }

    private fun streamsJson(map: StreamConfigurationMap?): JSONObject {
        val out = JSONObject()
        if (map == null) return out
        map.outputFormats.forEach { format ->
            val sizes = JSONArray()
            map.getOutputSizes(format)?.sortedByDescending { it.width.toLong() * it.height }?.forEach { size ->
                sizes.put(sizeJson(size, map.getOutputMinFrameDuration(format, size), map.getOutputStallDuration(format, size)))
            }
            val highRes = JSONArray()
            map.getHighResolutionOutputSizes(format)?.forEach { size ->
                highRes.put(sizeJson(size, map.getOutputMinFrameDuration(format, size), map.getOutputStallDuration(format, size)))
            }
            out.put(imageFormatName(format), JSONObject().put("sizes", sizes).put("highResolutionSizes", highRes))
        }
        return out
    }

    private fun sizeJson(size: Size, minFrameNs: Long, stallNs: Long) =
        JSONObject().put("w", size.width).put("h", size.height).put("minFrameNs", minFrameNs).put("stallNs", stallNs)

    private fun extensionsJson(id: String): JSONObject {
        val out = JSONObject()
        val ext = runCatching { manager.getCameraExtensionCharacteristics(id) }
            .getOrElse { return out.put("error", it.toString()) }
        ext.supportedExtensions.forEach { extension ->
            val entry = JSONObject()
            fun attempt(name: String, block: () -> Any?) {
                entry.put(name, runCatching { block() }.getOrElse { "error: $it" })
            }
            attempt("jpegSizes") { JSONArray(ext.getExtensionSupportedSizes(extension, ImageFormat.JPEG).map { "${it.width}x${it.height}" }) }
            attempt("yuvSizes") { JSONArray(ext.getExtensionSupportedSizes(extension, ImageFormat.YUV_420_888).map { "${it.width}x${it.height}" }) }
            attempt("jpegRSizes") { JSONArray(ext.getExtensionSupportedSizes(extension, ImageFormat.JPEG_R).map { "${it.width}x${it.height}" }) }
            attempt("previewSizes") { JSONArray(ext.getExtensionSupportedSizes(extension, SurfaceTexture::class.java).map { "${it.width}x${it.height}" }) }
            attempt("postviewAvailable") { ext.isPostviewAvailable(extension) }
            attempt("captureProgressAvailable") { ext.isCaptureProcessProgressAvailable(extension) }
            attempt("requestKeys") { JSONArray(ext.getAvailableCaptureRequestKeys(extension).map { it.name }.sorted()) }
            attempt("resultKeys") { JSONArray(ext.getAvailableCaptureResultKeys(extension).map { it.name }.sorted()) }
            if (Build.VERSION.SDK_INT >= 35) attempt("characteristicKeys") { JSONArray(ext.getKeys(extension).map { it.name }.sorted()) }
            out.put(extensionName(extension), entry)
        }
        return out
    }

    private fun sessionQueries(id: String, all: Map<String, CameraCharacteristics>): JSONObject {
        val out = JSONObject()
        if (Build.VERSION.SDK_INT < 35) return out.put("skipped", "API < 35")
        val supported = runCatching { manager.isCameraDeviceSetupSupported(id) }.getOrElse { return out.put("error", it.toString()) }
        out.put("cameraDeviceSetupSupported", supported)
        if (!supported) return out
        val setup = manager.getCameraDeviceSetup(id)
        val chars = all.getValue(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return out
        val preview = previewSize(map)
        val analysis = smallYuv(map)
        val raw = map.largest(ImageFormat.RAW_SENSOR)
        val jpeg = map.largest(ImageFormat.JPEG)
        val jpegR = map.largest(ImageFormat.JPEG_R)

        val queries = linkedMapOf<String, List<OutputConfiguration>>()
        fun output(format: Int, size: Size?, physicalId: String? = null): OutputConfiguration? =
            size?.let { OutputConfiguration(format, it).apply { if (physicalId != null) setPhysicalCameraId(physicalId) } }

        fun add(name: String, vararg outputs: OutputConfiguration?) {
            if (outputs.all { it != null }) queries[name] = outputs.filterNotNull()
        }

        add("preview", output(ImageFormat.PRIVATE, preview))
        add("preview+raw", output(ImageFormat.PRIVATE, preview), output(ImageFormat.RAW_SENSOR, raw))
        add("preview+yuv+raw", output(ImageFormat.PRIVATE, preview), output(ImageFormat.YUV_420_888, analysis), output(ImageFormat.RAW_SENSOR, raw))
        add("preview+jpeg", output(ImageFormat.PRIVATE, preview), output(ImageFormat.JPEG, jpeg))
        add("preview+yuv+jpeg", output(ImageFormat.PRIVATE, preview), output(ImageFormat.YUV_420_888, analysis), output(ImageFormat.JPEG, jpeg))
        add("preview+raw+jpeg", output(ImageFormat.PRIVATE, preview), output(ImageFormat.RAW_SENSOR, raw), output(ImageFormat.JPEG, jpeg))
        add("preview+yuv+raw+jpeg", output(ImageFormat.PRIVATE, preview), output(ImageFormat.YUV_420_888, analysis),
            output(ImageFormat.RAW_SENSOR, raw), output(ImageFormat.JPEG, jpeg))
        add("preview+jpegR", output(ImageFormat.PRIVATE, preview), output(ImageFormat.JPEG_R, jpegR))

        val physicalIds = chars.physicalCameraIds.sorted()
        physicalIds.forEach { physical ->
            val physicalMap = all[physical]?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            add("preview+raw@$physical", output(ImageFormat.PRIVATE, preview),
                output(ImageFormat.RAW_SENSOR, physicalMap?.largest(ImageFormat.RAW_SENSOR), physical))
            add("preview+yuv@$physical", output(ImageFormat.PRIVATE, preview),
                output(ImageFormat.YUV_420_888, physicalMap?.let(::smallYuv), physical))
        }
        physicalIds.zipWithNext().forEach { (a, b) ->
            val mapA = all[a]?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val mapB = all[b]?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            add("yuv@$a+yuv@$b", output(ImageFormat.YUV_420_888, mapA?.let(::smallYuv), a),
                output(ImageFormat.YUV_420_888, mapB?.let(::smallYuv), b))
        }

        val results = JSONObject()
        queries.forEach { (name, outputs) ->
            val outcome = try {
                setup.isSessionConfigurationSupported(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs))
            } catch (e: IllegalArgumentException) {
                "illegal: ${e.message}"
            } catch (e: UnsupportedOperationException) {
                "unsupported query: ${e.message}"
            } catch (e: CameraAccessException) {
                "camera access: ${e.message}"
            }
            results.put(name, JSONObject()
                .put("supported", outcome)
                .put("outputs", JSONArray(outputs.map { it.toString() })))
        }
        return out.put("previewSize", preview?.toString()).put("analysisSize", analysis?.toString()).put("queries", results)
    }

    private suspend fun availabilityEvents(): JSONArray {
        val events = JSONArray()
        val executor = Executors.newSingleThreadExecutor()
        val callback = object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) { synchronized(events) { events.put("available:$cameraId") } }
            override fun onCameraUnavailable(cameraId: String) { synchronized(events) { events.put("unavailable:$cameraId") } }
        }
        manager.registerAvailabilityCallback(executor, callback)
        delay(AVAILABILITY_WINDOW_MS)
        manager.unregisterAvailabilityCallback(callback)
        executor.shutdown()
        return synchronized(events) { JSONArray(events.toString()) }
    }

    private fun deviceJson() = JSONObject()
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("device", Build.DEVICE)
        .put("sdkInt", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE)
        .put("incremental", Build.VERSION.INCREMENTAL)
        .put("securityPatch", Build.VERSION.SECURITY_PATCH)

    private companion object {
        const val AVAILABILITY_WINDOW_MS = 750L
    }
}

internal fun StreamConfigurationMap.largest(format: Int): Size? =
    takeIf { format in outputFormats }?.getOutputSizes(format)?.maxByOrNull { it.width.toLong() * it.height }

internal fun previewSize(map: StreamConfigurationMap): Size? =
    map.getOutputSizes(ImageFormat.PRIVATE)
        ?.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
        ?.maxByOrNull { it.width }

internal fun smallYuv(map: StreamConfigurationMap): Size? {
    val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return null
    return sizes.firstOrNull { it.width == 640 && it.height == 480 }
        ?: sizes.filter { it.width * 3 == it.height * 4 }.minByOrNull { it.width }
}

/** Camera2 key-list getters are documented non-null but some HALs return null (seen on SM-S948B). */
private fun keyNames(keys: List<*>?): JSONArray = JSONArray(
    keys.orEmpty().mapNotNull {
        when (it) {
            is CaptureRequest.Key<*> -> it.name
            is CaptureResult.Key<*> -> it.name
            is CameraCharacteristics.Key<*> -> it.name
            else -> null
        }
    }.sorted(),
)
