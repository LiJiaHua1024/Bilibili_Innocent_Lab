package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAgentWindowPolicyTest {
    private val search = HostAgentWindowPolicy.Target("search", "黑神话")
    private val ready = HostAgentWindowPolicy.Observation.READY
    private val navigating = HostAgentWindowPolicy.Observation.NAVIGATING
    private val revoked = HostAgentWindowPolicy.Observation.REVOKED

    @Test fun `begin owns only the initial window and does not authorize a screenshot yet`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertEquals(ready, gate.observe(1, 10, "unknown", null, 100))
        assertFalse(gate.isTaskPage(1))
        assertEquals(revoked, gate.observe(1, 11, "unknown", null, 101))
        assertEquals(revoked, gate.observe(1, 10, "unknown", null, 102))
        assertNull(gate.expectNavigation(1, 10, search, 103))
    }

    @Test fun `unexpected pause revokes even when later resume carries a matching old intent`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertNotNull(gate.expectNavigation(1, 10, search, 100))
        assertEquals(ready, gate.observe(1, 10, search.kind, search.identity, 101))
        assertFalse(gate.pause(1, 10, 102))
        assertFalse(gate.resume(1, 10, search.kind, search.identity, 103))
        assertNull(gate.expectNavigation(1, 10, HostAgentWindowPolicy.Target("video", "av1"), 104))
    }

    @Test fun `expected navigation permits one pause and a matching target in its time window`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertNotNull(gate.expectNavigation(1, 10, search, 100))
        assertTrue(gate.awaitingNavigation(1, 101))
        assertEquals(navigating, gate.observe(1, 10, "unknown", null, 102))
        assertFalse(gate.isTaskPage(1))
        assertTrue(gate.pause(1, 10, 103))
        assertTrue(gate.resume(1, 11, search.kind, search.identity, 104))
        assertEquals(ready, gate.observe(1, 11, search.kind, search.identity, 105))
        assertTrue(gate.isTaskPage(1))
        assertFalse(gate.awaitingNavigation(1, 106))
    }

    @Test fun `expected navigation does not accept a login screen or different query`() {
        for ((kind, identity) in listOf("unknown" to null, "search" to "其他搜索", "video" to "av1")) {
            val gate = HostAgentWindowPolicy()
            gate.begin(1, 10)
            assertNotNull(gate.expectNavigation(1, 10, search, 100))
            assertTrue(gate.pause(1, 10, 101))
            assertFalse(gate.resume(1, 11, kind, identity, 102))
            assertFalse(gate.resume(1, 12, search.kind, search.identity, 103))
            assertFalse(gate.isTaskPage(1))
        }
    }

    @Test fun `same Activity navigation requires a new matching observation`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        val token = checkNotNull(gate.expectNavigation(1, 10, search, 100))
        assertEquals(navigating, gate.observe(1, 10, "search", "旧搜索", 101))
        assertNull(gate.expectNavigation(1, 10, HostAgentWindowPolicy.Target("video", "av1"), 102))
        assertEquals(ready, gate.observe(1, 10, "search", "黑神话", 103))
        assertTrue(gate.isTaskPage(1))
        assertFalse(gate.expireNavigation(1, token, 6000))
    }

    @Test fun `deadline cannot be revived by a late matching resume`() {
        val gate = HostAgentWindowPolicy(500)
        gate.begin(1, 10)
        val token = checkNotNull(gate.expectNavigation(1, 10, search, 100))
        assertFalse(gate.expireNavigation(1, token, 599))
        assertFalse(gate.awaitingNavigation(1, 600))
        assertTrue(gate.expireNavigation(1, token, 600))
        assertFalse(gate.resume(1, 11, search.kind, search.identity, 601))
    }

    @Test fun `matching target exactly at deadline still rejects navigation`() {
        val gate = HostAgentWindowPolicy(500)
        gate.begin(1, 10)
        assertNotNull(gate.expectNavigation(1, 10, search, 100))
        assertTrue(gate.pause(1, 10, 101))
        assertFalse(gate.resume(1, 11, search.kind, search.identity, 600))
    }

    @Test fun `user takeover revokes late navigation and capture despite an unchanged intent`() {
        val session = HostAgentSession()
        val lease = checkNotNull(session.begin("task-00000001", 1, 120_100, 100, true).lease)
        val gate = HostAgentWindowPolicy()
        gate.begin(lease.generation, 10)
        assertNotNull(gate.expectNavigation(lease.generation, 10, search, 101))
        assertEquals(ready, gate.observe(lease.generation, 10, search.kind, search.identity, 102))
        gate.revoke(lease.generation)
        session.cancel(lease, 103)
        assertEquals(revoked, gate.observe(lease.generation, 10, search.kind, search.identity, 104))
        assertFalse(gate.isTaskPage(lease.generation))
        assertFalse(session.allowVision(lease, 104))
        var navigationRan = false
        session.whileActive(lease, 104) { navigationRan = true }
        assertFalse(navigationRan)
    }

    @Test fun `old timers and old window callbacks cannot revoke a new task or navigation`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        val oldTask = checkNotNull(gate.expectNavigation(1, 10, search, 100))
        gate.begin(2, 20)
        val oldNavigation = checkNotNull(gate.expectNavigation(2, 20, search, 101))
        assertEquals(ready, gate.observe(2, 20, search.kind, search.identity, 102))
        val current = checkNotNull(gate.expectNavigation(2, 20, HostAgentWindowPolicy.Target("video", "av1"), 103))
        gate.revoke(1)
        assertFalse(gate.expireNavigation(1, oldTask, 6000))
        assertFalse(gate.expireNavigation(2, oldNavigation, 6000))
        assertTrue(gate.tracks(2))
        assertTrue(gate.expireNavigation(2, current, 6000))
    }

    @Test fun `settled page identity drift revokes instead of reacquiring an arbitrary foreground page`() {
        val gate = HostAgentWindowPolicy()
        gate.begin(1, 10)
        assertNotNull(gate.expectNavigation(1, 10, search, 100))
        assertEquals(ready, gate.observe(1, 10, search.kind, search.identity, 101))
        assertEquals(revoked, gate.observe(1, 10, "search", "别的目标", 102))
        assertEquals(revoked, gate.observe(1, 10, search.kind, search.identity, 103))
    }
}
