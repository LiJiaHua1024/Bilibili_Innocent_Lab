package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookLog
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowChromeGlassApi31
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowContentCaptureApi31
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry.ViewSamplingMatrix
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedChromeGlassApi31
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LensRefractionPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.LiveSampleProfile
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 宿主顶栏的同帧玻璃。只录内容分支，胶囊和融合带共同引用其 RenderNode；
 * 子节点的滚动直接进入当前 HWUI 帧，不经过 Picture/后台位图回传。
 * 渐隐在模糊之后用当前几何合成，避免旧纹理曲线与新色罩错位。
 */
@RequiresApi(31)
internal class HostBackdropApi31(private val density: Float) : AutoCloseable {
    private class Entry(val glass: GlowChromeGlassApi31, val refraction: Boolean, val scale: Int) {
        var profile: LiveSampleProfile? = null
        var fadeHeight = 0
        var fade: LinearGradient? = null
    }

    private val capture = GlowContentCaptureApi31()
    private val matrices = ViewSamplingMatrix()
    private val sourceToHost = Matrix()
    private val bounds = Rect()
    private val entries = WeakHashMap<View, Entry>()
    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var source: View? = null
    private var root: View? = null
    private var dirty = true
    private var failed = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        refresh()
        true
    }

    fun bindSource(view: View) {
        if (source === view) return
        source = view
        capture.release()
        dirty = true
    }

    fun invalidate() {
        dirty = true
    }

    private fun refresh() {
        if (failed || entries.isEmpty()) return
        val content = source ?: return
        if (!dirty && !content.isDirty) return
        if (record(content)) entries.keys.forEach(View::invalidate)
    }

    private fun record(content: View): Boolean {
        if (!content.isAttachedToWindow || content.width <= 0 || content.height <= 0) return false
        return runCatching {
            val recording = capture.begin(content.width, content.height)
            try {
                content.draw(recording)
            } finally {
                capture.end()
            }
            dirty = false
            true
        }.getOrElse {
            fail(it)
            false
        }
    }

    fun draw(
        canvas: Canvas,
        fill: RectF,
        radius: Float,
        view: View,
        alpha: Int,
        profile: LiveSampleProfile?
    ): Boolean {
        if (failed || !canvas.isHardwareAccelerated) return false
        val content = source ?: return false
        // 内容不能包含表面自己，否则录制会递归并把上一帧玻璃反馈进下一帧。
        var ancestor: View? = view
        while (ancestor != null) {
            if (ancestor === content) return false
            ancestor = ancestor.parent as? View
        }
        if (content.rootView !== view.rootView || !matrices.sourceToTarget(content, view, sourceToHost)) {
            return false
        }
        val windowRoot = view.rootView
        if (root !== windowRoot) {
            unhookRoot()
            windowRoot.viewTreeObserver.addOnPreDrawListener(preDraw)
            root = windowRoot
        }
        if (!capture.recorded && !record(content)) return false
        return runCatching {
            val refraction = profile?.refraction ?: true
            val margin = LensRefractionPolicy.marginPx(density)
            val scale = LensRefractionPolicy.sampleScale(view.width + 2 * margin, view.height + 2 * margin)
            var entry = entries[view]
            if (entry == null || entry.refraction != refraction || entry.scale != scale) {
                entry?.glass?.close()
                entry = Entry(FrostedChromeGlassApi31.create(density / scale, refraction), refraction, scale)
                entries[view] = entry
                ModernHookLog.info("[BIL] 宿主同帧玻璃启用: refraction=$refraction")
            }
            // RenderEffect 的离屏层按节点局部尺寸分配。沿用软件采样的像素预算，但缩采样、
            // 模糊、放大全在当帧 GPU 内完成，避免整屏宽的大半径模糊挤占滚动帧预算。
            val sampleWidth = ceil(fill.width() / scale).toInt().coerceAtLeast(1)
            val sampleHeight = ceil(fill.height() / scale).toInt().coerceAtLeast(1)
            val scaleX = fill.width() / sampleWidth
            val scaleY = fill.height() / sampleHeight
            bounds.set(0, 0, sampleWidth, sampleHeight)
            sourceToHost.postTranslate(-fill.left, -fill.top)
            sourceToHost.postScale(1f / scaleX, 1f / scaleY)
            val fading = profile?.fades == true
            val save = if (fading) canvas.saveLayer(fill, null) else canvas.save()
            try {
                val glassSave = canvas.save()
                try {
                    canvas.translate(fill.left, fill.top)
                    canvas.scale(scaleX, scaleY)
                    entry.glass.draw(
                        canvas, bounds, radius / minOf(scaleX, scaleY), capture, 0f, 0f, alpha / 255f, 0f, 0f,
                        underlay = null, contentToHost = sourceToHost
                    )
                } finally {
                    canvas.restoreToCount(glassSave)
                }
                if (fading) {
                    maskPaint.shader = fadeFor(entry, profile, fill.height().roundToInt())
                    canvas.translate(fill.left, fill.top)
                    canvas.drawRect(0f, 0f, fill.width(), fill.height(), maskPaint)
                }
            } finally {
                canvas.restoreToCount(save)
            }
            true
        }.getOrElse {
            fail(it)
            false
        }
    }

    private fun fadeFor(entry: Entry, profile: LiveSampleProfile, height: Int): LinearGradient {
        entry.fade?.let { if (entry.profile === profile && entry.fadeHeight == height) return it }
        val positions = FloatArray(33) { it / 32f }
        val colors = IntArray(33) { index ->
            ColorUtils.setAlphaComponent(Color.BLACK,
                (255f * LensRefractionPolicy.fadeWeight(positions[index], profile.fadeHold, profile.fadeEnd))
                    .roundToInt().coerceIn(0, 255))
        }
        return LinearGradient(0f, 0f, 0f, height.toFloat(), colors, positions, Shader.TileMode.CLAMP).also {
            entry.profile = profile
            entry.fadeHeight = height
            entry.fade = it
        }
    }

    fun releaseMemory() {
        capture.release()
        entries.values.forEach { it.glass.releaseDisplayList() }
        dirty = true
    }

    fun detach() {
        unhookRoot()
        releaseMemory()
        entries.values.forEach { it.glass.close() }
        entries.clear()
        source = null
    }

    private fun unhookRoot() {
        root?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(preDraw)
        root = null
    }

    private fun fail(error: Throwable) {
        failed = true
        ModernHookLog.error("[BIL] 宿主同帧玻璃回退软件采样: $error")
        entries.keys.forEach(View::invalidate)
        detach()
    }

    override fun close() = detach()
}
