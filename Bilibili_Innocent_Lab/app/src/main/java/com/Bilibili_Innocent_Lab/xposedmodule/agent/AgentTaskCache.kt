package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONObject
import java.util.LinkedHashMap

/** 任务内只读事实缓存；只保存有界 JSON 文本，不延长 View、Bitmap 或宿主对象生命周期。 */
internal class AgentTaskCache(private val capacity: Int, private val ttlMs: Long) {
    private data class Entry(val value: String, val observedAt: Long)
    private val entries = LinkedHashMap<String, Entry>(capacity, 0.75f, true)

    init { require(capacity in 1..128 && ttlMs in 1L..DETAIL_TTL_MS) }

    @Synchronized fun get(key: String, now: Long): JSONObject? {
        val entry = entries[key] ?: return null
        if (now < entry.observedAt || now - entry.observedAt >= ttlMs) {
            entries.remove(key)
            return null
        }
        return JSONObject(entry.value).put("cache_hit", true).put("observed_at_elapsed", entry.observedAt)
    }

    @Synchronized fun put(key: String, value: JSONObject, observedAt: Long) {
        require(observedAt >= 0)
        if (key.isBlank() || key.length > 512 || containsImage(value)) return
        val text = value.toString()
        if (text.length > MAX_ENTRY_CHARS) return
        entries[key] = Entry(text, observedAt)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun clear() = entries.clear()
    @Synchronized fun size(): Int = entries.size

    private fun containsImage(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { it == "image_data_url" || containsImage(value.opt(it)) }
        is org.json.JSONArray -> (0 until value.length()).any { containsImage(value.opt(it)) }
        is String -> value.contains("data:image/", ignoreCase = true)
        else -> false
    }

    companion object {
        const val DETAIL_CAPACITY = 64
        const val VISION_CAPACITY = 16
        const val DETAIL_TTL_MS = 120_000L
        const val VISION_TTL_MS = 30_000L
        const val MAX_ENTRY_CHARS = 16_384
    }
}
