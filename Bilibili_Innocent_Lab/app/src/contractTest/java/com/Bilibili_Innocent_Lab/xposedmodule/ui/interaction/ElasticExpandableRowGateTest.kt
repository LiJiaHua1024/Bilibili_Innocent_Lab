package com.Bilibili_Innocent_Lab.xposedmodule.ui.interaction

import com.Bilibili_Innocent_Lab.xposedmodule.contract.LceMotionSource

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before

/**
 * 可展开标题（实验性功能的「外观」「兼容」、净化/增强进阶的折叠卡标题）必须参与
 * 全局长按弹性。它们曾被打上 `EXCLUDED_TAG`，长按只剩原生涟漪（白色遮罩）与
 * "松手即展开"，全局拖动形变与高光在这类入口上静默失效——与「净化/增强进阶
 * 设置」两个同型入口的行为不一致。
 *
 * 折叠/展开本身不受影响：轻点时控制器在派发 UP 之前先恢复几何，点击照常触发；
 * 长按后拖动则原生流收到 CANCEL，涟漪退场且不触发展开。
 */
class ElasticExpandableRowGateTest {

    @Test fun appearanceHeaderParticipatesInTheElasticGesture() {
        val block = windowBefore(
            "setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.APPEARANCE) }")
        assertFalse(block.contains("EXCLUDED_TAG"))
        assertTrue(block.contains("background = selfRippleBackground(10f)"))
    }

    @Test fun compatibilityHeaderParticipatesInTheElasticGesture() {
        val block = windowBefore(
            "setOnClickListener { toggleSecondaryMenu(SettingsSearchSection.COMPATIBILITY) }")
        assertFalse(block.contains("EXCLUDED_TAG"))
        assertTrue(block.contains("background = selfRippleBackground(10f)"))
    }

    @Test fun advancedCategoryHeaderParticipatesInTheElasticGesture() {
        val block = main().after("val header = NativeLinearLayout(this).apply {")
            .take(560)
        assertFalse(block.contains("EXCLUDED_TAG"))
        assertTrue(block.contains("foreground = selfRippleBackground(12f)"))
    }

    /** 排除机制本身要保住：只剩角标不独占手势与 scrub 条自管触摸两处。 */
    @Test fun gestureOwnersStayExcluded() {
        assertEquals(2, main().split("EXCLUDED_TAG").size - 1)
        val badge = main().after("githubUpdateBadge = this")
            .before("visibility = View.INVISIBLE")
        assertTrue(badge.contains("ElasticInteractionController.EXCLUDED_TAG"))
    }

    /**
     * 全向可拖：WRAP_CONTENT 父容器里最后一个子元素的尾边间隙恒为 0，不补行程预算
     * 会把该方向（典型如向下）整体钳死——真机实证「兼容」卡只能上/左/右。
     * 预算必须由控制器传进去，且取的是弹性行程上限本身。
     */
    @Test fun dragKeepsAFullTravelBudgetInEveryDirection() {
        val body = interaction().after("private fun dragTo(")
            .before("\n    }")
        assertTrue(body.contains("clampToParent("))
        assertTrue(body.contains("gapLeft, gapTop, gapRight, gapBottom, drag)"))
        val capture = interaction().after("private fun captureGroupGaps(").before("private fun validGeometry()")
        assertTrue(capture.contains("policy == ElasticTravelPolicy.PARENT_BOUNDS"))
        assertTrue(capture.contains("Float.POSITIVE_INFINITY, limit, 0f, policy"))
        val host = LceMotionSource.read("ui/skin/activity/SkinnedActivity.kt")
        assertEquals(2, host.split("travelPolicy = com.lumen.coacervation.engine.interaction.ElasticTravelPolicy.PARENT_BOUNDS").size - 1)
    }

