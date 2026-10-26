package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.ByteBuffer
import java.util.BitSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Random-access view of one host-authorised media track, backed by a sparse local file.
 * A single worker fetches HTTP 206 ranges: first the block a reader is waiting for (initialisation data, a tail
 * index, a seek target), otherwise sequentially ahead of the last read up to [windowBytes]. Missing data is
 * waited for, never reported as EOF; cancellation and fetch failure surface as an [IOException] without URLs.
 * The same URL, header, redirect and Content-Range rules as [CastMediaFetch] apply; a server that ignores Range
 * fails with `media-range-ignored` so the caller can fall back to the complete download.
 */
internal class CastRangeCache(private val file: File, private val urls: List<String>, private val headers: Map<String, String>,
    private val open: (String) -> HttpURLConnection, private val cancelled: AtomicBoolean,
    private val connections: MutableSet<HttpURLConnection>, private val budget: Long,
    private val shared: CastDownloadBudget = CastDownloadBudget(budget), private val hasSpace: (Int) -> Boolean = { true },
    private val blockSize: Int = 1 shl 20, private val windowBytes: Long = 64L * 1024 * 1024, private val runBlocks: Int = 4,
    private val stallNs: Long = 30_000_000_000L, private val rejected: (Int, String) -> Unit = { _, _ -> }) : AutoCloseable {
    private val lock = Object() // Readers wait on the Java monitor until the worker publishes a block.
    private val present = BitSet()
    private val channel = RandomAccessFile(file, "rw").channel
    private val etags = arrayOfNulls<String>(urls.size)
    private var candidate = 0
    private var demand = -1
    private var cursor = 0L
    private var claimed = 0L
    private var failure: String? = null
    private var closed = false
    private var lastProgress = System.nanoTime()
    private var worker: Thread? = null
    @Volatile var length = -1L; private set
    /** Bytes actually transferred, for tests and metrics; each block is stored once. */
    @Volatile var fetchedBytes = 0L; private set
    private val blocks get() = ((length + blockSize - 1) / blockSize).toInt()

    init {
        require(urls.size in 1..8 && budget > 0 && blockSize >= 4096 && runBlocks >= 1 && windowBytes >= blockSize)
        urls.forEach { validateMediaUrl(it, allowHttp = true) }
        require(headers.keys.all { it in setOf("User-Agent", "Referer") } && headers.values.all {
            it.length in 1..4096 && it.none { c -> c == '\r' || c == '\n' || c == '\u0000' }
        }) { "invalid-media-headers" }
    }

    /** Fetches the first run synchronously to learn the length, then starts the background worker. */
    fun start() {
        check(worker == null)
        try { fetchRun(0, runBlocks) } catch (error: Throwable) { close(); throw error }
        worker = Thread(::work, "BIL-Cast-Range").apply { isDaemon = true; start() }
    }

    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && size >= 0 && offset >= 0 && offset + size <= buffer.size)
        check(length >= 0) { "range-cache-not-started" }
        if (size == 0) return 0
        if (position >= length) return -1
        val first = (position / blockSize).toInt()
        var last: Int
        synchronized(lock) {
            cursor = position
            val waited = System.nanoTime()
            while (!present[first]) {
                failure?.let { throw IOException(it) }
                if (closed || cancelled.get()) throw IOException("media-preparation-cancelled")
                val now = System.nanoTime()
                if (now - waited > stallNs && now - lastProgress > stallNs) throw IOException("media-read-timeout")
                demand = first; lock.notifyAll()
                lock.wait(100)
            }
            last = first
            while (last + 1 < blocks && present[last + 1] && (last + 1 - first).toLong() * blockSize < size) last++
        }
        val available = minOf((last + 1).toLong() * blockSize, length) - position
        val count = minOf(size.toLong(), available).toInt()
        var read = 0
        while (read < count) {
            val n = channel.read(ByteBuffer.wrap(buffer, offset + read, count - read), position + read)
            if (n < 0) throw IOException("range-cache-truncated")
            read += n
        }
        synchronized(lock) { cursor = position + count; lock.notifyAll() }
        return count
    }

    private fun work() {
        try {
            while (true) {
                val (first, count) = synchronized(lock) {
                    var next: Pair<Int, Int>?
                    while (true) {
                        if (closed || failure != null || cancelled.get()) return
                        next = pick()
                        if (next != null) break
                        lock.wait(200)
                    }
                    checkNotNull(next)
                }
                fetchRun(first, count)
            }
        } catch (error: Throwable) {
            synchronized(lock) {
                if (failure == null) failure = error.message?.takeIf { it.matches(Regex("[a-z0-9-]{1,60}")) } ?: error.javaClass.simpleName
                lock.notifyAll()
            }
        }
    }

    /** Urgent demand first; otherwise the first missing block within the prefetch window after the cursor. */
    private fun pick(): Pair<Int, Int>? {
        val start = if (demand >= 0 && !present[demand]) demand else {
            demand = -1
            val next = present.nextClearBit((cursor / blockSize).toInt())
            if (next >= blocks || next.toLong() * blockSize >= cursor + windowBytes) return null
            next
        }
        var count = 1
        while (count < runBlocks && start + count < blocks && !present[start + count]) count++
        return start to count
    }

    private fun fetchRun(first: Int, count: Int) {
        var attempts = 0
        while (true) {
            checkActive()
            try { transfer(urls[candidate], first, count); return }
            catch (failure: Exception) {
                if (cancelled.get() || closed || Thread.currentThread().isInterrupted) throw failure
                if (failure.message in FATAL) throw failure
                // Each candidate gets one retry for a transient I/O error, then the next same-track backup is used.
                if (failure is IOException && failure !is MediaFetchFailureIo && ++attempts < 2) continue
                rejected((failure as? MediaFetchFailureIo)?.status ?: 0, URI(urls[candidate]).host)
                attempts = 0
                if (++candidate >= urls.size) throw failure
            }
        }
    }

    private class MediaFetchFailureIo(val status: Int) : IOException("media-fetch-http-$status")

    private fun transfer(start: String, first: Int, count: Int) {
        val begin = first.toLong() * blockSize
        val known = length
        val end = (if (known >= 0) minOf(begin + count.toLong() * blockSize, known) else begin + count.toLong() * blockSize) - 1
        var url = start
        var redirects = 0
        while (true) {
            checkActive()
            validateMediaUrl(url, allowHttp = true)
            val connection = open(url).also(connections::add)
            try {
                connection.connectTimeout = 5000; connection.readTimeout = 10_000; connection.instanceFollowRedirects = false
                headers.forEach(connection::setRequestProperty)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Range", "bytes=$begin-$end")
                etags[candidate]?.let { connection.setRequestProperty("If-Range", it) }
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(++redirects <= 3) { "media-redirect-limit" }
                    val next = URI(url).resolve(connection.getHeaderField("Location") ?: error("media-redirect-unavailable"))
                    validateMediaUrl(next.toString(), allowHttp = true)
                    require(URI(url).scheme != "https" || next.scheme == "https") { "media-redirect-downgrade" }
                    url = next.toString(); continue
                }
                // 200 means the range was ignored or If-Range found a different entity: neither can be spliced.
                if (status == 200) throw IllegalStateException("media-range-ignored")
                if (status != 206) throw MediaFetchFailureIo(status)
                val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                    ?: throw IllegalStateException("media-range-unavailable")
                val from = range.groupValues[1].toLong(); val to = range.groupValues[2].toLong(); val total = range.groupValues[3].toLong()
                require(from == begin && to >= from && to < total && (known < 0 || total == known)) { "media-range-mismatch" }
                require(total <= budget) { "media-budget-exceeded" }
                val declared = connection.contentLengthLong
                require(declared < 0 || declared == to - from + 1) { "media-range-mismatch" }
                val tag = connection.getHeaderField("ETag")?.takeIf { it.length <= 512 && '\r' !in it && '\n' !in it }
                if (etags[candidate] == null) etags[candidate] = tag
                if (known < 0) synchronized(lock) { length = total }
                val stop = minOf(to, end)
                connection.inputStream.use { input -> receive(input, first, from, stop) }
                return
            } finally { connections -= connection; connection.disconnect() }
        }
    }

    private fun receive(input: java.io.InputStream, first: Int, from: Long, stop: Long) {
        val block = ByteArray(blockSize)
        var index = first
        var position = from
        while (position <= stop) {
            checkActive()
            val size = (minOf((index + 1).toLong() * blockSize, length) - index.toLong() * blockSize).toInt()
            var filled = 0
            while (filled < size) {
                checkActive()
                val n = input.read(block, filled, size - filled)
                if (n < 0) throw IOException("incomplete-media")
                filled += n
            }
            val stored = synchronized(lock) { present[index] }
            if (!stored) {
                require(hasSpace(size)) { "insufficient-space" }
                shared.acquire(size)
                synchronized(lock) { claimed += size }
                var written = 0
                while (written < size) written += channel.write(ByteBuffer.wrap(block, written, size - written), index.toLong() * blockSize + written)
                fetchedBytes += size
                synchronized(lock) { present.set(index); lastProgress = System.nanoTime(); lock.notifyAll() }
            }
            position += size; index++
            // A reader now waits outside this run (seek or tail index): abandon the rest of the sequential run.
            val lastInRun = (stop / blockSize).toInt()
            val moved = synchronized(lock) { demand >= 0 && !present[demand] && (demand < index || demand > lastInRun) }
            if (moved && position <= stop) return
        }
    }

    private fun checkActive() {
        if (cancelled.get() || Thread.currentThread().isInterrupted || synchronized(lock) { closed }) throw IOException("media-preparation-cancelled")
    }

    override fun close() {
        val thread = synchronized(lock) { closed = true; lock.notifyAll(); worker }
        runCatching { connections.toList() }.getOrDefault(emptyList()).forEach { runCatching { it.disconnect() } }
        var interrupted = false
        while (thread != null && thread.isAlive) {
            thread.interrupt()
            try { thread.join(100) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
        runCatching { channel.close() }
        if (!file.exists() || file.delete()) synchronized(lock) { if (claimed > 0) { shared.release(claimed); claimed = 0 } }
    }

    private companion object {
        val FATAL = setOf("media-budget-exceeded", "insufficient-space", "media-range-ignored", "media-range-mismatch",
            "media-preparation-cancelled", "media-redirect-downgrade")
    }
}
