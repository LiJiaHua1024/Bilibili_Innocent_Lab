package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.betterandroid.ui.extension.component.base.toPx
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/** 原生 UIComponent：重用键只返回稳定字符串，宿主进程级 view-type 缓存不持有 Activity/View。 */
internal class InsightHeaderComponent(private val componentType: Class<*>, private val entryType: Class<*>,
    private val unit: Any, private val root: (Context) -> ViewGroup, private val bind: (ViewGroup) -> Unit) : InvocationHandler {
    private val readRoot = checkNotNull(Lookup.methodOrNull(entryType, "getRoot"))
    val value: Any = Proxy.newProxyInstance(componentType.classLoader, arrayOf(componentType), this)
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "viewReusingKey", "identityEqualityKey", "contentEqualityKey" -> KEY
        "createViewEntry" -> {
            val container = root(args?.getOrNull(0) as Context); bind(container)
            Proxy.newProxyInstance(entryType.classLoader, arrayOf(entryType)) { entry, call, values -> when (call.name) {
                "getRoot" -> container
                "hashCode" -> System.identityHashCode(entry)
                "equals" -> entry === values?.getOrNull(0)
                "toString" -> KEY
                else -> error("unsupported-insight-entry")
            } }
        }
        "bindToView" -> {
            val entry = args?.getOrNull(0) ?: error("missing-insight-entry")
            val container = readRoot.invoke(entry) as? ViewGroup
                ?: error("invalid-insight-entry")
            bind(container); unit
        }
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.getOrNull(0)
        "toString" -> KEY
        else -> error("unsupported-insight-component")
    }
    companion object {
        const val KEY = "com.Bilibili_Innocent_Lab.insight.header"
        fun prepend(items: List<*>, header: Any): List<Any?> = listOf(header) + items.filterNot {
            it != null && Proxy.isProxyClass(it.javaClass) && Proxy.getInvocationHandler(it) is InsightHeaderComponent
        }
    }
}

