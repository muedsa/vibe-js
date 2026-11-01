package com.muedsa.js.runtime

import com.muedsa.js.runtime.regex.UnicodePropertyData
import com.muedsa.js.runtime.regex.UnicodePropertySet

/**
 * Unicode 默认大小写转换（Unicode 17.0.0）。
 *
 * 与平台 `String.uppercase()/lowercase()` 不同，这里使用仓库内的 UCD 数据，
 * 保证 JVM/JS/Wasm 行为一致：
 * - 先应用 SpecialCasing.txt 的无条件完整映射（如 ß → SS、İ → i + 组合点）；
 * - 否则应用 UnicodeData.txt 的简单映射；
 * - 小写时对 U+03A3 应用 Final_Sigma 上下文规则（词尾写 ς）。
 */
internal object UnicodeCaseMapping {

    private val EMPTY_CODE_POINTS = IntArray(0)

    /** Unicode Final_Sigma 所需的 Cased 属性（UCD 17.0.0）。 */
    private val cased: UnicodePropertySet by lazy {
        UnicodePropertyData.resolve("Cased")
            ?: error("missing Unicode Cased property")
    }

    /** Unicode Final_Sigma 所需的 Case_Ignorable 属性（UCD 17.0.0）。 */
    private val caseIgnorable: UnicodePropertySet by lazy {
        UnicodePropertyData.resolve("Case_Ignorable")
            ?: error("missing Unicode Case_Ignorable property")
    }

    fun toUpperCase(text: String): String = convert(text, upper = true)

    fun toLowerCase(text: String): String = convert(text, upper = false)

    /** 单码点简单大写映射（无映射时返回自身），供正则属性转义的大小写折叠使用。 */
    fun simpleUpperCase(codePoint: Int): Int {
        val mapped = lookupSimple(UnicodeCaseMappingData.UPPER_SIMPLE, codePoint)
        return if (mapped >= 0) mapped else codePoint
    }

    /** 单码点简单小写映射（无映射时返回自身）。 */
    fun simpleLowerCase(codePoint: Int): Int {
        val mapped = lookupSimple(UnicodeCaseMappingData.LOWER_SIMPLE, codePoint)
        return if (mapped >= 0) mapped else codePoint
    }

    /** 简单映射的反向索引：目标码点 → 映射到它的源码点（用于 \p{...} 折叠匹配）。 */
    private val simplePreimages: Map<Int, IntArray> by lazy {
        val result = HashMap<Int, MutableList<Int>>()
        fun collect(table: IntArray) {
            var index = 0
            while (index + 1 < table.size) {
                val source = table[index]
                val target = table[index + 1]
                if (source != target) {
                    result.getOrPut(target) { ArrayList(2) }.add(source)
                }
                index += 2
            }
        }
        collect(UnicodeCaseMappingData.UPPER_SIMPLE)
        collect(UnicodeCaseMappingData.LOWER_SIMPLE)
        result.mapValues { (_, sources) -> sources.toIntArray() }
    }

    /** 返回简单映射到 [codePoint] 的全部源码点。 */
    fun simpleSources(codePoint: Int): IntArray = simplePreimages[codePoint] ?: EMPTY_CODE_POINTS

