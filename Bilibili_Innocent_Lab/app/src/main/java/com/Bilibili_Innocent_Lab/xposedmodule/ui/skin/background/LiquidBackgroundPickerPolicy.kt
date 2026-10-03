package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 自定义背景的选图入口：一律走相册/图片选择器，不再用 `OpenDocument`。
 *
 * 2026-10-03 用户反馈：旧实现用 `ActivityResultContracts.OpenDocument()`，它按定义一定进
 * DocumentsUI（文件管理器），选图要一层层翻目录，"选择起来不太方便"。按可用性分三级降级：
 *
 * ① 系统照片选择器（[ActivityResultContracts.PickVisualMedia]）：Android 13+ 系统自带，
 *    10~12 有 Play 服务时用 backport，界面本身就是相册（相册分组 + 网格预览），且不申请
 *    任何媒体权限；
 * ② 系统相册的 [Intent.ACTION_PICK] + `MediaStore.Images.Media`：OEM 相册直接进挑图模式；
 * ③ [Intent.ACTION_GET_CONTENT]：最后兜底，它仍可包含相册，且 Go 版本/无相册设备也能用。
 *
 * **不要用 `resolveActivity()` 预判②是否存在**：Android 11+ 的包可见性过滤会让没在
 * `<queries>` 里声明过的相册返回 null，预判会把能用的相册误判成不可用。有没有应用能处理，
 * 交给调用方 `try/catch ActivityNotFoundException`（隐式意图的启动本身不受可见性过滤影响），
 * 失败再落③——那时 `ActivityNotFoundException` 只可能来自"真的没有相册"。
 */
internal object LiquidBackgroundPickerPolicy {
    const val MIME_TYPE = "image/*"

    /** 系统照片选择器（系统自带或 Play 服务 backport）是否可用。 */
    fun isSystemPickerAvailable(context: Context): Boolean =
        ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context)

    /** 只要图片、只要一张：自定义背景是单图语气。 */
    fun systemPickerRequest(): PickVisualMediaRequest =
        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    fun galleryIntent(): Intent =
        Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType(MIME_TYPE)

    fun documentFallbackIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType(MIME_TYPE)
}
