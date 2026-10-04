package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.GlowShape
import com.Bilibili_Innocent_Lab.xposedmodule.ui.widget.TouchGlowRenderer
import java.util.Collections
import java.util.WeakHashMap

/** 配置项参数 */
internal data class HostBottomBarFxConfig(
    val liquidGlass: Boolean = true,
    val streamingLight: Boolean = true,
    val touchGlow: Boolean = true
)

/**
 * 哔哩哔哩宿主底栏视觉增强控制器。
 *
 * 将哔哩哔哩宿主 APP 的底栏改造为 Liquid Glass 悬浮胶囊样式，
 * 并注入流光动效（沿胶囊边缘循环流动的呼吸光斑）与柔光动效（触控自适应光晕）。
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

        val bgView = if (bgId != 0) tabHost.findViewById<View>(bgId) else null
        val divView = if (divId != 0) tabHost.findViewById<View>(divId) else null
        val container = (if (containerId != 0) tabHost.findViewById<ViewGroup>(containerId) else null)
            ?: (0 until tabHost.childCount).map { tabHost.getChildAt(it) }
                .filterIsInstance<ViewGroup>().firstOrNull()

        val barHeight = (54f * density).toInt()

        // 2. 悬浮胶囊几何形态（仅在开启 Liquid Glass 时使底栏悬浮与圆角裁剪）
        if (config.liquidGlass) {
            val marginH = (14f * density).toInt()
            val marginB = (10f * density).toInt()

            val lp = tabHost.layoutParams
            if (lp != null) {
                // 固定底栏胶囊高度，防止 MATCH_PARENT 展开全屏
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

            // 隐藏宿主不透明纯色背景与分割线
            bgView?.visibility = View.GONE
            divView?.visibility = View.GONE
            for (i in 0 until tabHost.childCount) {
                val child = tabHost.getChildAt(i)
                if (child is android.widget.ImageView) child.visibility = View.GONE
                if (child != container && child.height in 1..4) child.visibility = View.GONE
                // 调整内部容器居中对齐
                if (child is ViewGroup && child !== container) {
                    val clp = child.layoutParams as? ViewGroup.MarginLayoutParams
                    if (clp != null && clp.topMargin != 0) {
                        clp.topMargin = 0
                        child.layoutParams = clp
                    }
                }
            }
            tabHost.background = null
            container?.background = null
        }

        // 3. 注入 Liquid Glass / 流光 / 柔光 绘制层
        val dockLayer = HostBottomBarDockLayer(context, config, container)
        tabHost.addView(
            dockLayer,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight)
        )

        // 4. 为每个 tab item 挂接非拦截触控监听，为柔光与流光提供动效反馈
        container?.let { c ->
            fun setupTabListeners() {
                for (i in 0 until c.childCount) {
                    val tabItem = c.getChildAt(i)
                    tabItem.setOnTouchListener { _, event ->
                        dockLayer.dispatchHostTouch(tabItem, event, i)
                        false // 严禁拦截，保证宿主点击事件 100% 正常响应
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
 * 负责 Liquid Glass 晶莹磨砂背景、高光边缘、选中项胶囊指示器、边缘流光、触控柔光。
 */
