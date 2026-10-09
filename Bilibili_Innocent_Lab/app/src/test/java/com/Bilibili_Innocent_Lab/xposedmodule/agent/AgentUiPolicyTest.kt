package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentUiPolicyTest {
    @Test fun delayedOrCancelledOperationsCannotAcquireActionPermission() {
        assertTrue(AgentUiPolicy.mayAct(true, false, 100, 101))
        assertFalse(AgentUiPolicy.mayAct(true, false, 101, 101))
        assertFalse(AgentUiPolicy.mayAct(true, true, 100, 101))
        assertFalse(AgentUiPolicy.mayAct(false, false, 100, 101))
    }
    @Test fun sensitiveAccountAndFinancialControlsAreDenied() {
        listOf("修改密码", "更改实名", "确认付款 ¥30", "开通大会员", "更换手机", "输入验证码", "银行卡",
            "change password", "Checkout", "Renew subscription", "delete account", "OTP", "CVV", "PIN", "手机号码").forEach {
            assertTrue(it, AgentUiPolicy.sensitiveControl(it))
        }
        listOf("搜索", "播放视频", "查看合集", "点赞", "返回").forEach { assertFalse(it, AgentUiPolicy.sensitiveControl(it)) }
    }
    @Test fun emptyClickableWrapperCannotHideProtectedDescendant() {
        assertTrue(AgentUiPolicy.protectedControl("", listOf("立即付款"), false))
        assertTrue(AgentUiPolicy.protectedControl("下一步", listOf("请输入身份证"), false))
        assertTrue(AgentUiPolicy.protectedControl("搜索", emptyList(), true))
        assertFalse(AgentUiPolicy.protectedControl("", listOf("执行搜索"), false))
        assertFalse(AgentUiPolicy.mayClick("", false))
        assertFalse(AgentUiPolicy.mayClick("执行搜索", true))
        assertTrue(AgentUiPolicy.mayClick("执行搜索", false))
    }
    @Test fun inputIsPlainBoundedAndNeverCredentialMaterial() {
        assertTrue(AgentUiPolicy.allowInput("搜索", false, "黑神话悟空 官方演示"))
        assertFalse(AgentUiPolicy.allowInput("密码", false, "hello"))
        assertFalse(AgentUiPolicy.allowInput("", true, "hello"))
        assertFalse(AgentUiPolicy.allowInput("", false, "6217000000000000000"))
        assertFalse(AgentUiPolicy.allowInput("", false, "Bearer abc"))
        assertFalse(AgentUiPolicy.allowInput("", false, "x".repeat(501)))
    }
    @Test fun unlabeledFinancialConfirmationAndStaticCredentialsRemainProtected() {
        assertTrue(AgentUiPolicy.sensitiveConfirmation("确定", "金额 ￥30.00"))
        assertTrue(AgentUiPolicy.sensitiveConfirmation("Confirm", "$ 19.99"))
        assertFalse(AgentUiPolicy.sensitiveConfirmation("返回", "金额 ￥30"))
        assertFalse(AgentUiPolicy.sensitiveConfirmation("确定", "搜索关键词"))
        assertEquals("[受保护内容]", AgentUiPolicy.publicText("身份证 110101199001011234"))
        assertEquals("[受保护内容]", AgentUiPolicy.publicText("手机 13800138000"))
        assertEquals("搜索视频", AgentUiPolicy.publicText("搜索视频"))
    }
    @Test fun windowAgeAndTargetAllHaveToMatch() {
        assertTrue(AgentUiPolicy.snapshotCurrent(3, 3, 20_000, true))
        assertFalse(AgentUiPolicy.snapshotCurrent(3, 4, 1, true))
        assertFalse(AgentUiPolicy.snapshotCurrent(3, 3, 60_001, true))
        assertFalse(AgentUiPolicy.snapshotCurrent(3, 3, -1, true))
        assertFalse(AgentUiPolicy.snapshotCurrent(3, 3, 1, false))
    }
    @Test fun promotionUsesSystemRouteAndOnlyOlderVersionsUseOverlay() {
        assertFalse(AgentUiPolicy.systemNotification(27))
        assertFalse(AgentUiPolicy.systemNotification(35))
        assertTrue(AgentUiPolicy.systemNotification(36))
        assertTrue(AgentUiPolicy.systemNotification(37))
    }
    @Test fun toolCoordinatesAndSnapshotsCannotEscapeTheirStrictShape() {
        val snapshot = "12345678-1234-1234-1234-123456789abc"
        val tap = JSONObject().put("snapshot_id", snapshot).put("x", "500").put("y", "700")
        assertTrue(AgentToolCatalog.valid("tap_ui", tap, true))
        assertFalse(AgentToolCatalog.valid("tap_ui", tap, false))
        assertFalse(AgentToolCatalog.valid("tap_ui", JSONObject(tap.toString()).put("x", "-1"), true))
        assertFalse(AgentToolCatalog.valid("tap_ui", JSONObject(tap.toString()).put("x", "NaN"), true))
        assertFalse(AgentToolCatalog.valid("tap_ui", JSONObject(tap.toString()).put("y", "1000"), true))
        assertFalse(AgentToolCatalog.valid("click_ui", JSONObject().put("snapshot_id", snapshot).put("node_id", "../../secret"), false))
        assertFalse(AgentToolCatalog.valid("press_back", JSONObject().put("snapshot_id", "old"), false))
    }
}
