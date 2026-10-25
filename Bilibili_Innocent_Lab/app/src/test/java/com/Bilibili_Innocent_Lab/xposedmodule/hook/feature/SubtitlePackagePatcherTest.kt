package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class SubtitlePackagePatcherTest {
    private val runtime = "/* Module-owned style adapter */ globalThis.__bilSubtitleStyle={path:__BIL_STYLE_FILE__,apply:function(){},color:function(r,k,v){return v}};"
    private val worker = "/* ${"bounded-renderer-fixture ".repeat(60)} */" +
        "var S=class{rasterize(){this._canvas.strokeColor=S.STROKE_COLOR;this._canvas.fillColor=S.BG_COLOR}};" +
        "var P=class{init(){this._mainRenderer={};this._viceRenderer={};}" +
        "applyRendererConfig(a,b,c){a.factor=this.rendererFactor(c)}handleOnVideoFrameChanged(){}" +
        "handleOnSubtitleConfigChanged(){this.applyRendererConfig(null,false,1)}" +
        "log(){return 'SubtitleFont: <SubtitleRenderer>'}};"

    private fun archive(file: File, version: String = "3.12.0") {
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, body) in listOf("info.json" to "{\"name\":\"danmaku-flame-master\",\"version\":\"$version\",\"commit\":\"official\"}",
                SubtitleScriptAdapter.WORKER to worker, "res/other.txt" to "untouched")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
    }

    @Test fun `private copy is reusable while the official bytes and unrelated resources are preserved`() {
        val root = Files.createTempDirectory("subtitle-patch-test").toFile()
        try {
            val source = File(root, "official.zip"); archive(source)
            val original = source.readBytes()
            val patcher = SubtitlePackagePatcher(runtime)
            val patched = requireNotNull(patcher.prepare(source, File(root, "style.json"), File(root, "private")))
            assertNotEquals(source.canonicalPath, patched.canonicalPath)
            assertArrayEquals(original, source.readBytes())
            assertEquals(patched, patcher.cached(source, File(root, "style.json")))
            assertEquals(patched, SubtitlePackagePatcher(runtime).prepare(source, File(root, "style.json"), File(root, "private")))
            ZipFile(patched).use { zip ->
                assertEquals("untouched", zip.getInputStream(zip.getEntry("res/other.txt")).bufferedReader().readText())
                val info = zip.getInputStream(zip.getEntry("info.json")).bufferedReader().readText()
                assertTrue(info.contains("innocent_subtitle_adapter")); assertFalse(info.contains("\"commit\":\"official\""))
                assertTrue(zip.getInputStream(zip.getEntry(SubtitleScriptAdapter.WORKER)).bufferedReader().readText().contains("globalThis.__bilSubtitleStyle.apply(a)"))
            }
            assertEquals(1, File(root, "private").listFiles()!!.count { it.extension == "zip" })
            val style = File(root, "style.json").apply { writeText(PlayerSubtitleStyle(sizePercent = 170).encode()) }
            assertNull(patcher.cached(source, style))
            val updated = requireNotNull(patcher.prepare(source, style, File(root, "private")))
            assertNotEquals(patched, updated)
            ZipFile(updated).use { zip ->
                val json = zip.getInputStream(zip.getEntry("res/${SubtitleStylePayload.CONFIG}")).reader().readText()
                assertEquals(170, org.json.JSONObject(json).getInt("size"))
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `unknown versions or damaged archives do not publish a replacement`() {
        val root = Files.createTempDirectory("subtitle-patch-test").toFile()
        try {
            val source = File(root, "official.zip"); archive(source, "future-version")
            val output = File(root, "private")
            assertNull(SubtitlePackagePatcher(runtime).prepare(source, File(root, "style.json"), output))
            source.writeText("corrupt")
            assertNull(SubtitlePackagePatcher(runtime).prepare(source, File(root, "style.json"), output))
            assertFalse(output.exists())
        } finally { root.deleteRecursively() }
    }
}
