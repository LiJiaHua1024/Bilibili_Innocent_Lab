package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 私有日志的磁盘和观察边界。主线程不读写文件，每轮最多排一个合并写入和一个 UI 通知。 */
internal object AgentExecutionLogStore {
    private val log = AgentExecutionLog(SystemClock::elapsedRealtime, System::currentTimeMillis)
    private val main = Handler(Looper.getMainLooper())
    private val worker = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "BIL-AgentJournal").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val observers = CopyOnWriteArraySet<(AgentLogSnapshot) -> Unit>()
    private val scheduling = Any()
    private val writes = AgentLogFlushPolicy(SystemClock::elapsedRealtime)
    private var file: AtomicFile? = null
    private var scheduledWrite: ScheduledFuture<*>? = null
    private var writeQueued = false
    private var immediateRequested = false
    private var writeGeneration = 0L
    private var notifyQueued = false
    private var lastNotifyAt = -1L
    @Volatile private var diskFailure = false

    fun initialize(context: Context) {
        val app = context.applicationContext
        synchronized(scheduling) {
            if (file != null) return
            // 唯一文件名由应用定义，永远在模块私有 filesDir，外部 Intent 不参与路径构造。
            file = AtomicFile(File(app.filesDir, "agent_execution_log.bin"))
            worker.execute {
                val target = synchronized(scheduling) { file } ?: return@execute
                val bytes = runCatching {
                    target.openRead().use { input ->
                        val buffer = ByteArray(AgentExecutionLog.MAX_ENCODED_BYTES + 1)
                        var count = 0
                        while (count < buffer.size) {
                            val read = input.read(buffer, count, buffer.size - count)
                            if (read < 0) break
                            if (read == 0) break
                            count += read
                        }
                        if (count <= AgentExecutionLog.MAX_ENCODED_BYTES) buffer.copyOf(count) else null
                    }
                }.getOrNull()
                if (bytes != null && log.mergeRestored(bytes)) {
                    changed(immediate = true)
                }
            }
        }
    }

    fun begin(taskId: String): Boolean = log.begin(taskId).also { if (it) changed() }

    fun record(
        taskId: String, phase: AgentLogPhase, status: AgentLogStatus = AgentLogStatus.STARTED,
        source: Int = 0, role: AgentLogRole = AgentLogRole.NONE, tool: AgentLogTool = AgentLogTool.NONE,
        durationMs: Long = 0, inputTokens: Long = 0, outputTokens: Long = 0,
        step: Long = 0, observations: Long = 0, cacheHit: Boolean = false, reason: AgentLogReason = AgentLogReason.NONE
    ): Boolean = log.record(taskId, phase, status, source, role, tool, durationMs, inputTokens, outputTokens,
        step, observations, cacheHit, reason).also { if (it) changed() }

    fun finish(taskId: String, status: AgentLogStatus, step: Long = 0, observations: Long = 0,
               reason: AgentLogReason = AgentLogReason.NONE): Boolean = log.finish(taskId, status, step, observations, reason)
        .also { if (it) changed(immediate = true) }

    fun snapshot(): AgentLogSnapshot = log.snapshot()
    fun persistenceFailed(): Boolean = diskFailure
    fun clear() { log.clear(); changed(immediate = true) }

    fun observe(observer: (AgentLogSnapshot) -> Unit) {
        observers += observer
        main.post { if (observer in observers) runCatching { observer(log.snapshot()) } }
    }

    fun removeObserver(observer: (AgentLogSnapshot) -> Unit) { observers -= observer }

    /** 收尾不等待一秒合并窗口；调用方不会等待磁盘完成。 */
    fun flush() { scheduleWrite(immediate = true) }

    private fun changed(immediate: Boolean = false) {
        scheduleWrite(immediate)
        notifyObservers()
    }

    private fun notifyObservers() {
        if (observers.isEmpty()) return
        synchronized(scheduling) {
            if (notifyQueued) return
            notifyQueued = true
            val now = SystemClock.elapsedRealtime()
            val delay = if (lastNotifyAt < 0 || now < lastNotifyAt) 0 else (NOTIFY_INTERVAL_MS - (now - lastNotifyAt)).coerceAtLeast(0)
            main.postDelayed({
                synchronized(scheduling) { notifyQueued = false; lastNotifyAt = SystemClock.elapsedRealtime() }
                if (observers.isNotEmpty()) {
                    val snapshot = log.snapshot()
                    observers.forEach { observer -> runCatching { observer(snapshot) } }
                }
            }, delay)
        }
    }

    private fun scheduleWrite(immediate: Boolean) = synchronized(scheduling) {
        if (file == null) return@synchronized
        if (immediate) immediateRequested = true
        if (writeQueued) return@synchronized
        if (immediate && scheduledWrite != null) {
            writeGeneration++
            scheduledWrite?.cancel(false)
            scheduledWrite = null
        }
        if (scheduledWrite != null) return@synchronized
        val delay = writes.delayFor(log.revision(), immediateRequested) ?: return@synchronized
        if (delay == 0L) {
            writeQueued = true
            worker.execute(::write)
        } else {
            val generation = ++writeGeneration
            scheduledWrite = worker.schedule({
                val current = synchronized(scheduling) {
                    if (generation != writeGeneration || writeQueued) false else {
                        scheduledWrite = null
                        writeQueued = true
                        true
                    }
                }
                if (current) write()
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun write() {
        val target = synchronized(scheduling) { immediateRequested = false; file }
        val revision = log.revision()
        var succeeded = false
        try {
            val bytes = log.encode()
            var output: java.io.FileOutputStream? = null
            succeeded = target != null && runCatching {
                output = target.startWrite()
                output!!.write(bytes)
                target.finishWrite(output)
                output = null
                true
            }.getOrElse {
                if (output != null) runCatching { target.failWrite(output) }
                false
            }
        } finally {
            val failed = !succeeded
            val changed = diskFailure != failed
            diskFailure = failed
            // 失败也遵守间隔；只在后续变化或显式收尾时重试，避免磁盘失败形成后台热循环。
            writes.written(revision, SystemClock.elapsedRealtime())
            synchronized(scheduling) { writeQueued = false }
            if (changed) notifyObservers()
            if (log.revision() != revision) scheduleWrite(immediate = false)
        }
    }

    private const val NOTIFY_INTERVAL_MS = 250L
}
