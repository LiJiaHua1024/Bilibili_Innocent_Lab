package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.MotionEvent
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostTouchGlowBinding

/** 在宿主分发给子控件之前观察触摸，不消费事件，也不替换官方监听器。 */
internal class HostTouchGlowFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = "host_touch_glow"

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        return runCatching {
            environment.registrar.exact("$id.dispatch", ViewGroup::class.java, "dispatchTouchEvent", MotionEvent::class.java) {
                before {
                    val host = instance as? ViewGroup ?: return@before
                    val event = argOrNull(0) as? MotionEvent ?: return@before
                    HostTouchGlowBinding.observeTouch(host, event)
                }
            }
            FeatureInstallResult.Installed(1)
        }.getOrElse {
            environment.logError("${id}_error", "[BIL] 宿主独立触控光晕安装失败: $it")
            FeatureInstallResult.Skipped("registration-failed")
        }
    }
}
