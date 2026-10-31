package com.muedsa.js.runtime.regex

/**
 * JS 正则（Unicode 模式）早期错误校验。
 *
 * 语法校验发生在词法/解析阶段，因此不能依赖具体匹配引擎；
 * 返回错误消息（null 表示通过），由 Lexer/Parser/Interpreter 转换为各自异常。
 */
internal object JsRegexSyntax {

    /** 校验 `u` 标志下的额外早期错误。 */
    fun unicodeError(pattern: String): String? {
        // 统计捕获组数量（跳过字符类与转义）
        var groupCount = 0
        var scan = 0
        var scanInClass = false
        while (scan < pattern.length) {
            val c = pattern[scan]
            when {
                c == '\\' -> scan++
                scanInClass -> if (c == ']') scanInClass = false
                c == '[' -> scanInClass = true
                c == '(' && (scan + 1 >= pattern.length || pattern[scan + 1] != '?') -> groupCount++
            }
            scan++
        }

        val groupStack = mutableListOf<Boolean>()
        var index = 0
        var inClass = false
        var lastAtomQuantifiable = false
        var lastWasLazy = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> {
                    if (index + 1 >= pattern.length) return invalid(pattern)
                    val escaped = pattern[index + 1]
                    // \u{...} 码点必须是不超过 0x10FFFF 的十六进制值
                    if (escaped == 'u' && index + 2 < pattern.length && pattern[index + 2] == '{') {
                        val close = pattern.indexOf('}', index + 3)
                        if (close < 0) return invalid(pattern)
                        val digits = pattern.substring(index + 3, close)
                        if (digits.isEmpty() ||
                            digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }
                        ) {
                            return invalid(pattern)
                        }
                        val codePoint = digits.toLongOrNull(16) ?: return invalid(pattern)
                        if (codePoint > 0x10FFFF) return invalid(pattern)
                        index = close + 1
                        lastAtomQuantifiable = true
                        lastWasLazy = false
                        continue
                    }
                    if (!inClass) {
                        if ((escaped == 'p' || escaped == 'P') &&
                            index + 2 < pattern.length && pattern[index + 2] == '{'
                        ) {
                            val close = pattern.indexOf('}', index + 3)
                            if (close < 0) return invalid(pattern)
                            index = close + 1
                            lastAtomQuantifiable = true
                            lastWasLazy = false
                            continue
                        }
                        if (escaped in '1'..'9') {
                            var reference = escaped - '0'
                            var cursor = index + 2
                            while (cursor < pattern.length && pattern[cursor] in '0'..'9' &&
                                reference * 10 + (pattern[cursor] - '0') <= groupCount
                            ) {
                                reference = reference * 10 + (pattern[cursor] - '0')
                                cursor++
                            }
                            if (reference > groupCount) return invalid(pattern)
                            index = cursor - 1
                        } else if (escaped == ' ' || escaped == '\t' || escaped == '\n' || escaped == '\r') {
                            // u 模式不允许 IdentityEscape 空白
                            return invalid(pattern)
                        }
                    }
                    lastAtomQuantifiable = true
                    lastWasLazy = false
                    index++
                }

                inClass -> if (c == ']') inClass = false

                c == '[' -> {
                    inClass = true
                    lastAtomQuantifiable = true
                    lastWasLazy = false
                }

                c == '(' -> {
                    val isLookahead = index + 2 < pattern.length && pattern[index + 1] == '?' &&
                        (pattern[index + 2] == '=' || pattern[index + 2] == '!')
                    groupStack.add(!isLookahead)
                    lastAtomQuantifiable = false
                    lastWasLazy = false
                }

                c == ')' -> {
                    lastAtomQuantifiable = groupStack.removeLastOrNull() ?: false
                    lastWasLazy = false
                }

                c == '*' || c == '+' || c == '?' -> {
                    if (lastWasLazy || !lastAtomQuantifiable) return invalid(pattern)
                    lastAtomQuantifiable = false
                    lastWasLazy = index + 1 < pattern.length && pattern[index + 1] == '?'
                    if (lastWasLazy) index++
                }

                c == '{' -> {
                    val end = braceQuantifierEnd(pattern, index) ?: return invalid(pattern)
                    if (!lastAtomQuantifiable) return invalid(pattern)
                    lastAtomQuantifiable = false
                    lastWasLazy = false
                    index = end - 1
                }

                c == '}' -> return invalid(pattern)

                c == '|' || c == '^' || c == '$' -> {
                    lastAtomQuantifiable = false
                    lastWasLazy = false
                }

                else -> {
                    lastAtomQuantifiable = true
                    lastWasLazy = false
                }
            }
            index++
        }
        return null
    }

    /** 若 index 处是合法的 {m} / {m,} / {m,n}，返回 '}' 之后的索引。 */
    private fun braceQuantifierEnd(pattern: String, index: Int): Int? {
        var cursor = index + 1
        val minStart = cursor
        while (cursor < pattern.length && pattern[cursor] in '0'..'9') cursor++
        if (cursor == minStart) return null
        if (cursor < pattern.length && pattern[cursor] == ',') {
            cursor++
            while (cursor < pattern.length && pattern[cursor] in '0'..'9') cursor++
        }
        if (cursor < pattern.length && pattern[cursor] == '}') return cursor + 1
        return null
    }

    private fun invalid(pattern: String): String = "Invalid regular expression: /$pattern/"
}
