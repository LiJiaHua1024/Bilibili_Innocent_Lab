package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CommentReplyPreviewRestorerTest {
    class Reply(
        val id: Long,
        val count: Long = 0,
        val root: Long = 0,
        val text: String = "content",
        private val replies: List<Reply> = emptyList()
    ) {
        fun getRepliesList(): List<Reply> = replies
        companion object { @JvmStatic fun newBuilder(source: Reply) = Builder(source) }
        class Builder(private val source: Reply) {
            private val replies = source.replies.toMutableList()
            fun clearReplies() = apply { replies.clear() }
            fun addAllReplies(values: Iterable<Reply>) = apply { replies.addAll(values) }
            fun build() = Reply(source.id, source.count, source.root, source.text, replies.toList())
        }
    }
    class Page(private val replies: List<Reply>, private val top: Reply? = null, val title: String = "page") {
        fun getRepliesList(): List<Reply> = replies
        fun getUpTop() = top
        fun hasUpTop() = top != null
        companion object { @JvmStatic fun newBuilder(source: Page) = Builder(source) }
        class Builder(private val source: Page) {
            private val replies = source.replies.toMutableList()
            private var top = source.top
            fun clearReplies() = apply { replies.clear() }
            fun addAllReplies(values: Iterable<Reply>) = apply { replies.addAll(values) }
            fun setUpTop(value: Reply) = apply { top = value }
            fun clearUpTop() = apply { top = null }
            fun build() = Page(replies.toList(), top, source.title)
        }
    }
    private class Port(
        val fetch: (CommentReplyPreviewKey, (Result<List<Any>>) -> Unit) -> Unit
    ) : CommentReplyPreviewPort {
        override val replyInfoClass = Reply::class.java
        val requests = mutableListOf<CommentReplyPreviewKey>()
        override fun missingPreviewKey(reply: Any): CommentReplyPreviewKey? {
            val value = reply as Reply
            return if (value.root == 0L && value.count > 0 && value.getRepliesList().isEmpty())
                CommentReplyPreviewKey(10, 1, value.id, value.count) else null
        }
        override fun request(key: CommentReplyPreviewKey, complete: (Result<List<Any>>) -> Unit) {
            synchronized(requests) { requests += key }
            fetch(key, complete)
        }
        override fun withReplies(reply: Any, replies: List<Any>): Any = Reply.newBuilder(reply as Reply)
            .addAllReplies(replies.map { it as Reply }).build()
    }

    @Test fun singleReplyIsInsertedWithoutChangingTheParentOrPage() {
        val parent = Reply(1, count = 1, text = "parent")
        val child = Reply(2, root = 1, text = "reply")
        val page = Page(listOf(parent), title = "original title")
        val port = Port { _, done -> done(Result.success(listOf(child))) }
        val changed = mutableListOf<Int>()
        val restored = CommentReplyPreviewRestorer(port, applied = changed::add).restore(page) as Page
        assertEquals(listOf(child), restored.getRepliesList().single().getRepliesList())
        assertEquals("parent", restored.getRepliesList().single().text)
        assertEquals("original title", restored.title)
        assertEquals(1L, restored.getRepliesList().single().count)
        assertTrue(parent.getRepliesList().isEmpty())
        assertEquals(listOf(1), changed)
        // 与宿主生成“更多回复”入口的条件相同，只有一条回复时不再生成计数入口。
        assertFalse(restored.getRepliesList().single().count > restored.getRepliesList().single().getRepliesList().size)
    }

    @Test fun previewIsLimitedToThreeAndTheMoreRepliesCountIsKept() {
        val port = Port { key, done -> done(Result.success((1L..8L).map { Reply(100 + it, root = key.root) })) }
        val restored = CommentReplyPreviewRestorer(port).restore(Page(listOf(Reply(1, 8)))) as Page
        val parent = restored.getRepliesList().single()
        assertEquals(3, parent.getRepliesList().size)
        assertEquals(8L, parent.count)
        assertTrue(parent.count > parent.getRepliesList().size)
    }

    @Test fun existingPreviewsAndCommentsWithoutRepliesDoNotFetchOrCopy() {
        val page = Page(listOf(Reply(1), Reply(2, 1, replies = listOf(Reply(3, root = 2))), Reply(4, 1, root = 2)))
        val port = Port { _, _ -> error("must not request") }
        assertSame(page, CommentReplyPreviewRestorer(port).restore(page))
        assertTrue(port.requests.isEmpty())
    }

    @Test fun pinnedCommentsAndDuplicateRootEntriesShareOneRequest() {
        val root = Reply(1, 1)
        val child = Reply(2, root = 1)
        val port = Port { _, done -> done(Result.success(listOf(child))) }
        val restored = CommentReplyPreviewRestorer(port).restore(Page(listOf(root), root)) as Page
        assertEquals(listOf(child), restored.getUpTop()!!.getRepliesList())
        assertEquals(listOf(child), restored.getRepliesList().single().getRepliesList())
        assertEquals(1, port.requests.size)
    }

    @Test fun failuresKeepTheOriginalResponseAndCanBeRetried() {
        var fail = true
        val port = Port { _, done ->
            if (fail) done(Result.failure(IllegalStateException("offline"))) else done(Result.success(listOf(Reply(2, root = 1))))
        }
        val restorer = CommentReplyPreviewRestorer(port)
        val page = Page(listOf(Reply(1, 1)))
        assertSame(page, restorer.restore(page))
        fail = false
        assertNotSame(page, restorer.restore(page))
        assertEquals(2, port.requests.size)
    }

    @Test fun timeoutDoesNotDelayThePageIndefinitelyAndLateRepliesOnlyFillTheCache() {
        var complete: ((Result<List<Any>>) -> Unit)? = null
        val port = Port { _, done -> complete = done }
        val restorer = CommentReplyPreviewRestorer(port, waitMillis = 10)
        val page = Page(listOf(Reply(1, 1)))
        assertSame(page, restorer.restore(page))
        complete!!(Result.success(listOf(Reply(2, root = 1))))
        assertNotSame(page, restorer.restore(page))
        assertTrue(page.getRepliesList().single().getRepliesList().isEmpty())
        assertEquals(1, port.requests.size)
    }

    @Test fun cacheExpiresAndReplyCountChangesUseANewRequest() {
        var now = 1L
        val port = Port { key, done -> done(Result.success(listOf(Reply(2, root = key.root)))) }
        val restorer = CommentReplyPreviewRestorer(port, nowMillis = { now })
        val page = Page(listOf(Reply(1, 1)))
        restorer.restore(page)
        restorer.restore(page)
        assertEquals(1, port.requests.size)
        restorer.restore(Page(listOf(Reply(1, 2))))
        assertEquals(2, port.requests.size)
        now += 60_001
        restorer.restore(page)
        assertEquals(3, port.requests.size)
    }

    @Test fun requestsRunInFourSlotsAndEachFinishedRequestStartsTheNextRoot() {
        val callbacks = java.util.concurrent.ConcurrentLinkedQueue<Pair<CommentReplyPreviewKey, (Result<List<Any>>) -> Unit>>()
        val firstFour = CountDownLatch(4)
        val port = Port { key, done -> callbacks.add(key to done); firstFour.countDown() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val restorer = CommentReplyPreviewRestorer(port)
            val result = worker.submit<Page> { restorer.restore(Page((1L..9L).map { Reply(it, 1) })) as Page }
            assertTrue(firstFour.await(1, TimeUnit.SECONDS))
            assertEquals(4, port.requests.size)
            repeat(9) {
                val (key, done) = checkNotNull(callbacks.poll())
                done(Result.success(listOf(Reply(100 + key.root, root = key.root))))
                assertTrue(callbacks.size <= 4)
            }
            assertTrue(result.get(1, TimeUnit.SECONDS).getRepliesList().all { it.getRepliesList().size == 1 })
            assertEquals(9, port.requests.size)
        } finally {
            worker.shutdownNow()
        }
    }
}
