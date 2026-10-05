package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** 保留宿主顶栏的父子关系、尺寸和分类位置，只裁剪外壳并分流当前触摸序列。 */
@SuppressLint("ClickableViewAccessibility")
internal class HostTopIslandBinding private constructor(
    private val dock: ViewGroup,
    private val tabs: ViewGroup?,
    private val density: Float,
    private val glow: HostGlowView?,
    private val backdrop: HostBottomBarBackdrop?
) {
    private var progress = 0f
    private var collapsed = false
    private var animator: ValueAnimator? = null
    private val childAlphas = WeakHashMap<View, Float>()
    private val location = IntArray(2)
    private var gesture: HostTopIslandGesture? = null
    private var downX = 0f
    private var downY = 0f
    private var bubbleTouch = false
    private var consumed = false
    private val touchSlop = ViewConfiguration.get(dock.context).scaledTouchSlop.toFloat()
    private var observer: ViewTreeObserver? = null
    private var lastSurface: Drawable? = null
    private val preDraw = ViewTreeObserver.OnPreDrawListener { sync(); true }

    private val input = object : View(dock.context), HostDockLayer {
        override fun verifyDrawable(who: Drawable): Boolean = who === dock.background || super.verifyDrawable(who)

        override fun onDraw(canvas: Canvas) {
            // 宿主栏在收起后不可见，让剩余区域的触摸真正落到视频内容。
            if (collapsed && animator == null) dock.background?.draw(canvas)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = handleTouch(event)

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (!collapsed) return
            info.className = "android.widget.Button"
            val inset = horizontalInset().roundToInt()
            @Suppress("DEPRECATION")
            info.setBoundsInParent(Rect(x.roundToInt() + inset, y.roundToInt(),
                x.roundToInt() + width - inset, y.roundToInt() + height))
            getLocationOnScreen(location)
            info.setBoundsInScreen(Rect(location[0] + inset, location[1],
                location[0] + width - inset, location[1] + height))
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (collapsed) animateTo(false)
            return true
        }
    }

    companion object {
        fun attach(
            dock: ViewGroup,
            tabs: ViewGroup?,
            density: Float,
            glow: HostGlowView?,
            backdrop: HostBottomBarBackdrop?
        ): HostTopIslandBinding? {
            val parent = dock.parent as? ViewGroup ?: return null
            return runCatching {
                HostTopIslandBinding(dock, tabs, density, glow, backdrop).also { it.install(parent) }
            }.onFailure { ModernHookLog.info("[BIL] 顶栏收起手势装配失败: $it") }.getOrNull()
        }
    }

    private fun install(parent: ViewGroup) {
        // 让宿主生成 LayoutParams，避免模块和宿主的 ConstraintLayout ClassLoader 不同。
        parent.addView(input)
        val lp = input.layoutParams
        lp.width = dock.width.coerceAtLeast(1)
        lp.height = dock.height.coerceAtLeast(1)
        HostTopBarFxController.setConstraintInt(lp, "topToTop", 0)
        HostTopBarFxController.setConstraintInt(lp, "startToStart", 0)
        input.layoutParams = lp
        input.translationZ = 11f * density
        input.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        input.contentDescription = "展开顶栏"
        dock.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val inset = horizontalInset().roundToInt()
                outline.setRoundRect(inset, 0, view.width - inset, view.height, view.height / 2f)
            }
        }
        input.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = observe()
            override fun onViewDetachedFromWindow(v: View) {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
                observer = null
                animator?.cancel()
                animator = null
                applyProgress(if (collapsed) 1f else 0f)
                gesture = null
                consumed = false
                glow?.resetGestureState()
            }
        })
        observe()
        sync()
    }

    private fun observe() {
        if (observer != null) return
        input.viewTreeObserver.takeIf { it.isAlive }?.let {
            it.addOnPreDrawListener(preDraw)
            observer = it
        }
    }

    /** 宿主 AppBar 位移、横竖屏和主题更新后，输入层与外壳始终同步。 */
    fun sync() {
        val lp = input.layoutParams
        val surface = dock.background
        val geometryChanged = lp.width != dock.width || lp.height != dock.height
        if (dock.width > 0 && dock.height > 0 && (lp.width != dock.width || lp.height != dock.height)) {
            lp.width = dock.width
            lp.height = dock.height
            input.layoutParams = lp
        }
        input.translationX = dock.x - input.left
        input.translationY = dock.y - input.top
        input.visibility = if (dock.visibility == View.GONE) View.GONE else View.VISIBLE
        (dock.background as? HostLiquidSurfaceDrawable)?.horizontalInset = horizontalInset()
        if (progress > 0f) fadeChildren()
        if (collapsed && animator == null) dock.visibility = View.INVISIBLE
        surface?.callback = if (collapsed && animator == null) input else dock
        if (geometryChanged || lastSurface !== surface) {
            lastSurface = surface
            dock.invalidateOutline()
            input.invalidate()
        }
    }

    private fun horizontalInset() = ((dock.width - dock.height).coerceAtLeast(0) / 2f) * progress

    private fun fadeChildren() {
        val alpha = (1f - progress) * (1f - progress)
        for (index in 0 until dock.childCount) {
            val child = dock.getChildAt(index)
            val original = childAlphas.getOrPut(child) { child.alpha }
            child.alpha = original * alpha
        }
    }

    private fun applyProgress(value: Float) {
        progress = value
        if (value == 0f) {
            childAlphas.forEach { (child, alpha) -> child.alpha = alpha }
            childAlphas.clear()
        } else {
            fadeChildren()
        }
        sync()
        dock.invalidateOutline()
        dock.invalidate()
        input.invalidate()
        backdrop?.onVisualMovement()
    }

    private fun animateTo(compact: Boolean) {
        animator?.cancel()
        collapsed = compact
        dock.visibility = View.VISIBLE
        input.importantForAccessibility = if (compact) View.IMPORTANT_FOR_ACCESSIBILITY_YES
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        input.isClickable = compact
        val end = if (compact) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            animator = null
            applyProgress(end)
            return
        }
        val animation = ValueAnimator.ofFloat(progress, end)
        animator = animation
        animation.duration = 280L
        animation.interpolator = DecelerateInterpolator(2f)
        animation.addUpdateListener { applyProgress(it.animatedValue as Float) }
        animation.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator !== animation) return
                animator = null
                applyProgress(end)
            }
        })
        animation.start()
        ModernHookLog.info("[BIL] 顶栏灵动岛: ${if (compact) "collapsed" else "expanded"}")
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!dock.isShown && !collapsed) return false
            val inset = horizontalInset()
            // 胶囊/圆球外的区域直接放行，不保留覆盖整条顶栏的隐形触摸墙。
            val radius = dock.height / 2f
            val nearestX = event.x.coerceIn(inset + radius, dock.width - inset - radius)
            if ((event.x - nearestX) * (event.x - nearestX) +
                (event.y - radius) * (event.y - radius) > radius * radius) return false
            downX = event.x
            downY = event.y
            consumed = animator != null
            bubbleTouch = collapsed && !consumed
            gesture = null
            if (bubbleTouch || consumed) return true

            val scroll = findScroll(tabs ?: dock) ?: findScroll(dock)
            dock.getLocationOnScreen(location)
            val dockLeft = location[0]
            scroll?.getLocationOnScreen(location)
            val onAction = if (scroll != null) {
                event.x + dockLeft >= location[0] + scroll.width
            } else event.x >= dock.width - 48f * density
            gesture = HostTopIslandGesture(
                startX = event.x,
                width = dock.width.toFloat(),
                edgeWidth = minOf(dock.width * .25f, 96f * density),
                touchSlop = touchSlop,
                collapseDistance = maxOf(24f * density, touchSlop * 2f),
                startsOnAction = onAction,
                canScrollLeft = canScroll(tabs ?: dock, -1),
                canScrollRight = canScroll(tabs ?: dock, 1)
            )
        }

        if (bubbleTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP && event.pointerCount == 1 &&
                abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                input.performClick()
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                bubbleTouch = false
                consumed = true
            } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                bubbleTouch = false
            }
            return true
        }
        if (consumed) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumed = false
                input.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
        if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_UP) gesture?.keepNative()
        if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            gesture?.move(event.x - downX, event.y - downY) == HostTopIslandGesture.Decision.COLLAPSE) {
            forward(event, MotionEvent.ACTION_CANCEL)
            glow?.resetGestureState()
            consumed = true
            input.parent?.requestDisallowInterceptTouchEvent(true)
            animateTo(true)
            return true
        }

        forward(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            gesture = null
            glow?.resetGestureState()
        } else {
            glow?.updateGesture(1f, 0f, 0f, event.x, event.y, dock.width, dock.height)
        }
        // 即使宿主忽略按钮上的 MOVE，也必须收到后续事件来判断收起。
        return true
    }

    private fun forward(event: MotionEvent, action: Int = event.action) {
        val copy = MotionEvent.obtain(event)
        try {
            copy.action = action
            dock.dispatchTouchEvent(copy)
        } finally {
            copy.recycle()
        }
    }

    private fun findScroll(view: View): View? {
        if (view is HorizontalScrollView || view.canScrollHorizontally(-1) || view.canScrollHorizontally(1)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findScroll(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun canScroll(view: View, direction: Int): Boolean {
        if (view.canScrollHorizontally(direction)) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (canScroll(view.getChildAt(index), direction)) return true
            }
        }
        return false
    }
}
