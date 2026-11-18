package com.datarohit.luma.capture

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Size
import com.datarohit.luma.settings.PhotoResolution
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a photo resolution tier is produced on one lens. */
enum class ResolutionSource(val label: String) {
    /** The sensor delivers this size itself (binned or full readout). */
    NATIVE("Native"),
    /** A larger native readout, reduced with an area-correct filter (sharper than native binning). */
    DOWNSAMPLED("Downsampled"),
    /**
     * Multi-frame super-resolution: the RAW samples of a hand-held burst, shifted against each other by tremor,
     * are gathered onto a denser grid (up to 2x per side): real detail beyond one frame, not interpolation.
     */
    SUPER_RES("Multi-frame super-res"),
    /** Super-resolved to 2x per side (the most a burst supports), then enlarged to the tier with Lanczos. */
    SUPER_RES_ENLARGED("Super-res + enlarged"),
    /** One frame from the lens's largest readout, enlarged (lenses without usable RAW). */
    UPSCALED("Upscaled"),
}

/** One readout size a lens exposes to apps. [maxResolution] = only in the sensor's maximum-resolution mode. */
data class NativeReadout(val size: Size, val raw: Boolean, val maxResolution: Boolean) {
    val megapixels: Float get() = size.width.toFloat() * size.height / 1e6f
}

data class ResolutionPlan(
    val tier: PhotoResolution,
    val source: ResolutionSource,
    /** The native readout the photo starts from. */
    val from: NativeReadout,
) {
    /** "50MP · Multi-frame super-res from 12.5MP" */
    val label: String
        get() = when (source) {
            ResolutionSource.NATIVE -> "${tier.label} · Native"
            ResolutionSource.SUPER_RES_ENLARGED -> "${tier.label} · Enlarged from ${formatMp(from.megapixels * 4f)} super-res"
            else -> "${tier.label} · ${source.label} from ${formatMp(from.megapixels)}"
        }

    /** True when every output pixel carries real captured detail (native, downsampled or super-resolved). */
    val realDetail: Boolean get() = source != ResolutionSource.UPSCALED && source != ResolutionSource.SUPER_RES_ENLARGED

    /** True when the session must capture [from] through a HAL still stream larger than the default RAW. */
    val needsHighResStream: Boolean
        get() = (source == ResolutionSource.NATIVE || source == ResolutionSource.DOWNSAMPLED) && tier != PhotoResolution.MP12

    companion object {
        fun formatMp(mp: Float): String = if (abs(mp - mp.roundToInt()) < 0.05f) "${mp.roundToInt()}MP" else String.format(java.util.Locale.ROOT, "%.1fMP", mp)
    }
}

/**
 * Photo resolution tiers per lens, from what the camera actually exposes: the public stream maps (binned
 * default readout), and on sensors with ULTRA_HIGH_RESOLUTION_SENSOR the maximum-resolution map. Nothing
 * is assumed from the marketing size of a sensor: sizes the HAL only accepts privately (and answers with a
 * smaller image, as measured on SM-S948B) are never treated as native.
 */
class ResolutionCatalog(private val characteristicsOf: (String) -> CameraCharacteristics) {

    private val cache = mutableMapOf<String, List<NativeReadout>>()

    /** Native 4:3-ish readouts of [cameraId], largest first. */
    fun readouts(cameraId: String): List<NativeReadout> = synchronized(cache) {
        cache.getOrPut(cameraId) {
            runCatching { readoutsOf(characteristicsOf(cameraId)) }.getOrDefault(emptyList())
        }
    }

