package com.muedsa.js.runtime

/**
 * Unicode 规范化（UAX #15），供 String.prototype.normalize 使用。
 *
 * 支持 NFC / NFD / NFKC / NFKD 四种形式：
 * - 分解使用 UnicodeData 的规范/兼容分解映射（递归展开）；
 * - 规范化排序按规范组合类（Canonical Combining Class）稳定排序；
 * - 组合使用主组合对表，Hangul 音节按算法组合。
 */
object UnicodeNormalizer {

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT

    /** 按指定形式规范化；form 必须已校验为 NFC/NFD/NFKC/NFKD。 */
    fun normalize(text: String, form: String): String = when (form) {
        "NFD" -> toCodePointString(toCanonicalOrder(decompose(text, compatibility = false)))
        "NFC" -> compose(toCanonicalOrder(decompose(text, compatibility = false)))
        "NFKD" -> toCodePointString(toCanonicalOrder(decompose(text, compatibility = true)))
        "NFKC" -> compose(toCanonicalOrder(decompose(text, compatibility = true)))
        else -> text
    }

    /** 递归分解为码点序列（含 Hangul 算法分解）。 */
    private fun decompose(text: String, compatibility: Boolean): MutableList<Int> {
        val result = ArrayList<Int>(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint: Int
            val high = text[index]
            if (high.isHighSurrogate() && index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            ) {
                codePoint = (((high.code - 0xD800) shl 10) or
                    (text[index + 1].code - 0xDC00)) + 0x10000
                index += 2
            } else {
                codePoint = high.code
                index++
            }
            decomposeCodePoint(codePoint, compatibility, result)
        }
        return result
    }

    private fun decomposeCodePoint(
        codePoint: Int,
        compatibility: Boolean,
        output: MutableList<Int>,
    ) {
        // Hangul 音节：L V [T]
        val syllableIndex = codePoint - S_BASE
        if (syllableIndex in 0 until S_COUNT) {
            val lead = L_BASE + syllableIndex / N_COUNT
            val vowel = V_BASE + (syllableIndex % N_COUNT) / T_COUNT
            val trail = T_BASE + syllableIndex % T_COUNT
            output.add(lead)
            output.add(vowel)
            if (trail != T_BASE) output.add(trail)
            return
        }
        // 兼容形式优先使用 <tag> 分解，否则回退规范分解
        val mapping = if (compatibility) {
            UnicodeNormalizationData.compatibilityDecomposition(codePoint)
                ?: UnicodeNormalizationData.canonicalDecomposition(codePoint)
        } else {
            UnicodeNormalizationData.canonicalDecomposition(codePoint)
        }
        if (mapping == null) {
            output.add(codePoint)
            return
        }
        for (mapped in mapping) {
            decomposeCodePoint(mapped, compatibility, output)
        }
    }

    /** 按规范组合类做稳定排序（插入排序，序列长度通常很小）。 */
    private fun toCanonicalOrder(codePoints: MutableList<Int>): MutableList<Int> {
        var index = 1
        while (index < codePoints.size) {
            val currentClass = UnicodeNormalizationData.combiningClass(codePoints[index])
            if (currentClass != 0) {
                var cursor = index
                while (cursor > 0) {
                    val previousClass =
                        UnicodeNormalizationData.combiningClass(codePoints[cursor - 1])
                    if (previousClass == 0 || previousClass <= currentClass) break
                    val temporary = codePoints[cursor - 1]
                    codePoints[cursor - 1] = codePoints[cursor]
                    codePoints[cursor] = temporary
                    cursor--
                }
            }
            index++
        }
        return codePoints
    }

    /** 规范组合（含 Hangul 算法组合）。 */
    private fun compose(codePoints: List<Int>): String {
        if (codePoints.isEmpty()) return ""
        val output = ArrayList<Int>(codePoints.size)
        var starterIndex = -1
        var starter = 0
        // 上一个已输出字符的规范组合类；-1 表示紧邻起始符（无中间字符）
        var lastClass = -1
        for (codePoint in codePoints) {
            val currentClass = UnicodeNormalizationData.combiningClass(codePoint)
            if (starterIndex >= 0 && (lastClass < 0 || lastClass < currentClass)) {
                val composed = composePair(starter, codePoint)
                if (composed != null) {
                    output[starterIndex] = composed
                    starter = composed
                    continue
                }
            }
            if (currentClass == 0) {
                starter = codePoint
                starterIndex = output.size
                output.add(codePoint)
                lastClass = -1
            } else {
                output.add(codePoint)
                lastClass = currentClass
            }
        }
        val builder = StringBuilder(output.size)
        for (codePoint in output) {
            appendCodePoint(builder, codePoint)
        }
        return builder.toString()
    }

    /** 码点序列转字符串（代理对按 UTF-16 编码）。 */
    private fun toCodePointString(codePoints: List<Int>): String {
        val builder = StringBuilder(codePoints.size)
        for (codePoint in codePoints) {
            appendCodePoint(builder, codePoint)
        }
        return builder.toString()
    }

    private fun appendCodePoint(builder: StringBuilder, codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            builder.append(codePoint.toChar())
        } else {
            val value = codePoint - 0x10000
            builder.append((0xD800 + (value shr 10)).toChar())
            builder.append((0xDC00 + (value and 0x3FF)).toChar())
        }
    }

    /** Hangul 算法组合与主组合对查表。 */
    private fun composePair(first: Int, second: Int): Int? {
        // L + V -> LV
        if (first in L_BASE until L_BASE + L_COUNT && second in V_BASE until V_BASE + V_COUNT) {
            return S_BASE + ((first - L_BASE) * V_COUNT + (second - V_BASE)) * T_COUNT
        }
        // LV + T -> LVT
        if (first in S_BASE until S_BASE + S_COUNT && (first - S_BASE) % T_COUNT == 0 &&
            second in T_BASE + 1 until T_BASE + T_COUNT
        ) {
            return first + (second - T_BASE)
        }
        return UnicodeNormalizationData.composePair(first, second)
    }
}
