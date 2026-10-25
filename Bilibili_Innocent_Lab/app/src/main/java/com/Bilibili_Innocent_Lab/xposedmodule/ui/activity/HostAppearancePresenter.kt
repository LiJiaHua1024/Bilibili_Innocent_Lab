@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.util.Log
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostCardMaterial
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch

// 宿主美化设置的界面分卷；状态和收藏身份仍由当前 MainActivity 管理。

internal fun MainActivity.loadHostCardSettings(settings: com.Bilibili_Innocent_Lab.xposedmodule.settings.ModuleUiSettings) {
    hostVideoCards = settings.bool(FeaturePreferences.HOST_VIDEO_CARDS)
    hostCardMaterial = HostCardMaterial.parse(settings.string(FeaturePreferences.HOST_CARD_MATERIAL))
    hostHomeCardHorizontalDp = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec.normalize(
        settings.int(FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP))
    hostHomeCardVerticalDp = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostHomeCardSpacingSpec.normalize(
        settings.int(FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP))
    hostVideoCardRadiusDp = com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.cards.HostVideoCardStyleSpec.normalizeRadius(
        settings.int(FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP))
}

@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.hostBottomBarAppearanceRows(
    performer: Hikage.Performer<NativeLinearLayout.LayoutParams>
) {
    with(performer) {
        var refreshGlassSettings: () -> Unit = {}
        val capsuleOptions = DependentAppearanceOptions { hostBottomBarLiquidGlass }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, directToggle = true)
            text = stringResource(R.string.host_bottom_bar_liquid_glass)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostBottomBarLiquidGlass
            setOnCheckedChangeListener { _, checked ->
                hostBottomBarLiquidGlass = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS,
                            checked
                        )
                        putString(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL, hostBottomBarGlassAppearance.material.value)
                        putString(FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER, hostBottomBarGlassAppearance.renderer.value)
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write host bottom bar liquid glass prefs failed",
                        throwable
                    )
                }
                refreshGlassSettings()
                capsuleOptions.refresh()
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_bottom_bar_liquid_glass_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        refreshGlassSettings = hostGlassConfigurationRows(this, bottom = true)
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                marginStart = 16.dp
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            capsuleOptions.add(this)
            bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_COMPACT, directToggle = true)
            text = stringResource(R.string.host_bottom_bar_compact)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostBottomBarCompact
            setOnCheckedChangeListener { _, checked ->
                if (!hostBottomBarLiquidGlass) {
                    isChecked = hostBottomBarCompact
                    return@setOnCheckedChangeListener
                }
                hostBottomBarCompact = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HOST_BOTTOM_BAR_COMPACT,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write host bottom bar compact prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true) { marginStart = 16.dp }) {
            alpha = 0.6f
            text = stringResource(R.string.host_bottom_bar_compact_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            capsuleOptions.add(this)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                marginStart = 16.dp
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            capsuleOptions.add(this)
            bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY, directToggle = true)
            text = stringResource(R.string.host_bottom_bar_icon_only)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostBottomBarIconOnly
            setOnCheckedChangeListener { _, checked ->
                if (!hostBottomBarLiquidGlass) {
                    isChecked = hostBottomBarIconOnly
                    return@setOnCheckedChangeListener
                }
                hostBottomBarIconOnly = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write host bottom bar icon_only prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true) { marginStart = 16.dp }) {
            alpha = 0.6f
            text = stringResource(R.string.host_bottom_bar_icon_only_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            capsuleOptions.add(this)
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                marginStart = 16.dp
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            capsuleOptions.add(this)
            bindFavoriteSwitch(this, FeaturePreferences.HOST_MINE_GARB_DIM, directToggle = true)
            bindSettingDestination(this, FeaturePreferences.HOST_MINE_GARB_DIM)
            text = stringResource(R.string.host_mine_garb_dim)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostMineGarbDim
            setOnCheckedChangeListener { _, checked ->
                if (!hostBottomBarLiquidGlass) {
                    isChecked = hostMineGarbDim
                    return@setOnCheckedChangeListener
                }
                hostMineGarbDim = checked
                runCatching {
                    prefs().edit { putBoolean(FeaturePreferences.HOST_MINE_GARB_DIM, checked) }
                }.onFailure { throwable ->
                    Log.e("BilibiliInnocentLab", "write host mine garb dim prefs failed", throwable)
                }
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true) { marginStart = 16.dp }) {
            alpha = 0.6f
            text = stringResource(R.string.host_mine_garb_dim_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            capsuleOptions.add(this)
        }
        capsuleOptions.refresh()
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW, directToggle = true)
            text = stringResource(R.string.host_bottom_bar_touch_glow)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostBottomBarTouchGlow
            setOnCheckedChangeListener { _, checked ->
                hostBottomBarTouchGlow = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HOST_BOTTOM_BAR_TOUCH_GLOW,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write host bottom bar touch glow prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_bottom_bar_touch_glow_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}

@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.hostTopBarAppearanceRows(
    performer: Hikage.Performer<NativeLinearLayout.LayoutParams>
) {
    with(performer) {
        var refreshGlassSettings: () -> Unit = {}
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS, directToggle = true)
            text = stringResource(R.string.host_top_bar_liquid_glass)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostTopBarLiquidGlass
            setOnCheckedChangeListener { _, checked ->
                hostTopBarLiquidGlass = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
                            checked
                        )
                        putString(FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL, hostTopBarGlassAppearance.material.value)
                        putString(FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER, hostTopBarGlassAppearance.renderer.value)
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write host top bar liquid glass prefs failed",
                        throwable
                    )
                }
                refreshGlassSettings()
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_top_bar_liquid_glass_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        refreshGlassSettings = hostGlassConfigurationRows(this, bottom = false)
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW, directToggle = true)
            text = stringResource(R.string.host_top_bar_touch_glow)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostTopBarTouchGlow
            setOnCheckedChangeListener { _, checked ->
                hostTopBarTouchGlow = checked
                runCatching {
                    prefs().edit {
                        putBoolean(
                            FeaturePreferences.HOST_TOP_BAR_TOUCH_GLOW,
                            checked
                        )
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write host top bar touch glow prefs failed",
                        throwable
                    )
                }
            }
        }
        TextView(
            lparams = LayoutParams(widthMatchParent = true)
        ) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_top_bar_touch_glow_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
    }
}

