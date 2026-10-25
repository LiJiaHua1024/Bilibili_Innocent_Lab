package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/** Finite automatic recovery; a later playback may rearm it after a quiet window. Never weakens admission. */
internal class FramePreparationRetry(
    private val delayNs: Long = 5_000_000_000L,
    private val maxAttempts: Int = 3,
    private val rearmNs: Long = 60_000_000_000L,
    private val capabilityDelayNs: Long = delayNs
) {
    enum class Work { LOAD, CAPABILITY }
    private var pending: Work? = null
    private var failedAt = 0L
    private var lastAttemptAt = 0L
    private var attempts = 0

    @Synchronized fun failed(work: Work?, nowNs: Long) { pending = work; failedAt = nowNs }
    @Synchronized fun canSchedule(): Boolean = pending != null && attempts < maxAttempts
    @Synchronized fun ready() { pending = null; attempts = 0 }
    fun delayFor(work: Work): Long = if (work == Work.CAPABILITY) capabilityDelayNs else delayNs
    @Synchronized fun take(nowNs: Long, newPlayback: Boolean): Work? {
        val work = pending ?: return null
        if (nowNs - failedAt < delayFor(work)) return null
        if (attempts >= maxAttempts) {
            if (!newPlayback || nowNs - lastAttemptAt < rearmNs) return null
            attempts = 0
        }
        attempts++
        lastAttemptAt = nowNs
        pending = null
        return work
    }
}
