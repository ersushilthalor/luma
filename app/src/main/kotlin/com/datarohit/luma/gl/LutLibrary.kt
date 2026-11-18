package com.datarohit.luma.gl

import java.io.InputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A 3D LUT: [size]^3 RGB entries, red fastest (the .cube order). */
class Lut3d(val name: String, val size: Int, val data: FloatArray) {
    init {
        require(data.size == size * size * size * 3) { "LUT data does not match size $size" }
    }
}

/**
 * Luma Log: the tone curve Luma asks the ISP to apply (TONEMAP_MODE_CONTRAST_CURVE) for flat recording,
 * and its exact inverse used by the built-in Rec.709 conversion LUT.
 */
object LumaLog {
    private const val K = 48f
    private val NORM = ln(1f + K)

    /** Scene-linear (0..1) to Luma Log code value (0..1). */
    fun encode(linear: Float): Float = (ln(1f + K * linear.coerceIn(0f, 1f)) / NORM).coerceIn(0f, 1f)

    /** Luma Log code value to scene-linear. */
    fun decode(code: Float): Float = ((exp(code.coerceIn(0f, 1f) * NORM) - 1f) / K)

    /** Curve points (in, out pairs) for TonemapCurve, [points] samples. */
    fun curve(points: Int): FloatArray {
        val n = points.coerceIn(2, 64)
        return FloatArray(n * 2) { i ->
            val x = (i / 2).toFloat() / (n - 1)
            if (i % 2 == 0) x else encode(x)
        }
    }
}

object LutLibrary {

    data class Entry(val id: String, val label: String, val build: () -> Lut3d)

    /** Built-in looks. "Log → Rec.709" is the technical conversion; the others are creative grades. */
    val builtIn: List<Entry> = listOf(
        Entry("log709", "Log → Rec.709") { generate("Log → Rec.709", 33) { r, g, b -> logTo709(r, g, b) } },
        Entry("film", "Warm Film") { generate("Warm Film", 33) { r, g, b -> film(r, g, b) } },
        Entry("teal", "Teal & Orange") { generate("Teal & Orange", 33) { r, g, b -> tealOrange(r, g, b) } },
        Entry("mono", "Silver Mono") { generate("Silver Mono", 33) { r, g, b -> mono(r, g, b) } },
        Entry("night", "Night Blue") { generate("Night Blue", 33) { r, g, b -> nightBlue(r, g, b) } },
    )

    fun generate(name: String, size: Int, f: (Float, Float, Float) -> FloatArray): Lut3d {
        val data = FloatArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val out = f(r / (size - 1f), g / (size - 1f), b / (size - 1f))
            data[i++] = out[0].coerceIn(0f, 1f)
            data[i++] = out[1].coerceIn(0f, 1f)
            data[i++] = out[2].coerceIn(0f, 1f)
        }
        return Lut3d(name, size, data)
    }

    /** Parses an Adobe/Resolve .cube file (3D only; DOMAIN_MIN/MAX honoured). */
    fun parseCube(name: String, input: InputStream): Lut3d {
        var size = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        var title = name
        val values = ArrayList<Float>()
        input.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"').ifEmpty { name }
                    "LUT_3D_SIZE" -> size = parts[1].toInt()
                    "LUT_1D_SIZE" -> error("1D LUTs are not supported")
                    "DOMAIN_MIN" -> domainMin = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    "DOMAIN_MAX" -> domainMax = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    "LUT_3D_INPUT_RANGE" -> {
                        domainMin = FloatArray(3) { parts[1].toFloat() }
                        domainMax = FloatArray(3) { parts[2].toFloat() }
                    }
                    else -> if (parts.size >= 3 && parts[0].first().let { it.isDigit() || it == '-' || it == '.' }) {
                        values += parts[0].toFloat(); values += parts[1].toFloat(); values += parts[2].toFloat()
                    }
                }
            }
        }
        require(size in 2..129) { "Missing or invalid LUT_3D_SIZE" }
        require(values.size == size * size * size * 3) { "Expected ${size * size * size} entries, found ${values.size / 3}" }
        val data = FloatArray(values.size)
        for (i in values.indices) {
            val c = i % 3
            data[i] = ((values[i] - domainMin[c]) / (domainMax[c] - domainMin[c]).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        }
        return Lut3d(title, size, data)
    }

    /** Writes a LUT as .cube text (used for exports and tests). */
    fun toCube(lut: Lut3d): String = buildString {
        append("TITLE \"").append(lut.name).append("\"\n")
        append("LUT_3D_SIZE ").append(lut.size).append('\n')
        for (i in 0 until lut.data.size / 3) {
            append(String.format(java.util.Locale.ROOT, "%.6f %.6f %.6f\n", lut.data[i * 3], lut.data[i * 3 + 1], lut.data[i * 3 + 2]))
        }
    }

    // ------------------------------------------------------------------------------------------

    private fun rec709Oetf(l: Float): Float = if (l < 0.018f) 4.5f * l else 1.099f * l.pow(0.45f) - 0.099f

    /** Luma Log → scene linear → mild filmic shoulder → Rec.709 OETF. */
    private fun logTo709(r: Float, g: Float, b: Float): FloatArray {
        fun ch(v: Float): Float {
            val lin = LumaLog.decode(v)
            val shoulder = lin * (1f + lin / 1.6f) / (1f + lin)
            return rec709Oetf((shoulder * 1.08f).coerceIn(0f, 1f))
        }
        return floatArrayOf(ch(r), ch(g), ch(b))
    }

    private fun sCurve(x: Float, amount: Float): Float {
        val s = x * x * (3f - 2f * x)
        return x + (s - x) * amount
    }

    private fun film(r: Float, g: Float, b: Float): FloatArray {
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val lift = 0.03f
        return floatArrayOf(
            sCurve(lift + r * 0.97f * 1.04f, 0.35f),
            sCurve(lift + g * 0.97f, 0.35f),
            sCurve(lift * 1.4f + b * 0.97f * 0.92f + (1f - y) * 0.02f, 0.30f),
        )
    }

    private fun tealOrange(r: Float, g: Float, b: Float): FloatArray {
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val shadowW = (1f - y).pow(2f)
        val highW = y.pow(1.5f)
        val rr = r + 0.06f * highW - 0.04f * shadowW
        val gg = g + 0.01f * highW + 0.02f * shadowW
        val bb = b - 0.06f * highW + 0.07f * shadowW
        val sat = 1.12f
        val y2 = 0.2126f * rr + 0.7152f * gg + 0.0722f * bb
        return floatArrayOf(sCurve(y2 + (rr - y2) * sat, 0.3f), sCurve(y2 + (gg - y2) * sat, 0.3f), sCurve(y2 + (bb - y2) * sat, 0.3f))
    }

    private fun mono(r: Float, g: Float, b: Float): FloatArray {
        val y = sCurve(0.30f * r + 0.59f * g + 0.11f * b, 0.45f)
        return floatArrayOf(y, y, min(1f, y * 1.01f))
    }

    private fun nightBlue(r: Float, g: Float, b: Float): FloatArray {
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val k = max(0f, 1f - y * 1.4f)
        return floatArrayOf(r * (1f - 0.12f * k), g * (1f - 0.04f * k), b + 0.10f * k * (1f - b))
    }
}