@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.hostVideoCardAppearanceRows(
    performer: Hikage.Performer<NativeLinearLayout.LayoutParams>
) {
    with(performer) {
        val cardOptions = DependentAppearanceOptions { hostVideoCards }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_VIDEO_CARDS, directToggle = true)
            text = stringResource(R.string.host_video_cards)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostVideoCards
            setOnCheckedChangeListener { _, checked ->
                hostVideoCards = checked
                runCatching { prefs().edit { putBoolean(FeaturePreferences.HOST_VIDEO_CARDS, checked) } }
                    .onFailure { Log.e("BilibiliInnocentLab", "write host video card prefs failed", it) }
                cardOptions.refresh()
            }
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_video_cards_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 12.dp }) {
            bindSettingDestination(this, FeaturePreferences.HOST_CARD_MATERIAL)
            fun refresh() {
                text = getString(R.string.host_card_material_current, getString(hostCardMaterialLabel(hostCardMaterial)))
            }
            refresh()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(0, 12.dp, 0, 12.dp)
            background = selfRippleBackground(10f)
            isClickable = true; isFocusable = true
            setOnClickListener { if (hostVideoCards) showHostCardMaterialDialog(this, ::refresh) }
            cardOptions.add(this)
        }
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            setLineSpacing(6f, 1f)
            text = stringResource(R.string.host_card_material_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
            cardOptions.add(this)
        }
        TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 12.dp }) {
            bindSettingDestination(this, FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP)
            fun refresh() {
                text = getString(R.string.host_video_card_radius_current,
                    if (hostVideoCardRadiusDp < 0) getString(R.string.host_video_card_radius_default)
                    else "${hostVideoCardRadiusDp} dp")
            }
            refresh()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(0, 12.dp, 0, 12.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (hostVideoCards) showHostVideoCardRadiusDialog(this) { refresh() } }
            cardOptions.add(this)
        }
        TextView(lparams = LayoutParams(widthMatchParent = true) { topMargin = 4.dp }) {
            bindSettingDestination(this, FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP)
            bindSettingDestination(this, FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP)
            fun refresh() {
                fun value(dp: Int) = if (dp < 0) getString(R.string.host_video_card_radius_default) else "$dp dp"
                text = getString(R.string.host_home_card_spacing_current,
                    value(hostHomeCardHorizontalDp), value(hostHomeCardVerticalDp))
            }
            refresh()
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            setPadding(0, 12.dp, 0, 12.dp)
            background = selfRippleBackground(10f)
            isClickable = true
            isFocusable = true
            setOnClickListener { showHostHomeCardSpacingDialog(this) { refresh() } }
        }
    }
}
