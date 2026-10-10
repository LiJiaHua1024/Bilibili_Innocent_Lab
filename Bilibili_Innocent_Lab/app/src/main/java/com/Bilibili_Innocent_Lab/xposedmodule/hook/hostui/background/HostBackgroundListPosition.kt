package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method

/** 只缓存成员，不持有列表或 LayoutManager；布局中的临时边界不能当作回到顶部。 */
internal class HostBackgroundListPosition(type: Class<*>) {
    private val computingLayout = KavaMemberLookup.inheritedMethodOrNull(type, "isComputingLayout")
    private val pendingUpdates = KavaMemberLookup.inheritedMethodOrNull(type, "hasPendingAdapterUpdates")
    private val childPosition = KavaMemberLookup.inheritedMethodOrNull(type, "getChildAdapterPosition", View::class.java)
    private val getManager = KavaMemberLookup.inheritedMethodOrNull(type, "getLayoutManager")
    private var managerType: Class<*>? = null
    private var decoratedTop: Method? = null

    fun isAtTop(list: ViewGroup): Boolean {
        if (list.childCount == 0 || list.isLayoutRequested || list.canScrollVertically(-1)) return false
        return runCatching {
            if (computingLayout?.invoke(list) != false || pendingUpdates?.invoke(list) != false) return@runCatching false
            val position = childPosition ?: return@runCatching false
            val manager = getManager?.invoke(list) ?: return@runCatching false
            if (managerType != manager.javaClass) {
                managerType = manager.javaClass
                decoratedTop = KavaMemberLookup.inheritedMethodOrNull(manager.javaClass, "getDecoratedTop", View::class.java)
            }
            val top = decoratedTop ?: return@runCatching false
            for (index in 0 until list.childCount) {
                val child = list.getChildAt(index)
                if (position.invoke(list, child) != 0) continue
                return@runCatching (top.invoke(manager, child) as? Int ?: return@runCatching false) >= list.paddingTop
            }
            false
        }.getOrDefault(false)
    }
}
