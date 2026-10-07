package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.GlowConfig
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.GlowFrame
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.GlowState
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationGesture
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationIntent
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationMotion
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationSpring
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.reachablePileRoomPx
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedMotionSurfaceAlpha
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.TouchGlowRenderer
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** 配置项参数 */
internal data class HostBottomBarFxConfig(
    val liquidGlass: Boolean = true,
    val touchGlow: Boolean = true,
    val compact: Boolean = false,
    val iconOnly: Boolean = false
) {
    val heightDp: Float
        get() = when {
            compact && iconOnly -> 44f
            iconOnly -> 48f
            compact -> 52f
            else -> ModernNavigationMotion.BAR_HEIGHT_DP.toFloat()
        }

    fun horizontalMarginDp(parentWidthDp: Float, tabCount: Int): Float {
        val baseMargin = if (liquidGlass) 16f else 0f
        if (!iconOnly) return baseMargin
        val normalWidth = (parentWidthDp - baseMargin * 2f).coerceAtLeast(0f)
        // 收窄实际布局，让图标、滑块与触控坐标一起适配；窄屏仍保留每项 44dp 的空间。
        val minimumWidth = tabCount.coerceAtLeast(1) * 44f + ModernNavigationMotion.INSET_DP * 2f
        val widthFraction = if (compact) 0.84f else 0.88f
        val width = (normalWidth * widthFraction).coerceAtLeast(minimumWidth).coerceAtMost(normalWidth)
        return (parentWidthDp - width) / 2f
    }
}

/**
 * 拖动滑块的落点语义：**落回当前页不算操作**。
 *
 * 模块自己的设置 pager 同页选中没有副作用，宿主不同——宿主点击当前 tab 是"刷新"（首页即刷新推荐流）。
 * 手势收尾给的是"离预览位置最近的页"，所以拖开又落回原页会拿回原页索引；那种情况必须什么都不做，
 * 只有真的落到别的页才把点击交回宿主。
 */
internal object HostBottomBarScrubRelease {
    /** [target] 手势落点页；[scrubbed] 表示这次是横向拖动滑块收尾；[currentPage] 是宿主当前页。 */
    fun selectableTarget(target: Int?, scrubbed: Boolean, currentPage: Int): Int? =
        target?.takeUnless { scrubbed && it == currentPage }

    /** activate 返回是否选中了页面；发布动作和未被处理的点击都保留原页面。 */
    fun activateTarget(target: Int?, scrubbed: Boolean, currentPage: Int, activate: (Int) -> Boolean): Int {
        val index = selectableTarget(target, scrubbed, currentPage) ?: return currentPage
        return if (activate(index)) index else currentPage
    }
}

/**
 * 哔哩哔哩宿主底栏视觉增强控制器。
 *
 * 100% 像素级对齐模块原生 [com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationBar]：
 * 1. 材质：直接应用 [ModernMaterialPolicy] 的 Liquid Glass 规范（浮动底栏 FLOATING 浅色 112/深色 120 雾度，菲涅尔高光上 140/下 32；
 *    指示滑块 SELECTED_ITEM 浅色 210/深色 218 晶体透镜，菲涅尔高光上 60/下 12；0.65dp 亚像素超细边缘描边），
 *    并叠加 [HostBottomBarBackdrop] 的实时透镜采样：底栏下方的内容被模糊/折射后从胶囊透出。
 * 2. 性能：材质采样全在后台线程（UI 线程只有一次 Picture 录制，28ms 节流 + 后台单飞），动画只走 RenderNode 属性；
 *    宿主专用的清理/对齐改成按需触发并合并到一帧一次，逐帧路径只剩 O(items) 空判与对齐（不再每帧全树递归）。
 * 3. 几何与对齐：BAR_HEIGHT_DP = 64dp, INSET = 4dp, slotWidth 分析对齐，首帧冷启动绝对居中，杜绝任何位移偏离。
 * 4. 物理反馈：460ms 阻尼简谐物理弹簧 (decay = 15.6, freq = 12.51559)；按压呼吸形变 (X +5.5%, Y +7.0%)；
 *    拖拽滑块 (Scrub) 与 2D 上拉弹性阻尼回弹 (MAX_TRAVEL = 4dp)。
 * 5. 柔光：集成 [AdaptiveGlowPolicy] 与 [TouchGlowRenderer]，边缘自适应压扁堆积。
 * 6. 干净纯粹：彻底屏蔽宿主官方按压深色方块与分割线。
 */
internal object HostBottomBarFxController {

    private val attachedHosts = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    fun attach(tabHost: ViewGroup, config: HostBottomBarFxConfig) {
        if (!config.liquidGlass && !config.touchGlow && !config.compact && !config.iconOnly) return
        tabHost.post {
            attachInternal(tabHost, config)
        }
    }

