package com.muedsa.js.runtime.regex

/**
 * 不可变码点集合，内部以升序区间对（start, end）存储。
 *
 * 供 RegExp `v` 标志的扩展字符类做并集、交集、差集与补集运算；
 * 区间对使用包含端点的语义，与 Unicode 属性表一致。
 */
internal class CodePointSet private constructor(val ranges: IntArray) {

    val isEmpty: Boolean get() = ranges.isEmpty()

    fun contains(codePoint: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val start = ranges[mid * 2]
            val end = ranges[mid * 2 + 1]
            when {
                codePoint < start -> high = mid - 1
                codePoint > end -> low = mid + 1
                else -> return true
            }
        }
        return false
    }

    fun union(other: CodePointSet): CodePointSet {
        if (ranges.isEmpty()) return other
        if (other.ranges.isEmpty()) return this
        val merged = ArrayList<Int>(ranges.size + other.ranges.size)
        var left = 0
        var right = 0
        while (left < ranges.size / 2 || right < other.ranges.size / 2) {
            val takeLeft = when {
                left >= ranges.size / 2 -> false
                right >= other.ranges.size / 2 -> true
                else -> ranges[left * 2] <= other.ranges[right * 2]
            }
            val start: Int
            val end: Int
            if (takeLeft) {
                start = ranges[left * 2]
                end = ranges[left * 2 + 1]
                left++
            } else {
                start = other.ranges[right * 2]
                end = other.ranges[right * 2 + 1]
                right++
            }
            if (merged.size >= 2 && start <= merged[merged.size - 1] + 1) {
                if (end > merged[merged.size - 1]) {
                    merged[merged.size - 1] = end
                }
            } else {
                merged.add(start)
                merged.add(end)
            }
        }
        return CodePointSet(merged.toIntArray())
    }

    fun intersect(other: CodePointSet): CodePointSet {
        val result = ArrayList<Int>()
        var left = 0
        var right = 0
        while (left < ranges.size / 2 && right < other.ranges.size / 2) {
            val leftStart = ranges[left * 2]
            val leftEnd = ranges[left * 2 + 1]
            val rightStart = other.ranges[right * 2]
            val rightEnd = other.ranges[right * 2 + 1]
            val start = maxOf(leftStart, rightStart)
            val end = minOf(leftEnd, rightEnd)
            if (start <= end) {
                result.add(start)
                result.add(end)
            }
            if (leftEnd < rightEnd) {
                left++
            } else {
                right++
            }
        }
        return CodePointSet(result.toIntArray())
    }

    fun subtract(other: CodePointSet): CodePointSet {
        if (other.ranges.isEmpty()) return this
        val result = ArrayList<Int>()
        var right = 0
        for (index in 0 until ranges.size / 2) {
            var start = ranges[index * 2]
            val end = ranges[index * 2 + 1]
            while (right < other.ranges.size / 2 && other.ranges[right * 2 + 1] < start) {
                right++
            }
            var probe = right
            while (probe < other.ranges.size / 2 && other.ranges[probe * 2] <= end) {
                val cutStart = other.ranges[probe * 2]
                val cutEnd = other.ranges[probe * 2 + 1]
                if (cutStart > start) {
                    result.add(start)
                    result.add(minOf(end, cutStart - 1))
                }
                start = maxOf(start, cutEnd + 1)
                if (start > end) break
                probe++
            }
            if (start <= end) {
                result.add(start)
                result.add(end)
            }
        }
        return CodePointSet(result.toIntArray())
    }

    fun complement(): CodePointSet {
        val result = ArrayList<Int>()
        var cursor = 0
        for (index in 0 until ranges.size / 2) {
            val start = ranges[index * 2]
            val end = ranges[index * 2 + 1]
            if (start > cursor) {
                result.add(cursor)
                result.add(start - 1)
            }
            cursor = end + 1
        }
        if (cursor <= MAX_CODE_POINT) {
            result.add(cursor)
            result.add(MAX_CODE_POINT)
        }
        return CodePointSet(result.toIntArray())
    }

    companion object {
        const val MAX_CODE_POINT = 0x10FFFF

        val EMPTY = CodePointSet(IntArray(0))
        val ALL = CodePointSet(intArrayOf(0, MAX_CODE_POINT))

        fun singleton(codePoint: Int): CodePointSet =
            CodePointSet(intArrayOf(codePoint, codePoint))

        fun range(start: Int, end: Int): CodePointSet =
            CodePointSet(intArrayOf(start, end))

        fun of(ranges: IntArray): CodePointSet = CodePointSet(ranges)
    }
}