/** 保留宿主 adapter 与所有原生项，仅在其组件列表开头加入一个标准 UIComponent。 */
internal class InsightIntroHeaders private constructor(private val environment: HookEnvironment,
    private val recycler: Class<*>, private val adapterType: Class<*>, private val getAdapter: Method,
    private val component: Class<*>, private val entry: Class<*>, private val unit: Any, private val scrollTo: Method) {
    private val owners = WeakHashMap<View, WeakReference<InsightScrollMount>>()
    private val adapters = WeakHashMap<Any, WeakReference<InsightScrollMount>>()
    private val plans = mutableMapOf<Class<*>, InsightAdapterItems?>()
    private val hooked = mutableSetOf<Method>()
    fun install(): Int {
        environment.registrar.exact("insight.intro.adapter", recycler, "setAdapter", adapterType) { after {
            if (!hasThrowable) (instance as? View)?.let { list -> owners[list]?.get()?.let { observe(list, it) } }
        } }
        return 1
    }
    fun attach(root: ViewGroup, card: InsightCard): InsightScrollMount? {
        // 宿主资源只能按宿主包名解析，模块 R.id 不包含这份布局。
        val id = root.resources.getIdentifier("recycler", "id", root.context.packageName)
        val list = root.findViewById<View>(id) as? ViewGroup ?: return null
        if (!recycler.isInstance(list)) return null
        val mount = InsightScrollMount(root, list, card, component, entry, unit, scrollTo) { observe(list, it) }
        environment.logInfo("insight.header.attach", "AI Insight header attached")
        owners[list] = WeakReference(mount)
        observe(list, mount)
        return mount
    }
    private fun observe(list: View, mount: InsightScrollMount) = HostThreadGuard.run("insight.header.adapter") {
        if (mount.closed) return@run
        val adapter = getAdapter.invoke(list) ?: return@run
        val type = adapter.javaClass
        environment.logInfo("insight.header.adapter.${type.name}", "AI Insight header adapter=${type.name}")
        val plan = plans.getOrPut(type) {
            InsightAdapterItems.resolve(type).also { resolved ->
                environment.logInfo("insight.header.plan.${type.name}",
                    "AI Insight header plan read=${resolved?.readKind ?: "none"} write=${resolved != null}")
            }
        } ?: return@run
        adapters[adapter] = WeakReference(mount)
        if (hooked.add(plan.write)) environment.registrar.exact("insight.intro.items.${type.name}",
            plan.write.declaringClass, plan.write.name, *plan.write.parameterTypes) { before {
            val target = instance?.let { adapters[it]?.get() } ?: return@before
            val items = argOrNull(0) as? List<*> ?: return@before
            if (!target.closed) {
                target.beforeUpdate()
                args[0] = target.items(items)
                environment.logInfo("insight.header.items", "AI Insight header items=${(args[0] as List<*>).size}")
            }
        } }
        // 数据可能先于 setAdapter 到达；下一次主线程任务补入，避开 RecyclerView 正在布局的阶段。
        list.post(HostThreadGuard.runnable("insight.header.initial") {
            if (!mount.closed && getAdapter.invoke(list) === adapter) {
                val items = plan.read(adapter) ?: return@runnable
                if (items.any { it == null || !component.isInstance(it) }) return@runnable
                if (items.firstOrNull() !== mount.component) {
                    mount.beforeUpdate(); plan.write.invoke(adapter, mount.items(items))
                }
            }
        })
    }
    companion object {
        fun resolve(environment: HookEnvironment): InsightIntroHeaders? = runCatching {
            val loader = environment.classLoader ?: return null
            val recycler = Lookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView") ?: return null
            val adapter = Lookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$Adapter") ?: return null
            val component = Lookup.classOrNull(loader, "com.bilibili.app.gemini.ui.UIComponent") ?: return null
            val entry = Lookup.classOrNull(loader, "com.bilibili.app.gemini.ui.UIComponent\$ViewEntry") ?: return null
            require(component.isInterface && entry.isInterface)
            val continuation = Lookup.classOrNull(loader, "kotlin.coroutines.Continuation") ?: return null
            require(Lookup.methodOrNull(component, "createViewEntry", Context::class.java, ViewGroup::class.java)?.returnType == entry)
            require(Lookup.methodOrNull(component, "bindToView", entry, continuation) != null)
            require(listOf("viewReusingKey", "identityEqualityKey", "contentEqualityKey").all { Lookup.methodOrNull(component, it) != null })
            require(Lookup.methodOrNull(entry, "getRoot")?.returnType == View::class.java)
            val unitClass = Lookup.classOrNull(loader, "kotlin.Unit") ?: return null
            val unit = Lookup.fieldOrNull(unitClass, "INSTANCE")?.get(null) ?: return null
            InsightIntroHeaders(environment, recycler, adapter,
                Lookup.inheritedMethodOrNull(recycler, "getAdapter") ?: return null, component, entry, unit,
                Lookup.inheritedMethodOrNull(recycler, "scrollToPosition", Int::class.javaPrimitiveType!!) ?: return null)
        }.getOrNull()
    }
}

internal class InsightScrollMount(private val owner: View, private val list: View, private val card: InsightCard,
    componentType: Class<*>, entryType: Class<*>, unit: Any, private val scrollTo: Method, private val attached: (InsightScrollMount) -> Unit) : AutoCloseable {
    var closed = false; private set
    private val header = InsightHeaderComponent(componentType, entryType, unit, { context -> FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(-1, -2)
        val inset = 8.toPx(context); setPadding(inset, inset, inset, inset)
    } }) { container ->
        if (!closed && card.parent !== container) {
            (card.parent as? ViewGroup)?.removeView(card)
            container.removeAllViews(); container.addView(card, ViewGroup.LayoutParams(-1, -2))
        }
    }
    val component: Any get() = header.value
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { attached(this@InsightScrollMount) }
        override fun onViewDetachedFromWindow(v: View) = Unit
    }
    init { owner.addOnAttachStateChangeListener(attachment) }
    private var inserted = false
    fun beforeUpdate() {
        if (inserted) return
        inserted = true
        // DiffUtil 会保留原首项锚点，把新插入的入口留在屏幕上方；仅首次且原本在顶部时恢复第 0 项。
        if (!list.canScrollVertically(-1)) list.post(HostThreadGuard.runnable("insight.header.top") {
            if (!closed) scrollTo.invoke(list, 0)
        })
    }
    fun items(original: List<*>): List<Any?> = InsightHeaderComponent.prepend(original, component)
    override fun close() {
        if (closed) return
        closed = true; owner.removeOnAttachStateChangeListener(attachment)
        card.dispose(); (card.parent as? ViewGroup)?.removeView(card)
    }
}