    private fun attachInternal(tabHost: ViewGroup, config: HostBottomBarFxConfig) {
        if (!attachedHosts.add(tabHost)) return

        val context = tabHost.context
        val density = tabHost.resources.displayMetrics.density
        val theme = HostChromeTheme(context)
        val colors = theme.read()
        val isDark = colors.dark
        val palette = colors.palette()

        // 1. 查找底栏内原有构件
        val bgId = context.resources.getIdentifier("tab_background", "id", context.packageName)
        val divId = context.resources.getIdentifier("bottom_tab_divider", "id", context.packageName)
        val containerId = context.resources.getIdentifier("container", "id", context.packageName)

        val container = (if (containerId != 0) tabHost.findViewById<ViewGroup>(containerId) else null)
            ?: (0 until tabHost.childCount).map { tabHost.getChildAt(it) }
                .filterIsInstance<ViewGroup>().firstOrNull()

        val barHeight = (config.heightDp * density).roundToInt()
        val marginH = horizontalMarginPx(tabHost, container, config, density)
        val marginB = (12f * density).roundToInt()
        val inset = ModernNavigationMotion.INSET_DP * density

        // 2. 悬浮胶囊几何形态与 Liquid Glass 外壳背景
        val backdrop = if (config.liquidGlass) HostBottomBarBackdrop(density) else null
        if (config.liquidGlass || config.compact || config.iconOnly) {
            val lp = tabHost.layoutParams
            if (lp != null) {
                lp.height = barHeight
                if ((config.liquidGlass || config.iconOnly) && lp is ViewGroup.MarginLayoutParams) {
                    lp.leftMargin = marginH
                    lp.rightMargin = marginH
                    if (config.liquidGlass) lp.bottomMargin = marginB
                }
                if (lp is FrameLayout.LayoutParams) {
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                } else if (lp is CoordinatorLayout.LayoutParams) {
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                tabHost.layoutParams = lp
            }
        }
        if (config.liquidGlass) {
            tabHost.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width > 0 && view.height > 0) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                    }
                }
            }
            tabHost.clipToOutline = true
            // 模块胶囊没有 elevation：与内容分层只靠 0.65dp 菲涅尔描边，投影会从半透明玻璃下透出灰边。
            tabHost.elevation = 0f

            // 应用模块浮动底栏原版 Liquid Glass 材质：常量色罩 + 实时透镜采样（模糊/折射底下的内容）
            tabHost.background = HostLiquidSurfaceDrawable(
                color = palette.surface,
                radius = barHeight / 2f,
                density = density,
                style = ModernMaterialPolicy.surface(SurfaceRole.FLOATING, isDark),
                backdrop = backdrop
            )
        }

        val insetH = inset.roundToInt()

        // 3. 清除宿主分割线/背景、对齐内容：宿主会在按压、切页、重建 tab 时把官方背景重新挂回来，
        //    所以保留整树清理，但改成"按需触发 + 合并到一帧一次"。逐帧路径只剩 pre-draw 里的
        //    O(items) 空判与对齐（见 HostBottomBarDockLayer.preDrawListener），不再每帧全树递归。
        var sanitizePosted = false
        val sanitizeRunnable = Runnable {
            sanitizePosted = false
            if (config.liquidGlass || config.touchGlow) stripAllHostArtifacts(tabHost, isRoot = true)
            alignTabContent(tabHost, container, density, insetH, config)
            // 冷启动时底栏还没有尺寸、找不到采样源；布局稳定后每次清理都顺手补一次定位（命中即 O(1)）。
            backdrop?.revalidate()
        }
        val requestSanitization = {
            if (!sanitizePosted) {
                sanitizePosted = true
                tabHost.postOnAnimation(sanitizeRunnable)
            }
        }
        requestSanitization()

        tabHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestSanitization() }


        if (!config.liquidGlass && !config.touchGlow) {
            container?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestSanitization() }
            tabHost.viewTreeObserver.addOnPreDrawListener {
                alignTabContent(tabHost, container, density, insetH, config)
                true
            }
            return
        }

        // 4. 插入专属硬件加速指示滑块、柔光图层与实时透镜 (放在最底层)
        val dockLayer = HostBottomBarDockLayer(
            context, config, tabHost, container, palette, isDark, backdrop, requestSanitization,
            theme = theme
        )
        tabHost.addView(
            dockLayer,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // 5. 挂接统一全域触控手势
        tabHost.setOnTouchListener { _, event ->
            dockLayer.handleTouch(event, -1)
        }
        dockLayer.setOnTouchListener { _, event ->
            dockLayer.handleTouch(event, -1)
        }

        container?.let { c ->
            c.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> requestSanitization() }

            fun hookTabTouch() {
                requestSanitization()
                for (i in 0 until c.childCount) {
                    val tabItem = c.getChildAt(i)
                    tabItem.background = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        tabItem.foreground = null
                    }
                    (tabItem as? FrameLayout)?.foreground = null
                    tabItem.setOnTouchListener { _, event ->
                        dockLayer.handleTouch(event, i)
                    }
                }
            }
            hookTabTouch()
            c.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    // 宿主重建 tab 时内容层可能整体被替换：重找实时透镜的采样源。
                    backdrop?.revalidate()
                    hookTabTouch()
                }

                override fun onChildViewRemoved(parent: View?, child: View?) {
                    backdrop?.revalidate()
                    hookTabTouch()
                }
            })
        }
    }

    /**
     * id → 资源名缓存。
     *
     * `getResourceEntryName` 走 AssetManager 的资源表（加锁、每次返回新建 String），而同一 id 在进程内
     * 的名字恒定。宿主底栏的逐帧路径曾经每个节点查一次，是快速切页掉帧的来源之一；清理与对齐现在都
     * 按需触发，这里再做一层缓存，让偶发的整树扫描也不产生这批查询。
     */
    private val resourceNames = HashMap<Int, String>()

    private fun resourceName(view: View, id: Int): String {
        if (id == 0 || id == View.NO_ID) return ""
        resourceNames[id]?.let { return it }
        val name = try {
            view.resources.getResourceEntryName(id)
        } catch (_: Exception) {
            ""
        }
        resourceNames[id] = name
        return name
    }

    /**
     * 垂直居中对齐底栏内容 (图标或图文组合居中于当前高度的底栏内)。
     * 消除文字过度靠下或图标独占居中的视觉失衡，完全还原模块导航栏的人机工程布局。
     */
    fun alignTabContent(
        tabHost: ViewGroup,
        container: ViewGroup?,
        density: Float,
        insetH: Int,
        config: HostBottomBarFxConfig = HostBottomBarFxConfig()
    ) {
        if (container == null) return
        if (config.iconOnly) {
            val lp = tabHost.layoutParams as? ViewGroup.MarginLayoutParams
            val marginH = horizontalMarginPx(tabHost, container, config, density)
            if (lp != null && (lp.leftMargin != marginH || lp.rightMargin != marginH)) {
                lp.leftMargin = marginH
                lp.rightMargin = marginH
                tabHost.layoutParams = lp
            }
        }
        val barHeight = tabHost.height.takeIf { it > 0 } ?: (config.heightDp * density).roundToInt()

        // 1. 宿主中间层包装容器 (例如包裹 divider 与 container 的 LinearLayout) 铺满并居中
        val contentParent = container.parent as? ViewGroup
        if (contentParent != null && contentParent != tabHost) {
            val clp = contentParent.layoutParams
            if (clp != null) {
                var changed = false
                if (clp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                    clp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    changed = true
                }
                if (clp is FrameLayout.LayoutParams && clp.gravity != Gravity.CENTER) {
                    clp.gravity = Gravity.CENTER
                    clp.topMargin = 0
                    clp.bottomMargin = 0
                    changed = true
                }
                if (changed) contentParent.layoutParams = clp
            }
            contentParent.setPadding(0, 0, 0, 0)
            if (tabHost.width > 0 && barHeight > 0) {
                if (contentParent.top != 0 || contentParent.bottom != barHeight) {
                    contentParent.layout(0, 0, tabHost.width, barHeight)
                }
            }
        }

        // 2. container 左右内嵌 insetH，上下内边距清零，高度 MATCH_PARENT
        val lp = container.layoutParams
        if (lp != null) {
            var changed = false
            if (lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                changed = true
            }
            if (lp is ViewGroup.MarginLayoutParams && (lp.topMargin != 0 || lp.bottomMargin != 0)) {
                lp.topMargin = 0
                lp.bottomMargin = 0
                changed = true
            }
            if (changed) container.layoutParams = lp
        }
        container.setPadding(insetH, 0, insetH, 0)
        container.clipToPadding = false
        if (tabHost.width > 0 && barHeight > 0) {
            if (container.top != 0 || container.bottom != barHeight) {
                container.layout(0, 0, tabHost.width, barHeight)
            }
        }

        // 3. 遍历每一个 Tab 项，确保 tab 高度铺满，且内部 normal_ll (包含图标与文字) 整体垂直居中
        for (i in 0 until container.childCount) {
            val tab = container.getChildAt(i) as? ViewGroup ?: continue
            if (tab.visibility == View.GONE) continue
            if (tab.background != null) tab.background = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && tab.foreground != null) tab.foreground = null
            if (tab is FrameLayout && tab.foreground != null) tab.foreground = null
            if (tab.isPressed) tab.isPressed = false
            tab.setPadding(0, 0, 0, 0)

            val tlp = tab.layoutParams
            if (tlp != null && tlp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                tlp.height = ViewGroup.LayoutParams.MATCH_PARENT
                tab.layoutParams = tlp
            }
            if (barHeight > 0 && (tab.top != 0 || tab.bottom != barHeight)) {
                tab.layout(tab.left, 0, tab.right, barHeight)
            }

            // 对齐 tab 内部承载图标和文字的布局
            for (j in 0 until tab.childCount) {
                val child = tab.getChildAt(j)
                val cId = child.id
                val name = resourceName(child, cId)

                if (name.contains("normal") || child is ConstraintLayout) {
                    if (config.iconOnly && child is ViewGroup) {
                        hideTabLabels(child)
                        child.setPadding(0, 0, 0, 0)
                        if (child is LinearLayout) child.gravity = Gravity.CENTER
                    }
                    // normal_ll 必须是 WRAP_CONTENT，由内容自然决定高度
                    val clp = child.layoutParams
                    if (clp is FrameLayout.LayoutParams) {
                        var nlpChanged = false
                        if (clp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                            clp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                            nlpChanged = true
                        }
                        if (clp.gravity != Gravity.CENTER) {
                            clp.gravity = Gravity.CENTER
                            nlpChanged = true
                        }
                        if (clp.topMargin != 0 || clp.bottomMargin != 0) {
                            clp.topMargin = 0
                            clp.bottomMargin = 0
                            nlpChanged = true
                        }
                        if (nlpChanged) child.layoutParams = clp
                    }
                    // 切页绑定会重新显示文字。pre-draw 中隐藏它以后，旧 measuredHeight
                    // 仍包含文字；必须当帧重测，否则图标先按图文高度上移，下一帧才居中。
                    val remeasured = config.iconOnly && child.isLayoutRequested && barHeight > 0
                    if (remeasured) {
                        val margins = clp as? ViewGroup.MarginLayoutParams
                        val parentWidth = tab.width.takeIf { it > 0 } ?: tab.measuredWidth
                        child.measure(
                            ViewGroup.getChildMeasureSpec(
                                View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                                tab.paddingLeft + tab.paddingRight + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0),
                                clp?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT
                            ),
                            ViewGroup.getChildMeasureSpec(
                                View.MeasureSpec.makeMeasureSpec(barHeight, View.MeasureSpec.EXACTLY),
                                tab.paddingTop + tab.paddingBottom + (margins?.topMargin ?: 0) + (margins?.bottomMargin ?: 0),
                                clp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        )
                    }
                    val ch = child.measuredHeight.takeIf { it > 0 } ?: child.height
                    if (barHeight > 0 && ch > 0) {
                        val targetTop = ((barHeight - ch) / 2f).roundToInt()
                        if (remeasured || child.top != targetTop || child.bottom != targetTop + ch) {
                            child.layout(child.left, targetTop, child.right, targetTop + ch)
                        }
                    }

                    // 优化文字属性，去除字体上下溢出内边距并完全居中
                    if (child is ViewGroup) {
                        for (k in 0 until child.childCount) {
                            val subChild = child.getChildAt(k)
                            if (subChild is TextView) {
                                subChild.includeFontPadding = false
                                subChild.gravity = Gravity.CENTER
                            }
                        }
                    }
                } else if (name.contains("publish") || child.javaClass.simpleName.contains("Publish")) {
                    val plp = child.layoutParams as? FrameLayout.LayoutParams
                    if (plp != null && plp.gravity != Gravity.CENTER) {
                        plp.gravity = Gravity.CENTER
                        child.layoutParams = plp
                    }
                    val ch = child.measuredHeight.takeIf { it > 0 } ?: child.height
                    if (barHeight > 0 && ch > 0) {
                        val targetTop = ((barHeight - ch) / 2f).roundToInt()
                        if (child.top != targetTop || child.bottom != targetTop + ch) {
                            child.layout(child.left, targetTop, child.right, targetTop + ch)
                        }
                    }
                }
            }
        }
    }

    private fun horizontalMarginPx(
        tabHost: ViewGroup,
        container: ViewGroup?,
        config: HostBottomBarFxConfig,
        density: Float
    ): Int {
        val parent = tabHost.parent as? ViewGroup
        val parentWidth = parent?.width?.takeIf { it > 0 } ?: tabHost.resources.displayMetrics.widthPixels
        val availableWidth = parentWidth - (parent?.paddingLeft ?: 0) - (parent?.paddingRight ?: 0)
        val tabCount = container?.let { c ->
            (0 until c.childCount).count { c.getChildAt(it).visibility != View.GONE }
        } ?: 5
        return (config.horizontalMarginDp(availableWidth / density, tabCount) * density).roundToInt()
    }

    /** Only hide labels inside normal tab content; badge and publish overlays remain intact. */
    private fun hideTabLabels(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            val name = resourceName(child, child.id)
            if (name.contains("badge") || name.contains("red") || name.contains("notify")) continue
            if (child is TextView) {
                if (child.visibility != View.GONE) child.visibility = View.GONE
            } else if (child is ViewGroup) {
                hideTabLabels(child)
            }
            if (child !is TextView) {
                val lp = child.layoutParams
                if (lp is ViewGroup.MarginLayoutParams && (lp.topMargin != 0 || lp.bottomMargin != 0)) {
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                    child.layoutParams = lp
                }
                if (lp is ConstraintLayout.LayoutParams &&
                    (lp.topToTop != ConstraintLayout.LayoutParams.PARENT_ID ||
                        lp.bottomToBottom != ConstraintLayout.LayoutParams.PARENT_ID ||
                        lp.topToBottom != ConstraintLayout.LayoutParams.UNSET ||
                        lp.bottomToTop != ConstraintLayout.LayoutParams.UNSET)) {
                    lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
                    lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
                    lp.verticalBias = 0.5f
                    child.layoutParams = lp
                }
            }
        }
    }
    /** 彻底剥离宿主所有官方背景、分割线、并清除按压阴影残余 */
    fun stripAllHostArtifacts(view: View, isRoot: Boolean = true) {
        if (view is HostBottomBarDockLayer || view is HostGlowView) return

        val resName = resourceName(view, view.id)
        // 发布按钮整棵子树交给宿主管理：日夜底色、白色加号、远程皮肤和 SVGA 动画
        // 使用同一套原版换肤逻辑，不能只给普通加号重染或清掉它的 GradientDrawable。
        if (resName == "home_publish_icon" || resName == "publish_root" ||
            view.javaClass.simpleName == "HomeTabPublishView") return

        // 保留宿主 TabHost 的玻璃背景，清除其余子 View 的官方底色。
        if (!isRoot && view.background != null) {
            view.background = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && view.foreground != null) {
            view.foreground = null
        }
        (view as? FrameLayout)?.foreground = null
        if (view.isPressed) {
            view.isPressed = false
        }

        // 彻底移除官方分割线
        if (resName.contains("divider") || (view !is ViewGroup && (view.height in 1..4 || view.layoutParams?.height in 1..4))) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            if (view.alpha != 0f) view.alpha = 0f
            if (view.layoutParams?.height != 0) view.layoutParams?.height = 0
        }

        // 彻底隐藏官方底栏底图与非 icon 纯色遮罩
        if (resName.contains("bg") && view !is ViewGroup) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            if (view.alpha != 0f) view.alpha = 0f
            (view as? android.widget.ImageView)?.setImageDrawable(null)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                stripAllHostArtifacts(view.getChildAt(i), isRoot = false)
            }
        }
    }
}

