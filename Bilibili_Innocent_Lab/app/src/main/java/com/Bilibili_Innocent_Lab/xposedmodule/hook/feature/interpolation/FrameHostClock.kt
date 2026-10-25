package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import kotlin.math.roundToLong

/**
 * 由宿主解码帧到达时刻推算主时钟。ijk 按音频主时钟在每帧应显示时 releaseOutputBuffer，
 * 帧到达只会因调度而偏晚、不会偏早，因此窗口内 (PTS − 到达时刻 × 倍速) 的最大值是最接近真实映射的样本（下包络）。
 *
 * 报告给 [FrameTimeline] 的误差上界是下包络的稳定度：最佳样本与第 [envelopeRank] 佳样本之差，加上限速平滑尚未
 * 追上的量。原先用「最大值 − 中位数」，那衡量的是典型迟到量而不是估计误差：宿主进程里帧普遍迟到 5–15 ms
 * （调度、捕获线程等 GPU），估计本身准确到 1 ms 时它仍报超过 8 ms，开播时只有 4 个样本更报出 60 ms 以上，
 * 有界回退因此在时钟收敛前就交还原生。若干样本都贴近下包络时估计可信；只有一个孤立的更早样本时差值大、照样拒绝。
 * 调度延迟的恒定下限会让映射整体偏晚同一常量，不影响节奏，也远小于音频补偿滞回。
 *
 * 映射整体变晚（宿主真机：GPU 忙于推理时解码输出稳定地晚一帧，窗口里先是几个原映射样本、其余整体晚 33 ms）
 * 不是时钟误差：已信任的映射保持不变，晚到只消耗流水线提前量；稳定度改取全窗口与最近样本簇中较紧的一个，
 * 映射因此不随旧样本移出窗口而判为不可信。晚到超过 [maxHoldUs] 才改用新映射（时间线随之重启）。
 * 宿主 ijk 的视频会逐步落后（33、67 ms），再丢一帧追回，到达映射呈锯齿；最早的那一档才是音画同步的映射，故只保持、不跟随。
 * 更早的样本须 [envelopeRank] 个确认才采用；锁定前孤立的早到样本照样拒绝，锁定后则忽略（不移动映射、不计入稳定度）。
 *
 * PTS 回跳、前跳超过 [discontinuityUs] 或长时间无帧后恢复（暂停/seek）都报告 DISCONTINUITY，
 * 外层须以新 epoch 重建；倍速只能由宿主已验证的设置传入，变化同样视为不连续。
 */
