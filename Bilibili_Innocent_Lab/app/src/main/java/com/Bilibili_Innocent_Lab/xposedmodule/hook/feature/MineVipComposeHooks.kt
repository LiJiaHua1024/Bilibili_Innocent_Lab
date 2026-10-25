package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostComposeHiddenLayout
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/** V2 and V3 share this native card facade. No account VIP identity or other VIP entry is changed. */
internal object MineVipComposeHooks {
    const val OWNER = "tv.danmaku.bili.ui.main2.mineV2.ui.MineVipModuleComposeKt"
    private const val COMPOSER = "androidx.compose.runtime.Composer"
    private const val MODIFIER = "androidx.compose.ui.Modifier"

    internal fun accepts(p: List<String>, static: Boolean, returns: String): Boolean = static && returns == "void" &&
        (p.size == 4 && p.take(3) == listOf("int", COMPOSER, MODIFIER) &&
            p[3].startsWith("tv.danmaku.bili.ui.main2.mineV2.ui.") ||
            p.size in 4..5 && p[0] == MODIFIER && p[1].startsWith("tv.danmaku.bili.ui.main2.mineV2.ui.") &&
                p[2] == COMPOSER && p.drop(3).all { it == "int" })

    fun locate(loader: ClassLoader): Method? = Lookup.classOrNull(loader, OWNER)?.let { owner ->
        Lookup.declaredMethods(owner, makeAccessible = true).filter {
            accepts(it.parameterTypes.map(Class<*>::getName), Modifier.isStatic(it.modifiers), it.returnType.name)
        }.singleOrNull()
    }

    fun install(environment: HookEnvironment, keepSpace: Boolean): Boolean {
        val loader = environment.classLoader ?: return false
        val method = locate(loader) ?: return false
        val observed = AtomicBoolean()
        val applied = AtomicBoolean()
        fun applied() {
            if (applied.compareAndSet(false, true)) {
                environment.reportRuntimeEvidence(MineVipFeatureInstaller.ID, FeatureRuntimeStage.APPLIED)
                environment.logInfo("mine_vip_compose_applied", "[BIL] 我的页会员卡片已隐藏（Compose，keepSpace=$keepSpace）")
            }
        }
        val layout = if (keepSpace) HostComposeHiddenLayout.resolve(loader, ::applied) ?: return false else null
        val modifierIndex = method.parameterTypes.indexOfFirst { it.name == MODIFIER }
        return runCatching {
            environment.registrar.exact("mine.vip.compose", method.declaringClass, method.name, *method.parameterTypes) {
                before {
                    if (observed.compareAndSet(false, true)) environment.reportRuntimeEvidence(
                        MineVipFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED)
                    if (layout == null) { result = null; applied() }
                    else {
                        args[modifierIndex] = layout.wrap(argOrNull(modifierIndex))
                        // R8 may move the changed mask to argument 0. The Modifier is still
                        // source parameter 0; it must be marked changed even during a restart.
                        val changedIndex = if (modifierIndex == 2) 0 else 3
                        val changed = argOrNull(changedIndex) as? Int ?: 0
                        args[changedIndex] = (changed and 0b1110.inv()) or 0b0100
                        // Older facades retain the default-argument mask and otherwise replace
                        // our supplied Modifier with Companion inside the native method.
                        if (method.parameterCount == 5) {
                            val defaults = argOrNull(4) as? Int ?: 0
                            args[4] = defaults and 1.inv()
                        }
                    }
                }
            }
        }.onFailure { environment.logError("mine_vip_compose_error", "[BIL] 我的页会员 Compose 边界注册失败: $it") }.isSuccess
    }
}
