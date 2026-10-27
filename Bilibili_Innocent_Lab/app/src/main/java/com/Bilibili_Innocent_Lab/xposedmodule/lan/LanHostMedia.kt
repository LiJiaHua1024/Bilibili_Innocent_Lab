package com.Bilibili_Innocent_Lab.xposedmodule.lan

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerBufferLocator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Exact IJK asset boundary: current CID, selected quality/codec, and the player's own HTTP headers. */
internal class LanHostMedia private constructor(private val cid: Method, private val quality: Method,
    private val videoId: Field?, private val audio: Field, private val codec: Field, private val config: Field, private val agent: Field,
    private val referer: Field, private val streamQuality: Method, private val streamKind: Method,
    private val streamCodec: Method, private val backups: Method) {
    fun read(real: Any, asset: Any, points: PlayerBufferLocator.Points, bvid: String, expectedCid: Long): CastSource {
        require(cid.invoke(real) == expectedCid) { "current-media-cid-mismatch" }
        require(points.assetDrm.invoke(asset) == 0) { "drm-not-supported" }
        val videoQuality = quality.invoke(real) as Int
        val audioQuality = audio.getInt(real)
        // 7/12/13 是 DASH 的 codecid（AVC/HEVC/AV1），属于协议常量而不是可逐版取证的宿主结构；
        // 认不出的编码值不再直接失败，交给"同清晰度唯一轨道"兜底，避免新编码或标记变化让整条链路不可用。
        val videoCodec = when (codec.getInt(real)) { 7 -> "H264"; 12 -> "H265"; 13 -> "AV1"; else -> null }
        val streams = (points.streams.invoke(asset) as? List<*>)?.filterNotNull() ?: error("current-tracks-unavailable")
        require(streams.size in 1..64)
        fun videoFor(qn: Int) = videoCodec?.let { name ->
            streams.filter {
                streamQuality.invoke(it) == qn && (streamKind.invoke(it) as Enum<*>).name in setOf("DASH_VIDEO", "NORMAL") &&
                    (streamCodec.invoke(it) as Enum<*>).name == name
            }.singleOrNull()
        }
        fun videoForAnyCodec(qn: Int) = streams.filter {
            streamQuality.invoke(it) == qn && (streamKind.invoke(it) as Enum<*>).name in setOf("DASH_VIDEO", "NORMAL")
        }.singleOrNull()
        // 8.90.2 上已实机验证的实现只读 mCurVideoId；8.97.0 起的 getCurQn()/getCurVideoId() 先读 native 属性
        // 0x4f1a 再回退该字段。属性值不保证等于清晰度编号，所以两个值都只用于"唯一选中同编码视频轨道"，
        // 选中不了就继续尝试另一个，不能把属性值直接当成清晰度。
        val fieldQuality = videoId?.let { field -> runCatching { field.getInt(real) }.getOrNull() }
        // 先按"清晰度 + 编码"精确匹配；认不出编码或该清晰度只有一条轨道时，用"清晰度唯一轨道"兜底。
        val video = videoFor(videoQuality)
            ?: fieldQuality?.let { videoFor(it) }
            ?: videoForAnyCodec(videoQuality)
            ?: fieldQuality?.let { videoForAnyCodec(it) }
            ?: error("current-video-track-ambiguous")
        val selected = if ((streamKind.invoke(video) as Enum<*>).name == "NORMAL") listOf(video) else listOf(video,
            streams.singleOrNull { streamQuality.invoke(it) == audioQuality && (streamKind.invoke(it) as Enum<*>).name == "DASH_AUDIO" }
                ?: error("current-audio-track-ambiguous"))
        val primary = ArrayList<String>(); val alternatives = linkedMapOf<String, List<String>>()
        selected.forEach { stream ->
            require(points.streamDrm.invoke(stream) == 0) { "drm-not-supported" }
            val segment = (points.segments.invoke(stream) as? List<*>)?.singleOrNull() ?: error("segmented-media-unavailable")
            val urls = (listOf(points.segmentUrl.invoke(segment)) + (backups.invoke(segment) as? List<*>).orEmpty())
                .filterIsInstance<String>().distinct().take(8).filter { runCatching { validateMediaUrl(it, allowHttp = true) }.isSuccess }
            require(urls.isNotEmpty()) { "current-media-origin-unavailable" }
            primary += urls.first(); alternatives[urls.first()] = urls.drop(1)
        }
        val settings = config.get(real) ?: error("current-http-config-unavailable")
        val userAgent = (agent.get(settings) as? String)?.takeIf(String::isNotBlank) ?: error("current-user-agent-unavailable")
        val headers = linkedMapOf("User-Agent" to userAgent)
        (referer.get(settings) as? String)?.takeIf(String::isNotBlank)?.let { headers["Referer"] = it }
        // Recheck after reading the asset; never label an outgoing old media item with a new playable CID.
        require(cid.invoke(real) == expectedCid && points.assetField.get(real) === asset) { "current-media-changed" }
        return CastSource(primary, bvid, expectedCid, alternatives, headers)
    }

    companion object {
        fun resolve(loader: ClassLoader, points: PlayerBufferLocator.Points): LanHostMedia? = runCatching {
            val item = points.item
            val config = Lookup.fieldOrNull(item, "mIjkMediaConfigParams") ?: return null
            val stream = Lookup.classOrNull(loader, "${PlayerBufferLocator.ASSET}\$MediaAssetStream") ?: return null
            val segment = Lookup.classOrNull(loader, "${PlayerBufferLocator.ASSET}\$MediaAssertSegment") ?: return null
            fun method(type: Class<*>, name: String, result: Class<*>) = Lookup.inheritedMethodOrNull(type, name)
                ?.takeIf { it.returnType == result } ?: error("host-media-signature-unavailable")
            /**
             * 当前视频质量 id。8.84.0–8.97.0 名为 getCurQn()I，8.98.0 起改名为 getCurVideoId()I。
             * 8.90.2（已实机验证）只读 mCurVideoId；8.97.0 起的实现先读 native 属性 0x4f1a 再回退该字段，
             * 所以调用方只把它当作"候选值"，另用 mCurVideoId 与唯一轨道兜底。
             */
            fun quality(type: Class<*>) = Lookup.inheritedMethodOrNull(type, "getCurQn")
                ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
                ?: Lookup.inheritedMethodOrNull(type, "getCurVideoId")
                    ?.takeIf { it.returnType == Int::class.javaPrimitiveType }
                ?: error("current-quality-unavailable")
            fun field(type: Class<*>, name: String, result: Class<*>) = Lookup.fieldOrNull(type, name)
                ?.takeIf { it.type == result } ?: error("host-media-field-unavailable")
            val kind = Lookup.inheritedMethodOrNull(stream, "getStreamType")?.takeIf { it.returnType.isEnum } ?: return null
            val codec = Lookup.inheritedMethodOrNull(stream, "getVideoCodecType")?.takeIf { it.returnType.isEnum } ?: return null
            LanHostMedia(method(item, "getCid", Long::class.javaPrimitiveType!!), quality(item),
                Lookup.fieldOrNull(item, "mCurVideoId")?.takeIf { it.type == Int::class.javaPrimitiveType },
                field(item, "mCurAudioId", Int::class.javaPrimitiveType!!), field(item, "mVideoCodecType", Int::class.javaPrimitiveType!!),
                config, field(config.type, "mUserAgent", String::class.java), field(config.type, "mReferer", String::class.java),
                method(stream, "getQualityId", Int::class.javaPrimitiveType!!), kind, codec, method(segment, "getBackupUrls", List::class.java))
        }.getOrNull()
    }
}
