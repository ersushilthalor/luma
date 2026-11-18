package com.datarohit.luma.camera

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.datarohit.luma.capture.CaptureMode
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically

/** Design tokens. Palette unchanged (amber on near-black); greys follow iOS dark-mode fills and labels. */
internal object LumaColors {
    val Amber = Color(0xFFF2B33D)
    val AmberWash = Color(0x2EF2B33D)
    val Glass = Color(0x52000000)
    val GlassStrong = Color(0x8C000000)
    val Fill = Color(0x26FFFFFF)
    val FillSoft = Color(0x14FFFFFF)
    val Hairline = Color(0x33FFFFFF)
    val Sheet = Color(0xFF1C1C1E)
    val Card = Color(0xE01C1C1E)
    val Label2 = Color(0x99EBEBF5)
    val Label3 = Color(0x5CEBEBF5)
    val Record = Color(0xFFFF453A)
    val Night = Color(0xFF8FA3FF)
}

/** Text without font padding, trimmed line height: glyphs sit on the true optical centre of their box. */
internal fun centered(size: TextUnit, weight: FontWeight = FontWeight.Medium, spacing: TextUnit = 0.sp) = TextStyle(
    fontSize = size,
    fontWeight = weight,
    letterSpacing = spacing,
    lineHeight = size,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)

@Composable
internal fun noRipple() = remember { MutableInteractionSource() }

// ---------------------------------------------------------------------------------------------
// Zoom

/**
 * Lens zoomer in the style of rotary camera dials. Collapsed: compact lens buttons in a glass capsule (tap = glide
 * to that lens). Dragging on the buttons, or pinching the viewfinder ([externalScrub] changes), opens a curved
 * disk above them: logarithmic radial ticks (8 per doubling), labelled lens stops, the live value over a centre
 * needle, a haptic tick at every lens stop. The disk follows the finger (drag left = zoom in) and folds away
 * 1.2 s after the last movement.
 */
@Composable
internal fun ZoomControl(
    presets: List<ZoomPreset>,
    zoom: Float,
    minZoom: Float,
    maxZoom: Float,
    rotation: Float,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
    /** True when the control sits on black (photo layout) rather than over the preview. */
    onDark: Boolean = false,
    /** Preset tap (an animated glide); dragging uses [onZoom]. */
    onPreset: (Float) -> Unit = onZoom,
    /** Incremented by the viewfinder on every pinch step, so the disk opens while pinching. */
    externalScrub: Int = 0,
) {
    if (presets.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var scrubbing by remember { mutableStateOf(false) }
    var lastScrub by remember { mutableIntStateOf(0) }
    val current by rememberUpdatedState(zoom)
    val zoomCb by rememberUpdatedState(onZoom)
    val pxPerStop = with(density) { 70.dp.toPx() }
    LaunchedEffect(externalScrub) {
        if (externalScrub > 0) {
            scrubbing = true
            lastScrub++
        }
    }
    LaunchedEffect(lastScrub) {
        if (lastScrub > 0) {
            delay(1_200)
            scrubbing = false
        }
    }
    val dragModifier = Modifier.pointerInput(minZoom, maxZoom) {
        var lastPreset = -1
        detectHorizontalDragGestures(
            onDragStart = { scrubbing = true },
            onDragEnd = { lastScrub++ },
            onDragCancel = { lastScrub++ },
        ) { change, dx ->
            change.consume()
            // Content follows the finger: dragging left brings the higher values to the needle.
            val next = (current * exp(-dx / pxPerStop)).coerceIn(minZoom, maxZoom)
            val nearest = presets.indexOfFirst { abs(ln(next / it.zoom)) < 0.03f }
            if (nearest >= 0 && nearest != lastPreset) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            lastPreset = nearest
            zoomCb(next)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            scrubbing,
            enter = fadeIn(tween(140)) + expandVertically(tween(180), expandFrom = Alignment.Bottom),
            exit = fadeOut(tween(220)) + shrinkVertically(tween(220), shrinkTowards = Alignment.Bottom),
        ) {
            ZoomDisk(zoom, minZoom, maxZoom, presets, dragModifier.padding(bottom = 4.dp))
        }
        val active = presets.minBy { abs(ln(zoom / it.zoom)) }
        Row(
            Modifier.clip(CircleShape).background(if (onDark) Color(0x14FFFFFF) else LumaColors.Glass).padding(4.dp).then(dragModifier),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            presets.forEach { preset ->
                val isActive = preset == active
                val exact = abs(zoom / preset.zoom - 1f) < 0.04f
                val diameter by animateDpAsState(if (isActive) 38.dp else 30.dp, spring(stiffness = 600f), label = "zoomDot")
                val label = when {
                    !isActive -> preset.label
                    exact -> "${preset.label}×"
                    else -> formatZoomValue(zoom) + "×"
                }
                Box(Modifier.size(40.dp).clickable(noRipple(), null) { onPreset(preset.zoom) }, contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(diameter).clip(CircleShape).background(
                            when {
                                isActive -> if (onDark) Color(0x33FFFFFF) else LumaColors.GlassStrong
                                onDark -> Color(0x1FFFFFFF)
                                else -> Color(0x33000000)
                            },
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = centered(if (isActive) 12.sp else 11.sp, FontWeight.SemiBold),
                            color = if (isActive) LumaColors.Amber else Color.White,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.rotate(rotation),
                        )
                    }
                }
            }
        }
    }
}

