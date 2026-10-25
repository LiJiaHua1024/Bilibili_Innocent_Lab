package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

internal data class CastDanmaku(val atMs: Long, val mode: Int, val color: Int, val text: String)
internal data class CastDanmakuStyle(val enabled: Boolean = true, val scale: Float = 1f,
    val opacity: Float = .85f, val coverage: Float = .5f) {
    init { require(scale in .5f..2f && opacity in .2f..1f && coverage in .25f..1f) }
}
internal object CastDanmakuSource {
    fun fetch(cid: Long, open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }): List<CastDanmaku> {
        require(cid > 0)
        val connection = open("https://comment.bilibili.com/$cid.xml")
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 8000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
            require(connection.responseCode == 200) { "danmaku-unavailable" }
            val data = readBody(connection.inputStream, connection.contentEncoding)
            return parse(String(data, Charsets.UTF_8))
        } finally { connection.disconnect() }
    }
    internal fun readBody(input: java.io.InputStream, encoding: String?): ByteArray = input.buffered().use { body ->
        val limit = 8 * 1024 * 1024
        when (encoding.orEmpty().trim().lowercase(java.util.Locale.ROOT)) {
            "", "identity" -> body.readBytesBounded(limit)
            "gzip" -> GZIPInputStream(body).use { it.readBytesBounded(limit) }
            "deflate" -> {
                // The real comment endpoint returns raw DEFLATE; other CDN nodes may use the zlib wrapper.
                body.mark(2)
                val first = body.read(); val second = body.read()
                body.reset()
                val zlib = first >= 0 && second >= 0 && first and 15 == 8 && first shr 4 <= 7 &&
                    ((first shl 8) or second) % 31 == 0
                val inflater = Inflater(!zlib)
                try { InflaterInputStream(body, inflater).use { it.readBytesBounded(limit) } }
                finally { inflater.end() }
            }
            else -> error("danmaku-encoding-unsupported")
        }
    }
    fun parse(raw: String): List<CastDanmaku> {
        val root = DlnaProtocol.xml(raw, 8 * 1024 * 1024)
        val nodes = root.getElementsByTagName("d")
        return (0 until minOf(nodes.length, 100_000)).mapNotNull { index ->
            val node = nodes.item(index) as org.w3c.dom.Element
            val properties = node.getAttribute("p").split(',')
            if (properties.size < 4) return@mapNotNull null
            val seconds = properties[0].toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..604800.0 } ?: return@mapNotNull null
            val mode = properties[1].toIntOrNull()?.takeIf { it in setOf(1, 4, 5, 6) } ?: return@mapNotNull null
            val color = properties[3].toIntOrNull()?.takeIf { it in 0..0xFFFFFF } ?: return@mapNotNull null
            val text = node.textContent.replace(Regex("[\\p{Cntrl}]"), " ").take(160)
            if (text.isBlank()) null else CastDanmaku((seconds * 1000).toLong(), mode, color or 0xFF000000.toInt(), text)
        }.sortedBy { it.atMs }
    }
}
