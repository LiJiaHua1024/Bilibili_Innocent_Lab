package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer

/**
 * 静止画面判定：相邻两帧在采样网格上几乎一致（平均差与最大差都极小）时，插值结果与原帧无可见差别，
 * 直接复制前一帧即可省掉整次推理（GPU 时间与功耗）。采样每 [STEP] 行、每 [STEP] 列一个像素的三个通道，
 * 720p 约 5.8 万个样本；稀疏网格可能漏掉极小的运动物体，此时该物体在这一槽位按源帧率显示，不会出错。
 */
internal object FrameStaticScene {
    fun isStatic(left: ByteBuffer, right: ByteBuffer, format: FrameBufferLedger.Format,
                 meanLimit: Double = MEAN_LIMIT, maxLimit: Int = MAX_LIMIT): Boolean {
        var sum = 0L
        var count = 0L
        var y = 0
        while (y < format.height) {
            val row = y * format.stride
            var x = 0
            while (x < format.width) {
                val index = row + x * 3
                for (channel in 0 until 3) {
                    val difference = kotlin.math.abs((left.get(index + channel).toInt() and 0xff) -
                        (right.get(index + channel).toInt() and 0xff))
                    if (difference > maxLimit) return false
                    sum += difference
                    count++
                }
                x += STEP
            }
            y += STEP
        }
        return count > 0 && sum.toDouble() / count <= meanLimit
    }

    private const val STEP = 4
    private const val MEAN_LIMIT = 0.5
    private const val MAX_LIMIT = 6
}
