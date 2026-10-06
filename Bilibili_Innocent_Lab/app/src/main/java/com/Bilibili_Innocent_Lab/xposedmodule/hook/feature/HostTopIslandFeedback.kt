package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction.ElasticSpringAxis
import kotlin.math.PI
import kotlin.math.sin

/** 按压立即响应；忙碌状态跟随宿主，而不是靠固定时长猜测刷新结束。 */
internal class HostTopIslandFeedback {
    enum class Operation { NONE, TOP, REFRESH }
    private val press = ElasticSpringAxis()
    private var pressed = false
    private var clickedAt = -1L
    private var lastOperation = Operation.NONE
    var operation = Operation.NONE
        private set
    var refreshing = false
        private set

    val busy get() = refreshing || operation != Operation.NONE
    val pressure get() = press.value.coerceIn(0f, 1f)
    val scale get() = 1f - .07f * pressure

    fun setPressed(value: Boolean) { pressed = value }

    fun clicked(action: Operation, now: Long) {
        operation = action
        lastOperation = action
        clickedAt = now
        // 无障碍点击或极短轻点也有完整的按下、回弹反馈。
        press.value = maxOf(press.value, .65f)
        if (action == Operation.REFRESH) refreshing = true
    }

    fun updateState(atTop: Boolean, nativeRefreshing: Boolean) {
        refreshing = nativeRefreshing
        if (operation == Operation.REFRESH && !nativeRefreshing || operation == Operation.TOP && atTop) {
            operation = Operation.NONE
        }
    }

    fun advance(seconds: Float) = press.advance(seconds.coerceAtMost(.05f), if (pressed) 1f else 0f, 420f, .8f)

    fun arrowOffset(now: Long): Float {
        val elapsed = now - clickedAt
        return if (lastOperation == Operation.TOP && clickedAt >= 0L && elapsed in 0L..320L)
            -3f * sin(elapsed / 320f * PI).toFloat() else 0f
    }

    fun showTopArrow(now: Long) = operation == Operation.TOP ||
        lastOperation == Operation.TOP && clickedAt >= 0L && now - clickedAt in 0L..320L

    fun needsFrame(now: Long) = busy || !press.atRest(if (pressed) 1f else 0f, .001f) ||
        clickedAt >= 0L && now - clickedAt in 0L..320L

    fun reset() {
        pressed = false
        press.reset()
        operation = Operation.NONE
        refreshing = false
        clickedAt = -1L
        lastOperation = Operation.NONE
    }
}
