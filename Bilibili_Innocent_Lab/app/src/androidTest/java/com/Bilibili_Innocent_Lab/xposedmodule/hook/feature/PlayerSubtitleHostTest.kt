package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dalvik.system.DexClassLoader
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-host ART/JNI checks in a separate verification package; does not launch or alter Bilibili. */
@RunWith(AndroidJUnit4::class)
class PlayerSubtitleHostTest {
    companion object { private var sharedLoader: ClassLoader? = null }
    private fun context(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Use an isolated verification application", context.packageName.endsWith(".subtitleverify"))
        return context
    }
    private fun loader(context: Context): ClassLoader {
        val apk = InstrumentationRegistry.getArguments().getString("hostApkPath")
        assumeTrue("Pass hostApkPath to opt in to official host loading", !apk.isNullOrBlank())
        val native = context.packageManager.getApplicationInfo("tv.danmaku.bili", 0).nativeLibraryDir
        return sharedLoader ?: DexClassLoader(apk!!, context.codeCacheDir.absolutePath, "$native:$apk!/lib/arm64-v8a", context.classLoader).also { sharedLoader = it }
    }

    @Test fun clientAiSubtitleGatesHaveTheInspectedHostAbi() {
        val host = loader(context())
        val decision = host.loadClass("com.bilibili.lib.dd.DeviceDecision")
        assertEquals(Boolean::class.javaPrimitiveType,
            decision.getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType).returnType)
        val params = host.loadClass("tv.danmaku.biliplayerv2.service.Video\$DanmakuResolveParams")
        val request = params.methods.singleOrNull { it.name == "getRequestSubtitle" && it.parameterCount == 0 }
        val version = context().packageManager.getPackageInfo("tv.danmaku.bili", 0).longVersionCode
        if (version >= 9150200) assertNotNull("9.15 subtitle RPC eligibility ABI", request)
        request?.let { assertEquals(Boolean::class.javaPrimitiveType, it.returnType) }
        assertEquals(String::class.java, params.getMethod("getFrom").returnType)
        assertEquals(Long::class.javaPrimitiveType, params.getMethod("getAvid").returnType)
        assertEquals(Long::class.javaPrimitiveType, params.getMethod("getCid").returnType)
    }

    @Test fun realCronPackageAcceptsThePrivateSubtitleCopy() {
        val context = context()
        val sourcePath = InstrumentationRegistry.getArguments().getString("subtitlePackagePath")
        assumeTrue("Pass subtitlePackagePath to opt in to a real rendering package", !sourcePath.isNullOrBlank())
        val source = File(sourcePath!!)
        val before = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
        val runtime = context.assets.open("subtitle/style-runtime.js").bufferedReader().use { it.readText() }
        val patcher = SubtitlePackagePatcher(runtime)
        val store = SubtitleStyleStore(context)
        store.save(PlayerSubtitleStyle(sizePercent = 150, font = SubtitleSystemFonts.files.first().name))
        val patched = patcher.prepare(source, store.file, File(context.cacheDir, "subtitle-host-test"))
            ?: error("Subtitle patch stage: ${patcher.lastFailure}")
        val owner = loader(context).loadClass("com.bilibili.cron.ChronosPackage")
        val factory = owner.getMethod("createPackageFromFile", Context::class.java, File::class.java)
        val original = requireNotNull(factory.invoke(null, context, source))
        val replacement = requireNotNull(factory.invoke(null, context, patched))
        try {
            val getInfo = owner.getMethod("getInfo")
            val info = JSONObject(getInfo.invoke(replacement) as String)
            assertEquals(1, info.getInt("innocent_subtitle_adapter"))
            assertNotEquals(JSONObject(getInfo.invoke(original) as String).getString("commit"), info.getString("commit"))
            assertNotEquals(owner.getMethod("getSandBoxDirectory").invoke(original), owner.getMethod("getSandBoxDirectory").invoke(replacement))
            val callback = owner.classLoader!!.loadClass("com.bilibili.cron.ChronosPackage\$LoadCompleteCallback")
            val completed = CountDownLatch(1)
            val succeeded = AtomicBoolean(false)
            val listener = Proxy.newProxyInstance(callback.classLoader, arrayOf(callback)) { _, method, args ->
                if (method.name == "onComplete") { succeeded.set(args?.firstOrNull() == true); completed.countDown() }
                null
            }
            owner.getMethod("preloadAsync", callback).invoke(replacement, listener)
            assertTrue("Native package extraction callback", completed.await(15, TimeUnit.SECONDS))
            assertTrue("Native package extraction", succeeded.get())
            val sandbox = File(owner.getMethod("getSandBoxDirectory").invoke(replacement) as String)
            SubtitleStyleStore.registerPrivatePackage(context, sandbox.path)
            val config = File(sandbox.parentFile, "res/${SubtitleStylePayload.CONFIG}")
            assertEquals(150, JSONObject(config.readText()).getInt("size"))
            assertTrue(File(sandbox.parentFile, "res/innocent-subtitle").listFiles().orEmpty().any { it.name.startsWith("font.") })
            assertTrue(store.save(PlayerSubtitleStyle(sizePercent = 175, backgroundOpacity = 35)) > 0)
            assertEquals(175, JSONObject(config.readText()).getInt("size"))
            assertEquals(35, JSONObject(config.readText()).getInt("backgroundOpacity"))
            store.save(null)
            assertEquals("null", config.readText())
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        } finally {
            owner.getMethod("release").invoke(replacement)
            owner.getMethod("release").invoke(original)
        }
    }

    @Test fun styleRuntimeUsesRealCronFontAndCanvasApis() {
        val context = context()
        val host = loader(context)
        val font = requireNotNull(SubtitleSystemFonts.files.firstOrNull())
        val styleFile = File(context.cacheDir, "subtitle-probe-style.json")
        styleFile.writeText(JSONObject(PlayerSubtitleStyle(sizePercent = 150, bold = true, font = font.name).encode())
            .put("fontPath", "innocent-subtitle/font.ttf").toString())
        val runtime = context.assets.open("subtitle/style-runtime.js").bufferedReader().use { it.readText() }
            .replace("__BIL_STYLE_FILE__", JSONObject.quote(SubtitleStylePayload.CONFIG))
        val script = runtime + """
            try {
                const nativeFont=cron.Font.createFromFile(${JSONObject.quote("innocent-subtitle/font.ttf")},30);
                if(!nativeFont) throw new Error('resource font unavailable');
                const data=cron.Data.createFromFile('${SubtitleStylePayload.CONFIG}');
                if(!data) throw new Error('style file unreadable');
                const parsed=JSON.parse(data.getSubString());
                cron.log('BIL_SUBTITLE_PROBE_DATA:'+typeof Number.isInteger+':'+parsed.version+':'+parsed.bold+':'+parsed.size);
                const canvas=cron.Canvas.create();
                const renderer={_fontFn:size=>cron.Font.createFromStyleAndSize(cron.Font.Style.Normal,size),
                    _canvas:canvas,fontSize:20,factor:1,bgPadding:2};
                globalThis.__bilSubtitleStyle.apply(renderer);
                if(renderer.fontSize!==30 || !canvas.font) throw new Error('font style not applied');
                const bounds=canvas.measureText('字幕 Font');
                if(!(bounds.width>0 && bounds.height>0)) throw new Error('font measurement failed');
                canvas.resize([320,100]);
                canvas.fillColor=[1,0,0,0.5];
                canvas.fillText('字幕 Font',[20,60]);
                const image=canvas.toImage();
                if(!image) throw new Error('native rasterization failed');
                image.release();canvas.resize([0,0]);
                cron.log('BIL_SUBTITLE_PROBE_OK');
                const refresh=cron.Task.scheduleTask(()=>{
                    renderer.fontSize=20;
                    renderer._fontFn=size=>cron.Font.createFromStyleAndSize(cron.Font.Style.Normal,size);
                    globalThis.__bilSubtitleStyle.apply(renderer);
                    if(renderer.fontSize===35 && globalThis.__bilSubtitleStyle.color(renderer,'background',[])[3]===0.35) {
                        cron.Task.cancelTask(refresh);cron.log('BIL_SUBTITLE_UPDATE_OK');
                    }
                },0.01,true);
            } catch(error) { cron.log('BIL_SUBTITLE_PROBE_ERROR:'+String(error)); throw error; }
        """.trimIndent()
        val archive = File(context.cacheDir, "subtitle-runtime-probe.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, data) in listOf("index.js" to script, "res/${SubtitleStylePayload.CONFIG}" to styleFile.readText(), "info.json" to "{\"name\":\"innocent-subtitle-probe\",\"version\":\"1\",\"commit\":\"8\"}")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(data.toByteArray()); zip.closeEntry()
            }
        }
        java.util.zip.ZipFile(archive).use { original ->
            val replacement = File(context.cacheDir, "subtitle-runtime-probe-font.zip")
            ZipOutputStream(replacement.outputStream()).use { output ->
                for(entry in original.entries()) {
                    output.putNextEntry(ZipEntry(entry.name)); original.getInputStream(entry).use { it.copyTo(output) };output.closeEntry()
                }
                output.putNextEntry(ZipEntry("res/innocent-subtitle/font.ttf")); font.inputStream().use { it.copyTo(output) }; output.closeEntry()
            }
            check(replacement.renameTo(archive))
        }
        val packageClass = host.loadClass("com.bilibili.cron.ChronosPackage")
        val pkg = requireNotNull(packageClass.getMethod("createPackageFromFile", Context::class.java, File::class.java).invoke(null, context, archive))
        val loggerClass = host.loadClass("com.bilibili.cron.ChronosPackageRunner\$ExternalLogger")
        val passed = CountDownLatch(1)
        val updated = CountDownLatch(1)
        val errors = java.util.concurrent.CopyOnWriteArrayList<String>()
        val logger = Proxy.newProxyInstance(host, arrayOf(loggerClass)) { _, _, args ->
            val text = args?.filterIsInstance<String>()?.joinToString(" ").orEmpty()
            if (text.contains("BIL_SUBTITLE_PROBE_OK")) passed.countDown()
            if (text.contains("BIL_SUBTITLE_UPDATE_OK")) updated.countDown()
            if (text.isNotBlank() && errors.size < 12) errors += text.take(600)
            null
        }
        val reader = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 2)
        val rendererClass = host.loadClass("com.bilibili.cron.ChronosRenderer")
        val renderer = rendererClass.getConstructor(Context::class.java, Surface::class.java, loggerClass).newInstance(context, reader.surface, logger)
        try {
            val callback = host.loadClass("com.bilibili.cron.ChronosPackage\$LoadCompleteCallback")
            val listener = Proxy.newProxyInstance(host, arrayOf(callback)) { _, _, _ -> null }
            rendererClass.getMethod("runPackage", packageClass, callback).invoke(renderer, pkg, listener)
            val update = rendererClass.getMethod("updateAndDraw", Float::class.javaPrimitiveType)
            repeat(60) {
                if (passed.count > 0) { update.invoke(renderer, 1f / 60f); passed.await(50, TimeUnit.MILLISECONDS) }
            }
            assertTrue("Native runtime probe: $errors", passed.count == 0L)
            val sandbox = packageClass.getMethod("getSandBoxDirectory").invoke(pkg) as String
            SubtitleStyleStore.registerPrivatePackage(context, sandbox)
            assertTrue(SubtitleStyleStore(context).save(PlayerSubtitleStyle(sizePercent = 175, backgroundOpacity = 35)) > 0)
            repeat(60) {
                if (updated.count > 0) { update.invoke(renderer, 1f / 60f); updated.await(50, TimeUnit.MILLISECONDS) }
            }
            assertTrue("Native configuration refresh: $errors", updated.count == 0L)
        } finally {
            rendererClass.getMethod("release").invoke(renderer)
            packageClass.getMethod("release").invoke(pkg)
            reader.close()
            SubtitleStyleStore(context).save(null)
            styleFile.delete()
            archive.delete()
        }
    }
}
