package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Outline
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.ModernPalette
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** 宿主顶栏视觉增强配置 */
internal data class HostTopBarFxConfig(
    val liquidGlass: Boolean = true,
    val touchGlow: Boolean = true
)

/**
 * 哔哩哔哩宿主顶栏视觉增强控制器。
 *
 * 打造与宿主底栏悬浮胶囊对齐的顶栏单行流体悬浮胶囊：
 * 1. **悬浮胶囊造型**：顶栏整体收敛为左右各 16dp 边距、居中悬浮、高度 44dp、圆角半径等于 `height / 2f` 的流体胶囊外壳；
 * 2. **实时透镜采样**：采用 [HostLiquidSurfaceDrawable] 与 [HostBottomBarBackdrop]，以 [SurfaceRole.FLOATING]
 *    规范（亚像素 0.65dp 菲涅尔微光高光边缘 + 实时多趟模糊折射透出下方滚动的内容卡片，elevation = 0f）；
 * 3. **严格保留原版对齐与下划线指示器**：不破坏原生 Tab 的字号、内边距与基线对齐，完整保留原版下划线指示器；
 * 4. **零破坏与原生兼容**：不隐藏官方顶部的搜索/头像大栏，不强行截断右侧标签，完全兼容官方原生滚动折叠；
 * 5. **触控柔光**：可选挂接 [HostGlowView]，触摸顶栏标签时绽放自适应光晕，触控事件对宿主完全透明（零拦截）。
 */
internal object HostTopBarFxController {

    private const val BAR_HEIGHT_DP = 44f
    private const val MARGIN_H_DP = 16f
    private const val MARGIN_V_DP = 6f
    private const val PADDING_H_DP = 8f

    private val attachedRoots = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val resourceNames = HashMap<Int, String>()

    internal data class TopBarHierarchy(
        val topBarDock: ViewGroup,
        val searchRow: View? = null,
        val tabContainer: ViewGroup? = null,
        val searchIconView: View? = null
    )

    fun attach(
        root: ViewGroup,
        fragment: Any?,
        config: HostTopBarFxConfig,
        searchFieldName: String? = null
    ) {
        if (!config.liquidGlass && !config.touchGlow) return
        root.post {
            attachInternal(root, fragment, config, searchFieldName)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachInternal(
        root: ViewGroup,
        fragment: Any?,
        config: HostTopBarFxConfig,
        searchFieldName: String?
    ) {
        if (!attachedRoots.add(root)) return

        val hierarchy = locateTopBarHierarchy(root, fragment, searchFieldName) ?: return
        val topBarDock = hierarchy.topBarDock
        val context = topBarDock.context
        val density = topBarDock.resources.displayMetrics.density
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val palette = ModernPalette.resolve(context)

        val barHeight = (BAR_HEIGHT_DP * density).roundToInt()
        val marginH = (MARGIN_H_DP * density).roundToInt()
        val marginV = (MARGIN_V_DP * density).roundToInt()

        // 1. 悬浮胶囊几何形态与 Liquid Glass 外壳背景
        val backdrop = if (config.liquidGlass) HostBottomBarBackdrop(density) else null
        var lastIsDark = isDark

        fun updateSurfaceDrawable(force: Boolean = false) {
            if (!config.liquidGlass) return
            val curContext = topBarDock.context
            val curDark = (curContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (force || topBarDock.background !is HostLiquidSurfaceDrawable || curDark != lastIsDark) {
                lastIsDark = curDark
                val curPalette = ModernPalette.resolve(curContext)
                topBarDock.background = HostLiquidSurfaceDrawable(
                    color = curPalette.surface,
                    radius = barHeight / 2f,
                    density = density,
                    style = ModernMaterialPolicy.surface(SurfaceRole.FLOATING, curDark),
                    backdrop = backdrop
                )
            }
        }

        if (config.liquidGlass) {
            val lp = topBarDock.layoutParams
            if (lp != null) {
                lp.height = barHeight
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.leftMargin = marginH
                    lp.rightMargin = marginH
                    lp.topMargin = marginV
                    lp.bottomMargin = marginV
                }
                topBarDock.layoutParams = lp
            }

            topBarDock.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width > 0 && view.height > 0) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                    }
                }
            }
            topBarDock.clipToOutline = true
            topBarDock.elevation = 0f

