package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowContentSample
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowLegibilityPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowSurfaceOptics
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidLegibilityTuning
import kotlin.math.roundToInt

/** 宿主原生灰色前景保留原样，深色玻璃通过加厚色罩抵消亮图透入。 */
internal object HostChromeLegibility {
    private const val NIGHT_FOREGROUND = 0xFF9EA3AB.toInt()

    fun tintAlpha(sample: GlowContentSample?, surface: Int, baseAlpha: Int): Int {
        if (sample == null || GlowLegibilityPolicy.encodedLuma(surface) >= .5f) return baseAlpha
        val base = baseAlpha / 255f
        // 宿主原生前景不做引擎的额外提亮，允许色罩达到引擎加厚的绝对上限。
        val optics = GlowSurfaceOptics(surface, base,
            LiquidLegibilityTuning.MAX_TINT_ALPHA.coerceAtLeast(base), seeThrough = 1f)
        val target = GlowLegibilityPolicy.target(sample, NIGHT_FOREGROUND, optics)
        return ((base + (optics.maxTintAlpha - base) * target.boost) * 255f).roundToInt()
    }
}
