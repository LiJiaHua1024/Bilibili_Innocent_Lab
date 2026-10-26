package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Simulated DLNA receiver: real loopback sockets against the production HTTP server and stream session, fed by
 * a synthetic AVC/AAC producer. No phone, codec, TV or external network is involved.
 */
class CastStreamTest {
    @get:Rule val folder = TemporaryFolder()
    private val fast = CastStreamConfig(startupUs = 2_000_000)

    private fun eventually(timeoutMs: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) { assertTrue("timed out waiting for $message", System.nanoTime() < end); Thread.sleep(20) }
    }

    private fun playlist(port: Int, token: String): List<String> {
        val reply = RawHttp.get(port, "/media/$token/index.m3u8")
        assertEquals(200, reply.status); assertEquals("application/vnd.apple.mpegurl", reply.headers["content-type"])
        return String(reply.body).lines().filter(String::isNotEmpty)
    }

    private fun segments(lines: List<String>) = lines.filter { it.endsWith(".ts") }

    @Test fun `HLS playlist lists completed key-frame segments that decode as one continuous TS`() {
        val producer = FakeProducer(20_000_000)
        CastStreamSession(folder.root, "127.0.0.1", producer, fast).use { session ->
            LanHttpServer(RawHttp.loopback, { true }) { session.response(it) }.use { server ->
                session.start(0); session.awaitReady()
                eventually { session.state == "complete" }
                val lines = playlist(server.port, session.token)
                assertEquals(listOf("#EXTM3U", "#EXT-X-VERSION:3", "#EXT-X-TARGETDURATION:4", "#EXT-X-MEDIA-SEQUENCE:0",
                    "#EXT-X-PLAYLIST-TYPE:EVENT", "#EXT-X-START:TIME-OFFSET=0"), lines.take(6))
                assertEquals("#EXT-X-ENDLIST", lines.last())
                val names = segments(lines)
                assertEquals(names.indices.map { "$it.ts" }, names)
                val durations = lines.filter { it.startsWith("#EXTINF:") }.map { it.removePrefix("#EXTINF:").removeSuffix(",").toDouble() }
                assertTrue(durations.all { Math.round(it) <= 4 })
                assertEquals(19.999, durations.sum(), 0.05)
                val counters = HashMap<Int, Int>()
                val all = ByteArrayOutputStream()
                val parsedSegments = names.map { name ->
                    val reply = RawHttp.get(server.port, "/media/${session.token}/$name")
                    assertEquals(200, reply.status); assertEquals("video/mp2t", reply.headers["content-type"])
                    assertEquals(reply.body.size.toString(), reply.headers["content-length"])
                    all.write(reply.body)
                    TsProbe.parse(reply.body, counters)
                }
                var previousDts = Long.MIN_VALUE
                var videoCount = 0; var audioCount = 0
                parsedSegments.forEachIndexed { index, parsed ->
                    assertEquals("segment starts with PAT, PMT", listOf(0 to 0, CastTsWriter.PMT_PID to 1), parsed.tables.take(2))
                    val first = parsed.pes.first()
                    assertEquals(CastTsWriter.VIDEO_PID, first.pid); assertTrue(first.randomAccess)
                    assertEquals(listOf(9, 7, 8, 5), TsProbe.nalTypes(first.data).take(4))
                    val start = first.pts
                    val end = parsedSegments.getOrNull(index + 1)?.pes?.first()?.pts ?: Long.MAX_VALUE
                    parsed.pes.forEach { pes ->
                        if (pes.pid == CastTsWriter.VIDEO_PID) {
                            videoCount++
                            assertTrue(pes.dts >= previousDts); previousDts = pes.dts
                        } else {
                            audioCount++
                            // Each segment carries its own audio: no sample belongs to a neighbour's time span.
                            assertTrue("audio ${pes.pts} in [$start, $end)", pes.pts >= start && pes.pts < end)
                            assertEquals(0xFFF1, ((pes.data[0].toInt() and 0xFF) shl 8) or (pes.data[1].toInt() and 0xFF))
                        }
                    }
                    assertTrue(parsed.pcrs.isNotEmpty())
                    assertTrue("PCR leads DTS", parsed.pcrs.first() <= first.dts * 300)
                }
                assertEquals(producer.videoFrames.size, videoCount)
                assertEquals(producer.audioFrames.size, audioCount)
                val stream = RawHttp.get(server.port, "/media/${session.token}/stream.ts")
                assertEquals(200, stream.status)
                assertNull(stream.headers["content-length"]); assertNull(stream.headers["transfer-encoding"])
                assertEquals("none", stream.headers["accept-ranges"])
                assertArrayEquals("continuous TS is the exact segment concatenation", all.toByteArray(), stream.body)
                val partial = RawHttp.get(server.port, "/media/${session.token}/1.ts", mapOf("Range" to "bytes=188-375"))
                assertEquals(206, partial.status); assertEquals(188, partial.body.size)
                val second = RawHttp.get(server.port, "/media/${session.token}/1.ts").body
                assertArrayEquals(second.copyOfRange(188, 376), partial.body)
                assertTrue(partial.headers["content-range"]!!.startsWith("bytes 188-375/"))
                assertEquals(416, RawHttp.get(server.port, "/media/${session.token}/stream.ts", mapOf("Range" to "bytes=100-")).status)
                assertEquals(404, RawHttp.get(server.port, "/media/${session.token}/${names.size}.ts").status)
                val head = RawHttp.get(server.port, "/media/${session.token}/stream.ts", method = "HEAD")
                assertEquals(200, head.status); assertEquals(0, head.body.size)
            }
        }
    }

    @Test fun `B-frame decode order gets a monotonic DTS that never exceeds PTS`() {
        CastStreamSession(folder.root, "127.0.0.1", FakeProducer(6_000_000, bFrames = true), fast).use { session ->
            LanHttpServer(RawHttp.loopback, { true }) { session.response(it) }.use { server ->
                session.start(0); eventually { session.state == "complete" }
                val body = RawHttp.get(server.port, "/media/${session.token}/stream.ts").body
                val video = TsProbe.parse(body).pes.filter { it.pid == CastTsWriter.VIDEO_PID }
                assertEquals(180, video.size)
                assertTrue(video.zipWithNext().all { (a, b) -> b.dts > a.dts })
                assertTrue(video.all { it.dts <= it.pts })
                assertTrue(video.zipWithNext().any { (a, b) -> b.pts < a.pts })
            }
        }
    }

    @Test fun `production waits for the receiver, suspends after a long pause and resumes with continuous timestamps`() {
        val producer = FakeProducer(30_000_000)
        val config = CastStreamConfig(startupUs = 2_000_000, maxAheadUs = 4_000_000, pauseBudgetNs = 300_000_000)
        CastStreamSession(folder.root, "127.0.0.1", producer, config).use { session ->
            LanHttpServer(RawHttp.loopback, { true }) { session.response(it) }.use { server ->
                session.start(0); session.awaitReady()
                eventually(message = "suspension") { session.state == "suspended" }
                // Nothing was requested yet: production stopped at maxAhead plus the segment it had open.
                assertTrue("published ${session.publishedUs}", session.publishedUs in 5_900_000..6_100_000)
                assertEquals(listOf(0L), producer.passes.toList())
                val fetched = ArrayList<ByteArray>()
                var lines = playlist(server.port, session.token)
                while (true) {
                    segments(lines).drop(fetched.size).forEach { fetched += RawHttp.get(server.port, "/media/${session.token}/$it").body }
                    if (lines.last() == "#EXT-X-ENDLIST") break
                    Thread.sleep(30)
                    lines = playlist(server.port, session.token)
                }
                assertTrue("resumed passes ${producer.passes}", producer.passes.size >= 2)
                assertEquals(180 * FakeProducer.FRAME_US, producer.passes[1])
                assertTrue(producer.passes.drop(1).all { it % (30 * FakeProducer.FRAME_US) == 0L })
                val counters = HashMap<Int, Int>()
                val pes = fetched.flatMap { TsProbe.parse(it, counters).pes }
                val video = pes.filter { it.pid == CastTsWriter.VIDEO_PID }.map { it.pts }
                assertEquals((0 until 900).map { CastTsSegmenter.ticks(it * FakeProducer.FRAME_US) }, video)
                val audio = pes.filter { it.pid == CastTsWriter.AUDIO_PID }.map { it.pts }
                val expected = generateSequence(0L) { it + FakeProducer.AUDIO_US }.takeWhile { it < 900 * FakeProducer.FRAME_US }
                    .map { CastTsSegmenter.ticks(it) }.toList()
                assertEquals("each audio sample exactly once across passes", expected, audio)
            }
        }
    }

    @Test fun `receiver address, token and replaced sessions are enforced`() {
        val first = CastStreamSession(folder.root, "127.0.0.1", FakeProducer(3_000_000), fast)
        val second = CastStreamSession(folder.root, "127.0.0.1", FakeProducer(3_000_000), fast, styleVersion = 1)
        val foreign = CastStreamSession(folder.root, "192.168.77.9", FakeProducer(3_000_000), fast)
        try {
            assertNotEquals(first.token, second.token)
            val current = java.util.concurrent.atomic.AtomicReference<CastPublication>(first)
            LanHttpServer(RawHttp.loopback, { true }) { current.get().response(it) }.use { server ->
                first.start(0); first.awaitReady()
                assertEquals(200, RawHttp.get(server.port, "/media/${first.token}/index.m3u8").status)
                second.start(0); second.awaitReady()
                current.set(second); first.close()
                assertEquals("old style session URL", 404, RawHttp.get(server.port, "/media/${first.token}/0.ts").status)
                assertEquals(200, RawHttp.get(server.port, "/media/${second.token}/0.ts").status)
                assertEquals(404, RawHttp.get(server.port, "/media/${second.token}/../index.m3u8").status)
                assertEquals(404, RawHttp.get(server.port, "/media/${second.token}/0.mp4").status)
                assertEquals(403, RawHttp.get(server.port, "/media/${second.token}/index.m3u8", method = "POST").status)
            }
            val request = LanRequest("GET", "/media/${foreign.token}/index.m3u8", emptyMap(), "", RawHttp.loopback)
            assertEquals("other address", 403, foreign.response(request).status)
            assertFalse(first.playlist().contains("bilivideo"))
            assertFalse(folder.root.listFiles()!!.any { it.name == "stream-${first.token}" })
        } finally { first.close(); second.close(); foreign.close() }
    }

    @Test fun `long media connections cannot exhaust control workers and overflow gets 503`() {
        val gate = CountDownLatch(1)
        val producer = FakeProducer(30_000_000) { time -> if (time >= 5_000_000) gate.await(20, TimeUnit.SECONDS) }
        CastStreamSession(folder.root, "127.0.0.1", producer, fast).use { session ->
            LanHttpServer(RawHttp.loopback, { true }) { request ->
                if (request.path == "/control") LanResponse(body = "ok".toByteArray()) else session.response(request)
            }.use { server ->
                try {
                    session.start(0); session.awaitReady()
                    val streams = (0 until LanHttpServer.MEDIA_CONNECTIONS).map { RawHttp.open(server.port, "/media/${session.token}/stream.ts") }
                    try {
                        streams.forEach { assertEquals(200, RawHttp.head(it.getInputStream()).first) }
                        eventually(message = "media workers") { server.activeMedia == LanHttpServer.MEDIA_CONNECTIONS }
                        assertEquals(503, RawHttp.get(server.port, "/media/${session.token}/0.ts").status)
                        repeat(5) {
                            val begin = System.nanoTime()
                            val reply = RawHttp.get(server.port, "/control")
                            assertEquals("ok", String(reply.body))
                            assertTrue((System.nanoTime() - begin) / 1_000_000 < 3000)
                        }
                    } finally { streams.forEach { it.close() } }
                    gate.countDown()
                    eventually(message = "released media workers") { server.activeMedia == 0 }
                } finally { gate.countDown() }
            }
        }
    }

    @Test fun `stalled receiver is disconnected and frees its media worker`() {
        val config = CastStreamConfig(startupUs = 2_000_000, maxAheadUs = 200_000_000)
        CastStreamSession(folder.root, "127.0.0.1", FakeProducer(60_000_000, frameBytes = 20_000), config).use { session ->
            LanHttpServer(RawHttp.loopback, { true }) { session.response(it) }.use { server ->
                server.mediaStallNs = 300_000_000
                session.start(0); session.awaitReady()
                RawHttp.open(server.port, "/media/${session.token}/stream.ts", receiveBuffer = 4096).use {
                    eventually(message = "worker started") { server.activeMedia == 1 }
                    // The receiver never reads: the blocked write is cut off by the stall watchdog.
                    eventually(20_000, "stall disconnect") { server.activeMedia == 0 }
                }
            }
        }
    }

    @Test fun `continuous TS uses chunked framing for HTTP 1_1 and close framing for HTTP 1_0`() {
        val config = CastStreamConfig(startupUs = 2_000_000, chunked = true)
        CastStreamSession(folder.root, "127.0.0.1", FakeProducer(4_000_000), config).use { session ->
            LanHttpServer(RawHttp.loopback, { true }) { session.response(it) }.use { server ->
                session.start(0); eventually { session.state == "complete" }
                val chunked = RawHttp.get(server.port, "/media/${session.token}/stream.ts")
                assertEquals("chunked", chunked.headers["transfer-encoding"]); assertNull(chunked.headers["content-length"])
                val legacy = RawHttp.get(server.port, "/media/${session.token}/stream.ts", version = "HTTP/1.0")
                assertNull(legacy.headers["transfer-encoding"]); assertNull(legacy.headers["content-length"])
                assertArrayEquals(legacy.body, chunked.body)
                TsProbe.parse(chunked.body)
            }
        }
    }

    @Test fun `unsupported codec and cancellation fail startup without publishing`() {
        val bad = CastStreamProducer { run ->
            run.sink.addTrack(CastTrackFormat("video/av01", listOf(byteArrayOf(0, 0, 0, 1, 0x67))))
        }
        CastStreamSession(folder.root, "127.0.0.1", bad, fast).use { session ->
            session.start(0)
            assertEquals("ts-video-unsupported", runCatching { session.awaitReady() }.exceptionOrNull()?.message)
            assertEquals("failed", session.state)
        }
        val gate = CountDownLatch(1)
        val session = CastStreamSession(folder.root, "127.0.0.1", FakeProducer(30_000_000) { gate.await(5, TimeUnit.SECONDS) }, fast)
        session.start(0)
        val failure = runCatching { session.awaitReady { false } }.exceptionOrNull()
        assertEquals("media-preparation-cancelled", failure?.message)
        gate.countDown(); session.close()
        assertFalse(folder.root.listFiles()!!.any { it.name == "stream-${session.token}" })
    }
}
