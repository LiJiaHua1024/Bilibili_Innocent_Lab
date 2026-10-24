package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

/** Draws into the encoded frames; DLNA receivers need no special subtitle or danmaku implementation. */
internal class CastDanmakuPainter(comments: List<CastDanmaku>, width: Int, height: Int, style: CastDanmakuStyle) {
    private data class Entry(val value: CastDanmaku, val lane: Int, val width: Float, val lifetime: Long)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 25f * height / 720 * style.scale; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val line = paint.textSize * 1.3f
    private val width = width.toFloat()
    private val alpha = (style.opacity * 255).toInt()
    private val entries: List<Entry>
    init {
        val lanes = (height * style.coverage / line).toInt().coerceIn(1, 40)
        val next = Array(4) { LongArray(lanes) }
        entries = comments.mapNotNull { value ->
            val group = when (value.mode) { 4 -> 1; 5 -> 2; 6 -> 3; else -> 0 }
            val lane = next[group].indices.minByOrNull { next[group][it] } ?: return@mapNotNull null
            if (next[group][lane] > value.atMs) return@mapNotNull null
            val textWidth = paint.measureText(value.text)
            // Constant pixel speed keeps later short/long comments from catching earlier comments.
            val moving = group == 0 || group == 3
            val lifetime = if (moving) ((width + textWidth) / (width / 8000f)).toLong() else 4000
            next[group][lane] = value.atMs + if (moving) ((textWidth + line) / (width / 8000f)).toLong() else lifetime
            Entry(value, lane, textWidth, lifetime)
        }
    }
    fun draw(canvas: Canvas, timeMs: Long) {
        val first = entries.binarySearchBy((timeMs - 60_000).coerceAtLeast(0)) { it.value.atMs }.let { if (it < 0) -it - 1 else it }
        for (index in first until entries.size) {
            val entry = entries[index]
            val age = timeMs - entry.value.atMs
            if (age < 0) break
            if (age > entry.lifetime) continue
            val x = when (entry.value.mode) { 4, 5 -> (width - entry.width) / 2;
                6 -> -entry.width + age * width / 8000; else -> width - age * width / 8000 }
            val y = if (entry.value.mode == 4) canvas.height - line * entry.lane - line * .3f else line * (entry.lane + 1)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = paint.textSize * .1f; paint.color = Color.BLACK; paint.alpha = alpha
            canvas.drawText(entry.value.text, x, y, paint)
            paint.style = Paint.Style.FILL; paint.color = entry.value.color; paint.alpha = alpha
            canvas.drawText(entry.value.text, x, y, paint)
        }
    }
}
