package com.datarohit.luma.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.datarohit.luma.capture.CaptureMode
import com.datarohit.luma.core.camera.lens.CaptureRoute
import com.datarohit.luma.core.camera.lens.FocusMath
import com.datarohit.luma.core.camera.session.AnalysisStats
import com.datarohit.luma.core.camera.session.CameraState
import com.datarohit.luma.core.camera.session.FlashMode
import com.datarohit.luma.core.camera.session.FrameInfo
import com.datarohit.luma.core.camera.session.ViewPoint
import com.datarohit.luma.video.DualLayout
import com.datarohit.luma.video.HyperlapseSpeed
import com.datarohit.luma.video.ScopeKind
import com.datarohit.luma.video.VideoMode
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

@Composable
fun CameraScreen(
    vm: CameraViewModel,
    tilt: Float?,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val info by vm.frameInfo.collectAsStateWithLifecycle()
    val stats by vm.analysis.collectAsStateWithLifecycle()
    val cameraState by vm.cameraState.collectAsStateWithLifecycle()
    val pro by vm.pro.collectAsStateWithLifecycle()
    val fps by vm.fps.collectAsStateWithLifecycle()
    val audio by vm.audio.collectAsStateWithLifecycle()
    val scopes by vm.scopes.collectAsStateWithLifecycle()
    val videoError by vm.videoError.collectAsStateWithLifecycle()
    val focus by vm.focusReadout.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val iconRotation by animateFloatAsState(-ui.deviceOrientation.toFloat(), label = "iconRotation")
    var showEv by remember { mutableStateOf(false) }
    var evDragging by remember { mutableStateOf(false) }
    var evAccumulator by remember { mutableFloatStateOf(0f) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    // Sheets cover the viewfinder: pause streaming so the system's camera refresh cap (60 Hz) is lifted.
    LaunchedEffect(showTools || showMore) { vm.setPreviewPaused(showTools || showMore) }
    var lastPortrait by rememberSaveable { mutableStateOf(ModeId.PORTRAIT) }
    var lastPro by rememberSaveable { mutableStateOf(ModeId.PRO_PHOTO) }

    val lutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLut) }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            vm.setStorageTree(uri)
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val video = ui.modeId.engine == Engine.VIDEO
    LaunchedEffect(video) {
        if (video && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(ui.modeId) {
        when (ui.modeId.family) {
            ModeFamily.PORTRAIT -> lastPortrait = ui.modeId
            ModeFamily.PRO -> lastPro = ui.modeId
            else -> Unit
        }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            notice = it
            vm.clearMessage()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            notice = null
        }
    }
    LaunchedEffect(ui.focusPoint, ui.evIndex, evDragging) {
        showEv = ui.focusPoint != null
        if (ui.focusPoint != null && !evDragging) {
            delay(5_000)
            showEv = false
        }
    }

    val mode = ui.modeId
    val entries = wheelEntries(mode, lastPortrait, lastPro)
    val selectedIndex = entries.indexOfFirst { it.mode == mode }.coerceAtLeast(0)
    val locked = ui.recording || ui.panorama.capturing
    fun pick(id: ModeId) {
        if (id != vm.ui.value.modeId) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        vm.selectMode(id)
    }
    fun swipe(direction: Int) {
        if (vm.ui.value.recording || vm.ui.value.panorama.capturing) return
        val now = vm.ui.value.modeId
        val list = wheelEntries(now, lastPortrait, lastPro)
        val from = list.indexOfFirst { it.mode == now }.coerceAtLeast(0)
        val target = (from + direction).coerceIn(0, list.lastIndex - 1)
        list[target].mode?.takeIf { target != from }?.let(::pick)
    }
    val evAvailable = ui.mode != CaptureMode.PRO && mode != ModeId.PRO_VIDEO && ui.evRange.last > ui.evRange.first
    val evTrackPx = with(density) { 220.dp.toPx() }
    val timecode = rememberTimecode(ui.recording, ui.recordStartMs, ui.videoPlan?.config?.fps ?: 30)

    val viewfinder: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            DisplaySurface(vm)
            AimingGuide(vm.digitalZoom())
            GestureLayer(
                onTap = { p ->
                    vm.tapToFocus(p)
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onLongPress = {
                    if (mode == ModeId.PRO_PHOTO || mode == ModeId.PRO_VIDEO) {
                        vm.lockFocusToManual()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    } else {
                        vm.resetFocus()
                    }
                },
                onPinch = { factor -> vm.pinchZoom(factor) },
                onVerticalDrag = { dy ->
                    val now = vm.ui.value
                    val span = now.evRange.last - now.evRange.first
                    if (now.focusPoint != null && evAvailable && span > 0) {
                        val pxPerStep = evTrackPx / span
                        evDragging = true
                        evAccumulator -= dy
                        val steps = (evAccumulator / pxPerStep).toInt()
                        if (steps != 0) {
                            vm.setEv(now.evIndex + steps)
                            evAccumulator -= steps * pxPerStep
                        }
                    }
                },
                onVerticalDragEnd = {
                    evDragging = false
                    evAccumulator = 0f
                },
                onSwipe = ::swipe,
            )
            if (!video) AspectMask(ui.settings.aspect)
            if (!video && ui.settings.grid) GridOverlay()
            if (video) FrameGuideOverlay(pro.guide, landscape = ui.deviceOrientation % 180 != 0)
            val levelOn = if (video) pro.levelGauge else ui.settings.level
            if (levelOn && tilt != null) LevelOverlay(tilt)
            if (mode == ModeId.PORTRAIT || mode == ModeId.PORTRAIT_VIDEO) EffectHandle(ui.effectCenter, 0.14f, ellipse = true)
            if (mode == ModeId.FOOD) EffectHandle(ui.effectCenter, pro.foodRadius, ellipse = false)
            if (ui.panorama.capturing) PanoramaGuide(ui.panorama)
            ui.focusPoint?.let { point ->
                val evFraction = if (evAvailable && showEv) (ui.evIndex - ui.evRange.first).toFloat() / (ui.evRange.last - ui.evRange.first) else null
                FocusIndicator(point, info, evFraction)
            }

            // Top cluster: Photo/Video switch, HUD or source chip, scopes.
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mode.sibling?.takeIf { !locked }?.let { sibling ->
                    val photo = if (mode.isVideo) sibling else mode
                    val vid = if (mode.isVideo) mode else sibling
                    SegmentedToggle(listOf("Photo", "Video"), if (mode.isVideo) 1 else 0, onSelect = { pick(if (it == 0) photo else vid) }, segment = 72.dp)
                }
                if (mode == ModeId.PRO_VIDEO) {
                    ProHud(
                        expanded = pro.hudExpanded, onToggle = { e -> vm.updatePro { it.copy(hudExpanded = e) } }, recording = ui.recording,
                        timecode = timecode, fps = fps, plan = ui.videoPlan, info = info, pro = ui.pro, focus = focus, freeBytes = ui.freeBytes,
                        storageLabel = ui.storageLabel, audio = audio, audioOn = pro.video.audio, modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        SourceChip(vm.sourceBadge(info), info, mode, focus, resolution = if (!video) vm.resolutionPlan(info) else null)
                        Spacer(Modifier.weight(1f))
                        if (!video && ui.settings.histogram && pro.scope == ScopeKind.OFF) Histogram(stats)
                    }
                    if (video && ui.recording) RecordPill(timecode)
                }
                if (pro.scope != ScopeKind.OFF && (video || mode == ModeId.PRO_PHOTO)) {
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        ScopeView(pro.scope, scopes, Modifier.size(width = 150.dp, height = 78.dp))
                    }
                }
                if (ui.macroActive) MacroBadge()
                AnimatedVisibility(evDragging, enter = fadeIn(), exit = fadeOut()) {
                    Pill(String.format(Locale.ROOT, "EV %+.1f", ui.evIndex * ui.evStep), LumaColors.Amber)
                }
                AnimatedVisibility(notice != null, enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        notice.orEmpty(), color = Color.White, fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(14.dp)).background(LumaColors.Card).padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            ui.countdown?.let {
                Text(it.toString(), color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light, modifier = Modifier.align(Alignment.Center).rotate(iconRotation))
            }
            val progress = ui.captureProgress ?: if (ui.panorama.stitching) "Stitching panorama…" else null
            progress?.let { Pill(it, Color.White, Modifier.align(Alignment.Center)) }
            StateOverlay(if (video) videoError else (cameraState as? CameraState.Failed)?.message, ui.hasPermission)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(ui, pro.video.profile.short, pro.video.stabilization.label, iconRotation, video,
                onFlash = vm::toggleFlash, onTimer = vm::cycleTimer, onAspect = vm::cycleAspect, onResolution = vm::cycleResolution,
                onRaw = {
                    if (ui.route == CaptureRoute.HAL_PROCESSED) notice = "RAW is not available at this zoom: Luma cannot read autofocused RAW from the telephoto lenses."
                    else vm.updateSettings { it.copy(saveDng = !it.saveDng) }
                },
                onSettings = onOpenSettings,
                onTools = { showTools = true },
            )
            if (!video) {
                // Modes with a contextual row (Pro dials, Portrait aperture, Food) float the zoomer over the bottom of
                // the 4:3 frame; all others put it under the frame, so the band above the mode wheel is never empty.
                val showZoom = ui.facingBack && ui.presets.size > 1 && ui.modeId != ModeId.PANORAMA && ui.modeId != ModeId.FOOD
                val contextual = ui.modeId in setOf(ModeId.PRO_PHOTO, ModeId.PORTRAIT, ModeId.FOOD, ModeId.PANORAMA)
                val pinch by vm.pinchTicks.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                    viewfinder(Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (ui.modeId == ModeId.PORTRAIT) BokehStyles(pro.portraitStyle) { st -> vm.updatePro { it.copy(portraitStyle = st) } }
                        if (showZoom && contextual) {
                            ZoomControl(ui.presets, ui.zoom, ui.minZoom, ui.maxZoom, iconRotation, vm::scrubZoom, onPreset = vm::zoomTo,
                                externalScrub = pinch)
                        }
                    }
                }
                // Controls share the remaining height evenly: no dead band between viewfinder and modes.
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoAccessory(vm, ui, info, focus, pro, iconRotation)
                    if (showZoom && !contextual) {
                        ZoomControl(ui.presets, ui.zoom, ui.minZoom, ui.maxZoom, iconRotation, vm::scrubZoom, onPreset = vm::zoomTo,
                            onDark = true, externalScrub = pinch)
                    }
                    ModeWheel(entries, selectedIndex, enabled = !locked, onSelect = { i -> entries[i].mode?.let(::pick) ?: run { showMore = true } }, onSwipe = ::swipe)
                }
                BottomRow(ui, iconRotation, vm, onGallery = onOpenGallery, streaming = cameraState is CameraState.Streaming, haptics = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) })
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    viewfinder(Modifier.fillMaxWidth().aspectRatio(9f / 16f).align(Alignment.TopCenter))
                    // Translucent dock over the lower part of the 16:9 frame (iOS / Blackmagic style).
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color(0x99000000), 1f to Color(0xE6000000)))
                            .padding(top = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        VideoAccessory(vm, ui, info, focus, pro, iconRotation, onTools = { showTools = true })
                        ModeWheel(entries, selectedIndex, enabled = !locked, onSelect = { i -> entries[i].mode?.let(::pick) ?: run { showMore = true } }, onSwipe = ::swipe)
                        BottomRow(ui, iconRotation, vm, onGallery = onOpenGallery, streaming = videoError == null && !ui.reconfiguring, haptics = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) })
                    }
                }
            }
        }
        MoreModesSheet(showMore, mode, onPick = { id -> pick(id); showMore = false }, onDismiss = { showMore = false })
        ToolsSheet(showTools, vm, ui, pro, onImportLut = { lutPicker.launch(arrayOf("*/*")) }, onPickStorage = { treePicker.launch(null) }, onDismiss = { showTools = false })
    }
}

