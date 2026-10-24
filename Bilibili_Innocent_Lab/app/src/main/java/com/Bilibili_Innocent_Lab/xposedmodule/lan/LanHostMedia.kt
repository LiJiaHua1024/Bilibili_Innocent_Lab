package com.Bilibili_Innocent_Lab.xposedmodule.lan

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerBufferLocator
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Exact IJK asset boundary: current CID, selected quality/codec, and the player's own HTTP headers. */
internal class LanHostMedia private constructor(private val cid: Method, private val quality: Method,
    private val audio: Field, private val codec: Field, private val config: Field, private val agent: Field,
    private val referer: Field, private val streamQuality: Method, private val streamKind: Method,
    private val streamCodec: Method, private val backups: Method) {
    fun read(real: Any, asset: Any, points: PlayerBufferLocator.Points, bvid: String, expectedCid: Long): CastSource {
        require(cid.invoke(real) == expectedCid) { "current-media-cid-mismatch" }
        require(points.assetDrm.invoke(asset) == 0) { "drm-not-supported" }
        val videoQuality = quality.invoke(real) as Int
        val audioQuality = audio.getInt(real)
        val videoCodec = when (codec.getInt(real)) { 7 -> "H264"; 12 -> "H265"; 13 -> "AV1"; else -> error("current-codec-unavailable") }
        val streams = (points.streams.invoke(asset) as? List<*>)?.filterNotNull() ?: error("current-tracks-unavailable")
        require(streams.size in 1..64)
        val video = streams.singleOrNull {
            streamQuality.invoke(it) == videoQuality && (streamKind.invoke(it) as Enum<*>).name in setOf("DASH_VIDEO", "NORMAL") &&
                (streamCodec.invoke(it) as Enum<*>).name == videoCodec
        } ?: error("current-video-track-ambiguous")
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
            fun field(type: Class<*>, name: String, result: Class<*>) = Lookup.fieldOrNull(type, name)
                ?.takeIf { it.type == result } ?: error("host-media-field-unavailable")
            val kind = Lookup.inheritedMethodOrNull(stream, "getStreamType")?.takeIf { it.returnType.isEnum } ?: return null
            val codec = Lookup.inheritedMethodOrNull(stream, "getVideoCodecType")?.takeIf { it.returnType.isEnum } ?: return null
            LanHostMedia(method(item, "getCid", Long::class.javaPrimitiveType!!), method(item, "getCurQn", Int::class.javaPrimitiveType!!),
                field(item, "mCurAudioId", Int::class.javaPrimitiveType!!), field(item, "mVideoCodecType", Int::class.javaPrimitiveType!!),
                config, field(config.type, "mUserAgent", String::class.java), field(config.type, "mReferer", String::class.java),
                method(stream, "getQualityId", Int::class.javaPrimitiveType!!), kind, codec, method(segment, "getBackupUrls", List::class.java))
        }.getOrNull()
    }
}
