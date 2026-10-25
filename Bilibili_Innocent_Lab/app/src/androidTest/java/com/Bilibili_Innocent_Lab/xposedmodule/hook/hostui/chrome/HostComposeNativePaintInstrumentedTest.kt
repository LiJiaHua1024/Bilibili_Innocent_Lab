package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostChromeAppearance
import com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance.HostGlassMaterial
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.NativePaintFixtureActivity
import com.lumen.coacervation.engine.host.LumenPrismGlass
import com.lumen.coacervation.engine.host.LumenPrismFallbackReason
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceBinding
import dalvik.system.DexClassLoader
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Manual exact-APK public Compose rendering, without installing hooks or changing host preferences. */
@RunWith(AndroidJUnit4::class)
class HostComposeNativePaintInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Test fun nativeBackdropPaintAndFailureRecoveryKeepMaterialAndInputAcrossThemes() {
        val args = InstrumentationRegistry.getArguments()
        val opaquePage = args.getString("opaqueComposePage") == "true"
        val expected = args.getString("hostSha256")
        assumeTrue("An exact installed host APK must be explicitly selected", expected != null)
        val context = instrumentation.targetContext
        @Suppress("DEPRECATION")
        val identity = context.packageManager.getPackageInfo("tv.danmaku.bili", 0)
        assertEquals(args.getString("hostVersionCode")!!.toLong(), identity.versionCode.toLong())
        val apk = File(identity.applicationInfo!!.sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { stream ->
            val bytes = ByteArray(128 * 1024)
            while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        assertEquals(expected, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        val loader = DexClassLoader(apk.path, context.codeCacheDir.path, null, ClassLoader.getSystemClassLoader())
        val native = Native(loader)
        assertTrue("Native background image fills its containing row", native.api.fillsBackdrop(native.full(native.empty)))
        assertFalse("Native foreground icon sizing must not be classified as a backdrop",
            native.api.fillsBackdrop(native.height(native.width(native.empty), 22f)))
        val runId = args.getString("captureRunId") ?: "manual-${SystemClock.uptimeMillis()}"
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,32}")))
        val output = File(context.getExternalFilesDir(null), "topbar-native-paint-$runId").apply { mkdirs() }
        for (dark in listOf(false, true)) for (material in listOf(HostGlassMaterial.SOFT, HostGlassMaterial.LIQUID, HostGlassMaterial.PRISM)) {
            ActivityScenario.launch(NativePaintFixtureActivity::class.java).use { scenario ->
                lateinit var owner: View
                lateinit var painter: HostComposeChromePainter
                lateinit var activity: NativePaintFixtureActivity
                var cleared = false
                var clicks = 0
                var applied = 0
                var imagePresent = true
                var compositions = 0
                var closeOwners: () -> Unit = {}
                val errors = ArrayList<Throwable>()
                val density = context.resources.displayMetrics.density
                fun install(it: NativePaintFixtureActivity, suppress: Boolean) {
                    cleared = suppress; applied = 0; imagePresent = true; compositions = 0
                    activity = it
                    val config = Configuration(it.resources.configuration).apply {
                        uiMode = uiMode and Configuration.UI_MODE_NIGHT_MASK.inv() or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val nativeContext = it.createConfigurationContext(config)
                    owner = native.view.getConstructor(Context::class.java).newInstance(nativeContext) as View
                    painter = HostComposeChromePainter(owner, true, true, HostChromeAppearance(material = material),
                        null, native.api, { applied++ }, { error -> errors += error })
                    assertFalse("Keep native paint until the replacement has drawn", painter.backdropActive)
                    val root = FrameLayout(it).apply {
                        keepScreenOn = true
                        setBackgroundColor(if (opaquePage) Color.BLACK else if (dark) 0xFF30343A.toInt() else Color.WHITE)
                        addView(owner, FrameLayout.LayoutParams(-1, (72 * density).toInt()).apply {
                            topMargin = (120 * density).toInt()
                        })
                    }
                    closeOwners = native.owners(root, it)
                    val cleanup = closeOwners
                    it.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                        override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) = cleanup()
                    })
                    it.setContentView(root)
                    native.content(owner, native.function(2) { values ->
                        compositions++
                        val composer = values!![0]!!
                        // A real Compose page may paint its opaque base inside the composition;
                        // the unrelated Window/View background behind it can still be black.
                        val page = native.background(native.full(native.empty), if (dark) 0xFF30343A.toInt() else Color.WHITE)
                        native.box(page, composer, native.function(3) { pageScope ->
                            val rowComposer = pageScope!![1]!!
                            val sized = native.height(native.width(native.empty), 48f)
                            native.box(painter.modifier(sized, HostChromeLayer.SURFACE), rowComposer,
                                native.function(3) { inner ->
                                    val c = inner!![1]!!
                                    native.group(c, 0x42494C) {
                                        imagePresent = !cleared || !painter.omitBackdropImage
                                        if (imagePresent) {
                                            val black = native.background(native.full(native.empty), Color.BLACK)
                                            val decoration = if (cleared) painter.modifier(black, HostChromeLayer.TOP_BACKDROP) else black
                                            // Image helpers may append their own paint after the supplied Modifier.
                                            val derived = native.paint(decoration) { canvas, w, h ->
                                                canvas.drawRect(0f, 0f, w, h, Paint().apply { color = Color.BLACK })
                                            }
                                            native.leaf(derived, c)
                                        }
                                    }
                                    val foreground = native.paint(native.full(native.empty)) { canvas, w, h ->
                                        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                            color = Color.MAGENTA; textSize = h * .35f
                                        }
                                        canvas.drawText("LIVE   HOME   HOT", w * .18f, h * .65f, text)
                                    }
                                    native.leaf(native.click(foreground) { clicks++ }, c)
                                })
                        })
                    })
                }
                scenario.onActivity { install(it, false) }
                settle(owner) { applied > 0 }
                fun snapshot(label: String): Bitmap {
                    val bitmap = copy(activity, owner)
                    File(output, "${dark}-${material.name}-$label.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    return bitmap
                }
                snapshot("native-black").useBitmap { image -> assertTrue("Baseline native strip must be opaque black", brightness(image) < 12) }
                main { native.dispose(owner); closeOwners() }
                scenario.onActivity { install(it, true) }
                settle(owner) { applied > 0 && !imagePresent && hasSampledFrame(painter) }
                snapshot("cleared").useBitmap { image ->
                    // Preserve the original non-black check (sum > 35) in per-channel units.
                    assertTrue("Native opaque paint must not cover the Lumen surface", brightness(image) > 12)
                    if (opaquePage) assertTrue("The visible Compose page base must survive, not the hidden black Window: ${brightness(image)}",
                        if (dark) brightness(image) < 85 else brightness(image) > 220)
                    assertTrue("Native foreground remains visible", magentaPixels(image) > 10)
                }
                var before = 0
                main { before = compositions; painter.requestBackdropRefresh() }
                SystemClock.sleep(300)
                main { assertEquals("Draw refresh must not recompose the native row", before, compositions) }
                click(owner, density, painter)
                main { assertEquals(1, clicks); assertTrue("Unexpected material failure: $errors", errors.isEmpty()) }
                val canceled = SystemClock.uptimeMillis()
                touch(owner, density, MotionEvent.ACTION_DOWN, canceled, painter)
                touch(owner, density, MotionEvent.ACTION_CANCEL, canceled, painter)
                instrumentation.waitForIdleSync()
                main { assertEquals("Native cancellation must not click", 1, clicks) }
                val held = SystemClock.uptimeMillis()
                touch(owner, density, MotionEvent.ACTION_DOWN, held, painter)
                main { painter.failure(IllegalStateException("fixture intentional failure")) }
                settle(owner) { imagePresent }
                touch(owner, density, MotionEvent.ACTION_UP, held, painter)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(320)
                snapshot("fallback").useBitmap { image -> assertTrue("Native image composition restores paint", brightness(image) < 12) }
                main { assertEquals("Native press survives material failure and image reinsertion", 2, clicks); native.dispose(owner); closeOwners() }
            }
        }
    }

    private fun settle(owner: View, ready: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 7000
        var good = false
        while (SystemClock.uptimeMillis() < until) {
            main { good = owner.isAttachedToWindow && owner.hasWindowFocus() && owner.width > 0 && ready() }
            if (good) break
            SystemClock.sleep(32)
        }
        assertTrue("Fixture must have layout, focus and a drawn material before pixel/input checks", good)
        SystemClock.sleep(160)
    }
    private fun touch(owner: View, density: Float, action: Int, down: Long, painter: HostComposeChromePainter) {
        val point = IntArray(2)
        main { assertTrue(owner.hasWindowFocus()); owner.getLocationOnScreen(point) }
        val x = point[0] + owner.width * .8f; val y = point[1] + 24 * density
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            main {
                val observed = MotionEvent.obtain(event)
                try { observed.offsetLocation(-point[0].toFloat(), -point[1].toFloat()); painter.touch(owner, observed) }
                finally { observed.recycle() }
            }
            instrumentation.sendPointerSync(event)
        } finally { event.recycle() }
    }
    private fun click(owner: View, density: Float, painter: HostComposeChromePainter) {
        val down = SystemClock.uptimeMillis()
        touch(owner, density, MotionEvent.ACTION_DOWN, down, painter)
        SystemClock.sleep(48)
        touch(owner, density, MotionEvent.ACTION_UP, down, painter)
        instrumentation.waitForIdleSync()
    }
    private fun copy(activity: NativePaintFixtureActivity, owner: View): Bitmap {
        lateinit var image: Bitmap; lateinit var bounds: Rect
        main {
            val at = IntArray(2); owner.getLocationInWindow(at)
            bounds = Rect(at[0], at[1], at[0] + owner.width, at[1] + owner.height)
            image = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        }
        val done = CountDownLatch(1); var result = -1
        PixelCopy.request(activity.window, bounds, image, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(done.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, result)
        return image
    }
    private fun brightness(image: Bitmap): Int {
        val color = image.getPixel(image.width / 2, (image.height / 6).coerceAtLeast(1))
        return (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
    }
    private fun hasSampledFrame(painter: HostComposeChromePainter): Boolean {
        fun field(name: String) = HostComposeChromePainter::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(painter)
        val prism = field("prism") as? LumenPrismGlass
        if (prism != null) return prism.diagnostics().let {
            it.glassVisible && it.fallbackReason == LumenPrismFallbackReason.NONE
        }
        return (field("binding") as? LumenSurfaceBinding)?.diagnostics()?.let {
            it.hasVisibleFrame && it.backend in setOf(LumenSurfaceBackend.GPU, LumenSurfaceBackend.SOFTWARE)
        } == true
    }
    private fun magentaPixels(image: Bitmap): Int {
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val c = image.getPixel(x, y)
            if (Color.red(c) > 180 && Color.blue(c) > 180 && Color.green(c) < 100) count++
        }
        return count
    }
    private inline fun Bitmap.useBitmap(block: (Bitmap) -> Unit) { try { block(this) } finally { recycle() } }

    private class Native(private val loader: ClassLoader) {
        val view = loader.loadClass("androidx.compose.ui.platform.ComposeView")
        val api = requireNotNull(HostComposeChromeApi.resolve(loader))
        val empty = api.modifier.getField("Companion").get(null)!!
        private val f1 = loader.loadClass("kotlin.jvm.functions.Function1")
        private val f2 = loader.loadClass("kotlin.jvm.functions.Function2")
        private val f3 = loader.loadClass("kotlin.jvm.functions.Function3")
        private val unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null)!!
        private fun method(owner: String, prefix: String, accept: (Method) -> Boolean) =
            loader.loadClass(owner).declaredMethods.single { it.name.startsWith(prefix) && !it.name.endsWith("\$default") && accept(it) }.apply { isAccessible = true }
        private val width = method("androidx.compose.foundation.layout.SizeKt", "fillMaxWidth") { it.parameterCount == 2 }
        private val full = method("androidx.compose.foundation.layout.SizeKt", "fillMaxSize") { it.parameterCount == 2 }
        private val height = method("androidx.compose.foundation.layout.SizeKt", "height-") { it.parameterCount == 2 }
        private val background = loader.loadClass("androidx.compose.foundation.BackgroundKt").declaredMethods.single {
            it.name.startsWith("background-") && it.name.endsWith("\$default") && it.parameterCount == 5 &&
                it.parameterTypes[1] == Long::class.javaPrimitiveType
        }.apply { isAccessible = true }
        private val behind = method("androidx.compose.ui.draw.DrawModifierKt", "drawBehind") { it.parameterCount == 2 }
        private val box = method("androidx.compose.foundation.layout.BoxKt", "Box") { it.parameterCount == 7 }
        private val leaf = method("androidx.compose.foundation.layout.BoxKt", "Box") { it.parameterCount == 3 }
        private val alignment = loader.loadClass("androidx.compose.ui.Alignment").getField("Companion").get(null)!!.let {
            it.javaClass.getMethod("getTopStart").invoke(it)
        }
        private val clickable = method("androidx.compose.foundation.ClickableKt", "clickable-") { it.parameterCount == 5 }
        private val drawScope = loader.loadClass("androidx.compose.ui.graphics.drawscope.DrawScope")
        private val size = drawScope.declaredMethods.single { it.name.startsWith("getSize-") }
        private val context = drawScope.getMethod("getDrawContext")
        private val canvas = loader.loadClass("androidx.compose.ui.graphics.drawscope.DrawContext").getMethod("getCanvas")
        private val native = loader.loadClass("androidx.compose.ui.graphics.AndroidCanvas_androidKt").declaredMethods.single { it.name == "getNativeCanvas" }
        fun function(arity: Int, block: (Array<out Any?>?) -> Unit): Any {
            val type = when (arity) { 1 -> f1; 2 -> f2; 3 -> f3; else -> loader.loadClass("kotlin.jvm.functions.Function0") }
            return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) { "invoke" -> { block(args); unit }; "equals" -> proxy === args?.firstOrNull(); "hashCode" -> System.identityHashCode(proxy); "toString" -> "native fixture"; else -> null }
            }
        }
        fun owners(view: View, context: Context): () -> Unit {
            val lifeOwner = loader.loadClass("androidx.lifecycle.LifecycleOwner")
            val savedOwner = loader.loadClass("androidx.savedstate.SavedStateRegistryOwner")
            val modelOwner = loader.loadClass("androidx.lifecycle.ViewModelStoreOwner")
            var lifecycle: Any? = null
            var savedState: Any? = null
            val store = loader.loadClass("androidx.lifecycle.ViewModelStore").getConstructor().newInstance()
            val owner = Proxy.newProxyInstance(loader, arrayOf(lifeOwner, savedOwner, modelOwner)) { proxy, method, args ->
                when (method.name) {
                    "getLifecycle" -> lifecycle
                    "getSavedStateRegistry" -> savedState
                    "getViewModelStore" -> store
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "isolated native fixture owner"
                    else -> null
                }
            }
            val registry = loader.loadClass("androidx.lifecycle.LifecycleRegistry")
            lifecycle = registry.getConstructor(lifeOwner).newInstance(owner)
            val controllerType = loader.loadClass("androidx.savedstate.SavedStateRegistryController")
            val create = controllerType.declaredMethods.single { java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                it.returnType == controllerType && it.parameterTypes.contentEquals(arrayOf(savedOwner)) }.apply { isAccessible = true }
            val controller = create.invoke(null, owner)
            savedState = controllerType.getMethod("getSavedStateRegistry").invoke(controller)
            controllerType.getMethod("performAttach").invoke(controller)
            controllerType.getMethod("performRestore", android.os.Bundle::class.java).invoke(controller, null)
            val event = loader.loadClass("androidx.lifecycle.Lifecycle\$Event")
            val handle = registry.getMethod("handleLifecycleEvent", event)
            fun dispatch(name: String) { handle.invoke(lifecycle, event.enumConstants.single { (it as Enum<*>).name == name }) }
            val resources = context.packageManager.getResourcesForApplication("tv.danmaku.bili")
            for (key in listOf("view_tree_lifecycle_owner", "view_tree_saved_state_registry_owner", "view_tree_view_model_store_owner")) {
                val id = resources.getIdentifier(key, "id", "tv.danmaku.bili")
                check(id != 0) { "Missing native owner tag: $key" }
                view.setTag(id, owner)
            }
            for (name in listOf("ON_CREATE", "ON_START", "ON_RESUME")) dispatch(name)
            var closed = false
            return {
                if (!closed) {
                    closed = true
                    for (name in listOf("ON_PAUSE", "ON_STOP", "ON_DESTROY")) dispatch(name)
                    store.javaClass.getMethod("clear").invoke(store)
                }
            }
        }
        private val composerType = loader.loadClass("androidx.compose.runtime.Composer")
        private val startGroup = runCatching { composerType.getMethod("startReplaceGroup", Int::class.javaPrimitiveType) }
            .getOrElse { composerType.getMethod("startReplaceableGroup", Int::class.javaPrimitiveType) }
        private val endGroup = composerType.getMethod(if (startGroup.name == "startReplaceGroup") "endReplaceGroup" else "endReplaceableGroup")
        fun group(composer: Any, key: Int, content: () -> Unit) {
            startGroup.invoke(composer, key)
            try { content() } finally { endGroup.invoke(composer) }
        }
        fun width(modifier: Any) = width.invoke(null, modifier, 1f)!!
        fun full(modifier: Any) = full.invoke(null, modifier, 1f)!!
        fun height(modifier: Any, dp: Float) = height.invoke(null, modifier, dp)!!
        fun background(modifier: Any, argb: Int) = background.invoke(null, modifier, argb.toLong() shl 32, null, 2, null)!!
        fun leaf(modifier: Any, composer: Any) { leaf.invoke(null, modifier, composer, 0) }
        fun box(modifier: Any, composer: Any, content: Any) { box.invoke(null, modifier, alignment, false, content, composer, 0, 0) }
        fun content(owner: View, content: Any) { view.getMethod("setContent", f2).invoke(owner, content) }
        fun dispose(owner: View) { view.getMethod("disposeComposition").invoke(owner) }
        fun create(owner: View) { view.getMethod("createComposition").invoke(owner) }
        fun click(modifier: Any, block: () -> Unit) = clickable.invoke(null, modifier, true, null, null, function(0) { block() })!!
        fun paint(modifier: Any, block: (Canvas, Float, Float) -> Unit) = behind.invoke(null, modifier, function(1) { args ->
            val scope = args!![0]!!; val packed = size.invoke(scope) as Long
            block(native.invoke(null, canvas.invoke(context.invoke(scope))) as Canvas,
                Float.fromBits((packed ushr 32).toInt()), Float.fromBits(packed.toInt()))
        })!!
    }
}
