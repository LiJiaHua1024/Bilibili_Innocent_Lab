package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/** 代码生成的分形云气纹理只生成一次；小纹理负责云气，星点另按实际尺寸绘制。 */
internal object HostBackgroundCosmicClouds {
    private val nebula by lazy { render(galaxy = false) }
    private val galaxy by lazy { render(galaxy = true) }

    fun shader(width: Float, height: Float, galaxy: Boolean): Shader {
        val texture = if (galaxy) this.galaxy else nebula
        return BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(width / texture.width, height / texture.height) })
        }
    }

    private fun render(galaxy: Boolean): Bitmap {
        val width = 192
        val height = 384
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val nx = x.toFloat() / width * 2 - 1
            val ny = y.toFloat() / height * 2 - 1
            val warp = fractal(nx * 2 + 13, ny * 2 + 9) - .5f
            val wx = nx * 3 + warp * 1.8f
            val wy = ny * 3 + warp
            val field = fractal(wx + 7, wy + 21)
            val detail = fractal(wx * 2 + 31, wy * 2 + 4)
            val center = .42f * sin(ny * 2.8f) + .13f * sin(ny * 7.5f)
            val distance = nx - center + warp * .25f
            val envelope = exp(-distance * distance / if (galaxy) .065f else .55f)
            val density = ((field - .22f) * if (galaxy) 1.5f else 2.9f).coerceIn(0f, 1f) * envelope
            // 多尺度纹理形成明亮气丝与暗尘带，避免几个平滑圆斑看起来像普通渐变。
            val dust = ((detail - .48f) * 3.5f).coerceIn(0f, .75f)
            val filament = (1 - abs(detail * 2 - 1)).let { it * it * it * it * it * it }
            val intensity = (density * (1 - dust) + filament * envelope * if (galaxy) .12f else .34f).coerceIn(0f, 1f)
            val hue = (ny * .34f + nx * .18f + field * 1.2f + warp * .5f + .22f).coerceIn(0f, 1f)
            val red: Float
            val green: Float
            val blue: Float
            if (galaxy) {
                red = 180 + hue * 70
                green = 180 + (1 - hue) * 65
                blue = 255f
            } else if (hue < .5f) {
                val t = hue * 2
                red = 18 + t * 130
                green = 190 - t * 110
                blue = 255f
            } else {
                val t = (hue - .5f) * 2
                red = 148 + t * 107
                green = 80 + t * 70
                blue = 255 - t * 150
            }
            val r = (3 + red * intensity).toInt().coerceIn(0, 255)
            val g = (4 + green * intensity).toInt().coerceIn(0, 255)
            val b = (10 + blue * intensity).toInt().coerceIn(0, 255)
            pixels[y * width + x] = (0xff000000.toInt() or (r shl 16) or (g shl 8) or b)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }

    private fun fractal(x: Float, y: Float): Float {
        var frequency = 1f
        var amplitude = .5f
        var result = 0f
        repeat(6) {
            result += noise(x * frequency, y * frequency) * amplitude
            frequency *= 2.07f
            amplitude *= .5f
        }
        return result / .984375f
    }

    private fun noise(x: Float, y: Float): Float {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val dx = x - ix
        val dy = y - iy
        val sx = dx * dx * (3 - 2 * dx)
        val sy = dy * dy * (3 - 2 * dy)
        val top = hash(ix, iy) * (1 - sx) + hash(ix + 1, iy) * sx
        val bottom = hash(ix, iy + 1) * (1 - sx) + hash(ix + 1, iy + 1) * sx
        return top * (1 - sy) + bottom * sy
    }

    private fun hash(x: Int, y: Int): Float {
        var value = x * 374761393 + y * 668265263 + 7021
        value = (value xor (value ushr 13)) * 1274126177
        value = value xor (value ushr 16)
        return (value ushr 8) / 16777215f
    }
}
