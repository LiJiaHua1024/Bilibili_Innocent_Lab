package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.junit.Assert.*
import org.junit.Test

class AgentExecutionLogTest {
    private var elapsed = 100L
    private var wall = 1_700_000_000_000L
    private fun log(bytes: Int = AgentExecutionLog.MAX_ENCODED_BYTES) = AgentExecutionLog({ elapsed }, { wall }, bytes)
    private fun id(index: Int = 1) = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"

    @Test fun `typed operation events include source cost counts and cache provenance`() {
        val log = log()
        assertTrue(log.begin(id()))
        elapsed += 75
        assertTrue(log.record(id(), AgentLogPhase.WAITING, AgentLogStatus.SUCCEEDED, 8, AgentLogRole.VISION,
            AgentLogTool.SCREEN, durationMs = 50, inputTokens = 120, outputTokens = 32, step = 3, observations = 2,
            cacheHit = true))
        val task = log.snapshot().tasks.single()
        val event = task.events.last()
        assertEquals(75L, event.elapsedMs)
        assertEquals(50L, event.durationMs)
        assertEquals(120L, event.inputTokens)
        assertEquals(32L, event.outputTokens)
        assertEquals(8, event.source)
        assertEquals(3L, event.step)
        assertTrue(event.cacheHit)
        assertEquals(log.encode().size, log.snapshot().totalBytes)
        assertEquals(log.snapshot().revision, log.revision())
        assertSame(log.snapshot(), log.snapshot())
    }

    @Test fun `invalid ids metadata and out of lifecycle events do not change revision`() {
        val log = log()
        assertFalse(log.begin("my goal https://api.example.test/?key=secret"))
        assertTrue(log.begin(id()))
        val before = log.snapshot()
        assertFalse(log.begin(id()))
        assertFalse(log.record(id(), AgentLogPhase.PLANNING, source = 9))
        assertFalse(log.record(id(), AgentLogPhase.PLANNING, inputTokens = -1))
        assertFalse(log.record(id(), AgentLogPhase.COMPLETE))
        assertFalse(log.record(id(), AgentLogPhase.PLANNING, status = AgentLogStatus.FINISHED))
        assertFalse(log.record(id(2), AgentLogPhase.PLANNING))
        assertFalse(log.finish(id(), AgentLogStatus.SUCCEEDED))
        assertSame(before, log.snapshot())
        assertTrue(log.finish(id(), AgentLogStatus.CANCELLED, reason = AgentLogReason.USER_TAKEOVER))
        assertFalse(log.record(id(), AgentLogPhase.PLANNING))
        assertFalse(log.finish(id(), AgentLogStatus.FAILED))
        assertEquals(AgentLogReason.USER_TAKEOVER, log.snapshot().tasks.single().events.last().reason)
    }

    @Test fun `long running events keep fixed window start and terminal with exact discarded count`() {
        val log = log()
        log.begin(id())
        repeat(10_000) { index ->
            elapsed++
            log.record(id(), AgentLogPhase.SEARCHING, AgentLogStatus.SUCCEEDED, tool = AgentLogTool.SEARCH, step = index.toLong())
        }
        log.finish(id(), AgentLogStatus.FINISHED, step = 10_000)
        val task = log.snapshot().tasks.single()
        assertEquals(256, task.events.size)
        assertEquals(9_746L, task.discardedEvents)
        assertEquals(AgentLogPhase.CONNECTING, task.events.first().phase)
        assertEquals(AgentLogStatus.FINISHED, task.events.last().status)
        assertTrue(task.events.zipWithNext().all { (before, after) -> before.sequence < after.sequence })
        assertTrue(log.encode().size <= AgentExecutionLog.MAX_ENCODED_BYTES)
    }

    @Test fun `task count and global byte budget independently bound retention`() {
        val log = log(AgentExecutionLog.MIN_ENCODED_BYTES)
        repeat(6) { taskIndex ->
            log.begin(id(taskIndex + 1))
            repeat(500) { elapsed++; log.record(id(taskIndex + 1), AgentLogPhase.PLANNING) }
            log.finish(id(taskIndex + 1), AgentLogStatus.FINISHED)
        }
        val snapshot = log.snapshot()
        assertEquals(4, snapshot.tasks.size)
        assertEquals(2L, snapshot.discardedTasks)
        assertEquals(id(6), snapshot.tasks.first().id)
        assertTrue(snapshot.tasks.all { it.events.first().phase == AgentLogPhase.CONNECTING && it.events.last().status == AgentLogStatus.FINISHED })
        assertTrue(snapshot.tasks.sumOf { it.discardedEvents } > 0)
        assertTrue(log.encode().size <= AgentExecutionLog.MIN_ENCODED_BYTES)
        assertEquals(log.encode().size, snapshot.totalBytes)
    }

