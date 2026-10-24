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
    val body: String, val remote: InetAddress)
internal data class LanResponse(val status: Int = 200, val type: String = "application/json", val body: ByteArray = ByteArray(0),
    val length: Long = body.size.toLong(), val headers: Map<String, String> = emptyMap(),
    val stream: ((OutputStream) -> Unit)? = null)

/** Small, bounded HTTP/1.1 origin server. No proxy, arbitrary files, WebView or shell endpoint. */
internal class LanHttpServer(bind: InetAddress,
    private val allowRemote: (InetAddress) -> Boolean = { LanAddress.privateV4(it.hostAddress.orEmpty()) != null },
    private val handler: (LanRequest) -> LanResponse) : AutoCloseable {
    private val socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, 0), 8) }
    val port get() = socket.localPort
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "BIL-LAN-HTTP").apply { isDaemon = true } })
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
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

    private fun serve(client: Socket) {
        try {
            client.use {
                client.soTimeout = 5000
                val request = readRequest(client.getInputStream(), client.inetAddress)
                val response = try { handler(request) } catch (_: Exception) { LanResponse(400) }
                val out = client.getOutputStream()
                val reason = when (response.status) { 200 -> "OK"; 206 -> "Partial Content"; 400 -> "Bad Request";
                    403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 416 -> "Range Not Satisfiable"; else -> "Error" }
                val header = buildString {
                    append("HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.type}\r\n")
                    append("Content-Length: ${response.length}\r\nConnection: close\r\nCache-Control: no-store\r\n")
                    response.headers.forEach { (k, v) ->
                        require(!k.contains('\r') && !k.contains('\n') && !v.contains('\r') && !v.contains('\n'))
                        append("$k: $v\r\n")
                    }
                    append("\r\n")
                }
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (request.method != "HEAD") { if (response.stream != null) response.stream.invoke(out) else out.write(response.body) }
                out.flush()
            }
        } catch (_: Exception) { runCatching { client.close() } }
        finally { connections -= client }
    }
    override fun close() {
        socket.close(); connections.forEach { runCatching { it.close() } }; workers.shutdownNow()
    }

    companion object {
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
            return LanRequest(start[0], start[1], headers, String(bytes, Charsets.UTF_8), remote)
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