    /** [rawRoute]: the lens is captured as a RAW burst by Luma (false for HAL-processed lenses). */
    fun plan(tier: PhotoResolution, cameraId: String, rawRoute: Boolean = true): ResolutionPlan? {
        val all = readouts(cameraId)
        if (all.isEmpty()) return null
        val target = tier.megapixels.toFloat()
        val largest = all.first()
        // Native within ±15 %.
        all.firstOrNull { abs(it.megapixels - target) / target <= 0.15f }?.let { return ResolutionPlan(tier, ResolutionSource.NATIVE, it) }
        // 12MP never enlarges: a smaller sensor (e.g. a 10MP telephoto) delivers its own native size.
        if (tier == PhotoResolution.MP12 && largest.megapixels < target) return ResolutionPlan(tier, ResolutionSource.NATIVE, largest)
        // The smallest readout comfortably above the target, reduced.
        all.filter { it.megapixels > target * 1.15f }.minByOrNull { it.megapixels }?.let { return ResolutionPlan(tier, ResolutionSource.DOWNSAMPLED, it) }
        // Above every readout: super-resolve the RAW burst (2x per side at most), enlarging only what is left.
        val raw = all.filter { it.raw }.maxByOrNull { it.megapixels }
        if (rawRoute && raw != null) {
            val source = if (target <= raw.megapixels * 4f * 1.15f) ResolutionSource.SUPER_RES else ResolutionSource.SUPER_RES_ENLARGED
            return ResolutionPlan(tier, source, raw)
        }
        return ResolutionPlan(tier, ResolutionSource.UPSCALED, largest)
    }

    /** One line per lens for the Settings footer: "Main 12.5MP native · 50MP super-res". */
    fun availability(tier: PhotoResolution, lenses: List<Pair<String, String>>): List<Pair<String, ResolutionPlan?>> =
        lenses.map { (id, name) -> name to plan(tier, id) }

    companion object {
        /** Output size for [tier] at the aspect of a [w] x [h] render; 12MP keeps the native crop (no enlargement). */
        fun outputSize(tier: PhotoResolution, w: Int, h: Int): Size = outputDims(tier, w, h).let { Size(it.first, it.second) }

        /** Pure form of [outputSize] (unit-testable): even dimensions, aspect of [w] x [h] kept. */
        internal fun outputDims(tier: PhotoResolution, w: Int, h: Int): Pair<Int, Int> {
            val pixels = w.toLong() * h
            if (tier == PhotoResolution.MP12 && pixels <= 14_000_000L) return w to h
            var scale = sqrt(tier.megapixels * 1_000_000.0 / pixels)
            // Within 1 % of a whole multiple (12.5MP x2 = 50MP, x4 = 200MP): use it exactly, which lands on
            // the sensor's own high-resolution grid and keeps the resampling phase-aligned.
            val whole = kotlin.math.round(scale)
            if (whole >= 2.0 && abs(scale - whole) / scale < 0.01) scale = whole
            val ow = ((w * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            val oh = ((h * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            return ow to oh
        }

        fun readoutsOf(c: CameraCharacteristics): List<NativeReadout> {
            val out = mutableListOf<NativeReadout>()
            fun add(map: android.hardware.camera2.params.StreamConfigurationMap?, max: Boolean) {
                map ?: return
                val raw = map.getOutputSizes(ImageFormat.RAW_SENSOR).orEmpty().toList()
                val jpeg = map.getOutputSizes(ImageFormat.JPEG).orEmpty().toList()
                // Only full-frame readouts (4:3 within 2 %) count as sensor modes; 16:9 etc. are crops.
                fun fourThree(s: Size) = abs(s.width.toFloat() / s.height - 4f / 3f) < 0.03f
                raw.filter(::fourThree).forEach { out += NativeReadout(it, raw = true, maxResolution = max) }
                jpeg.filter { s -> fourThree(s) && raw.none { it == s } }.maxByOrNull { it.width.toLong() * it.height }
                    ?.let { out += NativeReadout(it, raw = false, maxResolution = max) }
            }
            add(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP), max = false)
            val uhr = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) == true
            if (uhr) add(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION), max = true)
            // Keep the largest readout per pixel count; a small JPEG below the RAW size is not a sensor mode.
            val biggestRaw = out.filter { it.raw }.maxOfOrNull { it.megapixels } ?: 0f
            return out.filter { it.raw || it.megapixels > biggestRaw * 1.15f }
                .distinctBy { it.size }
                .filter { it.megapixels >= 2f }
                .sortedByDescending { it.megapixels }
        }
    }
}
