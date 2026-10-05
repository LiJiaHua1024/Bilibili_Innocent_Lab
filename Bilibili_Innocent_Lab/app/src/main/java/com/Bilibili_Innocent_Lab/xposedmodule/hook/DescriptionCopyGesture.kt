package com.Bilibili_Innocent_Lab.xposedmodule.hook

/** 简介长按候选；一旦让出给滑动，本次触摸不能再由其他长按入口接管。 */
internal class DescriptionCopyGesture {
    private enum class Phase { IDLE, PENDING, CANCELLED, HANDLED }

    private var phase = Phase.IDLE
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var slop = 0f
    var pointerId: Int = -1
        private set

    val isTracking: Boolean get() = phase != Phase.IDLE
    val isPending: Boolean get() = phase == Phase.PENDING

    fun begin(timeMillis: Long, pointer: Int, x: Float, y: Float, touchSlop: Float) {
        downTime = timeMillis
        pointerId = pointer
        downX = x
        downY = y
        slop = touchSlop
        phase = if (pointer >= 0 && x.isFinite() && y.isFinite() &&
            touchSlop.isFinite() && touchSlop > 0f
        ) Phase.PENDING else Phase.CANCELLED
    }

    fun matches(timeMillis: Long): Boolean = isTracking && downTime == timeMillis

    /** 返回 true 只表示本次采样刚刚取消候选，不消费或重放宿主事件。 */
    fun sample(timeMillis: Long, pointer: Int, pointerCount: Int, x: Float, y: Float): Boolean {
        if (!matches(timeMillis) || !isPending) return false
        val dx = (x - downX).toDouble()
        val dy = (y - downY).toDouble()
        if (pointerCount != 1 || pointer != pointerId || !x.isFinite() || !y.isFinite() ||
            dx * dx + dy * dy > slop.toDouble() * slop
        ) {
            phase = Phase.CANCELLED
            return true
        }
        return false
    }

    fun cancel(timeMillis: Long): Boolean {
        if (!matches(timeMillis) || !isPending) return false
        phase = Phase.CANCELLED
        return true
    }

    fun claim(timeMillis: Long, eventTime: Long): Boolean {
        if (!matches(timeMillis) || !isPending || eventTime < downTime ||
            eventTime - downTime < LONG_PRESS_MILLIS
        ) return false
        phase = Phase.HANDLED
        return true
    }

    fun reset() {
        phase = Phase.IDLE
        pointerId = -1
        downTime = 0L
    }

    /** 气泡关闭只释放已认领状态，不能终止退场期间刚开始的新手势。 */
    fun finishHandled() {
        if (phase == Phase.HANDLED) reset()
    }

    companion object {
        const val LONG_PRESS_MILLIS = 400L
    }
}
