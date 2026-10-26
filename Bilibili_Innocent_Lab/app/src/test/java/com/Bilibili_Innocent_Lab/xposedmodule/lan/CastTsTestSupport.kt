package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Independent MPEG-TS reader for tests; it shares no code with the writer under test. */
internal object TsProbe {
    class Pes(val pid: Int, val pts: Long, val dts: Long, val randomAccess: Boolean, val data: ByteArray, val packet: Int)
    class Result(val pes: List<Pes>, val tables: List<Pair<Int, Int>>, val packets: Int, val pcrs: List<Long>)

    fun crc(data: ByteArray, from: Int, to: Int): Int {
        // Table-free reference with the polynomial written out bit by bit.
        var crc = 0xFFFFFFFFL
        for (i in from until to) for (bit in 7 downTo 0) {
            val inBit = (data[i].toInt() shr bit) and 1
            val top = ((crc shr 31) and 1).toInt()
            crc = (crc shl 1) and 0xFFFFFFFFL
            if (top xor inBit == 1) crc = crc xor 0x04C11DB7L
        }
        return crc.toInt()
    }

    private fun ts(b: ByteArray, o: Int): Long {
        require(b[o].toInt() and 1 == 1 && b[o + 2].toInt() and 1 == 1 && b[o + 4].toInt() and 1 == 1) { "marker bits" }
        return ((b[o].toLong() shr 1) and 7 shl 30) or ((b[o + 1].toLong() and 0xFF) shl 22) or
            (((b[o + 2].toLong() and 0xFF) shr 1) shl 15) or ((b[o + 3].toLong() and 0xFF) shl 7) or ((b[o + 4].toLong() and 0xFF) shr 1)
    }

    /** Parses packets, checks sync bytes, PSI CRC and per-PID continuity across the whole input. */
    fun parse(bytes: ByteArray, counters: MutableMap<Int, Int> = HashMap()): Result {
        assertEquals("whole packets", 0, bytes.size % 188)
        val pes = ArrayList<Pes>(); val tables = ArrayList<Pair<Int, Int>>(); val pcrs = ArrayList<Long>()
        val open = HashMap<Int, Pair<ByteArrayOutputStream, Pair<Boolean, Int>>>()
        fun finish(pid: Int) {
            val (buffer, meta) = open.remove(pid) ?: return
            val d = buffer.toByteArray()
            assertTrue(d[0].toInt() == 0 && d[1].toInt() == 0 && d[2].toInt() == 1)
            val declared = (d[4].toInt() and 0xFF shl 8) or (d[5].toInt() and 0xFF)
            if (declared != 0) assertEquals("PES length", declared, d.size - 6)
            val flags = d[7].toInt() and 0xC0
            val headerLength = d[8].toInt() and 0xFF
            val pts = ts(d, 9)
            assertEquals(if (flags == 0xC0) 3 else 2, (d[9].toInt() and 0xF0) shr 4)
            val dts = if (flags == 0xC0) { assertEquals(1, (d[14].toInt() and 0xF0) shr 4); ts(d, 14) } else pts
            pes += Pes(pid, pts, dts, meta.first, d.copyOfRange(9 + headerLength, d.size), meta.second)
        }
        for (index in 0 until bytes.size / 188) {
            val o = index * 188
            assertEquals(0x47, bytes[o].toInt() and 0xFF)
            val start = bytes[o + 1].toInt() and 0x40 != 0
            val pid = (bytes[o + 1].toInt() and 0x1F shl 8) or (bytes[o + 2].toInt() and 0xFF)
            val control = (bytes[o + 3].toInt() shr 4) and 3
            val cc = bytes[o + 3].toInt() and 0x0F
            counters[pid]?.let { assertEquals("continuity pid=$pid packet=$index", (it + 1) and 15, cc) }
            counters[pid] = cc
            var p = o + 4
            var randomAccess = false
            if (control == 3) {
                val length = bytes[p].toInt() and 0xFF
                if (length > 0) {
                    val flags = bytes[p + 1].toInt() and 0xFF
                    randomAccess = flags and 0x40 != 0
                    if (flags and 0x10 != 0) {
                        val q = p + 2
                        val base = ((bytes[q].toLong() and 0xFF) shl 25) or ((bytes[q + 1].toLong() and 0xFF) shl 17) or
                            ((bytes[q + 2].toLong() and 0xFF) shl 9) or ((bytes[q + 3].toLong() and 0xFF) shl 1) or ((bytes[q + 4].toLong() and 0xFF) shr 7)
                        val ext = ((bytes[q + 4].toInt() and 1) shl 8) or (bytes[q + 5].toInt() and 0xFF)
                        pcrs += base * 300 + ext
                    }
                    for (s in p + 2 + (if (flags and 0x10 != 0) 6 else 0) until p + 1 + length) assertEquals(0xFF, bytes[s].toInt() and 0xFF)
                }
                p += 1 + length
            }
            assertTrue(control == 1 || control == 3)
            if (pid == 0 || pid == CastTsWriter.PMT_PID) {
                assertTrue(start)
                val s = p + 1 + (bytes[p].toInt() and 0xFF)
                val length = (bytes[s + 1].toInt() and 0x0F shl 8) or (bytes[s + 2].toInt() and 0xFF)
                assertEquals("PSI CRC", 0, crc(bytes, s, s + 3 + length))
                tables += pid to index
                continue
            }
            if (start) {
                finish(pid)
                open[pid] = ByteArrayOutputStream() to (randomAccess to index)
            }
            open[pid]?.first?.write(bytes, p, o + 188 - p)
        }
        open.keys.toList().forEach(::finish)
        return Result(pes, tables, bytes.size / 188, pcrs)
    }

