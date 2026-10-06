package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.WeakHashMap

internal object HostVideoCardStyleSpec {
    fun coverRadius(width: Int, height: Int): Float = minOf(width, height).coerceAtLeast(0) * 0.22f

    // 在宿主已有的 ItemDecoration 上增加留白，不替换其分区/全宽卡片装饰。
    fun extraHorizontalDp(spanIndex: Int): Pair<Int, Int> =
        if (spanIndex == 0) 14 to 7 else 7 to 14
}

/** 只处理宿主 RecyclerView 中有视频封面和标题的条目；不遍历 Activity 或播放器。 */
internal class HostVideoCardStyle(private val onApplied: () -> Unit, private val onError: (Throwable) -> Unit) {
    private data class State(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        var decorated: Boolean = false,
        var topRadius: Float = 0f,
        var bottomRadius: Float = 0f,
        val surface: GradientDrawable = GradientDrawable()
    )

    private val states = WeakHashMap<View, State>()
    private val pendingLayouts = WeakHashMap<ViewGroup, Boolean>()
    private val coverNames = setOf("cover_layout", "cover", "video_cover", "iv_cover", "cover_image", "image_cover", "cover_container", "thumbnail", "pic")
    private val titleNames = setOf("title", "title_layout", "video_title", "tv_title")
    private val coverOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height,
                HostVideoCardStyleSpec.coverRadius(view.width, view.height))
        }
    }
    private val cardOutline = object : ViewOutlineProvider() {
        private val path = Path()
        private val bounds = RectF()
        override fun getOutline(view: View, outline: Outline) {
            val state = states[view] ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                bounds.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                path.rewind()
                path.addRoundRect(bounds, cardRadii(state.topRadius, state.bottomRadius), Path.Direction.CW)
                outline.setPath(path)
            } else {
                // 旧系统仅能裁切统一圆角；背景顶部仍匹配封面，底部沿用原生裁切。
                outline.setRoundRect(0, 0, view.width, view.height, state.bottomRadius)
            }
        }
    }
    private val layoutListener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
        safelyApply(view)
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = safelyApply(view)
        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    fun bind(root: View) {
        if (root !is ViewGroup) return
        if (root !in states) {
            // 评论、导航等列表不挂视觉监听器；绑定期已生成封面容器，即使尚未测量。
            if (!hasCover(root, 0)) return
            val margins = root.layoutParams as? ViewGroup.MarginLayoutParams
            states[root] = State(margins?.leftMargin ?: 0, margins?.topMargin ?: 0,
                margins?.rightMargin ?: 0, margins?.bottomMargin ?: 0)
            root.addOnLayoutChangeListener(layoutListener)
            root.addOnAttachStateChangeListener(attachListener)
        }
        safelyApply(root)
    }

    private fun safelyApply(root: View) {
        // View 的生命周期回调不经过 Hook 框架的异常隔离。
        runCatching { apply(root as? ViewGroup ?: return) }.onFailure(onError)
    }

    private fun apply(root: ViewGroup) {
        val parent = root.parent as? ViewGroup ?: return
        if (!isRecycler(parent.javaClass)) return
        val covers = ArrayList<View>()
        var hasTitle = false
        fun visit(view: View, depth: Int) {
            if (depth > 10) return
            if (depth > 0 && isRecycler(view.javaClass)) return
            val name = resourceName(view)
            if (name in titleNames && (view is TextView || view is ViewGroup)) hasTitle = true
            if (name in coverNames && (view is ImageView || view is ViewGroup)) {
                covers += view
                return // 容器负责裁切封面内的图片、渐变和角标，避免重复裁切。
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
        }
        visit(root, 0)
        if (!hasTitle || covers.isEmpty()) return
        // 不改变 Story 竖屏播放器或全屏媒体容器。
        val visibleCovers = covers.filter { it.width > 0 && it.height > 0 && it.height < parent.height * 0.7f }
        if (visibleCovers.isEmpty()) return
        for (cover in visibleCovers) {
            cover.outlineProvider = coverOutline
            cover.clipToOutline = true
            cover.invalidateOutline()
        }

        val state = states[root] ?: return
        val density = root.resources.displayMetrics.density
        val cover = visibleCovers.singleOrNull()
        val topRadius = if (cover != null && cover.left == 0 && cover.top == 0 && cover.width == root.width)
            HostVideoCardStyleSpec.coverRadius(cover.width, cover.height) else 20f * density
        val bottomRadius = 20f * density
        if (!state.decorated || state.topRadius != topRadius || state.bottomRadius != bottomRadius) {
            state.topRadius = topRadius
            state.bottomRadius = bottomRadius
            // 背景与阴影/裁切共用轮廓，封面顶部不会露出半径较小的白色卡片背景。
            state.surface.cornerRadii = cardRadii(topRadius, bottomRadius)
        }
        // 首页播放数/时长是封面的兄弟节点，稍向内收，避免文字落进大圆角的空白。
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (resourceName(child) != "cover_bottom_info_container") continue
            val infoParams = child.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
            val inset = (12f * density).toInt()
            val bottomInset = (9f * density).toInt()
            if (infoParams.leftMargin != inset || infoParams.rightMargin != inset || infoParams.bottomMargin < bottomInset) {
                infoParams.leftMargin = inset
                infoParams.rightMargin = inset
                infoParams.bottomMargin = maxOf(infoParams.bottomMargin, bottomInset)
                child.layoutParams = infoParams
            }
            // 略收紧封面信息字号，为缩窄后的双列保留播放数、弹幕数和时长。
            if (child is ViewGroup) for (index in 0 until child.childCount) {
                (child.getChildAt(index) as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
        }
        val manager = call(parent, "getLayoutManager")
        val spans = (manager?.let { call(it, "getSpanCount") } as? Int)
        val params = root.layoutParams as? ViewGroup.MarginLayoutParams
        val spanIndex = params?.let { call(it, "getSpanIndex") } as? Int
        val spanSize = params?.let { call(it, "getSpanSize") } as? Int
        val doubleColumn = spans == 2 && spanSize == 1 && spanIndex in 0..1
        if (params != null) {
            val extra = if (doubleColumn) HostVideoCardStyleSpec.extraHorizontalDp(spanIndex!!) else 0 to 0
            val left = state.left + (extra.first * density).toInt()
            val right = state.right + (extra.second * density).toInt()
            val top = state.top + if (doubleColumn) (5 * density).toInt() else 0
            val bottom = state.bottom + if (doubleColumn) (12 * density).toInt() else 0
            if (params.leftMargin != left || params.rightMargin != right ||
                params.topMargin != top || params.bottomMargin != bottom) {
                params.setMargins(left, top, right, bottom)
                root.layoutParams = params
                // RecyclerView 在滚动/布局中会吞掉子项的 requestLayout；退出本帧再重测，
                // 避免新回收出来的条目要等下一次滚动才获得留白。同一列表只排一个任务。
                if (pendingLayouts.put(parent, true) == null) parent.post {
                    pendingLayouts.remove(parent)
                    if (parent.isAttachedToWindow) parent.requestLayout()
                }
            }
        }

        if (!state.decorated) {
            val night = root.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            state.surface.setColor(if (night) Color.rgb(35, 35, 38) else Color.WHITE)
            // Android 原生阴影由 RenderThread 绘制，滚动时不创建模糊位图或软件图层。
            root.elevation = (if (Build.VERSION.SDK_INT >= 28) 8f else 3f) * density
            if (Build.VERSION.SDK_INT >= 28) {
                root.outlineAmbientShadowColor = Color.argb(18, 112, 112, 112)
                root.outlineSpotShadowColor = Color.argb(24, 112, 112, 112)
            }
            state.decorated = true
            onApplied()
        }
        if (root.background !== state.surface) root.background = state.surface
        if (root.outlineProvider !== cardOutline) root.outlineProvider = cardOutline
        root.clipToOutline = true
        // 给阴影留出绘制空间，列表仍按自身窗口裁切，不跨到顶栏或底栏。
        parent.clipChildren = false
        root.invalidateOutline()
    }

    private fun call(target: Any, name: String): Any? = runCatching {
        KavaMemberLookup.inheritedMethodOrNull(target.javaClass, name)?.invoke(target)
    }.getOrNull()

    private fun resourceName(view: View): String = if (view.id == View.NO_ID) "" else
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")

    private fun cardRadii(top: Float, bottom: Float) =
        floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)

    private fun hasCover(view: View, depth: Int): Boolean {
        if (depth > 10) return false
        if (depth > 0 && isRecycler(view.javaClass)) return false
        if (resourceName(view) in coverNames && (view is ImageView || view is ViewGroup)) return true
        return view is ViewGroup && (0 until view.childCount).any { hasCover(view.getChildAt(it), depth + 1) }
    }

    private fun isRecycler(type: Class<*>): Boolean =
        generateSequence(type) { it.superclass }.any { it.name == "androidx.recyclerview.widget.RecyclerView" }
}
