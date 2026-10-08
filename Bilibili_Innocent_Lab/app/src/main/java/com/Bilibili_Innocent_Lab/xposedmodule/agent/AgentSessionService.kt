package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/** 用户启动的短任务：宿主前台任务绑定保持模块生命周期，既不常驻也不自动恢复目标。 */
class AgentSessionService : Service() {
    private var ownerTaskId: String? = null
    override fun onCreate() {
        super.onCreate()
        ownerTaskId = AgentController.currentTaskId()
    }
    private val endpoint = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != AgentWire.SERVICE_KEEP_ALIVE || data.dataSize() > 4096) return false
            return runCatching {
                @Suppress("DEPRECATION")
                val uid = packageManager.getApplicationInfo(AgentWire.TARGET_PACKAGE, 0).uid
                if (getCallingUid() != uid) return@runCatching false
                data.enforceInterface(AgentWire.SERVICE_DESCRIPTOR)
                if (!AgentController.owns(data.readString().orEmpty())) return@runCatching false
                stopSelf()
                true
            }.getOrDefault(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder = endpoint
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ownerTaskId = AgentController.currentTaskId()
        if (!AgentController.state.running) stopSelf(startId)
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        ownerTaskId?.takeIf(AgentController::owns)?.let {
            val accessibility = getSystemService(ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            AgentController.cancel(this, if (accessibility?.isEnabled != false) "accessibility_control_unverified" else "service_stopped")
        }
        super.onDestroy()
    }
}
