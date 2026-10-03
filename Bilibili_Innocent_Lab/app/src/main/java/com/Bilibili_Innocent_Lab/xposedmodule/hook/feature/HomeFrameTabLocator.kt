package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.isSubclassOf
import com.highcapable.kavaref.extension.makeAccessible
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.util.WeakHashMap

/**
 * 新首页框架（khome）里顶栏 Tab 与底栏的**共同数据源**定位器。
 *
 * 宿主 8.92.1 起同时带两套首页框架，`MainActivityV2` 按 `tv.danmaku.bili.common.home.c#b()`
 * （frameSwitch）二选一：屏幕尺寸档位 `large`/`medium`（平板、宽屏）、服务端
 * `dd.use_new_home_frame` 或 SP `sp_key_new_home_frame_test` 任一为真就走新框架。
 * 新框架不经过 `TabHost` 和 `HomeFragmentV2`，底栏与顶栏 Tab 都由 Compose 按
 * `HomeTabData`（序列化元素 `top/tab/bottom/top_more/top_left`）渲染。
 *
 * 定位**不依赖任何混淆名**（8.92.1–9.12.0 共 20 个本地宿主里这两个类每版都换名）：
 * 1. 未混淆锚点 [VIEW_MODEL_CLASS] 的字段泛型 → 两跳内收集候选类；
 * 2. 候选类的 Companion `serializer()` → `SerialDescriptor.serialName` 与 [TAB_DATA_SERIAL]
 *    精确比对——序列化名是 JSON 协议身份，R8 不会动；
 * 3. 元素名 → 字段：按 dex 的字段排序规则（名字、再类型描述符）重建声明顺序，
 *    并要求它与反序列化构造器 `(int, 元素类型…)` 的参数窗口逐个类型相同。
 *    元素顺序与字段顺序一致由 `Temp/host-compat/home_frame_audit.py` 在 20 个宿主上
 *    按 iput 源寄存器逐元素实证。
 *
 * 结果按 ClassLoader 缓存，底栏与首页 Tab 两个安装器共用一次解析。
 */
internal object HomeFrameTabLocator {
    const val VIEW_MODEL_CLASS = "tv.danmaku.bili.khome.vm.HomeFrameViewModel"
    const val TAB_DATA_SERIAL = "tv.danmaku.bili.khomeapi.frame.HomeTabData"
    const val TAB_ITEM_SERIAL = "tv.danmaku.bili.khomeapi.frame.HomeTabItemData"
    const val ELEMENT_TAB = "tab"
    const val ELEMENT_BOTTOM = "bottom"

    private const val K_SERIALIZER = "kotlinx.serialization.KSerializer"
    private const val SERIAL_DESCRIPTOR = "kotlinx.serialization.descriptors.SerialDescriptor"
    private const val MAX_GENERIC_DEPTH = 4
    private val ITEM_STRING_ELEMENTS = listOf("id", "name", "uri", "icon", "icon_selected", "tab_id")
    private val SKIPPED_PACKAGES = listOf("java.", "javax.", "kotlin.", "kotlinx.", "android.", "androidx.")

    /** 新框架一条列表型元素的读写入口。 */
    class ListSlot(val element: String, val field: Field)

    /** 条目的语义字段；缺失的元素为 null（旧宿主没有也不影响判定）。 */
    class Item(val itemClass: Class<*>, private val strings: Map<String, Field>, private val defaultSelected: Field?) {
        fun string(item: Any, element: String): String? =
            runCatching { strings[element]?.get(item) as? String }.getOrNull()

        /** 服务端默认选中项：底栏首页 / 顶栏推荐。删掉它宿主会失去初始页，一律保留。 */
        fun isDefaultSelected(item: Any): Boolean =
            runCatching { (defaultSelected?.get(item) as? Int ?: 0) != 0 }.getOrDefault(false)
    }

    class Model(
        val dataClass: Class<*>,
        val constructors: List<Constructor<*>>,
        private val slots: Map<String, ListSlot>,
        val item: Item
    ) {
        fun slot(element: String): ListSlot? = slots[element]
    }

    sealed interface Resolution {
        /** 宿主没有新框架（8.92.1 以前）：不是故障。 */
        data object Absent : Resolution
        data class Failed(val reason: String) : Resolution
        class Found(val model: Model) : Resolution
    }

    private val cache = WeakHashMap<ClassLoader, Resolution>()

