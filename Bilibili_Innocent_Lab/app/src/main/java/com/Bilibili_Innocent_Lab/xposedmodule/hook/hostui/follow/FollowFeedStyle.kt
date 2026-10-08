package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors

import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.host.LumenSurfaceFadeCurve
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.model.SurfaceRole
import kotlin.math.roundToInt

/** 关注页的宿主配置入口；材质与选中动画均由上游引擎实现。 */
internal object FollowFeedStyle {
    const val OUTER_DP = 12
    const val GAP_DP = 12
    const val CONTENT_DP = 12
    const val REFERENCE_INSET_DP = 6 // 保留宿主内容边距，边框与正文之间仍有 6dp 留白。
    const val AVATAR_DP = 46
    const val FREQUENT_WIDTH_DP = 74
    const val FREQUENT_HEIGHT_DP = 82
    const val FREQUENT_LIST_HEIGHT_DP = 90
    const val OPTION_HEIGHT_DP = 48
    const val ACTION_GAP_DP = 8
    const val SEGMENT_INSET_DP = 4
    const val OPTION_VERTICAL_INSET_DP = 8
    const val SEGMENT_HEIGHT_DP = OPTION_HEIGHT_DP + SEGMENT_INSET_DP * 2
    const val SEGMENT_FONT_GROWTH_DP = 20
    const val SEGMENT_TEXT_SP = 15f
    const val SEGMENT_WIDTH_DP = 176
    const val SEGMENT_FONT_WIDTH_GROWTH_DP = 80
    const val VIDEO_TITLE_SP = 16f
    const val FREQUENT_TITLE_SP = 14f
    const val STATUS_FADE_DP = 12 // 状态栏外 8dp 渐隐 + 4dp 透明尾部，先归零再结束 View。
    private const val STATUS_FADE_END = 2f / 3f
    const val MAX_CARD_SURFACES = 13 // 默认 16 个表面中预留轨道、选中框与状态栏融合带。

    fun frequentHeight(fontScale: Float) =
        FREQUENT_HEIGHT_DP + ((fontScale.coerceIn(1f, 2f) - 1f) * 32).roundToInt()

    fun frequentListHeight(fontScale: Float) =
        frequentHeight(fontScale) + FREQUENT_LIST_HEIGHT_DP - FREQUENT_HEIGHT_DP

    fun segmentHeight(fontScale: Float) =
        SEGMENT_HEIGHT_DP + ((fontScale - 1f).coerceAtLeast(0f) * SEGMENT_FONT_GROWTH_DP).roundToInt()

    fun segmentWidth(fontScale: Float) =
        SEGMENT_WIDTH_DP + ((fontScale - 1f).coerceAtLeast(0f) * SEGMENT_FONT_WIDTH_GROWTH_DP).roundToInt()

    /** 只避让尚未由页面位置承担的状态栏，不保留原标题行高度。 */
    fun contentTopPadding(statusTop: Int, pageTop: Int) = (statusTop - pageTop).coerceAtLeast(0)

    /** 普通作者名接入语义前景；有彩色会员/业务强调的昵称保留宿主原色。 */
    fun isNeutralText(color: Int): Boolean {
        val r = color ushr 16 and 255
        val g = color ushr 8 and 255
        val b = color and 255
        val maximum = maxOf(r, g, b)
        return maximum - minOf(r, g, b) <= maximum / 10
    }

    fun palette(colors: HostChromeColors): LumenPalette {
        return colors.palette()
    }

    fun card() = LumenSurfacePresets.staticPanel().copy(
        role = SurfaceRole.CARD, tintOpacity = 1f, fallbackTintOpacity = 1f,
        edgeWidthDp = .5f, edgeIntensity = .18f
    )

    fun reference(palette: LumenPalette) = card().copy(
        color = palette.surfaceVariant, radiusDp = 12f, edgeWidthDp = 1f, edgeIntensity = .45f
    )

    fun track(palette: LumenPalette) = card().copy(
        role = SurfaceRole.TOP_BAR, color = palette.surfaceVariant, radiusDp = 18f, edgeEnabled = false
    )

    fun selection() = card().copy(role = SurfaceRole.SELECTED_ITEM, radiusDp = 14f, edgeIntensity = .45f)

    fun statusBand(palette: LumenPalette, inset: Int, height: Int): com.lumen.coacervation.engine.host.LumenSurfaceOptions {
        val extent = height.coerceAtLeast(1).toFloat()
        val safeInset = inset.coerceIn(0, height.coerceAtLeast(0)).toFloat()
        val preset = LumenSurfacePresets.fadingBand(
            hold = 0f,
            end = (safeInset + (height.coerceAtLeast(0) - safeInset) * STATUS_FADE_END) / extent
        )
        return preset.copy(
            color = palette.background, tintOpacity = .72f, fallbackTintOpacity = 1f,
            sampling = preset.sampling.copy(fadeCurve = LumenSurfaceFadeCurve.LINEAR)
        )
    }

    /** 以覆盖区域求并集，已有 padding 与系统 inset 不重复相加。 */
    fun bottomPadding(original: Int, viewportBottom: Int, dockTop: Int?, systemBottom: Int, gap: Int): Int =
        maxOf(original, systemBottom, dockTop?.let { (viewportBottom - it + gap).coerceAtLeast(0) } ?: 0)
}

internal data class FollowFeedReference(val identity: Long, val first: Boolean, val last: Boolean, val quoted: Boolean)

internal data class FollowFeedRow(val identity: Long, val first: Boolean, val last: Boolean, val position: Int,
                                  val reference: FollowFeedReference? = null)

internal object FollowFeedGrouping {
    fun joins(previous: FollowFeedRow, next: FollowFeedRow,
              adjacent: Boolean = next.position == previous.position + 1): Boolean =
        previous.identity > 0 && previous.identity == next.identity &&
            !previous.last && !next.first && adjacent
}
