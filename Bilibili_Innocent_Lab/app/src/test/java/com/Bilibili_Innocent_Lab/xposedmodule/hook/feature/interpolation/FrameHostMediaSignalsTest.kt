package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameHostMediaSignals.Values
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Reason
import org.junit.Assert.*
import org.junit.Test

class FrameHostMediaSignalsTest {
    private val sdr709 = Values("video/avc", colorStandard = 1, colorTransfer = 3, colorRange = 2)

    private fun decision(source: Values, decoded: Values, capture: FrameMediaPolicy.Capture =
        FrameMediaPolicy.Capture.VERIFIED_SDR_RGB8) = FrameMediaPolicy.evaluate(FrameMediaPolicy.Snapshot(
            1280, 720, FrameHostMediaSignals.signal(source.fillFrom(decoded)), FrameHostMediaSignals.signal(decoded, 8),
            capture, FrameMediaPolicy.Evidence.NO, FrameMediaPolicy.Evidence.NO, FrameMediaPolicy.Evidence.NO,
            FrameMediaPolicy.Audio("audio/mp4a-latm", FrameMediaPolicy.AudioOutput.PCM,
                FrameMediaPolicy.Evidence.NO, FrameMediaPolicy.Evidence.NO)))

    @Test fun `sdr avc with color keys only in the decoder output is admitted`() {
        assertEquals(Reason.READY, decision(Values("video/avc"), sdr709).reason)
    }

    @Test fun `hdr evidence in either layer is kept and rejected`() {
        assertEquals(Reason.HDR_PQ, decision(sdr709.copy(colorTransfer = 6), sdr709).reason)
        assertEquals(Reason.HDR_METADATA, decision(Values("video/avc", hdrStaticInfo = true), sdr709).reason)
        assertEquals(Reason.HDR_DYNAMIC, decision(sdr709, sdr709.copy(hdr10PlusInfo = true)).reason)
        assertEquals(Reason.WIDE_COLOR, decision(sdr709, sdr709.copy(colorStandard = 6)).reason)
    }

    @Test fun `missing color keys in both layers or unknown bit depth are rejected`() {
        assertEquals(Reason.UNKNOWN_COLOR, decision(Values("video/avc"), Values("video/avc")).reason)
        val hevc = sdr709.copy(mime = "video/hevc")
        assertEquals(Reason.UNKNOWN_BIT_DEPTH, decision(hevc, hevc).reason)
        assertEquals(Reason.READY, decision(hevc.copy(profile = 1), hevc).reason)
        assertEquals(Reason.UNSUPPORTED_BIT_DEPTH, decision(hevc.copy(profile = 2), hevc).reason)
    }

    @Test fun `all zero or empty hdr metadata reported for sdr is not hdr evidence`() {
        assertFalse(FrameHostMediaSignals.hdrMetadataPresent(null))
        assertFalse(FrameHostMediaSignals.hdrMetadataPresent(ByteArray(0)))
        assertFalse(FrameHostMediaSignals.hdrMetadataPresent(ByteArray(25)))
        assertTrue(FrameHostMediaSignals.hdrMetadataPresent(ByteArray(25).also { it[3] = 0x10 }))
    }

    @Test fun `only bt601 and bt709 have a verified converter`() {
        assertEquals(0, FrameHostMediaSignals.converterStandard(FrameMediaPolicy.Standard.BT601))
        assertEquals(1, FrameHostMediaSignals.converterStandard(FrameMediaPolicy.Standard.BT709))
        assertNull(FrameHostMediaSignals.converterStandard(FrameMediaPolicy.Standard.BT2020))
        assertNull(FrameHostMediaSignals.converterStandard(FrameMediaPolicy.Standard.UNKNOWN))
    }
}
