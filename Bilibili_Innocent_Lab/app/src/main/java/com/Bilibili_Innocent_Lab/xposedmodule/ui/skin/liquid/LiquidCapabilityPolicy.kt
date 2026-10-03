package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.LiquidRenderBackend
import kotlin.math.ceil
import kotlin.math.sqrt

/** 不依赖 Android 对象的 Liquid 后端候选策略，便于 JVM 单测覆盖完整兼容矩阵。 */
internal object LiquidCapabilityPolicy {
    const val REFRACTION_MIN_API = 33
    const val BLUR_MIN_API = 31

    /**
     * RuntimeShader 与 RenderEffect 都只在硬件加速 Canvas 上启用。
     * 软件 Canvas 直接使用零图形资源依赖的半透明后端。
     */
    fun candidateOrder(
        sdkInt: Int,
        hardwareAccelerated: Boolean
    ): List<LiquidRenderBackend> = buildList {
        if (hardwareAccelerated && sdkInt >= REFRACTION_MIN_API) {
            add(LiquidRenderBackend.REFRACTION)
        }
        if (hardwareAccelerated && sdkInt >= BLUR_MIN_API) {
            add(LiquidRenderBackend.BLUR)
        }
        add(LiquidRenderBackend.TRANSLUCENT)
    }
}

/**
 * 一次 Activity 会话的单向降级游标。
 *
 * 已失败的高阶后端在当前会话内不会再次尝试，避免厂商图形实现持续抛错造成重绘循环。
 */
internal class LiquidBackendFallbackPlan(
    candidates: List<LiquidRenderBackend>
) {
    private val orderedCandidates = candidates.distinct()
    private var index = 0

    init {
        require(orderedCandidates.isNotEmpty()) { "Liquid backend candidates must not be empty" }
        require(orderedCandidates.last() == LiquidRenderBackend.TRANSLUCENT) {
            "Liquid backend candidates must end with the translucent fallback"
        }
    }

    val current: LiquidRenderBackend?
        get() = orderedCandidates.getOrNull(index)

    /** 只接受当前后端的失败；旧后端迟到失败不能跳过新的降级档。 */
    fun advanceAfterFailure(failed: LiquidRenderBackend): LiquidRenderBackend? {
        if (current != failed) return current
        index++
        return current
    }
}

internal data class LiquidBackdropSize(
    val width: Int,
    val height: Int
) {
    val byteCount: Long
        get() = width.toLong() * height.toLong() * LiquidBackdropSizingPolicy.BYTES_PER_PIXEL
}

/**
 * 稳定 backdrop 的两套尺寸预算，用途不同、不能合并：
 *
 * - **光学采样**（[LiquidBackdropSizingPolicy.resolve]）：固定 0.25x 窗口并限制在 2 MiB。
 *   玻璃折射、抑制遮罩、实时对比都只采它，而且它本身还要经 `LiquidOpticalSamplingPolicy`
 *   模糊，分辨率再高也不进可见画面。
 * - **呈现**（[LiquidBackdropSizingPolicy.resolvePresentation]）：根背景直接铺满窗口的那一张。
 *   自定义照片必须按窗口原生像素呈现——旧实现让呈现与采样共用同一张 0.25x 位图，等于把照片
 *   放大 4 倍铺满屏幕，用户看到的就是"图片变糊、像被压缩过"（2026-10-03 反馈）。上限 8M
 *   像素（ARGB_8888 约 32 MiB）覆盖 1440×3200 等真机分辨率，等比缩放到预算内保证不变形。
 *
 * 未设自定义图时的自动氛围底图仍只按 [LiquidBackdropSizingPolicy.resolve] 生成：它只有低频
 * 渐变与光晕，放大铺满不会糊，而它在 bindRoot 的主线程上同步光栅化，提分辨率只换来卡顿。
 */
internal object LiquidBackdropSizingPolicy {
    const val SAMPLE_SCALE = 0.25
    const val MAX_BUFFER_BYTES = 2L * 1024L * 1024L
    const val BYTES_PER_PIXEL = 4L
    const val MAX_PRESENTATION_PIXELS = 8L * 1024L * 1024L
    private const val MAX_PIXELS = MAX_BUFFER_BYTES / BYTES_PER_PIXEL

    fun resolve(fullWidth: Int, fullHeight: Int): LiquidBackdropSize {
        require(fullWidth > 0 && fullHeight > 0) { "Backdrop dimensions must be positive" }
        return bounded(
            ceil(fullWidth * SAMPLE_SCALE).toInt(),
            ceil(fullHeight * SAMPLE_SCALE).toInt(),
            MAX_PIXELS
        )
    }

    /** 根背景的呈现尺寸：窗口原生像素，只在超过 [MAX_PRESENTATION_PIXELS] 时等比缩小。 */
    fun resolvePresentation(fullWidth: Int, fullHeight: Int): LiquidBackdropSize {
        require(fullWidth > 0 && fullHeight > 0) { "Backdrop dimensions must be positive" }
        return bounded(fullWidth, fullHeight, MAX_PRESENTATION_PIXELS)
    }

    /** 等比缩到像素预算内；尾部的减法收缩兜住取整后仍越界的边角。 */
    private fun bounded(width: Int, height: Int, budget: Long): LiquidBackdropSize {
        var w = width.coerceAtLeast(1)
        var h = height.coerceAtLeast(1)
        val pixels = w.toLong() * h.toLong()
        if (pixels > budget) {
            val scale = sqrt(budget.toDouble() / pixels.toDouble())
            w = (w * scale).toInt().coerceAtLeast(1)
            h = (h * scale).toInt().coerceAtLeast(1)
            while (w.toLong() * h.toLong() > budget) {
                if (w >= h && w > 1) w-- else if (h > 1) h-- else break
            }
        }
        return LiquidBackdropSize(w, h)
    }
}
