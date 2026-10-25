package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/**
 * 按实际成功的查询选择补偿入口，失败的时间戳调用不能充当可用性证据。
 * 每次只平移所选接口，避免宿主把位置与时延组合时重复补偿；播放期间仍受严格时钟门限约束。
 */
internal object FrameAudioClockCompensation {
    enum class Mode { UNDECIDED, TIMESTAMP, HEAD, LATENCY, SILENCE }

    fun choose(successfulTimestamps: Int, headCalls: Int, latencyCalls: Int): Mode = when {
        successfulTimestamps > 0 -> Mode.TIMESTAMP
        headCalls > 0 -> Mode.HEAD
        latencyCalls > 0 -> Mode.LATENCY
        else -> Mode.SILENCE
    }

    fun latencyMs(rawMs: Int, delayUs: Long, mode: Mode): Int =
        if (mode == Mode.LATENCY) rawMs - (delayUs / 1_000L).toInt() else rawMs
}
