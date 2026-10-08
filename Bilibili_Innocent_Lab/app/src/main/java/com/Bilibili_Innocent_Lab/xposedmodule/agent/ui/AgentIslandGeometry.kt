package com.Bilibili_Innocent_Lab.xposedmodule.agent.ui

import kotlin.math.roundToInt

/** 公开 Insets 的纯几何策略；普通悬浮窗不能覆盖系统状态栏，因此始终放在其下方。 */
internal object AgentIslandGeometry {
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Input(
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val topInsetPx: Int = 0,
        val cutouts: List<Rect> = emptyList(),
        val leftInsetPx: Int = 0,
        val rightInsetPx: Int = 0,
        val bottomInsetPx: Int = 0
    )

    /** 图标中心与间隙坐标相对 bounds；cameraProjected 只表示顶孔的横向投影对齐。 */
    data class Placement(
        val bounds: Rect,
        val moduleCenterXPx: Int,
        val agentCenterXPx: Int,
        val iconCenterYPx: Int,
        val gapStartPx: Int,
        val gapEndPx: Int,
        val cameraProjected: Boolean
    )

    fun collapsed(input: Input): Placement? {
        val safe = safeArea(input) ?: return null
        val cell = dp(input, 48)
        val margin = dp(input, 4)
        val minimumGap = dp(input, 28)
        val minimumWidth = cell.toLong() * 2 + minimumGap
        if (minimumWidth > safe.width || cell > safe.height) return null
        val topCutouts = input.cutouts.asSequence().take(MAX_CUTOUTS).mapNotNull { normalize(it, input) }
            .filter { it.top.toLong() <= input.topInsetPx.toLong() + dp(input, 8) }
            .toList()
        val cutoutBottom = topCutouts.maxOfOrNull { it.bottom } ?: 0
        val top = maxOf(safe.top, cutoutBottom).toLong() + margin
        if (top + cell > safe.bottom) return null

        // 左置孔、宽刘海、横屏和多个顶切口不猜摄像头位置，使用安全居中布局。
        val camera = topCutouts.singleOrNull()?.takeIf {
            input.heightPx >= input.widthPx && it.width <= dp(input, 96) &&
                it.height <= dp(input, 96) &&
                kotlin.math.abs((it.left.toLong() + it.right) - input.widthPx) <= input.widthPx / 3L
        }
        if (camera != null) {
            val gap = maxOf(minimumGap.toLong(), camera.width.toLong() + margin * 2L)
            val center = (camera.left.toLong() + camera.right) / 2
            val left = center - gap / 2 - cell
            val right = left + cell * 2L + gap
            if (left >= safe.left && right <= safe.right) {
                return placement(left.toInt(), top.toInt(), right.toInt(), cell, cell, gap.toInt(), true)
            }
        }
        val width = minimumWidth.toInt()
        val left = safe.left + (safe.width - width) / 2
        return placement(left, top.toInt(), left + width, cell, cell, minimumGap, false)
    }

    /** 面板从岛下方展开；只返回实际内容区域，不能用全屏透明窗口拦住宿主触摸。 */
    fun panel(input: Input, collapsed: Placement, desiredHeightDp: Int = 240): Rect? {
        val safe = safeArea(input) ?: return null
        if (desiredHeightDp <= 0) return null
        val anchor = collapsed.bounds
        if (anchor.left < safe.left || anchor.right > safe.right || anchor.top < safe.top ||
            anchor.bottom > safe.bottom || anchor.right <= anchor.left || anchor.bottom <= anchor.top) return null
        val top = anchor.bottom.toLong() + dp(input, 8)
        val available = safe.bottom.toLong() - top
        if (available < dp(input, 48)) return null
        val width = minOf(safe.width, dp(input, 360))
        val height = minOf(available, dp(input, desiredHeightDp).toLong()).toInt()
        val center = (anchor.left.toLong() + anchor.right) / 2
        val left = (center - width / 2).coerceIn(safe.left.toLong(), safe.right.toLong() - width).toInt()
        return Rect(left, top.toInt(), left + width, top.toInt() + height)
    }

    private fun placement(left: Int, top: Int, right: Int, height: Int, cell: Int, gap: Int, projected: Boolean) =
        Placement(Rect(left, top, right, top + height), cell / 2, cell + gap + cell / 2,
            height / 2, cell, cell + gap, projected)

    private fun safeArea(input: Input): Rect? {
        if (input.widthPx <= 0 || input.heightPx <= 0 || !input.density.isFinite() || input.density <= 0f ||
            input.topInsetPx < 0 || input.leftInsetPx < 0 || input.rightInsetPx < 0 || input.bottomInsetPx < 0) return null
        val margin = dp(input, 8)
        val left = input.leftInsetPx.toLong() + margin
        val right = input.widthPx.toLong() - input.rightInsetPx - margin
        val bottom = input.heightPx.toLong() - input.bottomInsetPx - margin
        if (left >= right || input.topInsetPx >= bottom) return null
        return Rect(left.toInt(), input.topInsetPx, right.toInt(), bottom.toInt())
    }

    private fun normalize(rect: Rect, input: Input): Rect? {
        if (rect.right <= rect.left || rect.bottom <= rect.top) return null
        val left = rect.left.coerceIn(0, input.widthPx)
        val right = rect.right.coerceIn(0, input.widthPx)
        val top = rect.top.coerceIn(0, input.heightPx)
        val bottom = rect.bottom.coerceIn(0, input.heightPx)
        return if (left < right && top < bottom) Rect(left, top, right, bottom) else null
    }

    private fun dp(input: Input, value: Int): Int =
        (input.density.toDouble() * value).coerceAtMost(Int.MAX_VALUE.toDouble()).roundToInt().coerceAtLeast(1)

    private const val MAX_CUTOUTS = 32
}
