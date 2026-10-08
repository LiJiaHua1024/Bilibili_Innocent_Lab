package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentRoleRoutingTest {
    private val now = 1_000_000L
    private val planner = AgentModelSource(1, "https://planner.example/v1", "key", "planner")
    private val eye = AgentModelSource(2, "https://eye.example/v1", "key", "eye")
    private val judge = AgentModelSource(3, "https://judge.example/v1", "key", "jev", AgentSourceProtocol.DECISIONS)
    private val sources = listOf(planner, eye, judge)
    private val proof = mapOf(planner.fingerprint to AgentModelCapabilities(true, false, now, "tool round trip"),
        eye.fingerprint to AgentModelCapabilities(false, true, now, "image challenge"),
        judge.fingerprint to AgentModelCapabilities(false, true, now, "typed image challenge", decisions = true,
            decisionFormats = setOf("choice")))

    @Test fun `fixed text planner receives eyes from authorized ordinary or decision models`() {
        val router = AgentSourceRouter(sources, proof)
        val route = AgentRoutePolicy(setOf(1, 2, 3), fixedIndex = 1)
        router.acquire(AgentModelRole.PLANNER, route, now)!!.use { assertEquals(1, it.source.index) }
        router.acquire(AgentModelRole.VISION, route, now, preferredFingerprint = judge.fingerprint)!!.use {
            assertEquals(3, it.source.index)
        }
        router.acquire(AgentModelRole.VISION, route, now, preferredFingerprint = eye.fingerprint)!!.use {
            assertEquals(2, it.source.index)
        }
        assertNull(router.acquire(AgentModelRole.VISION, AgentRoutePolicy(setOf(1), 1), now))
        assertNull(router.acquire(AgentModelRole.PLANNER, AgentRoutePolicy(setOf(3), 3), now))
        router.acquire(AgentModelRole.DECISION, route, now)!!.use { assertEquals(3, it.source.index) }
    }

    @Test fun `shared health survives new task routers without cooling unrelated roles`() {
        val health = AgentHealthRegistry()
        val route = AgentRoutePolicy(setOf(3))
        AgentSourceRouter(sources, proof, health).acquire(AgentModelRole.VISION, route, now)!!
            .fail(now, 60_000, AgentModelException(AgentModelException.Reason.NETWORK))
        val next = AgentSourceRouter(sources, proof, health)
        assertNull(next.acquire(AgentModelRole.VISION, route, now + 59_999))
        next.acquire(AgentModelRole.DECISION, route, now)!!.use { assertEquals(3, it.source.index) }
        next.acquire(AgentModelRole.VISION, route, now + 60_000)!!.close()
        val changed = judge.copy(apiKey = "new-key")
        assertNull(AgentSourceRouter(listOf(changed), proof, health).acquire(AgentModelRole.DECISION, route, now))
    }

    @Test fun `local cancellation and budget failures do not punish source health`() {
        val route = AgentRoutePolicy(setOf(1), 1)
        val router = AgentSourceRouter(sources, proof)
        for (reason in listOf(AgentModelException.Reason.CANCELLED, AgentModelException.Reason.INVALID_REQUEST,
            AgentModelException.Reason.TOO_LARGE)) {
            router.acquire(AgentModelRole.PLANNER, route, now)!!.fail(now, error = AgentModelException(reason))
            assertNotNull(router.acquire(AgentModelRole.PLANNER, route, now)?.also { it.close() })
        }
    }

    @Test fun `planning stays on a successful source and explicit fallback remains bounded`() {
        val another = eye.copy(model = "tool-helper")
        val capabilities = proof + (another.fingerprint to AgentModelCapabilities(true, true, now, "verified"))
        val router = AgentSourceRouter(listOf(planner, another), capabilities)
        val route = AgentRoutePolicy(setOf(1, 2))
        router.acquire(AgentModelRole.PLANNER, route, now)!!.also { assertEquals(1, it.source.index) }.succeed(20_000)
        router.acquire(AgentModelRole.PLANNER, route, now)!!.use { assertEquals(1, it.source.index) }
        router.acquire(AgentModelRole.PLANNER, route.copy(fixedIndex = 1), now)!!.fail(now)
        assertNull(router.acquire(AgentModelRole.PLANNER, route.copy(fixedIndex = 1), now))
        router.acquire(AgentModelRole.PLANNER, route.copy(fixedIndex = 1, allowFallback = true), now)!!.use {
            assertEquals(2, it.source.index)
        }
    }

    @Test fun `role health storage stays bounded across repeated configuration changes`() {
        val health = AgentHealthRegistry()
        repeat(300) { index ->
            val source = planner.copy(apiKey = "key-$index")
            val capabilities = mapOf(source.fingerprint to AgentModelCapabilities(true, false, now, "probe"))
            AgentSourceRouter(listOf(source), capabilities, health).acquire(AgentModelRole.PLANNER,
                AgentRoutePolicy(setOf(1)), now)!!.succeed(100)
        }
        assertTrue(health.size() <= AgentHealthRegistry.MAX_RECORDS)
    }
}
