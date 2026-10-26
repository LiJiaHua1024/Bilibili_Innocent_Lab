package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import java.lang.ref.WeakReference

/** Native cast entry -> local Lumen controls. The process controller retains only weak UI owners. */
internal class HostLanPanel(private val runtime: () -> LanRuntime?, private val start: (Activity) -> LanRuntime,
    private val stop: () -> Unit, private val source: () -> CastSource?, private val snapshot: () -> ContinuityState?,
    private val resume: (Activity, ContinuityOffers.Offer) -> Unit,
    private val selectPart: (Activity, LanVideoPart, CastDanmakuStyle) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var owner = WeakReference<Activity>(null)
    private var mainSheet = WeakReference<LanSheet>(null)
    private val sheets = ArrayList<WeakReference<LanSheet>>()
    private var continuityHint = WeakReference<PopupWindow>(null)
    private var status = WeakReference<TextView>(null)
    private var progress = WeakReference<ProgressBar>(null)
    private var shownOffer: String? = null
    private var signature: String? = null
    private var pendingReceiver: String? = null
    private var page = 0
    private var style = CastDanmakuStyle(enabled = false)
    private var castId = 0

    private fun anchor(activity: Activity): View? {
        if (castId == 0) {
            // 8.90.2 declares its ARSC package as app, independently of its manifest package.
            val pkg = runCatching { activity.resources.getResourcePackageName(activity.applicationInfo.icon) }.getOrDefault(activity.packageName)
            castId = activity.resources.getIdentifier("cast_screen_icon", "id", pkg)
        }
        return if (castId == 0) null else activity.findViewById(castId)
    }
    private fun currentOffer() = runtime()?.offers?.latest(System.currentTimeMillis(), SystemClock.elapsedRealtime(), snapshot()?.session)
    private fun stateKey() = runtime()?.let { "${it.selected?.id}/${it.isPreparing}/${it.trust.list().size}/${it.status == "正在搜索 DLNA 电视…"}" } ?: "off"
    fun render(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (mainSheet.get()?.showing == true && signature != stateKey()) refreshMain()
        val net = runtime()
        status.get()?.text = net?.status ?: "选择电视，开始大屏播放"
        progress.get()?.apply {
            visibility = if (net?.isPreparing == true || net?.status == "正在搜索 DLNA 电视…") View.VISIBLE else View.GONE
            val seconds = net?.status?.let { Regex("已合成 ([0-9]+) 秒").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            val duration = snapshot()?.durationMs
            isIndeterminate = seconds == null || duration == null || duration <= 0
            if (!isIndeterminate && seconds != null && duration != null) setProgress((seconds * 100_000 / duration).toInt().coerceIn(0, 100), true)
        }
        val offer = currentOffer()
        val key = offer?.let { "${it.device}/${it.state.session}" }
        if (shownOffer == key) return
        continuityHint.get()?.dismiss(); continuityHint.clear()
        if (offer == null) { shownOffer = null; return }
        val target = anchor(activity)?.takeIf { it.isShown && it.isAttachedToWindow } ?: return
        if (mainSheet.get()?.showing == true) return
        owner.get()?.takeIf { it !== activity }?.let(::detach)
        owner = WeakReference(activity); shownOffer = key
        val ui = LanPanelUi(activity)
        val row = ui.row().apply {
            updatePadding(horizontal = ui.dp(14), vertical = ui.dp(10))
            ui.surface(this, ui.palette.surface, 20f)
            addView(ui.icon(LanGlyph.PLAY), LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(12) })
            addView(ui.column().apply {
                addView(ui.label("在此继续观看", 14f, bold = true))
                ui.add(this, ui.label("${net?.trust?.get(offer.device)?.name ?: "信任设备"} · ${time(offer.state.positionMs)}", 12f, ui.palette.textSecondary), 5)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val popup = PopupWindow(row, minOf(ui.dp(320), activity.resources.displayMetrics.widthPixels - ui.dp(28)), ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true; setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setOnDismissListener { ui.close() }
        }
        row.addView(ui.iconButton(LanGlyph.CLOSE, "忽略接力提示") { net?.offers?.dismiss(offer); popup.dismiss() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        ui.clickable(row) { continueWatching(activity, offer); popup.dismiss() }
        continuityHint = WeakReference(popup); popup.showAsDropDown(target)
        val weak = WeakReference(popup); main.postDelayed({ weak.get()?.dismiss() }, 6000)
    }
    fun detach(activity: Activity) {
        if (owner.get() !== activity) return
        continuityHint.get()?.dismiss(); continuityHint.clear()
        sheets.mapNotNull { it.get() }.filter { it.activity === activity }.forEach { it.dismiss() }
        sheets.clear(); mainSheet.clear(); status.clear(); progress.clear(); owner.clear(); shownOffer = null; signature = null
    }
    fun open(activity: Activity) {
        owner.get()?.let(::detach); owner = WeakReference(activity)
        val sheet = sheet(activity, "投到大屏", "同一 Wi-Fi，自在看大屏", child = false)
        mainSheet = WeakReference(sheet)
        sheet.onClosed = { if (mainSheet.get() === sheet) { mainSheet.clear(); status.clear(); progress.clear(); signature = null } }
        val tabs = sheet.ui.selection(sheet.root, listOf("投屏", "设备接力"), page, permanent = true) { page = it; refreshMain() }
        sheet.root.removeView(tabs); sheet.root.addView(tabs, 1)
        refreshMain(); sheet.show()
    }
    private fun sheet(activity: Activity, title: String, subtitle: String? = null, child: Boolean = true): LanSheet {
        lateinit var result: LanSheet
        result = LanSheet(activity, title, subtitle, if (child) ({ result.closeAnimated() }) else null)
        sheets.removeAll { it.get() == null }; sheets += WeakReference(result)
        return result
    }
    private fun refreshMain() {
        val sheet = mainSheet.get() ?: return
        if (runtime() == null) pendingReceiver = null
        sheet.ui.clearBody(sheet.body); status.clear(); progress.clear(); signature = stateKey()
        if (page == 0) casting(sheet) else continuity(sheet)
    }
    private fun casting(sheet: LanSheet) = with(sheet.ui) {
        val net = runtime(); val current = snapshot(); val receiver = net?.selected; val searching = net?.status == "正在搜索 DLNA 电视…"
        card(sheet.body, tone = true, top = 8) {
            addView(row().apply {
                addView(icon(LanGlyph.TV, 28), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(14) })
                addView(column().apply {
                    addView(label(receiver?.name ?: pendingReceiver ?: "选择你的大屏", 17f, bold = true))
                    add(this, label(if (receiver != null) "已连接 · 手机可继续遥控" else if (pendingReceiver != null) "正在为这台电视准备视频" else "发现同一网络里的电视", 12f, palette.textSecondary), 6)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            gap(this, 16)
            addView(label(if (current == null) "先在手机打开一个视频" else "当前视频  ${current.bvid}", 13f, bold = true))
            if (current != null) add(this, label("${time(current.positionMs)} / ${time(current.durationMs)}  ·  ${if (style.enabled) "带弹幕合成" else "原画音轨"}", 12f, palette.textSecondary), 6)
        }
        add(sheet.body, label(net?.status ?: "选择电视，开始大屏播放", 12f, palette.textSecondary).also { status = WeakReference(it) }, 12)
        val loading = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(accent); indeterminateTintList = ColorStateList.valueOf(accent)
            visibility = if (net?.isPreparing == true || net?.status == "正在搜索 DLNA 电视…") View.VISIBLE else View.GONE
            isIndeterminate = true; max = 100
        }
        progress = WeakReference(loading); add(sheet.body, loading, 6, 4)
        if (net?.isPreparing == true) {
            add(sheet.body, button("取消本次准备", LanGlyph.CLOSE) { net.cancelPreparation() }, 14)
        } else {
            add(sheet.body, button(if (searching) "正在搜索…" else if (receiver == null) "搜索并连接电视" else "更换电视", LanGlyph.CAST, primary = receiver == null) {
                safe(activity) { scan(sheet, runtime() ?: start(activity)) }
            }.apply { isEnabled = !searching; alpha = if (searching) .65f else 1f }, 14)
        }
        if (net != null && receiver != null) {
            val transport = row()
            listOf("播放" to LanGlyph.PLAY, "暂停" to LanGlyph.PAUSE, "跳转" to LanGlyph.SEEK).forEachIndexed { index, value ->
                transport.addView(button(value.first, value.second) {
                    if (index == 2) seek(activity, net) else net.control { client, device -> if (index == 0) client.play(device) else client.pause(device) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            add(sheet.body, transport, 12)
        }
        section(sheet.body, "观看设置")
        actionRow(sheet.body, LanGlyph.TUNE, "弹幕与样式", if (style.enabled) "已开启 · 重新推送后生效" else "原画播放 · 可选择带弹幕合成") { styles(activity) }
        if (net != null && receiver != null) {
            val tools = row()
            listOf("倍速" to LanGlyph.PLAY, "音量" to LanGlyph.VOLUME, "选集" to LanGlyph.QUEUE).forEachIndexed { index, value ->
                tools.addView(button(value.first, value.second) {
                    when (index) { 0 -> speeds(activity, net); 1 -> volume(activity, net); else -> parts(activity, net) }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (index > 0) marginStart = dp(8) })
            }
            add(sheet.body, tools, 10)
        }
        actionRow(sheet.body, LanGlyph.INFO, "投屏须知", "视频准备、弹幕与设备兼容性") { help(activity) }
        if (net != null) add(sheet.body, button("结束投屏会话", LanGlyph.STOP, danger = true) { stop(); pendingReceiver = null; refreshMain() }, 18)
        gap(sheet.body, 2)
    }
    private fun scan(parent: LanSheet, net: LanRuntime) {
        if (net.status == "正在搜索 DLNA 电视…") return
        val weak = WeakReference(parent)
        net.scan { main.post {
            val sheet = weak.get()?.takeIf { it.showing && valid(it.activity, net) } ?: return@post
            television(sheet.activity, net)
        } }
        refreshMain()
    }
    private fun television(activity: Activity, net: LanRuntime) {
        val target = sheet(activity, "附近的大屏", "仅在点选后推送当前视频")
        with(target.ui) {
            if (net.devices.isEmpty()) card(target.body, tone = true) {
                addView(label("暂未发现电视", 17f, bold = true)); add(this, label("确认手机和电视连接同一 Wi-Fi，并在电视上开启 DLNA 接收。", 13f, palette.textSecondary), 9)
            }
            net.devices.forEach { device -> actionRow(target.body, LanGlyph.TV, device.name.ifBlank { "DLNA 电视" }, device.description.host) {
                val media = source(); val state = snapshot()
                if (media == null || state == null || media.bvid != state.bvid || media.cid != state.cid) {
                    activity.toast("当前视频轨道尚未就绪，请在手机播放后重试"); return@actionRow
                }
                pendingReceiver = device.name; target.closeAnimated()
                val weak = WeakReference(activity)
                net.cast(device, media, state.title, state.positionMs, style.takeIf { it.enabled }) { success -> main.post {
                    val owner = weak.get()?.takeIf { valid(it, net) } ?: return@post
                    pendingReceiver = null; refreshMain()
                    owner.toast(if (success) "已推送，请确认电视画面" else "投屏未完成，请查看状态")
                } }
                refreshMain()
            } }
            add(target.body, button("重新搜索", LanGlyph.SCAN) { target.closeAnimated(); mainSheet.get()?.let { scan(it, net) } }, 18)
        }
        target.show()
    }
    private fun seek(activity: Activity, net: LanRuntime) = input(activity, "跳转进度", "输入目标秒数，例如 90 表示 1 分 30 秒", InputType.TYPE_CLASS_NUMBER) { value ->
        val seconds = value.toLongOrNull()?.takeIf { it in 0..604800 } ?: error("invalid-position")
        net.seek(seconds * 1000)
    }
    private fun speeds(activity: Activity, net: LanRuntime) {
        val target = sheet(activity, "播放速度", "需电视支持，拒绝时会显示原因")
        with(target.ui) {
            listOf(.1f, .25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f, 6f, 8f).forEach { speed ->
                actionRow(target.body, LanGlyph.PLAY, "$speed ×", if (speed == 1f) "正常速度" else null) {
                    net.control { client, device -> client.play(device, speed) }; target.closeAnimated()
                }
            }
        }; target.show()
    }
    private fun volume(activity: Activity, net: LanRuntime) {
        val target = sheet(activity, "电视音量", "拖动后松手，发送目标音量")
        with(target.ui) {
            val value = label("调整至 50%", 30f, accent, true).apply { gravity = Gravity.CENTER }
            add(target.body, value, 24)
            val slider = SeekBar(activity).apply { max = 100; progress = 50; progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent) }
            slider.contentDescription = "电视目标音量"
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) { value.text = "调整至 $progress%" }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) { net.control { client, device -> client.volume(device, bar.progress) } }
            })
            add(target.body, slider, 22, 52)
            add(target.body, label("此滑块设置目标值，不代表电视当前音量。", 12f, palette.textSecondary), 12)
        }; target.show()
    }
    private fun styles(activity: Activity) {
        val target = sheet(activity, "弹幕与样式", "重新推送后生效")
        with(target.ui) {
            val preview = FrameLayout(activity).apply { surface(this, if (colors.dark) 0xFF171B25.toInt() else 0xFFEDEEF5.toInt(), 18f) }
            val region = View(activity).apply { surface(this, soft, 12f) }
            preview.addView(region, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp((76 * style.coverage).toInt()), Gravity.TOP).apply {
                topMargin = dp(8); marginStart = dp(8); marginEnd = dp(8)
            })
            val demo = label("让每一份热爱，都有回响", 16f, accent, true)
            preview.addView(demo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(20) })
            preview.addView(label("样式预览", 11f, palette.textSecondary), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(12); marginEnd = dp(14) })
            add(target.body, preview, 12, 100)
            fun updatePreview() {
                demo.textSize = 16f * style.scale; demo.alpha = if (style.enabled) style.opacity else .25f
                region.alpha = if (style.enabled) .8f else .3f
                region.layoutParams = (region.layoutParams as FrameLayout.LayoutParams).apply { height = dp((76 * style.coverage).toInt()) }
            }
            selection(target.body, listOf("原画播放", "带弹幕"), if (style.enabled) 1 else 0) { style = style.copy(enabled = it == 1); updatePreview(); refreshMain() }
            section(target.body, "字号")
            val scales = listOf(.75f, 1f, 1.25f, 1.5f)
            selection(target.body, listOf("小", "标准", "大", "超大"), scales.indexOf(style.scale).coerceAtLeast(0)) { style = style.copy(scale = scales[it]); updatePreview() }
            section(target.body, "不透明度", 18)
            val opacities = listOf(.5f, .7f, .85f, 1f)
            selection(target.body, listOf("50%", "70%", "85%", "100%"), opacities.indexOf(style.opacity).coerceAtLeast(0)) { style = style.copy(opacity = opacities[it]); updatePreview() }
            section(target.body, "显示区域", 18)
            val areas = listOf(.25f, .5f, .75f, 1f)
            selection(target.body, listOf("¼ 屏", "½ 屏", "¾ 屏", "全屏"), areas.indexOf(style.coverage).coerceAtLeast(0)) { style = style.copy(coverage = areas[it]); updatePreview() }
            add(target.body, label("带弹幕会先合成视频，准备时间更长。样式仅影响下一次推送。", 12f, palette.textSecondary), 16)
            add(target.body, button("完成", primary = true) { target.closeAnimated(); refreshMain() }, 18)
            updatePreview()
        }; target.show()
    }
    private fun parts(activity: Activity, net: LanRuntime) {
        val state = snapshot() ?: return
        val weak = WeakReference(activity)
        net.parts(state.bvid) { values -> main.post {
            val owner = weak.get()?.takeIf { valid(it, net) && mainSheet.get()?.showing == true } ?: return@post
            if (values.isEmpty()) { owner.toast("无法读取分 P，可在手机原播放器切换后重新推送"); return@post }
            val target = sheet(owner, "切换选集", "点选后推送至当前电视")
            values.forEach { part -> target.ui.actionRow(target.body, LanGlyph.QUEUE, "P${part.page} · ${part.title}") {
                safe(owner) { selectPart(owner, part, style); target.closeAnimated(); mainSheet.get()?.closeAnimated() }
            } }; target.show()
        } }
    }
    private fun continuity(sheet: LanSheet) = with(sheet.ui) {
        val net = runtime()
        card(sheet.body, tone = true, top = 8) {
            addView(icon(LanGlyph.SHIELD, 30), LinearLayout.LayoutParams(dp(30), dp(30)))
            add(this, label("让观看，自然接续", 20f, bold = true), 14)
            add(this, label("配对一次，之后自动互认。观看状态仅在信任设备之间加密同步。", 13f, palette.textSecondary), 9)
        }
        currentOffer()?.let { offer -> actionRow(sheet.body, LanGlyph.PLAY, "在此继续观看", "${net?.trust?.get(offer.device)?.name ?: "信任设备"} · ${time(offer.state.positionMs)}") {
            continueWatching(activity, offer)
        } }
        add(sheet.body, button("显示配对码", LanGlyph.KEY, primary = true) { safe(activity) { invitation(activity, runtime() ?: start(activity)) } }, 16)
        add(sheet.body, button("输入另一台设备的配对码", LanGlyph.LINK) { safe(activity) { choosePeer(activity, runtime() ?: start(activity)) } }, 10)
        section(sheet.body, "信任的设备")
        val trusted = net?.trust?.list().orEmpty()
        if (trusted.isEmpty()) card(sheet.body, top = 10) {
            addView(label("还没有配对设备", 14f, bold = true))
            add(this, label("在另一台设备打开本面板，使用一次性配对码建立信任。", 12f, palette.textSecondary), 7)
        }
        trusted.forEach { device -> actionRow(sheet.body, LanGlyph.SHIELD, device.name, "已绑定 · ${device.id.take(6)}") { manage(activity, net ?: return@actionRow, device.id) } }
        if (net != null) add(sheet.body, button("停止本次局域网会话", LanGlyph.STOP, danger = true) { stop(); refreshMain() }, 18)
        gap(sheet.body, 2)
    }
    private fun continueWatching(activity: Activity, offer: ContinuityOffers.Offer) {
        val net = runtime()
        if (net?.trust?.get(offer.device) == null || !offer.state.offerAt(System.currentTimeMillis())) { activity.toast("接力提示已失效"); return }
        safe(activity) { resume(activity, offer); net.offers.dismiss(offer); mainSheet.get()?.closeAnimated() }
    }
    private fun invitation(activity: Activity, net: LanRuntime) {
        val code = net.invitation()
        val target = sheet(activity, "一次配对，安心接续", "2 分钟内有效 · 仅可使用一次")
        target.onClosed = { net.cancelInvitation() }
        with(target.ui) {
            card(target.body, tone = true) {
                addView(label("本机  ${net.trust.id.take(6)}", 12f, palette.textSecondary))
                add(this, label(code, 17f, accent, true).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true); setLineSpacing(dp(8).toFloat(), 1f) }, 14)
            }
            add(target.body, label("在另一台设备选择本机，输入或粘贴此码。关闭本面板后，配对码立即作废。", 13f, palette.textSecondary), 16)
            add(target.body, button("复制配对码", LanGlyph.COPY, primary = true) {
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("一次性局域网配对码", code)); activity.toast("已复制配对码")
            }, 20)
        }; target.show(); refreshMain()
    }
    private fun choosePeer(activity: Activity, net: LanRuntime) {
        val target = sheet(activity, "连接另一台设备", "请先在对方设备显示配对码")
        val peers = net.peers().filter { net.trust.get(it.id) == null }
        with(target.ui) {
            if (peers.isEmpty()) card(target.body, tone = true) {
                addView(label("暂未发现未配对设备", 16f, bold = true))
                add(this, label("两台设备都需开启局域网会话。等待几秒后，重新打开此页面。", 13f, palette.textSecondary), 9)
            }
            peers.forEach { peer -> actionRow(target.body, LanGlyph.LINK, "设备 ${peer.id.take(6)}", peer.address.host) {
                target.closeAnimated()
                input(activity, "输入配对码", "粘贴对方设备显示的一次性配对码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) { code ->
                    LanCrypto.invitationKey(code).fill(0)
                    val weak = WeakReference(activity)
                    net.pair(peer, code) { success -> main.post { weak.get()?.takeIf { valid(it, net) }?.let { owner ->
                        owner.toast(if (success) "设备已互信" else "配对失败，请重新生成配对码"); refreshMain()
                    } } }
                }
            } }
        }; target.show(); refreshMain()
    }
    private fun manage(activity: Activity, net: LanRuntime, id: String) {
        val device = net.trust.get(id) ?: return
        val target = sheet(activity, device.name, "信任设备 · ${id.take(6)}")
        with(target.ui) {
            actionRow(target.body, LanGlyph.TUNE, "修改设备名称") {
                input(activity, "设备名称", "输入便于辨认的名称", InputType.TYPE_CLASS_TEXT) { name ->
                    require(name.isNotBlank() && name.length <= 80); net.trust.put(device.copy(name = name)); target.closeAnimated(); refreshMain()
                }
            }
            actionRow(target.body, LanGlyph.SHIELD, "解除信任", "停止向此设备同步观看状态", danger = true) {
                val confirm = sheet(activity, "解除信任？", device.name)
                with(confirm.ui) {
                    add(confirm.body, label("本机将停止与此设备同步。再次连接时需要重新配对。", 14f, palette.textSecondary), 14)
                    add(confirm.body, button("解除信任", LanGlyph.CLOSE, danger = true) { net.revoke(id); confirm.closeAnimated(); target.closeAnimated(); refreshMain() }, 20)
                }
                confirm.show()
            }
        }; target.show()
    }
    private fun input(activity: Activity, title: String, hint: String, type: Int, accepted: (String) -> Unit) {
        val target = sheet(activity, title)
        with(target.ui) {
            val field = EditText(activity).apply {
                inputType = type; textSize = 16f; setTextColor(palette.textPrimary); setHintTextColor(palette.textSecondary)
                this.hint = hint; maxLines = 3; filters = arrayOf(InputFilter.LengthFilter(128))
                updatePadding(horizontal = dp(16), vertical = dp(14)); surface(this, soft, 16f)
            }
            add(target.body, field, 16)
            add(target.body, button("确定", primary = true) {
                runCatching { accepted(field.text.toString().trim()) }.onSuccess { target.closeAnimated() }.onFailure { field.error = "请检查输入内容" }
            }, 18)
        }; target.show()
    }
    private fun help(activity: Activity) {
        val target = sheet(activity, "投屏须知", "让准备与播放更可预期")
        with(target.ui) {
            listOf("当前视频" to "只读取手机当前播放、已获权且无 DRM 的普通视频。推送前会核对 BV/CID。",
                "视频准备" to "原画需先合并音视频，弹幕需合成全片。完成后才会推送，准备期间可以取消。",
                "弹幕样式" to "开关、字号、不透明度和区域仅对下一次推送生效；暂不支持新增弹幕的实时同步。",
                "电视兼容" to "倍速、编码和遥控能力以电视实际支持为准。媒体使用 DLNA 的 HTTP 通道，限制所选电视 IP 与随机地址读取。",
                "设备互信" to "首次均需配对；之后自动互认。公开 UID 不能证明账号身份。关闭面板保留会话，结束会话会停止电视播放。"
            ).forEach { (heading, text) -> card(target.body) { addView(label(heading, 14f, bold = true)); add(this, label(text, 13f, palette.textSecondary), 8) } }
        }; target.show()
    }
    private fun safe(activity: Activity, action: () -> Unit) { runCatching(action).onFailure { activity.toast("操作不可用，请检查 Wi-Fi、系统权限与宿主适配") } }
    private fun valid(activity: Activity, net: LanRuntime) = !activity.isFinishing && !activity.isDestroyed && runtime() === net && owner.get() === activity
    private fun time(ms: Long): String { val seconds = ms.coerceAtLeast(0) / 1000; return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%02d:%02d".format(seconds / 60, seconds % 60) }
}