@SuppressLint("ViewConstructor")
internal class HostBottomBarDockLayer(
    context: Context,
    private val config: HostBottomBarFxConfig,
    private val container: ViewGroup?
) : View(context) {

    private val density = resources.displayMetrics.density
    private val easeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    // 几何与轮廓
    private val dockBounds = RectF()
    private var dockRadius = 0f
    private val dockPath = Path()

    // 流光边缘内缩路径，确保流光光晕完全在圆角胶囊内显现
    private val streamBounds = RectF()
    private val streamPath = Path()
    private val pathMeasure = PathMeasure()
    private var pathLength = 0f

    // Liquid Glass 材质画笔
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
    }
    private val edgeRect = RectF()

    // 选中指示胶囊
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val currentPillRect = RectF()
    private val targetPillRect = RectF()
    private var pillAnimator: ValueAnimator? = null
    private var activeTabIndex = 0
    private val viewLoc = IntArray(2)
    private val myLoc = IntArray(2)

    // 流光动效 (Streaming Light)
    private val streamSegmentPath = Path()
    private val headPos = FloatArray(2)
    private val headTan = FloatArray(2)
    private var streamProgress = 0f
    private var pulseBoost = 0f
    private var streamingAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFB7299.toInt() // Bilibili 粉色流光辉光
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt() // 亮核白光
    }
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFFFFFFF.toInt()
    }

    // 柔光动效 (Touch Glow)
    private val glowRadius = 52f * density
    private val touchGlowRenderer = TouchGlowRenderer(
        color = 0xFFFB7299.toInt(),
        radiusPx = glowRadius
    )
    private val glowShape = GlowShape()
    private var pressFraction = 0f
    private var pressAnimator: ValueAnimator? = null
    private var touchX = 0f
    private var touchY = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        if (config.streamingLight) {
            setupStreamingAnimation()
        }
    }

    private fun isDarkTheme(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun setupStreamingAnimation() {
        streamingAnimator?.cancel()
        streamingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                streamProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
    }

    /** 交互触控或切换页面时，流光产生加速并泛光脉冲 */
    fun pulseStreamingLight() {
        if (!config.streamingLight) return
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 650L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pulseBoost = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
        pulseAnimator?.start()
    }

    /** 响应宿主 Tab 的非拦截触控事件 */
    fun dispatchHostTouch(tabItem: View, event: MotionEvent, tabIndex: Int) {
        tabItem.getLocationInWindow(viewLoc)
        getLocationInWindow(myLoc)
        touchX = (viewLoc[0] - myLoc[0]) + event.x
        touchY = (viewLoc[1] - myLoc[1]) + event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animatePress(1f)
                pulseStreamingLight()
                onTabSelected(tabIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                updateGlowPosition()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animatePress(0f)
            }
        }
    }

    private fun updateGlowPosition() {
        if (!config.touchGlow) return
        glowShape.centerX = touchX
        glowShape.centerY = touchY
        glowShape.radiusX = glowRadius
        glowShape.radiusY = glowRadius
        glowShape.alphaByte = (pressFraction * 160f).toInt().coerceIn(0, 255)
        glowShape.visible = glowShape.alphaByte > 0
        postInvalidateOnAnimation()
    }

    private fun animatePress(target: Float) {
        if (!config.touchGlow) return
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressFraction, target).apply {
            duration = if (target > 0f) 120L else 240L
            interpolator = easeInterpolator
            addUpdateListener {
                pressFraction = it.animatedValue as Float
                updateGlowPosition()
            }
        }
        pressAnimator?.start()
    }

    /** 选中项切换动效 */
    fun onTabSelected(index: Int) {
        if (index == activeTabIndex && currentPillRect.width() > 0) return
        activeTabIndex = index
        updatePillTarget(animated = true)
    }

    private fun detectSelectedTab(): Int {
        val c = container ?: return activeTabIndex
        for (i in 0 until c.childCount) {
            val child = c.getChildAt(i)
            if (child.isSelected || child.isActivated) return i
        }
        return activeTabIndex
    }

    private fun updatePillTarget(animated: Boolean) {
        val c = container ?: return
        if (activeTabIndex !in 0 until c.childCount) return
        val tab = c.getChildAt(activeTabIndex)
        if (tab.width <= 0 || tab.height <= 0) {
            post { updatePillTarget(animated) }
            return
        }

        tab.getLocationInWindow(viewLoc)
        getLocationInWindow(myLoc)
        val tabLeftInDock = (viewLoc[0] - myLoc[0]).toFloat()

        val pillW = minOf(tab.width.toFloat() * 0.70f, 64f * density)
        val pillH = minOf(height.toFloat() * 0.78f, 42f * density)
        val cx = tabLeftInDock + tab.width / 2f
        val cy = height / 2f

        targetPillRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f)

        if (!animated || currentPillRect.isEmpty) {
            currentPillRect.set(targetPillRect)
            postInvalidateOnAnimation()
        } else {
            val startLeft = currentPillRect.left
            val startTop = currentPillRect.top
            val startRight = currentPillRect.right
            val startBottom = currentPillRect.bottom

            pillAnimator?.cancel()
            pillAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240L
                interpolator = easeInterpolator
                addUpdateListener {
                    val f = it.animatedValue as Float
                    currentPillRect.set(
                        startLeft + (targetPillRect.left - startLeft) * f,
                        startTop + (targetPillRect.top - startTop) * f,
                        startRight + (targetPillRect.right - startRight) * f,
                        startBottom + (targetPillRect.bottom - startBottom) * f
                    )
                    postInvalidateOnAnimation()
                }
            }
            pillAnimator?.start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        dockBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        dockRadius = h / 2f

        dockPath.reset()
        dockPath.addRoundRect(dockBounds, dockRadius, dockRadius, Path.Direction.CW)

        // 流光路径内缩 1.5dp，确保光斑完全在胶囊内部边缘滑行
        val inset = 1.5f * density
        streamBounds.set(dockBounds.left + inset, dockBounds.top + inset, dockBounds.right - inset, dockBounds.bottom - inset)
        val streamRadius = (dockRadius - inset).coerceAtLeast(0f)
        streamPath.reset()
        streamPath.addRoundRect(streamBounds, streamRadius, streamRadius, Path.Direction.CW)
        pathMeasure.setPath(streamPath, true)
        pathLength = pathMeasure.length

        val dark = isDarkTheme()
        edgePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                if (dark) 0x65FFFFFF else 0x95FFFFFF.toInt(),
                if (dark) 0x15FFFFFF else 0x22FFFFFF,
                0x00FFFFFF
            ),
            floatArrayOf(0f, 0.28f, 1f),
            Shader.TileMode.CLAMP
        )

        updatePillTarget(animated = false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        // 同步宿主选中项变化
        val detected = detectSelectedTab()
        if (detected != activeTabIndex) {
            onTabSelected(detected)
        }

        val dark = isDarkTheme()

        // 1. Liquid Glass 玻璃基底与顶部菲涅尔高光边缘
        if (config.liquidGlass) {
            glassPaint.color = if (dark) 0xCC1A1C22.toInt() else 0xDCF6F7F9.toInt()
            canvas.drawRoundRect(dockBounds, dockRadius, dockRadius, glassPaint)

            // 菲涅尔双圈反射微光描边（内缩半个线宽）
            edgeRect.set(dockBounds)
            val halfEdge = edgePaint.strokeWidth / 2f
            edgeRect.inset(halfEdge, halfEdge)
            canvas.drawRoundRect(edgeRect, (dockRadius - halfEdge).coerceAtLeast(0f), (dockRadius - halfEdge).coerceAtLeast(0f), edgePaint)

            // 当前激活项的高光指示胶囊
            if (!currentPillRect.isEmpty) {
                val pillCorner = currentPillRect.height() / 2f
                pillPaint.color = if (dark) 0x28FFFFFF else 0x22FB7299
                canvas.drawRoundRect(currentPillRect, pillCorner, pillCorner, pillPaint)
                pillBorderPaint.color = if (dark) 0x40FFFFFF else 0x38FB7299
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

        // 3. 流光动效 (Streaming Light)
        if (config.streamingLight && pathLength > 0f) {
            drawStreamingLight(canvas)
        }
    }

    private fun drawStreamingLight(canvas: Canvas) {
        // 光斑覆盖约 26% 周长
        val beamLength = pathLength * 0.26f
        val headDist = (streamProgress * pathLength) % pathLength
        val tailDist = (headDist - beamLength + pathLength) % pathLength

        streamSegmentPath.reset()
        if (tailDist < headDist) {
            pathMeasure.getSegment(tailDist, headDist, streamSegmentPath, true)
        } else {
            pathMeasure.getSegment(tailDist, pathLength, streamSegmentPath, true)
            pathMeasure.getSegment(0f, headDist, streamSegmentPath, false)
        }

        // 外层柔和流光晕
        val haloWidth = (4.2f + 1.8f * pulseBoost) * density
        haloPaint.strokeWidth = haloWidth
        haloPaint.alpha = ((0.55f + 0.40f * pulseBoost) * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(streamSegmentPath, haloPaint)

        // 内层高能白光核心
        corePaint.strokeWidth = 1.6f * density
        corePaint.alpha = ((0.80f + 0.20f * pulseBoost) * 255f).toInt().coerceIn(0, 255)
        canvas.drawPath(streamSegmentPath, corePaint)

        // 头部流星亮核点
        if (pathMeasure.getPosTan(headDist, headPos, headTan)) {
            val sparkleRadius = (2.2f + 1.2f * pulseBoost) * density
            sparklePaint.alpha = ((0.88f + 0.12f * pulseBoost) * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(headPos[0], headPos[1], sparkleRadius, sparklePaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (config.streamingLight && isShown) {
            streamingAnimator?.start()
        }
    }

    override fun onDetachedFromWindow() {
        streamingAnimator?.cancel()
        pulseAnimator?.cancel()
        pressAnimator?.cancel()
        pillAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (config.streamingLight) {
            if (visibility == VISIBLE) {
                if (streamingAnimator?.isRunning != true) {
                    streamingAnimator?.start()
                }
            } else {
                streamingAnimator?.cancel()
            }
        }
    }
}
