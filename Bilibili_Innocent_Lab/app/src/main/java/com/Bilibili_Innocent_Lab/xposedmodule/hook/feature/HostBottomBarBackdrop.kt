package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowContentProbe
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowContentSample
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LiveBackdropSampler
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LiveSampleProfile

/**
 * 宿主底栏下方"内容层"的运行时定位。
 *
 * 模块里底栏与内容容器是设计好的兄弟（`SettingsHomePresenter` 把 pager 交给皮肤会话），宿主没有这种
 * 已知结构：底栏挂在官方 `tab_host` 上，被它遮住的内容可能在任意层级的兄弟容器里。这里只认三条约束：
 * 1. **必须是底栏链路上的兄弟** —— 结构上排除底栏自身与它的祖先，采样不会把玻璃自己录进去（无反馈
 *    回路，与模块 `GlowBackdropTarget` 的边界同义）；
 * 2. **必须绘制在底栏之前**（同级 childIndex 更小）—— 画在底栏之上的东西不是"底下的内容"；
 * 3. 屏幕矩形与底栏相交面积足够大（≥ [MIN_COVERAGE]），否则不值得当采样源。
 *
 * 逐层向上取**最近一层**的合格者：越近的容器越是"底栏正下方那块内容"本身，录制树也越小。
 */
internal interface HostDockLayer

internal object HostBackdropLocator {
    private const val MAX_LEVELS = 8

    /** 候选必须遮住底栏/顶栏面积的比例。 */
    private const val MIN_COVERAGE = 0.25f
    private val dockRect = Rect()
    private val siblingRect = Rect()
    private val location = IntArray(2)

    fun find(dock: View): View? {
        val dockArea = dock.width.toLong() * dock.height.toLong()
        if (dockArea <= 0L || !dock.isAttachedToWindow) return null
        dock.getLocationOnScreen(location)
        dockRect.set(location[0], location[1], location[0] + dock.width, location[1] + dock.height)

        var node: View = dock
        var level = 0
        while (level++ < MAX_LEVELS) {
            val parent = node.parent as? ViewGroup ?: return null
            val nodeIndex = parent.indexOfChild(node)
            var best: View? = null
            var bestOverlap = 0L
            for (index in 0 until parent.childCount) {
                if (index == nodeIndex) continue
                val child = parent.getChildAt(index) ?: continue
                if (!child.isShown || child.alpha <= 0f) continue
                if (child.width <= 0 || child.height <= 0) continue
                if (child is HostDockLayer || child is HostBottomBarDockLayer || child is HostGlowView) continue
                child.getLocationOnScreen(location)
                siblingRect.set(location[0], location[1], location[0] + child.width, location[1] + child.height)
                val overlap = overlapArea(dockRect, siblingRect)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = child
                }
            }
            if (best != null && bestOverlap.toFloat() >= dockArea.toFloat() * MIN_COVERAGE) return best
            node = parent
        }
        return null
    }

    private fun overlapArea(a: Rect, b: Rect): Long {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0L
        return (right - left).toLong() * (bottom - top).toLong()
    }
}

/**
 * 宿主栏的实时透镜材质——默认复用模块柔光引擎的软件路径。
 * 顶栏通过 [preferGpu] 优先使用 [HostBackdropApi31]，在同帧内容节点上完成模糊与渐隐；
 * 旧系统、软件画布或节点失败时仍走原软件路径。
 *
 * 管线本身全部复用模块原类（[LiveBackdropSampler] + `LensRefractionPolicy` + `ModernMaterialPolicy`
 * 的 FLOATING 色阶），与 `FrostedMaterialRenderer.drawLiveSample` 的软件分支逐行对应；区别只在
 * 模块把采样源与窗口刷新钩子交给皮肤会话，这里由本类自己维护：
 * - 采样源是 [HostBackdropLocator] 找到的内容层（底栏的兄弟），不是模块的 pager 引用；
 * - 滚动/显式动画统一走 [onVisualMovement]（对应模块 `notifyPositionChanged`），重新采样的节奏由
 *   `LIVE_SAMPLE_MIN_INTERVAL_MS` 节流与后台单飞决定，UI 线程只录 [android.graphics.Picture]；
 * - 内容层找不到时 [draw] 返回 false，表面退回静态色罩（与模块"退回静态磨砂"同一降级）。
 */
