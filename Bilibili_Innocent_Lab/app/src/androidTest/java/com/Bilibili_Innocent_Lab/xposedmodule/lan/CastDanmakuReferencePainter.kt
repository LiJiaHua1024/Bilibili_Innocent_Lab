package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** Draws into the encoded frames; DLNA receivers need no special subtitle or danmaku implementation. */
internal class CastDanmakuReferencePainter(comments: List<CastDanmaku>, width: Int, height: Int, style: CastDanmakuStyle) {
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
