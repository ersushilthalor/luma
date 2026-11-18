package com.datarohit.luma.core.camera.lens

import kotlin.math.sqrt

/** Subject distance and depth of field derived from the HAL's focus distance (diopters). */
data class FocusReadout(
    /** Subject distance in metres; null = infinity. */
    val distanceM: Float?,
    val nearM: Float?,
    /** Far limit; null = infinity (focused at or beyond hyperfocal). */
    val farM: Float?,
    val hyperfocalM: Float,
    val beyondHyperfocal: Boolean,
)

object FocusMath {

    /** Diopters (1/m) to metres; 0 or less = infinity (null). */
    fun dioptersToMeters(d: Float): Float? = if (d <= 1e-3f) null else 1f / d

    /** "12 cm", "1.4 m", "∞" with "≈" for approximate calibration. */
    fun format(meters: Float?, approximate: Boolean = true): String {
        val prefix = if (approximate) "≈ " else ""
        return when {
            meters == null || meters > 99f -> "∞"
            meters < 1f -> prefix + "${(meters * 100f).toInt()} cm"
            meters < 10f -> prefix + String.format(java.util.Locale.ROOT, "%.1f m", meters)
            else -> prefix + "${meters.toInt()} m"
        }
    }

    /**
     * Thin-lens depth of field. [focalMm] real focal length, [aperture] f-number, [sensorDiagonalMm] for the
     * circle of confusion (diagonal / 1500).
     */
    fun readout(diopters: Float, focalMm: Float, aperture: Float, sensorDiagonalMm: Float): FocusReadout {
        val coc = (sensorDiagonalMm / 1500f).coerceAtLeast(1e-4f)
        val f = focalMm / 1000f
        val c = coc / 1000f
        val n = aperture.coerceAtLeast(0.7f)
        val h = f * f / (n * c) + f
        val s = dioptersToMeters(diopters)
        if (s == null || s >= h) {
            return FocusReadout(s, nearM = h / 2f, farM = null, hyperfocalM = h, beyondHyperfocal = true)
        }
        val near = s * (h - f) / (h + s - 2f * f)
        val far = s * (h - f) / (h - s)
        return FocusReadout(s, near, far, h, beyondHyperfocal = false)
    }

    fun sensorDiagonal(widthMm: Float, heightMm: Float): Float = sqrt(widthMm * widthMm + heightMm * heightMm)

    /**
     * Relative background blur for a subject at [distanceM] compared with a 1 m reference (blur disc of a
     * distant background scales with f² / (N (s − f)) ≈ 1/s). Clamped for UI use.
     */
    fun bokehScale(distanceM: Float?): Float {
        val s = distanceM ?: return 0.35f
        return (0.6f / s.coerceAtLeast(0.08f)).coerceIn(0.35f, 2.2f)
    }
}

/**
 * Focus Enhancer decision: switch to the ultra-wide (macro) when the main lens reports a subject closer than
 * [enterM] for [frames] consecutive results, and back when the ultra-wide reports one farther than [exitM].
 */
class MacroSwitch(private val enterM: Float = 0.18f, private val exitM: Float = 0.30f, private val frames: Int = 4) {
    private var count = 0
    var active = false
        private set

    /** Feeds one focus result; returns true when [active] changed. */
    fun update(diopters: Float, focusSettled: Boolean): Boolean {
        val m = FocusMath.dioptersToMeters(diopters) ?: Float.MAX_VALUE
        val want = if (!active) m < enterM && focusSettled else m <= exitM
        if (!active && want || active && !want) count++ else count = 0
        if (count >= frames) {
            active = !active
            count = 0
            return true
        }
        return false
    }

    fun reset() {
        active = false
        count = 0
    }
}
