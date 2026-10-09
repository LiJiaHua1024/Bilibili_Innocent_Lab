package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentBoundedCacheTest {
    @Test fun `compatibility metadata uses LRU bounds TTL and refuses clock rollback`() {
        var now = 1000L
        val cache = AgentBoundedCache<String, String>(capacity = 2, ttlMs = 100, clock = { now })
        cache["a"] = "first"; cache["b"] = "second"
        assertEquals("first", cache["a"])
        cache["c"] = "third"
        assertNull(cache["b"])
        assertEquals(2, cache.size())
        now = 1100
        assertEquals("first", cache["a"])
        now = 1101
        assertNull(cache["a"])
        cache["new"] = "value"
        now = 900
        assertNull(cache["new"])
    }
}
