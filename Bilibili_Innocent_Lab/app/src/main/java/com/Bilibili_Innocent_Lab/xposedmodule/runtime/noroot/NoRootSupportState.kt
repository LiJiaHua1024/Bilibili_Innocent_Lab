package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isRootFramework

internal enum class NoRootDisplayState {
    UNSUPPORTED_OS,
    DISABLED,
    CHECKING,
    MANAGER_MISSING,
    MODULE_NOT_REGISTERED,
    SCOPE_MISSING,
    CONFIRMING,
    UNCONFIRMED,
    NOT_RUNNING,
    SYNCING,
    RESTART_REQUIRED,
    DISABLE_RESTART_REQUIRED,
    DISABLE_RESTART_REQUIRED_ACTIVE,
    ACTIVE,
    CONNECTION_TIMEOUT,
    ERROR
}

internal data class ActivationDecision(
    val activated: Boolean,
    val byNoRoot: Boolean
)

internal enum class ActivationDisplayState {
    CHECKING,
    ACTIVE_LSPOSED,
    ACTIVE_LSPATCH,
    ACTIVE_NPATCH,
    LSPATCH_WAITING_FOR_HOST,
    LSPATCH_HOST_FAILED,
    SCOPE_MISSING,
    CONFIRMING,
    UNCONFIRMED,
    NOT_RUNNING,
    UNAVAILABLE
}

internal enum class LspatchHostReceiptState {
    WAITING,
    CONFIRMED,
    FAILED
}

/** 纯状态归并，避免把“开关已开”或“Manager 可连接”误显示成已激活。 */
internal object NoRootSupportState {
    const val MIN_SUPPORTED_SDK = 28
    const val TARGET_PACKAGE = "tv.danmaku.bili"

    fun displayState(
        sdkInt: Int,
        status: NoRootSupportStore.Status,
        currentSnapshot: NoRootConfigSnapshot?,
        currentTargetVersionCode: Long,
        currentTargetUpdateTime: Long,
        scope: NPatchScopeProbe.ScopeState = NPatchScopeProbe.ScopeState.UNKNOWN,
        probe: HostRuntimeProbeStatus = HostRuntimeProbeStatus.IDLE
    ): NoRootDisplayState {
        if (sdkInt < MIN_SUPPORTED_SDK) return NoRootDisplayState.UNSUPPORTED_OS
        if (!status.desiredEnabled) {
            return if (status.syncState == NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED) {
                if (status.disableWasActive) {
                    NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE
                } else {
                    NoRootDisplayState.DISABLE_RESTART_REQUIRED
                }
            } else {
                NoRootDisplayState.DISABLED
            }
        }
        // 作用域被 Manager 移除后，历史心跳仍可能逐项匹配；此时不能再宣称已激活。
        // UNKNOWN 只代表本次没读到，不改变原有判定。
        if (scope == NPatchScopeProbe.ScopeState.OUT_OF_SCOPE) {
            return NoRootDisplayState.SCOPE_MISSING
        }
        val heartbeatMatches = currentSnapshot?.let { snapshot ->
            snapshot.enabled &&
                status.heartbeatRevision == snapshot.revision &&
                status.heartbeatModuleVersion == snapshot.moduleVersionCode &&
                status.heartbeatTargetPackage == TARGET_PACKAGE &&
                currentTargetVersionCode > 0L &&
                status.heartbeatTargetVersion == currentTargetVersionCode &&
                currentTargetUpdateTime > 0L &&
                status.heartbeatTargetUpdateTime == currentTargetUpdateTime &&
                status.heartbeatReceivedAt > 0L
        } == true
        // 心跳只证明“某个时刻装过 Hook”；B12 之后还要求存活探测的实时证据才叫已激活。
        if (heartbeatMatches) {
            return when (probe) {
                HostRuntimeProbeStatus.CONFIRMED -> NoRootDisplayState.ACTIVE
                HostRuntimeProbeStatus.PROBING -> NoRootDisplayState.CONFIRMING
                HostRuntimeProbeStatus.NOT_RUNNING -> NoRootDisplayState.NOT_RUNNING
                HostRuntimeProbeStatus.UNCONFIRMED,
                HostRuntimeProbeStatus.IDLE -> NoRootDisplayState.UNCONFIRMED
            }
        }
        return when (status.syncState) {
            NoRootSupportStore.SyncState.DISABLED -> NoRootDisplayState.CHECKING
            NoRootSupportStore.SyncState.CHECKING -> NoRootDisplayState.CHECKING
            NoRootSupportStore.SyncState.MANAGER_MISSING -> NoRootDisplayState.MANAGER_MISSING
            NoRootSupportStore.SyncState.MODULE_NOT_REGISTERED ->
                NoRootDisplayState.MODULE_NOT_REGISTERED
            NoRootSupportStore.SyncState.SYNCING -> NoRootDisplayState.SYNCING
            NoRootSupportStore.SyncState.RESTART_REQUIRED -> NoRootDisplayState.RESTART_REQUIRED
            NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED ->
                NoRootDisplayState.DISABLE_RESTART_REQUIRED
            NoRootSupportStore.SyncState.ACTIVE ->
                if (heartbeatMatches) NoRootDisplayState.ACTIVE
                else NoRootDisplayState.RESTART_REQUIRED
            NoRootSupportStore.SyncState.CONNECTION_TIMEOUT ->
                NoRootDisplayState.CONNECTION_TIMEOUT
            NoRootSupportStore.SyncState.ERROR -> NoRootDisplayState.ERROR
        }
    }

