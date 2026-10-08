package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common

import com.lumen.coacervation.engine.widget.LumenSpring
import kotlin.math.abs

/** 宿主帧时钟适配器；解析弹簧及速度由 lumen-motion 计算。只在重定向时创建弹簧。 */
internal class HostSpringAxis(value: Float = 0f, velocity: Float = 0f) {
    private var position = value
    private var speed = velocity
    private var target = Float.NaN
    private var elapsed = 0f
    private var spring: LumenSpring? = null
    private var stiffness = Float.NaN
    private var dampingRatio = Float.NaN
    var value: Float
        get() = position
        set(value) { position = value; spring = null }
    var velocity: Float
        get() = speed
        set(value) { speed = value; spring = null }

    fun advance(seconds: Float, target: Float, stiffness: Float = 310f, dampingRatio: Float = .7f) {
        if (!seconds.isFinite() || seconds <= 0f) return
        if (!position.isFinite() || !speed.isFinite() || !target.isFinite() ||
            !stiffness.isFinite() || stiffness <= 0f || !dampingRatio.isFinite() || dampingRatio <= 0f || dampingRatio >= 1f) {
            reset(if (target.isFinite()) target else 0f)
            return
        }
        if (spring == null || this.target != target || this.stiffness != stiffness || this.dampingRatio != dampingRatio) {
            this.target = target
            this.stiffness = stiffness
            this.dampingRatio = dampingRatio
            elapsed = 0f
            spring = LumenSpring(position, target, speed, stiffness, dampingRatio)
        }
        elapsed += seconds
        position = spring!!.value(elapsed)
        speed = spring!!.velocity(elapsed)
    }

    fun atRest(target: Float, tolerance: Float) = abs(position - target) <= tolerance && abs(speed) <= tolerance * 20f
    fun reset(value: Float = 0f) { position = value; speed = 0f; target = value; elapsed = 0f; spring = null }
}
