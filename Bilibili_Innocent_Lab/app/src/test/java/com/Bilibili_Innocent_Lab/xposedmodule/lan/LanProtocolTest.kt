package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.net.InetAddress
import java.net.URI
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LanProtocolTest {
    private val now = 1_700_000_000_000L
    private fun state(playing: Boolean = true, at: Long = now) = ContinuityState("session", "BV1xx411c7mD", 42, 1,
        "private title", 50_000, 600_000, 1.5f, playing, at)
    @Test fun `private address policy excludes DNS loopback public metadata and ambiguous IPv4`() {
        listOf("192.168.1.20", "10.1.2.3", "172.16.0.2", "172.31.3.4", "169.254.1.2").forEach { assertNotNull(LanAddress.privateV4(it)) }
        listOf("127.0.0.1", "0.0.0.0", "8.8.8.8", "172.32.0.1", "localhost", "192.168.001.1", "::1", "2130706433", "192.168.1.999").forEach { assertNull(it, LanAddress.privateV4(it)) }
        listOf("http://user@192.168.1.2/x", "https://192.168.1.2/x", "http://192.168.1.2/#x", "http://example.com/x").forEach {
            assertTrue(it, runCatching { LanAddress.url(it) }.isFailure)
        }
    }
    @Test fun `SSDP location must match unicast response origin`() {
        val response = "HTTP/1.1 200 OK\r\nST: ${DlnaProtocol.SEARCH_TARGET}\r\nLOCATION: http://192.168.1.2:123/desc.xml\r\n\r\n"
        assertNotNull(DlnaProtocol.location(response, "192.168.1.2"))
        assertNull(DlnaProtocol.location(response, "192.168.1.3"))
        assertNull(DlnaProtocol.location(response.replace("192.168.1.2:123", "169.254.169.254"), "192.168.1.2"))
        assertNull(DlnaProtocol.location(response.replace("LOCATION:", "LOCATION: http://192.168.1.2/x\r\nLOCATION:"), "192.168.1.2"))
    }
    private fun description(control: String = "/control") = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
        <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>Test TV</friendlyName><UDN>uuid:tv</UDN>
        <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>$control</controlURL><SCPDURL>/av.xml</SCPDURL></service></serviceList>
        </device></root>"""
    @Test fun `device service URLs are pinned to description origin`() {
        val uri = URI("http://192.168.1.2:123/desc.xml")
        assertEquals(URI("http://192.168.1.2:123/control"), DlnaProtocol.description(uri, description()).transport.control)
        listOf("http://192.168.1.3:123/x", "http://192.168.1.2:456/x", "//example.com/x").forEach {
            assertTrue(runCatching { DlnaProtocol.description(uri, description(it)) }.isFailure)
        }
    }
    @Test fun `XML refuses DTD external entity and oversized payload`() {
        val evil = """<!DOCTYPE r [<!ENTITY x SYSTEM "file:///secret">]><r>&x;</r>"""
        assertTrue(runCatching { DlnaProtocol.xml(evil) }.isFailure)
        assertTrue(runCatching { DlnaProtocol.xml("<r>" + "a".repeat(270_000) + "</r>") }.isFailure)
        assertEquals("a&b", DlnaProtocol.xml("<r>a&amp;b</r>").textContent)
    }
    @Test fun `SOAP escapes both URI metadata and field content and surfaces protocol fault`() {
        val service = DlnaService("urn:schemas-upnp-org:service:AVTransport:1", URI("http://192.168.1.2/c"), URI("http://192.168.1.2/s"))
        val text = DlnaProtocol.soap(service, "SetAVTransportURI", mapOf("CurrentURI" to "http://192.168.1.3/v?a=1&b=2"))
        assertTrue(text.contains("&amp;b=2")); DlnaProtocol.xml(text)
        val failure = runCatching { DlnaProtocol.response("<r><UPnPError><errorCode>717</errorCode><errorDescription>Speed unavailable</errorDescription></UPnPError></r>") }.exceptionOrNull()
        assertEquals(717, (failure as DlnaFault).code)
    }
    @Test fun `HTTP media ranges handle suffix open end clamp and malformed ranges`() {
        assertEquals(ByteRange(0, 99), ByteRange.parse(null, 100))
        assertEquals(ByteRange(10, 99), ByteRange.parse("bytes=10-", 100))
        assertEquals(ByteRange(90, 99), ByteRange.parse("bytes=-10", 100))
        assertEquals(ByteRange(0, 99), ByteRange.parse("bytes=-200", 100))
        assertEquals(ByteRange(90, 99), ByteRange.parse("bytes=90-999", 100))
        listOf("bytes=100-", "bytes=10-2", "bytes=0-1,5-6", "bytes=-0", "bytes=-", "bytes=999999999999999999999-").forEach { assertNull(it, ByteRange.parse(it, 100)) }
    }
    @Test fun `request parser rejects malformed lengths duplicate framing oversized and truncated inputs`() {
        fun parse(raw: String) = LanHttpServer.readRequest(raw.byteInputStream(), InetAddress.getLoopbackAddress())
        val valid = parse("POST /v1/state HTTP/1.1\r\nContent-Length: 3\r\nX-Bil-Device: random-id\r\n\r\nabc")
        assertEquals("abc", valid.body); assertEquals("random-id", valid.headers["x-bil-device"])
        listOf("Content-Length: invalid", "Content-Length: -1", "Content-Length: 24001",
            "Content-Length: 0\r\nContent-Length: 0", "Transfer-Encoding: chunked", "Expect: 100-continue").forEach {
            assertTrue(it, runCatching { parse("POST /v1/state HTTP/1.1\r\n$it\r\n\r\n") }.isFailure)
        }
        assertTrue(runCatching { parse("GET /v1/hello HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc") }.isFailure)
        assertTrue(runCatching { parse("POST /v1/state HTTP/1.1\r\nContent-Length: 3\r\n\r\na") }.isFailure)
        assertTrue(runCatching { parse("GET /" + "x".repeat(1100) + " HTTP/1.1\r\n\r\n") }.isFailure)
    }
    @Test fun `encrypted messages bind sender recipient operation and boot and refuse tampering`() {
        val key = LanCrypto.key(); val a = LanCrypto.randomId(); val b = LanCrypto.randomId(); val boot = LanCrypto.randomId()
        val context = LanCrypto.context("state-request", a, b, boot)
        val packet = LanCrypto.seal(key, context, state().json().toString())
        assertFalse(packet.contains("private title")); assertFalse(packet.contains("BV1xx411c7mD"))
        assertEquals(state(), ContinuityState.read(JSONObject(LanCrypto.open(key, context, packet))))
        listOf(LanCrypto.context("state-request", b, a, boot), LanCrypto.context("state-response", a, b, boot),
            LanCrypto.context("state-request", a, b, LanCrypto.randomId())).forEach {
            assertTrue(runCatching { LanCrypto.open(key, it, packet) }.isFailure)
        }
        val bytes = LanCrypto.decode(packet); bytes[20] = (bytes[20].toInt() xor 1).toByte()
        assertTrue(runCatching { LanCrypto.open(key, context, LanCrypto.encode(bytes)) }.isFailure)
        assertTrue(runCatching { LanCrypto.open(LanCrypto.key(), context, packet) }.isFailure)
    }
    @Test fun `replay window does not evict live entries to admit replays`() {
        val replay = LanReplayWindow(2)
        fun message(at: Long = now) = JSONObject().put("at", at).put("request", LanCrypto.randomId())
        val first = message(); assertTrue(replay.accept(first, now)); assertFalse(replay.accept(first, now))
        assertTrue(replay.accept(message(), now)); assertFalse(replay.accept(message(), now))
        assertFalse(replay.accept(message(now - 120001), now)); assertFalse(replay.accept(message(now + 30001), now))
        assertTrue(replay.accept(message(now + 150001), now + 150001))
    }
    @Test fun `clock rollback cannot resurrect a recently evicted authenticated request`() {
        val replay = LanReplayWindow(8)
        fun message(at: Long) = JSONObject().put("at", at).put("request", LanCrypto.randomId())
        val first = message(now)
        assertTrue(replay.accept(first, now))
        assertTrue(replay.accept(message(now + 121_000), now + 121_000))
        assertFalse(replay.accept(first, now + 119_000))
        assertTrue(replay.accept(message(now + 151_000), now + 151_000))
        assertFalse(replay.accept(first, now + 119_000))
        assertFalse(replay.accept(message(now + 119_000), now + 119_000))
        assertTrue(replay.accept(message(now + 140_000), now + 140_000))
    }
    @Test fun `public hello omits watch account and human device data`() {
        val protocol = LanPeerProtocol(LanCrypto.randomId(), { null }, {}, { state() }, { _, _ -> })
        val result = protocol.handle(LanRequest("GET", "/v1/hello", emptyMap(), "", InetAddress.getLoopbackAddress()))
        val hello = JSONObject(String(result.body))
        assertEquals(setOf("v", "id", "boot"), hello.keys().asSequence().toSet())
    }
    @Test fun `pairing is authenticated single use expiring and never trusts a UID assertion`() {
        val a = LanCrypto.randomId(); val b = LanCrypto.randomId()
        val peers = hashMapOf<String, TrustedLanDevice>(); var elapsed = 0L
        val protocol = LanPeerProtocol(a, peers::get, { peers[it.id] = it }, { state() }, { _, _ -> }, { now }, { elapsed })
        val code = protocol.invite(); val key = LanCrypto.invitationKey(code)
        val message = JSONObject().put("request", LanCrypto.randomId()).put("at", now).put("name", "Phone").put("uid", "12345")
        fun request(secret: ByteArray) = LanRequest("POST", "/v1/pair", mapOf("x-bil-device" to b),
            LanCrypto.seal(secret, LanCrypto.context("pair-request", b, a, protocol.boot), message.toString()), InetAddress.getLoopbackAddress())
        assertEquals(403, protocol.handle(request(LanCrypto.key())).status); assertTrue(peers.isEmpty())
        val valid = request(key)
        val result = protocol.handle(valid); assertEquals(200, result.status); assertEquals("Phone", peers.getValue(b).name)
        assertEquals(403, protocol.handle(valid).status)
        assertEquals(32, peers.getValue(b).key.size)
        protocol.invite(); elapsed = 120001
        assertEquals(403, protocol.handle(valid).status)
    }
    @Test fun `continuity advances playing position but keeps pauses fixed and expires stale offers`() {
        assertEquals(53_000, state().positionAt(now + 2000))
        assertEquals(50_000, state(false).positionAt(now + 2000))
        assertFalse(state().offerAt(now + 20_001)); assertFalse(state(false).offerAt(now + 120_001)); assertFalse(state().offerAt(now - 1))
        val offers = ContinuityOffers(); offers.receive("peer", state(), now, 100)
        assertNotNull(offers.latest(now, 100, null)); assertNull(offers.latest(now, 100, "session"))
        val offer = offers.latest(now, 100, null)!!; offers.dismiss(offer); assertNull(offers.latest(now, 100, null))
        offers.clear(); offers.receive("peer", state(), now, 100); offers.revoke("peer"); assertNull(offers.latest(now, 100, null))
        offers.receive("peer", state(), now, 100); assertNull(offers.latest(now, 20_101, null))
    }
    @Test fun `continuity rejects nonfinite speeds unknown identifiers and malformed media`() {
        assertTrue(runCatching { state().copy(speed = Float.NaN) }.isFailure)
        assertEquals(8f, state().copy(speed = 8f).speed)
        assertEquals(.1f, state().copy(speed = .1f).speed)
        assertTrue(runCatching { state().copy(speed = 8.01f) }.isFailure)
        assertTrue(runCatching { state().copy(cid = 0) }.isFailure)
        assertTrue(runCatching { state().copy(positionMs = 600_001) }.isFailure)
        assertTrue(runCatching { validateMediaUrl("https://bilivideo.com.evil.test/a.mp4") }.isFailure)
        assertTrue(runCatching { validateMediaUrl("http://a.bilivideo.com/a.mp4") }.isFailure)
        validateMediaUrl("https://a.bilivideo.com/a.m4s?token=private")
    }
    @Test fun `danmaku parser preserves timing color and supported modes without executing text`() {
        val rows = CastDanmakuSource.parse("""<i><d p="1.5,1,25,16711680,0">hello &amp; world</d><d p="2,7,25,1">advanced</d><d p="NaN,1,25,1">bad</d></i>""")
        assertEquals(listOf(CastDanmaku(1500, 1, 0xFFFF0000.toInt(), "hello & world")), rows)
    }
    @Test fun `paired paused offers stay alive while authenticated peers keep exchanging`() {
        val offers = ContinuityOffers()
        offers.receive("peer", state(false), now, 100)
        offers.receive("peer", state(false), now + 19_000, 19_100)
        assertNotNull(offers.latest(now + 35_000, 35_100, null))
        offers.receive("peer", state(false), now + 121_000, 121_100)
        assertNull(offers.latest(now + 121_000, 121_100, null))
    }
    @Test fun `authenticated source age tolerates clock offset without resurrecting expired pauses`() {
        val shifted = state(false, now - 60_000).receivedAt(now, now + 30_000)!!
        assertTrue(shifted.offerAt(now + 30_000))
        assertEquals(60_000, now + 30_000 - shifted.capturedAt)
        assertNull(state(false, now - 120_001).receivedAt(now, now + 30_000))
        assertNull(state(at = now + 1).receivedAt(now, now + 30_000))
        assertEquals(53_000, state().receivedAt(now, now - 30_000)!!.positionAt(now - 28_000))
    }
    @Test fun `part metadata keeps authoritative CID and rejects unrelated duplicates and failure responses`() {
        val valid = """{"code":0,"data":{"bvid":"BV1xx411c7mD","pages":[{"cid":43,"page":2,"part":"two"},{"cid":42,"page":1,"part":"one"}]}}"""
        assertEquals(listOf(LanVideoPart("BV1xx411c7mD", 42, 1, "one"), LanVideoPart("BV1xx411c7mD", 43, 2, "two")),
            LanVideoParts.parse("BV1xx411c7mD", valid))
        listOf(valid.replace("\"code\":0", "\"code\":-412"), valid.replace("BV1xx411c7mD", "BV1GJ411x7h7"),
            valid.replace("\"cid\":43", "\"cid\":42"), valid.replace("\"page\":2", "\"page\":1"),
            valid.replace("\"cid\":43", "\"cid\":0"), """{"code":0,"data":{"bvid":"BV1xx411c7mD","pages":[]}}""").forEach {
            assertTrue(it, runCatching { LanVideoParts.parse("BV1xx411c7mD", it) }.isFailure)
        }
    }
}
