package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.math.BigDecimal

/** 百分比整数沿用项目现有配置类型；0 表示跟随宿主，不引入 Float 偏好或协议类型。 */
internal object PlayerSpeedConfig {
    const val FOLLOW_HOST = 0
    /**
     * 0.1～8 倍，最多两位小数（百分比整数 10..800）。宿主 `setPlaySpeed` 在 Java 层不夹取（只把 2.0 换成 1.99），
     * 值原样交给原生播放器；原先的 0.25～4 与宿主菜单一致，不是宿主的硬限制。旧版保存的两位小数值天然仍在范围内。
     */
    const val MIN_PERCENT = 10
    const val MAX_PERCENT = 800
    const val MIN_MULTIPLIER = MIN_PERCENT / 100f
    const val MAX_MULTIPLIER = MAX_PERCENT / 100f
    const val MAX_MENU_OPTIONS = 12
    const val MAX_MENU_SPEC_LENGTH = 128
    val DEFAULT_HOST_MENU_PERCENTS: List<Int> = listOf(200, 150, 125, 100, 75, 50)
    val supportedPercents: Set<Int> = setOf(FOLLOW_HOST) + (MIN_PERCENT..MAX_PERCENT)
    private val decimalInput = Regex("(?:[0-9]{1,3}(?:\\.[0-9]{1,2})?|\\.[0-9]{1,2})")
    private val menuTokenSplit = Regex("[\\s,，;；、/]+")

    fun normalize(percent: Int): Int = percent.takeIf { it in supportedPercents } ?: FOLLOW_HOST

    fun multiplier(percent: Int): Float? = normalize(percent).takeIf { it != FOLLOW_HOST }?.div(100f)

    fun effectiveLongPressPercent(disabled: Boolean, percent: Int): Int =
        if (disabled) FOLLOW_HOST else normalize(percent)

    /**
     * 默认播放倍速与倍速菜单档位改造均作用于普通播放速度，二者互斥：
     * 当默认播放倍速已启用（非 FOLLOW_HOST）时，忽略菜单档位配置，绝不双路同时生效。
     */
    fun effectiveMenuPercents(defaultPercent: Int, rawMenuSpec: String?): List<Int> =
        if (normalize(defaultPercent) != FOLLOW_HOST) emptyList() else parseStoredMenuPercents(rawMenuSpec)

    fun menuMultipliers(defaultPercent: Int, rawMenuSpec: String?): List<Float> =
        effectiveMenuPercents(defaultPercent, rawMenuSpec).map { it / 100f }

    fun conflictsWithDefaultSpeed(defaultPercent: Int, rawMenuSpec: String?): Boolean =
        normalize(defaultPercent) != FOLLOW_HOST && parseStoredMenuPercents(rawMenuSpec).isNotEmpty()

    fun parseMultiplier(text: String): Int? = runCatching {
        val value = text.trim()
        if (!decimalInput.matches(value)) return@runCatching null
        BigDecimal(value).movePointRight(2).intValueExact().takeIf { it in supportedPercents }
    }.getOrNull()

    /**
     * 解析用户在弹窗中输入的多个倍速档位（0.1～8，最多两位小数）。
     * 任一分段非法、超出范围（含 0）或总数超过 [MAX_MENU_OPTIONS] 时返回 null，保留在弹窗内提示。
     * 成功时按宿主菜单习惯从高到低去重排序。
     */
    fun parseMenuMultipliers(text: String): List<Int>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_MENU_SPEC_LENGTH) return null
        val tokens = trimmed.split(menuTokenSplit).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > MAX_MENU_OPTIONS) return null
        val parsed = ArrayList<Int>(tokens.size)
        for (rawToken in tokens) {
            val cleaned = rawToken.trim().removeSuffix("x").removeSuffix("X").removeSuffix("倍").trim()
            val percent = parseMultiplier(cleaned)?.takeIf { it in MIN_PERCENT..MAX_PERCENT } ?: return null
            parsed.add(percent)
        }
        return parsed.distinct().sortedDescending()
    }

    fun encodeMenuPercents(percents: Collection<Int>): String =
        percents
            .filter { it in MIN_PERCENT..MAX_PERCENT }
            .distinct()
            .sortedDescending()
            .take(MAX_MENU_OPTIONS)
            .joinToString(",")

    /**
     * 读取持久化或远端下发的倍速菜单配置串。
     * 只接受百分比整数串（如 `"200,150,125,100,75,50"`），不混用倍率与百分比单位；
     * 任何格式损坏、越界或超长输入均整体回退为空列表（跟随宿主），不半截生效。
     */
    fun parseStoredMenuPercents(raw: String?): List<Int> {
        if (raw.isNullOrBlank() || raw.length > MAX_MENU_SPEC_LENGTH) return emptyList()
        val tokens = raw.split(',').map { it.trim() }
        if (tokens.isEmpty() || tokens.size > MAX_MENU_OPTIONS) return emptyList()
        val parsed = ArrayList<Int>(tokens.size)
        for (token in tokens) {
            if (token.isEmpty() || token.any { it !in '0'..'9' }) return emptyList()
            val asPercent = token.toIntOrNull()?.takeIf { it in MIN_PERCENT..MAX_PERCENT }
                ?: return emptyList()
            parsed.add(asPercent)
        }
        return parsed.distinct().sortedDescending()
    }

    fun normalizeMenuSpec(raw: String?): String = encodeMenuPercents(parseStoredMenuPercents(raw))

    fun formatMultiplier(percent: Int): String =
        BigDecimal.valueOf(normalize(percent).toLong(), 2).stripTrailingZeros().toPlainString()

    fun formatMenuInput(percents: List<Int>): String =
        percents.filter { it in MIN_PERCENT..MAX_PERCENT }.joinToString(", ") { formatMultiplier(it) }

    fun formatMenuSummary(percents: List<Int>): String =
        percents.filter { it in MIN_PERCENT..MAX_PERCENT }.joinToString(" / ") { "${formatMultiplier(it)}x" }
}
