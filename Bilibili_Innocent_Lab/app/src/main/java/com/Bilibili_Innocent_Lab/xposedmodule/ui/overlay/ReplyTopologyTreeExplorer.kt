package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyGraph
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyNodeFlags
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyPath
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology.ReplyTopologyTreeLayout
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.PredictiveBackApi33
import kotlin.math.roundToInt

/** 宿主同窗口内的全屏覆盖层；不创建 Activity/Dialog，也不改宿主窗口标志。 */
internal class ReplyTopologyTreeExplorer(
    context: Context,
    private val theme: ReplyTopologyPanelTheme,
    private val strings: ReplyTopologyPanelStrings,
    onLocate: (Long) -> Boolean,
    onText: (View, CharSequence) -> Unit,
    onClose: (ReplyTopologyTreeExplorer) -> Unit
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val controls = ArrayList<TextView>()
    private val safeInsets = Rect()
    private val title = TextView(context)
    private val scope = TextView(context)
    private val selection = TextView(context)
    private val pathButton = chip(strings.viewPath)
    private val locateButton = chip(strings.treeLocate)
    private val textButton = chip(strings.treeText)
    private val fullButton = chip(strings.completeTree)
    private var graph: ReplyTopologyGraph? = null
    private var pathIndex: ReplyTopologyPath.Index? = null
    private var selectedRpid: Long? = null
    private var pathRpid: Long? = null
    private var locate: ((Long) -> Boolean)? = onLocate
    private var text: ((View, CharSequence) -> Unit)? = onText
    private var close: ((ReplyTopologyTreeExplorer) -> Unit)? = onClose
    private var released = false
    private var backDispatcher: Any? = null
    private var backCallback: Any? = null
    private val tree = ReplyTopologyTreeCanvas(context, theme, strings) {
        selectedRpid = it
        updateSelection()
    }

    init {
        orientation = VERTICAL
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(theme.backgroundColor)
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        title.apply { textSize = 17f; setTextColor(theme.primaryTextColor); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val closeButton = chip("×").apply { contentDescription = strings.closeTree; textSize = 24f }
        closeButton.setOnClickListener { HostThreadGuard.run("reply_topology.tree_close") { close?.invoke(this) } }
        header.addView(title, LayoutParams(0, dp(46), 1f))
        header.addView(closeButton, LayoutParams(dp(44), dp(44)))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        scope.apply { textSize = 11f; setTextColor(theme.secondaryTextColor); maxLines = 2 }
        addView(scope, LayoutParams(LayoutParams.MATCH_PARENT, dp(38)))
        val toolbar = LinearLayout(context).apply { orientation = HORIZONTAL }
        val fit = chip(strings.treeFit)
        val center = chip(strings.treeCenter)
        val minus = chip("−")
        val plus = chip("+")
        fit.setOnClickListener { HostThreadGuard.run("reply_topology.tree_fit") { tree.fitView() } }
        center.setOnClickListener { HostThreadGuard.run("reply_topology.tree_center") { tree.centerSelected() } }
        minus.setOnClickListener { HostThreadGuard.run("reply_topology.tree_zoom_out") { tree.zoomBy(0.8) } }
        plus.setOnClickListener { HostThreadGuard.run("reply_topology.tree_zoom_in") { tree.zoomBy(1.25) } }
        fullButton.setOnClickListener { HostThreadGuard.run("reply_topology.tree_full") { showFull() } }
        listOf(fullButton, fit, center, minus, plus).forEach { toolbar.addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, dp(34))) }
        addView(scroll(toolbar), LayoutParams(LayoutParams.MATCH_PARENT, dp(38)))
        addView(tree, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        selection.apply { textSize = 12f; setTextColor(theme.secondaryTextColor); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        addView(selection, LayoutParams(LayoutParams.MATCH_PARENT, dp(28)))
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        pathButton.setOnClickListener {
            HostThreadGuard.run("reply_topology.tree_path") { selectedRpid?.let { showPath(it) } }
        }
        locateButton.setOnClickListener {
            HostThreadGuard.run("reply_topology.tree_locate") {
                val rpid = selectedRpid ?: return@run
                if (locate?.invoke(rpid) == true) close?.invoke(this)
                else Toast.makeText(context, strings.treeFilteredTarget, Toast.LENGTH_SHORT).show()
            }
        }
        textButton.setOnClickListener {
            HostThreadGuard.run("reply_topology.tree_text") {
                val value = graph ?: return@run
                val index = selectedRpid?.let { pathIndex?.indexOf(it) } ?: -1
                if (index >= 0 && textAllowed(value, index)) text?.invoke(textButton, value.messagePreviews[index])
            }
        }
        listOf(pathButton, locateButton, textButton).forEach { actions.addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36))) }
        addView(scroll(actions), LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
        updateSelection()
    }

    fun submit(value: ReplyTopologyGraph, selected: Long? = selectedRpid, initialPath: Long? = null) {
        if (released) return
        if (graph != null && graph?.key != value.key) { close?.invoke(this); return }
        if (graph === value && selectedRpid == selected && initialPath == null) return
        val initial = graph == null
        if (graph !== value) pathIndex = ReplyTopologyPath.Index(value)
        graph = value
        selectedRpid = selected?.takeIf { requireNotNull(pathIndex).indexOf(it) >= 0 }
        if (initial) pathRpid = initialPath
        if (pathRpid != null && requireNotNull(pathIndex).indexOf(requireNotNull(pathRpid)) < 0) pathRpid = null
        rebuild(reset = initial)
    }

    fun showFull() { if (released) return; pathRpid = null; rebuild(reset = true) }
    fun selection(): Long? = selectedRpid
    fun showPath(rpid: Long) {
        if (released || pathIndex?.indexOf(rpid)?.takeIf { it >= 0 } == null) return
        selectedRpid = rpid
        pathRpid = rpid
        rebuild(reset = true)
    }

    private fun rebuild(reset: Boolean) {
        val value = graph ?: return
        val path = pathRpid?.let { pathIndex?.resolve(it) }
        val layout = if (path == null) ReplyTopologyTreeLayout(value) else ReplyTopologyTreeLayout(value, path.indexes, path.baseDepth)
        title.text = if (path == null) strings.completeTree else strings.viewPath
        scope.text = if (path == null) "${strings.treeLoadedFormat.format(value.size)} · ${strings.treeGestureHint}"
        else (if (path.omittedAncestors > 0) strings.earlierAncestorsFormat.format(path.omittedAncestors) + " · " else "") + strings.pathScopeHint
        fullButton.visibility = if (path == null) View.GONE else View.VISIBLE
        tree.submit(layout, selectedRpid, reset)
        updateSelection()
    }

    private fun updateSelection() {
        val value = graph
        val index = selectedRpid?.let { pathIndex?.indexOf(it) } ?: -1
        pathButton.isEnabled = index >= 0
        locateButton.isEnabled = index >= 0
        textButton.isEnabled = value != null && index >= 0 && textAllowed(value, index)
        listOf(pathButton, locateButton, textButton).forEach { it.alpha = if (it.isEnabled) 1f else 0.45f }
        selection.text = if (value != null && index >= 0) {
            "${value.authorNames[index].ifBlank { strings.unknownAuthor }} · ${strings.depthLabel(value.depths[index])}"
        } else strings.treeSelectHint
    }

    private fun textAllowed(value: ReplyTopologyGraph, index: Int): Boolean =
        value.messagePreviews[index].isNotBlank() && value.flags[index] and
            (ReplyTopologyNodeFlags.PLACEHOLDER or ReplyTopologyNodeFlags.FILTERED or ReplyTopologyNodeFlags.UNAVAILABLE) == 0

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !released) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) HostThreadGuard.run("reply_topology.tree_back") { close?.invoke(this) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        HostThreadGuard.run("reply_topology.tree_attach") {
            updateInsets()
            requestFocus()
            if (Build.VERSION.SDK_INT >= 33) {
                val callback = PredictiveBackApi33.plainCallback { HostThreadGuard.run("reply_topology.tree_predictive_back") { close?.invoke(this) } }
                val dispatcher = PredictiveBackApi33.registerView(this, callback)
                if (dispatcher != null) { backCallback = callback; backDispatcher = dispatcher }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!released) HostThreadGuard.run("reply_topology.tree_resize") { updateInsets() }
    }

    private fun updateInsets() {
        replyTopologyWindowInsets(this, safeInsets)
        val left = safeInsets.left + dp(10)
        val top = safeInsets.top + dp(6)
        val right = safeInsets.right + dp(10)
        val bottom = safeInsets.bottom + dp(6)
        if (paddingLeft != left || paddingTop != top || paddingRight != right || paddingBottom != bottom) {
            setPadding(left, top, right, bottom)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        HostThreadGuard.run("reply_topology.tree_detach") {
            if (!released) close?.invoke(this)
            release()
        }
    }

    fun release() {
        if (released) return
        released = true
        animate().cancel()
        if (Build.VERSION.SDK_INT >= 33) {
            val dispatcher = backDispatcher
            val callback = backCallback
            if (dispatcher != null && callback != null) runCatching { PredictiveBackApi33.unregister(dispatcher, callback) }
        }
        backDispatcher = null; backCallback = null
        tree.release()
        controls.forEach { it.setOnClickListener(null) }
        controls.clear()
        graph = null; pathIndex = null; selectedRpid = null; pathRpid = null
        locate = null; text = null; close = null
    }

    private fun chip(label: String) = TextView(context).apply {
        text = label; textSize = 12f; setTextColor(theme.accentColor)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true; isFocusable = true
        val fill = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(ReplyTopologyPanelTheme.blendColor(theme.backgroundColor, theme.accentColor, 0.12f)) }
        background = RippleDrawable(ColorStateList.valueOf(theme.rippleColor), fill, null)
    }.also { controls += it }

    private fun scroll(row: LinearLayout) = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private fun dp(value: Int): Int = (value * density).roundToInt()
}
