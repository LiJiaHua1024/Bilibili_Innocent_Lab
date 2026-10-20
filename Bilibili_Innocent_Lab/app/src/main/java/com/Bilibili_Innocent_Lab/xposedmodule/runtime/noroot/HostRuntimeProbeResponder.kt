package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion

/**
 * 宿主主进程侧的存活探测响应器（B12）。
 *
 * 只在主进程注册一个动态接收器：没有 manifest 入口，宿主没运行时探测广播会被直接丢弃，
 * 不会把哔哩哔哩拉起来。回执只携带 nonce、宿主身份证明与「当前这轮是否装上 Hook」，
 * 不读写任何配置或用户数据。
 */
internal object HostRuntimeProbeResponder {
    private val lock = Any()

    @Volatile
    private var registered = false

    /** 由 HookEntry 在安装链成功后置位；回执据此回答“活着且已装 Hook”。 */
    @Volatile
    var hooksInstalled = false

    fun ensureRegistered(
        context: Context,
        logError: (String) -> Unit = {}
    ): Boolean {
        val application = context.applicationContext ?: context
        synchronized(lock) {
            if (registered) return true
            return runCatching {
                ContextCompat.registerReceiver(
                    application,
                    probeReceiver(),
                    IntentFilter(HostRuntimeProbeContract.ACTION_PROBE),
                    HostRuntimeProbeContract.PERMISSION_PROBE,
                    null,
                    ContextCompat.RECEIVER_EXPORTED
                )
            }.onSuccess {
                registered = true
            }.onFailure { throwable ->
                logError("[BIL] 模块存活探测接收器注册失败: $throwable")
            }.isSuccess
        }
    }

    private fun probeReceiver() = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != HostRuntimeProbeContract.ACTION_PROBE) return
            if (AndroidVersion.isAtLeast(AndroidVersion.U) &&
                sentFromPackage != HostRuntimeProbeContract.MODULE_PACKAGE
            ) return
            val nonce = intent.getStringExtra(HostRuntimeProbeContract.EXTRA_NONCE).orEmpty()
            if (!HostRuntimeProbeContract.isValidNonce(nonce)) return
            val appContext = context.applicationContext ?: context
            val hostProof = hostProof(appContext) ?: return
            val reply = Intent(HostRuntimeProbeContract.ACTION_REPLY).apply {
                setPackage(HostRuntimeProbeContract.MODULE_PACKAGE)
                putExtra(HostRuntimeProbeContract.EXTRA_NONCE, nonce)
                putExtra(HostRuntimeProbeContract.EXTRA_HOOKS_INSTALLED, hooksInstalled)
                putExtra(HostRuntimeProbeContract.EXTRA_HOST_PROOF, hostProof)
            }
            runCatching { appContext.sendBroadcast(reply) }
        }
    }

    /** 身份证明只要求 `creatorPackage`，宿主无法被要求可见模块包；创建者 UID 由系统写入。 */
    private fun hostProof(context: Context): PendingIntent? = runCatching {
        PendingIntent.getBroadcast(
            context,
            PROOF_REQUEST_CODE,
            Intent(HostRuntimeProbeContract.ACTION_REPLY)
                .setPackage(HostRuntimeProbeContract.MODULE_PACKAGE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }.getOrNull()

    private const val PROOF_REQUEST_CODE = 0x42494D
}
