package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameBufferLedger.Holder
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationBackend.Status
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationSession.Outcome
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameInterpolationSession.Phase
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FramePresentationRecovery.State
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.ClockState
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameTimeline.Epoch
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FrameInterpolationSessionTest {
    private val slot1 = 16_666_666L
    private var now = 0L
    private val tasks = ArrayDeque<() -> Unit>()
    private var acceptWork = true

    private class Synth : FrameInterpolationSession.Synthesizer {
        var status = Status.OK
        var calls = 0
        var closed = 0
        override fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
                             format: FrameBufferLedger.Format, alpha: Float, leftId: Long, rightId: Long): Status {
            calls++
            output.put(0, 7)
            return status
        }
        override fun close() { closed++ }
    }

    private class Shown(val id: Long, val dueNs: Long, val buffer: ByteBuffer, val done: (Outcome) -> Unit)
    private class Screen : FrameInterpolationSession.Presenter {
        val shown = ArrayList<Shown>()
        val restores = ArrayList<FramePresentationRecovery.Report>()
        var accept = true
        override fun present(frameId: Long, buffer: ByteBuffer, format: FrameBufferLedger.Format, dueNs: Long,
                             done: (Outcome) -> Unit): Boolean {
            if (accept) shown += Shown(frameId, dueNs, buffer, done)
            return accept
        }
        override fun restoreNative(report: FramePresentationRecovery.Report) { restores += report }
    }

    private val synth = Synth()
    private val screen = Screen()
    private val recovery = FramePresentationRecovery(window = 20)
    /** 默认 1 ms 提前量：每个节拍只能规划当期槽位，单个场景的持有关系可逐步断言；连续规划另有专测。 */
    private fun session(ledger: FrameBufferLedger = FrameBufferLedger(6) { ByteBuffer.allocate(it) },
                        leadNs: Long = 1_000_000L) =
        FrameInterpolationSession(60, ledger, synth, { if (acceptWork) { tasks += it; true } else false },
            screen, { now }, recovery, timeline = FrameTimeline(60, maxLeadNs = leadNs))
    private fun epoch(id: Long) = Epoch(id, 1L, 1L, 1L, 1L)
    private fun clock(paused: Boolean = false) = ClockSnapshot(0L, 0L, 1.0, paused, 1L)
    private fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst()() }
    private fun FrameInterpolationSession.capture(pts: Long, marker: Byte) =
        capture(pts) { buffer, _ -> buffer.put(0, marker); true }

    /** 30 fps 源（0、33.333 ms）→ 60 fps：第 0 槽为原帧，第 1 槽需要插帧。 */
    private fun playing(ledger: FrameBufferLedger = FrameBufferLedger(6) { ByteBuffer.allocate(it) }):
        Triple<FrameInterpolationSession, FrameBufferLedger, Pair<Long, Long>> {
        val session = session(ledger)
        assertTrue(session.begin(epoch(1L), 4, 2))
        assertEquals(ClockState.READY, session.updateClock(clock()))
        val first = session.capture(0L, 1)!!
        val second = session.capture(33_333L, 2)!!
        return Triple(session, ledger, first to second)
    }

    @Test fun `source duplicates are kept out of the timeline at most twice in a row and do not leak buffers`() {
        val ledger = FrameBufferLedger(8) { ByteBuffer.allocate(it) }
        // 标记相同即视为重复（模拟一拍二/下拉产生的重复画面）。
        val session = FrameInterpolationSession(60, ledger, synth, { tasks += it; true }, screen, { now }, recovery,
            timeline = FrameTimeline(60, maxLeadNs = 1_000_000L),
            duplicate = { previous, current, _ -> previous.get(0) == current.get(0) })
        assertTrue(session.begin(epoch(1L), 4, 2))
        assertEquals(ClockState.READY, session.updateClock(clock()))
        assertNotNull(session.capture(0L, 1))
        assertNull("first duplicate dropped", session.capture(33_333L, 1))
        assertNull("second duplicate dropped", session.capture(66_667L, 1))
        assertNotNull("a third identical frame is kept so still scenes keep advancing", session.capture(100_000L, 1))
        assertNotNull("new drawing accepted", session.capture(133_333L, 2))
        assertNull(session.capture(166_667L, 2))
        assertEquals(3L, session.snapshot().deduplicated)
        // 丢弃的重复帧只持有过 CAPTURE，已全部归还：活跃缓冲只剩时间线里的 3 帧。
        assertEquals(3, ledger.outstanding(Holder.TIMELINE).size)
        assertTrue(ledger.outstanding(Holder.CAPTURE).isEmpty())
        // 新 epoch 重置去重状态：首帧不会与上一 epoch 的帧比较。
        assertTrue(session.begin(epoch(2L), 4, 2))
        assertEquals(ClockState.READY, session.updateClock(clock()))
        assertNotNull(session.capture(0L, 2))
    }

    @Test fun `original then interpolated frame are presented and every buffer returns to the pool`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        assertEquals(ids.first, screen.shown.single().id)
        assertEquals(1.toByte(), screen.shown.single().buffer.get(0))

        now = slot1
        session.tick(now)
        assertEquals(1, tasks.size)
        assertEquals(setOf(Holder.TIMELINE, Holder.WORKER, Holder.PRESENTER), ledger.holders(ids.first))
        drain()
        assertEquals(1, synth.calls)
        val interpolated = screen.shown[1]
        assertEquals(slot1, interpolated.dueNs)
        assertEquals(7.toByte(), interpolated.buffer.get(0))
        assertEquals(setOf(Holder.PRESENTER), ledger.holders(interpolated.id))
        assertFalse(Holder.WORKER in ledger.holders(ids.first))

        screen.shown.forEach { it.done(Outcome.Presented(it.dueNs)) }
        assertTrue(ledger.holders(interpolated.id).isEmpty())
        assertEquals(State.INTERPOLATING, session.snapshot().recovery.state)
        session.close()
        drain()
        assertTrue(ledger.drained())
        assertEquals(1, synth.closed)
    }

    @Test fun `teardown cannot recycle the previous frame while duplicate comparison is reading it`() {
        val ledger = FrameBufferLedger(3) { ByteBuffer.allocate(it) }
        val comparing = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val session = FrameInterpolationSession(60, ledger, synth, { tasks += it; true }, screen, { now },
            duplicate = { previous, _, _ ->
                comparing.countDown()
                check(resume.await(5, java.util.concurrent.TimeUnit.SECONDS))
                if (previous.get(0) != 1.toByte()) failure.set(AssertionError("previous pixels changed during teardown"))
                false
            })
        assertTrue(session.begin(epoch(1L), 4, 2))
        val previous = session.capture(0L, 1)!!
        val capturing = Thread {
            try { assertNull(session.capture(33_333L, 2)) } catch (t: Throwable) { failure.set(t) }
        }
        capturing.start()
        try {
            assertTrue(comparing.await(5, java.util.concurrent.TimeUnit.SECONDS))
            session.close()
            assertNotNull("comparison needs a hold independent of the revoked timeline", ledger.format(previous))
            assertFalse(ledger.drained())
        } finally {
            resume.countDown()
            capturing.join(5_000)
        }
        assertFalse(capturing.isAlive)
        failure.get()?.let { throw AssertionError("capture failed", it) }
        drain()
        assertTrue(ledger.drained())
    }

    @Test fun `seek during duplicate comparison cannot reuse its pixels or admit its stale capture`() {
        val ledger = FrameBufferLedger(3) { ByteBuffer.allocate(it) }
        lateinit var session: FrameInterpolationSession
        var comparisons = 0
        var restarted = false
        var nextFrame: Long? = null
        var liveDuringComparison = 0
        var previousPixel: Byte? = null
        session = FrameInterpolationSession(60, ledger, synth, { tasks += it; true }, screen, { now },
            duplicate = { previous, _, _ ->
                comparisons++
                restarted = session.begin(epoch(2L), 4, 2)
                nextFrame = session.capture(0L, 9)
                liveDuringComparison = ledger.stats().live
                previousPixel = previous.get(0)
                false
            })
        assertTrue(session.begin(epoch(1L), 4, 2))
        assertNotNull(session.capture(0L, 1))
        assertNull(session.capture(33_333L, 2))
        assertEquals(1, comparisons)
        assertTrue(restarted)
        assertNotNull(nextFrame)
        assertEquals("old frame is still pinned beside the new capture", 3, liveDuringComparison)
        assertEquals(1.toByte(), previousPixel)
        assertEquals(1, ledger.stats().live)
        assertTrue(ledger.outstanding(Holder.COMPARISON).isEmpty())
        session.close()
        drain()
        assertTrue(ledger.drained())
    }

    @Test fun `duplicate comparator failure releases its hold and accepts the source frame`() {
        val ledger = FrameBufferLedger(3) { ByteBuffer.allocate(it) }
        val session = FrameInterpolationSession(60, ledger, synth, { tasks += it; true }, screen, { now },
            duplicate = { _, _, _ -> throw IllegalStateException("comparison failed") })
        assertTrue(session.begin(epoch(1L), 4, 2))
        assertNotNull(session.capture(0L, 1))
        assertNotNull(session.capture(33_333L, 2))
        assertTrue(ledger.outstanding(Holder.COMPARISON).isEmpty())
        assertEquals(0L, session.snapshot().deduplicated)
        session.close()
        drain()
        assertTrue(ledger.drained())
    }

    @Test fun `seek during inference discards the stale result and releases all its holds`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertTrue(session.begin(epoch(2L), 4, 2))
        assertEquals(setOf(Holder.PRESENTER, Holder.WORKER), ledger.holders(ids.first))
        assertEquals(setOf(Holder.WORKER), ledger.holders(ids.second))
        drain()
        assertEquals(1, screen.shown.size)
        assertTrue(ledger.holders(ids.second).isEmpty())
        screen.shown.single().done(Outcome.Presented(0L))
        assertEquals(0, ledger.stats().live)
        assertEquals(0, session.snapshot().recovery.samples)
    }

    @Test fun `teardown defers synthesizer close until the in flight task ends and drains after presenter returns`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertEquals(listOf(ids.first), session.close())
        assertEquals(0, synth.closed)
        assertNull(session.capture(66_666L, 3))
        assertFalse(session.begin(epoch(2L), 4, 2))
        drain()
        assertEquals(1, synth.closed)
        assertFalse(ledger.drained())
        screen.shown.single().done(Outcome.Dropped(now))
        assertTrue(ledger.drained())
        assertEquals(Phase.CLOSED, session.snapshot().phase)
    }

    @Test fun `frames superseded by a slower panel return their buffers without counting as failures`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        drain()
        val before = session.snapshot().recovery
        screen.shown.forEach { it.done(Outcome.Superseded(now)) }
        val after = session.snapshot()
        assertEquals(State.INTERPOLATING, after.recovery.state)
        assertEquals(before.samples, after.recovery.samples)
        assertEquals(0, after.recovery.failures)
        assertTrue(screen.restores.isEmpty())
        assertTrue(ledger.outstanding(Holder.PRESENTER).isEmpty())
        session.close()
        drain()
        assertTrue(ledger.drained())
    }

    @Test fun `surface loss restores native once, returns queued frames and retries on a later surface epoch`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        session.onSurfaceLost(0L)
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        assertEquals(State.FALLBACK, screen.restores.single().state)
        assertEquals(listOf(Holder.PRESENTER), ledger.holders(screen.shown.single().id).toList())
        screen.shown.single().done(Outcome.SurfaceLost(0L))
        assertEquals(0, ledger.stats().live)
        assertEquals(1, screen.restores.size)

        now = 500_000_000L
        assertFalse(session.begin(epoch(2L), 4, 2))
        assertEquals(1, screen.restores.size)
        now = 1_000_000_000L
        assertTrue(session.begin(epoch(3L), 4, 2))
        assertEquals(Phase.ACTIVE, session.snapshot().phase)
        assertEquals(1, session.snapshot().recovery.retries)
    }

    @Test fun `steady playback fallback retries by itself once the backoff expires`() {
        val (session, _, _) = playing()
        screen.accept = false
        repeat(3) { now = it * slot1; session.tick(now); drain() }
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        assertEquals(State.FALLBACK, screen.restores.single().state)
        screen.accept = true
        now = 999_999_999L
        session.tick(now)
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        now = 1_100_000_000L
        session.tick(now)
        assertEquals(Phase.ACTIVE, session.snapshot().phase)
        assertEquals(1, session.snapshot().recovery.retries)
    }

    @Test fun `after surface loss only an epoch with a different surface is retried automatically`() {
        val (session, _, _) = playing()
        session.onSurfaceLost(0L)
        now = 2_000_000_000L
        session.tick(now)
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        assertTrue(session.begin(Epoch(2L, 1L, 2L, 1L, 1L), 4, 2))

        session.onSurfaceLost(now)
        now = 2_500_000_000L
        assertFalse(session.begin(Epoch(3L, 1L, 3L, 1L, 1L), 4, 2))
        now = 3_999_999_999L
        session.tick(now)
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        now = 4_000_000_000L
        session.tick(now)
        assertEquals(Phase.ACTIVE, session.snapshot().phase)
        assertEquals(2, session.snapshot().recovery.retries)
    }

    @Test fun `fatal native error disables interpolation and hands playback back to the host`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        synth.status = Status.GPU_UNAVAILABLE
        drain()
        assertEquals(State.DISABLED, screen.restores.single().state)
        assertEquals(Status.GPU_UNAVAILABLE, screen.restores.single().nativeStatus)
        assertEquals(1, screen.shown.size)
        screen.shown.single().done(Outcome.Presented(0L))
        assertEquals(0, ledger.stats().live)
        now = 100_000_000_000L
        assertFalse(session.begin(epoch(2L), 4, 2))
    }

    @Test fun `a single worker timeout counts as a failure and its late result is never presented`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        now = slot1 + 100_000_001L
        session.tick(now)
        assertTrue(screen.restores.isEmpty())
        assertEquals(1, session.snapshot().recovery.failures)
        drain()
        // 迟到的推理结果被丢弃：只呈现过第 0 槽的原帧。
        assertEquals(1, screen.shown.size)
        screen.shown.single().done(Outcome.Presented(now))
        assertEquals(setOf(FrameBufferLedger.Holder.TIMELINE), ledger.holders(ids.first))
    }

    @Test fun `repeated worker timeouts fall back to native`() {
        val recovery = FramePresentationRecovery(window = 20).also { it.start(0L) }
        repeat(3) { recovery.onWorkerTimeout(it.toLong()) }
        assertEquals(State.FALLBACK, recovery.report().state)
        assertEquals(FramePresentationRecovery.Cause.WORKER_TIMEOUT, recovery.report().cause)
    }


    @Test fun `inference finishing after its slot is a dropped frame and not a late burst`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        now = slot1 + 1L
        drain()
        assertEquals(1, screen.shown.size)
        assertEquals(1, session.snapshot().recovery.failures)
        assertEquals(2, ledger.stats().live)
    }

    @Test fun `originals are planned back to back and the next slot starts as soon as inference ends`() {
        val session = session(leadNs = 34_000_000L)
        assertTrue(session.begin(epoch(1L), 4, 2))
        session.updateClock(clock())
        listOf(0L, 33_333L, 66_667L, 100_000L).forEachIndexed { i, pts -> session.capture(pts, (i + 1).toByte()) }
        // 一个节拍内：槽 0 原帧，随即规划槽 1（需推理）并提交任务，不再等下一个节拍。
        session.tick(0L)
        assertEquals(1, screen.shown.size)
        assertEquals(1, tasks.size)
        // 推理完成即呈现槽 1，并在同一回调里继续规划：槽 2 原帧立即呈现；槽 3 尚在提前量之外。
        drain()
        assertEquals(listOf(0L, slot1, 2 * slot1 + 1), screen.shown.map { it.dueNs })
        assertTrue(tasks.isEmpty())
        now = 20_000_000L
        session.tick(now)
        assertEquals(1, tasks.size)
    }

    @Test fun `replayed presenter callbacks release and count only once`() {
        val (session, ledger, ids) = playing()
        session.tick(0L)
        val shown = screen.shown.single()
        shown.done(Outcome.Presented(0L))
        shown.done(Outcome.Presented(0L))
        shown.done(Outcome.Dropped(0L))
        assertEquals(1, session.snapshot().recovery.samples)
        assertEquals(0, session.snapshot().recovery.failures)
        assertEquals(setOf(Holder.TIMELINE), ledger.holders(ids.first))
    }

    @Test fun `degraded state presents nearest originals without submitting inference`() {
        val (session, _, ids) = playing()
        repeat(9) { recovery.onPresented(0L, 0L, 1L) }
        recovery.onDropped(0L)
        assertEquals(State.DEGRADED, recovery.report().state)
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertTrue(tasks.isEmpty())
        assertEquals(0, synth.calls)
        assertEquals(2, screen.shown.size)
        assertTrue(screen.shown[1].id in setOf(ids.first, ids.second))
    }

    @Test fun `pause revokes the epoch and releases the timeline window without presenting`() {
        val (session, ledger, _) = playing()
        assertEquals(ClockState.RESTART_REQUIRED, session.updateClock(clock(paused = true)))
        assertEquals(Phase.IDLE, session.snapshot().phase)
        assertEquals(0, ledger.stats().live)
        session.tick(0L)
        assertTrue(screen.shown.isEmpty())
        assertNull(session.capture(0L, 1))
        assertTrue(session.begin(epoch(2L), 4, 2))
        assertTrue(screen.restores.isEmpty())
    }

    @Test fun `rejected presentation and rejected work release holds and feed recovery`() {
        val (session, ledger, ids) = playing()
        screen.accept = false
        session.tick(0L)
        assertEquals(setOf(Holder.TIMELINE), ledger.holders(ids.first))
        assertEquals(1, session.snapshot().recovery.failures)

        screen.accept = true
        acceptWork = false
        now = slot1
        session.tick(now)
        assertEquals(setOf(Holder.TIMELINE), ledger.holders(ids.first))
        assertEquals(setOf(Holder.TIMELINE), ledger.holders(ids.second))
        assertEquals(2, session.snapshot().recovery.failures)
        assertEquals(Status.BUSY, session.snapshot().recovery.nativeStatus)
        assertEquals(2, ledger.stats().live)
    }

    @Test fun `exhausted pool rejects capture and presents originals instead of over allocating`() {
        var allocations = 0
        val (session, ledger, ids) = playing(FrameBufferLedger(2) { allocations++; ByteBuffer.allocate(it) })
        assertNull(session.capture(66_666L, 3))
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertTrue(tasks.isEmpty())
        assertEquals(2, allocations)
        assertTrue(screen.shown[1].id in setOf(ids.first, ids.second))
        assertEquals(2, ledger.stats().allocated)
    }

    @Test fun `repeated rejected clocks restore native and drain old worker and presenter frames`() {
        val (session, ledger, _) = playing()
        session.tick(0L)
        now = slot1
        session.tick(now)
        assertEquals(1, tasks.size)
        repeat(3) { i ->
            val invalid = ClockSnapshot(0L, 0L, 1.0, false, 1L, uncertaintyUs = 10_000L)
            assertEquals(ClockState.RESTART_REQUIRED, session.updateClock(invalid))
            assertEquals(i < 2, session.onClockRejected(now))
            if (i < 2) assertTrue(session.begin(epoch(2L + i), 4, 2))
        }
        assertEquals(Phase.NATIVE, session.snapshot().phase)
        assertEquals(FramePresentationRecovery.Cause.UNVERIFIED_CLOCK, screen.restores.single().cause)
        drain()
        screen.shown.forEach { it.done(Outcome.Dropped(now)) }
        session.close()
        drain()
        assertTrue(ledger.drained())
        assertEquals(1, synth.closed)
    }
}
