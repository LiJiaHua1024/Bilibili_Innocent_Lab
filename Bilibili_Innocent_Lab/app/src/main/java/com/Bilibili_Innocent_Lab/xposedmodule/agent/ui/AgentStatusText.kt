package com.Bilibili_Innocent_Lab.xposedmodule.agent.ui

import android.content.Context
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.*

internal object AgentStatusText {
    fun tip(context: Context, state: AgentTaskState): String = context.getString(when (state.phase) {
        "connecting" -> R.string.agent_tip_connect; "thinking" -> R.string.agent_tip_planning
        "waiting_response" -> R.string.agent_tip_waiting; "waiting_host" -> R.string.agent_waiting_host
        "search_videos" -> R.string.agent_tip_search
        "get_video_details" -> R.string.agent_tip_details; "open_video" -> R.string.agent_tip_open
        "inspect_screen", "analyzing_image" -> R.string.agent_tip_inspect; "get_host_state", "get_ui_state" -> R.string.agent_tip_state
        "click_ui", "tap_ui" -> R.string.agent_tip_click; "swipe_ui" -> R.string.agent_tip_swipe
        "input_ui_text" -> R.string.agent_tip_input; "press_back" -> R.string.agent_tip_back
        "reviewing" -> R.string.agent_tip_review; "stopping" -> R.string.agent_stopping
        "finished" -> R.string.agent_finished; "cancelled" -> R.string.agent_cancelled
        "limited" -> R.string.agent_limited; "failed" -> R.string.agent_failed; else -> R.string.agent_idle
    })
    fun phase(context: Context, phase: AgentLogPhase): String = tip(context, AgentTaskState(phase = when (phase) {
        AgentLogPhase.CONNECTING -> "connecting"; AgentLogPhase.PLANNING -> "thinking"; AgentLogPhase.WAITING -> "waiting_response"
        AgentLogPhase.SEARCHING -> "search_videos"; AgentLogPhase.READING -> "get_video_details"; AgentLogPhase.OPENING -> "open_video"
        AgentLogPhase.OBSERVING -> "inspect_screen"; AgentLogPhase.REVIEWING -> "reviewing"; AgentLogPhase.STOPPING -> "stopping"
        AgentLogPhase.COMPLETE -> "finished"
        AgentLogPhase.CLICKING -> "click_ui"; AgentLogPhase.SCROLLING -> "swipe_ui"
        AgentLogPhase.INPUTTING -> "input_ui_text"; AgentLogPhase.BACK -> "press_back"
    }))
    fun status(context: Context, value: AgentLogStatus): String = context.getString(when (value) {
        AgentLogStatus.STARTED -> R.string.agent_log_started; AgentLogStatus.SUCCEEDED -> R.string.agent_log_succeeded
        AgentLogStatus.FAILED -> R.string.agent_failed; AgentLogStatus.CANCELLED -> R.string.agent_cancelled
        AgentLogStatus.LIMITED -> R.string.agent_limited; AgentLogStatus.FINISHED -> R.string.agent_finished
        AgentLogStatus.INTERRUPTED -> R.string.agent_log_interrupted; AgentLogStatus.FALLBACK -> R.string.agent_log_fallback
    })
    fun role(context: Context, role: AgentLogRole): String = context.getString(when (role) {
        AgentLogRole.PLANNER -> R.string.agent_log_planner; AgentLogRole.VISION -> R.string.agent_log_vision
        AgentLogRole.DECISION -> R.string.agent_log_decision; AgentLogRole.NONE -> R.string.agent_log_host
    })
    fun tool(context: Context, value: AgentLogTool): String = tip(context, AgentTaskState(phase = when (value) {
        AgentLogTool.HOST_STATE -> "get_host_state"; AgentLogTool.SEARCH -> "search_videos"; AgentLogTool.DETAILS -> "get_video_details"
        AgentLogTool.OPEN -> "open_video"; AgentLogTool.SCREEN -> "inspect_screen"; AgentLogTool.RENEW -> "connecting"; AgentLogTool.NONE -> "idle"
        AgentLogTool.UI_STATE -> "get_ui_state"; AgentLogTool.CLICK -> "click_ui"; AgentLogTool.SWIPE -> "swipe_ui"
        AgentLogTool.INPUT -> "input_ui_text"; AgentLogTool.BACK -> "press_back"
    }))
    fun reason(context: Context, value: AgentLogReason): String = if (value == AgentLogReason.NONE) "" else context.getString(when (value) {
        AgentLogReason.NETWORK -> R.string.agent_log_network; AgentLogReason.AUTH -> R.string.agent_log_auth
        AgentLogReason.RATE_LIMIT -> R.string.agent_log_rate_limit; AgentLogReason.TIMEOUT -> R.string.agent_log_timeout
        AgentLogReason.INVALID_RESPONSE -> R.string.agent_log_invalid_response; AgentLogReason.INVALID_ARGUMENT -> R.string.agent_log_invalid_argument
        AgentLogReason.HOST_UNAVAILABLE -> R.string.agent_host_unavailable; AgentLogReason.HOST_REJECTED -> R.string.agent_log_host_rejected
        AgentLogReason.CONFIG_CHANGED -> R.string.agent_log_config_changed; AgentLogReason.USER_TAKEOVER -> R.string.agent_log_takeover
        AgentLogReason.NOT_AUTHORIZED -> R.string.agent_not_enabled; AgentLogReason.BUDGET -> R.string.agent_limited
        AgentLogReason.STALLED -> R.string.agent_log_stalled; AgentLogReason.CANCELLED -> R.string.agent_cancelled
        AgentLogReason.ACCESSIBILITY_REQUIRED -> R.string.agent_accessibility_required
        AgentLogReason.PROTECTED_ACTION -> R.string.agent_sensitive_blocked; AgentLogReason.STALE_UI -> R.string.agent_ui_stale
        else -> R.string.agent_log_unknown
    })
}
