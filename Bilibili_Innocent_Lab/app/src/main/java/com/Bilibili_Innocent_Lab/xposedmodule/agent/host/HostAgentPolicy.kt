package com.Bilibili_Innocent_Lab.xposedmodule.agent.host

import java.net.URI
import java.net.URLEncoder
import java.util.LinkedHashMap

/** 只存任务元数据和有限的公开视频身份；取消与迟到动作共用同一把短锁。 */
internal class HostAgentSession(private val maximumLeaseMs: Long = 180_000L,
                                private val maximumCommandMs: Long = minOf(maximumLeaseMs, 15_000L)) {
    init { require(maximumLeaseMs in 1L..180_000L && maximumCommandMs in 1L..minOf(maximumLeaseMs, 15_000L)) }
    data class Lease(val taskId: String, val generation: Long, val deadline: Long)
    data class Admission(val lease: Lease? = null, val error: String? = null)
    private data class Task(
        val id: String,
        val generation: Long,
        var deadline: Long,
        val allowVision: Boolean,
        var sequence: Long,
        val candidates: LinkedHashMap<String, Unit> = LinkedHashMap(MAX_CANDIDATES, 0.75f, true),
        val cursors: LinkedHashMap<String, Pair<String, String>> = LinkedHashMap(MAX_CURSORS, 0.75f, true),
        var nextCursor: Long = 1,
        var bucketStarted: Long,
        var admittedInBucket: Int = 1,
        var expectedQuery: String? = null,
        var expectedVideo: String? = null
    )

    private var generation = 0L
    private var active: Task? = null
    private val closed = linkedMapOf<String, Long>()

    @Synchronized
    fun begin(id: String, sequence: Long, deadline: Long, now: Long, allowVision: Boolean): Admission =
        begin(id, sequence, minOf(deadline, boundedDeadline(now, maximumCommandMs)), deadline, now, allowVision)

    @Synchronized
    fun begin(id: String, sequence: Long, commandDeadline: Long, taskDeadline: Long, now: Long, allowVision: Boolean): Admission {
        expire(now)
        closed.entries.removeAll { now >= it.value }
        if (!TASK_ID.matches(id) || sequence != 1L || !validCommandDeadline(commandDeadline, now) ||
            !validTaskDeadline(taskDeadline, now) || commandDeadline > taskDeadline) {
            return Admission(error = "invalid_begin")
        }
        if (id in closed) return Admission(error = "closed_task")
        if (active != null) return Admission(error = "task_busy")
        if (generation == Long.MAX_VALUE) return Admission(error = "generation_exhausted")
        val task = Task(id, ++generation, taskDeadline, allowVision, sequence, bucketStarted = now)
        active = task
        return Admission(Lease(id, task.generation, commandDeadline))
    }

    /** 仅续当前任务授权；旧 operation lease 保持原 deadline，迟到操作不能因续租重获权限。 */
    @Synchronized
    fun renew(id: String, sequence: Long, commandDeadline: Long, taskDeadline: Long, now: Long): Admission {
        expire(now)
        val task = active ?: return Admission(error = "task_inactive")
        if (id != task.id) return Admission(error = "task_mismatch")
        if (!validCommandDeadline(commandDeadline, now) || !validTaskDeadline(taskDeadline, now) ||
            commandDeadline > taskDeadline) return Admission(error = "invalid_deadline")
        if (sequence <= task.sequence) return Admission(error = "replayed_sequence")
        if (!consumeAdmission(task, now)) return Admission(error = "host_rate_limited")
        task.sequence = sequence
        task.deadline = maxOf(task.deadline, taskDeadline)
        return Admission(Lease(id, task.generation, commandDeadline))
    }

    @Synchronized
    fun admit(id: String, sequence: Long, deadline: Long, now: Long, closing: Boolean = false): Admission {
        expire(now)
        val task = active ?: return Admission(error = "task_inactive")
        if (id != task.id) return Admission(error = "task_mismatch")
        if (!validCommandDeadline(deadline, now) || (!closing && deadline > task.deadline)) return Admission(error = "invalid_deadline")
        if (sequence <= task.sequence) return Admission(error = "replayed_sequence")
        if (!closing && !consumeAdmission(task, now)) return Admission(error = "host_rate_limited")
        task.sequence = sequence
        val lease = Lease(id, task.generation, deadline)
        if (closing) close(task, now)
        return Admission(lease)
    }

    @Synchronized
    fun isActive(lease: Lease, now: Long): Boolean {
        expire(now)
        return isTaskActive(lease, now) && now < lease.deadline
    }

    /** Window/Service 所有权可跨续租存活；只能长期监听使用，不能用于 RPC/截图/动作回调。 */
    @Synchronized
    fun isTaskActive(lease: Lease, now: Long): Boolean {
        expire(now)
        return active?.let { it.id == lease.taskId && it.generation == lease.generation } == true
    }

    /** 只包围短的主线程动作，绝不在锁内执行网络、压缩或等待。 */
    @Synchronized
    fun <T> whileActive(lease: Lease, now: Long, action: () -> T): T? =
        if (isActive(lease, now)) action() else null

    @Synchronized
    fun current(now: Long): Lease? {
        expire(now)
        return active?.let { Lease(it.id, it.generation, it.deadline) }
    }

    @Synchronized
    fun cancel(lease: Lease, now: Long): Boolean {
        val task = active ?: return false
        if (task.id != lease.taskId || task.generation != lease.generation) return false
        close(task, now)
        return true
    }

    @Synchronized
    fun allowVision(lease: Lease, now: Long): Boolean = isActive(lease, now) && active?.allowVision == true

    @Synchronized
    fun remember(lease: Lease, identities: List<String>, now: Long): List<String> {
        if (!isActive(lease, now)) return emptyList()
        val task = active ?: return emptyList()
        identities.forEach { id ->
            if (HostAgentNavigationPolicy.videoId(id) == id) {
                task.candidates[id] = Unit
                while (task.candidates.size > MAX_CANDIDATES) task.candidates.remove(task.candidates.keys.first())
            }
        }
        return identities.distinct().filter { task.candidates.containsKey(it) }
    }

    @Synchronized
    fun knows(lease: Lease, identity: String, now: Long): Boolean =
        isActive(lease, now) && active!!.candidates[identity] != null

    @Synchronized
    fun rememberCursor(lease: Lease, query: String, raw: String?, now: Long): String? {
        if (!isActive(lease, now) || raw.isNullOrBlank() || raw.length > 2048) return null
        val task = active ?: return null
        task.cursors.entries.firstOrNull { it.value == (query to raw) }?.key?.let { existing ->
            task.cursors[existing]
            return existing
        }
        if (task.nextCursor == Long.MAX_VALUE) return null
        val token = "page:${task.nextCursor++}"
        task.cursors[token] = query to raw
        while (task.cursors.size > MAX_CURSORS) task.cursors.remove(task.cursors.keys.first())
        return token
    }

    @Synchronized
    fun cursor(lease: Lease, query: String, token: String, now: Long): String? =
        if (isActive(lease, now)) active?.cursors?.get(token)?.takeIf { it.first == query }?.second else null

    @Synchronized
    fun expectedPage(lease: Lease, query: String? = null, video: String? = null, now: Long) {
        if (!isActive(lease, now)) return
        active?.apply { expectedQuery = query; expectedVideo = video }
    }

    @Synchronized
    fun matchesPage(lease: Lease, kind: String, identity: String?, now: Long): Boolean {
        if (!isActive(lease, now) || identity.isNullOrBlank()) return false
        return when (kind) {
            "search" -> active?.expectedQuery == identity
            "video" -> active?.expectedVideo == identity
            else -> false
        }
    }

    private fun expire(now: Long) { active?.takeIf { now >= it.deadline }?.let { close(it, now) } }
    private fun close(task: Task, now: Long) {
        closed[task.id] = boundedDeadline(now, maximumLeaseMs)
        while (closed.size > 32) closed.remove(closed.keys.first())
        active = null
    }

    companion object {
        private val TASK_ID = Regex("[A-Za-z0-9_-]{8,96}")
        const val MAX_CANDIDATES = 256
        const val MAX_CURSORS = 16
        // 限制突发收包而不是限制任务终身总操作数；取消不受此限。
        const val MAX_COMMANDS_PER_SECOND = 48
    }

    private fun validCommandDeadline(deadline: Long, now: Long): Boolean =
        now >= 0 && deadline > now && deadline - now <= maximumCommandMs

    private fun validTaskDeadline(deadline: Long, now: Long): Boolean =
        now >= 0 && deadline > now && deadline - now <= maximumLeaseMs

    private fun consumeAdmission(task: Task, now: Long): Boolean {
        if (now - task.bucketStarted >= 1_000L) { task.bucketStarted = now; task.admittedInBucket = 0 }
        if (task.admittedInBucket >= MAX_COMMANDS_PER_SECOND) return false
        task.admittedInBucket++
        return true
    }

    private fun boundedDeadline(now: Long, interval: Long): Long =
        if (interval > Long.MAX_VALUE - now) Long.MAX_VALUE else now + interval
}

