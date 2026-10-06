package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.concurrent.atomic.AtomicBoolean

internal class HostVideoCardStyleFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        val adapter = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$Adapter")
            ?: return FeatureInstallResult.Skipped("missing-recycler-adapter")
        val holder = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$ViewHolder")
            ?: return FeatureInstallResult.Skipped("missing-recycler-holder")
        val itemView = KavaMemberLookup.fieldOrNull(holder, "itemView", includeSuperclasses = true)
            ?: return FeatureInstallResult.Skipped("missing-item-view")
        val firstHit = AtomicBoolean(false)
        val firstError = AtomicBoolean(false)
        val style = HostVideoCardStyle(onApplied = {
            if (firstHit.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                environment.logInfo("host_video_cards_applied", "[BIL] 视频卡片大圆角、柔影和留白已生效")
            }
        }, onError = {
            if (firstError.compareAndSet(false, true)) {
                environment.logError("host_video_cards_runtime_error", "[BIL] 视频卡片美化失败，保留宿主内容: $it")
            }
        })
        return runCatching {
            // final bindViewHolder 统一覆盖各适配器，使用宿主 ClassLoader 的类型，避免跨加载器强转。
            environment.registrar.exact("host_video_cards.bind", adapter, "bindViewHolder", holder, Int::class.javaPrimitiveType!!) {
                after {
                    if (hasThrowable) return@after
                    val bound = argOrNull(0) ?: return@after
                    val root = itemView.get(bound) as? View ?: return@after
                    style.bind(root)
                }
            }
            environment.logInfo("host_video_cards_installed", "[BIL] 视频卡片美化安装成功")
            FeatureInstallResult.Installed(1)
        }.getOrElse {
            environment.logError("host_video_cards_error", "[BIL] 视频卡片美化安装失败: $it")
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    companion object { const val ID = "host_video_cards" }
}
