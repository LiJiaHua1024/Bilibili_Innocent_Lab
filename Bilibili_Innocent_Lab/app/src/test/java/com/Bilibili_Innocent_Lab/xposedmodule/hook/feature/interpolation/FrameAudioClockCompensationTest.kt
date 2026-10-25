package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameAudioClockCompensation.Mode
import org.junit.Assert.*
import org.junit.Test

class FrameAudioClockCompensationTest {
    @Test fun `failed timestamp attempts cannot select the timestamp path`() {
        assertEquals(Mode.LATENCY, FrameAudioClockCompensation.choose(0, 0, 3))
        assertEquals(Mode.HEAD, FrameAudioClockCompensation.choose(0, 1, 3))
        assertEquals(Mode.SILENCE, FrameAudioClockCompensation.choose(0, 0, 0))
        assertEquals(Mode.TIMESTAMP, FrameAudioClockCompensation.choose(1, 1, 3))
    }

    @Test fun `latency compensation is removed on native recovery`() {
        assertEquals(-110, FrameAudioClockCompensation.latencyMs(40, 150_000, Mode.LATENCY))
        assertEquals(40, FrameAudioClockCompensation.latencyMs(40, 0, Mode.LATENCY))
    }

    @Test fun `head compensation is not applied twice through latency`() {
        for (mode in listOf(Mode.TIMESTAMP, Mode.HEAD, Mode.SILENCE, Mode.UNDECIDED))
            assertEquals(40, FrameAudioClockCompensation.latencyMs(40, 150_000, mode))
    }
}
