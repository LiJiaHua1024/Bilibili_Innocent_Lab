package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.io.File
import java.io.InputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 宿主进程侧的模型落地：从 LSPosed 远程文件（模块设置中下载并校验后写入）复制到宿主私有
 * noBackupFilesDir，逐文件校验大小与 SHA-256 后原子改名。已存在且校验通过则直接复用。
 * 只在推理 worker 上调用（10 MB 读写），绝不在宿主主线程或解码线程执行。
 */
internal object FrameModelCache {
    sealed interface Result {
        data class Ready(val directory: File) : Result
        data class Missing(val part: String) : Result
        data class Invalid(val part: String) : Result
    }

    /** [openRemote] 按远程文件名打开输入流（宿主侧包装 LSPosed 远程文件描述符），没有则返回 null。 */
    fun materialize(hostPrivateRoot: File, parts: List<FrameModelSpec.Part> = FrameModelSpec.PARTS,
                    openRemote: (String) -> InputStream?): Result {
        val directory = File(hostPrivateRoot, "innocent-frame-model/${FrameModelSpec.NAME}")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return Result.Invalid("directory")
        for (part in parts) {
            val target = File(directory, part.fileName)
            if (FrameModelSpec.matches(part, target)) continue
            val source = runCatching { openRemote(part.remoteName) }.getOrNull() ?: return Result.Missing(part.fileName)
            val valid = runCatching {
                source.buffered().use { input ->
                    val temporary = File.createTempFile(part.fileName + ".", ".tmp", directory)
                    try {
                        val matches = FileOutputStream(temporary).use { file ->
                            val output = file.buffered()
                            val checked = FrameModelSpec.matches(part, input) { bytes, count -> output.write(bytes, 0, count) }
                            output.flush()
                            file.fd.sync()
                            checked
                        }
                        if (!matches) false
                        else {
                            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING)
                            true
                        }
                    } finally {
                        temporary.delete()
                    }
                }
            }.getOrDefault(false)
            // Another process may have atomically published the same pinned content first.
            if (!valid && !FrameModelSpec.matches(part, target)) return Result.Invalid(part.fileName)
        }
        return Result.Ready(directory)
    }
}
