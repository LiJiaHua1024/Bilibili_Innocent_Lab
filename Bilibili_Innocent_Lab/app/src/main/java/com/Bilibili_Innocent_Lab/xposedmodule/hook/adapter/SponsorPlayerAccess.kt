package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 31 样本共享的业务边界；仅安装期查找，运行期只用已验证的成员。 */
internal class SponsorPlayerAccess private constructor(
    val run: Method,
    val wrapper: Constructor<*>,
    val active: Method,
    private val params: Method,
    private val bvid: Method,
    private val cid: Method,
    private val aid: Method,
    private val business: Method,
    private val ugc: Any,
    val position: Method,
    val duration: Method,
    val state: Method,
    val seek: Method,
    val registerProgress: Method,
    val unregisterProgress: Method,
    val registerSeek: Method,
    val unregisterSeek: Method,
    val registerState: Method,
    val unregisterState: Method,
    val registerRelease: Method,
    val unregisterRelease: Method,
    val progressObserver: Class<*>,
    val seekObserver: Class<*>,
    val stateObserver: Class<*>,
    val releaseObserver: Class<*>,
    val suspended: Any
) {
    fun video(owner: Any): SponsorVideoId? = runCatching {
        val playable = active.invoke(owner) ?: return null
        val values = params.invoke(playable) ?: return null
        if (business.invoke(values) !== ugc || (aid.invoke(values) as? Long ?: 0) <= 0) return null
        SponsorVideoId(bvid.invoke(values) as? String ?: return null, cid.invoke(values) as? Long ?: return null)
    }.getOrNull()

    companion object {
        const val RUN_CLASS = "com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer\$runPlayable\$1"
        const val PARAMS_CLASS = "com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams"
        const val CORE_CLASS = "tv.danmaku.biliplayerv2.service.IPlayerCoreService"
        const val WRAPPER_CLASS = "com.bilibili.ship.theseus.united.player.oldway.playercontainer.TheseusPlayerContainerProvider\$providePlayerContainer\$playerContainer\$1\$1\$1"
        private const val FAMILY = "com.bilibili.ship.theseus.keel.player."

        fun resolve(loader: ClassLoader): SponsorPlayerAccess? = runCatching {
            val continuation = Lookup.classOrNull(loader, RUN_CLASS) ?: return null
            val params = Lookup.classOrNull(loader, PARAMS_CLASS) ?: return null
            val core = Lookup.classOrNull(loader, CORE_CLASS)?.takeIf { it.isInterface } ?: return null
            val wrapper = Lookup.classOrNull(loader, WRAPPER_CLASS) ?: return null
            val progress = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.PlayerProgressObserver") ?: return null
            val seekObserver = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.PlayerSeekObserver") ?: return null
            val stateObserver = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.PlayerStateObserver") ?: return null
            val release = Lookup.classOrNull(loader, "tv.danmaku.biliplayerv2.service.IPlayerReleaseObserver") ?: return null
            if (listOf(progress, seekObserver, stateObserver, release).any { !it.isInterface }) return null
            if (method(progress, "onPlayerProgressChange", Void.TYPE, classOf<Int>(), classOf<Int>()) == null ||
                method(seekObserver, "onSeekStart", Void.TYPE, classOf<Long>()) == null ||
                method(seekObserver, "onSeekComplete", Void.TYPE, classOf<Long>()) == null ||
                method(stateObserver, "onPlayerStateChanged", Void.TYPE, classOf<Int>()) == null ||
                method(release, "onPlayerWillRelease", Void.TYPE) == null) return null
            val owner = Lookup.declaredFields(continuation, true) {
                !Modifier.isStatic(it.modifiers) && it.type.name.startsWith(FAMILY) && it.type != continuation
            }.singleOrNull()?.type ?: return null
            val run = Lookup.declaredMethods(owner, true) {
                !Modifier.isStatic(it.modifiers) && it.returnType == classOf<Any>() && it.parameterCount == 2 &&
                    it.parameterTypes[0].isInterface && it.parameterTypes[0].name.startsWith(FAMILY) &&
                    it.parameterTypes[1].name in setOf("kotlin.coroutines.Continuation", "kotlin.coroutines.jvm.internal.ContinuationImpl")
            }.singleOrNull() ?: return null
            val active = Lookup.declaredMethods(owner, true) {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == run.parameterTypes[0]
            }.singleOrNull() ?: return null
            val parameters = Lookup.methods(run.parameterTypes[0], true, true) {
                !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == params
            }.distinctBy { it.name to it.parameterTypes.toList() }.singleOrNull() ?: return null
            val constructor = Lookup.declaredConstructors(wrapper, true) {
                it.parameterTypes.map(Class<*>::getName) == listOf(CORE_CLASS, owner.name, "kotlinx.coroutines.CoroutineScope")
            }.singleOrNull() ?: return null
            if (!core.isAssignableFrom(wrapper)) return null
            val business = Lookup.inheritedMethodOrNull(params, "getBizType")?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.returnType.isEnum
            } ?: return null
            val ugc = Lookup.fieldOrNull(business.returnType, "UGC")?.takeIf {
                Modifier.isStatic(it.modifiers) && it.type == business.returnType
            }?.get(null) ?: return null
            val suspended = Lookup.classOrNull(loader, "kotlin.coroutines.intrinsics.CoroutineSingletons")?.enumConstants
                ?.singleOrNull { (it as? Enum<*>)?.name == "COROUTINE_SUSPENDED" } ?: return null
            SponsorPlayerAccess(run, constructor, active, parameters,
                method(params, "getBvId", classOf<String>()) ?: return null,
                method(params, "getCid", classOf<Long>()) ?: return null,
                method(params, "getAvid", classOf<Long>()) ?: return null, business, ugc,
                method(core, "getCurrentPosition", classOf<Int>()) ?: return null,
                method(core, "getDuration", classOf<Int>()) ?: return null,
                method(core, "getState", classOf<Int>()) ?: return null,
                method(core, "seekTo", Void.TYPE, classOf<Int>(), classOf<Boolean>()) ?: return null,
                method(core, "registerPlayerProgressObserver", Void.TYPE, progress) ?: return null,
                method(core, "unregisterPlayerProgressObserver", Void.TYPE, progress) ?: return null,
                method(core, "registerSeekObserver", Void.TYPE, seekObserver) ?: return null,
                method(core, "unregisterSeekObserver", Void.TYPE, seekObserver) ?: return null,
                method(core, "registerState", Void.TYPE, stateObserver, IntArray::class.java) ?: return null,
                method(core, "unregisterState", Void.TYPE, stateObserver) ?: return null,
                method(core, "addPlayerReleaseObserver", Void.TYPE, release) ?: return null,
                method(core, "removePlayerReleaseObserver", Void.TYPE, release) ?: return null,
                progress, seekObserver, stateObserver, release, suspended)
        }.getOrNull()

        private fun method(owner: Class<*>, name: String, result: Class<*>, vararg parameters: Class<*>): Method? =
            Lookup.inheritedMethodOrNull(owner, name, *parameters)?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.returnType == result
            }
    }
}
