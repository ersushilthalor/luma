package com.datarohit.luma.ui.design

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Luma's design system: iOS dark-mode structure (system greys, inset-grouped lists, SF-like type ramp,
 * 44 dp rows, hairline separators) with Luma's amber as the single accent.
 */
object Ios {
    val Background = Color(0xFF000000)
    val Grouped = Color(0xFF1C1C1E)
    val Elevated = Color(0xFF2C2C2E)
    val Fill = Color(0xFF3A3A3C)
    val Separator = Color(0xFF38383A)
    val Label = Color(0xFFFFFFFF)
    val Label2 = Color(0x99EBEBF5)
    val Label3 = Color(0x4DEBEBF5)
    val Accent = Color(0xFFF2B33D)
    val Destructive = Color(0xFFFF453A)
    val Success = Color(0xFF30D158)

    private fun style(size: TextUnit, weight: FontWeight, tracking: TextUnit = 0.sp, line: TextUnit = size * 1.25f) = TextStyle(
        fontSize = size, fontWeight = weight, letterSpacing = tracking, lineHeight = line,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
    )

    val LargeTitle = style(34.sp, FontWeight.Bold, 0.37.sp, 41.sp)
    val Title2 = style(22.sp, FontWeight.Bold, 0.35.sp)
    val Headline = style(17.sp, FontWeight.SemiBold, (-0.41).sp)
    val Body = style(17.sp, FontWeight.Normal, (-0.41).sp, 22.sp)
    val Callout = style(16.sp, FontWeight.Normal, (-0.32).sp)
    val Subhead = style(15.sp, FontWeight.Normal, (-0.24).sp, 20.sp)
    val Footnote = style(13.sp, FontWeight.Normal, (-0.08).sp, 18.sp)
    val Caption = style(12.sp, FontWeight.Normal, 0.sp, 16.sp)
    val Caption2 = style(11.sp, FontWeight.Medium, 0.07.sp, 13.sp)
}

@Composable
private fun quiet() = remember { MutableInteractionSource() }

// ---------------------------------------------------------------------------------------------
// Navigation

/** Navigation bar: back chevron with the previous screen's name, centred title, optional trailing slot. */
@Composable
fun IosNavBar(title: String, backLabel: String?, onBack: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        if (onBack != null) {
            Row(
                Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(10.dp)).clickable(quiet(), null, onClick = onBack)
                    .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chevron(Ios.Accent, left = true, size = 20.dp)
                if (backLabel != null) Text(backLabel, style = Ios.Body, color = Ios.Accent)
            }
        }
        Text(title, style = Ios.Headline, color = Ios.Label, modifier = Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Screen scaffold: navigation bar and a scrolling body that starts with an optional large title. */
@Composable
fun IosScreen(
    title: String,
    backLabel: String?,
    onBack: (() -> Unit)?,
    largeTitle: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    val scroll = rememberScrollState()
    // Derived: the bar recomposes only when the title crosses the threshold, not on every scrolled pixel.
    val showBarTitle by remember { derivedStateOf { !largeTitle || scroll.value >= 60 } }
    Column(Modifier.fillMaxSize().background(Ios.Background).statusBarsPadding()) {
        IosNavBar(if (showBarTitle) title else "", backLabel, onBack, trailing)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 32.dp)) {
            if (largeTitle) Text(title, style = Ios.LargeTitle, color = Ios.Label, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 12.dp))
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Inset-grouped lists

/** Inset grouped section: caps header, rounded card, footnote footer. Rows inside are separated by hairlines. */
@Composable
fun IosSection(
    header: String? = null,
    footer: String? = null,
    card: Color = Ios.Grouped,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 10.dp)) {
        if (header != null) {
            Text(header.uppercase(), style = Ios.Footnote, color = Ios.Label2, modifier = Modifier.padding(start = 16.dp, bottom = 7.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(card), content = content)
        if (footer != null) {
            Text(footer, style = Ios.Footnote, color = Ios.Label2, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
        }
    }
}

@Composable
fun IosDivider(inset: Int = 16) {
    Box(Modifier.padding(start = inset.dp).fillMaxWidth().height(0.5.dp).background(Ios.Separator))
}

/** Standard 44 dp row: title, optional subtitle, trailing value and chevron. */
@Composable
fun IosRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    titleColor: Color = Ios.Label,
    chevron: Boolean = false,
    check: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Ios.Body, color = titleColor)
            if (subtitle != null) Text(subtitle, style = Ios.Footnote, color = Ios.Label2)
        }
        if (value != null) {
            Text(value, style = Ios.Body, color = Ios.Label2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 220.dp).padding(start = 12.dp))
        }
        trailing?.invoke()
        if (check) Checkmark()
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Chevron(Ios.Label3, left = false, size = 14.dp)
        }
    }
}

