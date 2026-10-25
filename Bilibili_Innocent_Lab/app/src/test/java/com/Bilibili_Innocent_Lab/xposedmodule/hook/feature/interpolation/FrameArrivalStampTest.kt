package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameArrivalStampTest {
    private val period = 33_333_333L

    @Test fun `a busy capture thread does not delay arrivals of a steady cadence`() {
        val stamp = FrameArrivalStamp()
        // 捕获线程落后一个帧间隔：锁存时刻 = 通知 + 33 ms。首两帧没有可比间隔，用锁存时刻。
        val arrivals = (0 until 6).map { i -> stamp.arrival(i * period, 1_000L + i * period, 1_000L + (i + 1) * period) }
        assertEquals(1_000L + period, arrivals[0])
        assertEquals(1_000L + 2 * period, arrivals[1])
        for (i in 2 until 6) assertEquals(1_000L + i * period, arrivals[i])
    }

    @Test fun `a replaced buffer falls back to the latch time until the cadence is consistent again`() {
        val stamp = FrameArrivalStamp()
        for (i in 0 until 3) stamp.arrival(i * period, i * period, i * period + 1_000L)
        // 第 3 帧被顶替：锁存到第 4 帧，通知时刻属于更早的帧，不能使用。
        assertEquals(4 * period + 9_000L, stamp.arrival(4 * period, 3 * period, 4 * period + 9_000L))
        assertEquals(5 * period + 9_000L, stamp.arrival(5 * period, 5 * period, 5 * period + 9_000L))
        assertEquals(6 * period, stamp.arrival(6 * period, 6 * period, 6 * period + 9_000L))
    }

    @Test fun `a notification after the latch is never used and a seek back resets the cadence`() {
        val stamp = FrameArrivalStamp()
        for (i in 0 until 3) stamp.arrival(i * period, i * period, i * period)
        assertEquals(3 * period, stamp.arrival(3 * period, 3 * period + 5L, 3 * period))
        assertEquals(10L, stamp.arrival(0L, 0L, 10L))
    }
}
