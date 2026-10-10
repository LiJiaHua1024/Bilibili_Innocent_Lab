package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import java.util.Random
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.cos

/** 按世界区带生成装饰，跨界光晕和流星由相邻区带共同绘制。 */
internal class HostBackgroundCelestialArtwork private constructor(
    preset: HostBackgroundPreset, private val width: Float, band: Long
) {
    private data class Light(val bounds: RectF, val shader: Shader)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stars: List<Light>
    private val sparkles = mutableListOf<Light>()
    private val trails = mutableListOf<Light>()
    private val sparkleColor = 0xffeaf4ff.toInt()

    init {
        val unit = width
        val random = Random(HostBackgroundWorld.seed(band, preset))
        val count = 700 + random.nextInt(251)
        stars = List(count) { index ->
            val ny = random.nextFloat()
            val nx = if (preset == HostBackgroundPreset.STARRY && index % 3 == 0) {
                val worldY = (band.toDouble() + ny) * 2 - 1
                (.5 + HostBackgroundWorld.galaxyCenter(worldY) * .5 + random.nextGaussian() * .07).toFloat()
            } else random.nextFloat()
            val x = width * nx
            val y = width * ny
            val radius = unit * (if (index % 41 == 0) .0035f + random.nextFloat() * .0025f
                else .0012f + random.nextFloat() * .0024f)
            if (index % 41 == 0) {
                val reach = radius * 4.5f
                sparkles += light(x, y, reach, radius * .65f, sparkleColor)
                sparkles += light(x, y, radius * .65f, reach, sparkleColor)
                sparkles += light(x, y, radius * 6, radius * 6, 0x404d9fff)
            }
            light(x, y, radius, radius, ColorUtils.setAlphaComponent(
                when (index % 7) { 0 -> 0xffa6d8ff.toInt(); 1 -> 0xffffdfb1.toInt(); else -> Color.WHITE },
                155 + random.nextInt(101)))
        }
        if (preset == HostBackgroundPreset.METEOR) {
            repeat(3 + random.nextInt(3)) {
                val sx = -.15f + random.nextFloat() * .6f
                val sy = random.nextFloat()
                val length = .5f + random.nextFloat() * .4f
                val angle = Math.toRadians(19.0 + random.nextDouble() * 16).toFloat()
                meteor(sx, sy, sx + cos(angle) * length, sy + sin(angle) * length,
                    unit * (.0035f + random.nextFloat() * .003f))
            }
        }
    }

    private fun light(x: Float, y: Float, rx: Float, ry: Float, color: Int): Light =
        Light(RectF(x - rx, y - ry, x + rx, y + ry), RadialGradient(0f, 0f, 1f,
            intArrayOf(color, ColorUtils.setAlphaComponent(color, Color.alpha(color) / 2), ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(x, y) })
        })

    private fun meteor(startX: Float, startY: Float, endX: Float, endY: Float, thickness: Float) {
        val sx = width * startX
        val sy = width * startY
        val ex = width * endX
        val ey = width * endY
        val length = hypot(ex - sx, ey - sy)
        val mask = RadialGradient(0f, 0f, 1f, intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(length / 2, thickness)
                postRotate(Math.toDegrees(atan2((ey - sy).toDouble(), (ex - sx).toDouble())).toFloat())
                postTranslate((sx + ex) / 2, (sy + ey) / 2)
            })
        }
        val color = LinearGradient(sx, sy, ex, ey,
            intArrayOf(0x00a0cdff, 0xff8dc9ff.toInt(), Color.WHITE), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        trails += Light(RectF(sx - thickness, sy - thickness, ex + thickness, ey + thickness),
            ComposeShader(color, mask, PorterDuff.Mode.DST_IN))
        val hx = ex - (ex - sx) * .08f
        val hy = ey - (ey - sy) * .08f
        sparkles += light(hx, hy, thickness * 7, thickness * 7, 0x703995ff)
        sparkles += light(hx, hy, thickness * 1.4f, thickness * 1.4f, Color.WHITE)
    }

    fun draw(canvas: Canvas, alpha: Int, filter: ColorFilter?) {
        paint.colorFilter = filter
        paint.color = Color.WHITE
        paint.alpha = alpha
        for (trail in trails) {
            paint.shader = trail.shader
            canvas.drawRect(trail.bounds, paint)
        }
        for (star in stars) {
            paint.shader = star.shader
            canvas.drawRect(star.bounds, paint)
        }
        for (sparkle in sparkles) {
            paint.shader = sparkle.shader
            canvas.drawRect(sparkle.bounds, paint)
        }
    }

    companion object {
        fun create(preset: HostBackgroundPreset, width: Float, band: Long): HostBackgroundCelestialArtwork? =
            when (preset) {
                HostBackgroundPreset.STARRY, HostBackgroundPreset.NEBULA, HostBackgroundPreset.METEOR ->
                    HostBackgroundCelestialArtwork(preset, width, band)
                else -> null
            }
    }
}
