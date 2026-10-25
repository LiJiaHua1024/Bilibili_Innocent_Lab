package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class FrameThermalControlContractTest {
    @Test fun `the confirmed preference reaches both player processes with protection on by default`() {
        val entry = SourceContract.read("hook/HookEntry.kt").after("FrameInterpolationFeatureInstaller(")
            .before("featureInstallCoordinator.installAll(")
        assertTrue(entry.contains("disableThermalControl = prefs.getBoolean(FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL, false)"))
        val installer = SourceContract.read("hook/feature/interpolation/FrameInterpolationFeatureInstaller.kt")
        assertTrue(installer.contains("private val disableThermalControl: Boolean = false"))
        assertTrue(installer.contains("override fun thermalControlEnabled(): Boolean = !disableThermalControl"))
        val bridge = SourceContract.read("hook/feature/interpolation/FrameCaptureBridge.kt")
        assertTrue(bridge.contains("fun thermalControlEnabled(): Boolean = true"))
        val temperature = bridge.after("// 温控：").before("session.tick(now)")
        assertTrue(temperature.contains("FrameThermalControl.pauseHeadroom(host.thermalControlEnabled()) { power?.thermalHeadroom(now) }"))
    }

    @Test fun `a switch request rolls back before confirming and only confirmation enables bypass`() {
        val ui = SourceContract.read("ui/activity/PlayerFrameInterpolationPresenter.kt")
            .after("val thermalKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_DISABLE_THERMAL_CONTROL")
            .before("val statusKey = FeaturePreferences.PLAYER_FRAME_INTERPOLATION_STATUS_TOAST")
        val listener = ui.after("setOnCheckedChangeListener { button, checked ->")
        assertTrue(listener.indexOf("button.isChecked = savedDisabled") < listener.indexOf("showFrameInterpolationThermalConfirmDialog"))
        assertTrue(listener.contains("if (checked) showFrameInterpolationThermalConfirmDialog(button) { saveThermalDisabled(true) }"))
        assertTrue(listener.contains("else saveThermalDisabled(false)"))
        val dialog = SourceContract.read("ui/activity/PlayerPickerDialogs.kt")
            .after("internal fun MainActivity.showFrameInterpolationThermalConfirmDialog(")
            .before("/** apply()")
        val cancel = dialog.after("R.string.dialog_cancel").before("R.string.player_frame_thermal_confirm_accept")
        assertFalse(cancel.contains("onConfirm()"))
        val confirm = dialog.after("R.string.player_frame_thermal_confirm_accept").before("container.addView(buttons)")
        assertTrue(confirm.contains("dismissWithAnimation(dialog, container) { onConfirm() }"))
        assertFalse(dialog.contains("putBoolean("))
    }
}
