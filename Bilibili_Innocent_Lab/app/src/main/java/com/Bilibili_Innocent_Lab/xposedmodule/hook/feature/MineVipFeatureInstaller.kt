package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/** “我的”页净化：原生卡片与 V2/V3 Compose 卡片分别处理。 */
internal class MineVipFeatureInstaller(
    private val enabled: Boolean,
    private val keepSpace: Boolean = false,
    private val point: VersionAdapter.MineVipPoint?
) : FeatureInstaller {

    override val id: String = ID
    override val capabilityIds: List<String> get() = buildList {
        if (enabled) add("mine_vip_hidden")
        if (enabled && keepSpace) add("mine_vip_space_kept")
    }

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val composeExpected = environment.classLoader?.let {
            KavaMemberLookup.hasClass(it, MineVipComposeHooks.OWNER)
        } == true
        val compose = composeExpected && MineVipComposeHooks.install(environment, keepSpace)
        val legacy = installLegacy(environment)
        val legacyReady = legacy is FeatureInstallResult.Installed
        val installed = (if (legacyReady) 1 else 0) + (if (compose) 1 else 0)
        val expected = 1 + if (composeExpected) 1 else 0
        capabilityIds.forEach { environment.reportCapabilityCoverage(it, true, installed, expected) }
        environment.reportStatus(CHANNEL_STATUS, "installed:view=$legacyReady,compose=$compose")
        return if (installed > 0) FeatureInstallResult.Installed(installed, installed == expected)
            else FeatureInstallResult.Skipped("missing-vip-boundaries")
    }

    private fun installLegacy(environment: HookEnvironment): FeatureInstallResult {
        val adapted = point ?: return missing(environment, "missing-adapter-point")
        val managerFieldName = adapted.onResume.viewField
            ?: return missing(environment, "missing-manager-field-name")

        // 安装期一次性解析完整成员链；回调只执行已缓存 Member，不重复扫描宿主结构。
        val managerField = environment.hookPoints.resolveField(
            "mine.vip.manager_field",
            adapted.onResume.className,
            managerFieldName
        ) ?: return missing(environment, "missing-manager-field")
        val bindingField = environment.hookPoints.resolveField(
            "mine.vip.binding_field",
            managerField.type,
            adapted.bindingField
        ) ?: return missing(environment, "missing-binding-field")
        val rootGetter = environment.hookPoints.resolveAdapted(
            "mine.vip.root_getter",
            adapted.rootGetter.className,
            adapted.rootGetter.methodName,
            adapted.rootGetter.paramClassNames
        ) ?: return missing(environment, "missing-root-getter")

        val visibility = MineVipVisibility(environment, keepSpace)
        return runCatching {
            environment.registrar.adapted("mine.vip.on_resume", adapted.onResume) {
                after {
                    if (hasThrowable) return@after
                    runCatching {
                        val fragment = instance
                        val manager = managerField.get(fragment) ?: return@runCatching
                        val binding = bindingField.get(manager) ?: return@runCatching
                        val root = rootGetter.invoke(binding) as? View ?: return@runCatching
                        visibility.hide(root)
                    }.onFailure { throwable ->
                        environment.logError(
                            "mine_vip_hide_err",
                            "[BIL] 隐藏“我的”页会员卡片失败: $throwable"
                        )
                    }
                }
            }
            environment.reportStatus(CHANNEL_STATUS, "success")
            environment.logInfo(
                "mine_vip_ok",
                "[BIL] “我的”页会员卡片净化已安装，keepSpace=$keepSpace"
            )
            FeatureInstallResult.Installed()
        }.getOrElse { throwable ->
            environment.reportStatus(CHANNEL_STATUS, "failed:${throwable.javaClass.simpleName}")
            environment.logError(
                "mine_vip_install_err",
                "[BIL] “我的”页会员卡片净化 Hook 注册失败: $throwable"
            )
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("mine_vip_missing", "[BIL] “我的”页会员卡片适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "mine_vip_purify"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "mine_vip_status"
    }
}
