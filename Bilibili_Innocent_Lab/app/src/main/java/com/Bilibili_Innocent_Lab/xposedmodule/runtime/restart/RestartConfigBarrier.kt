package com.Bilibili_Innocent_Lab.xposedmodule.runtime.restart

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptWire
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot.NoRootSupportController
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationAuthorityStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigStore
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigPublishResult

/** 保存通道由当前配置环境决定，与 Root / Shizuku / 手动执行方式独立。 */
internal object RestartConfigBarrier {
    private val worker = HostReceiptWire.executor("bil-restart-save")
    private val main = Handler(Looper.getMainLooper())
    fun flush(context: Context, preferences: SharedPreferences, noRoot: Boolean, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        if (noRoot) {
            NoRootSupportController.flushBeforeRestart(app, preferences) { result ->
                main.post { done(result == NoRootSupportController.FlushResult.SUCCESS) }
            }
        } else {
            val queued = runCatching { worker.execute {
                @Suppress("UseKtx") // 同步 commit 的布尔结果是保存屏障，不能丢弃。
                val persisted = runCatching {
                    preferences.edit().commit() && PublicationAuthorityStore.current(app)?.let { captured ->
                        captured.decision.isAuthorized && RemoteHookConfigStore.publish(app, captured.decision) is RemoteHookConfigPublishResult.Success
                    } == true
                }.getOrDefault(false)
                main.post { done(persisted) }
            } }.isSuccess
            if (!queued) main.post { done(false) }
        }
    }
}
