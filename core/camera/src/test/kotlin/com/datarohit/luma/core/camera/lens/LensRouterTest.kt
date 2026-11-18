package com.datarohit.luma.core.camera.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses the values measured on the Galaxy S26 Ultra (SM-S948B, 2026-11-18). */
class LensRouterTest {

    private val router = LensRouter(
        logicalCameraId = "0",
        logicalZoomRange = 0.6f..10f,
        ultraWideCameraId = "2",
        ultraWideNativeZoom = 0.58f,
        ultraWideZoomRange = 1f..8f,
        firstTelephotoZoom = 2.89f,
        logicalHasRaw = true,
    )

    @Test fun ultraWideUsesStandaloneCameraRaw() {
        val r = router.route(0.6f)
        assertEquals(CaptureRoute.ULTRA_WIDE_RAW, r.route)
        assertEquals("2", r.cameraId)
        assertEquals(1.034f, r.cameraZoomRatio, 0.01f)
        assertTrue(r.usesRaw)
    }

    @Test fun minimumZoomIsTheUltraWideNativeField() {
        assertEquals(0.58f, router.minimumZoom, 1e-4f)
        assertEquals(1f, router.route(0.58f).cameraZoomRatio, 1e-4f)
    }

    @Test fun oneAndTwoTimesUseMainRaw() {
        assertEquals(CaptureRoute.MAIN_RAW, router.route(1f).route)
        assertEquals(CaptureRoute.MAIN_RAW, router.route(2f).route)
        assertEquals(2f, router.route(2f).cameraZoomRatio, 1e-4f)
    }

    @Test fun telephotoPresetsUseHalProcessing() {
        for (z in listOf(3f, 5f, 10f)) {
            val r = router.route(z)
            assertEquals(CaptureRoute.HAL_PROCESSED, r.route)
            assertEquals("0", r.cameraId)
            assertFalse(r.usesRaw)
        }
        // 2.89x preset itself and the 5 % snap band below it.
        assertEquals(CaptureRoute.HAL_PROCESSED, router.route(2.8f).route)
        assertEquals(CaptureRoute.MAIN_RAW, router.route(2.7f).route)
    }

    @Test fun zoomIsClamped() {
        assertEquals(10f, router.route(30f).zoom, 1e-4f)
        assertEquals(0.58f, router.route(0.1f).zoom, 1e-4f)
    }

    @Test fun reconfigureOnlyAcrossRoutes() {
        assertFalse(router.needsReconfigure(1f, 2.5f))
        assertTrue(router.needsReconfigure(0.9f, 1.2f))
        assertTrue(router.needsReconfigure(2.5f, 3f))
        assertFalse(router.needsReconfigure(3f, 10f))
    }

    @Test fun withoutStandaloneUltraWideTheLogicalCameraServesWideZoom() {
        val r = LensRouter("0", 0.6f..10f, null, 0.58f, 1f..1f, 2.89f, true)
        assertEquals(CaptureRoute.MAIN_RAW, r.route(1f).route)
        // Below 1x the logical camera would stay on the main sensor with RAW, so it must be HAL-processed.
        assertEquals(CaptureRoute.HAL_PROCESSED, r.route(0.6f).route)
    }
}
