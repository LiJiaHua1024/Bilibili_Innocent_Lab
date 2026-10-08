package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** 页面负责排布和复用，凝光负责全部表面；仅状态栏采样，不接管手势或播放器。 */
internal class FollowFeedController(
    private val host: FollowFeedHostAccess,
    private val compactHeaderSupported: Boolean,
    private val report: (String) -> Unit,
    private val error: (Throwable) -> Unit
) {
    private class RowState(val row: FollowFeedRow?) {
        val edits = FollowFeedViewEdits()
        var originalInsets: Rect? = null
        var appliedInsets: Rect? = null
        var cachedInsets: Rect? = null
        var palette: LumenPalette? = null
        var decorated = false
        var page = WeakReference<Page>(null)
        fun restoreInsets() {
            val cached = cachedInsets
            val original = originalInsets
            if (cached != null && original != null && cached == appliedInsets) cached.set(original)
            cachedInsets = null; originalInsets = null; appliedInsets = null
        }
    }
    private val rows = WeakHashMap<View, RowState>()
    // 页面自身的 attach listener 持有生命周期；全局索引不能反向强持有 Activity。
    private val pages = WeakHashMap<View, WeakReference<Page>>()
    private val rowAttach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) { safely { attachRow(view) } }
        override fun onViewDetachedFromWindow(view: View) {
            rows[view]?.let { it.edits.restore(); it.restoreInsets(); it.palette = null; it.page.clear() }
        }
    }

    fun attach(root: View) = safely {
        if (pages[root]?.get()?.closed == false) return@safely
        val page = Page(root)
        pages[root] = WeakReference(page)
        page.start()
    }

    fun close(root: View) { pages.remove(root)?.get()?.close() }
    fun pause(root: View) { pages[root]?.get()?.setLifecycleActive(false) }
    fun resume(root: View) = safely { attach(root); pages[root]?.get()?.setLifecycleActive(true) }
    fun headerPadding(root: View, requested: Int) = pages[root]?.get()?.headerPadding(requested) ?: requested

    fun bind(root: View, holder: Any, position: Int) = safely {
        // 只保留这个 View 当前的标量身份，宿主每次 bind 后重读；不能缓存旧模型。
        val row = host.row(holder, position)
        if (row == null && !host.handles(holder) && pageFor(root) == null) return@safely
        val previous = rows[root]
        previous?.edits?.restore()
        previous?.restoreInsets()
        rows[root] = RowState(row)
        if (previous == null) root.addOnAttachStateChangeListener(rowAttach)
        if (root.isAttachedToWindow) attachRow(root)
    }

    fun recycle(root: View) {
        rows.remove(root)?.let { it.edits.restore(); it.restoreInsets() }
        root.removeOnAttachStateChangeListener(rowAttach)
    }

    fun replacesModuleBackground(list: ViewGroup, root: View): Boolean {
        val state = rows[root] ?: return false
        val identity = state.row?.identity ?: return false
        return root.parent === list && state.decorated && state.page.get()?.hasCardSurface(list, identity) == true
    }

    fun childAttached(root: View) = safely {
        val page = pageFor(root) ?: return@safely
        if (rows[root] == null && page.text(root, "dy_name") != null &&
            page.find<View>(root, "avatar_container") != null) {
            rows[root] = RowState(null)
            root.addOnAttachStateChangeListener(rowAttach)
        }
        attachRow(root)
    }

    fun insets(root: View, rect: Rect) = safely {
        val state = rows[root] ?: return@safely
        val row = state.row ?: return@safely
        val list = root.parent as? ViewGroup ?: return@safely
        val page = pageFor(list) ?: return@safely
        if (!page.isFeedList(list)) return@safely
        if (state.appliedInsets == rect) return@safely // RecyclerView 的 mDecorInsets 是缓存对象。
        state.originalInsets = Rect(rect)
        state.cachedInsets = rect
        val outer = page.dp(FollowFeedStyle.OUTER_DP)
        rect.left += outer; rect.right += outer
        // 覆盖原来的末尾分隔带，不能在同一动态模块之间累计间距。
        if (row.last) rect.bottom = maxOf(rect.bottom, page.dp(FollowFeedStyle.GAP_DP))
        state.appliedInsets = Rect(rect)
    }

    private fun pageFor(view: View): Page? {
        var current: View? = view
        repeat(18) {
            val node = current ?: return null
            pages[node]?.get()?.takeUnless { it.closed }?.let { return it }
            current = node.parent as? View
        }
        return null
    }

    private fun attachRow(root: View) {
        val state = rows[root] ?: return
        val page = pageFor(root) ?: return
        state.page = WeakReference(page)
        val list = root.parent as? ViewGroup ?: return
        if (page.isFeedList(list)) page.list(list)
        styleRow(root, state, page)
        if (state.row == null && page.isFeedList(list)) {
            val frequent = page.find<View>(root, "dy_fl_video_uplist") ?: page.find<View>(root, "dy_fl_uplist")
            val horizontal = frequent?.let { page.group(it, "dy_list") }
            if (horizontal != null) for (i in 0 until horizontal.childCount) {
                val child = horizontal.getChildAt(i)
                val childState = rows.getOrPut(child) {
                    child.addOnAttachStateChangeListener(rowAttach)
                    RowState(null)
                }
                childState.page = WeakReference(page)
                styleRow(child, childState, page)
            }
        }
    }

    private fun styleRow(root: View, state: RowState, page: Page) {
        if (state.row != null && !state.decorated) return
        if (state.palette == page.palette) return
        state.edits.restore()
        state.palette = page.palette
        val palette = page.palette
        val list = root.parent as? ViewGroup ?: return
        if (page.isFeedList(list) && state.row != null) {
            state.edits.background(root, null)
            // 只清理宿主模块承托背景，不扫描/改写播放器、媒体、会员装饰和引用容器。
            for (name in arrayOf("dy_root", "dy_following_card_header", "dy_card_bottom")) {
                page.find<View>(root, name)?.takeIf { it.parent === root }?.let { state.edits.background(it, null) }
            }
            for (name in arrayOf("dy_text_time", "dy_text_ip", "dy_card_repost_text", "dy_card_comment_text", "dy_card_support_text")) {
                page.text(root, name)?.let {
                    // 点赞等业务强调色必须保留。
                    if (!it.isSelected && !it.isActivated && FollowFeedStyle.isNeutralText(it.currentTextColor))
                        state.edits.text(it, palette.textSecondary)
                }
            }
            page.text(root, "dy_video_title")?.let {
                state.edits.text(it, palette.textPrimary, FollowFeedStyle.VIDEO_TITLE_SP, maxLines = 2)
                state.edits.size(it, height = ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            page.text(root, "card_user_name")?.let {
                if (FollowFeedStyle.isNeutralText(it.currentTextColor)) state.edits.text(it, palette.textPrimary)
            }
            page.text(root, "dy_title")?.let { state.edits.text(it, palette.textPrimary) }
            page.text(root, "dy_text")?.let { state.edits.text(it, palette.textPrimary) }
        } else {
            // 横向最常访问项目只在目标页内识别；原横向 RecyclerView 保持不变。
            val nickname = page.text(root, "dy_name")
            val avatar = page.find<View>(root, "avatar_container")
            if (nickname != null && avatar != null && !page.isFeedList(list)) {
                state.edits.text(nickname, palette.textSecondary, singleLine = true)
                state.edits.size(nickname, height = ViewGroup.LayoutParams.WRAP_CONTENT)
                state.edits.size(avatar, page.dp(FollowFeedStyle.AVATAR_DP), page.dp(FollowFeedStyle.AVATAR_DP))
                val font = root.resources.configuration.fontScale.coerceIn(1f, 2f)
                state.edits.size(root, page.dp((FollowFeedStyle.FREQUENT_WIDTH_DP * font).roundToInt()),
                    page.dp(FollowFeedStyle.frequentHeight(font)))
            }
            for (name in arrayOf("dy_fl_video_uplist", "dy_fl_uplist")) page.find<View>(root, name)?.let {
                state.edits.background(root, null); state.edits.background(it, null)
                page.text(it, "dy_title")?.let { title -> state.edits.text(title, palette.textSecondary, FollowFeedStyle.FREQUENT_TITLE_SP) }
                // 全部页的更多入口独立使用 bg_card_selector；原默认白色不会随
                // 父容器透明而消失。只给原 selector 副本配色，不移除点击与按压反馈。
                page.find<View>(it, "dy_more_container")?.let { more ->
                    state.edits.backgroundTint(more, ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                        intArrayOf(palette.surfaceVariant, palette.background)
                    ))
                    page.text(more, "dy_card_more_text")?.let { label -> state.edits.text(label, palette.textSecondary) }
                }
                page.find<View>(it, "dy_list")?.let { horizontal ->
                    val font = root.resources.configuration.fontScale
                    state.edits.size(horizontal, height = page.dp(FollowFeedStyle.frequentListHeight(font)))
                }
            }
        }
    }

    private inline fun safely(block: () -> Unit) { runCatching(block).onFailure(error) }

    internal inner class Page(root: View) : AutoCloseable, View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val root = WeakReference(root)
        private val theme = HostChromeTheme(root.context)
        private var colors = theme.read()
        var palette = FollowFeedStyle.palette(colors)
            private set
        private val session = LumenSurfaceSession(root.context, palette)
        private val cardOptions = FollowFeedStyle.card()
        private val edits = FollowFeedViewEdits()
        private val lists = HashMap<ViewGroup, FeedList>()
        private val ids = HashMap<String, Int>()
        private var observer: ViewTreeObserver? = null
        private var selection: FollowFeedSelection? = null
        private var header: FollowFeedHeader? = null
        private var statusBar: FollowFeedStatusBar? = null
        private var headerAttempted = false
        private var themeReadAt = 0L
        private var paused = false
        private var lifecycleActive = true
        private var selectionAttempted = false
        var closed = false
            private set
        private var reportedDraw = false
        private var reportedCards = false
        private var reportedStatus = false

        fun dp(value: Int) = (value * (root.get()?.resources?.displayMetrics?.density ?: 1f)).roundToInt()
        fun <T : View> find(view: View, name: String): T? {
            val id = ids.getOrPut(name) { view.resources.getIdentifier(name, "id", view.context.packageName) }
            return if (id == 0) null else view.findViewById<T>(id)
        }
        fun text(view: View, name: String): TextView? = find<View>(view, name) as? TextView
        fun group(view: View, name: String): ViewGroup? = find<View>(view, name) as? ViewGroup
        fun isFeedList(view: ViewGroup): Boolean = view.id == ids.getOrPut("dy_list") {
            view.resources.getIdentifier("dy_list", "id", view.context.packageName)
        } && view.parent is FrameLayout && view.javaClass.name == "androidx.recyclerview.widget.RecyclerView"

        fun list(view: ViewGroup) { lists.getOrPut(view) { FeedList(view) } }
        fun hasCardSurface(list: ViewGroup, identity: Long) = !closed && !paused &&
            lists[list]?.hasSurface(identity) == true

        fun start() {
            val view = root.get() ?: return
            view.addOnAttachStateChangeListener(this)
            applyPageColors(view)
            // bind/attach 可能早于 Fragment 的 posted 接入。只在接入时扫描一次，之后靠
            // RecyclerView 的真实 bind/child-attach 事件补齐，不在滚动帧遍历页面树。
            fun discover(node: View) {
                if (node !is ViewGroup) return
                if (isFeedList(node)) {
                    list(node)
                    for (i in 0 until node.childCount) childAttached(node.getChildAt(i))
                    return
                }
                for (i in 0 until node.childCount) discover(node.getChildAt(i))
            }
            discover(view)
            observe(view)
        }

        private fun applyPageColors(view: View) {
            edits.restore()
            edits.background(view, ColorDrawable(palette.background))
            find<View>(view, "dy_app_bar")?.let { edits.background(it, ColorDrawable(palette.background)) }
            var ancestor: View? = view
            repeat(12) {
                val node = ancestor ?: return@repeat
                if (node.id == ids.getOrPut("following_home_container") {
                    node.resources.getIdentifier("following_home_container", "id", node.context.packageName)
                }) {
                    edits.background(node, ColorDrawable(palette.background))
                    text(node, "fo_title")?.let { edits.text(it, palette.textPrimary) }
                }
                ancestor = node.parent as? View
            }
        }

        private fun observe(view: View) {
            val tree = view.viewTreeObserver
            if (observer === tree) return
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            tree.addOnPreDrawListener(this); observer = tree
        }

        override fun onPreDraw(): Boolean {
            safely {
                val view = root.get() ?: return@safely
                if (!lifecycleActive || !view.isShown) { pause(); return@safely }
                if (paused) resume()
                val now = SystemClock.uptimeMillis()
                if (now - themeReadAt >= 250) {
                    themeReadAt = now
                    val next = theme.read()
                    if (next != colors) {
                        colors = next; palette = FollowFeedStyle.palette(next)
                        session.updatePalette(palette)
                        applyPageColors(view)
                        selection?.updatePalette(palette)
                        header?.updatePalette(palette)
                        statusBar?.updatePalette(palette)
                        lists.values.forEach { it.updatePalette() }
                        rows.forEach { (row, state) -> if (state.page.get() === this) styleRow(row, state, this) }
                    }
                }
                if (!selectionAttempted) group(view, "dy_tab_layout")?.takeIf {
                    (it.getChildAt(0) as? ViewGroup)?.childCount?.let { count -> count > 0 } == true
                }?.let {
                    selectionAttempted = true
                    selection = FollowFeedSelection.create(it, session, palette)
                }
                selection?.resume(); selection?.sync()
                if (compactHeaderSupported && !headerAttempted && selection != null) {
                    headerAttempted = true
                    header = FollowFeedHeader.create(view, requireNotNull(selection), palette)
                }
                header?.sync()
                if (header != null && statusBar == null && view is FrameLayout)
                    statusBar = FollowFeedStatusBar(view, session, palette)
                lists.values.forEach { it.update() }
                statusBar?.sync(lists.entries.firstOrNull { it.key.isShown && it.value.panelCount > 0 }?.key)
                statusBar?.diagnostics()?.takeIf { it.hasVisibleFrame && it.backend != LumenSurfaceBackend.STATIC }?.let {
                    if (!reportedStatus) {
                        reportedStatus = true
                        diagnostics("status-fusion-${it.backend}-${it.failure}")
                    }
                }
                if (!reportedDraw && session.diagnostics().firstVisibleDraws > 0) {
                    reportedDraw = true
                    diagnostics("visible")
                }
                if (!reportedCards && lists.values.any { it.panelCount > 0 } && session.diagnostics().staticDraws > 2) {
                    reportedCards = true
                    diagnostics("cards-visible")
                }
            }
            return true
        }

        fun pause() {
            if (closed || paused) return
            paused = true
            // onPause 可能发生在 UP 主筛选/空间页的入场动画中，此时底页仍会绘制。
            // 只停采样和释放绑定；原标题、发布父容器与占位等到真正销毁 View 再恢复。
            statusBar?.pause()
            selection?.pause(); lists.values.forEach { it.releasePanels() }
            session.pause()
            diagnostics("paused")
        }

        fun headerPadding(requested: Int) = header?.mapNativePadding(requested) ?: requested

        fun setLifecycleActive(active: Boolean) {
            lifecycleActive = active
            if (active) resume() else pause()
        }

        fun resume() {
            if (closed) return
            paused = false; session.resume()
            root.get()?.let(::observe)
        }

        private fun diagnostics(stage: String) {
            val d = session.diagnostics()
            report("[BIL] 关注页凝光 $stage: surfaces=${d.attachedSurfaces}, static=${d.staticDraws}, " +
                "gpu=${d.gpuDraws}, software=${d.softwareDraws}, recordings=${d.contentRecordings}, palette=${d.paletteGeneration}")
        }

        override fun onViewAttachedToWindow(v: View) = resume()
        override fun onViewDetachedFromWindow(v: View) { close(); pages.remove(v) }
        override fun close() {
            if (closed) return
            closed = true
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            root.get()?.removeOnAttachStateChangeListener(this)
            header?.close(); header = null
            statusBar?.close(); statusBar = null
            selection?.close(); selection = null
            lists.values.forEach { it.close() }; lists.clear()
            rows.forEach { (_, state) -> if (state.page.get() === this) {
                state.edits.restore(); state.restoreInsets(); state.palette = null; state.page.clear()
            } }
            edits.restore(); session.close(); diagnostics("closed")
        }

        /** 表面只是列表的背景兄弟层；不拦截触摸，也不绘制自定义 Drawable。 */
        private inner class FeedList(private val list: ViewGroup) : AutoCloseable {
            private val parent = list.parent as FrameLayout
            private val layer = object : FrameLayout(list.context) {
                override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
            }.apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                isClickable = false; isFocusable = false
                clipChildren = false
                clipToPadding = false
            }
            private val listEdits = FollowFeedViewEdits()
            private var viewport: FollowFeedViewport? = null
            private val originalPadding = list.paddingBottom
            private var appliedPadding = originalPadding
            private val panels = LinkedHashMap<Long, Panel>()
            private val location = IntArray(2)
            private var dock: WeakReference<View>? = null
            private var dockChecked = false
            private var generation = 0

            private inner class Panel : View.OnAttachStateChangeListener {
                val view = View(list.context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                var binding: LumenSurfaceBinding? = null
                var generation = -1
                var top = 0
                var bottom = 0
                var first: FollowFeedRow? = null
                var last: FollowFeedRow? = null
                var valid = true
                var lastChildIndex = -1
                init {
                    view.addOnAttachStateChangeListener(this)
                    layer.addView(view, FrameLayout.LayoutParams(0, 0))
                }
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) { binding?.close(); binding = null }
                fun close() {
                    binding?.close(); binding = null
                    view.removeOnAttachStateChangeListener(this)
                    layer.removeView(view)
                }
            }

            init {
                parent.addView(layer, parent.indexOfChild(list), FrameLayout.LayoutParams(-1, -1))
                updatePalette()
            }

            fun updatePalette() {
                listEdits.restore()
                listEdits.background(parent, ColorDrawable(palette.background))
                listEdits.background(list, null)
            }

            fun update() {
                if (!list.isShown || !list.isAttachedToWindow) { releasePanels(); return }
                if (header != null) {
                    if (viewport == null) viewport = root.get()?.let { FollowFeedViewport(list, it as ViewGroup) }
                    viewport?.sync(root.get()?.paddingTop ?: 0)
                }
                generation++
                var surfacesChanged = false
                for (i in 0 until list.childCount) {
                    val child = list.getChildAt(i)
                    val state = rows[child] ?: continue
                    val row = state.row ?: continue
                    val panel = panels[row.identity] ?: if (lists.values.sumOf { it.panelCount } < FollowFeedStyle.MAX_CARD_SURFACES) {
                        Panel().also { panels[row.identity] = it; surfacesChanged = true }
                    } else continue
                    val top = child.top + child.translationY.roundToInt() + list.top
                    val bottom = child.bottom + child.translationY.roundToInt() + list.top
                    if (panel.generation != generation) {
                        panel.generation = generation; panel.top = top; panel.bottom = bottom
                        panel.first = row; panel.last = row; panel.valid = true
                    } else {
                        // notifyItemInserted/Diff 会改变 adapter position，未必重新 bind 所有
                        // 可见 holder。使用当前布局的相邻条目，不能依赖旧 bind position。
                        panel.valid = panel.valid && FollowFeedGrouping.joins(panel.last!!, row,
                            adjacent = i == panel.lastChildIndex + 1)
                        panel.bottom = bottom; panel.last = row
                    }
                    panel.lastChildIndex = i
                }
                val iterator = panels.iterator()
                val radius = dp(cardOptions.radiusDp.roundToInt())
                while (iterator.hasNext()) {
                    val panel = iterator.next().value
                    if (panel.generation != generation || !panel.valid) {
                        panel.close(); iterator.remove(); surfacesChanged = true; continue
                    }
                    // 部分模块已滚出视口时把缺失首尾延至视口外，不在卡片中途产生圆角。
                    val top = if (panel.first?.first == true) panel.top else minOf(panel.top - radius, -radius)
                    val bottom = if (panel.last?.last == true) panel.bottom else maxOf(panel.bottom + radius, list.height + radius)
                    val left = list.left + dp(FollowFeedStyle.OUTER_DP)
                    val right = list.right - dp(FollowFeedStyle.OUTER_DP)
                    if (panel.view.left != left || panel.view.top != top || panel.view.right != right || panel.view.bottom != bottom)
                        panel.view.layout(left, top, right, bottom)
                    if (panel.binding == null && panel.view.isAttachedToWindow) {
                        panel.binding = session.bind(panel.view, cardOptions)
                        surfacesChanged = true
                    }
                }
                // 分组验证和预算检查通过后才移除模块底色。未知布局、重复身份或配额
                // 不足时保留原模块，不能出现只有透明内容而缺失承托表面的半改版。
                for (i in 0 until list.childCount) {
                    val child = list.getChildAt(i)
                    val state = rows[child] ?: continue
                    val panel = state.row?.let { panels[it.identity] }
                    val decorate = panel?.let { it.valid && it.generation == generation } == true
                    if (state.decorated != decorate) {
                        state.edits.restore(); state.palette = null; state.decorated = decorate
                    }
                    styleRow(child, state, this@Page)
                }
                // 原 ItemDecoration 的矩形底色也在列表显示缓存中。绑定重建/撤销
                // 后必须刷新一次，不能等用户滚动才清掉暂停时录入的底色。
                if (surfacesChanged) list.invalidate()
                updateBottomPadding()
            }

            private fun updateBottomPadding() {
                if (!dockChecked) {
                    dockChecked = true
                    dock = WeakReference(find<View>(list.rootView, "bottom_navigation"))
                }
                list.getLocationInWindow(location)
                val viewportBottom = location[1] + list.height
                val bar = dock?.get()?.takeIf { it.isShown && it.height > 0 }
                val dockTop = bar?.let { it.getLocationInWindow(location); location[1] }
                @Suppress("DEPRECATION")
                val systemBottom = list.rootWindowInsets?.systemWindowInsetBottom ?: 0
                val padding = FollowFeedStyle.bottomPadding(originalPadding, viewportBottom, dockTop, systemBottom, dp(FollowFeedStyle.GAP_DP))
                if (padding != appliedPadding && list.paddingBottom == appliedPadding) {
                    list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, padding)
                    appliedPadding = padding
                }
            }

            fun releasePanels() {
                if (panels.isEmpty()) return
                panels.values.forEach { it.close() }; panels.clear()
                list.invalidate()
            }
            fun hasSurface(identity: Long) = panels[identity]?.let { it.binding != null && it.view.isAttachedToWindow } == true
            val panelCount: Int get() = panels.size
            override fun close() {
                releasePanels(); viewport?.close(); viewport = null
                parent.removeView(layer); listEdits.restore()
                if (list.paddingBottom == appliedPadding)
                    list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, originalPadding)
            }
        }
    }
}
