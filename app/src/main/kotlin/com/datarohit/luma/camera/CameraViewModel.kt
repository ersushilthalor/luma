package com.datarohit.luma.camera

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.MediaActionSound
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.datarohit.luma.appGraph
import com.datarohit.luma.capture.CaptureMode
import com.datarohit.luma.capture.CapturePlanner
import com.datarohit.luma.capture.CaptureRequestSpec
import com.datarohit.luma.capture.PanoramaStitcher
import com.datarohit.luma.capture.PlanInput
import com.datarohit.luma.core.camera.lens.CaptureRoute
import com.datarohit.luma.core.camera.lens.FocusMath
import com.datarohit.luma.core.camera.lens.FocusReadout
import com.datarohit.luma.core.camera.lens.MacroSwitch
import com.datarohit.luma.core.camera.lens.LensRouter
import com.datarohit.luma.core.camera.lens.RouteDecision
import com.datarohit.luma.core.camera.session.AnalysisStats
import com.datarohit.luma.core.camera.session.CameraDescriptor
import com.datarohit.luma.core.camera.session.CameraState
import com.datarohit.luma.core.camera.session.Controls
import com.datarohit.luma.core.camera.session.FlashMode
import com.datarohit.luma.core.camera.session.FocusMode
import com.datarohit.luma.core.camera.session.FrameInfo
import com.datarohit.luma.core.camera.session.SessionSpec
import com.datarohit.luma.core.camera.session.ViewPoint
import com.datarohit.luma.core.imaging.Look
import com.datarohit.luma.core.storage.MediaStoreWriter
import com.datarohit.luma.core.storage.PhotoMetadata
import com.datarohit.luma.gl.Composition
import com.datarohit.luma.gl.FocusEffect
import com.datarohit.luma.gl.InputSpec
import com.datarohit.luma.gl.LutLibrary
import com.datarohit.luma.gl.ShadeParams
import com.datarohit.luma.gl.Tile
import com.datarohit.luma.settings.AppSettings
import com.datarohit.luma.settings.AspectRatio
import com.datarohit.luma.video.AudioLevels
import com.datarohit.luma.video.ColorProfile
import com.datarohit.luma.video.ComboKind
import com.datarohit.luma.video.HyperlapseSpeed
import com.datarohit.luma.video.MultiCamOptions
import com.datarohit.luma.video.MultiCombo
import com.datarohit.luma.video.ProSettings
import com.datarohit.luma.video.ScopeKind
import com.datarohit.luma.video.Stabilization
import com.datarohit.luma.video.StorageInfo
import com.datarohit.luma.video.VideoControls
import com.datarohit.luma.video.VideoEngine
import com.datarohit.luma.video.VideoMode
import com.datarohit.luma.video.VideoPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** High-zoom aiming inset (fractions of the viewfinder): the whole telephoto frame, drawn by the renderer. */
internal object Aim {
    const val X = 0.05f
    const val Y = 0.17f
    const val W = 0.30f
    const val H = 0.20f
}

data class ZoomPreset(val zoom: Float, val label: String)

/** What the viewfinder is really showing (spec RULE-4, ZOOM-2). */
data class SourceBadge(val lens: String, val kind: String, val processedBy: String, val warning: String?)

data class ProState(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val kelvin: Int? = null,
    /** -50..50, applied with Kelvin. */
    val tint: Int = 0,
    val focusDiopters: Float? = null,
    /** Pro Video shutter angle in degrees (null = shutter auto). */
    val shutterAngle: Float? = null,
)

data class PanoramaUi(
    val capturing: Boolean = false,
    /** 0..1 of the maximum sweep. */
    val progress: Float = 0f,
    /** Cross-axis drift in degrees (keep near 0). */
    val drift: Float = 0f,
    val frames: Int = 0,
    val vertical: Boolean? = null,
    val stitching: Boolean = false,
)

data class RackState(val a: Float? = null, val b: Float? = null, val running: Boolean = false)

