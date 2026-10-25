package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Audio
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.AudioKind
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.AudioOutput
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Capture
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Evidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Range
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Reason
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Signal
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Snapshot
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Standard
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Transfer
import org.junit.Assert.*
import org.junit.Test

class FrameMediaPolicyTest {
    private val epoch = FrameTimeline.Epoch(1, 2, 3, 4, 5)
    private fun signal() = Signal("video/avc", "avc1.640028", Transfer.SDR, Standard.BT709,
        Range.LIMITED, 8, Evidence.NO, Evidence.NO)
    private fun media() = Snapshot(1920, 1080, signal(), signal(), Capture.VERIFIED_SDR_RGB8,
        Evidence.NO, Evidence.NO, Evidence.NO,
        Audio("audio/mp4a-latm", AudioOutput.PCM, Evidence.NO, Evidence.NO))
    private fun reason(media: Snapshot) = FrameMediaPolicy.evaluate(media).reason

    @Test fun `positive SDR RGB8 and ordinary PCM qualify without modifying source facts`() {
        val original = media()
        assertEquals(Reason.READY, reason(original))
        assertTrue(FrameMediaPolicy.evaluate(original).eligible)
        assertEquals(Range.LIMITED, original.source.range)
        assertEquals(Reason.READY, reason(original.copy(width = 1080, height = 1920)))
        assertEquals(Reason.READY, reason(original.copy(source = signal().copy(standard = Standard.BT601))))
    }

    @Test fun `unknown flags never stand in for explicit absence`() {
        val original = media()
        assertEquals(Reason.UNKNOWN_PROTECTION, reason(original.copy(protectedContent = Evidence.UNKNOWN)))
        assertEquals(Reason.UNKNOWN_PROTECTION, reason(original.copy(secureDecoder = Evidence.UNKNOWN)))
        assertEquals(Reason.UNKNOWN_VIDEO_PATH, reason(original.copy(videoTunneled = Evidence.UNKNOWN)))
        assertEquals(Reason.UNKNOWN_COLOR, reason(original.copy(source = signal().copy(hdrStatic = Evidence.UNKNOWN))))
        assertEquals(Reason.UNKNOWN_COLOR, reason(original.copy(decoded = signal().copy(hdrDynamic = Evidence.UNKNOWN))))
        assertEquals(Reason.CAPTURE_NOT_VERIFIED, reason(original.copy(capture = Capture.UNKNOWN)))
    }

    @Test fun `protection secure and tunneled output are never admitted`() {
        assertEquals(Reason.PROTECTED_CONTENT, reason(media().copy(protectedContent = Evidence.YES)))
        assertEquals(Reason.SECURE_DECODER, reason(media().copy(secureDecoder = Evidence.YES)))
        assertEquals(Reason.VIDEO_TUNNELED, reason(media().copy(videoTunneled = Evidence.YES)))
    }

    @Test fun `Dolby Vision MIME and all codec prefixes override a claimed SDR output`() {
        assertEquals(Reason.DOLBY_VISION, reason(media().copy(source = signal().copy(mime = " VIDEO/DOLBY-VISION "))))
        for (prefix in listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")) {
            assertEquals(prefix, Reason.DOLBY_VISION,
                reason(media().copy(source = signal().copy(codecs = "avc1.640028, ${prefix.uppercase()}.05.06"))))
            assertEquals(prefix, Reason.DOLBY_VISION,
                reason(media().copy(decoded = signal().copy(codecs = "$prefix.08.04"))))
        }
    }

    @Test fun `PQ HLG static and dynamic HDR in either layer retain original path`() {
        for (source in listOf(true, false)) {
            fun with(signal: Signal) = if (source) media().copy(source = signal) else media().copy(decoded = signal)
            assertEquals(Reason.HDR_PQ, reason(with(signal().copy(transfer = Transfer.PQ))))
            assertEquals(Reason.HDR_HLG, reason(with(signal().copy(transfer = Transfer.HLG))))
            assertEquals(Reason.HDR_METADATA, reason(with(signal().copy(hdrStatic = Evidence.YES))))
            assertEquals(Reason.HDR_DYNAMIC, reason(with(signal().copy(hdrDynamic = Evidence.YES))))
        }
        assertEquals(Reason.HDR_PQ, reason(media().copy(source = signal().copy(transfer = Transfer.PQ))))
        assertEquals(Transfer.SDR, media().decoded.transfer)
    }

