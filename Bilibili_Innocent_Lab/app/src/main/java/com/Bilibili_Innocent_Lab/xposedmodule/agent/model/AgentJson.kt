package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONObject

/** org.json 接受单引号、重复键等扩展；动作参数先过标准 JSON 语法及深度门禁。 */
internal object AgentJson {
    fun objectOf(text: String, maxChars: Int = 32_768): JSONObject {
        if (text.length > maxChars) throw AgentModelException(AgentModelException.Reason.TOO_LARGE)
        try {
            Reader(text).read()
            return JSONObject(text)
        } catch (e: AgentModelException) {
            throw e
        } catch (_: Exception) {
            throw AgentModelException(AgentModelException.Reason.INVALID_RESPONSE)
        }
    }

    private class Reader(private val text: String) {
        private var offset = 0
        fun read() {
            space()
            check(peek() == '{')
            value(0)
            space()
            check(offset == text.length)
        }

        private fun value(depth: Int) {
            check(depth <= 24)
            space()
            when (peek()) {
                '{' -> {
                    offset++
                    space()
                    val keys = hashSetOf<String>()
                    if (take('}')) return
                    do {
                        space()
                        check(peek() == '"')
                        val start = offset
                        string()
                        // JSON 解码后判重，防止 name 与 \u006eame 并存。
                        val key = JSONObject("{\"key\":" + text.substring(start, offset) + "}").getString("key")
                        check(keys.add(key))
                        space()
                        check(take(':'))
                        value(depth + 1)
                        space()
                    } while (take(','))
                    check(take('}'))
                }
                '[' -> {
                    offset++
                    space()
                    if (take(']')) return
                    do { value(depth + 1); space() } while (take(','))
                    check(take(']'))
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun string() {
            check(take('"'))
            while (offset < text.length) {
                when (val c = text[offset++]) {
                    '"' -> return
                    '\\' -> {
                        check(offset < text.length)
                        val escape = text[offset++]
                        if (escape == 'u') repeat(4) {
                            check(offset < text.length && text[offset++] in "0123456789abcdefABCDEF")
                        } else check(escape in "\"\\/bfnrt")
                    }
                    else -> check(c.code >= 0x20)
                }
            }
            error("unterminated string")
        }

        private fun number() {
            take('-')
            if (!take('0')) {
                check(peek() in '1'..'9')
                while (peek() in '0'..'9') offset++
            }
            if (take('.')) digits()
            if (take('e') || take('E')) { if (!take('+')) take('-'); digits() }
        }

        private fun digits() {
            check(peek() in '0'..'9')
            while (peek() in '0'..'9') offset++
        }

        private fun literal(value: String) { check(text.startsWith(value, offset)); offset += value.length }
        private fun peek(): Char = text.getOrNull(offset) ?: '\u0000'
        private fun take(c: Char): Boolean = if (peek() == c) { offset++; true } else false
        private fun space() { while (peek() in " \t\r\n") offset++ }
    }
}