    fun resolve(loader: ClassLoader?): Resolution {
        if (loader == null) return Resolution.Failed("no-class-loader")
        synchronized(cache) { cache[loader]?.let { return it } }
        val resolved = runCatching { resolveUncached(loader) }
            .getOrElse { Resolution.Failed("error:${it.javaClass.simpleName}") }
        synchronized(cache) { cache[loader] = resolved }
        return resolved
    }

    private fun resolveUncached(loader: ClassLoader): Resolution {
        val viewModel = KavaMemberLookup.classOrNull(loader, VIEW_MODEL_CLASS)
            ?: return Resolution.Absent
        val serialization = Serialization.load(loader)
            ?: return Resolution.Failed("missing-serialization-runtime")
        val dataClass = candidateClasses(viewModel).firstOrNull { candidate ->
            serialization.describe(candidate)?.serialName == TAB_DATA_SERIAL
        } ?: return Resolution.Failed("missing-tab-data")
        val dataShape = serialization.describe(dataClass) ?: return Resolution.Failed("tab-data-descriptor")
        val dataFields = orderedInstanceFields(dataClass)
        if (!matchesDescriptor(dataClass, dataFields, dataShape)) {
            return Resolution.Failed("tab-data-shape")
        }
        val slots = listOf(ELEMENT_TAB, ELEMENT_BOTTOM).associateWith { element ->
            val index = dataShape.elements.indexOf(element)
            val field = dataFields.getOrNull(index)
                ?.takeIf { it.type isSubclassOf classOf<List<*>>() }
                ?: return Resolution.Failed("missing-$element-slot")
            ListSlot(element, field)
        }
        val itemClass = slots.values.map { listItemClass(it.field) }.distinct().singleOrNull()
            ?: return Resolution.Failed("tab-item-type")
        val itemShape = serialization.describe(itemClass)
            ?.takeIf { it.serialName == TAB_ITEM_SERIAL }
            ?: return Resolution.Failed("tab-item-descriptor")
        val itemFields = orderedInstanceFields(itemClass)
        if (!matchesDescriptor(itemClass, itemFields, itemShape)) {
            return Resolution.Failed("tab-item-shape")
        }
        val strings = ITEM_STRING_ELEMENTS.mapNotNull { element ->
            val field = itemFields.getOrNull(itemShape.elements.indexOf(element)) ?: return@mapNotNull null
            if (field.type != classOf<String>()) return Resolution.Failed("tab-item-$element-type")
            element to field
        }.toMap()
        if ("name" !in strings || "uri" !in strings) return Resolution.Failed("tab-item-fields")
        // default_selected 是"服务端默认选中项不得删除"这条保护的前提；读不到它时
        // keep() 会对所有条目返回 false，勾中的首页/推荐页签会被静默删掉、宿主失去初始页。
        // 与其余形状不符同口径：宁可整层 Failed 让过滤不装，也不能带着失效的保护继续删。
        val defaultSelectedIndex = itemShape.elements.indexOf("default_selected")
        val defaultSelected = itemFields.getOrNull(defaultSelectedIndex)
            ?.takeIf { it.type == classOf<Int>() }
            ?: return Resolution.Failed("tab-item-default-selected")
        val constructors = KavaMemberLookup.declaredConstructors(dataClass, makeAccessible = true)
        if (constructors.isEmpty()) return Resolution.Failed("tab-data-constructors")
        (dataFields + itemFields).forEach { it.makeAccessible() }
        return Resolution.Found(
            Model(dataClass, constructors, slots, Item(itemClass, strings, defaultSelected))
        )
    }

    /** ViewModel 字段泛型里出现的类，再加它们各自的实例字段类型（两跳）。 */
    internal fun candidateClasses(viewModel: Class<*>): List<Class<*>> {
        val first = LinkedHashSet<Class<*>>()
        instanceFields(viewModel).forEach { collectTypes(it.genericType, first, 0) }
        val all = LinkedHashSet(first)
        first.forEach { owner -> instanceFields(owner).forEach { all += it.type } }
        return all.filter(::isHostClass)
    }

    private fun collectTypes(type: Type, sink: MutableSet<Class<*>>, depth: Int) {
        if (depth > MAX_GENERIC_DEPTH) return
        when (type) {
            is Class<*> -> sink += type
            is ParameterizedType -> {
                collectTypes(type.rawType, sink, depth + 1)
                type.actualTypeArguments.forEach { collectTypes(it, sink, depth + 1) }
            }
            is WildcardType -> type.upperBounds.forEach { collectTypes(it, sink, depth + 1) }
            is GenericArrayType -> collectTypes(type.genericComponentType, sink, depth + 1)
        }
    }

