package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import kotlin.math.exp

/** 空间跟随阻尼弹簧，显隐按空间行程交接；展开以可见的轻微过冲柔和回弹。 */
internal object HostTopIslandMotionSpec {
    const val COLLAPSE_STIFFNESS = 115f
    const val EXPAND_STIFFNESS = 88f
    const val DAMPING_RATIO = .84f
    const val EXPAND_DAMPING_RATIO = .68f
    const val MAX_FRAME_SECONDS = .05f

    fun shellWidth(progress: Float, width: Float, height: Float, density: Float): Float {
        val travel = (width - height).coerceAtLeast(0f)
        return when {
            progress > 1f -> height - resisted((progress - 1f) * travel, height * .07f)
            progress < 0f -> width + resisted(-progress * travel, 12f * density)
            else -> width - travel * progress
        }
    }

    fun horizontalInset(progress: Float, width: Float, height: Float, density: Float): Float =
        (width - shellWidth(progress, width, height, density)) / 2f

    fun verticalInset(progress: Float, width: Float, height: Float, density: Float): Float {
        val shell = shellWidth(progress, width, height, density)
        // 水平轻压时略微舒展高度，展开过冲时略微压低；不放大成橡皮球。
        return when {
            shell < height -> -(height - shell) * .11f
            shell > width -> (shell - width) * .14f
            else -> 0f
        }
    }

    fun contentAlpha(progress: Float): Float = 1f - smoothStep(.08f, .52f, progress)

    fun glyphAlpha(progress: Float): Float = smoothStep(.86f, .985f, progress)

    private fun resisted(distance: Float, limit: Float): Float =
        if (limit <= 0f) 0f else limit * (1f - exp(-distance / limit))

    private fun smoothStep(start: Float, end: Float, value: Float): Float {
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
