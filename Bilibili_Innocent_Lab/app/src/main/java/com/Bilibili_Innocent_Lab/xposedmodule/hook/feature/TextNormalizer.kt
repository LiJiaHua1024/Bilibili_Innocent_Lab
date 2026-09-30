package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import java.text.Normalizer

/**
 * 过滤判据共用的文本归一化：让"看起来一样"的写法落到同一个字符串上。
 *
 * - **NFKC**：全角 `ＶＸ`/`ｑｑ` → `VX`/`qq`，兼容字符（带圈数字、上标、兼容汉字等）→ 标准形式；
 * - **去掉不可见格式字符**（Unicode Cf：零宽空格/连接符、BOM、方向控制符等）：刷屏与引流常用
 *   它们把"加微信"拆开来躲关键词；
 * - 小写（Unicode 整串，与 [RuleSetCodec] 既有语义一致）。
 *
 * **不做**的事：不删普通空格和标点（"加 微 信"这类会把正常句子粘出假命中）、不做繁简转换
 * （需要词典，且"發/髮"这类一对多会误伤）。
 *
 * 性能：绝大多数文本是 ASCII 或常规 CJK，先做一次只读扫描，只有出现全角区、兼容区或格式字符时
 * 才走 `Normalizer`；常规路径与原先只做 `lowercase()` 的成本相同。
 */
internal object TextNormalizer {

    fun forMatching(raw: String): String {
        val value = if (needsNormalization(raw)) normalizeSlow(raw) else raw
        return value.lowercase()
    }

    /**
     * 语义判定的送判文本：在 [forMatching] 的基础上折叠连续空白、去首尾空白，但**保留大小写**
     * （大小写可能带语气，交给模型判断）。同时作为缓存键与弹幕去重的依据。
     */
    fun forSemantic(raw: String): String {
        val value = if (needsNormalization(raw)) normalizeSlow(raw) else raw
        return capRuns(collapseWhitespace(value))
    }

    /** 同一字符连续出现的保留上限：12 个足以让模型看出"刷屏"，再多只是让缓存键各不相同。 */
    const val MAX_RUN = 12

    /**
     * 同一字符（按码点，表情也算一个）连续超过 [MAX_RUN] 个时截到 [MAX_RUN]：「啊×30」「啊×31」落到同一个
     * 缓存键和去重项上，不再各判各的、各付各的钱。先只读扫描，没有超长连写时原样返回。
     */
    internal fun capRuns(value: String): String {
        var run = 0
        var previous = -1
        var index = 0
        var needs = false
        while (index < value.length) {
            val code = value.codePointAt(index)
            run = if (code == previous) run + 1 else 1
            if (run > MAX_RUN) {
                needs = true
                break
            }
            previous = code
            index += Character.charCount(code)
        }
        if (!needs) return value
        val out = StringBuilder(value.length)
        run = 0
        previous = -1
        index = 0
        while (index < value.length) {
            val code = value.codePointAt(index)
            run = if (code == previous) run + 1 else 1
            if (run <= MAX_RUN) out.appendCodePoint(code)
            previous = code
            index += Character.charCount(code)
        }
        return out.toString()
    }

    internal fun needsNormalization(value: String): Boolean {
        for (ch in value) {
            val code = ch.code
            if (code < 0x80) continue
            // 全角字母数字（ＶＸ、１２３）与半角片假名/谚文才触发；全角标点（，！？：（））是中文常态，
            // 若也触发，几乎每条中文评论都会进慢路径，而它们从来不是用来绕关键词的。
            if (code in 0xFF10..0xFF19 || code in 0xFF21..0xFF3A || code in 0xFF41..0xFF5A || code in 0xFF65..0xFFDC) {
                return true
            }
            if (code in 0x2460..0x24FF) return true // 带圈字母数字
            if (code in 0xF900..0xFAFF) return true // CJK 兼容汉字
            // 只认通用标点区里的特殊空格、零宽与方向控制；“”…— 这类常用中文标点不触发慢路径。
            if (code in 0x2000..0x200F || code in 0x2028..0x202F || code in 0x205F..0x206F) return true
            if (code == 0xFEFF || code == 0x3000 || code == 0x00A0 || code == 0x00AD) return true
            if (Character.getType(ch) == Character.FORMAT.toInt()) return true
            if (code in 0x2070..0x209F || code in 0x00B2..0x00B9) return true // 上下标
        }
        return false
    }

    private fun normalizeSlow(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        val out = StringBuilder(normalized.length)
        for (ch in normalized) {
            if (Character.getType(ch) == Character.FORMAT.toInt()) continue
            out.append(ch)
        }
        return out.toString()
    }

    private fun collapseWhitespace(value: String): String {
        var needs = false
        var previousSpace = true
        for (ch in value) {
            val space = ch.isWhitespace()
            if (space && (previousSpace || ch != ' ')) {
                needs = true; break
            }
            previousSpace = space
        }
        if (!needs && (value.isEmpty() || !value.last().isWhitespace())) return value
        val out = StringBuilder(value.length)
        var pendingSpace = false
        for (ch in value) {
            if (ch.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(ch)
            }
        }
        return out.toString()
    }
}
