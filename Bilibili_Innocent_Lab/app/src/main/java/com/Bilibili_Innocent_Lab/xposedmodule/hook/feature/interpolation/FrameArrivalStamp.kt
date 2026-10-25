package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/**
 * 帧到达时刻：优先用独立计时线程收到 onFrameAvailable 的时刻（notified），而不是捕获线程锁存之后（latched）。
 *
 * 1080p 插帧时捕获线程的读回排在 GPU 推理之后，每个回调要等上一帧捕获做完才开始处理，锁存时刻因此稳定地比
 * 真实到达晚约一个帧间隔（宿主真机：窗口里 3 个样本在原映射、其余 29 个整体晚 33.4 ms），时钟按下包络判为
 * 不可信并反复重启，最终回退原生。通知时刻不受捕获线程排队影响。
 *
 * 通知与锁存一一对应（回调按入队顺序投递、GLConsumer 先进先出锁存）。只有当 BufferQueue 用新帧顶替了未取走的旧帧，
 * 锁存的帧才可能比通知所属的帧新，那时通知时刻会伪造提前到达；顶替表现为 PTS 间隔不等于上一间隔，
 * 此时改用锁存时刻（只会偏晚，下包络安全）。首帧同理用锁存时刻。
 */
internal class FrameArrivalStamp {
    private var lastPtsNs = Long.MIN_VALUE
    private var lastGapNs = 0L

    fun arrival(ptsNs: Long, notifiedNs: Long, latchedNs: Long): Long {
        val gap = if (lastPtsNs == Long.MIN_VALUE) 0L else ptsNs - lastPtsNs
        val consistent = gap > 0L && lastGapNs > 0L && kotlin.math.abs(gap - lastGapNs) * 4 <= lastGapNs
        lastPtsNs = ptsNs
        if (gap > 0L) lastGapNs = gap
        return if (consistent && notifiedNs <= latchedNs) notifiedNs else latchedNs
    }

    fun reset() {
        lastPtsNs = Long.MIN_VALUE
        lastGapNs = 0L
    }
}
