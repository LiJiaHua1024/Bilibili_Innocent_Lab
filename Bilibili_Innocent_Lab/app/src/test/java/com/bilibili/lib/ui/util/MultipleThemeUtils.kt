package com.bilibili.lib.ui.util

import android.content.Context

object MultipleThemeUtils {
    @JvmField val NIGHT_THEME_ID = 1
    @JvmField var savedTheme = 8
    @JvmField var followSystem = true
    @JvmStatic fun getCurrentThemeId(context: Context?): Int = savedTheme
    @JvmStatic fun isNightFollowSystem(context: Context?): Boolean = followSystem
}
