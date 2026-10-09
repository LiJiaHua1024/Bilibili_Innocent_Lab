package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay


/**
 * 脉络悬浮窗折叠/展开的纯动效规格：时长、紧凑高度、透明度行高度与 alpha 的同步插值。
 *
 * 设计映射自设置备份页的容器动效纪律：高度是唯一几何通道（内容不做非等比缩放），
 * 透明度行与面板总高按同一进度同步收缩/生长，使状态行文字的位置全程连续——
 * 文字以"连贯位移"方式收进标题下方或自标题下方展开，不发生任何占位跳变。
 * 列表为权重行，随剩余高度连续压缩/展开，内容以位移方式滑出/滑入视野。
 * 动画进行中的重复触发由调用方消费，不在本规格内。无 Android 依赖，供 JVM 测试锁定。
 */
internal object ReplyTopologyCompactMotionSpec {

    /** 折叠态保留的标题行与状态行高度（dp），以及展开态独有的透明度行高度（dp）。
     *  数值必须与 PanelView 的 createHeader/createOpacityRow/createStatusRow 保持一致。 */
    const val HEADER_HEIGHT_DP = 48
    const val STATUS_HEIGHT_DP = 38
    const val OPACITY_ROW_HEIGHT_DP = 34

    /** 折叠态总高度（dp）= 标题行 + 状态行（透明度行与列表不参与布局）。 */
    const val COMPACT_HEIGHT_DP = HEADER_HEIGHT_DP + STATUS_HEIGHT_DP

    /** 收起时长略短于展开：收起是"让出视野"，用户对它的等待容忍度更低。 */
    const val COLLAPSE_DURATION_MS = 240L
    const val EXPAND_DURATION_MS = 280L

    /**
     * 校验展开/紧凑高度关系，非法输入直接抛出，避免动画以负值或倒置区间运行。
     * @return 校验通过的 (expandedPx, compactPx)
     */
    fun requireHeights(expandedPx: Int, compactPx: Int): Pair<Int, Int> =
        com.lumen.coacervation.engine.motion.CompactMotionPolicy.requireHeights(expandedPx, compactPx)

    fun heightAt(progress: Float, fromPx: Int, toPx: Int): Int =
        com.lumen.coacervation.engine.motion.CompactMotionPolicy.heightAt(progress, fromPx, toPx)

    fun opacityRowAlphaAt(progress: Float, collapsing: Boolean): Float =
        com.lumen.coacervation.engine.motion.CompactMotionPolicy.rowAlphaAt(progress, collapsing)

    fun requireExpandedHeight(expandedPx: Int, compactPx: Int): Int =
        requireHeights(expandedPx, compactPx).first
}