    /**
     * 重启确认框是否走免 Root 的“手动重启”分支（先落盘再打开应用详情）。
     *
     * 这条判据原本是 `YukiHookAPI.Status.isXposedModuleActive`——真 Root 框架已把模块自身注入。
     * API 102 重构（06ebc29）把它换成了 `capable`（Modern 服务可写），语义漂移：NPatch v1.0.8
     * 起也向模块下发 Modern 服务，`capable` 对它同样为真，于是无 Root 的 NPatch 用户拿到 Root 版
     * 重启——`su` 不存在、点了无效，还跳过了 [NoRootSupportController.flushBeforeRestart] 落盘
     * （2026-09-30 TB320FC 真机复现）。所以“真 Root 框架”要在 `capable` 之上排除 NPatch——
     * 先按名字，再按框架自己声明的 `PROP_CAP_SYSTEM` 能力位（见 [isRootFramework]，NPatch 改名
     * 也拦得住；属性读取失败时不额外否决）。
     *
     * 只在"免 Root 开关已打开、或正待关闭重启"时才会走免 Root 分支：没开开关的 LSPatch /
     * NPatch 用户 `desiredEnabled=false`，行为不变。带陈旧免 Root 标记的 LSPosed Root 用户
     * 名字与能力位都判为 Root 框架，仍走 Root 重启，与重构前一致。
     */
    fun useNoRootRestartFlow(
        standardCapable: Boolean,
        frameworkName: String,
        desiredEnabled: Boolean,
        displayState: NoRootDisplayState,
        /** 框架属性位；null = 读取失败/未知，保持只看名字的原判据。 */
        frameworkProperties: Long? = null
    ): Boolean {
        if (isRootFramework(standardCapable, frameworkName, frameworkProperties)) return false
        if (desiredEnabled) return true
        return displayState == NoRootDisplayState.DISABLE_RESTART_REQUIRED ||
            displayState == NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE
    }

    fun activationDecision(
        rootActive: Boolean,
        displayState: NoRootDisplayState
    ): ActivationDecision = when {
        rootActive -> ActivationDecision(activated = true, byNoRoot = false)
        displayState == NoRootDisplayState.ACTIVE ||
            displayState == NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE ->
            ActivationDecision(activated = true, byNoRoot = true)
        else -> ActivationDecision(activated = false, byNoRoot = false)
    }

    /**
     * 首页激活卡片的纯状态归并。LSPosed Binder 尚在异步投递时保留“确认中”，但经过
     * 严格版本校验的 NPatch heartbeat 可以立即确认激活，不需要等待 Root 框架结果。
     */
    fun activationDisplayState(
        rootActive: Boolean,
        frameworkCheckPending: Boolean,
        displayState: NoRootDisplayState,
        lspatchFramework: Boolean = false,
        lspatchHostState: LspatchHostReceiptState = LspatchHostReceiptState.WAITING,
        nPatchFramework: Boolean = false
    ): ActivationDisplayState = when {
        // Root 框架的服务证据独立于历史免 Root 设置；NPatch 的作用域和探测结果不能否决它。
        rootActive && !lspatchFramework && !nPatchFramework ->
            ActivationDisplayState.ACTIVE_LSPOSED
        // 作用域缺失时 Manager 仍可能提供 Modern 服务并留下旧回执；它比任何“已激活”
        // 补丁框架断言都更像当前事实，优先于补丁框架的激活结果。
        displayState == NoRootDisplayState.SCOPE_MISSING ->
            ActivationDisplayState.SCOPE_MISSING
        // B12：宿主没有给出存活证据时，不能让 Modern 服务/LSPatch 回执把界面抬回“已激活”。
        displayState == NoRootDisplayState.NOT_RUNNING ->
            ActivationDisplayState.NOT_RUNNING
        displayState == NoRootDisplayState.UNCONFIRMED ->
            ActivationDisplayState.UNCONFIRMED
        displayState == NoRootDisplayState.CONFIRMING ->
            ActivationDisplayState.CONFIRMING
        rootActive && lspatchFramework &&
            lspatchHostState == LspatchHostReceiptState.CONFIRMED ->
            ActivationDisplayState.ACTIVE_LSPATCH
        // 已验证的 NPatch heartbeat 比 LSPatch 的“尚待宿主回执”更强，不能被后者遮住；
        // 标准框架仍保持原有的 rootActive 优先语义。
        rootActive && lspatchFramework &&
            (displayState == NoRootDisplayState.ACTIVE ||
                displayState == NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE) ->
            ActivationDisplayState.ACTIVE_NPATCH
        rootActive && lspatchFramework &&
            lspatchHostState == LspatchHostReceiptState.FAILED ->
            ActivationDisplayState.LSPATCH_HOST_FAILED
        // LSPatch 管理器可在尚未启动已修补宿主时向 companion 交付可写服务。
        // 服务可用只证明发布通道可用，不能证明 B 站已读取配置或已装上 Hook。
        rootActive && lspatchFramework -> ActivationDisplayState.LSPATCH_WAITING_FOR_HOST
        rootActive -> ActivationDisplayState.ACTIVE_LSPOSED
        displayState == NoRootDisplayState.ACTIVE ||
            displayState == NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE ->
            ActivationDisplayState.ACTIVE_NPATCH
        frameworkCheckPending -> ActivationDisplayState.CHECKING
        else -> ActivationDisplayState.UNAVAILABLE
    }

    fun lspatchHostReceiptState(
        configState: HostConfigState?,
        installChainState: HostInstallChainState?
    ): LspatchHostReceiptState = when {
        configState == HostConfigState.ACCEPTED &&
            installChainState == HostInstallChainState.COMPLETED ->
            LspatchHostReceiptState.CONFIRMED
        configState == HostConfigState.REJECTED ||
            configState == HostConfigState.NOT_AUTHORIZED ||
            installChainState == HostInstallChainState.FAILED ->
            LspatchHostReceiptState.FAILED
        else -> LspatchHostReceiptState.WAITING
    }
}
