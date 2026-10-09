package com.Bilibili_Innocent_Lab.xposedmodule.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.LinkedHashMap

/** 枚举顺序属于日志格式版本；只在末尾追加，调整已有顺序时必须升级 VERSION。 */
internal enum class AgentLogPhase { CONNECTING, PLANNING, WAITING, SEARCHING, READING, OPENING, OBSERVING, REVIEWING, STOPPING, COMPLETE, CLICKING, SCROLLING, INPUTTING, BACK }
internal enum class AgentLogStatus { STARTED, SUCCEEDED, FAILED, CANCELLED, LIMITED, FINISHED, INTERRUPTED, FALLBACK }
internal enum class AgentLogRole { NONE, PLANNER, VISION, DECISION }
internal enum class AgentLogTool { NONE, HOST_STATE, SEARCH, DETAILS, OPEN, SCREEN, RENEW, UI_STATE, CLICK, SWIPE, INPUT, BACK }
internal enum class AgentLogReason {
    NONE, NETWORK, AUTH, RATE_LIMIT, TIMEOUT, INVALID_RESPONSE, INVALID_ARGUMENT,
    HOST_UNAVAILABLE, HOST_REJECTED, CONFIG_CHANGED, USER_TAKEOVER, NOT_AUTHORIZED, BUDGET, STALLED, CANCELLED, UNKNOWN,
    ACCESSIBILITY_REQUIRED, PROTECTED_ACTION, STALE_UI
}

internal data class AgentLogEvent(
    val sequence: Long, val elapsedMs: Long, val phase: AgentLogPhase, val status: AgentLogStatus,
    val source: Int, val role: AgentLogRole, val tool: AgentLogTool, val reason: AgentLogReason,
    val durationMs: Long, val inputTokens: Long, val outputTokens: Long,
    val step: Long, val observations: Long, val cacheHit: Boolean
)

internal data class AgentLogTask(
    val id: String, val startedAtWall: Long, val elapsedMs: Long, val status: AgentLogStatus,
    val events: List<AgentLogEvent>, val discardedEvents: Long
)

internal data class AgentLogSnapshot(
    val revision: Long, val tasks: List<AgentLogTask>, val discardedTasks: Long, val totalBytes: Int
)

/**
 * 无自由文本入口的任务运行记录。只保存随机任务 ID 和应用定义的代码、计数，不能记录
 * goal、地址、Key、图片、推理或请求响应。窗口和字节预算独立限制，长程任务不会扩大内存。
 */