@Composable
fun IosToggleRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    IosRow(title, subtitle = subtitle, onClick = { onChange(!checked) }, trailing = { IosSwitch(checked, onChange) })
}

/** Row with a segmented control on the right (2–4 short options). */
@Composable
fun <T> IosSegmentedRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title, style = Ios.Body, color = Ios.Label)
        IosSegmented(options.map { it.second }, options.indexOfFirst { it.first == selected }.coerceAtLeast(0)) { onSelect(options[it].first) }
    }
}

/**
 * Inline picker: shows the current value; tapping expands the options with a checkmark (iOS
 * "inline menu" pattern), so long lists never become chip soup.
 */
@Composable
fun <T> IosPickerRow(title: String, options: List<Pair<T, String>>, selected: T, footer: ((T) -> String?)? = null, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: "–"
    Column {
        IosRow(title, value = current, onClick = { open = !open }, trailing = {
            Spacer(Modifier.width(6.dp))
            UpDownChevron(Ios.Label3)
        })
        AnimatedVisibility(open, enter = expandVertically(tween(180)) + fadeIn(), exit = shrinkVertically(tween(160)) + fadeOut()) {
            Column(Modifier.fillMaxWidth().background(Color(0x0DFFFFFF))) {
                options.forEach { (value, label) ->
                    IosDivider(32)
                    IosRow(label, subtitle = footer?.invoke(value), check = value == selected, onClick = {
                        onSelect(value)
                        open = false
                    })
                }
            }
        }
    }
}

/** Row with a title, current value and a continuous slider underneath. */
@Composable
fun IosSliderRow(title: String, value: Float, valueText: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Ios.Body, color = Ios.Label, modifier = Modifier.weight(1f))
            Text(valueText, style = Ios.Body, color = Ios.Label2)
        }
        IosSlider(value, onChange)
    }
}

/** Centred action row (amber, or red when destructive). */
@Composable
fun IosButtonRow(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
        Text(title, style = Ios.Body, color = if (destructive) Ios.Destructive else Ios.Accent)
    }
}

// ---------------------------------------------------------------------------------------------
// Controls

/** iOS switch, 51 × 31 dp, amber when on. */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (checked) Ios.Accent else Color(0xFF39393D), tween(180), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.75f, stiffness = 600f), label = "thumb")
    Box(Modifier.size(51.dp, 31.dp).clip(CircleShape).background(track).clickable(quiet(), null) { onChange(!checked) }) {
        Box(Modifier.offset { IntOffset(x.roundToPx(), 2.dp.roundToPx()) }.size(27.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

/** iOS segmented control: equal segments, sliding raised thumb. */
@Composable
fun IosSegmented(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF2C2C2E)).padding(2.dp)) {
        val n = options.size.coerceAtLeast(1)
        val segment = maxWidth / n
        val x by animateDpAsState(segment * selected, spring(dampingRatio = 0.85f, stiffness = 600f), label = "seg")
        Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(segment).height(28.dp).shadow(1.dp, RoundedCornerShape(7.dp))
            .clip(RoundedCornerShape(7.dp)).background(Color(0xFF636366)))
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, label ->
                Box(Modifier.weight(1f).height(28.dp).clickable(quiet(), null) { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Text(label, style = Ios.Caption.copy(fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium),
                        color = Ios.Label, maxLines = 1)
                }
            }
        }
    }
}