@Composable
private fun PhotoAccessory(vm: CameraViewModel, ui: CameraUiState, info: FrameInfo?, focus: com.datarohit.luma.core.camera.lens.FocusReadout?, pro: com.datarohit.luma.video.ProSettings, rotation: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (ui.modeId) {
            ModeId.PRO_PHOTO -> ProPanel(proItems(vm, ui, info, focus, video = false))
            ModeId.PORTRAIT -> ApertureRow(pro.portraitFStop, focusText(focus)) { f -> vm.updatePro { it.copy(portraitFStop = f) } }
            ModeId.FOOD -> FoodRow(pro.foodRadius, pro.foodBlur, { r -> vm.updatePro { it.copy(foodRadius = r) } }, { b -> vm.updatePro { it.copy(foodBlur = b) } })
            ModeId.PANORAMA -> Text(
                when {
                    ui.panorama.capturing -> "Sweep slowly · keep the circle on the line · ${ui.panorama.frames} frames"
                    else -> "Tap the shutter, then sweep sideways or up/down. Tap again to finish."
                },
                style = centered(12.sp, FontWeight.Medium), color = LumaColors.Label2, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun VideoAccessory(
    vm: CameraViewModel, ui: CameraUiState, info: FrameInfo?, focus: com.datarohit.luma.core.camera.lens.FocusReadout?,
    pro: com.datarohit.luma.video.ProSettings, rotation: Float, onTools: () -> Unit,
) {
    val plan = ui.videoPlan
    if (ui.facingBack && ui.presets.size > 1 && plan?.mode !in setOf(VideoMode.DUAL, VideoMode.MULTI)) {
        val pinch by vm.pinchTicks.collectAsStateWithLifecycle()
        ZoomControl(ui.presets, ui.zoom, vm.videoMinZoom(), vm.videoMaxZoom(), rotation, vm::scrubZoom, onPreset = vm::zoomTo, externalScrub = pinch)
    }
    when (ui.modeId) {
        ModeId.PRO_VIDEO -> {
            ProPanel(proItems(vm, ui, info, focus, video = true) + gainItem(vm, pro.video.audioGainDb))
            ProVideoActions(ui, vm, onTools)
        }
        ModeId.PORTRAIT_VIDEO -> ApertureRow(pro.portraitFStop, focusText(focus)) { f -> vm.updatePro { it.copy(portraitFStop = f) } }
        ModeId.SLOW_MOTION -> {
            val rates = vm.slowMoRates()
            if (rates.isNotEmpty()) LabeledChips("Speed", rates.map { "$it fps" }, rates.indexOf(plan?.slowMoFps ?: pro.slowMoFps).coerceAtLeast(0)) { i ->
                vm.updatePro { it.copy(slowMoFps = rates[i]) }
            }
        }
        ModeId.HYPERLAPSE -> LabeledChips("Speed", HyperlapseSpeed.entries.map { it.label }, pro.hyperlapse.ordinal) { i -> vm.updatePro { it.copy(hyperlapse = HyperlapseSpeed.entries[i]) } }
        ModeId.DUAL_RECORDING -> {
            LabeledChips("Layout", DualLayout.entries.map { it.label }, pro.dualLayout.ordinal) { i -> vm.updatePro { it.copy(dualLayout = DualLayout.entries[i]) } }
            LabeledChips("Save", listOf("One file", "Two files"), if (pro.dualSeparateFiles) 1 else 0) { i -> vm.updatePro { it.copy(dualSeparateFiles = i == 1) } }
        }
        ModeId.MULTI_RECORDING -> {
            val combos = ui.multiCombos
            if (combos.isNotEmpty()) {
                LabeledChips("Cameras", combos.map { it.title }, combos.indexOfFirst { it.key == plan?.combo?.key }.coerceAtLeast(0)) { i ->
                    vm.updatePro { it.copy(multiComboKey = combos[i].key) }
                }
                plan?.combo?.let { c ->
                    Text("${c.kind.label} · ${c.note}", color = LumaColors.Label3, fontSize = 10.5.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
            LabeledChips("Save", listOf("One file", "Separate"), if (pro.multiSeparateFiles) 1 else 0) { i -> vm.updatePro { it.copy(multiSeparateFiles = i == 1) } }
        }
        else -> Unit
    }
}

@Composable
private fun MacroBadge() {
    Row(Modifier.clip(CircleShape).background(LumaColors.Amber).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("MACRO", style = centered(11.sp, FontWeight.Bold, 0.8.sp), color = Color.Black)
    }
}

@Composable
private fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text, style = centered(12.sp, FontWeight.SemiBold), color = color,
        modifier = modifier.clip(CircleShape).background(LumaColors.GlassStrong).padding(horizontal = 12.dp, vertical = 7.dp))
}

/** Portrait bokeh style chips (glass capsules over the bottom of the frame). */
@Composable
private fun BokehStyles(selected: com.datarohit.luma.gl.BokehStyle, onSelect: (com.datarohit.luma.gl.BokehStyle) -> Unit) {
    Row(
        Modifier.clip(CircleShape).background(LumaColors.Glass).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        com.datarohit.luma.gl.BokehStyle.entries.forEach { st ->
            val on = st == selected
            Text(
                st.label,
                style = centered(12.sp, FontWeight.SemiBold),
                color = if (on) Color.Black else Color.White,
                modifier = Modifier.clip(CircleShape).background(if (on) LumaColors.Amber else Color.Transparent)
                    .clickable { onSelect(st) }.padding(horizontal = 11.dp, vertical = 7.dp),
            )
        }
    }
}

/**
 * High-zoom aiming guide: the renderer draws the whole telephoto frame in an inset (see CameraViewModel AIM_*);
 * this outlines it and marks the part the viewfinder shows, so a subject is easy to find again at 30-100×.
 */
@Composable
private fun AimingGuide(digitalZoom: Float) {
    if (digitalZoom <= 1.05f) return
    Canvas(Modifier.fillMaxSize()) {
        val x = Aim.X * size.width
        val y = Aim.Y * size.height
        val w = Aim.W * size.width
        val h = Aim.H * size.height
        drawRect(Color.White, Offset(x, y), androidx.compose.ui.geometry.Size(w, h), style = Stroke(1.5.dp.toPx()))
        val rw = w / digitalZoom
        val rh = h / digitalZoom
        drawRect(LumaColors.Amber, Offset(x + (w - rw) / 2f, y + (h - rh) / 2f), androidx.compose.ui.geometry.Size(rw, rh), style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun DisplaySurface(vm: CameraViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).apply {
                // The viewfinder is motion content: let it present at the panel's full rate.
                if (android.os.Build.VERSION.SDK_INT >= 35) requestedFrameRate = com.datarohit.luma.maxRefreshRate
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        runCatching { holder.surface.setFrameRate(com.datarohit.luma.maxRefreshRate, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        vm.onDisplaySurface(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = vm.onDisplaySurface(null, 0, 0)
                })
            }
        },
    )
}

/**
 * One gesture surface for the viewfinder: tap focuses, long-press locks (Pro: AF → MF), pinch zooms, a
 * vertical drag after a tap changes exposure, and a horizontal swipe changes mode.
 */
@Composable
private fun GestureLayer(
    onTap: (ViewPoint) -> Unit,
    onLongPress: () -> Unit,
    onPinch: (Float) -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalDragEnd: () -> Unit,
    onSwipe: (Int) -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    val pinch by rememberUpdatedState(onPinch)
    val vDrag by rememberUpdatedState(onVerticalDrag)
    val vDragEnd by rememberUpdatedState(onVerticalDragEnd)
    val swipe by rememberUpdatedState(onSwipe)
    val swipePx = with(LocalDensity.current) { 56.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { o -> tap(ViewPoint(o.x / size.width, o.y / size.height)) }, onLongPress = { longPress() })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var axis = AXIS_NONE
                    var total = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            if (axis == AXIS_VERTICAL) vDragEnd()
                            axis = AXIS_PINCH
                            val zoom = event.calculateZoom()
                            if (abs(zoom - 1f) > 0.001f) pinch(zoom)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (axis != AXIS_PINCH) {
                            val change = event.changes.firstOrNull { it.pressed }
                            if (change != null) {
                                val d = change.positionChange()
                                total += d
                                if (axis == AXIS_NONE && total.getDistance() > viewConfiguration.touchSlop) {
                                    axis = if (abs(total.x) > abs(total.y)) AXIS_HORIZONTAL else AXIS_VERTICAL
                                }
                                if (axis == AXIS_VERTICAL) vDrag(d.y)
                                if (axis != AXIS_NONE) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    when (axis) {
                        AXIS_HORIZONTAL -> if (abs(total.x) > swipePx) swipe(if (total.x < 0) 1 else -1)
                        AXIS_VERTICAL -> vDragEnd()
                    }
                }
            },
    )
}

private const val AXIS_NONE = 0
private const val AXIS_HORIZONTAL = 1
private const val AXIS_VERTICAL = 2
private const val AXIS_PINCH = 3

@Composable
private fun Histogram(stats: AnalysisStats?) {
    val s = stats ?: return
    Canvas(Modifier.size(width = 108.dp, height = 52.dp).clip(RoundedCornerShape(10.dp)).background(LumaColors.Glass)) {
        val bins = s.histogram
        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val pad = 6.dp.toPx()
        val bw = (size.width - pad * 2) / bins.size
        bins.forEachIndexed { i, v ->
            val h = (ln(1f + v) / ln(1f + max)) * (size.height - pad * 2)
            drawRect(if (i >= bins.size - 2 && v > 0) Color(0xFFFF8A80) else Color(0xB3FFFFFF), Offset(pad + i * bw, size.height - pad - h), Size(bw * 0.85f, h))
        }
    }
}

@Composable
private fun SourceChip(
    badge: SourceBadge,
    info: FrameInfo?,
    mode: ModeId,
    focus: com.datarohit.luma.core.camera.lens.FocusReadout?,
    resolution: com.datarohit.luma.capture.ResolutionPlan? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${badge.lens} · ${badge.kind} · ${badge.processedBy}", color = Color.White, style = centered(11.sp, FontWeight.Medium),
            modifier = Modifier.clip(CircleShape).background(LumaColors.Glass).padding(horizontal = 10.dp, vertical = 6.dp))
        // Higher tiers: say on this lens whether the size is real sensor resolution or enlarged.
        AnimatedVisibility(resolution != null && resolution.tier != com.datarohit.luma.settings.PhotoResolution.MP12,
            enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            val r = resolution ?: return@AnimatedVisibility
            val native = r.realDetail
            Text("${badge.lens} · ${r.label}", color = if (native) Color.White else LumaColors.Amber, style = centered(11.sp, FontWeight.Medium),
                modifier = Modifier.clip(CircleShape).background(LumaColors.Glass).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        if ((mode == ModeId.PRO_PHOTO || mode == ModeId.PORTRAIT || mode == ModeId.FOOD) && info != null) {
            val text = if (mode == ModeId.PRO_PHOTO) "ISO ${info.iso} · ${formatShutter(info.exposureNs)} · f/${String.format(Locale.ROOT, "%.1f", info.aperture)} · ${focusText(focus)}"
            else "Subject ${focusText(focus)}"
            Text(text, color = Color.White, style = centered(11.sp, FontWeight.Medium),
                modifier = Modifier.clip(CircleShape).background(LumaColors.Glass).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        badge.warning?.let {
            Text(it, color = Color(0xFF1A1206), fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.widthIn(max = 240.dp).clip(RoundedCornerShape(10.dp)).background(LumaColors.Amber).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun StateOverlay(error: String?, permission: Boolean) {
    val text = when {
        !permission -> "Camera access is needed."
        error != null -> error
        else -> null
    } ?: return
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
    }
}

@Composable
private fun TopBar(
    ui: CameraUiState,
    profile: String,
    stabilization: String,
    rotation: Float,
    video: Boolean,
    onFlash: () -> Unit,
    onTimer: () -> Unit,
    onAspect: () -> Unit,
    onResolution: () -> Unit,
    onRaw: () -> Unit,
    onSettings: () -> Unit,
    onTools: () -> Unit,
) {
    // Equal columns: every control sits on the same centre line.
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        val cell = Modifier.weight(1f)
        Box(cell, contentAlignment = Alignment.Center) {
            val on = ui.flash == FlashMode.TORCH
            GlassIconButton(rotation, enabled = ui.hasFlash && ui.facingBack, onClick = onFlash, active = on) {
                Icon(if (on) LumaIcon.FLASH else LumaIcon.FLASH_OFF, if (on) LumaColors.Amber else Color.White, size = 20.dp)
            }
        }
        if (video) {
            Box(Modifier.weight(3f), contentAlignment = Alignment.Center) {
                val c = ui.videoPlan?.config
                FormatCapsule(
                    listOfNotNull(c?.label?.replace(" ", " · "), c?.codec?.label, profile, stabilization.replace(" + ", "+")),
                    highlighted = c?.profile?.let { it != com.datarohit.luma.video.ColorProfile.SDR } == true,
                    onClick = onTools,
                )
            }
        } else {
            Box(cell, contentAlignment = Alignment.Center) {
                val t = ui.settings.timerSeconds
                GlassIconButton(rotation, onClick = onTimer, active = t > 0) {
                    if (t > 0) Text("${t}s", style = centered(13.sp, FontWeight.SemiBold), color = LumaColors.Amber)
                    else Icon(LumaIcon.TIMER, Color.White, size = 20.dp)
                }
            }
            Box(cell, contentAlignment = Alignment.Center) {
                GlassIconButton(rotation, onClick = onAspect) { Text(ui.settings.aspect.label, style = centered(12.sp, FontWeight.SemiBold), color = Color.White) }
            }
            Box(cell, contentAlignment = Alignment.Center) {
                val r = ui.settings.photoResolution
                val high = r != com.datarohit.luma.settings.PhotoResolution.MP12
                GlassIconButton(rotation, onClick = onResolution, active = high) {
                    Text(r.label.removeSuffix("MP"), style = centered(if (r.megapixels >= 100) 12.sp else 13.sp, FontWeight.SemiBold),
                        color = if (high) LumaColors.Amber else Color.White)
                }
            }
            Box(cell, contentAlignment = Alignment.Center) {
                val rawAvailable = ui.route != CaptureRoute.HAL_PROCESSED
                val on = ui.settings.saveDng && rawAvailable
                GlassIconButton(rotation, onClick = onRaw, active = on) {
                    Text("RAW", style = centered(9.sp, FontWeight.Bold, 0.4.sp),
                        color = when {
                            !rawAvailable -> Color(0x66FFFFFF)
                            on -> LumaColors.Amber
                            else -> Color.White
                        },
                        modifier = Modifier.border(1.2.dp, if (on) LumaColors.Amber else Color(0x80FFFFFF), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 3.dp))
                }
            }
        }
        Box(cell, contentAlignment = Alignment.Center) {
            GlassIconButton(rotation, onClick = onSettings) { Icon(LumaIcon.SETTINGS, Color.White, size = 20.dp) }
        }
    }
}

/** iOS-camera style format indicator ("4K · 30 | HEVC | SDR"), tap for Pro Tools. */
@Composable
private fun FormatCapsule(parts: List<String>, highlighted: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(LumaColors.FillSoft).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.padding(horizontal = 9.dp).size(width = 1.dp, height = 12.dp).background(Color(0x40FFFFFF)))
            Text(p, style = centered(12.sp, FontWeight.SemiBold, 0.2.sp), color = if (highlighted && i == 2) LumaColors.Amber else Color.White, maxLines = 1)
        }
    }
}

@Composable
private fun BottomRow(ui: CameraUiState, rotation: Float, vm: CameraViewModel, onGallery: () -> Unit, streaming: Boolean, haptics: () -> Unit) {
    // Three equal columns keep the shutter on the exact horizontal centre.
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ThumbnailButton(ui.lastThumbnail, ui.processing > 0 || ui.panorama.stitching, rotation, onGallery)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ShutterButton(
                mode = ui.modeId,
                captureMode = ui.mode,
                capturing = ui.capturing,
                busy = ui.capturing || ui.processing >= 2 || ui.panorama.stitching,
                streaming = streaming || ui.countdown != null,
                recording = ui.recording || ui.panorama.capturing,
                onClick = {
                    haptics()
                    vm.shutter()
                },
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            GlassIconButton(rotation, enabled = !ui.recording, onClick = vm::switchFacing, size = 48.dp) { Icon(LumaIcon.FLIP, Color.White, size = 22.dp) }
        }
    }
}
