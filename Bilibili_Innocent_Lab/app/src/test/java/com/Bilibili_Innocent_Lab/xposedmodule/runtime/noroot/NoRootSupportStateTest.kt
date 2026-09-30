package com.Bilibili_Innocent_Lab.xposedmodule.runtime.noroot

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostConfigState
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostInstallChainState
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.frameworkHasSystemCapability
import com.Bilibili_Innocent_Lab.xposedmodule.settings.remote.isRootFramework
import io.github.libxposed.service.XposedService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoRootSupportStateTest {

    @Test
    fun `unsupported OS and disabled intent take precedence`() {
        assertEquals(
            NoRootDisplayState.UNSUPPORTED_OS,
            NoRootSupportState.displayState(
                sdkInt = NoRootSupportState.MIN_SUPPORTED_SDK - 1,
                status = status(desired = true, state = NoRootSupportStore.SyncState.ACTIVE),
                currentSnapshot = snapshot(),
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.DISABLED,
            NoRootSupportState.displayState(
                sdkInt = NoRootSupportState.MIN_SUPPORTED_SDK,
                status = status(desired = false, state = NoRootSupportStore.SyncState.ACTIVE),
                currentSnapshot = snapshot(),
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME
            )
        )
    }

    @Test
    fun `active requires a heartbeat matching the current snapshot`() {
        val snapshot = snapshot()
        val matching = status(
            desired = true,
            state = NoRootSupportStore.SyncState.ACTIVE,
            heartbeatRevision = snapshot.revision,
            heartbeatModuleVersion = snapshot.moduleVersionCode,
            heartbeatTargetVersion = TARGET_VERSION,
            heartbeatTargetUpdateTime = TARGET_UPDATE_TIME,
            heartbeatTargetPackage = NoRootSupportState.TARGET_PACKAGE,
            heartbeatReceivedAt = 1_700_000_000_000L
        )

        assertEquals(
            NoRootDisplayState.ACTIVE,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching.copy(heartbeatRevision = snapshot.revision - 1L),
                snapshot,
                TARGET_VERSION,
                TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                currentSnapshot = null,
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                currentTargetVersionCode = TARGET_VERSION + 1L,
                currentTargetUpdateTime = TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                matching,
                snapshot,
                currentTargetVersionCode = TARGET_VERSION,
                currentTargetUpdateTime = TARGET_UPDATE_TIME + 1L
            )
        )
    }

    @Test
    fun `transport states map without claiming activation`() {
        val expected = mapOf(
            NoRootSupportStore.SyncState.DISABLED to NoRootDisplayState.CHECKING,
            NoRootSupportStore.SyncState.CHECKING to NoRootDisplayState.CHECKING,
            NoRootSupportStore.SyncState.MANAGER_MISSING to NoRootDisplayState.MANAGER_MISSING,
            NoRootSupportStore.SyncState.MODULE_NOT_REGISTERED to
                NoRootDisplayState.MODULE_NOT_REGISTERED,
            NoRootSupportStore.SyncState.SYNCING to NoRootDisplayState.SYNCING,
            NoRootSupportStore.SyncState.RESTART_REQUIRED to
                NoRootDisplayState.RESTART_REQUIRED,
            NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED to
                NoRootDisplayState.DISABLE_RESTART_REQUIRED,
            NoRootSupportStore.SyncState.CONNECTION_TIMEOUT to
                NoRootDisplayState.CONNECTION_TIMEOUT,
            NoRootSupportStore.SyncState.ERROR to NoRootDisplayState.ERROR
        )

        expected.forEach { (syncState, displayState) ->
            assertEquals(
                displayState,
                NoRootSupportState.displayState(
                    sdkInt = 28,
                    status = status(desired = true, state = syncState),
                    currentSnapshot = snapshot(),
                    currentTargetVersionCode = TARGET_VERSION,
                    currentTargetUpdateTime = TARGET_UPDATE_TIME
                )
            )
        }
    }

    @Test
    fun `disable pending only preserves activation after a verified heartbeat`() {
        val pending = status(
            desired = false,
            state = NoRootSupportStore.SyncState.DISABLE_RESTART_REQUIRED
        )
        assertEquals(
            NoRootDisplayState.DISABLE_RESTART_REQUIRED,
            NoRootSupportState.displayState(
                28,
                pending,
                snapshot(),
                TARGET_VERSION,
                TARGET_UPDATE_TIME
            )
        )
        assertEquals(
            NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE,
            NoRootSupportState.displayState(
                28,
                pending.copy(disableWasActive = true),
                snapshot(),
                TARGET_VERSION,
                TARGET_UPDATE_TIME
            )
        )
    }

    @Test
    fun `root activation wins and no-root activates only after heartbeat`() {
        assertEquals(
            ActivationDecision(activated = true, byNoRoot = false),
            NoRootSupportState.activationDecision(
                rootActive = true,
                displayState = NoRootDisplayState.ACTIVE
            )
        )
        assertEquals(
            ActivationDecision(activated = true, byNoRoot = true),
            NoRootSupportState.activationDecision(
                rootActive = false,
                displayState = NoRootDisplayState.ACTIVE
            )
        )
        assertEquals(
            ActivationDecision(activated = true, byNoRoot = true),
            NoRootSupportState.activationDecision(
                rootActive = false,
                displayState = NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE
            )
        )
        assertEquals(
            ActivationDecision(activated = false, byNoRoot = false),
            NoRootSupportState.activationDecision(
                rootActive = false,
                displayState = NoRootDisplayState.DISABLE_RESTART_REQUIRED
            )
        )
        assertEquals(
            ActivationDecision(activated = false, byNoRoot = false),
            NoRootSupportState.activationDecision(
                rootActive = false,
                displayState = NoRootDisplayState.RESTART_REQUIRED
            )
        )
    }

    @Test
    fun `activation card distinguishes framework settling from unavailable`() {
        assertEquals(
            ActivationDisplayState.ACTIVE_LSPOSED,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = true,
                displayState = NoRootDisplayState.ACTIVE
            )
        )
        assertEquals(
            ActivationDisplayState.ACTIVE_NPATCH,
            NoRootSupportState.activationDisplayState(
                rootActive = false,
                frameworkCheckPending = true,
                displayState = NoRootDisplayState.ACTIVE
            )
        )
        assertEquals(
            ActivationDisplayState.CHECKING,
            NoRootSupportState.activationDisplayState(
                rootActive = false,
                frameworkCheckPending = true,
                displayState = NoRootDisplayState.DISABLED
            )
        )
        assertEquals(
            ActivationDisplayState.UNAVAILABLE,
            NoRootSupportState.activationDisplayState(
                rootActive = false,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.DISABLED
            )
        )
    }

    @Test
    fun `LSPatch state requires a receipt and does not override a valid NPatch heartbeat`() {
        assertEquals(
            ActivationDisplayState.LSPATCH_WAITING_FOR_HOST,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.DISABLED,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.WAITING
            )
        )
        assertEquals(
            ActivationDisplayState.ACTIVE_LSPATCH,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.DISABLED,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.CONFIRMED
            )
        )
        assertEquals(
            ActivationDisplayState.LSPATCH_HOST_FAILED,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.DISABLED,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.FAILED
            )
        )
        assertEquals(
            ActivationDisplayState.ACTIVE_NPATCH,
            NoRootSupportState.activationDisplayState(
                rootActive = true,
                frameworkCheckPending = false,
                displayState = NoRootDisplayState.ACTIVE,
                lspatchFramework = true,
                lspatchHostState = LspatchHostReceiptState.WAITING
            )
        )
    }

    @Test
    fun `LSPatch receipt state is bounded by config and install evidence`() {
        assertEquals(
            LspatchHostReceiptState.CONFIRMED,
            NoRootSupportState.lspatchHostReceiptState(
                HostConfigState.ACCEPTED,
                HostInstallChainState.COMPLETED
            )
        )
        assertEquals(
            LspatchHostReceiptState.FAILED,
            NoRootSupportState.lspatchHostReceiptState(
                HostConfigState.REJECTED,
                HostInstallChainState.STARTED
            )
        )
        assertEquals(
            LspatchHostReceiptState.FAILED,
            NoRootSupportState.lspatchHostReceiptState(
                HostConfigState.ACCEPTED,
                HostInstallChainState.FAILED
            )
        )
        assertEquals(
            LspatchHostReceiptState.WAITING,
            NoRootSupportState.lspatchHostReceiptState(
                HostConfigState.ACCEPTED,
                HostInstallChainState.STARTED
            )
        )
    }

    @Test
    fun `NPatch that hands the module a modern service still uses the no-root restart flow`() {
        // 2026-09-30 TB320FC 真机：NPatch v1.0.8 下 capable=true、desired=true，
        // 旧判据 `capable → false` 给出 Root 版重启（su 不存在，点了无效且不落盘）。
        assertTrue(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = true,
                frameworkName = "NPatch",
                desiredEnabled = true,
                displayState = NoRootDisplayState.RESTART_REQUIRED
            )
        )
        // 名称按包含且忽略大小写匹配，与 frameworkManagerTargets 同口径。
        assertTrue(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = true,
                frameworkName = "npatch-manager",
                desiredEnabled = true,
                displayState = NoRootDisplayState.SYNCING
            )
        )
        // 关闭免 Root 后仍待重启：tombstone 同样要经手动重启并落盘。
        for (pending in listOf(
            NoRootDisplayState.DISABLE_RESTART_REQUIRED,
            NoRootDisplayState.DISABLE_RESTART_REQUIRED_ACTIVE
        )) {
            assertTrue(
                NoRootSupportState.useNoRootRestartFlow(
                    standardCapable = true,
                    frameworkName = "NPatch",
                    desiredEnabled = false,
                    displayState = pending
                )
            )
        }
    }

    @Test
    fun `a real root framework keeps the root restart even with a stale no-root flag`() {
        for (name in listOf("LSPosed", "Vector", "Irena")) {
            for (desired in listOf(false, true)) {
                assertFalse(
                    "$name desired=$desired",
                    NoRootSupportState.useNoRootRestartFlow(
                        standardCapable = true,
                        frameworkName = name,
                        desiredEnabled = desired,
                        displayState = NoRootDisplayState.RESTART_REQUIRED
                    )
                )
            }
        }
    }

    @Test
    fun `restart flow without a modern service follows the no-root intent only`() {
        // 旧 NPatch / 无框架服务：capable=false，沿用重构前的行为。
        assertTrue(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = false,
                frameworkName = "",
                desiredEnabled = true,
                displayState = NoRootDisplayState.CHECKING
            )
        )
        assertFalse(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = false,
                frameworkName = "",
                desiredEnabled = false,
                displayState = NoRootDisplayState.DISABLED
            )
        )
        // 已知边界（本条不改）：LSPatch 与“未选免 Root 的 NPatch”仍是 Root 版重启。
        assertFalse(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = true,
                frameworkName = "LSPatch",
                desiredEnabled = false,
                displayState = NoRootDisplayState.DISABLED
            )
        )
        assertFalse(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = true,
                frameworkName = "NPatch",
                desiredEnabled = false,
                displayState = NoRootDisplayState.DISABLED
            )
        )
    }

    @Test
    fun `a renamed no-root framework is still recognised by its missing system capability bit`() {
        // 名字不含 npatch，也没有 PROP_CAP_SYSTEM：只有 CAP_REMOTE，说明不是 Root 框架。
        assertTrue(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = true,
                frameworkName = "SomeRenamedPatch",
                desiredEnabled = true,
                displayState = NoRootDisplayState.RESTART_REQUIRED,
                frameworkProperties = XposedService.PROP_CAP_REMOTE
            )
        )
        // 真 Root 框架声明了 SYSTEM，即使带陈旧免 Root 标记也仍是 Root 版重启。
        assertFalse(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = true,
                frameworkName = "LSPosed",
                desiredEnabled = true,
                displayState = NoRootDisplayState.RESTART_REQUIRED,
                frameworkProperties = XposedService.PROP_CAP_SYSTEM or XposedService.PROP_CAP_REMOTE
            )
        )
        // 属性读取失败（null）= 未知，不额外否决：与只看名字时完全一致。
        assertFalse(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = true,
                frameworkName = "SomeRenamedPatch",
                desiredEnabled = true,
                displayState = NoRootDisplayState.RESTART_REQUIRED,
                frameworkProperties = null
            )
        )
        // 没开免 Root 开关的无 SYSTEM 框架用户不受影响。
        assertFalse(
            NoRootSupportState.useNoRootRestartFlow(
                standardCapable = true,
                frameworkName = "SomeRenamedPatch",
                desiredEnabled = false,
                displayState = NoRootDisplayState.DISABLED,
                frameworkProperties = XposedService.PROP_CAP_REMOTE
            )
        )
    }

    @Test
    fun `root framework truth table combines capability name and system bit`() {
        val system = XposedService.PROP_CAP_SYSTEM or XposedService.PROP_CAP_REMOTE
        val remoteOnly = XposedService.PROP_CAP_REMOTE
        // (capable, name, properties) -> isRootFramework
        assertTrue(isRootFramework(true, "LSPosed", system))
        assertTrue(isRootFramework(true, "LSPosed", null))
        assertFalse(isRootFramework(false, "LSPosed", system))
        assertFalse(isRootFramework(true, "NPatch", system))
        assertFalse(isRootFramework(true, "NPatch", null))
        assertFalse(isRootFramework(true, "SomethingElse", remoteOnly))
        assertNull(frameworkHasSystemCapability(null))
        assertEquals(true, frameworkHasSystemCapability(system))
        assertEquals(false, frameworkHasSystemCapability(remoteOnly))
    }

    private fun snapshot() = NoRootConfigSnapshot(
        schemaVersion = NoRootConfigSnapshotCodec.CURRENT_SCHEMA_VERSION,
        catalogVersion = 3,
        modulePackage = "com.Bilibili_Innocent_Lab.xposedmodule",
        moduleVersionCode = 8L,
        revision = 42L,
        adapterResetRevision = 0L,
        enabled = true,
        values = mapOf("feature.enabled" to true)
    )

    private fun status(
        desired: Boolean,
        state: NoRootSupportStore.SyncState,
        heartbeatRevision: Long = 0L,
        heartbeatModuleVersion: Long = 0L,
        heartbeatTargetVersion: Long = 0L,
        heartbeatTargetUpdateTime: Long = 0L,
        heartbeatTargetPackage: String? = null,
        heartbeatReceivedAt: Long = 0L,
        disableWasActive: Boolean = false
    ) = NoRootSupportStore.Status(
        desiredEnabled = desired,
        syncState = state,
        detail = null,
        syncRevision = 0L,
        heartbeatRevision = heartbeatRevision,
        heartbeatModuleVersion = heartbeatModuleVersion,
        heartbeatTargetVersion = heartbeatTargetVersion,
        heartbeatTargetUpdateTime = heartbeatTargetUpdateTime,
        heartbeatTargetPackage = heartbeatTargetPackage,
        heartbeatReceivedAt = heartbeatReceivedAt,
        disableWasActive = disableWasActive
    )

    private companion object {
        const val TARGET_VERSION = 9_090_300L
        const val TARGET_UPDATE_TIME = 1_700_000_000_000L
    }
}
