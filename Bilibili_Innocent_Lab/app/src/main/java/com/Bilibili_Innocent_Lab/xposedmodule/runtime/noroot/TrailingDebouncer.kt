package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 尾沿去抖：连续的 [request] 只在最后一次之后静默 [delayMs] 才执行最后那个任务。
 *
 * 已经开始执行的任务不会被打断（`cancel(false)`），它之后到达的请求会再排一次，所以
 * “同步进行中设置又变了”最终仍会用最新内容再跑一遍，不会漏掉最后一次改动。
 */
internal class TrailingDebouncer(
    private val scheduler: ScheduledExecutorService,
    private val delayMs: Long
) {
    private val pending = AtomicReference<ScheduledFuture<*>?>(null)

    /**
     * @return 是否成功排入。调度器已关闭时返回 false；调用方另有生命周期同步兜底，
     * 所以这里不抛异常、不重试。
     */
    fun request(task: Runnable): Boolean {
        val scheduled = try {
            scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            return false
        }
        pending.getAndSet(scheduled)?.cancel(false)
        return true
    }
}
