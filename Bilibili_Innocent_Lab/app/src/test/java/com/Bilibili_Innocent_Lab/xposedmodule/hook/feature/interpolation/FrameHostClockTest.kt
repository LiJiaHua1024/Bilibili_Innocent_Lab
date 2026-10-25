package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameHostClock.Update
import org.junit.Assert.*
import org.junit.Test

class FrameHostClockTest {
    private val periodUs = 33_333L

    /** 第 i 帧 PTS = i·33.333 ms，理想在 base + PTS 到达，另加 jitter(i) 的调度延迟。 */
    private fun FrameHostClock.feedFrames(from: Int, to: Int, baseNs: Long, jitterUs: (Int) -> Long = { 0L }) {
        for (i in from until to) assertEquals(Update.ACCEPTED, feed(i * periodUs, baseNs + (i * periodUs + jitterUs(i)) * 1_000L))
    }

    @Test fun `needs a few samples before reporting a clock`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 11, 0L)
        assertNull(clock.snapshot(500_000_000L, 1L))
        clock.feedFrames(11, 12, 0L)
        assertNotNull(clock.snapshot(500_000_000L, 1L))
    }

    /**
     * 宿主进程的典型到达：普遍迟到 2–12 ms，另有约 10% 迟到 20–30 ms。真实映射已知（base + PTS）。
     * 估计误差应只有调度延迟的下限量级；旧的「最大值 − 中位数」会超过 8 ms 门限，新的误差上界不会。
     */
    @Test fun `heavy tailed lateness keeps an accurate estimate and a bound under the gate`() {
        val random = java.util.Random(7)
        // 尾部到 30 ms：真实解码器按顺序交付，迟到不会超过一个帧间隔而让后一帧先到。
        val lateness = LongArray(400) { if (random.nextInt(10) == 0) 20_000L + random.nextInt(10_000) else 2_000L + random.nextInt(10_000) }
        val base = 1_000_000_000L
        var worstError = 0L
        var worstBound = 0L
        for (i in 40 until 400) {
            val fresh = FrameHostClock()
            fresh.feedFrames(i - 32, i, base) { lateness[it] }
            val now = base + i * periodUs * 1_000L
            val snapshot = fresh.snapshot(now, 1L)!!
            // 误差恰为窗口内最小迟到量（调度延迟下限），而非典型迟到量。
            val floor = (i - 32 until i).minOf { lateness[it] }
            assertTrue("estimate error at $i", kotlin.math.abs(snapshot.mediaUs - i * periodUs) <= floor + 100L)
            worstError = maxOf(worstError, kotlin.math.abs(snapshot.mediaUs - i * periodUs))
            worstBound = maxOf(worstBound, snapshot.uncertaintyUs)
        }
        assertTrue("estimate error $worstError", worstError <= 4_000L)
        assertTrue("bound $worstBound must stay under the 8 ms gate", worstBound <= 8_000L)
    }

    @Test fun `a start up burst publishes nothing until the steady cadence has enough samples`() {
        val clock = FrameHostClock()
        // 开播突发：前 10 帧每 5 ms 释放一帧（相对 PTS 节奏越来越早），之后按 33.3 ms 节奏、带 2–6 ms 调度延迟。
        val base = 2_000_000_000L
        var arrival = base
        for (i in 0 until 10) { assertNotEquals(Update.REJECTED, clock.feed(i * periodUs, arrival)); arrival += 5_000_000L }
        // 突发后的稳态：映射 = 第 10 帧到达时刻。
        val steadyBase = arrival - 10 * periodUs * 1_000L
        for (i in 10 until 21) {
            assertNotEquals(Update.REJECTED, clock.feed(i * periodUs, steadyBase + (i * periodUs + 2_000L + (i % 3) * 2_000L) * 1_000L))
            assertNull("no snapshot from burst samples (frame $i)", clock.snapshot(steadyBase + (i * periodUs + 8_000L) * 1_000L, 1L))
        }
        clock.feed(21 * periodUs, steadyBase + (21 * periodUs + 2_000L) * 1_000L)
        val snapshot = clock.snapshot(steadyBase + (22 * periodUs) * 1_000L, 1L)!!
        assertTrue("accurate after the burst: ${snapshot.mediaUs}", kotlin.math.abs(snapshot.mediaUs - 22 * periodUs) <= 2_100L)
        assertTrue("bound under the gate: ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
    }

    @Test fun `an isolated early sample is not trusted until others confirm it`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 20, 0L) { 6_000L }
        clock.feed(20 * periodUs, 20 * periodUs * 1_000L - 4_000_000L)
        // 只有一个比其余早 10 ms 的样本：下包络未被确认，误差上界至少反映这 10 ms。
        assertTrue(clock.snapshot(800_000_000L, 1L)!!.uncertaintyUs >= 9_000L)
    }

    @Test fun `late arrivals do not drag the clock and jitter becomes the uncertainty`() {
        val clock = FrameHostClock()
        // 交替 0 / 3 ms 的调度延迟：最早到达的样本即真实映射。
        clock.feedFrames(0, 32, 5_000_000_000L) { if (it % 2 == 0) 0L else 3_000L }
        val now = 5_000_000_000L + 1_100_000_000L
        val snapshot = clock.snapshot(now, 7L)!!
        assertEquals(1_100_000L, snapshot.mediaUs)
        assertEquals(7L, snapshot.discontinuity)
        assertEquals(1.0, snapshot.speed, 0.0)
        assertTrue(snapshot.uncertaintyUs in 500L..3_000L)
    }

    @Test fun `a better sample moves the estimate gradually and a large offset jumps at once`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 12, 0L) { 3_000L }
        val first = clock.snapshot(500_000_000L, 1L)!!
        // 一帧准时到达：下包络提前 3 ms，但每次快照最多移动 0.2 ms。
        clock.feed(12 * periodUs, 12 * periodUs * 1_000L)
        val second = clock.snapshot(500_000_000L, 1L)!!
        assertEquals(200L, second.mediaUs - first.mediaUs)
        assertTrue(second.uncertaintyUs >= 2_800L)
        // 大偏差经 envelopeRank 个样本确认后一次跳过去。
        val slow = FrameHostClock(jumpUs = 2_000L)
        slow.feedFrames(0, 12, 0L) { 3_000L }
        val before = slow.snapshot(500_000_000L, 1L)!!
        for (i in 12 until 16) slow.feed(i * periodUs, i * periodUs * 1_000L)
        assertEquals(3_000L, slow.snapshot(800_000_000L, 1L)!!.mediaUs - before.mediaUs - 300_000L)
    }

    @Test fun `seek back, jump forward and resume after pause are discontinuities`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 10, 0L)
        assertEquals(Update.DISCONTINUITY, clock.feed(2 * periodUs, 400_000_000L))
        clock.feedFrames(3, 10, 400_000_000L - 2 * periodUs * 1_000L)
        // 同一 PTS 序列继续，但墙钟停了 2 s（暂停后恢复）。
        assertEquals(Update.DISCONTINUITY, clock.feed(10 * periodUs, 400_000_000L + 10 * periodUs * 1_000L + 2_000_000_000L))
        assertNull(clock.snapshot(3_000_000_000L, 1L))
    }

    @Test fun `speed change restarts and the clock advances at the given speed`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 8, 0L)
        val doubled = (8 until 20).map { clock.feed(it * periodUs, 8 * periodUs * 1_000L + (it - 8) * periodUs * 500L, 2.0) }
        assertEquals(Update.DISCONTINUITY, doubled.first())
        assertTrue(doubled.drop(1).all { it == Update.ACCEPTED })
        val lastArrival = 8 * periodUs * 1_000L + 11 * periodUs * 500L
        val snapshot = clock.snapshot(lastArrival + 10_000_000L, 1L)!!
        assertEquals(19 * periodUs + 20_000L, snapshot.mediaUs)
        assertEquals(2.0, snapshot.speed, 0.0)
    }

    /** 宿主真机：GPU 忙时解码输出整体晚一帧并保持；已信任映射不变、误差上界一直在门限内。 */
    @Test fun `a stable one frame later shift keeps the trusted mapping under the gate`() {
        val clock = FrameHostClock()
        val base = 3_000_000_000L
        clock.feedFrames(0, 32, base) { 2_000L }
        val locked = clock.snapshot(base + 32 * periodUs * 1_000L, 1L)!!
        for (i in 32 until 120) {
            clock.feed(i * periodUs, base + (i * periodUs + 35_400L + (i % 3) * 300L) * 1_000L)
            val now = base + (i * periodUs + 40_000L) * 1_000L
            val snapshot = clock.snapshot(now, 1L)!!
            assertTrue("bound at $i: ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
            // 映射不随晚到移动：媒体时间仍按锁定时的映射推进。
            assertEquals("held at $i", (locked.mediaUs + (now - (base + 32 * periodUs * 1_000L)) / 1_000L).toDouble(), snapshot.mediaUs.toDouble(), 1.0)
        }
    }

    @Test fun `initial lock follows the steady regime when only the first frames were early`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 3, 0L)
        clock.feedFrames(3, 12, 0L) { 33_400L + (it % 2) * 200L }
        val snapshot = clock.snapshot((12 * periodUs + 40_000L) * 1_000L, 1L)!!
        assertTrue("bound ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
        assertEquals((12 * periodUs + 40_000L - 33_400L).toDouble(), snapshot.mediaUs.toDouble(), 300.0)
    }

    @Test fun `a later shift beyond the hold limit moves to the new mapping`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 32, 0L)
        clock.snapshot(32 * periodUs * 1_000L, 1L)
        clock.feedFrames(32, 72, 0L) { 120_000L }
        val now = (72 * periodUs + 130_000L) * 1_000L
        assertEquals(72 * periodUs + 130_000L - 120_000L, clock.snapshot(now, 1L)!!.mediaUs)
    }

    /** 宿主锯齿：逐步晚 33、67 ms，再丢帧追回原映射；整个过程映射保持、误差上界不越线。 */
    @Test fun `a lateness sawtooth up to two frames is held without rejection`() {
        val clock = FrameHostClock()
        val base = 4_000_000_000L
        clock.feedFrames(0, 32, base) { 1_000L }
        val locked = clock.snapshot(base + (32 * periodUs + 2_000L) * 1_000L, 1L)!!
        val lateness = { i: Int -> 1_000L + ((i - 32) / 40 % 3) * 33_400L }
        for (i in 32 until 300) {
            clock.feed(i * periodUs, base + (i * periodUs + lateness(i)) * 1_000L)
            val now = base + (i * periodUs + 70_000L) * 1_000L
            val snapshot = clock.snapshot(now, 1L)!!
            assertTrue("bound at $i: ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
            val expected = locked.mediaUs + (now - (base + (32 * periodUs + 2_000L) * 1_000L)) / 1_000L
            assertTrue("held at $i: ${snapshot.mediaUs - expected}", kotlin.math.abs(snapshot.mediaUs - expected) <= 300L)
        }
    }

    /** 宿主真机：保持在比晚档早 30 ms 的映射时，一个样本部分追回（比晚档早 13 ms）不能把误差上界推过门限。 */
    @Test fun `a partly returning sample while holding stays under the gate`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 32, 0L)
        val locked = clock.snapshot((32 * periodUs + 1_000L) * 1_000L, 1L)!!
        clock.feedFrames(32, 64, 0L) { 30_000L + (it % 2) * 200L }
        clock.feed(64 * periodUs, (64 * periodUs + 17_000L) * 1_000L)
        val now = (64 * periodUs + 40_000L) * 1_000L
        val snapshot = clock.snapshot(now, 1L)!!
        assertTrue("bound ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 1_000L)
        assertEquals(locked.mediaUs + (now / 1_000L - (32 * periodUs + 1_000L)), snapshot.mediaUs)
    }

    /**
     * 宿主真机窗口：锁定在晚一档（33 ms）后 ijk 丢帧追回，一个过渡样本（早 22 ms）后三个样本回到原映射。
     * 无论映射锁在哪一档，过渡样本都不能把误差上界推过门限。
     */
    @Test fun `a catch up with one in between sample stays under the gate`() {
        for (lockedLate in listOf(true, false)) {
            val clock = FrameHostClock()
            val late = 33_300L
            clock.feedFrames(0, 32, 0L) { if (lockedLate) late + (it % 3) * 100L else (it % 3) * 100L }
            clock.snapshot((32 * periodUs + 40_000L) * 1_000L, 1L)!!
            if (!lockedLate) clock.feedFrames(32, 60, 0L) { late + (it % 3) * 100L }
            val from = if (lockedLate) 32 else 60
            clock.feed(from * periodUs, (from * periodUs + late - 22_000L) * 1_000L)
            for (i in from + 1 until from + 4) {
                clock.feed(i * periodUs, (i * periodUs + 200L) * 1_000L)
                // 锁在晚档时早 33 ms 触发突发重置：窗口重新积累期间不发布快照（不会被拒）。
                val snapshot = clock.snapshot((i * periodUs + 40_000L) * 1_000L, 1L) ?: continue
                assertTrue("lockedLate=$lockedLate bound at $i: ${snapshot.uncertaintyUs}", snapshot.uncertaintyUs <= 8_000L)
            }
        }
    }

    @Test fun `after lock an unconfirmed early sample is ignored`() {
        val clock = FrameHostClock()
        clock.feedFrames(0, 32, 0L) { 10_000L + (it % 2) * 300L }
        val before = clock.snapshot((32 * periodUs + 12_000L) * 1_000L, 1L)!!
        clock.feed(32 * periodUs, 32 * periodUs * 1_000L)
        val after = clock.snapshot((32 * periodUs + 12_000L) * 1_000L, 1L)!!
        assertEquals(before.mediaUs, after.mediaUs)
        assertTrue("bound ${after.uncertaintyUs}", after.uncertaintyUs <= 1_000L)
    }

    @Test fun `describe lists samples in arrival order relative to the best`() {
        val clock = FrameHostClock()
        assertEquals("[]", clock.describe())
        clock.feedFrames(0, 3, 0L) { if (it == 1) 2_000L else 0L }
        assertEquals("[0.0/0,-2.0/33,0.0/33]", clock.describe())
    }

    @Test fun `invalid input and time going backwards are rejected`() {
        val clock = FrameHostClock()
        assertEquals(Update.REJECTED, clock.feed(-1L, 0L))
        assertEquals(Update.REJECTED, clock.feed(0L, 0L, 0.0))
        clock.feedFrames(0, 5, 1_000_000_000L)
        assertEquals(Update.REJECTED, clock.feed(5 * periodUs, 0L))
        assertNull(clock.snapshot(0L, 1L))
    }
}
