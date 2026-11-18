package com.datarohit.luma.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Small line icons drawn in code (no icon library dependency). */
enum class LumaIcon {
    FLASH, FLASH_OFF, TIMER, SETTINGS, FLIP, GALLERY, INFO, DELETE, SHARE, BACK, GRID,
    MOON, PERSON, APERTURE, VIDEO, SLIDERS, FOOD, PANORAMA, SLOW_MOTION, HYPERLAPSE, DUAL, MULTI, SUN,
}

@Composable
fun Icon(icon: LumaIcon, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { draw(icon, tint) }
}

private fun DrawScope.draw(icon: LumaIcon, c: Color) {
    val w = size.width
    val s = w / 24f
    val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    when (icon) {
        LumaIcon.FLASH, LumaIcon.FLASH_OFF -> {
            val path = Path().apply {
                moveTo(13f * s, 2f * s); lineTo(5f * s, 13.5f * s); lineTo(11f * s, 13.5f * s)
                lineTo(10f * s, 22f * s); lineTo(19f * s, 10f * s); lineTo(13f * s, 10f * s); close()
            }
            drawPath(path, c, style = stroke)
            if (icon == LumaIcon.FLASH_OFF) drawLine(c, p(3f, 3f), p(21f, 21f), stroke.width, StrokeCap.Round)
        }
        LumaIcon.TIMER -> {
            drawCircle(c, 8.5f * s, p(12f, 13.5f), style = stroke)
            drawLine(c, p(12f, 13.5f), p(12f, 8.5f), stroke.width, StrokeCap.Round)
            drawLine(c, p(9.5f, 2.5f), p(14.5f, 2.5f), stroke.width, StrokeCap.Round)
        }
        LumaIcon.SETTINGS -> {
            drawCircle(c, 3.2f * s, p(12f, 12f), style = stroke)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(c, p(12f + 6.5f * cos(a).toFloat(), 12f + 6.5f * sin(a).toFloat()),
                    p(12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat()), stroke.width * 1.4f, StrokeCap.Round)
            }
            drawCircle(c, 6.6f * s, p(12f, 12f), style = stroke)
        }
        LumaIcon.FLIP -> {
            drawArc(c, 200f, 250f, false, topLeft = p(4f, 4f), size = Size(16f * s, 16f * s), style = stroke)
            val tip = p(4.6f, 8.2f)
            drawLine(c, tip, p(4.2f, 3.6f), stroke.width, StrokeCap.Round)
            drawLine(c, tip, p(8.8f, 7.6f), stroke.width, StrokeCap.Round)
        }
        LumaIcon.GALLERY -> {
            drawRoundRect(c, topLeft = p(3f, 4f), size = Size(18f * s, 16f * s), style = stroke)
            val path = Path().apply { moveTo(5f * s, 18f * s); lineTo(10f * s, 12f * s); lineTo(14f * s, 16f * s); lineTo(16f * s, 14f * s); lineTo(19f * s, 18f * s) }
            drawPath(path, c, style = stroke)
        }
        LumaIcon.INFO -> {
            drawCircle(c, 9.5f * s, p(12f, 12f), style = stroke)
            drawLine(c, p(12f, 11f), p(12f, 17f), stroke.width, StrokeCap.Round)
            drawCircle(c, 1.2f * s, p(12f, 7.5f))
        }
        LumaIcon.DELETE -> {
            drawLine(c, p(4f, 6f), p(20f, 6f), stroke.width, StrokeCap.Round)
            drawLine(c, p(9f, 6f), p(10f, 3f), stroke.width, StrokeCap.Round)
            drawLine(c, p(15f, 6f), p(14f, 3f), stroke.width, StrokeCap.Round)
            val path = Path().apply { moveTo(6f * s, 6f * s); lineTo(7f * s, 21f * s); lineTo(17f * s, 21f * s); lineTo(18f * s, 6f * s) }
            drawPath(path, c, style = stroke)
        }
        LumaIcon.SHARE -> {
            drawCircle(c, 2.5f * s, p(18f, 5f), style = stroke)
            drawCircle(c, 2.5f * s, p(6f, 12f), style = stroke)
            drawCircle(c, 2.5f * s, p(18f, 19f), style = stroke)
            drawLine(c, p(8.2f, 10.8f), p(15.8f, 6.2f), stroke.width, StrokeCap.Round)
            drawLine(c, p(8.2f, 13.2f), p(15.8f, 17.8f), stroke.width, StrokeCap.Round)
        }
        LumaIcon.BACK -> {
            drawLine(c, p(4f, 12f), p(20f, 12f), stroke.width, StrokeCap.Round)
            drawLine(c, p(4f, 12f), p(10f, 6f), stroke.width, StrokeCap.Round)
            drawLine(c, p(4f, 12f), p(10f, 18f), stroke.width, StrokeCap.Round)
        }
        LumaIcon.MOON -> {
            val outer = Path().apply { addOval(Rect(p(12f, 12.5f), 8.5f * s)) }
            val inner = Path().apply { addOval(Rect(p(17f, 8.5f), 7f * s)) }
            clipPath(inner, ClipOp.Difference) { drawCircle(c, 8.5f * s, p(12f, 12.5f), style = stroke) }
            clipPath(outer) { drawCircle(c, 7f * s, p(17f, 8.5f), style = stroke) }
        }
        LumaIcon.PERSON -> {
            drawCircle(c, 3.8f * s, p(12f, 8f), style = stroke)
            drawArc(c, 180f, 180f, false, topLeft = p(4.5f, 14.5f), size = Size(15f * s, 13f * s), style = stroke)
        }
        LumaIcon.APERTURE -> {
            drawCircle(c, 9f * s, p(12f, 12f), style = stroke)
            for (i in 0 until 6) {
                val a = Math.toRadians(i * 60.0)
                val b = Math.toRadians(i * 60.0 + 110.0)
                drawLine(c, p(12f + 9f * cos(a).toFloat(), 12f + 9f * sin(a).toFloat()),
                    p(12f + 4f * cos(b).toFloat(), 12f + 4f * sin(b).toFloat()), stroke.width, StrokeCap.Round)
            }
        }
        LumaIcon.VIDEO -> {
            drawRoundRect(c, topLeft = p(2.5f, 6.5f), size = Size(13f * s, 11f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * s), style = stroke)
            val path = Path().apply { moveTo(15.5f * s, 10.5f * s); lineTo(21.5f * s, 7f * s); lineTo(21.5f * s, 17f * s); lineTo(15.5f * s, 13.5f * s) }
            drawPath(path, c, style = stroke)
        }
        LumaIcon.SLIDERS -> {
            listOf(6f to 15f, 12f to 8f, 18f to 13f).forEach { (y, x) ->
                drawLine(c, p(3.5f, y), p(20.5f, y), stroke.width, StrokeCap.Round)
                drawCircle(c, 2.4f * s, p(x, y))
            }
        }
        LumaIcon.FOOD -> {
            drawLine(c, p(7f, 3f), p(7f, 21f), stroke.width, StrokeCap.Round)
            drawLine(c, p(4.5f, 3f), p(4.5f, 8f), stroke.width, StrokeCap.Round)
            drawLine(c, p(9.5f, 3f), p(9.5f, 8f), stroke.width, StrokeCap.Round)
            drawArc(c, 0f, 180f, false, topLeft = p(4.5f, 5.5f), size = Size(5f * s, 5f * s), style = stroke)
            val knife = Path().apply {
                moveTo(17f * s, 21f * s); lineTo(17f * s, 3f * s)
                quadraticTo(20.5f * s, 6f * s, 20f * s, 13f * s); lineTo(17f * s, 13f * s)
            }
            drawPath(knife, c, style = stroke)
        }
        LumaIcon.PANORAMA -> {
            val path = Path().apply {
                moveTo(2f * s, 7f * s); quadraticTo(12f * s, 4.5f * s, 22f * s, 7f * s); lineTo(22f * s, 17f * s)
                quadraticTo(12f * s, 19.5f * s, 2f * s, 17f * s); close()
            }
            drawPath(path, c, style = stroke)
        }
        LumaIcon.SLOW_MOTION -> {
            for (i in 0 until 12) drawArc(c, i * 30f - 90f, 16f, false, topLeft = p(3f, 3f), size = Size(18f * s, 18f * s), style = stroke)
            drawCircle(c, 2f * s, p(12f, 12f))
        }
        LumaIcon.HYPERLAPSE -> {
            drawCircle(c, 6.5f * s, p(14.5f, 12f), style = stroke)
            drawLine(c, p(2f, 8.5f), p(6f, 8.5f), stroke.width, StrokeCap.Round)
            drawLine(c, p(1.5f, 12f), p(5.5f, 12f), stroke.width, StrokeCap.Round)
            drawLine(c, p(2f, 15.5f), p(6f, 15.5f), stroke.width, StrokeCap.Round)
            drawLine(c, p(14.5f, 12f), p(14.5f, 8.5f), stroke.width, StrokeCap.Round)
        }
        LumaIcon.DUAL -> {
            drawRoundRect(c, topLeft = p(2.5f, 4.5f), size = Size(19f * s, 15f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5f * s), style = stroke)
            drawRoundRect(c, topLeft = p(12.5f, 7f), size = Size(6.5f * s, 5.5f * s),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.2f * s), style = stroke)
        }
        LumaIcon.MULTI -> {
            drawCircle(c, 3.6f * s, p(7.5f, 7.5f), style = stroke)
            drawCircle(c, 3.6f * s, p(16.5f, 7.5f), style = stroke)
            drawCircle(c, 3.6f * s, p(7.5f, 16.5f), style = stroke)
            drawCircle(c, 1.4f * s, p(16.5f, 16.5f))
        }
        LumaIcon.SUN -> {
            drawCircle(c, 4f * s, p(12f, 12f), style = stroke)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(c, p(12f + 7f * cos(a).toFloat(), 12f + 7f * sin(a).toFloat()),
                    p(12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat()), stroke.width, StrokeCap.Round)
            }
        }
        LumaIcon.GRID -> {
            drawRect(c, topLeft = p(3f, 3f), size = Size(18f * s, 18f * s), style = stroke)
            drawLine(c, p(9f, 3f), p(9f, 21f), stroke.width)
            drawLine(c, p(15f, 3f), p(15f, 21f), stroke.width)
            drawLine(c, p(3f, 9f), p(21f, 9f), stroke.width)
            drawLine(c, p(3f, 15f), p(21f, 15f), stroke.width)
        }
    }
}
