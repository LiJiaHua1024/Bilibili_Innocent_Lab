package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Looper
import androidx.annotation.WorkerThread
import androidx.core.graphics.createBitmap
import androidx.core.graphics.ColorUtils
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.AmbientBackdropScene
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.highcapable.betterandroid.system.extension.utils.AndroidVersion

/**
 * Activity 的稳定 underlay，或高负载模式下由 PixelCopy 三缓冲持有的实时采样 source。
 *
 * 普通 create/custom 路径不捕获 Window 或 View 树；实时 source 只接管预先分配的可变 Bitmap，
 * 捕获调度与反馈抑制仍由 Activity renderer 负责。
 */
internal class LiquidBackdropSource private constructor(
    val bitmap: Bitmap,
    val customAssetId: String?,
    val isRealtime: Boolean,
    fullWidth: Int,
    fullHeight: Int,
    private val opticalBitmap: Bitmap = bitmap,
    crispRefraction: Boolean = false
) : AutoCloseable {
    var fullWidth: Int = fullWidth
        private set
    var fullHeight: Int = fullHeight
        private set

    /**
     * 折射输入。呈现与光学采样共用坐标、不共用像素，这里决定后端 `content` 吃哪一张。
     *
     * 默认吃 [opticalBitmap]（自定义图的那份是 20dp 模糊过的 0.25x 采样），也就是标清档的
     * 磨砂观感。`crispRefraction`（实时档，由 [fromCustomBitmap] 按效果档传入）改吃 [bitmap]
     * 呈现位图：实时档玻璃折射的本来就是清晰截屏，"没有实时截屏可用"的那几段（起播、位移
     * 抑制、采集挂起）若退回模糊副本，换源就成了"先糊后清晰 / 先清晰后糊"的硬切（2026-10-03
     * 用户报告：滑动时是 Liquid Glass，一停就变成小米式磨砂）。清晰副本把所有阶段收敛成同一种
     * 清晰度，剩下的差别只有"折射带里有没有下层内容"，那才是实时档本来的特征。
     *
     * 映射随位图一起走：AGSL 的 `content.eval` 吃位图像素坐标，后端的 `backdropScale` 必须按
     * [refractionWidth] / [refractionHeight] 算，不能按呈现尺寸。模糊副本仍有它的用处——外部
     * 窗口（Dialog）的玻璃取样刻意只吃被过滤过的底图，不把下层内容透进面板（见 [drawOpticalRegion]）；
     * [presentationShader] 的 localMatrix 逐帧被边缘溶解改写，也不能与折射输入共用实例。
     */
    private val refractionBitmap = if (crispRefraction && opticalBitmap !== bitmap) bitmap else opticalBitmap

    val bitmapShader = BitmapShader(refractionBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

    /**
     * 折射输入的位图尺寸。AGSL 的 `content.eval` 吃**位图像素坐标**，后端的 `backdropScale`
     * 必须按这份尺寸算（窗口尺寸 / 它）；按呈现尺寸算会在标清档把模糊副本错位 4 倍——
     * 一份 20dp 模糊的低频底图错位后观感仍是"一片柔和的色"、很难被发现，但玻璃与底图的
     * 空间对应关系已经错了。
     */
    val refractionWidth: Int
        get() = refractionBitmap.width
    val refractionHeight: Int
        get() = refractionBitmap.height

    private val rootPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * 外部窗口（Dialog）玻璃取样专用的独立 Shader 与 Matrix，吃被过滤过的光学副本。
     *
     * 不能复用 [bitmapShader]：那一份已经作为 RuntimeShader 的 `content` 输入被后端持有，
     * 逐帧改写它的 local matrix 会污染折射采样；[drawOpticalRegion] 也因此不能与抑制替换
     * 或边缘溶解共用实例。
     */
    private val opticalRegionShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(opticalBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            // 与旧路径的 FILTER_BITMAP_FLAG 对齐：稳定底图是 0.25 倍采样，最近邻会在
            // 抑制区域露出明显色块。setFilterMode 是 API 33 才有的显式声明，31-32 仍依赖
            // paint 的 FILTER_BITMAP_FLAG。
            if (AndroidVersion.isAtLeast(AndroidVersion.T)) {
                setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            }
        }
    }
    private val opticalRegionMatrix = Matrix()
    private val opticalRegionPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = opticalRegionShader }
    }
    private var closed = false
    private var published = false

    val isClosed: Boolean
        get() = closed

    /** 采样尺寸未变化时只更新窗口映射，不重新分配 Bitmap。 */
    fun updateFullSize(width: Int, height: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        require(width > 0 && height > 0) { "Backdrop dimensions must be positive" }
        fullWidth = width
        fullHeight = height
    }

    fun drawRoot(canvas: Canvas, bounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        rootPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, bounds, rootPaint)
    }

    /**
     * 抑制替换底图：把**呈现位图**（与 [drawRoot] 同一张）画进 [bounds]。反馈抑制用它把截图里的
     * 玻璃区域换成"玻璃背后的画面"，否则下一帧的光学输入会含上一帧自己的输出。
     *
     * 必须与折射输入同为清晰档：替换区正是玻璃自己的区域，也就是静止态玻璃内部采到的主要内容；
     * 这里若画 20dp 模糊的光学副本，位移期刚给过清晰观感、一停下来玻璃内部就只剩一片糊，
     * 读作"从 Liquid Glass 变成磨砂"（2026-10-03 用户报告）。换成呈现位图后，替换区与周围清晰
     * 内容同清晰度，遮罩边界也不再是"模糊团 | 清晰内容"的可见台阶。
     */
    fun drawSuppressionBackdrop(canvas: Canvas, bounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        rootPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawBitmap(bitmap, null, bounds, rootPaint)
    }

    /**
     * [drawSuppressionBackdrop] 的路径填充版本：抑制底图分配失败时的兜底，逐帧按 [path] 填充，
     * 内容与几何完全一致。
     *
     * 旧实现是 `clipPath` + 全图 `drawBitmap`：即使裁剪把光栅化限制在玻璃区域，Skia 仍要为整张
     * 目标位图建立一次抗锯齿裁剪掩码并做 save/restore。带 Shader 的路径填充不分配裁剪掩码。
     * 独立 Shader/Matrix：折射输入被 RuntimeShader 直接持有、边缘溶解与对话框取样各自逐帧改写
     * localMatrix，三边都不能共用实例。
     */
    fun drawSuppressionBackdropMasked(canvas: Canvas, path: Path, dstBounds: Rect, alpha: Int) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (dstBounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0) return
        suppressionMatrix.setScale(
            dstBounds.width().toFloat() / bitmap.width.toFloat(),
            dstBounds.height().toFloat() / bitmap.height.toFloat()
        )
        suppressionMatrix.postTranslate(dstBounds.left.toFloat(), dstBounds.top.toFloat())
        suppressionShader.setLocalMatrix(suppressionMatrix)
        suppressionPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawPath(path, suppressionPaint)
    }

    private val suppressionShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (AndroidVersion.isAtLeast(AndroidVersion.T)) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }
    private val suppressionMatrix = Matrix()
    private val suppressionPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = suppressionShader }
    }

    /**
     * 在表面本地坐标中按根坐标取一块光学采样区，供外部窗口（Dialog）里的玻璃表面使用：
     * 那些表面折射不到自己窗口的内容，实时截屏里对应位置只有未压暗的锐利底页，改采已过滤副本
     * 才不会把底页文字透进面板。这是模糊副本仅剩的两处用途之一（另一处是标清档的折射输入）。
     *
     * @param rootOffsetX/rootOffsetY 表面在 backdrop 全幅坐标中的位置（根视图像素）。
     */
    fun drawOpticalRegion(
        canvas: Canvas,
        localBounds: Rect,
        radiusPx: Float,
        rootOffsetX: Float,
        rootOffsetY: Float,
        alpha: Int
    ) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (localBounds.isEmpty || opticalBitmap.width <= 0 || opticalBitmap.height <= 0 ||
            fullWidth <= 0 || fullHeight <= 0
        ) return
        val scaleX = fullWidth.toFloat() / opticalBitmap.width.toFloat()
        val scaleY = fullHeight.toFloat() / opticalBitmap.height.toFloat()
        opticalRegionMatrix.setScale(scaleX, scaleY)
        opticalRegionMatrix.postTranslate(-rootOffsetX, -rootOffsetY)
        opticalRegionShader.setLocalMatrix(opticalRegionMatrix)
        opticalRegionPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawRoundRect(
            localBounds.left.toFloat(), localBounds.top.toFloat(),
            localBounds.right.toFloat(), localBounds.bottom.toFloat(),
            radiusPx, radiusPx, opticalRegionPaint
        )
    }

    /**
     * 按根坐标把**可见根背景**（[bitmap]，与 [drawRoot] 同一张）画进 [bounds]，
     * 逐像素乘以 [alphaMask] 的 alpha 再乘 [alpha]。供滚动边缘溶解把内容"溶回"窗口底图：
     * 画的必须与根背景逐像素一致，否则溶解区会露出一块色差。
     *
     * 独立的 Shader/Matrix：[bitmapShader] 被折射后端持有，[opticalRegionShader] 属于光学副本。
     * 同一 Shader 在多个宿主间逐次改 local matrix 是安全的——HWUI 在录制那一刻快照原生实例。
     */
    fun drawPresentationRegion(
        canvas: Canvas,
        bounds: RectF,
        rootOffsetX: Float,
        rootOffsetY: Float,
        alphaMask: Shader?,
        alpha: Int
    ) {
        check(!closed) { "Liquid backdrop source is closed" }
        if (bounds.isEmpty || bitmap.width <= 0 || bitmap.height <= 0 || fullWidth <= 0 || fullHeight <= 0) return
        presentationMatrix.setScale(
            fullWidth.toFloat() / bitmap.width.toFloat(),
            fullHeight.toFloat() / bitmap.height.toFloat()
        )
        presentationMatrix.postTranslate(-rootOffsetX, -rootOffsetY)
        presentationShader.setLocalMatrix(presentationMatrix)
        presentationPaint.shader = if (alphaMask == null) presentationShader else {
            // ComposeShader 在子 Shader 的 local matrix 变化后会自行重建原生实例（API 26+），
            // 同一遮罩只需要组合一次。DST_IN：底图 × 遮罩 alpha。
            if (composedMask !== alphaMask) {
                composedShader = ComposeShader(presentationShader, alphaMask, PorterDuff.Mode.DST_IN)
                composedMask = alphaMask
            }
            composedShader
        }
        presentationPaint.alpha = alpha.coerceIn(0, 255)
        canvas.drawRect(bounds, presentationPaint)
    }

    private val presentationShader by lazy(LazyThreadSafetyMode.NONE) {
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (AndroidVersion.isAtLeast(AndroidVersion.T)) setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
        }
    }
    private val presentationMatrix = Matrix()
    private val presentationPaint by lazy(LazyThreadSafetyMode.NONE) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    }
    private var composedMask: Shader? = null
    private var composedShader: ComposeShader? = null

    /** Commit before binding this source to a renderer or exposing it to a window draw. */
    fun markPublished() {
        check(!closed) { "Liquid backdrop source is closed" }
        if (published) return
        published = true
        bitmap.prepareToDraw()
        if (opticalBitmap !== bitmap) opticalBitmap.prepareToDraw()
    }

    /** Worker results that never reached a display list can release both owned images immediately. */
    fun discardUnpublished() {
        if (closed) return
        check(!published && !isRealtime) { "Cannot recycle a published or realtime backdrop source" }
        closed = true
        if (opticalBitmap !== bitmap && !opticalBitmap.isRecycled) opticalBitmap.recycle()
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() {
        if (closed) return
        closed = true
        // 已提交到硬件 display list 的 Bitmap 不能用 recycle() 充当 GPU fence。断开 renderer、
        // Shader 与 source 引用后交给运行时回收，旧 display list 的 native 引用可安全完成重放。
    }

    companion object {
        /**
         * 未设自定义图时的稳定 underlay：与标准磨砂皮肤共用 [AmbientBackdropScene] 配方，
         * 两种材质下用户看到的是同一个 Monet 氛围背景。
         *
         * 场景按 0.25 倍窗口尺寸光栅化后直接放大铺满屏幕——它只有低频渐变与光晕，放大不会糊，
         * 而本方法在 bindRoot 的主线程上同步执行，全分辨率光栅化会变成可感知的卡顿。
         * 场景已不含颗粒噪声（见 [AmbientBackdropScene]），可见底图与折射采样底图因此共用
         * 同一张位图，不必各持一份副本。实时截屏路径不受影响。
         */
        fun create(
            palette: MonetColors,
            fullWidth: Int,
            fullHeight: Int
        ): LiquidBackdropSource {
            val size = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
            val bitmap = createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val dark = ColorUtils.calculateLuminance(palette.background) < .5
                AmbientBackdropScene.paint(canvas, palette, size.width, size.height, dark)

                // 顶部与底部精确回到 background，避免状态栏/导航栏出现颜色接缝。
                val seamPaint = Paint(
                    Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
                )
                val transparentBackground = ColorUtils.setAlphaComponent(palette.background, 0)
                seamPaint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    size.height.toFloat(),
                    intArrayOf(
                        palette.background,
                        transparentBackground,
                        transparentBackground,
                        palette.background
                    ),
                    floatArrayOf(0f, 0.10f, 0.90f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(
                    0f,
                    0f,
                    size.width.toFloat(),
                    size.height.toFloat(),
                    seamPaint
                )
                bitmap.prepareToDraw()
                return LiquidBackdropSource(
                    bitmap = bitmap,
                    customAssetId = null,
                    isRealtime = false,
                    fullWidth = fullWidth,
                    fullHeight = fullHeight
                )
            } catch (throwable: Throwable) {
                bitmap.recycle()
                throw throwable
            }
        }

        /**
         * Called on the existing background loader after bounded image decoding. One optical copy
         * is shared by every surface; the caller keeps ownership of [bitmap] if construction fails.
         *
         * [bitmap] is the **presentation** image: it is drawn into the root bounds 1:1 and must be
         * sized by [LiquidBackdropSizingPolicy.resolvePresentation] — photos are visibly soft when
         * a 0.25x sample is stretched back over the window (2026-10-03 用户反馈"自定义图片糊"）。
         * The optical sample therefore no longer equals the presentation bitmap: it is downscaled
         * to the stable 0.25x budget first and blurred there, so refraction cost stays unchanged.
         *
         * [crispRefraction] 为 true 时折射输入改用呈现位图，见 [bitmapShader]：实时档下稳定底图
         * 只是"实时截屏缺席时的替身"，替身必须与实时档同清晰度，否则换源会读成观感突变。
         */
        @WorkerThread
        fun fromCustomBitmap(
            bitmap: Bitmap,
            assetId: String,
            fullWidth: Int,
            fullHeight: Int,
            density: Float,
            crispRefraction: Boolean
        ): LiquidBackdropSource {
            check(Looper.myLooper() !== Looper.getMainLooper()) {
                "Custom optical backdrop must be prepared off the main thread"
            }
            require(!bitmap.isRecycled) { "Custom backdrop bitmap is recycled" }
            require(assetId.isNotBlank()) { "Custom backdrop asset id is blank" }
            val expected = LiquidBackdropSizingPolicy.resolvePresentation(fullWidth, fullHeight)
            require(bitmap.width == expected.width && bitmap.height == expected.height) {
                "Custom backdrop bitmap does not match the bounded presentation size"
            }
            val sample = LiquidBackdropSizingPolicy.resolve(fullWidth, fullHeight)
            val sampled = createBitmap(sample.width, sample.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(sampled).drawBitmap(
                    bitmap,
                    null,
                    Rect(0, 0, sample.width, sample.height),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
                )
                val pixels = IntArray(sample.width * sample.height)
                sampled.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
                val softened = LiquidOpticalSamplingPolicy.soften(
                    pixels, sample.width, sample.height, fullWidth, density
                )
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Backdrop replaced")
                val optical = createBitmap(sample.width, sample.height, Bitmap.Config.ARGB_8888)
                try {
                    optical.setPixels(softened, 0, sample.width, 0, 0, sample.width, sample.height)
                    return LiquidBackdropSource(
                        bitmap = bitmap,
                        customAssetId = assetId,
                        isRealtime = false,
                        fullWidth = fullWidth,
                        fullHeight = fullHeight,
                        opticalBitmap = optical,
                        crispRefraction = crispRefraction
                    )
                } catch (failure: Throwable) {
                    optical.recycle()
                    throw failure
                }
            } finally {
                sampled.recycle()
            }
        }

        /** 由 PixelCopy 三缓冲拥有的可变窗口截图；source 只建立长期复用的采样 Shader。 */
        fun fromRealtimeBitmap(
            bitmap: Bitmap,
            fullWidth: Int,
            fullHeight: Int
        ): LiquidBackdropSource {
            require(!bitmap.isRecycled && bitmap.isMutable) {
                "Realtime backdrop bitmap must be mutable and available"
            }
            require(fullWidth > 0 && fullHeight > 0) {
                "Realtime backdrop dimensions must be positive"
            }
            return LiquidBackdropSource(
                bitmap = bitmap,
                customAssetId = null,
                isRealtime = true,
                fullWidth = fullWidth,
                fullHeight = fullHeight
            )
        }
    }
}
