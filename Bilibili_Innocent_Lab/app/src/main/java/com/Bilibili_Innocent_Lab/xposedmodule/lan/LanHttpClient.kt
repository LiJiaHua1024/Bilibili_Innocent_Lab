package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale

/** DLNA requires cleartext HTTP. Use a literal private-address socket, never a global cleartext exception. */
internal class LanHttpClient(private val socket: () -> Socket = { Socket() }) {
    data class Response(val status: Int, val body: String)
    fun request(uri: URI, method: String = "GET", body: String = "", headers: Map<String, String> = emptyMap()): Response {
        LanAddress.url(uri.toString())
        require(method in setOf("GET", "POST") && body.toByteArray().size <= 32_768)
        return socket().use { client ->
            client.connect(InetSocketAddress(LanAddress.privateV4(uri.host), if (uri.port < 0) 80 else uri.port), 3000)
            client.soTimeout = 4000
            val bytes = body.toByteArray(Charsets.UTF_8)
            val path = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            require(path.none { it == '\r' || it == '\n' })
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n")
                headers.forEach { (k, v) ->
                    require(k.matches(Regex("[A-Za-z-]+")) && v.none { it == '\r' || it == '\n' })
                    append("$k: $v\r\n")
                }
                append("\r\n")
            }
            client.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
            val input = BufferedInputStream(client.getInputStream())
            val deadline = System.nanoTime() + 12_000_000_000L
            var headerBytes = 0
            fun line(): String {
                val out = ByteArrayOutputStream()
                while (true) {
                    require(System.nanoTime() < deadline) { "response-timeout" }
                    val b = input.read()
                    require(b >= 0 && ++headerBytes <= 32_768 && out.size() <= 8192)
                    if (b == 10) return out.toString("US-ASCII").removeSuffix("\r")
                    out.write(b)
                }
            }
            val status = line().split(' ').getOrNull(1)?.toIntOrNull() ?: error("invalid-http-status")
            val fields = linkedMapOf<String, String>()
            while (true) {
                val next = line()
                if (next.isEmpty()) break
                require(':' in next)
                val key = next.substringBefore(':').lowercase(Locale.ROOT)
                require(key !in fields)
                fields[key] = next.substringAfter(':').trim()
            }
            require(status !in 300..399 && !("transfer-encoding" in fields && "content-length" in fields))
            val output = ByteArrayOutputStream()
            fun read(count: Int) {
                require(count >= 0 && output.size() + count <= 262_144)
                val buffer = ByteArray(8192)
                var left = count
                while (left > 0) {
                    require(System.nanoTime() < deadline) { "response-timeout" }
                    val n = input.read(buffer, 0, minOf(left, buffer.size)); require(n > 0); output.write(buffer, 0, n); left -= n
                }
            }
            if (fields["transfer-encoding"] != null) {
                require(fields["transfer-encoding"].equals("chunked", true))
                while (true) {
                    val count = line().substringBefore(';').toIntOrNull(16) ?: error("bad-chunk")
                    if (count == 0) break
                    read(count); require(line().isEmpty())
                }
            } else if (fields["content-length"] != null) {
                read(fields.getValue("content-length").toIntOrNull() ?: error("bad-length"))
            } else {
                val buffer = ByteArray(8192)
                while (true) {
                    require(System.nanoTime() < deadline) { "response-timeout" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 262_144)
                    output.write(buffer, 0, count)
                }
            }
            Response(status, String(output.toByteArray(), Charsets.UTF_8))
        }
    }
}