/**
 * 宿主底栏专属合成容器图层。
 *
 * 结构与模块原生 [com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationBar] 完全一致：
 * - [selectionView]: 独立硬件加速子 View，承载 [SurfaceRole.SELECTED_ITEM] 晶体透镜，
 *   动画全程只更新 GPU RenderNode 的 translationX / scaleX / scaleY，零 CPU 重绘。
 * - [glowView]: 独立子 View，承载 [AdaptiveGlowPolicy] 与 [TouchGlowRenderer]，自适应触控柔光。
 * - [backdrop]: 外壳胶囊的实时透镜采样源装配（模块柔光引擎软件路径的宿主等价物）。
 */
@SuppressLint("ViewConstructor")
internal class HostBottomBarDockLayer(
    context: Context,
    private val config: HostBottomBarFxConfig,
    private val tabHost: ViewGroup,
    private val container: ViewGroup?,
    palette: MonetColors,
    isDark: Boolean,
    private val backdrop: HostBottomBarBackdrop?,
    private val requestSanitization: () -> Unit,
    private val theme: HostChromeTheme = HostChromeTheme(context)
) : FrameLayout(context), HostDockLayer {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val inset = dp(ModernNavigationMotion.INSET_DP.toFloat())
    private val maximumTravel = dp(ModernNavigationMotion.MAX_TRAVEL_DP)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val publishViewId = resources.getIdentifier("home_publish_icon", "id", context.packageName)
    private var materialColors = HostChromeColors(isDark, palette.primary)

    // 1. 独立大胶囊晶体滑块 (硬件加速子 View)
    val selectionView = View(context).apply {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (config.liquidGlass) {
            background = HostLiquidSurfaceDrawable(
                color = palette.surface,
                radius = dp(28f),
                density = density,
                style = ModernMaterialPolicy.surface(SurfaceRole.SELECTED_ITEM, isDark),
                tintOnly = true
            )
        }
    }

    // 2. 独立自适应柔光 (Glow View)
    val glowView = HostGlowView(context, palette.primary, density, maximumTravel)

    // 3. 物理状态与弹簧
    private var selectedIndex = 0
    private var pagerPosition = 0f
    private var displayedPosition = 0f
    private var press = 0f
    private var pressVelocity = 0f
    private var pressGeneration = 0L
    private var reboundGeneration = 0L
    private var pressAnimator: ValueAnimator? = null
    private var reboundAnimator: ValueAnimator? = null
    private var indicatorSettling = false
    private var disposed = false

    // 4. 2D 弹性手势与滑块拖动 (Scrub)
    private val gesture = ModernNavigationGesture()
    private var touchActive = false
    private var ignorePointers = false
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downRawX = 0f
    private var downRawY = 0f
    private var downLocalX = 0f
    private var downLocalY = 0f
    private var initialIndicator = 0f
    private var initialOffsetX = 0f
    private var initialOffsetY = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var offsetVelocityX = 0f
    private var offsetVelocityY = 0f
    private var lastMoveTime = 0L
    private var glowX = 0f
    private var glowY = 0f
    private val screenLoc = IntArray(2)

    private val rtl: Boolean get() = layoutDirection == LAYOUT_DIRECTION_RTL
    private val tabSlots = HostBottomBarTabSlots()
    private val participates: (Int) -> Boolean = { container?.getChildAt(it)?.visibility != View.GONE }
    private val count: Int get() = tabSlots.count.coerceAtLeast(1)
    private val contentWidth: Float get() = (width - inset * 2f).coerceAtLeast(0f)
    private val slotWidth: Float get() = if (count > 0) contentWidth / count else 0f

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (disposed) return@OnPreDrawListener true
        refreshTabSlots()
        refreshMaterial()
        // 宿主会在按压/切页/重建 tab 时重新挂上官方背景并重新布局：这里逐帧只做 O(items) 空判，
        // 命中才请求一次合并后的整树清理；不再每帧全树递归 + 资源名解析（快速切换掉帧的来源）。
        if (hostReappliedArtifacts()) requestSanitization()
        HostBottomBarFxController.alignTabContent(tabHost, container, density, inset.roundToInt(), config)
        // 同步外部切页
        val detected = detectSelectedTab()
        if (!touchActive && !indicatorSettling && detected != selectedIndex) {
            selectedIndex = detected
            // 外部切页只同步透镜；再次 performClick 当前宿主页会触发刷新。
            reboundTo(null, scrubbed = false)
        }
        true
    }

    private fun refreshMaterial() {
        val colors = theme.read()
        val changed = colors != materialColors
        if (config.liquidGlass && (changed || tabHost.background !is HostLiquidSurfaceDrawable)) {
            tabHost.background = HostLiquidSurfaceDrawable(
                color = colors.palette().surface,
                radius = dp(config.heightDp) / 2f,
                density = density,
                style = ModernMaterialPolicy.surface(SurfaceRole.FLOATING, colors.dark),
                backdrop = backdrop
            )
        }
        if (!changed) return
        materialColors = colors
        if (config.liquidGlass) {
            selectionView.background = HostLiquidSurfaceDrawable(
                color = colors.palette().surface,
                radius = dp(28f),
                density = density,
                style = ModernMaterialPolicy.surface(SurfaceRole.SELECTED_ITEM, colors.dark),
                tintOnly = true
            )
        }
        glowView.recolor(colors.accent)
    }

    /** 直属于底栏的 tab 项是否被宿主重新挂上了官方背景/按压态（不带资源查询，O(items)）。 */
    private fun hostReappliedArtifacts(): Boolean {
        val c = container ?: return false
        for (i in 0 until c.childCount) {
            val tab = c.getChildAt(i)
            if (tab.background != null || tab.foreground != null || tab.isPressed) return true
        }
        return false
    }

    init {
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        addView(selectionView)
        addView(glowView)

        // 初次加载对齐选中项
        tabSlots.update(container?.childCount ?: 0, participates)
        val initial = detectSelectedTab()
        selectedIndex = initial
        pagerPosition = initial.toFloat()
        displayedPosition = initial.toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshTabSlots(requestMeasure = false)
        val measuredW = MeasureSpec.getSize(widthMeasureSpec)
        val measuredH = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(measuredW, measuredH)

        val availableW = (measuredW - inset * 2f).coerceAtLeast(0f)
        val itemW = if (count > 0) (availableW / count).roundToInt() else 0
        val itemH = (measuredH - inset * 2f).roundToInt().coerceAtLeast(0)

        selectionView.measure(
            MeasureSpec.makeMeasureSpec(itemW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(itemH, MeasureSpec.EXACTLY)
        )
        glowView.measure(
            MeasureSpec.makeMeasureSpec(measuredW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredH, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val topInset = inset.roundToInt()
        selectionView.layout(topInset, topInset, topInset + selectionView.measuredWidth, topInset + selectionView.measuredHeight)
        glowView.layout(0, 0, width, height)
        applyVisuals()
    }

    /**
     * 120 FPS 纯硬件加速渲染管线：
     * 完全通过 RenderNode 属性 (translationX, scaleX, scaleY) 驱动，不触发任何 CPU 绘制。
     */
    private fun applyVisuals() {
        if (disposed || width <= 0 || height <= 0) return
        var moved = false

        // 1. 底栏 2D 弹性拖拽形变
        val travel = ModernNavigationMotion.travelClampScale(offsetX, offsetY, maximumTravel)
        val x = offsetX * travel
        val y = offsetY * travel
        if (tabHost.translationX != x) {
            tabHost.translationX = x
            moved = true
        }
        if (tabHost.translationY != y) {
            tabHost.translationY = y
            moved = true
        }

        // 2. 指示晶体透镜 RenderNode 位移与呼吸形变 (0 延迟对齐)
        val lensX = ModernNavigationMotion.physicalSlot(displayedPosition, count, rtl) * slotWidth
        val scaleX = ModernNavigationMotion.lensScaleX(press)
        val scaleY = ModernNavigationMotion.lensScaleY(press)

        if (selectionView.translationX != lensX) {
            selectionView.translationX = lensX
            moved = true
        }
        if (selectionView.scaleX != scaleX) {
            selectionView.scaleX = scaleX
            moved = true
        }
        if (selectionView.scaleY != scaleY) {
            selectionView.scaleY = scaleY
            moved = true
        }

        // 3. 柔光动效自适应位置
        if (config.touchGlow) {
            glowView.updateGesture(
                press = press,
                offsetX = offsetX,
                offsetY = offsetY,
                centerX = glowX,
                centerY = glowY,
                barWidth = width,
                barHeight = height,
                viewShiftX = x - initialOffsetX,
                viewShiftY = y - initialOffsetY
            )
        }

        // 4. 实时透镜：只有真的动了才通知重采样（对齐模块 moved 门控的 onVisualMovement）。
        if (moved) backdrop?.onVisualMovement()
    }

    /** 统一触控手势处理 (完全对齐 ModernNavigationBar) */
    fun handleTouch(event: MotionEvent, tabIndex: Int = -1): Boolean {
        if (disposed || !isEnabled || width <= 0) return false
        refreshTabSlots()
        if (tabSlots.count == 0 || tabIndex >= 0 && tabSlots.slotOf(tabIndex) < 0) return false
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            ignorePointers = true
            cancelTouch()
            return true
        }
        if (ignorePointers && event.actionMasked != MotionEvent.ACTION_DOWN) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                ignorePointers = false
            }
            return true
        }

        val rawX = event.rawX
        val rawY = event.rawY
        getLocationOnScreen(screenLoc)
        val localX = rawX - screenLoc[0]
        val localY = rawY - screenLoc[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ignorePointers = false
                touchActive = true
                pointerId = event.getPointerId(0)
                downRawX = rawX
                downRawY = rawY
                downLocalX = localX
                downLocalY = localY
                initialIndicator = displayedPosition
                initialOffsetX = tabHost.translationX
                initialOffsetY = tabHost.translationY
                offsetX = initialOffsetX
                offsetY = initialOffsetY
                lastMoveTime = event.eventTime
                stopRebound()

                val selectedLeft = inset + ModernNavigationMotion.physicalSlot(displayedPosition, count, rtl) * slotWidth
                val inSelection = localX >= selectedLeft && localX <= selectedLeft + slotWidth
                val index = if (tabIndex >= 0) tabSlots.slotOf(tabIndex)
                else ModernNavigationMotion.indexAt(localX, contentWidth, inset, count, rtl)

                gesture.begin(index, inSelection)
                glowX = localX
                glowY = localY
                animatePress(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive || event.getPointerId(0) != pointerId) {
                    cancelTouch()
                    return true
                }
                val dx = rawX - downRawX
                val dy = rawY - downRawY
                if (gesture.move(dx, dy, slop)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (gesture.intent == ModernNavigationIntent.SCRUB) {
                    displayedPosition = ModernNavigationMotion.scrubPosition(initialIndicator, dx, slotWidth, count, rtl)
                }

                // 2D 弹性形变
                val factor = ModernNavigationMotion.displacementScale(dx, dy, maximumTravel, dp(48f))
                val nextX = initialOffsetX + dx * factor
                val nextY = initialOffsetY + dy * factor
                val bounded = ModernNavigationMotion.travelClampScale(nextX, nextY, maximumTravel)
                val elapsed = (event.eventTime - lastMoveTime).coerceAtLeast(1L)
                offsetVelocityX = ((nextX * bounded - offsetX) * 1000f / elapsed).coerceIn(-dp(240f), dp(240f))
                offsetVelocityY = ((nextY * bounded - offsetY) * 1000f / elapsed).coerceIn(-dp(240f), dp(240f))
                offsetX = nextX * bounded
                offsetY = nextY * bounded
                lastMoveTime = event.eventTime

                glowX = downLocalX + dx
                glowY = downLocalY + dy
                applyVisuals()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchActive) return true
                val releaseLocalX = downLocalX + event.rawX - downRawX
                val releaseLocalY = downLocalY + event.rawY - downRawY
                val releaseIndex = if (releaseLocalX in 0f..width.toFloat() && releaseLocalY in 0f..height.toFloat()) {
                    ModernNavigationMotion.indexAt(releaseLocalX, contentWidth, inset, count, rtl)
                } else -1
                val scrubbed = gesture.intent == ModernNavigationIntent.SCRUB
                val target = gesture.finish(false, displayedPosition, releaseIndex, count)

                if (event.eventTime - lastMoveTime > 100L) {
                    offsetVelocityX = 0f
                    offsetVelocityY = 0f
                }
                touchActive = false
                pointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
                animatePress(0f)
                reboundTo(target, scrubbed, userInitiated = true)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTouch()
                return true
            }
        }
        return false
    }

    private fun cancelTouch() {
        gesture.cancel()
        touchActive = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        offsetVelocityX = 0f
        offsetVelocityY = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        animatePress(0f)
        reboundTo(null, scrubbed = false)
    }

    /** 460ms 阻尼简谐物理弹簧 (按压呼吸过渡) */
    private fun animatePress(target: Float, after: (() -> Unit)? = null) {
        pressGeneration++
        pressAnimator?.cancel()
        pressAnimator = null
        if (disposed) return
        // 系统关闭动画 / 未附着：直接落值，不建 ValueAnimator（对齐模块 ModernNavigationBar.animatePress）。
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            press = target
            pressVelocity = 0f
            applyVisuals()
            after?.invoke()
            return
        }

        val token = pressGeneration
        val spring = ModernNavigationSpring(press, target, pressVelocity)
        pressAnimator = ValueAnimator.ofFloat(0f, ModernNavigationMotion.SPRING_DURATION_MS / 1000f).apply {
            duration = ModernNavigationMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token == pressGeneration) {
                    val seconds = it.animatedFraction * ModernNavigationMotion.SPRING_DURATION_MS / 1000f
                    press = spring.value(seconds).coerceIn(0f, 1f)
                    pressVelocity = spring.velocity(seconds)
                    applyVisuals()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != pressGeneration) return
                    pressAnimator = null
                    press = target
                    pressVelocity = 0f
                    applyVisuals()
                    after?.invoke()
                }
            })
            start()
        }
    }

    /** 2D 弹性回弹与指示透镜物理弹簧到位 */
    private fun reboundTo(target: Int?, scrubbed: Boolean, userInitiated: Boolean = false) {
        stopRebound()
        if (disposed) return
        val token = ++reboundGeneration

        val startPos = displayedPosition
        indicatorSettling = true
        // 发布按钮是动作，不是页面；不能把它记成选中页，否则下一帧同步会误点原页面（首页即刷新）。
        selectedIndex = HostBottomBarScrubRelease.activateTarget(target, scrubbed, selectedIndex, ::clickTab)
        pagerPosition = selectedIndex.toFloat()
        val endPos = pagerPosition.coerceIn(0f, (count - 1).toFloat())
        val isClick = target != null && !scrubbed && abs(endPos - startPos) > 0.005f

        // 按压闪光只跟随用户点击：外部同步切页（宿主自己翻页/双次点击同页）不播 0.8→0 双段动画，
        // 快速切换时少一条 920ms 的动画链。
        if (isClick && userInitiated) {
            glowX = inset + (selectedIndex + 0.5f) * slotWidth
            glowY = height / 2f
            animatePress(0.8f) { animatePress(0f) }
        }

        // 系统关闭动画 / 未附着：直接落值（对齐模块 ModernNavigationBar.reboundTo）。
        if (!isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) {
            offsetX = 0f
            offsetY = 0f
            offsetVelocityX = 0f
            offsetVelocityY = 0f
            displayedPosition = endPos
            indicatorSettling = false
            applyVisuals()
            return
        }

        val springX = ModernNavigationSpring(offsetX, 0f, offsetVelocityX)
        val springY = ModernNavigationSpring(offsetY, 0f, offsetVelocityY)
        val indicatorSpring = ModernNavigationSpring(startPos, endPos)

        reboundAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ModernNavigationMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token != reboundGeneration) return@addUpdateListener
                val seconds = it.animatedFraction * ModernNavigationMotion.SPRING_DURATION_MS / 1000f

                offsetX = springX.value(seconds)
                offsetY = springY.value(seconds)
                offsetVelocityX = springX.velocity(seconds)
                offsetVelocityY = springY.velocity(seconds)

                if (indicatorSettling) {
                    displayedPosition = ModernNavigationMotion.position(indicatorSpring.value(seconds), count)
                }
                applyVisuals()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != reboundGeneration) return
                    reboundAnimator = null
                    offsetX = 0f
                    offsetY = 0f
                    offsetVelocityX = 0f
                    offsetVelocityY = 0f
                    displayedPosition = endPos
                    indicatorSettling = false
                    applyVisuals()
                }
            })
            start()
        }
    }

    private fun stopRebound() {
        reboundGeneration++
        reboundAnimator?.cancel()
        reboundAnimator = null
        indicatorSettling = false
    }

    /** 返回是否激活页面；发布按钮只执行宿主动作，滑块随后回到原页面。 */
    private fun clickTab(index: Int): Boolean {
        val c = container ?: return false
        val hostIndex = tabSlots.hostIndex(index)
        if (hostIndex !in 0 until c.childCount) return false
        val tab = c.getChildAt(hostIndex)
        if (!tab.isShown || !tab.isEnabled) return false
        val publish = if (publishViewId != 0) tab.findViewById<View>(publishViewId) else null
        if (publish?.isShown == true) {
            if (!publish.isEnabled) return false
            // HomeTabPublishView 自身实现 OnTouchListener，发布入口只接收完整 DOWN/UP，没有 OnClickListener。
            val listener = publish as? View.OnTouchListener ?: return false
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, publish.width / 2f, publish.height / 2f, 0)
            try {
                listener.onTouch(publish, event)
                event.action = MotionEvent.ACTION_UP
                listener.onTouch(publish, event)
            } finally {
                event.recycle()
            }
            return false
        }
        if (tab.performClick()) return true
        (tab as? ViewGroup)?.let { vg ->
            for (i in 0 until vg.childCount) {
                val child = vg.getChildAt(i)
                if (child.isShown && child.isEnabled && child.performClick()) return true
            }
        }
        return false
    }

    private fun detectSelectedTab(): Int {
        val c = container ?: return selectedIndex
        for (slot in 0 until tabSlots.count) {
            val child = c.getChildAt(tabSlots.hostIndex(slot))
            if (child.isSelected || child.isActivated) return slot
        }
        return selectedIndex.coerceIn(0, count - 1)
    }

    private fun refreshTabSlots(requestMeasure: Boolean = true) {
        if (!tabSlots.update(container?.childCount ?: 0, participates)) return
        // 隐藏/重建可能发生在宿主单项绑定之后，不能延用旧手势和旧槽位宽度。
        selectedIndex = detectSelectedTab()
        pagerPosition = selectedIndex.toFloat()
        resetInteraction()
        selectionView.visibility = if (tabSlots.count == 0) View.INVISIBLE else View.VISIBLE
        if (requestMeasure) requestLayout()
    }

    /** 交互态归零（对齐模块 ModernNavigationBar.resetInteraction）：尺寸变化与视图分离时必须回到静止姿态。 */
    private fun resetInteraction() {
        gesture.cancel()
        touchActive = false
        ignorePointers = false
        pointerId = MotionEvent.INVALID_POINTER_ID
        pressGeneration++
        pressAnimator?.cancel()
        pressAnimator = null
        stopRebound()
        press = 0f
        pressVelocity = 0f
        offsetX = 0f
        offsetY = 0f
        offsetVelocityX = 0f
        offsetVelocityY = 0f
        displayedPosition = pagerPosition
        parent?.requestDisallowInterceptTouchEvent(false)
        applyVisuals()
        // press 已归零 => 本帧起光晕不可见，此刻 reset 不构成可见跳变。
        glowView.resetGestureState()
    }

    fun dispose() {
        if (disposed) return
        resetInteraction()
        disposed = true
        backdrop?.close()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            resetInteraction()
            backdrop?.revalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (disposed) return
        container?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
        backdrop?.attach(tabHost)
    }

    override fun onDetachedFromWindow() {
        container?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
        dispose()
        super.onDetachedFromWindow()
    }
}

