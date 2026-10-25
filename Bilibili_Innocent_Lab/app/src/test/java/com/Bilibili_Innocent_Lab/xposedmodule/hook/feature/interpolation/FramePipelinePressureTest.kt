package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer
import java.util.PriorityQueue
import org.junit.Assert.*
import org.junit.Test

/** 用虚拟时间保留真实的 worker/呈现持帧时长，检查深流水线的缓冲背压，而非即时归还所有帧。 */
class FramePipelinePressureTest {
    private class Event(val at: Long, val order: Int, val run: () -> Unit)

    @Test fun `150 and 200 ms pipelines fit the bounded pool with pulldown and inference jitter`() {
        for (delayMs in listOf(150L, 200L)) for (sourceFps in listOf(25, 30)) for (pulldown in listOf(false, true)) {
            simulate(delayMs, sourceFps, pulldown)
        }
    }

    private fun simulate(delayMs: Long, sourceFps: Int, pulldown: Boolean) {
        var now = 0L
        var order = 0
        val events = PriorityQueue<Event>(compareBy<Event> { it.at }.thenBy { it.order })
        fun at(time: Long, task: () -> Unit) { events += Event(time, order++, task) }
        val ledger = FrameBufferLedger(16) { ByteBuffer.allocate(it) }
        val target = sourceFps * 2
        val timeline = FrameTimeline(target, presentationDelayNs = delayMs * 1_000_000L,
            maxLeadNs = 100_000_000L, minLeadNs = 32_000_000L, phaseLock = true,
            originalToleranceUs = 1_000_000L / target / 4,
            frameWindow = kotlin.math.ceil(delayMs / 1000.0 * sourceFps).toInt() + 2)
        var presented = 0
        var maxLive = 0
        var starved = 0
        var jobs = 0
        val presenter = object : FrameInterpolationSession.Presenter {
            override fun present(frameId: Long, buffer: ByteBuffer, format: FrameBufferLedger.Format,
                                 dueNs: Long, done: (FrameInterpolationSession.Outcome) -> Unit): Boolean {
                at(maxOf(now, dueNs)) { presented++; done(FrameInterpolationSession.Outcome.Presented(now)) }
                return true
            }
            override fun restoreNative(report: FramePresentationRecovery.Report) { fail("unexpected fallback: $report") }
        }
        val synth = object : FrameInterpolationSession.Synthesizer {
            override fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
                                 format: FrameBufferLedger.Format, alpha: Float, leftId: Long, rightId: Long) =
                FrameInterpolationBackend.Status.OK
            override fun close() = Unit
        }
        val session = FrameInterpolationSession(target, ledger, synth, { task ->
            // 正常 15 ms，每 31 个任务有一次 28 ms；任务完成后才交还 WORKER/输出缓冲。
            at(now + if (++jobs % 31 == 0) 28_000_000L else 15_000_000L, task)
            true
        }, presenter, { now }, timeline = timeline,
            duplicate = { a, b, _ -> a.getInt(0) == b.getInt(0) })
        val epoch = FrameTimeline.Epoch(1, 1, 1, 1, 1)
        assertTrue(session.begin(epoch, 4, 2))
        val seconds = 20
        repeat(seconds * sourceFps) { frame ->
            // 到达抖动不超过 6 ms，PTS 仍连续；每五张编码帧重复一张原画。
            val arrival = frame * 1_000_000_000L / sourceFps + (frame % 4) * 2_000_000L
            at(arrival) {
                val id = session.capture(1_000_000L + frame * 1_000_000L / sourceFps) { buffer, _ ->
                    buffer.putInt(0, if (pulldown) frame - frame / 5 else frame); true
                }
                if (id == null && (!pulldown || frame % 5 != 0 || frame == 0)) starved++
            }
        }
        repeat(seconds * 60) { tick ->
            at(tick * 1_000_000_000L / 60) {
                session.updateClock(FrameTimeline.ClockSnapshot(1_000_000L + now / 1000, now, 1.0, false, 1,
                    uncertaintyUs = 500L))
                session.tick(now)
            }
        }
        val end = seconds * 1_000_000_000L
        while (events.peek()?.let { it.at < end } == true) {
            val event = events.remove()
            now = event.at
            event.run()
            maxLive = maxOf(maxLive, ledger.stats().live)
        }
        val label = "delay=$delayMs source=$sourceFps pulldown=$pulldown peak=$maxLive ${session.snapshot()}"
        assertEquals(label, 0, starved)
        assertTrue(label, presented >= (seconds - 1) * target)
        assertTrue(label, ledger.stats().allocated <= 16)
        assertEquals(label, 0L, ledger.stats().exhausted)
        session.close()
        while (events.isNotEmpty()) { val event = events.remove(); now = event.at; event.run() }
        assertTrue(label, ledger.drained())
    }
}