            updateSurfaceDrawable(force = true)
        }

        // 2. 触控柔光图层 (放在顶栏最底层)
        var glowView: HostGlowView? = null
        if (config.touchGlow) {
            val glow = HostGlowView(
                context = context,
                highlightColor = palette.primary,
                density = density,
                maximumTravel = 4f * density
            )
            glowView = glow
            topBarDock.addView(
                glow,
                0,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )

            val touchForwarder = View.OnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        glow.updateGesture(
                            press = 1f,
                            offsetX = 0f,
                            offsetY = 0f,
                            centerX = event.x,
                            centerY = event.y,
                            barWidth = topBarDock.width,
                            barHeight = topBarDock.height
                        )
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        glow.resetGestureState()
                    }
                }
                false // 绝不拦截事件，完全放行给原生 Tab 与滚动
            }
            topBarDock.setOnTouchListener(touchForwarder)
            hierarchy.tabContainer?.setOnTouchListener(touchForwarder)
        }

        // 3. 将顶栏配置为浮动在视频流上方的 Overlay 图层，视频流撑满整页不被挤压
        configureOverlayConstraints(topBarDock, barHeight, marginH, marginV, density)

        // 4. 周期性清理与对齐调度
        val topPadding = barHeight + marginV * 2
        val parent = topBarDock.parent as? ViewGroup
        val viewPager = (if (parent != null) findViewPager(parent) else null) ?: findViewPager(root)
        if (viewPager != null) {
            setupViewPagerPageChangeListener(viewPager, topPadding, density)
        }

        var sanitizePosted = false
        val sanitizeRunnable = Runnable {
            sanitizePosted = false
            configureOverlayConstraints(topBarDock, barHeight, marginH, marginV, density)
            stripTopBarArtifacts(topBarDock, isRoot = true)
            alignTopBarContent(topBarDock, density)
            updateSurfaceDrawable()
            val vp = (if (parent != null) findViewPager(parent) else null) ?: findViewPager(root)
            if (vp != null) {
                applyScrollPadding(vp, topPadding, density)
            }
            backdrop?.revalidate(vp)
        }

        val requestSanitization = {
            if (!sanitizePosted) {
                sanitizePosted = true
                topBarDock.postOnAnimation(sanitizeRunnable)
            }
        }
        requestSanitization()

        var lastWidth = 0
        var lastHeight = 0
        topBarDock.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
                lastWidth = w
                lastHeight = h
                requestSanitization()
            }
        }

        // 5. 实时透镜生命周期绑定
        topBarDock.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                val vp = (if (topBarDock.parent != null) findViewPager(topBarDock.parent as ViewGroup) else null) ?: findViewPager(root)
                backdrop?.attach(topBarDock, vp)
                updateSurfaceDrawable(force = true)
                requestSanitization()
            }

            override fun onViewDetachedFromWindow(v: View) {
                backdrop?.detach()
                glowView?.resetGestureState()
            }
        })
        if (topBarDock.isAttachedToWindow) {
            backdrop?.attach(topBarDock, viewPager)
        }
    }

    /** 剥离官方分割线与背景 View，但严格保留原版下划线指示器和文字 */
    fun stripTopBarArtifacts(view: View, isRoot: Boolean = true) {
        if (view is HostDockLayer || view is HostGlowView) return

        // 核心保护：官方下划线指示条绝对不清除背景、绝对不隐藏！
        if (isUnderlineIndicator(view)) {
            if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
            if (view.alpha != 1f) view.alpha = 1f
            return
        }

        // 顶栏本身保留 Liquid Glass 背景，其余所有非指示器子 View 的背景/前景一律清空（与底栏 stripAllHostArtifacts 完全一致）
        if (!isRoot) {
            if (view.background != null) {
                view.background = null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && view.foreground != null) {
                view.foreground = null
            }
            (view as? FrameLayout)?.foreground = null
            if (view.isPressed) {
                view.isPressed = false
            }
        }

        val id = view.id
        val resName = resourceName(view, id).lowercase()

        // 隐藏分割线
        val vh = runCatching { view.height }.getOrDefault(0)
        val h = if (vh > 0) vh else view.layoutParams?.height ?: 0
        if (resName.contains("divider") || (view !is ViewGroup && h in 1..4 && !isUnderlineIndicator(view))) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            if (view.alpha != 0f) view.alpha = 0f
            if (view.layoutParams?.height != 0) view.layoutParams?.height = 0
            return
        }

        // 隐藏纯色底图或装饰背景
        if (resName.contains("tabs_bg") || (resName.contains("bg") && view !is ViewGroup)) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            if (view.alpha != 0f) view.alpha = 0f
            (view as? ImageView)?.setImageDrawable(null)
            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                stripTopBarArtifacts(view.getChildAt(i), isRoot = false)
            }
        }
    }

    /**
     * 判断指定 View 是否为官方原版的 Tab 下划线指示器。
     */
    fun isUnderlineIndicator(view: View): Boolean {
        val id = runCatching { view.id }.getOrDefault(View.NO_ID)
        val name = resourceName(view, id).lowercase()
        if (name.contains("indicator") || name.contains("underline") ||
            name.contains("tab_line") || name.contains("strip_line") ||
            name.contains("cursor") || name.contains("sliding_line")
        ) {
            return true
        }

        val lp = view.layoutParams
        val vh = runCatching { view.height }.getOrDefault(0)
        val vw = runCatching { view.width }.getOrDefault(0)
        val h = if (vh > 0) vh else lp?.height ?: 0
        val w = if (vw > 0) vw else lp?.width ?: 0
        val density = runCatching { view.resources.displayMetrics.density }.getOrDefault(3f)
        val maxHeight = (6f * density).roundToInt()
        val maxWidth = (120f * density).roundToInt()

        if (h in 1..maxHeight && w in 1..maxWidth) {
            return true
        }

        val parent = runCatching { view.parent as? ViewGroup }.getOrNull()
        if (parent != null) {
            val parentName = parent.javaClass.simpleName.lowercase()
            if (parentName.contains("tabstrip") || parentName.contains("sliding") || parentName.contains("tablayout")) {
                val pw = runCatching { parent.width }.getOrDefault(0)
                if (h in 1..maxHeight && (w <= 0 || pw == 0 || w < pw * 0.7f)) {
                    return true
                }
            }
        }
        return false
    }

    /** 适度内嵌水平边距，并确保所有 Tab 在胶囊内严格上下垂直居中 */
    fun alignTopBarContent(topBarDock: ViewGroup, density: Float) {
        val paddingH = (PADDING_H_DP * density).roundToInt()
        if (topBarDock.paddingLeft != paddingH || topBarDock.paddingRight != paddingH) {
            topBarDock.setPadding(paddingH, 0, paddingH, 0)
        }
        topBarDock.clipToPadding = false

        val barHeight = topBarDock.height.takeIf { it > 0 } ?: (BAR_HEIGHT_DP * density).roundToInt()
        val defaultTabH = (36f * density).roundToInt()

        // 核心修复：通过硬件平移将所有子视图在胶囊内严格上下垂直居中，使所有 Tab 的文字水平基线绝对对称
        for (i in 0 until topBarDock.childCount) {
            val child = topBarDock.getChildAt(i)
            if (child is HostDockLayer || child is HostGlowView) continue

            val ch = if (child.height > 0) child.height else child.layoutParams?.height ?: 0
            val targetOffset = if (ch in 1 until barHeight) {
                (barHeight - ch) / 2f
            } else if (ch <= 0) {
                (barHeight - defaultTabH) / 2f
            } else {
                0f
            }
            if (child.translationY != targetOffset && targetOffset > 0f) {
                child.translationY = targetOffset
            }
        }
    }

    /**
     * 将顶栏配置为独立于视频卡片的上一层 Overlay，让视频流撑满整页不被挤占位置。
     */
    internal fun configureOverlayConstraints(
        topBarDock: ViewGroup,
        barHeight: Int,
        marginH: Int,
        marginV: Int,
        density: Float
    ) {
        val parent = topBarDock.parent as? ViewGroup ?: return
        if (!parent.javaClass.simpleName.contains("ConstraintLayout")) return
        val viewPager = findViewPager(parent) ?: return

        val PARENT_ID = 0
        val UNSET = -1

        // 1. 设置 topBarDock 约束：居中浮动于 ConstraintLayout 顶部，固定高度与边距
        val tabsLp = topBarDock.layoutParams
        if (tabsLp is ViewGroup.MarginLayoutParams) {
            val curTopToTop = getConstraintInt(tabsLp, "topToTop")
            val curTopToBottom = getConstraintInt(tabsLp, "topToBottom")
            val curBottomToBottom = getConstraintInt(tabsLp, "bottomToBottom")
            val needUpdate = curTopToTop != PARENT_ID ||
                curTopToBottom != UNSET ||
                curBottomToBottom != UNSET ||
                tabsLp.height != barHeight ||
                tabsLp.leftMargin != marginH ||
                tabsLp.rightMargin != marginH ||
                tabsLp.topMargin != marginV
            if (needUpdate) {
                tabsLp.height = barHeight
                tabsLp.leftMargin = marginH
                tabsLp.rightMargin = marginH
                tabsLp.topMargin = marginV
                setConstraintInt(tabsLp, "topToTop", PARENT_ID)
                setConstraintInt(tabsLp, "topToBottom", UNSET)
                setConstraintInt(tabsLp, "bottomToTop", UNSET)
                setConstraintInt(tabsLp, "bottomToBottom", UNSET)
                setConstraintInt(tabsLp, "startToStart", PARENT_ID)
                setConstraintInt(tabsLp, "endToEnd", PARENT_ID)
                topBarDock.layoutParams = tabsLp
            }
        }

        // 2. 将 primary_multi_page_layout 内部的 ViewPager 约束展开为撑满整页（让视频卡片不再被顶栏挤压占位）
        val pagerLp = viewPager.layoutParams
        if (pagerLp is ViewGroup.MarginLayoutParams) {
            val curTopToTop = getConstraintInt(pagerLp, "topToTop")
            val curTopToBottom = getConstraintInt(pagerLp, "topToBottom")
            val curBottomToBottom = getConstraintInt(pagerLp, "bottomToBottom")
            val needUpdate = curTopToTop != PARENT_ID ||
                curTopToBottom != UNSET ||
                curBottomToBottom != PARENT_ID ||
                pagerLp.height != 0
            if (needUpdate) {
                pagerLp.height = 0 // MATCH_CONSTRAINT
                setConstraintInt(pagerLp, "topToTop", PARENT_ID)
                setConstraintInt(pagerLp, "topToBottom", UNSET)
                setConstraintInt(pagerLp, "bottomToTop", UNSET)
                setConstraintInt(pagerLp, "bottomToBottom", PARENT_ID)
                setConstraintInt(pagerLp, "startToStart", PARENT_ID)
                setConstraintInt(pagerLp, "endToEnd", PARENT_ID)
                viewPager.layoutParams = pagerLp
            }
        }

        // 3. 提升顶栏 Z 轴深度并置于最前，使其作为独立 Overlay 悬浮于视频流图层之上
        topBarDock.bringToFront()
        val targetZ = 10f * density
        if (topBarDock.translationZ != targetZ) {
            topBarDock.translationZ = targetZ
        }

        if (parent.clipChildren) parent.clipChildren = false
        if (parent.clipToPadding) parent.clipToPadding = false
    }

    private fun setConstraintInt(lp: Any, fieldName: String, value: Int) {
        runCatching {
            val field = lp.javaClass.getField(fieldName)
            field.setInt(lp, value)
        }
    }

    private fun getConstraintInt(lp: Any, fieldName: String): Int {
        return runCatching {
            val field = lp.javaClass.getField(fieldName)
            field.getInt(lp)
        }.getOrDefault(-1)
    }

    private fun applyScrollPadding(view: View, topPadding: Int, density: Float) {
        if (view is RecyclerView) {
            if (view.paddingTop != topPadding || view.clipToPadding) {
                view.setPadding(view.paddingLeft, topPadding, view.paddingRight, view.paddingBottom)
                view.clipToPadding = false
            }
            return
        }
        if (view.javaClass.simpleName.contains("RecyclerView")) {
            val pt = view.paddingTop
            if (pt != topPadding) {
                view.setPadding(view.paddingLeft, topPadding, view.paddingRight, view.paddingBottom)
                runCatching {
                    val m = view.javaClass.getMethod("setClipToPadding", Boolean::class.javaPrimitiveType)
                    m.invoke(view, false)
                }
            }
            return
        }
        if (view.javaClass.simpleName.contains("SwipeRefreshLayout")) {
            runCatching {
                val m = view.javaClass.getMethod(
                    "setProgressViewOffset",
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                m.invoke(view, false, topPadding, topPadding + (40f * density).roundToInt())
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyScrollPadding(view.getChildAt(i), topPadding, density)
            }
        }
    }

    private fun setupViewPagerPageChangeListener(viewPager: ViewGroup, topPadding: Int, density: Float) {
        runCatching {
            val listenerClass = Class.forName("androidx.viewpager.widget.ViewPager\$OnPageChangeListener", false, viewPager.javaClass.classLoader)
            val addListenerMethod = viewPager.javaClass.getMethod("addOnPageChangeListener", listenerClass)
            val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
                if (method.name == "onPageSelected") {
                    viewPager.post {
                        applyScrollPadding(viewPager, topPadding, density)
                    }
                }
                null
            }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                viewPager.javaClass.classLoader,
                arrayOf(listenerClass),
                handler
            )
            addListenerMethod.invoke(viewPager, proxy)
        }
    }

    internal fun findViewPager(root: ViewGroup): ViewGroup? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child.javaClass.simpleName.contains("ViewPager")) {
                return child as? ViewGroup
            }
            if (child is ViewGroup) {
                val found = findViewPager(child)
                if (found != null) return found
            }
        }
        return null
    }


    /** 定位顶栏各核心构件 */
    internal fun locateTopBarHierarchy(
        root: ViewGroup,
        fragment: Any?,
        searchFieldName: String?
    ): TopBarHierarchy? {
        var searchRow: View? = null
        if (fragment != null && !searchFieldName.isNullOrBlank()) {
            val field = KavaMemberLookup.fieldOrNull(fragment.javaClass, searchFieldName, includeSuperclasses = true)
            val searchTextView = runCatching { field?.get(fragment) as? View }.getOrNull()
            if (searchTextView != null) {
                searchRow = findTopLevelRowInRoot(root, searchTextView)
            }
        }

        // 策略 1：通过已知 Tab 文本（"推荐"、"热门"、"直播"、"动画" 等）向上推导
        val tabView = findViewByText(root, setOf("推荐", "热门", "直播", "动画", "影视", "追番", "Recommend", "Hot"))
        if (tabView != null) {
            val tabItem = tabView.parent as? ViewGroup
            val tabStrip = tabItem?.parent as? ViewGroup
            val tabContainer = (tabStrip?.parent as? ViewGroup) ?: tabStrip

            if (tabContainer != null) {
                val candidateDock = findRowContainer(root, tabContainer) ?: tabContainer
                val searchIcon = findSearchIcon(candidateDock)
                ModernHookLog.info("[BIL] locateTopBarHierarchy: candidateDock=${candidateDock.javaClass.name}, res=${resourceName(candidateDock, candidateDock.id)}, parent=${candidateDock.parent?.javaClass?.name}, tabContainer=${tabContainer.javaClass.name}")
                return TopBarHierarchy(
                    topBarDock = candidateDock,
                    searchRow = searchRow,
                    tabContainer = tabContainer,
                    searchIconView = searchIcon
                )
            }
        }

        // 策略 2：通过资源 ID 推导
        val context = root.context
        val candidateIds = listOf("tabs_layout", "channel_layout", "top_bar", "channel_tab_layout", "sliding_tabs", "tab_layout", "home_top_bar")
        for (idName in candidateIds) {
            val resId = context.resources.getIdentifier(idName, "id", context.packageName)
            if (resId != 0) {
                val view = root.findViewById<View>(resId)
                if (view is ViewGroup) {
                    val candidateDock = findRowContainer(root, view) ?: view
                    val searchIcon = findSearchIcon(candidateDock)
                    return TopBarHierarchy(
                        topBarDock = candidateDock,
                        searchRow = searchRow,
                        tabContainer = view,
                        searchIconView = searchIcon
                    )
                }
            }
        }

        return null
    }

    private fun findTopLevelRowInRoot(root: ViewGroup, target: View): View? {
        var current: View = target
        while (current.parent is View) {
            val p = current.parent as View
            if (p === root || p.parent === root) {
                return current
            }
            current = p
        }
        return null
    }

    private fun findRowContainer(root: ViewGroup, tabContainer: ViewGroup): ViewGroup? {
        var current: ViewGroup = tabContainer
        while (current.parent is ViewGroup) {
            val p = current.parent as ViewGroup
            if (p === root) return current
            val pName = p.javaClass.simpleName.lowercase()
            val pRes = resourceName(p, p.id).lowercase()
            if (pName.contains("appbar") || pName.contains("coordinator")) {
                return current
            }
            // 严禁将包含 ViewPager 的多页主容器（如 primary_multi_page_layout）误判为顶栏单行！
            if (pRes.contains("multi_page") || pRes.contains("pager_layout") || hasViewPagerChild(p)) {
                return current
            }
            val currentRes = resourceName(current, current.id).lowercase()
            if (currentRes.contains("tabs_layout") || currentRes.contains("tab_layout") || currentRes.contains("top_bar")) {
                return current
            }
            if (p.childCount > 1) {
                return p
            }
            current = p
        }
        return current
    }

    private fun hasViewPagerChild(vg: ViewGroup): Boolean {
        for (i in 0 until vg.childCount) {
            if (vg.getChildAt(i).javaClass.simpleName.contains("ViewPager")) {
                return true
            }
        }
        return false
    }

    private fun findSearchIcon(dock: ViewGroup): View? {
        for (i in 0 until dock.childCount) {
            val child = dock.getChildAt(i)
            val name = resourceName(child, child.id).lowercase()
            if (name.contains("search") && !name.contains("text")) {
                return child
            }
            if (child is ViewGroup) {
                val found = findSearchIcon(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findViewByText(root: ViewGroup, targets: Set<String>): View? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is TextView && child.text?.toString() in targets) {
                return child
            }
            if (child is ViewGroup) {
                val found = findViewByText(child, targets)
                if (found != null) return found
            }
        }
        return null
    }

    private fun resourceName(view: View, id: Int): String {
        if (id == 0 || id == View.NO_ID) return ""
        resourceNames[id]?.let { return it }
        val name = try {
            view.resources?.getResourceEntryName(id).orEmpty()
        } catch (_: Throwable) {
            ""
        }
        resourceNames[id] = name
        return name
    }
}
