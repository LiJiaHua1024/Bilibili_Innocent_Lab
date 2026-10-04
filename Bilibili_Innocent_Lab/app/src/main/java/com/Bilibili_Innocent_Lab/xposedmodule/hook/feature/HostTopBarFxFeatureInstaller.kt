package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宿主首页顶栏视觉增强安装器。
 * 负责挂钩 BaseMainFrameFragment.onViewCreated，向哔哩哔哩首页顶栏注入 Liquid Glass 悬浮胶囊与触控柔光，
 * 并保留原版下划线指示器。
 */
internal class HostTopBarFxFeatureInstaller(
    private val liquidGlass: Boolean,
    private val touchGlow: Boolean,
    private val points: VersionAdapter.HomeTopBarPoints?
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        if (!liquidGlass && !touchGlow) {
            return FeatureInstallResult.Skipped("disabled")
        }
        val viewPoint = points?.baseOnViewCreated ?: run {
            environment.reportStatus(CHANNEL_STATUS, "missing:missing-view-created-point")
            environment.logInfo("host_top_bar_fx_missing", "[BIL] 宿主顶栏视觉增强跳过: missing-view-created-point")
            return FeatureInstallResult.Skipped("missing-view-created-point")
        }

        val firstHit = AtomicBoolean(false)
        val config = HostTopBarFxConfig(
            liquidGlass = liquidGlass,
            touchGlow = touchGlow
        )

        return runCatching {
            environment.registrar.adapted("host_top_bar_fx.view_created", viewPoint) {
                after {
                    if (hasThrowable) return@after
                    val root = argOrNull(0) as? ViewGroup ?: return@after
                    HostTopBarFxController.attach(root, instance, config, viewPoint.viewField)
                    if (firstHit.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "host_top_bar_fx_hit",
                            "[BIL] 宿主顶栏视觉增强生效(liquidGlass=$liquidGlass, touchGlow=$touchGlow)"
                        )
                    }
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "success:active")
            environment.logInfo(
                "host_top_bar_fx_ok",
                "[BIL] 宿主顶栏视觉增强安装成功(liquidGlass=$liquidGlass, touchGlow=$touchGlow)"
            )
            FeatureInstallResult.Installed(1)
        }.getOrElse { throwable ->
            environment.logError("host_top_bar_fx_error", "[BIL] 宿主顶栏视觉增强失败: $throwable")
            environment.reportStatus(CHANNEL_STATUS, "missing:registration-failed")
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    companion object {
        const val ID = "host_top_bar_fx"
        const val CHANNEL_STATUS = "host_top_bar_fx.status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