/** The curved zoom disk: ticks on an arc, rotating under a fixed centre needle. */
@Composable
private fun ZoomDisk(zoom: Float, minZoom: Float, maxZoom: Float, presets: List<ZoomPreset>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = centered(11.sp, FontWeight.SemiBold)
    val valueStyle = centered(17.sp, FontWeight.SemiBold)
    Box(modifier.fillMaxWidth().height(150.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val radius = w * 0.92f
            val cx = w / 2f
            val apex = 44.dp.toPx()
            val cy = apex + radius
            val degPerLn = 34f / ln(2f)            // 34° per doubling of zoom
            val span = 62f
            fun angleOf(z: Float) = (ln(z) - ln(zoom)) * degPerLn
            fun point(deg: Float, r: Float): Offset {
                val a = Math.toRadians((deg - 90f).toDouble())
                return Offset(cx + r * kotlin.math.cos(a).toFloat(), cy + r * kotlin.math.sin(a).toFloat())
            }
            // Disk band, fading out towards both ends.
            val band = 46.dp.toPx()
            drawArc(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent, 0.2f to Color(0xB3000000), 0.8f to Color(0xB3000000), 1f to Color.Transparent,
                ),
                startAngle = -90f - span, sweepAngle = span * 2f, useCenter = false,
                topLeft = Offset(cx - radius + band / 2f, cy - radius + band / 2f),
                size = androidx.compose.ui.geometry.Size((radius - band / 2f) * 2f, (radius - band / 2f) * 2f),
                style = Stroke(width = band),
            )
            // Ticks: 8 per doubling, majors at the lens stops.
            val steps = (ln(maxZoom / minZoom) / ln(2f) * 8f).toInt()
            for (i in 0..steps) {
                val z = minZoom * 2f.pow(i / 8f)
                val deg = angleOf(z)
                if (abs(deg) > span) continue
                val fade = 1f - (abs(deg) / span).pow(3f)
                val major = presets.any { abs(ln(z / it.zoom)) < 0.045f }
                val len = if (major) 16.dp.toPx() else 8.dp.toPx()
                drawLine(
                    Color.White.copy(alpha = (if (major) 0.95f else 0.55f) * fade),
                    point(deg, radius - 4.dp.toPx()), point(deg, radius - 4.dp.toPx() - len),
                    strokeWidth = if (major) 2.dp.toPx() else 1.2.dp.toPx(), cap = StrokeCap.Round,
                )
            }
            for (p in presets) {
                val deg = angleOf(p.zoom)
                if (abs(deg) > span) continue
                val layout = measurer.measure(p.label, labelStyle)
                val at = point(deg, radius - 32.dp.toPx())
                drawText(layout, color = Color.White.copy(alpha = 1f - (abs(deg) / span).pow(3f)),
                    topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
            }
            // Fixed needle and the live value.
            drawLine(LumaColors.Amber, Offset(cx, apex - 2.dp.toPx()), Offset(cx, apex + 22.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
            val value = measurer.measure(formatZoomValue(zoom) + "×", valueStyle)
            drawText(value, color = LumaColors.Amber, topLeft = Offset(cx - value.size.width / 2f, apex - 8.dp.toPx() - value.size.height))
        }
    }
}

internal fun formatZoomValue(z: Float): String = when {
    z < 1f -> String.format(Locale.ROOT, "%.1f", z).removePrefix("0")
    z >= 10f || abs(z - Math.round(z)) < 0.05f -> Math.round(z).toString()
    else -> String.format(Locale.ROOT, "%.1f", z)
}

// ---------------------------------------------------------------------------------------------
// Mode wheel

/** Horizontally sliding mode names; the selected one is always centred, edges fade out smoothly. */
@Composable
internal fun ModeWheel(
    entries: List<WheelEntry>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onSwipe: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val centers = remember { mutableStateMapOf<Int, Float>() }
    var width by remember { mutableIntStateOf(0) }
    val offset = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = centers[selected]?.takeIf { width > 0 }?.let { width / 2f - it }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (!placed) {
            offset.snapTo(target)
            placed = true
        } else {
            offset.animateTo(target, spring(dampingRatio = 0.86f, stiffness = 420f))
        }
    }
    val swipePx = with(LocalDensity.current) { 32.dp.toPx() }
    val swipe by rememberUpdatedState(onSwipe)
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, label = "wheelAlpha")
    Box(
        modifier.fillMaxWidth().height(40.dp)
            .onSizeChanged { width = it.width }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; this.alpha = alpha }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.03f to Color.Transparent, 0.17f to Color.Black, 0.83f to Color.Black, 0.97f to Color.Transparent, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { if (abs(acc) > swipePx) swipe(if (acc < 0) 1 else -1) },
                ) { change, dx ->
                    acc += dx
                    change.consume()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer {
                    translationX = offset.value
                    this.alpha = if (placed) 1f else 0f
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            entries.forEachIndexed { i, entry ->
                val isSelected = i == selected
                val color by animateColorAsState(if (isSelected) LumaColors.Amber else Color.White, tween(180), label = "modeColor")
                Text(
                    entry.label.uppercase(Locale.ROOT),
                    style = centered(13.sp, FontWeight.SemiBold, 1.4.sp),
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .onGloballyPositioned { c -> centers[i] = c.positionInParent().x + c.size.width / 2f }
                        .clickable(noRipple(), null, enabled = enabled) { onSelect(i) }
                        .padding(horizontal = 11.dp, vertical = 12.dp),
                )
            }
        }
    }
}