    private fun isHostClass(type: Class<*>): Boolean =
        !type.isPrimitive && !type.isArray && !type.isInterface &&
            SKIPPED_PACKAGES.none { type.name.startsWith(it) }

    private fun instanceFields(owner: Class<*>): List<Field> =
        KavaMemberLookup.declaredFields(owner) { !it.isStatic }

    /**
     * 按 dex `field_ids` 的排序规则（字段名，其次类型描述符）重建声明顺序。
     * `Class.getDeclaredFields()` 不承诺顺序，所以这里不依赖反射返回顺序。
     */
    internal fun orderedInstanceFields(owner: Class<*>): List<Field> =
        instanceFields(owner).sortedWith(compareBy<Field>({ it.name }, { descriptorOf(it.type) }))

    internal fun descriptorOf(type: Class<*>): String = when {
        type.isArray -> "[" + descriptorOf(type.componentType!!)
        type == classOf<Int>() -> "I"
        type == classOf<Long>() -> "J"
        type == classOf<Boolean>() -> "Z"
        type == classOf<Byte>() -> "B"
        type == classOf<Short>() -> "S"
        type == classOf<Char>() -> "C"
        type == classOf<Float>() -> "F"
        type == classOf<Double>() -> "D"
        type == Void.TYPE -> "V"
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    /**
     * 字段数与元素数相同，且存在参数窗口与字段类型序列逐个相同的构造器：
     * 反序列化构造器 `(int seen, 元素…)` 或主构造器 `(元素…)`。
     */
    internal fun matchesDescriptor(owner: Class<*>, fields: List<Field>, shape: Shape): Boolean {
        if (fields.size != shape.elements.size || fields.isEmpty()) return false
        val types = fields.map { it.type }
        return KavaMemberLookup.declaredConstructors(owner).any { constructor ->
            val params = constructor.parameterTypes.toList()
            params == types || (params.size >= types.size + 1 &&
                params[0] == classOf<Int>() &&
                params.subList(1, types.size + 1) == types)
        }
    }

    private fun listItemClass(field: Field): Class<*>? =
        ((field.genericType as? ParameterizedType)?.actualTypeArguments?.singleOrNull())
            ?.let { argument ->
                when (argument) {
                    is Class<*> -> argument
                    is WildcardType -> argument.upperBounds.singleOrNull() as? Class<*>
                    else -> null
                }
            }

    class Shape(val serialName: String, val elements: List<String>)

    /** 只通过 kotlinx 公开接口读描述符；接口名与访问器名在 20 个宿主上均未混淆。 */
    private class Serialization(
        private val kSerializer: Class<*>,
        private val getDescriptor: java.lang.reflect.Method,
        private val getSerialName: java.lang.reflect.Method,
        private val getElementsCount: java.lang.reflect.Method,
        private val getElementName: java.lang.reflect.Method
    ) {
        fun describe(owner: Class<*>): Shape? = runCatching {
            val companion = KavaMemberLookup.declaredFields(owner) { it.isStatic }
                .firstNotNullOfOrNull { field ->
                    val factory = KavaMemberLookup.declaredMethods(field.type) { method ->
                        !method.isStatic && method.parameterCount == 0 &&
                            method.returnType isSubclassOf kSerializer
                    }.singleOrNull() ?: return@firstNotNullOfOrNull null
                    field.makeAccessible()
                    factory.makeAccessible()
                    field.get(null)?.let { factory.invoke(it) }
                } ?: return@runCatching null
            val descriptor = getDescriptor.invoke(companion) ?: return@runCatching null
            val count = getElementsCount.invoke(descriptor) as Int
            Shape(
                serialName = getSerialName.invoke(descriptor) as String,
                elements = (0 until count).map { getElementName.invoke(descriptor, it) as String }
            )
        }.getOrNull()

        companion object {
            fun load(loader: ClassLoader): Serialization? {
                val serializer = KavaMemberLookup.classOrNull(loader, K_SERIALIZER) ?: return null
                val descriptor = KavaMemberLookup.classOrNull(loader, SERIAL_DESCRIPTOR) ?: return null
                return runCatching {
                    Serialization(
                        kSerializer = serializer,
                        getDescriptor = serializer.getMethod("getDescriptor"),
                        getSerialName = descriptor.getMethod("getSerialName"),
                        getElementsCount = descriptor.getMethod("getElementsCount"),
                        getElementName = descriptor.getMethod("getElementName", classOf<Int>())
                    )
                }.getOrNull()
            }
        }
    }
}
