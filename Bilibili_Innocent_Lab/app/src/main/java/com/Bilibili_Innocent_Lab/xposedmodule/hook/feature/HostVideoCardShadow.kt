package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 一次生成柔影、以 NinePatch 拉伸复用；列表滚动时只画已缓存的纹理。 */
internal fun interface HostVideoCardShadowRadius {
    fun radius(view: View): Float
}

internal class HostVideoCardShadow(
    parent: ViewGroup,
    private val radiusOf: HostVideoCardShadowRadius
) : Drawable() {
    private class Shadow(val drawable: NinePatchDrawable, val padding: Int)
    private val parent = WeakReference(parent)
    private val density = parent.resources.displayMetrics.density
    private val resources = parent.resources
    private val shadows = SparseArray<Shadow>(4)
    private val keys = ArrayDeque<Int>(4)

    /** 在卡片首次就绪时生成；draw 热路径既不创建纹理，也不分配对象。 */
    fun prepare(radius: Float) {
        val key = radius.roundToInt().coerceAtLeast(0)
        if (shadows.get(key) != null) return
        val blur = 8f * density
        val offset = 2f * density
        val padding = ceil(blur * 2 + offset).toInt()
        val fixed = key + padding + 2
        val size = fixed * 2 + 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bitmap.density = resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        val rect = RectF(padding.toFloat(), padding.toFloat(),
            (size - padding).toFloat(), (size - padding).toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            setShadowLayer(blur, 0f, offset, Color.argb(22, 112, 112, 112))
        }
        canvas.drawRoundRect(rect, key.toFloat(), key.toFloat(), paint)
        paint.clearShadowLayer()
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawRoundRect(rect, key.toFloat(), key.toFloat(), paint)
        val chunk = ByteBuffer.allocate(84).order(ByteOrder.nativeOrder()).apply {
            put(1.toByte()); put(2.toByte()); put(2.toByte()); put(9.toByte())
            putInt(0); putInt(0)
            repeat(4) { putInt(0) }
            putInt(0)
            putInt(fixed); putInt(size - fixed)
            putInt(fixed); putInt(size - fixed)
            repeat(9) { putInt(1) } // NinePatch.NO_COLOR
        }.array()
        shadows.put(key, Shadow(NinePatchDrawable(resources, bitmap, chunk, Rect(), "video-card-shadow"), padding))
        keys.addLast(key)
        // 常见双列/横排共用少数半径，限制不同窗口尺寸产生的缓存数量。
        if (keys.size > 4) shadows.remove(keys.removeFirst())
    }

    override fun draw(canvas: Canvas) {
        val parent = parent.get() ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val radius = radiusOf.radius(child)
            if (radius < 0f || child.alpha <= 0f || child.visibility != android.view.View.VISIBLE) continue
            val shadow = shadows.get(radius.roundToInt()) ?: continue
            val padding = shadow.padding
            val left = (child.left + child.translationX).roundToInt()
            val top = (child.top + child.translationY).roundToInt()
            shadow.drawable.setBounds(left - padding, top - padding,
                left + child.width + padding, top + child.height + padding)
            val alpha = (child.alpha * 255).roundToInt()
            if (shadow.drawable.alpha != alpha) shadow.drawable.alpha = alpha
            shadow.drawable.draw(canvas)
        }
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
