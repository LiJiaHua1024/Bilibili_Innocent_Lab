package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.app.Dialog
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView as NativeTextView
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.TextView

private fun MainActivity.glassAppearance(bottom: Boolean) =
    if (bottom) hostBottomBarGlassAppearance else hostTopBarGlassAppearance

private fun materialKey(bottom: Boolean) = if (bottom) FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL else FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL
private fun rendererKey(bottom: Boolean) = if (bottom) FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER else FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER
private fun materialLabel(material: HostGlassMaterial) = if (material == HostGlassMaterial.SOFT) R.string.host_glass_soft else R.string.host_glass_liquid
private fun rendererLabel(renderer: HostGlassRenderer) = if (renderer == HostGlassRenderer.AUTO) R.string.host_glass_auto else R.string.host_glass_software

@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.hostGlassConfigurationRows(performer: Hikage.Performer<LinearLayout.LayoutParams>, bottom: Boolean): () -> Unit {
    lateinit var material: NativeTextView
    lateinit var renderer: NativeTextView
    fun refresh() {
        val appearance = glassAppearance(bottom)
        val enabled = if (bottom) hostBottomBarLiquidGlass else hostTopBarLiquidGlass
        material.text = getString(R.string.host_glass_material_current, getString(materialLabel(appearance.material)))
        renderer.text = getString(R.string.host_glass_renderer_current, getString(rendererLabel(appearance.compatible(Build.VERSION.SDK_INT).effectiveRenderer)))
        listOf(material, renderer).forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else .45f }
    }
    with(performer) {
        TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 6.dp }) {
            material = this
            bindSettingDestination(this, materialKey(bottom))
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(0, 12.dp, 0, 12.dp)
            background = selfRippleBackground(10f)
            isClickable = true; isFocusable = true
            setOnClickListener { showHostGlassChoiceDialog(this, bottom, rendering = false, onSaved = ::refresh) }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            renderer = this
            bindSettingDestination(this, rendererKey(bottom))
            textColor = colorResource(R.color.colorTextGray)
            textSize = 13f
            setPadding(0, 10.dp, 0, 10.dp)
            background = selfRippleBackground(10f)
            isClickable = true; isFocusable = true
            setOnClickListener { showHostGlassChoiceDialog(this, bottom, rendering = true, onSaved = ::refresh) }
        }
    }
    refresh()
    return ::refresh
}

private fun MainActivity.showHostGlassChoiceDialog(anchor: View, bottom: Boolean, rendering: Boolean, onSaved: () -> Unit) {
    val appearance = glassAppearance(bottom)
    val liquid = appearance.material == HostGlassMaterial.LIQUID && Build.VERSION.SDK_INT >= 33
    val density = resources.displayMetrics.density
    val dialog = Dialog(this).also { installDialogElasticInteraction(it) }
    val container = createModalContainer()
    container.addView(NativeTextView(this).apply {
        text = getString(if (rendering) R.string.host_glass_renderer else R.string.host_glass_material)
        textSize = 17f; textColor = getColor(R.color.colorTextDark)
    }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * density).toInt() })
    fun save(next: HostChromeAppearance) {
        runCatching {
            prefs().edit {
                putString(materialKey(bottom), next.material.value)
                putString(rendererKey(bottom), next.renderer.value)
            }
        }.onSuccess {
            if (bottom) hostBottomBarGlassAppearance = next else hostTopBarGlassAppearance = next
            onSaved()
            dismissWithAnimation(dialog, container) {}
        }.onFailure { Log.e("BilibiliInnocentLab", "write host glass appearance failed", it) }
    }
    if (rendering) {
        HostGlassRenderer.entries.forEach { option ->
            val available = option != HostGlassRenderer.SOFTWARE || !liquid
            val subtitle = if (!available) R.string.host_glass_liquid_gpu_required
                else if (option == HostGlassRenderer.AUTO) R.string.host_glass_auto_tip else R.string.host_glass_software_tip
            container.addView(createGitHubMenuRow(getString(rendererLabel(option)), getString(subtitle),
                appearance.compatible(Build.VERSION.SDK_INT).effectiveRenderer == option) {
                if (available) save(if (liquid) appearance else appearance.copy(renderer = option))
            }.apply {
                isEnabled = available; alpha = if (available) 1f else .45f
            }, LinearLayout.LayoutParams(-1, -2))
        }
    } else {
        HostGlassMaterial.entries.forEach { option ->
            val available = option != HostGlassMaterial.LIQUID || Build.VERSION.SDK_INT >= 33
            val subtitle = if (!available) R.string.host_glass_liquid_unavailable
                else if (option == HostGlassMaterial.SOFT) R.string.host_glass_soft_tip else R.string.host_glass_liquid_tip
            container.addView(createGitHubMenuRow(getString(materialLabel(option)), getString(subtitle),
                appearance.material == option) { if (available) save(appearance.copy(material = option)) }.apply {
                isEnabled = available; alpha = if (available) 1f else .45f
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }
    container.addView(NativeTextView(this).apply {
        text = getString(R.string.host_glass_apply_tip)
        textSize = 12f; textColor = getColor(R.color.colorTextGray)
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * density).toInt() })
    container.addView(createGitHubMenuRow(getString(R.string.dialog_close), "", false) {
        dismissWithAnimation(dialog, container) {}
    }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * density).toInt() })
    presentModalDialog(dialog, container, anchor)
}
