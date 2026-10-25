package com.Bilibili_Innocent_Lab.xposedmodule.lan

/** Media-time layout, independent of Canvas/GLES. Advancing a frame visits only active comments. */
internal class CastDanmakuLayout(comments: List<CastDanmaku>, private val width: Int,
    private val height: Int, style: CastDanmakuStyle, measure: (String) -> Float) {
    data class Entry(val value: CastDanmaku, val lane: Int, val width: Float, val lifetime: Long)
    private val line = 25f * height / 720 * style.scale * 1.3f
    private val entries: List<Entry>
    private val active = ArrayList<Entry>()
    private var cursor = 0
    private var previousTime = Long.MIN_VALUE
    private val longestLifetime: Long

    init {
        require(width > 0 && height > 0)
        val lanes = (height * style.coverage / line).toInt().coerceIn(1, 40)
        val next = Array(4) { LongArray(lanes) }
        entries = comments.sortedBy { it.atMs }.mapNotNull { value ->
            val group = when (value.mode) { 4 -> 1; 5 -> 2; 6 -> 3; else -> 0 }
            val lane = next[group].indices.minByOrNull { next[group][it] } ?: return@mapNotNull null
            if (next[group][lane] > value.atMs) return@mapNotNull null
            val textWidth = measure(value.text)
            require(textWidth.isFinite() && textWidth >= 0)
            val moving = group == 0 || group == 3
            val lifetime = if (moving) ((width + textWidth) / (width / 8000f)).toLong() else 4000
            next[group][lane] = value.atMs + if (moving) ((textWidth + line) / (width / 8000f)).toLong() else lifetime
            Entry(value, lane, textWidth, lifetime)
        }
        longestLifetime = entries.maxOfOrNull { it.lifetime } ?: 0
    }

    fun visit(timeMs: Long, draw: (Entry, Float, Float) -> Unit) {
        require(timeMs >= 0)
        if (previousTime == Long.MIN_VALUE || timeMs < previousTime || timeMs - previousTime > longestLifetime) {
            active.clear()
            val earliest = (timeMs - longestLifetime).coerceAtLeast(0)
            // Lower bound includes every comment at the same timestamp, unlike binarySearchBy.
            var low = 0; var high = entries.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (entries[mid].value.atMs < earliest) low = mid + 1 else high = mid
            }
            cursor = low
        }
        while (cursor < entries.size && entries[cursor].value.atMs <= timeMs) active += entries[cursor++]
        var index = 0
        while (index < active.size) {
            val entry = active[index]
            val age = timeMs - entry.value.atMs
            if (age > entry.lifetime) { active.removeAt(index); continue }
            val x = when (entry.value.mode) {
                4, 5 -> (width - entry.width) / 2
                6 -> -entry.width + age * width / 8000f
                else -> width - age * width / 8000f
            }
            val y = if (entry.value.mode == 4) height - line * entry.lane - line * .3f else line * (entry.lane + 1)
            draw(entry, x, y)
            index++
        }
        previousTime = timeMs
    }
}

/** Bounded shelf atlas. Flush queued draws BEFORE reusing pixels; already submitted GL draws stay ordered. */
internal class CastAtlasCache<K>(val side: Int, private val beforeReset: () -> Unit) {
    data class Region(val x: Int, val y: Int, val width: Int, val height: Int)
    private val regions = HashMap<K, Region>()
    private var x = 0; private var y = 0; private var rowHeight = 0
    var resets = 0; private set
    val size get() = regions.size
    init { require(side >= 4) }

    fun obtain(key: K, width: Int, height: Int, upload: (Region) -> Unit): Region {
        require(width in 1..side && height in 1..side)
        regions[key]?.let { check(it.width == width && it.height == height); return it }
        if (x + width > side) { x = 0; y += rowHeight; rowHeight = 0 }
        if (y + height > side) {
            beforeReset()
            regions.clear(); x = 0; y = 0; rowHeight = 0; resets++
        }
        val region = Region(x, y, width, height)
        upload(region)
        regions[key] = region
        x += width; rowHeight = maxOf(rowHeight, height)
        return region
    }
}