internal object HostAgentNavigationPolicy {
    private val bvid = Regex("BV[0-9A-Za-z]{10}")
    private val aid = Regex("(?:av)?[1-9][0-9]{0,18}")

    fun videoId(raw: String?): String? {
        if (raw == null || raw.length > 24) return null
        if (bvid.matches(raw)) return raw
        if (!aid.matches(raw)) return null
        val number = raw.removePrefix("av").toLongOrNull()?.takeIf { it > 0 } ?: return null
        return "av$number"
    }

    /** 只读取明确的 video 路由身份，丢弃全部查询参数，绝不把宿主原始 URI 交给执行器。 */
    fun videoFromUri(raw: String?): String? = runCatching {
        if (raw == null || raw.length > 8192) return null
        val uri = URI(raw)
        if (!uri.scheme.equals("bilibili", true) || !uri.rawAuthority.equals("video", true) ||
            uri.rawPath != uri.path || uri.userInfo != null || uri.port != -1) return null
        videoId(uri.path?.removePrefix("/"))
    }.getOrNull()

    fun candidateId(param: String?, uri: String?): String? {
        val fromParam = videoId(param)
        val fromUri = videoFromUri(uri)
        if (fromParam != null && fromUri != null && fromParam.startsWith("av") == fromUri.startsWith("av") &&
            fromParam != fromUri) return null
        return fromParam ?: fromUri
    }

