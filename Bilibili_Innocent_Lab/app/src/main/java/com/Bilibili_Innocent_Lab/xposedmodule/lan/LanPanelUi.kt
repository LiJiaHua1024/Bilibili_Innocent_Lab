package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeTheme
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostTouchGlowBinding
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.PredictiveBackApi33
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion
import com.highcapable.betterandroid.ui.extension.component.base.toPx
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import com.lumen.coacervation.engine.host.LumenSurfacePresets
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import com.lumen.coacervation.engine.host.LumenSurfaceSessionOptions
import com.lumen.coacervation.engine.model.SurfaceRole
import com.lumen.coacervation.engine.motion.LumenAnimator
import com.lumen.coacervation.engine.motion.LumenEasing
import com.lumen.coacervation.engine.widget.LumenMaxHeightScrollView
import com.lumen.coacervation.engine.widget.LumenSlidingSelection

/** Each dialog owns a bounded local Lumen session; never prepare/bindRoot on a third-party Activity. */
internal class LanPanelUi(val activity: Activity) : AutoCloseable {
    val colors = HostChromeTheme(activity).read()
    val palette = colors.palette()
    val accent get() = palette.primary
    val soft get() = ColorUtils.blendARGB(palette.surface, accent, if (colors.dark) .14f else .065f)
    private val session = LumenSurfaceSession(activity, palette, LumenSurfaceSessionOptions(maxSurfaces = 32))
    private val transient = ArrayList<LumenSurfaceBinding>()
    fun dp(value: Int) = value.toPx(activity)
    fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun label(value: String, size: Float = 14f, color: Int = palette.textPrimary, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun icon(glyph: LanGlyph, size: Int = 22, color: Int = accent) = ImageView(activity).apply {
        setImageDrawable(LanGlyphDrawable(glyph, color, dp(size)))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun surface(view: View, color: Int = palette.surfaceVariant, radius: Float = 18f, permanent: Boolean = false) {
        val binding = session.bind(view, LumenSurfacePresets.staticPanel().copy(
            role = SurfaceRole.CARD, color = color, radiusDp = radius, tintOpacity = 1f, fallbackTintOpacity = 1f,
            edgeWidthDp = .75f, edgeTopColor = ColorUtils.setAlphaComponent(if (colors.dark) Color.WHITE else accent, if (colors.dark) 24 else 18),
            edgeBottomColor = ColorUtils.setAlphaComponent(accent, if (colors.dark) 30 else 8)))
        if (!permanent) transient += binding
    }
    fun clearBody(body: ViewGroup) { transient.forEach { it.close() }; transient.clear(); body.removeAllViews() }
    fun gap(parent: LinearLayout, height: Int = 12) { parent.addView(View(activity), LinearLayout.LayoutParams(1, dp(height))) }
    fun add(parent: LinearLayout, child: View, top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        parent.addView(child, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (height < 0) height else dp(height)).apply { topMargin = dp(top) })
    }
    fun section(parent: LinearLayout, title: String, top: Int = 22) {
        add(parent, label(title, 12f, palette.textSecondary, true).apply { letterSpacing = .05f }, top)
    }
    fun card(parent: LinearLayout, tone: Boolean = false, top: Int = 12, build: LinearLayout.() -> Unit): LinearLayout = column().apply {
        updatePadding(horizontal = dp(16), vertical = dp(16))
        surface(this, if (tone) soft else palette.surfaceVariant)
        build(); add(parent, this, top)
    }
    fun button(text: String, glyph: LanGlyph? = null, primary: Boolean = false, danger: Boolean = false,
        action: (View) -> Unit): LinearLayout = row().apply {
        gravity = Gravity.CENTER; minimumHeight = dp(50)
        updatePadding(horizontal = dp(14), vertical = dp(12))
        val foreground = if (primary) palette.onPrimary else if (danger) (if (colors.dark) 0xFFFF919C else 0xFFB4233A).toInt() else accent
        surface(this, if (primary) accent else if (danger) ColorUtils.blendARGB(palette.surface, foreground, .07f) else soft, 16f)
        glyph?.let { addView(icon(it, 20, foreground), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) }) }
        addView(label(text, 14f, foreground, true))
        contentDescription = text; clickable(this, 16f, action)
    }
    fun iconButton(glyph: LanGlyph, description: String, action: (View) -> Unit): View = row().apply {
        gravity = Gravity.CENTER; minimumWidth = dp(44); minimumHeight = dp(44)
        addView(icon(glyph, 21, palette.textSecondary), LinearLayout.LayoutParams(dp(21), dp(21)))
        contentDescription = description; clickable(this, 22f, action)
    }
    fun clickable(view: View, radius: Float = 16f, action: (View) -> Unit) {
        view.isClickable = true; view.isFocusable = true
        // This is only a ripple mask; the background remains owned by its Lumen binding.
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius * activity.resources.displayMetrics.density }
        view.foreground = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 32)), null, mask)
        view.setOnClickListener { if (view.isEnabled) action(it) }
    }
    fun actionRow(parent: LinearLayout, glyph: LanGlyph, title: String, subtitle: String? = null,
        danger: Boolean = false, action: (View) -> Unit): View = row().apply {
        minimumHeight = dp(64); updatePadding(horizontal = dp(14), vertical = dp(12))
        surface(this, if (danger) ColorUtils.blendARGB(palette.surface, 0xFFCE3954.toInt(), .06f) else palette.surfaceVariant, 16f)
        val tint = if (danger) (if (colors.dark) 0xFFFF919C else 0xFFB4233A).toInt() else accent
        addView(icon(glyph, 22, tint), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(12) })
        addView(column().apply {
            addView(label(title, 14f, if (danger) tint else palette.textPrimary, true))
            subtitle?.let { add(label(it, 12f, palette.textSecondary), 5) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(icon(LanGlyph.CHEVRON, 16, palette.textSecondary), LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginStart = dp(6) })
        contentDescription = listOfNotNull(title, subtitle).joinToString("，")
        clickable(this, action = action); add(parent, this, 9)
    }
    private fun LinearLayout.add(view: View, top: Int) = add(this, view, top)
    fun selection(parent: LinearLayout, labels: List<String>, selected: Int, permanent: Boolean = false,
        onSelect: (Int) -> Unit): LumenSlidingSelection {
        val selection = LumenSlidingSelection(activity, ColorDrawable(Color.TRANSPARENT), LinearLayout.HORIZONTAL,
            notifyPositionChanged = session::notifyPositionChanged)
        surface(selection, palette.surfaceVariant, 15f, permanent)
        surface(selection.indicator, soft, 12f, permanent)
        selection.updatePadding(horizontal = dp(4), vertical = dp(4))
        val titles = labels.map { title -> label(title, 13f, palette.textSecondary, true).apply {
            gravity = Gravity.CENTER; minimumHeight = dp(40)
        } }
        titles.forEach { selection.addOption(it, LinearLayout.LayoutParams(0, dp(40), 1f)) }
        selection.setOnHighlightListener { index, weight ->
            titles[index].setTextColor(ColorUtils.blendARGB(palette.textSecondary, accent, weight))
        }
        selection.select(selected, animate = false); selection.onSelect = onSelect
        add(parent, selection, 12)
        return selection
    }
    override fun close() { transient.clear(); session.close() }
}

