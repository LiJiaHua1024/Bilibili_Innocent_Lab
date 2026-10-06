package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeFlags
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyGraph
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyTreeLayout
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 单一虚拟画布：显示坐标由 Double 投影，节点视图／位图数量不随树的宽高增长。 */
internal class ReplyTopologyTreeCanvas(
    context: Context,
    private val theme: ReplyTopologyPanelTheme,
    private val strings: ReplyTopologyPanelStrings,
    onSelected: (Long) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val cardWidth = 224.0 * density
    private val fontFactor = resources.configuration.fontScale.coerceAtLeast(1f)
    private val cardHeight = 116.0 * density * fontFactor
    private val columnStep = cardWidth + 40.0 * density
    private val rowStep = cardHeight + 24.0 * density
    private val viewport = ReplyTopologyTreeViewport()
    private var scene: ReplyTopologyTreeLayout? = null
    private var selectedRpid: Long? = null
    private var select: ((Long) -> Unit)? = onSelected
    private var released = false
    private var positioned = false
    private var wasScaling = false
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var lastFlingTime = 0L
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.trackColor; style = Paint.Style.STROKE }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.authorTextColor; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.primaryTextColor; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val metaPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.secondaryTextColor; textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
    }
    private class TextBlock(val title: StaticLayout, val body: StaticLayout, val depth: StaticLayout,
        val author: String, val replied: String?, val message: String, val flags: Int, val level: Int) {
        fun matches(graph: ReplyTopologyGraph, index: Int): Boolean =
            author == graph.authorNames[index] && replied == graph.repliedAuthorNames[index] &&
                message == graph.messagePreviews[index] && flags == graph.flags[index] && level == graph.depths[index]
    }
    private val textCache = LruCache<Long, TextBlock>(96)
    private var textWarmupPosted = false
    private val textWarmup = HostThreadGuard.runnable("reply_topology.tree_text_warmup") {
        textWarmupPosted = false
        warmViewportText()
    }
    private val flingFrame = HostThreadGuard.runnable("reply_topology.tree_fling") {
        if (!released) {
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFlingTime).coerceIn(0L, 40L)) / 1000.0
            lastFlingTime = now
            viewport.pan(velocityX * dt, velocityY * dt)
            val decay = exp(-6.0 * dt)
            velocityX *= decay
            velocityY *= decay
            requestTextWarmup()
            invalidate()
            if (hypot(velocityX, velocityY) > 20.0) postOnAnimation(flingFrameRunnable())
        }
    }
    private fun flingFrameRunnable(): Runnable = flingFrame
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = HostThreadGuard.call("reply_topology.tree_scale_begin", false) {
            stopFling()
            wasScaling = true
            !released
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean = HostThreadGuard.call("reply_topology.tree_scale", false) {
            if (released) false else viewport.zoom(detector.scaleFactor.toDouble(), detector.focusX.toDouble(), detector.focusY.toDouble())
                .also { if (it) { requestTextWarmup(); invalidate() } }
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = HostThreadGuard.call("reply_topology.tree_down", false) { stopFling(); !released }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean =
            HostThreadGuard.call("reply_topology.tree_pan", false) {
                if (released || wasScaling || scaler.isInProgress || e2.pointerCount != 1) false else viewport.pan(-distanceX.toDouble(), -distanceY.toDouble())
                    .also { if (it) { requestTextWarmup(); invalidate() } }
            }
        override fun onSingleTapUp(e: MotionEvent): Boolean = HostThreadGuard.call("reply_topology.tree_select", false) {
            if (released || wasScaling) false else pick(e.x.toDouble(), e.y.toDouble())?.let {
                selectedRpid = it
                select?.invoke(it)
                performClick()
                invalidate()
                true
            } ?: false
        }
        override fun onDoubleTap(e: MotionEvent): Boolean = HostThreadGuard.call("reply_topology.tree_double_tap", false) {
            if (released || wasScaling) false else viewport.zoom(1.6, e.x.toDouble(), e.y.toDouble()).also { requestTextWarmup(); invalidate() }
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean = HostThreadGuard.call("reply_topology.tree_fling_start", false) {
            if (released || wasScaling || !vx.isFinite() || !vy.isFinite()) return@call false
            velocityX = vx.toDouble()
            velocityY = vy.toDouble()
            lastFlingTime = SystemClock.uptimeMillis()
            postOnAnimation(flingFrame)
            true
        }
    })

    init {
        isClickable = true
        isFocusable = true
        contentDescription = strings.treeGestureHint
        setBackgroundColor(theme.backgroundColor)
    }

    fun submit(layout: ReplyTopologyTreeLayout, selection: Long?, reset: Boolean) {
        if (released) return
        val previous = scene
        val anchor = selectedRpid
        val previousRow = if (anchor != null) previous?.rowOf(anchor) ?: -1 else -1
        val x = if (previousRow >= 0) viewport.screenX(requireNotNull(previous).depthAt(previousRow) * columnStep + cardWidth * 0.5) else 0.0
        val y = if (previousRow >= 0) viewport.screenY(previousRow * rowStep + cardHeight * 0.5) else 0.0
        scene = layout
        selectedRpid = selection?.takeIf { layout.rowOf(it) >= 0 }
        // 相同 rpid 的内容由 matches 验真；分页和选中变化保留仍有效的布局。
        if (previous?.graph?.key != layout.graph.key) textCache.evictAll()
        if (reset || !positioned) centerSelected(resetZoom = true)
        else if (previousRow >= 0 && anchor != null) {
            val row = layout.rowOf(anchor)
            if (row >= 0) viewport.place(layout.depthAt(row) * columnStep + cardWidth * 0.5, row * rowStep + cardHeight * 0.5, x, y)
        }
        requestTextWarmup()
        invalidate()
    }

    fun fitView() {
        if (released) return
        val value = scene ?: return
        stopFling()
        if (value.size > 0 && viewport.fit(value.maxDepth * columnStep + cardWidth,
                (value.size - 1) * rowStep + cardHeight, width.toDouble(), height.toDouble(), 12.0 * density)) {
            positioned = true
            requestTextWarmup()
            invalidate()
        }
    }

    fun centerSelected(resetZoom: Boolean = false) {
        if (released) return
        val value = scene ?: return
        if (width <= 0 || height <= 0 || value.size == 0) return
        stopFling()
        val row = selectedRpid?.let(value::rowOf)?.takeIf { it >= 0 } ?: 0
        viewport.place(value.depthAt(row) * columnStep + cardWidth * 0.5, row * rowStep + cardHeight * 0.5,
            width * 0.5, height * 0.5, if (resetZoom) 1.0 else max(viewport.scale, 0.4))
        positioned = true
        requestTextWarmup()
        invalidate()
    }

    fun zoomBy(factor: Double) {
        if (released) return
        stopFling(); viewport.zoom(factor, width * 0.5, height * 0.5); requestTextWarmup(); invalidate()
    }
    fun selection(): Long? = selectedRpid

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!released) HostThreadGuard.run("reply_topology.tree_resize_canvas") {
            if (!positioned) centerSelected(resetZoom = true)
            else viewport.pan((w - oldw) * 0.5, (h - oldh) * 0.5)
            requestTextWarmup()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = HostThreadGuard.call("reply_topology.tree_touch", false) {
        if (released) return@call false
        parent?.requestDisallowInterceptTouchEvent(true)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) wasScaling = false
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (released) return
        // 常规绘制不创建兜底 lambda；异常才进入宿主线程防波堤。
        val saved = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        try { drawScene(canvas) } catch (failure: Throwable) {
            HostThreadGuard.run("reply_topology.tree_draw") { throw failure }
        } finally { canvas.restoreToCount(saved) }
    }

    private fun drawScene(canvas: Canvas) {
        val value = scene ?: return
        val zoom = viewport.scale
        val visible = value.visibleRows(viewport.worldY(0.0), viewport.worldY(height.toDouble()), rowStep, cardHeight)
        edge.strokeWidth = (density * zoom).toFloat().coerceIn(1f, 2.5f * density)
        run {
            val count = if (visible.isEmpty()) 0 else value.collectVisibleBranches(visible.first, visible.last)
            for (branch in 0 until count) {
                val row = value.visibleBranchRows[branch]
                val last = value.lastChildRows[row]
                if (last < 0) continue
                val right = viewport.screenX(value.depthAt(row) * columnStep + cardWidth)
                val junction = viewport.screenX(value.depthAt(row) * columnStep + cardWidth + (columnStep - cardWidth) * 0.5)
                val from = viewport.screenY(row * rowStep + cardHeight * 0.5)
                val to = viewport.screenY(last * rowStep + cardHeight * 0.5)
                if (from in 0.0..height.toDouble() && junction >= 0.0 && right <= width) {
                    canvas.drawLine(max(0.0, right).toFloat(), from.toFloat(), min(width.toDouble(), junction).toFloat(), from.toFloat(), edge)
                }
                if (junction in 0.0..width.toDouble() && to >= 0.0 && from <= height) {
                    canvas.drawLine(junction.toFloat(), max(0.0, from).toFloat(), junction.toFloat(), min(height.toDouble(), to).toFloat(), edge)
                }
            }
        }
        var bucketX = Int.MIN_VALUE
        var bucketY = Int.MIN_VALUE
        for (row in visible) {
            val index = value.indexAt(row)
            val left = viewport.screenX(value.depthAt(row) * columnStep)
            val top = viewport.screenY(row * rowStep)
            val right = left + cardWidth * zoom
            val bottom = top + cardHeight * zoom
            run {
                val parentRow = value.parentRow(row)
                if (parentRow >= 0) {
                    val from = viewport.screenX(value.depthAt(parentRow) * columnStep + cardWidth + (columnStep - cardWidth) * 0.5)
                    val y = viewport.screenY(row * rowStep + cardHeight * 0.5)
                    if (y in 0.0..height.toDouble() && from <= width && left >= 0.0) {
                        canvas.drawLine(max(0.0, from).toFloat(), y.toFloat(), min(width.toDouble(), left).toFloat(), y.toFloat(), edge)
                    }
                }
            }
            if (right < 0.0 || left > width || bottom < 0.0 || top > height) continue
            val selected = value.graph.rpids[index] == selectedRpid
            if (cardWidth * zoom < 24.0) {
                val bx = (left / 2.0).toInt()
                val by = (top / 2.0).toInt()
                if (bx == bucketX && by == bucketY && !selected) continue
                bucketX = bx; bucketY = by
                fill.color = if (selected) theme.accentColor else theme.trackColor
                canvas.drawRect(left.toFloat(), top.toFloat(), max(right, left + 2.0).toFloat(), max(bottom, top + 2.0).toFloat(), fill)
                continue
            }
            val save = canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            canvas.scale(zoom.toFloat(), zoom.toFloat())
            canvas.clipRect(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat())
            fill.color = if (selected) ReplyTopologyPanelTheme.blendColor(theme.backgroundColor, theme.accentColor, 0.2f) else theme.backgroundColor
            border.color = if (selected) theme.accentColor else theme.strokeColor
            border.strokeWidth = if (selected) 2f * density else density
            canvas.drawRoundRect(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat(), 10f * density, 10f * density, fill)
            canvas.drawRoundRect(0f, 0f, cardWidth.toFloat(), cardHeight.toFloat(), 10f * density, 10f * density, border)
            if (cardWidth * zoom >= 100.0 && cardHeight * zoom >= 36.0) {
                val text = textCache.get(value.graph.rpids[index])?.takeIf { it.matches(value.graph, index) }
                canvas.translate(8f * density, 7f * density)
                if (text != null) {
                    text.title.draw(canvas)
                    canvas.translate(0f, 22f * density * fontFactor)
                    text.body.draw(canvas)
                    canvas.translate(0f, 64f * density * fontFactor)
                    text.depth.draw(canvas)
                } else {
                    // 首帧保留作者及可点击节点；文字布局在可见节点优先的帧任务中完成。
                    canvas.drawText(value.graph.authorNames[index], 0f, -titlePaint.ascent(), titlePaint)
                }
            }
            canvas.restoreToCount(save)
        }
    }

    private fun textBlock(layout: ReplyTopologyTreeLayout, index: Int): TextBlock {
        val graph = layout.graph
        val flags = graph.flags[index]
        val filtered = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.FILTERED)
        val unavailable = ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.PLACEHOLDER) || ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.UNAVAILABLE)
        val author = graph.authorNames[index].ifBlank { if (filtered) strings.filteredAuthor else if (unavailable) strings.unavailableAuthor else strings.unknownAuthor }
        val replied = graph.repliedAuthorNames[index]?.takeIf { it.isNotBlank() && !ReplyTopologyNodeFlags.has(flags, ReplyTopologyNodeFlags.ROOT) }
        val title = if (replied == null) author else "$author → $replied"
        val body = graph.messagePreviews[index].ifBlank { if (filtered) strings.filteredMessage else if (unavailable) strings.unavailableMessage else strings.emptyMessage }
        fun block(text: String, paint: TextPaint, lines: Int): StaticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, (cardWidth - 16 * density).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END).build()
        return TextBlock(block(title, titlePaint, 1), block(body, bodyPaint, 3), block(strings.depthLabel(graph.depths[index]), metaPaint, 1),
            graph.authorNames[index], graph.repliedAuthorNames[index], graph.messagePreviews[index], flags, graph.depths[index])
    }

    private fun requestTextWarmup() {
        if (released || textWarmupPosted || scene == null) return
        textWarmupPosted = true
        postOnAnimation(textWarmup)
    }

    private fun warmViewportText() {
        val value = scene ?: return
        if (released || width <= 0 || height <= 0 || cardWidth * viewport.scale < 100.0 || cardHeight * viewport.scale < 36.0) return
        val visible = value.visibleRows(viewport.worldY(0.0), viewport.worldY(height.toDouble()), rowStep, cardHeight)
        if (visible.isEmpty()) return
        val candidates = ReplyTopologyLoadPriority.rows(value.size, visible.first, visible.last)
        val start = SystemClock.uptimeMillis()
        var built = 0
        var pending = false
        // 横向离屏卡片也属于邻近预取，不能抢在真正屏内的文字之前。
        work@ for (rank in 0..1) for (row in candidates) {
            val left = viewport.screenX(value.depthAt(row) * columnStep)
            val right = left + cardWidth * viewport.scale
            if (right < -width || left > width * 2.0) continue
            val top = viewport.screenY(row * rowStep)
            val bottom = top + cardHeight * viewport.scale
            val onScreen = ReplyTopologyLoadPriority.intersects(left, top, right, bottom, width.toDouble(), height.toDouble())
            if (onScreen != (rank == 0)) continue
            val index = value.indexAt(row)
            val id = value.graph.rpids[index]
            if (textCache.get(id)?.matches(value.graph, index) == true) continue
            if (built >= 4 || built > 0 && SystemClock.uptimeMillis() - start >= 2L) { pending = true; break@work }
            textCache.put(id, textBlock(value, index))
            built++
        }
        if (built > 0) invalidate()
        if (pending) requestTextWarmup()
    }

    private fun pick(x: Double, y: Double): Long? {
        val value = scene ?: return null
        val worldX = viewport.worldX(x)
        val worldY = viewport.worldY(y)
        val row = floor(worldY / rowStep).toInt()
        if (row !in 0 until value.size || worldY - row * rowStep > cardHeight) return null
        val left = value.depthAt(row) * columnStep
        return if (worldX in left..(left + cardWidth)) value.graph.rpids[value.indexAt(row)] else null
    }

    private fun stopFling() { velocityX = 0.0; velocityY = 0.0; removeCallbacks(flingFrame) }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT)
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (released) return false
        return HostThreadGuard.call("reply_topology.tree_accessibility", false) {
            val dx = when (action) { AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id -> width * 0.5; AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id -> -width * 0.5; else -> 0.0 }
            val dy = when (action) { AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id -> height * 0.5; AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id -> -height * 0.5; else -> 0.0 }
            if (dx != 0.0 || dy != 0.0) { stopFling(); viewport.pan(dx, dy); requestTextWarmup(); invalidate(); true }
            else super.performAccessibilityAction(action, arguments)
        }
    }

    fun release() {
        if (released) return
        released = true
        removeCallbacks(textWarmup)
        textWarmupPosted = false
        stopFling()
        val cancel = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            runCatching { gestures.onTouchEvent(cancel) }
            runCatching { scaler.onTouchEvent(cancel) }
        } finally { cancel.recycle() }
        textCache.evictAll()
        scene = null
        select = null
    }
}
