package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal class MediaFetchFailure(val status: Int) : Exception("media-fetch-http-$status")
internal class MediaCleanupFailure(cause: Throwable) : Exception("media-cleanup-failed", cause)

/** Follow only the selected track's host-provided CDN candidates; no playurl API or cookie forwarding. */
internal class CastMediaFetch(private val open: (String) -> HttpURLConnection, private val cancelled: AtomicBoolean,
    private val connections: MutableSet<HttpURLConnection>, private val rejected: (Int, String) -> Unit = { _, _ -> },
    private val removePartial: (File) -> Boolean = File::delete) {
    fun download(urls: List<String>, headers: Map<String, String>, file: File, budget: Long,
        sharedBudget: CastDownloadBudget = CastDownloadBudget(budget), hasSpace: (Int) -> Boolean): Long {
        require(urls.size in 1..8 && budget > 0) { "media-budget-exceeded" }
        urls.forEach { validateMediaUrl(it, allowHttp = true) }
        require(headers.keys.all { it in setOf("User-Agent", "Referer") } && headers.values.all {
            it.length in 1..4096 && it.none { c -> c == '\r' || c == '\n' || c == '\u0000' }
        }) { "invalid-media-headers" }
        var last: Exception = MediaFetchFailure(0)
        for (url in urls.distinct()) {
            checkActive()
            try { return fetch(url, headers, file, budget, sharedBudget, hasSpace) }
            catch (failure: Exception) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) throw failure
                if (failure is MediaCleanupFailure) throw failure
                if (failure.message in setOf("media-budget-exceeded", "insufficient-space")) throw failure
                rejected((failure as? MediaFetchFailure)?.status ?: 0, URI(url).host)
                last = failure
            }
        }
        throw last
    }

    private fun fetch(start: String, headers: Map<String, String>, file: File, budget: Long,
        sharedBudget: CastDownloadBudget, hasSpace: (Int) -> Boolean): Long {
        var cursor = 0L; var total: Long? = null; var url = start; var redirects = 0; var requests = 0
        var etag: String? = null
        var claimed = 0L
        val deadline = System.nanoTime() + 1_200_000_000_000L
        try {
            val length = file.outputStream().use { output ->
                var finalRange = false
                while (!finalRange) {
                    checkActive(); require(System.nanoTime() < deadline && ++requests <= 1024) { "media-transfer-timeout" }
                    validateMediaUrl(url, allowHttp = true)
                    val connection = open(url).also(connections::add)
                    try {
                        checkActive()
                        connection.connectTimeout = 5000; connection.readTimeout = 10_000; connection.instanceFollowRedirects = false
                        headers.forEach(connection::setRequestProperty)
                        connection.setRequestProperty("Accept-Encoding", "identity")
                        connection.setRequestProperty("Range", "bytes=$cursor-")
                        etag?.let { connection.setRequestProperty("If-Range", it) }
                        val status = connection.responseCode
                        if (status in setOf(301, 302, 303, 307, 308)) {
                            require(++redirects <= 3) { "media-redirect-limit" }
                            val next = URI(url).resolve(connection.getHeaderField("Location") ?: error("media-redirect-unavailable"))
                            validateMediaUrl(next.toString(), allowHttp = true)
                            require(URI(url).scheme != "https" || next.scheme == "https") { "media-redirect-downgrade" }
                            url = next.toString(); continue
                        }
                        if (status != 200 && status != 206) throw MediaFetchFailure(status)
                        var expected: Long? = connection.contentLengthLong.takeIf { it >= 0 }
                        if (status == 206) {
                            val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                                ?: error("media-range-unavailable")
                            val begin = range.groupValues[1].toLong(); val end = range.groupValues[2].toLong(); val length = range.groupValues[3].toLong()
                            require(begin == cursor && end >= begin && end < length && (total == null || total == length)) { "media-range-mismatch" }
                            require(length <= budget) { "media-budget-exceeded" }
                            require(expected == null || expected == end - begin + 1) { "media-range-mismatch" }
                            expected = end - begin + 1; total = length
                        } else {
                            require(cursor == 0L) { "media-range-ignored" }
                            total = expected
                            require(expected == null || expected <= budget) { "media-budget-exceeded" }
                        }
                        if (etag == null) etag = connection.getHeaderField("ETag")?.takeIf { it.length <= 512 && '\r' !in it && '\n' !in it }
                        val expectedBytes = expected
                        var read = 0L
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkActive(); require(System.nanoTime() < deadline) { "media-transfer-timeout" }
                                val count = input.read(buffer)
                                if (count < 0) break
                                checkActive()
                                read += count
                                require(cursor + read <= budget && (expectedBytes == null || read <= expectedBytes)) { "media-budget-exceeded" }
                                require(hasSpace(count)) { "insufficient-space" }
                                sharedBudget.acquire(count); claimed += count
                                output.write(buffer, 0, count)
                            }
                        }
                        checkActive()
                        require(read > 0 && (expectedBytes == null || read == expectedBytes)) { "incomplete-media" }
                        cursor += read
                        finalRange = status == 200 || cursor == total
                    } finally { connections -= connection; connection.disconnect() }
                }
                cursor
            }
            checkActive()
            return length
        } catch (failure: Throwable) {
            // One boundary owns both deletion and quota rollback; never truncate a retained failed attempt.
            if (runCatching { !file.exists() || removePartial(file) }.getOrDefault(false)) sharedBudget.release(claimed)
            else throw MediaCleanupFailure(failure)
            throw failure
        }
    }
    private fun checkActive() = check(!cancelled.get() && !Thread.currentThread().isInterrupted) { "media-preparation-cancelled" }
}
