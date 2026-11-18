package com.datarohit.luma.diagnostics

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureResult
import android.view.Surface
import com.datarohit.luma.capture.CaptureMode
import com.datarohit.luma.capture.CapturePlanner
import com.datarohit.luma.capture.CaptureProcessor
import com.datarohit.luma.capture.CaptureRequestSpec
import com.datarohit.luma.capture.PlanInput
import com.datarohit.luma.core.camera.session.CameraCatalog
import com.datarohit.luma.core.camera.session.CameraController
import com.datarohit.luma.core.camera.session.CameraState
import com.datarohit.luma.core.camera.session.Controls
import com.datarohit.luma.core.camera.session.SessionSpec
import com.datarohit.luma.core.storage.PrivateDirectorySink
import com.datarohit.luma.settings.AppSettings
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug-only end-to-end capture validation: runs real bursts through the full Luma pipeline and
 * writes JPEG/DNG output to the app-private `files/diagnostics/captures/` directory only.
 */
class CaptureTestRunner(
    private val manager: CameraManager,
    private val outputDir: File,
    private val log: (String) -> Unit,
) {

    data class Case(
        val name: String, val mode: CaptureMode, val zoom: Float, val physicalId: String?, val dng: Boolean = false,
        val resolution: com.datarohit.luma.settings.PhotoResolution = com.datarohit.luma.settings.PhotoResolution.MP12,
    )

    /** [superRes]: only the main lens at 1x, 12MP baseline then 24 / 50 / 200MP, with centre crops saved as PNG. */
    suspend fun run(superRes: Boolean = false): JSONObject {
        val catalog = CameraCatalog(manager)
        val back = requireNotNull(catalog.back) { "no back camera" }
        val lenses = catalog.backLenses
        val controller = CameraController(manager)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(back.previewSize.width, back.previewSize.height) }
        val surface = Surface(texture)
        val sink = PrivateDirectorySink(outputDir)
        val processor = CaptureProcessor(controller, { id -> catalog.characteristics(id) }, sink)
        val report = JSONObject().put("cases", JSONArray())

        val tele = lenses?.lenses?.filter { lenses.nativeZoom(it) > 1.5f }.orEmpty()
        val cases = if (superRes) com.datarohit.luma.settings.PhotoResolution.entries.map { r ->
            Case("photo-1x-${r.label}", CaptureMode.PHOTO, 1f, null, resolution = r)
        } else buildList {
            add(Case("photo-1x", CaptureMode.PHOTO, 1f, null, dng = true))
            add(Case("night-1x", CaptureMode.NIGHT, 1f, null))
            add(Case("photo-0.6x", CaptureMode.PHOTO, 0.6f, null))
            add(Case("photo-2x", CaptureMode.PHOTO, 2f, null))
            for (lens in tele) {
                val z = lenses!!.nativeZoom(lens)
                add(Case("photo-%.1fx-system".format(z), CaptureMode.PHOTO, z, null))
                add(Case("photo-%.1fx-physical-${lens.physicalId}".format(z), CaptureMode.PHOTO, z, lens.physicalId))
            }
        }
        try {
            for (case in cases) {
                log("C: ${case.name}")
                val entry = JSONObject().put("name", case.name)
                try {
                    val spec = SessionSpec(back.cameraId, back.previewSize, back.analysisSize,
                        rawSize = case.physicalId?.let { id -> rawSizeOf(catalog.characteristics(id)) } ?: back.rawSize,
                        rawPhysicalId = case.physicalId)
                    controller.start(back, spec, surface, Controls(zoomRatio = case.zoom))
                    val state = controller.state.value
                    if (state is CameraState.Failed) error(state.message)
                    delay(SETTLE_MS)
                    val info = requireNotNull(controller.frameInfo.value) { "no preview results" }
                    val stats = controller.analysis.value
                    val chars = back.characteristics
                    val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val boundLens = case.physicalId?.let { id -> lenses?.lenses?.firstOrNull { it.physicalId == id } }
                    val activeLens = lenses?.lenses?.firstOrNull { it.physicalId == info.activePhysicalId }
                    val apertureFactor = if (boundLens != null && activeLens != null && activeLens.aperture > 0f)
                        (boundLens.aperture / activeLens.aperture).let { it * it } else 1f
                    val plan = CapturePlanner.plan(PlanInput(
                        mode = case.mode, previewExposureNs = info.exposureNs, previewIso = info.iso,
                        isoMin = iso?.lower ?: 50, isoMax = iso?.upper ?: 3200,
                        exposureMinNs = exp?.lower ?: 10_000L, exposureMaxNs = exp?.upper ?: 1_000_000_000L,
                        clippedFraction = stats?.clippedFraction ?: 0f, motion = stats?.motion ?: 0f,
                        apertureFactor = apertureFactor,
                        superResolution = if (case.resolution.megapixels > 12) kotlin.math.sqrt(case.resolution.megapixels / 12.5).toFloat().coerceAtMost(2f) else 1f,
                    ))
                    val request = CaptureRequestSpec(
                        mode = case.mode, plan = plan,
                        settings = AppSettings(saveDng = case.dng, photoResolution = case.resolution),
                        zoom = case.zoom,
                        cameraZoomRatio = case.zoom,
                        rotationDegrees = back.sensorOrientation,
                        whiteBalanceKelvin = null,
                        lensDescription = { id -> "physical camera $id" },
                        equivalentFocalMm = { id -> lenses?.lenses?.firstOrNull { it.physicalId == id }?.equivalentFocalLengthMm ?: 24f },
                        sourceLabel = "diagnostics ${case.name}",
                    )
                    val t0 = System.nanoTime()
                    val burst = requireNotNull(processor.capture(request)) { "no frames delivered" }
                    val captureMs = (System.nanoTime() - t0) / 1e6
                    val physicalResult = burst.firstResult
                    val photo = processor.process(burst)
                    entry.put("ok", true)
                        .put("file", photo.jpegUri.lastPathSegment)
                        .put("dng", photo.dngUri?.lastPathSegment)
                        .put("previewActivePhysical", info.activePhysicalId)
                        .put("previewIso", info.iso).put("previewExposureMs", info.exposureNs / 1e6)
                        .put("previewFocusDiopters", info.focusDiopters)
                        .put("clipped", stats?.clippedFraction).put("motion", stats?.motion)
                        .put("plan", plan.toString())
                        .put("sourceCamera", burst.sourceCameraId)
                        .put("frameFocusDiopters", physicalResult.get(CaptureResult.LENS_FOCUS_DISTANCE))
                        .put("frameAfState", physicalResult.get(CaptureResult.CONTROL_AF_STATE))
                        .put("frameIso", physicalResult.get(CaptureResult.SENSOR_SENSITIVITY))
                        .put("frameExposureMs", (physicalResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L) / 1e6)
                        .put("captureMs", captureMs)
                        .put("summary", photo.summary)
                        .put("stats", photo.stats.toString())
                    log("C: ${case.name} -> ${photo.summary}")
                    if (superRes) saveCentreCrop(photo.jpegUri, case.name)?.let { entry.put("crop", it) }
                } catch (e: Exception) {
                    entry.put("ok", false).put("error", e.toString())
                    log("C: ${case.name} failed: $e")
                }
                report.getJSONArray("cases").put(entry)
            }
        } finally {
            controller.stop()
            controller.release()
            surface.release()
            texture.release()
        }
        return report
    }

    /** Centre crop covering the same scene area at every tier (1/10 of the frame width), as PNG. */
    private fun saveCentreCrop(uri: android.net.Uri, name: String): String? = runCatching {
        val file = File(requireNotNull(uri.path))
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(file.absolutePath)
        val w = decoder.width
        val h = decoder.height
        val side = w / 10
        val rect = android.graphics.Rect((w - side) / 2, (h - side) / 2, (w + side) / 2, (h + side) / 2)
        val bmp = decoder.decodeRegion(rect, null)
        decoder.recycle()
        val out = File(outputDir, "crop-$name.png")
        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        "${out.name} ${side}x$side of ${w}x$h"
    }.onFailure { log("crop failed: $it") }.getOrNull()

    /** Which physical camera the logical camera activates per zoom, for different stream setups. */
    suspend fun lensProbe(): JSONObject {
        val catalog = CameraCatalog(manager)
        val back = requireNotNull(catalog.back) { "no back camera" }
        val controller = CameraController(manager)
        val texture = SurfaceTexture(false).apply { setDefaultBufferSize(back.previewSize.width, back.previewSize.height) }
        val surface = Surface(texture)
        val out = JSONObject()
        val setups = buildList<Pair<String, SessionSpec>> {
            add("preview+yuv" to SessionSpec(back.cameraId, back.previewSize, back.analysisSize, null))
            add("preview+yuv+raw" to SessionSpec(back.cameraId, back.previewSize, back.analysisSize, back.rawSize))
            for (id in back.physicalIds) {
                add("preview+yuv+raw@$id" to SessionSpec(back.cameraId, back.previewSize, back.analysisSize,
                    rawSizeOf(catalog.characteristics(id)), rawPhysicalId = id))
            }
        }
        try {
            // Direct opens: public ultra-wide and the hidden physical cameras.
            val processor = CaptureProcessor(controller, { id -> catalog.characteristics(id) }, PrivateDirectorySink(outputDir))
            for (id in (listOf("2") + back.physicalIds).distinct()) {
                val entry = JSONObject()
                try {
                    val d = requireNotNull(catalog.descriptorFor(id)) { "no descriptor" }
                    controller.start(d, SessionSpec(id, d.previewSize, d.analysisSize, d.rawSize), surface, Controls())
                    val st = controller.state.value
                    if (st is CameraState.Failed) error(st.message)
                    delay(2_000L)
                    val info = controller.frameInfo.value
                    entry.put("opened", true).put("af", info?.afState).put("focus", info?.focusDiopters).put("iso", info?.iso)
                        .put("exposureMs", (info?.exposureNs ?: 0L) / 1e6).put("raw", d.rawSize.toString())
                    if (info != null) {
                        val plan = com.datarohit.luma.capture.CapturePlan(4, info.exposureNs, info.iso, 0f, true, "direct $id")
                        val req = CaptureRequestSpec(CaptureMode.PHOTO, plan, AppSettings(), 1f, 1f, d.sensorOrientation, null,
                            { "camera $it" }, { 24f }, "direct $id")
                        processor.capture(req)?.let { b -> entry.put("file", processor.process(b).jpegUri.lastPathSegment) }
                    }
                } catch (e: Exception) {
                    entry.put("opened", false).put("error", e.toString())
                }
                log("L: direct $id $entry")
                out.put("direct-$id", entry)
            }
            for ((name, spec) in setups) {
                val row = JSONObject()
                for (zoom in listOf(0.6f, 1f, 3f, 5f, 10f)) {
                    controller.start(back, spec, surface, Controls(zoomRatio = zoom))
                    delay(1_500L)
                    val info = controller.frameInfo.value
                    row.put(zoom.toString(), "${info?.activePhysicalId} f=${info?.focusDiopters} iso=${info?.iso}")
                }
                log("L: $name $row")
                out.put(name, row)
            }
        } finally {
            controller.stop()
            controller.release()
            surface.release()
            texture.release()
        }
        return out
    }

    private fun rawSizeOf(c: CameraCharacteristics) = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
