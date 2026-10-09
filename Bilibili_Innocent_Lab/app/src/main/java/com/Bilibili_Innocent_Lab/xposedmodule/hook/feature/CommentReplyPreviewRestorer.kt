package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class CommentReplyPreviewKey(val oid: Long, val type: Long, val root: Long, val count: Long)

internal interface CommentReplyPreviewPort {
    val replyInfoClass: Class<*>
    fun missingPreviewKey(reply: Any): CommentReplyPreviewKey?
    fun request(key: CommentReplyPreviewKey, complete: (Result<List<Any>>) -> Unit)
    fun withReplies(reply: Any, replies: List<Any>): Any
}

/**
 * 主列表常只带回复计数，没有 replies。仅补齐这些主楼的前三条预览，不翻页。
 * 调用点必须是后台数据转换，整页共用等待预算；绝不在 UI 线程或 MOSS 收包线程等待。
 * 请求最多四个并发，缓存有时间和容量上限。超时/失败保持原来的“共 N 条回复”入口。
 */
internal class CommentReplyPreviewRestorer(
    private val port: CommentReplyPreviewPort,
    private val waitMillis: Long = 2_500,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val observed: (Int) -> Unit = {},
    private val applied: (Int) -> Unit = {},
    private val failure: (Throwable) -> Unit = {}
) {
    private class Entry(val startedAt: Long, val future: CompletableFuture<List<Any>> = CompletableFuture())
    private val cache = LinkedHashMap<CommentReplyPreviewKey, Entry>(32, 0.75f, true)
    private val permits = Semaphore(MAX_CONCURRENT)

    fun restore(message: Any): Any {
        val keys = LinkedHashSet<CommentReplyPreviewKey>()
        ProtobufReplyTreeVisitor(port.replyInfoClass) { reply ->
            if (keys.size < MAX_ROOTS_PER_PAGE) port.missingPreviewKey(reply)?.let(keys::add)
        }.visit(message)
        if (keys.isEmpty()) return message
        observed(keys.size)
        val deadline = nowMillis() + waitMillis
        val pending = CountDownLatch(keys.size)
        val queue = keys.toList()
        val next = AtomicInteger()
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        val entries = java.util.concurrent.ConcurrentHashMap<CommentReplyPreviewKey, Entry>()

        fun startNext() {
            if (!accepting.get() || nowMillis() >= deadline) return
            val index = next.getAndIncrement()
            if (index >= queue.size) return
            val key = queue[index]
            val (entry, fresh) = synchronized(cache) {
                val existing = cache[key]?.takeIf { nowMillis() - it.startedAt < CACHE_MILLIS }
                if (existing != null) existing to false else {
                    val created = Entry(nowMillis())
                    cache[key] = created
                    while (cache.size > MAX_CACHED_ROOTS) cache.remove(cache.keys.first())
                    created to true
                }
            }
            entries[key] = entry
            entry.future.whenComplete { _, _ ->
                HostThreadGuard.run("comment_preview_complete") {
                    pending.countDown()
                    startNext()
                }
            }
            if (!fresh) return
            if (!permits.tryAcquire()) {
                synchronized(cache) { if (cache[key] === entry) cache.remove(key) }
                entry.future.complete(emptyList())
                return
            }
            val finished = java.util.concurrent.atomic.AtomicBoolean()
            fun complete(result: Result<List<Any>>) {
                if (!finished.compareAndSet(false, true)) return
                permits.release()
                result.exceptionOrNull()?.let { runCatching { failure(it) } }
                val replies = result.getOrDefault(emptyList()).take(MAX_PREVIEW_REPLIES)
                // 空响应不负缓存，下一次刷新可以重试。
                if (replies.isEmpty()) synchronized(cache) { if (cache[key] === entry) cache.remove(key) }
                entry.future.complete(replies)
            }
            runCatching { port.request(key, ::complete) }.onFailure { complete(Result.failure(it)) }
        }

        repeat(minOf(MAX_CONCURRENT, keys.size)) { startNext() }
        try {
            pending.await((deadline - nowMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            accepting.set(false)
        }
        // 回调迟到也只写有界缓存，不重发宿主响应、不持有页面或 Activity。
        var changed = 0
        val rewriter = ProtobufReplyTreeRewriter(port.replyInfoClass, mapReply = { reply ->
            val key = port.missingPreviewKey(reply)
            val replies = key?.let { entries[it]?.future?.getNow(emptyList()) }.orEmpty()
            if (replies.isEmpty()) reply else port.withReplies(reply, replies).also { changed++ }
        }, decide = { emptySet() })
        val updated = rewriter.rewrite(message).message
        if (changed > 0) applied(changed)
        return updated
    }

    companion object {
        const val MAX_PREVIEW_REPLIES = 3
        private const val MAX_CONCURRENT = 4
        private const val MAX_ROOTS_PER_PAGE = 32
        private const val MAX_CACHED_ROOTS = 192
        private const val CACHE_MILLIS = 60_000L
    }
}
