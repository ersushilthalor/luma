package com.datarohit.luma.camera

import androidx.compose.runtime.Composable
import com.datarohit.luma.ui.design.IosButtonRow
import com.datarohit.luma.ui.design.IosDivider
import com.datarohit.luma.ui.design.IosPickerRow
import com.datarohit.luma.ui.design.IosRow
import com.datarohit.luma.ui.design.IosSection
import com.datarohit.luma.ui.design.IosSegmentedRow
import com.datarohit.luma.ui.design.IosSheet
import com.datarohit.luma.ui.design.IosSliderRow
import com.datarohit.luma.ui.design.IosToggleRow
import com.datarohit.luma.video.ApvQuality
import com.datarohit.luma.video.AudioSourceOption
import com.datarohit.luma.video.FrameGuide
import com.datarohit.luma.video.PeakColor
import com.datarohit.luma.video.PeakSensitivity
import com.datarohit.luma.video.ProSettings
import com.datarohit.luma.video.ScopeKind
import com.datarohit.luma.video.Stabilization
import com.datarohit.luma.video.VideoCodec
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pro tools: format, colour, LUTs, monitoring, lens, audio and media, in iOS inset-grouped form. */
@Composable
internal fun ToolsSheet(
    visible: Boolean,
    vm: CameraViewModel,
    ui: CameraUiState,
    pro: ProSettings,
    onImportLut: () -> Unit,
    onPickStorage: () -> Unit,
    onDismiss: () -> Unit,
) {
    IosSheet(visible, "Pro Tools", onDismiss) {
        val v = pro.video
        val plan = ui.videoPlan
        IosSection("Format", footer = plan?.let { "${it.config.label} · ${it.config.codec.label} · ${it.config.profile.label}" + if (it.direct) " · camera → encoder, 10-bit" else " · GPU pipeline" }) {
            val sizes = vm.videoSizes()
            if (sizes.isNotEmpty()) {
                IosPickerRow("Resolution", sizes.map { it to sizeLabel(it.width, it.height) }, plan?.config?.size ?: v.size) { s ->
                    vm.updatePro { it.copy(video = it.video.copy(size = s)) }
                }
                IosDivider()
            }
            val fps = vm.videoFps()
            if (fps.isNotEmpty()) {
                IosSegmentedRow("Frame rate", fps.map { it to "$it" }, plan?.config?.fps ?: v.fps) { f -> vm.updatePro { it.copy(video = it.video.copy(fps = f)) } }
                IosDivider()
            }
            val codecs = vm.videoCodecs()
            IosSegmentedRow("Codec", codecs.map { it to it.label }, v.codec) { c -> vm.updatePro { it.copy(video = it.video.copy(codec = c)) } }
            IosDivider()
            if (v.codec == VideoCodec.APV) {
                IosSegmentedRow("APV quality", ApvQuality.entries.map { it to it.label }, v.apvQuality) { q -> vm.updatePro { it.copy(video = it.video.copy(apvQuality = q)) } }
            } else {
                val rates = listOf(0.1f to "Standard", 0.2f to "High", 0.35f to "Max")
                val sel = rates.minBy { abs(it.first - v.bitsPerPixel) }.first
                IosSegmentedRow("Bitrate", rates, sel) { b -> vm.updatePro { it.copy(video = it.video.copy(bitsPerPixel = b)) } }
            }
            IosDivider()
            val profiles = vm.videoProfiles()
            IosPickerRow("Colour", profiles.map { it to it.label }, v.profile) { p -> vm.updatePro { it.copy(video = it.video.copy(profile = p)) } }
        }

        IosSection("Colour", footer = if (v.engine) {
            "Luma renders colour on the GPU from a neutral camera stream: no camera sharpening, a scene-log curve " +
                "instead of the camera's tone mapping, then Luma's filmic highlight roll-off, true blacks and the selected look. " +
                "Headroom underexposes by the chosen amount to keep highlights and restores mid-tones on the GPU."
        } else "The camera's own processing (sharpening, tone curve and noise reduction), for comparison.") {
            IosToggleRow("Luma Colour Engine", v.engine) { on -> vm.updatePro { it.copy(video = it.video.copy(engine = on)) } }
            if (v.engine) {
                IosDivider()
                IosSegmentedRow("Look", com.datarohit.luma.gl.ColorLook.entries.map { it to it.label }, v.look) { l ->
                    vm.updatePro { it.copy(video = it.video.copy(look = l)) }
                }
                IosDivider()
                val headroom = listOf(0f to "Off", 0.7f to "0.7 EV", 1.3f to "1.3 EV")
                IosSegmentedRow("Highlight Headroom", headroom, headroom.minBy { abs(it.first - v.headroomEv) }.first) { h ->
                    vm.updatePro { it.copy(video = it.video.copy(headroomEv = h)) }
                }
                IosDivider()
                val ltm = listOf(0f to "Off", 0.3f to "Natural", 0.5f to "Strong")
                IosSegmentedRow("Dynamic Range", ltm, ltm.minBy { abs(it.first - v.localTone) }.first) { l ->
                    vm.updatePro { it.copy(video = it.video.copy(localTone = l)) }
                }
                IosDivider()
                IosToggleRow("Fine Grain", v.grain) { on -> vm.updatePro { it.copy(video = it.video.copy(grain = on)) } }
            }
        }

        IosSection("LUTs", footer = if (v.profile.hdr) "In 10-bit HDR the LUT is applied to the monitor only." else "Display LUT is for monitoring. Record LUT burns the grade into the clip.") {
            val luts = listOf<Pair<String?, String>>(null to "None") + ui.lutChoices.map { it.first to it.second }
            IosPickerRow("LUT", luts, v.lutId) { id -> vm.selectLut(id) }
            IosDivider()
            IosToggleRow("Display LUT", v.displayLut) { on -> vm.updatePro { it.copy(video = it.video.copy(displayLut = on)) } }
            IosDivider()
            IosToggleRow("Record LUT to Clip", v.recordLut) { on -> vm.updatePro { it.copy(video = it.video.copy(recordLut = on)) } }
            IosDivider()
            IosButtonRow("Import .cube LUT…", onClick = onImportLut)
        }

        IosSection("Monitoring") {
            IosToggleRow("Focus Peaking", pro.peaking) { on -> vm.updatePro { it.copy(peaking = on) } }
            if (pro.peaking) {
                IosDivider()
                IosPickerRow("Peaking Colour", PeakColor.entries.map { it to it.label }, pro.peakColor) { c -> vm.updatePro { it.copy(peakColor = c) } }
                IosDivider()
                IosSegmentedRow("Sensitivity", PeakSensitivity.entries.map { it to it.label }, pro.peakSensitivity) { s -> vm.updatePro { it.copy(peakSensitivity = s) } }
            }
            IosDivider()
            val zebra = listOf(0, 70, 80, 90, 95, 100)
            IosPickerRow("Zebras", zebra.map { it to if (it == 0) "Off" else "$it IRE" }, pro.zebraIre) { z -> vm.updatePro { it.copy(zebraIre = z) } }
            IosDivider()
            IosToggleRow("False Colour", pro.falseColor) { on -> vm.updatePro { it.copy(falseColor = on) } }
            IosDivider()
            IosPickerRow("Scope", ScopeKind.entries.map { it to it.label }, pro.scope) { s -> vm.updatePro { it.copy(scope = s) } }
            IosDivider()
            IosPickerRow("Frame Guides", FrameGuide.entries.map { it to it.label }, pro.guide) { g -> vm.updatePro { it.copy(guide = g) } }
            IosDivider()
            IosToggleRow("Horizon Level", pro.levelGauge) { on -> vm.updatePro { it.copy(levelGauge = on) } }
            IosDivider()
            IosToggleRow("Expanded HUD", pro.hudExpanded) { on -> vm.updatePro { it.copy(hudExpanded = on) } }
        }

        IosSection("Lens", footer = vm.lensCapabilityText()) {
            IosPickerRow("Stabilisation", Stabilization.entries.map { it to it.label }, v.stabilization, footer = { s ->
                when (s) {
                    Stabilization.OFF -> "No stabilisation"
                    Stabilization.OIS -> "Optical only"
                    Stabilization.OIS_EIS -> "Optical + camera electronic stabilisation"
                    Stabilization.HORIZON -> "Keeps the horizon level through a full 360° roll (crops the frame)"
                }
            }) { s -> vm.updatePro { it.copy(video = it.video.copy(stabilization = s)) } }
            IosDivider()
            IosSegmentedRow("Anamorphic", listOf(1f to "Off", 1.33f to "1.33×", 1.5f to "1.5×", 2f to "2×"), listOf(1f, 1.33f, 1.5f, 2f).minBy { abs(it - v.desqueeze) }) { d ->
                vm.updatePro { it.copy(video = it.video.copy(desqueeze = d)) }
            }
            if (v.desqueeze > 1f) {
                IosDivider()
                IosToggleRow("Desqueeze in File", v.bakeDesqueeze) { on -> vm.updatePro { it.copy(video = it.video.copy(bakeDesqueeze = on)) } }
            }
        }

        IosSection("Audio") {
            IosToggleRow("Record Sound", v.audio) { on -> vm.updatePro { it.copy(video = it.video.copy(audio = on)) } }
            if (v.audio) {
                IosDivider()
                IosPickerRow("Input", AudioSourceOption.entries.map { it to it.label }, v.audioSource) { a -> vm.updatePro { it.copy(video = it.video.copy(audioSource = a)) } }
                IosDivider()
                IosSliderRow("Gain", (v.audioGainDb + 12f) / 36f, String.format(Locale.ROOT, "%+.0f dB", v.audioGainDb)) { f ->
                    vm.updatePro { it.copy(video = it.video.copy(audioGainDb = (f * 36f - 12f).roundToInt().toFloat())) }
                }
            }
        }

        IosSection("Media", footer = ui.storageLabel + (ui.freeBytes?.let { String.format(Locale.ROOT, " · %.1f GB available", it / 1e9) } ?: "")) {
            IosRow("Internal Storage", check = pro.storageTree == null, onClick = { vm.setStorageTree(null) })
            IosDivider()
            IosRow("Folder or USB Drive…", check = pro.storageTree != null, onClick = onPickStorage)
        }
    }
}

private fun sizeLabel(w: Int, h: Int) = when {
    w >= 7680 -> "8K"
    w >= 3840 -> "4K"
    w >= 3600 -> "${w}×$h"
    w >= 2560 -> "1440p"
    w >= 1920 -> "1080p"
    else -> "720p"
}
