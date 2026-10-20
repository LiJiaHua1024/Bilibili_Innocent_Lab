package com.Bilibili_Innocent_Lab.xposedmodule.runtime

/**
 * 把启动授权端点的 status 分成「权威拒绝」和「本路不可用」。
 *
 * 四路通道、两秒窗口、nonce/UID/租约、以及「明确拒绝则禁止 Remote Preferences
 * 回退」全部保持原样。这里只纠正一种误判：包可见性导致的 `identity_rejected`、
 * 协议/存储未就绪、租约过期等，都是**这一路没走通**，不是用户/管理器否定授权。
 * 把它们记成 `admission_denied` 会置位 `sawDenied`，连已经完整校验过的 Remote
 * Preferences 回退也被禁掉，冷启动首页就会在钩子装上之前画完。
 */
internal object HostAdmissionRouteClassifier {
    enum class StatusClass { PREPARED, GRANTED, AUTHORITY_DENIED, UNAVAILABLE }

    enum class Outcome { CONTINUE, AUTHORITY_DENIED, UNAVAILABLE }

    const val REASON_DENIED = "admission_denied"
    const val REASON_UNAVAILABLE = "admission_unavailable"

    private val AUTHORITY_DENIED_STATUSES = setOf(
        "denied",
        "consent_commit_failed",
        "manager_sync_required",
    )

    fun classify(status: String?): StatusClass = when (status) {
        "prepared" -> StatusClass.PREPARED
        "granted" -> StatusClass.GRANTED
        in AUTHORITY_DENIED_STATUSES -> StatusClass.AUTHORITY_DENIED
        else -> StatusClass.UNAVAILABLE
    }

    /** PREPARE：信封残缺只算本路没走通，换别路或 Remote Preferences 保底；条款拒绝仍是否定。 */
    fun prepare(status: String?, envelopeValid: Boolean): Outcome = when (classify(status)) {
        StatusClass.PREPARED -> if (envelopeValid) Outcome.CONTINUE else Outcome.UNAVAILABLE
        StatusClass.AUTHORITY_DENIED -> Outcome.AUTHORITY_DENIED
        StatusClass.GRANTED, StatusClass.UNAVAILABLE -> Outcome.UNAVAILABLE
    }

    /** CONFIRM：granted 但绑定对不上只丢掉这一路，不闩整次启动。 */
    fun confirm(status: String?, successBinding: Boolean): Outcome = when (classify(status)) {
        StatusClass.GRANTED -> if (successBinding) Outcome.CONTINUE else Outcome.UNAVAILABLE
        StatusClass.AUTHORITY_DENIED -> Outcome.AUTHORITY_DENIED
        StatusClass.PREPARED, StatusClass.UNAVAILABLE -> Outcome.UNAVAILABLE
    }

    fun reason(outcome: Outcome): String? = when (outcome) {
        Outcome.CONTINUE -> null
        Outcome.AUTHORITY_DENIED -> REASON_DENIED
        Outcome.UNAVAILABLE -> REASON_UNAVAILABLE
    }

    fun latchesDenial(reason: String): Boolean = reason == REASON_DENIED

    fun allowRemoteConfigFallback(
        sawDenied: Boolean,
        normalFailure: String?,
        authorized: Boolean,
        moduleVersionMatches: Boolean,
        generation: Long,
        nPatchRuntime: Boolean = false,
    ): Boolean = !sawDenied &&
        normalFailure == null &&
        authorized &&
        moduleVersionMatches &&
        generation > 0L &&
        // NPatch 的正 revision 也可能是 Manager 失联前的旧缓存：无法证明用户之后没有关闭。
        // 必须通过已有授权端点取得当前许可，不能只凭缓存文档恢复功能 Hook。
        !nPatchRuntime
}
