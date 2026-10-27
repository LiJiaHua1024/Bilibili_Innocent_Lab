package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistCandidateSelector
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistQuery
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex.DexAssistSession
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.json.JSONObject

/** 缓存里的投屏入口标识；重新使用前必须在当前宿主 ClassLoader 完整验真。 */
data class CastToolbarClickPoint(val owner: String, val lambda: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("owner", owner).put("lambda", lambda)

    companion object {
        fun fromJson(value: JSONObject): CastToolbarClickPoint? {
            // 合并 lambda 的 owner 是全局短类名（如 Bc.c），不能按包名前缀过滤；
            // 只保留可加载类名的字符与长度边界，命中身份仍由 cached() 的完整复核决定。
            val owner = value.optString("owner").takeIf {
                it.length in 1..512 && it.all { c -> c.isLetterOrDigit() || c in "._$" }
            } ?: return null
            return CastToolbarClickPoint(owner, value.optBoolean("lambda", false))
        }
    }
}

/**
 * 原生投屏按钮的点击边界。
 *
 * 8.84.0–8.96.0：Repository 内唯一的非 synthetic 静态 `(Repository, View)V` 回调。
 * 8.97.0 起：宿主不再保留该静态回调，点击变成实例 `onClick(View)V`；
 * 类名被 R8 合并进全局 lambda（8.97.0 仍在 projection 包内，9.x 分散到任意合并类），
 * 但方法体始终携带专用埋点常量，且监听器持有指向 Repository 的捕获字段：
 * 合并分支为 `Object` + `int` 选择子（8.97.0 / 9.17.0 等），单用途 lambda 直接持有 Repository 类型
 * （8.99.0 / 9.12.0）。两种字段布局都要接受，运行时再用字段实值复核。
 *
 * 判定只用能在全部样本上复核的三条特征：埋点常量、`onClick(Landroid/view/View;)V` 的完整 ABI、
 * 能被 Repository 填充的捕获字段。是否实现 `View.OnClickListener` 不作为硬条件——该接口归属
 * 无法逐版静态取证，且宿主能被点击链路调用本身就取决于它，多要求一层只会把可用宿主误判成缺失。
 *
 * 两种形态共用同一定位常量；运行时再用 payload 身份复核，避免把合并类里的其他点击分支当成投屏。
 */
internal object CastToolbarClickLocator {
    /** 宿主投屏点击埋点常量，8.84.0–9.17.0 全部样本都只在投屏点击入口里出现（另有无关的 story 点击同串，按签名排除）。 */
    const val CLICK_MARK = "player.player.screencast.click.player"

    /** 经典播放页的投屏 Repository；theseus2 属于另一套页面架构，不在此定位范围内。 */
    const val REPOSITORY =
        "com.bilibili.ship.theseus.united.page.toolbar.actionview.projection.ToolbarCastScreenRepository"

    val query = DexAssistQuery.CAST_TOOLBAR_CLICK

    sealed interface Point {
        val owner: Class<*>
        val method: Method
        val repository: Class<*>
    }

    data class StaticCallback(
        override val owner: Class<*>,
        override val method: Method,
        override val repository: Class<*>
    ) : Point

    data class LambdaListener(
        override val owner: Class<*>,
        override val method: Method,
        override val repository: Class<*>,
        val payload: List<Field>
    ) : Point

    fun applicable(loader: ClassLoader): Boolean = Lookup.hasClass(loader, REPOSITORY)

    /** 直连形态：8.84.0–8.96.0 的静态回调。 */
    fun direct(loader: ClassLoader): StaticCallback? = runCatching {
        val repository = Lookup.classOrNull(loader, REPOSITORY) ?: return null
        val click = Lookup.declaredMethods(repository, makeAccessible = true) {
            !it.isSynthetic && Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(repository, View::class.java))
        }.singleOrNull() ?: return null
        StaticCallback(repository, click, repository)
    }.getOrNull()

    fun needsQuery(loader: ClassLoader, enabled: Boolean, directFound: Boolean): Boolean =
        enabled && !directFound && applicable(loader)

    /** 后台 DexKit 结果：只接受唯一的 onClick 形态，并要求监听器声明 Object payload。 */
    fun assisted(loader: ClassLoader, session: DexAssistSession?): Point? = runCatching {
        val repository = Lookup.classOrNull(loader, REPOSITORY) ?: return null
        val result = session?.result(query) as? DexAssistResult.Candidates ?: return null
        val candidates = result.methods
            .filter { listenerShape(it, repository) }
            .distinctBy { it.declaringClass.name to it.name }
        val method = DexAssistCandidateSelector.selectUniqueLeaf(candidates) ?: return null
        val payload = payloadFields(method.declaringClass, repository)
        if (payload.isEmpty()) return null
        LambdaListener(method.declaringClass, method, repository, payload)
    }.getOrNull()

    /** 缓存只保存 owner 名称；重新解析时仍要求同一形态与同一 Repository 身份。 */
    fun cached(loader: ClassLoader, point: CastToolbarClickPoint?): Point? = runCatching {
        point ?: return null
        if (!point.lambda) return direct(loader)
        val repository = Lookup.classOrNull(loader, REPOSITORY) ?: return null
        val owner = Lookup.classOrNull(loader, point.owner) ?: return null
        val method = Lookup.methodOrNull(owner, "onClick", View::class.java)
            ?.takeIf { listenerShape(it, repository) } ?: return null
        val payload = payloadFields(owner, repository)
        if (payload.isEmpty()) return null
        LambdaListener(owner, method, repository, payload)
    }.getOrNull()

    /** 合并 lambda 的分支判别：payload 就是该 Repository 实例时才是投屏点击。 */
    fun matches(listener: Any?, point: Point): Boolean {
        if (listener == null) return false
        val lambda = point as? LambdaListener ?: return true
        return lambda.payload.any { field ->
            runCatching { point.repository.isInstance(field.get(listener)) }.getOrDefault(false)
        }
    }

    private fun listenerShape(method: Method, repository: Class<*>): Boolean {
        val owner = method.declaringClass
        if (method.isSynthetic || Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) {
            return false
        }
        if (method.returnType != Void.TYPE || !method.parameterTypes.contentEquals(arrayOf(View::class.java))) {
            return false
        }
        if (owner == repository) return false
        return payloadFields(owner, repository).isNotEmpty()
    }

    /**
     * 捕获字段：声明类型能容纳 Repository 的实例字段。
     * 合并 lambda 用 `Object`（另外还有 int 选择子），单用途 lambda 直接声明 Repository 类型；
     * 两者都只在字段实值确实是该 Repository 实例时才拦截。
     */
    private fun payloadFields(owner: Class<*>, repository: Class<*>): List<Field> =
        Lookup.declaredFields(owner, makeAccessible = true) {
            !Modifier.isStatic(it.modifiers) && it.type.isAssignableFrom(repository)
        }
}
