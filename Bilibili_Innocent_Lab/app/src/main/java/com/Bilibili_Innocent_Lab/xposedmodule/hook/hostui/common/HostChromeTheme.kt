package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import android.content.Context
import android.content.res.Configuration
import com.lumen.coacervation.engine.model.LumenPalette
import kotlin.math.pow

/** 宿主换肤独立于系统 uiMode；栏的材质与前景必须消费同一份宿主主题。 */
internal class HostChromeTheme(private val context: Context) {
    private val nightTheme = runCatching {
        context.classLoader.loadClass("com.bilibili.lib.ui.util.NightTheme")
            .getMethod("isNightTheme", Context::class.java)
    }.getOrNull()
    private val hostColor = runCatching {
        context.classLoader.loadClass("com.bilibili.magicasakura.utils.ThemeUtils")
            .getMethod("getColorById", Context::class.java, Int::class.javaPrimitiveType)
    }.getOrNull()
    private val accentId = context.resources.getIdentifier("theme_color_secondary", "color", context.packageName)

    /** 逐帧只读换肤值；反射方法和资源 id 在绑定时解析一次，不跑壁纸取色/HCT。 */
    fun read(): HostChromeColors {
        val hostDark = runCatching { nightTheme?.invoke(null, context) as? Boolean }.getOrNull()
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val accent = if (accentId == 0) null else {
            runCatching { hostColor?.invoke(null, context, accentId) as? Int }.getOrNull()
                ?: runCatching { context.getColor(accentId) }.getOrNull()
        }
        return HostChromeColors.resolve(hostDark, systemDark, accent)
    }
}

internal data class HostChromeColors(val dark: Boolean, val accent: Int) {
    /** 与凝光引擎的 modern 调色板一致：中性深浅表面 + 宿主强调色。 */
    fun palette(): LumenPalette {
        fun channel(shift: Int): Double {
            val value = (accent ushr shift and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        val luminance = .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
        return LumenPalette.modern(accent,
            if (luminance > .179) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), accent, accent, dark)
    }

    companion object {
        fun resolve(hostDark: Boolean?, systemDark: Boolean, accent: Int?): HostChromeColors =
            HostChromeColors(hostDark ?: systemDark, accent ?: 0xFFFF6699.toInt())
    }
}
