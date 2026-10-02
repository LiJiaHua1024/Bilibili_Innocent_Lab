package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kpurifyfixture.Content
import kpurifyfixture.MainListReply
import kpurifyfixture.ReplyInfo
import kpurifyfixture.SubjectControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentKotlinPurifierTest {

    private val search = "bilibili://search?keyword=x&from=appcommentline_search"
    private val events = mutableListOf<Pair<String, FeatureRuntimeStage>>()

    private fun purifier(
        links: Boolean = true,
        emptyPage: Boolean = true,
        payloads: Map<String, String> = mapOf("Qoe" to "comments_qoe_removed", "OperationV2" to "comments_operations_removed")
    ) = CommentKotlinPurifier(
        ReplyInfo::class.java,
        isSearchUrl = if (links) { value -> value == search } else null,
        clearEmptyPage = emptyPage,
        payloads = payloads
    ) { capability, stage, _ -> events += capability to stage }

    private fun content(vararg urls: Pair<String, Any>) = Content(linkedMapOf(*urls))

    @Test
    fun `an unresolvable shape is reported once, and only when the top level needs it`() {
        val skipped = mutableListOf<String>()
        fun build(payloads: Map<String, String>, links: ((Any?) -> Boolean)? = null) =
            CommentKotlinPurifier(
                MainListReply::class.java,
                isSearchUrl = links,
                clearEmptyPage = false,
                payloads = payloads,
                logSkip = { reason -> skipped += reason }
            ) { _, _, _ -> }

        val message = MainListReply(listOf(ReplyInfo(content())), false, false, null)

        // 顶层确实有事要做（要清 Qoe），却读不到字段：净化空转，必须留痕，且每个类只报一次。
        val topLevel = build(mapOf("NoSuchField" to "comments_qoe_removed"))
        assertSame(message, topLevel.purify(message))
        topLevel.purify(message)
        assertEquals(1, skipped.size)
        assertTrue(skipped.single().startsWith("top-shape:"))

        // 只开着"摘搜索跳转"时，顶层形状用不上（摘链接走 rewriter）：解析不出来是正常的，
        // 不能报成"读不到"，否则是一条假警报。
        skipped.clear()
        build(emptyMap(), links = { it == search }).purify(message)
        assertTrue("skipped=$skipped", skipped.isEmpty())
    }

    @Test
    fun `search links are stripped from replies and sub replies only`() {
        val child = ReplyInfo(content("词" to search))
        val reply = ReplyInfo(content("关键词" to search, "视频" to "bilibili://video/1"), child)
        val clean = ReplyInfo(content("视频" to "bilibili://video/2"))
        val message = MainListReply(listOf(reply, clean), false, false, null)

        val rebuilt = purifier().purify(message) as MainListReply

        val first = rebuilt.repliesList[0]
        assertEquals(setOf("视频"), first.content.urlsMap.keys)
        assertTrue(first.repliesList.single().content.urlsMap.isEmpty())
        assertSame(clean, rebuilt.repliesList[1]) // 没有搜索跳转的评论不复制
        assertEquals(2, message.repliesList[0].content.urlsMap.size) // 原响应不变
        assertTrue("comments_search_links_removed" to FeatureRuntimeStage.APPLIED in events)
    }

    @Test
    fun `optional payloads and the empty page guide are cleared`() {
        val message = MainListReply(emptyList(), true, true, SubjectControl(true))

        val rebuilt = purifier().purify(message) as MainListReply

        assertFalse(rebuilt.hasQoe())
        assertTrue(rebuilt.hasOperation()) // 没开的项不动（这里只开了 OperationV2，夹具没有它，跳过）
        assertFalse(rebuilt.subjectControl.hasEmptyPage())
        assertTrue("comments_qoe_removed" to FeatureRuntimeStage.APPLIED in events)
        assertTrue("comments_empty_guide_removed" to FeatureRuntimeStage.APPLIED in events)
    }

    @Test
    fun `nothing to purify keeps the very same message`() {
        val message = MainListReply(listOf(ReplyInfo(content("视频" to "bilibili://video/1"))), false, false, SubjectControl(false))
        assertSame(message, purifier().purify(message))
    }

    @Test
    fun `disabled sub features are left alone`() {
        val message = MainListReply(listOf(ReplyInfo(content("词" to search))), true, false, SubjectControl(true))
        val rebuilt = purifier(links = false, emptyPage = false, payloads = emptyMap()).purify(message)
        assertSame(message, rebuilt)
    }

    @Test
    fun `request extra gains disable underline without overriding the host`() {
        assertEquals(true, JSONObject(CommentKotlinPurifier.withDisableUnderline(null)!!).getBoolean("disable_underline"))
        val merged = JSONObject(CommentKotlinPurifier.withDisableUnderline("""{"spmid":"a.b"}""")!!)
        assertEquals("a.b", merged.getString("spmid"))
        assertTrue(merged.getBoolean("disable_underline"))
        // 宿主自己设过（哪怕是 false）就不覆盖；不是 JSON 对象就不动。
        assertNull(CommentKotlinPurifier.withDisableUnderline("""{"disable_underline":false}"""))
        assertNull(CommentKotlinPurifier.withDisableUnderline("not json"))
    }
}
