package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceOptions
import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.model.SurfaceRole

/** 顶栏与底栏的公共视觉参数；这里只声明外观，不实现采样、Shader 或 Drawable。 */
internal object HostSurfaceStyle {
    fun floating(dark: Boolean, radiusDp: Float) = LumenSurfacePresets.floating(
        LumenSurfaceMaterial.LIQUID
    ).copy(radiusDp = radiusDp, tintOpacity = (if (dark) 120 else 112) / 255f,
        fallbackTintOpacity = (if (dark) 120 else 112) / 255f,
        backdropOpacity = (255 - if (dark) 120 else 112) / 255f, edgeWidthDp = .65f,
        edgeTopColor = ((if (dark) 56 else 140) shl 24) or 0xFFFFFF,
        edgeBottomColor = ((if (dark) 16 else 32) shl 24) or 0xFFFFFF,
        sampling = LumenSurfacePresets.floating().sampling.copy(blurRadiusDp = 10f))

    fun selection(dark: Boolean, radiusDp: Float = 28f) = LumenSurfaceOptions(
        material = LumenSurfaceMaterial.STATIC, role = SurfaceRole.SELECTED_ITEM,
        radiusDp = radiusDp, tintOpacity = (if (dark) 218 else 210) / 255f,
        fallbackTintOpacity = (if (dark) 218 else 210) / 255f, edgeWidthDp = .65f,
        edgeTopColor = ((if (dark) 16 else 60) shl 24) or 0xFFFFFF,
        edgeBottomColor = ((if (dark) 5 else 12) shl 24) or 0xFFFFFF,
        sampling = LumenSurfacePresets.staticPanel().sampling
    )

    fun fusion(dark: Boolean, hold: Float, end: Float) = LumenSurfacePresets.fadingBand(hold, end).copy(
        tintOpacity = (if (dark) 146 else 132) / 255f, fallbackTintOpacity = (if (dark) 146 else 132) / 255f,
        backdropOpacity = (255 - if (dark) 146 else 132) / 255f
    )
}
