package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

internal enum class HostBackgroundPreset(val value: String) {
    OFF("off"), AURORA("aurora"), SAKURA("sakura"), OCEAN("ocean"), SUNSET("sunset"), MIST("mist"), CUSTOM("custom");

    companion object {
        fun read(value: String) = entries.firstOrNull { it.value == value } ?: OFF
    }
}

internal data class HostBackgroundConfig(
    val preset: HostBackgroundPreset = HostBackgroundPreset.OFF,
    val blur: Int = 16,
    val saturation: Int = 85,
    val veil: Int = 35,
    val asset: String = ""
) {
    val enabled get() = preset != HostBackgroundPreset.OFF
    fun normalized() = copy(blur = blur.coerceIn(0, 60), saturation = saturation.coerceIn(0, 200),
        veil = veil.coerceIn(0, 90), asset = asset.takeIf(::validAsset).orEmpty())

    companion object {
        fun validAsset(value: String) = value.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
    }
}
