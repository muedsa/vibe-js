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
) {
    val groupCount: Int get() = groupValues.size

    fun group(index: Int): String? = groupValues.getOrNull(index)

    val groupValuesView: List<String?> get() = groupValues
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

    override fun find(input: String, startIndex: Int): JsMatch? {
        val match = regex.find(input, startIndex) ?: return null
        val groups = ArrayList<String?>(match.groupValues.size)
        for (index in match.groupValues.indices) {
            groups.add(if (index == 0) match.value else match.groups[index]?.value)
        }
        return JsMatch(match.range.first, match.range.last + 1, match.value, groups)
    }

    override fun findAll(input: String): Sequence<JsMatch> = sequence {
        for (match in regex.findAll(input)) {
            val groups = ArrayList<String?>(match.groupValues.size)
            for (index in match.groupValues.indices) {
                groups.add(if (index == 0) match.value else match.groups[index]?.value)
            }
            yield(JsMatch(match.range.first, match.range.last + 1, match.value, groups))
        }
    }
}