package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isPublic
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 推荐视频播放量过滤范围，单位为播放次数。
 *
 * 0 表示对应边界未设置；损坏的负值或反向区间整体失效，避免误删全部推荐内容。
 * 与 [VideoDurationRange] 同构：未知或非正播放量一律放行。
 */
internal data class VideoPlayCountRange(
    val minimum: Int,
    val maximum: Int
) {
    val isConfigured: Boolean
        get() = minimum != 0 || maximum != 0

    val isValid: Boolean
        get() = minimum >= 0 && maximum >= 0 &&
            (minimum == 0 || maximum == 0 || minimum <= maximum)

    val isEnabled: Boolean
        get() = isConfigured && isValid

    /** 未知或非正播放量保守放行；恰好等于上下限的卡片保留。 */
    fun shouldRemove(playCount: Long?): Boolean {
        if (!isEnabled || playCount == null || playCount <= 0L) return false
        return (minimum > 0 && playCount < minimum.toLong()) ||
            (maximum > 0 && playCount > maximum.toLong())
    }
}

/** 安装期构建并缓存的详情页播放量 getter 链：卡片 → Stat → StatInfo → value。 */
internal data class VideoPlayCountMethodPath(
    val itemGetter: Method,
    val statGetter: Method,
    val vtGetter: Method,
    val valueGetter: Method
)

/**
 * 详情页走已适配的公开 getter 链；首页走 Class 缓存的封面左文案解析。
 *
 * 热路径只调用缓存后的 Method/Field，查找失败或值无效时返回 null（由范围层放行）。
 */
internal object VideoPlayCountReader {

    private val ABSENT = Any()
    private val coverTextGetters = ConcurrentHashMap<Class<*>, Any>()
    private val playerArgsAccess = ConcurrentHashMap<Class<*>, Any>()

    fun buildMethodPaths(
        chains: List<List<Method>>
    ): List<VideoPlayCountMethodPath> = chains.mapNotNull { steps ->
        if (steps.size != 4) return@mapNotNull null
        val item = steps[0]
        val stat = steps[1]
        val vt = steps[2]
        val value = steps[3]
        if (!(item.returnType isSubclassOf stat.declaringClass)) return@mapNotNull null
        if (!(stat.returnType isSubclassOf vt.declaringClass)) return@mapNotNull null
        if (!(vt.returnType isSubclassOf value.declaringClass)) return@mapNotNull null
        VideoPlayCountMethodPath(item, stat, vt, value)
    }.distinctBy { path ->
        path.itemGetter.toGenericString() + "|" +
            path.statGetter.toGenericString() + "|" +
            path.vtGetter.toGenericString() + "|" +
            path.valueGetter.toGenericString()
    }

    fun fromMethods(item: Any, paths: List<VideoPlayCountMethodPath>): Long? {
        paths.forEach { path ->
            if (!path.itemGetter.declaringClass.isInstance(item)) return@forEach
            val card = runCatching { path.itemGetter.invoke(item) }.getOrNull() ?: return@forEach
            if (!path.statGetter.declaringClass.isInstance(card)) return@forEach
            val stat = runCatching { path.statGetter.invoke(card) }.getOrNull() ?: return@forEach
            if (!path.vtGetter.declaringClass.isInstance(stat)) return@forEach
            val vt = runCatching { path.vtGetter.invoke(stat) }.getOrNull() ?: return@forEach
            if (!path.valueGetter.declaringClass.isInstance(vt)) return@forEach
            positiveCount(runCatching { path.valueGetter.invoke(vt) }.getOrNull())?.let {
                return it
            }
        }
        return null
    }

    /**
     * 首页：先用 PlayerArgs 确认是普通投稿（aid>0 且不是直播），再读 `getCoverLeftText1`。
     * 直播人数、封面文案缺失或解析失败一律返回 null。
     */
    fun fromHomeCover(item: Any, playerArgsGetter: Method): Long? {
        if (!playerArgsGetter.declaringClass.isInstance(item)) return null
        val playerArgs = runCatching { playerArgsGetter.invoke(item) }.getOrNull() ?: return null
        if (!isAvVod(playerArgs)) return null
        val text = coverLeftText(item) ?: return null
        return parseCoverText(text)
    }

    fun parseCoverText(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        var text = raw.trim()
        if (text.isEmpty()) return null
        text = text.replace(",", "").replace("，", "").replace(" ", "")
        text = stripSuffix(text, "人在看")
        text = stripSuffix(text, "人看过")
        text = stripSuffix(text, "观看")
        text = stripSuffix(text, "播放")
        text = stripSuffix(text, "次")
        if (text.isEmpty()) return null
        val multiplier = when {
            text.endsWith("亿") || text.endsWith("億") -> {
                text = text.dropLast(1)
                100_000_000L
            }
            text.endsWith("万") || text.endsWith("萬") -> {
                text = text.dropLast(1)
                10_000L
            }
            else -> 1L
        }
        if (text.isEmpty()) return null
        // 十进制精确计算：双精度乘法再截断会把 "1.13万" 算成 11299（两位小数 × 万/亿里有 10832 种），
        // 在"最低播放量"边界上误删。
        val number = runCatching { java.math.BigDecimal(text) }.getOrNull() ?: return null
        if (number.signum() <= 0) return null
        val scaled = number.multiply(java.math.BigDecimal.valueOf(multiplier))
        if (scaled > java.math.BigDecimal.valueOf(Long.MAX_VALUE)) return null
        return scaled.toLong().takeIf { it > 0L }
    }

