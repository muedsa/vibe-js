package com.muedsa.js.runtime.regex

/**
 * 纯 Kotlin 的 JS 正则回退引擎。
 *
 * 仅在 Kotlin Regex 无法编译某个 JS 模式时使用（例如 lookahead 中的反向引用）。
 * 支持常用 ES5/ES2015 语法：字面量、字符类、分组、选择、量词、锚点、
 * lookahead、反向引用与 i/m/s 标志。
 */
class JsRegexEngine(
    pattern: String,
    flags: String = "",
    initialNameCandidates: Map<String, List<Int>> = emptyMap(),
) : JsRegex {

    private val unicode = 'u' in flags || 'v' in flags

    /** v 标志：扩展字符类（集合运算、属性字符串与 \q）。 */
    private val unicodeSets = 'v' in flags

    /** 匹配期生效的标志（修饰符组可局部覆盖）。 */
    private class MatchFlags(
        val ignoreCase: Boolean,
        val multiline: Boolean,
        val dotAll: Boolean,
    )

    private val baseFlags = MatchFlags(
        ignoreCase = 'i' in flags,
        multiline = 'm' in flags,
        dotAll = 's' in flags,
    )

    private companion object {
        const val FLAG_IGNORE_CASE = 1
        const val FLAG_MULTILINE = 2
        const val FLAG_DOT_ALL = 4

        /** ASCII 单词字符集合（`\w`）。 */
        val WORD_SET = CodePointSet.of(
            intArrayOf(0x30, 0x39, 0x41, 0x5A, 0x5F, 0x5F, 0x61, 0x7A)
        )

        /** JS WhiteSpace + LineTerminator 集合（`\s`）。 */
        val SPACE_SET = CodePointSet.of(
            intArrayOf(
                0x09, 0x0D, 0x20, 0x20, 0xA0, 0xA0, 0x1680, 0x1680,
                0x2000, 0x200A, 0x2028, 0x2029, 0x202F, 0x202F,
                0x205F, 0x205F, 0x3000, 0x3000, 0xFEFF, 0xFEFF,
            )
        )

        /** v 模式中必须成对转义的保留双标点。 */
        val CLASS_SET_RESERVED_DOUBLES = arrayOf(
            "&&", "!!", "##", "$$", "%%", "**", "++", ",,", "..", "::",
            ";;", "<<", "==", ">>", "??", "@@", "^^", "``", "~~",
        )
    }

    private val root: Node
    private var groupCount: Int = 0

    /** 整个模式就是单个字符类且无捕获组时的快速路径（如 `\p{...}`）。 */
    private val singleClassRoot: Node.ClassNode?

    /** 模式以 `^` 开头且非多行时，只需尝试起点 0。 */
    private val anchoredAtStart: Boolean

    /** 命名捕获组：解码后的名称 → 候选组索引列表。 */
    private var namedGroupIndices: Map<String, List<Int>> = initialNameCandidates

    init {
        val parser = PatternParser(pattern, initialNameCandidates)
        root = parser.parse()
        groupCount = parser.groupCount
        namedGroupIndices = parser.namedGroupsSnapshot()
        singleClassRoot = if (groupCount == 0) {
            ((root as? Node.SequenceNode)?.nodes?.singleOrNull() as? Node.ClassNode)
                ?.takeIf { it.strings.isEmpty() }
        } else {
            null
        }
        val first = (root as? Node.SequenceNode)?.nodes?.firstOrNull() as? Node.AnchorNode
        anchoredAtStart = first?.kind == Node.AnchorKind.START
    }

    override fun find(input: String, startIndex: Int): JsMatch? {
        if (startIndex < 0 || startIndex > input.length) return null
        inputForMatch = input
        singleClassRoot?.let { return findSingleClass(it, startIndex) }
        for (index in startIndex..input.length) {
            // 非多行的 ^ 锚定模式：起点 0 之后的尝试必然失败
            if (anchoredAtStart && !baseFlags.multiline && index > 0) break
            // u 模式按码点推进：不允许从代理对中间开始匹配
            if (unicode && index > 0 && index < input.length &&
                input[index].isLowSurrogate() && input[index - 1].isHighSurrogate()
            ) {
                continue
            }
            var resultCaps: Array<IntRange?>? = null
            val matched = matchNode(root, index, arrayOfNulls(groupCount + 1), baseFlags, false) { end, captures ->
                resultCaps = captures
                internalMatchEnd = end
                true
            }
            if (matched) {
                val end = internalMatchEnd
                val captures = resultCaps ?: arrayOfNulls(groupCount + 1)
                val groups = ArrayList<String?>(groupCount + 1)
                val ranges = ArrayList<IntRange?>(groupCount + 1)
                groups.add(input.substring(index, end))
                ranges.add(index until end)
                for (groupIndex in 1..groupCount) {
                    val range = captures[groupIndex]
                    groups.add(if (range == null) null else input.substring(range.first, range.last + 1))
                    ranges.add(range)
                }
                val namedValues = LinkedHashMap<String, String?>()
                for ((name, indices) in namedGroupIndices) {
                    var value: String? = null
                    for (index in indices) {
                        val candidate = groups.getOrNull(index)
                        if (candidate != null) {
                            value = candidate
                            break
                        }
                    }
                    namedValues[name] = value
                }
                return JsMatch(
                    index,
                    end,
                    input.substring(index, end),
                    groups,
                    ranges,
                    namedGroupIndices,
                    namedValues,
                )
            }
        }
        return null
    }

    /** 单字符类快速路径：逐码点扫描，避免通用回溯框架的节点分发开销。 */
    private fun findSingleClass(node: Node.ClassNode, startIndex: Int): JsMatch? {
        var index = startIndex
        while (index <= inputForMatch.length) {
            if (index == inputForMatch.length) return null
            // u 模式按码点推进，跳过代理对中间的位置
            if (unicode && index > 0 && inputForMatch[index].isLowSurrogate() &&
                inputForMatch[index - 1].isHighSurrogate()
            ) {
                index++
                continue
            }
            val codePoint = codePointAt(index)
            val width = if (codePoint > 0xFFFF) 2 else 1
            var matched = false
            for (item in node.items) {
                if (item.matches(codePoint, baseFlags.ignoreCase, unicode)) {
                    matched = true
                    break
                }
            }
            if (matched != node.negated) {
                val end = index + width
                val value = inputForMatch.substring(index, end)
                return JsMatch(
                    start = index,
                    end = end,
                    value = value,
                    groupValues = listOf(value),
                    groupRanges = listOf(index until end),
                )
            }
            index += width
        }
        return null
    }

    override fun findAll(input: String): Sequence<JsMatch> = sequence {
        var index = 0
        while (index <= input.length) {
            val match = find(input, index) ?: break
            yield(match)
            index = if (match.end > match.start) match.end else match.start + 1
        }
    }

    // 匹配结果结束位置的临时通道，避免在每个节点中传递额外的可变对象。
    private var internalMatchEnd: Int = 0

    /**
     * 节点匹配。reverse=true 时按 JS lookbehind 语义从右向左消费字符
     * （用于 (?<=...)/(?<!...)）；字符序、量词迭代与捕获覆盖顺序随之反向。
     */
    private fun matchNode(
        node: Node,
        pos: Int,
        captures: Array<IntRange?>,
        flags: MatchFlags,
        reverse: Boolean,
        continuation: (Int, Array<IntRange?>) -> Boolean,
    ): Boolean {
        return when (node) {
            is Node.CharNode -> {
                if (reverse) {
                    if (pos <= 0) {
                        false
                    } else if (!charEquals(inputForMatch[pos - 1], node.character, flags.ignoreCase)) {
                        false
                    } else {
                        continuation(pos - 1, captures)
                    }
                } else {
                    if (pos >= inputForMatch.length) false
                    else if (!charEquals(inputForMatch[pos], node.character, flags.ignoreCase)) false
                    else continuation(pos + 1, captures)
                }
            }

            is Node.AnyNode -> {
                if (reverse) {
                    if (pos <= 0) {
                        false
                    } else if (!flags.dotAll && isLineTerminator(inputForMatch[pos - 1])) {
                        false
                    } else {
                        val next = if (unicode && pos >= 2 &&
                            inputForMatch[pos - 2].isHighSurrogate() &&
                            inputForMatch[pos - 1].isLowSurrogate()
                        ) {
                            pos - 2
                        } else {
                            pos - 1
                        }
                        continuation(next, captures)
                    }
                } else {
                    if (pos >= inputForMatch.length) {
                        false
                    } else if (!flags.dotAll && isLineTerminator(inputForMatch[pos])) {
                        false
                    } else {
                        // u 模式下 . 匹配完整码点（代理项对整体消耗）
                        val next = if (unicode && inputForMatch[pos].isHighSurrogate() &&
                            pos + 1 < inputForMatch.length &&
                            inputForMatch[pos + 1].isLowSurrogate()
                        ) {
                            pos + 2
                        } else {
                            pos + 1
                        }
                        continuation(next, captures)
                    }
                }
            }

            is Node.ClassNode -> {
                // u 模式按完整码点匹配；v 模式的字符串操作数可消耗多个码点，
                // 需要在同一位置尝试多个候选长度（回溯）
                if (reverse) {
                    if (pos <= 0) {
                        if (node.negated) {
                            false
                        } else {
                            var result = false
                            for (string in node.strings) {
                                if (string.isEmpty() && continuation(pos, captures)) {
                                    result = true
                                    break
                                }
                            }
                            result
                        }
                    } else {
                        var matched = false
                        val codePoint = codePointBefore(pos)
                        for (item in node.items) {
                            if (item.matches(codePoint, flags.ignoreCase, unicode)) {
                                matched = true
                                break
                            }
                        }
                        if (node.negated) {
                            if (matched) false
                            else continuation(pos - codePointWidthBefore(pos), captures)
                        } else {
                            var result = false
                            if (matched) {
                                result = continuation(pos - codePointWidthBefore(pos), captures)
                            }
                            if (!result) {
                                for (string in node.strings) {
                                    if (matchesEndingAt(pos, string, false) &&
                                        continuation(pos - string.length, captures)
                                    ) {
                                        result = true
                                        break
                                    }
                                }
                            }
                            result
                        }
                    }
                } else {
                    if (pos > inputForMatch.length) {
                        false
                    } else if (pos >= inputForMatch.length) {
                        if (node.negated) {
                            false
                        } else {
                            var result = false
                            for (string in node.strings) {
                                if (string.isEmpty() && continuation(pos, captures)) {
                                    result = true
                                    break
                                }
                            }
                            result
                        }
                    } else {
                        var matched = false
                        val codePoint = codePointAt(pos)
                        for (item in node.items) {
                            if (item.matches(codePoint, flags.ignoreCase, unicode)) {
                                matched = true
                                break
                            }
                        }
                        if (node.negated) {
                            if (matched) false
                            else continuation(pos + codePointWidthAt(pos), captures)
                        } else {
                            var result = false
                            if (matched) {
                                result = continuation(pos + codePointWidthAt(pos), captures)
                            }
                            if (!result) {
                                for (string in node.strings) {
                                    if (matchesAt(pos, string, false) &&
                                        continuation(pos + string.length, captures)
                                    ) {
                                        result = true
                                        break
                                    }
                                }
                            }
                            result
                        }
                    }
                }
            }

            is Node.SequenceNode ->
                matchSequence(
                    node.nodes,
                    if (reverse) node.nodes.size - 1 else 0,
                    pos,
                    captures,
                    flags,
                    reverse,
                    continuation,
                )

            is Node.AlternationNode -> {
                for (branch in node.branches) {
                    if (matchNode(branch, pos, captures.copyOf(), flags, reverse, continuation)) {
                        return true
                    }
                }
                false
            }

            is Node.GroupNode -> {
                matchNode(node.child, pos, captures, flags, reverse) { end, innerCaptures ->
                    val updated = innerCaptures.copyOf()
                    if (node.index != null) {
                        updated[node.index] = minOf(pos, end) until maxOf(pos, end)
                    }
                    continuation(end, updated)
                }
            }

            // 修饰符组：按 add/remove 位掩码局部覆盖 i/m/s
            is Node.FlagGroupNode -> {
                val nested = MatchFlags(
                    ignoreCase = when {
                        node.add and FLAG_IGNORE_CASE != 0 -> true
                        node.remove and FLAG_IGNORE_CASE != 0 -> false
                        else -> flags.ignoreCase
                    },
                    multiline = when {
                        node.add and FLAG_MULTILINE != 0 -> true
                        node.remove and FLAG_MULTILINE != 0 -> false
                        else -> flags.multiline
                    },
                    dotAll = when {
                        node.add and FLAG_DOT_ALL != 0 -> true
                        node.remove and FLAG_DOT_ALL != 0 -> false
                        else -> flags.dotAll
                    },
                )
                matchNode(node.child, pos, captures, nested, reverse, continuation)
            }

            is Node.QuantifierNode ->
                matchQuantifier(node, 0, pos, captures, flags, reverse, continuation)

            is Node.LookaheadNode -> {
                // 前向断言始终从当前位置向右匹配
                var lookedCaptures: Array<IntRange?>? = null
                val matched = matchNode(node.child, pos, captures.copyOf(), flags, false) { _, inner ->
                    lookedCaptures = inner
                    true
                }
                if (node.positive) {
                    if (matched) continuation(pos, lookedCaptures ?: captures) else false
                } else {
                    if (matched) false else continuation(pos, captures)
                }
            }

            is Node.LookbehindNode -> {
                // 后向断言从当前位置向左匹配，匹配成功不消耗字符
                var lookedCaptures: Array<IntRange?>? = null
                val matched = matchNode(node.child, pos, captures.copyOf(), flags, true) { _, inner ->
                    lookedCaptures = inner
                    true
                }
                if (node.positive) {
                    if (matched) continuation(pos, lookedCaptures ?: captures) else false
                } else {
                    if (matched) false else continuation(pos, captures)
                }
            }

            is Node.AnchorNode -> when (node.kind) {
                Node.AnchorKind.START ->
                    if (pos == 0 ||
                        (flags.multiline && pos > 0 && isLineTerminator(inputForMatch[pos - 1]))
                    ) {
                        continuation(pos, captures)
                    } else {
                        false
                    }

                Node.AnchorKind.END ->
                    if (pos == inputForMatch.length ||
                        (flags.multiline && pos < inputForMatch.length &&
                            isLineTerminator(inputForMatch[pos]))
                    ) {
                        continuation(pos, captures)
                    } else {
                        false
                    }

                Node.AnchorKind.WORD_BOUNDARY -> {
                    val previousIsWord = pos > 0 && isWord(inputForMatch[pos - 1], flags.ignoreCase)
                    val currentIsWord = pos < inputForMatch.length &&
                        isWord(inputForMatch[pos], flags.ignoreCase)
                    val boundary = previousIsWord != currentIsWord
                    if (boundary == node.positive) continuation(pos, captures) else false
                }
            }

            is Node.BackReferenceNode -> {
                // 重复命名组：取首个参与匹配的候选组；均未参与时匹配空串
                var range: IntRange? = null
                for (index in node.indices) {
                    val candidate = captures.getOrNull(index)
                    if (candidate != null) {
                        range = candidate
                        break
                    }
                }
                if (range == null) {
                    continuation(pos, captures)
                } else {
                    val expected = inputForMatch.substring(range.first, range.last + 1)
                    if (reverse) {
                        if (matchesEndingAt(pos, expected, flags.ignoreCase)) {
                            continuation(pos - expected.length, captures)
                        } else {
                            false
                        }
                    } else {
                        if (matchesAt(pos, expected, flags.ignoreCase)) {
                            continuation(pos + expected.length, captures)
                        } else {
                            false
                        }
                    }
                }
            }
        }
    }

    private fun matchSequence(
        nodes: List<Node>,
        startIndex: Int,
        pos: Int,
        captures: Array<IntRange?>,
        flags: MatchFlags,
        reverse: Boolean,
        continuation: (Int, Array<IntRange?>) -> Boolean,
    ): Boolean {
        val step = if (reverse) -1 else 1
        fun recurse(index: Int, currentPos: Int, currentCaptures: Array<IntRange?>): Boolean {
            if (if (reverse) index < 0 else index >= nodes.size) {
                return continuation(currentPos, currentCaptures)
            }
            return matchNode(nodes[index], currentPos, currentCaptures, flags, reverse) {
                    nextPos, nextCaptures ->
                recurse(index + step, nextPos, nextCaptures)
            }
        }
        return recurse(startIndex, pos, captures)
    }

    private fun matchQuantifier(
        node: Node.QuantifierNode,
        count: Int,
        pos: Int,
        captures: Array<IntRange?>,
        flags: MatchFlags,
        reverse: Boolean,
        continuation: (Int, Array<IntRange?>) -> Boolean,
    ): Boolean {
        // 规范：量词每次迭代前清除其内部捕获组的值（反向匹配时迭代自右向左）
        val innerGroups = mutableSetOf<Int>()
        collectGroupIndices(node.child, innerGroups)
        fun iterationCaptures(base: Array<IntRange?>): Array<IntRange?> {
            if (innerGroups.isEmpty()) return base
            val copy = base.copyOf()
            for (group in innerGroups) {
                if (group in copy.indices) copy[group] = null
            }
            return copy
        }

        fun repeat(nextPos: Int, nextCaptures: Array<IntRange?>): Boolean {
            if (nextPos == pos) {
                // 规范 RepeatMatcher：min 已满足时零宽迭代被丢弃（含捕获回滚），
                // 仅必要迭代（count < min）允许零宽推进，避免死循环
                if (count >= node.min) return false
                return continuation(nextPos, nextCaptures)
            }
            return matchQuantifier(node, count + 1, nextPos, nextCaptures, flags, reverse, continuation)
        }

        // 单字符类/字面量/任意字符的量词：迭代消费，避免长文本上递归导致栈溢出
        if (count == 0 && innerGroups.isEmpty()) {
            val child = node.child
            if (child is Node.CharNode || child is Node.AnyNode ||
                (child is Node.ClassNode && child.strings.isEmpty())
            ) {
                return matchSimpleQuantifier(node, pos, captures, flags, reverse, continuation)
            }
            if (child is Node.ClassNode) {
                return matchStringClassQuantifier(node, child, pos, captures, flags, reverse, continuation)
            }
        }

        if (count < node.min) {
            return matchNode(node.child, pos, iterationCaptures(captures), flags, reverse) { nextPos, nextCaptures ->
                repeat(nextPos, nextCaptures)
            }
        }

        if (node.greedy) {
            if (count < node.max) {
                val matched = matchNode(
                    node.child,
                    pos,
                    iterationCaptures(captures),
                    flags,
                    reverse,
                ) { nextPos, nextCaptures ->
                    repeat(nextPos, nextCaptures)
                }
                if (matched) return true
            }
            return continuation(pos, captures)
        }

        if (continuation(pos, captures)) return true
        if (count < node.max) {
            return matchNode(node.child, pos, iterationCaptures(captures), flags, reverse) { nextPos, nextCaptures ->
                repeat(nextPos, nextCaptures)
            }
        }
        return false
    }

    /**
     * 简单节点量词的迭代实现：一次消费一个码点/字符，不产生递归调用栈。
     *
     * 贪婪模式先消费到上限再逐个回退；惰性模式在满足下限后逐个尝试余下位置。
     * 子节点不含捕获组，因此无需重置捕获。
     */
    private fun matchSimpleQuantifier(
        node: Node.QuantifierNode,
        startPos: Int,
        captures: Array<IntRange?>,
        flags: MatchFlags,
        reverse: Boolean,
        continuation: (Int, Array<IntRange?>) -> Boolean,
    ): Boolean {
        var current = startPos
        var consumed = 0
        if (!node.greedy) {
            if (node.min == 0 && continuation(current, captures)) return true
            while (consumed < node.max) {
                val next = simpleStep(node.child, current, flags, reverse) ?: break
                current = next
                consumed++
                if (consumed >= node.min && continuation(current, captures)) return true
            }
            return false
        }

        while (consumed < node.max) {
            val next = simpleStep(node.child, current, flags, reverse) ?: break
            current = next
            consumed++
        }
        if (consumed < node.min) return false
        if (continuation(current, captures)) return true
        if (consumed == node.min) return false

        // 需要回退时再收集中间位置，避免常规成功路径分配大数组
        val positions = IntArray(consumed)
        var probe = startPos
        for (index in 0 until consumed) {
            probe = simpleStep(node.child, probe, flags, reverse) ?: return false
            positions[index] = probe
        }
        var back = consumed - 1
        while (back >= node.min) {
            val position = if (back == 0) startPos else positions[back - 1]
            if (continuation(position, captures)) return true
            back--
        }
        return false
    }

    /**
     * 含属性字符串的字符类量词：迭代消费并优先选择最长匹配，避免递归过深。
     *
     * 与 [matchSimpleQuantifier] 相同，贪婪模式在失败后按已消费位置回退。
     */
    private fun matchStringClassQuantifier(
        node: Node.QuantifierNode,
        child: Node.ClassNode,
        startPos: Int,
        captures: Array<IntRange?>,
        flags: MatchFlags,
        reverse: Boolean,
        continuation: (Int, Array<IntRange?>) -> Boolean,
    ): Boolean {
        var current = startPos
        var consumed = 0
        var zeroWidth = false
        if (!node.greedy) {
            if (node.min == 0 && continuation(current, captures)) return true
            while (consumed < node.max) {
                val step = stringClassStep(child, current, flags, reverse)
                if (step < 0) break
                if (step == 0) {
                    zeroWidth = true
                    break
                }
                current += if (reverse) -step else step
                consumed++
                if (consumed >= node.min && continuation(current, captures)) return true
            }
            if (zeroWidth && continuation(current, captures)) return true
            return false
        }

        while (consumed < node.max) {
            val step = stringClassStep(child, current, flags, reverse)
            if (step < 0) break
            if (step == 0) {
                zeroWidth = true
                break
            }
            current += if (reverse) -step else step
            consumed++
        }
        if (consumed < node.min) {
            // 零宽匹配（\q{}）按通用量词语义允许一次空迭代
            return zeroWidth && continuation(current, captures)
        }
        if (continuation(current, captures)) return true
        if (consumed == node.min) return false

        val positions = IntArray(consumed)
        var probe = startPos
        for (index in 0 until consumed) {
            val step = stringClassStep(child, probe, flags, reverse)
            if (step <= 0) return false
            probe += if (reverse) -step else step
            positions[index] = probe
        }
        var back = consumed - 1
        while (back >= node.min) {
            val position = if (back == 0) startPos else positions[back - 1]
            if (continuation(position, captures)) return true
            back--
        }
        return false
    }

    /**
     * 字符串类单步：返回消费长度（UTF-16 码元），0 表示仅空字符串匹配，
     * -1 表示无匹配。
     */
    private fun stringClassStep(
        node: Node.ClassNode,
        pos: Int,
        flags: MatchFlags,
        reverse: Boolean,
    ): Int {
        var best = -1
        if (reverse) {
            if (pos > 0) {
                val codePoint = codePointBefore(pos)
                for (item in node.items) {
                    if (item.matches(codePoint, flags.ignoreCase, unicode)) {
                        best = maxOf(best, codePointWidthBefore(pos))
                        break
                    }
                }
            }
            for (string in node.strings) {
                if (string.length > best && matchesEndingAt(pos, string, false)) {
                    best = string.length
                }
            }
        } else {
            if (pos < inputForMatch.length) {
                val codePoint = codePointAt(pos)
                for (item in node.items) {
                    if (item.matches(codePoint, flags.ignoreCase, unicode)) {
                        best = maxOf(best, codePointWidthAt(pos))
                        break
                    }
                }
            }
            for (string in node.strings) {
                if (string.length > best && matchesAt(pos, string, false)) {
                    best = string.length
                }
            }
        }
        return best
    }

    /** 消费一个简单节点；不匹配返回 null。 */
    private fun simpleStep(node: Node, pos: Int, flags: MatchFlags, reverse: Boolean): Int? {
        if (reverse) {
            if (pos <= 0) return null
            return when (node) {
                is Node.CharNode -> {
                    if (charEquals(inputForMatch[pos - 1], node.character, flags.ignoreCase)) pos - 1 else null
                }

                is Node.AnyNode -> {
                    if (!flags.dotAll && isLineTerminator(inputForMatch[pos - 1])) {
                        null
                    } else {
                        pos - codePointWidthBefore(pos)
                    }
                }

                is Node.ClassNode -> {
                    val codePoint = codePointBefore(pos)
                    var matched = false
                    for (item in node.items) {
                        if (item.matches(codePoint, flags.ignoreCase, unicode)) {
                            matched = true
                            break
                        }
                    }
                    if (matched != node.negated) pos - codePointWidthBefore(pos) else null
                }

                else -> null
            }
        }
        if (pos >= inputForMatch.length) return null
        return when (node) {
            is Node.CharNode -> {
                if (charEquals(inputForMatch[pos], node.character, flags.ignoreCase)) pos + 1 else null
            }

            is Node.AnyNode -> {
                if (!flags.dotAll && isLineTerminator(inputForMatch[pos])) {
                    null
                } else {
                    pos + codePointWidthAt(pos)
                }
            }

            is Node.ClassNode -> {
                val codePoint = codePointAt(pos)
                var matched = false
                for (item in node.items) {
                    if (item.matches(codePoint, flags.ignoreCase, unicode)) {
                        matched = true
                        break
                    }
                }
                if (matched != node.negated) pos + codePointWidthAt(pos) else null
            }

            else -> null
        }
    }

    /** 收集节点内部所有捕获组编号（用于量词迭代时的捕获重置）。 */
    private fun collectGroupIndices(node: Node, result: MutableSet<Int>) {
        when (node) {
            is Node.GroupNode -> {
                node.index?.let { result.add(it) }
                collectGroupIndices(node.child, result)
            }
            is Node.SequenceNode -> node.nodes.forEach { collectGroupIndices(it, result) }
            is Node.AlternationNode -> node.branches.forEach { collectGroupIndices(it, result) }
            is Node.QuantifierNode -> collectGroupIndices(node.child, result)
            is Node.LookaheadNode -> collectGroupIndices(node.child, result)
            is Node.LookbehindNode -> collectGroupIndices(node.child, result)
            is Node.FlagGroupNode -> collectGroupIndices(node.child, result)
            else -> Unit
        }
    }

    /** 反向匹配：expected 是否恰好结束于 pos。 */
    private fun matchesEndingAt(pos: Int, expected: String, ignoreCase: Boolean): Boolean {
        if (pos - expected.length < 0) return false
        val start = pos - expected.length
        for (index in expected.indices) {
            if (!charEquals(inputForMatch[start + index], expected[index], ignoreCase)) return false
        }
        return true
    }

    private fun matchesAt(pos: Int, expected: String, ignoreCase: Boolean): Boolean {
        if (pos + expected.length > inputForMatch.length) return false
        for (index in expected.indices) {
            if (!charEquals(inputForMatch[pos + index], expected[index], ignoreCase)) return false
        }
        return true
    }

    private fun charEquals(left: Char, right: Char, ignoreCase: Boolean): Boolean {
        if (!ignoreCase) return left == right
        if (left == right) return true
        return if (unicode) {
            // 规范 Canonicalize：仅应用 CaseFolding.txt 的简单/通用折叠，
            // 不用 Kotlin 的完整大小写转换（后者会在 U+1FD3 等字符上过度折叠）
            UnicodeCaseFoldingData.simpleFold(left.code) ==
                UnicodeCaseFoldingData.simpleFold(right.code)
        } else {
            // 非 u 模式：非 ASCII 字符转为 ASCII 大写时保持原字符（如 U+212A）
            canonicalizeNonUnicode(left) == canonicalizeNonUnicode(right)
        }
    }

    private fun canonicalizeNonUnicode(character: Char): Char {
        val upper = character.uppercaseChar()
        return if (character.code >= 128 && upper.code < 128) character else upper
    }

    /**
     * 单词字符判定（ASCII \w）；i 模式下 ς/ſ/K 等会大小写折叠到 ASCII 字母，
     * 与非 u 模式 JS 的 Canonicalize 语义一致。
     */
    private fun isWord(character: Char, ignoreCase: Boolean): Boolean {
        if (character in 'a'..'z' || character in 'A'..'Z' ||
            character in '0'..'9' || character == '_'
        ) {
            return true
        }
        if (!ignoreCase) return false
        return isAsciiWord(character.lowercaseChar()) || isAsciiWord(character.uppercaseChar())
    }

    private fun isAsciiWord(character: Char): Boolean =
        character in 'a'..'z' || character in 'A'..'Z' ||
            character in '0'..'9' || character == '_'

    private fun isLineTerminator(character: Char): Boolean =
        character == '\n' || character == '\r' || character == '\u2028' || character == '\u2029'

    /** 位置 index 处的码点；非 u 模式按 UTF-16 码元处理。 */
    private fun codePointAt(index: Int): Int {
        val character = inputForMatch[index]
        if (unicode && character.isHighSurrogate() && index + 1 < inputForMatch.length &&
            inputForMatch[index + 1].isLowSurrogate()
        ) {
            return 0x10000 + ((character.code - 0xD800) shl 10) +
                (inputForMatch[index + 1].code - 0xDC00)
        }
        return character.code
    }

    /** 位置 index 处的码点宽度（1 或 2 个 UTF-16 码元）。 */
    private fun codePointWidthAt(index: Int): Int = if (codePointAt(index) > 0xFFFF) 2 else 1

    /** 结束于 index 的码点；非 u 模式按 UTF-16 码元处理。 */
    private fun codePointBefore(index: Int): Int {
        val character = inputForMatch[index - 1]
        if (unicode && character.isLowSurrogate() && index >= 2 &&
            inputForMatch[index - 2].isHighSurrogate()
        ) {
            return 0x10000 + ((inputForMatch[index - 2].code - 0xD800) shl 10) +
                (character.code - 0xDC00)
        }
        return character.code
    }

    /** 结束于 index 的码点宽度（1 或 2 个 UTF-16 码元）。 */
    private fun codePointWidthBefore(index: Int): Int = if (codePointBefore(index) > 0xFFFF) 2 else 1

    // find 期间保存输入，避免给每个节点传输入参数
    private var inputForMatch: String = ""

    // ================================================================
    // 模式解析
    // ================================================================

    private sealed class Node {
        data class CharNode(val character: Char) : Node()
        object AnyNode : Node()
        /** v 模式的字符串操作数（\q 或属性字符串）；items 负责码点部分。 */
        data class ClassNode(
            val negated: Boolean,
            val items: List<ClassItem>,
            val strings: List<String> = emptyList(),
        ) : Node()
        data class SequenceNode(val nodes: List<Node>) : Node()
        data class AlternationNode(val branches: List<Node>) : Node()
        data class GroupNode(val index: Int?, val child: Node) : Node()

        /** 作用域修饰符组：(?ims-ims:child)。 */
        data class FlagGroupNode(val add: Int, val remove: Int, val child: Node) : Node()

        /** 后向断言：(?<=child) / (?<!child)。 */
        data class LookbehindNode(val positive: Boolean, val child: Node) : Node()
        data class QuantifierNode(
            val child: Node,
            val min: Int,
            val max: Int,
            val greedy: Boolean,
        ) : Node()

        data class BackReferenceNode(val indices: List<Int>) : Node()
        data class LookaheadNode(val child: Node, val positive: Boolean) : Node()
        data class AnchorNode(val kind: AnchorKind, val positive: Boolean = true) : Node()

        enum class AnchorKind {
            START,
            END,
            WORD_BOUNDARY,
        }
    }

    private sealed class ClassItem {
        abstract fun matches(codePoint: Int, ignoreCase: Boolean, unicode: Boolean): Boolean

        data class Single(val codePoint: Int) : ClassItem() {
            override fun matches(codePoint: Int, ignoreCase: Boolean, unicode: Boolean): Boolean {
                if (codePoint == this.codePoint) return true
                if (!ignoreCase) return false
                if (unicode) {
                    return UnicodeCaseFoldingData.simpleFold(codePoint) ==
                        UnicodeCaseFoldingData.simpleFold(this.codePoint)
                }
                if (codePoint > 0xFFFF || this.codePoint > 0xFFFF) return false
                return codePoint.toChar().equals(this.codePoint.toChar(), ignoreCase = true)
            }
        }

        data class Range(val start: Int, val end: Int) : ClassItem() {
            override fun matches(codePoint: Int, ignoreCase: Boolean, unicode: Boolean): Boolean {
                if (codePoint in start..end) return true
                if (!ignoreCase || codePoint > 0xFFFF || start > 0xFFFF || end > 0xFFFF) {
                    return false
                }
                if (unicode) {
                    // 规范：比较输入与集合成员的 Canonicalize 结果
                    val folded = UnicodeCaseFoldingData.simpleFold(codePoint)
                    val foldedStart = UnicodeCaseFoldingData.simpleFold(start)
                    val foldedEnd = UnicodeCaseFoldingData.simpleFold(end)
                    return folded in start..end || codePoint in foldedStart..foldedEnd
                }
                // 非 u 模式沿用大写映射的近似语义
                val character = codePoint.toChar()
                val lower = character.lowercaseChar().code
                val upper = character.uppercaseChar().code
                if (lower in start..end || upper in start..end) return true
                val foldedStart = start.toChar().lowercaseChar().code
                val foldedEnd = end.toChar().lowercaseChar().code
                return lower in foldedStart..foldedEnd
            }
        }

        data class Predicate(
            val description: String,
            val predicate: (Int, Boolean) -> Boolean,
        ) : ClassItem() {
            override fun matches(codePoint: Int, ignoreCase: Boolean, unicode: Boolean): Boolean =
                predicate(codePoint, ignoreCase)
        }

        /** v 模式扩展字符类求值后的码点集合。 */
        data class Ranges(val set: CodePointSet) : ClassItem() {
            override fun matches(codePoint: Int, ignoreCase: Boolean, unicode: Boolean): Boolean {
                if (set.contains(codePoint)) return true
                if (!ignoreCase || codePoint > 0xFFFF) return false
                val character = codePoint.toChar()
                if (unicode) {
                    return set.contains(UnicodeCaseFoldingData.simpleFold(codePoint)) ||
                        set.contains(character.uppercaseChar().code) ||
                        set.contains(character.lowercaseChar().code)
                }
                return set.contains(character.uppercaseChar().code) ||
                    set.contains(character.lowercaseChar().code)
            }
        }

        /**
         * Unicode 属性转义：`\p{...}` / `\P{...}`。
         *
         * i 模式下按规范先得到有效集合（`\P` 取补集），再匹配码点或其大小写
         * 折叠变体；因此 `(?i:\P{Lu})` 能匹配大写字母（其小写形态属于补集）。
         */
        data class Property(val set: UnicodePropertySet, val negated: Boolean) : ClassItem() {
            override fun matches(codePoint: Int, ignoreCase: Boolean, unicode: Boolean): Boolean {
                if (containsEffective(codePoint)) return true
                if (!ignoreCase || codePoint > 0xFFFF) return false
                val character = codePoint.toChar()
                val upper = character.uppercaseChar()
                val lower = character.lowercaseChar()
                if (unicode) {
                    // 规范同时折叠输入与集合成员；只检查大小写映射的近似集合
                    return containsEffective(UnicodeCaseFoldingData.simpleFold(codePoint)) ||
                        containsEffective(upper.code) ||
                        containsEffective(lower.code) ||
                        containsEffective(upper.lowercaseChar().code)
                }
                return containsEffective(upper.code) ||
                    containsEffective(lower.code) ||
                    containsEffective(upper.lowercaseChar().code)
            }

            private fun containsEffective(codePoint: Int): Boolean =
                set.contains(codePoint) != negated
        }
    }

    /** v 模式字符类的求值结果：码点集合 + 多码点字符串（属性字符串或 \q）。 */
    private class ClassSetValue(
        val codePoints: CodePointSet,
        val strings: List<String>,
    ) {
        fun union(other: ClassSetValue): ClassSetValue = ClassSetValue(
            codePoints.union(other.codePoints),
            mergeStrings(strings, other.strings),
        )

        fun intersect(other: ClassSetValue): ClassSetValue = ClassSetValue(
            codePoints.intersect(other.codePoints),
            strings.filter { it in other.strings }.distinct(),
        )

        fun subtract(other: ClassSetValue): ClassSetValue = ClassSetValue(
            codePoints.subtract(other.codePoints),
            strings.filter { it !in other.strings },
        )

        fun complement(): ClassSetValue {
            if (strings.isNotEmpty()) {
                throw IllegalArgumentException("Negated character class may contain strings")
            }
            return ClassSetValue(codePoints.complement(), emptyList())
        }

        companion object {
            val EMPTY = ClassSetValue(CodePointSet.EMPTY, emptyList())

            fun ofCodePoints(set: CodePointSet): ClassSetValue =
                ClassSetValue(set, emptyList())

            /** 单码点字符串规范化为码点集合，保留多码点与空字符串。 */
            fun ofStrings(values: List<String>): ClassSetValue {
                var codePoints = CodePointSet.EMPTY
                val multi = LinkedHashSet<String>()
                for (value in values) {
                    val single = singleCodePoint(value)
                    if (single != null) {
                        codePoints = codePoints.union(CodePointSet.singleton(single))
                    } else {
                        multi.add(value)
                    }
                }
                return ClassSetValue(codePoints, multi.toList())
            }

            private fun mergeStrings(
                left: List<String>,
                right: List<String>,
            ): List<String> {
                if (right.isEmpty()) return left
                if (left.isEmpty()) return right
                val merged = LinkedHashSet(left)
                merged.addAll(right)
                return merged.toList()
            }

            private fun singleCodePoint(value: String): Int? = when {
                value.isEmpty() -> null
                value.length == 1 -> value[0].code
                value.length == 2 && value[0].isHighSurrogate() && value[1].isLowSurrogate() ->
                    0x10000 + ((value[0].code - 0xD800) shl 10) + (value[1].code - 0xDC00)
                else -> null
            }
        }
    }

    /** 集合项：值 + 是否为范围（范围不能直接作为 && / -- 的操作数）。 */
    private class ClassSetTerm(val value: ClassSetValue, val isRange: Boolean)

    /** 集合操作数：单个字符（可构成范围）或已求值的集合。 */
    private sealed class SetOperand {
        data class CharAtom(val value: Int) : SetOperand()
        data class Value(val value: ClassSetValue) : SetOperand()
    }

    private inner class PatternParser(
        private val pattern: String,
        initialNameCandidates: Map<String, List<Int>> = emptyMap(),
    ) {
        private var position = 0
        var groupCount: Int = 0
            private set

        /** 整个模式的总捕获组数（解析前统计，用于区分前向反向引用与旧式八进制转义）。 */
        private val totalGroupCount: Int = countCapturingGroups(pattern)

        private fun countCapturingGroups(source: String): Int {
            var count = 0
            var index = 0
            var inClass = false
            while (index < source.length) {
                val c = source[index]
                when {
                    c == '\\' -> index++
                    inClass -> if (c == ']') inClass = false
                    c == '[' -> inClass = true
                    c == '(' -> {
                        val named = index + 2 < source.length && source[index + 1] == '?' &&
                            source[index + 2] == '<'
                        if (index + 1 >= source.length || source[index + 1] != '?' || named) {
                            count++
                        }
                    }
                }
                index++
            }
            return count
        }
        private val namedGroups = LinkedHashMap<String, MutableList<Int>>()
            .apply {
                for ((name, indices) in initialNameCandidates) {
                    put(name, indices.toMutableList())
                }
            }

        fun namedGroupsSnapshot(): Map<String, List<Int>> =
            namedGroups.mapValues { it.value.toList() }

        fun parse(): Node {
            val node = parseAlternation()
            if (position < pattern.length) {
                throw IllegalArgumentException("Unexpected ')' at index $position")
            }
            return node
        }

        private fun parseAlternation(): Node {
            val branches = mutableListOf<Node>()
            branches.add(parseSequence())
            while (matches('|')) {
                branches.add(parseSequence())
            }
            return if (branches.size == 1) branches[0] else Node.AlternationNode(branches)
        }

        private fun parseSequence(): Node {
            val nodes = mutableListOf<Node>()
            while (position < pattern.length && peek() != '|' && peek() != ')') {
                nodes.add(parseQuantified())
            }
            return Node.SequenceNode(nodes)
        }

        private fun parseQuantified(): Node {
            val atom = parseAtom()
            if (position >= pattern.length) return atom
            var min: Int
            var max: Int
            when (peek()) {
                '*' -> {
                    min = 0
                    max = Int.MAX_VALUE
                    position++
                }

                '+' -> {
                    min = 1
                    max = Int.MAX_VALUE
                    position++
                }

                '?' -> {
                    min = 0
                    max = 1
                    position++
                }

                '{' -> {
                    val saved = position
                    val range = parseBraceQuantifier()
                    if (range == null) {
                        position = saved
                        return atom
                    }
                    min = range.first
                    max = range.second
                }

                else -> return atom
            }
            var greedy = true
            if (position < pattern.length && peek() == '?') {
                greedy = false
                position++
            }
            return Node.QuantifierNode(atom, min, max, greedy)
        }

        private fun parseBraceQuantifier(): Pair<Int, Int>? {
            position++ // '{'
            val min = parseNumber() ?: return null
            var max = min
            if (position < pattern.length && peek() == ',') {
                position++
                max = if (position < pattern.length && peek() == '}') Int.MAX_VALUE else (parseNumber() ?: return null)
            }
            if (position >= pattern.length || peek() != '}') return null
            position++
            return min to max
        }

        private fun parseNumber(): Int? {
            val start = position
            while (position < pattern.length && peek() in '0'..'9') position++
            if (position == start) return null
            val text = pattern.substring(start, position)
            // 规范允许任意大的十进制数；钳制到 Int.MAX_VALUE 即可（超过输入长度必然不匹配）
            return text.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                ?: Int.MAX_VALUE
        }

        private fun parseAtom(): Node {
            if (position >= pattern.length) return Node.SequenceNode(emptyList())
            return when (val character = peek()) {
                '.' -> {
                    position++
                    Node.AnyNode
                }

                '^' -> {
                    position++
                    Node.AnchorNode(Node.AnchorKind.START)
                }

                '$' -> {
                    position++
                    Node.AnchorNode(Node.AnchorKind.END)
                }

                '[' -> parseCharacterClass()
                '(' -> parseGroup()
                '\\' -> parseEscape()
                else -> {
                    position++
                    Node.CharNode(character)
                }
            }
        }

        private fun parseGroup(): Node {
            position++ // '('
            var capturing = true
            var lookahead: Boolean? = null
            var lookbehind: Boolean? = null
            var groupName: String? = null
            var modifierAdd = 0
            var modifierRemove = 0
            if (position < pattern.length && peek() == '?') {
                position++
                when {
                    position < pattern.length && peek() == ':' -> {
                        capturing = false
                        position++
                    }

                    position < pattern.length && (peek() in "ims-") -> {
                        // 作用域修饰符：(?ims:...) / (?-i:...) / (?im-s:...)
                        capturing = false
                        var inRemove = false
                        while (position < pattern.length && peek() != ':') {
                            val c = peek()
                            if (c == '-') {
                                if (inRemove) {
                                    throw IllegalArgumentException("Duplicate '-' in modifiers")
                                }
                                inRemove = true
                                position++
                                continue
                            }
                            val bit = when (c) {
                                'i' -> FLAG_IGNORE_CASE
                                'm' -> FLAG_MULTILINE
                                's' -> FLAG_DOT_ALL
                                else -> throw IllegalArgumentException("Invalid modifier '$c'")
                            }
                            if (inRemove) {
                                if (modifierRemove and bit != 0) {
                                    throw IllegalArgumentException("Duplicate modifier '$c'")
                                }
                                modifierRemove = modifierRemove or bit
                            } else {
                                if (modifierAdd and bit != 0) {
                                    throw IllegalArgumentException("Duplicate modifier '$c'")
                                }
                                modifierAdd = modifierAdd or bit
                            }
                            position++
                        }
                        if (position >= pattern.length || peek() != ':') {
                            throw IllegalArgumentException("Unterminated modifiers")
                        }
                        if (modifierAdd == 0 && modifierRemove == 0) {
                            throw IllegalArgumentException("Empty modifiers")
                        }
                        if (modifierAdd and modifierRemove != 0) {
                            throw IllegalArgumentException("Modifier both added and removed")
                        }
                        position++ // ':'
                    }

                    position < pattern.length && peek() == '=' -> {
                        lookahead = true
                        position++
                    }

                    position < pattern.length && peek() == '!' -> {
                        lookahead = false
                        position++
                    }

                    position < pattern.length && peek() == '<' -> {
                        position++
                        if (position < pattern.length && (peek() == '=' || peek() == '!')) {
                            lookbehind = peek() == '='
                            position++
                        } else {
                            val nameStart = position
                            while (position < pattern.length && peek() != '>') position++
                            if (position >= pattern.length) {
                                throw IllegalArgumentException("Unterminated named group")
                            }
                            groupName = pattern.substring(nameStart, position)
                            position++
                        }
                    }

                    else -> throw IllegalArgumentException("Unsupported group extension at index $position")
                }
            }
            val index = if (capturing && lookahead == null && lookbehind == null) ++groupCount else null
            if (index != null && groupName != null) {
                val list = namedGroups.getOrPut(groupName) { mutableListOf() }
                if (index !in list) list.add(index)
            }
            val child = parseAlternation()
            if (position >= pattern.length || peek() != ')') {
                throw IllegalArgumentException("Unterminated group")
            }
            position++
            if (lookbehind != null) {
                return Node.LookbehindNode(lookbehind, child)
            }
            return if (lookahead != null) {
                Node.GroupNode(
                    index = null,
                    child = Node.SequenceNode(
                        listOf(
                            Node.AlternationNode(listOf(child)),
                        )
                    ),
                ).let {
                    // lookahead 节点需要额外标记，使用专用节点
                    Node.LookaheadNode(child, lookahead)
                }
            } else if (modifierAdd != 0 || modifierRemove != 0) {
                Node.FlagGroupNode(modifierAdd, modifierRemove, child)
            } else {
                Node.GroupNode(index, child)
            }
        }

        private fun parseCharacterClass(): Node {
            if (unicodeSets) {
                val value = parseClassSet()
                val items = if (value.codePoints.isEmpty) {
                    emptyList()
                } else {
                    listOf(ClassItem.Ranges(value.codePoints))
                }
                return Node.ClassNode(false, items, value.strings)
            }
            position++ // '['
            var negated = false
            if (position < pattern.length && peek() == '^') {
                negated = true
                position++
            }
            val items = mutableListOf<ClassItem>()
            if (position < pattern.length && peek() == ']') {
                // [] 匹配空集合，[^] 匹配任意字符
                position++
                return Node.ClassNode(negated, items)
            }
            var first = true
            while (position < pattern.length && (peek() != ']' || first)) {
                first = false
                val atom = parseClassAtom()
                if (position < pattern.length && peek() == '-' &&
                    position + 1 < pattern.length && pattern[position + 1] != ']'
                ) {
                    position++
                    val endAtom = parseClassAtom()
                    if (atom is ClassItem.Single && endAtom is ClassItem.Single) {
                        // JS 规范：范围起点大于终点是 SyntaxError
                        if (atom.codePoint > endAtom.codePoint) {
                            throw IllegalArgumentException("Range out of order in character class")
                        }
                        items.add(ClassItem.Range(atom.codePoint, endAtom.codePoint))
                    } else if (unicode) {
                        // 规范：u/v 模式下范围任一端点是字符类转义（\d、\p{...} 等）是语法错误
                        throw IllegalArgumentException("Invalid character class range")
                    } else {
                        // Annex B：非 u 模式把 '-' 视为字面量（如 [\s-_]）
                        items.add(atom)
                        items.add(ClassItem.Single('-'.code))
                        items.add(endAtom)
                    }
                } else {
                    items.add(atom)
                }
            }
            if (position >= pattern.length) {
                throw IllegalArgumentException("Unterminated character class")
            }
            position++ // ']'
            return Node.ClassNode(negated, items)
        }

        /** 解析 v 模式扩展字符类，返回求值结果；position 指向 '['。 */
        private fun parseClassSet(): ClassSetValue {
            position++ // '['
            var negated = false
            if (position < pattern.length && peek() == '^') {
                negated = true
                position++
            }
            if (position >= pattern.length) {
                throw IllegalArgumentException("Unterminated class set")
            }
            if (peek() == ']') {
                throw IllegalArgumentException("Empty class set")
            }
            var result = parseClassSetExpression()
            if (position >= pattern.length || peek() != ']') {
                throw IllegalArgumentException("Unterminated class set")
            }
            position++ // ']'
            if (negated) result = result.complement()
            return result
        }

        /** ClassSetExpression：并集，或单个 && / -- 运算（不能与并集混用）。 */
        private fun parseClassSetExpression(): ClassSetValue {
            val leftTerm = parseClassSetTerm()
            var left = leftTerm.value
            when {
                startsWith("&&") || startsWith("--") -> {
                    if (leftTerm.isRange) {
                        throw IllegalArgumentException("Invalid range in class set operation")
                    }
                    val intersection = startsWith("&&")
                    position += 2
                    val rightTerm = parseClassSetTerm()
                    if (rightTerm.isRange) {
                        throw IllegalArgumentException("Invalid range in class set operation")
                    }
                    left = if (intersection) {
                        left.intersect(rightTerm.value)
                    } else {
                        left.subtract(rightTerm.value)
                    }
                    if (startsWith("&&") || startsWith("--")) {
                        throw IllegalArgumentException("Invalid class set operation chain")
                    }
                }

                else -> {
                    while (position < pattern.length && peek() != ']' &&
                        !startsWith("&&") && !startsWith("--")
                    ) {
                        left = left.union(parseClassSetTerm().value)
                    }
                    if (startsWith("&&") || startsWith("--")) {
                        throw IllegalArgumentException("Class set operation cannot follow union")
                    }
                }
            }
            return left
        }

        /** 解析一个操作数，并在单个字符时按 `-` 合并范围。 */
        private fun parseClassSetTerm(): ClassSetTerm {
            val first = parseClassSetOperand()
            if (first is SetOperand.CharAtom && position < pattern.length && peek() == '-' &&
                !startsWith("--")
            ) {
                // v 模式不允许末尾/孤立 '-'，必须转义为 \-
                if (position + 1 >= pattern.length || pattern[position + 1] == ']') {
                    throw IllegalArgumentException("Invalid '-' in class set")
                }
                position++
                val second = parseClassSetOperand()
                if (second !is SetOperand.CharAtom) {
                    throw IllegalArgumentException("Invalid class set range endpoint")
                }
                if (first.value > second.value) {
                    throw IllegalArgumentException("Range out of order in character class")
                }
                return ClassSetTerm(
                    ClassSetValue.ofCodePoints(CodePointSet.range(first.value, second.value)),
                    isRange = true,
                )
            }
            return when (first) {
                is SetOperand.CharAtom -> ClassSetTerm(
                    ClassSetValue.ofCodePoints(CodePointSet.singleton(first.value)),
                    isRange = false,
                )
                is SetOperand.Value -> ClassSetTerm(first.value, isRange = false)
            }
        }

        private fun parseClassSetOperand(): SetOperand {
            if (position >= pattern.length) {
                throw IllegalArgumentException("Unterminated class set")
            }
            return when (peek()) {
                '[' -> SetOperand.Value(parseClassSet())
                '\\' -> parseClassSetEscape()
                ']' -> throw IllegalArgumentException("Unexpected ']' in class set")
                '-' -> throw IllegalArgumentException("Invalid '-' in class set")
                '&' -> {
                    if (startsWith("&&")) {
                        throw IllegalArgumentException("Invalid '&&' in class set")
                    }
                    SetOperand.CharAtom(parseClassSetCharacterAtom())
                }

                else -> SetOperand.CharAtom(parseClassSetCharacterAtom())
            }
        }

        /** position 指向反斜杠后的转义字符；返回对应码点并推进。 */
        private fun readEscapedCharAtom(): Int {
            if (position >= pattern.length) {
                throw IllegalArgumentException("Trailing backslash")
            }
            val escaped = peek()
            return when (escaped) {
                'd', 'D', 'w', 'W', 's', 'S', 'p', 'P', 'q' ->
                    throw IllegalArgumentException("Invalid class set range endpoint")

                'b' -> {
                    position++
                    0x08
                }

                'n' -> {
                    position++
                    0x0A
                }

                'r' -> {
                    position++
                    0x0D
                }

                't' -> {
                    position++
                    0x09
                }

                'f' -> {
                    position++
                    0x0C
                }

                'v' -> {
                    position++
                    0x0B
                }

                '0' -> {
                    position++
                    0
                }

                'x' -> {
                    position++
                    readHex(2)
                }

                'u' -> {
                    position++
                    val braceForm = position < pattern.length && peek() == '{'
                    val value = readUnicode()
                    if (braceForm) value else (readLowSurrogatePair(value) ?: value)
                }

                'c' -> {
                    position++
                    val control = peek()
                    if (control !in 'a'..'z' && control !in 'A'..'Z') {
                        throw IllegalArgumentException("Invalid control escape")
                    }
                    position++
                    control.code and 0x1F
                }

                else -> {
                    position++
                    escaped.code
                }
            }
        }

        /** 单个字符原子（可作范围端点），返回码点。 */
        private fun parseClassSetCharacterAtom(): Int {
            if (position >= pattern.length) {
                throw IllegalArgumentException("Unterminated class set")
            }
            val character = peek()
            if (character == '\\') {
                position++
                return readEscapedCharAtom()
            }
            if (character == ']') {
                throw IllegalArgumentException("Unterminated class set")
            }
            if (character in "()[{}/|") {
                throw IllegalArgumentException("Invalid unescaped character in class set")
            }
            for (reserved in CLASS_SET_RESERVED_DOUBLES) {
                if (startsWith(reserved)) {
                    throw IllegalArgumentException("Invalid reserved double punctuator in class set")
                }
            }
            if (unicode && character.isHighSurrogate() && position + 1 < pattern.length &&
                pattern[position + 1].isLowSurrogate()
            ) {
                position += 2
                return 0x10000 + ((character.code - 0xD800) shl 10) +
                    (pattern[position - 1].code - 0xDC00)
            }
            position++
            return character.code
        }

        /** 解析字符类内的转义操作数（集合转义、属性转义或单字符）。 */
        private fun parseClassSetEscape(): SetOperand {
            position++ // '\'
            if (position >= pattern.length) {
                throw IllegalArgumentException("Trailing backslash")
            }
            val escaped = peek()
            return when (escaped) {
                'd' -> {
                    position++
                    SetOperand.Value(
                        ClassSetValue.ofCodePoints(CodePointSet.range(0x30, 0x39))
                    )
                }

                'D' -> {
                    position++
                    SetOperand.Value(
                        ClassSetValue.ofCodePoints(
                            CodePointSet.range(0x30, 0x39).complement()
                        )
                    )
                }

                'w' -> {
                    position++
                    SetOperand.Value(ClassSetValue.ofCodePoints(WORD_SET))
                }

                'W' -> {
                    position++
                    SetOperand.Value(ClassSetValue.ofCodePoints(WORD_SET.complement()))
                }

                's' -> {
                    position++
                    SetOperand.Value(ClassSetValue.ofCodePoints(SPACE_SET))
                }

                'S' -> {
                    position++
                    SetOperand.Value(ClassSetValue.ofCodePoints(SPACE_SET.complement()))
                }

                'p' -> {
                    position++
                    SetOperand.Value(readPropertySet(negated = false))
                }

                'P' -> {
                    position++
                    SetOperand.Value(readPropertySet(negated = true))
                }

                'q' -> {
                    position++
                    SetOperand.Value(readClassStringDisjunction())
                }

                else -> SetOperand.CharAtom(readEscapedCharAtom())
            }
        }

        /** 字符类之外的属性转义（v 模式可匹配属性字符串）。 */
        private fun propertyNode(negated: Boolean): Node {
            val value = readPropertySet(negated)
            val items = if (value.codePoints.isEmpty) {
                emptyList()
            } else {
                listOf(ClassItem.Ranges(value.codePoints))
            }
            return Node.ClassNode(false, items, value.strings)
        }

        /** 读取 `\p{...}` / `\P{...}`：码点属性或属性字符串；position 指向 '{'。 */
        private fun readPropertySet(negated: Boolean): ClassSetValue {
            if (position >= pattern.length || peek() != '{') {
                throw IllegalArgumentException("Invalid property escape")
            }
            val close = pattern.indexOf('}', position + 1)
            if (close < 0) {
                throw IllegalArgumentException("Unterminated property escape")
            }
            val expression = pattern.substring(position + 1, close)
            position = close + 1
            val property = UnicodePropertyData.resolve(expression)
            if (property != null) {
                var set = CodePointSet.of(property.rangeData())
                if (negated) set = set.complement()
                return ClassSetValue.ofCodePoints(set)
            }
            if (negated) {
                throw IllegalArgumentException("Invalid property name or value: $expression")
            }
            val strings = UnicodeStringPropertyData.resolve(expression)
                ?: throw IllegalArgumentException("Invalid property name or value: $expression")
            return ClassSetValue.ofStrings(strings)
        }

        /** 解析 `\q{...}` 字符串析取；position 指向 '{'。 */
        private fun readClassStringDisjunction(): ClassSetValue {
            if (position >= pattern.length || peek() != '{') {
                throw IllegalArgumentException("Invalid \\q")
            }
            position++ // '{'
            val strings = mutableListOf<String>()
            var builder = StringBuilder()
            while (true) {
                if (position >= pattern.length) {
                    throw IllegalArgumentException("Unterminated \\q")
                }
                when (peek()) {
                    '}' -> {
                        strings.add(builder.toString())
                        position++
                        break
                    }

                    '|' -> {
                        strings.add(builder.toString())
                        builder = StringBuilder()
                        position++
                    }

                    else -> appendCodePoint(builder, parseClassStringCharacter())
                }
            }
            return ClassSetValue.ofStrings(strings)
        }

        private fun parseClassStringCharacter(): Int {
            if (position >= pattern.length) {
                throw IllegalArgumentException("Unterminated \\q")
            }
            val character = peek()
            if (character == '\\') {
                position++
                return readEscapedCharAtom()
            }
            if (character == ']' || character == '|' || character == '}' ||
                character in "()[{}/-"
            ) {
                throw IllegalArgumentException("Invalid character in \\q")
            }
            for (reserved in CLASS_SET_RESERVED_DOUBLES) {
                if (startsWith(reserved)) {
                    throw IllegalArgumentException("Invalid reserved double punctuator in \\q")
                }
            }
            if (unicode && character.isHighSurrogate() && position + 1 < pattern.length &&
                pattern[position + 1].isLowSurrogate()
            ) {
                position += 2
                return 0x10000 + ((character.code - 0xD800) shl 10) +
                    (pattern[position - 1].code - 0xDC00)
            }
            position++
            return character.code
        }

        private fun appendCodePoint(builder: StringBuilder, codePoint: Int) {
            if (codePoint <= 0xFFFF) {
                builder.append(codePoint.toChar())
            } else {
                val value = codePoint - 0x10000
                builder.append(((value shr 10) + 0xD800).toChar())
                builder.append(((value and 0x3FF) + 0xDC00).toChar())
            }
        }

        private fun startsWith(text: String): Boolean {
            if (position + text.length > pattern.length) return false
            for (offset in text.indices) {
                if (pattern[position + offset] != text[offset]) return false
            }
            return true
        }

        private fun parseClassAtom(): ClassItem {
            if (peek() == '\\') {
                position++
                val character = peek()
                position++
                return when (character) {
                    'd' -> digitPredicate(negated = false)
                    'D' -> digitPredicate(negated = true)
                    'w' -> wordPredicate(negated = false)
                    'W' -> wordPredicate(negated = true)
                    's' -> spacePredicate(negated = false)
                    'S' -> spacePredicate(negated = true)
                    'p' -> readPropertyEscape(negated = false)
                    'P' -> readPropertyEscape(negated = true)
                    'b' -> ClassItem.Single('\b'.code)
                    'n' -> ClassItem.Single('\n'.code)
                    'r' -> ClassItem.Single('\r'.code)
                    't' -> ClassItem.Single('\t'.code)
                    'f' -> ClassItem.Single('\u000C'.code)
                    'v' -> ClassItem.Single('\u000B'.code)
                    '0' -> ClassItem.Single('\u0000'.code)
                    'x' -> if (!unicode &&
                        !(isHexDigitAt(position) && isHexDigitAt(position + 1))
                    ) {
                        // Annex B：非 u 模式非法 \x 按 IdentityEscape 'x'
                        ClassItem.Single('x'.code)
                    } else {
                        ClassItem.Single(readHex(2))
                    }

                    // u 模式下 \u{...} 产生完整码点；非 u 模式保持原有码元语义
                    'u' -> {
                        if (!unicode && !hasUnicodeEscapeDigits()) {
                            // Annex B：非 u 模式非法 \u 按 IdentityEscape 'u'
                            ClassItem.Single('u'.code)
                        } else if (!unicode) {
                            ClassItem.Single(readUnicode().toChar().code)
                        } else {
                            val braceForm = peek() == '{'
                            val value = readUnicode()
                            val combined = if (braceForm) null else readLowSurrogatePair(value)
                            ClassItem.Single(combined ?: value)
                        }
                    }

                    'c' -> {
                        val control = peek()
                        val validControl = control in 'a'..'z' || control in 'A'..'Z' ||
                            (!unicode && (control in '0'..'9' || control == '_'))
                        if (validControl) {
                            position++
                            ClassItem.Single(control.code and 0x1F)
                        } else if (unicode) {
                            throw IllegalArgumentException("Invalid control escape")
                        } else {
                            ClassItem.Single('c'.code)
                        }
                    }

                    else -> ClassItem.Single(character.code)
                }
            }
            val character = peek()
            if (unicode && character.isHighSurrogate() && position + 1 < pattern.length &&
                pattern[position + 1].isLowSurrogate()
            ) {
                position += 2
                val codePoint = 0x10000 + ((character.code - 0xD800) shl 10) +
                    (pattern[position - 1].code - 0xDC00)
                return ClassItem.Single(codePoint)
            }
            position++
            return ClassItem.Single(character.code)
        }

        private fun digitPredicate(negated: Boolean): ClassItem = ClassItem.Predicate("digit") { codePoint, _ ->
            (codePoint in 0x30..0x39) != negated
        }

        private fun wordPredicate(negated: Boolean): ClassItem =
            ClassItem.Predicate("word") { codePoint, ignoreCase ->
                (codePoint <= 0xFFFF && isWord(codePoint.toChar(), ignoreCase)) != negated
            }

        private fun spacePredicate(negated: Boolean): ClassItem = ClassItem.Predicate("space") { codePoint, _ ->
            SPACE_SET.contains(codePoint) != negated
        }

        /** 读取 `\p{...}` / `\P{...}`；position 指向 '{'。 */
        private fun readPropertyEscape(negated: Boolean): ClassItem {
            if (!unicode || position >= pattern.length || peek() != '{') {
                throw IllegalArgumentException("Unicode property escape requires the u flag")
            }
            position++ // '{'
            val start = position
            while (position < pattern.length && peek() != '}') position++
            if (position >= pattern.length) {
                throw IllegalArgumentException("Unterminated property escape")
            }
            val expression = pattern.substring(start, position)
            position++ // '}'
            val set = UnicodePropertyData.resolve(expression)
                ?: throw IllegalArgumentException("Invalid property name or value: $expression")
            return ClassItem.Property(set, negated)
        }

        private fun isHexDigitAt(index: Int): Boolean {
            if (index !in pattern.indices) return false
            val c = pattern[index]
            return c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
        }

        /** 非 u 模式 \u 后是否紧跟 4 位十六进制（{...} 码点形式仅 u 模式有效）。 */
        private fun hasUnicodeEscapeDigits(): Boolean {
            if (position < pattern.length && peek() == '{') return false
            for (offset in 0 until 4) {
                if (!isHexDigitAt(position + offset)) return false
            }
            return true
        }

        private fun parseEscape(): Node {
            position++ // '\'
            if (position >= pattern.length) {
                throw IllegalArgumentException("Trailing backslash")
            }
            val character = peek()
            position++
            return when (character) {
                'd' -> Node.ClassNode(false, listOf(digitPredicate(false)))
                'D' -> Node.ClassNode(false, listOf(digitPredicate(true)))
                'w' -> Node.ClassNode(false, listOf(wordPredicate(false)))
                'W' -> Node.ClassNode(false, listOf(wordPredicate(true)))
                's' -> Node.ClassNode(false, listOf(spacePredicate(false)))
                'S' -> Node.ClassNode(false, listOf(spacePredicate(true)))
                'p' -> if (unicodeSets) {
                    propertyNode(negated = false)
                } else {
                    Node.ClassNode(false, listOf(readPropertyEscape(negated = false)))
                }

                'P' -> if (unicodeSets) {
                    propertyNode(negated = true)
                } else {
                    Node.ClassNode(false, listOf(readPropertyEscape(negated = true)))
                }
                'q' -> if (unicodeSets) {
                    throw IllegalArgumentException("\\q is only valid inside a class")
                } else {
                    Node.CharNode('q')
                }
                'b' -> Node.AnchorNode(Node.AnchorKind.WORD_BOUNDARY, positive = true)
                'B' -> Node.AnchorNode(Node.AnchorKind.WORD_BOUNDARY, positive = false)
                'n' -> Node.CharNode('\n')
                'r' -> Node.CharNode('\r')
                't' -> Node.CharNode('\t')
                'f' -> Node.CharNode('\u000C')
                'v' -> Node.CharNode('\u000B')
                '0' -> if (!unicode && position < pattern.length &&
                    peek() in '0'..'7'
                ) {
                    // Annex B：\0 后跟八进制数字构成 LegacyOctalEscapeSequence（最多 3 位）
                    var value = 0
                    var count = 0
                    while (count < 2 && position < pattern.length && peek() in '0'..'7') {
                        value = value * 8 + (peek() - '0')
                        position++
                        count++
                    }
                    Node.CharNode(value.toChar())
                } else {
                    Node.CharNode('\u0000')
                }
                'x' -> {
                    // Annex B：非 u 模式下 \x 后不足两位十六进制时按 IdentityEscape 'x'
                    if (!unicode &&
                        !(isHexDigitAt(position) && isHexDigitAt(position + 1))
                    ) {
                        Node.CharNode('x')
                    } else {
                        Node.CharNode(readHex(2).toChar())
                    }
                }

                'u' -> {
                    // Annex B：非 u 模式下非法 \u 序列按 IdentityEscape 'u'
                    if (!unicode && !hasUnicodeEscapeDigits()) {
                        Node.CharNode('u')
                    } else {
                        val braceForm = peek() == '{'
                        val codePoint = readUnicode()
                        val combined = if (braceForm) null else readLowSurrogatePair(codePoint)
                        if (combined != null) {
                            // u 模式下相邻代理项转义表示一个完整码点，量词作用于整个码点
                            Node.ClassNode(false, listOf(ClassItem.Single(combined)))
                        } else if (codePoint <= 0xFFFF) {
                            Node.CharNode(codePoint.toChar())
                        } else {
                            val value = codePoint - 0x10000
                            Node.SequenceNode(
                                listOf(
                                    Node.CharNode(((value shr 10) + 0xD800).toChar()),
                                    Node.CharNode(((value and 0x3FF) + 0xDC00).toChar()),
                                )
                            )
                        }
                    }
                }

                'c' -> {
                    val control = peek()
                    if (unicode) {
                        if (control !in 'a'..'z' && control !in 'A'..'Z') {
                            throw IllegalArgumentException("Invalid control escape")
                        }
                        position++
                        Node.CharNode((control.code and 0x1F).toChar())
                    } else if (control in 'a'..'z' || control in 'A'..'Z') {
                        position++
                        Node.CharNode((control.code and 0x1F).toChar())
                    } else {
                        // Annex B：非字母控制转义按 IdentityEscape 'c' 处理
                        Node.CharNode('c')
                    }
                }

                in '1'..'9' -> {
                    var index = character - '0'
                    while (position < pattern.length && peek() in '0'..'9' &&
                        index * 10 + (peek() - '0') <= totalGroupCount
                    ) {
                        index = index * 10 + (peek() - '0')
                        position++
                    }
                    if (index <= totalGroupCount) {
                        Node.BackReferenceNode(listOf(index))
                    } else if (unicode) {
                        // u 模式：无效反向引用由解析期校验拦截
                        Node.BackReferenceNode(listOf(index))
                    } else {
                        // Annex B：无对应捕获组的数字转义按 LegacyOctalEscapeSequence 解释
                        val first = character
                        val maxDigits = if (first in '0'..'3') 3 else 2
                        var value = first - '0'
                        var count = 1
                        while (count < maxDigits && position < pattern.length &&
                            peek() in '0'..'7'
                        ) {
                            value = value * 8 + (peek() - '0')
                            position++
                            count++
                        }
                        Node.CharNode(value.toChar())
                    }
                }

                'k' -> {
                    if (position < pattern.length && peek() == '<') {
                        position++
                        val nameStart = position
                        while (position < pattern.length && peek() != '>') position++
                        if (position >= pattern.length) {
                            throw IllegalArgumentException("Unterminated named backreference")
                        }
                        val name = pattern.substring(nameStart, position)
                        position++
                        val indices = namedGroups[name]
                            ?: throw IllegalArgumentException("Unknown group name '$name'")
                        Node.BackReferenceNode(indices.toList())
                    } else {
                        Node.CharNode('k')
                    }
                }

                else -> Node.CharNode(character)
            }
        }

        /** 高代理项转义后紧跟 `\uXXXX` 低代理项时，合并为一个码点（仅 u 模式）。 */
        private fun readLowSurrogatePair(high: Int): Int? {
            if (!unicode || high !in 0xD800..0xDBFF) return null
            if (position + 1 >= pattern.length || pattern[position] != '\\' ||
                pattern[position + 1] != 'u'
            ) {
                return null
            }
            if (position + 2 < pattern.length && pattern[position + 2] == '{') return null
            val saved = position
            position += 2
            val low = try {
                readHex(4)
            } catch (_: IllegalArgumentException) {
                position = saved
                return null
            }
            if (low !in 0xDC00..0xDFFF) {
                position = saved
                return null
            }
            return 0x10000 + ((high - 0xD800) shl 10) + (low - 0xDC00)
        }

        private fun readHex(length: Int): Int {
            if (position + length > pattern.length) {
                throw IllegalArgumentException("Invalid hex escape")
            }
            val text = pattern.substring(position, position + length)
            position += length
            return text.toIntOrNull(16) ?: throw IllegalArgumentException("Invalid hex escape")
        }

        private fun readUnicode(): Int {
            if (position < pattern.length && peek() == '{') {
                position++
                val start = position
                while (position < pattern.length && peek() != '}') position++
                if (position >= pattern.length) throw IllegalArgumentException("Invalid unicode escape")
                val text = pattern.substring(start, position)
                position++
                return text.toIntOrNull(16) ?: throw IllegalArgumentException("Invalid unicode escape")
            }
            return readHex(4)
        }

        private fun peek(): Char = pattern[position]

        private fun matches(character: Char): Boolean {
            if (position < pattern.length && pattern[position] == character) {
                position++
                return true
            }
            return false
        }
    }
}