    fun videoRoute(id: String): String? = videoId(id)?.let { "bilibili://video/${it.removePrefix("av")}" }

    fun searchQuery(raw: String?): String? = raw?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= 200 && it.none { char -> char.isISOControl() }
    }

    fun searchRoute(query: String): String? = searchQuery(query)?.let {
        "bilibili://search?keyword=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20")
    }
}

/** 保护判据是纯函数；树扫描未完成与页面身份不明同样拒绝截图。 */
internal object HostAgentScreenPolicy {
    fun refusal(
        authorized: Boolean,
        foreground: Boolean,
        taskPage: Boolean,
        secure: Boolean,
        password: Boolean,
        editableFocus: Boolean,
        completeInspection: Boolean,
        accessibilityEnabled: Boolean?
    ): String? = when {
        !authorized -> "vision_not_authorized"
        !HostAgentAccessibilityPolicy.mayControl(accessibilityEnabled) -> HostAgentAccessibilityPolicy.REASON
        !foreground -> "host_not_foreground"
        !taskPage -> "screen_not_task_page"
        secure -> "screen_secure"
        password -> "screen_password"
        editableFocus -> "screen_input_active"
        !completeInspection -> "screen_inspection_incomplete"
        else -> null
    }
}

/** 无障碍可绕过 Window 的触摸/按键分派，首版只接受明确的关闭状态，未知同样拒绝。 */
internal object HostAgentAccessibilityPolicy {
    const val REASON = "accessibility_control_unverified"
    fun mayControl(enabled: Boolean?): Boolean = enabled == false
}