/**
 * 宿主触控柔光渲染 View。
 *
 * 直接应用 [AdaptiveGlowPolicy] 与 [TouchGlowRenderer]，几何椭圆光晕随触点速度伸长并于边缘压扁堆积。
 */
@SuppressLint("ViewConstructor")
internal class HostGlowView(
    context: Context,
    highlightColor: Int,
    density: Float,
    maximumTravel: Float
) : View(context) {

    private val radius = 64f * density
    private val renderer = TouchGlowRenderer(highlightColor, radius)
    private var color = highlightColor

    fun recolor(highlightColor: Int) {
        if (color == highlightColor) return
        color = highlightColor
        renderer.recolor(highlightColor)
        invalidate()
    }
    private val config = GlowConfig.create(
        density = density,
        maxTravelPx = maximumTravel,
        travelEpsPx = GlowConfig.TRAVEL_EPS_DP * density,
        velocityRefPxPerSec = GlowConfig.VELOCITY_REF_DP_PER_SEC * density,
        edgeBandPx = GlowConfig.EDGE_BAND_DP * density,
        continuousEdgePile = true
    )
    private val frame = GlowFrame()
    private val state = GlowState()
    private var lastUpdateNanos = 0L
    private var lastOffsetX = 0f
    private var lastOffsetY = 0f
    private val screenLoc = IntArray(2)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun updateGesture(
        press: Float,
        offsetX: Float,
        offsetY: Float,
        centerX: Float,
        centerY: Float,
        barWidth: Int,
        barHeight: Int,
        viewShiftX: Float = 0f,
        viewShiftY: Float = 0f
    ) {
        val now = System.nanoTime()
        val dt = if (lastUpdateNanos == 0L) GlowState.DEFAULT_DT_SECONDS
        else ((now - lastUpdateNanos).coerceAtLeast(0L)) / 1_000_000_000f
        lastUpdateNanos = now
        val elapsed = dt.coerceAtLeast(GlowState.MIN_DT_SECONDS)

        frame.press = press
        frame.offsetX = offsetX
        frame.offsetY = offsetY
        frame.velocityX = (offsetX - lastOffsetX) / elapsed
        frame.velocityY = (offsetY - lastOffsetY) / elapsed
        lastOffsetX = offsetX
        lastOffsetY = offsetY

        val touchX = centerX - viewShiftX
        val touchY = centerY - viewShiftY
        frame.centerX = touchX
        frame.centerY = touchY
        frame.boundsWidth = barWidth.toFloat()
        frame.boundsHeight = barHeight.toFloat()
        frame.cornerRadius = barHeight / 2f

        getLocationOnScreen(screenLoc)
        val metrics = resources.displayMetrics
        frame.pileRoomPx = reachablePileRoomPx(
            screenLoc[0], screenLoc[1],
            screenLoc[0] + width, screenLoc[1] + height,
            metrics.widthPixels, metrics.heightPixels,
            touchX, touchY, barWidth.toFloat(), barHeight.toFloat()
        )
        state.update(frame, dt, radius, 72, config)
        invalidate()
    }

    fun resetGestureState() {
        lastUpdateNanos = 0L
        lastOffsetX = 0f
        lastOffsetY = 0f
        state.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (state.shape.visible) {
            renderer.draw(canvas, state.shape)
        }
    }
}

