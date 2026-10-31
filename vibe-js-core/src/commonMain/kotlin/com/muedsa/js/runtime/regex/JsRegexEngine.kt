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
) : JsRegex {

    private val ignoreCase = 'i' in flags
    private val multiline = 'm' in flags
    private val dotAll = 's' in flags
    private val unicode = 'u' in flags

    private val root: Node
    private var groupCount: Int = 0

    init {
        val parser = PatternParser(pattern)
        root = parser.parse()
        groupCount = parser.groupCount
    }

    override fun find(input: String, startIndex: Int): JsMatch? {
        if (startIndex < 0 || startIndex > input.length) return null
        inputForMatch = input
        for (index in startIndex..input.length) {
            var resultCaps: Array<IntRange?>? = null
            val matched = matchNode(root, index, arrayOfNulls(groupCount + 1)) { end, captures ->
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
                return JsMatch(index, end, input.substring(index, end), groups, ranges)
            }
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

    private fun matchNode(
        node: Node,
        pos: Int,
        captures: Array<IntRange?>,
        continuation: (Int, Array<IntRange?>) -> Boolean,
    ): Boolean {
        return when (node) {
            is Node.CharNode -> {
                if (pos >= inputForMatch.length) false
                else if (!charEquals(inputForMatch[pos], node.character)) false
                else continuation(pos + 1, captures)
            }

            is Node.AnyNode -> {
                if (pos >= inputForMatch.length) false
                else if (!dotAll && isLineTerminator(inputForMatch[pos])) false
                else continuation(pos + 1, captures)
            }

            is Node.ClassNode -> {
                if (pos >= inputForMatch.length) false
                else {
                    val matched = node.items.any { it.matches(inputForMatch[pos], ignoreCase) }
                    if (matched != node.negated) continuation(pos + 1, captures) else false
                }
            }

            is Node.SequenceNode -> matchSequence(node.nodes, 0, pos, captures, continuation)

            is Node.AlternationNode -> {
                for (branch in node.branches) {
                    if (matchNode(branch, pos, captures.copyOf(), continuation)) return true
                }
                false
            }

            is Node.GroupNode -> {
                matchNode(node.child, pos, captures) { end, innerCaptures ->
                    val updated = innerCaptures.copyOf()
                    if (node.index != null) {
                        updated[node.index] = pos until end
                    }
                    continuation(end, updated)
                }
            }

            is Node.QuantifierNode -> matchQuantifier(node, 0, pos, captures, continuation)

            is Node.LookaheadNode -> {
                var lookedCaptures: Array<IntRange?>? = null
                val matched = matchNode(node.child, pos, captures.copyOf()) { _, inner ->
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
                    if (pos == 0 || (multiline && pos > 0 && isLineTerminator(inputForMatch[pos - 1]))) {
                        continuation(pos, captures)
                    } else {
                        false
                    }

                Node.AnchorKind.END ->
                    if (pos == inputForMatch.length ||
                        (multiline && pos < inputForMatch.length && isLineTerminator(inputForMatch[pos]))
                    ) {
                        continuation(pos, captures)
                    } else {
                        false
                    }

                Node.AnchorKind.WORD_BOUNDARY -> {
                    val previousIsWord = pos > 0 && isWord(inputForMatch[pos - 1])
                    val currentIsWord = pos < inputForMatch.length && isWord(inputForMatch[pos])
                    val boundary = previousIsWord != currentIsWord
                    if (boundary == node.positive) continuation(pos, captures) else false
                }
            }

            is Node.BackReferenceNode -> {
                val range = captures.getOrNull(node.index)
                if (range == null) {
                    continuation(pos, captures)
                } else {
                    val expected = inputForMatch.substring(range.first, range.last + 1)
                    if (matchesAt(pos, expected)) continuation(pos + expected.length, captures) else false
                }
            }
        }
    }

    private fun matchSequence(
        nodes: List<Node>,
        index: Int,
        pos: Int,
        captures: Array<IntRange?>,
        continuation: (Int, Array<IntRange?>) -> Boolean,
    ): Boolean {
        if (index >= nodes.size) return continuation(pos, captures)
        return matchNode(nodes[index], pos, captures) { nextPos, nextCaptures ->
            matchSequence(nodes, index + 1, nextPos, nextCaptures, continuation)
        }
    }

    private fun matchQuantifier(
        node: Node.QuantifierNode,
        count: Int,
        pos: Int,
        captures: Array<IntRange?>,
        continuation: (Int, Array<IntRange?>) -> Boolean,
    ): Boolean {
        // 规范：量词每次迭代前清除其内部捕获组的值
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
            if (nextPos == pos) return false
            return matchQuantifier(node, count + 1, nextPos, nextCaptures, continuation)
        }

        if (count < node.min) {
            return matchNode(node.child, pos, iterationCaptures(captures)) { nextPos, nextCaptures ->
                repeat(nextPos, nextCaptures)
            }
        }

        if (node.greedy) {
            if (count < node.max) {
                val matched = matchNode(node.child, pos, iterationCaptures(captures)) { nextPos, nextCaptures ->
                    repeat(nextPos, nextCaptures)
                }
                if (matched) return true
            }
            return continuation(pos, captures)
        }

        if (continuation(pos, captures)) return true
        if (count < node.max) {
            return matchNode(node.child, pos, iterationCaptures(captures)) { nextPos, nextCaptures ->
                repeat(nextPos, nextCaptures)
            }
        }
        return false
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
            else -> Unit
        }
    }

    private fun matchesAt(pos: Int, expected: String): Boolean {
        if (pos + expected.length > inputForMatch.length) return false
        for (index in expected.indices) {
            if (!charEquals(inputForMatch[pos + index], expected[index])) return false
        }
        return true
    }

    private fun charEquals(left: Char, right: Char): Boolean =
        left == right || (ignoreCase && left.equals(right, ignoreCase = true))

    private fun isWord(character: Char): Boolean =
        character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_'

    private fun isLineTerminator(character: Char): Boolean =
        character == '\n' || character == '\r' || character == '\u2028' || character == '\u2029'

    // find 期间保存输入，避免给每个节点传输入参数
    private var inputForMatch: String = ""

    // ================================================================
    // 模式解析
    // ================================================================

    private sealed class Node {
        data class CharNode(val character: Char) : Node()
        object AnyNode : Node()
        data class ClassNode(val negated: Boolean, val items: List<ClassItem>) : Node()
        data class SequenceNode(val nodes: List<Node>) : Node()
        data class AlternationNode(val branches: List<Node>) : Node()
        data class GroupNode(val index: Int?, val child: Node) : Node()
        data class QuantifierNode(
            val child: Node,
            val min: Int,
            val max: Int,
            val greedy: Boolean,
        ) : Node()

        data class BackReferenceNode(val index: Int) : Node()
        data class LookaheadNode(val child: Node, val positive: Boolean) : Node()
        data class AnchorNode(val kind: AnchorKind, val positive: Boolean = true) : Node()

        enum class AnchorKind {
            START,
            END,
            WORD_BOUNDARY,
        }
    }

    private sealed class ClassItem {
        abstract fun matches(character: Char, ignoreCase: Boolean): Boolean

        data class Single(val character: Char) : ClassItem() {
            override fun matches(character: Char, ignoreCase: Boolean): Boolean =
                character == this.character ||
                    (ignoreCase && character.equals(this.character, ignoreCase = true))
        }

        data class Range(val start: Char, val end: Char) : ClassItem() {
            override fun matches(character: Char, ignoreCase: Boolean): Boolean {
                if (character in start..end) return true
                if (!ignoreCase) return false
                val lower = character.lowercaseChar()
                val upper = character.uppercaseChar()
                return lower in start..end || upper in start..end ||
                    (start.lowercaseChar()..end.lowercaseChar()).let { lower in it }
            }
        }

        data class Predicate(
            val description: String,
            val predicate: (Char, Boolean) -> Boolean,
        ) : ClassItem() {
            override fun matches(character: Char, ignoreCase: Boolean): Boolean =
                predicate(character, ignoreCase)
        }
    }

    private inner class PatternParser(
        private val pattern: String,
    ) {
        private var position = 0
        var groupCount: Int = 0
            private set
        private val namedGroups = mutableMapOf<String, Int>()

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
            return pattern.substring(start, position).toIntOrNull()
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
            var groupName: String? = null
            if (position < pattern.length && peek() == '?') {
                position++
                when {
                    position < pattern.length && peek() == ':' -> {
                        capturing = false
                        position++
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
                            throw IllegalArgumentException("Lookbehind is not supported by fallback regex engine")
                        }
                        val nameStart = position
                        while (position < pattern.length && peek() != '>') position++
                        if (position >= pattern.length) {
                            throw IllegalArgumentException("Unterminated named group")
                        }
                        groupName = pattern.substring(nameStart, position)
                        position++
                    }

                    else -> throw IllegalArgumentException("Unsupported group extension at index $position")
                }
            }
            val index = if (capturing && lookahead == null) ++groupCount else null
            if (index != null && groupName != null) {
                namedGroups[groupName] = index
            }
            val child = parseAlternation()
            if (position >= pattern.length || peek() != ')') {
                throw IllegalArgumentException("Unterminated group")
            }
            position++
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
            } else {
                Node.GroupNode(index, child)
            }
        }

        private fun parseCharacterClass(): Node {
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
                if (atom is ClassItem.Single && position < pattern.length && peek() == '-' &&
                    position + 1 < pattern.length && pattern[position + 1] != ']'
                ) {
                    position++
                    val endAtom = parseClassAtom()
                    if (endAtom is ClassItem.Single) {
                        // JS 规范：范围起点大于终点是 SyntaxError
                        if (atom.character > endAtom.character) {
                            throw IllegalArgumentException("Range out of order in character class")
                        }
                        items.add(ClassItem.Range(atom.character, endAtom.character))
                    } else {
                        items.add(atom)
                        items.add(ClassItem.Single('-'))
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
                    'b' -> ClassItem.Single('\b')
                    'n' -> ClassItem.Single('\n')
                    'r' -> ClassItem.Single('\r')
                    't' -> ClassItem.Single('\t')
                    'f' -> ClassItem.Single('\u000C')
                    'v' -> ClassItem.Single('\u000B')
                    '0' -> ClassItem.Single('\u0000')
                    'x' -> ClassItem.Single(readHex(2).toChar())
                    'u' -> ClassItem.Single(readUnicode().toChar())
                    else -> ClassItem.Single(character)
                }
            }
            val character = peek()
            position++
            return ClassItem.Single(character)
        }

        private fun digitPredicate(negated: Boolean): ClassItem = ClassItem.Predicate("digit") { character, _ ->
            (character in '0'..'9') != negated
        }

        private fun wordPredicate(negated: Boolean): ClassItem = ClassItem.Predicate("word") { character, _ ->
            (character in 'a'..'z' || character in 'A'..'Z' ||
                character in '0'..'9' || character == '_') != negated
        }

        private fun spacePredicate(negated: Boolean): ClassItem = ClassItem.Predicate("space") { character, _ ->
            (character == ' ' || character == '\t' || character == '\n' ||
                character == '\r' || character == '\u000B' || character == '\u000C' ||
                character == '\u00A0' || character == '\uFEFF') != negated
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
                'b' -> Node.AnchorNode(Node.AnchorKind.WORD_BOUNDARY, positive = true)
                'B' -> Node.AnchorNode(Node.AnchorKind.WORD_BOUNDARY, positive = false)
                'n' -> Node.CharNode('\n')
                'r' -> Node.CharNode('\r')
                't' -> Node.CharNode('\t')
                'f' -> Node.CharNode('\u000C')
                'v' -> Node.CharNode('\u000B')
                '0' -> Node.CharNode('\u0000')
                'x' -> Node.CharNode(readHex(2).toChar())
                'u' -> {
                    val codePoint = readUnicode()
                    if (codePoint <= 0xFFFF) {
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

                'c' -> {
                    val control = peek()
                    position++
                    Node.CharNode((control.code and 0x1F).toChar())
                }

                in '1'..'9' -> {
                    var index = character - '0'
                    while (position < pattern.length && peek() in '0'..'9' &&
                        index * 10 + (peek() - '0') <= groupCount
                    ) {
                        index = index * 10 + (peek() - '0')
                        position++
                    }
                    Node.BackReferenceNode(index)
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
                        val index = namedGroups[name]
                            ?: throw IllegalArgumentException("Unknown group name '$name'")
                        Node.BackReferenceNode(index)
                    } else {
                        Node.CharNode('k')
                    }
                }

                else -> Node.CharNode(character)
            }
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