internal class FrameHostClock(
    private val window: Int = 32,
    private val discontinuityUs: Long = 250_000L,
    /** 首个快照前至少这么多帧（30 fps 约 0.4 s），开播解码突发不会产出不可信的映射。 */
    private val minSamples: Int = 12,
    private val envelopeRank: Int = 4,
    private val minUncertaintyUs: Long = 500L,
    /**
     * 每次快照最多移动的映射量。下包络在更早样本到达或最佳样本移出窗口时会跳 1–3 ms（真机），
     * 时间线会把超出误差预算的跳变视为目标时刻改变而撤销在途提案；限速后变化平滑，大偏差仍直接跳过去。
     */
    private val maxSlewUs: Long = 200L,
    private val jumpUs: Long = 8_000L,
    /**
     * 新样本比窗口内最佳样本还早超过此量：说明窗口里的样本全都迟到（开播时 ijk 连续突发释放若干帧，或映射真的跳变），
     * 下包络假设已不成立。此时从该样本重新积累窗口，样本够 [minSamples] 前不报快照——突发期间不会产出被拒的快照，
     * 也就不消耗有界回退的预算（宿主真机开播首个快照不确定度 98.8 ms 即由此而来）。
     */
    private val resetUs: Long = 20_000L,
    /** 最近样本簇：判断映射整体变晚、以及开播只有最早几帧落在另一映射时的稳定度。 */
    private val recentSamples: Int = 8,
    /** 已信任映射可容忍的整体晚到量（150 ms 流水线延迟内留 50 ms 余量）。 */
    private val maxHoldUs: Long = 100_000L
) {
    enum class Update { ACCEPTED, DISCONTINUITY, REJECTED }

    private val offsets = DoubleArray(window)
    private val ptsLog = LongArray(window)
    private var count = 0
    private var cursor = 0
    private var lastPtsUs = Long.MIN_VALUE
    private var lastArrivalNs = Long.MIN_VALUE
    private var speed = 1.0
    private var applied: Double? = null

    init {
        require(window in 8..256 && discontinuityUs > 0L && minSamples in 2..window && minUncertaintyUs >= 0L &&
            maxSlewUs > 0L && jumpUs > maxSlewUs && envelopeRank in 2..minSamples && resetUs > jumpUs &&
            recentSamples in envelopeRank..minSamples && maxHoldUs > jumpUs)
    }

    @Synchronized fun feed(ptsUs: Long, arrivalNs: Long, playbackSpeed: Double = 1.0): Update {
        if (ptsUs < 0L || !playbackSpeed.isFinite() || playbackSpeed <= 0.0) return Update.REJECTED
        if (count > 0 && arrivalNs < lastArrivalNs) return Update.REJECTED
        val restart = count > 0 && (playbackSpeed != speed || ptsUs < lastPtsUs ||
            predictedUs(arrivalNs)?.let { kotlin.math.abs(ptsUs - it) > discontinuityUs } != false)
        if (restart) reset()
        speed = playbackSpeed
        val offset = ptsUs - arrivalNs / 1_000.0 * speed
        // 回到已信任映射（宿主丢帧追回）不是突发：不重置。
        val returning = applied?.let { offset <= it + jumpUs } == true
        if (count > 0 && !returning && offset - (0 until count).maxOf { offsets[it] } > resetUs) {
            count = 0; cursor = 0; applied = null
        }
        offsets[cursor] = offset
        ptsLog[cursor] = ptsUs
        cursor = (cursor + 1) % window
        if (count < window) count++
        lastPtsUs = ptsUs
        lastArrivalNs = arrivalNs
        return if (restart) Update.DISCONTINUITY else Update.ACCEPTED
    }

    /** 样本不足时返回 null；discontinuity 由外层 epoch 提供，时钟本身不发明代次。 */
    @Synchronized fun snapshot(nowNs: Long, discontinuity: Long): FrameTimeline.ClockSnapshot? {
        if (count < minSamples || nowNs < lastArrivalNs) return null
        val samples = offsets.copyOf(count).also { it.sort() }
        val best = samples.last()
        val spread = best - samples[count - envelopeRank]
        val recent = DoubleArray(recentSamples) { offsets[(cursor - recentSamples + it + window) % window] }.also { it.sort() }
        val recentBest = recent.last()
        val recentSpread = recentBest - recent[recentSamples - envelopeRank]
        val previous = applied
        val offset: Double
        val instability: Double
        when {
            // 首次锁定：最早几帧（开播、捕获空闲）落在更早的映射，而最近样本一致地晚：跟随当前映射。
            previous == null && spread > jumpUs && recentSpread <= jumpUs && recentBest < best - jumpUs -> {
                offset = recentBest; instability = recentSpread
            }
            previous == null || best - previous > jumpUs && spread <= jumpUs -> { offset = best; instability = spread }
            // 锁定后出现未经确认的早到样本：保持映射。
            best - previous > jumpUs -> { offset = previous; instability = heldStability(samples, previous, spread) }
            best >= previous - jumpUs -> {
                offset = previous + (best - previous).coerceIn(-maxSlewUs.toDouble(), maxSlewUs.toDouble())
                instability = heldStability(samples, previous, spread) + kotlin.math.abs(best - offset)
            }
            // 窗口内全部样本都比已信任映射晚：映射整体变晚，限度内保持。
            previous - recentBest <= maxHoldUs -> { offset = previous; instability = heldStability(samples, previous, spread) }
            else -> { offset = recentBest; instability = recentSpread }
        }
        applied = offset
        val media = (offset + nowNs / 1_000.0 * speed).roundToLong()
        if (media < 0L) return null
        val uncertainty = maxOf(minUncertaintyUs, instability.roundToLong())
        return FrameTimeline.ClockSnapshot(media, nowNs, speed, false, discontinuity, uncertainty)
    }

    /**
     * 诊断：窗口样本按到达顺序，每项为「相对最佳样本的偏移 ms / 与上一样本的 PTS 间隔 ms」。
     * 只在时钟被拒时记录，用于区分孤立早到样本、映射整体跳变（如音频补偿改动宿主主时钟）与普遍迟到。
     */
    @Synchronized fun describe(): String {
        if (count == 0) return "[]"
        val best = (0 until count).maxOf { offsets[it] }
        val first = (cursor - count + window) % window
        return (0 until count).joinToString(",", "[", "]") { i ->
            val index = (first + i) % window
            val gap = if (i == 0) 0L else ptsLog[index] - ptsLog[(index - 1 + window) % window]
            "%.1f/%d".format(java.util.Locale.ROOT, (offsets[index] - best) / 1000.0, gap / 1000)
        }
    }

    /**
     * 锁定后的稳定度：按已信任映射把样本分成「贴近映射」与「整体晚到」两簇（更早的未确认样本不计），
     * 取较紧的一簇。锯齿追回时夹在两档之间的过渡样本只落进其中一簇，不会把两档之差算作误差；
     * 两簇都不足 envelopeRank 个样本时退回全窗口跨度。
     */
    private fun heldStability(sorted: DoubleArray, previous: Double, spread: Double): Double {
        val near = sorted.filter { it >= previous - jumpUs && it <= previous + jumpUs }.toDoubleArray()
        val late = sorted.filter { it < previous - jumpUs }.toDoubleArray()
        return minOf(tight(near), tight(late)).takeIf { it.isFinite() } ?: spread
    }

    /**
     * 簇内稳定度：去掉至多 envelopeRank − 1 个最早的离群样本后，最紧的 envelopeRank 个相邻样本的跨度。
     * 锯齿两档之间、或部分追回的过渡样本不会把两档之差当作误差；没有任何紧凑样本簇时仍然很大。
     */
    private fun tight(sorted: DoubleArray): Double {
        var result = Double.POSITIVE_INFINITY
        for (skip in 0 until envelopeRank) {
            val top = sorted.size - 1 - skip
            val bottom = top - envelopeRank + 1
            if (bottom < 0) break
            result = minOf(result, sorted[top] - sorted[bottom])
        }
        return result
    }

    @Synchronized fun reset() {
        count = 0; cursor = 0; lastPtsUs = Long.MIN_VALUE; lastArrivalNs = Long.MIN_VALUE; applied = null
    }

    private fun predictedUs(arrivalNs: Long): Long? {
        if (count == 0) return null
        val latest = offsets[(cursor - 1 + window) % window]
        return (latest + arrivalNs / 1_000.0 * speed).roundToLong()
    }
}