    fun nalTypes(data: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) { types += data[i + 3].toInt() and 0x1F; i += 3 } else i++
        }
        return types
    }
}

/** Synthetic AVC + AAC source: honours the requested start, aligned to its one-second GOP. */
internal class FakeProducer(private val durationUs: Long, private val frameBytes: Int = 2000,
    private val bFrames: Boolean = false, private val beforeFrame: (Long) -> Unit = {}) : CastStreamProducer {
    val passes = CopyOnWriteArrayList<Long>()
    val videoFrames = CopyOnWriteArrayList<Long>()
    val audioFrames = CopyOnWriteArrayList<Long>()
    companion object {
        const val FRAME_US = 33_333L
        const val AUDIO_US = 21_333L
        val SPS = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1E, 0x5A, 0x5A)
        val PPS = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val ASC = byteArrayOf(0x11, 0x90.toByte())
    }
    override fun produce(run: CastProduction) {
        passes += run.startUs
        val offset = run.timeline.fix(run.startUs)
        val sink = run.sink
        val video = sink.addTrack(CastTrackFormat("video/avc", listOf(SPS, PPS), 640, 360))
        val audio = sink.addTrack(CastTrackFormat("audio/mp4a-latm", listOf(ASC)))
        sink.start()
        var frame = run.startUs / FRAME_US
        var sample = (run.startUs + AUDIO_US - 1) / AUDIO_US
        val last = durationUs / FRAME_US
        // Decode order with one B-frame per pair: I0 P2 B1 P4 B3 ... inside each GOP.
        fun order(index: Long): Long {
            if (!bFrames) return index
            val inGop = index % 30
            return if (inGop == 0L || inGop == 29L) index else if (inGop % 2 == 1L) index + 1 else index - 1
        }
        while (frame < last) {
            check(!run.cancelled.get()) { "media-preparation-cancelled" }
            val pts = order(frame) * FRAME_US
            while (sample * AUDIO_US < frame * FRAME_US) {
                val bytes = ByteArray(300) { 0x21 }
                sink.write(audio, ByteBuffer.wrap(bytes), sample * AUDIO_US - offset, false)
                audioFrames += sample * AUDIO_US
                sample++
            }
            beforeFrame(frame * FRAME_US)
            val key = frame % 30 == 0L
            val data = ByteArray(frameBytes) { 0x5A }
            data[0] = 0; data[1] = 0; data[2] = 0; data[3] = 1; data[4] = if (key) 0x65 else 0x41
            sink.write(video, ByteBuffer.wrap(data), pts - offset, key)
            videoFrames += pts
            frame++
        }
        while (sample * AUDIO_US < last * FRAME_US) {
            sink.write(audio, ByteBuffer.wrap(ByteArray(300) { 0x21 }), sample * AUDIO_US - offset, false)
            audioFrames += sample * AUDIO_US; sample++
        }
        sink.finish()
    }
}

/** Minimal raw-socket HTTP/1.x client: no redirects, keeps raw framing visible to the test. */
internal object RawHttp {
    class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)
    val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    fun open(port: Int, path: String, headers: Map<String, String> = emptyMap(), version: String = "HTTP/1.1",
        method: String = "GET", receiveBuffer: Int = 0): Socket {
        val socket = Socket()
        if (receiveBuffer > 0) socket.receiveBufferSize = receiveBuffer
        socket.connect(InetSocketAddress(loopback, port), 2000); socket.soTimeout = 10_000
        val head = StringBuilder("$method $path $version\r\nHost: test\r\n")
        headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        socket.getOutputStream().apply { write(head.toString().toByteArray()); flush() }
        return socket
    }
    fun head(input: InputStream): Pair<Int, Map<String, String>> {
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) { val b = input.read(); require(b >= 0); if (b == 10) break; out.write(b) }
            return out.toString("US-ASCII").removeSuffix("\r")
        }
        val status = line().split(' ')[1].toInt()
        val fields = linkedMapOf<String, String>()
        while (true) { val l = line(); if (l.isEmpty()) break; fields[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
        return status to fields
    }
    fun get(port: Int, path: String, headers: Map<String, String> = emptyMap(), version: String = "HTTP/1.1",
        method: String = "GET"): Reply = open(port, path, headers, version, method).use { socket ->
        val input = socket.getInputStream().buffered()
        val (status, fields) = head(input)
        val body = if (fields["transfer-encoding"] == "chunked") dechunk(input) else input.readBytes()
        Reply(status, fields, body)
    }
    fun dechunk(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        fun line(): String { val b = ByteArrayOutputStream(); while (true) { val c = input.read(); require(c >= 0) { "truncated chunk" }; if (c == 10) break; b.write(c) }; return b.toString("US-ASCII").removeSuffix("\r") }
        while (true) {
            val size = line().toInt(16)
            if (size == 0) { assertEquals("", line()); return out.toByteArray() }
            val chunk = ByteArray(size); var read = 0
            while (read < size) { val n = input.read(chunk, read, size - read); require(n > 0); read += n }
            out.write(chunk); assertEquals("", line())
        }
    }
}
