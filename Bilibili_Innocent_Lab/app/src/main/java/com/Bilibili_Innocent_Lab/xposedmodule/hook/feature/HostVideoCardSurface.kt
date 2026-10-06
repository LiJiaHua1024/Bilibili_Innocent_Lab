package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.View
import com.lumen.coacervation.engine.host.LumenActivityDelegate
import com.lumen.coacervation.engine.model.LumenPalette

/**
 * 使用凝光引擎公开的 CARD 表面 API。列表卡片背后只有页面底色，采用其静态柔光路径，
 * 不创建实时截图/折射会话，也不为每张卡片登记逐帧位置采样。每张卡片独占 Drawable。
 */
internal object HostVideoCardSurface {
    private val light = LumenPalette.neutral(false)
    private val dark = LumenPalette.neutral(true)

    fun color(night: Boolean): Int = palette(night).surface

    fun create(view: View, radiusPx: Float, night: Boolean): Drawable? {
        val activity = activity(view.context) ?: return null
        val palette = palette(night)
        // 未 prepare 的表面 API 返回引擎提供的等价静态材质，无会话需要托管生命周期。
        return LumenActivityDelegate(activity, paletteProvider = { palette }).cardBackground(
            color = palette.surface,
            radiusDp = radiusPx / view.resources.displayMetrics.density)
    }

    private fun palette(night: Boolean) = if (night) dark else light

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(10) {
            if (current is Activity) return current as Activity
            val wrapper = current as? ContextWrapper ?: return null
            val base = wrapper.baseContext
            if (base === current) return null
            current = base
        }
        return null
    }
}
