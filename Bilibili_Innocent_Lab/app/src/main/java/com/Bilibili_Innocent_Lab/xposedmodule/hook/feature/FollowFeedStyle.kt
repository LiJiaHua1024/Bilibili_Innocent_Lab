package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import kotlin.math.roundToInt

/** 关注页的宿主配置入口；材质与选中动画均由上游引擎实现。 */
internal object FollowFeedStyle {
    const val OUTER_DP = 12
    const val GAP_DP = 12
    const val CONTENT_DP = 12
    const val AVATAR_DP = 46
    const val FREQUENT_WIDTH_DP = 74
    const val FREQUENT_HEIGHT_DP = 82
    const val FREQUENT_LIST_HEIGHT_DP = 90
    const val OPTION_HEIGHT_DP = 48
    const val SEGMENT_INSET_DP = 4
    const val OPTION_VERTICAL_INSET_DP = 8
    const val SEGMENT_HEIGHT_DP = OPTION_HEIGHT_DP + SEGMENT_INSET_DP * 2
    const val SEGMENT_FONT_GROWTH_DP = 20
    const val SEGMENT_TEXT_SP = 15f
    const val VIDEO_TITLE_SP = 16f
    const val FREQUENT_TITLE_SP = 14f
    const val MAX_CARD_SURFACES = 14 // 保留默认 16 个表面中的轨道与选中框配额。

    fun frequentHeight(fontScale: Float) =
        FREQUENT_HEIGHT_DP + ((fontScale.coerceIn(1f, 2f) - 1f) * 32).roundToInt()

    fun frequentListHeight(fontScale: Float) =
        frequentHeight(fontScale) + FREQUENT_LIST_HEIGHT_DP - FREQUENT_HEIGHT_DP

    fun segmentHeight(fontScale: Float) =
        SEGMENT_HEIGHT_DP + ((fontScale - 1f).coerceAtLeast(0f) * SEGMENT_FONT_GROWTH_DP).roundToInt()

    /** 普通作者名接入语义前景；有彩色会员/业务强调的昵称保留宿主原色。 */
    fun isNeutralText(color: Int): Boolean {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        val maximum = maxOf(r, g, b)
        return maximum - minOf(r, g, b) <= maximum / 10
    }

    fun palette(colors: HostChromeColors): LumenPalette {
        val host = colors.palette()
        return LumenPalette.modern(host.primary, host.onPrimary, host.secondary, host.tertiary, colors.dark)
    }

    fun card() = LumenSurfacePresets.staticPanel().copy(
        role = SurfaceRole.CARD, tintOpacity = 1f, fallbackTintOpacity = 1f,
        edgeWidthDp = .5f, edgeIntensity = .18f
    )

    fun track(palette: LumenPalette) = card().copy(
        role = SurfaceRole.TOP_BAR, color = palette.surfaceVariant, radiusDp = 18f, edgeEnabled = false
    )

    fun selection() = card().copy(role = SurfaceRole.SELECTED_ITEM, radiusDp = 14f, edgeIntensity = .45f)

    /** 以覆盖区域求并集，已有 padding 与系统 inset 不重复相加。 */
    fun bottomPadding(original: Int, viewportBottom: Int, dockTop: Int?, systemBottom: Int, gap: Int): Int =
        maxOf(original, systemBottom, dockTop?.let { (viewportBottom - it + gap).coerceAtLeast(0) } ?: 0)
}

internal data class FollowFeedRow(val identity: Long, val first: Boolean, val last: Boolean, val position: Int)

internal object FollowFeedGrouping {
    fun joins(previous: FollowFeedRow, next: FollowFeedRow,
              adjacent: Boolean = next.position == previous.position + 1): Boolean =
        previous.identity > 0 && previous.identity == next.identity &&
            !previous.last && !next.first && adjacent
}
