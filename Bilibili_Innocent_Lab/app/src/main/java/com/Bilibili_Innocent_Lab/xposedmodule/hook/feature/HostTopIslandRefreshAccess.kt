package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 调用下拉刷新自身的带通知入口，动画结束后由宿主通知刷新监听器。 */
internal object HostTopIslandRefreshAccess {
    fun notifyMethod(owner: Class<*>): Method? {
        val bool = Boolean::class.javaPrimitiveType!!
        KavaMemberLookup.inheritedMethodOrNull(owner, "setRefreshing", bool, bool)?.let { return it }
        // 哔哩哔哩的 SwipeRefreshLayout 副本混淆了私有入口；按已核对的唯一签名解析。
        for (base in generateSequence(owner) { it.superclass }) {
            val methods = KavaMemberLookup.declaredMethods(base, makeAccessible = true)
            if (methods.none { it.name == "setOnRefreshListener" }) continue
            val candidates = methods.filter {
                Modifier.isPrivate(it.modifiers) && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(bool, bool))
            }
            if (candidates.size > 1) return null
            candidates.singleOrNull()?.let { return it }
        }
        return null
    }

    fun refresh(target: Any): Boolean {
        val bool = KavaMemberLookup.inheritedMethodOrNull(target.javaClass, "isRefreshing") ?: return false
        if (bool.invoke(target) == true) return false
        val method = notifyMethod(target.javaClass) ?: return false
        method.invoke(target, true, true)
        return true
    }

    fun isRefreshing(target: Any): Boolean =
        KavaMemberLookup.inheritedMethodOrNull(target.javaClass, "isRefreshing")?.invoke(target) == true
}
