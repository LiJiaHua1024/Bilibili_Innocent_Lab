package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** On-demand track reads against a fake CDN; no network, extractor or phone is used. */
class CastRangeCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val primary = "https://a.bilivideo.com/upgcxcode/42/video.m4s?deadline=123&token=signed"
    private val backup = "https://b.bilivideo.com/upgcxcode/42/video.m4s?deadline=123&token=backup"
    private val headers = mapOf("User-Agent" to "Bilibili Freedoooooom/MarkII", "Referer" to "https://www.bilibili.com/")
    private val block = 4096
    private val data = ByteArray(200 * 1024) { (it * 31 + it / 7).toByte() }

    private class Cdn(val data: ByteArray, val status: Int? = null, val ignoreRange: Boolean = false,
        val readDelayMs: Long = 0, val total: Long = data.size.toLong()) {
        val ranges = CopyOnWriteArrayList<String>()
        val opened = CopyOnWriteArrayList<String>()
        fun open(url: String): HttpURLConnection = object : HttpURLConnection(URL(url)) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            private val range get() = getRequestProperty("Range")
            private val bounds: Pair<Long, Long> get() {
                val match = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(range.orEmpty()) ?: error("range expected")
                return match.groupValues[1].toLong() to minOf(match.groupValues[2].toLong(), data.size - 1L)
            }
            override fun getResponseCode(): Int { opened += url; ranges += range.orEmpty(); return status ?: if (ignoreRange) 200 else 206 }
            override fun getHeaderField(name: String): String? = when (name.lowercase()) {
                "content-range" -> if (ignoreRange) null else "bytes ${bounds.first}-${bounds.second}/$total"
                "etag" -> "\"fixed\""
                else -> null
            }
            override fun getContentLengthLong() = if (ignoreRange) data.size.toLong() else bounds.second - bounds.first + 1
            override fun getInputStream(): java.io.InputStream {
                val (from, to) = bounds
                val slice = data.copyOfRange(from.toInt(), to.toInt() + 1)
                return object : java.io.InputStream() {
                    var position = 0
                    override fun read(): Int = error("bulk reads expected")
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (position >= slice.size) return -1
                        if (readDelayMs > 0) Thread.sleep(readDelayMs)
                        val n = minOf(len, slice.size - position, 1024)
                        System.arraycopy(slice, position, b, off, n); position += n
                        return n
                    }
                }
            }
        }
    }

    private fun cache(cdn: Cdn, urls: List<String> = listOf(primary), cancelled: AtomicBoolean = AtomicBoolean(false),
        window: Long = 8L * block, open: (String) -> HttpURLConnection = cdn::open) =
        CastRangeCache(folder.newFile(), urls, headers, open, cancelled, java.util.concurrent.ConcurrentHashMap.newKeySet(), 10L * 1024 * 1024,
            blockSize = block, windowBytes = window, runBlocks = 2, stallNs = 2_000_000_000L)

    private fun read(cache: CastRangeCache, position: Long, size: Int): ByteArray {
        val out = ByteArray(size); var done = 0
        while (done < size) { val n = cache.readAt(position + done, out, done, size - done); assertTrue(n > 0); done += n }
        return out
    }

    @Test fun `random reads return exact bytes and tail reads fetch on demand without the whole file`() {
        val cdn = Cdn(data)
        cache(cdn).use { cache ->
            cache.start()
            assertEquals(data.size.toLong(), cache.length)
            val tail = data.size - 3000L
            assertArrayEquals(data.copyOfRange(tail.toInt(), data.size), read(cache, tail, 3000))
            assertTrue("tail range was requested directly: ${cdn.ranges}", cdn.ranges.any { it.startsWith("bytes=${tail / block * block}-") })
            assertTrue(cache.fetchedBytes < data.size)
            assertEquals(-1, cache.readAt(data.size.toLong(), ByteArray(4), 0, 4))
            assertArrayEquals(data.copyOfRange(10_000, 60_000), read(cache, 10_000, 50_000))
            assertTrue(cdn.ranges.all { it.matches(Regex("bytes=[0-9]+-[0-9]+")) })
        }
    }

    @Test fun `prefetch stays within the window ahead of the last read`() {
        val cdn = Cdn(data)
        cache(cdn, window = 8L * block).use { cache ->
            cache.start()
            read(cache, 0, 100)
            Thread.sleep(300)
            // Initial run (2 blocks) plus a window of 8 blocks after the cursor, at most one extra run in flight.
            assertTrue("fetched ${cache.fetchedBytes}", cache.fetchedBytes <= 12L * block)
            read(cache, 40L * block, 10)
            Thread.sleep(300)
            assertTrue(cache.fetchedBytes <= 26L * block)
            assertTrue(cache.fetchedBytes < data.size)
        }
    }

    @Test fun `server ignoring range fails explicitly so complete download can be used`() {
        val failure = runCatching { cache(Cdn(data, ignoreRange = true)).use { it.start() } }.exceptionOrNull()
        assertEquals("media-range-ignored", failure?.message)
        val mismatch = runCatching { cache(Cdn(data, total = 5)).use { it.start() } }.exceptionOrNull()
        assertEquals("media-range-mismatch", mismatch?.message)
    }

    @Test fun `rejected primary falls back to the same track backup`() {
        val bad = Cdn(data, status = 403); val good = Cdn(data)
        val rejected = ArrayList<Int>()
        CastRangeCache(folder.newFile(), listOf(primary, backup), headers, { if (it == primary) bad.open(it) else good.open(it) },
            AtomicBoolean(false), java.util.concurrent.ConcurrentHashMap.newKeySet(), 10L * 1024 * 1024, blockSize = block, runBlocks = 2,
            rejected = { code, _ -> rejected += code }).use { cache ->
            cache.start()
            assertArrayEquals(data.copyOfRange(0, 9000), read(cache, 0, 9000))
            assertEquals(listOf(403), rejected)
            assertTrue(good.opened.isNotEmpty())
        }
    }

    @Test fun `cancellation and failure wake waiting readers with errors that never contain signed URLs`() {
        val cancelled = AtomicBoolean(false)
        val slow = Cdn(data, readDelayMs = 50)
        val cache = cache(slow, cancelled = cancelled)
        cache.start()
        val result = arrayOfNulls<Throwable>(1)
        val reader = Thread { result[0] = runCatching { read(cache, 45L * block, 10) }.exceptionOrNull() }.apply { start() }
        Thread.sleep(150)
        cancelled.set(true)
        reader.join(5000)
        assertFalse(reader.isAlive)
        assertTrue(result[0] is IOException)
        assertEquals("media-preparation-cancelled", result[0]!!.message)
        cache.close()
        val failing = AtomicBoolean(false)
        val flaky = Cdn(data)
        val second = cache(flaky, urls = listOf(primary, backup), open = { url ->
            if (failing.get()) object : HttpURLConnection(URL(url)) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 500
            } else flaky.open(url)
        })
        second.use {
            it.start()
            failing.set(true)
            val failure = runCatching { read(it, 40L * block, 10) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("media-fetch-http-500", failure!!.message)
            assertFalse(failure.message!!.contains("token"))
        }
    }

    @Test fun `closing deletes the sparse file and releases the shared budget`() {
        val shared = CastDownloadBudget(64L * 1024)
        val file = folder.newFile()
        CastRangeCache(file, listOf(primary), headers, Cdn(data)::open, AtomicBoolean(false), java.util.concurrent.ConcurrentHashMap.newKeySet(), 10L * 1024 * 1024,
            shared, blockSize = block, runBlocks = 2).use { it.start(); read(it, 0, 10) }
        assertFalse(file.exists())
        shared.acquire(64 * 1024)
    }
}
