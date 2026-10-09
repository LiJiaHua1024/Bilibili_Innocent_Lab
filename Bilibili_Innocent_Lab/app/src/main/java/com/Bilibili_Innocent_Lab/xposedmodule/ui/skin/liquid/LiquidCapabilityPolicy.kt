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

/** 光学采样限制为 0.25x / 2 MiB；自定义图片的显示底图使用独立的原生分辨率预算。 */
internal object LiquidBackdropSizingPolicy {
    const val SAMPLE_SCALE = 0.25
    const val MAX_BUFFER_BYTES = 2L * 1024L * 1024L
    const val BYTES_PER_PIXEL = 4L
    const val MAX_PRESENTATION_PIXELS = 8L * 1024L * 1024L
    private const val MAX_PIXELS = MAX_BUFFER_BYTES / BYTES_PER_PIXEL

    /** 覆盖手机原生分辨率；极大窗口等比缩小，极窄窗口也不能突破预算。 */
    fun resolvePresentation(fullWidth: Int, fullHeight: Int): LiquidBackdropSize {
        require(fullWidth > 0 && fullHeight > 0) { "Backdrop dimensions must be positive" }
        val pixels = fullWidth.toLong() * fullHeight.toLong()
        if (pixels <= MAX_PRESENTATION_PIXELS) return LiquidBackdropSize(fullWidth, fullHeight)
        val scale = minOf(sqrt(MAX_PRESENTATION_PIXELS.toDouble() / pixels),
            MAX_PRESENTATION_PIXELS.toDouble() / fullWidth,
            MAX_PRESENTATION_PIXELS.toDouble() / fullHeight)
        val height = (fullHeight * scale).toInt().coerceAtLeast(1)
        val width = (fullWidth * scale).toInt().coerceIn(1, (MAX_PRESENTATION_PIXELS / height).toInt())
        return LiquidBackdropSize(width, height)
    }

    /** 预览按控件的实际像素解码，超过现有位图预算才等比缩小。 */
    fun resolvePreview(viewWidth: Int, viewHeight: Int): LiquidBackdropSize {
        require(viewWidth > 0 && viewHeight > 0) { "Preview dimensions must be positive" }
        val pixels = viewWidth.toLong() * viewHeight.toLong()
        if (pixels <= MAX_PIXELS) return LiquidBackdropSize(viewWidth, viewHeight)
        // 同时约束单边，防止极窄视图的另一边取整为 1 后突破像素预算。
        val scale = minOf(sqrt(MAX_PIXELS.toDouble() / pixels),
            MAX_PIXELS.toDouble() / viewWidth, MAX_PIXELS.toDouble() / viewHeight)
        val height = (viewHeight * scale).toInt().coerceAtLeast(1)
        val width = (viewWidth * scale).toInt().coerceIn(1, (MAX_PIXELS / height).toInt())
        return LiquidBackdropSize(width, height)
    }

    fun resolve(fullWidth: Int, fullHeight: Int): LiquidBackdropSize {
        require(fullWidth > 0 && fullHeight > 0) { "Backdrop dimensions must be positive" }
        var width = ceil(fullWidth * SAMPLE_SCALE).toInt().coerceAtLeast(1)
        var height = ceil(fullHeight * SAMPLE_SCALE).toInt().coerceAtLeast(1)
        val sampledPixels = width.toLong() * height.toLong()
        if (sampledPixels > MAX_PIXELS) {
            val scale = sqrt(MAX_PIXELS.toDouble() / sampledPixels.toDouble())
            width = (width * scale).toInt().coerceAtLeast(1)
            height = (height * scale).toInt().coerceAtLeast(1)
            while (width.toLong() * height.toLong() > MAX_PIXELS) {
                if (width >= height && width > 1) width-- else if (height > 1) height-- else break
            }
        }
        return LiquidBackdropSize(width, height)
    }
}
