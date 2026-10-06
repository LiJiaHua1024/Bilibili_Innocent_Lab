package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

/** 可复用的行内连接几何；不读取父 View，滚动／回收不能改变同一条分支的形状。 */
internal class ReplyTopologyBranchGeometry {
    var startX = 0f
        private set
    var startY = 0f
        private set
    var control1X = 0f
        private set
    var control1Y = 0f
        private set
    var control2X = 0f
        private set
    var control2Y = 0f
        private set
    var endX = 0f
        private set
    var endY = 0f
        private set

    fun update(parentX: Float, nodeX: Float, rowTop: Float, rowBottom: Float): Boolean {
        if (!parentX.isFinite() || !nodeX.isFinite() || !rowTop.isFinite() || !rowBottom.isFinite() ||
            rowBottom <= rowTop
        ) return false
        val centerY = rowTop * 0.5f + rowBottom * 0.5f
        startX = parentX
        startY = rowTop
        control1X = parentX
        control1Y = centerY
        control2X = nodeX
        control2Y = rowTop
        endX = nodeX
        endY = centerY
        return true
    }
}
