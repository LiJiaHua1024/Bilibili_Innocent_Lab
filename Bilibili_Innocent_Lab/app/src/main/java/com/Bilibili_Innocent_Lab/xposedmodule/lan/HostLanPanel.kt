package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.betterandroid.ui.extension.view.updatePadding
import com.highcapable.betterandroid.ui.extension.component.base.toPx
import androidx.core.content.res.use
import java.lang.ref.WeakReference

/** All views belong to their host Activity; the long-lived controller keeps only weak references. */
internal class HostLanPanel(private val runtime: () -> LanRuntime?, private val start: (Activity) -> LanRuntime,
    private val stop: () -> Unit, private val source: () -> CastSource?, private val snapshot: () -> ContinuityState?,
    private val resume: (Activity, ContinuityOffers.Offer) -> Unit,
    private val selectPart: (Activity, LanVideoPart, CastDanmakuStyle) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var owner = WeakReference<Activity>(null)
    private var continuityHint = WeakReference<PopupWindow>(null)
    private var status = WeakReference<TextView>(null)
    private var dialog = WeakReference<AlertDialog>(null)
    private var shownOffer: String? = null
    private var style = CastDanmakuStyle(enabled = false)
    private var castId = 0
    private fun anchor(activity: Activity): View? {
        if (castId == 0) {
            // The host manifest package and ARSC package differ (8.90.2 uses app:id/...).
            val resourcePackage = runCatching { activity.resources.getResourcePackageName(activity.applicationInfo.icon) }.getOrDefault(activity.packageName)
            castId = activity.resources.getIdentifier("cast_screen_icon", "id", resourcePackage)
        }
        return if (castId == 0) null else activity.findViewById(castId)
    }
    fun isCastButton(activity: Activity, view: View): Boolean {
        if (!view.isShown || view.id == View.NO_ID) return false
        if (runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() != "cast_screen_icon") return false
        castId = view.id
        return true
    }
    fun render(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val net = runtime()
        status.get()?.text = net?.status ?: "局域网会话未开启"
        val offer = net?.offers?.latest(System.currentTimeMillis(), SystemClock.elapsedRealtime(), snapshot()?.session)
        val key = offer?.let { "${it.device}/${it.state.session}" }
        if (shownOffer == key) return
        continuityHint.get()?.dismiss(); continuityHint.clear()
        if (offer == null) { shownOffer = null; return }
        val anchor = anchor(activity)?.takeIf { it.isShown && it.isAttachedToWindow } ?: return
        if (dialog.get()?.isShowing == true) return
        owner.get()?.takeIf { it !== activity }?.let(::detach)
        owner = WeakReference(activity); shownOffer = key
        if (offer != null) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(activity).apply {
                    text = "在此继续 ${offer.state.title}"
                    isAllCaps = false
                    contentDescription = "接续已配对设备的视频、进度和倍速"
                    setOnClickListener {
                        if (runtime() !== net || net.trust.get(offer.device) == null ||
                            !offer.state.offerAt(System.currentTimeMillis())) { activity.toast("接力提示已失效"); return@setOnClickListener }
                        runCatching { resume(activity, offer); net.offers.dismiss(offer); continuityHint.get()?.dismiss() }
                            .onFailure { activity.toast("当前宿主无法打开接力视频") }
                    }
                })
                addView(Button(activity).apply { text = "×"; contentDescription = "忽略此视频的接力提示"
                    setOnClickListener { net.offers.dismiss(offer); continuityHint.get()?.dismiss() } })
            }
            // A short-lived hint is anchored to the native cast control, never to the Activity content root.
            val background = activity.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).use { it.getColor(0, android.graphics.Color.WHITE) }
            val popup = PopupWindow(row, minOf(dp(activity, 360), activity.resources.displayMetrics.widthPixels - dp(activity, 24)),
                ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
                isOutsideTouchable = true; setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background))
            }
            continuityHint = WeakReference(popup); popup.showAsDropDown(anchor)
            val weak = WeakReference(popup); main.postDelayed({ weak.get()?.dismiss() }, 6000)
        }
    }
    fun detach(activity: Activity) {
        if (owner.get() !== activity) return
        continuityHint.get()?.dismiss(); continuityHint.clear(); owner.clear(); shownOffer = null
        dialog.get()?.dismiss(); dialog.clear(); status.clear()
    }
    fun open(activity: Activity) {
        owner.get()?.takeIf { it !== activity }?.let(::detach)
        owner = WeakReference(activity); continuityHint.get()?.dismiss()
        dialog.get()?.dismiss()
        val currentVideo = source()
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; updatePadding(horizontal = dp(activity, 16), vertical = dp(activity, 8)) }
        val text = TextView(activity).apply { this.text = runtime()?.status ?: "首次需手动开启并配对，之后打开宿主自动恢复接力。观看状态仅向已配对设备加密发送。" }
        status = WeakReference(text); column.addView(text)
        fun action(label: String, run: () -> Unit) { column.addView(Button(activity).apply {
            this.text = label; isAllCaps = false
            setOnClickListener { runCatching(run).onFailure { activity.toast("操作不可用，请检查 Wi-Fi、系统权限与宿主适配") } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        if (runtime() == null) action("开启本次局域网会话") { start(activity); open(activity) }
        else {
            action("搜索 DLNA 电视") {
                val net = runtime() ?: return@action
                val weak = WeakReference(activity)
                net.scan {
                    main.post { weak.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { target ->
                        if (runtime() === net) television(target, net)
                    } }
                }
            }
            action("遥控当前电视") { runtime()?.let { remote(activity, it) } }
            action("弹幕开关与样式") { styles(activity) }
            action("切换分 P / 选集") {
                val net = runtime() ?: return@action
                val state = snapshot() ?: return@action
                if (net.selected == null) { activity.toast("请先推送到电视"); return@action }
                val weak = WeakReference(activity)
                net.parts(state.bvid) { parts -> main.post {
                    val target = weak.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@post
                    if (runtime() !== net) return@post
                    if (parts.isEmpty()) { target.toast("无法读取分 P，可在手机原播放器切换后重新推送"); return@post }
                    AlertDialog.Builder(target).setTitle("选择分 P 并推送至当前电视")
                        .setItems(parts.map { "P${it.page} · ${it.title}" }.toTypedArray()) { _, index ->
                            runCatching { selectPart(target, parts[index], style); dialog.get()?.dismiss() }
                                .onFailure { target.toast("当前宿主无法切换分 P") }
                        }.setNegativeButton("取消", null).show()
                } }
            }
            action("显示一次性配对码") { runtime()?.let { invitation(activity, it) } }
            action("输入另一台设备的配对码") { runtime()?.let { choosePeer(activity, it) } }
            action("管理与解绑信任设备") { runtime()?.let { trusted(activity, it) } }
            action("取消媒体准备") { runtime()?.cancelPreparation() }
            action("停止本次会话") { stop(); open(activity) }
        }
        column.addView(TextView(activity).apply {
            this.text = "DLNA 媒体链路为电视协议要求的 HTTP，仅所选电视 IP 和随机地址可读取。原画合轨、弹幕合成均需先准备文件；弹幕样式变化需要重新推送。仅支持宿主已获权、无 DRM 的普通视频。首次需配对，公开 UID 不能证明账号身份。"
            textSize = 12f
        })
        val modal = AlertDialog.Builder(activity).setTitle(currentVideo?.let { "投屏：${it.bvid}" } ?: "局域网投屏与安全接力（实验）")
            .setView(ScrollView(activity).apply { addView(column) }).setNegativeButton("关闭", null).create()
        dialog = WeakReference(modal); modal.show()
    }
    private fun television(activity: Activity, net: LanRuntime) {
        val devices = net.devices
        if (devices.isEmpty()) { activity.toast(net.status); return }
        AlertDialog.Builder(activity).setTitle("选择电视并推送当前视频")
            .setItems(devices.map { it.name.ifBlank { it.description.host } }.toTypedArray()) { _, index ->
                val media = source(); val state = snapshot()
                if (media == null || state == null || media.bvid != state.bvid || media.cid != state.cid) {
                    activity.toast("当前视频没有可验证的媒体轨道，请等待播放就绪或切换到普通视频")
                    return@setItems
                }
                val weak = WeakReference(activity)
                net.cast(devices[index], media, state.title, state.positionMs, style.takeIf { it.enabled }) { success ->
                    main.post { weak.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { target ->
                        if (runtime() === net) target.toast(if (success) "推送指令已接受，请确认电视画面" else "投屏未完成")
                    } }
                }
            }.setNegativeButton("取消", null).show()
    }
    private fun remote(activity: Activity, net: LanRuntime) {
        if (net.selected == null) { activity.toast("请先推送到电视"); return }
        AlertDialog.Builder(activity).setTitle(net.selected?.name).setItems(arrayOf("播放", "暂停", "跳转进度", "倍速", "音量", "停止电视播放")) { _, index ->
            when (index) {
                0 -> net.control { client, device -> client.play(device) }
                1 -> net.control { client, device -> client.pause(device) }
                2 -> input(activity, "跳转到第几秒", android.text.InputType.TYPE_CLASS_NUMBER) { value ->
                    val seconds = value.toLongOrNull()?.takeIf { it in 0..604800 } ?: error("invalid-position")
                    net.control { client, device -> client.seek(device, seconds * 1000) }
                }
                3 -> {
                    val speeds = listOf(.1f, .25f, .5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f, 6f, 8f)
                    AlertDialog.Builder(activity).setTitle("倍速（需电视支持）").setItems(speeds.map { "${it}×" }.toTypedArray()) { _, n ->
                        net.control { client, device -> client.play(device, speeds[n]) }
                    }.show()
                }
                4 -> input(activity, "音量 0–100", android.text.InputType.TYPE_CLASS_NUMBER) { value ->
                    val volume = value.toIntOrNull()?.takeIf { it in 0..100 } ?: error("invalid-volume")
                    net.control { client, device -> client.volume(device, volume) }
                }
                5 -> net.control { client, device -> client.stop(device) }
            }
        }.setNegativeButton("关闭", null).show()
    }
    private fun styles(activity: Activity) {
        AlertDialog.Builder(activity).setTitle("弹幕样式（重新推送后生效）").setItems(arrayOf(
            if (style.enabled) "关闭弹幕" else "开启弹幕", "字号", "不透明度", "显示区域")) { _, index ->
            when (index) {
                0 -> { style = style.copy(enabled = !style.enabled); activity.toast(if (style.enabled) "已选择带弹幕合成" else "已选择原画合轨") }
                1 -> choose(activity, "字号", listOf(.75f, 1f, 1.25f, 1.5f)) { style = style.copy(scale = it) }
                2 -> choose(activity, "不透明度", listOf(.5f, .7f, .85f, 1f)) { style = style.copy(opacity = it) }
                3 -> choose(activity, "显示区域", listOf(.25f, .5f, .75f, 1f)) { style = style.copy(coverage = it) }
            }
        }.setNegativeButton("关闭", null).show()
    }
    private fun invitation(activity: Activity, net: LanRuntime) {
        val code = net.invitation()
        val text = TextView(activity).apply {
            this.text = "在另一台设备选择本机 ${net.trust.id.take(6)} 后输入：\n\n$code\n\n两分钟内有效，只能使用一次。"; setTextIsSelectable(true)
            updatePadding(horizontal = dp(activity, 20), vertical = dp(activity, 12))
        }
        AlertDialog.Builder(activity).setTitle("一次性配对码").setView(text)
            .setNeutralButton("复制") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("一次性局域网配对码", code))
                activity.toast("配对码已复制，两分钟内有效")
            }.setNegativeButton("关闭并作废") { _, _ -> net.cancelInvitation() }
            .setOnCancelListener { net.cancelInvitation() }.show()
    }
    private fun choosePeer(activity: Activity, net: LanRuntime) {
        val peers = net.peers().filter { net.trust.get(it.id) == null }
        if (peers.isEmpty()) { activity.toast("未发现未配对设备，请先在另一台设备开启会话，稍候重试"); return }
        AlertDialog.Builder(activity).setTitle("选择显示配对码的设备").setItems(peers.map { "${it.id.take(6)} · ${it.address.host}" }.toTypedArray()) { _, index ->
            input(activity, "一次性配对码", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) { code ->
                LanCrypto.invitationKey(code).fill(0)
                val weak = WeakReference(activity)
                net.pair(peers[index], code) { success -> main.post {
                    weak.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.toast(if (success) "设备已互信" else "配对失败，请重新生成配对码")
                } }
            }
        }.setNegativeButton("取消", null).show()
    }
    private fun trusted(activity: Activity, net: LanRuntime) {
        val devices = net.trust.list()
        if (devices.isEmpty()) { activity.toast("尚无信任设备"); return }
        AlertDialog.Builder(activity).setTitle("信任设备（点选管理）").setItems(devices.map { "${it.name} · ${it.id.take(6)}" }.toTypedArray()) { _, index ->
            val device = devices[index]
            AlertDialog.Builder(activity).setTitle(device.name).setItems(arrayOf("重命名", "解除绑定")) { _, operation ->
                if (operation == 0) input(activity, "设备名称", android.text.InputType.TYPE_CLASS_TEXT) { name ->
                    require(name.isNotBlank() && name.length <= 80); net.trust.put(device.copy(name = name))
                } else { net.revoke(device.id); activity.toast("已解除本机对此设备的信任"); render(activity) }
            }.show()
        }.setNegativeButton("关闭", null).show()
    }
    private fun choose(activity: Activity, title: String, values: List<Float>, selected: (Float) -> Unit) {
        AlertDialog.Builder(activity).setTitle(title).setItems(values.map { it.toString() }.toTypedArray()) { _, index -> selected(values[index]) }.show()
    }
    private fun input(activity: Activity, title: String, type: Int, accepted: (String) -> Unit) {
        val text = EditText(activity).apply { inputType = type; maxLines = 2; filters = arrayOf(android.text.InputFilter.LengthFilter(128)) }
        val modal = AlertDialog.Builder(activity).setTitle(title).setView(text).setPositiveButton("确定", null).setNegativeButton("取消", null).create()
        modal.setOnShowListener { modal.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { accepted(text.text.toString().trim()) }.onSuccess { modal.dismiss() }.onFailure { text.error = "请检查输入内容" }
        } }; modal.show()
    }
    private fun dp(activity: Activity, value: Int): Int = value.toPx(activity)
}
