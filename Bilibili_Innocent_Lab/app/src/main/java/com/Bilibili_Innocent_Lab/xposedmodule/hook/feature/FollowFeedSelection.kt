package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.model.LumenPalette
import com.lumen.coacervation.engine.widget.LumenSlidingSelection
import kotlin.math.roundToInt

/** 保留原 TabLayout、TabView、监听与引用；只把新控件的点击转回原 TabView。 */
internal class FollowFeedSelection private constructor(
    private val tabs: ViewGroup,
    private val parent: ViewGroup,
    private val tabViews: List<View>,
    private val labels: List<String>,
    private val selected: java.lang.reflect.Method,
    private val session: LumenSurfaceSession,
    initialPalette: LumenPalette
) : AutoCloseable {
    private val oldIndex = parent.indexOfChild(tabs)
    private val oldParams = tabs.layoutParams
    private val originalHeight = oldParams.height
    private val oldAlpha = tabs.alpha
    private val oldAccessibility = tabs.importantForAccessibility
    private val density = tabs.resources.displayMetrics.density
    private val wrapper = FrameLayout(tabs.context)
    private var palette = initialPalette
    private var trackBinding: LumenSurfaceBinding? = null
    private var indicatorBinding: LumenSurfaceBinding? = null
    private val titles = ArrayList<TextView>()
    val choice = LumenSlidingSelection(tabs.context, ColorDrawable(Color.TRANSPARENT),
        orientation = LinearLayout.HORIZONTAL, notifyPositionChanged = session::notifyPositionChanged)
    private var closed = false
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = pause()
    }

    init {
        try {
            parent.removeView(tabs)
            parent.addView(wrapper, oldIndex, oldParams)
            wrapper.addView(tabs, FrameLayout.LayoutParams(-1, -1))
            tabs.alpha = 0f
            tabs.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val inset = dp(FollowFeedStyle.SEGMENT_INSET_DP)
            choice.setPadding(inset, inset, inset, inset)
            labels.forEach { label ->
                val title = TextView(tabs.context).apply {
                    text = label
                    textSize = FollowFeedStyle.SEGMENT_TEXT_SP
                    gravity = Gravity.CENTER
                    minHeight = dp(FollowFeedStyle.OPTION_HEIGHT_DP)
                    setPadding(dp(FollowFeedStyle.CONTENT_DP), dp(FollowFeedStyle.OPTION_VERTICAL_INSET_DP),
                        dp(FollowFeedStyle.CONTENT_DP), dp(FollowFeedStyle.OPTION_VERTICAL_INSET_DP))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                titles += title
                choice.addOption(title, LinearLayout.LayoutParams(0, -2, 1f))
            }
            wrapper.addView(choice, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
                leftMargin = dp(FollowFeedStyle.OUTER_DP); rightMargin = dp(FollowFeedStyle.OUTER_DP)
            })
            // 保留原 AppBar 子项参数类型与滚动 flags，只按字号给控件足够的触控高度。
            wrapper.layoutParams = oldParams.let {
                it.height = dp(FollowFeedStyle.segmentHeight(tabs.resources.configuration.fontScale))
                it
            }
            choice.setOnHighlightListener { index, weight ->
                titles[index].setTextColor(ColorUtils.blendARGB(palette.textSecondary, palette.primary, weight))
            }
            choice.onSelect = { index ->
                // 不调用宿主的模型切换函数，不绕开原埋点，不搬入并覆盖原 View 的点击监听。
                tabViews[index].performClick()
                sync(animate = true)
            }
            sync(animate = false)
            choice.addOnAttachStateChangeListener(attach)
            choice.indicator.addOnAttachStateChangeListener(attach)
        } catch (failure: Throwable) {
            runCatching { close() }
            if (tabs.parent == null) {
                oldParams.height = originalHeight
                parent.addView(tabs, oldIndex.coerceAtMost(parent.childCount), oldParams)
            }
            throw failure
        }
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    fun sync(animate: Boolean = true) {
        if (closed) return
        val actual = (selected.invoke(tabs) as? Int)?.takeIf { it in labels.indices } ?: return
        if (choice.selectedIndex != actual) choice.select(actual, animate)
    }

    fun resume() {
        if (closed || !choice.isAttachedToWindow) return
        if (trackBinding == null) trackBinding = session.bind(choice, FollowFeedStyle.track(palette))
        if (indicatorBinding == null) indicatorBinding = session.bind(choice.indicator, FollowFeedStyle.selection())
    }

    fun pause() {
        indicatorBinding?.close(); indicatorBinding = null
        trackBinding?.close(); trackBinding = null
    }

    fun updatePalette(value: LumenPalette) {
        palette = value
        trackBinding?.update(FollowFeedStyle.track(value))
        // 回调按当前覆盖比例刷新标题；不会触发业务选择。
        choice.setOnHighlightListener { index, weight ->
            titles[index].setTextColor(ColorUtils.blendARGB(palette.textSecondary, palette.primary, weight))
        }
    }

    override fun close() {
        if (closed) return
        pause(); closed = true
        choice.removeOnAttachStateChangeListener(attach)
        choice.indicator.removeOnAttachStateChangeListener(attach)
        choice.onSelect = null
        choice.setOnHighlightListener(null)
        if (tabs.parent === wrapper && wrapper.parent === parent) {
            wrapper.removeView(tabs)
            parent.removeView(wrapper)
            // oldParams 被包装容器使用；恢复高度由创建前的副本记录负责。
            oldParams.height = originalHeight
            parent.addView(tabs, oldIndex.coerceAtMost(parent.childCount), oldParams)
        }
        if (tabs.alpha == 0f) tabs.alpha = oldAlpha
        if (tabs.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
            tabs.importantForAccessibility = oldAccessibility
    }

    companion object {
        fun create(tabs: ViewGroup, session: LumenSurfaceSession, palette: LumenPalette): FollowFeedSelection? = runCatching {
            val parent = tabs.parent as? ViewGroup ?: return null
            val strip = tabs.getChildAt(0) as? ViewGroup ?: return null
            if (strip.childCount != 2) return null // 同城/校园等额外标签保留原布局，不强制丢弃。
            val id = tabs.resources.getIdentifier("dy_tv_title", "id", tabs.context.packageName)
            val tabViews = (0 until 2).map { strip.getChildAt(it) }
            val labels = tabViews.map { it.findViewById<TextView>(id)?.text?.toString() ?: return null }
            if (labels != listOf("全部", "视频")) return null
            val selected = tabs.javaClass.getMethod("getSelectedTabPosition")
            FollowFeedSelection(tabs, parent, tabViews, labels, selected, session, palette)
        }.getOrNull()
    }
}
