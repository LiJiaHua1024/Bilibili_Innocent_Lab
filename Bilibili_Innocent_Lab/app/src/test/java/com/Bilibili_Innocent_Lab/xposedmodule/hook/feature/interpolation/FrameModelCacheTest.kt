package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FrameModelCacheTest {
    private val root: File = Files.createTempDirectory("frame-model").toFile()

    @After fun cleanUp() { root.deleteRecursively() }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test fun `spec matches only the exact size and hash and streams what it read`() {
        val bytes = ByteArray(200_000) { (it * 31).toByte() }
        val part = FrameModelSpec.Part("x.bin", bytes.size, sha256(bytes))
        val copy = ByteArrayOutputStream()
        assertTrue(FrameModelSpec.matches(part, ByteArrayInputStream(bytes)) { chunk, count -> copy.write(chunk, 0, count) })
        assertArrayEquals(bytes, copy.toByteArray())
        assertFalse(FrameModelSpec.matches(part, ByteArrayInputStream(bytes.copyOf(bytes.size - 1))))
        assertFalse(FrameModelSpec.matches(part, ByteArrayInputStream(bytes + 0)))
        assertFalse(FrameModelSpec.matches(part, ByteArrayInputStream(bytes.copyOf().also { it[7] = 0 })))
        val file = File(root, "x.bin").apply { writeBytes(bytes) }
        assertTrue(FrameModelSpec.matches(part, file))
        assertFalse(FrameModelSpec.matches(part, File(root, "absent")))
    }

    @Test fun `pinned parts come from the fixed commit under distinct remote names`() {
        FrameModelSpec.PARTS.forEach { part ->
            assertTrue(part.sourceUrl.startsWith("https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/${FrameModelSpec.SOURCE_COMMIT}/"))
            assertTrue(part.sourceUrl.endsWith("/models/rife-v4.6/${part.fileName}"))
            assertEquals(64, part.sha256.length)
        }
        assertEquals(listOf("frame_model_rife_v4_6_flownet.param", "frame_model_rife_v4_6_flownet.bin"),
            FrameModelSpec.PARTS.map { it.remoteName })
    }

    @Test fun `missing remote file reports missing without creating model files`() {
        val result = FrameModelCache.materialize(root) { null }
        assertEquals(FrameModelCache.Result.Missing(FrameModelSpec.PARAM.fileName), result)
        assertEquals(emptyList<String>(), File(root, "innocent-frame-model/rife-v4.6").list()!!.toList())
    }

    @Test fun `corrupt remote file is rejected and leaves no partial copy`() {
        val opened = mutableListOf<String>()
        val result = FrameModelCache.materialize(root) { name -> opened += name; ByteArrayInputStream(ByteArray(16_532)) }
        assertEquals(FrameModelCache.Result.Invalid(FrameModelSpec.PARAM.fileName), result)
        assertEquals(listOf(FrameModelSpec.PARAM.remoteName), opened)
        assertEquals(emptyList<String>(), File(root, "innocent-frame-model/rife-v4.6").list()!!.toList())
    }

    @Test fun `throwing opener counts as missing`() {
        val result = FrameModelCache.materialize(root) { error("service gone") }
        assertTrue(result is FrameModelCache.Result.Missing)
    }

    @Test fun concurrentPlaybackProcessesPublishTheirOwnTemporaryFilesAndReuseACompleteCache() {
        val payload = "verified model".toByteArray()
        val part = FrameModelSpec.Part("small.bin", payload.size, sha256(payload))
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                pool.submit<Result<FrameModelCache.Result>> {
                    runCatching { FrameModelCache.materialize(root, listOf(part)) {
                        barrier.await(5, java.util.concurrent.TimeUnit.SECONDS)
                        ByteArrayInputStream(payload)
                    } }
                }
            }.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS).getOrThrow() }
            assertTrue("concurrent outcomes: $results", results.all { it is FrameModelCache.Result.Ready })
            val cached = FrameModelCache.materialize(root, listOf(part)) { error("must reuse verified cache") }
            assertTrue(cached is FrameModelCache.Result.Ready)
            assertEquals(listOf(part.fileName), (cached as FrameModelCache.Result.Ready).directory.list()!!.toList())
        } finally { pool.shutdownNow() }
    }
}
