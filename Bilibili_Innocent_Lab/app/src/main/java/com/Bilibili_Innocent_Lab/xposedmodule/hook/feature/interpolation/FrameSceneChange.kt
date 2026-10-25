package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 保守的切镜保护：最多采样 64×36 个 RGB 像素，直方图变化与全画面变化同时成立才跳过光流。
 * 平移通常只改变像素位置，不大幅改变颜色分布；仅平均帧差会把快速摇镜误判成转场。
 * 不尝试推断转场中的运动，也不把渐变叠化混成额外闪帧。调用者保持 before 到下一张源帧。
 */
internal object FrameSceneChange {
    fun isCut(left: ByteBuffer, right: ByteBuffer, format: FrameBufferLedger.Format): Boolean {
        val bins = IntArray(3 * 16)
        val columns = minOf(format.width, 64)
        val rows = minOf(format.height, 36)
        var changed = 0
        var totalDifference = 0L
        for (gy in 0 until rows) {
            val y = ((gy * 2L + 1) * format.height / (rows * 2L)).toInt()
            for (gx in 0 until columns) {
                val x = ((gx * 2L + 1) * format.width / (columns * 2L)).toInt()
                val offset = y * format.stride + x * 3
                var difference = 0
                for (c in 0 until 3) {
                    val a = left.get(offset + c).toInt() and 255
                    val b = right.get(offset + c).toInt() and 255
                    bins[c * 16 + (a shr 4)]++
                    bins[c * 16 + (b shr 4)]--
                    difference += abs(a - b)
                }
                totalDifference += difference
                if (difference >= 3 * 40) changed++
            }
        }
        val samples = columns * rows
        if (samples == 0) return false
        // L1 / 2 是两张颜色分布不重叠的质量，范围 0..1。
        val histogramDifference = bins.sumOf { abs(it) }.toDouble() / (samples * 6)
        return changed >= samples * 0.70 && totalDifference >= samples * 3L * 45 && histogramDifference >= 0.45
    }
}
