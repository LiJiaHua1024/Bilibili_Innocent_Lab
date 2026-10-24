package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import com.Bilibili_Innocent_Lab.xposedmodule.lan.DlnaProtocol.value

/** An explicit scan owns its socket; no always-on multicast listener and no discovery of watch history. */
internal class DlnaClient(private val http: LanHttpClient = LanHttpClient(), private val bindSocket: (DatagramSocket) -> Unit = {}) {
    fun request(uri: URI, action: String? = null, body: String? = null): String {
        LanAddress.url(uri.toString())
        val response = http.request(uri, if (body == null) "GET" else "POST", body.orEmpty(), if (body == null) emptyMap() else
            mapOf("Content-Type" to "text/xml; charset=utf-8", "SOAPAction" to "\"$action\""))
        if (response.status !in 200..299) { DlnaProtocol.response(response.body); error("http-${response.status}") }
        return response.body
    }

    fun discover(bind: InetAddress): List<DlnaDevice> {
        val locations = linkedSetOf<URI>()
        DatagramSocket(InetSocketAddress(bind, 0)).use { socket ->
            bindSocket(socket)
            socket.soTimeout = 500
            val search = DlnaProtocol.search
            repeat(2) { socket.send(DatagramPacket(search, search.size, InetAddress.getByName("239.255.255.250"), 1900)) }
            val end = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < end && locations.size < 32) {
                val packet = DatagramPacket(ByteArray(8192), 8192)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                DlnaProtocol.location(String(packet.data, 0, packet.length, Charsets.US_ASCII), packet.address.hostAddress.orEmpty())
                    ?.let(locations::add)
            }
        }
        return locations.mapNotNull { uri -> runCatching { DlnaProtocol.description(uri, request(uri)) }.getOrNull() }
            .distinctBy { it.id.ifBlank { it.description.toString() } }
    }
    fun call(service: DlnaService, action: String, fields: Map<String, String> = emptyMap()): org.w3c.dom.Element =
        DlnaProtocol.response(request(service.control, "${service.type}#$action",
            DlnaProtocol.soap(service, action, linkedMapOf("InstanceID" to "0") + fields)))

    fun setMedia(device: DlnaDevice, uri: String, title: String, mime: String) {
        call(device.transport, "SetAVTransportURI", mapOf("CurrentURI" to uri,
            "CurrentURIMetaData" to DlnaProtocol.metadata(title, uri, mime)))
    }
    fun play(device: DlnaDevice, speed: Float = 1f) {
        require(speed.isFinite() && speed in .1f..8f)
        val percent = java.math.BigDecimal(speed.toString()).movePointRight(2).intValueExact()
        val divisor = java.math.BigInteger.valueOf(percent.toLong()).gcd(java.math.BigInteger.valueOf(100)).toInt()
        val fraction = if (divisor == 100) (percent / 100).toString() else "${percent / divisor}/${100 / divisor}"
        call(device.transport, "Play", mapOf("Speed" to fraction))
    }
    fun pause(device: DlnaDevice) { call(device.transport, "Pause") }
    fun stop(device: DlnaDevice) { call(device.transport, "Stop") }
    fun seek(device: DlnaDevice, position: Long) { call(device.transport, "Seek", mapOf("Unit" to "REL_TIME", "Target" to DlnaProtocol.time(position))) }
    fun volume(device: DlnaDevice, volume: Int) {
        require(volume in 0..100)
        val rendering = device.rendering ?: error("volume-unavailable")
        val descriptor = DlnaProtocol.xml(request(rendering.scpd))
        // DesiredVolume uses the receiver's declared range, which is not necessarily 0..100.
        val variables = descriptor.getElementsByTagNameNS("*", "stateVariable")
        val variable = (0 until variables.length).map { variables.item(it) as org.w3c.dom.Element }
            .singleOrNull { it.value("name") == "Volume" } ?: error("volume-range-unavailable")
        val min = variable.value("minimum").toIntOrNull() ?: error("volume-range-unavailable")
        val max = variable.value("maximum").toIntOrNull() ?: error("volume-range-unavailable")
        val step = variable.value("step").toIntOrNull()?.coerceAtLeast(1) ?: 1
        require(min in 0..65535 && max in min..65535)
        val level = min + ((max - min).toLong() * volume / 100 / step * step).toInt()
        call(rendering, "SetVolume", mapOf("Channel" to "Master", "DesiredVolume" to level.toString()))
    }
    fun position(device: DlnaDevice): DlnaPosition = call(device.transport, "GetPositionInfo").let {
        DlnaPosition(DlnaProtocol.millis(it.value("RelTime")) ?: error("position-unavailable"),
            DlnaProtocol.millis(it.value("TrackDuration")) ?: 0)
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) return output.toByteArray()
        require(output.size() + count <= limit) { "response-too-large" }
        output.write(buffer, 0, count)
    }
}
