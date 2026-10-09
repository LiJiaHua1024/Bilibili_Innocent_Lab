package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardGridAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardHostAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyleSpec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.concurrent.atomic.AtomicBoolean

internal class HostVideoCardStyleFeatureInstaller(
    private val enabled: Boolean,
    private val radiusDp: Int = HostVideoCardStyleSpec.DEFAULT_RADIUS,
    private val backgroundConfig: HostBackgroundConfig = HostBackgroundConfig(),
    override val id: String = ID
) : FeatureInstaller {
    private var backgroundEnvironment: HookEnvironment? = null

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled && !backgroundConfig.enabled) return FeatureInstallResult.Skipped("disabled")
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
        val grid = if (enabled) HostVideoCardGridAccess.resolve(loader) else null
        val styleHost = HostVideoCardHostAccess(loader)
        val background = HostBackgroundController(backgroundConfig.normalized(), onError = {
            (backgroundEnvironment ?: environment).logError("host_background_unavailable", "[BIL] 宿主背景加载失败，使用柔光预设: $it")
        }, onApplied = {
            val backgroundEnv = backgroundEnvironment ?: environment
            backgroundEnv.reportRuntimeEvidence(BACKGROUND_ID, FeatureRuntimeStage.APPLIED)
            backgroundEnv.logInfo("${BACKGROUND_ID}_applied", "[BIL] 视频列表背景已生效")
        })
        val style = HostVideoCardStyle(grid = grid, host = styleHost, decorateCards = enabled, background = background,
            radiusDp = HostVideoCardStyleSpec.normalizeRadius(radiusDp), onApplied = {
            if (enabled && firstHit.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(id, FeatureRuntimeStage.APPLIED)
                environment.logInfo("${id}_applied", if (enabled) "[BIL] 视频卡片大圆角、柔影和留白已生效" else "[BIL] 视频列表背景已生效")
            }
        }, onError = {
            if (firstError.compareAndSet(false, true)) {
                environment.logError("${id}_runtime_error", "[BIL] 视频列表美化失败，保留宿主内容: $it")
            }
        })
        return runCatching {
            if (grid != null) {
                // assignSpans 已完成，直接参与宿主当前测量，避免布局后再重测整列表。
                val measure = grid.measureChild
                environment.registrar.exact("$id.measure", measure.declaringClass,
                    measure.name, *measure.parameterTypes) {
                    before {
                        val manager = instance ?: return@before
                        val root = argOrNull(0) as? View ?: return@before
                        style.prepareMeasurement(manager, root)
                    }
                }
            }
            // final bindViewHolder 统一覆盖各适配器，使用宿主 ClassLoader 的类型，避免跨加载器强转。
            environment.registrar.exact("$id.bind", adapter, "bindViewHolder", holder, Int::class.javaPrimitiveType!!) {
                after {
                    if (hasThrowable) return@after
                    val bound = argOrNull(0) ?: return@after
                    val root = itemView.get(bound) as? View ?: return@after
                    style.bind(root, feedback = styleHost.isDislikeHolder(bound))
                }
            }
            environment.logInfo("${id}_installed", "[BIL] 视频列表美化安装成功（测量前留白=${grid != null}）")
            FeatureInstallResult.Installed(if (grid != null) 2 else 1, complete = !enabled || grid != null)
        }.getOrElse {
            environment.logError("${id}_error", "[BIL] 视频列表美化安装失败: $it")
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    companion object {
        const val ID = "host_video_cards"
        const val BACKGROUND_ID = "host_background"

        /** 两项诊断仍独立，实际 bind Hook、卡片识别、主题读取和列表监听只安装一套。 */
        fun shared(enabled: Boolean, radiusDp: Int, backgroundConfig: HostBackgroundConfig): List<FeatureInstaller> {
            val delegate = HostVideoCardStyleFeatureInstaller(enabled, radiusDp, backgroundConfig,
                id = if (enabled) ID else BACKGROUND_ID)
            var result: FeatureInstallResult? = null
            fun install(environment: HookEnvironment): FeatureInstallResult = result ?: delegate.install(environment).also { result = it }
            return listOf(
                FunctionalFeatureInstaller(ID) { environment ->
                    if (enabled) install(environment) else FeatureInstallResult.Skipped("disabled")
                },
                FunctionalFeatureInstaller(BACKGROUND_ID) { environment ->
                    delegate.backgroundEnvironment = environment
                    if (!backgroundConfig.enabled) FeatureInstallResult.Skipped("disabled")
                    else when (val shared = install(environment)) {
                        // 背景覆盖同一个 bind Hook；不依赖卡片的 GridLayoutManager 测量点。
                        is FeatureInstallResult.Installed -> FeatureInstallResult.Installed(1)
                        is FeatureInstallResult.Skipped -> if (enabled && shared.reason == "registration-failed") {
                            // 卡片测量点失败时，背景仍可独立使用 bind；保持原有故障隔离。
                            HostVideoCardStyleFeatureInstaller(false, radiusDp, backgroundConfig, BACKGROUND_ID).install(environment)
                        } else shared
                        else -> shared
                    }
                }
            )
        }
    }
}
