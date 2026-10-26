package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.io.OutputStream
import java.util.PriorityQueue

/**
 * ISO/IEC 13818-1 transport packets for one program: PAT, PMT, one AVC/HEVC video and one ADTS AAC stream.
 * Continuity counters belong to this instance and continue across segments, so concatenated HLS segments are
 * also a valid continuous stream. Timestamps are 90 kHz values; PCR is a 27 MHz value.
 */
internal class CastTsWriter(videoMime: String, private val audio: Boolean = true) {
    private val videoType = when (videoMime) {
        "video/avc" -> 0x1B
        "video/hevc" -> 0x24
        else -> throw IllegalArgumentException("ts-video-unsupported")
    }
    private val counters = IntArray(0x2000)
    private val packet = ByteArray(PACKET)

    fun tables(out: OutputStream) {
        section(out, 0, byteArrayOf(0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01, (0xE0 or (PMT_PID shr 8)).toByte(), (PMT_PID and 0xFF).toByte()))
        val streams = ArrayList<Byte>()
        fun stream(type: Int, pid: Int) {
            streams += type.toByte(); streams += (0xE0 or (pid shr 8)).toByte(); streams += (pid and 0xFF).toByte()
            streams += 0xF0.toByte(); streams += 0x00
        }
        stream(videoType, VIDEO_PID)
        if (audio) stream(0x0F, AUDIO_PID)
        val length = 9 + streams.size + 4
        section(out, PMT_PID, byteArrayOf(0x02, (0xB0 or (length shr 8)).toByte(), (length and 0xFF).toByte(),
            0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, (0xE0 or (VIDEO_PID shr 8)).toByte(), (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00) + streams.toByteArray())
    }

    fun video(out: OutputStream, data: ByteArray, size: Int, pts: Long, dts: Long, key: Boolean, pcr: Long) =
        pes(out, VIDEO_PID, 0xE0, data, size, pts, if (dts != pts) dts else null, pcr, key, unbounded = true)

    fun audio(out: OutputStream, data: ByteArray, size: Int, pts: Long) {
        check(audio) { "ts-audio-unconfigured" }
        pes(out, AUDIO_PID, 0xC0, data, size, pts, null, null, false, unbounded = false)
    }

    private fun section(out: OutputStream, pid: Int, body: ByteArray) {
        val crc = crc32(body, 0, body.size)
        val payload = ByteArray(1 + body.size + 4)
        System.arraycopy(body, 0, payload, 1, body.size)
        for (i in 0 until 4) payload[1 + body.size + i] = (crc ushr (24 - 8 * i)).toByte()
        packet.fill(0xFF.toByte())
        header(pid, start = true, adaptation = false)
        System.arraycopy(payload, 0, packet, 4, payload.size)
        out.write(packet)
    }

    private fun pes(out: OutputStream, pid: Int, streamId: Int, data: ByteArray, size: Int, pts: Long, dts: Long?,
        pcr: Long?, key: Boolean, unbounded: Boolean) {
        require(size in 1..data.size) { "empty-sample" }
        val headerData = if (dts == null) 5 else 10
        val pesLength = 3 + headerData + size
        require(unbounded || pesLength <= 0xFFFF) { "ts-pes-too-large" }
        val head = ByteArray(9 + headerData)
        head[2] = 1; head[3] = streamId.toByte()
        val declared = if (pesLength > 0xFFFF) 0 else pesLength
        head[4] = (declared shr 8).toByte(); head[5] = declared.toByte()
        head[6] = (if (streamId == 0xE0) 0x84 else 0x80).toByte()
        head[7] = (if (dts == null) 0x80 else 0xC0).toByte()
        head[8] = headerData.toByte()
        timestamp(head, 9, if (dts == null) 0x2 else 0x3, pts)
        if (dts != null) timestamp(head, 14, 0x1, dts)
        val total = head.size + size
        var written = 0
        var first = true
        while (written < total) {
            val withPcr = first && pcr != null
            val flags = first && (withPcr || key)
            var adaptation = if (flags) 2 + (if (withPcr) 6 else 0) else 0
            val remaining = total - written
            val stuffing = (PACKET - 4 - adaptation - remaining).coerceAtLeast(0)
            adaptation += stuffing
            packet.fill(0xFF.toByte())
            header(pid, first, adaptation > 0)
            var cursor = 4
            if (adaptation > 0) {
                packet[cursor++] = (adaptation - 1).toByte()
                if (adaptation > 1) {
                    packet[cursor++] = ((if (first && key) 0x40 else 0) or (if (withPcr) 0x10 else 0)).toByte()
                    if (withPcr) { writePcr(packet, cursor, checkNotNull(pcr)); cursor += 6 }
                }
                cursor = 4 + adaptation
            }
            val count = PACKET - cursor
            // Copy from the PES header first, then the borrowed sample bytes.
            var copied = 0
            while (copied < count) {
                val position = written + copied
                if (position < head.size) {
                    val n = minOf(head.size - position, count - copied)
                    System.arraycopy(head, position, packet, cursor + copied, n); copied += n
                } else {
                    val n = minOf(total - position, count - copied)
                    System.arraycopy(data, position - head.size, packet, cursor + copied, n); copied += n
                }
            }
            written += count
            out.write(packet)
            first = false
        }
    }

    private fun header(pid: Int, start: Boolean, adaptation: Boolean) {
        packet[0] = 0x47
        packet[1] = ((if (start) 0x40 else 0) or (pid shr 8)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        val counter = counters[pid]
        counters[pid] = (counter + 1) and 0x0F
        packet[3] = ((if (adaptation) 0x30 else 0x10) or counter).toByte()
    }

    companion object {
        const val PACKET = 188
        const val PMT_PID = 0x1000
        const val VIDEO_PID = 0x100
        const val AUDIO_PID = 0x101
        private const val MASK33 = (1L shl 33) - 1

        /** CRC-32/MPEG-2: polynomial 0x04C11DB7, initial value 0xFFFFFFFF, no reflection or final XOR. */
        fun crc32(data: ByteArray, offset: Int, length: Int): Int {
            var crc = -1
            for (i in offset until offset + length) {
                crc = crc xor (data[i].toInt() and 0xFF shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }

        fun timestamp(target: ByteArray, offset: Int, prefix: Int, value: Long) {
            val v = value and MASK33
            target[offset] = ((prefix shl 4) or ((v shr 30).toInt() and 0x07 shl 1) or 1).toByte()
            target[offset + 1] = (v shr 22).toByte()
            target[offset + 2] = (((v shr 15).toInt() and 0x7F shl 1) or 1).toByte()
            target[offset + 3] = (v shr 7).toByte()
            target[offset + 4] = (((v.toInt() and 0x7F) shl 1) or 1).toByte()
        }

        fun writePcr(target: ByteArray, offset: Int, value: Long) {
            val base = (value / 300) and MASK33
            val extension = (value % 300).toInt()
            target[offset] = (base shr 25).toByte()
            target[offset + 1] = (base shr 17).toByte()
            target[offset + 2] = (base shr 9).toByte()
            target[offset + 3] = (base shr 1).toByte()
            target[offset + 4] = (((base and 1).toInt() shl 7) or 0x7E or (extension shr 8)).toByte()
            target[offset + 5] = extension.toByte()
        }
    }
}

/** Annex-B access-unit preparation. Samples from MediaCodec and Android's MP4 extractor already use start codes. */
internal class CastNal(mime: String, csd: List<ByteArray>) {
    val hevc = when (mime) { "video/avc" -> false; "video/hevc" -> true; else -> throw IllegalArgumentException("ts-video-unsupported") }
    private val parameterSets: ByteArray = csd.fold(ByteArray(0)) { all, value ->
        require(value.isNotEmpty() && startCode(value, 0, value.size) > 0) { "ts-video-config-unsupported" }
        all + value
    }.also { sets -> require(types(sets, sets.size).any { it == if (hevc) 33 else 7 }) { "ts-video-config-unsupported" } }

    fun types(data: ByteArray, size: Int): List<Int> {
        val result = ArrayList<Int>()
        var i = 0
        while (i + 3 < size) {
            val code = startCode(data, i, size)
            if (code > 0 && i + code < size) {
                val header = data[i + code].toInt() and 0xFF
                result += if (hevc) header shr 1 and 0x3F else header and 0x1F
                i += code
            } else i++
        }
        return result
    }

    /** Adds an access-unit delimiter and, before a key frame without in-band parameter sets, the configuration. */
    fun accessUnit(sample: ByteArray, size: Int, key: Boolean): ByteArray {
        require(size > 0 && startCode(sample, 0, size) > 0) { "ts-video-not-annexb" }
        val types = types(sample, size)
        val delimiter = if (types.firstOrNull() == (if (hevc) 35 else 9)) ByteArray(0)
            else if (hevc) byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50) else byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        val config = if (key && types.none { it == if (hevc) 33 else 7 }) parameterSets else ByteArray(0)
        val output = ByteArray(delimiter.size + config.size + size)
        System.arraycopy(delimiter, 0, output, 0, delimiter.size)
        System.arraycopy(config, 0, output, delimiter.size, config.size)
        System.arraycopy(sample, 0, output, delimiter.size + config.size, size)
        return output
    }

    companion object {
        fun startCode(data: ByteArray, offset: Int, size: Int): Int = when {
            offset + 3 <= size && data[offset].toInt() == 0 && data[offset + 1].toInt() == 0 && data[offset + 2].toInt() == 1 -> 3
            offset + 4 <= size && data[offset].toInt() == 0 && data[offset + 1].toInt() == 0 && data[offset + 2].toInt() == 0 &&
                data[offset + 3].toInt() == 1 -> 4
            else -> 0
        }
    }
}

/** ADTS framing for raw AAC access units, derived from the AudioSpecificConfig in csd-0. */
internal class CastAdts(config: ByteArray) {
    val profile: Int
    val frequencyIndex: Int
    val channels: Int
    init {
        var bit = 0
        fun bits(count: Int): Int {
            var value = 0
            repeat(count) {
                require(bit / 8 < config.size) { "ts-audio-config-unsupported" }
                value = value shl 1 or (config[bit / 8].toInt() shr (7 - bit % 8) and 1); bit++
            }
            return value
        }
        fun objectType() = bits(5).let { if (it == 31) 32 + bits(6) else it }
        var type = objectType()
        val frequency = bits(4)
        require(frequency != 15) { "ts-audio-config-unsupported" }
        val channel = bits(4)
        if (type == 5 || type == 29) {
            // Explicit SBR/PS: ADTS carries the AAC core and decoders find SBR implicitly.
            require(bits(4) != 15) { "ts-audio-config-unsupported" }
            type = objectType()
        }
        require(type in 1..4 && frequency in 0..12 && channel in 1..7) { "ts-audio-config-unsupported" }
        profile = type - 1; frequencyIndex = frequency; channels = channel
    }
    fun header(payload: Int): ByteArray {
        val length = payload + 7
        require(payload > 0 && length <= 0x1FFF) { "ts-audio-frame-too-large" }
        return byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((profile shl 6) or (frequencyIndex shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (length shr 11)).toByte(), (length shr 3).toByte(),
            (((length and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
    }
}

/**
 * Restores a monotonic decode clock for video delivered in decode order without DTS (B-frames).
 * The n-th DTS is the n-th smallest PTS minus [delayUs], computed over a bounded look-ahead [window].
 * A stream whose reordering exceeds either bound fails explicitly instead of producing invalid timestamps.
 */
internal class CastDtsReorder<T>(private val window: Int = 16, private val delayUs: Long = 250_000) {
    private val pending = ArrayDeque<Pair<Long, T>>()
    private val times = PriorityQueue<Long>()
    private var last = Long.MIN_VALUE
    val delay get() = delayUs
    val size get() = pending.size
    fun push(pts: Long, item: T, emit: (T, Long, Long) -> Unit) {
        pending.addLast(pts to item); times.add(pts)
        while (pending.size > window) pop(emit)
    }
    fun flush(emit: (T, Long, Long) -> Unit) { while (pending.isNotEmpty()) pop(emit) }
    fun clear() { pending.clear(); times.clear() }
    private fun pop(emit: (T, Long, Long) -> Unit) {
        val (pts, item) = pending.removeFirst()
        val dts = checkNotNull(times.poll()) - delayUs
        check(dts >= last && dts <= pts) { "ts-reorder-too-deep" }
        last = dts
        emit(item, pts, dts)
    }
}
