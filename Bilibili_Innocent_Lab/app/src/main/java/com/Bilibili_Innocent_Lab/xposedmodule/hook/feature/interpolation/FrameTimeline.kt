package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * 纯数据的真实时间输出槽位；不取时钟、不持宿主/GPU 对象，也不实际绘制或生成图像。
 * 外层必须验证主时钟与媒体 PTS 的映射，并串行协调最后一次 valid 与实际 presentation。
 * 返回的 discardedIds 只撤销本时间线的帧所有权；worker/fence 尚持有的资源仍由外层延后回收。
 */
internal class FrameTimeline(
    targetFps: Int,
    private val maxClockAgeNs: Long = 250_000_000L,
    /**
     * 深流水线：槽位在墙钟 due 呈现 due − delay 时刻的媒体内容，并允许最多提前 maxLeadNs 规划，
     * 推理预算由一个目标周期放宽到 lead。视频因此落后主时钟 delay，外层必须同步延后音频。
     */
    private val presentationDelayNs: Long = 0L,
    private val maxLeadNs: Long? = null,
    /**
     * 起步或错过槽位后至少提前这么多再规划：只放宽上限时规划器总取下一个未过槽位，提前量不足一个周期，
     * 推理永远赶不上（真机实测平均提前 6.9 ms、推理 27 ms）。稳态下提案自然保持在该提前量之前。
     */
    private val minLeadNs: Long = 0L,
    /** 槽位媒体时刻与某原帧 PTS 相差不超过该值时直接呈现原帧，不做推理。 */
    private val originalToleranceUs: Long = 0L,
    /** 每个 epoch 首次规划时把延迟再增加不足一个源帧间隔，使目标槽位网格对齐原帧 PTS。 */
    private val phaseLock: Boolean = false,
    private val maxClockUncertaintyUs: Long = MAX_CLOCK_UNCERTAINTY_US,
    private val frameWindow: Int = MAX_FRAMES
) {
    data class Epoch(val media: Long, val decoder: Long, val surface: Long, val format: Long, val discontinuity: Long)

    /**
     * speed 是已验主时钟实际推进率；不能用配置倍速或轮询播放进度伪造证据。
     * uncertaintyUs 是已证明的媒体时间误差上界（含量化），本层最多接纳 2 ms；不能省略或缩报误差。
     */
    data class ClockSnapshot(val mediaUs: Long, val monotonicNs: Long, val speed: Double,
                             val paused: Boolean, val discontinuity: Long, val uncertaintyUs: Long = 0L)

    /** id 唯一标识捕获帧，不能在旧任务/fence 释放前复用为另一张图；PTS 以微秒表示。 */
    data class Frame(val id: Long, val ptsUs: Long)
    class Lease internal constructor(internal val timeline: Any, val epoch: Epoch)
    data class Begin(val lease: Lease, val discardedIds: List<Long>)
    data class Offered(val accepted: Boolean, val discardedIds: List<Long>)
    enum class ClockState { READY, PAUSED, NO_CLOCK, STALE_LEASE, STALE_SNAPSHOT, RESTART_REQUIRED }
    data class ClockUpdate(val state: ClockState, val discardedIds: List<Long> = emptyList())
    enum class Reason { DISABLED, STALE_LEASE, NO_CLOCK, PAUSED, STALE_CLOCK, INVALID_TIME,
        IN_FLIGHT, AHEAD_OF_WINDOW, NO_PREVIOUS_FRAME, NO_FUTURE_FRAME }
    enum class Mode { ORIGINAL, INTERPOLATE }
    sealed interface Decision {
        data class Waiting(val reason: Reason, val dueNs: Long? = null, val mediaUs: Long? = null) : Decision
        data class Ready(val proposal: Proposal) : Decision
    }
    class Proposal internal constructor(internal val lease: Lease, internal val tick: Long,
        internal val plannedAtNs: Long, val dueNs: Long, val mediaUs: Long,
        val before: Frame, val after: Frame, val alpha: Double, val uncertaintyUs: Long, val mode: Mode) {
        val isOriginal: Boolean get() = mode == Mode.ORIGINAL
    }

    private val identity = Any()
    private var fps = targetFps.also { require(it in SUPPORTED_FPS) }
    private var lease: Lease? = null
    private var clock: ClockSnapshot? = null
    private var anchorNs: Long? = null
    private var lastObservedNs: Long? = null
    private var nextTick = 0L
    private var pending: Proposal? = null
    private val frames = ArrayList<Frame>(frameWindow)
    private var phaseOffsetUs = 0L
    private var phaseLocked = false
    /** 诊断计数：规划出的原帧/插帧槽位、相位跟踪对齐次数、因缺前后帧而等待次数。跨 epoch 累计。 */
    /** skippedLead：补帧槽位剩余提前量不足而跳过；skippedBehind：规划落后于时钟而越过的槽位。两者都会让上一帧多停一刷新。 */
    data class Counters(val original: Long, val interpolate: Long, val snapped: Long, val noFuture: Long, val noPrevious: Long,
                        val skippedLead: Long = 0L, val skippedBehind: Long = 0L)
    private var plannedOriginal = 0L
    private var plannedInterpolate = 0L
    private var snapped = 0L
    private var waitedFuture = 0L
    private var waitedPrevious = 0L
    private var skippedLead = 0L
    private var skippedBehind = 0L
    @Synchronized fun counters() = Counters(plannedOriginal, plannedInterpolate, snapped, waitedFuture, waitedPrevious,
        skippedLead, skippedBehind)

    init {
        require(frameWindow in 4..12)
        require(maxClockAgeNs > 0L && presentationDelayNs in 0L..MAX_DELAY_NS && (maxLeadNs ?: 1L) in 1L..MAX_DELAY_NS &&
            minLeadNs >= 0L && minLeadNs <= (maxLeadNs ?: ((NS_PER_SECOND + targetFps - 1L) / targetFps.coerceAtLeast(1))) &&
            originalToleranceUs >= 0L && maxClockUncertaintyUs in 0L..MAX_CONFIGURABLE_UNCERTAINTY_US)
    }

    /** 当前生效的媒体延迟（墙钟延迟换算 + 相位锁定量），供外层同步音频。 */
    @Synchronized fun effectiveDelayUs(speed: Double = clock?.speed ?: 1.0): Long =
        (presentationDelayNs / 1_000.0 * speed).roundToLong() + phaseOffsetUs

    /** 所有媒体/解码/Surface/格式代次及目标变化均从此进入；相同 Epoch 重进也不复用旧 lease。 */
    @Synchronized fun begin(epoch: Epoch, targetFps: Int = fps): Begin {
        require(targetFps in SUPPORTED_FPS)
        val discarded = reset()
        fps = targetFps
        return Begin(Lease(identity, epoch).also { lease = it }, discarded)
    }

    @Synchronized fun close(expected: Lease): List<Long> = if (current(expected)) reset() else emptyList()

    /**
     * 仅接收已验真的主时钟。暂停/倍率变化、回跳及 discontinuity 使旧帧任务也失效，须 begin 后重建。
     * 正常刷新只在目标媒体时刻改变时撤销提案；迟到旧快照不能影响当前提案。
     */
    @Synchronized fun updateClock(expected: Lease, snapshot: ClockSnapshot?): ClockUpdate {
        if (!current(expected)) return ClockUpdate(ClockState.STALE_LEASE)
        if (snapshot == null) return ClockUpdate(ClockState.NO_CLOCK, reset())
        if (snapshot.mediaUs < 0L || snapshot.uncertaintyUs !in 0L..maxClockUncertaintyUs ||
            !snapshot.speed.isFinite() || snapshot.speed < 0.0 ||
            (!snapshot.paused && snapshot.speed == 0.0) || snapshot.discontinuity != expected.epoch.discontinuity)
            return ClockUpdate(ClockState.RESTART_REQUIRED, reset())
        val previous = clock
        if (previous != null && snapshot.monotonicNs < previous.monotonicNs)
            return ClockUpdate(ClockState.STALE_SNAPSHOT)
        if (previous != null) {
            val budget = previous.uncertaintyUs + snapshot.uncertaintyUs
            if (snapshot.speed != previous.speed || snapshot.paused != previous.paused ||
                snapshot.mediaUs < previous.mediaUs && !within(snapshot.mediaUs, previous.mediaUs, budget) ||
                snapshot.monotonicNs == previous.monotonicNs && !within(snapshot.mediaUs, previous.mediaUs, budget))
                return ClockUpdate(ClockState.RESTART_REQUIRED, reset())
        }
        // 始终对照最初提案，不能在连续小修正中累积扩大误差。
        pending?.let { proposal ->
            val updatedMedia = slotMedia(snapshot, proposal.dueNs)
            if (updatedMedia == null || !within(updatedMedia, proposal.mediaUs,
                    proposal.uncertaintyUs + snapshot.uncertaintyUs)) pending = null
        }
        clock = snapshot
        if (anchorNs == null) { anchorNs = snapshot.monotonicNs; nextTick = 0L }
        return ClockUpdate(if (snapshot.paused) ClockState.PAUSED else ClockState.READY)
    }

    /** 四帧有界窗口。替换、淘汰与拒绝的新帧均返回 ID，重复的在持 ID 不会被错误释放。 */
    @Synchronized fun offer(expected: Lease, frame: Frame): Offered {
        if (frames.any { it.id == frame.id }) return Offered(false, emptyList())
        if (!current(expected) || fps == 0 || frame.ptsUs < 0L) return Offered(false, listOf(frame.id))
        val sameTime = frames.indexOfFirst { it.ptsUs == frame.ptsUs }
        val discarded = when {
            sameTime >= 0 -> listOf(frames[sameTime].id).also { frames[sameTime] = frame }
            frames.lastOrNull()?.let { frame.ptsUs < it.ptsUs } == true -> return Offered(false, listOf(frame.id))
            else -> {
                val removed = if (frames.size == frameWindow) listOf(frames.removeAt(0).id) else emptyList()
                frames += frame
                removed
            }
        }
        if (pending?.let { it.before.id in discarded || it.after.id in discarded } == true) pending = null
        return Offered(true, discarded)
    }

    /**
     * 只预留一个提案，最多领先一个目标周期；错过的槽位直接跳过，不突发补交历史帧。
     * 高频段选择最近原帧（平局取前帧）；低频段 alpha 仅描述运动补偿输入，不证明已生成/呈现。
     */
    @Synchronized fun plan(expected: Lease, nowNs: Long): Decision {
        if (!current(expected)) return Decision.Waiting(Reason.STALE_LEASE)
        if (fps == 0) return Decision.Waiting(Reason.DISABLED)
        val actualClock = clock ?: return Decision.Waiting(Reason.NO_CLOCK)
        if (actualClock.paused) return Decision.Waiting(Reason.PAUSED)
        if (!observe(nowNs)) return Decision.Waiting(Reason.INVALID_TIME)
        val age = difference(nowNs, actualClock.monotonicNs) ?: return Decision.Waiting(Reason.INVALID_TIME)
        if (age < 0L) return Decision.Waiting(Reason.INVALID_TIME)
        if (age > maxClockAgeNs) return Decision.Waiting(Reason.STALE_CLOCK)
        pending?.let {
            if (nowNs <= it.dueNs) return Decision.Waiting(Reason.IN_FLIGHT, it.dueNs, it.mediaUs)
            pending = null
        }
        val start = anchorNs ?: return Decision.Waiting(Reason.NO_CLOCK)
        val elapsed = difference(nowNs, start) ?: return Decision.Waiting(Reason.INVALID_TIME)
        if (elapsed < 0L) return Decision.Waiting(Reason.INVALID_TIME)
        val firstCurrent = firstTick(elapsed) ?: return Decision.Waiting(Reason.INVALID_TIME)
        if (firstCurrent > nextTick && plannedOriginal + plannedInterpolate > 0L) skippedBehind += firstCurrent - nextTick
        nextTick = maxOf(nextTick, firstCurrent)
        val maxLead = maxLeadNs ?: ((NS_PER_SECOND + fps - 1L) / fps)
        // 最小提前量只约束需要推理的槽位：原帧无需推理，若也跳过会丢掉原帧、让插帧比例上升并掉帧（真机实测）。
        repeat(MAX_SLOT_SCAN) {
            val due = slotTime(start, nextTick) ?: return Decision.Waiting(Reason.INVALID_TIME)
            val ahead = difference(due, nowNs) ?: return Decision.Waiting(Reason.INVALID_TIME)
            if (ahead > maxLead) return Decision.Waiting(Reason.AHEAD_OF_WINDOW, due)
            if (phaseLock && !phaseLocked) {
                // 锁到不晚于首个槽位的最近原帧：首槽即为原帧，额外延迟不超过一个源帧间隔。
                val base = slotMedia(actualClock, due) ?: return Decision.Waiting(Reason.INVALID_TIME)
                val reference = frames.lastOrNull { it.ptsUs <= base }
                    ?: return Decision.Waiting(Reason.NO_PREVIOUS_FRAME, due, base)
                phaseOffsetUs = base - reference.ptsUs
                phaseLocked = true
            }
            var media = slotMedia(actualClock, due) ?: return Decision.Waiting(Reason.INVALID_TIME)
            if (phaseLock) {
                // 相位跟踪：主时钟估计会缓慢漂移数毫秒（真机），一次性锁相后原帧槽位逐渐偏出容差而被当作插帧，
                // 推理负载翻倍、输出掉到约 43 fps。落在半个目标周期内的原帧直接对齐，并把误差并入相位偏移。
                val periodUs = 1_000_000.0 * actualClock.speed / fps
                val nearest = frames.minByOrNull { kotlin.math.abs(it.ptsUs - media) }
                if (nearest != null && nearest.ptsUs != media && kotlin.math.abs(nearest.ptsUs - media) < periodUs / 2) {
                    val corrected = phaseOffsetUs + (media - nearest.ptsUs)
                    if (corrected in -periodUs.roundToLong()..MAX_PHASE_OFFSET_US) {
                        phaseOffsetUs = corrected
                        media = nearest.ptsUs
                        snapped++
                    }
                }
            }
            val exact = frames.minByOrNull { kotlin.math.abs(it.ptsUs - media) }
                ?.takeIf { kotlin.math.abs(it.ptsUs - media) <= originalToleranceUs }
            val before = exact ?: frames.lastOrNull { it.ptsUs < media }
                ?: return Decision.Waiting(Reason.NO_PREVIOUS_FRAME, due, media).also { waitedPrevious++ }
            val after = exact ?: frames.firstOrNull { it.ptsUs > media }
                ?: return Decision.Waiting(Reason.NO_FUTURE_FRAME, due, media).also { waitedFuture++ }
            // 输入 PTS 只有整数微秒精度；向上取整一次，不把 60 fps 的微秒量化误当低帧。
            val periodUs = ceil(1_000_000.0 * actualClock.speed / fps)
            val selected = exact ?: if ((after.ptsUs - before.ptsUs).toDouble() <= periodUs) {
                if (media - before.ptsUs <= after.ptsUs - media) before else after
            } else null
            val mode = if (selected == null) Mode.INTERPOLATE else Mode.ORIGINAL
            if (mode == Mode.INTERPOLATE && ahead < minLeadNs) {
                skippedLead++
                nextTick++
                return@repeat
            }
            if (mode == Mode.ORIGINAL) plannedOriginal++ else plannedInterpolate++
            val alpha = if (selected != null) 0.0 else (media - before.ptsUs).toDouble() / (after.ptsUs - before.ptsUs)
            return Decision.Ready(Proposal(expected, nextTick, nowNs, due, media, selected ?: before, selected ?: after, alpha,
                actualClock.uncertaintyUs, mode).also { pending = it })
        }
        return Decision.Waiting(Reason.AHEAD_OF_WINDOW, slotTime(start, nextTick))
    }

    /** 必须在外层提交前再次调用；此纯状态核对不提供跨宿主/GPU 调用的原子性。 */
    @Synchronized fun valid(proposal: Proposal, nowNs: Long): Boolean {
        if (pending !== proposal || !current(proposal.lease))
            return false
        if (!observe(nowNs) || nowNs < proposal.plannedAtNs || nowNs > proposal.dueNs) return false
        val actualClock = clock ?: return false
        val age = difference(nowNs, actualClock.monotonicNs) ?: return false
        return !actualClock.paused && age in 0L..maxClockAgeNs
    }

    /** 仅确认外层本次输出已处理；不等同 GPU 完成或屏幕呈现，原帧/输入资源仍由外层记账。 */
    @Synchronized fun commit(proposal: Proposal, nowNs: Long): Boolean {
        if (!valid(proposal, nowNs)) {
            if (pending === proposal) pending = null
            return false
        }
        pending = null
        nextTick = proposal.tick + 1L
        return true
    }

    private fun current(expected: Lease) = expected.timeline === identity && lease === expected
    private fun reset(): List<Long> {
        val discarded = frames.map(Frame::id)
        frames.clear(); pending = null; clock = null; anchorNs = null; lastObservedNs = null; nextTick = 0L; lease = null
        phaseOffsetUs = 0L; phaseLocked = false
        return discarded
    }
    private fun observe(nowNs: Long): Boolean {
        if (lastObservedNs?.let { nowNs < it } == true) return false
        lastObservedNs = nowNs
        return true
    }
    private fun slotTime(start: Long, tick: Long): Long? = runCatching {
        Math.addExact(start, Math.addExact(Math.multiplyExact(tick / fps, NS_PER_SECOND),
            Math.multiplyExact(tick % fps, NS_PER_SECOND) / fps))
    }.getOrNull()
    private fun firstTick(elapsed: Long): Long? = runCatching {
        Math.addExact(Math.multiplyExact(elapsed / NS_PER_SECOND, fps.toLong()),
            Math.addExact(Math.multiplyExact(elapsed % NS_PER_SECOND, fps.toLong()), NS_PER_SECOND - 1L) / NS_PER_SECOND)
    }.getOrNull()
    private fun mediaAt(snapshot: ClockSnapshot, dueNs: Long): Long? {
        val elapsed = difference(dueNs, snapshot.monotonicNs) ?: return null
        if (elapsed < 0L) return null
        val deltaUs = elapsed.toDouble() / 1_000.0 * snapshot.speed
        if (!deltaUs.isFinite() || deltaUs >= Long.MAX_VALUE.toDouble()) return null
        return runCatching { Math.addExact(snapshot.mediaUs, deltaUs.roundToLong()) }.getOrNull()
    }
    /** 有延迟时允许向过去外推（不超过时钟时效 + 延迟）；无延迟时与 [mediaAt] 完全一致。 */
    private fun slotMedia(snapshot: ClockSnapshot, dueNs: Long): Long? {
        if (presentationDelayNs == 0L && phaseOffsetUs == 0L) return mediaAt(snapshot, dueNs)
        val shown = difference(dueNs, presentationDelayNs) ?: return null
        val elapsed = difference(shown, snapshot.monotonicNs) ?: return null
        if (elapsed < -(maxClockAgeNs + presentationDelayNs)) return null
        val deltaUs = elapsed.toDouble() / 1_000.0 * snapshot.speed
        if (!deltaUs.isFinite()) return null
        val media = runCatching {
            Math.subtractExact(Math.addExact(snapshot.mediaUs, deltaUs.roundToLong()), phaseOffsetUs)
        }.getOrNull() ?: return null
        return media.takeIf { it >= 0L }
    }
    private fun difference(first: Long, second: Long): Long? = runCatching { Math.subtractExact(first, second) }.getOrNull()
    private fun within(first: Long, second: Long, budget: Long): Boolean =
        if (first >= second) first - second <= budget else second - first <= budget

    companion object {
        private const val NS_PER_SECOND = 1_000_000_000L
        private const val MAX_FRAMES = 4
        private const val MAX_CLOCK_UNCERTAINTY_US = 2_000L
        private const val MAX_CONFIGURABLE_UNCERTAINTY_US = 8_000L
        private const val MAX_DELAY_NS = 250_000_000L
        private const val MAX_PHASE_OFFSET_US = 100_000L
        private const val MAX_SLOT_SCAN = 8
        // 原有验证档位 + 帧率上限自适应（FrameRateTarget）产生的源帧率整数倍（24/25/30 fps 源）。
        private val SUPPORTED_FPS = setOf(0, 15, 30, 45, 50, 60, 120, 144, 165, 185) + FrameRateTarget.TARGETS
    }
}
