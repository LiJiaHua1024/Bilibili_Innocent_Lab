package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameModelSpec
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Fixed model identities only. Verified downloads survive a failed framework publication. */
internal class FrameModelFiles(private val parts: List<FrameModelSpec.Part> = FrameModelSpec.PARTS) {
    enum class State { MISSING, INVALID, READY }

    fun cached(directory: File): Map<FrameModelSpec.Part, File>? {
        val files = parts.associateWith { File(directory, it.fileName) }
        return files.takeIf { it.all { (part, file) -> FrameModelSpec.matches(part, file) } }
    }

    fun inspect(names: Set<String>, open: (String) -> InputStream): State {
        if (parts.any { it.remoteName !in names }) return State.MISSING
        return if (parts.all { part -> open(part.remoteName).buffered().use { FrameModelSpec.matches(part, it) } })
            State.READY else State.INVALID
    }

    fun prepare(directory: File, fetch: (FrameModelSpec.Part, File) -> Boolean): Map<FrameModelSpec.Part, File>? {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return null
        val ready = LinkedHashMap<FrameModelSpec.Part, File>()
        for (part in parts) {
            val target = File(directory, part.fileName)
            if (!FrameModelSpec.matches(part, target)) {
                val temporary = File.createTempFile("model-", ".part", directory)
                try {
                    if (!fetch(part, temporary) || !FrameModelSpec.matches(part, temporary)) return null
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
            ready[part] = target
        }
        return ready
    }

    fun publish(files: Map<FrameModelSpec.Part, File>, names: Set<String>, open: (String) -> InputStream,
                write: (String, File) -> Unit): Boolean {
        for (part in parts) {
            val valid = part.remoteName in names && open(part.remoteName).buffered().use { FrameModelSpec.matches(part, it) }
            if (!valid) write(part.remoteName, files.getValue(part))
        }
        // All files must be readable together after writes, including a connection change during publication.
        return parts.all { part -> open(part.remoteName).buffered().use { FrameModelSpec.matches(part, it) } }
    }
}
