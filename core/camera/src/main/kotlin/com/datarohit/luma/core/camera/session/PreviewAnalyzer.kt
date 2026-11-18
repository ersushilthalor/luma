package com.datarohit.luma.core.camera.session

import android.media.Image
import kotlin.math.abs

/** Luminance statistics of the YUV analysis stream (histogram, clipping, motion). */
internal class PreviewAnalyzer {

    private var previousGrid: FloatArray? = null
    private var frame = 0

    fun analyze(image: Image): AnalysisStats? {
        if (frame++ % 2 != 0) return null
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val histogram = IntArray(BINS)
        var clipped = 0
        var shadows = 0
        var sum = 0L
        var count = 0
        val grid = FloatArray(GRID_W * GRID_H)
        val gridCount = IntArray(GRID_W * GRID_H)
        var y = 0
        while (y < h) {
            val row = y * rowStride
            val gy = y * GRID_H / h
            var x = 0
            while (x < w) {
                val v = buffer.get(row + x * pixelStride).toInt() and 0xFF
                histogram[v * BINS / 256]++
                if (v >= 250) clipped++
                if (v <= 8) shadows++
                sum += v
                count++
                val gi = gy * GRID_W + x * GRID_W / w
                grid[gi] += v.toFloat()
                gridCount[gi]++
                x += STEP
            }
            y += STEP
        }
        for (i in grid.indices) if (gridCount[i] > 0) grid[i] /= gridCount[i]
        // Focus peaking: strong local gradients on a coarse grid.
        val pw = w / STEP
        val ph = h / STEP
        val peaking = BooleanArray(pw * ph)
        for (py in 1 until ph - 1) {
            val yy = py * STEP
            for (px in 1 until pw - 1) {
                val xx = px * STEP
                val c = buffer.get(yy * rowStride + xx * pixelStride).toInt() and 0xFF
                val r = buffer.get(yy * rowStride + (xx + 2) * pixelStride).toInt() and 0xFF
                val d = buffer.get((yy + 2) * rowStride + xx * pixelStride).toInt() and 0xFF
                val gradient = abs(r - c) + abs(d - c)
                peaking[py * pw + px] = gradient > PEAK_THRESHOLD + c / 8
            }
        }
        val prev = previousGrid
        var motion = 0f
        if (prev != null) {
            var diff = 0f
            var level = 0f
            for (i in grid.indices) {
                diff += abs(grid[i] - prev[i])
                level += grid[i]
            }
            motion = if (level > 0f) diff / level else 0f
        }
        previousGrid = grid
        return AnalysisStats(
            histogram = histogram,
            clippedFraction = clipped.toFloat() / count.coerceAtLeast(1),
            shadowFraction = shadows.toFloat() / count.coerceAtLeast(1),
            peaking = peaking,
            peakingWidth = pw,
            peakingHeight = ph,
            meanLuma = sum.toFloat() / count.coerceAtLeast(1) / 255f,
            motion = motion,
        )
    }

    private companion object {
        const val BINS = 64
        const val STEP = 4
        const val GRID_W = 16
        const val GRID_H = 12
        const val PEAK_THRESHOLD = 38
    }
}
