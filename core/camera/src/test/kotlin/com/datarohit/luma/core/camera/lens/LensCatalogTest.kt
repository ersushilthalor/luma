package com.datarohit.luma.core.camera.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** Values from the Galaxy S26 Ultra HAL static metadata (SM-S948B, S948BXXS6BZK1, 2026-11-18). */
class LensCatalogTest {

    private val ultraWide = lens("2", 2.2f, 5.712f, 4.284f, 4080, 3060, 1.9f, minFocus = 0.05f, ois = false)
    private val main = lens("5", 6.5f, 9.792f, 7.344f, 4080, 3060, 1.4f, minFocus = 0.10f)
    private val tele3x = lens("6", 7.0f, 3.648f, 2.736f, 3648, 2736, 2.4f, minFocus = 0.40f)
    private val tele5x = lens("7", 18.6f, 5.712f, 4.284f, 4080, 3060, 2.9f, minFocus = 0.80f)
    private val catalog = LensCatalog(listOf(tele5x, main, ultraWide, tele3x))

    @Test
    fun identifiesMainLensFromFieldOfView() {
        assertSame(main, catalog.main)
        assertEquals(LensRole.ULTRA_WIDE, catalog.role(ultraWide))
        assertEquals(LensRole.TELEPHOTO, catalog.role(tele5x))
    }

    @Test
    fun computesEquivalentFocalLengthsAndNativeZoom() {
        assertEquals(23.0f, main.equivalentFocalLengthMm, 0.1f)
        assertEquals(0.58f, catalog.nativeZoom(ultraWide), 0.01f)
        assertEquals(2.89f, catalog.nativeZoom(tele3x), 0.01f)
        assertEquals(4.90f, catalog.nativeZoom(tele5x), 0.01f)
    }

    @Test
    fun presetsNearNativeFactorsSnapToOpticalLenses() {
        val wide = catalog.resolve(0.6f, outputMegapixels = 12.0)
        assertSame(ultraWide, wide.lens)
        assertEquals(SourceKind.OPTICAL, wide.kind)

        val three = catalog.resolve(3f, outputMegapixels = 10.0)
        assertSame(tele3x, three.lens)
        assertEquals(SourceKind.OPTICAL, three.kind)
        assertEquals(1f, three.crop, 0f)

        assertEquals(SourceKind.OPTICAL, catalog.resolve(5f, outputMegapixels = 12.0).kind)
    }

    @Test
    fun cropsWithoutMaximumResolutionReadoutAreDigital() {
        val two = catalog.resolve(2f, outputMegapixels = 12.0)
        assertSame(main, two.lens)
        assertEquals(SourceKind.DIGITAL, two.kind)
        assertFalse(two.usesMaximumResolution)
        assertEquals(3.1, two.capturedMegapixels, 0.1)

        val ten = catalog.resolve(10f, outputMegapixels = 12.0)
        assertSame(tele5x, ten.lens)
        assertEquals(SourceKind.DIGITAL, ten.kind)
        assertEquals(3.0, ten.capturedMegapixels, 0.1)
    }

    @Test
    fun subjectsInsideMinimumFocusFallBackToALensThatCanFocus() {
        val closeThree = catalog.resolve(3f, outputMegapixels = 10.0, subjectDistanceM = 0.3f)
        assertSame(main, closeThree.lens)
        assertEquals(SourceKind.DIGITAL, closeThree.kind)
        assertEquals(1.39, closeThree.capturedMegapixels, 0.02)

        val closeFive = catalog.resolve(5f, outputMegapixels = 12.0, subjectDistanceM = 0.5f)
        assertSame(tele3x, closeFive.lens)
    }

    @Test
    fun maximumResolutionReadoutTurnsCropsIntoSensorCrops() {
        val hiResMain = main.copy(maxResolutionRawWidth = 16320, maxResolutionRawHeight = 12240)
        val hiResCatalog = LensCatalog(listOf(ultraWide, hiResMain, tele3x, tele5x))
        val two = hiResCatalog.resolve(2f, outputMegapixels = 12.0)
        assertEquals(SourceKind.SENSOR_CROP, two.kind)
        assertEquals(true, two.usesMaximumResolution)
    }

    private fun lens(
        id: String,
        focal: Float,
        widthMm: Float,
        heightMm: Float,
        widthPx: Int,
        heightPx: Int,
        aperture: Float,
        minFocus: Float,
        ois: Boolean = true,
    ) = LensSpec(
        cameraId = "0",
        physicalId = id,
        focalLengthMm = focal,
        sensorWidthMm = widthMm,
        sensorHeightMm = heightMm,
        pixelArrayWidth = widthPx,
        pixelArrayHeight = heightPx,
        activeWidth = widthPx,
        activeHeight = heightPx,
        aperture = aperture,
        minimumFocusDistanceM = minFocus,
        hasOis = ois,
        rawWidth = widthPx,
        rawHeight = heightPx,
    )
}
