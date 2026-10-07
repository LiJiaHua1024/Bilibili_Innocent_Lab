package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Outline
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
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
 * 5. **触控柔光**：可选挂接 [HostGlowView]，触摸顶栏标签时绽放自适应光晕；
 * 6. **手势收岛**：仅接管边缘向内且分类已到边界的滑动（右侧固定按钮直接收起），中心与可滚动分类仍交给宿主。
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
        val theme = HostChromeTheme(context)
        val colors = theme.read()
        val isDark = colors.dark
        val palette = colors.palette()

        val barHeight = (BAR_HEIGHT_DP * density).roundToInt()
        val marginH = (MARGIN_H_DP * density).roundToInt()
        val marginV = (MARGIN_V_DP * density).roundToInt()

        // 1. 悬浮胶囊几何形态与 Liquid Glass 外壳背景
        val backdrop = if (config.liquidGlass) HostBottomBarBackdrop(density, preferGpu = true) else null
        var materialColors = colors

        fun updateSurfaceDrawable(force: Boolean = false, current: HostChromeColors = theme.read()) {
            if (!config.liquidGlass) return
            if (force || topBarDock.background !is HostLiquidSurfaceDrawable || current != materialColors) {
                val curPalette = current.palette()
                topBarDock.background = HostLiquidSurfaceDrawable(
                    color = curPalette.surface,
                    radius = barHeight / 2f,
                    density = density,
                    style = ModernMaterialPolicy.surface(SurfaceRole.FLOATING, current.dark),
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
        val pageActions = HostTopIslandPageActions(root, viewPager)
        val island = if (config.liquidGlass) {
            HostTopIslandBinding.attach(topBarDock, hierarchy.tabContainer, density, glowView, backdrop,
                palette.primary, pageActions)
        } else null

        // 5. 顶部状态栏融合带：内容一路上延到窗口顶边，在顶栏之下铺一层渐渐消隐的实时模糊
        val fusion = if (config.liquidGlass && parent != null) {
            HostTopFusionBinding.attach(
                container = parent,
                pager = viewPager,
                pageActions = pageActions,
                backdrop = backdrop,
                basePadding = topPadding,
                density = density,
                statusBarInset = resolveStatusBarInset(root),
                palette = palette,
                style = ModernMaterialPolicy.surface(SurfaceRole.TOP_BAR, isDark),
                isDark = isDark
            )
        } else {
            null
        }

        // 换肤可以不重建、不改变尺寸；在现有绘制帧同步颜色，不依赖布局清理触发。
        fun refreshTheme() {
            val current = theme.read()
            updateSurfaceDrawable(current = current)
            if (current != materialColors) {
                glowView?.recolor(current.accent)
                island?.recolor(current.accent)
                materialColors = current
            }
            fusion?.refreshMaterial(current)
        }
        val materialPreDraw = ViewTreeObserver.OnPreDrawListener { refreshTheme(); true }
        var materialObserver: ViewTreeObserver? = null
        fun observeMaterial() {
            val observer = topBarDock.viewTreeObserver
            if (materialObserver === observer && observer.isAlive) return
            materialObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(materialPreDraw)
            if (observer.isAlive) {
                observer.addOnPreDrawListener(materialPreDraw)
                materialObserver = observer
            }
        }

        if (viewPager != null) {
            setupViewPagerPageChangeListener(
                viewPager,
                density,
                { fusion?.scrollPadding ?: topPadding },
                { fusion?.spinnerTravelBase ?: topPadding }
            )
        }

        var sanitizePosted = false
        val sanitizeRunnable = Runnable {
            sanitizePosted = false
            configureOverlayConstraints(topBarDock, barHeight, marginH, marginV, density)
            stripTopBarArtifacts(topBarDock, isRoot = true)
            alignTopBarContent(topBarDock, density)
            refreshTheme()
            island?.sync()
            fusion?.sync()
            val vp = (if (parent != null) findViewPager(parent) else null) ?: findViewPager(root)
            if (vp != null) {
                applyScrollPadding(
                    vp,
                    fusion?.scrollPadding ?: topPadding,
                    density,
                    fusion?.spinnerTravelBase ?: topPadding
                )
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
                observeMaterial()
                requestSanitization()
            }

            override fun onViewDetachedFromWindow(v: View) {
                backdrop?.detach()
                materialObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(materialPreDraw)
                materialObserver = null
                glowView?.resetGestureState()
            }
        })
        if (topBarDock.isAttachedToWindow) {
            backdrop?.attach(topBarDock, viewPager)
            observeMaterial()
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

    internal fun setConstraintInt(lp: Any, fieldName: String, value: Int) {
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

    /**
     * 状态栏高度（像素）。优先用窗口真实 inset（挖孔/异形屏才准），拿不到时退回系统资源。
     * 两者都没有时按 24dp 兜底，绝不返回 0——融合带的几何全靠它，返回 0 会让带子退化成一条无意义的窄条。
     */
    private fun resolveStatusBarInset(root: View): Int {
        val insets = root.rootWindowInsets
        if (insets != null) {
            val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            if (top > 0) return top
        }
        val resources = root.resources
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id != 0) {
            val height = resources.getDimensionPixelSize(id)
            if (height > 0) return height
        }
        return (24f * resources.displayMetrics.density).roundToInt()
    }

    /**
     * 顶栏之下的滚动内边距与下拉刷新参数。
     *
     * @param topPadding 列表要用的顶部内边距（胶囊行高度）；搜索区由 pager 位移占位。
     * @param spinnerTravelBase 下拉刷新提示球的**行程基准**：`SwipeRefreshLayout` 的 `end` 同时
     *   决定"球的行程"和"停下后够到哪"，它是**距离**不是位置；内容上延后不能跟着一起挪，
     *   跟着挪会让提示球按同比例变快（实测 1.9 倍，手感就是"轻轻一拉球就飞下去"）。
     *   这里传未经上延的基础内边距，只有静止位置（`start`）跟着上延走。
     */
    internal fun applyScrollPadding(
        view: View,
        topPadding: Int,
        density: Float,
        spinnerTravelBase: Int = topPadding
    ) {
        // 宿主 RecyclerView 属于另一份 ClassLoader，连同其子类按继承链识别。
        if (view is RecyclerView || generateSequence(view.javaClass as Class<*>) { it.superclass }
                .any { it.simpleName == "RecyclerView" }) {
            if (view.paddingTop != topPadding) {
                val wasAtTop = !view.canScrollVertically(-1)
                view.setPadding(view.paddingLeft, topPadding, view.paddingRight, view.paddingBottom)
                // setPadding 会保留已布局卡片的旧坐标。冷启动首项可能仍停在 0，
                // 被误判为滚动了 topPadding，连带撤掉搜索区占位。仅原本在顶部时重建首项锚点。
                if (wasAtTop) {
                    if (view is RecyclerView) view.scrollToPosition(0)
                    else runCatching {
                        KavaMemberLookup.inheritedMethodOrNull(view.javaClass, "scrollToPosition",
                            Int::class.javaPrimitiveType!!)?.invoke(view, 0)
                    }
                }
            }
            (view as? ViewGroup)?.let { if (it.clipToPadding) it.clipToPadding = false }
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
                m.invoke(view, false, topPadding, spinnerTravelBase + (40f * density).roundToInt())
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyScrollPadding(view.getChildAt(i), topPadding, density, spinnerTravelBase)
            }
        }
    }

    /**
     * 切页后重建的列表要重新吃一遍顶栏内边距。[scrollPadding] / [spinnerTravelBase] 由
     * [HostTopFusionBinding] 持有。切页只恢复固定胶囊行内边距，搜索区占位与原生刷新行程分开。
     */
    private fun setupViewPagerPageChangeListener(
        viewPager: ViewGroup,
        density: Float,
        scrollPadding: () -> Int,
        spinnerTravelBase: () -> Int
    ) {
        runCatching {
            val listenerClass = Class.forName("androidx.viewpager.widget.ViewPager\$OnPageChangeListener", false, viewPager.javaClass.classLoader)
            val addListenerMethod = viewPager.javaClass.getMethod("addOnPageChangeListener", listenerClass)
            val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
                if (method.name == "onPageSelected") {
                    viewPager.post {
                        applyScrollPadding(viewPager, scrollPadding(), density, spinnerTravelBase())
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

/**
 * 顶部"融合带"的运行时装配：让内容层从容器顶边一路画到窗口顶边，再在顶栏之下铺一层
 * 渐渐消隐的实时模糊（[HostTopStatusFusionView]）。
 *
 * 最大容器顶边作为固定负 topMargin，滚动内容仍能画到窗口顶边。列表只保留胶囊行的固定
 * padding；临近列表起点时，pager 用 translationY 连续退回原布局位置，为搜索/状态栏恢复占位。
 * 同一进度把融合带从模糊变为实色，并降回内容层。距离使用列表子项的局部坐标，视觉位移不会
 * 反过来改变进度，也不会逐帧改 padding、干扰原生滚动与刷新行程。
 */
internal class HostTopFusionBinding private constructor(
    private val container: ViewGroup,
    private val band: HostTopStatusFusionView,
    private val pager: ViewGroup?,
    private val pageActions: HostTopIslandPageActions,
    private val backdrop: HostBottomBarBackdrop?,
    private val basePadding: Int,
    private val density: Float,
    isDark: Boolean
) {
    private val location = IntArray(2)
    private var preDraw: android.view.ViewTreeObserver.OnPreDrawListener? = null

    /** 固定胶囊行内边距；搜索区的占位由 pager 位移独立承担。 */
    val scrollPadding: Int get() = basePadding

    /**
     * 下拉刷新提示球的行程基准：**不能**跟着内容上延走（`SwipeRefreshLayout` 的 `end` 是距离
     * 不是位置），否则提示球速度按同比例翻倍。见 [HostTopBarFxController.applyScrollPadding]。
     */
    val spinnerTravelBase: Int get() = basePadding

    /** 融合带顶边当前对齐到的屏幕 y。 */
    private var bandTop = Int.MIN_VALUE

    /** 渐隐收口当前对齐到的"内容静止顶边"屏幕 y。 */
    private var restTop = Int.MIN_VALUE

    /** 内容上延的固定位移量 = 观测到的容器最大屏幕顶边。 */
    private var contentOffset = 0
    private var listPosition: HostTopListPosition? = null
    private var dockingProgress = 1f

    /** 融合带当前生效的材质深浅色，见 [refreshMaterial]。 */
    private var materialDark = isDark

    companion object {
        fun attach(
            container: ViewGroup,
            pager: ViewGroup?,
            pageActions: HostTopIslandPageActions,
            backdrop: HostBottomBarBackdrop?,
            basePadding: Int,
            density: Float,
            statusBarInset: Int,
            palette: MonetColors,
            style: ModernSurfaceStyle,
            isDark: Boolean
        ): HostTopFusionBinding? {
            if (statusBarInset <= 0) return null
            return runCatching {
                val band = HostTopStatusFusionView(
                    context = container.context,
                    density = density,
                    palette = palette,
                    style = style,
                    backdrop = backdrop,
                    statusBarInset = statusBarInset,
                    // 先按"容器顶边 = 状态栏下沿"这个下界建带；首次 sync 观测到真实顶边后只增不减地长高。
                    initialHeight = HostTopFusionPolicy.bandHeight(statusBarInset + basePadding)
                )
                // 不传 LayoutParams：让宿主自己的 ConstraintLayout 生成它那一份（跨 ClassLoader）
                container.addView(band)
                val lp = band.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = band.bandHeight
                    lp.leftMargin = 0
                    lp.rightMargin = 0
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                    HostTopBarFxController.setConstraintInt(lp, "topToTop", 0)
                    HostTopBarFxController.setConstraintInt(lp, "startToStart", 0)
                    HostTopBarFxController.setConstraintInt(lp, "endToEnd", 0)
                    band.layoutParams = lp
                }
                // Z 序：低于顶栏胶囊（10f·density）、高于 ViewPager（0）——模糊永远在顶栏之下。
                band.translationZ = 2f * density
                HostTopFusionBinding(container, band, pager, pageActions, backdrop, basePadding, density, isDark).also {
                    it.unclipToWindowTop()
                    it.install()
                }
            }.getOrNull()
        }
    }

    private fun install() {
        val observer = container.viewTreeObserver
        if (!observer.isAlive) return
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            sync()
        }
        observer.addOnPreDrawListener(listener)
        preDraw = listener
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (preDraw == null) install()
                sync()
            }

            override fun onViewDetachedFromWindow(v: View) {
                preDraw?.let { runCatching { v.viewTreeObserver.removeOnPreDrawListener(it) } }
                preDraw = null
            }
        })
        sync()
    }

    /** 原生滚动完成后、绘制之前同步。修复宿主覆盖的 padding 时等待重布局，不画错位帧。 */
    fun sync(): Boolean {
        val top = screenTopOf(container) ?: return true
        if (top != bandTop) {
            val first = bandTop == Int.MIN_VALUE
            bandTop = top
            band.translationY = -top.toFloat()
            // 首帧之前带子先不画：那时它的顶边还没对齐到窗口顶边，画出来会是一块错位的白雾。
            if (first) band.alpha = 1f
            // 表面动了：下一帧重采一次，否则纹理停在旧映射上（静止态纹理本就会随内容位移刷新）。
            backdrop?.onVisualMovement()
        }
        // 渐隐收口锚在"内容静止顶边"：宿主 AppBar 一折叠，第一排卡片就上移，收口必须跟着上移，
        // 否则它会被糊在渐隐尾里（且再也滑不出去）。
        val contentRestTop = HostTopFusionPolicy.contentRestTop(top, basePadding)
        if (contentRestTop != restTop) {
            restTop = contentRestTop
            band.updateFade(contentRestTop)
            backdrop?.onVisualMovement()
        }
        if (top > contentOffset) {
            contentOffset = top
            val lp = pager?.layoutParams
            if (lp is ViewGroup.MarginLayoutParams && lp.topMargin != -top) {
                lp.topMargin = -top
                pager.layoutParams = lp
            }
            if (pager != null) {
                HostTopBarFxController.applyScrollPadding(pager, scrollPadding, density, basePadding)
            }
            ModernHookLog.info("[BIL] 顶栏融合带内容上延: offset=$top, scrollPadding=$scrollPadding")
        }
        val list = pageActions.visibleList()
        if (listPosition?.view !== list) listPosition = list?.let(::HostTopListPosition)
        if (list != null && list.paddingTop != basePadding) {
            HostTopBarFxController.applyScrollPadding(list, basePadding, density, basePadding)
            if (list.paddingTop == basePadding) return false
        }
        // padding/首项锚点在下一次布局才生效，不能用旧坐标更新停靠进度或画出遮挡帧。
        if (list?.isLayoutRequested == true) return false
        listPosition?.edge()?.let { edge ->
            val range = minOf(HostTopFusionPolicy.DOCK_RANGE_DP * density, edge.firstRowExtent)
            dockingProgress = HostTopFusionPolicy.dockingProgress(edge.distance, range)
        }
        val offset = contentOffset * dockingProgress
        if (pager != null && pager.translationY != offset) {
            pager.translationY = offset
            backdrop?.onVisualMovement()
        }
        band.updateDocking(dockingProgress, top)
        return true
    }

    /** 深浅色/主题切换后刷新融合带的色罩（采样纹理只带 alpha 曲线，不受影响）。 */
    fun refreshMaterial(colors: HostChromeColors) {
        val isDark = colors.dark
        if (isDark == materialDark) return
        materialDark = isDark
        band.updateMaterial(
            colors.palette(),
            ModernMaterialPolicy.surface(SurfaceRole.TOP_BAR, isDark)
        )
    }

    private fun screenTopOf(view: View): Int? {
        if (!view.isAttachedToWindow) return null
        view.getLocationOnScreen(location)
        return location[1]
    }

    /**
     * 打开"内容画到窗口顶边"这条路：从容器往上到窗口根，逐层关掉 clipChildren/clipToPadding。
     * 只放宽裁剪、不改任何布局参数；这条链上每个宿主视图都没有依赖这些裁剪的子视图。
     */
    private fun unclipToWindowTop() {
        var node: View? = container
        while (node != null) {
            if (node is ViewGroup) {
                if (node.clipChildren) node.clipChildren = false
                if (node.clipToPadding) node.clipToPadding = false
            }
            node = node.parent as? View
        }
    }
}