data class CameraUiState(
    val hasPermission: Boolean = false,
    val modeId: ModeId = ModeId.PHOTO,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val facingBack: Boolean = true,
    val zoom: Float = 1f,
    val presets: List<ZoomPreset> = emptyList(),
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val route: CaptureRoute = CaptureRoute.MAIN_RAW,
    val focusPoint: ViewPoint? = null,
    val evIndex: Int = 0,
    val evRange: IntRange = 0..0,
    val evStep: Float = 0f,
    val flash: FlashMode = FlashMode.OFF,
    val hasFlash: Boolean = false,
    val countdown: Int? = null,
    val capturing: Boolean = false,
    val captureProgress: String? = null,
    val processing: Int = 0,
    val lastThumbnail: Bitmap? = null,
    val lastUri: Uri? = null,
    val lastSummary: String? = null,
    val message: String? = null,
    val pro: ProState = ProState(),
    val settings: AppSettings = AppSettings(),
    val deviceOrientation: Int = 0,
    val descriptor: CameraDescriptor? = null,
    val reconfiguring: Boolean = false,
    /** Upright preview aspect (width / height). */
    val previewAspect: Float = 3f / 4f,
    val recording: Boolean = false,
    val recordStartMs: Long = 0L,
    val videoPlan: VideoPlan? = null,
    val panorama: PanoramaUi = PanoramaUi(),
    val effectCenter: ViewPoint = ViewPoint(0.5f, 0.42f),
    val rack: RackState = RackState(),
    val dollyRunning: Boolean = false,
    val multiCombos: List<MultiCombo> = emptyList(),
    val freeBytes: Long? = null,
    val storageLabel: String = "Internal",
    val lutChoices: List<Pair<String, String>> = emptyList(),
    /** Focus Enhancer has moved the shot to the macro-capable ultra-wide (or the HAL did, in video). */
    val macroActive: Boolean = false,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = app.appGraph
    private val controller = graph.controller
    private val catalog = graph.catalog
    private val renderer = graph.renderer
    private val videoEngine = graph.videoEngine
    private val proStore = graph.proSettings
    private val processingDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "luma-processing").apply { priority = Thread.NORM_PRIORITY - 1 }
    }.asCoroutineDispatcher()
    private val sessionMutex = Mutex()
    /**
     * Camera session work (open, configure, GPU input setup, encoder start/stop) runs here, never on the
     * main thread: mode switches and recording toggles then cost the UI no frames.
     */
    private val sessionDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "luma-session") }.asCoroutineDispatcher()
    private var displayReady = false
    private var resumed = false
    private var zoomJob: Job? = null
    private var timerJob: Job? = null
    private var rackJob: Job? = null
    private var dollyJob: Job? = null
    private var storageJob: Job? = null
    private var levelJob: Job? = null
    private var sound: MediaActionSound? = null
    private var photoInput: Pair<Size, android.view.Surface>? = null
    @Volatile private var rawOrientation = 0f

    private val _ui = MutableStateFlow(CameraUiState(settings = graph.settings.settings.value))
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()
    private val _frameInfo = MutableStateFlow<FrameInfo?>(null)
    val frameInfo: StateFlow<FrameInfo?> = _frameInfo.asStateFlow()
    val analysis: StateFlow<AnalysisStats?> = controller.analysis
    val cameraState: StateFlow<CameraState> = controller.state
    val pro: StateFlow<ProSettings> = proStore.state
    val fps: StateFlow<Float> = renderer.fps
    private val _audio = MutableStateFlow(AudioLevels())
    val audio: StateFlow<AudioLevels> = _audio.asStateFlow()
    private val _scopes = MutableStateFlow<Scopes?>(null)
    val scopes: StateFlow<Scopes?> = _scopes.asStateFlow()
    private val _videoError = MutableStateFlow<String?>(null)
    val videoError: StateFlow<String?> = _videoError.asStateFlow()
    private val _focus = MutableStateFlow<FocusReadout?>(null)
    /** Subject distance and depth of field from the HAL's focus distance (approximate calibration). */
    val focusReadout: StateFlow<FocusReadout?> = _focus.asStateFlow()
    private val macroSwitch = MacroSwitch()
    private var lastBokehDistance: Float? = null

    private val back: CameraDescriptor? = catalog.back
    private val front: CameraDescriptor? = catalog.front
    private val lenses = catalog.backLenses
    private val ultraWide: CameraDescriptor? = catalog.standaloneBack.minByOrNull { catalog.equivalentFocalMm(it.cameraId) ?: 99f }
    private val mainEquivalent: Float = lenses?.main?.equivalentFocalLengthMm ?: back?.let { catalog.equivalentFocalMm(it.cameraId) } ?: 24f

    private val router: LensRouter? = back?.let { b ->
        val uwZoom = ultraWide?.let { (catalog.equivalentFocalMm(it.cameraId) ?: mainEquivalent) / mainEquivalent } ?: 1f
        val firstTele = lenses?.lenses?.map { lenses.nativeZoom(it) }?.filter { it > 1.5f }?.minOrNull()
        LensRouter(
            logicalCameraId = b.cameraId,
            logicalZoomRange = b.zoomRange.lower..b.zoomRange.upper,
            ultraWideCameraId = ultraWide?.cameraId,
            ultraWideNativeZoom = uwZoom,
            ultraWideZoomRange = (ultraWide?.zoomRange?.lower ?: 1f)..(ultraWide?.zoomRange?.upper ?: 1f),
            firstTelephotoZoom = firstTele,
            logicalHasRaw = b.rawSize != null,
            // Beyond the logical camera's limit, Luma crops the longest telephoto on the GPU (up to 100×).
            digitalLimit = (MAX_ZOOM / b.zoomRange.upper).coerceAtLeast(1f),
        )
    }

    private val sensorManager: SensorManager = app.getSystemService(SensorManager::class.java)
    private var sensorListener: SensorEventListener? = null

    init {
        viewModelScope.launch { graph.settings.settings.collect { s -> _ui.update { it.copy(settings = s) } } }
        viewModelScope.launch { controller.frameInfo.collect { if (_ui.value.modeId.engine != Engine.VIDEO) onFrame(it) } }
        viewModelScope.launch { videoEngine.camera.frameInfo.collect { if (_ui.value.modeId.engine == Engine.VIDEO) onFrame(it) } }
        viewModelScope.launch { videoEngine.camera.error.collect { e -> if (e != null) _videoError.value = e } }
        viewModelScope.launch {
            proStore.state.collect {
                updateShade()
                applyDisplayComposition()
            }
        }
        _ui.update {
            it.copy(
                presets = buildPresets(),
                minZoom = router?.minimumZoom ?: 1f,
                maxZoom = router?.maximumZoom ?: (back?.zoomRange?.upper ?: 1f),
                hasFlash = back?.hasFlash == true,
                lutChoices = lutChoices(),
            )
        }
        controller.analysisListener = { img ->
            val d = controller.currentDescriptor
            onAnalysisFrame(img, d?.sensorOrientation ?: 90, d?.facingBack == false)
        }
        videoEngine.camera.analysisSink = { img -> onAnalysisFrame(img, sensorOrientation(videoEngine.plan?.cameraId), videoEngine.plan?.facingBack == false) }
        // Logical multi-camera lens switches (exact sensor timestamp) keep colour and exposure continuous.
        videoEngine.camera.onLensChange = { _, ts -> renderer.lensChanged(ts) }
        viewModelScope.launch(Dispatchers.Default) {
            val combos = runCatching { MultiCamOptions.enumerate(graph.cameraManager, ::comboLabel) }.getOrDefault(emptyList())
            _ui.update { it.copy(multiCombos = combos) }
        }
        pro.value.video.lutId?.let { loadLut(it) }
        loadLatestThumbnail()
    }

    // ------------------------------------------------------------------------------------------
    // Lifecycle

    fun onPermission(granted: Boolean) {
        _ui.update { it.copy(hasPermission = granted) }
        restart()
    }

    /** The SurfaceView that shows the renderer output. */
    fun onDisplaySurface(surface: android.view.Surface?, width: Int, height: Int) {
        renderer.setDisplay(surface, width, height)
        val was = displayReady
        displayReady = surface != null
        if (!was && displayReady) restart()
    }

    /**
     * Thermal guard: the phone reports its thermal status; from MODERATE the optional GPU stages (local tone,
     * detail lift) are dropped, from SEVERE also temporal denoising, so the frame rate holds while hot. The core
     * colour pipeline always stays.
     */
    @Volatile private var thermal = android.os.PowerManager.THERMAL_STATUS_NONE
    private val thermalListener = android.os.PowerManager.OnThermalStatusChangedListener { status ->
        if (status != thermal) {
            thermal = status
            Log.i(TAG, "thermal status $status")
            applyDisplayComposition()
        }
    }

    init {
        runCatching {
            val pm = getApplication<Application>().getSystemService(android.os.PowerManager::class.java)
            thermal = pm.currentThermalStatus
            pm.addThermalStatusListener(getApplication<Application>().mainExecutor, thermalListener)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Portrait subject segmentation

    @Volatile private var subjectCoverage = 0f
    private var segmentJob: Job? = null

    /** Runs person segmentation on rendered preview frames (~10 / s) while a Portrait mode is active. */
    private fun updateSegmentation() {
        val on = resumed && _ui.value.modeId in setOf(ModeId.PORTRAIT, ModeId.PORTRAIT_VIDEO)
        if (on && segmentJob?.isActive != true) {
            segmentJob = viewModelScope.launch(Dispatchers.Default) {
                while (true) {
                    val frame = kotlinx.coroutines.withTimeoutOrNull(1_000) {
                        kotlinx.coroutines.suspendCancellableCoroutine<Bitmap?> { cont -> renderer.snapshotRendered(320) { b -> cont.resume(b) {} } }
                    }
                    val mask = frame?.let { graph.segmenter.segment(it) }
                    frame?.recycle()
                    val coverage = mask?.coverage ?: 0f
                    if (mask != null) renderer.setSubjectMask(mask.width, mask.height, mask.data)
                    if ((coverage > 0.02f) != (subjectCoverage > 0.02f)) {
                        subjectCoverage = coverage
                        updateShade()
                    } else subjectCoverage = coverage
                    delay(90)
                }
            }
        } else if (!on) {
            segmentJob?.cancel()
            segmentJob = null
            subjectCoverage = 0f
            renderer.setSubjectMask(0, 0, null)
        }
    }

    fun onResume() {
        resumed = true
        updateSegmentation()
        restart()
    }

    fun onPause() {
        resumed = false
        updateSegmentation()
        timerJob?.cancel()
        stopSensors()
        _ui.update { it.copy(countdown = null) }
        viewModelScope.launch(sessionDispatcher) {
            sessionMutex.withLock {
                if (_ui.value.recording) runCatching { finishRecordingLocked() }
                if (_ui.value.panorama.capturing) runCatching { finishPanorama() }
                controller.stop()
                sessionDecision = null
                videoEngine.stop()
                // Sessions park their device for fast mode switches; leaving the app releases them.
                controller.devices.closeAll()
                photoInput = null
            }
        }
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().getSystemService(android.os.PowerManager::class.java).removeThermalStatusListener(thermalListener) }
        controller.analysisListener = null
        videoEngine.camera.analysisSink = null
        controller.release()
        sound?.release()
        processingDispatcher.close()
        sessionDispatcher.close()
    }

    // ------------------------------------------------------------------------------------------
    // Sessions

    private fun currentDecision(): RouteDecision? {
        val s = _ui.value
        if (!s.facingBack) {
            val f = front ?: return null
            return RouteDecision(CaptureRoute.MAIN_RAW, f.cameraId, 1f, 1f)
        }
        if (s.macroActive && s.modeId.engine == Engine.PHOTO) router?.macro(s.zoom)?.let { return it }
        return router?.route(s.zoom)
    }

    // ------------------------------------------------------------------------------------------
    // Focus telemetry: distance / DoF readout, Focus Enhancer, distance-aware bokeh

    private var lastPhotoLens: String? = null

    /**
     * Night / high-gain temporal denoising strength from the live sensor gain: off up to ISO 400 (no cost in good
     * light), ramping to 0.85 history weight (≈ 6 frames of averaging on static areas) by ISO 3200. Telephotos
     * reach their analog limit early, so the same ISO there is noisier: the noise estimate scales with
     * sqrt(ISO) and the exposure time.
     */
    private fun updateTemporalDenoise(info: FrameInfo, s: CameraUiState) {
        val iso = info.iso.takeIf { it > 0 } ?: return
        val strength = (((iso - 400f) / 2800f).coerceIn(0f, 1f) * 0.85f)
            .let { if (s.capturing || thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE) 0f else it }
        val noise = (0.005f * kotlin.math.sqrt(iso / 100f)).coerceIn(0.003f, 0.06f)
        val r = renderer
        r.tnrStrength = r.tnrStrength + (strength - r.tnrStrength) * 0.3f
        r.tnrNoise = noise
    }

    private fun onFrame(info: FrameInfo?) {
        _frameInfo.value = info
        if (info == null) return
        val s = _ui.value
        updateTemporalDenoise(info, s)
        if (s.modeId.engine != Engine.VIDEO) {
            // Photo sessions report the active lens on sampled results: match colour from the next frame.
            val lens = info.activePhysicalId
            if (lens != null && lastPhotoLens != null && lens != lastPhotoLens) renderer.lensChanged(0L)
            lastPhotoLens = lens
        }
        _focus.value = readout(info, s)
        focusEnhancer(info, s)
        val d = _focus.value?.distanceM
        val last = lastBokehDistance
        if (s.modeId in EFFECT_MODES && (last == null || d == null || abs(d - last) / last > 0.1f)) {
            lastBokehDistance = d
            updateShade()
        }
    }

    private fun readout(info: FrameInfo, s: CameraUiState): FocusReadout? {
        val id = info.activePhysicalId ?: s.descriptor?.cameraId ?: return null
        val chars = runCatching { catalog.characteristics(id) }.getOrNull() ?: s.descriptor?.characteristics ?: return null
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val focal = info.focalLengthMm.takeIf { it > 0f } ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val aperture = info.aperture.takeIf { it > 0f } ?: chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 1.8f
        return FocusMath.readout(info.focusDiopters, focal, aperture, FocusMath.sensorDiagonal(sensor.width, sensor.height))
    }

    /** Physical ID of the ultra-wide inside the logical back camera (for the video macro badge). */
    private val ultraWidePhysicalId: String? by lazy {
        lenses?.lenses?.minByOrNull { lenses.nativeZoom(it) }?.takeIf { lenses.nativeZoom(it) < 0.9f }?.physicalId
    }

    private fun focusEnhancer(info: FrameInfo, s: CameraUiState) {
        if (s.modeId.engine == Engine.VIDEO) {
            // Video runs on the logical camera, whose HAL moves to the ultra-wide for close subjects itself.
            val macro = s.facingBack && s.zoom in 0.95f..1.5f && info.activePhysicalId != null && info.activePhysicalId == ultraWidePhysicalId
            if (macro != s.macroActive) _ui.update { it.copy(macroActive = macro) }
            return
        }
        val eligible = s.settings.focusEnhancer && s.facingBack && s.modeId in MACRO_MODES && s.zoom in 0.95f..1.5f &&
            s.pro.focusDiopters == null && router?.macro(s.zoom) != null && !s.capturing && !s.reconfiguring
        if (!eligible) {
            if (s.macroActive && !s.capturing && !s.reconfiguring) {
                macroSwitch.reset()
                _ui.update { it.copy(macroActive = false) }
                restart()
            }
            return
        }
        val settled = info.afState in SETTLED_AF
        if (macroSwitch.update(info.focusDiopters, settled)) {
            _ui.update { it.copy(macroActive = macroSwitch.active, message = if (macroSwitch.active) "Focus Enhancer: close-up on the ultra-wide" else null) }
            restart()
        }
    }

    private val ultraWideNative: Float by lazy {
        ultraWide?.let { (catalog.equivalentFocalMm(it.cameraId) ?: mainEquivalent) / mainEquivalent } ?: 1f
    }

    /**
     * Video reaches the ultra-wide through the logical camera when its zoom range goes below 1× (the HAL
     * switches lenses inside one continuous stream, so recording and audio stay in sync). Only if the
     * logical camera stops at 1× does Luma open the standalone ultra-wide camera instead.
     */
    private fun standaloneUltraWideVideo(s: CameraUiState): Boolean {
        val b = back ?: return false
        return s.facingBack && ultraWide != null && b.zoomRange.lower >= 0.95f && s.zoom < 0.95f &&
            s.modeId.video in setOf(VideoMode.STANDARD, VideoMode.PRO, VideoMode.PORTRAIT, VideoMode.HYPERLAPSE)
    }

    /** Largest zoom offered in video: the camera's range, then a GPU crop of the telephoto up to [MAX_ZOOM]. */
    fun videoMaxZoom(): Float {
        val b = back ?: return 1f
        val mode = _ui.value.modeId.video
        return if (mode in setOf(VideoMode.STANDARD, VideoMode.PRO, VideoMode.PORTRAIT, VideoMode.HYPERLAPSE)) maxOf(b.zoomRange.upper, MAX_ZOOM)
        else b.zoomRange.upper
    }

    /** Digital zoom the GPU adds on top of the camera (preview, recording, aiming inset). */
    fun digitalZoom(): Float = digitalZoomNow()

    private fun digitalZoomNow(): Float {
        val s = _ui.value
        if (!s.facingBack) return 1f
        return if (s.modeId.engine == Engine.VIDEO) {
            val upper = back?.zoomRange?.upper ?: return 1f
            (s.zoom / upper).coerceAtLeast(1f)
        } else currentDecision()?.digitalZoom ?: 1f
    }

    private fun applyDigitalZoom() {
        val dz = digitalZoomNow()
        videoEngine.setDigitalZoom(dz)
        applyDisplayComposition()
    }

    /** Smallest zoom offered in video: the logical camera's own minimum, or the standalone ultra-wide. */
    fun videoMinZoom(): Float {
        val b = back ?: return 1f
        return if (b.zoomRange.lower >= 0.95f && ultraWide != null) ultraWideNative else b.zoomRange.lower
    }

    /** One-line capability summary of the lens currently streaming, read from the phone at runtime. */
    fun lensCapabilityText(): String {
        val info = _frameInfo.value
        val id = info?.activePhysicalId ?: _ui.value.videoPlan?.cameraId ?: back?.cameraId ?: return ""
        val c = graph.caps.cameras[id] ?: return ""
        val parts = mutableListOf<String>()
        parts += lensName(id) + (catalog.equivalentFocalMm(id)?.let { " ${it.roundToInt()} mm" } ?: "")
        c.videoSizes.take(3).takeIf { it.isNotEmpty() }?.let { v -> parts += v.joinToString(" / ") { "${it.size.height}p${it.maxFps}" } }
        c.highSpeed.map { it.fps }.distinct().sorted().takeIf { it.isNotEmpty() }?.let { parts += "slow-mo " + it.joinToString("/") }
        parts += c.dynamicRangeProfiles.filter { it != android.hardware.camera2.params.DynamicRangeProfiles.STANDARD }
            .joinToString("/") { com.datarohit.luma.device.DeviceCapabilities.profileName(it) }.ifEmpty { "SDR" }
        parts += listOfNotNull("OIS".takeIf { c.ois }, "EIS".takeIf { c.videoStabilization.size > 1 }).joinToString("+").ifEmpty { "no stabilisation" }
        return "Streaming: " + parts.joinToString(" · ")
    }

    /** Freezes the current autofocus distance into manual focus (AF mode OFF). */
    fun lockFocusToManual() {
        val d = _frameInfo.value?.focusDiopters ?: return
        setPro { it.copy(focusDiopters = d) }
        _ui.update { it.copy(message = "Focus locked at ${FocusMath.format(FocusMath.dioptersToMeters(d))}") }
    }

    // Video option lists for the Pro tools sheet (from the phone's reported capabilities).
    private fun videoCameraId(): String = _ui.value.videoPlan?.cameraId ?: back?.cameraId ?: "0"
    fun videoSizes(): List<Size> = videoEngine.sizesFor(videoCameraId())
    fun videoFps(): List<Int> {
        val p = _ui.value.videoPlan ?: return emptyList()
        return videoEngine.fpsFor(p.cameraId, p.config.size, p.config.codec)
    }
    fun videoCodecs() = videoEngine.codecs()
    fun videoProfiles() = videoEngine.profiles(videoCameraId(), pro.value.video.codec)
    fun slowMoRates(): List<Int> = videoEngine.slowMoOptions(back?.cameraId ?: "0").map { it.fps }

    private fun descriptorFor(decision: RouteDecision): CameraDescriptor? = when (decision.cameraId) {
        back?.cameraId -> back
        front?.cameraId -> front
        ultraWide?.cameraId -> ultraWide
        else -> catalog.descriptorFor(decision.cameraId)
    }

    /**
     * Rebuilds the camera session. With [transition] the viewfinder holds its last image and cross-fades
     * into the new stream instead of flashing; [lensSwitch] also matches the new lens's colour to the old.
     */
    private fun restart(transition: Boolean = false, lensSwitch: Boolean = false) {
        if (!resumed || !_ui.value.hasPermission) return
        if (transition) renderer.freeze()
        if (lensSwitch) renderer.lensChanged(0L)
        val t0 = android.os.SystemClock.elapsedRealtime()
        viewModelScope.launch(sessionDispatcher) {
            sessionMutex.withLock {
                if (_ui.value.recording || !resumed) return@withLock
                try {
                    if (_ui.value.modeId.engine == Engine.VIDEO) startVideoLocked() else startPhotoLocked()
                    renderer.sessionStarted()
                    Log.i(TAG, "session ${_ui.value.modeId} ready in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                } catch (e: Exception) {
                    Log.w(TAG, "session start failed", e)
                    _ui.update { it.copy(message = e.message ?: "Camera could not start", reconfiguring = false) }
                }
            }
        }
    }

    private suspend fun startPhotoLocked() {
        videoEngine.stop()
        val decision = currentDecision() ?: run {
            _ui.update { it.copy(message = "No usable camera found") }
            return
        }
        val d = descriptorFor(decision) ?: return
        // A resolution tier the sensor exposes natively above the RAW readout is captured as a HAL still at
        // that size (public map, or the maximum-resolution map on ULTRA_HIGH_RESOLUTION sensors).
        val hires = graph.resolutions.plan(_ui.value.settings.photoResolution, d.cameraId)
            ?.takeIf { p -> p.needsHighResStream && d.rawSize.let { r -> r == null || p.from.megapixels > r.width * r.height / 1e6f * 1.15f } }
        val useRaw = decision.usesRaw && hires == null
        val jpeg = hires?.from?.size ?: if (useRaw || d.rawSize == null) null else largestJpeg(d)
        val spec = SessionSpec(
            cameraId = d.cameraId,
            previewSize = d.previewSize,
            analysisSize = d.analysisSize,
            rawSize = if (useRaw) d.rawSize else null,
            jpegSize = jpeg ?: if (d.rawSize == null) largestJpeg(d) else null,
            jpegMaxResolution = hires?.from?.maxResolution == true,
        )
        _ui.update {
            it.copy(
                route = if (d.rawSize == null || hires != null) CaptureRoute.HAL_PROCESSED else decision.route,
                descriptor = d,
                evRange = d.compensationRange.lower..d.compensationRange.upper,
                evStep = d.compensationStep,
                hasFlash = d.hasFlash,
                reconfiguring = true,
                previewAspect = 3f / 4f,
                videoPlan = null,
            )
        }
        val reuse = photoInput?.takeIf { it.first == d.previewSize && controller.state.value is CameraState.Streaming }
        val input = reuse ?: run {
            val surfaces = renderer.configureInputs(listOf(InputSpec(d.previewSize, rotated = d.sensorOrientation % 180 != 0)))
            (d.previewSize to surfaces[0]).also { photoInput = it }
        }
        updateShade()
        applyDisplayComposition()
        controller.start(d, spec, input.second, controls(decision))
        sessionDecision = decision
        _ui.update { it.copy(reconfiguring = false) }
    }

    private suspend fun startVideoLocked() {
        sessionDecision = null
        controller.stop()
        photoInput = null
        _videoError.value = null
        val plan = videoPlan() ?: run {
            _ui.update { it.copy(message = _videoError.value ?: "No camera available for this mode") }
            return
        }
        _ui.update {
            it.copy(
                videoPlan = plan,
                previewAspect = 9f / 16f,
                descriptor = if (plan.facingBack) back else front,
                evRange = (back?.compensationRange?.lower ?: 0)..(back?.compensationRange?.upper ?: 0),
                evStep = back?.compensationStep ?: 0f,
                reconfiguring = true,
            )
        }
        updateShade()
        applyDisplayComposition()
        try {
            videoEngine.startPreview(plan, videoControls())
        } catch (e: Exception) {
            _videoError.value = e.message
            throw e
        } finally {
            _ui.update { it.copy(reconfiguring = false) }
        }
        // Rates above the advertised stream limit are measured once per phone, camera and size.
        if (videoEngine.needsRateCheck(plan) && !videoEngine.verifyRate(plan)) {
            _ui.update { it.copy(message = "${plan.config.label} is not sustained by this camera. Using the next lower frame rate.") }
            startVideoLocked()
            return
        }
        startStorageWatch()
    }

    private fun videoPlan(): VideoPlan? {
        val s = _ui.value
        val p = pro.value
        val b = back ?: return null
        val mode = s.modeId.video ?: return null
        val combo = when (mode) {
            VideoMode.DUAL -> s.multiCombos.firstOrNull { c -> c.kind == ComboKind.CONCURRENT && c.sources.any { it.facingBack } && c.sources.any { !it.facingBack } }
                ?: s.multiCombos.firstOrNull { it.kind == ComboKind.CONCURRENT }
            VideoMode.MULTI -> s.multiCombos.firstOrNull { it.key == p.multiComboKey } ?: s.multiCombos.firstOrNull()
            else -> null
        }
        if ((mode == VideoMode.DUAL || mode == VideoMode.MULTI) && combo == null) {
            _videoError.value = "This phone reports no camera combination that can stream at the same time"
            return null
        }
        val raw = VideoPlan(
            mode = mode,
            config = p.video,
            facingBack = s.facingBack || mode == VideoMode.SLOW_MOTION,
            backId = if (standaloneUltraWideVideo(s)) ultraWide!!.cameraId else b.cameraId,
            frontId = front?.cameraId,
            slowMoFps = p.slowMoFps,
            hyperlapse = p.hyperlapse,
            dualLayout = p.dualLayout,
            separateFiles = if (mode == VideoMode.DUAL) p.dualSeparateFiles else p.multiSeparateFiles,
            combo = combo,
        )
        return videoEngine.sanitize(raw)
    }

    private fun largestJpeg(d: CameraDescriptor): Size? =
        d.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }

    private fun controls(decision: RouteDecision? = currentDecision()): Controls {
        val s = _ui.value
        val pro = s.mode == CaptureMode.PRO
        val focusMode = when {
            pro && s.pro.focusDiopters != null -> FocusMode.MANUAL
            s.focusPoint != null -> FocusMode.LOCKED_AREA
            else -> FocusMode.CONTINUOUS
        }
        return Controls(
            zoomRatio = decision?.cameraZoomRatio ?: 1f,
            focusMode = focusMode,
            focusPoint = s.focusPoint,
            focusDiopters = s.pro.focusDiopters ?: 0f,
            exposureCompensation = s.evIndex,
            manualExposureNs = if (pro) s.pro.exposureNs else null,
            manualIso = if (pro) s.pro.iso else null,
            whiteBalanceKelvin = if (pro) s.pro.kelvin else null,
            whiteBalanceTint = if (pro) s.pro.tint else 0,
            flash = s.flash,
            // Framing aid at long zoom: the HAL's preview stabilisation steadies the viewfinder.
            stabilizePreview = s.zoom >= 10f,
            isp = if (proStore.state.value.video.engine) com.datarohit.luma.core.camera.session.IspMode.NEUTRAL_LOG else com.datarohit.luma.core.camera.session.IspMode.CAMERA,
        )
    }

    private fun videoControls(): VideoControls {
        val s = _ui.value
        val p = pro.value
        val plan = s.videoPlan
        val manual = s.modeId == ModeId.PRO_VIDEO
        val fps = plan?.config?.fps ?: p.video.fps
        val exposure = if (manual) s.pro.shutterAngle?.let { (it / 360f / fps * 1e9f).toLong() } ?: s.pro.exposureNs else null
        val zoomRange = back?.zoomRange
        val zoomRatio = when {
            !s.facingBack -> 1f
            standaloneUltraWideVideo(s) -> (s.zoom / ultraWideNative).coerceIn(ultraWide!!.zoomRange.lower, ultraWide.zoomRange.upper)
            zoomRange != null -> s.zoom.coerceIn(zoomRange.lower, zoomRange.upper)  // beyond: GPU crop (videoDigitalZoom)
            else -> 1f
        }
        return VideoControls(
            zoomRatio = zoomRatio,
            focusMode = when {
                manual && s.pro.focusDiopters != null -> FocusMode.MANUAL
                s.focusPoint != null -> FocusMode.LOCKED_AREA
                else -> FocusMode.CONTINUOUS
            },
            focusPoint = s.focusPoint,
            focusDiopters = s.pro.focusDiopters ?: 0f,
            exposureNs = exposure,
            iso = if (manual) s.pro.iso else null,
            evIndex = s.evIndex - headroomSteps(plan),
            kelvin = if (manual) s.pro.kelvin else null,
            tint = if (manual) s.pro.tint.toFloat() else 0f,
            fps = fps,
            ois = p.video.stabilization != Stabilization.OFF,
            eis = p.video.stabilization == Stabilization.OIS_EIS || p.video.stabilization == Stabilization.HORIZON || s.modeId == ModeId.HYPERLAPSE,
            isp = plan?.isp ?: com.datarohit.luma.core.camera.session.IspMode.CAMERA,
            torch = s.flash == FlashMode.TORCH,
        )
    }

    private fun pushControls() {
        if (_ui.value.modeId.engine == Engine.VIDEO) videoEngine.camera.setControls(videoControls())
        else controller.setControls(controls())
    }

    // ------------------------------------------------------------------------------------------
    // GPU look: effects, LUT and monitoring

    private fun updateShade() {
        val s = _ui.value
        val p = pro.value
        val mode = s.modeId
        val c = s.effectCenter
        // Distance-aware bokeh: a closer subject throws the background further out of focus and narrows the
        // transition (blur of a distant background scales ~1/subject distance).
        val distance = _focus.value?.distanceM
        val scale = FocusMath.bokehScale(distance)
        val falloff = ((distance ?: 1f) / 1f).coerceIn(0.4f, 1.6f)
        var shade = when (mode) {
            ModeId.PORTRAIT, ModeId.PORTRAIT_VIDEO -> ShadeParams(
                effect = FocusEffect.PORTRAIT, centerX = c.x, centerY = c.y, radius = 0.14f, feather = 0.16f * falloff,
                blur = (fStopToBlur(p.portraitFStop) * scale).coerceAtMost(0.042f), ellipseX = 0.75f, ellipseY = 1.3f,
                // A person in frame: blur follows the segmentation; otherwise the focus ellipse.
                useMask = subjectCoverage > 0.02f,
                bokehStyle = p.portraitStyle.ordinal,
            )
            ModeId.FOOD -> ShadeParams(
                effect = FocusEffect.TILT_SHIFT, centerX = c.x, centerY = c.y, radius = p.foodRadius, feather = 0.12f * falloff,
                blur = ((0.012f + 0.03f * p.foodBlur) * scale).coerceAtMost(0.042f), warmth = 0.5f, saturation = 1.18f,
            )
            else -> ShadeParams()
        }
        val monitorsAllowed = mode.isVideo || mode == ModeId.PRO_PHOTO
        val manualFocus = s.pro.focusDiopters != null && (mode == ModeId.PRO_PHOTO || mode == ModeId.PRO_VIDEO)
        shade = shade.copy(
            peaking = (monitorsAllowed && p.peaking) || manualFocus,
            peakColor = p.peakColor.argb,
            peakThreshold = p.peakSensitivity.threshold,
            zebraIre = if (monitorsAllowed) p.zebraIre.toFloat() else 0f,
            falseColor = monitorsAllowed && p.falseColor,
            lutMix = if (mode.isVideo && p.video.lutId != null && (p.video.displayLut || p.video.recordLut)) 1f else 0f,
        )
        renderer.shade = shade
    }

    /** AE compensation steps for the plan's highlight headroom (whole steps, so the GPU gain is exact). */
    private fun headroomSteps(plan: VideoPlan?): Int {
        if (plan == null || plan.isp != com.datarohit.luma.core.camera.session.IspMode.NEUTRAL_LOG) return 0
        val step = back?.compensationStep?.takeIf { it > 0f } ?: return 0
        // HDR keeps at least 1.3 EV above diffuse white: that headroom is what the HDR highlights are made of.
        val ev = if (plan.gpuHdr) maxOf(plan.config.headroomEv, 1.3f) else plan.config.headroomEv
        return kotlin.math.round(ev / step).toInt()
    }

    /**
     * Configures the GPU colour engine for the running session: the stream is scene-log when the HAL was
     * neutralised (and the camera supports a custom curve); the gain restores the highlight headroom.
     */
    private fun applyEngine() {
        val s = _ui.value
        val p = pro.value
        val plan = s.videoPlan
        val video = s.modeId.engine == Engine.VIDEO && plan != null
        val cameraId = if (video) plan!!.cameraId else s.descriptor?.cameraId
        val chars = cameraId?.let { runCatching { catalog.characteristics(it) }.getOrNull() }
        val logStream = chars != null && com.datarohit.luma.core.camera.session.IspTuning.supportsLog(chars) &&
            (if (video) plan!!.isp == com.datarohit.luma.core.camera.session.IspMode.NEUTRAL_LOG else p.video.engine)
        val look = if (video) p.video.look else when (s.settings.look) {
            com.datarohit.luma.core.imaging.Look.VIVID -> com.datarohit.luma.gl.ColorLook.VIVID
            else -> com.datarohit.luma.gl.ColorLook.NATURAL
        }
        val steps = if (video) headroomSteps(plan) else 0
        val stepEv = back?.compensationStep ?: 0f
        renderer.prepareLook(look)
        renderer.engine = com.datarohit.luma.gl.EngineConfig(
            enabled = p.video.engine || (video && plan!!.log),
            inputLog = logStream,
            gain = Math.pow(2.0, (steps * stepEv).toDouble()).toFloat(),
            look = look,
            grain = p.video.grain && video,
            // Photo previews follow the RAW pipeline's own local tone setting.
            localTone = if (thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE) 0f
            else if (video) p.video.localTone else s.settings.localTone * 0.5f,
            sharpen = if (thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE) 0f else if (video) 0.45f else 0.35f,
        )
    }

    private fun applyDisplayComposition() {
        applyEngine()
        val s = _ui.value
        val p = pro.value
        val plan = s.videoPlan
        val video = s.modeId.engine == Engine.VIDEO && plan != null
        val horizon = video && p.video.stabilization == Stabilization.HORIZON && plan?.direct == false
        val desq = if (video && plan!!.mode in setOf(VideoMode.STANDARD, VideoMode.PRO)) p.video.desqueeze else 1f
        val dz = digitalZoomNow()
        val single = !video || plan!!.inputCount == 1
        renderer.displayComposition = Composition(
            tiles = when {
                video && !single -> videoEngine.displayTiles(plan!!, 9f / 16f)
                dz > 1.05f -> listOf(Tile(0, zoom = dz), Tile(0, Aim.X, Aim.Y, Aim.W, Aim.H, border = true))
                else -> listOf(Tile(0))
            },
            rotationDeg = if (horizon) VideoEngine.rollCorrection(rawOrientation, s.deviceOrientation, !s.facingBack) else 0f,
            rotationSafeCrop = horizon,
            desqueeze = desq,
            fit = desq > 1f,
            lut = video && (p.video.displayLut || p.video.recordLut) && p.video.lutId != null,
            effects = true,
            monitors = true,
            // Log: the viewfinder shows Luma's Rec.709 rendering, or the log signal under a user display LUT.
            tone = when {
                !p.video.engine && !(video && plan!!.log) -> com.datarohit.luma.gl.GlRenderer.TONE_NONE
                video && plan!!.log && p.video.displayLut && p.video.lutId != null -> com.datarohit.luma.gl.GlRenderer.TONE_LOG
                else -> com.datarohit.luma.gl.GlRenderer.TONE_FILMIC
            },
        )
    }

    private fun fStopToBlur(f: Float): Float = (0.028f * (1.4f / f.coerceIn(1.4f, 16f))).coerceIn(0.002f, 0.028f)

    fun lutChoices(): List<Pair<String, String>> {
        val imported = lutDir().listFiles { f -> f.extension.equals("cube", true) }.orEmpty().sortedBy { it.name }
            .map { "file:${it.name}" to it.nameWithoutExtension }
        return LutLibrary.builtIn.map { it.id to it.label } + imported
    }

    private fun lutDir() = File(getApplication<Application>().filesDir, "luts").apply { mkdirs() }

    fun selectLut(id: String?) {
        proStore.update { it.copy(video = it.video.copy(lutId = id, displayLut = if (id != null && !it.video.recordLut) true else it.video.displayLut)) }
        loadLut(id)
    }

    private fun loadLut(id: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val lut = runCatching {
                when {
                    id == null -> null
                    id.startsWith("file:") -> File(lutDir(), id.removePrefix("file:")).inputStream().use { LutLibrary.parseCube(id.removePrefix("file:"), it) }
                    else -> LutLibrary.builtIn.firstOrNull { it.id == id }?.build?.invoke()
                }
            }.onFailure { _ui.update { s -> s.copy(message = "LUT could not be loaded: ${it.message}") } }.getOrNull()
            renderer.setLut(lut)
        }
    }

    /** Copies a picked .cube file into Luma's private LUT folder after validating it. */
    fun importLut(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "imported.cube"
            val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").let { if (it.endsWith(".cube", true)) it else "$it.cube" }
            val result = runCatching {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("cannot read file")
                LutLibrary.parseCube(safe, bytes.inputStream())
                File(lutDir(), safe).writeBytes(bytes)
            }
            result.onSuccess {
                _ui.update { it.copy(lutChoices = lutChoices(), message = "Imported LUT $safe") }
                selectLut("file:$safe")
            }.onFailure { e -> _ui.update { it.copy(message = "Not a usable .cube LUT: ${e.message}") } }
        }
    }

    fun setStorageTree(uri: Uri?) {
        proStore.update { it.copy(storageTree = uri?.toString()) }
        startStorageWatch()
    }

    private fun startStorageWatch() {
        storageJob?.cancel()
        storageJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val tree = pro.value.storageTree?.let(Uri::parse)
                val ctx = getApplication<Application>()
                _ui.update { it.copy(freeBytes = StorageInfo.freeBytes(ctx, tree), storageLabel = StorageInfo.volumeLabel(ctx, tree)) }
                delay(5_000)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // User intents

    fun setZoom(z: Float) {
        val s = _ui.value
        if (!s.facingBack) return
        if (s.modeId.engine == Engine.VIDEO) {
            val range = back?.zoomRange ?: return
            val wasUw = standaloneUltraWideVideo(s)
            _ui.update { it.copy(zoom = z.coerceIn(videoMinZoom(), videoMaxZoom()), focusPoint = null) }
            applyDigitalZoom()
            if (standaloneUltraWideVideo(_ui.value) != wasUw && !s.recording) {
                // Only phones whose logical camera cannot reach the ultra-wide need a new session.
                zoomJob?.cancel()
                zoomJob = viewModelScope.launch {
                    delay(ZOOM_SETTLE_MS)
                    restart(transition = true, lensSwitch = true)
                }
            } else {
                videoEngine.camera.setControls(videoControls())
            }
            return
        }
        val r = router ?: return
        val clamped = z.coerceIn(r.minimumZoom, r.maximumZoom)
        _ui.update { it.copy(zoom = clamped, focusPoint = null) }
        // Compare with the session that is actually running, not with the previous zoom step: a glide or pinch
        // crosses the telephoto threshold once, and later steps must not cancel the switch it scheduled.
        val target = currentDecision() ?: return
        applyDisplayComposition()
        if (needsPhotoSession(target)) {
            if (zoomJob?.isActive != true) {
                zoomJob = viewModelScope.launch {
                    delay(ZOOM_SETTLE_MS)
                    val now = currentDecision()
                    if (now != null && needsPhotoSession(now)) restart(transition = true, lensSwitch = now.cameraId != sessionDecision?.cameraId)
                }
            }
            pushControls()
        } else {
            zoomJob?.cancel()
            pushControls()
        }
    }

    /** Route and camera of the running photo session (null when no photo session runs). */
    @Volatile private var sessionDecision: RouteDecision? = null

    /** True when [target] needs a different photo session than the one streaming (route or camera). */
    private fun needsPhotoSession(target: RouteDecision): Boolean {
        val running = sessionDecision ?: return true
        return running.route != target.route || running.cameraId != target.cameraId
    }

    fun tapToFocus(point: ViewPoint) {
        val s = _ui.value
        if ((s.mode == CaptureMode.PRO || s.modeId == ModeId.PRO_VIDEO) && s.pro.focusDiopters != null) return
        _ui.update { st -> st.copy(focusPoint = point, effectCenter = if (st.modeId in EFFECT_MODES) point else st.effectCenter) }
        updateShade()
        pushControls()
    }

    fun resetFocus() {
        _ui.update { it.copy(focusPoint = null) }
        pushControls()
    }

    fun selectMode(id: ModeId) {
        val s = _ui.value
        if (s.modeId == id || s.recording || s.panorama.capturing) return
        val sessionChanged = s.modeId.engine != id.engine || (id.engine == Engine.VIDEO && s.modeId.video != id.video)
        val backOnly = id in BACK_ONLY
        macroSwitch.reset()
        viewModelScope.launch { delay(1); updateSegmentation() }
        _ui.update {
            it.copy(
                macroActive = false,
                modeId = id,
                mode = id.capture,
                focusPoint = null,
                facingBack = if (backOnly) true else it.facingBack,
                zoom = if (id.engine != s.modeId.engine) 1f else it.zoom,
            )
        }
        updateShade()
        val reuse = sessionChanged && s.modeId.engine == Engine.VIDEO && id.engine == Engine.VIDEO && !(backOnly && !s.facingBack) &&
            s.videoPlan?.let { old -> videoPlan()?.takeIf { videoEngine.canReuse(old, it) } }?.also { p ->
                // Same camera, stream size, rate and colour path: only the GPU composition changes.
                videoEngine.adopt(p)
                _ui.update { it.copy(videoPlan = p) }
            } != null
        if ((sessionChanged || (backOnly && !s.facingBack)) && !reuse) restart(transition = true) else {
            updateShade()
            applyDisplayComposition()
            pushControls()
        }
    }

    private var zoomAnim: Job? = null

    /**
     * Holds the viewfinder on its last frame while a sheet covers it. Android limits apps with a streaming
     * camera to 60 Hz; paused, the sheet scrolls at the panel's 120 Hz. Never pauses a recording or capture.
     */
    fun setPreviewPaused(on: Boolean) {
        val s = _ui.value
        if (on && (s.recording || s.capturing || s.panorama.capturing || s.countdown != null)) return
        controller.setPaused(on)
        videoEngine.camera.setPaused(on)
    }

    private val _pinch = MutableStateFlow(0)
    /** Increments on every pinch step (the zoom disk opens while pinching). */
    val pinchTicks: StateFlow<Int> = _pinch.asStateFlow()

    fun pinchZoom(factor: Float) {
        _pinch.value++
        scrubZoom(_ui.value.zoom * factor)
    }

    /** Direct zoom from a pinch or a scrub; interrupts a running glide. */
    fun scrubZoom(z: Float) {
        zoomAnim?.cancel()
        setZoom(z)
    }

    /** Lens preset tap: an eased zoom (in log space) instead of a jump, so framing glides between lenses. */
    fun zoomTo(target: Float) {
        val from = _ui.value.zoom
        zoomAnim?.cancel()
        if (abs(ln(target / from)) < 0.01f) {
            setZoom(target)
            return
        }
        zoomAnim = viewModelScope.launch {
            val start = android.os.SystemClock.uptimeMillis()
            val duration = (ZOOM_GLIDE_MS * (0.6f + 0.4f * (abs(ln(target / from)) / ln(10f)).coerceAtMost(1f))).toLong()
            while (true) {
                val t = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / duration).coerceIn(0f, 1f)
                val e = t * t * (3f - 2f * t)
                setZoom(from * exp(ln(target / from) * e))
                if (t >= 1f) break
                delay(8)
            }
        }
    }

    fun setEv(index: Int) {
        _ui.update { it.copy(evIndex = index.coerceIn(it.evRange.first, it.evRange.last)) }
        pushControls()
    }

    fun toggleFlash() {
        _ui.update { it.copy(flash = if (it.flash == FlashMode.OFF) FlashMode.TORCH else FlashMode.OFF) }
        pushControls()
    }

    fun cycleTimer() = updateSettings { s -> s.copy(timerSeconds = when (s.timerSeconds) { 0 -> 3; 3 -> 10; else -> 0 }) }

    /** Lens whose readout the next photo starts from. */
    private fun photoSourceId(info: FrameInfo?): String? {
        val s = _ui.value
        return when {
            !s.facingBack -> front?.cameraId
            s.route == CaptureRoute.ULTRA_WIDE_RAW -> ultraWide?.cameraId
            else -> info?.activePhysicalId ?: s.descriptor?.cameraId
        }
    }

    /** How the selected resolution tier is produced on the lens in use (native / downsampled / upscaled). */
    fun resolutionPlan(info: FrameInfo? = _frameInfo.value): com.datarohit.luma.capture.ResolutionPlan? =
        photoSourceId(info)?.let { graph.resolutions.plan(_ui.value.settings.photoResolution, it, rawRoute = _ui.value.route != CaptureRoute.HAL_PROCESSED) }

    /** Super-resolution scale per side a RAW capture at the selected tier will use (1 = none, at most 2). */
    private fun superResolutionFor(plan: com.datarohit.luma.capture.ResolutionPlan?, route: CaptureRoute): Float {
        val p = plan ?: return 1f
        if (route == CaptureRoute.HAL_PROCESSED) return 1f
        if (p.source != com.datarohit.luma.capture.ResolutionSource.SUPER_RES &&
            p.source != com.datarohit.luma.capture.ResolutionSource.SUPER_RES_ENLARGED) return 1f
        return kotlin.math.sqrt(p.tier.megapixels / p.from.megapixels.toDouble()).toFloat().coerceIn(1f, 2f)
    }

    fun cycleResolution() {
        val all = com.datarohit.luma.settings.PhotoResolution.entries
        val old = _ui.value.settings.photoResolution
        val next = all[(old.ordinal + 1) % all.size]
        updateSettings { s -> s.copy(photoResolution = next) }
        // Only a tier that the current lens exposes natively above its RAW readout needs a new still stream.
        val id = _ui.value.descriptor?.cameraId ?: return
        val streamChanges = listOf(old, next).any { graph.resolutions.plan(it, id)?.needsHighResStream == true }
        if (streamChanges && _ui.value.modeId.engine == Engine.PHOTO) {
            viewModelScope.launch {
                delay(50)
                restart(transition = true)
            }
        }
    }

    fun cycleAspect() = updateSettings { s -> s.copy(aspect = AspectRatio.entries[(s.aspect.ordinal + 1) % AspectRatio.entries.size]) }

    fun switchFacing() {
        val s = _ui.value
        if (front == null || s.recording) return
        if (s.modeId in BACK_ONLY) {
            _ui.update { it.copy(message = "${s.modeId.label} uses the back cameras") }
            return
        }
        _ui.update { it.copy(facingBack = !it.facingBack, zoom = 1f, focusPoint = null, flash = FlashMode.OFF) }
        restart(transition = true)
    }

    fun setPro(transform: (ProState) -> ProState) {
        _ui.update { it.copy(pro = transform(it.pro)) }
        updateShade()
        pushControls()
    }

    fun updatePro(transform: (ProSettings) -> ProSettings) {
        val before = pro.value
        proStore.update(transform)
        val after = pro.value
        val b = before.video
        val a = after.video
        val reconfigure = b.size != a.size || b.fps != a.fps || b.profile != a.profile || b.codec != a.codec ||
            before.slowMoFps != after.slowMoFps || before.multiComboKey != after.multiComboKey ||
            before.dualLayout != after.dualLayout || before.dualSeparateFiles != after.dualSeparateFiles ||
            before.multiSeparateFiles != after.multiSeparateFiles
        if (a.profile == ColorProfile.LOG && b.profile != ColorProfile.LOG && a.lutId == null) selectLut("log709")
        if (_ui.value.modeId.engine == Engine.VIDEO && !_ui.value.recording) {
            val old = _ui.value.videoPlan
            val next = videoPlan()
            if (reconfigure || next == null || old == null || !videoEngine.canReuse(old, next)) restart() else {
                // The running session keeps its streams; the recording plan and GPU rendering follow the change.
                videoEngine.adopt(next)
                _ui.update { it.copy(videoPlan = next) }
                applyDisplayComposition()
                pushControls()
            }
        } else if (b.engine != a.engine || b.look != a.look || b.localTone != a.localTone) {
            applyDisplayComposition()
            pushControls()
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = graph.settings.update(transform)

    fun onDeviceOrientation(degrees: Int) {
        if (degrees != _ui.value.deviceOrientation) _ui.update { it.copy(deviceOrientation = degrees) }
    }

    /** Continuous device rotation (OrientationEventListener degrees) for horizon lock. */
    fun onRawOrientation(degrees: Float) {
        rawOrientation = degrees
        val s = _ui.value
        if (s.modeId.engine == Engine.VIDEO && pro.value.video.stabilization == Stabilization.HORIZON) {
            if (s.recording) videoEngine.updateRoll(degrees, s.deviceOrientation)
            applyDisplayComposition()
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    fun refreshThumbnail() = loadLatestThumbnail()

    // Rack focus and dolly zoom presets (Pro Video).

    fun setRackPoint(a: Boolean) {
        val d = _ui.value.pro.focusDiopters ?: _frameInfo.value?.focusDiopters ?: 0f
        _ui.update { it.copy(rack = if (a) it.rack.copy(a = d) else it.rack.copy(b = d)) }
    }

    fun runRack(durationMs: Long = 1_800) {
        val r = _ui.value.rack
        val a = r.a ?: return
        val b = r.b ?: return
        rackJob?.cancel()
        val from = _ui.value.pro.focusDiopters ?: a
        val to = if (abs(from - a) < abs(from - b)) b else a
        rackJob = viewModelScope.launch {
            _ui.update { it.copy(rack = it.rack.copy(running = true)) }
            val steps = (durationMs / 33).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val e = t * t * (3 - 2 * t)
                setPro { it.copy(focusDiopters = from + (to - from) * e) }
                delay(33)
            }
            _ui.update { it.copy(rack = it.rack.copy(running = false)) }
        }
    }

    fun runDolly(durationMs: Long = 4_000) {
        val s = _ui.value
        dollyJob?.cancel()
        val from = s.zoom.coerceAtLeast(0.6f)
        val to = if (from < 2f) 3f else 1f
        dollyJob = viewModelScope.launch {
            _ui.update { it.copy(dollyRunning = true) }
            val steps = (durationMs / 33).toInt().coerceAtLeast(1)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val e = t * t * (3 - 2 * t)
                setZoom(from * Math.pow((to / from).toDouble(), e.toDouble()).toFloat())
                delay(33)
            }
            _ui.update { it.copy(dollyRunning = false) }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Shutter

    fun shutter() {
        when (_ui.value.modeId.engine) {
            Engine.VIDEO -> toggleRecording()
            Engine.PANORAMA -> togglePanorama()
            Engine.PHOTO -> photoShutter()
        }
    }

    private fun photoShutter() {
        val s = _ui.value
        if (s.countdown != null) {
            timerJob?.cancel()
            _ui.update { it.copy(countdown = null) }
            return
        }
        if (s.capturing || s.processing >= MAX_PENDING || cameraState.value !is CameraState.Streaming) return
        timerJob = viewModelScope.launch {
            val seconds = s.settings.timerSeconds
            for (left in seconds downTo 1) {
                _ui.update { it.copy(countdown = left) }
                delay(1_000L)
            }
            _ui.update { it.copy(countdown = null) }
            capture()
        }
    }

    private suspend fun capture() {
        // Never shoot on the wrong lens: if the zoom asks for another route than the running session (a switch
        // still pending), switch now and let 3A settle before capturing.
        currentDecision()?.let { target ->
            if (_ui.value.modeId.engine == Engine.PHOTO && needsPhotoSession(target)) {
                zoomJob?.cancel()
                _ui.update { it.copy(captureProgress = "Switching lens…") }
                withContext(sessionDispatcher) { sessionMutex.withLock { startPhotoLocked() } }
                renderer.sessionStarted()
                withTimeoutOrNull(2_000) { controller.state.first { it is CameraState.Streaming } }
                delay(LENS_SETTLE_MS)
                _ui.update { it.copy(captureProgress = null) }
            }
        }
        val s = _ui.value
        val d = s.descriptor ?: return
        val decision = currentDecision() ?: return
        val info = _frameInfo.value
        val stats = analysis.value
        val route = s.route
        val chars = d.characteristics
        val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val plan = CapturePlanner.plan(
            PlanInput(
                mode = s.mode,
                previewExposureNs = info?.exposureNs?.takeIf { it > 0 } ?: 20_000_000L,
                previewIso = info?.iso?.takeIf { it > 0 } ?: 100,
                isoMin = isoRange?.lower ?: 50,
                isoMax = isoRange?.upper ?: 3200,
                exposureMinNs = expRange?.lower ?: 10_000L,
                exposureMaxNs = (expRange?.upper ?: 250_000_000L).coerceAtMost(MAX_FRAME_EXPOSURE_NS),
                clippedFraction = stats?.clippedFraction ?: 0f,
                motion = stats?.motion ?: 0f,
                manualExposureNs = if (s.mode == CaptureMode.PRO) s.pro.exposureNs else null,
                manualIso = if (s.mode == CaptureMode.PRO) s.pro.iso else null,
                proMultiFrame = s.settings.proMultiFrame,
                superResolution = superResolutionFor(resolutionPlan(info), route),
            ),
        )
        val rotation = jpegRotation(d, s.deviceOrientation)
        val settings = if (s.modeId == ModeId.FOOD) s.settings.copy(look = Look.FOOD) else s.settings
        val spec = CaptureRequestSpec(
            mode = s.mode,
            plan = plan,
            settings = settings,
            zoom = decision.zoom,
            cameraZoomRatio = decision.cameraZoomRatio,
            rotationDegrees = rotation,
            whiteBalanceKelvin = if (s.mode == CaptureMode.PRO) s.pro.kelvin else null,
            lensDescription = ::lensDescription,
            equivalentFocalMm = { id -> catalog.equivalentFocalMm(id) ?: mainEquivalent },
            sourceLabel = sourceBadge(info).let { "${it.lens} · ${it.kind}" },
            effect = stillEffect(s),
            modeLabel = s.modeId.label,
            resolution = resolutionPlan(info),
        )
        playSound(MediaActionSound.SHUTTER_CLICK)
        _ui.update {
            it.copy(
                capturing = true,
                captureProgress = if (route == CaptureRoute.HAL_PROCESSED) "Capturing…" else if (plan.frames > 1) "Hold still · ${plan.frames} frames" else null,
            )
        }
        try {
            if (route == CaptureRoute.HAL_PROCESSED) {
                _ui.update { it.copy(processing = it.processing + 1) }
                val time = ZonedDateTime.now()
                val still = controller.captureStill(spec.rotationDegrees, spec.settings.jpegQuality)
                _ui.update { it.copy(capturing = false, captureProgress = null) }
                val photo = withContext(processingDispatcher) { graph.processor.saveHalStill(spec, still, time, info?.activePhysicalId) }
                onSaved(photo.thumbnail, photo.jpegUri, photo.summary)
            } else {
                val burst = graph.processor.capture(spec)
                _ui.update { it.copy(capturing = false, captureProgress = null) }
                if (burst == null) {
                    _ui.update { it.copy(message = "No frames arrived from the camera. Try again.") }
                    return
                }
                _ui.update { it.copy(processing = it.processing + 1) }
                viewModelScope.launch {
                    try {
                        val photo = withContext(processingDispatcher) { graph.processor.process(burst) }
                        onSaved(photo.thumbnail, photo.jpegUri, photo.summary)
                    } catch (e: Throwable) {
                        Log.e(TAG, "processing failed", e)
                        _ui.update { it.copy(processing = (it.processing - 1).coerceAtLeast(0), message = "Processing failed: ${e.message}") }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture failed", e)
            _ui.update {
                it.copy(
                    capturing = false,
                    captureProgress = null,
                    processing = if (route == CaptureRoute.HAL_PROCESSED) (it.processing - 1).coerceAtLeast(0) else it.processing,
                    message = "Capture failed: ${e.message}",
                )
            }
        }
    }

    /** Effect for the saved photo: the preview's portrait / food shader, or the Pro tint. */
    private fun stillEffect(s: CameraUiState): ShadeParams? {
        val shade = renderer.shade.copy(peaking = false, zebraIre = 0f, falseColor = false, lutMix = 0f)
        return when {
            s.modeId == ModeId.PORTRAIT || s.modeId == ModeId.FOOD -> shade
            s.modeId == ModeId.PRO_PHOTO && s.pro.tint != 0 && s.pro.kelvin != null -> ShadeParams(tint = s.pro.tint / 50f)
            else -> null
        }
    }

    private fun onSaved(thumbnail: Bitmap, uri: Uri, summary: String) {
        _ui.update { it.copy(processing = (it.processing - 1).coerceAtLeast(0), lastThumbnail = thumbnail, lastUri = uri, lastSummary = summary) }
    }

    private fun playSound(kind: Int) {
        if (_ui.value.settings.shutterSound || MediaActionSound.mustPlayShutterSound()) {
            (sound ?: MediaActionSound().also { sound = it }).play(kind)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Video recording

    private fun toggleRecording() {
        viewModelScope.launch(sessionDispatcher) {
            sessionMutex.withLock {
                if (_ui.value.recording) finishRecordingLocked() else startRecordingLocked()
            }
        }
    }

    private suspend fun startRecordingLocked() {
        val s = _ui.value
        if (s.videoPlan == null || !videoEngine.camera.isOpen) {
            _ui.update { it.copy(message = _videoError.value ?: "Camera is not ready") }
            return
        }
        val ctx = getApplication<Application>()
        val audioOk = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val p = pro.value
        try {
            val active = videoEngine.startRecording(
                controls = videoControls(),
                quadrant = s.deviceOrientation,
                rollDeg = rawOrientation,
                storageTree = p.storageTree?.let(Uri::parse),
                audioAllowed = audioOk,
                lutBurn = p.video.recordLut && p.video.lutId != null,
            )
            playSound(MediaActionSound.START_VIDEO_RECORDING)
            _ui.update { it.copy(recording = true, recordStartMs = active.startedAtMs) }
            levelJob?.cancel()
            active.recorders.firstOrNull()?.let { rec -> levelJob = viewModelScope.launch { rec.levels.collect { _audio.value = it } } }
            if (s.modeId == ModeId.HYPERLAPSE) startHyperlapseAuto()
            if (!audioOk && p.video.audio && s.modeId.video !in setOf(VideoMode.SLOW_MOTION, VideoMode.HYPERLAPSE)) {
                _ui.update { it.copy(message = "Recording without sound: microphone permission is off") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "record start failed", e)
            _ui.update { it.copy(message = "Recording could not start: ${e.message}") }
        }
    }

    private suspend fun finishRecordingLocked() {
        stopSensors()
        levelJob?.cancel()
        _audio.value = AudioLevels()
        val plan = _ui.value.videoPlan
        try {
            val results = videoEngine.stopRecording()
            playSound(MediaActionSound.STOP_VIDEO_RECORDING)
            val first = results.first()
            val thumb = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.loadThumbnail(first.uri, Size(320, 320), null) }.getOrNull()
            }
            val seconds = first.durationMs / 1000
            val summary = "${plan?.config?.label ?: "Video"} · ${plan?.config?.codec?.label ?: ""} · " +
                String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60) +
                (if (results.size > 1) " · ${results.size} files" else "")
            _ui.update { it.copy(recording = false, lastThumbnail = thumb ?: it.lastThumbnail, lastUri = first.uri, lastSummary = summary, message = "Saved $summary") }
        } catch (e: Exception) {
            Log.e(TAG, "record stop failed", e)
            _ui.update { it.copy(recording = false, message = "Recording failed: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Sensors: hyperlapse auto speed and panorama guidance

    private fun stopSensors() {
        sensorListener?.let { sensorManager.unregisterListener(it) }
        sensorListener = null
    }

    private fun startHyperlapseAuto() {
        if (pro.value.hyperlapse != HyperlapseSpeed.AUTO) return
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: return
        var avg = 0f
        var lastApplied = 0
        var lastUpdate = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val w = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                avg = avg * 0.98f + w * 0.02f
                if (e.timestamp - lastUpdate > 1_000_000_000L) {
                    lastUpdate = e.timestamp
                    val f = VideoEngine.hyperlapseFactor(HyperlapseSpeed.AUTO, avg)
                    if (f != lastApplied) {
                        lastApplied = f
                        videoEngine.setHyperlapseRate(f)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        sensorListener = listener
    }

    private class Pano(val focalPx: Float, val fovShort: Float, val fovLong: Float) {
        var stitcher: PanoramaStitcher? = null
        @Volatile var pendingFirst: Bitmap? = null
        var yaw = 0f
        var pitch = 0f
        var lastKey = 0f
        var lastTs = 0L
        @Volatile var busy = false
        var vertical: Boolean? = null
    }

    private var pano: Pano? = null

    private fun togglePanorama() {
        if (_ui.value.panorama.capturing) viewModelScope.launch { finishPanorama() } else startPanorama()
    }

    private fun startPanorama() {
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: run {
            _ui.update { it.copy(message = "Panorama needs a gyroscope") }
            return
        }
        if (cameraState.value !is CameraState.Streaming) return
        val d = _ui.value.descriptor ?: return
        val chars = d.characteristics
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 6.5f
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val shortSide = sensor?.let { minOf(it.width, it.height) } ?: 7.3f
        val longSide = sensor?.let { maxOf(it.width, it.height) } ?: 9.8f
        val zoom = _ui.value.zoom.coerceAtLeast(1f)
        val fovShort = 2f * atan(shortSide / (2f * focal * zoom))
        val fovLong = 2f * atan(longSide / (2f * focal * zoom))
        val frameW = (PANO_MAX_SIDE * shortSide / longSide).roundToInt()
        val p = Pano(PanoramaStitcher.focalFromFov(frameW, fovShort), fovShort, fovLong)
        pano = p
        playSound(MediaActionSound.START_VIDEO_RECORDING)
        _ui.update { it.copy(panorama = PanoramaUi(capturing = true)) }
        renderer.snapshot(0, PANO_MAX_SIDE) { bmp -> p.pendingFirst = bmp }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (p.lastTs != 0L) {
                    val dt = (e.timestamp - p.lastTs) / 1e9f
                    p.yaw += -e.values[1] * dt
                    p.pitch += e.values[0] * dt
                }
                p.lastTs = e.timestamp
                if (p.vertical == null && (abs(p.yaw) > 0.09f || abs(p.pitch) > 0.09f)) p.vertical = abs(p.pitch) > abs(p.yaw)
                val vertical = p.vertical ?: return
                val along = if (vertical) p.pitch else p.yaw
                val across = if (vertical) p.yaw else p.pitch
                val fovAlong = if (vertical) p.fovLong else p.fovShort
                val maxSweep = Math.toRadians(PANO_MAX_DEG.toDouble()).toFloat()
                _ui.update {
                    it.copy(panorama = it.panorama.copy(progress = (abs(along) / maxSweep).coerceIn(0f, 1f),
                        drift = Math.toDegrees(across.toDouble()).toFloat(), vertical = vertical))
                }
                if (abs(along) >= maxSweep) {
                    viewModelScope.launch { finishPanorama() }
                    return
                }
                if (!p.busy && abs(along - p.lastKey) >= fovAlong * KEY_STEP) {
                    p.busy = true
                    val shift = p.focalPx * abs(along - p.lastKey)
                    p.lastKey = along
                    renderer.snapshot(0, PANO_MAX_SIDE) { bmp ->
                        viewModelScope.launch(processingDispatcher) {
                            try {
                                if (bmp != null) addPanoFrame(p, vertical, bmp, shift)
                            } catch (t: Throwable) {
                                Log.w(TAG, "pano frame", t)
                            } finally {
                                p.busy = false
                            }
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        sensorListener = listener
    }

    private fun addPanoFrame(p: Pano, vertical: Boolean, bmp: Bitmap, shift: Float) {
        val st = p.stitcher ?: PanoramaStitcher(p.focalPx, vertical).also { s ->
            p.stitcher = s
            p.pendingFirst?.let { first ->
                s.add(first, 0f)
                first.recycle()
            }
            p.pendingFirst = null
        }
        st.add(bmp, shift)
        bmp.recycle()
        _ui.update { it.copy(panorama = it.panorama.copy(frames = st.frames)) }
    }

    private suspend fun finishPanorama() {
        val p = pano ?: return
        pano = null
        stopSensors()
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        _ui.update { it.copy(panorama = it.panorama.copy(capturing = false, stitching = true)) }
        try {
            val saved = withContext(processingDispatcher) {
                val st = p.stitcher ?: return@withContext null
                val result = st.finish() ?: return@withContext null
                val time = ZonedDateTime.now()
                val info = _frameInfo.value
                val meta = PhotoMetadata(
                    captureTime = time, exifOrientation = MediaStoreWriter.exifOrientation(0), exposureNs = info?.exposureNs ?: 0L,
                    iso = info?.iso ?: 0, aperture = info?.aperture ?: 0f, focalLengthMm = info?.focalLengthMm ?: 0f,
                    focalLength35mm = mainEquivalent.roundToInt(), digitalZoom = 1f, lensModel = "Main camera",
                    software = "Luma", description = "Luma: Panorama · ${st.frames} frames · ${result.width}×${result.height} · stitched by Luma",
                    whiteBalanceManual = false, srgb = true,
                )
                val item = graph.processor.save(result, _ui.value.settings, meta, MediaStoreWriter.baseName(time))
                val thumb = graph.processor.thumbnail(result, 0)
                val label = "Panorama · ${st.frames} frames · ${result.width}×${result.height}"
                result.recycle()
                Triple(item.uri, thumb, label)
            }
            if (saved == null) {
                _ui.update { it.copy(panorama = PanoramaUi(), message = "Sweep further: a panorama needs at least two overlapping frames") }
            } else {
                _ui.update { it.copy(panorama = PanoramaUi(), lastUri = saved.first, lastThumbnail = saved.second, lastSummary = saved.third, message = "Saved ${saved.third}") }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "panorama", e)
            _ui.update { it.copy(panorama = PanoramaUi(), message = "Panorama failed: ${e.message}") }
        } finally {
            p.pendingFirst?.recycle()
        }
    }

    // ------------------------------------------------------------------------------------------
    // Scopes

    @Volatile private var lastScope = 0L

    private fun onAnalysisFrame(img: android.media.Image, sensorOrientation: Int, mirror: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastScope < 90) return
        val s = _ui.value
        val wanted = pro.value.scope != ScopeKind.OFF && (s.modeId.isVideo || s.modeId == ModeId.PRO_PHOTO)
        if (!wanted) return
        lastScope = now
        _scopes.value = runCatching { ScopeAnalyzer.analyze(img, sensorOrientation, mirror) }.getOrNull()
    }

    private fun sensorOrientation(id: String?): Int = id?.let {
        runCatching { catalog.characteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) }.getOrNull()
    } ?: 90

    // ------------------------------------------------------------------------------------------
    // Labels

    private fun jpegRotation(d: CameraDescriptor, deviceOrientation: Int): Int =
        if (d.facingBack) (d.sensorOrientation + deviceOrientation) % 360
        else (d.sensorOrientation - deviceOrientation + 360) % 360

    private fun loadLatestThumbnail() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val item = graph.media.list().firstOrNull { !it.mime.contains("dng") } ?: return@runCatching null
                    val bmp = getApplication<Application>().contentResolver.loadThumbnail(item.uri, Size(320, 320), null)
                    item.uri to bmp
                }.getOrNull()
            }
            _ui.update { s -> if (result == null) s.copy(lastThumbnail = null, lastUri = null) else s.copy(lastUri = result.first, lastThumbnail = result.second) }
        }
    }

    private fun buildPresets(): List<ZoomPreset> {
        val r = router ?: return listOf(ZoomPreset(1f, "1"))
        val list = mutableListOf<ZoomPreset>()
        if (r.minimumZoom < 0.95f) list += ZoomPreset(r.minimumZoom, formatZoom(r.minimumZoom))
        list += ZoomPreset(1f, "1")
        if (r.maximumZoom >= 2f) list += ZoomPreset(2f, "2")
        lenses?.lenses?.map { lenses.nativeZoom(it) }?.filter { it > 1.5f }?.sorted()?.forEach { z -> list += ZoomPreset(z, formatZoom(z)) }
        if (r.maximumZoom >= 10f) list += ZoomPreset(10f, "10")
        if (r.maximumZoom >= 30f) list += ZoomPreset(30f, "30")
        if (r.maximumZoom >= 100f) list += ZoomPreset(100f, "100")
        return list.distinctBy { (it.zoom * 10).roundToInt() }
    }

    private fun formatZoom(z: Float): String = when {
        z < 1f -> String.format(Locale.ROOT, "%.1f", z).removePrefix("0")
        abs(z - z.roundToInt()) < 0.15f -> z.roundToInt().toString()
        else -> String.format(Locale.ROOT, "%.1f", z)
    }

    private fun comboLabel(deviceId: String, physicalId: String?): String {
        val id = physicalId ?: deviceId
        val chars = runCatching { catalog.characteristics(id) }.getOrNull()
        if (chars?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) return "Front"
        if (physicalId == null && chars?.physicalCameraIds?.isNotEmpty() == true) return "Back"
        val eq = catalog.equivalentFocalMm(id) ?: return "Camera $id"
        return formatZoom(eq / mainEquivalent) + "×"
    }

    private fun lensName(id: String?): String {
        if (id == null) return "Camera"
        if (!_ui.value.facingBack) return "Front"
        if (id == ultraWide?.cameraId) return "Ultra-wide"
        val spec = lenses?.lenses?.firstOrNull { it.id == id } ?: return if (id == back?.cameraId) "Main" else "Camera $id"
        val native = lenses.nativeZoom(spec)
        return when {
            native < 0.9f -> "Ultra-wide"
            native < 1.5f -> "Main"
            else -> "${formatZoom(native)}× tele"
        }
    }

    private fun nativeZoom(id: String?): Float {
        if (id == null) return 1f
        if (id == ultraWide?.cameraId) return (catalog.equivalentFocalMm(id) ?: mainEquivalent) / mainEquivalent
        val spec = lenses?.lenses?.firstOrNull { it.id == id } ?: return 1f
        return lenses.nativeZoom(spec)
    }

    fun lensDescription(id: String): String {
        val eq = catalog.equivalentFocalMm(id)
        val f = catalog.aperture(id)
        return buildString {
            append(lensName(id)).append(" camera")
            if (eq != null) append(" ${eq.roundToInt()} mm eq.")
            if (f != null) append(" f/${String.format(Locale.ROOT, "%.1f", f)}")
        }
    }

    /** Source badge for the current preview: real lens, optical/digital, who processes the image. */
    fun sourceBadge(info: FrameInfo?): SourceBadge {
        val s = _ui.value
        if (s.modeId.engine == Engine.VIDEO) {
            val plan = s.videoPlan
            val lens = when {
                plan?.mode == VideoMode.DUAL || plan?.mode == VideoMode.MULTI -> plan.combo?.title ?: "Cameras"
                !s.facingBack -> "Front"
                else -> lensName(info?.activePhysicalId ?: back?.cameraId)
            }
            val native = if (s.facingBack) nativeZoom(info?.activePhysicalId) else 1f
            val crop = if (s.facingBack) s.zoom / native else 1f
            val kind = if (abs(crop - 1f) <= LensRouter.SNAP) "Optical" else "Digital ${String.format(Locale.ROOT, "%.1f", crop)}×"
            return SourceBadge(lens, kind, plan?.config?.let { "${it.label} ${it.profile.short}" } ?: "Video", null)
        }
        val sourceId = when {
            !s.facingBack -> front?.cameraId
            s.route == CaptureRoute.ULTRA_WIDE_RAW -> ultraWide?.cameraId
            else -> info?.activePhysicalId ?: s.descriptor?.cameraId
        }
        val native = if (s.facingBack) nativeZoom(sourceId) else 1f
        val crop = if (s.facingBack) s.zoom / native else 1f
        val kind = if (abs(crop - 1f) <= LensRouter.SNAP) "Optical" else "Digital ${String.format(Locale.ROOT, "%.1f", crop)}×"
        val processedBy = if (s.route == CaptureRoute.HAL_PROCESSED) "Camera HAL" else "Luma RAW"
        var warning: String? = null
        if (s.route == CaptureRoute.HAL_PROCESSED && s.facingBack) {
            val presetNative = lenses?.lenses?.map { lenses.nativeZoom(it) }?.filter { it <= s.zoom * 1.05f }?.maxOrNull()
            if (presetNative != null && native < presetNative * 0.95f) {
                warning = "Camera is cropping the ${lensName(sourceId)} lens (subject too close for the telephoto, or low light)"
            }
        }
        if (s.route == CaptureRoute.MAIN_RAW && s.settings.closeUpHints && info != null && s.facingBack && s.zoom < 1.3f) {
            val min = s.descriptor?.minFocusDiopters ?: 0f
            val notFocused = info.afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED ||
                info.afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            if (min > 0f && info.focusDiopters >= min * 0.92f && notFocused && ultraWide != null) {
                warning = "Too close to focus. Use 0.6× for close-ups"
            }
        }
        return SourceBadge(lensName(sourceId), kind, processedBy, warning)
    }

    private companion object {
        const val TAG = "LumaCameraVM"
        const val ZOOM_SETTLE_MS = 350L
        /** Highest total zoom (camera zoom range, then a GPU crop of the longest telephoto). */
        const val MAX_ZOOM = 100f

        /** 3A settling after a lens switch forced by the shutter. */
        const val LENS_SETTLE_MS = 450L
        /** Preset-to-preset zoom glide (the longest span, 0.6x to 10x). */
        const val ZOOM_GLIDE_MS = 380L
        const val MAX_PENDING = 2
        const val MAX_FRAME_EXPOSURE_NS = 250_000_000L
        const val PANO_MAX_SIDE = 1600
        const val PANO_MAX_DEG = 240f
        const val KEY_STEP = 0.35f
        val EFFECT_MODES = setOf(ModeId.PORTRAIT, ModeId.PORTRAIT_VIDEO, ModeId.FOOD)
        val MACRO_MODES = setOf(ModeId.PHOTO, ModeId.NIGHT, ModeId.FOOD)
        val SETTLED_AF = setOf(
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED,
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
        )
        val BACK_ONLY = setOf(ModeId.PANORAMA, ModeId.SLOW_MOTION, ModeId.DUAL_RECORDING, ModeId.MULTI_RECORDING)
    }
}
