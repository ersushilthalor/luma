package com.datarohit.luma.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.datarohit.luma.appGraph
import com.datarohit.luma.capture.CaptureProcessor
import com.datarohit.luma.capture.ResolutionPlan
import com.datarohit.luma.capture.ResolutionSource
import com.datarohit.luma.core.imaging.ImagingEngine
import com.datarohit.luma.core.imaging.Look
import com.datarohit.luma.core.imaging.OutputSpace
import com.datarohit.luma.ui.design.IosDivider
import com.datarohit.luma.ui.design.IosPickerRow
import com.datarohit.luma.ui.design.IosRow
import com.datarohit.luma.ui.design.IosScreen
import com.datarohit.luma.ui.design.IosSection
import com.datarohit.luma.ui.design.IosSegmentedRow
import com.datarohit.luma.ui.design.IosSliderRow
import com.datarohit.luma.ui.design.IosToggleRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Back lenses (physical cameras of the logical back camera, else standalone), named by focal length. */
private fun backLensNames(context: android.content.Context): List<Pair<String, String>> {
    val g = context.appGraph
    val logical = g.caps.cameras.values.firstOrNull { it.facingBack && it.physicalIds.isNotEmpty() }
    val ids = logical?.physicalIds ?: g.caps.cameras.values.filter { it.facingBack }.map { it.id }
    val focal = ids.associateWith { g.catalog.equivalentFocalMm(it) ?: 24f }
    val main = focal.values.minByOrNull { kotlin.math.abs(it - 24f) } ?: 24f
    return ids.sortedBy { focal[it] }.map { id ->
        val f = focal[id] ?: 24f
        id to when {
            f < main * 0.8f -> "Ultra-wide"
            f <= main * 1.2f -> "Main"
            else -> "${(f / main).roundToInt()}× telephoto"
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { context.appGraph.settings }
    val s by repo.settings.collectAsStateWithLifecycle()
    val storage by produceState<Pair<Int, Long>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.appGraph.media.list().let { list -> list.size to list.sumOf { it.size } } }.getOrNull()
        }
    }
    val engine = remember { runCatching { ImagingEngine.buildInfo() }.getOrNull() }

    IosScreen(title = "Settings", backLabel = "Camera", onBack = onBack) {
        val lenses = remember { backLensNames(context) }
        val availability = remember(s.photoResolution) {
            context.appGraph.resolutions.availability(s.photoResolution, lenses).joinToString("\n") { (name, plan) ->
                "$name: " + (plan?.let { p -> if (p.source == ResolutionSource.NATIVE) "native ${ResolutionPlan.formatMp(p.from.megapixels)}" else "${p.source.label.lowercase()} from ${ResolutionPlan.formatMp(p.from.megapixels)}" } ?: "not available")
            }
        }
        IosSection("Resolution", footer = "$availability\n\nOnly sizes the camera delivers to apps count as native. Larger tiers are rendered from the merged RAW and enlarged with Luma's Lanczos resampler, and are labelled that way in the viewfinder and in the photo's description.") {
            IosSegmentedRow("Photo size", PhotoResolution.entries.map { it to it.label }, s.photoResolution) { v -> repo.update { it.copy(photoResolution = v) } }
        }
        IosSection("Photo", footer = "Display P3 matches this phone's screen. HEIF files are about half the size of JPEG.") {
            IosSegmentedRow("Format", PhotoFormat.entries.map { it to it.label }, s.photoFormat) { v -> repo.update { it.copy(photoFormat = v) } }
            IosDivider()
            IosPickerRow("Look", listOf(Look.NATURAL to "Natural", Look.VIVID to "Vivid", Look.MONOCHROME to "Monochrome", Look.FLAT to "Flat"), s.look) { v ->
                repo.update { it.copy(look = v) }
            }
            IosDivider()
            IosSegmentedRow("Colour space", listOf(OutputSpace.DISPLAY_P3 to "Display P3", OutputSpace.SRGB to "sRGB"), s.outputSpace) { v ->
                repo.update { it.copy(outputSpace = v) }
            }
        }
        IosSection("Processing", footer = "Applied by Luma's RAW pipeline to every Photo, Night, Portrait and Food capture.") {
            IosSliderRow("Local tone mapping", s.localTone, "${(s.localTone * 100).roundToInt()}%") { v -> repo.update { it.copy(localTone = v) } }
            IosDivider()
            IosSliderRow("Sharpening", s.sharpness, "${(s.sharpness * 100).roundToInt()}%") { v -> repo.update { it.copy(sharpness = v) } }
            IosDivider()
            IosSliderRow("Noise reduction", s.noiseReduction, "${(s.noiseReduction * 100).roundToInt()}%") { v -> repo.update { it.copy(noiseReduction = v) } }
            IosDivider()
            IosSliderRow("Quality", (s.jpegQuality - 80) / 20f, "${s.jpegQuality}") { v -> repo.update { it.copy(jpegQuality = (80 + v * 20).roundToInt()) } }
        }
        IosSection("RAW", footer = "Merged bursts are saved as Merged RAW. The 3× and 5× telephotos give no autofocused RAW on this phone; those shots are processed by the camera and labelled.") {
            IosToggleRow("Save DNG with each photo", s.saveDng) { v -> repo.update { it.copy(saveDng = v) } }
        }
        IosSection("Focus", footer = "Focus Enhancer moves to the ultra-wide, cropped to the same framing, when a subject is closer than the main lens can focus, and back when you step away.") {
            IosToggleRow("Focus Enhancer", s.focusEnhancer) { v -> repo.update { it.copy(focusEnhancer = v) } }
            IosDivider()
            IosToggleRow("Close-up hints", s.closeUpHints) { v -> repo.update { it.copy(closeUpHints = v) } }
        }
        IosSection("Viewfinder") {
            IosToggleRow("Grid", s.grid) { v -> repo.update { it.copy(grid = v) } }
            IosDivider()
            IosToggleRow("Level", s.level) { v -> repo.update { it.copy(level = v) } }
            IosDivider()
            IosToggleRow("Histogram", s.histogram) { v -> repo.update { it.copy(histogram = v) } }
        }
        IosSection("Capture", footer = "Where the law requires a shutter sound, the phone plays it regardless of this setting.") {
            IosToggleRow("Pro multi-frame noise reduction", s.proMultiFrame) { v -> repo.update { it.copy(proMultiFrame = v) } }
            IosDivider()
            IosToggleRow("Shutter sound", s.shutterSound) { v -> repo.update { it.copy(shutterSound = v) } }
        }
        IosSection("Storage") {
            IosRow("Photos", value = "DCIM/Luma")
            IosDivider()
            IosRow("Videos", value = "Movies/Luma")
            IosDivider()
            IosRow("Luma library", value = storage?.let { "${it.first} items · ${Formatter.formatShortFileSize(context, it.second)}" } ?: "…")
            IosDivider()
            IosRow("Available", value = Formatter.formatShortFileSize(context, context.filesDir.usableSpace))
        }
        IosSection("Privacy", footer = "Luma has no internet permission, no analytics and no accounts. Location is never stored. Diagnostics stay on the phone unless you share them.") {
            IosRow("Network access", value = "None")
        }
        IosSection("About") {
            IosRow("Version", value = CaptureProcessor.APP_VERSION)
            IosDivider()
            IosRow("Rendering", value = CaptureProcessor.RENDER_VERSION)
            engine?.let {
                IosDivider()
                IosRow("Engine", value = "${it.version} · ${it.simd}")
            }
            IosDivider()
            IosRow("Diagnostics", chevron = true, onClick = onOpenDiagnostics)
        }
        Spacer(Modifier.height(24.dp))
    }
}
