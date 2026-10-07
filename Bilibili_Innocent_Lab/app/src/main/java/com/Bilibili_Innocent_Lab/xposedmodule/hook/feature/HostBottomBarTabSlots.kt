package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.ModernNavigationMotion

/** GONE 项保留宿主页码，但不占胶囊槽位；漫游直接删项时也走同一映射。 */
internal class HostBottomBarTabSlots {
    private val hostIndices = IntArray(ModernNavigationMotion.MAX_ITEMS)
    var count = 0
        private set

    /** 原地比较并更新，逐帧不分配列表；只在布局参与项变化时返回 true。 */
    fun update(childCount: Int, participates: (Int) -> Boolean): Boolean {
        var nextCount = 0
        var changed = false
        for (hostIndex in 0 until childCount) {
            if (!participates(hostIndex)) continue
            if (nextCount == hostIndices.size) break
            if (nextCount >= count || hostIndices[nextCount] != hostIndex) changed = true
            hostIndices[nextCount++] = hostIndex
        }
        if (count != nextCount) changed = true
        count = nextCount
        return changed
    }

    fun hostIndex(slot: Int): Int = if (slot in 0 until count) hostIndices[slot] else -1

    fun slotOf(hostIndex: Int): Int {
        for (slot in 0 until count) if (hostIndices[slot] == hostIndex) return slot
        return -1
    }
}