/** iOS-style segmented control with a sliding thumb. */
@Composable
internal fun SegmentedToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, segment: Dp = 84.dp) {
    val thumbX by animateDpAsState(segment * selected, spring(dampingRatio = 0.8f, stiffness = 500f), label = "segment")
    Box(modifier.clip(CircleShape).background(LumaColors.Fill).padding(2.dp)) {
        Box(Modifier.offset { IntOffset(thumbX.roundToPx(), 0) }.size(segment, 28.dp).clip(CircleShape).background(Color(0x4DFFFFFF)))
        Row {
            options.forEachIndexed { i, label ->
                Box(Modifier.size(segment, 28.dp).clickable(noRipple(), null) { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(label, style = centered(13.sp, FontWeight.SemiBold), color = if (i == selected) Color.White else LumaColors.Label2, maxLines = 1)
                }
            }
        }
    }
}

/** Small capsule chips (option pickers inside panels and sheets). */
@Composable
internal fun ChipRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = centered(12.sp, FontWeight.SemiBold),
                color = if (on) Color.Black else Color.White,
                maxLines = 1,
                modifier = Modifier.clip(CircleShape).background(if (on) LumaColors.Amber else LumaColors.Fill)
                    .clickable { onSelect(i) }.padding(horizontal = 11.dp, vertical = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Ruler dial (Pro controls)

/** Tick ruler: drag sideways to move the value under the amber centre mark. [fraction] is 0..1. */
@Composable
internal fun RulerDial(fraction: Float, onFraction: (Float) -> Unit, modifier: Modifier = Modifier, steps: Int = 60) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val spacing = with(density) { 8.dp.toPx() }
    val total = spacing * steps
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(fraction) }
    val shown = if (dragging) dragFraction else fraction
    val latest by rememberUpdatedState(fraction)
    val report by rememberUpdatedState(onFraction)
    Canvas(
        modifier.height(44.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragFraction = latest },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dx ->
                    change.consume()
                    val before = floor(dragFraction * steps / 5f)
                    dragFraction = (dragFraction - dx / total).coerceIn(0f, 1f)
                    if (floor(dragFraction * steps / 5f) != before) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    report(dragFraction)
                }
            },
    ) {
        val cx = size.width / 2f
        val baseY = size.height * 0.62f
        for (i in 0..steps) {
            val x = cx + (i.toFloat() / steps - shown) * total
            if (x < -2f || x > size.width + 2f) continue
            val major = i % 5 == 0
            val h = (if (major) 14.dp else 8.dp).toPx()
            drawLine(if (major) Color(0xD9FFFFFF) else Color(0x66FFFFFF), Offset(x, baseY - h / 2f), Offset(x, baseY + h / 2f), 1.2.dp.toPx(), StrokeCap.Round)
        }
        val markH = 22.dp.toPx()
        drawLine(LumaColors.Amber, Offset(cx, baseY - markH / 2f), Offset(cx, baseY + markH / 2f), 2.dp.toPx(), StrokeCap.Round)
        val t = 4.dp.toPx()
        val top = baseY - markH / 2f - 3.dp.toPx()
        drawPath(Path().apply { moveTo(cx - t, top - t); lineTo(cx + t, top - t); lineTo(cx, top); close() }, LumaColors.Amber)
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.82f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons

@Composable
internal fun GlassIconButton(rotation: Float, enabled: Boolean = true, onClick: () -> Unit, size: Dp = 36.dp, active: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(if (active) LumaColors.AmberWash else LumaColors.FillSoft)
            .clickable(enabled = enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .rotate(rotation),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Shutter. Photo: white disc (Night blue, Pro amber). Video: red disc that morphs into a rounded square
 * while recording. Panorama: a stop square while sweeping.
 */
@Composable
internal fun ShutterButton(
    mode: ModeId,
    captureMode: CaptureMode,
    capturing: Boolean,
    busy: Boolean,
    streaming: Boolean,
    recording: Boolean,
    onClick: () -> Unit,
) {
    val source = noRipple()
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed || capturing) 0.88f else 1f, spring(dampingRatio = 0.6f, stiffness = 700f), label = "shutter")
    val inner = when {
        mode.isVideo || mode == ModeId.PANORAMA -> LumaColors.Record
        captureMode == CaptureMode.NIGHT -> LumaColors.Night
        captureMode == CaptureMode.PRO -> LumaColors.Amber
        else -> Color.White
    }
    val size by animateDpAsState(if (recording) 30.dp else if (mode.isVideo) 58.dp else 62.dp, spring(dampingRatio = 0.75f, stiffness = 500f), label = "recSize")
    val corner by animateDpAsState(if (recording) 7.dp else 31.dp, spring(dampingRatio = 0.75f, stiffness = 500f), label = "recCorner")
    Box(
        Modifier.size(78.dp).border(4.dp, Color.White, CircleShape)
            .clickable(source, null, enabled = streaming || recording, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).scale(scale).clip(RoundedCornerShape(corner))
                .background(inner.copy(alpha = if (streaming && !busy || recording) 1f else 0.4f)),
            contentAlignment = Alignment.Center,
        ) {
            if (capturing) CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
internal fun ThumbnailButton(thumb: android.graphics.Bitmap?, processing: Boolean, rotation: Float, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).rotate(rotation).clip(RoundedCornerShape(12.dp)).background(Color(0xFF1C1C1E))
            .border(1.dp, LumaColors.Hairline, RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (thumb != null) {
            Image(thumb.asImageBitmap(), contentDescription = "Last capture", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(LumaIcon.GALLERY, Color.White, size = 22.dp)
        }
        if (processing) CircularProgressIndicator(color = LumaColors.Amber, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets

/** Bottom sheet scaffold shared by More Modes and the Pro tools sheet. */
@Composable
internal fun GlassSheet(visible: Boolean, title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(noRipple(), null, onClick = onDismiss))
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 500f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(LumaColors.Sheet)
                    .clickable(noRipple(), null) {}
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color(0x4DFFFFFF)))
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = centered(17.sp, FontWeight.SemiBold), color = Color.White, modifier = Modifier.weight(1f))
                    Text(
                        "Done",
                        style = centered(16.sp, FontWeight.SemiBold),
                        color = LumaColors.Amber,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(6.dp),
                    )
                }
                content()
            }
        }
    }
}

