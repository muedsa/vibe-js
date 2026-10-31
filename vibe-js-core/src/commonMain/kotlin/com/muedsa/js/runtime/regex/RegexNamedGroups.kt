package com.muedsa.js.runtime.regex

/** 命名捕获组分析/校验失败（由调用方转换为 SyntaxError）。 */
internal class RegexSyntaxError(message: String) : Exception(message)

/**
 * 命名捕获组分析结果。
 *
 * @property names 解码后的组名 → 捕获组索引（按声明顺序）
 * @property enginePattern 规范化后的模式：组名/引用去除 Unicode 转义，
 *   Annex B 的 IdentityEscape 转换为字面字符，便于底层引擎编译
 * @property hasNamedGroups 模式中是否声明了命名捕获组
 * @property hasForwardReference 是否存在位于声明之前的 \k<name> 引用
 */
internal class NamedGroupAnalysis(
    val names: Map<String, Int>,
    val enginePattern: String,
    val hasNamedGroups: Boolean,
    val hasForwardReference: Boolean,
)

/**
 * 命名捕获组与身份转义的语法分析与规范化。
 *
 * 规则要点：
 * - 存在命名组时，\k 必须后随 `<name>` 且名称必须已声明（允许前向引用）；
 * - 不存在命名组时，\k 是 Annex B IdentityEscape（u 模式下非法）；
 * - 组名遵循 RegExpIdentifierName：支持 \uXXXX 与 u 模式下的 \u{...}，
 *   首字符 ID_Start，后续 ID_Continue（含 ZWNJ/ZWJ），禁止孤立代理项；
 * - 非 u 模式下 Java 引擎对部分 IdentityEscape（如 \a、\e）的解释与 JS 不同，
 *   这里统一改写为字面字符。
 */
internal object RegexNamedGroups {

    private val OTHER_ID_START = setOf('\u1885', '\u1886', '\u2118', '\u212E', '\u309B', '\u309C')
    private val OTHER_ID_CONTINUE = setOf('\u00B7', '\u0387', '\u19DA') +
        ('\u1369'..'\u1371').toSet()

    private val ASTRAL_ID_START_RANGES = listOf(
        0x10000..0x1004D,
        0x10480..0x1049D,
        0x104B0..0x104D3,
        0x104D8..0x104FB,
        0x1D400..0x1D7CB,
        0x1EE00..0x1EEFF,
        0x20000..0x2B81D,
        0x2B820..0x2CEAF,
        0x2CEB0..0x2EBEF,
        0x30000..0x3134F,
    )

    /** 仅 ID_Continue 的 astral 区间（数学数字、Osmanya 数字等）。 */
    private val ASTRAL_ID_CONTINUE_ONLY_RANGES = listOf(
        0x104A0..0x104A9,
        0x1D7CE..0x1D7FF,
    )

    /** 允许作为 IdentityEscape 的语法字符（u 模式）。 */
    private const val SYNTAX_CHARACTERS = "^$\\.*+?()[]{}|/"

    fun analyze(pattern: String, unicode: Boolean): NamedGroupAnalysis {
        val declarations = LinkedHashMap<String, Int>()
        val declarationPositions = LinkedHashMap<String, Int>()
        val referenceList = ArrayList<Triple<String, Int, Boolean>>() // name, pos, wellFormed
        var groupIndex = 0
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(index + 1)
                    if (next == 'k') {
                        var wellFormed = false
                        var name: String? = null
                        if (!inClass && pattern.getOrNull(index + 2) == '<') {
                            val close = pattern.indexOf('>', index + 3)
                            if (close > 0) {
                                val raw = pattern.substring(index + 3, close)
                                name = decodeName(raw, unicode)
                                wellFormed = name != null
                            }
                        }
                        if (!inClass) {
                            referenceList.add(Triple(name ?: "", index, wellFormed))
                        }
                    }
                    index = escapeEnd(pattern, index)
                }

                inClass -> {
                    if (c == ']') inClass = false
                    index++
                }

                c == '[' -> {
                    inClass = true
                    index++
                }

