package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SemanticCacheTest {

    @get:Rule val temp = TemporaryFolder()

    private fun key(i: Int) = "%032x".format(i)

    /**
     * 真实场景：刷一条弹幕很多的视频（大量一次性条目）期间，首页标题、热门评论这类热点
     * 每隔一段仍会被再次访问。间隔 150 条 > 容量 100，所以同一序列下纯 LRU 必然把热点挤掉。
     */
    private fun <T> replay(hot: List<String>, get: (String) -> T, put: (String) -> Unit) {
        hot.forEach(put)
        repeat(3) { hot.forEach { get(it) } }
        (1_000 until 4_000).forEachIndexed { index, i ->
            val k = key(i)
            get(k)
            put(k)
            if (index % 150 == 149) hot.forEach { get(it) }
        }
    }

    @Test
    fun `hot entries survive a scan of one-off entries`() {
        val cache = SemanticScoreCache(maxEntries = 100)
        val hot = (0 until 20).map(::key)
        replay(hot, get = { cache.get(it, 0L) }, put = { cache.put(it, 0.5f, 0L) })
        assertTrue(cache.size() <= 100)
        assertTrue("W-TinyLFU should keep the frequently used entries", hot.all(cache::contains))
    }

    @Test
    fun `plain recency would have evicted the same hot set`() {
        // 对照：同一访问序列下，纯 LRU（LinkedHashMap accessOrder + 容量 100）保不住热点。
        val lru = object : LinkedHashMap<String, Float>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) = size > 100
        }
        val hot = (0 until 20).map(::key)
        var hotHits = 0
        var hotLookups = 0
        replay(hot, get = { k -> if (k in hot) { hotLookups++; if (lru[k] != null) hotHits++ } else lru[k] },
            put = { lru[it] = 0.5f })
        // 首轮预热之后，扫描期间每次回访热点都已被挤出。
        assertTrue(hotHits <= hot.size * 3)
        assertTrue(hotLookups > hot.size * 3)
    }

    @Test
    fun `capacity is bounded and expired entries are dropped`() {
        val cache = SemanticScoreCache(maxEntries = 50, ttlMs = 1_000)
        (0 until 500).forEach { cache.put(key(it), 0.5f, 0L) }
        assertTrue(cache.size() <= 50)

        cache.put(key(9_999), 0.7f, 0L)
        assertEquals(0.7f, cache.get(key(9_999), 1_000L), 0f)
        assertTrue(cache.get(key(9_999), 1_001L).isNaN())
        assertFalse(cache.contains(key(9_999)))
    }

    @Test
    fun `updating an existing key keeps one entry`() {
        val cache = SemanticScoreCache(maxEntries = 50)
        cache.put(key(1), 0.2f, 0L)
        cache.put(key(1), 0.8f, 5L)
        assertEquals(1, cache.size())
        assertEquals(0.8f, cache.get(key(1), 5L), 0f)
    }

    @Test
    fun `restore bypasses admission but respects ttl and capacity`() {
        val cache = SemanticScoreCache(maxEntries = 10, ttlMs = 100)
        val entries = (0 until 30).map { SemanticScoreCache.Entry(key(it), 0.4f, if (it < 5) -1_000L else 50L) }
        cache.restore(entries, now = 100L)
        assertTrue(cache.size() <= 10)
        assertFalse(cache.contains(key(0))) // 过期的不恢复
        assertTrue(cache.contains(key(5)))
    }

    @Test
    fun `disk store round trips and rejects foreign configuration`() {
        val file = File(temp.root, "bil_semantic/comment.bin")
        val store = SemanticDiskStore(file, fingerprint = "A")
        store.save(listOf(SemanticScoreCache.Entry(key(1), 0.9f, 100L), SemanticScoreCache.Entry(key(2), 0.1f, 50L)))
        assertTrue(file.isFile)
        assertTrue(file.length() < 200)

        val loaded = store.load(now = 150L, ttlMs = 1_000L)
        assertEquals(listOf(key(1), key(2)), loaded.map { it.key })
        assertEquals(0.9f, loaded[0].score, 0f)
        // 超过保存时长的条目不加载。
        assertEquals(listOf(key(1)), store.load(now = 1_080L, ttlMs = 1_000L).map { it.key })
        // 模型 / 勾选 / 端点变了：整份作废。
        assertTrue(SemanticDiskStore(file, fingerprint = "B").load(150L, 1_000L).isEmpty())
    }

    @Test
    fun `corrupt or missing files load as empty`() {
        val file = File(temp.root, "bil_semantic/video.bin")
        assertTrue(SemanticDiskStore(file, "A").load(0L, 1_000L).isEmpty())
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(SemanticDiskStore(file, "A").load(0L, 1_000L).isEmpty())
    }

    @Test
    fun `startup cleanup deletes only inactive surfaces`() {
        SemanticSurface.entries.forEach { surface ->
            SemanticDiskStore(SemanticDiskStore.fileFor(temp.root, surface), "A")
                .save(listOf(SemanticScoreCache.Entry(key(1), 0.5f, 0L)))
        }
        SemanticDiskCleanup.run(temp.root, setOf(SemanticSurface.COMMENT))
        SemanticSurface.entries.forEach { surface ->
            assertEquals(surface == SemanticSurface.COMMENT, SemanticDiskStore.fileFor(temp.root, surface).isFile)
        }
        // JEV 未配置：全部删除。
        SemanticDiskCleanup.run(temp.root, emptySet())
        assertTrue(SemanticSurface.entries.none { SemanticDiskStore.fileFor(temp.root, it).isFile })
    }

    @Test
    fun `judgements persist across a restart and are not billed again`() {
        var calls = 0
        val transport: (ByteArray, String, Int) -> Pair<Int, String>? = { body, _, _ ->
            calls += 1
            val count = JSONObject(String(body)).getJSONObject("questions").length()
            val answers = JSONObject()
            repeat(count) { i ->
                answers.put("item_$i", JSONObject().put("type", "choice").put("choice", "block")
                    .put("probabilities", JSONObject().put("block", 0.95).put("keep", 0.05)))
            }
            200 to JSONObject().put("answers", answers).toString()
        }
        val rules = SemanticPresets.COMMENT
        val settings = SemanticSettings.from("k", "", "medium", false, cacheDays = 7, storeRoot = temp.root)!!
        fun judge() = SemanticJudge(
            "k", rules, transport = transport, background = { it.run(); true },
            cache = SemanticScoreCache(ttlMs = settings.cacheTtlMs),
            store = SemanticDiskStore(SemanticDiskStore.fileFor(temp.root, SemanticSurface.COMMENT),
                SemanticJudge.fingerprintOf(rules, settings.endpoint)),
            cacheTtlMs = settings.cacheTtlMs
        )

        val texts = List(60) { "评论$it" } // 超过落盘阈值 50 条
        assertTrue(judge().evaluate(texts, SemanticMode.WAIT).all { it == SemanticVerdict.BLOCK })
        assertEquals(3, calls)
        assertTrue(SemanticDiskStore.fileFor(temp.root, SemanticSurface.COMMENT).isFile)

        // "重启"：新实例、空内存，从硬盘恢复后直接命中。
        val restarted = judge()
        restarted.evaluate(listOf("预热"), SemanticMode.CACHE_ONLY)
        assertTrue(restarted.evaluate(texts, SemanticMode.CACHE_ONLY).all { it == SemanticVerdict.BLOCK })
        assertEquals(3, calls)
    }

    @Test
    fun `retention setting is clamped to one to ninety days`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(90 * day, SemanticSettings.from("k", "", "", false, cacheDays = 365)!!.cacheTtlMs)
        assertEquals(1 * day, SemanticSettings.from("k", "", "", false, cacheDays = 0)!!.cacheTtlMs)
        assertEquals(7 * day, SemanticSettings.from("k", "", "", false)!!.cacheTtlMs)
    }
}
