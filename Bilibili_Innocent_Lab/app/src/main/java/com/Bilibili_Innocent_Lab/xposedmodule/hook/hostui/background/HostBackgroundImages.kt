package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import com.Bilibili_Innocent_Lab.xposedmodule.BuildConfig
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundSizingPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.roundToInt

internal object HostBackgroundImages {
    const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.hostbackground"
    fun uri(asset: String): Uri = Uri.parse("content://$AUTHORITY/image/$asset")
    fun file(context: Context, asset: String): File {
        require(HostBackgroundConfig.validAsset(asset))
        return File(context.filesDir, "host_background/$asset.png")
    }

    /** 仅在后台导入；源 URI 不落盘，失败时旧资产不受影响。 */
    fun import(context: Context, uri: Uri): String {
        val folder = File(context.filesDir, "host_background").apply { check(isDirectory || mkdirs()) }
        val raw = File.createTempFile("import_", ".raw", folder)
        val asset = UUID.randomUUID().toString()
        val target = file(context, asset)
        var bitmap: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(raw).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= LiquidBackgroundSizingPolicy.MAX_INPUT_BYTES)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Unreadable image")
            bitmap = LiquidBackgroundStore.normalizedBitmap(raw) ?: error("Unsupported image")
            FileOutputStream(target).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.fd.sync()
            }
            check(target.length() in 1..LiquidBackgroundSizingPolicy.MAX_ASSET_BYTES)
            return asset
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        } finally {
            bitmap?.recycle()
            raw.delete()
        }
    }

    fun grant(context: Context, asset: String) {
        if (HostBackgroundConfig.validAsset(asset))
            context.grantUriPermission("tv.danmaku.bili", uri(asset), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** 调用方在提交新设置后清理旧图；取消编辑只清理本次临时导入。 */
    fun cleanup(context: Context, keep: String) {
        File(context.filesDir, "host_background").listFiles()?.filter { it.name.endsWith(".png") && it.name != "$keep.png" }
            ?.forEach {
                val asset = it.name.removeSuffix(".png")
                if (HostBackgroundConfig.validAsset(asset)) {
                    context.revokeUriPermission(uri(asset), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    it.delete()
                }
            }
    }

    fun load(context: Context, config: HostBackgroundConfig): Bitmap? {
        if (!HostBackgroundConfig.validAsset(config.asset)) return null
        return runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri(config.asset))?.use { BitmapFactory.decodeStream(it, null, options) }
            check(options.outWidth in 1..2048 && options.outHeight in 1..2048)
            val sample = BitmapFactory.Options().apply {
                inSampleSize = if (maxOf(options.outWidth, options.outHeight) > 960) 2 else 1
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val source = context.contentResolver.openInputStream(uri(config.asset))?.use { BitmapFactory.decodeStream(it, null, sample) }
                ?: return@runCatching null
            try { process(source, config) } finally { source.recycle() }
        }.getOrNull()
    }

    /** 有界位图上的三遍 box blur，API 27 也支持，始终在后台执行。 */
    internal fun process(source: Bitmap, config: HostBackgroundConfig): Bitmap {
        val scale = minOf(1f, 960f / maxOf(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(config.saturation / 100f) })
        }
        Canvas(target).drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), paint)
        val radius = (config.blur * width / 360f / 2f).roundToInt()
        var processedPixels: IntArray? = null
        if (radius > 0) {
            var pixels = IntArray(width * height)
            var buffer = IntArray(pixels.size)
            target.getPixels(pixels, 0, width, 0, 0, width, height)
            repeat(3) {
                box(pixels, buffer, width, height, radius)
                box(buffer, pixels, height, width, radius)
            }
            target.setPixels(pixels, 0, width, 0, 0, width, height)
            processedPixels = pixels
        }
        // PNG 可能带 alpha 通道但每个像素都不透明；只在后台确认一次，后续可省掉底色和重复遮罩。
        if (!source.hasAlpha()) target.setHasAlpha(false)
        else {
            val pixels = processedPixels ?: IntArray(width * height).also {
                target.getPixels(it, 0, width, 0, 0, width, height)
            }
            if (pixels.all { it ushr 24 == 255 }) target.setHasAlpha(false)
        }
        target.prepareToDraw()
        return target
    }

    /** 水平滑窗求平均并转置，边缘复制，避免暗边。 */
    private fun box(input: IntArray, output: IntArray, width: Int, height: Int, radius: Int) {
        val divisor = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var a = 0; var r = 0; var g = 0; var b = 0
            fun add(pixel: Int, sign: Int) {
                a += (pixel ushr 24) * sign; r += ((pixel ushr 16) and 255) * sign
                g += ((pixel ushr 8) and 255) * sign; b += (pixel and 255) * sign
            }
            for (x in -radius..radius) add(input[row + x.coerceIn(0, width - 1)], 1)
            for (x in 0 until width) {
                output[x * height + y] = ((a / divisor) shl 24) or ((r / divisor) shl 16) or ((g / divisor) shl 8) or (b / divisor)
                add(input[row + (x - radius).coerceIn(0, width - 1)], -1)
                add(input[row + (x + radius + 1).coerceIn(0, width - 1)], 1)
            }
        }
    }
}