/**
 * 宿主专属 Liquid Glass 材质 Drawable。
 *
 * 严格按照 [ModernSurfaceDrawable] 架构实现，包含：
 * 1. [ModernMaterialPolicy] 级精确半透明色阶 (SurfaceRole.FLOATING / SELECTED_ITEM)。
 * 2. 实时透镜采样：底栏下方的内容经 [HostBottomBarBackdrop] 模糊/折射后叠在色罩之下（`style.live`）。
 * 3. 0.65dp 亚像素菲涅尔微光描边，上缘高光下缘沉入底色。
 * 4. 硬件加速 Outline 支持。
 */
internal class HostLiquidSurfaceDrawable(
    private val color: Int,
    private val radius: Float,
    private val density: Float,
    private val style: ModernSurfaceStyle,
    private val tintOnly: Boolean = false,
    private val backdrop: HostBottomBarBackdrop? = null
) : Drawable() {

    private val rect = RectF()
    private val edgeRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeWidth = density.coerceAtLeast(1f) * .65f
    }
    private val edgeShader = LinearGradient(
        0f, 0f, 0f, 1f,
        ColorUtils.setAlphaComponent(Color.WHITE, style.upperEdgeAlpha),
        ColorUtils.setAlphaComponent(Color.WHITE, style.lowerEdgeAlpha),
        Shader.TileMode.CLAMP
    )
    private val edgeMatrix = Matrix()
    private var edgeTop = Float.NaN
    private var edgeBottom = Float.NaN
    private var drawingAlpha = 255
    private var tintFrom = style.tintAlpha.toFloat()
    private var tintTo = style.tintAlpha
    private var tintStarted = 0L

    /** 顶栏收岛只改变外壳宽度，不重新测量宿主分类。底栏默认保持 0。 */
    var horizontalInset = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    var verticalInset = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    init {
        edge.shader = edgeShader
    }

    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds)
    }

    override fun draw(canvas: Canvas) = drawSurface(canvas, callback as? View)

    /** 代画时使用可见层的局部坐标，采样完成也会刷新该层；外壳偏移与取景同步。 */
    fun drawForView(canvas: Canvas, reference: View, offsetY: Float = 0f) =
        drawSurface(canvas, reference, offsetY)

    private fun drawSurface(canvas: Canvas, reference: View?, offsetY: Float = 0f) {
        rect.set(bounds)
        rect.inset(horizontalInset, verticalInset)
        rect.offset(0f, offsetY)
        if (rect.isEmpty || !rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite() || !radius.isFinite()
        ) return

        val drawRadius = radius.coerceIn(0f, minOf(rect.width(), rect.height()) * .5f)
        val frameAlpha = FrostedMotionSurfaceAlpha.frameAlpha(color, drawingAlpha)
        if (frameAlpha <= 0) return

        val tintAlpha = legibleTintAlpha(reference)
        val overlayAlpha = tintAlpha * frameAlpha / 255
        // 与模块 ModernSurfaceDrawable.draw 同一套 alpha 数学：实时透镜在下、色罩在上。
        // 宿主没有静态磨砂的 revealFraction 淡入，色罩恒用 overlayAlpha —— 采样未就绪时就是改造前的
        // 半透明色罩观感；若照抄模块的 "sampled ? overlayAlpha : frameAlpha"，找不到采样源时
        // 会退回不透明实心块（那是模块首帧的过渡态，不是稳态）。
        val sampleAlpha = FrostedMotionSurfaceAlpha.sampleAlpha(frameAlpha, overlayAlpha)
        if (!tintOnly && style.live) {
            if (reference != null) backdrop?.draw(canvas, rect, drawRadius, reference, sampleAlpha)
        }
        fill.color = ColorUtils.setAlphaComponent(color, overlayAlpha)
        canvas.drawRoundRect(rect, drawRadius, drawRadius, fill)

        edgeRect.set(rect)
        edgeRect.inset(edge.strokeWidth / 2f, edge.strokeWidth / 2f)
        if (edgeTop != rect.top || edgeBottom != rect.bottom) {
            edgeTop = rect.top
            edgeBottom = rect.bottom
            edgeMatrix.setScale(1f, rect.height().coerceAtLeast(1f))
            edgeMatrix.postTranslate(0f, rect.top)
            edgeShader.setLocalMatrix(edgeMatrix)
        }
        edge.alpha = frameAlpha
        canvas.drawRoundRect(
            edgeRect,
            (drawRadius - edge.strokeWidth / 2f).coerceAtLeast(0f),
            (drawRadius - edge.strokeWidth / 2f).coerceAtLeast(0f),
            edge
        )
    }

    private fun legibleTintAlpha(reference: View?): Int {
        if (tintOnly || !style.live || reference == null || GlowLegibilityPolicy.encodedLuma(color) >= .5f) {
            return style.tintAlpha
        }
        val desired = backdrop?.legibleTintAlpha(reference, rect, color, style.tintAlpha) ?: style.tintAlpha
        val now = SystemClock.uptimeMillis()
        val fraction = ((now - tintStarted) / 240f).coerceIn(0f, 1f)
        val current = tintFrom + (tintTo - tintFrom) * fraction
        // 与引擎同样做迟滞与 240ms 过渡，避免内容取样噪声让色罩闪烁。
        val hysteresis = ((.92f * 255f - style.tintAlpha) * GlowLegibilityPolicy.HYSTERESIS)
        if (desired != tintTo && (abs(desired - tintTo) >= hysteresis || desired == style.tintAlpha || desired >= 234)) {
            tintFrom = current
            tintTo = desired
            tintStarted = now
        }
        if (now - tintStarted < 240L) invalidateSelf()
        return current.roundToInt()
    }

    override fun setAlpha(alpha: Int) {
        drawingAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawingAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
        edge.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getOutline(outline: Outline) {
        if (bounds.isEmpty) outline.setEmpty()
        else outline.setRoundRect(bounds, radius)
    }
}
