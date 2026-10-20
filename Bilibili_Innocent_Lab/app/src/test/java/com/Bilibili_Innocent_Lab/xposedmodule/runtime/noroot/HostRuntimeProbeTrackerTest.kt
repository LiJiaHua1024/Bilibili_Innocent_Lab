package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostRuntimeProbeTrackerTest {

    private var now = 0L
    private val tracker = HostRuntimeProbeTracker { now }

    @Test
    fun `a plain probe that times out stays unconfirmed`() {
        assertTrue(tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)

        now = 1_500L
        assertEquals(HostRuntimeProbeStatus.UNCONFIRMED, tracker.onTimeout())
    }

    @Test
    fun `a user verify that times out reports not running`() {
        assertTrue(tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L))

        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout())
    }

    @Test
    fun `a reply carrying hooks installed confirms the module is live`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        assertTrue(tracker.onReply("nonce-1", hooksInstalled = true))
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
    }

    @Test
    fun `a reply without hooks is kept pending and times out as not running`() {
        tracker.begin("nonce-1", verify = false, timeoutMs = 1_500L)

        assertTrue(tracker.onReply("nonce-1", hooksInstalled = false))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)

        now = 1_500L
        assertEquals(HostRuntimeProbeStatus.NOT_RUNNING, tracker.onTimeout())
    }

    @Test
    fun `a heartbeat settles the probe window`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        tracker.onHeartbeat()
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)
        assertFalse(tracker.isPending("nonce-1"))
    }

    @Test
    fun `a reply for another window is ignored`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)

        assertFalse(tracker.onReply("stale-nonce", hooksInstalled = true))
        assertEquals(HostRuntimeProbeStatus.PROBING, tracker.status)
    }

    @Test
    fun `concurrent probes merge into the in-flight window`() {
        assertTrue(tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L))
        assertFalse(tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L))
    }

    @Test
    fun `a new probe keeps the previous confirmation until it settles`() {
        tracker.begin("nonce-1", verify = true, timeoutMs = 15_000L)
        tracker.onReply("nonce-1", hooksInstalled = true)

        // 不把已确认闪回“确认中”；只有窗口收不到新证据时才降级。
        tracker.begin("nonce-2", verify = false, timeoutMs = 1_500L)
        assertEquals(HostRuntimeProbeStatus.CONFIRMED, tracker.status)

        now = 15_000L
        assertEquals(HostRuntimeProbeStatus.UNCONFIRMED, tracker.onTimeout())
    }
}
