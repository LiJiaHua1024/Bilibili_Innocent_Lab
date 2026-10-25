package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 新首页框架 `HomeTabData` 某一个列表元素（`tab` 或 `bottom`）的过滤边界。
 *
 * 挂在数据类的**全部**构造器上而不是某一个：宿主有主构造器、反序列化构造器和
 * R8 压缩过签名的默认参数构造器，哪个直接写字段、哪个委托给别的，每版都可能不同。
 * 嵌套构造（默认参数构造器 → 主构造器）在同一线程里同步发生，用深度计数只在
 * **最外层**构造结束时处理一次，此时字段已是最终值。
 *
 * 过滤结果装进 [FilteredTabList] 作标记：宿主 `copy()` 会把已过滤列表原样传回构造器，
 * 认出标记就跳过——否则扫描快照会拿缺了隐藏项的列表覆盖全量，勾选面板里就再也看不到
 * 已隐藏的条目，没法取消勾选。
 *
 * 数据层过滤让所有消费方（建页、选中恢复、按下标找页）看到同一份列表，不会错位；
 * 代价是宿主把整个响应写进磁盘缓存时存的也是过滤后的版本，取消隐藏要等下一次网络刷新。
 */
internal class HomeFrameListHook(
    private val slot: HomeFrameTabLocator.ListSlot,
    private val item: HomeFrameTabLocator.Item,
    private val onScan: (List<*>) -> Unit,
    /** null = 只扫描不过滤（未配置任何隐藏项）。 */
    private val shouldHide: ((Any) -> Boolean)?,
    private val onApplied: (before: Int, after: Int) -> Unit,
    private val preserveNavigation: Boolean = true
) {
    private val depth = ThreadLocal<IntArray>()

    fun enter() {
        val counter = depth.get() ?: IntArray(1).also(depth::set)
        counter[0] += 1
    }

    fun exit(instance: Any?) {
        val counter = depth.get() ?: return
        counter[0] -= 1
        if (counter[0] > 0) return
        counter[0] = 0
        if (instance != null) process(instance)
    }

    internal fun process(instance: Any) {
        val source = slot.field.get(instance) as? List<*> ?: return
        if (source is FilteredTabList) return
        onScan(source)
        val hide = shouldHide ?: return
        val filtered = if (preserveNavigation) filterFrameTabs(source, hide, item::isDefaultSelected)
            else CopyOnFilter.list(source, hide)
        if (filtered === source) return
        slot.field.set(instance, FilteredTabList(filtered))
        onApplied(source.size, filtered.size)
    }

    /** 已过滤的标记类型；对宿主而言就是一个普通 ArrayList。 */
    internal class FilteredTabList(items: Collection<Any?>) : ArrayList<Any?>(items)

    companion object {
        /**
         * 只删"命中且不是默认选中"的项；全删光时原样放行（宿主拿到空列表会退回兜底配置，
         * 比留一项更糟）。无命中返回原实例。
         */
        internal fun filterFrameTabs(
            source: List<*>,
            hide: (Any) -> Boolean,
            keep: (Any) -> Boolean
        ): List<*> {
            val filtered = CopyOnFilter.list(source) { hide(it) && !keep(it) }
            return if (filtered.isEmpty()) source else filtered
        }
    }
}
