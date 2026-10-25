package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameSourceCadence.Verdict
import org.junit.Assert.*
import org.junit.Test

class FrameSourceCadenceTest {
    private fun verdictFor(fps: Double, frames: Int = 20): Verdict {
        val cadence = FrameSourceCadence()
        for (i in 0 until frames) cadence.add((i * 1_000_000.0 / fps).toLong())
        return cadence.verdict()
    }

    @Test fun `integer divisors of 60 including 29_97 are suitable`() {
        for (fps in listOf(30.0, 29.97, 20.0, 15.0, 60.0)) assertEquals("$fps", Verdict.SUITABLE, verdictFor(fps))
    }

    @Test fun `playback requires at least one synthetic frame per source frame`() {
        fun verdict(fps: Double, target: Int) = FrameSourceCadence(target, interpolationRequired = true).also { c ->
            for (i in 0 until 20) c.add((i * 1_000_000.0 / fps).toLong())
        }.verdict()
        assertEquals(Verdict.UNSUITABLE, verdict(60.0, 60))
        assertEquals(Verdict.UNSUITABLE, verdict(59.94, 60))
        assertEquals(Verdict.UNSUITABLE, verdict(50.0, 50))
        assertEquals(Verdict.SUITABLE, verdict(30.0, 60))
        assertEquals(Verdict.SUITABLE, verdict(25.0, 50))
    }

    @Test fun `24 and 25 fps cannot be phase aligned to 60 and are unsuitable`() {
        assertEquals(Verdict.UNSUITABLE, verdictFor(24.0))
        assertEquals(Verdict.UNSUITABLE, verdictFor(25.0))
        assertEquals(Verdict.UNSUITABLE, verdictFor(23.976))
    }

    @Test fun `50 fps target accepts 25 fps and rejects 30 fps sources`() {
        fun verdict(fps: Double) = FrameSourceCadence(targetFps = 50).also { c ->
            for (i in 0 until 20) c.add((i * 1_000_000.0 / fps).toLong())
        }.verdict()
        assertEquals(Verdict.SUITABLE, verdict(25.0))
        assertEquals(Verdict.UNSUITABLE, verdict(29.97))
        assertEquals(Verdict.UNSUITABLE, verdict(24.0))
    }

    @Test fun `too few frames is pending and dropped source frames do not skew the estimate`() {
        assertEquals(Verdict.PENDING, verdictFor(30.0, frames = 8))
        val cadence = FrameSourceCadence()
        var pts = 0L
        repeat(30) { i -> pts += if (i % 7 == 6) 66_667L else 33_333L; cadence.add(pts) }
        assertEquals(Verdict.SUITABLE, cadence.verdict())
        assertEquals(30.0, cadence.sourceFps()!!, 0.1)
        assertTrue(cadence.describe().contains("gaps=4"))
    }
}
