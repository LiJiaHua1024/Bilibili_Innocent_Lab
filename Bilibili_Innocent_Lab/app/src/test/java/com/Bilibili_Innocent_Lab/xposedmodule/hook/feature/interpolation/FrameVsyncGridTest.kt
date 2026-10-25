package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import org.junit.Assert.*
import org.junit.Test

class FrameVsyncGridTest {
    private val display120 = 8_333_333L
    private val content90 = 11_111_111L

    private fun FrameVsyncGrid.feed(from: Long, count: Int, period: Long, skip: (Int) -> Boolean = { false }) {
        for (i in 0 until count) if (!skip(i)) onVsync(from + i * period)
    }

    @Test fun `display period converges to the panel and ignores skipped callbacks`() {
        val grid = FrameVsyncGrid(content90)
        // 每 5 个回调漏 1 个：整倍数间隔不能拉长估计。
        grid.feed(1_000_000_000L, 120, display120) { it % 5 == 4 }
        assertEquals(display120.toDouble(), grid.periodNs.toDouble(), 50_000.0)
    }

    @Test fun `a panel idling below the target is followed at its real period`() {
        val grid = FrameVsyncGrid(content90)
        val period60 = 16_666_666L
        grid.feed(1L, 40, period60)
        assertEquals(period60, grid.periodNs)
        assertTrue(grid.slowerThanContent())
        // 面板随后升到 120 Hz：约 10 个短间隔（不到 0.1 s）后生效。
        grid.feed(grid.lastNs + display120, 10, display120)
        assertEquals(display120, grid.periodNs)
        assertFalse(grid.slowerThanContent())
    }

    @Test fun `a skipped callback still advances the grid one refresh at a time`() {
        val grid = FrameVsyncGrid(content90)
        grid.feed(0L + 1, 60, display120)
        val last = grid.lastNs
        // 驱动线程漏掉下一个回调：逾期 2 ms 后外推，仍落在真实 vsync 上；逾期前沿用最近一次回调。
        assertEquals(last + grid.periodNs, grid.current(last + display120 + 2_500_000L))
        assertEquals(last, grid.current(last + display120 + 1_000_000L))
        assertEquals(last, grid.current(last + 1_000_000L))
    }

    /**
     * 30→90 fps 在 120 Hz 面板上，驱动回调频繁漏掉：每个刷新至多放行一帧，按 vsync 逐个交付不作废任何一帧
     * （只看最近回调时，漏掉一个回调会让两帧同时可提交、前一帧被作废）。
     */
    @Test fun `90 fps content on a 120 Hz panel never releases two frames in one refresh`() {
        val grid = FrameVsyncGrid(content90)
        val start = 1_000_000_000L
        grid.feed(start, 30, display120)
        val base = grid.lastNs
        val dues = ArrayDeque((0 until 200).map { base + 30_000_000L + it * content90 })
        var dropped = 0
        var released = 0
        for (k in 1..300) {
            val vsync = base + k * display120
            // 驱动线程忙：约三分之一的 vsync 没有回调；呈现线程在回调后 0.5 ms，或逾期外推时刻之后复查。
            val delivered = k % 3 != 0
            if (delivered) grid.onVsync(vsync)
            val now = vsync + if (delivered) 500_000L else 2_500_000L
            val head = dues.firstOrNull() ?: break
            if (!grid.releasable(head, now)) continue
            dues.removeFirst()
            val next = dues.firstOrNull()
            if (next != null && grid.releasable(next, now)) dropped++ else released++
        }
        assertEquals(0, dropped)
        assertTrue("released $released", released > 150)
    }

    @Test fun `60 fps content on a 60 Hz panel keeps the previous release window`() {
        val period = 16_666_666L
        val grid = FrameVsyncGrid(period)
        grid.feed(1L, 30, period)
        val vsync = grid.lastNs
        val now = vsync + 2_000_000L
        assertTrue(grid.releasable(vsync + period + period / 2, now))
        assertFalse(grid.releasable(vsync + period + period / 2 + 1_000L, now))
    }

    /** 120 Hz 面板播 60 fps：迟交的本帧与下一帧都可提交时，下一帧属于后一次刷新，本帧不应作废。 */
    @Test fun `a faster panel leaves a spare refresh before the next frame`() {
        val content = 16_666_666L
        val grid = FrameVsyncGrid(content)
        grid.feed(1L, 40, display120)
        val v = grid.lastNs
        val now = v + 500_000L
        val head = v + 2_000_000L
        val next = head + content
        assertTrue(grid.releasable(next, now))
        assertFalse(grid.sameRefresh(next, now))
        // 60 Hz 面板：同样的两帧落在同一次刷新，前一帧让位。
        val grid60 = FrameVsyncGrid(content)
        grid60.feed(1L, 40, content)
        val v60 = grid60.lastNs
        assertTrue(grid60.sameRefresh(v60 + 2_000_000L + content, v60 + 500_000L))
    }

    /** Choreographer 抖动校正偶尔给出偏短的间隔：估计不被拉短，外推的 vsync 不早于真实 vsync。 */
    @Test fun `occasional short intervals do not shorten the period`() {
        val grid = FrameVsyncGrid(16_666_666L)
        var t = 1L
        for (i in 0 until 64) { t += if (i % 10 == 5) 12_000_000L else if (i % 10 == 6) 21_333_332L else 16_666_666L; grid.onVsync(t) }
        assertEquals(16_666_666.0, grid.periodNs.toDouble(), 1_000.0)
    }

    @Test fun `stops following when callbacks stop`() {
        val grid = FrameVsyncGrid(content90)
        assertTrue(grid.stale(0L))
        grid.onVsync(1_000L)
        assertFalse(grid.stale(1_000L + 3 * content90))
        assertTrue(grid.stale(1_000L + 4 * content90))
        assertFalse("time not advancing is ignored", grid.onVsync(1_000L))
    }
}
