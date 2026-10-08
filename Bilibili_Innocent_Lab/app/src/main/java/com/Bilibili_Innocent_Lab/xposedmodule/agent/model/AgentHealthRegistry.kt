package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

/** 进程共享的角色健康记录；只保存配置摘要和统计，不持有凭据、目标或模型输入。 */
internal class AgentHealthRegistry {
    private data class Key(val fingerprint: String, val role: AgentModelRole)
    internal class Record {
        var inFlight = 0
        var latencyMs = 2_000.0
        var failures = 0
        var cooldownUntil = 0L
        var touchedAt = 0L
        var lastAcquired = -1L
    }
    private val records = LinkedHashMap<Key, Record>(16, 0.75f, true)
    private var acquisitions = 0L

    @Synchronized
    internal fun reserve(sources: List<AgentModelSource>, role: AgentModelRole, nowMs: Long,
                         preferredFingerprint: String?): Lease? {
        records.entries.removeAll { (_, value) -> value.inFlight == 0 &&
            (nowMs < value.touchedAt || nowMs - value.touchedAt > TTL_MS) }
        val candidates = sources.mapNotNull { source ->
            val key = Key(source.fingerprint, role)
            val record = records[key] ?: run {
                if (records.size >= MAX_RECORDS) {
                    val victim = records.entries.firstOrNull { it.value.inFlight == 0 }?.key
                        ?: return@mapNotNull null
                    records.remove(victim)
                }
                Record().also { records[key] = it }
            }
            record.touchedAt = nowMs
            if (nowMs >= record.cooldownUntil) Triple(source, key, record) else null
        }
        val preferred = candidates.firstOrNull { it.first.fingerprint == preferredFingerprint }
        val idle = candidates.filter { it.third.inFlight == 0 }
        val untried = idle.filter { it.third.lastAcquired < 0 }.minByOrNull { it.first.index }
        val overdue = idle.filter { it.third.lastAcquired >= 0 && acquisitions - it.third.lastAcquired >= REPROBE_AFTER }
            .minByOrNull { it.third.lastAcquired }
        val chosen = preferred ?: untried ?: overdue ?: candidates.minWithOrNull(
            compareBy<Triple<AgentModelSource, Key, Record>>(
                { (it.third.inFlight + 1) * it.third.latencyMs }, { it.first.index })) ?: return null
        if (acquisitions == Long.MAX_VALUE) {
            records.values.forEach { if (it.lastAcquired >= 0) it.lastAcquired = 0 }
            acquisitions = 1
        }
        chosen.third.lastAcquired = acquisitions++
        chosen.third.inFlight++
        return Lease(chosen.first, chosen.third)
    }

    internal inner class Lease internal constructor(val source: AgentModelSource, private val record: Record) : AutoCloseable {
        private var released = false

        fun succeed(elapsedMs: Long) = synchronized(this@AgentHealthRegistry) {
            if (!released) {
                record.latencyMs = record.latencyMs * 0.7 +
                    elapsedMs.coerceIn(1, AgentHttpsTransport.MAX_TIMEOUT_MS.toLong()) * 0.3
                record.failures = 0
                record.cooldownUntil = 0L
                release()
            }
        }

        fun fail(nowMs: Long, retryAfterMs: Long? = null, error: AgentModelException? = null) = synchronized(this@AgentHealthRegistry) {
            if (!released) {
                // 本地预算、未验证能力和取消均不说明远端来源有故障。
                if (error?.reason !in LOCAL_FAILURES) {
                    val exponential = (15_000L shl record.failures.coerceAtMost(5)).coerceAtMost(300_000L)
                    val authentication = if (error?.status in setOf(401, 403)) 300_000L else 0L
                    val cooldown = maxOf(exponential, authentication, retryAfterMs?.coerceIn(0, 300_000L) ?: 0L)
                    record.cooldownUntil = maxOf(record.cooldownUntil, nowMs.coerceAtMost(Long.MAX_VALUE - cooldown) + cooldown)
                    record.failures = (record.failures + 1).coerceAtMost(6)
                }
                record.touchedAt = nowMs
                release()
            }
        }

        override fun close() = synchronized(this@AgentHealthRegistry) { release() }
        private fun release() { if (!released) { released = true; record.inFlight-- } }
    }

    internal fun size(): Int = synchronized(this) { records.size }

    companion object {
        internal const val MAX_RECORDS = 96
        private const val TTL_MS = 24 * 60 * 60_000L
        private const val REPROBE_AFTER = 32L
        private val LOCAL_FAILURES = setOf(AgentModelException.Reason.CANCELLED, AgentModelException.Reason.INVALID_REQUEST,
            AgentModelException.Reason.TOO_LARGE, AgentModelException.Reason.VISION_UNVERIFIED,
            AgentModelException.Reason.DECISIONS_UNVERIFIED)
    }
}
