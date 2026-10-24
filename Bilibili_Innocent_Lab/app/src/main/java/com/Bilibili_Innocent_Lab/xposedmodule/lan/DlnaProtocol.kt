package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

internal data class DlnaService(val type: String, val control: URI, val scpd: URI)
internal data class DlnaDevice(val id: String, val name: String, val description: URI,
    val transport: DlnaService, val rendering: DlnaService?)
internal data class DlnaPosition(val positionMs: Long, val durationMs: Long)
internal class DlnaFault(val code: Int, message: String) : Exception("DLNA $code: $message")

internal object DlnaProtocol {
    const val SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1"
    val search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $SEARCH_TARGET\r\n\r\n").toByteArray(Charsets.US_ASCII)

    fun location(response: String, sender: String): URI? = runCatching {
        require(response.length <= 8192 && response.startsWith("HTTP/1.1 200"))
        val fields = response.split("\r\n").drop(1).filter { ':' in it }.groupBy(
            { it.substringBefore(':').trim().lowercase(Locale.ROOT) }, { it.substringAfter(':').trim() })
        require(fields["st"]?.singleOrNull()?.contains("MediaRenderer:") == true)
        LanAddress.url(fields["location"]?.singleOrNull() ?: error("missing-location"), sender)
    }.getOrNull()

    fun xml(value: String, limit: Int = 262_144): Element {
        require(value.toByteArray(Charsets.UTF_8).size <= limit)
        require(!value.contains("<!DOCTYPE", true) && !value.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Android's parser does not implement the JAXP external-access attributes. Reject DTDs above.
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8))).documentElement
    }

    fun description(uri: URI, raw: String): DlnaDevice {
        val root = xml(raw)
        val devices = root.getElementsByTagNameNS("*", "device")
        val device = (0 until devices.length).map { devices.item(it) as Element }.firstOrNull {
            it.child("deviceType").startsWith("urn:schemas-upnp-org:device:MediaRenderer:")
        } ?: error("not-a-renderer")
        val baseText = root.child("URLBase")
        val base = if (baseText.isBlank()) uri else LanAddress.sameOrigin(uri, baseText)
        val serviceList = device.direct("serviceList") ?: error("missing-services")
        val services = serviceList.getElementsByTagNameNS("*", "service")
        fun service(name: String): DlnaService? = (0 until services.length).map { services.item(it) as Element }
            .firstOrNull { it.child("serviceType").startsWith("urn:schemas-upnp-org:service:$name:") }?.let {
                val type = it.child("serviceType")
                require(type.matches(Regex("urn:schemas-upnp-org:service:[A-Za-z]+:[1-3]")))
                DlnaService(type, LanAddress.sameOrigin(base, it.child("controlURL")),
                    LanAddress.sameOrigin(base, it.child("SCPDURL")))
            }
        return DlnaDevice(device.child("UDN").take(128), device.child("friendlyName").take(100), uri,
            service("AVTransport") ?: error("missing-avtransport"), service("RenderingControl"))
    }

    fun actions(raw: String): Set<String> {
        val nodes = xml(raw).getElementsByTagNameNS("*", "action")
        return (0 until nodes.length).map { (nodes.item(it) as Element).child("name") }.toSet()
    }
    fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    fun soap(service: DlnaService, action: String, fields: Map<String, String>): String {
        require(action.matches(Regex("[A-Za-z]+")) && fields.keys.all { it.matches(Regex("[A-Za-z]+")) })
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"${service.type}\">" + fields.entries.joinToString("") { (k, v) -> "<$k>${escape(v)}</$k>" } +
            "</u:$action></s:Body></s:Envelope>"
    }
    fun response(raw: String): Element = xml(raw).also { root ->
        val errors = root.getElementsByTagNameNS("*", "UPnPError")
        if (errors.length > 0) {
            val error = errors.item(0) as Element
            throw DlnaFault(error.child("errorCode").toIntOrNull() ?: 0, error.child("errorDescription").take(100))
        }
    }
    fun metadata(title: String, uri: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\"><dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class><res protocolInfo=\"http-get:*:${escape(mime)}:*\">${escape(uri)}</res></item></DIDL-Lite>"
    fun time(ms: Long): String {
        require(ms in 0..604_800_000)
        val seconds = ms / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
    fun millis(value: String): Long? {
        if (!value.matches(Regex("[0-9]{1,3}:[0-5][0-9]:[0-5][0-9](\\.[0-9]{1,3})?"))) return null
        val fields = value.split(':')
        return ((fields[0].toLong() * 3600 + fields[1].toLong() * 60) * 1000 + (fields[2].toDouble() * 1000).toLong())
            .takeIf { it <= 604_800_000 }
    }
    fun Element.value(name: String): String = getElementsByTagNameNS("*", name).item(0)?.textContent.orEmpty()
    private fun Element.direct(name: String): Element? = (0 until childNodes.length).map { childNodes.item(it) }
        .filterIsInstance<Element>().firstOrNull { it.localName == name || it.nodeName == name }
    private fun Element.child(name: String): String = direct(name)?.textContent.orEmpty().trim()
}
