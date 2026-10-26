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
    @Volatile private var media: CastPublication? = null
    @Volatile private var preparation: AutoCloseable? = null
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
        directory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.matches(Regex("[A-Za-z0-9_-]{22}(\\.mp4|-[0-9]{1,2}\\.(track|range))"))) file.delete()
            if (file.isDirectory && file.name.matches(Regex("stream-[A-Za-z0-9_-]{22}"))) {
                file.listFiles()?.filter { it.name.matches(Regex("[0-9]{1,6}\\.(ts|part)")) }?.forEach(File::delete)
                file.delete()
            }
        }
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
        style: CastDanmakuStyle?, delivery: CastDelivery = CastDelivery.COMPLETE_MP4, completion: (Boolean) -> Unit) {
        if (!preparing.compareAndSet(false, true)) { status = "正在准备视频，可先取消准备"; completion(false); return }
        val attempt = CastAttempt()
        preparation = attempt
        val accepted = task {
            try {
                val comments = if (style?.enabled == true) {
                    status = "正在读取视频弹幕…"
                    CastDanmakuSource.fetch(source.cid) { wifi.network.openConnection(java.net.URL(it)) as java.net.HttpURLConnection }
                } else emptyList()
                val output = delivery.stream
                val streamed = output != null && try {
                    publishStream(device, source, title, position, style, comments, output, attempt)
                    lastStream = StreamRequest(device, source, title, style, delivery)
                    true
                } catch (failure: Exception) {
                    // Fall back only for a capability gap; cancellation and a changed video are never retried.
                    if (attempt.closed || closed.get() || !fallback(failure)) throw failure
                    status = "该视频或电视暂不支持边合成边播，改为完整准备…"
                    false
                }
                if (!streamed) { publishComplete(device, source, title, position, style, comments, attempt); lastStream = null }
                completion(true)
            } catch (failure: Exception) {
                completion(false); throw failure
            } finally {
                attempt.close()
                if (preparation === attempt) preparation = null
                preparing.set(false)
            }
        }
        if (!accepted) {
            attempt.close(); if (preparation === attempt) preparation = null
            preparing.set(false); completion(false)
        }
    }

    private fun fallback(failure: Exception): Boolean = failure is DlnaFault || failure.message.orEmpty().let {
        it.startsWith("ts-") || it in setOf("media-range-ignored", "media-range-unavailable", "media-range-mismatch", "seek-unavailable")
    }

    private fun opener(): (String) -> java.net.HttpURLConnection = { wifi.network.openConnection(java.net.URL(it)) as java.net.HttpURLConnection }

    private fun publishComplete(device: DlnaDevice, source: CastSource, title: String, position: Long,
        style: CastDanmakuStyle?, comments: List<CastDanmaku>, attempt: CastAttempt) {
        val next = CastMedia(directory, opener()).also(attempt::own)
        next.prepare(source, style, comments) { status = it }
        verify(source, attempt)
        transport(device, attempt, next, next.publish(device.description.host, base), title, "video/mp4", position)
        status = "已推送到 ${device.name}，请确认电视实际开始播放"
    }

    private fun publishStream(device: DlnaDevice, source: CastSource, title: String, position: Long,
        style: CastDanmakuStyle?, comments: List<CastDanmaku>, output: CastStreamOutput, attempt: CastAttempt) {
        val pipeline = CastStreamPipeline(directory, source, style, comments, opener()) { status = it }
        var published: CastStreamCast? = null
        val space = CastWriteSpace({ directory.usableSpace })
        val session = try {
            CastStreamSession(directory, device.description.host, pipeline, hasSpace = space::allows, failed = {
                if (media === published && !closed.get()) status = "后续片段准备失败，电视播放到已准备部分后会停止"
            })
        } catch (failure: Exception) { pipeline.close(); throw failure }
        val cast = CastStreamCast(session, pipeline, output).also(attempt::own)
        session.start(position.coerceAtLeast(0) * 1000)
        status = "正在准备首批分片…"
        session.awaitReady { !closed.get() && !attempt.closed }
        verify(source, attempt)
        published = cast
        transport(device, attempt, cast, session.uri(base, output), title, output.mime, 0)
        status = "已推送到 ${device.name}（边合成边播），后续片段持续准备中"
    }

    private fun verify(source: CastSource, attempt: CastAttempt) {
        check(!closed.get() && preparation === attempt && !attempt.closed) { "media-preparation-cancelled" }
        check(watching?.let { it.bvid == source.bvid && it.cid == source.cid } == true) { "current-video-changed" }
    }

    private fun transport(device: DlnaDevice, attempt: CastAttempt, next: CastPublication, uri: String, title: String,
        mime: String, seekMs: Long) {
        val previous = media
        media = next
        try {
            synchronized(transportLock) {
                check(!closed.get())
                dlna.setMedia(device, uri, title, mime); dlna.play(device)
                if (seekMs >= 1000) dlna.seek(device, seekMs)
                selected = device
            }
            check(!closed.get())
        } catch (failure: Exception) {
            synchronized(transportLock) { runCatching { dlna.stop(device) } }
            media = previous; throw failure
        }
        attempt.disown(next)
        previous?.close()
    }

    /**
     * Seeks in source time. A streaming cast maps to its own clock and seeks the receiver only inside published
     * HLS segments; anything else rebuilds the stream from the target, with a short rebuffer.
     */
    fun seek(positionMs: Long) = task {
        require(positionMs in 0..604_800_000) { "invalid-position" }
        val rebuild = synchronized(transportLock) {
            check(!closed.get())
            val device = selected ?: error("no-selected-receiver")
            val stream = media as? CastStreamCast
            if (stream == null) { dlna.seek(device, positionMs); status = "电视已接受遥控指令"; return@task }
            val outputUs = stream.session.timeline.outputUs(positionMs * 1000)
            if (stream.output == CastStreamOutput.HLS_TS && outputUs >= 0 && outputUs < stream.session.publishedUs) {
                dlna.seek(device, outputUs / 1000); status = "电视已接受遥控指令"; return@task
            }
            lastStream ?: error("stream-seek-unavailable")
        }
        status = "目标进度尚未准备，正在从该位置重新推送…"
        cast(rebuild.device, rebuild.source, rebuild.title, positionMs, rebuild.style, rebuild.delivery) {}
    }

    private data class StreamRequest(val device: DlnaDevice, val source: CastSource, val title: String,
        val style: CastDanmakuStyle?, val delivery: CastDelivery)
    @Volatile private var lastStream: StreamRequest? = null

    /** Owns whatever the current attempt prepared until it is published; cancellation closes it. */
    private class CastAttempt : AutoCloseable {
        @Volatile var closed = false; private set
        private var current: AutoCloseable? = null
        @Synchronized fun own(value: AutoCloseable) {
            if (closed) { runCatching { value.close() }; error("media-preparation-cancelled") }
            // A failed stream attempt is released before the complete-MP4 fallback takes ownership.
            current?.let { runCatching { it.close() } }
            current = value
        }
        @Synchronized fun disown(value: AutoCloseable) { if (current === value) current = null }
        override fun close() {
            val owned = synchronized(this) { closed = true; current.also { current = null } }
            owned?.let { runCatching { it.close() } }
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
