package com.datarohit.luma.capture

import com.datarohit.luma.settings.PhotoResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ResolutionCatalogTest {

    private fun mp(d: Pair<Int, Int>) = d.first.toLong() * d.second / 1e6

    @Test
    fun twelveKeepsTheNativeCrop() {
        assertEquals(4080 to 3060, ResolutionCatalog.outputDims(PhotoResolution.MP12, 4080, 3060))
        // A digital-zoom crop is never enlarged at 12MP.
        assertEquals(2040 to 1530, ResolutionCatalog.outputDims(PhotoResolution.MP12, 2040, 1530))
    }

    @Test
    fun higherTiersHitTheirPixelCountAndKeepAspect() {
        for (tier in listOf(PhotoResolution.MP24, PhotoResolution.MP50, PhotoResolution.MP200)) {
            val d = ResolutionCatalog.outputDims(tier, 4080, 3060)
            assertTrue("$tier gives ${mp(d)} MP", abs(mp(d) - tier.megapixels) / tier.megapixels < 0.01)
            assertEquals("$tier keeps 4:3", 4f / 3f, d.first.toFloat() / d.second, 0.002f)
            assertEquals(0, d.first % 2)
            assertEquals(0, d.second % 2)
        }
    }

    @Test
    fun fiftyAndTwoHundredMatchTheSensorGeometry() {
        // 2x and 4x of the 12.5MP binned readout: the same grid as the sensor's 50MP / 200MP modes.
        assertEquals(8160 to 6120, ResolutionCatalog.outputDims(PhotoResolution.MP50, 4080, 3060))
        assertEquals(16320 to 12240, ResolutionCatalog.outputDims(PhotoResolution.MP200, 4080, 3060))
    }

    @Test
    fun largeNativeFramesAreReducedToTwelve() {
        val d = ResolutionCatalog.outputDims(PhotoResolution.MP12, 8160, 6120)
        assertTrue(abs(mp(d) - 12.0) < 0.2)
    }

    @Test
    fun otherAspectsKeepTheirShape() {
        val d = ResolutionCatalog.outputDims(PhotoResolution.MP50, 4080, 2296)
        assertEquals(4080f / 2296f, d.first.toFloat() / d.second, 0.003f)
    }
}
