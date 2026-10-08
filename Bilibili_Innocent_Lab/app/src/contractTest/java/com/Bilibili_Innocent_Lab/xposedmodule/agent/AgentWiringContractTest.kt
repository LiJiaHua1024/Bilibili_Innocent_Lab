package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

/** Android 进程与 UI 无法由 JVM 替身证明；这些契约锁定易被搬动的授权与生命周期接线。 */
class AgentWiringContractTest {
    @Test fun permissionRollbackUsesThePreferenceSnapshotRatherThanAStaleCheckbox() {
        val dialog = SourceContract.read("ui/activity/AgentDialogs.kt")
        val change = dialog.after("setOnCheckedChangeListener").before("body.addView(enabled)")
        assertTrue(change.contains("val previousEnabled = preferences.getBoolean(AgentPreferences.ENABLED, false)"))
        assertTrue(change.contains("AgentPreferences.writeEnabled(previousEnabled, checked)"))
        assertTrue(change.contains("button.isChecked = previousEnabled"))
        assertFalse(change.contains("writeEnabled(!checked"))
    }
    @Test fun hostBridgeOnlyInitializesAfterAuthorizedInstallAndRetainsApplicationFallback() {
        val hook = SourceContract.read("hook/HookEntry.kt")
        val install = hook.after("fun performAuthorizationAndInstall(").before("fun authorizeAndInstall(")
        assertTrue(install.indexOf("if (!config.authorized)") < install.indexOf("HostAgentRuntime.initialize"))
        assertTrue(install.contains("processName == TARGET_PACKAGE && config.values["))
        val fallback = hook.after("\"callApplicationOnCreate\"").before("callApplicationOnCreate 授权 bootstrap 注册失败")
        assertTrue(fallback.contains("authorizedHooksInstalled.get()"))
        assertTrue(fallback.contains("AgentPreferences.ENABLED, false"))
        assertTrue(fallback.contains("HostAgentRuntime.initialize(application, biliClassLoader)"))
    }
    @Test fun commandsUseDedicatedBinderWhileReceiptsKeepTheirExistingQueryAllowlist() {
        val receipts = SourceContract.read("runtime/HostReceiptHost.kt")
        assertTrue(receipts.contains("channel != HostReceiptWire.DIAGNOSTICS && channel !in MineComponentSnapshotCodec.ALLOWED_SURFACES"))
        assertTrue(receipts.contains("AgentWire.ENDPOINT_KEY"))
        val client = SourceContract.read("agent/AgentHostClient.kt")
        assertTrue(client.contains("HostReceiptRegistry.isCurrent(current.session, getCallingUid())"))
        assertTrue(client.contains("data.readString() != taskId || data.readLong() != sequence"))
        assertFalse(client.contains("sendBroadcast"))
    }
    @Test fun permissionsAreRecheckedAtActionBoundaryAndServiceDestructionIsTaskScoped() {
        val runner = SourceContract.read("agent/AgentController.kt")
        val boundary = runner.after("private fun host(").before("private fun completeClose(")
        assertTrue(boundary.contains("check(authorized(task))"))
        assertTrue(runner.contains("registerOnSharedPreferenceChangeListener"))
        assertTrue(runner.contains("UserTermsAuthorizationCoordinator.addListener(termsListener)"))
        assertTrue(runner.contains("UserTermsAuthorizationCoordinator.removeListener(termsListener)"))
        val service = SourceContract.read("agent/AgentSessionService.kt")
        assertTrue(service.contains("ownerTaskId?.takeIf(AgentController::owns)"))
        assertTrue(service.contains("START_NOT_STICKY"))
    }
    @Test fun visionIsASeparateReadOnlyModelRequestAndDoesNotEnterPlannerHistory() {
        val runner = SourceContract.read("agent/AgentController.kt")
        assertTrue(runner.contains("data?.remove(\"image_data_url\")"))
        assertTrue(runner.contains("requireTools = false"))
        assertTrue(runner.contains("visionMessages, JSONArray(), true"))
        assertTrue(runner.contains("AgentToolCatalog.tools(canSee), false"))
        assertFalse(runner.contains("messages.put(visionMessages"))
        assertFalse(runner.contains("imageInHistory"))
    }
}
