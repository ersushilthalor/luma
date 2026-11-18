package com.datarohit.luma.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePlannerTest {

    private fun input(mode: CaptureMode, exposureMs: Double, iso: Int, clipped: Float = 0f, motion: Float = 0f) = PlanInput(
        mode = mode,
        previewExposureNs = (exposureMs * 1e6).toLong(),
        previewIso = iso,
        isoMin = 50,
        isoMax = 3200,
        exposureMinNs = 10_000L,
        exposureMaxNs = 1_000_000_000L,
        clippedFraction = clipped,
        motion = motion,
    )

    @Test fun brightLowContrastSceneUsesSingleFrame() {
        val p = CapturePlanner.plan(input(CaptureMode.PHOTO, 2.0, 50))
        assertEquals(1, p.frames)
        assertEquals(false, p.merge)
        assertEquals(0f, p.processingEv)
        assertEquals(2_000_000L, p.exposureNs)
    }

    @Test fun clippedHighlightsAreProtected() {
        val p = CapturePlanner.plan(input(CaptureMode.PHOTO, 8.0, 100, clipped = 0.05f))
        assertEquals(1f, p.processingEv)
        assertEquals(4, p.frames)
        assertEquals(4_000_000L, p.exposureNs)
    }

    @Test fun dimSceneUsesMoreFrames() {
        assertEquals(10, CapturePlanner.plan(input(CaptureMode.PHOTO, 40.0, 1900)).frames)
    }

    @Test
    fun superResolutionBurstLength() {
        val p24 = CapturePlanner.plan(input(CaptureMode.PHOTO, 2.0, 50).copy(superResolution = 1.39f))
        assertEquals(CapturePlanner.SR_FRAMES_24, p24.frames)
        assertTrue(p24.merge)
        val p50 = CapturePlanner.plan(input(CaptureMode.PHOTO, 2.0, 50).copy(superResolution = 2f))
        assertEquals(CapturePlanner.SR_FRAMES_50, p50.frames)
        assertTrue(p50.reason, p50.reason.startsWith("14-frame") && p50.reason.contains("2.0x super-resolution"))
        // Never fewer frames than the mode itself plans.
        val night = CapturePlanner.plan(input(CaptureMode.NIGHT, 40.0, 3200))
        val nightSr = CapturePlanner.plan(input(CaptureMode.NIGHT, 40.0, 3200).copy(superResolution = 2f))
        assertEquals(maxOf(night.frames, CapturePlanner.SR_FRAMES_50), nightSr.frames)
    }

    @Test fun motionShortensFrames() {
        val p = CapturePlanner.plan(input(CaptureMode.PHOTO, 20.0, 400, motion = 0.2f))
        assertEquals(10_000_000L, p.exposureNs)
        assertEquals(800, p.iso)
        assertEquals(4, p.frames)
    }

    @Test fun nightUsesLongerLowerIsoFrames() {
        val p = CapturePlanner.plan(input(CaptureMode.NIGHT, 40.0, 3200))
        assertTrue(p.exposureNs in 100_000_000L..125_000_000L)
        assertTrue(p.iso in 900..1300)
        assertTrue(p.frames >= 10)
        // Total light gathered exceeds a single preview frame by a large factor.
        assertTrue(p.frames * p.exposureNs * p.iso > 8.0 * 40e6 * 3200)
    }

    @Test fun fitMovesExcessIntoExposure() {
        val i = input(CaptureMode.PRO, 10.0, 100)
        val (e, s) = CapturePlanner.fit(10_000_000L, 6400, i)
        assertEquals(3200, s)
        assertEquals(20_000_000L, e)
    }

    @Test fun proUsesManualValues() {
        val p = CapturePlanner.plan(input(CaptureMode.PRO, 10.0, 100).copy(manualExposureNs = 4_000_000L, manualIso = 200))
        assertEquals(1, p.frames)
        assertEquals(4_000_000L, p.exposureNs)
        assertEquals(200, p.iso)
    }
}
