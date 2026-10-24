package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import androidx.core.graphics.withSave

/** Business pictograms only; all panel materials and motion are owned by Lumen. */
internal enum class LanGlyph(val data: String) {
    CAST("M3,9V5Q3,3 5,3H19Q21,3 21,5V17Q21,19 19,19H15 M3,15Q9,15 9,21 M3,11Q13,11 13,21 M3,19H3.1"),
    TV("M5,4H19Q21,4 21,6V16Q21,18 19,18H5Q3,18 3,16V6Q3,4 5,4 M8,22H16 M12,18V22"),
    PLAY("M9,5L19,12L9,19Z"), PAUSE("M8,5V19 M16,5V19"),
    CLOSE("M6,6L18,18 M18,6L6,18"), BACK("M14,5L7,12L14,19"),
    SCAN("M4,8V5Q4,3 6,3H9 M15,3H18Q20,3 20,5V8 M20,16V19Q20,21 18,21H15 M9,21H6Q4,21 4,19V16 M8,12H16"),
    TUNE("M4,6H8 M12,6H20 M4,12H14 M18,12H20 M4,18H6 M10,18H20 M10,3V9 M16,9V15 M8,15V21"),
    QUEUE("M5,5H19 M5,11H19 M5,17H12 M17,15L21,18L17,21Z"),
    LINK("M9,15L15,9 M8,16L6,18Q3,21 1,18Q-1,15 2,12L6,8Q9,5 12,8 M16,8L18,6Q21,3 23,6Q25,9 22,12L18,16Q15,19 12,16"),
    SHIELD("M12,2L21,6V12Q21,19 12,22Q3,19 3,12V6Z M8,12L11,15L16,9"),
    COPY("M9,8H19Q21,8 21,10V20Q21,22 19,22H9Q7,22 7,20V10Q7,8 9,8 M4,16H3V2H16V4"),
    VOLUME("M3,9H7L12,5V19L7,15H3Z M16,8Q20,12 16,16 M19,5Q25,12 19,19"),
    STOP("M7,5H17Q19,5 19,7V17Q19,19 17,19H7Q5,19 5,17V7Q5,5 7,5"),
    CHEVRON("M9,6L15,12L9,18"), INFO("M12,8H12.1 M12,11V17 M22,12A10,10 0,1 1,2,12A10,10 0,1 1,22,12"),
    KEY("M14,10A5,5 0,1 1,4,10A5,5 0,1 1,14,10 M14,10H22 M19,10V14 M22,10V13"),
    SEEK("M12,3A9,9 0,1 1,3,12 M3,4V12H11 M12,7V12L16,14")
}

internal class LanGlyphDrawable(glyph: LanGlyph, color: Int, private val size: Int) : Drawable() {
    private val path = checkNotNull(PathParser.createPathFromPathData(glyph.data))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) = canvas.withSave {
        translate(bounds.left.toFloat(), bounds.top.toFloat())
        scale(bounds.width() / 24f, bounds.height() / 24f)
        drawPath(path, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
}
