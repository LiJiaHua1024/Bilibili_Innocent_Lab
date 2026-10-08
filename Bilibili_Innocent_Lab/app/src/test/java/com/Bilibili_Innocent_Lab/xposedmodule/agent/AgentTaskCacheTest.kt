package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentTaskCacheTest {
    @Test fun `cache hit is copied and preserves original observation time`() {
        val cache = AgentTaskCache(2, 100)
        val input = JSONObject().put("title", "original")
        cache.put("model:page:query", input, 100)
        input.put("title", "mutated")
        val first = checkNotNull(cache.get("model:page:query", 101))
        assertEquals("original", first.getString("title"))
        assertTrue(first.getBoolean("cache_hit"))
        assertEquals(100L, first.getLong("observed_at_elapsed"))
        first.put("title", "changed read")
        assertEquals("original", cache.get("model:page:query", 102)?.getString("title"))
    }

    @Test fun `ttl expires at boundary and future timestamps cannot become fresh`() {
        val cache = AgentTaskCache(2, 100)
        cache.put("a", JSONObject().put("value", 1), 100)
        assertNotNull(cache.get("a", 199))
        assertNull(cache.get("a", 200))
        cache.put("a", JSONObject(), 300)
        assertNull(cache.get("a", 299))
        assertEquals(0, cache.size())
    }

    @Test fun `recently read entries survive lru eviction and different keys cannot reuse a result`() {
        val cache = AgentTaskCache(2, 100)
        cache.put("source1:page1", JSONObject(), 100)
        cache.put("source2:page1", JSONObject(), 100)
        cache.get("source1:page1", 101)
        cache.put("source1:page2", JSONObject(), 102)
        assertNull(cache.get("source2:page1", 103))
        assertNotNull(cache.get("source1:page1", 103))
        assertNull(cache.get("source1:page3", 103))
    }

    @Test fun `large or image payloads are never retained`() {
        val cache = AgentTaskCache(2, 100)
        cache.put("image", JSONObject().put("data", JSONObject().put("image_data_url", "data:image/jpeg;base64,abc")), 100)
        cache.put("alternate", JSONObject().put("text", "data:image/png;base64,abc"), 100)
        cache.put("large", JSONObject().put("text", "x".repeat(AgentTaskCache.MAX_ENTRY_CHARS)), 100)
        assertEquals(0, cache.size())
    }

    @Test fun `clear removes all task facts`() {
        val cache = AgentTaskCache(2, 100)
        repeat(1_000) { cache.put("$it", JSONObject().put("value", it), 100) }
        assertEquals(2, cache.size())
        cache.clear()
        assertNull(cache.get("999", 101))
        assertEquals(0, cache.size())
    }
}
