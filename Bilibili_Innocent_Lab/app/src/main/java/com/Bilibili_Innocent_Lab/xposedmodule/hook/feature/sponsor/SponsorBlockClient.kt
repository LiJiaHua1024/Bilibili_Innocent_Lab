package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

internal class SponsorRequestCancellation {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    val isCancelled: Boolean get() = cancelled.get() || Thread.currentThread().isInterrupted
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
internal class SponsorBlockClient(private val version: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) : SponsorSegmentSource {
    override fun fetch(video: SponsorVideoId, cancellation: SponsorRequestCancellation): SponsorFetchResult {
        var connection: HttpURLConnection? = null
        try {
            cancellation.check()
            val request = open(URL(endpoint(video))).apply {
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
            connection = request
            cancellation.attach(request)
            cancellation.check()
            val status = request.responseCode
            if (status == 404) return SponsorFetchResult.Available(emptyList())
            if (status != 200) return SponsorFetchResult.Unavailable(
                if (status == 429) "rate-limited" else "http-$status",
                if (status == 429) 60_000 else if (status in 500..599) 5_000 else 0)
            val body = ByteArrayOutputStream()
            request.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    cancellation.check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(body.size() + count <= MAX_RESPONSE_BYTES) { "response-budget" }
                    body.write(buffer, 0, count)
                }
            }
            return SponsorSegmentParser.parseDetailed(body.toString("UTF-8"), video)
        } catch (failure: Exception) {
            return when {
                cancellation.isCancelled -> SponsorFetchResult.Unavailable("cancelled")
                failure is SocketTimeoutException -> SponsorFetchResult.Unavailable("timeout", 2_000)
                failure is UnknownHostException -> SponsorFetchResult.Unavailable("dns-error", 2_000)
                failure is SSLException -> SponsorFetchResult.Unavailable("tls-error")
                failure is IOException -> SponsorFetchResult.Unavailable("network-error", 2_000)
                else -> SponsorFetchResult.Unavailable("invalid-response")
            }
        } finally {
            cancellation.detach()
            connection?.disconnect()
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        fun endpoint(video: SponsorVideoId): String = "https://www.bsbsb.top/api/skipSegments/${video.hashPrefix}"
    }
}
