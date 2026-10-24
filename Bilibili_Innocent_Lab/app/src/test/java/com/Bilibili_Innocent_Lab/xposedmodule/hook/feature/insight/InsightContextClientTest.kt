package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InsightContextClientTest {
    @Test fun `subtitle URL cannot redirect data access to an arbitrary host`() {
        assertEquals("https://aisubtitle.hdslb.com/file.json?sign=abc", InsightContextClient.subtitleUrl("//aisubtitle.hdslb.com/file.json?sign=abc"))
        assertEquals("https://aisubtitle.hdslb.com/a?sign=a%2Fb", InsightContextClient.subtitleUrl("http://aisubtitle.hdslb.com/a?sign=a%2Fb"))
        assertEquals("https://aisubtitle.hdslb.com/a", InsightContextClient.subtitleUrl("http://aisubtitle.hdslb.com:80/a"))
        for (url in listOf("http://aisubtitle.hdslb.com:8080/a", "https://hdslb.com.evil.test/a", "https://localhost/a",
            "https://evil.test/a", "https://key@aisubtitle.hdslb.com/a", "https://aisubtitle.hdslb.com:8443/a")) {
            assertNull(InsightContextClient.subtitleUrl(url))
        }
    }
    @Test fun `subtitle parsing retains timestamps and discards malformed cues`() {
        val cues = InsightContextClient.parseSubtitle("""{"body":[{"from":12.5,"to":20,"content":"后半"},{"from":0,"to":4,"content":"开头"},{"from":-1,"to":3,"content":"无效"},{"from":30,"to":40,"content":"越界"},{"from":5,"to":5,"content":"空区间"}]}""", 30)
        assertEquals(listOf(0.0, 12.5), cues.map { it.start })
        assertEquals("开头", cues.first().text)
    }
    @Test fun `context fallback is explicit and current CID selects the correct video part`() {
        val video = InsightVideo("BV1xx411c7mD", 2)
        val client = InsightContextClient(InsightHostSnapshot(), subtitleWaitMs = 0) { url -> when {
            "web-interface/view" in url -> """{"code":0,"data":{"bvid":"BV1xx411c7mD","aid":1,"title":"title","desc":"desc","pages":[{"cid":1,"duration":60},{"cid":2,"duration":1200}]}}"""
            "x/player/v2" in url -> """{"code":0,"data":{}}"""
            "x/v2/reply" in url -> """{"code":0,"data":{"replies":[{"content":{"message":"观点"}}]}}"""
            "comment.bilibili.com" in url -> """<i><d p="1,2">弹幕&amp;内容</d></i>"""
            else -> error("unexpected URL")
        } }
        val context = client.load(video)
        assertEquals(1200, context.duration); assertFalse(context.timed)
        assertTrue("subtitle" in context.missing)
        assertEquals(listOf("观点"), context.comments)
        assertEquals(listOf("弹幕&内容"), context.danmaku)
    }
    @Test fun `captured host subtitle is preferred to unavailable public player metadata`() {
        val snapshot = InsightHostSnapshot()
        snapshot.subtitles(2, listOf(InsightSubtitleSource(2, "https://aisubtitle.hdslb.com/sub.json", "zh-CN")))
        val client = InsightContextClient(snapshot) { url -> when {
            "web-interface/view" in url -> """{"code":0,"data":{"bvid":"BV1xx411c7mD","aid":1,"title":"title","pages":[{"cid":2,"duration":1200}]}}"""
            "hdslb.com" in url -> """{"body":[{"from":0,"to":10,"content":"内容"}]}"""
            else -> error("public metadata blocked")
        } }
        assertTrue(client.load(InsightVideo("BV1xx411c7mD", 2)).timed)
    }
    @Test fun `public metadata failure falls back to the matching host video part only`() {
        val video = InsightVideo("BV1xx411c7mD", 2)
        val snapshot = InsightHostSnapshot()
        snapshot.metadata(InsightMetadata(video, 123, "宿主标题", 1200))
        snapshot.comments(123, listOf("宿主评论"))
        val client = InsightContextClient(snapshot, subtitleWaitMs = 0) { error("public endpoint unavailable") }
        val context = client.load(video)
        assertEquals("宿主标题", context.title)
        assertEquals(listOf("宿主评论"), context.comments)
        assertTrue(context.missing.contains("public-metadata-and-description"))
        assertTrue(runCatching { client.load(video.copy(cid = 3)) }.isFailure)
    }
    @Test fun `late subtitles wake the bounded waiter without blocking the host writer`() {
        val snapshot = InsightHostSnapshot()
        val expected = listOf(InsightSubtitleSource(2, "https://aisubtitle.hdslb.com/sub.json", "zh-CN"))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val waiting = pool.submit<List<InsightSubtitleSource>> { snapshot.awaitSubtitles(2, 2000) }
            snapshot.subtitles(9, expected.map { it.copy(cid = 9) })
            snapshot.subtitles(2, emptyList())
            snapshot.subtitles(2, expected)
            assertEquals(expected, waiting.get(1, TimeUnit.SECONDS))
            snapshot.subtitles(2, emptyList())
            assertEquals(expected, snapshot.awaitSubtitles(2, 0))
            assertTrue(snapshot.awaitSubtitles(3, 0).isEmpty())
        } finally { pool.shutdownNow() }
    }
}
