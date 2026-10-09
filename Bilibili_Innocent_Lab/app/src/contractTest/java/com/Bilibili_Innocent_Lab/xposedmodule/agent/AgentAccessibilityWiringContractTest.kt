package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class AgentAccessibilityWiringContractTest {
    @Test fun readingModuleLogsWaitsWithoutReplayingThePreviousUiAction() {
        val controller = SourceContract.read("agent/AgentController.kt")
        val boundary = controller.after("val visibleResponse =").before("private fun leaseUntil(")
        assertTrue(boundary.contains("AgentAccessibilityService.Foreground.MODULE"))
        assertTrue(boundary.contains("AgentAccessibilityService.clear(task.id)"))
        assertTrue(boundary.contains("while (authorized(task)"))
        assertTrue(boundary.contains("\"ui_snapshot_stale\""))
        assertFalse(boundary.contains("AgentAccessibilityService.request"))
    }
    @Test fun everyUiActionHasTaskWindowAndFreshTargetChecks() {
        val service = SourceContract.read("agent/AgentAccessibilityService.kt")
        assertTrue(service.contains("AgentController.actionAuthorized(task)"))
        assertTrue(service.contains("it.isActive && it.isFocused"))
        assertTrue(service.contains("root.packageName?.toString() != AgentWire.TARGET_PACKAGE"))
        assertTrue(service.contains("old.task == task && old.id == args.optString(\"snapshot_id\")"))
        assertTrue(service.contains("now.signature == previous.signature"))
        assertTrue(service.contains("AgentUiPolicy.protectedControl"))
        assertTrue(service.contains("AgentUiPolicy.mayClick(target.label, target.protected)"))
        assertTrue(service.contains("check(now.enabled && !now.protected)"))
        assertFalse(service.contains("ACTION_ACCESSIBILITY_SETTINGS"))
    }
    @Test fun oldAndroidDoesNotEnterNewApiOrOverlayOnModernAndroid() {
        val service = SourceContract.read("agent/AgentSessionService.kt")
        assertTrue(service.contains("if (!AgentUiPolicy.systemNotification(Build.VERSION.SDK_INT))"))
        assertTrue(service.contains("startForeground(AgentTaskNotification.ID"))
        assertTrue(service.contains("AgentController.removeObserver(observer)"))
        val notification = SourceContract.read("agent/ui/AgentTaskNotification.kt")
        assertTrue(notification.contains("if (Build.VERSION.SDK_INT >= 36) AgentPromotedNotification.configure"))
        assertTrue(notification.contains("manager.canPostPromotedNotifications()"))
        assertTrue(notification.contains("PendingIntent.FLAG_IMMUTABLE"))
        assertFalse(notification.contains("RemoteViews"))
    }
    @Test fun screenshotsDoNotContainOtherAppWindowsAndExpiredCallbacksCannotPublish() {
        val capture = SourceContract.read("agent/AgentAccessibilityService.kt").after("private fun screenshot(").before("companion object")
        assertTrue(SourceContract.read("agent/AgentAccessibilityService.kt").contains("if (Build.VERSION.SDK_INT >= 34) screenshot"))
        assertTrue(capture.contains("takeScreenshotOfWindow(shot.tree.window"))
        assertTrue(capture.contains("check(!result.isDone)"))
        assertTrue(capture.contains("!tree.privateInput"))
        assertTrue(capture.contains("frame.contains(tree.bounds)"))
        assertTrue(capture.contains("buffer.close()"))
        val service = SourceContract.read("agent/AgentAccessibilityService.kt")
        assertTrue(service.contains("renderer.contains(\"WebView\", true)"))
        assertTrue(service.contains("renderer.contains(\"ComposeView\", true)"))
    }
}
