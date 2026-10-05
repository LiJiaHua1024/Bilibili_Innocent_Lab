package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background

import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidBackgroundPickerPolicyTest {

    @Test
    fun `gallery permission follows the media permission split at api 33`() {
        val legacy = "android.permission.READ_EXTERNAL_STORAGE"
        val media = "android.permission.READ_MEDIA_IMAGES"
        assertEquals(legacy, LiquidBackgroundPickerPolicy.galleryPermission(27))
        assertEquals(legacy, LiquidBackgroundPickerPolicy.galleryPermission(32))
        assertEquals(media, LiquidBackgroundPickerPolicy.galleryPermission(33))
        assertEquals(media, LiquidBackgroundPickerPolicy.galleryPermission(36))
    }
}
