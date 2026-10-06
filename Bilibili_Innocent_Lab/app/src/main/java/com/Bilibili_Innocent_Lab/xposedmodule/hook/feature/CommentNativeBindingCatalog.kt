package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Method

/** 三个外观实验可在同一个 APK 中共存；只在安装时解析，不进入逐条绑定热路径。 */
internal object CommentNativeBindingCatalog {
    const val LEGACY = "com.bilibili.app.comment3.ui.holder.handle.CommentContentRichTextHandler"
    const val NEXT = "com.bilibili.app.comment3.ui.nextholder.handle.CommentNextContentRichTextHandler"
    const val EXP3 = "com.bilibili.app.comment3.ui.nextholderexp3.handle.CommentNextExperiment3ContentRichTextHandler"
    val mainHandlers = listOf(EXP3, NEXT, LEGACY)

    fun bindingMethods(owner: Class<*>): List<Method> {
        if (KavaMemberLookup.declaredFields(owner).none { it.type.name.endsWith(".CommentItem") }) {
            return emptyList()
        }
        return KavaMemberLookup.declaredMethods(owner).filter { method ->
            !method.isStatic && !method.isBridge && !method.isSynthetic &&
                method.returnType == Void.TYPE && method.parameterCount in 1..5 &&
                method.parameterTypes.any(::isBinding)
        }
    }

    private fun isBinding(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isArray || type.isInterface) return false
        return KavaMemberLookup.declaredFields(type).any { it.type isSubclassOf classOf<View>() } ||
            KavaMemberLookup.methods(type, includeSuperclasses = true).any {
                it.name == "getRoot" && it.parameterCount == 0 && it.returnType isSubclassOf classOf<View>()
            }
    }
}

/** 失败单位留在分母里；没有注册的方法不能由“类存在”升级为完整覆盖。 */
internal class CommentFamilyCoverage {
    private val outcomes = linkedMapOf<String, Int>()

    @Synchronized fun record(family: String, installed: Boolean) {
        outcomes[family] = maxOf(outcomes[family] ?: 0, if (installed) 2 else 0)
    }

    @Synchronized fun record(family: String, result: FeatureInstallResult) {
        val state = when (result) {
            is FeatureInstallResult.Installed -> if (result.complete) 2 else 1
            else -> 0
        }
        outcomes[family] = maxOf(outcomes[family] ?: 0, state)
    }

    @Synchronized fun installedCount(): Int = outcomes.values.count { it > 0 }
    @Synchronized fun isComplete(): Boolean = outcomes.isNotEmpty() && outcomes.values.all { it == 2 }
    @Synchronized fun describe(): String = outcomes.entries.joinToString(",") {
        "${it.key}=${when (it.value) { 2 -> "ok"; 1 -> "partial"; else -> "missing" }}"
    }
}
