package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.packing.FrameNativeLoader
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Optional backend only; no model is bundled and no player is intercepted by this class. */
internal class FrameInterpolationBackend private constructor(private var handle: Long, private val maxPixels: Long) : AutoCloseable {
    enum class Backend(val nativeValue: Int) { CPU_RESEARCH(0), VULKAN(1) }
    enum class Status(val nativeValue: Int) {
        OK(0), INVALID_ARGUMENT(1), UNSUPPORTED_BACKEND(2), GPU_UNAVAILABLE(3), MODEL_TOO_LARGE(4),
        INVALID_MODEL(5), MODEL_LOAD_FAILED(6), NOT_LOADED(7), BUSY(8), ALLOCATION_FAILED(9),
        INFERENCE_FAILED(10), INVALID_OUTPUT(11), NATIVE_UNAVAILABLE(-1);

        companion object {
            fun fromNative(value: Int): Status = entries.firstOrNull { it.nativeValue == value } ?: INFERENCE_FAILED
        }
    }

    data class OpenResult(val status: Status, val engine: FrameInterpolationBackend? = null)

    /** The caller owns buffers and serializes their mutation on a bounded background worker. */
    @Synchronized fun process(
        left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
        width: Int, height: Int, timestep: Float,
        leftStride: Int = width * 3, rightStride: Int = width * 3, outputStride: Int = width * 3,
        /** 帧 ID ≥ 0 时引擎复用该帧已上传的显存张量；同一 ID 的像素在其存续期内必须不变。 */
        leftId: Long = -1L, rightId: Long = -1L,
        /** 自适应光流模式下本次的光流比例 4/6/8；0 按像素数自动选择。 */
        flowScale: Int = 0
    ): Status {
        requireWorker()
        if (handle == 0L) return Status.NOT_LOADED
        if (!timestep.isFinite() || timestep !in 0f..1f || output.isReadOnly ||
            !planeValid(left, width, height, leftStride, maxPixels) || !planeValid(right, width, height, rightStride, maxPixels) ||
            !planeValid(output, width, height, outputStride, maxPixels) || flowScale !in setOf(0, 4, 6, 8)) return Status.INVALID_ARGUMENT
        return Status.fromNative(
            FrameInterpolationNative.process(handle, left, right, output, width, height,
                leftStride, rightStride, outputStride, timestep, leftId, rightId, flowScale)
        )
    }

    /** 最近一次插帧中运动置信度不足的像素比例（千分比，抽样）；用于把转场帧换成最近原帧。 */
    @Synchronized fun unreliablePermille(): Int =
        if (handle == 0L) 0 else FrameInterpolationNative.unreliablePermille(handle)

    /** 最近一次插帧两帧颜色分布差异（千分比）：切镜明显，摇镜很小。 */
    @Synchronized fun histogramPermille(): Int = if (handle == 0L) 0 else FrameInterpolationNative.histogramPermille(handle)

    /** 最近一次插帧中因局部转场换成最近原帧的分块数（16×9 网格）。 */
    @Synchronized fun patchedTiles(): Int = if (handle == 0L) 0 else FrameInterpolationNative.patchedTiles(handle)

    /** 运动置信度保护默认开启；仅供对照测试关闭。 */
    @Synchronized fun setMotionGuard(enabled: Boolean) {
        if (handle != 0L) FrameInterpolationNative.setMotionGuard(handle, enabled)
    }

    /** Last successful interpolation stage durations in ns: preprocess, inference (incl. transfers), postprocess. */
    @Synchronized fun stageNanos(): LongArray =
        if (handle == 0L) LongArray(3) else FrameInterpolationNative.timings(handle)

    /** Schedule on the engine worker after revoking presentation epochs. */
    @Synchronized override fun close() {
        requireWorker()
        val previous = handle
        handle = 0
        if (previous != 0L) FrameInterpolationNative.close(previous)
    }

