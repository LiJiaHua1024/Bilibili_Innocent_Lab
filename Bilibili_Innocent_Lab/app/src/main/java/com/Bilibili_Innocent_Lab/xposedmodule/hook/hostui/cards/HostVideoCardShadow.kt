package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards

import android.os.Build
import android.view.View

/** 静态列表卡片使用平台轮廓柔影；不维护位图缓存或自绘阴影管线。 */
internal object HostVideoCardShadow {
    fun apply(view: View) {
        view.elevation = 2f * view.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.outlineAmbientShadowColor = 0x16707070
            view.outlineSpotShadowColor = 0x16707070
        }
    }
}
