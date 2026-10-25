package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject

/** Host-local display preferences; no subtitle text, video identifiers or URLs are persisted. */
internal data class PlayerSubtitleStyle(
    val sizePercent: Int = 100,
    val font: String = "sans-serif",
    val bold: Boolean = false,
    val textColor: Int = 0xFFFFFF,
    val textOpacity: Int = 100,
    val backgroundColor: Int = 0x000000,
    val backgroundOpacity: Int = 50,
    val outlineColor: Int = 0x000000,
    val outlineOpacity: Int = 100,
    val outlineWidth: Int = 10
) {
    fun valid(): Boolean = sizePercent in 50..250 && validFont(font) &&
        textColor in 0..0xFFFFFF && backgroundColor in 0..0xFFFFFF && outlineColor in 0..0xFFFFFF &&
        textOpacity in 0..100 && backgroundOpacity in 0..100 && outlineOpacity in 0..100 &&
        outlineWidth in 0..60

    fun encode(): String {
        require(valid())
        return JSONObject().apply {
            put("version", 1)
            put("size", sizePercent)
            put("font", font)
            put("bold", bold)
            put("text", textColor)
            put("textOpacity", textOpacity)
            put("background", backgroundColor)
            put("backgroundOpacity", backgroundOpacity)
            put("outline", outlineColor)
            put("outlineOpacity", outlineOpacity)
            put("outlineWidth", outlineWidth)
        }.toString()
    }

    companion object {
        val FONTS = listOf("sans-serif", "NotoSerif-Regular.ttf", "DroidSansMono.ttf")
        fun validFont(value: String): Boolean = value == "sans-serif" ||
            value.length <= 128 && Regex("[A-Za-z0-9_.-]+\\.(ttf|otf|ttc)").matches(value)
        const val MAX_LENGTH = 1024

        fun argb(rgb: Int, opacity: Int): Int =
            ((opacity.coerceIn(0, 100) * 255 + 50) / 100 shl 24) or (rgb and 0xFFFFFF)

        /** Missing, future, mistyped and corrupt preferences leave the official style in control. */
        fun decode(raw: String?): PlayerSubtitleStyle? = runCatching {
            if (raw.isNullOrBlank() || raw.length > MAX_LENGTH) return null
            val json = JSONObject(raw)
            fun int(key: String): Int = (json.get(key) as? Int) ?: error("invalid-number")
            fun bool(key: String): Boolean = (json.get(key) as? Boolean) ?: error("invalid-boolean")
            if (int("version") != 1) return null
            PlayerSubtitleStyle(int("size"), json.get("font") as? String ?: return null,
                bool("bold"), int("text"), int("textOpacity"),
                int("background"), int("backgroundOpacity"), int("outline"),
                int("outlineOpacity"), int("outlineWidth")).takeIf { it.valid() }
        }.getOrNull()
    }
}
