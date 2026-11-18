package com.datarohit.luma.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.datarohit.luma.core.camera.lens.FocusMath
import com.datarohit.luma.core.camera.lens.FocusReadout
import com.datarohit.luma.core.camera.session.FrameInfo
import com.datarohit.luma.video.AudioLevels
import com.datarohit.luma.video.ScopeKind
import com.datarohit.luma.video.VideoPlan
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/** Running clock while recording: HH:MM:SS:FF timecode at the project frame rate. */
@Composable
internal fun rememberTimecode(recording: Boolean, startMs: Long, fps: Int): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recording) {
        while (recording) {
            withFrameMillis { now = System.currentTimeMillis() }
        }
    }
    if (!recording) return "00:00:00:00"
    val ms = (now - startMs).coerceAtLeast(0)
    val s = ms / 1000
    val f = ((ms % 1000) * fps / 1000).toInt()
    return String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60, f)
}

/** Compact recording pill for the simple video modes. */
@Composable
internal fun RecordPill(timecode: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(7.dp)).background(LumaColors.Record).padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(timecode.substring(0, 8), style = centered(15.sp, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace), color = Color.White)
    }
}

/**
 * Blackmagic-style status strip in iOS materials. Line 1 (always): record state, timecode, measured fps,
 * format. Line 2 (expanded): exposure, white balance, focus with depth of field, media. Hairline audio
 * meters close it. Tap to expand or collapse, swipe up to collapse.
 */
@Composable
internal fun ProHud(
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    recording: Boolean,
    timecode: String,
    fps: Float,
    plan: VideoPlan?,
    info: FrameInfo?,
    pro: ProState,
    focus: FocusReadout?,
    freeBytes: Long?,
    storageLabel: String,
    audio: AudioLevels,
    audioOn: Boolean,
    modifier: Modifier = Modifier,
) {
    val mono = FontFamily.Monospace
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x8C000000))
            .pointerInput(expanded) {
                var acc = 0f
                detectVerticalDragGestures(onDragEnd = {
                    if (acc < -24) onToggle(false) else if (acc > 24) onToggle(true)
                    acc = 0f
                }) { c, dy -> acc += dy; c.consume() }
            }
            .clickable(noRipple(), null) { onToggle(!expanded) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (recording) LumaColors.Record else Color(0x59FFFFFF)))
            Spacer(Modifier.width(7.dp))
            Text(if (recording) "REC" else "STBY", style = centered(10.sp, FontWeight.Bold, 0.8.sp), color = if (recording) LumaColors.Record else LumaColors.Label2)
            Spacer(Modifier.width(10.dp))
            Text(timecode, style = centered(14.sp, FontWeight.Medium).copy(fontFamily = mono), color = Color.White)
            Spacer(Modifier.weight(1f))
            Text(String.format(Locale.ROOT, "%.2f", fps), style = centered(12.sp, FontWeight.Medium).copy(fontFamily = mono), color = Color.White)
            Text(" FPS", style = centered(9.sp, FontWeight.SemiBold, 0.6.sp), color = LumaColors.Label2)
            plan?.let {
                Spacer(Modifier.width(10.dp))
                Text("${it.config.label} · ${it.config.codec.label} · ${it.config.profile.short}", style = centered(11.sp, FontWeight.SemiBold, 0.3.sp),
                    color = if (it.config.profile != com.datarohit.luma.video.ColorProfile.SDR) LumaColors.Amber else Color.White, maxLines = 1)
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0x33FFFFFF)))
                val fpsProject = plan?.config?.fps ?: 30
                val expNs = info?.exposureNs ?: 0L
                val angle = if (expNs > 0) (expNs / 1e9 * fpsProject * 360).roundToInt() else 0
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HudField("ISO", info?.iso?.toString() ?: "–", pro.iso != null)
                    HudField("SHUTTER", "$angle° ${formatShutter(expNs)}", pro.shutterAngle != null || pro.exposureNs != null)
                    HudField("WB", pro.kelvin?.let { "${it}K" } ?: "AUTO", pro.kelvin != null)
                    HudField("TINT", if (pro.kelvin == null) "–" else String.format(Locale.ROOT, "%+d", pro.tint), pro.tint != 0)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HudField(afState(info?.afState, pro.focusDiopters != null).uppercase(Locale.ROOT), focusText(focus), pro.focusDiopters != null)
                    HudField("DOF", dofText(focus), false)
                    val bitrate = plan?.config?.bitrate(0) ?: 0
                    val minutes = if (freeBytes != null && bitrate > 0) freeBytes * 8 / bitrate / 60 else null
                    HudField(storageLabel.uppercase(Locale.ROOT).take(10), (freeBytes?.let { gb(it) } ?: "–") + (minutes?.let { " · ${it / 60}h${it % 60}m" } ?: ""), false)
                }
                if (audioOn) AudioMeters(audio)
            }
        }
    }
}

