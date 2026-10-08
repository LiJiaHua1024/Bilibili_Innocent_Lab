package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

/** 自动路由也必须由任务明确提供授权来源集；固定来源失败默认不扩大数据接收方。 */
internal data class AgentRoutePolicy(
    val sourceIndices: Set<Int>,
    val fixedIndex: Int? = null,
    val allowFallback: Boolean = false
) {
    internal val allowedSources: Set<Int> = sourceIndices.toSet()

    init {
        require(sourceIndices.isNotEmpty() && sourceIndices.all { it in 1..AgentModelSource.MAX_SOURCES })
        require(fixedIndex == null || fixedIndex in sourceIndices)
    }
}

/** 只选择一次尚未执行的模型请求，不保存动作、也不重放宿主动作。 */
internal class AgentSourceRouter(
    sources: List<AgentModelSource>,
    capabilities: Map<String, AgentModelCapabilities> = emptyMap()
) {
    internal class Slot(val source: AgentModelSource) {
        var inFlight = 0
        var latencyMs = 2_000.0
        var failures = 0
        var cooldownUntil = 0L
    }

    private val slots = sources.map(::Slot)
    private val known = capabilities.toMutableMap()

    init {
        require(sources.size <= AgentModelSource.MAX_SOURCES && sources.map { it.index }.toSet().size == sources.size)
    }

    @Synchronized
    fun updateCapabilities(source: AgentModelSource, capabilities: AgentModelCapabilities) {
        known[source.fingerprint] = capabilities
    }

    @Synchronized
    fun acquire(
        requireTools: Boolean,
        requireVision: Boolean,
        route: AgentRoutePolicy,
        nowMs: Long,
        excludeFingerprints: Set<String> = emptySet()
    ): Lease? {
        val candidates = slots.filter { slot ->
            val source = slot.source
            val capability = known[source.fingerprint]
            source.index in route.allowedSources && source.fingerprint !in excludeFingerprints && nowMs >= slot.cooldownUntil &&
                (!requireTools || capability?.let { it.tools && it.fresh(nowMs) } == true) &&
                (!requireVision || capability?.let { it.vision && it.fresh(nowMs) } == true)
        }
        val fixed = route.fixedIndex
        val eligible = if (fixed == null) candidates else {
            val preferred = candidates.filter { it.source.index == fixed }
            if (preferred.isNotEmpty() || !route.allowFallback) preferred else candidates
        }
        val slot = eligible.minWithOrNull(compareBy<Slot>({ (it.inFlight + 1) * it.latencyMs }, { it.source.index }))
            ?: return null
        slot.inFlight++
        return Lease(slot)
    }

    internal inner class Lease internal constructor(private val slot: Slot) : AutoCloseable {
        val source: AgentModelSource get() = slot.source
        private var released = false

        fun succeed(elapsedMs: Long) = synchronized(this@AgentSourceRouter) {
            if (!released) {
                slot.latencyMs = slot.latencyMs * 0.7 + elapsedMs.coerceIn(1, AgentHttpsTransport.MAX_TIMEOUT_MS.toLong()) * 0.3
                slot.failures = 0
                slot.cooldownUntil = 0L
                release()
            }
        }

        fun fail(nowMs: Long, retryAfterMs: Long? = null) = synchronized(this@AgentSourceRouter) {
            if (!released) {
                val exponential = (15_000L shl slot.failures.coerceAtMost(5)).coerceAtMost(300_000L)
                val cooldown = maxOf(exponential, retryAfterMs?.coerceIn(0, 300_000L) ?: 0L)
                slot.cooldownUntil = maxOf(slot.cooldownUntil, nowMs.coerceAtMost(Long.MAX_VALUE - cooldown) + cooldown)
                slot.failures = (slot.failures + 1).coerceAtMost(6)
                release()
            }
        }

        override fun close() = synchronized(this@AgentSourceRouter) { release() }

        private fun release() {
            if (!released) { released = true; slot.inFlight-- }
        }
    }
}
