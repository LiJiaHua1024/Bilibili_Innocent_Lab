package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameBufferLedger.Holder
import java.nio.ByteBuffer

/**
 * 正式（非预览）插帧会话：把 [FrameTimeline]、[FrameBufferLedger]、推理 worker、Presenter 与
 * [FramePresentationRecovery] 串成一条所有权与恢复都闭合的管线。本类不接宿主播放器、不取 Surface，
 * 只能经 [FrameInterpolationEntryGate] 放行后由外层集成实例化（见契约测试）。
 *
 * 所有权（每张帧只经 [FrameBufferLedger] 分配，持有者各自 release 一次）：
 * - CAPTURE：[capture] 填充期间；交给 TIMELINE 后立即释放。插帧输出在推理期间也以 CAPTURE 持有。
 * - TIMELINE：时间线窗口内；被淘汰、替换、拒绝或 epoch 撤销时按 discardedIds 释放。
 * - WORKER：推理任务的两张输入；任务结束（含超时/撤销后迟到的结束）才释放。
 * - COMPARISON：锁外去重比较的上一张输入；比较结束才释放，epoch 撤销不能使它提前回收。
 * - PRESENTER：交给 Presenter 后，直到它调用 done；每次交付一份持有，重复 done 只生效一次。
 *
 * epoch（暂停倍率变化、seek、Surface 或格式变化）由外层 [begin] 换代：TIMELINE 立即归还，
 * WORKER/PRESENTER 在各自结束时归还；旧代任务的结果一律丢弃，不呈现、不计入恢复统计。
 */
