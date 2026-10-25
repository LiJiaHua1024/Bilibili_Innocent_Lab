package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio

import android.os.Process
import dalvik.system.PathClassLoader
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

/** Optional bundled model, prepared once on a worker. No network, host objects or remote model paths. */
internal object AudioRescueModel {
    private val started = AtomicBoolean()
    @Volatile private var ready: AudioSpeechModel? = null
    fun current(): AudioSpeechModel? = ready

    fun prepare(moduleApk: File, hostCodeCache: File, log: (String, String) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        Thread({
            runCatching {
                val abi = if (Process.is64Bit()) "arm64-v8a" else "armeabi-v7a"
                val specs = if (Process.is64Bit()) ARM64 else ARM32
                val cacheRoot = hostCodeCache.canonicalFile.toPath()
                val directory = cacheRoot.resolve("bil-audio-onnx-1.31.0")
                Files.createDirectories(directory)
                check(!Files.isSymbolicLink(directory) && directory.toRealPath().parent == cacheRoot)
                val model = ZipFile(moduleApk.canonicalFile).use { apk ->
                    FileChannel.open(directory.resolve("extract.lock"), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                        channel.lock().use {
                            for (spec in specs) {
                                val target = directory.resolve(spec.name)
                                if (!Files.exists(target, NOFOLLOW_LINKS)) {
                                    val entry = checkNotNull(apk.getEntry("lib/$abi/${spec.name}"))
                                    check(entry.size == spec.bytes.toLong())
                                    val temporary = Files.createTempFile(directory, ".extract-", ".part")
                                    try {
                                        apk.getInputStream(entry).use { input -> Files.newOutputStream(temporary).use { input.copyTo(it) } }
                                        check(Files.size(temporary) == spec.bytes.toLong() && hash(Files.readAllBytes(temporary)) == spec.sha256)
                                        Files.move(temporary, target, ATOMIC_MOVE)
                                    } finally { Files.deleteIfExists(temporary) }
                                }
                                check(Files.isRegularFile(target, NOFOLLOW_LINKS) && Files.size(target) == spec.bytes.toLong())
                                check(hash(Files.readAllBytes(target)) == spec.sha256)
                            }
                        }
                    }
                    val entry = checkNotNull(apk.getEntry("assets/audio-rescue/gtcrn-dns3.onnx"))
                    check(entry.size == 535190L)
                    apk.getInputStream(entry).use { it.readBytes() }.also {
                        check(hash(it) == "b4718df6228e7bdf1a8a435cf98f838636eb2fd331acabf86ba87c5192ebcb87")
                    }
                }
                // ORT's Android loader always calls loadLibrary. A dedicated loader gives it
                // the verified native path without mutating a host/framework loader or relying
                // on hidden APIs. Only ORT and its tiny model bridge are defined again; the
                // model interface, Kotlin and all other module types delegate to the parent.
                val loader = ModelLoader(moduleApk.canonicalPath, directory.toString(),
                    checkNotNull(AudioRescueModel::class.java.classLoader))
                val type = checkNotNull(Lookup.classOrNull(loader, MODEL_CLASS))
                val result = checkNotNull(Lookup.constructorOrNull(type, ByteArray::class.java))
                    .newInstance(model) as AudioSpeechModel
                // Complete lazy native/kernel initialisation before an AudioTrack callback can use it.
                result.infer(FloatArray(514), arrayOf(FloatArray(16896), FloatArray(96), FloatArray(1056)))
                // Warm the Kotlin rate conversion/FFT paths as well as ORT before publishing.
                // This prevents a cold ART interpreter from spending the first spoken second
                // compiling work on the AudioTrack writer (notably on the MSM8998 test device).
                val warm = AudioSpeechEnhancer(48000, 2, result)
                repeat(12000) { warm.process(0.0, 0.0) }
                ready = result
                log("player_audio_rescue.model.ready", "[BIL] audio_rescue model=gtcrn-dns3 ready=true")
            }.onFailure { log("player_audio_rescue.model.failed", "[BIL] audio_rescue model unavailable; voice bypass, basic noise fallback: $it") }
        }, "bil-audio-model").apply { isDaemon = true }.start()
    }

    private const val MODEL_CLASS = "com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.audio.GtcrnSpeechModel"
    private class ModelLoader(apk: String, nativePath: String, parent: ClassLoader) : PathClassLoader(apk, nativePath, parent) {
        @Synchronized override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("ai.onnxruntime.") && name != MODEL_CLASS && !name.startsWith("$MODEL_CLASS$"))
                return super.loadClass(name, resolve)
            val type = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(type)
            return type
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private data class Native(val name: String, val bytes: Int, val sha256: String)
    private val ARM64 = listOf(
        Native("libonnxruntime.so", 34208680, "8005a741e57117b4d7b476417706c0e9af13cf1b89db47e60f0cbc56e1af91e0"),
        Native("libonnxruntime4j_jni.so", 122576, "5f2f1a99fd9f65fd6271eed825632f4ae5ada95f5cc13d66605888ccb80cdc29")
    )
    private val ARM32 = listOf(
        Native("libonnxruntime.so", 24182012, "2a63f9a56b211511209dbf4b5bc143f18d0b365977dd7a0afd57d95c85479af6"),
        Native("libonnxruntime4j_jni.so", 89900, "6cc9816ea72fa290c6b13627412efad178eca031421ac9c367ebf4523b971b3d")
    )
}
