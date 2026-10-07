package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface SponsorPlayerPort {
    fun valid(video: SponsorVideoId): Boolean
    fun position(): Long
    fun duration(): Long
    fun playing(): Boolean
    fun seek(targetMs: Int, valid: () -> Boolean, executing: () -> Unit): Boolean
}

/** 除执行门禁外均由主线程调用；所有异步回调在主队列重新核对身份和生命周期。 */
internal class SponsorPlaybackController(
    private val video: SponsorVideoId,
    automatic: Boolean,
    private val player: SponsorPlayerPort,
    private val lookup: (SponsorVideoId, (SponsorFetchResult) -> Unit) -> SponsorSegmentRepository.Ticket,
    private val main: (() -> Unit) -> Unit,
    private val after: (Long, () -> Unit) -> SponsorSegmentRepository.Ticket,
    private val now: () -> Long,
    private val hint: (Hint) -> Unit,
    private val applied: () -> Unit
) {
    enum class Hint { NONE, SKIP, UNDO }
    private val policy = SponsorPlaybackPolicy(automatic)
    private val alive = AtomicBoolean(true)
    private val gate = AtomicReference<SponsorPlaybackPolicy.Request?>()
    private val executed = AtomicBoolean(false)
    private var query: SponsorSegmentRepository.Ticket? = null
    private var deadline: SponsorSegmentRepository.Ticket? = null
    private var undoExpiry: SponsorSegmentRepository.Ticket? = null
    private var seekRecovery: SponsorSegmentRepository.Ticket? = null
    private var pendingAutomatic = false
    private var confirmedTarget: Long? = null

    fun start() {
        if (!live()) return
        query = lookup(video) { result -> main {
            if (!live()) return@main
            policy.load((result as? SponsorFetchResult.Available)?.segments.orEmpty())
            refresh()
        } }
        refresh()
    }

    fun refresh() {
        if (!live()) { stop(); return }
        policy.progress(player.position(), player.duration(), now())
        if (policy.pending == null) gate.set(null)
        val request = policy.request(player.playing(), now())
        if (request != null) send(request, automatic = true)
        render()
    }

    fun skip() {
        if (!live()) return
        policy.progress(player.position(), player.duration(), now())
        policy.request(player.playing(), now(), manual = true)?.let { send(it, automatic = false) }
        render()
    }

    fun undo() {
        if (!live()) return
        policy.progress(player.position(), player.duration(), now())
        policy.requestUndo(now())?.let { send(it, automatic = false) }
        render()
    }

    fun seekStarted(targetMs: Long) {
        if (!live()) return
        val pending = gate.get()
        if (pending != null && executed.get() && kotlin.math.abs(pending.targetMs - targetMs) <= 1_000) return
        externalSeekStarted()
    }

    /** Hook 当场撤销门禁；主线程随后处理 UI/策略，不能让已排队协程抢先执行。 */
    fun invalidateExternalSeek(targetMs: Long? = null) {
        val pending = gate.get()
        if (targetMs != null && pending != null && executed.get() &&
            kotlin.math.abs(pending.targetMs - targetMs) <= 1_000) return
        gate.set(null)
        executed.set(false)
    }

    fun externalSeekStarted() {
        gate.set(null)
        executed.set(false)
        deadline?.cancel(); deadline = null
        policy.externalSeekStart(now())
        seekRecovery?.cancel()
        seekRecovery = after(4_000) { if (live()) refresh() }
        render()
    }

    fun seekCompleted() {
        if (!live()) return
        if (gate.get() == null && executed.get() && confirmedTarget?.let {
                kotlin.math.abs(player.position() - it) <= 1_000
            } == true) return // 同一宿主 seek 的重复完成通知。
        seekRecovery?.cancel(); seekRecovery = null
        val position = player.position()
        if (gate.get() != null && executed.get() && policy.confirm(position, now())) {
            confirmedTarget = position
            gate.set(null); deadline?.cancel(); deadline = null
            applied()
            undoExpiry?.cancel()
            undoExpiry = after(6_001) { if (live()) refresh() }
        } else {
            confirmedTarget = null
            executed.set(false)
            gate.set(null); deadline?.cancel(); deadline = null
            policy.externalSeekStart(now())
            policy.externalSeekComplete(position)
        }
        render()
    }

    fun stateChanged() {
        if (pendingAutomatic && !player.playing()) gate.get()?.let {
            gate.set(null); policy.fail(it.serial)
        }
        refresh()
    }

    fun stop() {
        if (!alive.getAndSet(false)) return
        gate.set(null)
        query?.cancel(); query = null
        deadline?.cancel(); deadline = null
        undoExpiry?.cancel(); undoExpiry = null
        seekRecovery?.cancel(); seekRecovery = null
        hint(Hint.NONE)
    }

    private fun live(): Boolean = alive.get() && player.valid(video)

    private fun send(request: SponsorPlaybackPolicy.Request, automatic: Boolean) {
        pendingAutomatic = automatic
        gate.set(request)
        executed.set(false)
        deadline?.cancel()
        deadline = after(4_000) {
            if (gate.compareAndSet(request, null)) { policy.fail(request.serial); if (live()) refresh() }
        }
        val accepted = runCatching { player.seek(request.targetMs.toInt(),
            { gate.get() === request && live() && now() < request.deadlineMs && (!automatic || player.playing()) },
            { executed.set(true) }) }.getOrDefault(false)
        if (!accepted) {
            gate.compareAndSet(request, null); policy.fail(request.serial)
            deadline?.cancel(); deadline = null
        }
    }

    private fun render() {
        hint(when {
            !live() || policy.pending != null -> Hint.NONE
            policy.canUndo(now()) -> Hint.UNDO
            policy.currentRange != null -> Hint.SKIP
            else -> Hint.NONE
        })
    }
}