    companion object {
        const val MAX_PIXELS = 1920L * 1080L

        /**
         * Research caller supplies the exact reviewed files. No network, download or model discovery.
         * fp16 is still clipped by ncnn to device support. flowScale 2, 4 or 8 estimates motion at 1/flowScale
         * resolution and warps at full resolution; gpuIo converts RGB8 on the GPU. flowScale > 1 and gpuIo
         * are Vulkan only. All are research options, off by default.
         */
        fun openResearchModel(directory: File, backend: Backend, threads: Int = 1,
                              fp16: Boolean = false, flowScale: Int = 1, gpuIo: Boolean = false,
                              protectStaticDetails: Boolean = false, maxPixels: Long = MAX_PIXELS,
                              fusedWarp: Boolean = gpuIo && flowScale > 1,
                              /** Research opt-in: a small 4K latency gain has higher measured CPU/PSS cost. */
                              rawGpuInput: Boolean = false,
                              /** flowScale 4 下，像素数超过 1920×1080 的帧改用 1/8 光流（光流输入约 480×270）。 */
                              adaptiveFlow: Boolean = false): OpenResult {
            requireWorker()
            if (threads !in 1..4 || flowScale !in setOf(1, 2, 4, 8) || protectStaticDetails && !gpuIo ||
                maxPixels !in 1L..(4096L * 2160L) || fusedWarp && (!gpuIo || flowScale == 1) || rawGpuInput && !fusedWarp || adaptiveFlow && flowScale != 4)
                return OpenResult(Status.INVALID_ARGUMENT)
            if (!FrameInterpolationNative.available) return OpenResult(Status.NATIVE_UNAVAILABLE)
            val model = runCatching {
                val root = directory.canonicalFile
                require(root.isDirectory)
                Pair(readVerified(root, FrameModelSpec.PARAM.fileName, FrameModelSpec.PARAM.size, FrameModelSpec.PARAM.sha256),
                    readVerified(root, FrameModelSpec.WEIGHTS.fileName, FrameModelSpec.WEIGHTS.size, FrameModelSpec.WEIGHTS.sha256))
            }.getOrElse { return OpenResult(if (it is OutOfMemoryError) Status.ALLOCATION_FAILED else Status.INVALID_MODEL) }
            val created = runCatching {
                if (maxPixels == MAX_PIXELS) FrameInterpolationNative.create(backend.nativeValue, 0, threads)
                else FrameInterpolationNative.createWithPixelBudget(backend.nativeValue, 0, threads, maxPixels)
            }
                .getOrElse { return OpenResult(Status.NATIVE_UNAVAILABLE) }
            if (created.size != 2 || created[0] != 0L || created[1] <= 0L)
                return OpenResult(if (created.firstOrNull() == 0L) Status.ALLOCATION_FAILED
                    else Status.fromNative(created.firstOrNull()?.toInt() ?: -1))
            val handle = created[1]
            if (fp16 || flowScale != 1 || gpuIo) {
                val flags = (if (fp16) 1 else 0) or (if (flowScale == 2 || flowScale == 8) 2 else 0) or (if (flowScale == 4 || flowScale == 8) 4 else 0) or
                    (if (gpuIo) 8 else 0) or (if (protectStaticDetails) 16 else 0) or (if (fusedWarp) 32 else 0) or
                    (if (rawGpuInput) 64 else 0) or (if (adaptiveFlow) 128 else 0)
                val configured = runCatching {
                    Status.fromNative(FrameInterpolationNative.configure(handle, flags))
                }.getOrElse { Status.NATIVE_UNAVAILABLE }
                if (configured != Status.OK) {
                    runCatching { FrameInterpolationNative.close(handle) }
                    return OpenResult(configured)
                }
            }
            val loaded = runCatching {
                Status.fromNative(FrameInterpolationNative.load(handle, model.first, model.second))
            }.getOrElse { Status.MODEL_LOAD_FAILED }
            if (loaded != Status.OK) {
                runCatching { FrameInterpolationNative.close(handle) }
                return OpenResult(loaded)
            }
            return try {
                OpenResult(Status.OK, FrameInterpolationBackend(handle, maxPixels))
            } catch (failure: Throwable) {
                // Transfer ownership only after both JVM owner/result allocations succeed.
                runCatching { FrameInterpolationNative.close(handle) }
                if (failure is OutOfMemoryError) OpenResult(Status.ALLOCATION_FAILED) else throw failure
            }
        }

        private fun readVerified(root: File, name: String, expectedSize: Int, expectedHash: String): ByteBuffer {
            val file = File(root, name).canonicalFile
            require(file.parentFile == root && file.isFile && file.length() == expectedSize.toLong())
            val buffer = ByteBuffer.allocateDirect(expectedSize)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    require(count <= expectedSize - total)
                    buffer.put(chunk, 0, count)
                    digest.update(chunk, 0, count)
                    total += count
                }
                require(total == expectedSize)
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(actualHash == expectedHash)
            buffer.flip()
            return buffer
        }

