package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import kotlin.math.floor

/** 世界坐标不依赖视口高度或已加载条目数；Long 保留长列表累计距离。 */
internal object HostBackgroundWorld {
    fun seed(band: Long, preset: HostBackgroundPreset): Long {
        var value = band + when (preset) {
            HostBackgroundPreset.NEBULA -> 7021L
            HostBackgroundPreset.METEOR -> 9043L
            else -> 5039L
        }
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    fun fractal(x: Double, y: Double): Double {
        var frequency = 1.0
        var amplitude = .5
        var result = 0.0
        repeat(6) {
            result += noise(x * frequency, y * frequency) * amplitude
            frequency *= 2.07
            amplitude *= .5
        }
        return result / .984375
    }

    private fun noise(x: Double, y: Double): Double {
        val ix = floor(x).toLong()
        val iy = floor(y).toLong()
        val dx = x - ix
        val dy = y - iy
        val sx = dx * dx * (3 - 2 * dx)
        val sy = dy * dy * (3 - 2 * dy)
        val top = hash(ix, iy) * (1 - sx) + hash(ix + 1, iy) * sx
        val bottom = hash(ix, iy + 1) * (1 - sx) + hash(ix + 1, iy + 1) * sx
        return top * (1 - sy) + bottom * sy
    }

    private fun hash(x: Long, y: Long): Double {
        val value = seed(x * 374761393L + y * 668265263L, HostBackgroundPreset.NEBULA)
        return (value ushr 11).toDouble() / 9007199254740991.0
    }
}
