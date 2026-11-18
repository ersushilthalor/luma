package com.datarohit.luma.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Incremental cylindrical panorama. Frames arrive upright, each roughly [stepPx] further along the pan;
 * every frame is warped onto a cylinder of radius [focalPx], registered against the previous frame
 * (translation search around the gyro estimate, both pan signs), and feather-blended into the canvas.
 * Vertical pans are handled by rotating frames 90° and back.
 */
class PanoramaStitcher(
    private val focalPx: Float,
    private val vertical: Boolean,
    private val maxCanvasWidth: Int = 10_000,
) {
    private var frameW = 0
    private var frameH = 0
    private var canvas: IntArray? = null
    private var canvasW = 0
    private var canvasH = 0
    private var margin = 0
    private var prevGray: IntArray? = null
    private var prevX = 0
    private var prevY = 0
    private var minX = Int.MAX_VALUE
    private var maxX = Int.MIN_VALUE
    private var validTop = 0
    private var validBottom = Int.MAX_VALUE
    private var direction = 0
    private lateinit var colTheta: FloatArray
    private lateinit var colTan: FloatArray
    private lateinit var colCos: FloatArray
    var frames = 0
        private set

    /** Pixels the pan should advance between keyframes for ~65 % overlap. */
    val stepFraction = 0.35f

    /** Adds one frame. [expectedShiftPx] is the gyro estimate of the pan since the previous frame (unsigned). */
    @Synchronized
    fun add(input: Bitmap, expectedShiftPx: Float): Boolean {
        val bmp = if (vertical) rotate(input, 90f) else input
        if (frames == 0) init(bmp.width, bmp.height)
        if (bmp.width != frameW || bmp.height != frameH) return false
        val warped = warp(bmp)
        if (bmp !== input) bmp.recycle()
        val gray = downGray(warped)
        var x: Int
        var y: Int
        if (frames == 0) {
            x = (canvasW - frameW) / 2
            y = margin
        } else {
            val (dx, dy) = register(prevGray!!, gray, expectedShiftPx)
            if (direction == 0) direction = if (dx >= 0) 1 else -1
            x = prevX + dx
            y = (prevY + dy).coerceIn(0, canvasH - frameH)
            if (x < 0 || x + frameW > canvasW) return false
        }
        blend(warped, x, y)
        prevGray = gray
        prevX = x
        prevY = y
        minX = min(minX, x)
        maxX = max(maxX, x + frameW)
        val edge = ((frameH / 2f) * (1f - cos(colTheta[0]))).roundToInt()
        validTop = max(validTop, y + edge)
        validBottom = min(validBottom, y + frameH - edge)
        frames++
        return true
    }

    /** Pan length covered so far in pixels. */
    val coveredPx: Int get() = if (frames == 0) 0 else maxX - minX

    @Synchronized
    fun finish(): Bitmap? {
        val c = canvas ?: return null
        if (frames < 2) return null
        val top = validTop.coerceIn(0, canvasH - 1)
        val bottom = validBottom.coerceIn(top + 1, canvasH)
        val w = maxX - minX
        val h = bottom - top
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(c, top * canvasW + minX, canvasW, 0, 0, w, h)
        canvas = null
        return if (vertical) rotate(out, -90f).also { out.recycle() } else out
    }

    // ------------------------------------------------------------------------------------------

    private fun init(w: Int, h: Int) {
        frameW = w
        frameH = h
        margin = (h * 0.08f).roundToInt()
        canvasW = maxCanvasWidth.coerceAtLeast(w * 2)
        canvasH = h + margin * 2
        canvas = IntArray(canvasW * canvasH)
        colTheta = FloatArray(w)
        colTan = FloatArray(w)
        colCos = FloatArray(w)
        for (x in 0 until w) {
            val theta = (x - w / 2f) / focalPx
            colTheta[x] = theta
            colTan[x] = tan(theta)
            colCos[x] = cos(theta)
        }
    }

    /** Cylindrical projection (bilinear). Pixels outside the source stay transparent. */
    private fun warp(src: Bitmap): IntArray {
        val w = frameW
        val h = frameH
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val out = IntArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        for (x in 0 until w) {
            val sx = focalPx * colTan[x] + cx
            if (sx < 0f || sx > w - 1.001f) continue
            val x0 = sx.toInt()
            val fx = sx - x0
            val inv = 1f / colCos[x]
            for (y in 0 until h) {
                val sy = (y - cy) * inv + cy
                if (sy < 0f || sy > h - 1.001f) continue
                val y0 = sy.toInt()
                val fy = sy - y0
                val i = y0 * w + x0
                out[y * w + x] = lerp4(px[i], px[i + 1], px[i + w], px[i + w + 1], fx, fy)
            }
        }
        return out
    }

    private fun lerp4(a: Int, b: Int, c: Int, d: Int, fx: Float, fy: Float): Int {
        fun ch(shift: Int): Int {
            val top = ((a shr shift) and 0xFF) * (1 - fx) + ((b shr shift) and 0xFF) * fx
            val bot = ((c shr shift) and 0xFF) * (1 - fx) + ((d shr shift) and 0xFF) * fx
            return (top * (1 - fy) + bot * fy).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val scale = 4

    private fun downGray(px: IntArray): IntArray {
        val w = frameW / scale
        val h = frameH / scale
        val g = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = px[(y * scale) * frameW + x * scale]
            g[y * w + x] = if (p ushr 24 == 0) -1 else (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
        }
        return g
    }

    /** Finds the translation of [cur] relative to [prev] (full-resolution pixels). */
    private fun register(prev: IntArray, cur: IntArray, expected: Float): Pair<Int, Int> {
        val w = frameW / scale
        val h = frameH / scale
        val e = (expected / scale).roundToInt().coerceIn(w / 10, w * 7 / 10)
        val lo = max(2, (e * 0.6f).roundToInt())
        val hi = min(w - w / 6, (e * 1.4f).roundToInt() + 2)
        val signs = if (direction == 0) intArrayOf(1, -1) else intArrayOf(direction)
        var best = Long.MAX_VALUE
        var bestDx = e
        var bestDy = 0
        var bestSign = signs[0]
        for (sign in signs) for (s in lo..hi) for (dy in -h / 12..h / 12) {
            // cur pixel (x, y) corresponds to prev pixel (x + sign*s, y + dy).
            var sum = 0L
            var n = 0
            var y = max(0, -dy) + 2
            while (y < min(h, h - dy) - 2) {
                var x = if (sign > 0) 0 else s
                val xEnd = if (sign > 0) w - s else w
                while (x < xEnd) {
                    val a = cur[y * w + x]
                    val b = prev[(y + dy) * w + x + sign * s]
                    if (a >= 0 && b >= 0) {
                        sum += abs(a - b)
                        n++
                    }
                    x += 2
                }
                y += 2
            }
            if (n < 200) continue
            val cost = sum * 1024 / n
            if (cost < best) {
                best = cost
                bestDx = s
                bestDy = dy
                bestSign = sign
            }
        }
        return (bestSign * bestDx * scale) to (bestDy * scale)
    }

    /** Pastes [px] at (x, y) with a linear feather across the overlap with existing content. */
    private fun blend(px: IntArray, x: Int, y: Int) {
        val c = canvas ?: return
        val w = frameW
        val h = frameH
        val overlapStart: Int
        val overlapEnd: Int
        if (frames == 0) {
            overlapStart = 0
            overlapEnd = 0
        } else if (x >= prevX) {
            overlapStart = x
            overlapEnd = min(prevX + w, x + w)
        } else {
            overlapStart = max(prevX, x)
            overlapEnd = x + w
        }
        val span = max(1, overlapEnd - overlapStart)
        for (yy in 0 until h) {
            val cy = y + yy
            if (cy < 0 || cy >= canvasH) continue
            val row = cy * canvasW
            for (xx in 0 until w) {
                val p = px[yy * w + xx]
                if (p ushr 24 == 0) continue
                val cx = x + xx
                val existing = c[row + cx]
                if (existing ushr 24 == 0) {
                    c[row + cx] = p
                    continue
                }
                if (cx < overlapStart || cx >= overlapEnd) continue
                var t = (cx - overlapStart).toFloat() / span
                if (x < prevX) t = 1f - t
                c[row + cx] = mix(existing, p, t)
            }
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun rotate(b: Bitmap, deg: Float): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)

    companion object {
        /** Focal length in pixels for an upright frame [frameWidthPx] wide whose width spans [hfovRad]. */
        fun focalFromFov(frameWidthPx: Int, hfovRad: Float): Float = (frameWidthPx / 2f) / tan(hfovRad / 2f)

        fun fovFromFocal(frameWidthPx: Int, focalPx: Float): Float = 2f * atan(frameWidthPx / 2f / focalPx)
    }
}
