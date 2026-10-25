package com.Bilibili_Innocent_Lab.xposedmodule.runtime

internal class HostAdmissionRetryPolicy(startedAtMs: Long, private val removeQueued: (Runnable) -> Unit = {}) {
    val deadline = startedAtMs + WINDOW_MS
    var attempts = 0
        private set
    private var generation = 0L
    private var closed = false
    private var delivery: Runnable? = null

    @Synchronized fun nextDelay(nowMs: Long): Long? {
        if (closed || attempts >= MAX_ATTEMPTS || nowMs >= deadline) return null
        val delay = DELAYS_MS[attempts]
        return delay.takeIf { nowMs + it < deadline }
    }

    @Synchronized fun begin(nowMs: Long): Long? {
        if (closed || attempts >= MAX_ATTEMPTS || nowMs >= deadline) return null
        attempts += 1
        return ++generation
    }

    @Synchronized fun accepts(token: Long, nowMs: Long): Boolean = !closed && token == generation && nowMs < deadline

    // 排队与撤销共用锁，避免暂停清理后工作线程才把旧回执放进主线程队列。
    @Synchronized fun postDelivery(token: Long, nowMs: Long, task: Runnable, post: (Runnable) -> Boolean): Boolean {
        if (!accepts(token, nowMs)) return false
        removeDelivery()
        delivery = task
        return runCatching { post(task) }.getOrDefault(false).also { if (!it) delivery = null }
    }

    @Synchronized fun takeDelivery(token: Long, nowMs: Long, task: Runnable): Boolean {
        if (delivery !== task) return false
        delivery = null
        return accepts(token, nowMs)
    }

    @Synchronized fun invalidate() { generation += 1L; removeDelivery() }

    @Synchronized fun close() { closed = true; invalidate() }

    private fun removeDelivery() {
        val queued = delivery
        delivery = null
        if (queued != null) runCatching { removeQueued(queued) }
    }

    companion object {
        const val WINDOW_MS = 20_000L
        const val MAX_ATTEMPTS = 4
        private val DELAYS_MS = longArrayOf(0L, 1_000L, 2_000L, 4_000L)
    }
}
