package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentHttpTransport
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelClient
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelSource
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.SemanticSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InsightRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val source = AgentModelSource(1, "https://example.test/v1", "secret", "model")
    private val video = InsightVideo("BV1xx411c7mD", 123)
    private val input = InsightContext(video, 900, "title", "desc", listOf(InsightCue(0.0, 900.0, "content")))
    private val summary = """{"overview":"overview","points":["[[t:10]] point"]}"""
    private fun client(count: AtomicInteger, content: String = summary) = AgentModelClient(AgentHttpTransport { _, body, _, _ ->
        count.incrementAndGet()
        val request = JSONObject(body.toString(Charsets.UTF_8))
        assertFalse(request.has("tools"))
        JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
            JSONObject().put("role", "assistant").put("content", content)))).toString()
    })

    @Test fun `second opening loads summary from disk without context or model requests`() {
        val root = temporary.newFolder(); val posts = AtomicInteger(); val contexts = AtomicInteger()
        val first = InsightRepository(source, InsightCache(root), { contexts.incrementAndGet(); input }, client(posts))
        val expected = first.generate(video, InsightMode.SUMMARY)
        val reopened = InsightRepository(source, InsightCache(root), { error("must not fetch context") }, client(posts))
        assertEquals(expected, reopened.generate(video, InsightMode.SUMMARY))
        assertEquals(1, posts.get()); assertEquals(1, contexts.get())
        assertFalse(root.listFiles()!!.single().readText().contains("secret"))
    }
    @Test fun `concurrent duplicate summaries share the one successful request`() {
        val posts = AtomicInteger()
        val repo = InsightRepository(source, InsightCache(temporary.newFolder()), { input }, client(posts))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map { pool.submit<InsightCache.Entry> { repo.generate(video, InsightMode.SUMMARY) } }
            assertEquals(1, futures.map { it.get() }.distinct().size)
            assertEquals(1, posts.get())
        } finally { pool.shutdownNow() }
    }
    @Test fun `cache keys isolate video parts models and output modes`() {
        val key = InsightCache.key(video, source, InsightMode.SUMMARY)
        assertNotEquals(key, InsightCache.key(video.copy(cid = 124), source, InsightMode.SUMMARY))
        assertNotEquals(key, InsightCache.key(video, source.copy(model = "other"), InsightMode.SUMMARY))
        assertNotEquals(key, InsightCache.key(video, source, InsightMode.COMMENTS))
    }
    @Test fun `expired corrupt and future dated cache entries are not used`() {
        var now = 1_000_000L
        val root = temporary.newFolder(); val cache = InsightCache(root) { now }
        val entry = InsightCache.Entry(InsightOutput("sample"), 900, true)
        cache.write("key", entry)
        assertEquals(entry, cache.read("key", InsightMode.COMMENTS))
        now += 60 * 60_000L + 1000
        assertNull(cache.read("key", InsightMode.COMMENTS))
        cache.write("key", entry); root.listFiles()!!.single().writeText("broken")
        assertNull(InsightCache(root) { now }.read("key", InsightMode.SUMMARY))
        cache.write("key", entry); now -= 10000
        assertNull(cache.read("key", InsightMode.SUMMARY))
    }
    @Test fun `failed model output is never cached and retry can succeed`() {
        val posts = AtomicInteger(); val root = temporary.newFolder()
        val invalid = InsightRepository(source, InsightCache(root), { input }, client(posts, """{"answer":"wrong format"}"""))
        assertTrue(runCatching { invalid.generate(video, InsightMode.SUMMARY) }.isFailure)
        assertTrue(root.listFiles().orEmpty().isEmpty())
        val retry = InsightRepository(source, InsightCache(root), { input }, client(posts))
        assertTrue(retry.generate(video, InsightMode.SUMMARY).output.text.contains("overview"))
        assertEquals(2, posts.get())
    }
    @Test fun `missing comments and subtitles cause no billable chapter or comment requests`() {
        val posts = AtomicInteger()
        val repo = InsightRepository(source, InsightCache(temporary.newFolder()), { input.copy(cues = emptyList()) }, client(posts))
        assertTrue(runCatching { repo.generate(video, InsightMode.COMMENTS) }.isFailure)
        assertTrue(runCatching { repo.generate(video, InsightMode.CHAPTERS) }.isFailure)
        assertEquals(0, posts.get())
    }
    @Test fun `ask uses dialogue context and is not persisted`() {
        val posts = AtomicInteger(); val root = temporary.newFolder()
        val repo = InsightRepository(source, InsightCache(root), { input }, client(posts, """{"answer":"[[t:10]] explanation"}"""))
        repo.generate(video, InsightMode.ASK, "question", listOf("old question" to "old answer"))
        assertEquals(1, posts.get()); assertTrue(root.listFiles().orEmpty().isEmpty())
    }
    @Test fun `decision sources are excluded and existing chat configuration is reused`() {
        val decision = SemanticSource.from(1, "key", "", "jev", "")!!
        val chat = SemanticSource.from(2, "chat-key", "https://example.test/v1", "openai", "chat-model")!!
        assertNull(InsightSources.resolve(listOf(decision)))
        assertEquals(2, InsightSources.resolve(listOf(chat, decision))?.index)
        assertEquals("chat-model", InsightSources.resolve(listOf(decision, chat))?.model)
    }
    @Test fun `cache key does not persist credentials embedded in an endpoint query`() {
        val configured = source.copy(endpoint = "https://example.test/v1?api_key=endpoint-token")
        assertFalse(InsightCache.key(video, configured, InsightMode.SUMMARY).contains("endpoint-token"))
    }
    @Test fun `cold process restores only chapters belonging to the current model source`() {
        val root = temporary.newFolder(); val cache = InsightCache(root)
        val entry = InsightCache.Entry(InsightOutput("", listOf(InsightChapter(0, "a"), InsightChapter(300, "b"), InsightChapter(600, "c"))), 900, true)
        cache.write(InsightCache.key(video, source, InsightMode.CHAPTERS), entry)
        cache.write(InsightCache.key(video.copy(cid = 999), source.copy(model = "other"), InsightMode.CHAPTERS), entry)
        cache.write(InsightCache.key(video, source, InsightMode.SUMMARY), InsightCache.Entry(InsightOutput("summary"), 900, true))
        val restored = mutableListOf<InsightVideo>()
        InsightCache(root).restoreChapters(source) { id, value -> restored += id; assertEquals(entry, value) }
        assertEquals(listOf(video), restored)
    }
    @Test fun `cached summary is readable while another video holds the generation lock`() {
        val cache = InsightCache(temporary.newFolder())
        val entry = InsightCache.Entry(InsightOutput("cached"), 900, true)
        cache.write(InsightCache.key(video, source, InsightMode.SUMMARY), entry)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val repo = InsightRepository(source, cache, {
            entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); input.copy(video = it)
        }, client(AtomicInteger()))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val generation = pool.submit<InsightCache.Entry> { repo.generate(video.copy(cid = 999), InsightMode.SUMMARY) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reading = pool.submit<InsightCache.Entry?> { repo.cached(video, InsightMode.SUMMARY) }
            assertEquals(entry, reading.get(2, TimeUnit.SECONDS))
            release.countDown(); generation.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
