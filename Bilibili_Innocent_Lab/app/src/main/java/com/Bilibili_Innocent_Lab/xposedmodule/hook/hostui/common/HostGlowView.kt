package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.lumen.coacervation.engine.touch.GlowConfig
import com.lumen.coacervation.engine.touch.GlowFrame
import com.lumen.coacervation.engine.touch.GlowState
import com.lumen.coacervation.engine.touch.reachablePileRoomPx
import com.lumen.coacervation.engine.touch.TouchGlowRenderer
/**
 * 宿主触控柔光渲染 View。
 *
 * 由引擎 GlowState 和 TouchGlowRenderer 求值并绘制椭圆光晕。
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