@Composable
internal fun MoreModesSheet(visible: Boolean, current: ModeId, onPick: (ModeId) -> Unit, onDismiss: () -> Unit) {
    com.datarohit.luma.ui.design.IosSheet(visible, "More Modes", onDismiss) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            ModeId.MORE.chunked(4).forEachIndexed { row, items ->
                if (row > 0) Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    items.forEach { mode -> ModeTile(mode, mode == current, Modifier.weight(1f)) { onPick(mode) } }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun ModeTile(mode: ModeId, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(noRipple(), null, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(60.dp).clip(RoundedCornerShape(16.dp))
                .background(if (selected) LumaColors.AmberWash else com.datarohit.luma.ui.design.Ios.Grouped)
                .border(1.dp, if (selected) LumaColors.Amber else Color.Transparent, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(mode.icon, if (selected) LumaColors.Amber else Color.White, size = 26.dp)
            if (mode.isVideo) {
                Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(6.dp).clip(CircleShape).background(LumaColors.Record))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(mode.label, style = com.datarohit.luma.ui.design.Ios.Caption.copy(fontWeight = FontWeight.Medium), color = Color.White, textAlign = TextAlign.Center, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(mode.blurb, color = LumaColors.Label3, fontSize = 9.5.sp, lineHeight = 12.sp, textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** Rounded rect helper for canvases. */
internal fun cornerRadius(dp: Float) = CornerRadius(dp, dp)

internal val ZeroSize = Size(0f, 0f)
