package com.datarohit.luma.gl

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Display looks applied after Luma's tone mapping, as 33³ 3D LUTs on gamma-encoded Rec.709 (uploaded as
 * RGB16F). All shaping happens in OKLCh at constant lightness unless stated, and every result is pulled
 * back into gamut by reducing chroma at constant hue, so no profile can clip a colour channel.
 */
enum class ColorLook(val label: String, val blurb: String) {
    NATURAL("Natural", "Reference colour: clean skin, true foliage and skies"),
    CINEMA("Cinema", "Rich contrast, warm highlights, cool shadows, fine grain"),
    VIVID("Vivid", "More colour, without clipping or neon skies"),
}

object ColorProfiles {

    const val SIZE = 33

    fun build(look: ColorLook): Lut3d = LutLibrary.generate("look-${look.name.lowercase()}", SIZE) { r, g, b -> shape(look, r, g, b) }

    /** Grain amplitude (display code values) used with each look. */
    fun grain(look: ColorLook): Float = if (look == ColorLook.CINEMA) 0.012f else 0.004f

    internal fun shape(look: ColorLook, r: Float, g: Float, b: Float): FloatArray {
        val lab = toOklab(decode(r), decode(g), decode(b))
        var l = lab[0]
        var c = hypot(lab[1], lab[2])
        var h = atan2(lab[2], lab[1])  // radians
        val hd = Math.toDegrees(h.toDouble()).toFloat().let { if (it < 0) it + 360f else it }
        val chromatic = smooth(0.01f, 0.04f, c)

        // Memory colours (OKLCh hue angles): skin ~ 30-75°, foliage ~ 110-150°, sky ~ 215-265°.
        val skin = band(hd, 25f, 80f) * chromatic
        val foliage = band(hd, 105f, 160f) * chromatic
        val sky = band(hd, 210f, 270f) * chromatic

        // Skin: pull toward a clean, warm 52° (away from yellow-green and from pink), cap chroma.
        h += Math.toRadians(((52f - hd) * 0.22f * skin).toDouble()).toFloat()
        // Foliage: a touch toward yellow-green, less chroma (no neon greens).
        h += Math.toRadians(((128f - hd) * 0.12f * foliage).toDouble()).toFloat()
        // Sky: hold the hue (no drift to cyan), slightly less chroma in bright skies.
        h += Math.toRadians(((242f - hd) * 0.10f * sky).toDouble()).toFloat()

        when (look) {
            ColorLook.NATURAL -> {
                // Clean, slightly richer than neutral (Vivo/iPhone reference rendering), skin kept natural.
                c *= (1.07f - 0.07f * skin) * (1f - 0.05f * foliage - 0.06f * sky * smooth(0.6f, 0.95f, l))
            }
            ColorLook.CINEMA -> {
                // Gentle S-curve on lightness around mid grey, slightly reduced chroma, then a split tone:
                // warm highlights and cool shadows (applied as an a/b offset, scaled to avoid neutrals drifting).
                l = (l + 0.05f * sin(2f * PI.toFloat() * (l - 0.5f)) * smooth(0.02f, 0.2f, l)).coerceIn(0f, 1f)
                c *= 0.9f - 0.04f * foliage
            }
            ColorLook.VIVID -> {
                val boost = 1.28f - 0.14f * skin - 0.06f * sky
                c = 0.34f * tanh(c * boost / 0.34f)
            }
        }
        var a = c * cos(h)
        var bb = c * sin(h)
        if (look == ColorLook.CINEMA) {
            val hi = smooth(0.55f, 0.95f, l)
            val lo = 1f - smooth(0.05f, 0.45f, l)
            a += 0.006f * hi - 0.002f * lo
            bb += 0.014f * hi - 0.010f * lo
        }
        return encodeInGamut(l, a, bb)
    }

    private fun encodeInGamut(l: Float, a: Float, b: Float): FloatArray {
        var lo = 0f
        var hi = 1f
        var rgb = fromOklab(l, a, b)
        if (!inGamut(rgb)) {
            repeat(12) {
                val m = (lo + hi) / 2f
                if (inGamut(fromOklab(l, a * m, b * m))) lo = m else hi = m
            }
            rgb = fromOklab(l, a * lo, b * lo)
        }
        return floatArrayOf(encode(rgb[0]), encode(rgb[1]), encode(rgb[2]))
    }

    private fun inGamut(v: FloatArray) = v.all { it >= -1e-4f && it <= 1.0001f }

    /** 1 inside [lo, hi] hues with soft 12° edges, 0 outside. */
    private fun band(h: Float, lo: Float, hi: Float): Float {
        val e = 12f
        return when {
            h < lo - e || h > hi + e -> 0f
            h < lo -> (h - (lo - e)) / e
            h > hi -> ((hi + e) - h) / e
            else -> 1f
        }
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun decode(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun encode(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    internal fun toOklab(r: Float, g: Float, b: Float): FloatArray {
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    internal fun fromOklab(lv: Float, a: Float, b: Float): FloatArray {
        val l = lv + 0.3963377774f * a + 0.2158037573f * b
        val m = lv - 0.1055613458f * a - 0.0638541728f * b
        val s = lv - 0.0894841775f * a - 1.2914855480f * b
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        return floatArrayOf(
            4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3,
            -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3,
            -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3,
        )
    }

    /**
     * Display luminance of scene mid grey. Measured against the stock rendering of the same scene (code 149 at
     * the median vs 124 with a literal 0.18): phone video is judged at about +0.4 EV over scene-referred.
     */
    const val MID_OUT = 0.23f

    /** Log-log slope of the toe below mid grey (> 1 keeps blacks down; Samsung lifts them). */
    const val TOE = 1.35f

    /** Shoulder strength k so the filmic curve meets the toe smoothly (equal slope) at mid grey. */
    fun shoulderFor(white: Float, midOut: Float = MID_OUT): Float {
        val mid = 0.18f
        val slope = TOE * midOut / mid
        val target = slope * (white - mid) / (1f - midOut)
        // f(k) = k / (1 - e^-k) is increasing; solve f(k) = target by bisection.
        if (target <= 1.0001f) return 1e-3f
        var lo = 1e-3f
        var hi = 30f
        repeat(40) {
            val k = (lo + hi) / 2f
            if (k / (1f - exp(-k)) < target) lo = k else hi = k
        }
        return (lo + hi) / 2f
    }
}
