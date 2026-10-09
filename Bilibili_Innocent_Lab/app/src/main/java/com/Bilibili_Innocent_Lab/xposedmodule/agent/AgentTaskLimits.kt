package com.Bilibili_Innocent_Lab.xposedmodule.agent

/** 用户预算与单次操作超时分开；0 只表示用户预算无限，不传给网络或 Binder。 */
internal data class AgentTaskLimits(val durationMs: Long = 120_000L, val maximumSteps: Long = 12L) {
    init { require(durationMs >= 0 && maximumSteps >= 0) }

    fun elapsedMs(startedAt: Long, now: Long): Long {
        require(startedAt >= 0 && now >= 0)
        return if (now >= startedAt) now - startedAt else 0L
    }

    fun timeExceeded(startedAt: Long, now: Long): Boolean =
        durationMs > 0 && elapsedMs(startedAt, now) >= durationMs

    fun allowsStep(completedSteps: Long): Boolean {
        require(completedSteps >= 0)
        return completedSteps < Long.MAX_VALUE && (maximumSteps == 0L || completedSteps < maximumSteps)
    }

    fun requestDeadline(startedAt: Long, now: Long, capMs: Long): Long {
        require(capMs in 1L..MAX_OPERATION_MS)
        val remaining = if (durationMs == 0L) capMs else
            (durationMs - elapsedMs(startedAt, now)).coerceAtLeast(0L).coerceAtMost(capMs)
        return saturatedAdd(now, remaining)
    }

    companion object {
        const val MAX_OPERATION_MS = 180_000L
        fun saturatedAdd(now: Long, interval: Long): Long {
            require(now >= 0 && interval >= 0)
            return if (interval > Long.MAX_VALUE - now) Long.MAX_VALUE else now + interval
        }
    }
}