/**
 * 前台控制权只属于未被用户接管的同一窗口。Intent 是导航匹配证据之一，不能单独恢复控制权。
 * 首次搜索可以从 begin 时的前台窗口发起；之后只能从已观测的任务页继续，或等待有界导航。仅主线程使用。
 */
internal class HostAgentWindowPolicy(private val navigationMs: Long = NAVIGATION_MS) {
    enum class Observation { READY, NAVIGATING, REVOKED }
    data class Target(val kind: String, val identity: String)
    private data class Transition(val token: Long, val target: Target, val expires: Long)

    private var generation: Long? = null
    private var window: Long? = null
    private var target: Target? = null
    private var pending: Transition? = null
    private var serial = 0L

    fun begin(generation: Long, window: Long) {
        this.generation = generation
        this.window = window
        target = null
        pending = null
    }

    fun expectNavigation(generation: Long, window: Long, target: Target, now: Long): Long? {
        if (this.generation != generation || this.window != window || pending != null ||
            target.kind !in setOf("search", "video") || target.identity.isBlank()) return null
        val token = ++serial
        pending = Transition(token, target, now + navigationMs)
        return token
    }

    fun observe(generation: Long, window: Long, kind: String, identity: String?, now: Long): Observation {
        if (this.generation != generation) return Observation.REVOKED
        if (this.window != window) { revoke(generation); return Observation.REVOKED }
        val transition = pending
        if (transition != null) {
            if (now >= transition.expires) { revoke(generation); return Observation.REVOKED }
            if (transition.target != identity?.let { Target(kind, it) }) return Observation.NAVIGATING
            target = transition.target
            pending = null
        }
        if (target != null && target != identity?.let { Target(kind, it) }) {
            revoke(generation)
            return Observation.REVOKED
        }
        return Observation.READY
    }

    /** 返回 false 时必须立即停止。预期导航只允许当前窗口离开一次，不授权其它页面接管。 */
    fun pause(generation: Long, window: Long, now: Long): Boolean {
        if (this.generation != generation || this.window != window) return false
        if (!awaitingNavigation(generation, now)) { revoke(generation); return false }
        this.window = null
        return true
    }

    fun resume(generation: Long, window: Long, kind: String, identity: String?, now: Long): Boolean {
        if (this.generation != generation) return false
        val transition = pending
        if (transition == null) {
            val retained = this.window == window && observe(generation, window, kind, identity, now) == Observation.READY
            if (!retained) revoke(generation)
            return retained
        }
        if (now >= transition.expires || transition.target != identity?.let { Target(kind, it) }) {
            revoke(generation)
            return false
        }
        this.window = window
        target = transition.target
        pending = null
        return true
    }

    fun awaitingNavigation(generation: Long, now: Long): Boolean =
        this.generation == generation && pending?.let { now < it.expires } == true

    fun expireNavigation(generation: Long, token: Long, now: Long): Boolean {
        val transition = pending ?: return false
        if (this.generation != generation || transition.token != token || now < transition.expires) return false
        revoke(generation)
        return true
    }

    fun isTaskPage(generation: Long): Boolean = this.generation == generation && target != null && pending == null

    fun tracks(generation: Long): Boolean = this.generation == generation

    fun revoke(generation: Long) {
        if (this.generation != generation) return
        this.generation = null
        window = null
        target = null
        pending = null
    }

    companion object { const val NAVIGATION_MS = 5_000L }
}
