package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.content.res.Configuration
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup

/** 只缓存宿主成员，不持有 Activity；宿主主题接口不可用时回退到系统模式。 */
internal class HostVideoCardHostAccess(loader: ClassLoader) {
    private val nightTheme = KavaMemberLookup.classOrNull(loader, "com.bilibili.lib.ui.util.NightTheme")
        ?.let { KavaMemberLookup.methodOrNull(it, "isNightTheme", Context::class.java) }
    private val roundCover = KavaMemberLookup.classOrNull(loader,
        "com.bilibili.app.comm.list.common.widget.RoundCircleFrameLayout")
    private val setRadius = roundCover?.let {
        KavaMemberLookup.methodOrNull(it, "setRadius", Float::class.javaPrimitiveType!!)
    }

    fun isNight(context: Context): Boolean {
        val hostNight = runCatching { nightTheme?.invoke(null, context) as? Boolean }.getOrNull()
        return hostNight ?: (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES)
    }

    fun setCoverRadius(view: View, radius: Float) {
        if (roundCover?.isInstance(view) == true) setRadius?.invoke(view, radius)
    }
}