    @Test fun `persistence round trip restores completed tasks without changing their terminal evidence`() {
        val original = log()
        original.begin(id())
        elapsed += 200
        original.record(id(), AgentLogPhase.READING, AgentLogStatus.SUCCEEDED, step = 2)
        original.finish(id(), AgentLogStatus.LIMITED, step = 2, reason = AgentLogReason.BUDGET)
        val restored = log()
        assertTrue(restored.mergeRestored(original.encode()))
        val task = restored.snapshot().tasks.single()
        assertEquals(original.snapshot().tasks.single(), task)
        assertEquals(AgentLogStatus.LIMITED, task.status)
        assertEquals(AgentLogReason.BUDGET, task.events.last().reason)
    }

    @Test fun `loading older disk log preserves live begin and marks old running tasks interrupted`() {
        val disk = log()
        disk.begin(id())
        elapsed += 50
        disk.record(id(), AgentLogPhase.WAITING, step = 9, observations = 4)
        val live = log()
        live.begin(id(2))
        live.record(id(2), AgentLogPhase.SEARCHING)
        assertTrue(live.mergeRestored(disk.encode()))
        val tasks = live.snapshot().tasks
        assertEquals(id(2), tasks.first().id)
        assertEquals(AgentLogStatus.STARTED, tasks.first().status)
        assertEquals(AgentLogStatus.INTERRUPTED, tasks.last().status)
        assertEquals(50L, tasks.last().elapsedMs)
        assertEquals(9L, tasks.last().events.last().step)
        assertEquals(4L, tasks.last().events.last().observations)
        assertFalse(live.record(id(), AgentLogPhase.SEARCHING))
        assertTrue(live.record(id(2), AgentLogPhase.READING))
        assertTrue(live.finish(id(2), AgentLogStatus.FINISHED))
        assertTrue(log().mergeRestored(live.encode()))
    }

    @Test fun `invalid and oversized disk data never replaces valid live records`() {
        val log = log()
        log.begin(id())
        val snapshot = log.snapshot()
        val valid = log.encode()
        assertFalse(log.mergeRestored(ByteArray(AgentExecutionLog.MAX_ENCODED_BYTES + 1)))
        assertFalse(log.mergeRestored(valid.copyOf(valid.size - 1)))
        assertFalse(log.mergeRestored(valid + byteArrayOf(0)))
        assertFalse(log.mergeRestored(valid.copyOf().apply { this[7] = 2 }))
        assertFalse(log.mergeRestored(valid.copyOf().apply { this[44] = 'x'.code.toByte() }))
        assertSame(snapshot, log.snapshot())
    }

    @Test fun `clear prevents asynchronous older load from resurrecting records`() {
        val disk = log()
        disk.begin(id())
        disk.finish(id(), AgentLogStatus.FINISHED)
        val live = log()
        live.clear()
        assertFalse(live.mergeRestored(disk.encode()))
        assertTrue(live.snapshot().tasks.isEmpty())
        assertTrue(live.begin(id(2)))
        assertEquals(0L, live.snapshot().discardedTasks)
    }

    @Test fun `clock rollback does not reduce elapsed and very large counters remain nonnegative`() {
        val log = log()
        log.begin(id())
        elapsed += 200
        log.record(id(), AgentLogPhase.WAITING)
        elapsed = 0
        log.record(id(), AgentLogPhase.WAITING, durationMs = Long.MAX_VALUE, inputTokens = Long.MAX_VALUE,
            outputTokens = Long.MAX_VALUE, step = Long.MAX_VALUE, observations = Long.MAX_VALUE)
        assertEquals(200L, log.snapshot().tasks.single().elapsedMs)
        assertTrue(log().mergeRestored(log.encode()))
    }

    @Test fun `ordinary writes merge for one second terminal bypasses and unchanged revision is idle`() {
        val policy = AgentLogFlushPolicy { elapsed }
        assertEquals(1_000L, policy.delayFor(1, false))
        policy.written(1, elapsed)
        assertNull(policy.delayFor(1, false))
        elapsed += 250
        assertEquals(750L, policy.delayFor(2, false))
        assertEquals(0L, policy.delayFor(2, true))
        elapsed += 2_000
        assertEquals(0L, policy.delayFor(3, false))
        elapsed = 0
        assertEquals(1_000L, policy.delayFor(3, false))
    }
}
