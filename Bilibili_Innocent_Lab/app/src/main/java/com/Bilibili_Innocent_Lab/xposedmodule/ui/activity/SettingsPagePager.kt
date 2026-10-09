package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.SwitchCompat
import com.lumen.coacervation.engine.motion.pager.LumenPagePager

/** Host switch drawable adapter; LCE owns paging, gestures and animation. */
internal class SettingsPagePager(context: Context, attrs: AttributeSet? = null) : LumenPagePager(context, attrs) {
    init {
        switchParts = { view -> (view as? SwitchCompat)?.let { it.trackDrawable to it.thumbDrawable } }
    }
}
