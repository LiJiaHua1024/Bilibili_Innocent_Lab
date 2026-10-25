package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class PlayerSubtitleStyleTest {
    @Test fun `style preserves independent alpha channels and font choices`() {
        for (font in PlayerSubtitleStyle.FONTS) {
            val style = PlayerSubtitleStyle(sizePercent = 250, font = font, bold = true,
                textColor = 0x123456, textOpacity = 17, backgroundOpacity = 83,
                outlineOpacity = 42, outlineWidth = 60)
            assertEquals(style, PlayerSubtitleStyle.decode(style.encode()))
        }
        assertEquals(0x00123456, PlayerSubtitleStyle.argb(0x123456, 0))
        assertEquals(0xFF123456.toInt(), PlayerSubtitleStyle.argb(0x123456, 100))
        assertEquals(0x80123456.toInt(), PlayerSubtitleStyle.argb(0x123456, 50))
    }

    @Test fun `corrupt or future settings preserve official rendering`() {
        val valid = PlayerSubtitleStyle().encode()
        for (raw in listOf(null, "", "{}", "[1]", "x".repeat(1025),
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace("\"size\":100", "\"size\":999"),
            valid.replace("\"size\":100", "\"size\":100.5"),
            valid.replace("\"size\":100", "\"size\":\"100\""),
            valid.replace("\"font\":\"sans-serif\"", "\"font\":\"/tmp/font.ttf\""),
            valid.replace("\"textOpacity\":100", "\"textOpacity\":-1"))) {
            assertNull(raw, PlayerSubtitleStyle.decode(raw))
        }
    }
}
