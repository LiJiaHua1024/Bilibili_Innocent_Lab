package com.Bilibili_Innocent_Lab.xposedmodule.agent

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostReceiptRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.ReceiptSessionGate
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 只接受现有回执鉴权链登记的端点；等待发生在任务线程，永不通过广播执行动作。 */
internal object AgentHostClient {
    private data class Connection(val session: ReceiptSessionGate.Session<IBinder>, val endpoint: IBinder)
    @Volatile private var connection: Connection? = null

    fun register(session: ReceiptSessionGate.Session<IBinder>, endpoint: IBinder?) {
        val previous = connection
        if (previous?.session == session && previous.endpoint == endpoint) return
        connection = endpoint?.let { Connection(session, it) }
    }

    fun ready(): Boolean {
        val current = connection ?: return false
        return HostReceiptRegistry.current() == current.session && current.endpoint.isBinderAlive
    }

    fun awaitReady(deadline: Long, cancelled: () -> Boolean): Boolean {
        while (!cancelled() && SystemClock.elapsedRealtime() < deadline) {
            if (ready()) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    fun request(taskId: String, sequence: Long, deadline: Long, operation: String,
                arguments: JSONObject = JSONObject(), cancelled: () -> Boolean = { false }): JSONObject {
        val current = connection ?: return failure("host_unavailable")
        if (!ready() || cancelled()) return failure("host_unavailable")
        val completed = CountDownLatch(1)
        val outcome = AtomicReference<JSONObject?>()
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != AgentWire.RESPONSE || cancelled() || connection !== current ||
                    !HostReceiptRegistry.isCurrent(current.session, getCallingUid()) ||
                    data.dataSize() > AgentWire.MAX_RESPONSE_BYTES) return false
                return runCatching {
                    data.enforceInterface(AgentWire.DESCRIPTOR)
                    if (data.readString() != taskId || data.readLong() != sequence) return@runCatching false
                    val payload = data.readString() ?: return@runCatching false
                    if (SystemClock.elapsedRealtime() >= deadline || payload.length > AgentWire.MAX_RESPONSE_BYTES / 2)
                        return@runCatching false
                    val decoded = JSONObject(payload)
                    if (decoded.opt("ok") !is Boolean) return@runCatching false
                    if (outcome.compareAndSet(null, decoded)) completed.countDown()
                    true
                }.getOrDefault(false)
            }
        }
        val outgoing = Parcel.obtain()
        try {
            outgoing.writeInterfaceToken(AgentWire.DESCRIPTOR)
            outgoing.writeString(taskId)
            outgoing.writeLong(sequence)
            outgoing.writeLong(deadline)
            outgoing.writeString(operation)
            outgoing.writeString(arguments.toString())
            outgoing.writeStrongBinder(callback)
            if (outgoing.dataSize() > AgentWire.MAX_REQUEST_BYTES) return failure("request_too_large")
            if (!current.endpoint.transact(AgentWire.REQUEST, outgoing, null, IBinder.FLAG_ONEWAY))
                return failure("host_protocol_unavailable")
            while (!cancelled() && connection === current && ready()) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                if (completed.await(minOf(remaining, 100), TimeUnit.MILLISECONDS)) return outcome.get() ?: failure("invalid_response")
            }
            return failure(if (cancelled()) "cancelled" else "host_response_timeout")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure("cancelled")
        } catch (_: Exception) {
            return failure("host_disconnected")
        } finally {
            outgoing.recycle()
        }
    }

    private fun failure(reason: String) = JSONObject().put("ok", false).put("error", reason)
}
