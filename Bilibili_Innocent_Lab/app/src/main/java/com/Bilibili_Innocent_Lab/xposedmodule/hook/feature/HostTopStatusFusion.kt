package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LensRefractionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LiveSampleProfile
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import kotlin.math.roundToInt

/**
 * 顶部融合带的几何与曲线：纯标量，不碰 android.graphics，可在 JVM 单测里跑。
 *
 * 顶栏胶囊**收起后紧贴状态栏**（首页实测：容器顶边 = 状态栏下沿 90px，胶囊占 6 + 44 + 6dp），
 * 所以满强度区只要覆盖"状态栏 + 胶囊那一行"就够：卡片顶边开始的地方正好起渐隐，
 * 既不会让模糊糊到胶囊上，也不会在胶囊下方留出一段突兀的全强度带。
 */
internal object HostTopFusionPolicy {

    /** 满强度保持到状态栏下沿再往下这么多（dp）：正好是顶栏胶囊收起后的那一行。 */
    const val HOLD_DP = 56f

    /** 满强度之后继续渐隐到零的长度（dp）；再往下就是完全清晰的内容。 */
    const val FADE_DP = 44f

    /** 融合带总高度：状态栏 + 满强度区 + 渐隐尾巴。 */
    fun bandHeight(statusBarInset: Int, density: Float): Int =
        statusBarInset + ((HOLD_DP + FADE_DP) * density).roundToInt()

    /** 满强度区在带子内的归一化位置；带子为空时退化为"整条都满强度"。 */
    fun holdFraction(statusBarInset: Int, density: Float): Float {
        val height = bandHeight(statusBarInset, density)
        if (height <= 0) return 1f
        return ((statusBarInset + HOLD_DP * density) / height).coerceIn(0f, 1f)
    }

    /** 归一化高度 → alpha 权重；与采样纹理的逐像素渐隐共用同一条曲线。 */
    fun fadeWeight(fraction: Float, hold: Float): Float =
        LensRefractionPolicy.fadeWeight(fraction, hold, 1f)
}

/**
 * 顶部"状态栏融合带"：铺满窗口顶边的整幅宽磨砂，把滚动到状态栏/顶栏下方的视频卡片
 * 变成一层渐渐消隐的模糊，而不是被官方顶栏在某个 y 上齐齐切断。
 *
 * 三条几何约定：
 * 1. **锚在窗口顶边**：视图由控制器放在顶栏所在容器里，靠 `translationY = -容器屏幕顶边`
 *    把自身顶边钉在窗口 y = 0，于是局部坐标与屏幕坐标一一对应，渐隐曲线可以直接按
 *    "状态栏高度"这类屏幕量写死，不必随宿主 AppBar 的折叠位移重算；
 * 2. **Z 序夹在内容与顶栏之间**：`translationZ` 低于顶栏胶囊、高于 ViewPager，模糊因此
 *    永远在顶栏之下（顶栏自身的玻璃与文字不被这层糊到），却又盖在卡片之上；
 * 3. **曲线两端都收敛**：满强度保持到 [HostTopFusionPolicy.holdFraction]，之后按 smoothstep
 *    减到 0，底边正好是曲线的零点，所以带子下沿与未模糊的内容**无缝相接**，不会出现一条分界线。
 *
 * 模糊本身来自 [HostBottomBarBackdrop] 的实时透镜采样（与顶栏胶囊共用同一次内容层录制），
 * 渐隐曲线则在后台线程按行烘进采样纹理（见 [LiveSampleProfile]），UI 线程只画一张
 * BitmapShader 矩形加一道同曲线的色罩。
 */
@SuppressLint("ViewConstructor")
internal class HostTopStatusFusionView(
    context: Context,
    density: Float,
    palette: MonetColors,
    style: ModernSurfaceStyle,
    private val backdrop: HostBottomBarBackdrop?,
    statusBarInset: Int
) : View(context), HostDockLayer {

    /** 融合带总高度：状态栏 + 顶栏胶囊行 + 渐隐尾巴。 */
    val bandHeight: Int = HostTopFusionPolicy.bandHeight(statusBarInset, density)

    /** 与色罩共用同一条曲线；折射关闭——整幅宽表面上的透镜外推会变成可见的横向形变。 */
    val profile = LiveSampleProfile(
        refraction = false,
        fadeHold = HostTopFusionPolicy.holdFraction(statusBarInset, density),
        fadeEnd = 1f
    )

    private var palette: MonetColors = palette
    private var style: ModernSurfaceStyle = style
    private val fill = RectF()
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scrim: LinearGradient? = null
    private var scrimHeight = 0

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isHapticFeedbackEnabled = false
        // 顶边还没对齐到窗口顶边之前不画：第一帧画出来会是一块错位的白雾（见 HostTopFusionBinding.sync）。
        alpha = 0f
    }

    /** 深浅色切换：色罩跟着换色阶（采样纹理不受影响，它只带 alpha 曲线）。 */
    fun updateMaterial(palette: MonetColors, style: ModernSurfaceStyle) {
        if (this.palette.surface == palette.surface && this.style.tintAlpha == style.tintAlpha) return
        this.palette = palette
        this.style = style
        scrim = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        fill.set(0f, 0f, w.toFloat(), h.toFloat())
        // 采样未就绪时只留色罩：与官方顶栏的纯色底同一观感，不会闪出空洞。
        backdrop?.draw(canvas, fill, 0f, this, 255, profile)
        scrimPaint.shader = scrimFor(h)
        canvas.drawRect(fill, scrimPaint)
    }

    /**
     * 色罩 = 玻璃色阶 ([ModernSurfaceStyle.tintAlpha]) 乘同一条渐隐曲线，用多段折线逼近
     * smoothstep：线性渐变的每一段都是直线，段数够密（[SCRIM_STOPS]）就与逐像素曲线无差，
     * 同时避开 `ComposeShader` 的 API 级别限制与 `saveLayer` 的离屏开销。
     */
    private fun scrimFor(height: Int): LinearGradient {
        scrim?.let { if (scrimHeight == height) return it }
        val colors = IntArray(SCRIM_STOPS + 1)
        val positions = FloatArray(SCRIM_STOPS + 1)
        val hold = profile.fadeHold
        for (i in 0..SCRIM_STOPS) {
            val fraction = i / SCRIM_STOPS.toFloat()
            positions[i] = fraction
            colors[i] = ColorUtils.setAlphaComponent(
                palette.surface,
                (style.tintAlpha * HostTopFusionPolicy.fadeWeight(fraction, hold)).toInt().coerceIn(0, 255)
            )
        }
        return LinearGradient(0f, 0f, 0f, height.toFloat(), colors, positions, Shader.TileMode.CLAMP)
            .also {
                scrim = it
                scrimHeight = height
            }
    }

    private companion object {
        /** 色罩折线段数：够密即可，逐段线性在模糊底上无可见折点。 */
        const val SCRIM_STOPS = 32
    }
}
