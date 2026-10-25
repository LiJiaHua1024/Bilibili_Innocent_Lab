package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.File
import org.json.JSONObject

/** Only module-owned resources in the private Cron copy; never writes an official resource. */
internal data class SubtitleStylePayload(val json: String, val font: File?) {
    val fontEntry: String? get() = font?.let { "$DIRECTORY/font.${it.extension}" }
    val identity: String get() = json + ":" + (font?.length() ?: 0) + ":" + (font?.lastModified() ?: 0)
    companion object {
        const val DIRECTORY = "innocent-subtitle"
        const val CONFIG = "$DIRECTORY/style.json"
        fun read(file: File): SubtitleStylePayload = runCatching {
            val raw = file.inputStream().use { it.boundedBytes(2048) }.toString(Charsets.UTF_8)
            val style = PlayerSubtitleStyle.decode(raw) ?: return SubtitleStylePayload("null", null)
            val json = JSONObject(style.encode())
            val selected = JSONObject(raw).optString("fontPath").takeIf { path ->
                Regex("/(system|product|system_ext)/fonts/[A-Za-z0-9_.-]+\\.(ttf|otf|ttc)").matches(path)
            }?.let(::File)?.takeIf { it.isFile && it.length() in 1..50_000_000 }
            selected?.let { json.put("fontPath", "$DIRECTORY/font.${it.extension}") }
            SubtitleStylePayload(json.toString(), selected)
        }.getOrDefault(SubtitleStylePayload("null", null))
    }
}
