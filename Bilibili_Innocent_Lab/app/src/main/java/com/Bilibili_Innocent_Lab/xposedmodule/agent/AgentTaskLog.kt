package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelException
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelRole
import org.json.JSONObject

/** 只把应用定义的枚举和计数送入日志，原始内容没有持久化入口。 */
internal object AgentTaskLog {
    fun phase(name: String) = when (name) {
        "connecting" -> AgentLogPhase.CONNECTING
        "thinking" -> AgentLogPhase.PLANNING
        "waiting_response", "waiting_host" -> AgentLogPhase.WAITING
        "search_videos" -> AgentLogPhase.SEARCHING
        "get_video_details" -> AgentLogPhase.READING
        "open_video" -> AgentLogPhase.OPENING
        "inspect_screen", "analyzing_image", "get_host_state", "get_ui_state" -> AgentLogPhase.OBSERVING
        "click_ui", "tap_ui" -> AgentLogPhase.CLICKING
        "swipe_ui" -> AgentLogPhase.SCROLLING
        "input_ui_text" -> AgentLogPhase.INPUTTING
        "press_back" -> AgentLogPhase.BACK
        "reviewing" -> AgentLogPhase.REVIEWING
        "stopping" -> AgentLogPhase.STOPPING
        else -> AgentLogPhase.COMPLETE
    }
    fun tool(name: String) = when (name) {
        "get_host_state" -> AgentLogTool.HOST_STATE; "search_videos" -> AgentLogTool.SEARCH
        "get_video_details" -> AgentLogTool.DETAILS; "open_video" -> AgentLogTool.OPEN
        "inspect_screen" -> AgentLogTool.SCREEN; "renew" -> AgentLogTool.RENEW
        "get_ui_state" -> AgentLogTool.UI_STATE; "click_ui", "tap_ui" -> AgentLogTool.CLICK
        "swipe_ui" -> AgentLogTool.SWIPE; "input_ui_text" -> AgentLogTool.INPUT; "press_back" -> AgentLogTool.BACK
        else -> AgentLogTool.NONE
    }
    fun role(value: AgentModelRole?) = when (value) {
        AgentModelRole.PLANNER -> AgentLogRole.PLANNER; AgentModelRole.VISION -> AgentLogRole.VISION
        AgentModelRole.DECISION -> AgentLogRole.DECISION; null -> AgentLogRole.NONE
    }
    fun reason(code: String): AgentLogReason = when (code) {
        "accessibility_not_connected", "accessibility_disconnected", "accessibility_host_unavailable" -> AgentLogReason.ACCESSIBILITY_REQUIRED
        "sensitive_action_blocked", "screen_protected", "ui_protected_or_incomplete" -> AgentLogReason.PROTECTED_ACTION
        "ui_snapshot_stale", "ui_target_missing", "visual_snapshot_required" -> AgentLogReason.STALE_UI
        "task_budget", "task_budget_exhausted" -> AgentLogReason.BUDGET
        "task_stalled", "repeated_operation_failed" -> AgentLogReason.STALLED
        "not_authorized" -> AgentLogReason.NOT_AUTHORIZED
        "configuration_changed", "source_config_changed" -> AgentLogReason.CONFIG_CHANGED
        "cancelled" -> AgentLogReason.CANCELLED
        "user_takeover", "accessibility_control_unverified" -> AgentLogReason.USER_TAKEOVER
        "host_unavailable", "host_unavailable_restart", "host_disconnected", "host_launch_failed", "service_stopped" -> AgentLogReason.HOST_UNAVAILABLE
        "host_response_timeout" -> AgentLogReason.TIMEOUT
        "invalid_tool_arguments", "parallel_tools_rejected", "duplicate_tool_call" -> AgentLogReason.INVALID_ARGUMENT
        else -> AgentModelException.Reason.entries.firstOrNull { it.description == code }?.let(::modelReason) ?: AgentLogReason.UNKNOWN
    }
    private fun modelReason(value: AgentModelException.Reason) = when (value) {
        AgentModelException.Reason.CANCELLED -> AgentLogReason.CANCELLED
        AgentModelException.Reason.TIMEOUT -> AgentLogReason.TIMEOUT
        AgentModelException.Reason.NETWORK -> AgentLogReason.NETWORK
        AgentModelException.Reason.INVALID_RESPONSE -> AgentLogReason.INVALID_RESPONSE
        AgentModelException.Reason.INVALID_REQUEST, AgentModelException.Reason.TOO_LARGE -> AgentLogReason.INVALID_ARGUMENT
        else -> AgentLogReason.UNKNOWN
    }
    fun state(id: String, state: AgentTaskState) {
        val terminal = when (state.phase) {
            "finished" -> AgentLogStatus.FINISHED; "failed" -> AgentLogStatus.FAILED
            "cancelled" -> AgentLogStatus.CANCELLED; "limited" -> AgentLogStatus.LIMITED
            else -> null
        }
        if (terminal != null) AgentExecutionLogStore.finish(id, terminal, state.step, state.observations,
            if (terminal == AgentLogStatus.FINISHED) AgentLogReason.NONE else reason(state.detail))
        else if (phase(state.phase) != AgentLogPhase.COMPLETE) AgentExecutionLogStore.record(id, phase(state.phase),
            source = state.source, role = role(state.role), tool = tool(state.phase), step = state.step, observations = state.observations)
    }
    fun model(id: String, event: AgentRequestUpdate, step: Long, observations: Long) {
        val status = when (event.status) {
            AgentRequestStatus.STARTED -> AgentLogStatus.STARTED; AgentRequestStatus.FAILED -> AgentLogStatus.FAILED
            AgentRequestStatus.FALLBACK -> AgentLogStatus.FALLBACK; else -> AgentLogStatus.SUCCEEDED
        }
        val reason = when (event.httpStatus) { 401, 403 -> AgentLogReason.AUTH; 429 -> AgentLogReason.RATE_LIMIT
            else -> event.error?.let(::modelReason) ?: AgentLogReason.NONE }
        AgentExecutionLogStore.record(id, when (event.role) { AgentModelRole.PLANNER -> AgentLogPhase.WAITING
            AgentModelRole.VISION -> AgentLogPhase.OBSERVING; AgentModelRole.DECISION -> AgentLogPhase.REVIEWING }, status,
            event.source, role(event.role), durationMs = event.durationMs, inputTokens = event.usage?.inputTokens ?: 0,
            outputTokens = event.usage?.outputTokens ?: 0, step = step, observations = observations,
            cacheHit = event.status == AgentRequestStatus.CACHE_HIT, reason = reason)
    }
    fun host(id: String, operation: String, response: JSONObject, elapsedMs: Long, state: AgentTaskState) {
        if (operation == "renew" && response.optBoolean("ok")) return
        val phase = if (operation == "begin" || operation == "renew") AgentLogPhase.CONNECTING else phase(operation)
        if (phase == AgentLogPhase.COMPLETE) return
        AgentExecutionLogStore.record(id, phase, if (response.optBoolean("ok")) AgentLogStatus.SUCCEEDED else AgentLogStatus.FAILED,
            source = state.source, role = role(state.role), tool = tool(operation), durationMs = elapsedMs,
            step = state.step, observations = state.observations,
            cacheHit = response.optJSONObject("data")?.optBoolean("cache_hit") == true,
            reason = if (response.optBoolean("ok")) AgentLogReason.NONE else reason(response.optString("error")))
    }
}
