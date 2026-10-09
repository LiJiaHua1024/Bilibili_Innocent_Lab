package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 非导出接收器只接收模块自己的不可变 PendingIntent，旧任务的按钮不会影响新任务。 */
class AgentNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = intent.data?.takeIf { it.scheme == "agent-task" }?.schemeSpecificPart ?: return
        if (!AgentController.owns(task)) return
        when (intent.action) {
            STOP -> AgentController.cancel(context)
            DISMISS -> dismissedTask = task
        }
    }
    companion object {
        internal const val STOP = "com.Bilibili_Innocent_Lab.xposedmodule.agent.STOP"
        internal const val DISMISS = "com.Bilibili_Innocent_Lab.xposedmodule.agent.DISMISS"
        @Volatile private var dismissedTask: String? = null
        internal fun dismissed(task: String): Boolean = dismissedTask == task
    }
}
