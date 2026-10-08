package com.Bilibili_Innocent_Lab.xposedmodule.settings.appearance

internal enum class HostGlassMaterial(val value: String) {
    SOFT("soft"), LIQUID("liquid");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.value == value } ?: SOFT
    }
}

internal enum class HostGlassRenderer(val value: String) {
    AUTO("auto"), SOFTWARE("software");

    companion object {
        // 仅用于缺少新配置的旧设置/旧备份，不作为第三个界面选项。
        const val LEGACY = "legacy"
        fun parse(value: String?, bottom: Boolean, enabled: Boolean) =
            entries.firstOrNull { it.value == value } ?: if (bottom && enabled) SOFTWARE else AUTO
    }
}

/** 保存用户意图；Liquid 临时使用自动渲染，不覆盖柔光之前的后端选择。 */
internal data class HostChromeAppearance(
    val material: HostGlassMaterial = HostGlassMaterial.SOFT,
    val renderer: HostGlassRenderer = HostGlassRenderer.AUTO
) {
    val effectiveRenderer: HostGlassRenderer
        get() = if (material == HostGlassMaterial.LIQUID) HostGlassRenderer.AUTO else renderer

    fun compatible(api: Int) = if (api < 33 && material == HostGlassMaterial.LIQUID)
        copy(material = HostGlassMaterial.SOFT) else this

    companion object {
        fun read(material: String?, renderer: String?, bottom: Boolean, enabled: Boolean) =
            HostChromeAppearance(HostGlassMaterial.parse(material), HostGlassRenderer.parse(renderer, bottom, enabled))
    }
}
