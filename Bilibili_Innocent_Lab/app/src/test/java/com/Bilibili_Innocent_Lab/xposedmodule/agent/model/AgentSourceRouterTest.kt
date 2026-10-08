package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentSourceRouterTest {
    private val sources = (1..8).map { AgentModelSource(it, "https://source$it.example/v1", "key$it", "model") }
    private val now = 1_000_000L
    private val all = AgentRoutePolicy((1..8).toSet())
    private fun router(vision: Set<Int> = emptySet()) = AgentSourceRouter(sources,
        sources.associate { it.fingerprint to AgentModelCapabilities(true, it.index in vision, now, "probe") })

    @Test fun `eight simultaneous requests spread across all eight verified sources`() {
        val router = router()
        val leases = (1..8).map { router.acquire(true, false, all, now)!! }
        assertEquals((1..8).toList(), leases.map { it.source.index })
        leases.forEach { it.close(); it.close() }
        val next = router.acquire(true, false, all, now)!!
        assertEquals(1, next.source.index)
        next.close()
    }

    @Test fun `fixed route never sends content to another provider without explicit fallback`() {
        val router = router()
        val fixed = AgentRoutePolicy(setOf(1, 2), fixedIndex = 1)
        val first = router.acquire(true, false, fixed, now)!!
        assertEquals(1, first.source.index)
        first.fail(now)
        assertNull(router.acquire(true, false, fixed, now))
        val fallback = router.acquire(true, false, fixed.copy(allowFallback = true), now)!!
        assertEquals(2, fallback.source.index)
        fallback.close()
        assertNull(router.acquire(true, false, AgentRoutePolicy(setOf(1), 1, true), now))
    }

    @Test fun `task scope snapshots cannot grow through a mutable caller set`() {
        val allowed = mutableSetOf(1)
        val policy = AgentRoutePolicy(allowed)
        allowed.add(8)
        assertNull(router(setOf(8)).acquire(true, true, policy, now))
    }

    @Test fun `vision routing needs fresh proof and respects explicit scope`() {
        val router = router(setOf(8))
        assertNull(router.acquire(true, true, AgentRoutePolicy(setOf(1, 2)), now))
        val lease = router.acquire(true, true, all, now)!!
        assertEquals(8, lease.source.index)
        lease.close()
        router.updateCapabilities(sources[7], AgentModelCapabilities(true, false, now, "uncertain"))
        assertNull(router.acquire(true, true, all, now))
        router.updateCapabilities(sources[7], AgentModelCapabilities(true, true, now, "probe"))
        assertNull(router.acquire(true, true, all, now + AgentModelCapabilities.VALID_FOR_MS + 1))
    }

    @Test fun `vision only source can describe images but cannot act as a tool planner`() {
        val visionOnly = sources[7]
        val router = AgentSourceRouter(listOf(visionOnly), mapOf(visionOnly.fingerprint to
            AgentModelCapabilities(false, true, now, "vision verified")))
        val selected = AgentRoutePolicy(setOf(8), fixedIndex = 8)
        assertNull(router.acquire(true, true, selected, now))
        assertNull(router.acquire(true, false, selected, now))
        val helper = router.acquire(false, true, selected, now)!!
        assertEquals(8, helper.source.index)
        helper.close()
        assertNull(router.acquire(false, true, AgentRoutePolicy(setOf(1), fixedIndex = 1), now))
    }

    @Test fun `unverified models and changed credentials never inherit old proof`() {
        val source = sources.first()
        val changed = source.copy(apiKey = "changed-key")
        val router = AgentSourceRouter(listOf(changed), mapOf(source.fingerprint to AgentModelCapabilities(true, true, now, "old")))
        assertNull(router.acquire(true, false, all, now))
        assertNull(router.acquire(false, true, all, now))
        assertNull(AgentSourceRouter(sources).acquire(true, false, all, now))
    }

    @Test fun `cooldown and exclusion prevent repeating a failed model request`() {
        val router = router()
        val lease = router.acquire(true, false, all, now)!!
        lease.fail(now, 60_000)
        lease.fail(now, 300_000) // 重复回调不得扩大冷却或减成负的在途数。
        lease.close()
        assertNull(router.acquire(true, false, AgentRoutePolicy(setOf(1)), now + 59_999))
        assertNotNull(router.acquire(true, false, AgentRoutePolicy(setOf(1)), now + 60_000)?.also { it.close() })
        val next = router.acquire(true, false, all, now + 60_000, setOf(sources[0].fingerprint))!!
        assertEquals(2, next.source.index)
        next.close()
    }

    @Test fun `latency and in flight load affect selection`() {
        val router = router()
        router.acquire(true, false, AgentRoutePolicy(setOf(1)), now)!!.succeed(20_000)
        router.acquire(true, false, AgentRoutePolicy(setOf(2)), now)!!.succeed(100)
        val fast = router.acquire(true, false, AgentRoutePolicy(setOf(1, 2)), now)!!
        assertEquals(2, fast.source.index)
        fast.close()
    }
}
