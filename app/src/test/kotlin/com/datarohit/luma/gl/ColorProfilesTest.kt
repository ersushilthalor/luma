package com.datarohit.luma.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow

class ColorProfilesTest {

    /** Mirror of the shader's filmic() for checking continuity and range. */
    private fun filmic(x: Float, white: Float): Float {
        val mid = 0.18f
        val mo = ColorProfiles.MID_OUT
        if (x <= mid) return mo * (x / mid).pow(ColorProfiles.TOE)
        val k = ColorProfiles.shoulderFor(white)
        val u = (x - mid) / (white - mid)
        return minOf(1f, mo + (1f - mo) * (1f - exp(-k * u)) / (1f - exp(-k)))
    }

    @Test
    fun curveIsContinuousSmoothAndReachesWhite() {
        for (white in listOf(1f, 1.62f, 2.46f)) {
            val e = 1e-4f
            val below = (filmic(0.18f, white) - filmic(0.18f - e, white)) / e
            val above = (filmic(0.18f + e, white) - filmic(0.18f, white)) / e
            assertEquals("slope matches at mid grey (W=$white)", below, above, below * 0.02f)
            assertEquals("scene white maps to display white (W=$white)", 1f, filmic(white, white), 1e-3f)
            assertEquals(0f, filmic(0f, white), 0f)
            var last = -1f
            var x = 0f
            while (x <= white) {
                val y = filmic(x, white)
                assertTrue("monotonic at $x", y >= last)
                last = y
                x += white / 400f
            }
        }
    }

    @Test
    fun toeKeepsBlacksBelowIdentity() {
        // Deep shadows render darker than a straight scaling of mid grey would: no lifted haze.
        val y = filmic(0.02f, 1.62f)
        assertTrue(y < ColorProfiles.MID_OUT * 0.02f / 0.18f)
    }

    @Test
    fun naturalAndVividKeepNeutralsNeutralAndStayInGamut() {
        for (look in listOf(ColorLook.NATURAL, ColorLook.VIVID)) {
            for (v in listOf(0f, 0.18f, 0.5f, 0.8f, 1f)) {
                val out = ColorProfiles.shape(look, v, v, v)
                assertEquals("$look grey $v R", v, out[0], 0.01f)
                assertEquals("$look grey $v G", v, out[1], 0.01f)
                assertEquals("$look grey $v B", v, out[2], 0.01f)
            }
        }
        for (look in ColorLook.entries) {
            var r = 0f
            while (r <= 1f) {
                var g = 0f
                while (g <= 1f) {
                    var b = 0f
                    while (b <= 1f) {
                        val o = ColorProfiles.shape(look, r, g, b)
                        assertTrue("$look in gamut", o.all { it in -0.001f..1.001f })
                        b += 0.25f
                    }
                    g += 0.25f
                }
                r += 0.25f
            }
        }
    }

    @Test
    fun vividAddsChromaButNaturalSkinStaysClean() {
        fun chroma(c: FloatArray): Float {
            val lin = c.map { if (it <= 0.04045f) it / 12.92f else ((it + 0.055f) / 1.055f).pow(2.4f) }
            val lab = ColorProfiles.toOklab(lin[0], lin[1], lin[2])
            return hypot(lab[1], lab[2])
        }
        val leaf = floatArrayOf(0.35f, 0.55f, 0.25f)
        assertTrue(chroma(ColorProfiles.shape(ColorLook.VIVID, leaf[0], leaf[1], leaf[2])) > chroma(leaf))
        // A typical skin tone keeps its chroma within 6 % in Natural (no orange push, no pasty drain).
        val skin = floatArrayOf(0.85f, 0.62f, 0.52f)
        val out = ColorProfiles.shape(ColorLook.NATURAL, skin[0], skin[1], skin[2])
        assertTrue(abs(chroma(out) / chroma(skin) - 1f) < 0.06f)
    }
}