internal class AgentExecutionLog(
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    private val maxEncodedBytes: Int = MAX_ENCODED_BYTES
) {
    private class MutableTask(val id: String, val startedAtWall: Long, val startedAtElapsed: Long,
                              var elapsedMs: Long = 0, var status: AgentLogStatus = AgentLogStatus.STARTED,
                              var discardedEvents: Long = 0) {
        val events = ArrayDeque<AgentLogEvent>()
    }
    private val tasks = LinkedHashMap<String, MutableTask>()
    private var revision = 0L
    private var nextSequence = 0L
    private var discardedTasks = 0L
    private var cachedSnapshot: AgentLogSnapshot? = null
    private var restorationAllowed = true

    init { require(maxEncodedBytes in MIN_ENCODED_BYTES..MAX_ENCODED_BYTES) }

    @Synchronized fun begin(taskId: String): Boolean {
        if (!validTaskId(taskId) || taskId in tasks) return false
        val task = MutableTask(taskId, wall().coerceAtLeast(0), elapsed().coerceAtLeast(0))
        tasks[taskId] = task
        add(task, AgentLogPhase.CONNECTING, AgentLogStatus.STARTED)
        while (tasks.size > MAX_TASKS) evictTask(tasks.keys.first())
        trimBytes()
        return true
    }

    @Synchronized fun record(
        taskId: String, phase: AgentLogPhase, status: AgentLogStatus = AgentLogStatus.STARTED,
        source: Int = 0, role: AgentLogRole = AgentLogRole.NONE, tool: AgentLogTool = AgentLogTool.NONE,
        durationMs: Long = 0, inputTokens: Long = 0, outputTokens: Long = 0,
        step: Long = 0, observations: Long = 0, cacheHit: Boolean = false, reason: AgentLogReason = AgentLogReason.NONE
    ): Boolean {
        val task = tasks[taskId]?.takeIf { it.status == AgentLogStatus.STARTED } ?: return false
        if (source !in 0..8 || durationMs < 0 || inputTokens < 0 || outputTokens < 0 || step < 0 || observations < 0 ||
            status !in OPERATION_STATUSES || phase == AgentLogPhase.COMPLETE) return false
        add(task, phase, status, source, role, tool, durationMs, inputTokens, outputTokens, step, observations, cacheHit, reason)
        trimBytes()
        return true
    }

    @Synchronized fun finish(taskId: String, status: AgentLogStatus, step: Long = 0, observations: Long = 0,
                             reason: AgentLogReason = AgentLogReason.NONE): Boolean {
        val task = tasks[taskId]?.takeIf { it.status == AgentLogStatus.STARTED } ?: return false
        if (status !in TERMINAL_STATUSES || step < 0 || observations < 0) return false
        task.status = status
        add(task, AgentLogPhase.COMPLETE, status, step = step, observations = observations, reason = reason)
        trimBytes()
        return true
    }

    @Synchronized fun snapshot(): AgentLogSnapshot = cachedSnapshot ?: AgentLogSnapshot(revision, tasks.values.toList().asReversed().map { task ->
        AgentLogTask(task.id, task.startedAtWall, task.elapsedMs, task.status, task.events.toList(), task.discardedEvents)
    }, discardedTasks, encodedSize()).also { cachedSnapshot = it }

    /** 排队、脏检查只需常数时间版本号，避免用户开始/停止时复制整个历史窗口。 */
    @Synchronized fun revision(): Long = revision

    @Synchronized fun clear() {
        tasks.clear()
        discardedTasks = 0
        restorationAllowed = false
        incrementRevision()
    }

    @Synchronized fun encode(): ByteArray {
        val buffer = ByteArrayOutputStream(encodedSize())
        DataOutputStream(buffer).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(VERSION)
            data.writeLong(revision)
            data.writeLong(nextSequence)
            data.writeLong(discardedTasks)
            data.writeInt(tasks.size)
            tasks.values.forEach { task ->
                data.writeUTF(task.id)
                data.writeLong(task.startedAtWall)
                data.writeLong(task.elapsedMs)
                data.writeByte(task.status.ordinal)
                data.writeLong(task.discardedEvents)
                data.writeInt(task.events.size)
                task.events.forEach { event ->
                    data.writeLong(event.sequence)
                    data.writeLong(event.elapsedMs)
                    data.writeByte(event.phase.ordinal)
                    data.writeByte(event.status.ordinal)
                    data.writeByte(event.source)
                    data.writeByte(event.role.ordinal)
                    data.writeByte(event.tool.ordinal)
                    data.writeByte(event.reason.ordinal)
                    data.writeLong(event.durationMs)
                    data.writeLong(event.inputTokens)
                    data.writeLong(event.outputTokens)
                    data.writeLong(event.step)
                    data.writeLong(event.observations)
                    data.writeBoolean(event.cacheHit)
                }
            }
        }
        return buffer.toByteArray()
    }

    /** 只恢复历史记录；旧进程的活跃任务变为 INTERRUPTED，不恢复控制器或任何操作。 */
    @Synchronized fun mergeRestored(bytes: ByteArray): Boolean {
        if (!restorationAllowed) return false
        val restored = decode(bytes) ?: return false
        val current = tasks.values.toList()
        val ids = current.mapTo(hashSetOf()) { it.id }
        val loaded = restored.tasks.filter { it.id !in ids }
        if (loaded.isEmpty()) return true
        tasks.clear()
        nextSequence = maxOf(nextSequence, restored.nextSequence)
        revision = maxOf(revision, restored.revision)
        discardedTasks = maxOf(discardedTasks, restored.discardedTasks)
        loaded.forEach { task -> tasks[task.id] = task }
        current.forEach { task -> tasks[task.id] = task }
        loaded.filter { it.status == AgentLogStatus.STARTED }.forEach { task ->
            task.status = AgentLogStatus.INTERRUPTED
            // 恢复时沿用最后已知运行时长，不将进程退出后的时间算成实际执行。
            val last = task.events.lastOrNull()
            append(task, AgentLogEvent(incrementSequence(), task.elapsedMs, AgentLogPhase.COMPLETE, AgentLogStatus.INTERRUPTED,
                0, AgentLogRole.NONE, AgentLogTool.NONE, AgentLogReason.UNKNOWN, 0, 0, 0,
                last?.step ?: 0, last?.observations ?: 0, false))
        }
        while (tasks.size > MAX_TASKS) evictTask(tasks.keys.first())
        incrementRevision()
        trimBytes()
        return true
    }

    private fun add(task: MutableTask, phase: AgentLogPhase, status: AgentLogStatus, source: Int = 0,
                    role: AgentLogRole = AgentLogRole.NONE, tool: AgentLogTool = AgentLogTool.NONE,
                    durationMs: Long = 0, inputTokens: Long = 0, outputTokens: Long = 0, step: Long = 0,
                    observations: Long = 0, cacheHit: Boolean = false, reason: AgentLogReason = AgentLogReason.NONE) {
        val now = elapsed().coerceAtLeast(0)
        task.elapsedMs = maxOf(task.elapsedMs, if (now >= task.startedAtElapsed) now - task.startedAtElapsed else 0)
        append(task, AgentLogEvent(incrementSequence(), task.elapsedMs, phase, status, source, role, tool, reason,
            durationMs, inputTokens, outputTokens, step, observations, cacheHit))
        incrementRevision()
    }

    private fun append(task: MutableTask, event: AgentLogEvent) {
        cachedSnapshot = null
        task.events.addLast(event)
        while (task.events.size > MAX_EVENTS_PER_TASK) discardEvent(task)
    }

    private fun trimBytes() {
        while (encodedSize() > maxEncodedBytes) {
            // 留住每个任务的开始和终态；中间事件的丢弃次数进入快照。
            val victim = tasks.values.firstOrNull { it.events.size > 2 }
            if (victim != null) discardEvent(victim)
            else if (tasks.size > 1) evictTask(tasks.keys.first())
            else break
        }
    }

    private fun discardEvent(task: MutableTask) {
        if (task.events.size <= 1) return
        val first = task.events.removeFirst()
        task.events.removeFirst()
        task.events.addFirst(first)
        task.discardedEvents = increment(task.discardedEvents)
        cachedSnapshot = null
    }

    private fun evictTask(id: String) {
        tasks.remove(id)
        discardedTasks = increment(discardedTasks)
        cachedSnapshot = null
    }

    private fun encodedSize(): Int = HEADER_BYTES + tasks.values.sumOf { TASK_BYTES + it.events.size * EVENT_BYTES }
    private fun incrementSequence(): Long { nextSequence = increment(nextSequence); return nextSequence }
    private fun incrementRevision() { revision = increment(revision); cachedSnapshot = null }

    private data class Restored(val revision: Long, val nextSequence: Long, val discardedTasks: Long, val tasks: List<MutableTask>)

    private fun decode(bytes: ByteArray): Restored? {
        if (bytes.size !in HEADER_BYTES..MAX_ENCODED_BYTES) return null
        return runCatching { DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            require(data.readInt() == MAGIC && data.readInt() == VERSION)
            val storedRevision = data.readLong().also { require(it >= 0) }
            val sequence = data.readLong().also { require(it >= 0) }
            val discarded = data.readLong().also { require(it >= 0) }
            val count = data.readInt().also { require(it in 0..MAX_TASKS) }
            val restored = ArrayList<MutableTask>(count)
            val ids = hashSetOf<String>()
            repeat(count) {
                val id = data.readUTF().also { require(validTaskId(it) && ids.add(it)) }
                val started = data.readLong().also { require(it >= 0) }
                val duration = data.readLong().also { require(it >= 0) }
                val status = AgentLogStatus.entries[data.readUnsignedByte()].also { require(it == AgentLogStatus.STARTED || it in TERMINAL_STATUSES) }
                val dropped = data.readLong().also { require(it >= 0) }
                val events = data.readInt().also { require(it in 1..MAX_EVENTS_PER_TASK) }
                val task = MutableTask(id, started, 0, duration, status, dropped)
                var previous = 0L
                repeat(events) {
                    val eventSequence = data.readLong().also { require(it > previous && it <= sequence) }
                    val eventElapsed = data.readLong().also { require(it in 0..duration) }
                    val phase = AgentLogPhase.entries[data.readUnsignedByte()]
                    val eventStatus = AgentLogStatus.entries[data.readUnsignedByte()]
                    val source = data.readUnsignedByte().also { require(it in 0..8) }
                    val role = AgentLogRole.entries[data.readUnsignedByte()]
                    val tool = AgentLogTool.entries[data.readUnsignedByte()]
                    val reason = AgentLogReason.entries[data.readUnsignedByte()]
                    val operationDuration = data.readLong().also { require(it >= 0) }
                    val input = data.readLong().also { require(it >= 0) }
                    val output = data.readLong().also { require(it >= 0) }
                    val step = data.readLong().also { require(it >= 0) }
                    val observations = data.readLong().also { require(it >= 0) }
                    val cache = data.readBoolean()
                    task.events.addLast(AgentLogEvent(eventSequence, eventElapsed, phase, eventStatus, source, role, tool, reason,
                        operationDuration, input, output, step, observations, cache))
                    previous = eventSequence
                }
                require(task.events.first().phase == AgentLogPhase.CONNECTING)
                require(status == AgentLogStatus.STARTED || task.events.last().let { it.phase == AgentLogPhase.COMPLETE && it.status == status })
                restored += task
            }
            require(data.read() == -1)
            Restored(storedRevision, sequence, discarded, restored)
        } }.getOrNull()
    }

    companion object {
        const val MAX_TASKS = 4
        const val MAX_EVENTS_PER_TASK = 256
        const val MAX_ENCODED_BYTES = 64 * 1024
        const val MIN_ENCODED_BYTES = 4096
        private const val MAGIC = 0x42494C41 // BILA
        private const val VERSION = 1
        private const val HEADER_BYTES = 36
        private const val TASK_BYTES = 67 // UTF UUID(38), wall/elapsed/status/drop/count(29)
        private const val EVENT_BYTES = 63 // seven Long values, six codes and cache flag
        private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val OPERATION_STATUSES = setOf(AgentLogStatus.STARTED, AgentLogStatus.SUCCEEDED, AgentLogStatus.FAILED, AgentLogStatus.FALLBACK)
        private val TERMINAL_STATUSES = setOf(AgentLogStatus.FAILED, AgentLogStatus.CANCELLED, AgentLogStatus.LIMITED, AgentLogStatus.FINISHED, AgentLogStatus.INTERRUPTED)
        private fun validTaskId(value: String) = value.length == 36 && UUID.matches(value)
        private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1
    }
}

/** 合并写入时序不依赖 Android；终态可立即提交，普通变化至少间隔一秒。 */
internal class AgentLogFlushPolicy(private val elapsed: () -> Long) {
    private var lastRevision = -1L
    private var lastAttemptAt = -1L

    @Synchronized fun delayFor(revision: Long, immediate: Boolean): Long? {
        require(revision >= 0)
        if (revision == lastRevision && !immediate) return null
        if (immediate) return 0
        val now = elapsed().coerceAtLeast(0)
        return if (lastAttemptAt < 0 || now < lastAttemptAt) MIN_INTERVAL_MS
        else (MIN_INTERVAL_MS - (now - lastAttemptAt)).coerceAtLeast(0)
    }

    @Synchronized fun written(revision: Long, at: Long) {
        lastRevision = revision
        lastAttemptAt = at.coerceAtLeast(0)
    }

    companion object { const val MIN_INTERVAL_MS = 1_000L }
}