internal class FrameInterpolationSession(
    private val targetFps: Int,
    private val ledger: FrameBufferLedger,
    private val synthesizer: Synthesizer,
    private val worker: Worker,
    private val presenter: Presenter,
    private val nanoTime: () -> Long,
    private val recovery: FramePresentationRecovery = FramePresentationRecovery(),
    private val workerTimeoutNs: Long = 100_000_000L,
    /** 深流水线由外层传入带延迟/提前量的时间线；默认与单周期时间线一致。 */
    private val timeline: FrameTimeline = FrameTimeline(targetFps),
    /**
     * 源重复帧判定（null 关闭去重）。动画常以 24 fps 嵌在 30 fps 流里（每 5 帧重复 1 帧）或「一拍二」（每帧画两次），
     * 重复帧之间补出的帧仍是同一画面，60 fps 下表现为 50 ms 停顿（宿主真机录屏所见的掉帧感）。重复帧不进时间线，
     * 其前后槽位改由相邻的真实画面插值；连续最多丢 [maxConsecutiveDuplicates] 帧，真正的静止画面仍按节奏前进。
     */
    private val duplicate: ((ByteBuffer, ByteBuffer, FrameBufferLedger.Format) -> Boolean)? = null,
    private val maxConsecutiveDuplicates: Int = 2
) {
    /** 只在 worker 线程调用；返回原生状态，异常按 INFERENCE_FAILED 处理。 */
    interface Synthesizer {
        /** leftId/rightId 为账本帧 ID（唯一、不复用），可用于在实现内缓存该帧的派生数据。 */
        fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
                    format: FrameBufferLedger.Format, alpha: Float, leftId: Long, rightId: Long): FrameInterpolationBackend.Status
        fun close()
    }
    /** 有界单线程 worker；拒绝（忙或已关闭）时返回 false，不能排队无限任务。 */
    fun interface Worker { fun execute(task: () -> Unit): Boolean }
    sealed interface Outcome {
        data class Presented(val presentedNs: Long) : Outcome
        data class Dropped(val nowNs: Long) : Outcome
        data class SurfaceLost(val nowNs: Long) : Outcome
        /**
         * 面板实际刷新低于目标帧率，同一刷新里更新的一帧取代了本帧：不是管线故障，不计入恢复窗口；
         * 桥的 vsync 实测随后按真实刷新交回原生、由宿主按该上限重新接管。
         */
        data class Superseded(val nowNs: Long) : Outcome
    }
    /**
     * present 返回 true 即接收缓冲的借用，必须在缓冲不再被读取（含 GPU fence）后恰好调用 done 一次；
     * Surface 销毁时对所有在队帧调用 done(SurfaceLost/Dropped)。restoreNative 要求宿主恢复原生呈现。
     */
    interface Presenter {
        fun present(frameId: Long, buffer: ByteBuffer, format: FrameBufferLedger.Format, dueNs: Long,
                    done: (Outcome) -> Unit): Boolean
        fun restoreNative(report: FramePresentationRecovery.Report)
    }
    enum class Phase { IDLE, ACTIVE, NATIVE, CLOSED }
    /** presented/interpolated 只统计 Presenter 回报已呈现的帧；late 为超出迟到预算的已呈现帧。 */
    data class Snapshot(val phase: Phase, val recovery: FramePresentationRecovery.Report,
                        val ledger: FrameBufferLedger.Stats, val inFlight: Boolean,
                        val presented: Long = 0L, val interpolated: Long = 0L, val late: Long = 0L,
                        /** 推理完成时提案已失效（错过槽位或被时钟/窗口撤销）、推理失败、Presenter 拒收。 */
                        val missedCommit: Long = 0L, val failedInference: Long = 0L, val rejected: Long = 0L,
                        /** missedCommit 中完成时已过槽位时刻的部分；其余为提案在推理期间被撤销。 */
                        val missedLate: Long = 0L,
                        /** 已完成推理任务的平均规划提前量与平均推理耗时（含排队）。 */
                        val averageLeadUs: Long = 0L, val averageInferenceUs: Long = 0L,
                        val planned: FrameTimeline.Counters? = null,
                        /** 作为源重复帧未进入时间线的帧数。 */
                        val deduplicated: Long = 0L)

    private companion object { const val MAX_PLANS_PER_TICK = 8 }

    private var lastAcceptedId: Long? = null
    private var consecutiveDuplicates = 0
    private var deduplicatedCount = 0L

    private class Job(val token: Long, val proposal: FrameTimeline.Proposal, val outputId: Long,
                      val format: FrameBufferLedger.Format)

    private val lateBudgetNs = 1_000_000_000L / targetFps.coerceAtLeast(1) / 2
    private var lease: FrameTimeline.Lease? = null
    private var token = 0L
    private var phase = Phase.IDLE
    private var job: Job? = null
    /** worker 上实际在跑的任务；被撤销的 job 仍可能在跑，关闭 synthesizer 必须等它结束。 */
    private var workerBusy = false
    private var presentedCount = 0L
    private var interpolatedCount = 0L
    private var lateCount = 0L
    private var missedCommitCount = 0L
    private var failedInferenceCount = 0L
    private var rejectedCount = 0L
    private var missedLateCount = 0L
    private var jobs = 0L
    private var leadTotalNs = 0L
    private var inferenceTotalNs = 0L
    private val interpolatedIds = HashSet<Long>()
    /** 最近一次 [begin] 的 epoch/格式，回退后由 [tick] 自动重试；被拒绝的 begin 也记录（如新 Surface）。 */
    private var lastEpoch: FrameTimeline.Epoch? = null
    private var lastFormat: FrameBufferLedger.Format? = null
    /** Surface 丢失时的 surface 代次；只有换了 Surface 的 epoch 才允许自动重试。 */
    private var lostSurface: Long? = null
    private var synthesizerClosed = false

    init { require(targetFps > 0 && workerTimeoutNs > 0L) }

    /** 每次 epoch 变化调用；返回本 epoch 是否走插帧管线（false 时宿主保持原生呈现）。 */
    @Synchronized fun begin(epoch: FrameTimeline.Epoch, width: Int, height: Int, stride: Int = width * 3): Boolean {
        if (phase == Phase.CLOSED) return false
        revoke()
        lastEpoch = epoch
        lastFormat = FrameBufferLedger.Format(width, height, stride)
        if (!ledger.configure(width, height, stride)) {
            native(); return false
        }
        lastAcceptedId = null
        consecutiveDuplicates = 0
        val started = timeline.begin(epoch, targetFps)
        ledger.releaseAll(started.discardedIds, Holder.TIMELINE)
        lease = started.lease
        if (!recovery.start(nanoTime())) {
            revoke(); native(); return false
        }
        phase = Phase.ACTIVE
        lostSurface = null
        return true
    }

    /** fill 写入 RGB8 并返回是否成功；缓冲耗尽或填充失败时返回 null，当前帧由宿主原样处理。 */
    /**
     * 填充（GPU 读回，可能阻塞到推理让出 GPU）在会话锁之外进行：持锁读回会让 worker 完成回调、呈现交付与规划
     * 全部排队等待，宿主真机表现为补帧槽位因提前量不足被跳过（间歇顿挫）。缓冲在锁内取得、填好后再在锁内交给
     * 时间线；期间若 epoch 变化或会话关闭，丢弃这一帧。
     */
    fun capture(ptsUs: Long, fill: (ByteBuffer, FrameBufferLedger.Format) -> Boolean): Long? {
        val (current, slot) = synchronized(this) {
            val current = lease ?: return null
            if (phase != Phase.ACTIVE) return null
            current to (ledger.acquire() ?: return null)
        }
        val buffer = ledger.buffer(slot.frameId, Holder.CAPTURE)
        val filled = buffer?.let { runCatching { fill(it, slot.format) }.getOrDefault(false) }
        val compare = duplicate
        // 比较保持在锁外，但必须独立持有上一帧：驱动线程可在此时撤销 TIMELINE，seek/close 也可换代。
        val previous = if (filled == true && compare != null) synchronized(this) {
            lastAcceptedId?.takeIf { lease === current && phase == Phase.ACTIVE &&
                consecutiveDuplicates < maxConsecutiveDuplicates && ledger.retain(it, Holder.COMPARISON) }
        } else null
        val isDuplicate = try {
            previous != null && buffer != null && compare != null &&
                ledger.buffer(previous, Holder.COMPARISON)?.let {
                    runCatching { compare(it, buffer, slot.format) }.getOrDefault(false)
                } == true
        } finally {
            previous?.let { ledger.release(it, Holder.COMPARISON) }
        }
        synchronized(this) {
            if (isDuplicate && lease === current) {
                consecutiveDuplicates++
                deduplicatedCount++
                ledger.release(slot.frameId, Holder.CAPTURE); return null
            }
            if (filled != true || lease !== current || phase != Phase.ACTIVE || !ledger.retain(slot.frameId, Holder.TIMELINE)) {
                ledger.release(slot.frameId, Holder.CAPTURE); return null
            }
            consecutiveDuplicates = 0
            val offered = timeline.offer(current, FrameTimeline.Frame(slot.frameId, ptsUs))
            if (offered.accepted) lastAcceptedId = slot.frameId
            ledger.releaseAll(offered.discardedIds, Holder.TIMELINE)
            ledger.release(slot.frameId, Holder.CAPTURE)
            return slot.frameId.takeIf { offered.accepted }
        }
    }

    /** RESTART_REQUIRED/NO_CLOCK 会撤销本 epoch；宿主须以新 epoch 调用 [begin]，期间不呈现。 */
    @Synchronized fun updateClock(snapshot: FrameTimeline.ClockSnapshot?): FrameTimeline.ClockState {
        val current = lease ?: return FrameTimeline.ClockState.STALE_LEASE
        val update = timeline.updateClock(current, snapshot)
        ledger.releaseAll(update.discardedIds, Holder.TIMELINE)
        if (update.state == FrameTimeline.ClockState.RESTART_REQUIRED || update.state == FrameTimeline.ClockState.NO_CLOCK) {
            lease = null; token++; job = null
            if (phase == Phase.ACTIVE) phase = Phase.IDLE
        }
        return update.state
    }

    /** 不可信时钟导致的重启有独立预算；耗尽时即使没有晚交帧也必须交还原生播放。 */
    @Synchronized fun onClockRejected(nowNs: Long): Boolean {
        if (phase == Phase.CLOSED || phase == Phase.NATIVE) return false
        val report = recovery.onClockRejected(nowNs)
        if (!report.pipelinePresents) native()
        return report.pipelinePresents
    }

    /**
     * 由宿主的帧回调驱动（回退期间也要继续调用）；一次至多一个推理任务，超时即判定恢复失败。
     * 回退且退避已过时，以最近的 epoch 自动重试；Surface 丢失后须先有新 Surface 的 epoch。
     */
    @Synchronized fun tick(nowNs: Long) {
        if (phase == Phase.NATIVE) {
            val epoch = lastEpoch
            val format = lastFormat
            if (epoch != null && format != null && epoch.surface != lostSurface && recovery.retryDue(nowNs))
                begin(epoch, format.width, format.height, format.stride)
            return
        }
        if (phase != Phase.ACTIVE) return
        val current = lease ?: return
        job?.let {
            if (nowNs - it.proposal.dueNs > workerTimeoutNs) {
                job = null
                if (!recovery.onWorkerTimeout(nowNs).pipelinePresents) native()
            }
            return
        }
        // 原帧无需推理：同一节拍内连续规划直到遇到需推理的槽位或时间窗外。每节拍只规划一个时，
        // 原帧/插帧交替的一对要耗两个节拍加推理，超过一个源帧间隔，管线落后并跳槽（宿主内真机约 43 fps）。
        repeat(MAX_PLANS_PER_TICK) {
            val proposal = (timeline.plan(current, nowNs) as? FrameTimeline.Decision.Ready)?.proposal ?: return
            val report = recovery.report()
            if (proposal.isOriginal || !report.interpolationAllowed) {
                val original = if (proposal.isOriginal || proposal.alpha <= 0.5) proposal.before else proposal.after
                if (!timeline.commit(proposal, nowNs)) return
                present(original.id, proposal.dueNs)
                if (phase != Phase.ACTIVE || lease !== current) return
                return@repeat
            }
            startJob(proposal, nowNs)
            return
        }
    }

    /** Surface 销毁或不可用：立即回退，在队帧由 Presenter 以 done 归还。 */
    @Synchronized fun onSurfaceLost(nowNs: Long) {
        if (phase == Phase.CLOSED) return
        lostSurface = lastEpoch?.surface
        recovery.onSurfaceLost(nowNs)
        native()
    }

    /**
     * 拆除：撤销 epoch、停止分配；synthesizer 在没有在途任务时于 worker 上关闭，否则延后到任务结束。
     * 返回时仍由 Presenter 持有的帧 ID 列表；它们在 done 时被账本丢弃。
     */
    @Synchronized fun close(): List<Long> {
        if (phase != Phase.CLOSED) {
            revoke()
            recovery.stop()
            phase = Phase.CLOSED
            ledger.close()
            if (!workerBusy) closeSynthesizer()
        }
        return ledger.outstanding(Holder.PRESENTER)
    }

    /** 视频落后主时钟的媒体时长；外层据此延后音频。非 ACTIVE 时为 0。 */
    @Synchronized fun effectiveDelayUs(): Long = if (phase == Phase.ACTIVE) timeline.effectiveDelayUs() else 0L

    @Synchronized fun snapshot() =
        Snapshot(phase, recovery.report(), ledger.stats(), workerBusy, presentedCount, interpolatedCount, lateCount,
            missedCommitCount, failedInferenceCount, rejectedCount, missedLateCount,
            if (jobs == 0L) 0L else leadTotalNs / jobs / 1_000L, if (jobs == 0L) 0L else inferenceTotalNs / jobs / 1_000L,
            timeline.counters(), deduplicatedCount)

    private fun startJob(proposal: FrameTimeline.Proposal, nowNs: Long) {
        val before = proposal.before.id
        val after = proposal.after.id
        val output = if (workerBusy) null else ledger.acquire()
        if (output == null) {
            // 输出缓冲耗尽或旧任务仍占 worker 是背压而非呈现失败：本槽位呈现最近原帧。
            if (timeline.commit(proposal, nowNs)) present(if (proposal.alpha <= 0.5) before else after, proposal.dueNs)
            return
        }
        if (!ledger.retain(before, Holder.WORKER) || !ledger.retain(after, Holder.WORKER)) {
            ledger.release(before, Holder.WORKER); ledger.release(output.frameId, Holder.CAPTURE); return
        }
        val scheduled = Job(token, proposal, output.frameId, output.format)
        job = scheduled
        workerBusy = true
        val accepted = runCatching { worker.execute { run(scheduled) } }.getOrDefault(false)
        if (!accepted) {
            job = null
            workerBusy = false
            releaseJob(scheduled)
            if (!recovery.onNativeStatus(FrameInterpolationBackend.Status.BUSY, nowNs).pipelinePresents) native()
        }
    }

    private fun releaseJob(scheduled: Job) {
        ledger.release(scheduled.proposal.before.id, Holder.WORKER)
        ledger.release(scheduled.proposal.after.id, Holder.WORKER)
        ledger.release(scheduled.outputId, Holder.CAPTURE)
    }

    /** worker 线程：缓冲借用由 WORKER/CAPTURE 持有保证有效；完成后回到锁内处理。 */
    private fun run(scheduled: Job) {
        val left = ledger.buffer(scheduled.proposal.before.id, Holder.WORKER)
        val right = ledger.buffer(scheduled.proposal.after.id, Holder.WORKER)
        val out = ledger.buffer(scheduled.outputId, Holder.CAPTURE)
        val status = if (left == null || right == null || out == null) FrameInterpolationBackend.Status.INVALID_ARGUMENT
        else runCatching { synthesizer.process(left, right, out, scheduled.format, scheduled.proposal.alpha.toFloat(),
            scheduled.proposal.before.id, scheduled.proposal.after.id) }
            .getOrDefault(FrameInterpolationBackend.Status.INFERENCE_FAILED)
        complete(scheduled, status)
    }

    @Synchronized private fun complete(scheduled: Job, status: FrameInterpolationBackend.Status) {
        val nowNs = nanoTime()
        workerBusy = false
        ledger.release(scheduled.proposal.before.id, Holder.WORKER)
        ledger.release(scheduled.proposal.after.id, Holder.WORKER)
        jobs++
        leadTotalNs += scheduled.proposal.dueNs - scheduled.proposal.plannedAtNs
        inferenceTotalNs += nowNs - scheduled.proposal.plannedAtNs
        val stale = job !== scheduled || scheduled.token != token || phase != Phase.ACTIVE
        if (job === scheduled) job = null
        if (stale) {
            ledger.release(scheduled.outputId, Holder.CAPTURE)
            if (phase == Phase.CLOSED) closeSynthesizer()
            return
        }
        if (status != FrameInterpolationBackend.Status.OK) {
            failedInferenceCount++
            ledger.release(scheduled.outputId, Holder.CAPTURE)
            if (!recovery.onNativeStatus(status, nowNs).pipelinePresents) native()
            return
        }
        if (!timeline.commit(scheduled.proposal, nowNs)) {
            // 推理晚于目标时刻或提案已被撤销：此槽位已错过，不补交。
            missedCommitCount++
            if (nowNs > scheduled.proposal.dueNs) missedLateCount++
            ledger.release(scheduled.outputId, Holder.CAPTURE)
            if (!recovery.onDropped(nowNs).pipelinePresents) native()
            return
        }
        ledger.retain(scheduled.outputId, Holder.PRESENTER)
        ledger.release(scheduled.outputId, Holder.CAPTURE)
        interpolatedIds += scheduled.outputId
        handOver(scheduled.outputId, scheduled.proposal.dueNs)
        // 推理一结束就规划下一槽，不等下一个 vsync 节拍。
        tick(nanoTime())
    }

    private fun present(frameId: Long, dueNs: Long) {
        if (!ledger.retain(frameId, Holder.PRESENTER)) return
        handOver(frameId, dueNs)
    }

    private fun handOver(frameId: Long, dueNs: Long) {
        val buffer = ledger.buffer(frameId, Holder.PRESENTER)
        val format = ledger.format(frameId)
        val issued = token
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val accepted = buffer != null && format != null && runCatching {
            presenter.present(frameId, buffer, format, dueNs) { outcome ->
                if (once.compareAndSet(false, true)) done(frameId, issued, dueNs, outcome)
            }
        }.getOrDefault(false)
        if (!accepted) {
            rejectedCount++
            interpolatedIds.remove(frameId)
            ledger.release(frameId, Holder.PRESENTER)
            if (!recovery.onDropped(nanoTime()).pipelinePresents) native()
        }
    }

    @Synchronized private fun done(frameId: Long, issued: Long, dueNs: Long, outcome: Outcome) {
        if (!ledger.release(frameId, Holder.PRESENTER)) return
        // 先结清插帧标记，再判断代次：旧代回调也不能让集合残留 ID。
        val wasInterpolated = interpolatedIds.remove(frameId)
        if (issued != token || phase != Phase.ACTIVE) return
        val report = when (outcome) {
            is Outcome.Presented -> {
                presentedCount++
                if (wasInterpolated) interpolatedCount++
                if (outcome.presentedNs - dueNs > lateBudgetNs) lateCount++
                recovery.onPresented(dueNs, outcome.presentedNs, lateBudgetNs)
            }
            is Outcome.Dropped -> recovery.onDropped(outcome.nowNs)
            is Outcome.SurfaceLost -> { lostSurface = lastEpoch?.surface; recovery.onSurfaceLost(outcome.nowNs) }
            is Outcome.Superseded -> recovery.report()
        }
        if (!report.pipelinePresents) native()
    }

    /** 撤销本 epoch：TIMELINE 立即归还，在途任务作废（其持有在任务结束时归还）。 */
    private fun revoke() {
        lease?.let { ledger.releaseAll(timeline.close(it), Holder.TIMELINE) }
        lease = null
        token++
        job = null
    }

    private fun native() {
        revoke()
        if (phase == Phase.CLOSED || phase == Phase.NATIVE) return
        phase = Phase.NATIVE
        runCatching { presenter.restoreNative(recovery.report()) }
    }

    private fun closeSynthesizer() {
        if (synthesizerClosed) return
        synthesizerClosed = true
        val queued = runCatching { worker.execute { runCatching { synthesizer.close() } } }.getOrDefault(false)
        // worker 已停时只能就地关闭；生产 Backend 要求非主线程，宿主须在后台线程调用 close()。
        if (!queued) runCatching { synthesizer.close() }
    }

    /** 生产适配：Backend 本身已校验平面布局与 worker 线程。 */
    class BackendSynthesizer(private val backend: FrameInterpolationBackend) : Synthesizer {
        override fun process(left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
                             format: FrameBufferLedger.Format, alpha: Float, leftId: Long, rightId: Long) =
            backend.process(left, right, output, format.width, format.height, alpha,
                format.stride, format.stride, format.stride, leftId, rightId)
        override fun close() = backend.close()
    }
}
