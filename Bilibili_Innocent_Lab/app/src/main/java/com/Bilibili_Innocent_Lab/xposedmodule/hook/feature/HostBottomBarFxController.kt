package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.GlowShape
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationMotion
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationSpring
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.TouchGlowRenderer
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** 配置项参数 */
internal data class HostBottomBarFxConfig(
    val liquidGlass: Boolean = true,
    val streamingLight: Boolean = true,
    val touchGlow: Boolean = true
)

/**
 * 哔哩哔哩宿主底栏视觉增强控制器。
 *
 * 采用模块原生 [ModernNavigationBar] 体系的阻尼非线性弹簧、可拖拽滑块（Scrub）与通透 Liquid Glass 渲染，
 * 彻底剥离宿主自带的按压变暗矩形与官方分割线，打造原汁原味的高级悬浮胶囊底栏。
 */
internal object HostBottomBarFxController {

    private val attachedHosts = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    fun attach(tabHost: ViewGroup, config: HostBottomBarFxConfig) {
        if (!config.liquidGlass && !config.streamingLight && !config.touchGlow) return
        tabHost.post {
            attachInternal(tabHost, config)
        }
    }

    private fun attachInternal(tabHost: ViewGroup, config: HostBottomBarFxConfig) {
        if (!attachedHosts.add(tabHost)) return

        val density = tabHost.resources.displayMetrics.density

        // 1. 查找底栏内原有构件
        val context = tabHost.context
        val bgId = context.resources.getIdentifier("tab_background", "id", context.packageName)
        val divId = context.resources.getIdentifier("bottom_tab_divider", "id", context.packageName)
        val containerId = context.resources.getIdentifier("container", "id", context.packageName)

        val container = (if (containerId != 0) tabHost.findViewById<ViewGroup>(containerId) else null)
            ?: (0 until tabHost.childCount).map { tabHost.getChildAt(it) }
                .filterIsInstance<ViewGroup>().firstOrNull()

        val barHeight = (54f * density).toInt()

        // 2. 悬浮胶囊几何形态
        if (config.liquidGlass) {
            val marginH = (14f * density).toInt()
            val marginB = (10f * density).toInt()

            val lp = tabHost.layoutParams
            if (lp != null) {
                lp.height = barHeight
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.leftMargin = marginH
                    lp.rightMargin = marginH
                    lp.bottomMargin = marginB
                }
                if (lp is FrameLayout.LayoutParams) {
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                } else if (lp is CoordinatorLayout.LayoutParams) {
                    lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                tabHost.layoutParams = lp
            }

            tabHost.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width > 0 && view.height > 0) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                    }
                }
            }
            tabHost.clipToOutline = true
            tabHost.elevation = 6f * density
        }

        // 3. 递归清除所有宿主原生的不透明背景、隐藏分割线、官方底图与按压深色背景
        fun stripAllHostBackgrounds() {
            if (!config.liquidGlass) return
            tabHost.background = null

            fun cleanView(v: View) {
                if (v is HostBottomBarDockLayer) return

                v.background = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    v.foreground = null
                }
                (v as? FrameLayout)?.foreground = null
                if (v.isPressed) {
                    v.isPressed = false
                }

                // 彻底移除/隐藏分割线
                if (v.id == divId || (v !is ViewGroup && (v.height in 1..4 || v.layoutParams?.height in 1..4))) {
                    v.visibility = View.GONE
                    v.layoutParams?.height = 0
                    v.alpha = 0f
                }

                // 彻底隐藏官方底栏底图与非 icon 纯色遮罩
                if (v.id == bgId || (v is android.widget.ImageView && v.id != 0 &&
                                    context.resources.getResourceEntryName(v.id).contains("bg"))) {
                    v.visibility = View.GONE
                    v.layoutParams?.height = 0
                    v.alpha = 0f
                    (v as? android.widget.ImageView)?.setImageDrawable(null)
                }

                if (v is ViewGroup) {
                    for (i in 0 until v.childCount) {
                        cleanView(v.getChildAt(i))
                    }
                }
            }
            cleanView(tabHost)

            // 将底栏内部容器居中垂直对齐，消除顶部暴露缝隙
            for (i in 0 until tabHost.childCount) {
                val child = tabHost.getChildAt(i)
                if (child is ViewGroup) {
                    val clp = child.layoutParams
                    if (clp is FrameLayout.LayoutParams) {
                        if (clp.gravity != Gravity.CENTER) {
                            clp.gravity = Gravity.CENTER
                            clp.topMargin = 0
                            clp.bottomMargin = 0
                            child.layoutParams = clp
                        }
                    } else if (clp is ViewGroup.MarginLayoutParams) {
                        if (clp.topMargin != 0 || clp.bottomMargin != 0) {
                            clp.topMargin = 0
                            clp.bottomMargin = 0
                            child.layoutParams = clp
                        }
                    }
                }
            }
        }

        // 4. 注入 Liquid Glass / 非线性可拖拽指示器 / 柔光 绘制层
        val dockLayer = HostBottomBarDockLayer(context, config, container)
        tabHost.addView(
            dockLayer,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight)
        )

        stripAllHostBackgrounds()
        tabHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            stripAllHostBackgrounds()
        }

        // 5. 为每个 tab item 挂接物理滑动手势与非线性触控监听，并同步容器布局
        container?.let { c ->
            c.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                dockLayer.onContainerLayoutChanged()
            }
            fun setupTabListeners() {
                stripAllHostBackgrounds()
                for (i in 0 until c.childCount) {
                    val tabItem = c.getChildAt(i)
                    tabItem.background = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        tabItem.foreground = null
                    }
                    (tabItem as? FrameLayout)?.foreground = null
                    tabItem.setOnTouchListener { v, event ->
                        dockLayer.onTabTouch(v, event, i)
                    }
                }
            }
            setupTabListeners()
            c.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) { setupTabListeners() }
                override fun onChildViewRemoved(parent: View?, child: View?) { setupTabListeners() }
            })
        }
    }
}

