package com.datarohit.luma.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.datarohit.luma.core.camera.session.FrameInfo
import com.datarohit.luma.core.camera.session.ViewPoint
import com.datarohit.luma.settings.AspectRatio
import com.datarohit.luma.video.FrameGuide
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Scrim = Color(0x99000000)

/** Darkens the parts of the 3:4 frame that a 16:9 or 1:1 photo will not contain. */
@Composable
internal fun AspectMask(aspect: AspectRatio) {
    if (aspect == AspectRatio.RATIO_4_3) return
    Canvas(Modifier.fillMaxSize()) {
        val captureHeight = size.width * aspect.widthOverHeight
        val bar = ((size.height - captureHeight) / 2f).coerceAtLeast(0f)
        drawRect(Scrim, Offset.Zero, Size(size.width, bar))
        drawRect(Scrim, Offset(0f, size.height - bar), Size(size.width, bar))
    }
}

@Composable
internal fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color(0x59FFFFFF)
        val w = 1.dp.toPx()
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), w)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), w)
        }
    }
}

/** Cinema frame guides: aspect masks with a hairline, thirds or a centre cross. */
@Composable
internal fun FrameGuideOverlay(guide: FrameGuide, landscape: Boolean) {
    if (guide == FrameGuide.NONE) return
    Canvas(Modifier.fillMaxSize()) {
        val line = Color(0xB3FFFFFF)
        val w = 1.dp.toPx()
        when (guide) {
            FrameGuide.THIRDS -> for (i in 1..2) {
                drawLine(line, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), w)
                drawLine(line, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), w)
            }
            FrameGuide.CROSS -> {
                val c = center
                val l = 14.dp.toPx()
                drawLine(line, Offset(c.x - l, c.y), Offset(c.x + l, c.y), w * 1.5f)
                drawLine(line, Offset(c.x, c.y - l), Offset(c.x, c.y + l), w * 1.5f)
            }
            else -> {
                // Guides describe the recorded frame; in portrait the recorded frame is the preview rotated.
                val a = guide.aspect ?: return@Canvas
                val target = if (landscape) 1f / a else a
                val current = size.width / size.height
                if (target > current) {
                    val h = size.width / target
                    val bar = (size.height - h) / 2f
                    drawRect(Color(0x80000000), Offset.Zero, Size(size.width, bar))
                    drawRect(Color(0x80000000), Offset(0f, size.height - bar), Size(size.width, bar))
                    drawLine(line, Offset(0f, bar), Offset(size.width, bar), w)
                    drawLine(line, Offset(0f, size.height - bar), Offset(size.width, size.height - bar), w)
                } else {
                    val wd = size.height * target
                    val bar = (size.width - wd) / 2f
                    drawRect(Color(0x80000000), Offset.Zero, Size(bar, size.height))
                    drawRect(Color(0x80000000), Offset(size.width - bar, 0f), Size(bar, size.height))
                    drawLine(line, Offset(bar, 0f), Offset(bar, size.height), w)
                    drawLine(line, Offset(size.width - bar, 0f), Offset(size.width - bar, size.height), w)
                }
            }
        }
    }
}

/** Horizon level: a line that turns amber when the phone is level. */
@Composable
internal fun LevelOverlay(tilt: Float) {
    if (abs(tilt) > 12f) return
    val level = abs(tilt) < 0.8f
    Canvas(Modifier.fillMaxSize()) {
        val c = if (level) LumaColors.Amber else Color(0xCCFFFFFF)
        val center = Offset(size.width / 2, size.height / 2)
        val half = size.width * 0.18f
        val rad = Math.toRadians(tilt.toDouble())
        val dx = (half * cos(rad)).toFloat()
        val dy = (half * sin(rad)).toFloat()
        val w = 1.5.dp.toPx()
        drawLine(c, Offset(center.x - dx, center.y - dy), Offset(center.x + dx, center.y + dy), w, StrokeCap.Round)
        drawLine(Color(0x66FFFFFF), Offset(center.x - half * 1.25f, center.y), Offset(center.x - half * 1.08f, center.y), w, StrokeCap.Round)
        drawLine(Color(0x66FFFFFF), Offset(center.x + half * 1.08f, center.y), Offset(center.x + half * 1.25f, center.y), w, StrokeCap.Round)
    }
}

