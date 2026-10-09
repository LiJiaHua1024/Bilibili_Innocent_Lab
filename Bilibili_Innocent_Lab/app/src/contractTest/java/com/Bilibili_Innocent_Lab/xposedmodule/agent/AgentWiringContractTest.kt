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
        val image = runner.after("val image =").before("models.record(call, response)")
        assertTrue(image.after("data?.remove(\"image_data_url\")").contains("models.inspect(image, response)"))
        val cooperation = SourceContract.read("agent/AgentCooperation.kt")
        val inspect = cooperation.after("fun inspect(").before("private fun decisionTurn(")
        assertTrue(inspect.contains("request<Auxiliary>(AgentModelRole.VISION, route"))
        assertTrue(inspect.contains("chat.generate(source, messages, JSONArray(), true, timeout, cancelled)"))
        assertTrue(cooperation.contains("AgentToolCatalog.tools(vision), false"))
        val history = SourceContract.read("agent/AgentConversation.kt")
        assertTrue(history.contains("it != \"image_data_url\""))
        assertTrue(history.contains("value.contains(\"data:image/\", true)"))
    }
    @Test fun allProtocolsRetainSourceIdentityAndUseSharedRoleHealthWithinTaskAuthorization() {
        val preferences = SourceContract.read("agent/AgentPreferences.kt")
        val sources = preferences.after("fun sources(").before("fun selected(")
        assertTrue(sources.contains("AgentSourceProtocol.DECISIONS else AgentSourceProtocol.CHAT"))
        assertFalse(sources.contains("if (kind == SemanticBackend.JEV) return@mapNotNull null"))
        assertTrue(preferences.contains("CAPABILITY_TTL_MS = AgentModelCapabilities.VALID_FOR_MS"))
        val cooperation = SourceContract.read("agent/AgentCooperation.kt")
        assertTrue(cooperation.contains("AgentSourceRouter(sources, caps, health)"))
        assertTrue(cooperation.contains("health: AgentHealthRegistry = AgentModelRuntime.health"))
        assertTrue(cooperation.contains("AgentModelRuntime.chatClient"))
        assertTrue(cooperation.contains("AgentModelRuntime.decisionClient"))
        assertTrue(cooperation.contains("AgentRoutePolicy(route.allowedSources, allowFallback = route.allowFallback)"))
        val router = SourceContract.read("agent/model/AgentSourceRouter.kt")
        assertTrue(router.contains("source.index in route.allowedSources"))
        assertTrue(router.contains("role != AgentModelRole.PLANNER || route.fixedIndex == null"))
        assertTrue(router.contains("source.protocol == AgentSourceProtocol.CHAT && (capability.tools || capability.plainPlanning)"))
        assertTrue(router.contains("source.protocol == AgentSourceProtocol.DECISIONS && capability.decisions"))
    }
    @Test fun unlimitedUserBudgetsKeepFiniteCommandLeasesAndBoundedConversation() {
        val runner = SourceContract.read("agent/AgentController.kt")
        assertTrue(runner.contains("task.limits.allowsStep(steps)"))
        assertTrue(runner.contains("limits.timeExceeded(startedAt, SystemClock.elapsedRealtime())"))
        assertTrue(runner.contains("task.deadline(AgentWire.IPC_TIMEOUT_MS)"))
        assertTrue(runner.contains("task.deadline(AgentWire.MAX_LEASE_MS)"))
        assertTrue(runner.contains("host(task, \"renew\""))
        assertTrue(runner.contains("checkpoint = { renew(task);"))
        assertTrue(runner.contains("AgentConversation(AgentToolCatalog.SYSTEM, task.goal)"))
        assertTrue(runner.contains("history.acceptCallId(call.id)"))
        assertTrue(runner.contains("history.append(turn, response, models.plannerFingerprint)"))
        assertTrue(runner.contains("conversation?.clear()"))
        assertTrue(runner.contains("cooperation?.clear()"))
        val host = SourceContract.read("agent/host/HostAgentRuntime.kt")
        assertTrue(host.contains("HostAgentSession(AgentWire.MAX_LEASE_MS, AgentWire.IPC_TIMEOUT_MS)"))
        assertTrue(host.contains("session.renew(task, sequence, deadline, arguments.getLong(\"lease_until\"), now())"))
    }
    @Test fun taskBudgetAndFallbackControlsAreValidatedSavedAndLockedDuringExecution() {
        val dialog = SourceContract.read("ui/activity/AgentDialogs.kt")
        assertTrue(dialog.contains("seconds !in 0L..Long.MAX_VALUE / 1000L"))
        assertTrue(dialog.contains("AgentTaskLimits(seconds * 1000L, stepLimit)"))
        assertTrue(dialog.contains("saveSelection(activity, chosen, fixedIndex, vision.isChecked, limits, fallback.isChecked)"))
        assertTrue(dialog.contains("start(activity, goal.textToString(), chosen, fixedIndex, vision.isChecked, limits, fallback.isChecked)"))
        assertTrue(dialog.contains("taskInputs.forEach { it.isEnabled = !state.running }"))
        assertTrue(dialog.contains("state.maximumSteps == 0L"))
        assertTrue(dialog.contains("AgentModelRuntime.chatClient"))
        assertTrue(dialog.contains("AgentModelRuntime.decisionClient"))
        assertTrue(dialog.contains("if (probing.get())"))
    }
    @Test fun terminalCommunicationCannotSkipLocalTaskCleanup() {
        val runner = SourceContract.read("agent/AgentController.kt")
        val cancellation = runner.after("@Synchronized fun cancel(").before("private fun run(")
        assertTrue(cancellation.contains("finally { completeClose(context.applicationContext, task"))
        assertTrue(cancellation.contains(".onFailure { completeClose(context.applicationContext, task"))
        val cleanup = runner.after("preferences.unregisterOnSharedPreferenceChangeListener").before("private fun authorized(")
        assertTrue(cleanup.contains("finally { completeClose(context, task) }"))
        assertFalse(runner.contains("ThreadPoolExecutor.DiscardPolicy"))
        val close = runner.after("private fun completeClose(").before("private fun publish(")
        assertTrue(close.contains("if (task.cancelled.get()) AgentTaskState(phase = \"cancelled\""))
        assertTrue(close.contains(".copy(running = false, maximumSteps = task.limits.maximumSteps)"))
    }
}
