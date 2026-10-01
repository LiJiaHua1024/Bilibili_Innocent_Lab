package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kmossfixture.MainListReply
import kmossfixture.ReplyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinMossChannelTest {

    private fun rewriter(blocked: Set<String>, seen: MutableList<List<String>> = mutableListOf()) =
        ProtobufReplyTreeRewriter(ReplyInfo::class.java) { replies ->
            seen += replies.map { (it as ReplyInfo).text }
            replies.filter { (it as ReplyInfo).text in blocked }.toSet()
        }

    @Test
    fun `raw scope is reentrant and thread local`() {
        assertFalse(KotlinMossChannel.isRaw())
        KotlinMossChannel.raw {
            assertTrue(KotlinMossChannel.isRaw())
            KotlinMossChannel.raw { assertTrue(KotlinMossChannel.isRaw()) }
            assertTrue(KotlinMossChannel.isRaw())
            var other = true
            Thread { other = KotlinMossChannel.isRaw() }.apply { start(); join() }
            assertFalse(other)
        }
        assertFalse(KotlinMossChannel.isRaw())
    }

    @Test
    fun `nothing blocked keeps the very same message`() {
        val reply = MainListReply(listOf(ReplyInfo.of("a", ReplyInfo.of("a1")), ReplyInfo.of("b")), null, null)
        val result = rewriter(emptySet()).rewrite(reply)
        assertSame(reply, result.message)
        assertEquals(0, result.removed)
    }

    @Test
    fun `blocked replies sub replies and the top slot are removed`() {
        val keep = ReplyInfo.of("keep", ReplyInfo.of("child-ok"), ReplyInfo.of("child-bad"))
        val reply = MainListReply(listOf(keep, ReplyInfo.of("bad")), ReplyInfo.of("bad-top"), null)
        val seen = mutableListOf<List<String>>()

        val result = rewriter(setOf("bad", "child-bad", "bad-top"), seen).rewrite(reply)
        val rebuilt = result.message as MainListReply

        assertEquals(3, result.removed)
        assertEquals(listOf("keep"), rebuilt.repliesList.map { it.text })
        assertEquals(listOf("child-ok"), rebuilt.repliesList.single().repliesList.map { it.text })
        assertFalse(rebuilt.hasUpTop())
        // 同一层整批判定：主楼一批、子回复一批、置顶位一批。
        assertTrue(listOf("keep", "bad") in seen)
        assertTrue(listOf("child-ok", "child-bad") in seen)
        assertTrue(listOf("bad-top") in seen)
        // 原响应不被改动。
        assertEquals(2, reply.repliesList.size)
        assertTrue(reply.hasUpTop())
    }

    @Test
    fun `a non removable single only has its children filtered`() {
        // DetailListReply.root 这类单条字段：自己不删，只删它下面命中的子回复。
        val root = ReplyInfo.of("bad", ReplyInfo.of("bad"), ReplyInfo.of("ok"))
        val reply = MainListReply(emptyList(), null, root)

        val rebuilt = rewriter(setOf("bad")).rewrite(reply).message as MainListReply

        assertEquals("bad", rebuilt.root.text)
        assertEquals(listOf("ok"), rebuilt.root.repliesList.map { it.text })
    }

    @Test
    fun `getter filters are bypassed while reading raw lists`() {
        // 模拟 Java getter 过滤：非 raw 时读到的是过滤后的列表。
        var filteredReads = 0
        val reply = MainListReply(listOf(ReplyInfo.of("bad"), ReplyInfo.of("ok")), null, null)
        val rewriter = ProtobufReplyTreeRewriter(ReplyInfo::class.java) { replies ->
            if (!KotlinMossChannel.isRaw()) filteredReads++
            replies.filter { (it as ReplyInfo).text == "bad" }.toSet()
        }
        val result = rewriter.rewrite(reply)
        // decide 本身在 raw 作用域外调用（判据读取不受影响），但列表是在 raw 里读的。
        assertEquals(1, result.removed)
        assertTrue(filteredReads > 0)
    }
}
