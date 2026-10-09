package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

internal object AgentAccessibility {
    enum class State { CONNECTED, ENABLED_PENDING, DISABLED, UNKNOWN }
    fun state(context: Context): State {
        if (AgentAccessibilityService.connected()) return State.CONNECTED
        return runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
                ?: return@runCatching State.UNKNOWN
            val component = ComponentName(context, AgentAccessibilityService::class.java)
            val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val service = it.resolveInfo?.serviceInfo
                service != null && ComponentName(service.packageName, service.name) == component
            }
            if (enabled) State.ENABLED_PENDING else State.DISABLED
        }.getOrDefault(State.UNKNOWN)
    }

    /** 只打开系统授权界面，不写 Secure 设置，不自动点击授权。 */
    fun settingsIntent(context: Context): Intent {
        // 使用公开入口，避免 OEM 私有详情页的参数或组件名差异。
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
