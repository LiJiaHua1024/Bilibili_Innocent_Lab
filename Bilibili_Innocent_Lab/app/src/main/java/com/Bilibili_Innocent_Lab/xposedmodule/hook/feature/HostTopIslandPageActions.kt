package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/** 只访问当前可见的分页；不能刷新 ViewPager 预加载的邻页。 */
internal class HostTopIslandPageActions(private val root: ViewGroup, private val pager: ViewGroup?) {
    private data class Content(val list: View?, val refresh: View?)
    data class State(val atTop: Boolean, val refreshing: Boolean)
    enum class Action { TOP, REFRESH, BUSY, UNAVAILABLE }
    private val appBar by lazy { findAppBar(root) }

    /** 融合带与收岛操作使用同一个当前页，避免预加载邻页的滚动位置影响顶部。 */
    fun visibleList(): View? = content().list

    fun state(): State {
        val content = content()
        return State(isAtTop(content), content.refresh?.let { HostTopIslandRefreshAccess.isRefreshing(it) } == true)
    }

    private fun isAtTop(content: Content) =
        content.list?.canScrollVertically(-1) != true && appBar?.top?.let { it < 0 } != true

    fun click(): Action =
        runCatching {
            val content = content()
            if (content.refresh?.let { HostTopIslandRefreshAccess.isRefreshing(it) } == true) return@runCatching Action.BUSY
            if (!isAtTop(content)) {
                val list = content.list
                if (list != null) {
                    val smooth = KavaMemberLookup.inheritedMethodOrNull(list.javaClass,
                        "smoothScrollToPosition", Int::class.javaPrimitiveType!!)
                    if (smooth != null) smooth.invoke(list, 0)
                    else if (list is ScrollView) list.smoothScrollTo(0, 0)
                }
                appBar?.let {
                    KavaMemberLookup.inheritedMethodOrNull(it.javaClass, "setExpanded",
                        Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)?.invoke(it, true, true)
                }
                ModernHookLog.info("[BIL] 顶栏灵动岛: scroll-to-top ${list?.javaClass?.name}")
                Action.TOP
            } else {
                val refresh = content.refresh
                val started = refresh?.let { it.isEnabled && HostTopIslandRefreshAccess.refresh(it) } == true
                ModernHookLog.info("[BIL] 顶栏灵动岛: refresh=$started ${refresh?.javaClass?.name}")
                if (started) Action.REFRESH else Action.UNAVAILABLE
            }
        }.onFailure { ModernHookLog.info("[BIL] 顶栏灵动岛页面操作失败: $it") }.getOrDefault(Action.UNAVAILABLE)

    private fun content(): Content {
        var refresh: View? = null
        fun visit(view: View): View? {
            if (!view.isShown) return null
            if (hasType(view, "SwipeRefreshLayout")) refresh = view
            if (hasType(view, "RecyclerView") || view is AbsListView || view is ScrollView) return view
            if (view !is ViewGroup) return null
            if (hasType(view, "ViewPager") || hasType(view, "ViewPager2")) {
                return visiblePage(view)?.let { visit(it) }
            }
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.getGlobalVisibleRect(Rect())) visit(child)?.let { return it }
            }
            return null
        }
        val list = visit(pager ?: root)
        // 找到列表后，从祖先确认刷新容器，避免其他可见控件覆盖候选。
        var ancestor = list?.parent as? View
        while (ancestor != null && ancestor !== root) {
            if (hasType(ancestor, "SwipeRefreshLayout")) { refresh = ancestor; break }
            ancestor = ancestor.parent as? View
        }
        return Content(list, refresh)
    }

    private fun visiblePage(view: ViewGroup): View? {
        val viewport = Rect()
        if (!view.getGlobalVisibleRect(viewport)) return null
        val pages = (0 until view.childCount).map { view.getChildAt(it) }.filter { child ->
            val lp = child.layoutParams
            KavaMemberLookup.fieldOrNull(lp.javaClass, "isDecor", true)?.get(lp) != true
        }
        val current = KavaMemberLookup.inheritedMethodOrNull(view.javaClass, "getCurrentItem")?.invoke(view)
        if (current is Int) pages.firstOrNull { child ->
            val lp = child.layoutParams
            KavaMemberLookup.fieldOrNull(lp.javaClass, "position", true)?.get(lp) == current &&
                child.isShown && child.getGlobalVisibleRect(Rect())
        }?.let { return it }
        return pages.filter { it.isShown && it.getGlobalVisibleRect(Rect()) }.maxByOrNull { child ->
            val bounds = Rect()
            if (child.isShown && child.getGlobalVisibleRect(bounds) && bounds.intersect(viewport))
                bounds.width().toLong() * bounds.height() else 0L
        }
    }

    private fun findAppBar(view: View): View? {
        if (hasType(view, "AppBarLayout")) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findAppBar(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun hasType(view: View, name: String) =
        generateSequence(view.javaClass as Class<*>) { it.superclass }.any { it.simpleName == name }
}
