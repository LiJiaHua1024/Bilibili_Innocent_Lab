package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宿主底栏视觉增强安装器。
 * 负责挂钩 TabHost 绑定点，向哔哩哔哩底栏注入 Liquid Glass 外壳与触控柔光。
 */
internal class HostBottomBarFxFeatureInstaller(
    private val liquidGlass: Boolean,
    private val touchGlow: Boolean,
    private val points: VersionAdapter.BottomBarPoints?,
    private val compact: Boolean = false,
    private val iconOnly: Boolean = false
) : FeatureInstaller {

    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        if (!liquidGlass && !touchGlow && !compact && !iconOnly) {
            return FeatureInstallResult.Skipped("disabled")
        }
        val adapted = points ?: run {
            environment.reportStatus(CHANNEL_STATUS, "missing:missing-bottom-bar-points")
            environment.logInfo("host_bottom_bar_fx_missing", "[BIL] 宿主底栏视觉增强跳过: missing-bottom-bar-points")
            return FeatureInstallResult.Skipped("missing-bottom-bar-points")
        }

        val firstHit = AtomicBoolean(false)
        val config = HostBottomBarFxConfig(
            liquidGlass = liquidGlass,
            touchGlow = touchGlow,
            compact = compact,
            iconOnly = iconOnly
        )

        return runCatching {
            environment.registrar.adapted("host_bottom_bar_fx.bind", adapted.bindTabMethod) {
                after {
                    val host = instance as? ViewGroup ?: return@after
                    HostBottomBarFxController.attach(host, config)
                    if (firstHit.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                        environment.logInfo(
                            "host_bottom_bar_fx_hit",
                            "[BIL] 宿主底栏视觉增强生效(liquidGlass=$liquidGlass, touchGlow=$touchGlow)"
                        )
                    }
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "success:active")
            environment.logInfo(
                "host_bottom_bar_fx_ok",
                "[BIL] 宿主底栏视觉增强安装成功(liquidGlass=$liquidGlass, touchGlow=$touchGlow)"
            )
            FeatureInstallResult.Installed(1)
        }.getOrElse { throwable ->
            environment.logError("host_bottom_bar_fx_error", "[BIL] 宿主底栏视觉增强失败: $throwable")
            environment.reportStatus(CHANNEL_STATUS, "missing:registration-failed")
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    companion object {
        const val ID = "host_bottom_bar_fx"
        const val CHANNEL_STATUS = "host_bottom_bar_fx.status"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
