package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/**
 * 音画同步补偿的字节账本。插帧管线让视频落后 ijk 主时钟 delay，音频须在 AudioTrack 中同样落后：
 * 在宿主自己的 write 调用里先写入 ijk 不计数的静音，ijk 的时钟与送帧节奏因此不变，声音整体后移。
 * 目标变小（回退原生、相位变化）时丢弃同等字节追回；flush 清空硬件缓冲后已施加量归零重新补。
 *
 * 只做账本，不调 AudioTrack；所有量按 PCM 帧对齐。宿主线程串行调用，类本身仍加锁防并发。
 */
internal class FrameAudioDelay(sampleRate: Int, channels: Int, bytesPerSample: Int) {
    data class Plan(val silenceBytes: Int, val dropBytes: Int)

    private val frameBytes = channels * bytesPerSample
    private val bytesPerSecond = sampleRate.toLong() * frameBytes
    private var targetBytes = 0L
    private var appliedBytes = 0L

    init { require(sampleRate in 8_000..384_000 && channels in 1..16 && bytesPerSample in 1..4) }

    @Synchronized fun setTargetDelayUs(delayUs: Long) {
        require(delayUs in 0L..MAX_DELAY_US)
        targetBytes = align(delayUs * bytesPerSecond / 1_000_000L)
    }

    @Synchronized fun appliedDelayUs(): Long = appliedBytes * 1_000_000L / bytesPerSecond

    /** 宿主每次写入前调用：先写 silenceBytes 个零，再跳过本次数据开头的 dropBytes 后写余下部分。 */
    @Synchronized fun onWrite(requestedBytes: Int): Plan {
        if (requestedBytes <= 0) return Plan(0, 0)
        val difference = targetBytes - appliedBytes
        return when {
            difference > 0L -> {
                val silence = difference.coerceAtMost(MAX_SILENCE_PER_WRITE.toLong()).toInt()
                appliedBytes += silence
                Plan(silence, 0)
            }
            difference < 0L -> {
                val drop = align(minOf(-difference, requestedBytes.toLong())).toInt()
                appliedBytes -= drop
                Plan(0, drop)
            }
            else -> Plan(0, 0)
        }
    }

    /** AudioTrack.flush 丢弃了缓冲中的静音，需要在下一次写入时重新补上。 */
    @Synchronized fun onFlush() { appliedBytes = 0L }

    private fun align(bytes: Long): Long = bytes / frameBytes * frameBytes

    companion object {
        private const val MAX_DELAY_US = 350_000L
        private const val MAX_SILENCE_PER_WRITE = 1 shl 20
    }
}
