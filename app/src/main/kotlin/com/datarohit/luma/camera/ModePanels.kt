package com.datarohit.luma.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.datarohit.luma.core.camera.lens.FocusReadout
import com.datarohit.luma.core.camera.session.FrameInfo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** One adjustable value of a Pro panel. */
internal class ProItem(
    val key: String,
    val label: String,
    val value: String,
    val manual: Boolean,
    val fraction: Float,
    val onFraction: (Float) -> Unit,
    val onAuto: () -> Unit,
    /** Extra line under the ruler (focus distance, depth of field). */
    val detail: String? = null,
    /** Optional action next to AUTO (e.g. AF → MF lock). */
    val action: Pair<String, () -> Unit>? = null,
)

/**
 * iOS-style Pro control: a quiet strip of values (small-caps label over the value). Tapping one turns the
 * strip into a dial with the value floating above its centre mark, a round AUTO button and an optional
 * action. Tapping the value chip goes back to the strip.
 */
@Composable
internal fun ProPanel(items: List<ProItem>, modifier: Modifier = Modifier) {
    var activeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val active = items.firstOrNull { it.key == activeKey }
    Box(modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        if (active == null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                items.forEach { item ->
                    Column(
                        Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).clickable(noRipple(), null) { activeKey = item.key },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
                    ) {
                        Text(item.label, style = centered(9.sp, FontWeight.SemiBold, 1.2.sp), color = LumaColors.Label2, maxLines = 1)
                        Text(item.value, style = centered(14.sp, FontWeight.Medium), color = if (item.manual) LumaColors.Amber else Color.White, maxLines = 1)
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(active.label.take(3), selected = true, onClick = { activeKey = null })
                Box(Modifier.weight(1f).padding(horizontal = 6.dp), contentAlignment = Alignment.TopCenter) {
                    RulerDial(active.fraction, active.onFraction, Modifier.fillMaxWidth().padding(top = 6.dp))
                    Text(active.value, style = centered(12.sp, FontWeight.SemiBold, 0.6.sp), color = LumaColors.Amber,
                        modifier = Modifier.offset(y = (-6).dp))
                }
                active.action?.let { (label, run) ->
                    RoundButton(label, selected = false, onClick = run)
                    Spacer(Modifier.width(8.dp))
                }
                RoundButton("A", selected = !active.manual, onClick = active.onAuto)
            }
        }
        // Context line (distance, depth of field, shutter rule) floats above the panel, over the frame edge.
        val detail = active?.detail
        if (detail != null) {
            Text(
                detail.uppercase(Locale.ROOT),
                style = centered(10.sp, FontWeight.SemiBold, 0.8.sp),
                color = Color.White,
                maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).offset(y = (-30).dp).clip(CircleShape).background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** Round 38 dp control in the iOS camera style: dark fill, short label, amber when engaged. */
@Composable
internal fun RoundButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(38.dp).clip(CircleShape).background(if (selected) LumaColors.Amber else Color(0x33FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = centered(if (label.length > 3) 9.sp else 11.sp, FontWeight.Bold, 0.3.sp), color = if (selected) Color.Black else Color.White, maxLines = 1)
    }
}

@Composable
internal fun PanelButton(label: String, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(30.dp).clip(CircleShape).background(if (highlighted) LumaColors.AmberWash else LumaColors.FillSoft)
            .clickable(onClick = onClick).padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = centered(10.sp, FontWeight.Bold, 0.6.sp), color = if (highlighted) LumaColors.Amber else Color.White, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Items

private val ISO_STOPS = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
private val SHUTTER_STOPS = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80,
    60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4)
internal val SHUTTER_ANGLES = listOf(11.25f, 22.5f, 45f, 90f, 144f, 172.8f, 180f, 216f, 270f, 360f)

private fun snapIso(iso: Int): Int = ISO_STOPS.minBy { abs(ln(it.toFloat() / iso)) }
private fun snapShutter(ns: Long): Long = (1e9 / SHUTTER_STOPS.minBy { abs(ln(1e9f / it / ns)) }).toLong()

internal fun formatShutter(ns: Long): String {
    if (ns <= 0) return "–"
    val seconds = ns / 1e9
    return if (seconds >= 0.3) String.format(Locale.ROOT, "%.1fs", seconds) else "1/${(1.0 / seconds).roundToInt()}"
}

/** ISO, shutter, WB, tint, focus and EV items shared by Pro Photo and Pro Video. */
internal fun proItems(
    vm: CameraViewModel,
    ui: CameraUiState,
    info: FrameInfo?,
    focus: FocusReadout?,
    video: Boolean,
): List<ProItem> {
    val d = ui.descriptor ?: return emptyList()
    val p = ui.pro
    val items = mutableListOf<ProItem>()
    d.isoRange?.let { r ->
        val lo = ln(r.lower.toFloat())
        val hi = ln(r.upper.toFloat())
        val cur = (p.iso ?: info?.iso ?: 100).toFloat().coerceIn(r.lower.toFloat(), r.upper.toFloat())
        items += ProItem("iso", "ISO", (p.iso ?: info?.iso)?.toString() ?: "–", p.iso != null, (ln(cur) - lo) / (hi - lo),
            { f -> vm.setPro { it.copy(iso = snapIso(exp(lo + f * (hi - lo)).roundToInt())) } }, { vm.setPro { it.copy(iso = null) } })
    }
    if (video) {
        val fps = ui.videoPlan?.config?.fps ?: 30
        val angle = p.shutterAngle
        val idx = angle?.let { a -> SHUTTER_ANGLES.indexOfFirst { abs(it - a) < 0.1f } }?.takeIf { it >= 0 } ?: SHUTTER_ANGLES.indexOf(180f)
        val expNs = info?.exposureNs ?: 0L
        items += ProItem("shutter", "SHUTTER",
            angle?.let { "${fmtAngle(it)}°" } ?: "${(expNs / 1e9 * fps * 360).roundToInt()}°", angle != null,
            idx.toFloat() / (SHUTTER_ANGLES.size - 1),
            { f -> vm.setPro { it.copy(shutterAngle = SHUTTER_ANGLES[(f * (SHUTTER_ANGLES.size - 1)).roundToInt()], exposureNs = null) } },
            { vm.setPro { it.copy(shutterAngle = null, exposureNs = null) } },
            detail = angle?.let { "1/${(fps * 360f / it).roundToInt()} s at $fps fps" + if (abs(it - 180f) < 0.1f) " · 180° rule" else "" },
            action = "180°" to { vm.setPro { it.copy(shutterAngle = 180f, exposureNs = null) } },
        )
    } else {
        d.exposureRangeNs?.let { r ->
            val minNs = r.lower.coerceAtLeast(125_000L)
            val maxNs = r.upper.coerceAtMost(250_000_000L)
            val lo = ln(minNs.toFloat())
            val hi = ln(maxNs.toFloat())
            val cur = (p.exposureNs ?: info?.exposureNs ?: 10_000_000L).coerceIn(minNs, maxNs).toFloat()
            items += ProItem("shutter", "SHUTTER", formatShutter(p.exposureNs ?: info?.exposureNs ?: 0L), p.exposureNs != null, (ln(cur) - lo) / (hi - lo),
                { f -> vm.setPro { it.copy(exposureNs = snapShutter(exp(lo + f * (hi - lo)).toLong())) } }, { vm.setPro { it.copy(exposureNs = null) } })
        }
    }
    val cctLo = (d.cctRange?.lower ?: 2300).toFloat()
    val cctHi = (d.cctRange?.upper ?: 10000).toFloat()
    val kelvin = (p.kelvin ?: 5500).toFloat().coerceIn(cctLo, cctHi)
    items += ProItem("wb", "WB", p.kelvin?.let { "${it}K" } ?: "Auto", p.kelvin != null, (kelvin - cctLo) / (cctHi - cctLo),
        { f -> vm.setPro { it.copy(kelvin = ((cctLo + f * (cctHi - cctLo)) / 100f).roundToInt() * 100) } }, { vm.setPro { it.copy(kelvin = null, tint = 0) } },
        detail = if (!d.supportsCctWhiteBalance) "Kelvin is applied when Luma processes the RAW" else null)
    items += ProItem("tint", "TINT", if (p.kelvin == null) "–" else (if (p.tint > 0) "M${p.tint}" else if (p.tint < 0) "G${-p.tint}" else "0"),
        p.tint != 0, (p.tint + 50) / 100f,
        { f -> vm.setPro { it.copy(tint = (f * 100 - 50).roundToInt(), kelvin = it.kelvin ?: 5500) } }, { vm.setPro { it.copy(tint = 0) } },
        detail = "Green ← → Magenta (applies with Kelvin)")
    if (d.minFocusDiopters > 0f) {
        val cur = (p.focusDiopters ?: info?.focusDiopters ?: 0f).coerceIn(0f, d.minFocusDiopters)
        items += ProItem("focus", "FOCUS", if (p.focusDiopters == null) "AF" else focusText(focus), p.focusDiopters != null, cur / d.minFocusDiopters,
            { f -> vm.setPro { it.copy(focusDiopters = f * d.minFocusDiopters) } }, { vm.setPro { it.copy(focusDiopters = null) } },
            detail = "${focusText(focus)} · DoF ${dofText(focus)} · ${afState(info?.afState, p.focusDiopters != null)}",
            action = "AF→MF" to { vm.lockFocusToManual() },
        )
    }
    if (ui.evRange.last > ui.evRange.first) {
        val span = (ui.evRange.last - ui.evRange.first).toFloat()
        items += ProItem("ev", "EV", String.format(Locale.ROOT, "%+.1f", ui.evIndex * ui.evStep), ui.evIndex != 0,
            (ui.evIndex - ui.evRange.first) / span, { f -> vm.setEv((ui.evRange.first + f * span).roundToInt()) }, { vm.setEv(0) })
    }
    return items
}

private fun fmtAngle(a: Float) = if (a % 1f == 0f) a.toInt().toString() else String.format(Locale.ROOT, "%.1f", a)

/** Audio gain item for Pro Video (-12..+24 dB). */
internal fun gainItem(vm: CameraViewModel, gainDb: Float): ProItem =
    ProItem("gain", "GAIN", String.format(Locale.ROOT, "%+.0f dB", gainDb), gainDb != 0f, (gainDb + 12f) / 36f,
        { f -> vm.updatePro { it.copy(video = it.video.copy(audioGainDb = ((f * 36f - 12f)).roundToInt().toFloat())) } },
        { vm.updatePro { it.copy(video = it.video.copy(audioGainDb = 0f)) } })

// ---------------------------------------------------------------------------------------------
// Mode rows

/**
 * Focus pulls and presets for Pro Video as one row of compact capsules (34 dp): the dock over the 16:9
 * frame stays short enough that dials, presets, wheel and shutter never crowd or overlap.
 */
@Composable
internal fun ProVideoActions(ui: CameraUiState, vm: CameraViewModel, onTools: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val cell = Modifier.weight(1f)
        ActionCapsule("Set A", ui.rack.a != null, cell) { vm.setRackPoint(true) }
        ActionCapsule("Set B", ui.rack.b != null, cell) { vm.setRackPoint(false) }
        ActionCapsule("Rack", ui.rack.running, cell) { vm.runRack() }
        ActionCapsule("Dolly", ui.dollyRunning, cell) { vm.runDolly() }
        ActionCapsule("MF", ui.pro.focusDiopters != null, cell) { vm.lockFocusToManual() }
        ActionCapsule("Tools", false, cell, onTools)
    }
}

@Composable
private fun ActionCapsule(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(34.dp).clip(CircleShape).background(if (selected) LumaColors.Amber else LumaColors.GlassStrong).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = centered(12.sp, FontWeight.SemiBold), color = if (selected) Color.Black else Color.White, maxLines = 1, softWrap = false)
    }
}

