package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class LanRequest(val method: String, val path: String, val headers: Map<String, String>,
    val body: String, val remote: InetAddress, val version: String = "HTTP/1.1")

/**
 * FIXED sends Content-Length. Unknown-length media uses CLOSE (end of body = connection close) or CHUNKED; the
 * two are never combined with Content-Length, and CHUNKED falls back to CLOSE for an HTTP/1.0 request.
 */
internal enum class LanFraming { FIXED, CLOSE, CHUNKED }
internal data class LanResponse(val status: Int = 200, val type: String = "application/json", val body: ByteArray = ByteArray(0),
    val length: Long = body.size.toLong(), val headers: Map<String, String> = emptyMap(),
    val stream: ((OutputStream) -> Unit)? = null, val framing: LanFraming = LanFraming.FIXED,
    /** Served by the separate media pool so a slow or paused receiver cannot hold control/continuity workers. */
    val media: Boolean = false)

/** Small, bounded HTTP/1.1 origin server. No proxy, arbitrary files, WebView or shell endpoint. */
internal class LanHttpServer(bind: InetAddress,
    private val allowRemote: (InetAddress) -> Boolean = { LanAddress.privateV4(it.hostAddress.orEmpty()) != null },
    private val handler: (LanRequest) -> LanResponse) : AutoCloseable {
    private val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, 0), 8) }
    val port get() = socket.localPort
    /** A receiver blocked in one media write longer than this is disconnected; adjustable for local tests. */
    @Volatile var mediaStallNs = 600_000_000_000L
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "BIL-LAN-HTTP").apply { isDaemon = true } })
    // No queue: a receiver beyond the media connection limit gets 503 immediately instead of waiting unboundedly.
    private val media = ThreadPoolExecutor(0, MEDIA_CONNECTIONS, 30, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
        { task -> Thread(task, "BIL-LAN-Media").apply { isDaemon = true } })
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private val guards = java.util.concurrent.ConcurrentHashMap.newKeySet<StallGuard>()
    val activeMedia get() = media.activeCount
    private val acceptor = Thread({
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            if (!allowRemote(client.inetAddress)) { client.close(); continue }
            connections += client
            try { workers.execute { serve(client) } } catch (_: java.util.concurrent.RejectedExecutionException) {
                connections -= client; client.close()
            }
        }
    }, "BIL-LAN-Accept").apply { isDaemon = true; start() }
    // Java sockets have no write timeout. A receiver that stops reading is disconnected after the stall limit.
    private val watchdog = Thread({
        while (!socket.isClosed) {
            try { Thread.sleep(250) } catch (_: InterruptedException) { break }
            val now = System.nanoTime()
            guards.forEach { guard -> val since = guard.since; if (since != 0L && now - since > mediaStallNs) runCatching { guard.socket.close() } }
        }
    }, "BIL-LAN-Stall").apply { isDaemon = true; start() }

    private class StallGuard(private val target: OutputStream, val socket: Socket) : OutputStream() {
        @Volatile var since = 0L
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            since = System.nanoTime()
            try { target.write(b, off, len) } finally { since = 0L }
        }
        override fun flush() {
            since = System.nanoTime()
            try { target.flush() } finally { since = 0L }
        }
    }

    private class Chunked(private val target: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0) return
            target.write("${Integer.toHexString(len)}\r\n".toByteArray(Charsets.US_ASCII))
            target.write(b, off, len); target.write(CRLF)
        }
        override fun flush() = target.flush()
        fun finish() { target.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII)); target.flush() }
    }

    private fun serve(client: Socket) {
        var handedOff = false
        try {
            client.soTimeout = 5000
            val request = readRequest(client.getInputStream(), client.inetAddress)
            val response = try { handler(request) } catch (_: Exception) { LanResponse(400) }
            if (response.media) {
                try {
                    media.execute {
                        try { client.use { respond(client, request, response, guarded = true) } }
                        catch (_: Exception) { runCatching { client.close() } }
                        finally { connections -= client }
                    }
                    handedOff = true
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    client.use { respond(client, request, LanResponse(503, headers = mapOf("Retry-After" to "2")), guarded = false) }
                }
            } else client.use { respond(client, request, response, guarded = false) }
        } catch (_: Exception) { runCatching { client.close() } }
        finally { if (!handedOff) connections -= client }
    }

    private fun respond(client: Socket, request: LanRequest, response: LanResponse, guarded: Boolean) {
        val raw = client.getOutputStream()
        val guard = if (guarded) StallGuard(raw, client).also(guards::add) else null
        try {
            val out = guard ?: raw
            val framing = if (response.framing == LanFraming.CHUNKED && request.version != "HTTP/1.1") LanFraming.CLOSE else response.framing
            val reason = when (response.status) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request";
                403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 416 -> "Range Not Satisfiable";
                503 -> "Service Unavailable"; else -> "Error" }
            val header = buildString {
                append("HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.type}\r\n")
                when (framing) {
                    LanFraming.FIXED -> append("Content-Length: ${response.length}\r\n")
                    LanFraming.CHUNKED -> append("Transfer-Encoding: chunked\r\n")
                    LanFraming.CLOSE -> Unit
                }
                append("Connection: close\r\nCache-Control: no-store\r\n")
                response.headers.forEach { (k, v) ->
                    require(!k.contains('\r') && !k.contains('\n') && !v.contains('\r') && !v.contains('\n'))
                    require(!k.equals("Content-Length", true) && !k.equals("Transfer-Encoding", true))
                    append("$k: $v\r\n")
                }
                append("\r\n")
            }
            out.write(header.toByteArray(Charsets.US_ASCII))
            if (request.method != "HEAD") {
                if (framing == LanFraming.CHUNKED) {
                    val chunked = Chunked(out)
                    if (response.stream != null) response.stream.invoke(chunked) else chunked.write(response.body)
                    chunked.finish()
                } else if (response.stream != null) response.stream.invoke(out) else out.write(response.body)
            }
            out.flush()
        } finally { guard?.let(guards::remove) }
    }
    override fun close() {
        socket.close(); connections.forEach { runCatching { it.close() } }; workers.shutdownNow(); media.shutdownNow()
        watchdog.interrupt()
    }

    companion object {
        const val MEDIA_CONNECTIONS = 6
        private val CRLF = byteArrayOf(13, 10)
        /** Kept independent of sockets so hostile inputs can be tested without an OS HTTP filter. */
        fun readRequest(stream: java.io.InputStream, remote: InetAddress): LanRequest {
            val input = BufferedInputStream(stream)
            val deadline = System.nanoTime() + 10_000_000_000L
            var total = 0
            fun line(): String {
                val output = java.io.ByteArrayOutputStream()
                while (true) {
                    require(System.nanoTime() < deadline) { "request-timeout" }
                    val b = input.read()
                    require(b >= 0 && ++total <= 16_384 && output.size() <= 4096)
                    if (b == 10) break
                    output.write(b)
                }
                return output.toString("US-ASCII").removeSuffix("\r")
            }
            val start = line().split(' ')
            require(start.size == 3 && start[0] in setOf("GET", "HEAD", "POST") && start[2] in setOf("HTTP/1.0", "HTTP/1.1"))
            require(start[1].length <= 1024 && start[1].startsWith('/') && !start[1].startsWith("//"))
            val headers = linkedMapOf<String, String>()
            while (true) {
                val value = line()
                if (value.isEmpty()) break
                require(':' in value)
                val name = value.substringBefore(':').lowercase(Locale.ROOT)
                require(name !in headers)
                headers[name] = value.substringAfter(':').trim()
            }
            require("transfer-encoding" !in headers && "expect" !in headers)
            val size = if ("content-length" in headers) headers.getValue("content-length").toIntOrNull()
                ?: error("bad-length") else 0
            require(size in 0..24_000 && (start[0] == "POST" || size == 0))
            val bytes = ByteArray(size)
            var read = 0
            while (read < size) {
                require(System.nanoTime() < deadline) { "request-timeout" }
                val n = input.read(bytes, read, size - read); require(n > 0); read += n
            }
            return LanRequest(start[0], start[1], headers, String(bytes, Charsets.UTF_8), remote, start[2])
        }
    }
}

internal data class ByteRange(val start: Long, val end: Long) {
    val length get() = end - start + 1
    companion object {
        fun parse(value: String?, length: Long): ByteRange? {
            require(length > 0)
            if (value == null) return ByteRange(0, length - 1)
            if (!value.matches(Regex("bytes=[0-9]*-[0-9]*"))) return null
            val start = value.substringAfter('=').substringBefore('-')
            val end = value.substringAfter('-')
            if (start.isBlank()) {
                val count = end.toLongOrNull()?.takeIf { it > 0 } ?: return null
                return ByteRange((length - count).coerceAtLeast(0), length - 1)
            }
            val first = start.toLongOrNull()?.takeIf { it in 0 until length } ?: return null
            val last = if (end.isBlank()) length - 1 else end.toLongOrNull()?.takeIf { it >= first }?.coerceAtMost(length - 1) ?: return null
            return ByteRange(first, last)
        }
    }
}