/** Square focus box with side ticks and, after a tap, the exposure sun on its track. */
@Composable
internal fun FocusIndicator(point: ViewPoint, info: FrameInfo?, evFraction: Float?) {
    val focused = info?.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
    val failed = info?.afState == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
    val sunAlpha by animateFloatAsState(if (evFraction != null) 1f else 0f, label = "sun")
    var lastFraction by remember { mutableFloatStateOf(0.5f) }
    if (evFraction != null) lastFraction = evFraction
    Canvas(Modifier.fillMaxSize()) {
        val c = when {
            focused -> LumaColors.Amber
            failed -> Color(0xFFFF8A80)
            else -> Color.White
        }
        val center = Offset(point.x * size.width, point.y * size.height)
        val half = 36.dp.toPx()
        val sw = 1.4.dp.toPx()
        val tick = 5.dp.toPx()
        drawRect(c, Offset(center.x - half, center.y - half), Size(half * 2, half * 2), style = Stroke(sw))
        drawLine(c, Offset(center.x, center.y - half), Offset(center.x, center.y - half + tick), sw)
        drawLine(c, Offset(center.x, center.y + half), Offset(center.x, center.y + half - tick), sw)
        drawLine(c, Offset(center.x - half, center.y), Offset(center.x - half + tick, center.y), sw)
        drawLine(c, Offset(center.x + half, center.y), Offset(center.x + half - tick, center.y), sw)
        if (sunAlpha > 0.01f) {
            val gap = 16.dp.toPx()
            val onRight = center.x + half + gap * 2 < size.width
            val x = if (onRight) center.x + half + gap else center.x - half - gap
            val track = 120.dp.toPx()
            val sunY = center.y + track / 2f - lastFraction * track
            val sunR = 5.dp.toPx()
            val clear = 11.dp.toPx()
            val trackColor = LumaColors.Amber.copy(alpha = 0.75f * sunAlpha)
            if (sunY - clear > center.y - track / 2f) drawLine(trackColor, Offset(x, center.y - track / 2f), Offset(x, sunY - clear), 1.dp.toPx())
            if (sunY + clear < center.y + track / 2f) drawLine(trackColor, Offset(x, sunY + clear), Offset(x, center.y + track / 2f), 1.dp.toPx())
            val sun = LumaColors.Amber.copy(alpha = sunAlpha)
            drawCircle(sun, sunR * 0.75f, Offset(x, sunY))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val inner = sunR * 1.25f
                val outer = sunR * 1.75f
                drawLine(sun, Offset(x + inner * cos(a).toFloat(), sunY + inner * sin(a).toFloat()),
                    Offset(x + outer * cos(a).toFloat(), sunY + outer * sin(a).toFloat()), 1.4.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/** Effect handle: the sharp zone of Portrait (ellipse) or Food (ring). */
@Composable
internal fun EffectHandle(center: ViewPoint, radius: Float, ellipse: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(center.x * size.width, center.y * size.height)
        val r = radius * size.height
        val color = Color(0x99FFFFFF)
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        if (ellipse) {
            drawOval(color, Offset(c.x - r * 0.75f, c.y - r * 1.3f), Size(r * 1.5f, r * 2.6f), style = Stroke(1.2.dp.toPx(), pathEffect = dash))
        } else {
            drawCircle(color, r, c, style = Stroke(1.2.dp.toPx(), pathEffect = dash))
        }
    }
}

/** Panorama guidance: the sweep axis, a drift marker to keep on it, and progress. */
@Composable
internal fun PanoramaGuide(p: PanoramaUi) {
    Canvas(Modifier.fillMaxSize()) {
        val vertical = p.vertical == true
        val line = Color(0xB3FFFFFF)
        val w = 1.5.dp.toPx()
        val c = center
        if (vertical) drawLine(line, Offset(c.x, 0f), Offset(c.x, size.height), w) else drawLine(line, Offset(0f, c.y), Offset(size.width, c.y), w)
        // Drift marker: 1° of drift = 6 dp off the axis.
        val off = (p.drift * 6.dp.toPx()).coerceIn(-size.minDimension / 3, size.minDimension / 3)
        val ok = abs(p.drift) < 2.5f
        val marker = if (vertical) Offset(c.x + off, c.y) else Offset(c.x, c.y + off)
        drawCircle(if (ok) LumaColors.Amber else LumaColors.Record, 9.dp.toPx(), marker, style = Stroke(2.dp.toPx()))
        // Arrow showing the sweep direction.
        val arrow = Path().apply {
            val s = 10.dp.toPx()
            if (vertical) {
                moveTo(c.x - s, c.y - 40.dp.toPx()); lineTo(c.x, c.y - 40.dp.toPx() - s); lineTo(c.x + s, c.y - 40.dp.toPx())
            } else {
                moveTo(c.x + 40.dp.toPx(), c.y - s); lineTo(c.x + 40.dp.toPx() + s, c.y); lineTo(c.x + 40.dp.toPx(), c.y + s)
            }
        }
        drawPath(arrow, Color.White, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        // Progress bar along the bottom.
        val barY = size.height - 6.dp.toPx()
        drawLine(Color(0x55FFFFFF), Offset(24.dp.toPx(), barY), Offset(size.width - 24.dp.toPx(), barY), 4.dp.toPx(), StrokeCap.Round)
        val end = 24.dp.toPx() + (size.width - 48.dp.toPx()) * p.progress
        drawLine(LumaColors.Amber, Offset(24.dp.toPx(), barY), Offset(end, barY), 4.dp.toPx(), StrokeCap.Round)
    }
}