    private fun coverLeftText(item: Any): String? {
        val getter = cachedCoverTextGetter(item.javaClass) ?: return null
        if (!getter.declaringClass.isInstance(item)) return null
        return runCatching { getter.invoke(item) }.getOrNull() as? String
    }

    private fun cachedCoverTextGetter(type: Class<*>): Method? {
        val cached = coverTextGetters.computeIfAbsent(type) { owner ->
            runCatching {
                uniquePublicGetter(owner, setOf("getCoverLeftText1")) { method ->
                    method.returnType == classOf<String>()
                }
            }.getOrNull() ?: ABSENT
        }
        return cached as? Method
    }

    private fun isAvVod(playerArgs: Any): Boolean {
        val access = cachedPlayerArgsAccess(playerArgs.javaClass) ?: return false
        val aid = readNumber(playerArgs, access.aidGetter, access.aidField) ?: return false
        if (aid <= 0L) return false
        if (readLive(playerArgs, access.liveGetter, access.liveField)) return false
        val roomId = readNumber(playerArgs, access.roomIdGetter, access.roomIdField)
        return roomId == null || roomId <= 0L
    }

    private fun cachedPlayerArgsAccess(type: Class<*>): PlayerArgsAccess? {
        val cached = playerArgsAccess.computeIfAbsent(type) { owner ->
            runCatching { resolvePlayerArgsAccess(owner) }.getOrNull() ?: ABSENT
        }
        return cached as? PlayerArgsAccess
    }

    private fun resolvePlayerArgsAccess(owner: Class<*>): PlayerArgsAccess? {
        val aidGetter = uniquePublicGetter(owner, setOf("getAid")) { isIntegral(it.returnType) }
        val aidField = uniqueField(owner, setOf("aid")) { isIntegral(it.type) }
        if (aidGetter == null && aidField == null) return null
        return PlayerArgsAccess(
            aidGetter = aidGetter,
            aidField = aidField,
            liveGetter = uniquePublicGetter(owner, setOf("isLive", "getIsLive")) { isFlag(it.returnType) },
            liveField = uniqueField(owner, setOf("isLive")) { isFlag(it.type) },
            roomIdGetter = uniquePublicGetter(owner, setOf("getRoomId")) { isIntegral(it.returnType) },
            roomIdField = uniqueField(owner, setOf("roomId")) { isIntegral(it.type) }
        )
    }

    private fun uniquePublicGetter(
        owner: Class<*>,
        names: Set<String>,
        accept: (Method) -> Boolean
    ): Method? = KavaMemberLookup.methods(
        owner,
        includeSuperclasses = true,
        makeAccessible = true
    ) { method ->
        method.name in names && method.parameterCount == 0 &&
            method.isPublic && !method.isStatic && accept(method)
    }.distinctBy(Method::toGenericString).singleOrNull()

    private fun uniqueField(
        owner: Class<*>,
        names: Set<String>,
        accept: (Field) -> Boolean
    ): Field? = KavaMemberLookup.fields(
        owner,
        includeSuperclasses = true,
        makeAccessible = true
    ) { field ->
        field.name in names && !field.isStatic && accept(field)
    }.distinctBy(Field::toGenericString).singleOrNull()

    private fun readNumber(target: Any, getter: Method?, field: Field?): Long? {
        getter?.takeIf { it.declaringClass.isInstance(target) }?.let { method ->
            (runCatching { method.invoke(target) }.getOrNull() as? Number)?.toLong()?.let { return it }
        }
        field?.takeIf { it.declaringClass.isInstance(target) }?.let { member ->
            return (runCatching { member.get(target) }.getOrNull() as? Number)?.toLong()
        }
        return null
    }

    private fun readLive(target: Any, getter: Method?, field: Field?): Boolean {
        val raw = getter?.takeIf { it.declaringClass.isInstance(target) }?.let { method ->
            runCatching { method.invoke(target) }.getOrNull()
        } ?: field?.takeIf { it.declaringClass.isInstance(target) }?.let { member ->
            runCatching { member.get(target) }.getOrNull()
        }
        return when (raw) {
            is Boolean -> raw
            is Number -> raw.toLong() != 0L
            else -> false
        }
    }

    private fun isIntegral(type: Class<*>): Boolean = type in setOf(
        classOf<Long>(),
        classOf<Long>(primitiveType = false),
        classOf<Int>(),
        classOf<Int>(primitiveType = false)
    )

    private fun isFlag(type: Class<*>): Boolean = type in setOf(
        classOf<Boolean>(),
        classOf<Boolean>(primitiveType = false),
        classOf<Int>(),
        classOf<Int>(primitiveType = false)
    )

    private fun stripSuffix(value: String, suffix: String): String =
        if (value.endsWith(suffix)) value.substring(0, value.length - suffix.length) else value

    private fun positiveCount(value: Any?): Long? = (value as? Number)
        ?.toLong()
        ?.takeIf { it > 0L }

    private data class PlayerArgsAccess(
        val aidGetter: Method?,
        val aidField: Field?,
        val liveGetter: Method?,
        val liveField: Field?,
        val roomIdGetter: Method?,
        val roomIdField: Field?
    )
}
