package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.graphics.Rect
import android.view.View

/** 分页缓存的 View 可以仍是 VISIBLE/isShown，但已完全离开窗口；复用调用方的 Rect。 */
internal object HostViewVisibility {
    fun visible(view: View, bounds: Rect): Boolean {
        return shown(view) && view.getGlobalVisibleRect(bounds)
    }

    /** Visibility/alpha only; callers retain ownership of their local/window coordinate checks. */
    fun shown(view: View): Boolean {
        if (!view.isShown) return false
        var node: View? = view
        while (node != null) {
            if (node.alpha <= 0f) return false
            node = node.parent as? View
        }
        return true
    }
}
