package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background

import android.Manifest
import android.content.Intent
import android.provider.MediaStore

internal object LiquidBackgroundPickerPolicy {
    const val MIME_TYPE = "image/*"

    /** 相册通道需要的读图权限：33+ 用 READ_MEDIA_IMAGES，27–32 用 READ_EXTERNAL_STORAGE。 */
    fun galleryPermission(sdkInt: Int): String =
        if (sdkInt >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun galleryIntent(): Intent =
        Intent(Intent.ACTION_PICK).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MIME_TYPE)
}