/** Caption + iOS segmented control, used for the per-mode options (speed, layout, saving, cameras). */
@Composable
internal fun LabeledChips(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label.uppercase(Locale.ROOT), style = centered(9.sp, FontWeight.SemiBold, 1.2.sp), color = LumaColors.Label2, modifier = Modifier.width(62.dp))
        com.datarohit.luma.ui.design.IosSegmented(options, selected, Modifier.weight(1f), onSelect)
    }
}

/** Portrait strength as an f-stop ruler, with the live subject distance. */
@Composable
internal fun ApertureRow(fStop: Float, distance: String, onFStop: (Float) -> Unit) {
    val lo = ln(1.4f)
    val hi = ln(16f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(70.dp)) {
            Text(String.format(Locale.ROOT, "f/%.1f", fStop), style = centered(15.sp, FontWeight.SemiBold), color = LumaColors.Amber)
            Spacer(Modifier.height(4.dp))
            Text(distance, style = centered(10.sp, FontWeight.Medium), color = LumaColors.Label2, maxLines = 1)
        }
        RulerDial((ln(fStop) - lo) / (hi - lo), { f -> onFStop((exp(lo + f * (hi - lo)) * 10f).roundToInt() / 10f) }, Modifier.weight(1f))
    }
}

/** Food: ring size ruler and blur strength chips. */
@Composable
internal fun FoodRow(radius: Float, blur: Float, onRadius: (Float) -> Unit, onBlur: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("RING", style = centered(10.sp, FontWeight.SemiBold, 0.8.sp), color = LumaColors.Label2, modifier = Modifier.width(54.dp))
            RulerDial((radius - 0.08f) / 0.32f, { f -> onRadius(0.08f + f * 0.32f) }, Modifier.weight(1f))
        }
        LabeledChips("Blur", listOf("Soft", "Medium", "Strong"), when { blur < 0.4f -> 0; blur < 0.8f -> 1; else -> 2 }) { onBlur(listOf(0.25f, 0.6f, 1f)[it]) }
    }
}