/** One dismissible modal. Engine surfaces, easing, selection motion and touch glow stay local to it. */
internal class LanSheet(val activity: Activity, title: String, subtitle: String? = null, private val onBack: (() -> Unit)? = null) {
    val ui = LanPanelUi(activity)
    val body = ui.column()
    private val header = ui.row()
    private var closing = false
    private var released = false
    private var backDispatcher: Any? = null
    private var backCallback: Any? = null
    private var motion: ValueAnimator? = null
    var onClosed: () -> Unit = {}
    val dialog = object : Dialog(activity) {
        override fun cancel() { if (onBack != null) onBack.invoke() else closeAnimated() }
    }.apply { requestWindowFeature(Window.FEATURE_NO_TITLE); setOwnerActivity(activity) }
    val root = object : LinearLayout(activity) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            HostTouchGlowBinding.observeTouch(this, event)
            return super.dispatchTouchEvent(event)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        updatePadding(horizontal = ui.dp(22), vertical = ui.dp(20))
    }
    init {
        ui.surface(root, ui.palette.surface, 28f, permanent = true)
        HostTouchGlowBinding.attach(root)
        if (onBack != null) header.addView(ui.iconButton(LanGlyph.BACK, "返回") { onBack() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginStart = -ui.dp(10) })
        header.addView(ui.column().apply {
            addView(ui.label(title, 23f, bold = true))
            subtitle?.let { ui.add(this, ui.label(it, 12f, ui.palette.textSecondary), 7) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(ui.iconButton(LanGlyph.CLOSE, "关闭面板") { closeAnimated() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginEnd = -ui.dp(10) })
        root.addView(header)
        val scrollLimit = minOf((activity.resources.displayMetrics.heightPixels * .70f).toInt(),
            (activity.resources.displayMetrics.heightPixels - ui.dp(220)).coerceAtLeast(ui.dp(120)))
        val scroll = LumenMaxHeightScrollView(activity, scrollLimit).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { release() }
    }
    val showing get() = dialog.isShowing && !closing && !released
    fun show() {
        check(!released)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setWindowAnimations(0)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(if (ui.colors.dark) .54f else .28f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dialog.show()
        dialog.window?.apply {
            setGravity(Gravity.CENTER)
            setLayout(minOf(ui.dp(440), activity.resources.displayMetrics.widthPixels - ui.dp(28)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (AndroidVersion.isAtLeast(AndroidVersion.T)) runCatching {
            val callback = PredictiveBackApi33.plainCallback { if (onBack != null) onBack() else closeAnimated() }
            backDispatcher = PredictiveBackApi33.register(dialog, callback); backCallback = callback
        }
        if (!ValueAnimator.areAnimatorsEnabled()) return
        root.alpha = 0f; root.translationY = ui.dp(18).toFloat(); root.scaleX = .97f; root.scaleY = .97f
        motion = LumenAnimator.ofFloat(0f, 1f).apply {
            duration = 260; interpolator = LumenEasing.emphasizedDecelerate()
            addUpdateListener {
                val progress = it.animatedValue as Float
                root.alpha = progress; root.translationY = ui.dp(18) * (1f - progress)
                root.scaleX = .97f + progress * .03f; root.scaleY = root.scaleX
                root.invalidate()
            }; start()
        }
    }
    fun closeAnimated() {
        if (closing || released) return
        closing = true; motion?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) { dismiss(); return }
        val start = root.alpha
        motion = LumenAnimator.ofFloat(start, 0f).apply {
            duration = 160; interpolator = LumenEasing.emphasizedAccelerate()
            addUpdateListener { root.alpha = it.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { root.postOnAnimation { dismiss() } }
            }); start()
        }
    }
    fun dismiss() { if (!released) dialog.dismiss(); release() }
    private fun release() {
        if (released) return
        released = true; motion?.cancel(); motion = null
        if (AndroidVersion.isAtLeast(AndroidVersion.T)) backDispatcher?.let { dispatcher ->
            backCallback?.let { callback -> runCatching { PredictiveBackApi33.unregister(dispatcher, callback) } }
        }
        backDispatcher = null; backCallback = null; ui.close(); onClosed()
    }
}
