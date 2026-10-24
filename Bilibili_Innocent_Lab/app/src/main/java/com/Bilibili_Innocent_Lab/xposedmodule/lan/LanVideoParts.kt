package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.net.HttpURLConnection
import org.json.JSONObject

internal data class LanVideoPart(val bvid: String, val cid: Long, val page: Int, val title: String)

/** Public UGC metadata only. Playback URLs still come exclusively from the bound host player. */
internal object LanVideoParts {
    fun fetch(bvid: String, open: (String) -> HttpURLConnection): List<LanVideoPart> {
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}")))
        val connection = open("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 8000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Referer", "https://www.bilibili.com/")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            require(connection.responseCode == 200) { "video-metadata-rejected" }
            return parse(bvid, connection.inputStream.use { String(it.readBytesBounded(2 * 1024 * 1024), Charsets.UTF_8) })
        } finally { connection.disconnect() }
    }
    fun parse(bvid: String, raw: String): List<LanVideoPart> {
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}")))
        val root = JSONObject(raw)
        require(root.getInt("code") == 0) { "video-metadata-rejected" }
        val data = root.getJSONObject("data")
        require(data.getString("bvid") == bvid) { "video-metadata-mismatch" }
        val pages = data.getJSONArray("pages")
        require(pages.length() in 1..1000) { "video-parts-unavailable" }
        val parts = (0 until pages.length()).map { index ->
            val value = pages.getJSONObject(index)
            val cid = value.getLong("cid"); val page = value.getInt("page")
            require(cid > 0 && page in 1..1000)
            val title = value.getString("part").replace(Regex("[\\p{Cntrl}]"), " ").take(160)
            LanVideoPart(bvid, cid, page, title.ifBlank { "P$page" })
        }
        require(parts.distinctBy { it.cid }.size == parts.size && parts.distinctBy { it.page }.size == parts.size)
        return parts.sortedBy { it.page }
    }
}
