package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LensRefractionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LiveSampleProfile
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import kotlin.math.roundToInt

/**
 * 顶部融合带的几何与曲线：纯标量，不碰 android.graphics，可在 JVM 单测里跑。
 *
 * **滚动玻璃的满强度只留给状态栏，渐隐落在"内容静止顶边之上"；回顶时搜索区逐渐变为实色。** 内容静止顶边 = 顶栏容器顶边 +
 * 列表基础内边距（首页收起态 = 状态栏下沿 + 154px，即胶囊行下沿再往下一点）。这样：
 * - 推荐流第一排卡片停在静止位置时**完全清晰**——它在屏幕最下方时也不能被糊到（用户往上滑不动它，
 *   糊了就永远看不到清晰的），这是把渐隐收口放在它上方的唯一原因；
 * - 滚动时卡片从渐隐区穿过，仍然是从模糊渐渐变清晰，没有分界线。
 */
internal object HostTopFusionPolicy {

    /** 回顶前最后一段手势距离；进度直接跟手，两端的速度均收敛到零。 */
    const val DOCK_RANGE_DP = 208f

    fun dockingProgress(distance: Float, range: Float): Float {
        if (!distance.isFinite()) return 0f
        if (range <= 0f) return if (distance <= 0f) 1f else 0f
        val t = (1f - distance / range).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * 渐隐收尾处离"内容静止顶边"的留白（dp）。留白比 smoothstep 的收口更靠上，
     * 静止态的卡片顶边落在权重严格为 0 的一侧，不存在"第一排永远带一点糊"的情况。
     */
    const val CONTENT_GAP_DP = 8f

    /** 带子高度相对"内容静止顶边"的余量（px）：顶边本身取整后仍能盖住渐隐收尾。 */
    const val HEIGHT_SLACK_PX = 2

    /** 满强度区：状态栏整段（挖孔/异形屏也计入）吃满模糊。 */
    fun holdFraction(statusBarInset: Int, bandHeight: Int): Float {
        if (bandHeight <= 0) return 1f
        return (statusBarInset.toFloat() / bandHeight).coerceIn(0f, 1f)
    }

    /** 内容静止顶边在屏幕上的位置。 */
    fun contentRestTop(containerTop: Int, basePadding: Int): Int = containerTop + basePadding

    /** 融合带高度：内容静止顶边（+ 取整余量），保证渐隐能在带内收干净。 */
    fun bandHeight(contentRestTop: Int): Int = contentRestTop + HEIGHT_SLACK_PX

    /**
     * 渐隐收尾位置的归一化值：内容静止顶边往上留 [CONTENT_GAP_DP]，并夹在满强度区之后。
     * 夹到 [hold] 意味着"渐隐在满强度区结束处就收完"，绝不会越过内容静止顶边。
     */
    fun fadeEndFraction(contentRestTop: Int, density: Float, statusBarInset: Int, bandHeight: Int): Float {
        if (bandHeight <= 0) return 1f
        val gap = (CONTENT_GAP_DP * density).roundToInt()
        val end = ((contentRestTop - gap).toFloat() / bandHeight).coerceIn(0f, 1f)
        return end.coerceAtLeast(holdFraction(statusBarInset, bandHeight))
    }

    /** 归一化高度 → alpha 权重；与采样纹理的逐像素渐隐共用同一条曲线。 */
    fun fadeWeight(fraction: Float, hold: Float, end: Float): Float =
        LensRefractionPolicy.fadeWeight(fraction, hold, end)
}

/**
 * 顶部"状态栏融合带"：铺满窗口顶边的整幅宽磨砂，把滚动到状态栏/顶栏下方的视频卡片
 * 变成一层渐渐消隐的模糊，而不是被官方顶栏在某个 y 上齐齐切断。
 *
 * 四条几何约定：
 * 1. **锚在窗口顶边**：视图由控制器放在顶栏所在容器里，靠 `translationY = -容器屏幕顶边`
 *    把自身顶边钉在窗口 y = 0，于是局部坐标与屏幕坐标一一对应；
 * 2. **滚动时夹在内容与顶栏之间，回顶时退回内容层**：`translationZ` 随占位进度降到 0，
 *    模糊逐渐退场、搜索区变为实色，顶栏胶囊的层级和手势不变；
 * 3. **渐隐收口在内容静止顶边之上**（见 [HostTopFusionPolicy]）：第一排卡片停在静止位置时
 *    完全清晰，滚动中穿过渐隐区的卡片仍然平滑地由糊转清；
 * 4. **曲线两端都收敛**：满强度保持到状态栏下沿，之后按 smoothstep 减到 0，所以状态栏下沿
 *    与底边都不会出现分界线。
 *
 * 模糊本身来自 [HostBottomBarBackdrop]：API 31+ 在同帧内容节点上完成模糊与渐隐，
 * 与顶栏胶囊共用内容录制；旧系统保留后台软件采样。色罩始终使用同一条渐隐曲线。
 */
@SuppressLint("ViewConstructor")
internal class HostTopStatusFusionView(
    context: Context,
    private val density: Float,
    palette: MonetColors,
    style: ModernSurfaceStyle,
    private val backdrop: HostBottomBarBackdrop?,
    private val statusBarInset: Int,
    initialHeight: Int
) : View(context), HostDockLayer {

    /** 融合带总高度；只在内容静止顶边变低（宿主 AppBar 展开得更多）时长高，不会缩回去。 */
    var bandHeight: Int = initialHeight
        private set

    /**
     * 逐帧重采样：这条带子横跨整屏、又带一段盖在清晰内容上的渐隐，28ms 节流留下的
     * "滞后—跳变"在这里肉眼可见（表现为整层模糊在抖）。胶囊/底栏不传这个档案，节奏不变。
     */
    private val profileIntervalMs = 0L

    /** 与色罩共用同一条曲线；折射关闭——整幅宽表面上的透镜外推会变成可见的横向形变。 */
    var profile: LiveSampleProfile = buildProfile(1f)
        private set

    private var palette: MonetColors = palette
    private var style: ModernSurfaceStyle = style
    private val fill = RectF()
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scrim: LinearGradient? = null
    private var scrimHeight = 0
    private var dockingProgress = 1f
    private var dockingBottom = 0

    /** 同一进度控制模糊退场与实色占位；满进度时与内容同层，顶栏仍在其上。 */
    fun updateDocking(progress: Float, bottom: Int) {
        if (progress == dockingProgress && bottom == dockingBottom) return
        dockingProgress = progress
        dockingBottom = bottom
        translationZ = 2f * density * (1f - progress)
        scrim = null
        invalidate()
    }

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

    /** 内容静止顶边或带高变化后重算渐隐收口；曲线没变就不动，避免无谓重采。 */
    fun updateFade(contentRestTop: Int) {
        val height = HostTopFusionPolicy.bandHeight(contentRestTop)
        if (height > bandHeight) {
            bandHeight = height
            val lp = layoutParams
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.height = height
                layoutParams = lp
            }
            scrim = null
        }
        val end = HostTopFusionPolicy.fadeEndFraction(contentRestTop, density, statusBarInset, bandHeight)
        if (end == profile.fadeEnd) return
        profile = buildProfile(end)
        scrim = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        fill.set(0f, 0f, w.toFloat(), h.toFloat())
        // 采样未就绪时只留色罩：与官方顶栏的纯色底同一观感，不会闪出空洞。
        if (dockingProgress < 1f) {
            backdrop?.draw(canvas, fill, 0f, this, (255 * (1f - dockingProgress)).roundToInt(), profile)
        }
        scrimPaint.shader = scrimFor(h)
        canvas.drawRect(fill, scrimPaint)
    }

    private fun buildProfile(fadeEnd: Float) = LiveSampleProfile(
        refraction = false,
        fadeHold = HostTopFusionPolicy.holdFraction(statusBarInset, bandHeight),
        fadeEnd = fadeEnd,
        minIntervalMs = profileIntervalMs
    )

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
        val end = profile.fadeEnd
        val dockHold = (dockingBottom.toFloat() / height).coerceIn(0f, end)
        val opaqueColor = if (ColorUtils.calculateLuminance(palette.surface) > 0.5) 0xFFFFFFFF.toInt() else palette.surface
        val color = ColorUtils.blendARGB(palette.surface, opaqueColor, dockingProgress)
        for (i in 0..SCRIM_STOPS) {
            val fraction = i / SCRIM_STOPS.toFloat()
            positions[i] = fraction
            colors[i] = ColorUtils.setAlphaComponent(
                color,
                ((1f - dockingProgress) * style.tintAlpha * HostTopFusionPolicy.fadeWeight(fraction, hold, end) +
                    dockingProgress * 255f * HostTopFusionPolicy.fadeWeight(fraction, dockHold, end))
                    .roundToInt().coerceIn(0, 255)
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
