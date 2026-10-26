package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CastMediaFetchTest {
    @get:Rule val folder = TemporaryFolder()
    private val primary = "http://a.bilivideo.com/upgcxcode/42/video.m4s?deadline=123&token=signed"
    private val backup = "https://b.bilivideo.com/upgcxcode/42/video.m4s?deadline=123&token=backup"
    private val headers = mapOf("User-Agent" to "Bilibili Freedoooooom/MarkII", "Referer" to "https://www.bilibili.com/")
    private open class Reply(url: String, private val code: Int, private val bytes: ByteArray,
        private val fields: Map<String, String> = emptyMap()) : HttpURLConnection(URL(url)) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream(): java.io.InputStream = bytes.inputStream()
        override fun getContentLengthLong() = fields["Content-Length"]?.toLong() ?: bytes.size.toLong()
        override fun getHeaderField(name: String) = fields.entries.firstOrNull { it.key.equals(name, true) }?.value
    }
    @Test fun `preserves the actual host URL and headers without changing scheme or sending credentials`() {
        val opened = arrayListOf<String>(); val reply = Reply(primary, 200, "video".toByteArray())
        val connections = linkedSetOf<HttpURLConnection>()
        val fetch = CastMediaFetch({ opened += it; reply }, AtomicBoolean(false), connections)
        val file = folder.newFile()
        assertEquals(5L, fetch.download(listOf(primary), headers, file, 100) { true })
        assertEquals(listOf(primary), opened); assertEquals("video", file.readText())
        assertEquals(headers["User-Agent"], reply.getRequestProperty("User-Agent"))
        assertEquals(headers["Referer"], reply.getRequestProperty("Referer"))
        assertEquals("bytes=0-", reply.getRequestProperty("Range")); assertNull(reply.getRequestProperty("Cookie"))
        assertTrue(reply.closed); assertTrue(connections.isEmpty())
    }
    @Test fun `rejected primary falls back only to the same track supplied backup`() {
        val opened = arrayListOf<String>(); val rejected = arrayListOf<Int>()
        val first = Reply(primary, 403, byteArrayOf()); val second = Reply(backup, 200, "backup-video".toByteArray())
        val fetch = CastMediaFetch({ opened += it; if (it == primary) first else second }, AtomicBoolean(false), linkedSetOf(), { code, _ -> rejected += code })
        val file = folder.newFile()
        assertEquals(12L, fetch.download(listOf(primary, backup), headers, file, 100) { true })
        assertEquals(listOf(primary, backup), opened); assertEquals(listOf(403), rejected)
        assertEquals("backup-video", file.readText()); assertTrue(first.closed && second.closed)
    }
    @Test fun `complete 206 and bounded range continuation produce the entire file`() {
        val replies = arrayListOf(Reply(backup, 206, "abc".toByteArray(), mapOf("Content-Range" to "bytes 0-2/6", "ETag" to "fixed")),
            Reply(backup, 206, "def".toByteArray(), mapOf("Content-Range" to "bytes 3-5/6", "ETag" to "fixed")))
        var request = 0
        val file = folder.newFile()
        assertEquals(6L, CastMediaFetch({ replies[request++] }, AtomicBoolean(false), linkedSetOf())
            .download(listOf(backup), headers, file, 100) { true })
        assertEquals("abcdef", file.readText()); assertEquals("bytes=3-", replies[1].getRequestProperty("Range"))
        assertEquals("fixed", replies[1].getRequestProperty("If-Range")); assertTrue(replies.all { it.closed })
    }
    @Test fun `redirects retain headers but cannot escape CDN allowlist or downgrade TLS`() {
        val redirect = Reply(primary, 302, byteArrayOf(), mapOf("Location" to backup))
        val final = Reply(backup, 200, "video".toByteArray())
        val file = folder.newFile()
        CastMediaFetch({ if (it == primary) redirect else final }, AtomicBoolean(false), linkedSetOf())
            .download(listOf(primary), headers, file, 100) { true }
        assertEquals(headers["User-Agent"], final.getRequestProperty("User-Agent"))
        listOf("http://a.bilivideo.com/unsafe", "https://evil.example/private").forEach { location ->
            var requests = 0
            val fetch = CastMediaFetch({ requests++; Reply(backup, 302, byteArrayOf(), mapOf("Location" to location)) }, AtomicBoolean(false), linkedSetOf())
            assertTrue(runCatching { fetch.download(listOf(backup), headers, folder.newFile(), 100) { true } }.isFailure)
            assertEquals(1, requests)
        }
    }
    @Test fun `wrong range never concatenates mismatched bytes and fallback discards partial data`() {
        val first = Reply(primary, 206, "abc".toByteArray(), mapOf("Content-Range" to "bytes 1-3/6"))
        val second = Reply(backup, 200, "good".toByteArray())
        val file = folder.newFile()
        CastMediaFetch({ if (it == primary) first else second }, AtomicBoolean(false), linkedSetOf())
            .download(listOf(primary, backup), headers, file, 100) { true }
        assertEquals("good", file.readText())
    }
    @Test fun `budgets and forbidden credentials fail before extra transfers`() {
        var requests = 0
        val fetch = CastMediaFetch({ requests++; Reply(primary, 200, "too big".toByteArray()) }, AtomicBoolean(false), linkedSetOf())
        assertTrue(runCatching { fetch.download(listOf(primary), headers + ("Cookie" to "never-forward"), folder.newFile(), 100) { true } }.isFailure)
        assertEquals(0, requests)
        assertTrue(runCatching { fetch.download(listOf(primary, backup), headers, folder.newFile(), 2) { true } }.isFailure)
        assertEquals(1, requests)
    }
    @Test fun `HTTP failure preserves status without leaking signed URLs and cancellation opens nothing`() {
        val fetch = CastMediaFetch({ Reply(primary, 403, byteArrayOf()) }, AtomicBoolean(false), linkedSetOf())
        val failure = runCatching { fetch.download(listOf(primary), headers, folder.newFile(), 100) { true } }.exceptionOrNull() as MediaFetchFailure
        assertEquals(403, failure.status); assertFalse(failure.message!!.contains("signed"))
        var opened = false
        assertTrue(runCatching { CastMediaFetch({ opened = true; error("must-not-open") }, AtomicBoolean(true), linkedSetOf())
            .download(listOf(primary), headers, folder.newFile(), 100) { true } }.isFailure)
        assertFalse(opened)
    }
    @Test fun `failed candidate releases its partial bytes before a backup consumes the shared budget`() {
        val first = Reply(primary, 200, "abc".toByteArray(), mapOf("Content-Length" to "6"))
        val second = Reply(backup, 200, "abcdef".toByteArray())
        val budget = CastDownloadBudget(6)
        val file = folder.newFile()
        val fetch = CastMediaFetch({ if (it == primary) first else second }, AtomicBoolean(false), linkedSetOf())
        assertEquals(6L, fetch.download(listOf(primary, backup), headers, file, 6, budget) { true })
        assertEquals("abcdef", file.readText())
        assertTrue(runCatching { budget.acquire(1) }.isFailure)
    }
    @Test fun `separate tracks share one quota and a rejected track cannot erase the successful claim`() {
        val budget = CastDownloadBudget(8)
        val fetch = CastMediaFetch({ Reply(it, 200, "track".toByteArray()) }, AtomicBoolean(false), linkedSetOf())
        val first = folder.newFile(); val second = folder.newFile()
        assertEquals(5L, fetch.download(listOf(primary), headers, first, 8, budget) { true })
        val failure = runCatching { fetch.download(listOf(backup), headers, second, 8, budget) { true } }.exceptionOrNull()
        assertEquals("media-budget-exceeded", failure?.message)
        assertEquals("track", first.readText()); assertFalse(second.exists())
        budget.acquire(3)
        assertTrue(runCatching { budget.acquire(1) }.isFailure)
    }
    @Test fun `failed partial cleanup retains quota and stops before the backup`() {
        val budget = CastDownloadBudget(6)
        var opened = 0; var removals = 0
        val file = folder.newFile()
        val fetch = CastMediaFetch({ opened++; Reply(it, 200, "abc".toByteArray(), mapOf("Content-Length" to "6")) },
            AtomicBoolean(false), linkedSetOf(), removePartial = { removals++; false })
        val failure = runCatching { fetch.download(listOf(primary, backup), headers, file, 6, budget) { true } }.exceptionOrNull()
        assertTrue(failure is MediaCleanupFailure)
        assertEquals(1, opened); assertEquals(1, removals); assertEquals("abc", file.readText())
        budget.acquire(3)
        assertTrue(runCatching { budget.acquire(1) }.isFailure)
    }
    @Test fun `cancellation discards partial bytes and never starts a backup`() {
        val cancelled = AtomicBoolean(false)
        var opened = 0
        val reply = object : Reply(primary, 200, ByteArray(6)) {
            override fun getInputStream() = object : java.io.InputStream() {
                var reads = 0
                override fun read(): Int = error("bulk reads expected")
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (++reads == 2) cancelled.set(true)
                    bytes.fill(1, offset, offset + 3)
                    return 3
                }
            }
        }
        val budget = CastDownloadBudget(6)
        val file = folder.newFile()
        val fetch = CastMediaFetch({ opened++; reply }, cancelled, linkedSetOf())
        assertTrue(runCatching { fetch.download(listOf(primary, backup), headers, file, 6, budget) { true } }.isFailure)
        assertEquals(1, opened); assertTrue(reply.closed); assertFalse(file.exists())
        budget.acquire(6)
    }
}
