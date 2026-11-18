package com.datarohit.luma.capture

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class CaptureMode(val label: String) { NIGHT("Night"), PHOTO("Photo"), PRO("Pro") }

/** Everything the planner needs; plain values so it can be unit tested. */
data class PlanInput(
    val mode: CaptureMode,
    /** Auto-exposure result of the current preview. */
    val previewExposureNs: Long,
    val previewIso: Int,
    val isoMin: Int,
    val isoMax: Int,
    val exposureMinNs: Long,
    val exposureMaxNs: Long,
    /** Preview statistics. */
    val clippedFraction: Float,
    val motion: Float,
    /** Manual (Pro) values, null when automatic. */
    val manualExposureNs: Long? = null,
    val manualIso: Int? = null,
    /** Pro-mode multi-frame noise reduction. */
    val proMultiFrame: Boolean = false,
    /** f-number ratio squared of the capturing lens versus the metering lens (>1 = capture lens is slower). */
    val apertureFactor: Float = 1f,
    /** Multi-frame super-resolution scale per side (1 = none): needs a longer hand-held burst. */
    val superResolution: Float = 1f,
)

data class CapturePlan(
    val frames: Int,
    val exposureNs: Long,
    val iso: Int,
    /** Digital exposure applied in processing to undo planned underexposure (EV). */
    val processingEv: Float,
    val merge: Boolean,
    val reason: String,
) {
    val totalDurationMs: Long get() = frames * max(exposureNs, 33_333_333L) / 1_000_000L
}

object CapturePlanner {

    private const val HANDHELD_NIGHT_MAX_NS = 125_000_000L
    private const val MOVING_NIGHT_MAX_NS = 50_000_000L
    private const val NIGHT_TARGET_ISO = 400

    fun plan(input: PlanInput): CapturePlan {
        val base = when (input.mode) {
            CaptureMode.PHOTO -> photo(input)
            CaptureMode.NIGHT -> night(input)
            CaptureMode.PRO -> pro(input)
        }
        if (input.superResolution <= 1.01f) return base
        // Super-resolution: enough frames that tremor covers the sub-pixel phases of the denser grid
        // (~10 for 1.4x per side / 24MP, ~14 for 2x / 50MP); never fewer than the mode planned.
        val needed = if (input.superResolution <= 1.5f) SR_FRAMES_24 else SR_FRAMES_50
        val frames = max(base.frames, needed).coerceAtMost(MAX_FRAMES)
        return base.copy(frames = frames, merge = true,
            reason = base.reason.replaceFirst(Regex("^(single frame \\([^)]*\\)|\\d+-frame merge)"), "$frames-frame") +
                ", %.1fx super-resolution burst".format(java.util.Locale.ROOT, input.superResolution))
    }

    const val SR_FRAMES_24 = 10
    const val SR_FRAMES_50 = 14
    /** Engine limit per burst (engine/src/pipeline.cpp kMaxFrames). */
    const val MAX_FRAMES = 16

    private fun photo(i: PlanInput): CapturePlan {
        // Highlight protection: underexpose when the preview shows clipping, recover in processing.
        val under = when {
            i.clippedFraction > 0.03f -> 1.0f
            i.clippedFraction > 0.01f -> 0.7f
            i.clippedFraction > 0.003f -> 0.4f
            else -> 0f
        }
        var exposure = (i.previewExposureNs * 2.0.pow(-under.toDouble()) * i.apertureFactor).roundToLong()
        var iso = i.previewIso
        // Motion: halve the exposure (and double ISO) so each frame stays sharp.
        val moving = i.motion > 0.06f
        if (moving && exposure > 8_000_000L) {
            exposure /= 2
            iso *= 2
        }
        val (e, s) = fit(exposure, iso, i)
        // Spec CP-1: a single frame unless dynamic range, light or noise justify merging.
        val frames = when {
            under == 0f && i.previewIso <= 100 && !moving -> 1
            moving -> 4
            i.previewIso <= 200 -> 4
            i.previewIso <= 800 -> 6
            i.previewIso <= 1600 -> 8
            else -> 10
        }
        val reason = buildString {
            append(if (frames == 1) "single frame (bright, low contrast)" else "$frames-frame merge")
            if (under > 0f) append(", -%.1f EV for highlights".format(java.util.Locale.ROOT, under))
            if (moving) append(", motion: shorter frames")
        }
        return CapturePlan(frames, e, s, processingEv = under, merge = frames > 1, reason = reason)
    }

    private fun night(i: PlanInput): CapturePlan {
        val product = i.previewExposureNs.toDouble() * i.previewIso * i.apertureFactor  // ns * ISO
        val moving = i.motion > 0.04f
        val maxT = if (moving) MOVING_NIGHT_MAX_NS else HANDHELD_NIGHT_MAX_NS
        // Longer, lower-ISO frames than preview AE, capped for handheld sharpness.
        var exposure = (product / NIGHT_TARGET_ISO).roundToLong().coerceIn(i.previewExposureNs, maxT)
        var iso = (product / exposure).roundToInt()
        val (e, s) = fit(exposure, iso, i)
        exposure = e
        iso = s
        val frames = when {
            exposure >= 100_000_000L -> 10
            exposure >= 50_000_000L -> 12
            else -> 15
        }
        // Night renders brighter than the preview AE target but keeps lights from clipping.
        val under = if (i.clippedFraction > 0.005f) 0.7f else 0f
        val finalExposure = (exposure * 2.0.pow(-under.toDouble())).roundToLong().coerceAtLeast(i.exposureMinNs)
        return CapturePlan(frames, finalExposure, iso, processingEv = under + 0.6f, merge = true,
            reason = "$frames × ${finalExposure / 1_000_000} ms night merge")
    }

    private fun pro(i: PlanInput): CapturePlan {
        val exposure = i.manualExposureNs ?: (i.previewExposureNs * i.apertureFactor).roundToLong()
        val iso = i.manualIso ?: i.previewIso
        val (e, s) = fit(exposure, iso, i)
        val frames = if (i.proMultiFrame) 6 else 1
        return CapturePlan(frames, e, s, processingEv = 0f, merge = frames > 1,
            reason = if (frames > 1) "Pro, $frames-frame noise reduction" else "Pro, single frame")
    }

    /** Clamps exposure and ISO to the sensor ranges, moving any excess into the other parameter. */
    internal fun fit(exposureNs: Long, iso: Int, i: PlanInput): Pair<Long, Int> {
        var e = exposureNs.toDouble()
        var s = iso.toDouble()
        if (e > i.exposureMaxNs) {
            s *= e / i.exposureMaxNs
            e = i.exposureMaxNs.toDouble()
        }
        if (e < i.exposureMinNs) {
            s *= e / i.exposureMinNs
            e = i.exposureMinNs.toDouble()
        }
        if (s > i.isoMax) {
            e = min(i.exposureMaxNs.toDouble(), e * s / i.isoMax)
            s = i.isoMax.toDouble()
        }
        if (s < i.isoMin) {
            e = max(i.exposureMinNs.toDouble(), e * s / i.isoMin)
            s = i.isoMin.toDouble()
        }
        return e.roundToLong() to s.roundToInt()
    }
}
