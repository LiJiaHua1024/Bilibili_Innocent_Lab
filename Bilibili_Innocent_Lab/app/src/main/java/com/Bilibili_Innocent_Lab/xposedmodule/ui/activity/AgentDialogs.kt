package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentController
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentTaskState
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentTaskLimits
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentVisionChallenge
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentWire
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentCapabilityProbe
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentDecisionProbe
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentCapabilityState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.MaxHeightScrollView
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 图片能力探测与任务编排分开排队；关面板即取消探测，不取消用户已启动的宿主任务。 */
private val capabilityWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
    { Thread(it, "BIL-AgentProbe").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

@SuppressLint("UseKtx") // 开关需要 commit 的真实返回值，失败时恢复控件而不报告已启用。
internal fun MainActivity.showAgentDialog(anchor: View? = null) {
    val activity = this
    val taskInputs = mutableListOf<View>()
    val density = resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(TextView(this).apply {
        text = getString(R.string.agent_title)
        textColor = getColor(R.color.colorTextDark)
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }, LinearLayout.LayoutParams(-1, -2))
    val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    fun label(text: CharSequence, small: Boolean = false): TextView = TextView(activity).apply {
        this.text = text
        textSize = if (small) 12f else 14f
        textColor = getColor(R.color.colorTextGray)
        setPadding(0, dp(8), 0, dp(4))
    }.also { body.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    label(getString(R.string.agent_help), true)
    var restoringEnabled = false
    val enabled = CheckBox(this).apply {
        text = getString(R.string.agent_enable)
        textColor = getColor(R.color.colorTextDark)
        isChecked = prefs().getBoolean(AgentPreferences.ENABLED, false)
        setOnCheckedChangeListener { button, checked ->
            if (restoringEnabled) return@setOnCheckedChangeListener
            val preferences = prefs()
            val previousEnabled = preferences.getBoolean(AgentPreferences.ENABLED, false)
            if (!AgentPreferences.writeEnabled(previousEnabled, checked) { value ->
                    preferences.edit().putBoolean(AgentPreferences.ENABLED, value).commit()
                }) {
                restoringEnabled = true
                button.isChecked = previousEnabled
                restoringEnabled = false
                toast(getString(R.string.agent_save_failed))
            } else {
                if (!checked) AgentController.cancel(activity)
                toast(getString(R.string.agent_restart_hint))
            }
        }
    }
    body.addView(enabled)
    body.addView(createTermsActionButton(getString(R.string.agent_configure_sources), filled = false) {
        dismissWithAnimation(dialog, container) { showSemanticJevSettingsDialog(anchor) {} }
    }.also(taskInputs::add), LinearLayout.LayoutParams(-1, -2))

    label(getString(R.string.agent_source_scope))
    val sources = AgentPreferences.sources(this)
    val selected = AgentPreferences.selected(this)
    val sourceViews = sources.associateWith { source ->
        CheckBox(this).apply {
            isChecked = source.index in selected
            textColor = getColor(R.color.colorTextDark)
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        }.also(body::addView).also(taskInputs::add)
    }
    fun updateCapabilities() {
        fun capabilityText(state: AgentCapabilityState?) = getString(when (state) {
            AgentCapabilityState.SUPPORTED -> R.string.agent_detected
            AgentCapabilityState.UNSUPPORTED -> R.string.agent_unsupported
            else -> R.string.agent_unverified
        })
        sourceViews.forEach { (source, view) ->
            val caps = AgentPreferences.capabilities(activity, source)
            view.text = getString(R.string.agent_source_capability, source.index, source.model.take(64),
                capabilityText(caps?.toolState), capabilityText(caps?.decisionState), capabilityText(caps?.visionState))
        }
    }
    updateCapabilities()
    if (sources.isEmpty()) label(getString(R.string.agent_sources_empty), true)
    label(getString(R.string.agent_fixed_source))
    @SuppressLint("SetTextI18n") // 来源编号是 ASCII 协议标识，与路由 ID 一致，不做本地化数字替换。
    val fixed = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(1))
        setSingleLine(true)
        setText(AgentPreferences.fixed(activity)?.toString().orEmpty())
        hint = getString(R.string.agent_auto_route)
        textColor = getColor(R.color.colorTextDark)
    }.also(body::addView).also(taskInputs::add)
    val fallback = CheckBox(this).apply {
        text = getString(R.string.agent_fallback_permission)
        isChecked = AgentPreferences.fallbackAllowed(activity)
        textColor = getColor(R.color.colorTextDark)
        textSize = 13f
    }.also(body::addView).also(taskInputs::add)
    val vision = CheckBox(this).apply {
        text = getString(R.string.agent_vision_permission)
        isChecked = AgentPreferences.visionAllowed(activity)
        textColor = getColor(R.color.colorTextDark)
        textSize = 13f
    }.also(body::addView).also(taskInputs::add)
    val probeStatus = label("", true)
    val probing = AtomicBoolean()
    val closed = AtomicBoolean()
    val app = applicationContext
    body.addView(createTermsActionButton(getString(R.string.agent_probe), filled = false) {
        val chosen = sourceViews.filterValues { it.isChecked }.keys.toList()
        if (chosen.isEmpty()) { toast(getString(R.string.agent_choose_sources)); return@createTermsActionButton }
        if (!probing.compareAndSet(false, true)) return@createTermsActionButton
        probeStatus.text = getString(R.string.agent_probing)
        runCatching { capabilityWorker.execute {
            try {
                val probe = AgentCapabilityProbe(AgentModelRuntime.chatClient,
                    decisionProbe = AgentDecisionProbe(AgentModelRuntime.decisionClient))
                for (source in chosen) {
                    if (closed.get()) break
                    val result = probe.probe(source, AgentVisionChallenge.create(), 30_000, closed::get)
                    if (closed.get()) break
                    val saved = AgentPreferences.saveCapabilities(app, source, result)
                    probeStatus.post {
                        if (!closed.get()) {
                            updateCapabilities()
                            probeStatus.text = if (saved) getString(R.string.agent_probe_result, source.index, result.detail)
                                else getString(R.string.agent_save_failed)
                        }
                    }
                }
            } catch (_: Exception) {
                probeStatus.post { if (!closed.get()) probeStatus.text = getString(R.string.agent_probe_failed) }
            } finally { probing.set(false) }
        } }.onFailure { probing.set(false); probeStatus.text = getString(R.string.agent_probe_busy) }
    }.also(taskInputs::add), LinearLayout.LayoutParams(-1, -2))
    label(getString(R.string.agent_probe_help), true)
    val savedLimits = AgentPreferences.limits(activity)
    @SuppressLint("SetTextI18n") // 预算字段使用 ASCII 整数；0 是固定的无限预算协议值。
    fun budgetInput(value: Long): EditText = EditText(activity).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(19))
        setSingleLine(true)
        setText(value.toString())
        textColor = getColor(R.color.colorTextDark)
    }.also(body::addView).also(taskInputs::add)
    label(getString(R.string.agent_duration_seconds))
    val duration = budgetInput(if (savedLimits.durationMs == 0L) 0L else (savedLimits.durationMs / 1000L).coerceAtLeast(1L))
    label(getString(R.string.agent_maximum_steps))
    val maximumSteps = budgetInput(savedLimits.maximumSteps)
    label(getString(R.string.agent_budget_help), true)
    label(getString(R.string.agent_goal_label))
    val goal = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(AgentWire.MAX_GOAL_LENGTH))
        minLines = 2
        maxLines = 5
        gravity = Gravity.TOP or Gravity.START
        hint = getString(R.string.agent_goal_hint)
        textColor = getColor(R.color.colorTextDark)
    }.also(body::addView).also(taskInputs::add)
    val status = label("", true).apply { setTextIsSelectable(true) }
    val render: (AgentTaskState) -> Unit = { state ->
        taskInputs.forEach { it.isEnabled = !state.running }
        val stage = getString(when (state.phase) {
            "idle" -> R.string.agent_idle
            "connecting" -> R.string.agent_connecting
            "thinking" -> R.string.agent_thinking
            "search_videos" -> R.string.agent_searching
            "get_video_details" -> R.string.agent_reading_details
            "open_video" -> R.string.agent_opening
            "inspect_screen" -> R.string.agent_inspecting
            "get_host_state" -> R.string.agent_reading_state
            "finished" -> R.string.agent_finished
            "cancelled" -> R.string.agent_cancelled
            "stopping" -> R.string.agent_stopping
            "limited" -> R.string.agent_limited
            else -> R.string.agent_failed
        })
        status.text = buildString {
            append(stage)
            if (state.step > 0) append(getString(R.string.agent_steps, state.step,
                if (state.maximumSteps == 0L) getString(R.string.agent_unlimited) else state.maximumSteps.toString()))
            if (state.source > 0) append(getString(R.string.agent_current_source, state.source))
            if (state.detail.isNotEmpty()) append('\n').append(if (state.phase == "finished") state.detail else agentError(state.detail))
        }
    }
    AgentController.observe(render)
    container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { closed.set(true); AgentController.removeObserver(render) }
    })
    container.addView(MaxHeightScrollView(this, minOf(dp(520), (resources.displayMetrics.heightPixels * 0.64f).toInt())).apply {
        addView(body, FrameLayout.LayoutParams(-1, -2))
    }, LinearLayout.LayoutParams(-1, -2))
    val buttons = LinearLayout(this).apply { gravity = Gravity.END; orientation = LinearLayout.HORIZONTAL }
    fun button(label: String, primary: Boolean = false, action: () -> Unit) {
        buttons.addView(createTermsActionButton(label, filled = primary) { action() }.apply {
            if (primary) { taskInputs += this; isEnabled = !AgentController.state.running }
        },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
    }
    button(getString(R.string.dialog_cancel)) { dismissWithAnimation(dialog, container) {} }
    button(getString(R.string.agent_stop)) { AgentController.cancel(activity) }
    button(getString(R.string.agent_start), true) {
        if (probing.get()) { toast(getString(R.string.agent_probe_busy)); return@button }
        val chosen = sourceViews.filterValues { it.isChecked }.keys.mapTo(linkedSetOf()) { it.index }
        val rawFixed = fixed.textToString().trim()
        val fixedIndex = rawFixed.toIntOrNull()
        if (chosen.isEmpty() || (rawFixed.isNotEmpty() && fixedIndex !in chosen)) {
            toast(getString(R.string.agent_choose_sources)); return@button
        }
        val seconds = duration.textToString().trim().toLongOrNull()
        if (seconds == null || seconds !in 0L..Long.MAX_VALUE / 1000L) {
            duration.error = getString(R.string.agent_invalid_duration)
            duration.requestFocus()
            return@button
        }
        val stepLimit = maximumSteps.textToString().trim().toLongOrNull()
        if (stepLimit == null || stepLimit < 0L) {
            maximumSteps.error = getString(R.string.agent_invalid_steps)
            maximumSteps.requestFocus()
            return@button
        }
        val limits = AgentTaskLimits(seconds * 1000L, stepLimit)
        if (!AgentPreferences.saveSelection(activity, chosen, fixedIndex, vision.isChecked, limits, fallback.isChecked)) {
            toast(getString(R.string.agent_save_failed)); return@button
        }
        AgentController.start(activity, goal.textToString(), chosen, fixedIndex, vision.isChecked, limits, fallback.isChecked)?.let {
            status.text = agentError(it)
        }
    }
    container.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    presentModalDialog(dialog, container, anchor)
}