/**
 * 宿主底栏专属绘制层。
 *
 * 采用模块原生 [ModernNavigationBar] 同款：
 * 1. 纯正通透 Liquid Glass 材质（浅色 28%-43% 柔光透雾、深色 31%-50% 曜石微透 + 双圈菲涅尔高光与弧形穹顶天光）。
 * 2. 物理阻尼谐振弹簧（[ModernNavigationSpring]）非线性动画。
 * 3. 完整的水平拖动滑块（Scrub）与指尖吸附交互。
 * 4. 触控自适应柔光（[TouchGlowRenderer]）。
 * 5. 严格杜绝宿主官方按压变暗矩形与启动时胶囊未对齐问题。
 */
@SuppressLint("ViewConstructor")
internal class HostBottomBarDockLayer(
    context: Context,
    private val config: HostBottomBarFxConfig,
    private val container: ViewGroup?
) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val inset = dp(ModernNavigationMotion.INSET_DP.toFloat())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // 几何与轮廓
    private val dockBounds = RectF()
    private var dockRadius = 0f
    private val dockPath = Path()

    // Liquid Glass 材质画笔
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val topSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * density
    }
    private val edgeRect = RectF()

    // 物理指示滑块
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val currentPillRect = RectF()

    // 状态与非线性物理动画
    private var activeTabIndex = 0
    private var displayedPosition = 0f
    private var pressFraction = 0f
    private var pressVelocity = 0f
    private var pressGeneration = 0L
    private var reboundGeneration = 0L
    private var pressAnimator: ValueAnimator? = null
    private var reboundAnimator: ValueAnimator? = null
    private var indicatorSettling = false

    // 拖动手势交互
    private var touchActive = false
    private var isScrubbing = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var initialIndicator = 0f
    private val myLoc = IntArray(2)

    // 柔光动效 (Touch Glow)
    private val glowRadius = 52f * density
    private val touchGlowRenderer = TouchGlowRenderer(
        color = 0xFFFB7299.toInt(),
        radiusPx = glowRadius
    )
    private val glowShape = GlowShape()
    private var glowX = 0f
    private var glowY = 0f

    private val count: Int
        get() = container?.childCount?.coerceIn(1, 10) ?: 4

    private val preDrawListener = android.view.ViewTreeObserver.OnPreDrawListener {
        val detected = detectSelectedTab()
        if (!touchActive && detected != activeTabIndex) {
            activeTabIndex = detected
            reboundTo(detected.toFloat())
        }
        true
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun isDarkTheme(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun onContainerLayoutChanged() {
        updatePillFromPosition()
    }

    /** 抑制宿主原生的灰色按压状态 */
    private fun suppressPressState(v: View) {
        if (v.isPressed) v.isPressed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && v.foreground != null) {
            v.foreground = null
        }
        (v as? FrameLayout)?.foreground = null
        if (v.background != null) v.background = null
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                suppressPressState(v.getChildAt(i))
            }
        }
    }

    /** 触发目标 Tab 的原生点击逻辑 */
    private fun clickTab(index: Int) {
        val c = container ?: return
        if (index !in 0 until c.childCount) return
        val tab = c.getChildAt(index)
        if (!tab.performClick()) {
            (tab as? ViewGroup)?.let { vg ->
                for (i in 0 until vg.childCount) {
                    if (vg.getChildAt(i).performClick()) break
                }
            }
        }
    }

    /** 处理 Tab 项的触控事件（支持轻触点击与水平拖拽滑块） */
    fun onTabTouch(tabItem: View, event: MotionEvent, tabIndex: Int): Boolean {
        suppressPressState(tabItem)
        val rawX = event.rawX
        val rawY = event.rawY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                isScrubbing = false
                downRawX = rawX
                downRawY = rawY
                initialIndicator = displayedPosition

                getLocationInWindow(myLoc)
                glowX = rawX - myLoc[0]
                glowY = rawY - myLoc[1]
                updateGlowPosition()

                animatePress(1f)
                stopRebound()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return false
                suppressPressState(tabItem)

                val dx = rawX - downRawX
                val dy = rawY - downRawY

                if (!isScrubbing && abs(dx) > slop && abs(dx) > abs(dy) * 1.15f) {
                    isScrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                if (isScrubbing) {
                    val availableW = (width - inset * 2f).coerceAtLeast(1f)
                    val slotW = availableW / count.coerceAtLeast(1)
                    val targetPos = (initialIndicator + dx / slotW).coerceIn(0f, (count - 1).toFloat())
                    displayedPosition = targetPos
                    updatePillFromPosition()
                }

                getLocationInWindow(myLoc)
                glowX = rawX - myLoc[0]
                glowY = rawY - myLoc[1]
                updateGlowPosition()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchActive) return false
                suppressPressState(tabItem)
                touchActive = false
                animatePress(0f)
                parent?.requestDisallowInterceptTouchEvent(false)

                if (isScrubbing) {
                    isScrubbing = false
                    val target = displayedPosition.roundToInt().coerceIn(0, count - 1)
                    reboundTo(target.toFloat())
                    if (target != activeTabIndex) {
                        activeTabIndex = target
                        clickTab(target)
                    }
                } else {
                    reboundTo(tabIndex.toFloat())
                    if (tabIndex != activeTabIndex) {
                        activeTabIndex = tabIndex
                    }
                    clickTab(tabIndex)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                isScrubbing = false
                suppressPressState(tabItem)
                animatePress(0f)
                reboundTo(activeTabIndex.toFloat())
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun updateGlowPosition() {
        if (!config.touchGlow) return
        glowShape.centerX = glowX
        glowShape.centerY = glowY
        glowShape.radiusX = glowRadius
        glowShape.radiusY = glowRadius
        glowShape.alphaByte = (pressFraction * 145f).toInt().coerceIn(0, 255)
        glowShape.visible = glowShape.alphaByte > 0
        postInvalidateOnAnimation()
    }

    /** 阻尼弹簧按压非线性形变 */
    private fun animatePress(target: Float) {
        pressGeneration++
        pressAnimator?.cancel()
        pressAnimator = null

        val token = pressGeneration
        val spring = ModernNavigationSpring(pressFraction, target, pressVelocity)
        pressAnimator = ValueAnimator.ofFloat(0f, ModernNavigationMotion.SPRING_DURATION_MS / 1000f).apply {
            duration = ModernNavigationMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token == pressGeneration) {
                    val seconds = it.animatedFraction * ModernNavigationMotion.SPRING_DURATION_MS / 1000f
                    pressFraction = spring.value(seconds).coerceIn(0f, 1f)
                    pressVelocity = spring.velocity(seconds)
                    updateGlowPosition()
                    updatePillFromPosition()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != pressGeneration) return
                    pressAnimator = null
                    pressFraction = target
                    pressVelocity = 0f
                    updateGlowPosition()
                    updatePillFromPosition()
                }
            })
            start()
        }
    }

    /** 阻尼谐振回弹到指定 Tab 位置（460ms 物理自然回弹） */
    fun reboundTo(target: Float) {
        stopRebound()
        val token = ++reboundGeneration
        val indicator = ModernNavigationSpring(displayedPosition, target)
        indicatorSettling = true

        reboundAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ModernNavigationMotion.SPRING_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (token == reboundGeneration) {
                    val seconds = it.animatedFraction * ModernNavigationMotion.SPRING_DURATION_MS / 1000f
                    if (indicatorSettling) {
                        displayedPosition = indicator.value(seconds).coerceIn(0f, (count - 1).toFloat())
                    }
                    updatePillFromPosition()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != reboundGeneration) return
                    reboundAnimator = null
                    if (indicatorSettling) {
                        displayedPosition = target.coerceIn(0f, (count - 1).toFloat())
                    }
                    indicatorSettling = false
                    updatePillFromPosition()
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

    /** 精准计算指定 Tab 索引在 dockLayer 画布中的水平几何中心（消除坐标延迟） */
    private fun getTabCenter(index: Int): Float {
        val c = container
        if (c != null && index in 0 until c.childCount) {
            val child = c.getChildAt(index)
            if (child.width > 0) {
                val containerOffset = c.left - left
                return containerOffset + child.left + child.width / 2f
            }
        }
        val availableW = (width - inset * 2f).coerceAtLeast(1f)
        val slotW = availableW / count.coerceAtLeast(1)
        return inset + (index + 0.5f) * slotW
    }

    /** 根据连续浮点位置平滑插值获取胶囊中心点 */
    private fun getInterpolatedCenter(position: Float): Float {
        val maxIndex = (count - 1).coerceAtLeast(0)
        val clamped = position.coerceIn(0f, maxIndex.toFloat())
        val index = clamped.toInt()
        val frac = clamped - index
        val c1 = getTabCenter(index)
        if (frac <= 0.0001f || index >= maxIndex) return c1
        val c2 = getTabCenter(index + 1)
        return c1 + (c2 - c1) * frac
    }

    /** 根据当前物理连续位置 [displayedPosition] 和按压缩放因子计算滑块尺寸与位置 */
    private fun updatePillFromPosition() {
        if (width <= 0 || height <= 0) return
        val c = container
        val tabW = if (c != null && activeTabIndex in 0 until c.childCount && c.getChildAt(activeTabIndex).width > 0) {
            c.getChildAt(activeTabIndex).width.toFloat()
        } else {
            (width - inset * 2f).coerceAtLeast(1f) / count.coerceAtLeast(1)
        }
        val pillW = minOf(tabW * 0.78f, 62f * density)
        val pillH = minOf((height - inset * 2f) * 0.80f, 40f * density)

        val sx = ModernNavigationMotion.lensScaleX(pressFraction)
        val sy = ModernNavigationMotion.lensScaleY(pressFraction)
        val scaledW = pillW * sx
        val scaledH = pillH * sy

        val cx = getInterpolatedCenter(displayedPosition)
        val cy = height / 2f

        currentPillRect.set(cx - scaledW / 2f, cy - scaledH / 2f, cx + scaledW / 2f, cy + scaledH / 2f)
        postInvalidateOnAnimation()
    }

    private fun detectSelectedTab(): Int {
        val c = container ?: return activeTabIndex
        for (i in 0 until c.childCount) {
            val child = c.getChildAt(i)
            if (child.isSelected || child.isActivated) return i
        }
        return activeTabIndex
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        dockBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        dockRadius = h / 2f

        dockPath.reset()
        dockPath.addRoundRect(dockBounds, dockRadius, dockRadius, Path.Direction.CW)

        val dark = isDarkTheme()

        // 菲涅尔双圈反射微光描边（顶部镜面高光沉入底边）
        edgePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                if (dark) 0x75FFFFFF else 0x98FFFFFF.toInt(),
                if (dark) 0x20FFFFFF else 0x30FFFFFF,
                0x0AFFFFFF
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )

        // 弧形穹顶天光反射，赋予真实 3D 凸面光学玻璃质感
        topSheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.42f,
            intArrayOf(if (dark) 0x18FFFFFF else 0x30FFFFFF, 0x00FFFFFF),
            null,
            Shader.TileMode.CLAMP
        )

        // 尺寸初次确定时立即精确对齐当前 Tab，杜绝冷启动偏离
        val detected = detectSelectedTab()
        activeTabIndex = detected
        displayedPosition = detected.toFloat()
        updatePillFromPosition()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        if (currentPillRect.isEmpty) {
            updatePillFromPosition()
        }

        val dark = isDarkTheme()

        // 1. 纯正通透 Liquid Glass 磨砂玻璃基底与菲涅尔双圈微光描边
        if (config.liquidGlass) {
            // 通透高阶玻璃轻染色（浅色 28%-43% 柔光透雾、深色 31%-50% 曜石微透）
            val topColor = if (dark) 0x80252830.toInt() else 0x6EFFFFFF
            val bottomColor = if (dark) 0x5014161C else 0x2EE2E6ED
            glassPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                topColor, bottomColor, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(dockBounds, dockRadius, dockRadius, glassPaint)

            // 弧形穹顶天光
            canvas.save()
            canvas.clipPath(dockPath)
            canvas.drawRect(dockBounds.left, dockBounds.top, dockBounds.right, dockBounds.top + height * 0.42f, topSheenPaint)
            canvas.restore()

            // 菲涅尔双圈反射微光描边（内缩半个线宽）
            edgeRect.set(dockBounds)
            val halfEdge = edgePaint.strokeWidth / 2f
            edgeRect.inset(halfEdge, halfEdge)
            canvas.drawRoundRect(
                edgeRect,
                (dockRadius - halfEdge).coerceAtLeast(0f),
                (dockRadius - halfEdge).coerceAtLeast(0f),
                edgePaint
            )

            // 物理指示滑块（非线性阻尼谐振 + 呼吸形变 Liquid Lens）
            if (!currentPillRect.isEmpty) {
                val pillCorner = currentPillRect.height() / 2f
                pillPaint.color = if (dark) 0x28FFFFFF else 0x26FB7299
                canvas.drawRoundRect(currentPillRect, pillCorner, pillCorner, pillPaint)
                pillBorderPaint.color = if (dark) 0x45FFFFFF else 0x42FB7299
                canvas.drawRoundRect(currentPillRect, pillCorner, pillCorner, pillBorderPaint)
            }
        }

        // 2. 柔光动效 (Touch Glow) - 裁切在胶囊内
        if (config.touchGlow && glowShape.visible) {
            canvas.save()
            canvas.clipPath(dockPath)
            touchGlowRenderer.draw(canvas, glowShape)
            canvas.restore()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        container?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        container?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
        pressAnimator?.cancel()
        reboundAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
