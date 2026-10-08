package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup

internal interface HostDockLayer

internal object HostBackdropLocator {
    private const val MAX_LEVELS = 8

    private const val MIN_COVERAGE = 0.25f
    private val dockRect = Rect()
    private val siblingRect = Rect()
    private val location = IntArray(2)

    /** 只选与表面重叠的兄弟内容层，排除自身、祖先和注入装饰。 */
    fun find(dock: View): View? {
        val area = dock.width.toLong() * dock.height
        if (area <= 0L || !dock.isAttachedToWindow) return null
        dock.getLocationOnScreen(location)
        dockRect.set(location[0], location[1], location[0] + dock.width, location[1] + dock.height)
        var node = dock
        repeat(MAX_LEVELS) {
            val parent = node.parent as? ViewGroup ?: return null
            var best: View? = null
            var coverage = 0L
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                if (child === node || child is HostDockLayer || child is HostGlowView ||
                    !child.isShown || child.alpha <= 0f) continue
                child.getLocationOnScreen(location)
                siblingRect.set(location[0], location[1], location[0] + child.width, location[1] + child.height)
                val width = (minOf(dockRect.right, siblingRect.right) - maxOf(dockRect.left, siblingRect.left)).coerceAtLeast(0)
                val height = (minOf(dockRect.bottom, siblingRect.bottom) - maxOf(dockRect.top, siblingRect.top)).coerceAtLeast(0)
                val overlap = width.toLong() * height
                if (overlap > coverage) { best = child; coverage = overlap }
            }
            if (coverage >= area * MIN_COVERAGE && best != null) return best
            node = parent
        }
        return null
    }
}
