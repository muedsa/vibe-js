package com.muedsa.js.runtime.regex

/**
 * 与平台无关的 JS 正则匹配结果。
 *
 * 捕获组 0 为整个匹配，未参与匹配的组用 null 表示。
 */
class JsMatch(
    val start: Int,
    val end: Int,
    val value: String,
    private val groupValues: List<String?>,
    /** 各组匹配区间（含组 0），与 [groupValues] 一一对应；未参与匹配的组为 null。 */
    private val groupRanges: List<IntRange?>,
) {
    val groupCount: Int get() = groupValues.size

    fun group(index: Int): String? = groupValues.getOrNull(index)

    val groupValuesView: List<String?> get() = groupValues

    /**
     * 组的匹配区间，采用 Kotlin 的半开区间表示：空匹配为 `start until start`。
     * d 标志（hasIndices）据此生成 `[start, end]` 数组。
     */
    fun groupRange(index: Int): IntRange? = groupRanges.getOrNull(index)
}

/**
 * JS 正则匹配器抽象。
 *
 * 正常情况下由 Kotlin Regex 适配器提供；Kotlin Regex 无法编译的 JS 语法
 * （例如 lookahead 中的反向引用）由 [JsRegexEngine] 回退实现。
 */
interface JsRegex {
    /** 从 startIndex 开始查找第一个匹配；不要求匹配正好从 startIndex 开始。 */
    fun find(input: String, startIndex: Int = 0): JsMatch?

    /** 迭代所有匹配（用于全局替换/匹配）。 */
    fun findAll(input: String): Sequence<JsMatch>
}

/** Kotlin Regex 到 [JsRegex] 的适配器。 */
class KotlinJsRegex(private val regex: Regex) : JsRegex {

    /**
     * Kotlin/JS 等平台的 MatchGroup 不暴露捕获位置，
     * 因此只提供组 0 的区间；d 标志的正则统一走回退引擎。
     */
    private fun ranges(match: MatchResult): List<IntRange?> {
        val ranges = ArrayList<IntRange?>(match.groupValues.size)
        for (index in match.groupValues.indices) {
            ranges.add(if (index == 0) match.range else null)
        }
        return ranges
    }

    override fun find(input: String, startIndex: Int): JsMatch? {
        val match = regex.find(input, startIndex) ?: return null
        val groups = ArrayList<String?>(match.groupValues.size)
        for (index in match.groupValues.indices) {
            groups.add(if (index == 0) match.value else match.groups[index]?.value)
        }
        return JsMatch(match.range.first, match.range.last + 1, match.value, groups, ranges(match))
    }

    override fun findAll(input: String): Sequence<JsMatch> = sequence {
        for (match in regex.findAll(input)) {
            val groups = ArrayList<String?>(match.groupValues.size)
            for (index in match.groupValues.indices) {
                groups.add(if (index == 0) match.value else match.groups[index]?.value)
            }
            yield(JsMatch(match.range.first, match.range.last + 1, match.value, groups, ranges(match)))
        }
    }
}