        private fun planeValid(buffer: ByteBuffer, width: Int, height: Int, stride: Int, maxPixels: Long): Boolean {
            if (!buffer.isDirect || buffer.position() != 0 || width <= 0 || height <= 0 ||
                width.toLong() * height > maxPixels || stride.toLong() < width.toLong() * 3) return false
            return (height - 1L) * stride + width.toLong() * 3 <= buffer.limit().toLong()
        }

        private fun requireWorker() {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Frame inference requires a background worker" }
        }
    }
}

internal object FrameInterpolationNative {
    val status: FrameNativeLoader.Status get() = FrameNativeLoader.status()
    val available: Boolean get() = status == FrameNativeLoader.Status.READY

    /** Supply framework module APK + actual host codeCacheDir on a bounded background worker. */
    fun prepare(moduleApk: File, hostCodeCache: File): FrameNativeLoader.Status =
        FrameNativeLoader.prepare(moduleApk, hostCodeCache, FrameInterpolationNative::class.java)

    external fun create(backend: Int, gpuIndex: Int, threads: Int): LongArray
    external fun createWithPixelBudget(backend: Int, gpuIndex: Int, threads: Int, maxPixels: Long): LongArray
    external fun load(handle: Long, param: ByteBuffer, weights: ByteBuffer): Int
    external fun process(handle: Long, left: ByteBuffer, right: ByteBuffer, output: ByteBuffer,
        width: Int, height: Int, leftStride: Int, rightStride: Int, outputStride: Int, timestep: Float,
        leftId: Long, rightId: Long, flowScale: Int): Int
    external fun close(handle: Long)
    external fun unreliablePermille(handle: Long): Int
    external fun patchedTiles(handle: Long): Int
    /** 源重复帧（同一画面重新编码）：1 是，0 否，-1 布局无效；比 analyzeScene 的「基本静止」严格得多。 */
    external fun isDuplicate(left: ByteBuffer, right: ByteBuffer, width: Int, height: Int, stride: Int): Int
    external fun histogramPermille(handle: Long): Int
    external fun setMotionGuard(handle: Long, enabled: Boolean)
    external fun configure(handle: Long, flags: Int): Int
    external fun timings(handle: Long): LongArray
    /** 0 运动，1 近静止，2 切镜，-1 布局无效；只读 RGB8 直接缓冲，不拷贝像素。 */
    external fun analyzeScene(left: ByteBuffer, right: ByteBuffer, width: Int, height: Int, stride: Int): Int
    /** glReadPixels 的紧凑 RGBA8 → 带步长 RGB8；返回 Status 原生值。 */
    external fun rgbaToRgb(rgba: ByteBuffer, width: Int, height: Int, output: ByteBuffer, outputStride: Int): Int
}
