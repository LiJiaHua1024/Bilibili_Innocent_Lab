package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

/**
 * 自定义隐藏规则统一解析：逗号、中文逗号、分号或换行分隔，忽略空项和大小写。
 * 规则与被匹配文本走同一套 [TextNormalizer.forMatching]：全角、兼容字符与零宽字符不再能绕过关键词。
 */
internal object RuleSetCodec {
    private val separators = Regex("[,，;；\\r\\n]+")

    fun parse(raw: String): Set<String> = raw.split(separators)
        .asSequence()
        .map(String::trim)
        .map(TextNormalizer::forMatching)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(linkedSetOf())

    fun matches(tokens: Set<String>, vararg values: String?): Boolean {
        if (tokens.isEmpty()) return false
        for (raw in values) {
            if (raw == null) continue
            // 保持 Unicode 整串小写映射语义；ignoreCase 子串比较不与它等价。
            val value = TextNormalizer.forMatching(raw)
            for (token in tokens) {
                if (value.contains(token)) return true
            }
        }
        return false
    }
}
