package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 所有网络工作有界；请求身份与 flight 对象一起核验，取消后迟到结果不能写缓存或覆盖新请求。 */
internal class SponsorSegmentRepository(
    private val source: SponsorSegmentSource,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMs: Long = 8_000
) : AutoCloseable {
    internal fun interface Ticket { fun cancel() }
    private data class Cache(val expires: Long, val result: SponsorFetchResult.Available)
    private class Flight {
        val cancellation = SponsorRequestCancellation()
        val listeners = LinkedHashMap<Any, (SponsorFetchResult) -> Unit>()
        var task: Future<*>? = null
        var timeout: Future<*>? = null
    }
    private val lock = Any()
    private val cache = LinkedHashMap<SponsorVideoId, Cache>(16, .75f, true)
    private val flights = HashMap<SponsorVideoId, Flight>()
    private val executor = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "BIL-SponsorBlock-fetch").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    private val timer = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "BIL-SponsorBlock-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var closed = false
    private var rateLimitedUntil = 0L
    init { require(timeoutMs > 0) }

    /** 回调可能内联；调用方须在 UI/播放器执行前调度到主线程并检查会话代次。 */
    fun lookup(video: SponsorVideoId, listener: (SponsorFetchResult) -> Unit): Ticket {
        val key = Any()
        var immediate: SponsorFetchResult? = null
        val flight = synchronized(lock) {
            val hit = cache[video]?.takeIf { it.expires > now() }
            if (closed) { immediate = SponsorFetchResult.Unavailable("closed"); null }
            else if (hit != null) { immediate = hit.result; null }
            else if (rateLimitedUntil > now()) {
                immediate = SponsorFetchResult.Unavailable("rate-limited", (rateLimitedUntil - now()).coerceAtLeast(1)); null
            }
            else {
                cache.remove(video)
                val existing = flights[video]
                if (existing != null) {
                    if (existing.listeners.size >= 8) { immediate = SponsorFetchResult.Unavailable("busy"); null }
                    else existing.also { it.listeners[key] = listener }
                } else if (flights.size >= 10) {
                    immediate = SponsorFetchResult.Unavailable("busy"); null
                } else Flight().also { value ->
                    value.listeners[key] = listener
                    flights[video] = value
                    try {
                        value.timeout = timer.schedule({ finish(video, value, SponsorFetchResult.Unavailable("timeout", 2_000), true) }, timeoutMs, TimeUnit.MILLISECONDS)
                        value.task = executor.submit {
                            val result = runCatching { source.fetch(video, value.cancellation) }
                                .getOrElse { SponsorFetchResult.Unavailable("network-or-response", 2_000) }
                            finish(video, value, result, false)
                        }
                    } catch (_: RejectedExecutionException) {
                        value.timeout?.cancel(false)
                        flights.remove(video)
                        immediate = SponsorFetchResult.Unavailable("busy")
                    }
                }.takeIf { immediate == null }
            }
        }
        immediate?.let { runCatching { listener(it) } }
        return Ticket {
            if (flight != null) synchronized(lock) {
                if (flights[video] === flight) {
                    flight.listeners.remove(key)
                    if (flight.listeners.isEmpty()) {
                        flights.remove(video)
                        stop(flight)
                    }
                }
            }
        }
    }

    private fun finish(video: SponsorVideoId, flight: Flight, result: SponsorFetchResult, abort: Boolean) {
        val listeners = synchronized(lock) {
            if (flights[video] !== flight) return
            flights.remove(video)
            val callbacks = flight.listeners.values.toList()
            flight.timeout?.cancel(false)
            if (abort) stop(flight)
            if (result is SponsorFetchResult.Unavailable && result.reason == "rate-limited") rateLimitedUntil = now() + 60_000
            if (!closed && result is SponsorFetchResult.Available) {
                val stable = result.copy(segments = result.segments.toList())
                cache[video] = Cache(now() + if (stable.segments.isEmpty()) 600_000 else 3_600_000, stable)
                while (cache.size > 128 || cache.values.sumOf { it.result.segments.size } > 8_192) cache.remove(cache.keys.first())
            }
            flight.listeners.clear()
            callbacks
        }
        listeners.forEach { runCatching { it(result) } }
    }

    private fun stop(flight: Flight) {
        flight.timeout?.cancel(false)
        flight.task?.cancel(true)
        (flight.task as? Runnable)?.let(executor::remove)
        flight.cancellation.cancel()
        flight.listeners.clear()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            flights.values.forEach(::stop)
            flights.clear()
            cache.clear()
        }
        executor.shutdownNow()
        timer.shutdownNow()
    }
}
