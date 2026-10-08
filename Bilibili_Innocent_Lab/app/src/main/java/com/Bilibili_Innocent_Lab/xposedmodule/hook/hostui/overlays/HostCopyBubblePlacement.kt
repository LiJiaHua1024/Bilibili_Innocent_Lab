package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays

/** 保留原定位规则：文字左侧对齐、底部 20% 留白、失效锚点回退。 */
internal object HostCopyBubblePlacement {
    fun left(anchorX: Int, bubbleMaxWidth: Int, screenWidth: Int, lead: Int, margin: Int): Float {
        var x = (anchorX - lead).toFloat()
        if (x + bubbleMaxWidth > screenWidth - margin) x = (screenWidth - bubbleMaxWidth - margin).toFloat()
        return x.coerceAtLeast(margin.toFloat())
    }

    fun top(anchorY: Int, anchorHeight: Int, shown: Boolean, bubbleHeight: Int,
        screenHeight: Int, gap: Int, margin: Int): Float {
        val safeBottom = (screenHeight * .8f).toInt()
        val y = if (shown && anchorY in 0 until screenHeight) anchorY else (screenHeight * .4f).toInt()
        var top = (y + anchorHeight + gap).toFloat()
        if (top + bubbleHeight > safeBottom) top = (y - bubbleHeight - gap).toFloat()
        if (top + bubbleHeight > safeBottom) top = (safeBottom - bubbleHeight).toFloat()
        return top.coerceAtLeast(margin.toFloat())
    }
}
