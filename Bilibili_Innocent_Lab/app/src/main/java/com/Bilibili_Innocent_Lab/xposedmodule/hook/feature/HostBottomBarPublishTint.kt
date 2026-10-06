package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.widget.ImageView

/** 胶囊清除发布按钮底色后，加号跟随宿主换肤强调色，而非模块的壁纸配色。 */
internal class HostBottomBarPublishTint(private val context: Context) {
    private val plusId = context.resources.getIdentifier("publish_plus", "id", context.packageName)
    // primary 在宿主夜间模式是背景灰；secondary 才是日夜模式的主题强调色。
    private val accentId = context.resources.getIdentifier("theme_color_secondary", "color", context.packageName)
    private val getHostColor = runCatching {
        context.classLoader.loadClass("com.bilibili.magicasakura.utils.ThemeUtils")
            .getMethod("getColorById", Context::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()
    private var appliedColor: Int? = null
    private var appliedFilter: PorterDuffColorFilter? = null

    fun apply(publishView: View) {
        if (plusId == 0 || accentId == 0) return
        val plus = publishView.findViewById<ImageView>(plusId) ?: return
        if (!plus.isShown) return
        // 每次对齐读当前换肤值，兼容不重建 Activity 的换肤；资源 id 和反射方法只查一次。
        val color = runCatching { getHostColor?.invoke(null, context, accentId) as? Int }.getOrNull()
            ?: runCatching { context.getColor(accentId) }.getOrNull()
            ?: return
        if (appliedColor != color) {
            appliedColor = color
            appliedFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        if (plus.colorFilter == appliedFilter) return
        plus.colorFilter = appliedFilter
    }
}