private fun gb(bytes: Long) = String.format(Locale.ROOT, "%.0f GB", bytes / 1e9)

internal fun focusText(f: FocusReadout?): String = when {
    f == null -> "–"
    f.beyondHyperfocal -> "∞"
    else -> FocusMath.format(f.distanceM)
}

internal fun dofText(f: FocusReadout?): String = when {
    f == null -> "–"
    f.farM == null -> "${FocusMath.format(f.nearM, false)} – ∞"
    else -> "${FocusMath.format(f.nearM, false)} – ${FocusMath.format(f.farM, false)}"
}

internal fun afState(state: Int?, manual: Boolean): String = when {
    manual -> "MF"
    state == null -> "AF"
    state == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN || state == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "AF ·"
    state == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "AF-L"
    state == android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "AF ✕"
    else -> "AF-C"
}

@Composable
private fun HudField(label: String, value: String, manual: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = centered(8.sp, FontWeight.SemiBold, 1.sp), color = LumaColors.Label2, maxLines = 1)
        Text(value, style = centered(13.sp, FontWeight.Medium).copy(fontFamily = FontFamily.Monospace), color = if (manual) LumaColors.Amber else Color.White, maxLines = 1)
    }
}

/** Hairline stereo meters (-60..0 dBFS): RMS fill, peak tick, clip dot. */
@Composable
internal fun AudioMeters(a: AudioLevels, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("L R", style = centered(8.sp, FontWeight.SemiBold, 1.sp), color = LumaColors.Label2)
        Spacer(Modifier.width(8.dp))
        Canvas(Modifier.weight(1f).height(9.dp)) {
            fun bar(y: Float, peak: Float, rms: Float) {
                val h = 3.dp.toPx()
                drawRoundRect(Color(0x26FFFFFF), Offset(0f, y), Size(size.width, h), cornerRadius(h / 2))
                fun x(db: Float) = ((db + 60f) / 60f).coerceIn(0f, 1f) * size.width
                val c = when {
                    peak > -3f -> LumaColors.Record
                    peak > -12f -> LumaColors.Amber
                    else -> Color(0xFF30D158)
                }
                drawRoundRect(c, Offset(0f, y), Size(x(rms), h), cornerRadius(h / 2))
                drawRect(Color.White, Offset((x(peak) - 1.dp.toPx()).coerceAtLeast(0f), y), Size(1.5.dp.toPx(), h))
            }
            bar(0f, a.peakL, a.rmsL)
            bar(size.height - 3.dp.toPx(), a.peakR, a.rmsR)
        }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (a.clipped) LumaColors.Record else Color(0x33FFFFFF)))
    }
}

/** Histogram, luma-style waveform or RGB parade from [Scopes]. */
@Composable
internal fun ScopeView(kind: ScopeKind, scopes: Scopes?, modifier: Modifier = Modifier) {
    if (kind == ScopeKind.OFF || scopes == null) return
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xA6000000))) {
        val bins = Scopes.BINS
        val cols = Scopes.COLUMNS
        val pad = 4.dp.toPx()
        val w = size.width - pad * 2
        val h = size.height - pad * 2
        val colors = listOf(Color(0xCCFF453A), Color(0xCC34C759), Color(0xCC0A84FF))
        when (kind) {
            ScopeKind.HISTOGRAM -> {
                val hist = scopes.histogram
                val max = (0 until 4 * bins).maxOf { hist[it] }.coerceAtLeast(1).toFloat()
                for (ch in 0 until 3) for (i in 0 until bins) {
                    val v = ln(1f + hist[ch * bins + i]) / ln(1f + max)
                    drawRect(colors[ch].copy(alpha = 0.45f), Offset(pad + i * w / bins, pad + h * (1 - v)), Size(w / bins, h * v))
                }
            }
            ScopeKind.WAVEFORM, ScopeKind.PARADE -> {
                val wave = scopes.waveform
                var max = 1
                for (v in wave) max = max(max, v)
                val parade = kind == ScopeKind.PARADE
                for (ch in 0 until 3) for (c in 0 until cols) for (b in 0 until bins) {
                    val n = wave[(ch * cols + c) * bins + b]
                    if (n == 0) continue
                    val a = (0.25f + 0.75f * n / max).coerceAtMost(1f)
                    val x = if (parade) pad + ch * w / 3f + c * (w / 3f) / cols else pad + c * w / cols
                    val cw = if (parade) (w / 3f) / cols else w / cols
                    val y = pad + h * (1f - (b + 1f) / bins)
                    drawRect(if (parade) colors[ch].copy(alpha = a) else Color.White.copy(alpha = a * 0.5f), Offset(x, y), Size(cw, h / bins))
                }
                // 100 % and 0 % reference lines.
                drawLine(Color(0x55FFFFFF), Offset(pad, pad), Offset(pad + w, pad), 1f)
            }
            ScopeKind.OFF -> Unit
        }
    }
}
