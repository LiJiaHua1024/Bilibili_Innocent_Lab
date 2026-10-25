package com.bilibili.playerbizcommonv2.widget.speed

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

// 多字母类名故意不在快路径候选表中；只检查成员结构，不构造 Android Adapter。
class RenamedSpeedAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), View.OnClickListener {
    class Item { @JvmField var speed = 1f; @JvmField var selected = false }
    @JvmField val items: List<Item> = arrayListOf()
    @JvmField val speeds = floatArrayOf(2f, 1f, 0.5f)
    fun populate(@Suppress("UNUSED_PARAMETER") current: Float) = Unit
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = error("unused")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    override fun onClick(view: View?) = Unit
}

class OtherRenamedSpeedAdapter(@Suppress("UNUSED_PARAMETER") theme: Int) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), View.OnClickListener {
    class Item { @JvmField var speed = 1f; @JvmField var selected = false }
    @JvmField val items: List<Item> = arrayListOf()
    @JvmField val speeds = floatArrayOf(2f, 1f, 0.5f)
    fun populate(@Suppress("UNUSED_PARAMETER") current: Float) = Unit
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = error("unused")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
    override fun onClick(view: View?) = Unit
}
