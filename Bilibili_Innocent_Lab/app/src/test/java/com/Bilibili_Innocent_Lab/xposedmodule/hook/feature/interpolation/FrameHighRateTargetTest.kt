package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FrameHighRateTargetTest {
    @Test fun `60 fps source retains the source frame needed for delayed 120 fps startup`() {
        fun timeline(window: Int): FrameTimeline.Decision {
            val t = FrameTimeline(120, presentationDelayNs = 100_000_000L, frameWindow = window, phaseLock = true)
            val epoch = FrameTimeline.Epoch(1, 1, 1, 1, 1)
            val lease = t.begin(epoch).lease
            repeat(16) { t.offer(lease, FrameTimeline.Frame(it.toLong(), it * 16_667L)) }
            t.updateClock(lease, FrameTimeline.ClockSnapshot(250_000L, 1_000_000_000L, 1.0, false, 1))
            return t.plan(lease, 1_000_000_000L)
        }
        assertTrue(timeline(4) is FrameTimeline.Decision.Waiting)
        assertTrue(timeline(8) is FrameTimeline.Decision.Ready)
    }
    @Test fun `explicit 120 target is retained for integer source divisors`() {
        for (target in listOf(15, 30, 45, 50, 60, 120, 144, 165, 185))
            assertEquals(target, FrameCaptureBridge.targetFpsFor(30f, target))
        for (source in listOf(24f, 30f, 60f)) {
            assertEquals(120, FrameCaptureBridge.targetFpsFor(source, 120))
            val cadence = FrameSourceCadence(120)
            repeat(20) { cadence.add((it * 1_000_000.0 / source).toLong()) }
            assertEquals(FrameSourceCadence.Verdict.SUITABLE, cadence.verdict())
        }
        assertEquals(50, FrameCaptureBridge.targetFpsFor(25f))
        assertEquals(60, FrameCaptureBridge.targetFpsFor(30f))
    }
}
