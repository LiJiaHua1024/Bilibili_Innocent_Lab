package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.junit.Assert.*
import org.junit.Test

class AgentTaskLimitsTest {
    @Test fun `default limits preserve two minute twelve step budget`() {
        val limits = AgentTaskLimits()
        assertFalse(limits.timeExceeded(100, 120_099))
        assertTrue(limits.timeExceeded(100, 120_100))
        assertTrue(limits.allowsStep(11))
        assertFalse(limits.allowsStep(12))
        assertEquals(120_100L, limits.requestDeadline(100, 119_100, 15_000))
    }

    @Test fun `unlimited dimensions are independent and requests remain finite`() {
        val time = AgentTaskLimits(0, 12)
        assertFalse(time.timeExceeded(100, 9_000_000))
        assertFalse(time.allowsStep(12))
        assertEquals(9_015_000L, time.requestDeadline(100, 9_000_000, 15_000))
        val steps = AgentTaskLimits(120_000, 0)
        assertTrue(steps.timeExceeded(100, 120_100))
        assertTrue(steps.allowsStep(10_000_000))
        assertTrue(AgentTaskLimits(0, 0).allowsStep(Long.MAX_VALUE - 1))
    }

    @Test fun `expired task gives no fresh operation deadline`() {
        assertEquals(120_101L, AgentTaskLimits().requestDeadline(100, 120_101, 15_000))
    }

    @Test fun `large budgets and near maximum timestamps never overflow`() {
        val limits = AgentTaskLimits(Long.MAX_VALUE, 0)
        assertEquals(10L, limits.elapsedMs(Long.MAX_VALUE - 10, Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, limits.requestDeadline(Long.MAX_VALUE - 10, Long.MAX_VALUE - 5, 15_000))
        assertFalse(limits.allowsStep(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, AgentTaskLimits.saturatedAdd(Long.MAX_VALUE - 1, 100))
    }

    @Test fun `clock rollback does not become a negative elapsed duration`() {
        assertEquals(0L, AgentTaskLimits().elapsedMs(200, 100))
    }

    @Test fun `invalid values and unbounded operation timeout are rejected`() {
        fun rejected(action: () -> Unit) { try { action(); fail("Expected invalid budget") } catch (_: IllegalArgumentException) { } }
        rejected { AgentTaskLimits(-1, 1) }
        rejected { AgentTaskLimits(1, -1) }
        rejected { AgentTaskLimits().allowsStep(-1) }
        rejected { AgentTaskLimits().requestDeadline(0, 1, 0) }
        rejected { AgentTaskLimits(0, 0).requestDeadline(0, 1, Long.MAX_VALUE) }
    }
}
