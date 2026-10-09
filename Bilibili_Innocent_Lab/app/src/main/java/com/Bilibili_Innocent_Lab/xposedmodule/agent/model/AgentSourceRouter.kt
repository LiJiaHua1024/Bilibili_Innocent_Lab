package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

/** 所有角色只在任务授权来源内协作；固定来源只约束规划器。 */
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

/** 只选择尚未执行的模型请求。健康可跨任务共享，角色粘性只属于本任务。 */
internal class AgentSourceRouter(
    sources: List<AgentModelSource>,
    capabilities: Map<String, AgentModelCapabilities> = emptyMap(),
    private val health: AgentHealthRegistry = AgentHealthRegistry()
) {
    private val sources = sources.toList()
    private val known = capabilities.toMutableMap()
    private val preferred = mutableMapOf<AgentModelRole, String>()

    init {
        require(sources.size <= AgentModelSource.MAX_SOURCES && sources.map { it.index }.toSet().size == sources.size)
    }

    @Synchronized
    fun acquire(role: AgentModelRole, route: AgentRoutePolicy, nowMs: Long,
                excludeFingerprints: Set<String> = emptySet(), preferredFingerprint: String? = null): Lease? {
        val candidates = eligible(role, route, nowMs, excludeFingerprints)
        val requested = if (role == AgentModelRole.PLANNER) route.fixedIndex?.let { index ->
            candidates.firstOrNull { it.index == index }?.fingerprint
        } else null
        val reserved = health.reserve(candidates, role, nowMs, requested ?: preferredFingerprint ?: preferred[role]) ?: return null
        return Lease(role, reserved)
    }

    @Synchronized
    fun updateCapabilities(source: AgentModelSource, capabilities: AgentModelCapabilities) {
        known[source.fingerprint] = capabilities
    }

    /** 兼容旧调用；tools + vision 仍要求同一 CHAT 来源同时具备两项能力。 */
    @Synchronized
    fun acquire(
        requireTools: Boolean,
        requireVision: Boolean,
        route: AgentRoutePolicy,
        nowMs: Long,
        excludeFingerprints: Set<String> = emptySet()
    ): Lease? {
        val role = if (requireTools) AgentModelRole.PLANNER else if (requireVision) AgentModelRole.VISION else AgentModelRole.PLANNER
        val candidates = eligible(role, route, nowMs, excludeFingerprints).filter {
            (!requireTools || known[it.fingerprint]?.tools == true) && (!requireVision || known[it.fingerprint]?.vision == true)
        }
        val requested = if (role == AgentModelRole.PLANNER) route.fixedIndex?.let { index ->
            candidates.firstOrNull { it.index == index }?.fingerprint
        } else null
        val reserved = health.reserve(candidates, role, nowMs, requested) ?: return null
        return Lease(role, reserved)
    }

    private fun eligible(role: AgentModelRole, route: AgentRoutePolicy, nowMs: Long,
                         excluded: Set<String>): List<AgentModelSource> {
        val candidates = sources.filter { source ->
            val capability = known[source.fingerprint]
            source.index in route.allowedSources && source.fingerprint !in excluded && capability?.fresh(nowMs) == true &&
                when (role) {
                    AgentModelRole.PLANNER -> source.protocol == AgentSourceProtocol.CHAT && (capability.tools || capability.plainPlanning)
                    AgentModelRole.VISION -> capability.vision
                    AgentModelRole.DECISION -> source.protocol == AgentSourceProtocol.DECISIONS && capability.decisions
                }
        }
        if (role != AgentModelRole.PLANNER || route.fixedIndex == null) return candidates
        return if (route.allowFallback) candidates else candidates.filter { it.index == route.fixedIndex }
    }

    internal inner class Lease internal constructor(private val role: AgentModelRole, private val reserved: AgentHealthRegistry.Lease) : AutoCloseable {
        val source: AgentModelSource get() = reserved.source
        private var released = false
        fun succeed(elapsedMs: Long) = synchronized(this@AgentSourceRouter) {
            if (!released) {
                if (role == AgentModelRole.PLANNER) preferred[role] = source.fingerprint
                reserved.succeed(elapsedMs)
                released = true
            }
        }

        fun fail(nowMs: Long, retryAfterMs: Long? = null, error: AgentModelException? = null) = synchronized(this@AgentSourceRouter) {
            if (!released) {
                if (preferred[role] == source.fingerprint) preferred.remove(role)
                reserved.fail(nowMs, retryAfterMs, error)
                released = true
            }
        }
        override fun close() = synchronized(this@AgentSourceRouter) {
            if (!released) { reserved.close(); released = true }
        }
    }
}
