package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/** 视口只保留可见区及前后一块；滚动帧只平移位图，后台任务合并到最新视口。 */
internal class HostBackgroundScene(private val config: HostBackgroundConfig, private val invalidate: () -> Unit) {
    internal data class Key(val config: HostBackgroundConfig, val night: Boolean, val width: Int, val band: Long)
    private val lock = Any()
    private var wanted = emptyList<Key>()
    private val ready = HashMap<Key, Bitmap>()
    private var working = false
    private var first = 0L
    private var last = 0L
    private var offset = 0
    private var logicalWidth = 0
    private var rasterWidth = 0
    private var theme = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    internal val isReady get() = synchronized(lock) { wanted.isNotEmpty() && wanted.filter { it.band in first..last }.all { ready.containsKey(it) } }
    internal val retainedTileCount get() = synchronized(lock) { ready.size }

    fun draw(canvas: Canvas, bounds: Rect, scroll: Long, night: Boolean, alpha: Int, filter: ColorFilter?, synchronous: Boolean) {
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val oldFirst = first
        val oldLast = last
        first = Math.floorDiv(scroll, width.toLong())
        offset = Math.floorMod(scroll, width.toLong()).toInt()
        last = first + (offset.toLong() + height - 1) / width
        val nextWidth = width.coerceAtMost(1080)
        val nextTheme = night && !config.preset.isCelestial
        val keys = synchronized(lock) {
            if (wanted.isEmpty() || oldFirst != first || oldLast != last || rasterWidth != nextWidth || theme != nextTheme) {
                val keys = (first..last).map { Key(config, nextTheme, nextWidth, it) } +
                    listOf(Key(config, nextTheme, nextWidth, last + 1), Key(config, nextTheme, nextWidth, first - 1))
                wanted = keys
                ready.keys.retainAll(keys.toSet())
                for (key in keys) if (!ready.containsKey(key)) cached(key)?.let { ready[key] = it }
            }
            wanted
        }
        logicalWidth = width
        rasterWidth = nextWidth
        theme = nextTheme
        if (synchronous) {
            for (key in keys.take((last - first + 1).toInt())) {
                if (synchronized(lock) { ready.containsKey(key) }) continue
                val bitmap = cached(key) ?: render(key).also { cache(key, it) }
                synchronized(lock) { ready[key] = bitmap }
            }
        } else startWorker()
        paint.shader = null
        paint.colorFilter = filter
        paint.color = if (config.preset.isCelestial) 0xff03040c.toInt() else if (night) 0xff10121b.toInt() else 0xffe8f3f1.toInt()
        paint.alpha = alpha
        val saved = canvas.save()
        canvas.clipRect(bounds)
        canvas.drawRect(bounds, paint)
        for (key in keys) {
            if (key.band !in first..last) continue
            val bitmap = synchronized(lock) { ready[key] } ?: continue
            val top = bounds.top + ((key.band - first) * logicalWidth - offset).toFloat()
            source.set(0, 1, bitmap.width, bitmap.height - 1)
            destination.set(bounds.left.toFloat(), top, bounds.right.toFloat(), top + logicalWidth)
            canvas.drawBitmap(bitmap, source, destination, paint)
        }
        canvas.restoreToCount(saved)
    }

    private fun startWorker() {
        synchronized(lock) {
            if (working || wanted.all { ready.containsKey(it) }) return
            working = true
        }
        worker.execute {
            while (true) {
                val key = synchronized(lock) {
                    wanted.firstOrNull { !ready.containsKey(it) }.also { if (it == null) working = false }
                } ?: break
                val bitmap = runCatching { cached(key) ?: render(key).also { cache(key, it) } }.getOrNull()
                synchronized(lock) {
                    if (bitmap == null) { working = false; return@execute }
                    // 快速 fling 时放弃过时结果，下一块直接取最新的可见区，不积压整条滚动路径。
                    if (key in wanted) ready[key] = bitmap
                }
                main.post { invalidate() }
            }
        }
    }

    companion object {
        private val cacheLock = Any()
        private val rasters = object : LruCache<Key, Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
        }
        private val worker = Executors.newFixedThreadPool(2) { Thread(it, "host-background-world").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())
        private fun cached(key: Key): Bitmap? = synchronized(cacheLock) { rasters.get(key) }
        private fun cache(key: Key, bitmap: Bitmap) = synchronized(cacheLock) { rasters.put(key, bitmap) }

        internal fun render(key: Key): Bitmap {
            val width = key.width
            val bitmap = Bitmap.createBitmap(width, width + 2, Bitmap.Config.ARGB_8888)
            val texture = HostBackgroundCosmicClouds.texture(key.config.preset, key.band, key.night)
            try {
                val canvas = Canvas(bitmap)
                canvas.translate(0f, 1f)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                paint.shader = HostBackgroundCosmicClouds.shader(texture, width.toFloat())
                canvas.drawRect(0f, -1f, width.toFloat(), width + 1f, paint)
                if (key.config.preset.isCelestial) for (band in key.band - 1..key.band + 1) {
                    val saved = canvas.save()
                    canvas.translate(0f, ((band - key.band) * width).toFloat())
                    HostBackgroundCelestialArtwork.create(key.config.preset, width.toFloat(), band)?.draw(canvas, 255, null)
                    canvas.restoreToCount(saved)
                }
                paint.shader = null
                paint.color = if (key.config.preset.isCelestial) Color.BLACK else if (key.night) 0xff10121b.toInt() else Color.WHITE
                paint.alpha = key.config.veil * 255 / 100
                canvas.drawRect(0f, -1f, width.toFloat(), width + 1f, paint)
                bitmap.setHasAlpha(false)
                bitmap.prepareToDraw()
                return bitmap
            } catch (failure: Throwable) { bitmap.recycle(); throw failure }
            finally { texture.recycle() }
        }
    }
}
