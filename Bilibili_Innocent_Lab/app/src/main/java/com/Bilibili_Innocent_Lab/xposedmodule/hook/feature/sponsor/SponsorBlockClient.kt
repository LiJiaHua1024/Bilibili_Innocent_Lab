package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal class SponsorRequestCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    fun attach(value: HttpURLConnection) {
        connection = value
        if (cancelled.get()) value.disconnect()
    }
    // 取消可能来自主线程；不在调用方执行可能等待网络锁的 disconnect。
    fun cancel() { cancelled.set(true) }
    fun check() { check(!cancelled.get() && !Thread.currentThread().isInterrupted) { "cancelled" } }
    fun detach() { connection = null }
}

internal fun interface SponsorSegmentSource {
    fun fetch(video: SponsorVideoId, cancellation: SponsorRequestCancellation): SponsorFetchResult
}

/** 独立网络连接，既不复用宿主 Cookie，也不发送 BVID/CID、用户 ID 或跳过统计。 */
internal class SponsorBlockClient(private val version: String) : SponsorSegmentSource {
    override fun fetch(video: SponsorVideoId, cancellation: SponsorRequestCancellation): SponsorFetchResult {
        cancellation.check()
        val connection = (URL(endpoint(video)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 5_000
            readTimeout = 5_000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Bilibili-Innocent-Lab/$version")
            setRequestProperty("Origin", "https://github.com/jichuo1/Bilibili_Innocent_Lab")
            setRequestProperty("X-EXT-VERSION", version)
        }
        cancellation.attach(connection)
        try {
            cancellation.check()
            val status = connection.responseCode
            if (status == 404) return SponsorFetchResult.Available(emptyList())
            if (status != 200) return SponsorFetchResult.Unavailable(if (status == 429) "rate-limited" else "http-error")
            val body = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    cancellation.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(body.size() + count <= MAX_RESPONSE_BYTES) { "response-budget" }
                    body.write(buffer, 0, count)
                }
            }
            return SponsorFetchResult.Available(SponsorSegmentParser.parse(body.toString("UTF-8"), video))
        } catch (_: Exception) {
            return SponsorFetchResult.Unavailable("network-or-response")
        } finally {
            cancellation.detach()
            connection.disconnect()
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        fun endpoint(video: SponsorVideoId): String = "https://www.bsbsb.top/api/skipSegments/${video.hashPrefix}"
    }
}