    @Test fun `wide gamut linear or unclassified colors never use SDR backend`() {
        assertEquals(Reason.WIDE_COLOR, reason(media().copy(source = signal().copy(standard = Standard.BT2020))))
        assertEquals(Reason.UNSUPPORTED_COLOR, reason(media().copy(decoded = signal().copy(transfer = Transfer.LINEAR))))
        assertEquals(Reason.UNSUPPORTED_COLOR, reason(media().copy(decoded = signal().copy(standard = Standard.OTHER))))
        for (s in listOf(signal().copy(standard = Standard.UNKNOWN), signal().copy(transfer = Transfer.UNKNOWN),
                signal().copy(range = Range.UNKNOWN))) {
            assertEquals(Reason.UNKNOWN_COLOR, reason(media().copy(source = s)))
            assertEquals(Reason.UNKNOWN_COLOR, reason(media().copy(decoded = s)))
        }
    }

    @Test fun `ten bit SDR and missing bit depth do not silently become eight bit`() {
        assertEquals(Reason.UNSUPPORTED_BIT_DEPTH, reason(media().copy(source = signal().copy(bitDepth = 10))))
        assertEquals(Reason.UNSUPPORTED_BIT_DEPTH, reason(media().copy(decoded = signal().copy(bitDepth = 12))))
        assertEquals(Reason.UNKNOWN_BIT_DEPTH, reason(media().copy(source = signal().copy(bitDepth = null))))
        assertEquals(Reason.UNKNOWN_BIT_DEPTH, reason(media().copy(decoded = signal().copy(bitDepth = null))))
    }

    @Test fun `bounded dimensions include portrait and reject overflow and oversized tensors`() {
        for ((width, height) in listOf(0 to 1, 1 to -1, 4096 to 2161, 1 to Int.MAX_VALUE,
                Int.MAX_VALUE to Int.MAX_VALUE, 4097 to 1)) {
            assertEquals("$width x $height", Reason.INVALID_SIZE, reason(media().copy(width = width, height = height)))
        }
        // 4K 源由捕获缩到处理尺寸后插帧；源尺寸本身只受 4096×2160 像素上限约束。
        for ((width, height) in listOf(1920 to 1081, 3840 to 2160, 2160 to 3840, 4096 to 2160))
            assertNotEquals("$width x $height", Reason.INVALID_SIZE, reason(media().copy(width = width, height = height)))
    }

    @Test fun `DTS family decoded to verified PCM is a candidate without forcing audio conversion`() {
        val kinds = listOf("audio/vnd.dts" to AudioKind.DTS, "audio/vnd.dts.hd" to AudioKind.DTS_HD,
            "audio/vnd.dts.uhd" to AudioKind.DTS_UHD, "audio/eac3-joc" to AudioKind.DOLBY,
            "audio/vnd.dolby.mlp" to AudioKind.DOLBY, "audio/vnd.dolby.mat" to AudioKind.DOLBY)
        for ((mime, kind) in kinds) {
            val audio = Audio(mime, AudioOutput.PCM, Evidence.NO, Evidence.NO)
            val result = FrameMediaPolicy.evaluate(media().copy(audio = audio))
            assertTrue(mime, result.eligible)
            assertEquals(kind, result.audioKind)
            assertEquals(AudioOutput.PCM, audio.output)
        }
    }

    @Test fun `DTS label never authorizes passthrough offload tunneling or unknown transport`() {
        val cases = listOf(AudioOutput.PASSTHROUGH to Reason.AUDIO_PASSTHROUGH,
            AudioOutput.OFFLOAD to Reason.AUDIO_OFFLOAD, AudioOutput.TUNNELED to Reason.AUDIO_TUNNELED,
            AudioOutput.UNKNOWN to Reason.UNKNOWN_AUDIO_PATH)
        for ((output, expected) in cases) {
            assertEquals(expected, reason(media().copy(audio = Audio("audio/vnd.dts", output, Evidence.NO, Evidence.NO))))
        }
        assertEquals(Reason.AUDIO_OFFLOAD,
            reason(media().copy(audio = media().audio.copy(offloaded = Evidence.YES))))
        assertEquals(Reason.AUDIO_TUNNELED,
            reason(media().copy(audio = media().audio.copy(tunneled = Evidence.YES))))
        assertEquals(Reason.UNKNOWN_AUDIO_PATH,
            reason(media().copy(audio = media().audio.copy(offloaded = Evidence.UNKNOWN))))
        assertEquals(Reason.UNKNOWN_AUDIO_PATH,
            reason(media().copy(audio = media().audio.copy(tunneled = Evidence.UNKNOWN))))
    }

    @Test fun `no active audio must be explicit and does not authorize contradictory transport flags`() {
        assertEquals(Reason.READY, reason(media().copy(audio = Audio(output = AudioOutput.NONE))))
        assertEquals(Reason.AUDIO_OFFLOAD,
            reason(media().copy(audio = Audio(output = AudioOutput.NONE, offloaded = Evidence.YES))))
        assertEquals(Reason.AUDIO_TUNNELED,
            reason(media().copy(audio = Audio(output = AudioOutput.NONE, tunneled = Evidence.YES))))
    }