                c == '(' -> {
                    if (pattern.getOrNull(index + 1) == '?') {
                        if (pattern.getOrNull(index + 2) == '<') {
                            val marker = pattern.getOrNull(index + 3)
                            if (marker != '=' && marker != '!') {
                                val close = pattern.indexOf('>', index + 3)
                                if (close < 0) throw RegexSyntaxError("Unterminated group name")
                                val raw = pattern.substring(index + 3, close)
                                val decoded = decodeName(raw, unicode)
                                    ?: throw RegexSyntaxError("Invalid group name")
                                groupIndex++
                                if (decoded in declarations) {
                                    throw RegexSyntaxError("Duplicate group name '$decoded'")
                                }
                                declarations[decoded] = groupIndex
                                declarationPositions[decoded] = index
                                index = close + 1
                                continue
                            }
                        }
                        // (?: (?= (?! (?<= (?<! 等非捕获组
                        index += 2
                    } else {
                        groupIndex++
                        index++
                    }
                }

                else -> index++
            }
        }

        val hasNamedGroups = declarations.isNotEmpty()
        var hasForwardReference = false
        for ((name, position, wellFormed) in referenceList) {
            if (!hasNamedGroups) {
                // Annex B：无命名组时 \k 是 IdentityEscape（u 模式另行判错）
                continue
            }
            if (!wellFormed) throw RegexSyntaxError("Invalid named backreference")
            val declaration = declarationPositions[name]
                ?: throw RegexSyntaxError("Unknown group name '$name'")
            if (declaration > position) hasForwardReference = true
        }
        if (!hasNamedGroups && unicode) {
            // u 模式不允许把 k 作为 IdentityEscape
            if (patternWithAnyNamedRef(pattern)) {
                throw RegexSyntaxError("Invalid named backreference")
            }
        }

        val enginePattern = buildEnginePattern(
            pattern,
            unicode,
            hasNamedGroups,
            declarations,
            groupIndex,
        )
        return NamedGroupAnalysis(
            names = declarations,
            enginePattern = enginePattern,
            hasNamedGroups = hasNamedGroups,
            hasForwardReference = hasForwardReference,
        )
    }

    /** 是否存在任意 \k 转义（含字符类内）。 */
    private fun patternWithAnyNamedRef(pattern: String): Boolean {
        var index = 0
        while (index < pattern.length) {
            if (pattern[index] == '\\') {
                if (pattern.getOrNull(index + 1) == 'k') return true
                index = escapeEnd(pattern, index)
            } else {
                index++
            }
        }
        return false
    }

    /** 构建供引擎编译的规范化模式。 */
    private fun buildEnginePattern(
        pattern: String,
        unicode: Boolean,
        hasNamedGroups: Boolean,
        names: Map<String, Int>,
        totalGroups: Int,
    ): String {
        val builder = StringBuilder(pattern.length)
        var index = 0
        var inClass = false
        while (index < pattern.length) {
            val c = pattern[index]
            when {
                c == '\\' -> {
                    val next = pattern.getOrNull(index + 1)
                    when (next) {
                        null -> {
                            builder.append(c)
                            index++
                        }

                        'k' -> {
                            if (inClass) {
                                if (unicode) throw RegexSyntaxError("Invalid escape")
                                // Annex B：字符类内 \k 是字面量 k
                                builder.append('k')
                                index += 2
                            } else if (hasNamedGroups) {
                                val close = pattern.indexOf('>', index + 3)
                                if (pattern.getOrNull(index + 2) != '<' || close < 0) {
                                    throw RegexSyntaxError("Invalid named backreference")
                                }
                                val raw = pattern.substring(index + 3, close)
                                val decoded = decodeName(raw, unicode)
                                    ?: throw RegexSyntaxError("Invalid group name")
                                if (decoded !in names) {
                                    throw RegexSyntaxError("Unknown group name '$decoded'")
                                }
                                builder.append("\\k<").append(decoded).append('>')
                                index = close + 1
                            } else {
                                if (unicode) throw RegexSyntaxError("Invalid escape")
                                // Annex B：无命名组时 \k 是字面量 k
                                builder.append('k')
                                index += 2
                            }
                        }

                        'u' -> {
                            builder.append('\\').append('u')
                            // 原样复制转义序列，交由引擎编译
                            val end = unicodeEscapeEnd(pattern, index)
                            builder.append(pattern, index + 2, end)
                            index = end
                        }

                        in '1'..'9' -> {
                            if (unicode) {
                                builder.append('\\').append(next)
                                index += 2
                            } else {
                                var decimal = 0
                                var cursor = index + 1
                                while (cursor < pattern.length && pattern[cursor] in '0'..'9') {
                                    decimal = decimal * 10 + (pattern[cursor] - '0')
                                    cursor++
                                }
                                if (decimal <= totalGroups) {
                                    builder.append(pattern, index, cursor)
                                    index = cursor
                                } else {
                                    // Annex B：超出组数时按八进制转义（首字符 8/9 为 IdentityEscape）
                                    var octal = 0
                                    var digits = 0
                                    var octalCursor = index + 1
                                    while (digits < 3 && octalCursor < pattern.length &&
                                        pattern[octalCursor] in '0'..'7'
                                    ) {
                                        octal = octal * 8 + (pattern[octalCursor] - '0')
                                        digits++
                                        octalCursor++
                                    }
                                    if (digits > 0) {
                                        builder.append("\\x")
                                        builder.append(octal.toString(16).padStart(2, '0'))
                                        index = octalCursor
                                    } else {
                                        builder.append(next)
                                        index += 2
                                    }
                                }
                            }
                        }

                        else -> {
                            val rewritten = identityRewrite(next, unicode, inClass)
                            if (rewritten != null) {
                                builder.append(rewritten)
                            } else {
                                builder.append('\\').append(next)
                            }
                            index += 2
                        }
                    }
                }

                inClass -> {
                    if (c == ']') inClass = false
                    builder.append(c)
                    index++
                }

                c == '[' -> {
                    inClass = true
                    builder.append(c)
                    index++
                }

                c == '(' -> {
                    if (pattern.getOrNull(index + 1) == '?' &&
                        pattern.getOrNull(index + 2) == '<'
                    ) {
                        val marker = pattern.getOrNull(index + 3)
                        if (marker != '=' && marker != '!') {
                            val close = pattern.indexOf('>', index + 3)
                            val raw = pattern.substring(index + 3, close)
                            val decoded = decodeName(raw, unicode)
                                ?: throw RegexSyntaxError("Invalid group name")
                            builder.append("(?<").append(decoded).append('>')
                            index = close + 1
                            continue
                        }
                    }
                    builder.append(c)
                    index++
                }

                else -> {
                    builder.append(c)
                    index++
                }
            }
        }
        return builder.toString()
    }

    /**
     * 非 u 模式下把 Java 特有含义的转义还原为 JS 的 IdentityEscape 字面量；
     * 返回 null 表示保留原样（已知转义）。
     */
    private fun identityRewrite(escaped: Char, unicode: Boolean, inClass: Boolean): String? {
        if (unicode) {
            // u 模式的 IdentityEscape 只允许语法字符与 '/'，由 unicodeError 处理
            return null
        }
        // 字符类内的 '-' 必须保留转义，否则会被解析为范围分隔符
        if (inClass && escaped == '-') return null
        // 已知转义：字符类、锚点、控制字符、十六进制/Unicode、八进制数字
        if (escaped in "bBdDsSwWnrtfvxuck0") return null
        if (escaped in '1'..'9') return null
        if (escaped in SYNTAX_CHARACTERS) return null
        // JS 非 u 模式下其余字符（如 a、e、h、A）均为 IdentityEscape
        return escaped.toString()
    }

    /** 跳过转义序列，返回其后的索引（用于结构扫描）。 */
    private fun escapeEnd(pattern: String, index: Int): Int {
        val next = pattern.getOrNull(index + 1) ?: return index + 1
        return when (next) {
            'u' -> unicodeEscapeEnd(pattern, index)
            'x' -> (index + 4).coerceAtMost(pattern.length)
            'c' -> (index + 3).coerceAtMost(pattern.length)
            'p', 'P' -> if (pattern.getOrNull(index + 2) == '{') {
                val close = pattern.indexOf('}', index + 3)
                if (close < 0) pattern.length else close + 1
            } else {
                index + 2
            }

            in '0'..'7' -> {
                var cursor = index + 2
                while (cursor < pattern.length && cursor < index + 4 &&
                    pattern[cursor] in '0'..'7'
                ) {
                    cursor++
                }
                cursor
            }

            else -> (index + 2).coerceAtMost(pattern.length)
        }
    }

    private fun unicodeEscapeEnd(pattern: String, index: Int): Int {
        if (pattern.getOrNull(index + 2) == '{') {
            val close = pattern.indexOf('}', index + 3)
            return if (close < 0) pattern.length else close + 1
        }
        return (index + 6).coerceAtMost(pattern.length)
    }

    /**
     * 解码 RegExpIdentifierName；非法返回 null。
     * 支持字面字符、\uXXXX，以及 u 模式下的 \u{...}。
     */
    private fun decodeName(raw: String, unicode: Boolean): String? {
        if (raw.isEmpty()) return null
        val codePoints = ArrayList<Int>()
        var index = 0
        while (index < raw.length) {
            val c = raw[index]
            if (c == '\\') {
                if (raw.getOrNull(index + 1) != 'u') return null
                if (raw.getOrNull(index + 2) == '{') {
                    val close = raw.indexOf('}', index + 3)
                    if (close < 0) return null
                    val digits = raw.substring(index + 3, close)
                    if (digits.isEmpty() || digits.length > 6) return null
                    val value = digits.toIntOrNull(16) ?: return null
                    if (value > 0x10FFFF) return null
                    codePoints.add(value)
                    index = close + 1
                } else {
                    if (index + 6 > raw.length) return null
                    val digits = raw.substring(index + 2, index + 6)
                    val value = digits.toIntOrNull(16) ?: return null
                    // 4 位转义可能是孤立代理项，交由下面的配对逻辑处理
                    codePoints.add(value)
                    index += 6
                }
                continue
            }
            val codePoint = if (c.isHighSurrogate() && index + 1 < raw.length &&
                raw[index + 1].isLowSurrogate()
            ) {
                val value = 0x10000 + ((c.code - 0xD800) shl 10) + (raw[index + 1].code - 0xDC00)
                index++
                value
            } else {
                c.code
            }
            codePoints.add(codePoint)
            index++
        }
        // 合并 \uD835\uDCA4 这类代理项对
        val merged = ArrayList<Int>(codePoints.size)
        var cursor = 0
        while (cursor < codePoints.size) {
            val value = codePoints[cursor]
            if (value in 0xD800..0xDBFF && cursor + 1 < codePoints.size &&
                codePoints[cursor + 1] in 0xDC00..0xDFFF
            ) {
                merged.add(0x10000 + ((value - 0xD800) shl 10) + (codePoints[cursor + 1] - 0xDC00))
                cursor += 2
            } else {
                if (value in 0xD800..0xDFFF) return null // 孤立代理项
                merged.add(value)
                cursor++
            }
        }
        if (merged.isEmpty()) return null
        if (!isIdStart(merged.first())) return null
        for (i in 1 until merged.size) {
            if (!isIdContinue(merged[i])) return null
        }
        return buildString {
            for (codePoint in merged) appendCodePoint(codePoint)
        }
    }

    private fun StringBuilder.appendCodePoint(codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            append(codePoint.toChar())
        } else {
            val value = codePoint - 0x10000
            append(((value shr 10) + 0xD800).toChar())
            append(((value and 0x3FF) + 0xDC00).toChar())
        }
    }

    private fun isIdStart(codePoint: Int): Boolean {
        if (codePoint > 0xFFFF) {
            return ASTRAL_ID_START_RANGES.any { codePoint in it }
        }
        val c = codePoint.toChar()
        return c == '_' || c == '$' || c.isLetter() ||
            c.category == CharCategory.LETTER_NUMBER || c in OTHER_ID_START
    }

    private fun isIdContinue(codePoint: Int): Boolean {
        if (codePoint > 0xFFFF) {
            if (ASTRAL_ID_START_RANGES.any { codePoint in it }) return true
            if (ASTRAL_ID_CONTINUE_ONLY_RANGES.any { codePoint in it }) return true
            return codePoint in 0xE0100..0xE01EF
        }
        val c = codePoint.toChar()
        if (isIdStart(codePoint)) return true
        return c.isDigit() || c.category == CharCategory.DECIMAL_DIGIT_NUMBER ||
            c.category == CharCategory.NON_SPACING_MARK ||
            c.category == CharCategory.ENCLOSING_MARK ||
            c.category == CharCategory.COMBINING_SPACING_MARK ||
            c.category == CharCategory.CONNECTOR_PUNCTUATION ||
            c == '\u200C' || c == '\u200D' || c in OTHER_ID_CONTINUE
    }
}
