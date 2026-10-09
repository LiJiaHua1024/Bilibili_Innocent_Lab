package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAgentPolicyTest {
    private val taskId = "task-00000001"
    private fun begin(session: HostAgentSession, vision: Boolean = false) =
        checkNotNull(session.begin(taskId, 1, 120_100, 100, vision).lease)

    @Test fun `begin requires fresh bounded task and first sequence`() {
        val session = HostAgentSession()
        assertEquals("invalid_begin", session.begin("short", 1, 200, 100, false).error)
        assertEquals("invalid_begin", session.begin(taskId, 2, 200, 100, false).error)
        assertEquals("invalid_begin", session.begin(taskId, 1, 100, 100, false).error)
        assertEquals("invalid_begin", session.begin(taskId, 1, 180_101, 100, false).error)
        assertEquals("invalid_begin", session.begin(taskId, 1, 15_101, 180_100, 100, false).error)
        assertNotNull(session.begin(taskId, 1, 15_100, 180_100, 100, false).lease)
        assertEquals("task_busy", session.begin("task-00000002", 1, 200, 100, false).error)
    }

    @Test fun `sequence replays and wrong task are rejected without changing live lease`() {
        val session = HostAgentSession()
        val lease = begin(session)
        assertEquals("task_mismatch", session.admit("task-00000002", 2, 1000, 200).error)
        assertEquals("replayed_sequence", session.admit(taskId, 1, 1000, 200).error)
        assertNotNull(session.admit(taskId, 3, 1000, 200).lease)
        assertEquals("replayed_sequence", session.admit(taskId, 2, 1000, 200).error)
        assertTrue(session.isActive(lease, 201))
    }

    @Test fun `per command deadline cannot extend task and expired command cannot act`() {
        val session = HostAgentSession()
        begin(session)
        assertEquals("invalid_deadline", session.admit(taskId, 2, 120_101, 200).error)
        val command = checkNotNull(session.admit(taskId, 2, 500, 200).lease)
        var actions = 0
        session.whileActive(command, 499) { actions++ }
        session.whileActive(command, 500) { actions++ }
        assertEquals(1, actions)
        assertNotNull(session.current(500))
    }

    @Test fun `cancel immediately invalidates previously queued actions and disallows resurrection`() {
        val session = HostAgentSession()
        val lease = begin(session)
        val queued = checkNotNull(session.admit(taskId, 2, 2_000, 200).lease)
        assertNotNull(session.admit(taskId, 3, 2_000, 201, closing = true).lease)
        var navigated = false
        session.whileActive(queued, 202) { navigated = true }
        assertFalse(navigated)
        assertFalse(session.isActive(lease, 202))
        assertEquals("closed_task", session.begin(taskId, 1, 1000, 203, false).error)
        assertEquals("task_inactive", session.admit(taskId, 4, 1000, 204).error)
    }

    @Test fun `old service death or expiry cannot cancel replacement task`() {
        val session = HostAgentSession()
        val old = begin(session)
        assertTrue(session.cancel(old, 200))
        val fresh = checkNotNull(session.begin("task-00000002", 1, 1000, 201, false).lease)
        assertFalse(session.cancel(old, 202))
        assertTrue(session.isActive(fresh, 202))
        assertNull(session.current(1000))
        assertFalse(session.isActive(fresh, 1000))
    }

    @Test fun `candidate identity is local to task bounded and invalid after cancel`() {
        val session = HostAgentSession()
        val lease = begin(session)
        val admitted = session.remember(lease, (1..300).map { "av$it" }, 101)
        assertEquals(HostAgentSession.MAX_CANDIDATES, admitted.size)
        assertFalse(session.knows(lease, "av1", 102))
        assertTrue(session.knows(lease, "av300", 102))
        assertFalse(session.knows(lease, "123", 102))
        assertEquals(listOf("av1", "av999"), session.remember(lease, listOf("evil", "av1", "av999"), 103))
        session.cancel(lease, 104)
        val fresh = checkNotNull(session.begin("task-00000002", 1, 1000, 105, false).lease)
        assertFalse(session.knows(fresh, "av1", 106))
    }

    @Test fun `opaque page tokens are query and task bound and capped`() {
        val session = HostAgentSession()
        val lease = begin(session)
        val token = checkNotNull(session.rememberCursor(lease, "黑神话", "backend-next", 101))
        assertEquals("backend-next", session.cursor(lease, "黑神话", token, 102))
        assertNull(session.cursor(lease, "其他目标", token, 102))
        assertNull(session.cursor(lease, "黑神话", "backend-next", 102))
        assertNull(session.rememberCursor(lease, "黑神话", "x".repeat(2049), 102))
        repeat(HostAgentSession.MAX_CURSORS - 1) { assertNotNull(session.rememberCursor(lease, "黑神话", "next-$it", 103)) }
        assertNotNull(session.rememberCursor(lease, "黑神话", "next-overflow", 104))
        assertNull(session.cursor(lease, "黑神话", token, 104))
        session.cancel(lease, 105)
        assertNull(session.cursor(lease, "黑神话", token, 106))
    }

    @Test fun `vision permission and requested page both belong to task`() {
        val session = HostAgentSession()
        val lease = begin(session)
        assertFalse(session.allowVision(lease, 101))
        session.expectedPage(lease, query = "悟空", now = 101)
        assertTrue(session.matchesPage(lease, "search", "悟空", 102))
        assertFalse(session.matchesPage(lease, "search", "其他", 102))
        assertFalse(session.matchesPage(lease, "video", "av1", 102))
        session.expectedPage(lease, video = "av1", now = 103)
        assertFalse(session.matchesPage(lease, "search", "悟空", 104))
        assertTrue(session.matchesPage(lease, "video", "av1", 104))
        session.cancel(lease, 105)
        assertFalse(session.matchesPage(lease, "video", "av1", 106))
        val fresh = checkNotNull(session.begin("task-00000002", 1, 1000, 107, true).lease)
        assertTrue(session.allowVision(fresh, 108))
        assertFalse(session.matchesPage(fresh, "video", "av1", 108))
    }

    @Test fun `burst limit recovers and task can still be cancelled`() {
        val session = HostAgentSession()
        val lease = begin(session)
        for (sequence in 2L..HostAgentSession.MAX_COMMANDS_PER_SECOND.toLong()) {
            assertNotNull(session.admit(taskId, sequence, 2_000, 200).lease)
        }
        assertEquals("host_rate_limited", session.admit(taskId, 99, 2_000, 201).error)
        assertNotNull(session.admit(taskId, 100, 2_000, 1_101).lease)
        assertNotNull(session.admit(taskId, 101, 2_000, 1_102, closing = true).lease)
        assertFalse(session.isTaskActive(lease, 1_103))
    }

    @Test fun `renew extends task ownership but never an old command lease`() {
        val session = HostAgentSession()
        val initial = checkNotNull(session.begin(taskId, 1, 1_000, 120_100, 100, true).lease)
        val renewed = checkNotNull(session.renew(taskId, 2, 111_000, 290_000, 110_000).lease)
        assertEquals(initial.generation, renewed.generation)
        assertTrue(session.isTaskActive(initial, 120_101))
        assertFalse(session.isActive(initial, 120_101))
        val command = checkNotNull(session.admit(taskId, 3, 121_000, 120_101).lease)
        assertTrue(session.isActive(command, 120_102))
        checkNotNull(session.renew(taskId, 4, 122_000, 300_000, 120_500).lease)
        assertFalse(session.isActive(command, 121_000))
        assertTrue(session.isTaskActive(command, 121_000))
        assertEquals(300_000L, session.current(121_000)?.deadline)
        assertNull(session.current(300_000))
    }

    @Test fun `renew validates task sequence finite deadlines and cannot resurrect cancelled or expired task`() {
        val session = HostAgentSession()
        val initial = checkNotNull(session.begin(taskId, 1, 1_000, 120_100, 100, false).lease)
        assertEquals("task_mismatch", session.renew("task-00000002", 2, 1_000, 180_200, 200).error)
        assertEquals("replayed_sequence", session.renew(taskId, 1, 1_000, 180_200, 200).error)
        assertEquals("invalid_deadline", session.renew(taskId, 2, Long.MAX_VALUE, Long.MAX_VALUE, 200).error)
        assertEquals("invalid_deadline", session.renew(taskId, 2, 1_000, 180_201, 200).error)
        assertTrue(session.cancel(initial, 201))
        assertEquals("task_inactive", session.renew(taskId, 2, 1_000, 180_200, 202).error)
        val fresh = checkNotNull(session.begin("task-00000002", 1, 1_000, 1_000, 203, false).lease)
        assertEquals("task_inactive", session.renew(fresh.taskId, 2, 2_000, 180_000, 1_000).error)
        assertFalse(session.isTaskActive(fresh, 1_001))
    }

    @Test fun `long task allows hundreds of finite operations with bounded rolling leases`() {
        val session = HostAgentSession()
        val initial = checkNotNull(session.begin(taskId, 1, 1_000, 180_100, 100, false).lease)
        var sequence = 1L
        repeat(400) { index ->
            val now = 1_100L + index * 1_001L
            checkNotNull(session.renew(taskId, ++sequence, now + 1_000, now + 180_000, now).lease)
            checkNotNull(session.admit(taskId, ++sequence, now + 1_000, now + 1).lease)
        }
        assertTrue(session.isTaskActive(initial, 401_000))
        assertFalse(session.isActive(initial, 401_000))
    }

    @Test fun `candidate lru retains recently read video and cursors deduplicate backend tokens`() {
        val session = HostAgentSession()
        val lease = begin(session)
        session.remember(lease, (1..HostAgentSession.MAX_CANDIDATES).map { "av$it" }, 101)
        assertTrue(session.knows(lease, "av1", 102))
        session.remember(lease, listOf("av999"), 103)
        assertTrue(session.knows(lease, "av1", 104))
        assertFalse(session.knows(lease, "av2", 104))
        val token = session.rememberCursor(lease, "悟空", "same-token", 105)
        repeat(100) { assertEquals(token, session.rememberCursor(lease, "悟空", "same-token", 106)) }
        assertEquals("same-token", session.cursor(lease, "悟空", checkNotNull(token), 107))
    }

    @Test fun `closing deadline may extend past task end without extending authorization`() {
        val session = HostAgentSession()
        val lease = begin(session)
        assertNotNull(session.admit(taskId, 2, 123_000, 120_000, closing = true).lease)
        assertFalse(session.isActive(lease, 120_001))
    }

    @Test fun `video identity canonicalization rejects overflow paths and unknown schemes`() {
        val policy = HostAgentNavigationPolicy
        assertEquals("av123", policy.videoId("123"))
        assertEquals("av123", policy.videoId("av123"))
        assertEquals("BV1xx411c7mD", policy.videoId("BV1xx411c7mD"))
        listOf("0", "av0", "00123", "-1", "9223372036854775808", "123/evil", " BV1xx411c7mD").forEach {
            assertNull(it, policy.videoId(it))
        }
        listOf("https://www.bilibili.com/video/123", "bilibili://video.evil/123", "bilibili://user@video/123",
            "bilibili://video:80/123", "bilibili://video/%31%32%33", "bilibili://video/123/456",
            "bilibili://search?keyword=123", "bilibili://video//123").forEach {
            assertNull(it, policy.videoFromUri(it))
        }
    }

    @Test fun `navigation drops all host supplied query actions and catches conflicting ids`() {
        val policy = HostAgentNavigationPolicy
        val id = checkNotNull(policy.videoFromUri("bilibili://video/123?redirect=https://evil.example#fragment"))
        assertEquals("bilibili://video/123", policy.videoRoute(id))
        assertNull(policy.candidateId("123", "bilibili://video/456"))
        assertEquals("av123", policy.candidateId("123", "bilibili://video/BV1xx411c7mD"))
        assertEquals("av123", policy.candidateId("123", "bilibili://evil/execute"))
    }

    @Test fun `search query is bounded and safely encoded into one keyword`() {
        val policy = HostAgentNavigationPolicy
        assertNull(policy.searchQuery(" "))
        assertNull(policy.searchQuery("a\nb"))
        assertNull(policy.searchQuery("x".repeat(201)))
        assertEquals("悟空", policy.searchQuery("  悟空  "))
        assertEquals("bilibili://search?keyword=a%26redirect%3Db%23c%20d", policy.searchRoute("a&redirect=b#c d"))
    }

    @Test fun `capture denies every privacy or incomplete observation condition`() {
        fun refusal(authorized: Boolean = true, foreground: Boolean = true, taskPage: Boolean = true,
                    secure: Boolean = false, password: Boolean = false, editing: Boolean = false,
                    complete: Boolean = true) = HostAgentScreenPolicy.refusal(authorized, foreground, taskPage,
            secure, password, editing, complete)
        assertNull(refusal())
        assertEquals("vision_not_authorized", refusal(authorized = false))
        assertEquals("host_not_foreground", refusal(foreground = false))
        assertEquals("screen_not_task_page", refusal(taskPage = false))
        assertEquals("screen_secure", refusal(secure = true))
        assertEquals("screen_password", refusal(password = true))
        assertEquals("screen_input_active", refusal(editing = true))
        assertEquals("screen_inspection_incomplete", refusal(complete = false))
    }


}
