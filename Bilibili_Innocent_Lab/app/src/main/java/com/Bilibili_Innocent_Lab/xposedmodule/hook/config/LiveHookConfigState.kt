package com.Bilibili_Innocent_Lab.xposedmodule.hook.config

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookEntry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.DanmakuPurifyPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences as P
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.RuleSetCodec
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.VideoDurationRange
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.VideoPlayCountRange
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.PublicationIdentity
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigContract
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.RemoteHookConfigSnapshot
import java.util.concurrent.atomic.AtomicReference

internal data class LiveHomeRules(val titles: Set<String>, val duration: VideoDurationRange, val plays: VideoPlayCountRange)
internal data class LiveDanmakuRules(val minimum: Int?, val colorful: Boolean)
internal data class LiveApplyReport(val status: String, val applied: Int = 0, val deferred: Int = 0, val revision: Long = 0)

/** 只更新有完整叶项安装证据的值；原启动授权与结构配置始终不可变。 */
internal class LiveHookConfigState(
    private val initial: RemoteHookConfigSnapshot,
    private val authority: PublicationIdentity
) : HookConfigSource {
    private data class State(val source: SnapshotHookConfigSource, val values: Map<String, Any>,
        val revision: Long, val fingerprint: String, val home: LiveHomeRules, val danmaku: LiveDanmakuRules)
    private val current = AtomicReference(make(initial.values, initial.noRootRevision, authority.fingerprint))

    override fun getBoolean(key: String, defaultValue: Boolean) = current.get().source.getBoolean(key, defaultValue)
    override fun getInt(key: String, defaultValue: Int) = current.get().source.getInt(key, defaultValue)
    override fun getLong(key: String, defaultValue: Long) = current.get().source.getLong(key, defaultValue)
    override fun getString(key: String, defaultValue: String) = current.get().source.getString(key, defaultValue)
    fun home(): LiveHomeRules = current.get().home
    fun danmaku(): LiveDanmakuRules = current.get().danmaku

    @Synchronized fun apply(candidate: RemoteHookConfigSnapshot, identity: PublicationIdentity,
                            installed: Set<String>, mayCommit: () -> Boolean = { true }): LiveApplyReport {
        val previous = current.get()
        if (!initial.authorized || !candidate.authorized || initial.noRootRevision <= 0 || candidate.noRootRevision <= 0 ||
            candidate.moduleVersionCode != initial.moduleVersionCode || identity.incarnation != authority.incarnation ||
            identity.consentRevision != authority.consentRevision ||
            identity.snapshotRevision != candidate.generation ||
            candidate.noRootRevision < previous.revision ||
            candidate.noRootRevision == previous.revision && identity.fingerprint != previous.fingerprint) {
            return LiveApplyReport("REJECTED")
        }
        val allowed = mutableSetOf<String>()
        fun enabled(key: String, default: Boolean = false) = initial.values[key] as? Boolean ?: default
        fun ints(values: Map<String, Any>, key: String) = values[key] as? Int ?: 0
        val comment = enabled(HookEntry.PREF_FREE_COPY_ENABLED, true) && "free_copy_comment_enabled" in installed
        val description = enabled(HookEntry.PREF_FREE_COPY_DESC_ENABLED, true) && "free_copy_description_enabled" in installed
        if (comment) allowed += HookEntry.PREF_FREE_COPY_ENABLED
        if (description) allowed += HookEntry.PREF_FREE_COPY_DESC_ENABLED
        if (comment || description) allowed += HookEntry.PREF_FREE_COPY_LIGHT_MODE
        // 自动跟随在启动时条件注册额外 Hook，没有独立完整证据，保留重启语义。
        if (enabled(P.DANMAKU_WEIGHT_FILTER_ENABLED) && "player_danmaku_weight_filter_enabled" in installed &&
            candidate.values[P.DANMAKU_WEIGHT_FILTER_MINIMUM] is Int &&
            ints(candidate.values, P.DANMAKU_WEIGHT_FILTER_MINIMUM) in DanmakuPurifyPolicy.MIN_WEIGHT..DanmakuPurifyPolicy.MAX_WEIGHT) {
            allowed += P.DANMAKU_WEIGHT_FILTER_MINIMUM
        }
        if (enabled(P.REMOVE_VIP_COLORFUL_DANMAKU) && "player_danmaku_vip_colorful_removed" in installed) {
            allowed += P.REMOVE_VIP_COLORFUL_DANMAKU
        }
        if (enabled(P.HOME_RECOMMEND_TITLE_FILTER_ENABLED) &&
            RuleSetCodec.parse(initial.values[P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS] as? String ?: "").isNotEmpty() &&
            "home_recommend_title_filter_enabled" in installed) allowed += P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS
        if (VideoDurationRange(ints(initial.values, P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS),
                ints(initial.values, P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS)).isEnabled &&
            candidate.values[P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS] is Int && candidate.values[P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS] is Int &&
            VideoDurationRange(ints(candidate.values, P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS),
                ints(candidate.values, P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS)).isValid &&
            "home_recommend_duration_filter" in installed) {
            allowed += setOf(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS, P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS)
        }
        if (VideoPlayCountRange(ints(initial.values, P.RECOMMEND_VIDEO_MIN_PLAY_COUNT),
                ints(initial.values, P.RECOMMEND_VIDEO_MAX_PLAY_COUNT)).isEnabled &&
            candidate.values[P.RECOMMEND_VIDEO_MIN_PLAY_COUNT] is Int && candidate.values[P.RECOMMEND_VIDEO_MAX_PLAY_COUNT] is Int &&
            VideoPlayCountRange(ints(candidate.values, P.RECOMMEND_VIDEO_MIN_PLAY_COUNT),
                ints(candidate.values, P.RECOMMEND_VIDEO_MAX_PLAY_COUNT)).isValid &&
            "home_recommend_play_count_filter" in installed) {
            allowed += setOf(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT, P.RECOMMEND_VIDEO_MAX_PLAY_COUNT)
        }
        val values = previous.values.toMutableMap()
        var applied = 0
        for (key in allowed) {
            val value = candidate.values[key] ?: continue
            // 完整协议已经校验类型，这里再次防止直接调用构造出的错误候选。
            val valid = when (key) {
                P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS -> value is String
                P.DANMAKU_WEIGHT_FILTER_MINIMUM, P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS,
                P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS, P.RECOMMEND_VIDEO_MIN_PLAY_COUNT,
                P.RECOMMEND_VIDEO_MAX_PLAY_COUNT -> value is Int
                else -> value is Boolean
            }
            if (valid && values[key] != value) { values[key] = value; applied++ }
        }
        val ignored = setOf(RemoteHookConfigContract.KEY_FREE_COPY_CONFIG_REVISION)
        val deferred = (values.keys + candidate.values.keys).count {
            it !in ignored && values[it] != candidate.values[it]
        }
        val replacement = make(values, candidate.noRootRevision, identity.fingerprint)
        if (!mayCommit()) return LiveApplyReport("REJECTED")
        current.set(replacement)
        return LiveApplyReport(if (applied > 0) "APPLIED" else if (deferred > 0) "RESTART_REQUIRED" else "UNCHANGED",
            applied, deferred, candidate.noRootRevision)
    }

    private fun make(values: Map<String, Any>, revision: Long, fingerprint: String): State {
        val frozen = values.toMap()
        val source = SnapshotHookConfigSource(frozen)
        return State(source, frozen, revision, fingerprint,
            LiveHomeRules(if (source.getBoolean(P.HOME_RECOMMEND_TITLE_FILTER_ENABLED, false))
                RuleSetCodec.parse(source.getString(P.HOME_RECOMMEND_TITLE_FILTER_KEYWORDS, "")) else emptySet(),
                VideoDurationRange(source.getInt(P.RECOMMEND_VIDEO_MIN_DURATION_SECONDS, 0),
                    source.getInt(P.RECOMMEND_VIDEO_MAX_DURATION_SECONDS, 0)),
                VideoPlayCountRange(source.getInt(P.RECOMMEND_VIDEO_MIN_PLAY_COUNT, 0),
                    source.getInt(P.RECOMMEND_VIDEO_MAX_PLAY_COUNT, 0))),
            LiveDanmakuRules(if (source.getBoolean(P.DANMAKU_WEIGHT_FILTER_ENABLED, false))
                DanmakuPurifyPolicy.normalizeWeight(source.getInt(P.DANMAKU_WEIGHT_FILTER_MINIMUM,
                    DanmakuPurifyPolicy.DEFAULT_MINIMUM_WEIGHT)) else null,
                source.getBoolean(P.REMOVE_VIP_COLORFUL_DANMAKU, false)))
    }
}
