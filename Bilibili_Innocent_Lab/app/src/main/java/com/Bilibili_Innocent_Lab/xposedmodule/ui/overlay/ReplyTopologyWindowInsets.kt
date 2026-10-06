package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.WindowInsets

internal fun replyTopologyWindowInsets(view: View, out: Rect) {
    val insets = view.rootWindowInsets
    if (insets == null) out.setEmpty()
    else if (Build.VERSION.SDK_INT >= 30) {
        val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        out.set(safe.left, safe.top, safe.right, safe.bottom)
    } else {
        @Suppress("DEPRECATION")
        out.set(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        if (Build.VERSION.SDK_INT >= 28) insets.displayCutout?.let { cutout ->
            out.set(maxOf(out.left, cutout.safeInsetLeft), maxOf(out.top, cutout.safeInsetTop),
                maxOf(out.right, cutout.safeInsetRight), maxOf(out.bottom, cutout.safeInsetBottom))
        }
    }
}
