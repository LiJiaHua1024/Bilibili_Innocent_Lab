package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Byte-level MPEG-TS boundaries checked against an independent reader and published reference values. */
class CastTsTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    @Test fun `CRC32 MPEG-2 matches the standard check value and the common muxer PAT`() {
        assertEquals(0x0376E6E7, CastTsWriter.crc32("123456789".toByteArray(), 0, 9))
        val out = ByteArrayOutputStream()
        CastTsWriter("video/avc").tables(out)
        val bytes = out.toByteArray()
        assertEquals(2 * 188, bytes.size)
        // PAT emitted by common muxers for program 1 / PMT PID 0x1000, including its CRC.
        assertEquals("47 40 00 10 00 00 B0 0D 00 01 C1 00 00 00 01 F0 00 2A B1 04 B2", hex(bytes.copyOfRange(0, 21)))
        assertTrue(bytes.copyOfRange(21, 188).all { it == 0xFF.toByte() })
        val result = TsProbe.parse(bytes)
        assertEquals(listOf(0, CastTsWriter.PMT_PID), result.tables.map { it.first })
        // PMT: PCR on video PID 0x100, AVC 0x1B on 0x100 and ADTS AAC 0x0F on 0x101.
        assertEquals("02 B0 17 00 01 C1 00 00 E1 00 F0 00 1B E1 00 F0 00 0F E1 01 F0 00", hex(bytes.copyOfRange(188 + 5, 188 + 5 + 22)))
        val hevc = ByteArrayOutputStream().also { CastTsWriter("video/hevc").tables(it) }.toByteArray()
        assertEquals(0x24, hevc[188 + 5 + 12].toInt())
        assertEquals("ts-video-unsupported", runCatching { CastTsWriter("video/av01") }.exceptionOrNull()?.message)
    }

    @Test fun `33-bit PTS DTS and PCR fields keep marker bits and wrap`() {
        val field = ByteArray(5)
        CastTsWriter.timestamp(field, 0, 0x2, (1L shl 33) + 0x1_2345_6789L)
        val value = ((field[0].toLong() shr 1) and 7 shl 30) or ((field[1].toLong() and 0xFF) shl 22) or
            (((field[2].toLong() and 0xFF) shr 1) shl 15) or ((field[3].toLong() and 0xFF) shl 7) or ((field[4].toLong() and 0xFF) shr 1)
        assertEquals(0x1_2345_6789L, value)
        assertEquals(0x20, field[0].toInt() and 0xF0); assertEquals(1, field[0].toInt() and 1)
        assertEquals(1, field[2].toInt() and 1); assertEquals(1, field[4].toInt() and 1)
        val pcr = ByteArray(6)
        CastTsWriter.writePcr(pcr, 0, 27_000_000L * 3 + 299)
        val base = ((pcr[0].toLong() and 0xFF) shl 25) or ((pcr[1].toLong() and 0xFF) shl 17) or ((pcr[2].toLong() and 0xFF) shl 9) or
            ((pcr[3].toLong() and 0xFF) shl 1) or ((pcr[4].toLong() and 0xFF) shr 7)
        assertEquals(270_000L, base)
        assertEquals(299, ((pcr[4].toInt() and 1) shl 8) or (pcr[5].toInt() and 0xFF))
        assertEquals(0x7E, pcr[4].toInt() and 0x7E)
    }

    @Test fun `PES split covers one-byte adaptation, stuffing and continuity wrap`() {
        val writer = CastTsWriter("video/avc")
        val out = ByteArrayOutputStream()
        writer.tables(out)
        // Audio PES header is 14 bytes: 170 payload bytes fill one packet exactly; 169 needs a 1-byte adaptation field.
        for (size in listOf(169, 168, 167, 170, 400, 1)) writer.audio(out, ByteArray(size) { 7 }, size, 90_000L + size)
        repeat(20) { writer.video(out, ByteArray(3000) { 9 }.also { b -> b[0] = 0; b[1] = 0; b[2] = 1; b[3] = 0x65 }, 3000, 1000L * it + 9000, 1000L * it, it == 0, 27_000L * it) }
        val parsed = TsProbe.parse(out.toByteArray())
        val audio = parsed.pes.filter { it.pid == CastTsWriter.AUDIO_PID }
        assertEquals(listOf(169, 168, 167, 170, 400, 1), audio.map { it.data.size })
        assertTrue(audio.all { it.data.all { b -> b.toInt() == 7 } && it.dts == it.pts })
        val video = parsed.pes.filter { it.pid == CastTsWriter.VIDEO_PID }
        assertEquals(20, video.size)
        assertTrue(video.first().randomAccess); assertFalse(video[1].randomAccess)
        assertEquals((0 until 20).map { 1000L * it + 9000 }, video.map { it.pts })
        assertEquals((0 until 20).map { 1000L * it }, video.map { it.dts })
        assertEquals(20, parsed.pcrs.size)
        assertTrue(parsed.packets > 16 * 2)
        assertEquals("ts-pes-too-large", runCatching { writer.audio(out, ByteArray(70_000), 70_000, 0) }.exceptionOrNull()?.message)
    }

    @Test fun `ADTS header follows AudioSpecificConfig including explicit HE-AAC`() {
        val lc = CastAdts(byteArrayOf(0x11, 0x90.toByte()))
        assertEquals(1, lc.profile); assertEquals(3, lc.frequencyIndex); assertEquals(2, lc.channels)
        assertEquals("FF F1 4C 80 2E 5F FC", hex(lc.header(363)))
        // AOT 5 (SBR), 24 kHz core, stereo, extension 48 kHz, core AOT 2.
        val he = CastAdts(byteArrayOf(0x2B, 0x11, 0x88.toByte(), 0x00))
        assertEquals(1, he.profile); assertEquals(6, he.frequencyIndex); assertEquals(2, he.channels)
        assertEquals("ts-audio-config-unsupported", runCatching { CastAdts(byteArrayOf(0x11, 0x80.toByte())) }.exceptionOrNull()?.message)
        assertEquals("ts-audio-config-unsupported", runCatching { CastAdts(byteArrayOf(0xF8.toByte(), 0x00)) }.exceptionOrNull()?.message)
        assertEquals("ts-audio-frame-too-large", runCatching { lc.header(8190) }.exceptionOrNull()?.message)
    }

    @Test fun `access units gain a delimiter and parameter sets before key frames only`() {
        val nal = CastNal("video/avc", listOf(FakeProducer.SPS, FakeProducer.PPS))
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
        assertEquals(listOf(9, 7, 8, 5), TsProbe.nalTypes(nal.accessUnit(idr, idr.size, true)))
        val p = byteArrayOf(0, 0, 1, 0x41, 1, 2)
        assertEquals(listOf(9, 1), TsProbe.nalTypes(nal.accessUnit(p, p.size, false)))
        val inBand = FakeProducer.SPS + FakeProducer.PPS + idr
        assertEquals(listOf(9, 7, 8, 5), TsProbe.nalTypes(nal.accessUnit(inBand, inBand.size, true)))
        val delimited = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte()) + p
        assertEquals(listOf(9, 1), TsProbe.nalTypes(nal.accessUnit(delimited, delimited.size, false)))
        assertEquals("ts-video-not-annexb", runCatching { nal.accessUnit(byteArrayOf(0, 0, 0, 4, 0x65, 1, 2, 3), 8, true) }.exceptionOrNull()?.message)
        assertEquals("ts-video-config-unsupported", runCatching { CastNal("video/avc", listOf(byteArrayOf(1, 0x42, 0, 0x1E))) }.exceptionOrNull()?.message)
        val hevc = CastNal("video/hevc", listOf(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0, 0, 0, 1, 0x42, 1, 0, 0, 0, 1, 0x44, 1)))
        val unit = hevc.accessUnit(byteArrayOf(0, 0, 0, 1, 0x26, 1, 9), 7, true)
        assertEquals("00 00 00 01 46 01 50", hex(unit.copyOfRange(0, 7)))
    }

    @Test fun `decode clock is monotonic and below PTS for B-frames and rejects deeper reordering`() {
        val reorder = CastDtsReorder<Int>(window = 4, delayUs = 100)
        val out = ArrayList<Triple<Int, Long, Long>>()
        listOf(0L, 300, 100, 200, 600, 400, 500, 700).forEachIndexed { i, pts -> reorder.push(pts, i) { item, p, d -> out += Triple(item, p, d) } }
        reorder.flush { item, p, d -> out += Triple(item, p, d) }
        assertEquals((0..7).toList(), out.map { it.first })
        assertTrue(out.zipWithNext().all { (a, b) -> b.third >= a.third })
        assertTrue(out.all { it.third <= it.second })
        assertEquals(listOf(-100L, 0, 100, 200, 300, 400, 500, 600), out.map { it.third })
        val shallow = CastDtsReorder<Int>(window = 1, delayUs = 0)
        assertEquals("ts-reorder-too-deep", runCatching {
            listOf(0L, 300, 100, 200).forEachIndexed { i, pts -> shallow.push(pts, i) { _, _, _ -> } }
            shallow.flush { _, _, _ -> }
        }.exceptionOrNull()?.message)
    }

    @Test fun `interleave writes in time order and shifted readers drop samples before a later start`() {
        class Reader(val times: List<Long>, val keys: Set<Long> = emptySet()) : CastSampleReader {
            var index = 0
            override fun peekTimeUs() = times.getOrNull(index)
            override val key get() = times[index] in keys
            override fun read(buffer: java.nio.ByteBuffer): Int { buffer.put(byteArrayOf(1, 2)); return 2 }
            override fun advance() { index++ }
        }
        val written = ArrayList<Triple<Int, Long, Boolean>>()
        val sink = object : CastSampleSink {
            override fun addTrack(format: CastTrackFormat) = 0
            override fun start() = Unit
            override fun write(track: Int, data: java.nio.ByteBuffer, timeUs: Long, key: Boolean) { assertEquals(2, data.remaining()); written += Triple(track, timeUs, key) }
            override fun finish() = Unit
        }
        val video = CastShiftedReader(Reader(listOf(1000L, 2000, 3000, 4000), setOf(2000L)), 2000, 2000)
        val audio = CastShiftedReader(Reader(listOf(-20L, 1500, 2000, 2500, 3500, 4500)), 2000, 2000)
        CastInterleave.copy(video, 0, audio, 1, sink, java.util.concurrent.atomic.AtomicBoolean(false), capacity = 16)
        assertEquals(listOf(0 to 0L, 1 to 0L, 1 to 500L, 0 to 1000L, 1 to 1500L, 0 to 2000L, 1 to 2500L), written.map { it.first to it.second })
        assertTrue(written.first().third)
        // Zero start keeps negative AAC priming, as the complete-MP4 path does.
        assertEquals(-20L, CastShiftedReader(Reader(listOf(-20L, 0)), 0, 0).peekTimeUs())
    }
}