internal class HostBottomBarBackdrop(private val density: Float, private val preferGpu: Boolean = false) {

    private var live = LiveBackdropSampler(density, ViewSamplingMatrix())
    private var gpu = createGpu()
    private var surface: View? = null
    private var content: View? = null
    private var hookedRoot: View? = null
    private var trimRegistered = false
    private var closed = false
    private var legibilityProbe: GlowContentProbe? = null
    private var contentSample: GlowContentSample? = null
    private var probeFailed = false
    private var lastProbeTime = 0L
    private val probeLocation = IntArray(2)
    private val probeRegion = RectF()
    private var tintSample: GlowContentSample? = null
    private var tintColor = 0
    private var tintBase = 0
    private var tintGoal = 0
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { onVisualMovement() }
    // 阈值沿用模块会话层原实现（FrostedMaterialRenderer.onTrimMemory）；API 34 起平台不再下发
    // RUNNING_* 级别，更高的级别照样满足条件；onLowMemory 在 API 35 起标记废弃，但仍是低端机的兜底。
    @Suppress("DEPRECATION")
    private val trimCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseMemory()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onLowMemory() = releaseMemory()

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    fun attach(surface: View, explicitContent: View? = null) {
        if (closed) {
            closed = false
            live = LiveBackdropSampler(density, ViewSamplingMatrix())
            gpu = createGpu()
        }
        this.surface = surface
        if (explicitContent != null) {
            this.content = explicitContent
            bindSource(explicitContent)
            ModernHookLog.info("[BIL] 宿主实时透镜绑定显式内容层: ${explicitContent.javaClass.name}")
        }
        hookRoot(surface)
        if (explicitContent == null) {
            revalidate()
        }
        registerTrim(surface)
    }

