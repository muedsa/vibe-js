package com.muedsa.js.runtime.regex

/**
 * JS 正则（Unicode 模式）早期错误校验。
 *
 * 语法校验发生在词法/解析阶段，因此不能依赖具体匹配引擎；
 * 返回错误消息（null 表示通过），由 Lexer/Parser/Interpreter 转换为各自异常。
 */
internal object JsRegexSyntax {

    /**
     * 非 u 模式的通用早期错误：
     * - 量词出现在无原子位置（如 `/{2}/`、`/^{2}/`）；
     * - `{m,n}` 中 m > n。
     *
     * 返回错误消息（null 表示通过）。u 模式的额外规则由 [unicodeError] 处理。
     */
    fun patternError(pattern: String): String? {
        var index = 0
        var lastAtom = false
        var lastQuantifier = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> {
                    if (index + 1 >= pattern.length) return invalid(pattern)
                    val escaped = pattern[index + 1]
                    lastAtom = escaped != 'b' && escaped != 'B'
                    lastQuantifier = false
                    index += 2
                }

                c == '[' -> {
                    index = plainClassEnd(pattern, index)
                    if (index < 0) return invalid(pattern)
                    lastAtom = true
                    lastQuantifier = false
                }

                c == '(' -> {
                    lastAtom = false
                    lastQuantifier = false
                    if (index + 1 < pattern.length && pattern[index + 1] == '?') {
                        when (pattern.getOrNull(index + 2)) {
                            ':' -> index += 3
                            '=', '!' -> index += 3
                            '<' -> {
                                val marker = pattern.getOrNull(index + 3)
                                if (marker == '=' || marker == '!') {
                                    index += 4
                                } else {
                                    val close = pattern.indexOf('>', index + 3)
                                    if (close < 0) return invalid(pattern)
                                    index = close + 1
                                }
                            }

                            else -> {
                                val colon = pattern.indexOf(':', index + 2)
                                if (colon < 0) return invalid(pattern)
                                index = colon + 1
                            }
                        }
                    } else {
                        index++
                    }
                }

                c == ')' -> {
                    lastAtom = true
                    lastQuantifier = false
                    index++
                }

                c == '|' || c == '^' || c == '$' -> {
                    lastAtom = false
                    lastQuantifier = false
                    index++
                }

                c == '*' || c == '+' || c == '?' -> {
                    if (c == '?' && lastQuantifier) {
                        // 惰性修饰符
                        lastQuantifier = false
                        lastAtom = false
                        index++
                    } else {
                        if (!lastAtom || lastQuantifier) return invalid(pattern)
                        lastAtom = false
                        lastQuantifier = true
                        index++
                        if (index < pattern.length && pattern[index] == '?') {
                            lastQuantifier = false
                            index++
                        }
                    }
                }

                c == '{' -> {
                    val end = braceQuantifierEnd(pattern, index)
                    if (end == null) {
                        lastAtom = true
                        lastQuantifier = false
                        index++
                    } else {
                        if (!lastAtom || lastQuantifier) return invalid(pattern)
                        if (!orderedBraceQuantifier(pattern, index, end)) return invalid(pattern)
                        lastAtom = false
                        lastQuantifier = true
                        index = end
                    }
                }

                else -> {
                    lastAtom = true
                    lastQuantifier = false
                    index++
                }
            }
        }
        return null
    }

    /** 非 u 模式的字符类扫描：仅查找结束 ']'，不应用 u 模式范围规则。 */
    private fun plainClassEnd(pattern: String, start: Int): Int {
        var index = start + 1
        if (index < pattern.length && pattern[index] == '^') index++
        if (index < pattern.length && pattern[index] == ']') return index + 1
        while (index < pattern.length) {
            when (pattern[index]) {
                '\\' -> index += 2
                ']' -> return index + 1
                else -> index++
            }
        }
        return -1
    }

    /** 校验 {m}/{m,}/{m,n} 的上下界顺序（m <= n）。 */
    private fun orderedBraceQuantifier(pattern: String, start: Int, end: Int): Boolean {
        var cursor = start + 1
        val minStart = cursor
        while (cursor < end && pattern[cursor] in '0'..'9') cursor++
        val minText = pattern.substring(minStart, cursor)
        var maxText = minText
        if (cursor < end - 1 && pattern[cursor] == ',') {
            val maxStart = cursor + 1
            var probe = maxStart
            while (probe < end - 1 && pattern[probe] in '0'..'9') probe++
            maxText = pattern.substring(maxStart, probe)
        }
        if (minText.isEmpty() || maxText.isEmpty()) return true
        val min = minText.toLongOrNull() ?: return true
        val max = maxText.toLongOrNull() ?: return true
        return min <= max
    }

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
                c == '(' -> {
                    val named = scan + 3 < pattern.length && pattern[scan + 1] == '?' &&
                        pattern[scan + 2] == '<' && pattern[scan + 3] != '=' &&
                        pattern[scan + 3] != '!'
                    if (scan + 1 >= pattern.length || pattern[scan + 1] != '?' || named) {
                        groupCount++
                    }
                }
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
                    // \p{...} / \P{...} 在字符类内外都允许（u 模式），属性名/取值必须合法
                    if ((escaped == 'p' || escaped == 'P') &&
                        index + 2 < pattern.length && pattern[index + 2] == '{'
                    ) {
                        val close = pattern.indexOf('}', index + 3)
                        if (close < 0) return invalid(pattern)
                        val expression = pattern.substring(index + 3, close)
                        if (UnicodePropertyData.resolve(expression) == null) return invalid(pattern)
                        index = close
                    } else if (escaped == 'x') {
                        if (index + 3 >= pattern.length ||
                            !isHexChar(pattern[index + 2]) || !isHexChar(pattern[index + 3])
                        ) {
                            return invalid(pattern)
                        }
                        index += 3
                    } else if (escaped == 'c') {
                        val control = pattern.getOrNull(index + 2)
                        if (control == null || control !in 'a'..'z' && control !in 'A'..'Z') {
                            return invalid(pattern)
                        }
                        index += 2
                    } else if (escaped == 'k') {
                        // 命名反向引用由 RegexNamedGroups 校验
                    } else if (escaped in '1'..'9') {
                        if (inClass) return invalid(pattern)
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
                    } else if (escaped == '0') {
                        // u 模式下 \0 后不得跟数字
                        if (pattern.getOrNull(index + 2)?.isDigit() == true) return invalid(pattern)
                    } else if (escaped == 'B') {
                        if (inClass) return invalid(pattern)
                    } else if (escaped in "bdfnrstuvwDSW^$\\.*+?()[]{}|/") {
                        // 允许的字符类转义 / 控制字符 / SyntaxCharacter
                    } else if (escaped == '-') {
                        if (!inClass) return invalid(pattern)
                    } else {
                        // u 模式不允许其余 IdentityEscape（如 \a、空白）
                        return invalid(pattern)
                    }
                    lastAtomQuantifiable = true
                    lastWasLazy = false
                    index++
                }

                inClass -> if (c == ']') inClass = false

                c == ']' -> return invalid(pattern)

                c == '[' -> {
                    // 字符类整体校验：属性转义与范围端点规则
                    if (classEnd(pattern, index) < 0) return invalid(pattern)
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
                    // 跳过组前缀中的 '?'/'?'+标记，避免被当作量词
                    if (index + 1 < pattern.length && pattern[index + 1] == '?') {
                        if (index + 2 < pattern.length && pattern[index + 2] == '<') {
                            val marker = pattern.getOrNull(index + 3)
                            if (marker == '=' || marker == '!') {
                                // 后向断言前缀 (?<= / (?<!
                                index += 3
                            } else {
                                val close = pattern.indexOf('>', index + 3)
                                if (close < 0) return invalid(pattern)
                                index = close
                            }
                        } else if (index + 2 < pattern.length &&
                            pattern[index + 2] != ':' && pattern[index + 2] != '=' &&
                            pattern[index + 2] != '!'
                        ) {
                            // 作用域修饰符：跳过到冒号
                            val colon = pattern.indexOf(':', index + 2)
                            if (colon < 0) return invalid(pattern)
                            index = colon
                        } else {
                            index += 2
                        }
                    }
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

    /**
     * 校验字符类并返回结束 ']' 之后的索引；出错返回 -1。
     *
     * 重点覆盖：类内 `\p{...}` 的属性解析、范围端点不得是字符类转义、
     * 字面量范围起点不得大于终点。
     */
    private fun classEnd(pattern: String, start: Int): Int {
        var index = start + 1
        if (index < pattern.length && pattern[index] == '^') index++
        if (index < pattern.length && pattern[index] == ']') return index + 1
        while (index < pattern.length && pattern[index] != ']') {
            val first = readClassAtom(pattern, index) ?: return -1
            index = first.next
            if (index < pattern.length && pattern[index] == '-' &&
                index + 1 < pattern.length && pattern[index + 1] != ']'
            ) {
                val second = readClassAtom(pattern, index + 1) ?: return -1
                if (first.isCharacterClass || second.isCharacterClass) return -1
                if (first.codePoint > second.codePoint) return -1
                index = second.next
            }
        }
        if (index >= pattern.length) return -1
        return index + 1
    }

    /** 字符类原子：下一个读取位置、是否为字符类转义、字面量码点。 */
    private class ClassAtom(
        val next: Int,
        val isCharacterClass: Boolean,
        val codePoint: Int,
    )

    private fun readClassAtom(pattern: String, index: Int): ClassAtom? {
        if (index >= pattern.length) return null
        val c = pattern[index]
        if (c == ']') return null
        if (c != '\\') {
            if (c.isHighSurrogate() && index + 1 < pattern.length &&
                pattern[index + 1].isLowSurrogate()
            ) {
                val codePoint = 0x10000 + ((c.code - 0xD800) shl 10) +
                    (pattern[index + 1].code - 0xDC00)
                return ClassAtom(index + 2, false, codePoint)
            }
            return ClassAtom(index + 1, false, c.code)
        }
        if (index + 1 >= pattern.length) return null
        val escaped = pattern[index + 1]
        val bodyStart = index + 2
        when (escaped) {
            'd', 'D', 'w', 'W', 's', 'S' -> return ClassAtom(bodyStart, true, 0)
            'p', 'P' -> {
                if (bodyStart >= pattern.length || pattern[bodyStart] != '{') return null
                val close = pattern.indexOf('}', bodyStart + 1)
                if (close < 0) return null
                val expression = pattern.substring(bodyStart + 1, close)
                if (UnicodePropertyData.resolve(expression) == null) return null
                return ClassAtom(close + 1, true, 0)
            }
            'u' -> {
                if (bodyStart < pattern.length && pattern[bodyStart] == '{') {
                    val close = pattern.indexOf('}', bodyStart + 1)
                    if (close < 0) return null
                    val digits = pattern.substring(bodyStart + 1, close)
                    if (digits.isEmpty() || digits.any { !isHexChar(it) }) return null
                    val codePoint = digits.toLongOrNull(16) ?: return null
                    if (codePoint > 0x10FFFF) return null
                    return ClassAtom(close + 1, false, codePoint.toInt())
                }
                val value = readHexValue(pattern, bodyStart, 4) ?: 0
                // u 模式下相邻的高/低代理项转义合并为一个码点（如 \uD834\uDF06）
                var next = bodyStart + 4
                if (value in 0xD800..0xDBFF && next + 5 < pattern.length &&
                    pattern[next] == '\\' && pattern[next + 1] == 'u'
                ) {
                    val low = readHexValue(pattern, next + 2, 4)
                    if (low != null && low in 0xDC00..0xDFFF) {
                        val combined = 0x10000 + ((value - 0xD800) shl 10) + (low - 0xDC00)
                        return ClassAtom(next + 6, false, combined)
                    }
                }
                return ClassAtom(next, false, value)
            }
            'x' -> return ClassAtom(bodyStart + 2, false, readHexValue(pattern, bodyStart, 2) ?: 0)
            'c' -> {
                val control = pattern.getOrNull(bodyStart) ?: return null
                return ClassAtom(bodyStart + 1, false, control.code and 0x1F)
            }
            'b' -> return ClassAtom(bodyStart, false, 0x08)
            'n' -> return ClassAtom(bodyStart, false, 0x0A)
            'r' -> return ClassAtom(bodyStart, false, 0x0D)
            't' -> return ClassAtom(bodyStart, false, 0x09)
            'f' -> return ClassAtom(bodyStart, false, 0x0C)
            'v' -> return ClassAtom(bodyStart, false, 0x0B)
            '0' -> return ClassAtom(bodyStart, false, 0)
            else -> return ClassAtom(bodyStart, false, escaped.code)
        }
    }

    private fun readHexValue(pattern: String, start: Int, length: Int): Int? {
        if (start + length > pattern.length) return null
        var value = 0
        for (offset in 0 until length) {
            val digit = pattern[start + offset].digitToIntOrNull(16) ?: return null
            value = value * 16 + digit
        }
        return value
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

    private fun isHexChar(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun invalid(pattern: String): String = "Invalid regular expression: /$pattern/"
}
