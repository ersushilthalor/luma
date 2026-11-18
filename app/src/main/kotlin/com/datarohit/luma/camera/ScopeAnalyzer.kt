package com.datarohit.luma.camera

import android.media.Image

/** Live scopes from one analysis frame: RGB+luma histograms and an RGB waveform in upright columns. */
class Scopes(
    /** 4 x [BINS]: R, G, B, luma. */
    val histogram: IntArray,
    /** 3 x [COLUMNS] x [BINS]: per channel, per upright column, counts per level. */
    val waveform: IntArray,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        const val BINS = 64
        const val COLUMNS = 96
    }
}

object ScopeAnalyzer {

    /**
     * Samples a YUV_420_888 frame on a sparse grid (BT.709 full-range conversion). [sensorOrientation] maps
     * sensor columns to upright display columns; [mirror] flips them for the front camera.
     */
    fun analyze(image: Image, sensorOrientation: Int, mirror: Boolean): Scopes {
        val bins = Scopes.BINS
        val cols = Scopes.COLUMNS
        val hist = IntArray(4 * bins)
        val wave = IntArray(3 * cols * bins)
        val w = image.width
        val h = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uvRow = uPlane.rowStride
        val uvPix = uPlane.pixelStride
        val stepX = (w / 160).coerceAtLeast(1)
        val stepY = (h / 120).coerceAtLeast(1)
        var sy = 0
        while (sy < h) {
            var sx = 0
            while (sx < w) {
                val yv = (yBuf.get(sy * yRow + sx * yPix).toInt() and 0xFF)
                val uvIndex = (sy / 2) * uvRow + (sx / 2) * uvPix
                val u = (uBuf.get(uvIndex).toInt() and 0xFF) - 128
                val v = (vBuf.get(uvIndex).toInt() and 0xFF) - 128
                val r = (yv + 1.5748f * v).toInt().coerceIn(0, 255)
                val g = (yv - 0.1873f * u - 0.4681f * v).toInt().coerceIn(0, 255)
                val b = (yv + 1.8556f * u).toInt().coerceIn(0, 255)
                val rb = r * bins / 256
                val gb = g * bins / 256
                val bb = b * bins / 256
                hist[rb]++
                hist[bins + gb]++
                hist[2 * bins + bb]++
                hist[3 * bins + yv * bins / 256]++
                // Upright horizontal position of this sample.
                val fx = sx.toFloat() / w
                val fy = sy.toFloat() / h
                var ux = when (sensorOrientation) {
                    90 -> 1f - fy
                    180 -> 1f - fx
                    270 -> fy
                    else -> fx
                }
                if (mirror) ux = 1f - ux
                val col = (ux * cols).toInt().coerceIn(0, cols - 1)
                wave[(0 * cols + col) * bins + rb]++
                wave[(1 * cols + col) * bins + gb]++
                wave[(2 * cols + col) * bins + bb]++
                sx += stepX
            }
            sy += stepY
        }
        return Scopes(hist, wave)
    }
}
