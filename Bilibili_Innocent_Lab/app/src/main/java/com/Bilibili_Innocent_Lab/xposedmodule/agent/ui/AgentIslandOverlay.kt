package com.Bilibili_Innocent_Lab.xposedmodule.agent.ui

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.hardware.display.DisplayManager
import android.view.Display
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentController
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentPreferences
import com.Bilibili_Innocent_Lab.xposedmodule.agent.AgentTaskState

/** 模块自己的不可聚焦小窗口，不改宿主 Window，也不向其它页面扩展任务控制权。 */
internal class AgentIslandOverlay(private val service: Context) : android.content.ComponentCallbacks {
    private val main = Handler(Looper.getMainLooper())
    private val context = if (Build.VERSION.SDK_INT >= 30) {
        val display = (service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("display_unavailable")
        service.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else service
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val power = service.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val appOps = service.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    private var window: View? = null
    private var island: AgentIslandView? = null
    private var logs: AgentLogView? = null
    private var tip: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var placement: AgentIslandGeometry.Placement? = null
    private var input: AgentIslandGeometry.Input? = null
    private var originX = 0
    private var originY = 0
    private var mode = 0 // 0 收起，1 简要状态，2 执行日志
    private var closed = false
    private var retryAt = 0L
    private var receiverRegistered = false
    private var callbacksRegistered = false
    private var opsRegistered = false
    private var state = AgentController.state
    private val refresh = Runnable { if (!closed) apply(AgentController.state) }
    private val permissionRefresh = Runnable { if (!closed) { retryAt = 0; apply(AgentController.state) } }
    private val observer: (AgentTaskState) -> Unit = { apply(it) }
    private val opListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (!closed && op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && packageName == service.packageName) {
            main.removeCallbacks(permissionRefresh); main.post(permissionRefresh)
        }
    }
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { retryAt = 0; apply(AgentController.state) }
    }

    init {
        try {
        if (Build.VERSION.SDK_INT >= 31) context.registerComponentCallbacks(this) else service.registerComponentCallbacks(this)
        callbacksRegistered = true
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
        if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") service.registerReceiver(screen, filter)
        receiverRegistered = true
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, service.packageName, opListener)
        opsRegistered = true
        AgentController.observe(observer)
        } catch (error: RuntimeException) { close(); throw error }
    }

    private fun apply(next: AgentTaskState) {
        state = next
        try {
        if (closed || !next.running || AgentController.currentTaskId() == null || !AgentPreferences.islandAllowed(service) ||
            !power.isInteractive || keyguard.isKeyguardLocked || !Settings.canDrawOverlays(service)) { remove(); return }
        if (window == null && SystemClock.elapsedRealtime() >= retryAt) rebuild()
        placement?.let { island?.update(next, it) }
        island?.setAnimating(true)
        tip?.text = AgentStatusText.tip(context, next)
        } catch (_: RuntimeException) { failed() }
    }

    @Suppress("DEPRECATION") // API27-29使用系统默认窗口拟合并安全居中，不读取内部状态栏资源。
    private fun geometry(): AgentIslandGeometry.Input {
        val density = context.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            originX = bounds.left; originY = bounds.top
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            val waterfall = metrics.windowInsets.displayCutout?.waterfallInsets
            val cutouts = metrics.windowInsets.displayCutout?.boundingRects.orEmpty().map {
                AgentIslandGeometry.Rect(it.left, it.top, it.right, it.bottom)
            }
            return AgentIslandGeometry.Input(bounds.width(), bounds.height(), density, maxOf(insets.top, waterfall?.top ?: 0), cutouts,
                maxOf(insets.left, waterfall?.left ?: 0), maxOf(insets.right, waterfall?.right ?: 0), maxOf(insets.bottom, waterfall?.bottom ?: 0))
        }
        originX = 0; originY = 0
        val display = android.util.DisplayMetrics()
        wm.defaultDisplay.getMetrics(display)
        return AgentIslandGeometry.Input(display.widthPixels, display.heightPixels, density)
    }

    private fun rebuild() {
        remove()
        if (closed) return
        val currentInput = geometry()
        val anchor = AgentIslandGeometry.collapsed(currentInput) ?: return
        val bounds = if (mode == 0) anchor.bounds else AgentIslandGeometry.panel(currentInput, anchor, if (mode == 2) 440 else 156) ?: anchor.bounds
        val actualMode = if (bounds == anchor.bounds) 0 else mode
        val view: View = if (actualMode == 0) AgentIslandView(context).also {
            island = it; it.update(state, anchor)
            it.setOnClickListener { mode = 1; safeRebuild() }
        } else LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(Color.rgb(24, 28, 36)); cornerRadius = dp(22).toFloat() }
            clipToOutline = true
            val actions = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            actions.addView(TextView(context).apply {
                text = context.getString(if (actualMode == 2) R.string.agent_logs_title else R.string.agent_island_operating)
                setTextColor(Color.WHITE); textSize = 14f
            }, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(button(R.string.agent_island_collapse) { mode = 0; safeRebuild() })
            addView(actions)
            if (actualMode == 2) {
                val list = AgentLogView(context).also { logs = it }
                addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            } else {
                addView(TextView(context).apply {
                    text = AgentStatusText.tip(context, state); setTextColor(Color.WHITE); textSize = 17f; tip = this
                    setPadding(0, dp(8), 0, dp(8))
                }, LinearLayout.LayoutParams(-1, 0, 1f))
                val row = LinearLayout(context)
                row.addView(button(R.string.agent_logs_title) { mode = 2; safeRebuild() }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(button(R.string.agent_stop) { AgentController.cancel(service) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(row)
            }
        }
        val layout = WindowManager.LayoutParams(bounds.width, bounds.height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = bounds.left + originX; y = bounds.top + originY
            if (Build.VERSION.SDK_INT >= 30) {
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                setFitInsetsTypes(0); layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            title = "Agent task status"
        }
        try {
            wm.addView(view, layout)
            window = view; params = layout; placement = anchor; input = currentInput
            island?.setAnimating(true)
        } catch (_: RuntimeException) {
            island?.close(); logs?.close(); island = null; logs = null; tip = null
            failed()
            // 权限/系统禁止仅撤除展示，Agent仍沿其独立租约执行。
        }
    }
    private fun safeRebuild() { try { rebuild() } catch (_: RuntimeException) { failed() } }
    private fun failed() { retryAt = SystemClock.elapsedRealtime() + 5_000L; remove() }

    private fun button(textId: Int, action: () -> Unit) = Button(context).apply {
        text = context.getString(textId); textSize = 12f; isAllCaps = false; minWidth = dp(48); minimumWidth = dp(48)
        setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun remove() {
        island?.close(); logs?.close()
        window?.let { runCatching { wm.removeViewImmediate(it) } }
        window = null; island = null; logs = null; tip = null; params = null; placement = null; input = null
    }
    override fun onConfigurationChanged(newConfig: Configuration) { if (!closed) { retryAt = 0; remove(); apply(AgentController.state) } }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() { if (mode == 2) { mode = 0; remove(); apply(AgentController.state) } }
    fun close() {
        if (closed) return
        closed = true
        AgentController.removeObserver(observer); main.removeCallbacks(refresh); main.removeCallbacks(permissionRefresh)
        if (receiverRegistered) runCatching { service.unregisterReceiver(screen) }
        if (callbacksRegistered) runCatching { if (Build.VERSION.SDK_INT >= 31) context.unregisterComponentCallbacks(this) else service.unregisterComponentCallbacks(this) }
        if (opsRegistered) runCatching { appOps.stopWatchingMode(opListener) }
        remove()
    }
}
