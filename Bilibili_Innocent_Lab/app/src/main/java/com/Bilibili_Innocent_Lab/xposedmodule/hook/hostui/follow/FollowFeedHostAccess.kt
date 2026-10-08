package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** 只缓存成员；不保存动态正文、模型实例或历史 ViewHolder。未知模型故障开放。 */
internal class FollowFeedHostAccess private constructor(
    private val module: Class<*>,
    private val owner: java.lang.reflect.Method,
    private val identity: java.lang.reflect.Method,
    private val first: java.lang.reflect.Method,
    private val last: java.lang.reflect.Method,
    private val localOwner: java.lang.reflect.Method,
    private val quoted: java.lang.reflect.Method,
    private val modules: java.lang.reflect.Method,
    private val kind: java.lang.reflect.Method,
    private val video: Class<*>
) {
    val moduleClass: Class<*> get() = module
    private val holders = HashMap<Class<*>, Field?>()

    fun handles(holder: Any): Boolean = holders[holder.javaClass] != null

    fun row(holder: Any, position: Int): FollowFeedRow? {
        val type = holder.javaClass
        val field = if (holders.containsKey(type)) holders[type] else {
            var current: Class<*>? = type
            var found: Field? = null
            while (current != null && found == null) {
                found = current.declaredFields.firstOrNull {
                    !Modifier.isStatic(it.modifiers) && it.type == module
                }?.apply { isAccessible = true }
                current = current.superclass
            }
            holders[type] = found
            found
        } ?: return null
        val value = field.get(holder) ?: return null
        val item = owner.invoke(value) ?: return null
        val id = identity.invoke(item) as? Long ?: return null
        if (id <= 0) return null // 最常访问、排序和加载占位不属于动态卡片。
        val local = localOwner.invoke(value) ?: return null
        val localModules = modules.invoke(local) as? List<*> ?: return null
        if (localModules.none { it === value }) return null
        val reference = if (quoted.invoke(value) == true) {
            // d0/g0 是外层动态的首尾；引用区域必须使用 O().i() 自己的首尾。
            val referenceId = identity.invoke(local) as? Long ?: return null
            if (referenceId <= 0) return null
            FollowFeedReference(referenceId, localModules.firstOrNull() === value,
                localModules.lastOrNull() === value, quoted = true)
        } else if (video.isInstance(value) && localModules.takeWhile { it !== value }.any {
            it != null && (kind.invoke(it) as? Enum<*>)?.name in DESCRIPTION_MODULES
        }) {
            FollowFeedReference(id, first = true, last = true, quoted = false)
        } else null
        return FollowFeedRow(id, first.invoke(value) == true, last.invoke(value) == true, position, reference)
    }

    companion object {
        private val DESCRIPTION_MODULES = setOf("Desc", "OpusDesc", "OpusDescList")

        fun resolve(loader: ClassLoader): FollowFeedHostAccess? = runCatching {
            // 已实查的宿主模型：j0.M() 返回转发归属的顶层 q0，q0.f() 是动态稳定身份。
            // d0/g0 比较顶层模块列表首尾，包含引用中的模块；不能改用嵌套投稿身份 N()。
            val module = KavaMemberLookup.classOrNull(loader, "com.bilibili.bplus.followinglist.model.j0")
                ?: return null
            val item = KavaMemberLookup.classOrNull(loader, "com.bilibili.bplus.followinglist.model.q0")
                ?: return null
            val owner = module.getDeclaredMethod("M").takeIf { it.returnType == item } ?: return null
            val identity = item.getDeclaredMethod("f").takeIf { it.returnType == Long::class.javaPrimitiveType }
                ?: return null
            val first = module.getDeclaredMethod("d0").takeIf { it.returnType == Boolean::class.javaPrimitiveType }
                ?: return null
            val last = module.getDeclaredMethod("g0").takeIf { it.returnType == Boolean::class.javaPrimitiveType }
                ?: return null
            val localOwner = module.getDeclaredMethod("O").takeIf { it.returnType == item } ?: return null
            val quoted = module.getDeclaredMethod("f0").takeIf { it.returnType == Boolean::class.javaPrimitiveType }
                ?: return null
            val modules = item.getDeclaredMethod("i").takeIf { List::class.java.isAssignableFrom(it.returnType) }
                ?: return null
            val kind = module.getDeclaredMethod("T").takeIf { it.returnType.isEnum } ?: return null
            val video = KavaMemberLookup.classOrNull(loader, "com.bilibili.bplus.followinglist.model.m7")
                ?: return null
            FollowFeedHostAccess(module, owner, identity, first, last, localOwner, quoted, modules, kind, video)
        }.getOrNull()
    }
}
