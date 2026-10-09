package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.ColorUtils
import java.lang.ref.WeakReference

/** 静态柔光，几何变化时才重建 shader；不在滚动帧中分配位图或运行模糊。 */
internal class HostBackgroundDrawable(
    private val config: HostBackgroundConfig,
    var night: Boolean = false,
    private val image: Bitmap? = null,
    private val cacheEnabled: Boolean = true
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var base: Shader? = null
    private var glow: Shader? = null
    private var accent: Shader? = null
    private val destination = RectF()
    private var shaderNight: Boolean? = null
    private var drawableAlpha = 255
    private var key: HostBackgroundRasterCache.Key? = null
    private var raster: Bitmap? = null
    internal val isRasterReady get() = raster != null
    private var requestScheduled = false
    private var requestedKey: HostBackgroundRasterCache.Key? = null
    private val cacheRequest = Runnable {
        requestScheduled = false
        val current = key ?: return@Runnable
        if (requestedKey == current) return@Runnable
        requestedKey = current
        val owner = WeakReference(this)
        HostBackgroundRasterCache.request(current) { bitmap ->
            owner.get()?.takeIf { it.key == current }?.let { drawable ->
                drawable.raster = bitmap
                if (bitmap != null) drawable.invalidateSelf()
            }
        }
    }

    override fun onBoundsChange(bounds: Rect) { rebuild() }

    private fun rebuild() {
        unscheduleSelf(cacheRequest)
        requestScheduled = false
        requestedKey = null
        raster = null
        val opaqueImage = config.preset == HostBackgroundPreset.CUSTOM && image != null && !image.hasAlpha()
        key = if (cacheEnabled && opaqueImage) {
            // 只合成源图遮罩，保留原来的 GPU 缩放取样；尺寸动画也可复用。
            HostBackgroundRasterCache.Key(config, night, image.width, image.height, image)
        } else if (cacheEnabled && image == null && bounds.left == 0 && bounds.top == 0 && bounds.width() > 0 && bounds.height() > 0 &&
            bounds.width().toLong() * bounds.height() <= 4_000_000) {
            HostBackgroundRasterCache.Key(config, night, bounds.width(), bounds.height(), image)
        } else null
        key?.let { raster = HostBackgroundRasterCache.get(it) }
        shaderNight = night
        val colors = when (config.preset) {
            HostBackgroundPreset.SAKURA -> intArrayOf(0xfffbe8ee.toInt(), 0xffe8e9fc.toInt(), 0xffedb8d5.toInt())
            HostBackgroundPreset.OCEAN -> intArrayOf(0xffe4f3fa.toInt(), 0xffe3eafb.toInt(), 0xff8bd7d8.toInt())
            HostBackgroundPreset.SUNSET -> intArrayOf(0xffffeddf.toInt(), 0xfff3e6f7.toInt(), 0xffffbb9c.toInt())
            HostBackgroundPreset.MIST -> intArrayOf(0xffedf1ee.toInt(), 0xffe2e8ed.toInt(), 0xffb5c9be.toInt())
            else -> intArrayOf(0xffe8f3f1.toInt(), 0xffeae7fa.toInt(), 0xffa8d8cc.toInt())
        }
        if (night) for (i in colors.indices) colors[i] = ColorUtils.blendARGB(colors[i], 0xff10121b.toInt(), .86f)
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        base = LinearGradient(0f, 0f, w, h, colors[0], colors[1], Shader.TileMode.CLAMP)
        glow = RadialGradient(w * .05f, h * .26f, maxOf(w, h) * .65f,
            colors[2], Color.TRANSPARENT, Shader.TileMode.CLAMP)
        accent = RadialGradient(w * .95f, h * .78f, maxOf(w, h) * .52f,
            ColorUtils.setAlphaComponent(colors[1], 180), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        image?.let {
            val scale = maxOf(w / it.width, h / it.height)
            val dx = (w - it.width * scale) / 2
            val dy = (h - it.height * scale) / 2
            destination.set(dx, dy, dx + it.width * scale, dy + it.height * scale)
            destination.offset(bounds.left.toFloat(), bounds.top.toFloat())
        }
    }

    override fun draw(canvas: Canvas) {
        if (shaderNight != night) rebuild()
        if (drawableAlpha == 0) return
        paint.alpha = drawableAlpha
        if (canvas.isHardwareAccelerated && drawableAlpha == 255 && paint.colorFilter == null) {
            raster?.let {
                paint.shader = null
                if (config.preset == HostBackgroundPreset.CUSTOM && image != null) canvas.drawBitmap(it, null, destination, paint)
                else canvas.drawBitmap(it, bounds.left.toFloat(), bounds.top.toFloat(), paint)
                return
            }
            if (key != null && requestedKey != key && !requestScheduled && callback != null) {
                requestScheduled = true
                scheduleSelf(cacheRequest, SystemClock.uptimeMillis() + 150)
            }
        }
        // 只有完全不透明且没有淡出/滤镜时，图片才确定覆盖底色。
        if (config.preset != HostBackgroundPreset.CUSTOM || image == null || image.hasAlpha() ||
            drawableAlpha != 255 || paint.colorFilter != null) {
            paint.shader = base
            canvas.drawRect(bounds, paint)
        }
        if (config.preset == HostBackgroundPreset.CUSTOM && image != null) {
            paint.shader = null
            canvas.drawBitmap(image, null, destination, paint)
        } else {
            paint.shader = glow
            canvas.drawRect(bounds, paint)
            paint.shader = accent
            canvas.drawRect(bounds, paint)
        }
        paint.shader = null
        if (config.veil == 0) return
        paint.color = ColorUtils.setAlphaComponent(if (night) 0xff10121b.toInt() else Color.WHITE,
            (config.veil * drawableAlpha / 100).coerceIn(0, 255))
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) { drawableAlpha = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
