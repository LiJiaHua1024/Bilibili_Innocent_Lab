package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

/** Only the interpolation temperature pause is optional; all other admission and capacity gates remain. */
internal object FrameThermalControl {
    /** 1.0 means severe throttling; retain the existing 0.85 early-exit boundary. */
    inline fun pauseHeadroom(enabled: Boolean, query: () -> Float?): Float? {
        if (!enabled) return null
        return query()?.takeIf { it >= 0.85f }
    }
}
