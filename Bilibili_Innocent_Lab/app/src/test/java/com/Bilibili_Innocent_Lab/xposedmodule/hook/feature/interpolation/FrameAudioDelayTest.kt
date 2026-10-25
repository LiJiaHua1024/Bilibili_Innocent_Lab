package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameAudioDelay.Plan
import org.junit.Assert.*
import org.junit.Test

class FrameAudioDelayTest {
    // 48 kHz 双声道 16 bit：每帧 4 字节，每毫秒 192 字节。
    private fun delay() = FrameAudioDelay(48_000, 2, 2)

    @Test fun `silence is inserted once before host data and is frame aligned`() {
        val delay = delay()
        delay.setTargetDelayUs(66_667L)
        val plan = delay.onWrite(4_096)
        assertEquals(0, plan.dropBytes)
        assertEquals(0, plan.silenceBytes % 4)
        assertEquals(12_800, plan.silenceBytes)
        assertEquals(Plan(0, 0), delay.onWrite(4_096))
        assertEquals(66_666L, delay.appliedDelayUs())
    }

    @Test fun `lowering the target drops host bytes across writes`() {
        val delay = delay()
        delay.setTargetDelayUs(50_000L)
        delay.onWrite(1_024)
        delay.setTargetDelayUs(0L)
        assertEquals(Plan(0, 4_096), delay.onWrite(4_096))
        assertEquals(Plan(0, 4_096), delay.onWrite(4_096))
        assertEquals(Plan(0, 1_408), delay.onWrite(4_096))
        assertEquals(Plan(0, 0), delay.onWrite(4_096))
        assertEquals(0L, delay.appliedDelayUs())
    }

    @Test fun `flush discards applied silence so it is inserted again`() {
        val delay = delay()
        delay.setTargetDelayUs(10_000L)
        assertEquals(1_920, delay.onWrite(100).silenceBytes)
        delay.onFlush()
        assertEquals(1_920, delay.onWrite(100).silenceBytes)
    }

    @Test fun `invalid configuration and empty writes are rejected or ignored`() {
        assertThrows(IllegalArgumentException::class.java) { FrameAudioDelay(1_000, 2, 2) }
        val delay = delay()
        assertThrows(IllegalArgumentException::class.java) { delay.setTargetDelayUs(-1L) }
        delay.setTargetDelayUs(20_000L)
        assertEquals(Plan(0, 0), delay.onWrite(0))
    }
}
