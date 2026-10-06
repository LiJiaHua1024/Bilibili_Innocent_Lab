package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import kotlin.math.min

/** Double 虚拟坐标可无限平移；只在最终画入屏幕时转 Float，不把内容尺寸交给 Android 布局。 */
internal class ReplyTopologyTreeViewport {
    var scale = 1.0
        private set
    var x = 0.0
        private set
    var y = 0.0
        private set

    fun pan(dx: Double, dy: Double): Boolean {
        val nextX = x + dx
        val nextY = y + dy
        if (!dx.isFinite() || !dy.isFinite() || !nextX.isFinite() || !nextY.isFinite()) return false
        x = nextX
        y = nextY
        return true
    }

    fun zoom(factor: Double, focusX: Double, focusY: Double): Boolean {
        if (!factor.isFinite() || factor <= 0.0 || !focusX.isFinite() || !focusY.isFinite()) return false
        val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        return place(worldX(focusX), worldY(focusY), focusX, focusY, next)
    }

    fun place(worldX: Double, worldY: Double, screenX: Double, screenY: Double, zoom: Double = scale): Boolean {
        if (!zoom.isFinite() || zoom <= 0.0) return false
        val next = zoom.coerceIn(MIN_SCALE, MAX_SCALE)
        val nextX = screenX - worldX * next
        val nextY = screenY - worldY * next
        if (!nextX.isFinite() || !nextY.isFinite()) return false
        scale = next
        x = nextX
        y = nextY
        return true
    }

    fun fit(contentWidth: Double, contentHeight: Double, width: Double, height: Double, margin: Double): Boolean {
        if (!contentWidth.isFinite() || !contentHeight.isFinite() || !width.isFinite() || !height.isFinite() ||
            !margin.isFinite() || margin < 0.0 || contentWidth <= 0.0 || contentHeight <= 0.0 ||
            width <= margin * 2 || height <= margin * 2
        ) return false
        val zoom = min((width - margin * 2) / contentWidth, (height - margin * 2) / contentHeight)
        return place(contentWidth * 0.5, contentHeight * 0.5, width * 0.5, height * 0.5, zoom)
    }

    fun worldX(screenX: Double): Double = (screenX - x) / scale
    fun worldY(screenY: Double): Double = (screenY - y) / scale
    fun screenX(worldX: Double): Double = worldX * scale + x
    fun screenY(worldY: Double): Double = worldY * scale + y

    companion object {
        const val MIN_SCALE = 0.00001
        const val MAX_SCALE = 4.0
    }
}
