package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * The host clips the top row to its own node, so the Prism capsule and its outer shadow must
 * both fit inside it. Tabs and indicators keep the full row; only the drawn material is inset.
 */
internal data class HostChromeTopCapsule(val insetX: Float, val insetY: Float, val shadowRadius: Float) {
    fun cornerRadius(height: Float): Float = (height - 2f * insetY).coerceAtLeast(0f) / 2f

    companion object {
        private const val INSET_X_DP = 6f
        private const val INSET_Y_DP = 2f
        const val SHADOW_OPACITY = 0.18f

        /** BlurMaskFilter sigma = 0.57735 * radius + 0.5 and paints up to 3 sigma outside the shape. */
        fun shadowExtent(radius: Float): Float = if (radius <= 0f) 0f else ceil(3f * (0.57735f * radius + 0.5f))

        fun fit(width: Float, height: Float, density: Float): HostChromeTopCapsule? {
            if (!width.isFinite() || !height.isFinite() || !density.isFinite() || width <= 0f || height <= 0f || density <= 0f) return null
            // Never let the inset eat more than a sliver of a short row; the indicator sits near its edge.
            val insetY = min(INSET_Y_DP * density, height * .06f)
            val insetX = min(INSET_X_DP * density, width / 8f).coerceAtLeast(insetY)
            if (height - 2f * insetY <= 0f || width - 2f * insetX <= 0f) return null
            val radius = (((floor(insetY) - .01f) / 3f - .5f) / .57735f).coerceAtLeast(0f)
            return HostChromeTopCapsule(insetX, insetY, radius)
        }
    }
}