    @Test fun `invalid MIME codec syntax and over budget data cannot hide special format evidence`() {
        for (s in listOf(signal().copy(mime = null), signal().copy(mime = "video/dolby-vision;profile=5"),
                signal().copy(mime = "video/avc\n" + "secret"), signal().copy(codecs = "avc1.640028,"),
                signal().copy(codecs = "x.dvh1.05"), signal().copy(codecs = "dvh1:05:06"),
                signal().copy(codecs = "a".repeat(257)))) {
            assertEquals(Reason.UNKNOWN_COLOR, reason(media().copy(source = s)))
        }
        assertEquals(Reason.UNKNOWN_AUDIO_PATH, reason(media().copy(audio = media().audio.copy(mime = "a".repeat(257)))))
    }

    @Test fun `format changes revoke old permits even when returning to identical SDR facts`() {
        val gate = FrameMediaGate()
        val firstLease = gate.begin(epoch).lease
        val first = requireNotNull(gate.resolve(firstLease, media()).permit)
        val hdrBegin = gate.begin(epoch)
        assertSame(first, hdrBegin.revoked)
        assertFalse(gate.valid(first, firstLease))
        val hdr = gate.resolve(hdrBegin.lease, media().copy(source = signal().copy(transfer = Transfer.PQ)))
        assertNull(hdr.permit)
        val restoredLease = gate.begin(epoch).lease
        val restored = requireNotNull(gate.resolve(restoredLease, media()).permit)
        assertNotSame(first, restored)
        assertFalse(gate.valid(first, firstLease))
        assertTrue(gate.valid(restored, restoredLease))
    }

    @Test fun `media decoder surface format and discontinuity epochs each invalidate permits`() {
        val epochs = listOf(epoch.copy(media = 7), epoch.copy(decoder = 7), epoch.copy(surface = 7),
            epoch.copy(format = 7), epoch.copy(discontinuity = 7))
        for (next in epochs) {
            val gate = FrameMediaGate()
            val firstLease = gate.begin(epoch).lease
            val first = requireNotNull(gate.resolve(firstLease, media()).permit)
            val begin = gate.begin(next)
            assertSame(first, begin.revoked)
            val current = requireNotNull(gate.resolve(begin.lease, media()).permit)
            assertFalse(gate.valid(first, firstLease))
            assertFalse(gate.valid(current, firstLease))
            assertTrue(gate.valid(current, begin.lease))
        }
    }

    @Test fun `stale close and foreign gate permits cannot revoke the current format`() {
        val gate = FrameMediaGate()
        val oldLease = gate.begin(epoch).lease
        val old = requireNotNull(gate.resolve(oldLease, media()).permit)
        val currentLease = gate.begin(epoch).lease
        val current = requireNotNull(gate.resolve(currentLease, media().copy(width = 1280, height = 720)).permit)
        assertFalse(gate.close(oldLease))
        assertFalse(gate.valid(old, oldLease))
        assertTrue(gate.valid(current, currentLease))
        val other = FrameMediaGate()
        val foreignLease = other.begin(epoch).lease
        val foreign = requireNotNull(other.resolve(foreignLease, media()).permit)
        assertFalse(gate.close(foreignLease))
        assertFalse(gate.valid(foreign, foreignLease))
        assertTrue(gate.close(currentLease))
        assertFalse(gate.valid(current, currentLease))
        assertFalse(gate.close(currentLease))
    }

    @Test fun `late old format result cannot mint a new permit after an HDR observation`() {
        val gate = FrameMediaGate()
        val old = gate.begin(epoch).lease
        assertNotNull(gate.resolve(old, media()).permit)
        val hdr = gate.begin(epoch.copy(format = 6)).lease
        assertNull(gate.resolve(hdr, media().copy(source = signal().copy(transfer = Transfer.PQ))).permit)
        val late = gate.resolve(old, media())
        assertEquals(Reason.STALE_LEASE, late.decision.reason)
        assertNull(late.permit)
        assertEquals(Reason.ALREADY_RESOLVED, gate.resolve(hdr, media()).decision.reason)
    }

    @Test fun `a single format observation cannot resolve twice even with identical epoch values`() {
        val gate = FrameMediaGate()
        val first = gate.begin(epoch).lease
        assertNull(gate.resolve(first, media().copy(source = Signal())).permit)
        assertEquals(Reason.ALREADY_RESOLVED, gate.resolve(first, media()).decision.reason)
        val second = gate.begin(epoch).lease
        assertNotNull(gate.resolve(second, media()).permit)
        assertEquals(Reason.STALE_LEASE, gate.resolve(first, media()).decision.reason)
    }
}
