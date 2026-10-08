package com.Bilibili_Innocent_Lab.xposedmodule.agent.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentExecutionLogStore
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentLogEvent
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentLogPhase
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentLogReason
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentLogSnapshot
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentLogTool

/** 同一只读日志列表用于模块弹窗和悬浮窗口；数据只持有有界事件快照，行没有操作或复制入口。 */
internal class AgentLogView(context: Context) : LinearLayout(context) {
    private val taskHeader = label(14f, PRIMARY).apply { setTypeface(typeface, Typeface.BOLD) }
    private val counts = label(12f, SECONDARY)
    private val empty = label(14f, SECONDARY).apply {
        text = context.getString(R.string.agent_logs_empty)
        gravity = Gravity.CENTER
    }
    private val persistence = label(12f, Color.rgb(253, 186, 116)).apply {
        text = context.getString(R.string.agent_logs_persistence_failed)
        visibility = GONE
    }
    private val nextTask = Button(context).apply {
        text = context.getString(R.string.agent_logs_next)
        isAllCaps = false
        setTextColor(PRIMARY)
        textSize = 12f
        minHeight = dp(36)
        minimumHeight = dp(36)
        backgroundTintList = ColorStateList.valueOf(Color.rgb(51, 65, 85))
    }
    private val rows = LogAdapter()
    private val layout = LinearLayoutManager(context)
    private val list = RecyclerView(context).apply {
        layoutManager = layout
        adapter = rows
        itemAnimator = null
        isVerticalScrollBarEnabled = true
        clipToPadding = false
        setPadding(0, dp(4), 0, dp(8))
    }
    private var latest: AgentLogSnapshot? = null
    private var selectedTask: String? = null
    private var shownRevision = -1L
    private var shownDiskFailure: Boolean? = null
    private var subscribed = false
    private var closed = false
    private val observer: (AgentLogSnapshot) -> Unit = { snapshot ->
        if (!closed && subscribed) {
            updatePersistence()
            if (snapshot.revision != shownRevision) {
                latest = snapshot
                render(snapshot)
            }
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.rgb(24, 28, 36))
        setPadding(dp(12), dp(10), dp(12), dp(6))
        val toolbar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(label(16f, PRIMARY).apply {
            text = context.getString(R.string.agent_logs_title)
            setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(nextTask, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(taskHeader, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(counts, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        addView(persistence, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(empty, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        list.visibility = GONE
        nextTask.isEnabled = false
        nextTask.setOnClickListener {
            val snapshot = latest ?: return@setOnClickListener
            if (snapshot.tasks.size < 2) return@setOnClickListener
            val index = snapshot.tasks.indexOfFirst { it.id == selectedTask }
            selectedTask = snapshot.tasks[(index + 1).coerceAtLeast(0) % snapshot.tasks.size].id
            render(snapshot, selectionChanged = true)
        }
        AgentExecutionLogStore.initialize(context.applicationContext)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!closed && !subscribed) {
            subscribed = true
            shownRevision = -1
            AgentExecutionLogStore.observe(observer)
        }
    }

    override fun onDetachedFromWindow() {
        unsubscribe()
        super.onDetachedFromWindow()
    }

    /** 宿主关闭悬浮面板或模块弹窗时可主动解除观察，detach 重复调用也安全。 */
    fun close() {
        closed = true
        unsubscribe()
        latest = null
        rows.release()
    }

    private fun unsubscribe() {
        if (!subscribed) return
        subscribed = false
        AgentExecutionLogStore.removeObserver(observer)
    }

    private fun render(snapshot: AgentLogSnapshot, selectionChanged: Boolean = false) {
        shownRevision = snapshot.revision
        val task = snapshot.tasks.firstOrNull { it.id == selectedTask } ?: snapshot.tasks.firstOrNull()
        val changedTask = selectedTask != task?.id || selectionChanged
        selectedTask = task?.id
        nextTask.isEnabled = snapshot.tasks.size > 1
        nextTask.alpha = if (nextTask.isEnabled) 1f else 0.5f
        updatePersistence()
        empty.visibility = if (task == null) VISIBLE else GONE
        list.visibility = if (task == null) GONE else VISIBLE
        taskHeader.visibility = if (task == null) GONE else VISIBLE
        counts.visibility = if (task == null) GONE else VISIBLE
        if (task == null) { rows.submit(emptyList(), reset = true); return }
        taskHeader.text = context.getString(R.string.agent_logs_task, task.id.take(8), AgentStatusText.status(context, task.status))
        // 计数只扫描当前最多 256 条事件一次；绑定单行时不查历史任务。
        val steps = task.events.maxOfOrNull { it.step } ?: 0
        val observations = task.events.maxOfOrNull { it.observations } ?: 0
        counts.text = context.getString(R.string.agent_logs_count, task.events.size, task.discardedEvents, steps, observations)
        val follow = changedTask || !list.canScrollVertically(1)
        rows.submit(task.events, reset = changedTask)
        if (follow && rows.itemCount > 0) layout.scrollToPosition(rows.itemCount - 1)
    }

    private fun updatePersistence() {
        val failed = AgentExecutionLogStore.persistenceFailed()
        if (shownDiskFailure == failed) return
        shownDiskFailure = failed
        persistence.visibility = if (failed) VISIBLE else GONE
    }

    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        setTextIsSelectable(false)
        includeFontPadding = false
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private inner class LogAdapter : RecyclerView.Adapter<LogHolder>() {
        private var events: List<AgentLogEvent> = emptyList()

        init { setHasStableIds(true) }

        fun submit(next: List<AgentLogEvent>, reset: Boolean) {
            if (events === next) return
            val previous = events
            events = next
            if (reset) {
                notifyDataSetChanged()
            } else {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = previous.size
                    override fun getNewListSize() = next.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        previous[oldItemPosition].sequence == next[newItemPosition].sequence
                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                        previous[oldItemPosition] == next[newItemPosition]
                }, false).dispatchUpdatesTo(this)
            }
        }

        fun release() { submit(emptyList(), reset = true) }
        override fun getItemCount() = events.size
        override fun getItemId(position: Int) = events[position].sequence

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogHolder {
            val container = LinearLayout(parent.context).apply {
                orientation = VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val title = label(13f, PRIMARY)
            val source = label(12f, SECONDARY)
            val metrics = label(12f, SECONDARY)
            val reason = label(12f, Color.rgb(253, 186, 116))
            listOf(title, source, metrics, reason).forEach { container.addView(it) }
            return LogHolder(container, title, source, metrics, reason)
        }

        override fun onBindViewHolder(holder: LogHolder, position: Int) {
            val event = events[position]
            val operation = if (event.tool == AgentLogTool.NONE || event.tool == AgentLogTool.RENEW)
                AgentStatusText.phase(context, event.phase) else AgentStatusText.tool(context, event.tool)
            holder.title.text = DateUtils.formatElapsedTime(event.elapsedMs / 1_000) + " · " +
                (if (event.phase == AgentLogPhase.COMPLETE) "" else "$operation · ") + AgentStatusText.status(context, event.status)
            holder.source.visibility = if (event.source > 0) VISIBLE else GONE
            if (event.source > 0) holder.source.text = context.getString(R.string.agent_logs_source,
                event.source, AgentStatusText.role(context, event.role))
            val hasMetrics = event.durationMs > 0 || event.inputTokens > 0 || event.outputTokens > 0 || event.cacheHit
            holder.metrics.visibility = if (hasMetrics) VISIBLE else GONE
            if (hasMetrics) {
                val metrics = if (event.inputTokens == 0L && event.outputTokens == 0L)
                    "${event.durationMs} ms · " + context.getString(R.string.agent_logs_no_usage)
                else context.getString(R.string.agent_logs_metrics, event.durationMs, event.inputTokens, event.outputTokens)
                holder.metrics.text = metrics + if (event.cacheHit) " · " + context.getString(R.string.agent_logs_cache) else ""
            }
            holder.reason.visibility = if (event.reason == AgentLogReason.NONE) GONE else VISIBLE
            if (event.reason != AgentLogReason.NONE) holder.reason.text = AgentStatusText.reason(context, event.reason)
        }
    }

    private class LogHolder(view: View, val title: TextView, val source: TextView, val metrics: TextView,
                            val reason: TextView) : RecyclerView.ViewHolder(view)

    private companion object {
        val PRIMARY: Int = Color.WHITE
        val SECONDARY: Int = Color.rgb(203, 213, 225)
    }
}
