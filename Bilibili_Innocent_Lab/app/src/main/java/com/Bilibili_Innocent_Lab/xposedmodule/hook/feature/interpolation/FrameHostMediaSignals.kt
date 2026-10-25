package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Evidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Range
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Signal
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Standard
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameMediaPolicy.Transfer

/**
 * MediaFormat 键值 → [FrameMediaPolicy.Signal]。只接收原始整数与字符串，便于纯 JVM 测试；
 * 常量取自 android.media.MediaFormat / MediaCodecInfo.CodecProfileLevel，未知值一律映射为 UNKNOWN。
 *
 * 宿主 configure 时的格式常缺颜色键（ijk 只填尺寸与 csd）；缺失项用解码器输出格式补齐，
 * 两层都缺才是 UNKNOWN。补齐只填空缺，任何一层出现的 HDR/广色域证据都会保留并使准入失败。
 */
internal object FrameHostMediaSignals {
    data class Values(
        val mime: String? = null,
        val colorStandard: Int? = null,
        val colorTransfer: Int? = null,
        val colorRange: Int? = null,
        val hdrStaticInfo: Boolean = false,
        val hdr10PlusInfo: Boolean = false,
        val profile: Int? = null
    ) {
        fun fillFrom(other: Values) = Values(mime ?: other.mime, colorStandard ?: other.colorStandard,
            colorTransfer ?: other.colorTransfer, colorRange ?: other.colorRange,
            hdrStaticInfo || other.hdrStaticInfo, hdr10PlusInfo || other.hdr10PlusInfo, profile ?: other.profile)
    }

    fun signal(values: Values, decodedBitDepth: Int? = null): Signal = Signal(
        mime = values.mime,
        transfer = when (values.colorTransfer) { 1 -> Transfer.LINEAR; 3 -> Transfer.SDR; 6 -> Transfer.PQ; 7 -> Transfer.HLG
            null -> Transfer.UNKNOWN; else -> Transfer.UNKNOWN },
        standard = when (values.colorStandard) { 1 -> Standard.BT709; 2, 4 -> Standard.BT601; 6 -> Standard.BT2020
            null -> Standard.UNKNOWN; else -> Standard.OTHER },
        range = when (values.colorRange) { 1 -> Range.FULL; 2 -> Range.LIMITED; else -> Range.UNKNOWN },
        bitDepth = decodedBitDepth ?: bitDepth(values.mime, values.profile),
        hdrStatic = if (values.hdrStaticInfo) Evidence.YES else Evidence.NO,
        hdrDynamic = if (values.hdr10PlusInfo) Evidence.YES else Evidence.NO
    )

    /**
     * 真机（高通 Codec2）：SDR 流的输出格式也带 hdr-static-info（全零）与 hdr10-plus-info（空缓冲）。
     * 只有存在非零元数据才算 HDR 证据；缺失、空与全零都视为没有该类 HDR 元数据。
     */
    fun hdrMetadataPresent(bytes: ByteArray?): Boolean = bytes != null && bytes.any { it.toInt() != 0 }

    /** BT.601 = 0、BT.709 = 1；其余返回 null（转换器不支持，捕获不能声称已验证）。 */
    fun converterStandard(standard: Standard): Int? = when (standard) {
        Standard.BT601 -> 0; Standard.BT709 -> 1; else -> null
    }

    private fun bitDepth(mime: String?, profile: Int?): Int? = when (mime?.lowercase()) {
        // Android 硬解不支持 AVC High10；缺 profile 时按 8 bit，High10(0x10)/High422(0x20)/High444(0x40) 视为非 8 bit。
        "video/avc" -> if (profile == null || profile !in setOf(0x10, 0x20, 0x40)) 8 else 10
        "video/hevc" -> when (profile) { 1 -> 8; 2, 0x1000, 0x2000 -> 10; else -> null }
        "video/x-vnd.on2.vp9" -> when (profile) { 1, 2 -> 8; 4, 8, 0x1000, 0x2000, 0x4000, 0x8000 -> 10; else -> null }
        "video/av01" -> when (profile) { 1 -> 8; 2, 0x1000, 0x2000 -> 10; else -> null }
        else -> null
    }
}
