package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Decision
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Epoch
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Frame
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Mode
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Reason
import org.junit.Assert.*
import org.junit.Test

/** 深流水线模式：呈现延迟、规划提前量、相位锁定与原帧容差。 */
class FrameTimelineDelayTest {
    private val epoch = Epoch(1L, 1L, 1L, 1L, 1L)
    private val ms = 1_000_000L
    private fun clock(mediaUs: Long, monotonicNs: Long, uncertaintyUs: Long = 0L) =
        ClockSnapshot(mediaUs, monotonicNs, 1.0, false, 1L, uncertaintyUs)
    private fun FrameTimeline.ready(lease: FrameTimeline.Lease, now: Long) =
        (plan(lease, now) as? Decision.Ready)?.proposal ?: fail("expected proposal, got ${plan(lease, now)}") as Nothing

    @Test fun `delay shows media from the past so the future frame is already captured`() {
        val timeline = FrameTimeline(60, presentationDelayNs = 50 * ms, maxLeadNs = 34 * ms)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(100_000L, 0L))
        // 主时钟 100 ms 时已到达 66.7 ms 与 100 ms 的原帧；延迟 50 ms 后槽位 0 显示 50 ms。
        listOf(33_333L, 66_667L, 100_000L).forEachIndexed { i, pts -> timeline.offer(lease, Frame(i.toLong(), pts)) }
        val first = timeline.ready(lease, 0L)
        assertEquals(50_000L, first.mediaUs)
        assertEquals(Mode.INTERPOLATE, first.mode)
        assertEquals(0L, first.before.id)
        assertEquals(1L, first.after.id)
        assertEquals(50_000L, timeline.effectiveDelayUs())
    }

    @Test fun `lead allows planning a slot more than one period ahead`() {
        val oneSlot = FrameTimeline(60)
        val deep = FrameTimeline(60, presentationDelayNs = 50 * ms, maxLeadNs = 34 * ms)
        for (timeline in listOf(oneSlot, deep)) {
            val lease = timeline.begin(epoch).lease
            timeline.updateClock(lease, clock(100_000L, 0L))
            listOf(0L, 50_000L, 100_000L, 150_000L).forEachIndexed { i, pts -> timeline.offer(lease, Frame(i.toLong(), pts)) }
            assertTrue(timeline.commit(timeline.ready(lease, 0L), 0L))
        }
        // 槽位 1、2 分别在 16.7 ms、33.3 ms；此刻（1 ms）请求的是槽位 1，提前 15.7 ms。
        // 用 commit 推进到槽位 2 后在 1 ms 规划：普通模式超出一个周期，深流水线允许。
        val oneLease = oneSlot.begin(epoch).lease
        oneSlot.updateClock(oneLease, clock(100_000L, 0L))
        listOf(0L, 50_000L, 100_000L, 150_000L).forEachIndexed { i, pts -> oneSlot.offer(oneLease, Frame(10L + i, pts)) }
        val slot0 = oneSlot.ready(oneLease, 0L)
        assertTrue(oneSlot.commit(slot0, 0L))
        val slot1 = oneSlot.ready(oneLease, 1L)
        assertTrue(oneSlot.commit(slot1, 1L))
        assertEquals(Reason.AHEAD_OF_WINDOW, (oneSlot.plan(oneLease, 2L) as Decision.Waiting).reason)

        val deepLease = deep.begin(epoch).lease
        deep.updateClock(deepLease, clock(100_000L, 0L))
        listOf(0L, 50_000L, 100_000L, 150_000L).forEachIndexed { i, pts -> deep.offer(deepLease, Frame(20L + i, pts)) }
        assertTrue(deep.commit(deep.ready(deepLease, 0L), 0L))
        assertTrue(deep.commit(deep.ready(deepLease, 1L), 1L))
        assertEquals(33_333_333L, deep.ready(deepLease, 2L).dueNs)
    }

    @Test fun `minimum lead skips ahead to a slot the worker can still meet`() {
        val timeline = FrameTimeline(60, presentationDelayNs = 80 * ms, maxLeadNs = 48 * ms, minLeadNs = 32 * ms)
        val lease = timeline.begin(epoch).lease
        timeline.updateClock(lease, clock(200_000L, 0L))
        listOf(100_000L, 133_333L, 166_667L, 200_000L).forEachIndexed { i, pts -> timeline.offer(lease, Frame(i.toLong(), pts)) }
        val first = timeline.ready(lease, 0L)
        // 32 ms 之后的第一个槽位是第 2 槽（33.3 ms），而不是第 0 槽。
        assertEquals(33_333_333L, first.dueNs)
        assertTrue(first.dueNs - 0L >= 32 * ms)
        assertTrue(timeline.commit(first, 1L))
        // 已经领先时下一槽紧随其后，不再额外跳过。
        assertEquals(Reason.AHEAD_OF_WINDOW, (timeline.plan(lease, 2L) as Decision.Waiting).reason)
        assertEquals(50_000_000L, timeline.ready(lease, 3 * ms).dueNs)
        assertThrows(IllegalArgumentException::class.java) { FrameTimeline(60, maxLeadNs = 20 * ms, minLeadNs = 30 * ms) }
    }

    @Test fun `phase lock aligns 30 to 60 so every other slot is an original frame`() {
        val timeline = FrameTimeline(60, presentationDelayNs = 50 * ms, maxLeadNs = 20 * ms,
            originalToleranceUs = 500L, phaseLock = true)
        val lease = timeline.begin(epoch).lease
        // 时钟相位故意与源帧错开 7 ms。
        timeline.updateClock(lease, clock(107_000L, 0L))
        val sourcePts = (0..8).map { it * 33_333L + it / 3 }
        sourcePts.forEachIndexed { i, pts -> if (pts <= 120_000L) timeline.offer(lease, Frame(i.toLong(), pts)) }
        val modes = ArrayList<Mode>()
        for (slot in 0 until 4) {
            val now = slot * 1_000_000_000L / 60
            if (slot > 0) timeline.updateClock(lease, clock(107_000L + now / 1_000, now))
            sourcePts.forEachIndexed { i, pts -> if (pts <= 107_000L + now / 1_000 + 20_000L) timeline.offer(lease, Frame(i.toLong(), pts)) }
            val proposal = timeline.ready(lease, now)
            modes += proposal.mode
            if (proposal.mode == Mode.INTERPOLATE) assertEquals(0.5, proposal.alpha, 0.03)
            assertTrue(timeline.commit(proposal, now))
        }
        assertEquals(listOf(Mode.ORIGINAL, Mode.INTERPOLATE, Mode.ORIGINAL, Mode.INTERPOLATE), modes)
        val delay = timeline.effectiveDelayUs()
        assertTrue("delay $delay", delay in 50_000L until 50_000L + 33_334L)
    }

    @Test fun `phase tracking keeps original slots aligned while the clock estimate drifts`() {
        val timeline = FrameTimeline(60, presentationDelayNs = 50 * ms, maxLeadNs = 20 * ms, phaseLock = true)
        val lease = timeline.begin(epoch).lease
        val sourcePts = (0..40).map { it * 33_333L }
        val modes = ArrayList<Mode>()
        for (slot in 0 until 24) {
            val now = slot * 1_000_000_000L / 60
            // 时钟估计每槽漂移 +0.4 ms，24 槽累计约 9.6 ms，远超一次性锁相的容差。
            timeline.updateClock(lease, clock(107_000L + now / 1_000 + slot * 400L, now, 2_000L))
            sourcePts.forEachIndexed { i, pts -> if (pts <= 107_000L + now / 1_000 + slot * 400L + 20_000L) timeline.offer(lease, Frame(i.toLong(), pts)) }
            val proposal = timeline.ready(lease, now)
            modes += proposal.mode
            assertTrue(timeline.commit(proposal, now))
        }
        assertEquals(12, modes.count { it == Mode.ORIGINAL })
        assertTrue(modes.zipWithNext().all { (a, b) -> a != b })
    }

    @Test fun `25 to 50 alternates originals and midpoint interpolations`() {
        val timeline = FrameTimeline(50, presentationDelayNs = 50 * ms, maxLeadNs = 20 * ms, phaseLock = true)
        val lease = timeline.begin(epoch).lease
        val modes = ArrayList<Mode>()
        for (slot in 0 until 10) {
            val now = slot * 20 * ms
            timeline.updateClock(lease, clock(107_000L + now / 1_000, now))
            (0..20).forEach { i -> val pts = i * 40_000L; if (pts <= 107_000L + now / 1_000 + 20_000L) timeline.offer(lease, Frame(i.toLong(), pts)) }
            val proposal = timeline.ready(lease, now)
            if (proposal.mode == Mode.INTERPOLATE) assertEquals(0.5, proposal.alpha, 0.03)
            modes += proposal.mode
            assertTrue(timeline.commit(proposal, now))
        }
        assertEquals(5, modes.count { it == Mode.ORIGINAL })
        assertTrue(modes.zipWithNext().all { (a, b) -> a != b })
    }

    @Test fun `tolerance snaps near original slots and zero tolerance keeps exact matching`() {
        for ((tolerance, expected) in listOf(0L to Mode.INTERPOLATE, 500L to Mode.ORIGINAL)) {
            val timeline = FrameTimeline(60, originalToleranceUs = tolerance)
            val lease = timeline.begin(epoch).lease
            timeline.updateClock(lease, clock(300L, 0L))
            timeline.offer(lease, Frame(1L, 0L))
            timeline.offer(lease, Frame(2L, 33_333L))
            assertEquals(expected, timeline.ready(lease, 0L).mode)
        }
    }

    @Test fun `configurable uncertainty is bounded and phase lock resets per epoch`() {
        assertThrows(IllegalArgumentException::class.java) { FrameTimeline(60, maxClockUncertaintyUs = 9_000L) }
        val timeline = FrameTimeline(60, maxClockUncertaintyUs = 4_000L, presentationDelayNs = 40 * ms, phaseLock = true)
        val lease = timeline.begin(epoch).lease
        assertEquals(FrameTimeline.ClockState.READY, timeline.updateClock(lease, clock(100_000L, 0L, 3_000L)).state)
        timeline.offer(lease, Frame(1L, 50_000L))
        timeline.offer(lease, Frame(2L, 83_333L))
        timeline.ready(lease, 0L)
        assertNotEquals(40_000L, timeline.effectiveDelayUs())
        timeline.begin(epoch)
        assertEquals(40_000L, timeline.effectiveDelayUs())
    }
}
