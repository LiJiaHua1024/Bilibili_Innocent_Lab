package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real loopback sockets only; no phone, LAN multicast, Bilibili API or external service. */
class LanSocketTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private fun client() = LanHttpClient { object : Socket() {
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            super.connect(InetSocketAddress(loopback, (endpoint as InetSocketAddress).port), timeout)
        }
    } }
    private fun watching(title: String) = ContinuityState("session-$title", "BV1xx411c7mD", 42, 1, title,
        1000, 600_000, 1.25f, true, System.currentTimeMillis())
    @Test fun `two peers pair and exchange encrypted watch states over real HTTP then revoke`() {
        val a = LanCrypto.randomId(); val b = LanCrypto.randomId()
        val aTrust = java.util.concurrent.ConcurrentHashMap<String, TrustedLanDevice>(); val bTrust = java.util.concurrent.ConcurrentHashMap<String, TrustedLanDevice>()
        val receivedA = java.util.concurrent.CopyOnWriteArrayList<ContinuityState?>(); val receivedB = java.util.concurrent.CopyOnWriteArrayList<ContinuityState?>()
        val packets = java.util.concurrent.CopyOnWriteArrayList<String>()
        val protocolA = LanPeerProtocol(a, aTrust::get, { aTrust[it.id] = it }, { watching("private-A") }, { _, state -> receivedA += state })
        val protocolB = LanPeerProtocol(b, bTrust::get, { bTrust[it.id] = it }, { watching("private-B") }, { _, state -> receivedB += state })
        LanHttpServer(loopback, { true }) { request -> packets += request.body; protocolA.handle(request) }.use { serverA ->
            LanHttpServer(loopback, { true }) { request -> packets += request.body; protocolB.handle(request) }.use { serverB ->
                val peerA = LanPeer(a, URI("http://192.168.77.1:${serverA.port}"))
                val peerB = LanPeer(b, URI("http://192.168.77.2:${serverB.port}"))
                val code = protocolA.invite()
                val storedA = protocolB.pairWith(peerA, code, "Phone B", client())
                assertArrayEquals(storedA.key, aTrust.getValue(b).key)
                protocolB.exchange(peerA, storedA, client())
                protocolA.exchange(peerB, aTrust.getValue(b), client())
                assertEquals("private-B", receivedA.last()!!.title)
                assertEquals("private-A", receivedB.last()!!.title)
                assertTrue(packets.filter(String::isNotEmpty).all { !it.contains("private-A") && !it.contains("private-B") && !it.contains("BV1xx411c7mD") })
                aTrust.remove(b)
                assertTrue(runCatching { protocolB.exchange(peerA, storedA, client()) }.isFailure)
            }
        }
    }
    @Test fun `untrusted state request and stale boot are denied without returning watch data`() {
        val a = LanCrypto.randomId(); val b = LanCrypto.randomId(); val key = LanCrypto.key()
        val peers = hashMapOf(b to TrustedLanDevice(b, "B", key, 1))
        val protocol = LanPeerProtocol(a, peers::get, {}, { watching("private-A") }, { _, _ -> })
        LanHttpServer(loopback, { true }, protocol::handle).use { server ->
            val endpoint = URI("http://192.168.77.1:${server.port}/v1/state")
            val request = JSONObject().put("request", LanCrypto.randomId()).put("at", System.currentTimeMillis()).toString()
            val stale = LanCrypto.seal(key, LanCrypto.context("state-request", b, a, LanCrypto.randomId()), request)
            assertEquals(403, client().request(endpoint, "POST", stale, mapOf("X-Bil-Device" to b)).status)
            val stranger = LanCrypto.randomId()
            assertEquals(403, client().request(endpoint, "POST", stale, mapOf("X-Bil-Device" to stranger)).status)
            val valid = LanCrypto.seal(key, LanCrypto.context("state-request", b, a, protocol.boot), request)
            assertEquals(200, client().request(endpoint, "POST", valid, mapOf("X-Bil-Device" to b)).status)
            assertEquals(403, client().request(endpoint, "POST", valid, mapOf("X-Bil-Device" to b)).status)
        }
    }
    @Test fun `default server rejects loopback remote even when bound to loopback for testing`() {
        LanHttpServer(loopback) { LanResponse(body = "secret".toByteArray()) }.use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, server.port), 1000); socket.soTimeout = 1000
                assertEquals(-1, socket.getInputStream().read())
            }
        }
    }
    @Test fun `server supports HEAD without writing a response body`() {
        LanHttpServer(loopback, { true }) { LanResponse(body = "test-body".toByteArray()) }.use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, server.port), 1000); socket.soTimeout = 2000
                socket.getOutputStream().apply { write("HEAD /file HTTP/1.1\r\nHost: test\r\n\r\n".toByteArray()); flush() }
                val reply = String(socket.getInputStream().readBytes())
                assertTrue(reply.contains("Content-Length: 9")); assertTrue(reply.endsWith("\r\n\r\n")); assertFalse(reply.contains("test-body"))
            }
        }
    }
    @Test fun `legacy TV HTTP requests work over real sockets`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        LanHttpServer(loopback, { true }) { calls.incrementAndGet(); LanResponse(body = "ok".toByteArray()) }.use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress(loopback, server.port), 1000); socket.soTimeout = 2000
                socket.getOutputStream().apply { write("GET /file HTTP/1.0\r\n\r\n".toByteArray()); flush() }
                assertTrue(String(socket.getInputStream().readBytes()).endsWith("ok"))
            }
            assertEquals(1, calls.get())
        }
    }
    @Test fun `mock DLNA receiver accepts media seek and scaled volume and returns speed fault`() {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val description = """<root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><UDN>uuid:mock</UDN><friendlyName>Mock TV</friendlyName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL><SCPDURL>/av.xml</SCPDURL></service>
            <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL><SCPDURL>/rc.xml</SCPDURL></service>
            </serviceList></device></root>"""
        LanHttpServer(loopback, { true }) { request ->
            val action = request.headers["soapaction"].orEmpty().substringAfter('#').removeSuffix("\"")
            calls += action to request.body
            val body = when (request.path) {
                "/description" -> description
                "/rc.xml" -> "<scpd><serviceStateTable><stateVariable><name>Volume</name><allowedValueRange><minimum>0</minimum><maximum>255</maximum><step>1</step></allowedValueRange></stateVariable></serviceStateTable></scpd>"
                else -> if (action == "Play" && request.body.contains("<Speed>3/2</Speed>"))
                    "<r><UPnPError><errorCode>717</errorCode><errorDescription>Speed not supported</errorDescription></UPnPError></r>" else "<r/>"
            }
            LanResponse(if (body.contains("UPnPError")) 500 else 200, "text/xml", body.toByteArray())
        }.use { server ->
            val dlna = DlnaClient(client())
            val descriptionUri = URI("http://192.168.77.5:${server.port}/description")
            val device = DlnaProtocol.description(descriptionUri, dlna.request(descriptionUri))
            dlna.setMedia(device, "http://192.168.77.1/video?x=1&y=2", "title & detail", "video/mp4")
            dlna.play(device); dlna.pause(device); dlna.seek(device, 62_000); dlna.volume(device, 50)
            assertTrue(calls.any { it.first == "SetAVTransportURI" && it.second.contains("&amp;y=2") })
            assertTrue(calls.any { it.first == "Seek" && it.second.contains("<Target>00:01:02</Target>") })
            assertTrue(calls.any { it.first == "SetVolume" && it.second.contains("<DesiredVolume>127</DesiredVolume>") })
            val fault = runCatching { dlna.play(device, 1.5f) }.exceptionOrNull()
            assertEquals(717, (fault as DlnaFault).code)
        }
    }
}