    private fun convert(text: String, upper: Boolean): String {
        if (text.isEmpty()) return text
        asciiConvert(text, upper)?.let { return it }
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = codePointAt(text, index)
            val width = if (codePoint > 0xFFFF) 2 else 1
            // Final_Sigma 仅影响小写 U+03A3
            if (!upper && codePoint == 0x03A3 && isFinalSigma(text, index, index + width)) {
                builder.append('\u03C2')
                index += width
                continue
            }
            val fullIndex = fullIndex(if (upper) UnicodeCaseMappingData.UPPER_FULL_KEYS else UnicodeCaseMappingData.LOWER_FULL_KEYS, codePoint)
            if (fullIndex >= 0) {
                val offsets = if (upper) UnicodeCaseMappingData.UPPER_FULL_OFFSETS else UnicodeCaseMappingData.LOWER_FULL_OFFSETS
                val values = if (upper) UnicodeCaseMappingData.UPPER_FULL_VALUES else UnicodeCaseMappingData.LOWER_FULL_VALUES
                for (position in offsets[fullIndex] until offsets[fullIndex + 1]) {
                    appendCodePoint(builder, values[position])
                }
            } else {
                val simple = lookupSimple(
                    if (upper) UnicodeCaseMappingData.UPPER_SIMPLE else UnicodeCaseMappingData.LOWER_SIMPLE,
                    codePoint,
                )
                appendCodePoint(builder, if (simple >= 0) simple else codePoint)
            }
            index += width
        }
        return builder.toString()
    }

    /**
     * 纯 ASCII 快速路径；包含非 ASCII 码元时返回 null 交给通用路径。
     * 没有需要转换的 ASCII 字母时直接复用原字符串。
     */
    private fun asciiConvert(text: String, upper: Boolean): String? {
        var needsConversion = false
        for (char in text) {
            if (char.code >= 0x80) return null
            val letter = if (upper) char in 'a'..'z' else char in 'A'..'Z'
            if (letter) needsConversion = true
        }
        if (!needsConversion) return text
        val converted = CharArray(text.length)
        for (index in text.indices) {
            val char = text[index]
            converted[index] = when {
                upper && char in 'a'..'z' -> char - 32
                !upper && char in 'A'..'Z' -> char + 32
                else -> char
            }
        }
        return converted.concatToString()
    }

    /** 判断 U+03A3 是否满足 Unicode Final_Sigma：前方有 Cased，且后方没有 Cased。 */
    private fun isFinalSigma(text: String, start: Int, end: Int): Boolean {
        var cursor = start
        var precededByCased = false
        while (cursor > 0) {
            val previous = codePointBefore(text, cursor)
            val width = if (previous > 0xFFFF) 2 else 1
            // 同时属于 Cased 与 Case_Ignorable 的字符（如 U+0345）按可忽略处理，
            // 否则 "\u0345Σ" 会被错误识别为词尾大写（Test262 special_casing_conditional）
            if (caseIgnorable.contains(previous)) {
                cursor -= width
                continue
            }
            if (cased.contains(previous)) precededByCased = true
            break
        }
        if (!precededByCased) return false
        cursor = end
        while (cursor < text.length) {
            val next = codePointAt(text, cursor)
            if (cased.contains(next) && !caseIgnorable.contains(next)) return false
            if (!caseIgnorable.contains(next)) return true
            cursor += if (next > 0xFFFF) 2 else 1
        }
        return true
    }

    /** 在升序键表中查找完整映射下标；未命中返回 -1。 */
    private fun fullIndex(keys: IntArray, codePoint: Int): Int {
        var low = 0
        var high = keys.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val key = keys[mid]
            when {
                codePoint < key -> high = mid - 1
                codePoint > key -> low = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /** 在 (source, target) 对的升序表中查找简单映射；未命中返回 -1。 */
    private fun lookupSimple(table: IntArray, codePoint: Int): Int {
        var low = 0
        var high = table.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val key = table[mid * 2]
            when {
                codePoint < key -> high = mid - 1
                codePoint > key -> low = mid + 1
                else -> return table[mid * 2 + 1]
            }
        }
        return -1
    }

    /** 按 UTF-16 码元读取一个码点；孤立代理项按自身返回。 */
    private fun codePointAt(text: String, index: Int): Int {        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length) {
            val low = text[index + 1]
            if (low.isLowSurrogate()) {
                return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
            }
        }
        return high.code
    }

    /** 读取 [indexExclusive] 之前的一个码点；孤立代理项按自身返回。 */
    private fun codePointBefore(text: String, indexExclusive: Int): Int {
        val low = text[indexExclusive - 1]
        if (low.isLowSurrogate() && indexExclusive - 2 >= 0) {
            val high = text[indexExclusive - 2]
            if (high.isHighSurrogate()) {
                return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
            }
        }
        return low.code
    }

    /** 追加一个码点；辅助平面按 UTF-16 高位/低位代理项写出。 */
    private fun appendCodePoint(builder: StringBuilder, codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            builder.append(codePoint.toChar())
        } else {
            val value = codePoint - 0x10000
            builder.append((0xD800 + (value shr 10)).toChar())
            builder.append((0xDC00 + (value and 0x3FF)).toChar())
        }
    }
}
