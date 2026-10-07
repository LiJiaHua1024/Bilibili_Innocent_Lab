@file:Suppress("SetTextI18n")

package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.util.Log
import android.widget.LinearLayout as NativeLinearLayout
import androidx.core.content.edit
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.prefs
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.TextView
import com.highcapable.hikage.widget.com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch

// 宿主美化设置的界面分卷；状态和收藏身份仍由当前 MainActivity 管理。

@com.highcapable.hikage.annotation.Hikagable
internal fun MainActivity.hostBottomBarAppearanceRows(
    performer: Hikage.Performer<NativeLinearLayout.LayoutParams>
) {
    with(performer) {
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
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write host bottom bar liquid glass prefs failed",
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
            text = stringResource(R.string.host_bottom_bar_liquid_glass_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_COMPACT, directToggle = true)
            text = stringResource(R.string.host_bottom_bar_compact)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostBottomBarCompact
            setOnCheckedChangeListener { _, checked ->
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
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            text = stringResource(R.string.host_bottom_bar_compact_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
        MaterialSwitch(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = 12.dp
                bottomMargin = 5.dp
            }
        ) {
            bindFavoriteSwitch(this, FeaturePreferences.HOST_BOTTOM_BAR_ICON_ONLY, directToggle = true)
            text = stringResource(R.string.host_bottom_bar_icon_only)
            isAllCaps = false
            textColor = colorResource(R.color.colorTextGray)
            textSize = 15f
            isChecked = hostBottomBarIconOnly
            setOnCheckedChangeListener { _, checked ->
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
        TextView(lparams = LayoutParams(widthMatchParent = true)) {
            alpha = 0.6f
            text = stringResource(R.string.host_bottom_bar_icon_only_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
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
                    }
                }.onFailure { throwable ->
                    Log.e(
                        "BilibiliInnocentLab",
                        "write host top bar liquid glass prefs failed",
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
            text = stringResource(R.string.host_top_bar_liquid_glass_tip)
            textColor = colorResource(R.color.colorTextDark)
            textSize = 12f
        }
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
            setOnClickListener { showHostVideoCardRadiusDialog(this) { refresh() } }
        }
    }
}