    /** 临时从窗口脱离（如切后台、切页）：暂停监听并释放位图，不永久销毁，再次 attach 时满血恢复 */
    fun detach() {
        clearProbe()
        unhookRoot()
        live.releaseAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) gpu?.detach()
        content = null
    }

    /** 内容层缺失或已脱离时重找；命中时 O(1) 早退，可以挂在每帧之外的布局/层级回调上。 */
    fun revalidate(explicitContent: View? = null) {
        if (closed) return
        val surface = this.surface ?: return
        hookRoot(surface)
        if (explicitContent != null) {
            if (this.content !== explicitContent) {
                clearProbe()
                this.content = explicitContent
                bindSource(explicitContent)
                ModernHookLog.info("[BIL] 宿主实时透镜更新显式内容层: ${explicitContent.javaClass.name}")
            }
            return
        }
        val current = content
        if (current != null && current.isAttachedToWindow && current.parent != null) {
            bindSource(current)
            return
        }
        val found = HostBackdropLocator.find(surface)
        ModernHookLog.info("[BIL] 宿主实时透镜寻找内容层: surface=${surface.javaClass.name}, found=${found?.javaClass?.name}")
        if (found == null) return
        clearProbe()
        content = found
        bindSource(found)
        ModernHookLog.info("[BIL] 宿主实时透镜绑定内容层: ${found.javaClass.name}")
    }

    /** 底栏自身动画/拖拽的位移通知：下一帧 pre-draw 重采样（与模块 `notifyPositionChanged` 同一入口）。 */
    fun onVisualMovement() {
        if (closed) return
        live.invalidate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) gpu?.invalidate()
    }

    /**
     * 与 `FrostedMaterialRenderer.drawLiveSample` 的软件分支一致：接管前先登记，未就绪返回 false。
     *
     * [profile] 是表面自己的采样档案（透镜开关 + 纵向渐隐）；不传即胶囊/面板的既有行为。
     * 同一 [HostBottomBarBackdrop] 上的多个表面共用一次内容层录制，档案因此必须按表面给，
     * 不能挂在采样器实例上。
     */
    fun draw(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        view: View,
        alpha: Int,
        profile: LiveSampleProfile? = null
    ): Boolean {
        if (closed || content == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            gpu?.draw(canvas, bounds, radius, view, alpha, profile) == true
        ) {
            live.unregister(view)
            return true
        }
        live.register(view, profile)
        return live.draw(canvas, bounds, radius, view, alpha)
    }

    /** 与凝光可读性探针同款后台统计，最多 8Hz；区域直接取当前胶囊（包含收岛后的裁剪）。 */
    fun legibleTintAlpha(view: View, bounds: RectF, color: Int, baseAlpha: Int): Int {
        val source = content
        val now = SystemClock.uptimeMillis()
        if (!closed && !probeFailed && source != null && source.isAttachedToWindow &&
            view.isAttachedToWindow && (now - lastProbeTime >= 125L) &&
            legibilityProbe?.inFlight != true
        ) {
            val probe = legibilityProbe ?: GlowContentProbe { samples ->
                contentSample = samples.firstOrNull()
                surface?.background?.invalidateSelf()
            }.also { legibilityProbe = it }
            view.getLocationOnScreen(probeLocation)
            val x = probeLocation[0]
            val y = probeLocation[1]
            source.getLocationOnScreen(probeLocation)
            probeRegion.set(bounds)
            probeRegion.offset((x - probeLocation[0]).toFloat(), (y - probeLocation[1]).toFloat())
            lastProbeTime = now
            runCatching {
                probe.probe(source, listOf(RectF(probeRegion))) { canvas, _ -> canvas.drawColor(color) }
            }.onFailure {
                probeFailed = true
                clearProbe()
                ModernHookLog.info("[BIL] 宿主栏可读性探针回退: ${it.javaClass.simpleName}")
            }
        }
        if (tintSample !== contentSample || tintColor != color || tintBase != baseAlpha) {
            tintSample = contentSample
            tintColor = color
            tintBase = baseAlpha
            tintGoal = HostChromeLegibility.tintAlpha(contentSample, color, baseAlpha)
        }
        return tintGoal
    }

    private fun clearProbe() {
        legibilityProbe?.close()
        legibilityProbe = null
        contentSample = null
        tintColor = 0
        lastProbeTime = 0L
    }

    fun close() {
        if (closed) return
        closed = true
        clearProbe()
        unhookRoot()
        unregisterTrim()
        live.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) gpu?.close()
        content = null
        surface = null
    }

    private fun createGpu(): HostBackdropApi31? =
        if (preferGpu && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { HostBackdropApi31(density) }.getOrNull()
        } else null

    private fun bindSource(view: View) {
        live.bindSource(view)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) gpu?.bindSource(view)
    }

    private fun releaseMemory() {
        clearProbe()
        live.releaseAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) gpu?.releaseMemory()
    }

    private fun hookRoot(surface: View) {
        val root = surface.rootView ?: return
        if (hookedRoot === root) return
        unhookRoot()
        root.viewTreeObserver.addOnScrollChangedListener(scrollListener)
        hookedRoot = root
    }

    private fun unhookRoot() {
        hookedRoot?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        hookedRoot = null
    }

    private fun registerTrim(surface: View) {
        if (trimRegistered) return
        val context = surface.context.applicationContext ?: surface.context
        runCatching { context.registerComponentCallbacks(trimCallback) }
            .onSuccess { trimRegistered = true }
    }

    private fun unregisterTrim() {
        if (!trimRegistered) return
        val context = surface?.context?.applicationContext ?: surface?.context ?: return
        trimRegistered = false
        runCatching { context.unregisterComponentCallbacks(trimCallback) }
    }
}
