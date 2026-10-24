package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One stoppable Wi-Fi session. A paired host may restore discovery; casts are never replayed. */
internal class LanRuntime(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val workers = java.util.concurrent.ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue(8), { task -> Thread(task, "BIL-LAN-Task").apply { isDaemon = true } })
    private val sync = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "BIL-LAN-Sync").apply { isDaemon = true } }
    val trust = LanTrustStore(app)
    val offers = ContinuityOffers()
    val wifi = LanWifi.current(app)
    private val http = LanHttpClient { wifi.network.socketFactory.createSocket() }
    val dlna = DlnaClient(http) { wifi.network.bindSocket(it) }
    private val directory = File(app.cacheDir, "bil-lan-cast")
    @Volatile var watching: ContinuityState? = null
    @Volatile var status = "局域网会话已开启"; private set
    @Volatile var devices: List<DlnaDevice> = emptyList(); private set
    @Volatile var selected: DlnaDevice? = null; private set
    @Volatile private var media: CastMedia? = null
    @Volatile private var preparation: CastMedia? = null
    private val preparing = AtomicBoolean(false)
    private val transportLock = Any()
    private val protocol = LanPeerProtocol(trust.id, trust::get, trust::put, { watching }, { id, state ->
        offers.receive(id, state, System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }, elapsed = SystemClock::elapsedRealtime)
    private val server = LanHttpServer(wifi.address) { request ->
        if (closed.get()) LanResponse(403)
        else if (request.path.startsWith("/media/")) media?.response(request) ?: LanResponse(404)
        else protocol.handle(request)
    }
    private var discovery: LanDiscovery? = null
    val running get() = !closed.get()
    val isPreparing get() = preparing.get()
    val base get() = "http://${wifi.address.hostAddress}:${server.port}"
    init {
        try {
            discovery = LanDiscovery(app, trust.id, server.port, wifi) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { close() }
            }
        } catch (failure: Exception) {
            server.close(); workers.shutdownNow(); sync.shutdownNow(); throw failure
        }
        // Remove only files owned by this feature from an interrupted previous session.
        directory.listFiles()?.filter { it.name.matches(Regex("[A-Za-z0-9_-]{22}(\\.mp4|-[0-9]{1,2}\\.track)")) }?.forEach(File::delete)
        sync.scheduleWithFixedDelay({
            if (closed.get()) return@scheduleWithFixedDelay
            for (peer in discovery?.peers().orEmpty()) {
                if (closed.get()) break
                val trusted = trust.get(peer.id) ?: continue
                runCatching { protocol.exchange(peer, trusted, http) }
            }
        }, 1, 4, TimeUnit.SECONDS)
    }
    fun peers() = discovery?.peers().orEmpty()
    fun invitation() = protocol.invite()
    fun cancelInvitation() = protocol.cancelInvitation()
    fun pair(peer: LanPeer, code: String, completion: (Boolean) -> Unit) = task {
        val success = runCatching { protocol.pairWith(peer, code, android.os.Build.MODEL.take(80), http) }.isSuccess
        status = if (success) "设备已配对" else "配对失败：检查配对码、有效期与局域网连接"
        completion(success)
    }
    fun revoke(id: String) { trust.remove(id); offers.revoke(id) }
    fun parts(bvid: String, complete: (List<LanVideoPart>) -> Unit) = task {
        status = "正在读取视频分 P…"
        try {
            val values = LanVideoParts.fetch(bvid) { wifi.network.openConnection(java.net.URL(it)) as java.net.HttpURLConnection }
            complete(values); status = "请选择分 P"
        } catch (failure: Exception) { complete(emptyList()); throw failure }
    }
    fun scan(complete: () -> Unit = {}) = task {
        status = "正在搜索 DLNA 电视…"
        devices = dlna.discover(wifi.address)
        status = if (devices.isEmpty()) "未找到 DLNA 电视，请检查同一 Wi-Fi 与电视接收开关" else "找到 ${devices.size} 台电视"
        complete()
    }
    fun cast(device: DlnaDevice, source: CastSource, title: String, position: Long,
        style: CastDanmakuStyle?, completion: (Boolean) -> Unit) {
        if (!preparing.compareAndSet(false, true)) { status = "正在准备视频，可先取消准备"; completion(false); return }
        val next = CastMedia(directory) { wifi.network.openConnection(java.net.URL(it)) as java.net.HttpURLConnection }
        preparation = next
        val accepted = task {
            try {
                val comments = if (style?.enabled == true) {
                    status = "正在读取视频弹幕…"
                    CastDanmakuSource.fetch(source.cid) { wifi.network.openConnection(java.net.URL(it)) as java.net.HttpURLConnection }
                } else emptyList()
                next.prepare(source, style, comments) { status = it }
                check(!closed.get() && preparation === next)
                check(watching?.let { it.bvid == source.bvid && it.cid == source.cid } == true) { "current-video-changed" }
                val uri = next.publish(device.description.host, base)
                val previous = media
                media = next
                try {
                    synchronized(transportLock) {
                        check(!closed.get())
                        dlna.setMedia(device, uri, title, "video/mp4"); dlna.play(device)
                        if (position >= 1000) dlna.seek(device, position)
                        selected = device
                    }
                    check(!closed.get())
                } catch (failure: Exception) {
                    synchronized(transportLock) { runCatching { dlna.stop(device) } }
                    media = previous; next.close(); throw failure
                }
                previous?.close()
                status = "已推送到 ${device.name}，请确认电视实际开始播放"
                completion(true)
            } catch (failure: Exception) {
                completion(false); throw failure
            } finally {
                if (media !== next) next.close()
                if (preparation === next) preparation = null
                preparing.set(false)
            }
        }
        if (!accepted) {
            next.close(); if (preparation === next) preparation = null
            preparing.set(false); completion(false)
        }
    }
    fun cancelPreparation() { preparation?.close(); preparation = null; status = "已取消准备" }
    fun control(operation: (DlnaClient, DlnaDevice) -> Unit) = task {
        synchronized(transportLock) {
            check(!closed.get())
            val device = selected ?: error("no-selected-receiver")
            operation(dlna, device); status = "电视已接受遥控指令"
        }
    }
    private fun task(action: () -> Unit): Boolean {
        if (closed.get()) return false
        try { workers.execute {
            try { check(!closed.get()); action() }
            catch (error: Exception) { if (!closed.get()) status = when (error) {
                is DlnaFault -> "电视拒绝指令（${error.code}）；该电视可能不支持此能力"
                is MediaFetchFailure -> "媒体服务器拒绝读取（HTTP ${error.status}），未向电视推送其他内容"
                is SecurityException -> "局域网权限不足，请检查系统权限"
                else -> "操作未完成：${error.message?.takeIf { it.matches(Regex("[a-z0-9-]{1,60}")) } ?: error.javaClass.simpleName}"
            } }
        }; return true } catch (_: java.util.concurrent.RejectedExecutionException) { status = "操作队列已满，请稍候"; return false }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        status = "局域网会话已关闭"
        protocol.cancelInvitation(); watching = null; offers.clear()
        preparation?.close(); media?.close(); media = null
        discovery?.close(); discovery = null
        server.close(); sync.shutdownNow(); workers.shutdownNow()
        Thread({
            synchronized(transportLock) { selected?.let { runCatching { dlna.stop(it) } }; selected = null }
        }, "BIL-LAN-Stop").apply { isDaemon = true; start() }
    }
}