private fun MainActivity.agentError(reason: String): String = getString(when (reason) {
    "accessibility_control_unverified" -> R.string.agent_accessibility_unverified
    "invalid_goal" -> R.string.agent_invalid_goal
    "no_sources", "model_route_unavailable", "planner_route_unavailable", "decision_route_unavailable", "vision_route_unavailable" -> R.string.agent_choose_sources
    "probe_required" -> R.string.agent_probe_required
    "already_running" -> R.string.agent_already_running
    "not_authorized" -> R.string.agent_not_enabled
    "host_unavailable_restart", "host_unavailable", "host_launch_failed", "host_disconnected" -> R.string.agent_host_unavailable
    "cancelled", "service_stopped", "task_inactive" -> R.string.agent_cancelled
    "task_budget", "context_budget" -> R.string.agent_limited
    else -> R.string.agent_error_detail
}, *if (reason !in setOf("accessibility_control_unverified", "invalid_goal", "no_sources", "model_route_unavailable", "planner_route_unavailable", "decision_route_unavailable", "vision_route_unavailable", "probe_required",
        "already_running", "not_authorized", "host_unavailable_restart", "host_unavailable", "host_launch_failed", "host_disconnected",
        "cancelled", "service_stopped", "task_inactive", "task_budget", "context_budget")) arrayOf(reason.take(100)) else emptyArray())