    /**
     * 「模块已激活」整卡要参与弹性：它原先不可点击，命中测试只能落到右侧诊断按钮上，
     * 卡面其余区域长按无高光无拖动。
     * 2026-09-24 用户要求：轻点整卡不再打开统一功能诊断，入口只有右侧按钮。
     */
    @Test fun activationCardParticipatesInTheElasticGesture() {
        val block = main().after("// 首次绘制使用中性确认态").take(820)
        assertFalse(block.contains("EXCLUDED_TAG"))
        assertTrue(block.contains("isClickable = true"))
        assertFalse("整卡不能再跳转诊断", block.contains("launchDiagnostics()"))
        assertFalse(block.contains("setOnClickListener"))
        // 诊断入口只剩右侧按钮这一处。
        assertEquals(1, Regex(Regex.escape("setOnClickListener { launchDiagnostics() }")).findAll(main()).count())
        assertTrue(block.contains("selfRippleBackground(ActivationCardVisualSpec.CORNER_RADIUS_DP)"))
    }

    /** 状态渲染不能把整卡前景的涟漪冲掉：未激活态的 accent 光晕只叠在涟漪之上。 */
    @Test fun activationRendererKeepsTheRippleUnderTheAccent() {
        val render = main().after("activationCardView?.apply {")
            .before("val activationContentColor")
        assertTrue(render.contains("selfRippleBackground(ActivationCardVisualSpec.CORNER_RADIUS_DP)"))
        assertTrue(render.contains("ActivationCardAccentDrawable(accentColor"))
        assertFalse(render.contains("foreground = if (activated) null"))
    }

    /**
     * 高光圆角的唯一来源：`RippleDrawable.getOutline()` 只报第一个非 mask 层，
     * content 若是无圆角的透明层就会报出 radius=0 的直角轮廓，高光随之按方角裁剪
     * （真机实证：弹窗行的高光是完全矩形，与精心设计的圆角涟漪边缘割裂）。
     */
    @Test fun rippleContentLayerDeclaresTheDesignCorner() {
        val body = main().after("internal fun selfRippleBackground(")
            .before("\n    }\n")
        assertTrue(body.contains("val content = GradientDrawable()"))
        assertTrue(body.contains("val mask = GradientDrawable()"))
        assertFalse(body.contains("ColorDrawable(Color.TRANSPARENT)"))
        // content 与 mask 各写一次圆角（右边变量名不限，只数行首赋值次数；
        // 注释里提到同一字样不算）。
        // 别把局部 val 命名成 cornerRadius——apply 里会遮蔽 GradientDrawable 的
        // 同名属性，报 "'val' cannot be reassigned"。
        assertEquals(2, body.lines().count { it.trimStart().startsWith("cornerRadius = ") })
    }

    /** 诊断页的自绘涟漪同款：content 与 mask 都要声明圆角。 */
    @Test fun diagnosticsRippleDeclaresTheDesignCorner() {
        val body = diagnostics().after("private fun rippleBackground(")
            .before("\n    }\n")
        assertFalse(body.contains("toDrawable()"))
        assertEquals(2, body.lines().count { it.trimStart().startsWith("cornerRadius = ") })
    }

    private fun windowBefore(anchor: String): String =
        main().before(anchor).takeLast(360)

    private fun main(): String = source(
        "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/MainActivity.kt")

    private fun interaction(): String = source(
        "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/interaction/ElasticInteractionController.kt")

    private fun diagnostics(): String = source(
        "src/main/java/com/Bilibili_Innocent_Lab/xposedmodule/ui/activity/DiagnosticsActivity.kt")

    // 归一化 CRLF：autocrlf 检出会把源码写成 CRLF，substringBefore("\n    }\n")
    // 这类按行锚定的切片会失配、静默退化成整文件尾部（assertTrue 假性通过）。
    private fun source(relative: String): String =
        LceMotionSource.read(relative).replace("\r\n", "\n")

    /**
     * 中性涟漪随主题（2026-09-24 用户报告：浅色下展开/收起条目时开头结尾压一层黑色遮罩；
     * 真机录屏确认是涟漪，标题行亮度 229.8 → 203.7）。浅色用白色提亮，深色沿用浅灰。
     */
    @Test fun neutralRippleBrightensInLightTheme() {
        val ripple = main().after("internal fun selfRippleBackground(").before("internal fun renderPendingTermsUi(")
        assertTrue(ripple.contains("val darkTheme = ColorUtils.calculateLuminance(monetColors.surface) < 0.5"))
        assertTrue(ripple.contains("ColorUtils.setAlphaComponent(Color.WHITE, LIGHT_THEME_RIPPLE_ALPHA)"))
        assertTrue(ripple.contains("return CoverableRippleDrawable(rippleColor, content, mask)"))
    }
}
