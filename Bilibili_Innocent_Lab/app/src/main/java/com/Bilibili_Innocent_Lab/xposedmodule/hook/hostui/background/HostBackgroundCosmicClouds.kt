package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** 每个区块按全局坐标取样，同一云气跨区块连续延伸，不拉伸或重复整张图片。 */
internal object HostBackgroundCosmicClouds {
    private val palettes = mapOf(
        HostBackgroundPreset.AURORA to intArrayOf(0xffe8f3f1.toInt(), 0xffeae7fa.toInt(), 0xffa8d8cc.toInt()),
        HostBackgroundPreset.SAKURA to intArrayOf(0xfffbe8ee.toInt(), 0xffe8e9fc.toInt(), 0xffedb8d5.toInt()),
        HostBackgroundPreset.OCEAN to intArrayOf(0xffe4f3fa.toInt(), 0xffe3eafb.toInt(), 0xff8bd7d8.toInt()),
        HostBackgroundPreset.SUNSET to intArrayOf(0xffffeddf.toInt(), 0xfff3e6f7.toInt(), 0xffffbb9c.toInt()),
        HostBackgroundPreset.MIST to intArrayOf(0xffedf1ee.toInt(), 0xffe2e8ed.toInt(), 0xffb5c9be.toInt())
    )
    fun texture(preset: HostBackgroundPreset, band: Long, night: Boolean): Bitmap {
        val size = 160
        // 两边额外取样一行，双线性过滤不会在接缝处 clamp 到不同的颜色。
        val pixels = IntArray(size * (size + 2))
        for (y in 0 until size + 2) for (x in 0 until size) {
            val nx = (x + .5) / size * 2 - 1
            val ny = (band.toDouble() + (y - .5) / size) * 2 - 1
            pixels[y * size + x] = color(preset, nx, ny, night)
        }
        return Bitmap.createBitmap(pixels, size, size + 2, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }

    fun shader(texture: Bitmap, width: Float): Shader =
        BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                val scale = width / texture.width
                setScale(scale, scale)
                postTranslate(0f, -scale)
            })
        }

    internal fun color(preset: HostBackgroundPreset, nx: Double, ny: Double, night: Boolean): Int {
        val warp = HostBackgroundWorld.fractal(nx * 2 + 13, ny * 2 + 9) - .5
        val wx = nx * 3 + warp * 1.8
        val wy = ny * 3 + warp
        val field = HostBackgroundWorld.fractal(wx + 7, wy + 21)
        val detail = HostBackgroundWorld.fractal(wx * 2 + 31, wy * 2 + 4)
        if (!preset.isCelestial) {
            val palette = palettes.getValue(preset)
            val mixed = blend(blend(palette[0], palette[1], field), palette[2], detail * .7)
            return if (night) blend(mixed, 0xff10121b.toInt(), .86) else mixed
        }
        val center = .42 * sin(ny * 2.8) + .13 * sin(ny * 7.5)
        val distance = nx - center + warp * .25
        val galaxy = preset == HostBackgroundPreset.STARRY
        val envelope = exp(-distance * distance / if (galaxy) .065 else .55)
        val density = ((field - .22) * if (galaxy) 1.5 else 2.9).coerceIn(0.0, 1.0) * envelope
        val dust = ((detail - .48) * 3.5).coerceIn(0.0, .75)
        val filament = (1 - abs(detail * 2 - 1)).let { it * it * it * it * it * it }
        val intensity = (density * (1 - dust) + filament * envelope * if (galaxy) .12 else .34).coerceIn(0.0, 1.0)
        // hue 不随距离累积到纯色；连续噪声让远处仍有新的色彩变化。
        val hue = ((HostBackgroundWorld.fractal(nx + 45, ny * .3 + 17) - .25) * 2).coerceIn(0.0, 1.0)
        val red: Double
        val green: Double
        val blue: Double
        if (preset == HostBackgroundPreset.METEOR) {
            red = 20.0; green = 58.0; blue = 130.0
        } else if (galaxy) {
            red = 180 + hue * 70; green = 180 + (1 - hue) * 65; blue = 255.0
        } else if (hue < .5) {
            val t = hue * 2
            red = 18 + t * 130; green = 190 - t * 110; blue = 255.0
        } else {
            val t = (hue - .5) * 2
            red = 148 + t * 107; green = 80 + t * 70; blue = 255 - t * 150
        }
        val r = (3 + red * intensity).toInt().coerceIn(0, 255)
        val g = (4 + green * intensity).toInt().coerceIn(0, 255)
        val b = (10 + blue * intensity).toInt().coerceIn(0, 255)
        return 0xff000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun blend(a: Int, b: Int, amount: Double): Int {
        fun component(shift: Int) = (((a ushr shift) and 255) * (1 - amount) + ((b ushr shift) and 255) * amount).toInt()
        return 0xff000000.toInt() or (component(16) shl 16) or (component(8) shl 8) or component(0)
    }
}
