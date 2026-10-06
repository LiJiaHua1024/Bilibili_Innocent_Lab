package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Modifier

/** Follow the host's stagger/ResManager file lookup, then apply bounded decoding on our worker. */
internal class BrandSplashBitmapDecoder(private val environment: HookEnvironment) {
    private class Capture(var path: String? = null)
    private val captures = BrandSplashScopes<Capture>()
    private val loader = requireNotNull(environment.classLoader)
    private val info = requireNotNull(KavaMemberLookup.classOrNull(loader, BrandSplashSkipFeatureInstaller.NATIVE_INFO))
    private val createInfo = KavaMemberLookup.constructorOrNull(info)
        ?: error("missing-brand-info-constructor")
    private val setHash = requireNotNull(KavaMemberLookup.methodOrNull(info, "setThumbHash", classOf<String>()))
    private val setThumb = requireNotNull(KavaMemberLookup.methodOrNull(info, "setThumb", classOf<String>()))
    private val setDefault = requireNotNull(KavaMemberLookup.methodOrNull(info, "setDefault", Boolean::class.javaPrimitiveType!!))
    private val decoder = DECODER_OWNERS.mapNotNull { name ->
        val owner = KavaMemberLookup.classOrNull(loader, name) ?: return@mapNotNull null
        KavaMemberLookup.declaredMethods(owner).singleOrNull { method ->
            method.name == "a" && Modifier.isStatic(method.modifiers) && method.returnType == classOf<Bitmap>() &&
                method.parameterTypes.contentEquals(arrayOf(info))
        }
    }.distinct().singleOrNull() ?: error("ambiguous-brand-bitmap-decoder")

    fun install() {
        val parameters = listOf(arrayOf(classOf<String>()), arrayOf(classOf<String>(), classOf<BitmapFactory.Options>()))
        parameters.forEachIndexed { index, types ->
            environment.registrar.exact("brand.custom.decode.capture.$index", classOf<BitmapFactory>(), "decodeFile", *types) {
                before {
                    val capture = captures.current() ?: return@before
                    // This scope is entered only by our worker. No disk work happens in the callback.
                    capture.path = argOrNull(0) as? String
                    result = null
                }
            }
        }
    }

    fun decode(image: BrandSplashModelAccess.Image): Bitmap? {
        val value = createInfo.newInstance()
        setHash.invoke(value, image.hash)
        setThumb.invoke(value, image.url)
        setDefault.invoke(value, false)
        val capture = Capture()
        captures.enter(capture)
        val returned = try { decoder.invoke(null, value) as? Bitmap } finally { captures.leave() }
        val path = capture.path
        if (path == null) {
            // A runtime may inline the one-argument factory. Never enlarge or retain an oversized result.
            return returned?.takeIf { it.width.toLong() * it.height <= MAX_PIXELS }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val sample = brandSplashBitmapSampleSize(bounds.outWidth, bounds.outHeight)
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        internal const val MAX_PIXELS = 4_194_304L
        /** Actual BaseBrandSplashFragment call targets, verified over all 31 original APKs. */
        private val DECODER_OWNERS = listOf(
            "Cg1.a", "Cq1.a", "Eg1.a", "Ip1.a", "Jc1.a", "Mp1.a", "Sa1.a", "Sl1.a", "Wg1.a", "Zq1.a",
            "dc6.a", "eg1.a", "ep1.a", "fg1.a", "j46.a", "jd6.a", "ka1.a", "nb1.a", "nh6.a", "no1.a",
            "on1.a", "qc6.a", "qj1.a", "rb6.a", "rg1.a", "sb6.a", "sm1.a", "v86.a", "wq1.a", "zk1.a"
        )
    }
}

/** Rounded-up dimensions keep a narrow image's short side from disappearing from the budget. */
internal fun brandSplashBitmapSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    var sample = 1
    while (((width.toLong() + sample - 1) / sample) *
        ((height.toLong() + sample - 1) / sample) > BrandSplashBitmapDecoder.MAX_PIXELS
    ) sample *= 2
    return sample
}