/** Thin iOS slider with a white 28 dp thumb; [value] is 0..1. */
@Composable
fun IosSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val report by rememberUpdatedState(onChange)
    BoxWithConstraints(modifier.fillMaxWidth().height(28.dp)
        .pointerInput(Unit) {
            detectTapGestures { o -> report((o.x / size.width).coerceIn(0f, 1f)) }
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures { change, _ ->
                change.consume()
                report((change.position.x / size.width).coerceIn(0f, 1f))
            }
        }) {
        val w = maxWidth
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(CircleShape).background(Color(0xFF3A3A3C)))
        Box(Modifier.align(Alignment.CenterStart).width(w * value.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Ios.Accent))
        Box(Modifier.align(Alignment.CenterStart).offset(x = (w - 28.dp) * value.coerceIn(0f, 1f)).size(28.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White))
    }
}

/** Filled capsule button (primary) or plain (secondary). */
@Composable
fun IosButton(title: String, primary: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp))
            .background(if (primary) Ios.Accent else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = Ios.Headline, color = if (primary) Color.Black else Ios.Accent)
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets

/** Bottom sheet: grabber, title, Done; grouped content scrolls inside up to [maxHeight]. */
@Composable
fun IosSheet(visible: Boolean, title: String, onDismiss: () -> Unit, maxHeight: Int = 620, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(Modifier.fillMaxSize().background(Color(0x80000000)).clickable(quiet(), null, onClick = onDismiss))
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(spring(dampingRatio = 0.9f, stiffness = 500f)) { it } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(220)),
        ) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(Ios.Background)
                    .clickable(quiet(), null) {}.navigationBarsPadding(),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).size(width = 36.dp, height = 5.dp).clip(CircleShape).background(Color(0xFF5A5A5E)))
                Box(Modifier.fillMaxWidth().height(48.dp)) {
                    Text(title, style = Ios.Headline, color = Ios.Label, modifier = Modifier.align(Alignment.Center))
                    Text("Done", style = Ios.Headline, color = Ios.Accent,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(8.dp))
                }
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp), content = content)
            }
        }
    }
}

/** iOS action sheet: a card of actions plus a separate Cancel button. */
@Composable
fun IosActionSheet(visible: Boolean, message: String, actions: List<Triple<String, Boolean, () -> Unit>>, onCancel: () -> Unit) {
    BackHandler(enabled = visible, onBack = onCancel)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x80000000)).clickable(quiet(), null, onClick = onCancel))
        }
        AnimatedVisibility(visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ios.Elevated)) {
                    Text(message, style = Ios.Footnote, color = Ios.Label2, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp))
                    actions.forEach { (label, destructive, run) ->
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Ios.Separator))
                        Box(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = run), contentAlignment = Alignment.Center) {
                            Text(label, style = Ios.Body.copy(fontSize = 20.sp), color = if (destructive) Ios.Destructive else Ios.Accent)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(Ios.Elevated).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                    Text("Cancel", style = Ios.Headline.copy(fontSize = 20.sp), color = Ios.Accent)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Glyphs

@Composable
fun Chevron(color: Color, left: Boolean, size: androidx.compose.ui.unit.Dp = 14.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            if (left) {
                moveTo(w * 0.68f, h * 0.12f); lineTo(w * 0.28f, h * 0.5f); lineTo(w * 0.68f, h * 0.88f)
            } else {
                moveTo(w * 0.32f, h * 0.12f); lineTo(w * 0.72f, h * 0.5f); lineTo(w * 0.32f, h * 0.88f)
            }
        }
        drawPath(p, color, style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

@Composable
fun UpDownChevron(color: Color) {
    Canvas(Modifier.size(width = 10.dp, height = 16.dp)) {
        val w = size.width
        val h = size.height
        val s = Stroke(width = w * 0.18f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawPath(Path().apply { moveTo(w * 0.1f, h * 0.38f); lineTo(w * 0.5f, h * 0.12f); lineTo(w * 0.9f, h * 0.38f) }, color, style = s)
        drawPath(Path().apply { moveTo(w * 0.1f, h * 0.62f); lineTo(w * 0.5f, h * 0.88f); lineTo(w * 0.9f, h * 0.62f) }, color, style = s)
    }
}

@Composable
fun Checkmark(color: Color = Ios.Accent) {
    Canvas(Modifier.padding(start = 8.dp).size(16.dp)) {
        val w = size.width
        drawPath(Path().apply { moveTo(w * 0.12f, w * 0.52f); lineTo(w * 0.4f, w * 0.8f); lineTo(w * 0.9f, w * 0.2f) }, color,
            style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
