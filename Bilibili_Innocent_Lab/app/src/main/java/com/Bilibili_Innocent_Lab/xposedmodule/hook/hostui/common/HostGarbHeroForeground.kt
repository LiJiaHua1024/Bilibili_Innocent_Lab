package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

/** 在主视觉的独立硬件层内最后裁切，视频与渐变共享一次边缘覆盖率。 */
internal class HostGarbHeroForeground(private val radius: Float, private val dimEnabled: Boolean = true) : Drawable() {
    private val scrim = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0x66000000, 0x94000000.toInt(), 0xAC000000.toInt()))
    private val corners = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    override fun onBoundsChange(bounds: Rect) {
        scrim.bounds = bounds
        corners.rewind()
        corners.fillType = Path.FillType.EVEN_ODD
        val r = radius.coerceIn(0f, minOf(bounds.width() / 2f, bounds.height().toFloat()))
        corners.addRect(RectF(bounds), Path.Direction.CW)
        // 负的顶边保留方形状态栏，只有底部两个角参与裁切。
        corners.addRoundRect(RectF(bounds.left.toFloat(), bounds.top - r,
            bounds.right.toFloat(), bounds.bottom.toFloat()), r, r, Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipRect(bounds)
        if (dimEnabled) scrim.draw(canvas)
        canvas.drawPath(corners, erase)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { scrim.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { scrim.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
