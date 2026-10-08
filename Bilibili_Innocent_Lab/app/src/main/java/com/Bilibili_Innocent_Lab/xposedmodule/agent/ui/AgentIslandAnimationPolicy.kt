package com.Bilibili_Innocent_Lab.xposedmodule.agent.ui

/** 状态图标只在实际可见的活动任务中刷新，50ms 一帧，不与系统布局动画竞争。 */
internal object AgentIslandAnimationPolicy {
    const val FRAME_MS = 50L

    fun shouldAnimate(requested: Boolean, attached: Boolean, visible: Boolean,
                      animatorsEnabled: Boolean, closed: Boolean): Boolean =
        requested && attached && visible && animatorsEnabled && !closed

    fun dotAlpha(frame: Int, dot: Int): Int {
        val phase = (frame ushr 2) and 3
        val start = phaseAlpha(dot - phase)
        val end = phaseAlpha(dot - phase - 1)
        return start + (end - start) * (frame and 3) / 4
    }

    private fun phaseAlpha(distance: Int): Int = when (distance and 3) {
        0 -> 255
        1 -> 140
        2 -> 84
        else -> 56
    }
}
