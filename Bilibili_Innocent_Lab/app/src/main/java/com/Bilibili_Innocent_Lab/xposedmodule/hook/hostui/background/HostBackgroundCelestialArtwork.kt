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

/** 装饰只在尺寸或主题变化时生成，固定种子避免预览、宿主与缓存之间的星点跳动。 */
internal class HostBackgroundCelestialArtwork private constructor(
    preset: HostBackgroundPreset, private val width: Float, private val height: Float
) {
    private data class Light(val bounds: RectF, val shader: Shader)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stars: List<Light>
    private val sparkles = mutableListOf<Light>()
    private val clouds = mutableListOf<Shader>()
    private val trails = mutableListOf<Light>()
    private val sparkleColor = 0xffeaf4ff.toInt()

    init {
        val unit = minOf(width, height)
        val random = Random(when (preset) {
            HostBackgroundPreset.NEBULA -> 7021L
            HostBackgroundPreset.METEOR -> 9043L
            else -> 5039L
        })
        val count = (width * height / (unit * unit) * 800).toInt().coerceIn(800, 2600)
        stars = List(count) { index ->
            val ny = random.nextFloat()
            val nx = if (preset == HostBackgroundPreset.STARRY && index % 3 == 0) {
                val band = ny * 2 - 1
                (.5f + .21f * sin(band * 2.8f) + .065f * sin(band * 7.5f) + random.nextGaussian().toFloat() * .07f).coerceIn(.01f, .99f)
            } else random.nextFloat()
            val x = width * nx
            val y = height * ny
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
        when (preset) {
            HostBackgroundPreset.NEBULA -> {
                clouds += HostBackgroundCosmicClouds.shader(width, height, galaxy = false)
                cloud(.19f, .26f, .35f, .14f, 0xff19c9ff.toInt(), 100)
                cloud(.72f, .63f, .4f, .18f, 0xffff43b6.toInt(), 90)
            }
            HostBackgroundPreset.METEOR -> {
                cloud(.55f, .48f, .8f, .6f, 0xff163d76.toInt(), 100)
                meteor(-.08f, .04f, .6f, .23f, unit)
                meteor(.35f, .07f, .98f, .25f, unit)
                meteor(.02f, .28f, .7f, .48f, unit)
                meteor(.45f, .38f, 1.08f, .57f, unit)
                meteor(-.1f, .55f, .52f, .74f, unit)
                meteor(.25f, .65f, .96f, .87f, unit)
                meteor(-.05f, .83f, .5f, .99f, unit)
            }
            else -> clouds += HostBackgroundCosmicClouds.shader(width, height, galaxy = true)
        }
    }

    private fun light(x: Float, y: Float, rx: Float, ry: Float, color: Int): Light =
        Light(RectF(x - rx, y - ry, x + rx, y + ry), RadialGradient(0f, 0f, 1f,
            intArrayOf(color, ColorUtils.setAlphaComponent(color, Color.alpha(color) / 2), ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(rx, ry); postTranslate(x, y) })
        })

    private fun cloud(x: Float, y: Float, rx: Float, ry: Float, color: Int, alpha: Int) {
        clouds += RadialGradient(0f, 0f, 1f,
            intArrayOf(ColorUtils.setAlphaComponent(color, alpha), ColorUtils.setAlphaComponent(color, alpha / 3),
                ColorUtils.setAlphaComponent(color, 0)), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(width * rx, height * ry)
                postTranslate(width * x, height * y)
            })
        }
    }

    private fun meteor(startX: Float, startY: Float, endX: Float, endY: Float, unit: Float) {
        val sx = width * startX
        val sy = height * startY
        val ex = width * endX
        val ey = height * endY
        val thickness = unit * .005f
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
        for (cloud in clouds) {
            paint.shader = cloud
            canvas.drawRect(0f, 0f, width, height, paint)
        }
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
        fun create(preset: HostBackgroundPreset, width: Float, height: Float): HostBackgroundCelestialArtwork? =
            when (preset) {
                HostBackgroundPreset.STARRY, HostBackgroundPreset.NEBULA, HostBackgroundPreset.METEOR ->
                    HostBackgroundCelestialArtwork(preset, width, height)
                else -> null
            }
    }
}
