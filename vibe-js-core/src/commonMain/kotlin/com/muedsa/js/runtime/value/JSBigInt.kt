package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.JSException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * BigInt 原始值（ECMAScript 的 BigInt 类型，任意精度有符号整数）。
 *
 * 实现说明：
 * - 使用「符号 + 32 位 limb 数组（小端序）」表示，不依赖 java.math.BigInteger，
 *   因此可在所有 Kotlin 多平台目标上运行；
 * - limb 以 [Int] 存储但按无符号解释，高位 limb 恒非零（零用空数组表示）；
 * - 除法使用 Knuth 算法 D（归一化长除法），除数为单 limb 时走快速路径；
 * - 本类只做纯数值运算，**不抛 JS 异常**：除数为零、指数为负、结果超出实现上限
 *   等情况通过返回 null 交给解释器转换为带正确原型的 RangeError/TypeError。
 */
class JSBigInt private constructor(
    /** 符号：-1 负数、0 零、1 正数。 */
    private val sign: Int,
    /** 幅值（小端 32 位无符号 limb），最高位非零；零为空数组。 */
    private val magnitude: IntArray,
) : JSValue() {

    override val isPrimitive: Boolean get() = true

    /** 是否为零。 */
    val isZero: Boolean get() = sign == 0

    /** 是否为负数。 */
    val isNegative: Boolean get() = sign < 0

    /** 二进制有效位数（零为 0）。 */
    val bitLength: Int
        get() {
            if (magnitude.isEmpty()) return 0
            return (magnitude.size - 1) * 32 +
                (32 - magnitude[magnitude.size - 1].countLeadingZeroBits())
        }

    override fun toPrimitiveBoolean(): Boolean = sign != 0

    /**
     * ToNumber(BigInt) 按规范抛 TypeError。
     *
     * 正常路径由 [com.muedsa.js.runtime.Conversions] 抛出带 TypeError 原型的异常；
     * 这里只是值模型的兜底实现（没有解释器上下文，无法构造完整原型）。
     */
    override fun toPrimitiveNumber(): Double =
        throw JSException(JSError("TypeError", "Cannot convert a BigInt value to a number"))

    override fun toPrimitiveString(): String = toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is JSBigInt) return false
        return sign == other.sign && magnitude.contentEquals(other.magnitude)
    }

    override fun hashCode(): Int {
        // 相等的 BigInt 具有规范化的相同 limb 数组，因此按内容哈希即可
        var result = sign
        for (limb in magnitude) result = result * 31 + limb
        return result
    }

    override fun toString(): String = toString(10)

    /**
     * 按指定进制输出（2..36），负号在最高位数字前。
     *
     * 采用「每次按块除」的算法：每轮除以 radix^k（k 为使 radix^k 不超过 Int 上限的最大值），
     * 余数即一个块，最后拼接并补齐前导零。
     */
    fun toString(radix: Int): String {
        require(radix in 2..36) { "radix must be between 2 and 36" }
        if (sign == 0) return "0"
        val chunkDigits = chunkDigitCount(radix)
        val chunkBase = powerOf(radix, chunkDigits)
        val chunks = ArrayList<Int>()
        var current = magnitude
        while (current.isNotEmpty()) {
            val (quotient, remainder) = divideMagnitudeBySmall(current, chunkBase)
            chunks.add(remainder)
            current = quotient
        }
        val builder = StringBuilder()
        if (sign < 0) builder.append('-')
        builder.append(chunks[chunks.lastIndex].toString(radix))
        for (index in chunks.lastIndex - 1 downTo 0) {
            val text = chunks[index].toString(radix)
            repeat(chunkDigits - text.length) { builder.append('0') }
            builder.append(text)
        }
        return builder.toString()
    }

    /** 数值比较：返回 -1 / 0 / 1。 */
    operator fun compareTo(other: JSBigInt): Int {
        if (sign != other.sign) return if (sign < other.sign) -1 else 1
        if (sign == 0) return 0
        val comparison = compareMagnitudes(magnitude, other.magnitude)
        return if (sign > 0) comparison else -comparison
    }

    /** 取负。 */
    fun negate(): JSBigInt = if (sign == 0) this else JSBigInt(-sign, magnitude)

    /** 绝对值。 */
    fun abs(): JSBigInt = if (sign < 0) JSBigInt(1, magnitude) else this

    /** 加法。 */
    fun add(other: JSBigInt): JSBigInt {
        if (sign == 0) return other
        if (other.sign == 0) return this
        if (sign == other.sign) {
            return fromMagnitude(sign, addMagnitudes(magnitude, other.magnitude))
        }
        val comparison = compareMagnitudes(magnitude, other.magnitude)
        return when {
            comparison == 0 -> ZERO
            comparison > 0 -> fromMagnitude(sign, subtractMagnitudes(magnitude, other.magnitude))
            else -> fromMagnitude(other.sign, subtractMagnitudes(other.magnitude, magnitude))
        }
    }

    /** 减法。 */
    fun subtract(other: JSBigInt): JSBigInt = add(other.negate())

    /** 乘法。 */
    fun multiply(other: JSBigInt): JSBigInt {
        if (sign == 0 || other.sign == 0) return ZERO
        return fromMagnitude(sign * other.sign, multiplyMagnitudes(magnitude, other.magnitude))
    }

    /** 除法（截断取整）；调用方必须保证除数非零。 */
    fun divide(other: JSBigInt): JSBigInt {
        require(other.sign != 0) { "division by zero" }
        val quotient = divideMagnitudesKnuth(magnitude, other.magnitude)
        return fromMagnitude(sign * other.sign, quotient)
    }

    /** 取模（余数符号与被除数一致）；调用方必须保证除数非零。 */
    fun remainder(other: JSBigInt): JSBigInt {
        require(other.sign != 0) { "division by zero" }
        val remainder = remainderMagnitude(magnitude, other.magnitude)
        return fromMagnitude(sign, remainder)
    }

    /**
     * 幂运算（指数为非负 BigInt）。
     *
     * @return 指数为负或结果超出实现上限（约 1600 万位二进制位）时返回 null，
     *   由调用方转换为 RangeError
     */
    fun pow(exponent: JSBigInt): JSBigInt? {
        if (exponent.sign < 0) return null
        if (exponent.sign == 0) return ONE
        if (sign == 0) return ZERO
        // ±1 的任意次幂只需要判断奇偶，不受结果位数上限约束
        if (magnitude.size == 1 && magnitude[0] == 1) {
            val negative = sign < 0 && exponent.isOdd
            return if (negative) NEGATIVE_ONE else ONE
        }
        val exponentValue = exponent.toLongOrNull() ?: return null
        if (exponentValue > MAX_RESULT_BITS.toLong()) return null
        // 结果位数估算：(bitLength - 1) * exponent + 1，乘数不超过 2^31，不会溢出 Long
        val estimatedBits = (bitLength - 1).toLong() * exponentValue + 1
        if (estimatedBits > MAX_RESULT_BITS) return null
        var result = ONE
        var base = this
        var remaining = exponentValue
        while (remaining > 0L) {
            if (remaining and 1L == 1L) result = result.multiply(base)
            remaining = remaining ushr 1
            if (remaining > 0L) base = base.multiply(base)
        }
        return result
    }

    /**
     * 左移指定位数（负数位数表示右移）。
     *
     * @return 结果超过实现上限时返回 null（调用方转换为 RangeError）
     */
    fun shiftLeft(bits: Long): JSBigInt? {
        if (bits == 0L || sign == 0) return this
        if (bits < 0L) {
            // Long.MIN_VALUE 取负会溢出，直接按“超大右移”处理
            return shiftRight(if (bits == Long.MIN_VALUE) MAX_RESULT_BITS.toLong() else -bits)
        }
        if (bits > MAX_RESULT_BITS.toLong()) return null
        if (bitLength.toLong() + bits > MAX_RESULT_BITS) return null
        return fromMagnitude(sign, shiftLeftMagnitude(magnitude, bits.toInt()))
    }

    /**
     * 算术右移指定位数（向下取整，负数移位结果仍是 floor 除法）。
     *
     * @return 结果超过实现上限时返回 null（负数位数等同左移）
     */
    fun shiftRight(bits: Long): JSBigInt? {
        if (bits == 0L || sign == 0) return this
        if (bits < 0L) {
            // Long.MIN_VALUE 取负会溢出，直接按“超大左移”处理
            return shiftLeft(if (bits == Long.MIN_VALUE) MAX_RESULT_BITS.toLong() else -bits)
        }
        if (sign > 0) {
            if (bits >= bitLength.toLong()) return ZERO
            return fromMagnitude(1, shiftRightMagnitude(magnitude, bits.toInt()))
        }
        // 负数：floor(-|x| / 2^n) = -ceil(|x| / 2^n)
        if (bits >= bitLength.toLong()) return NEGATIVE_ONE
        val shifted = shiftRightMagnitude(magnitude, bits.toInt())
        val result = if (hasNonZeroBitsBelow(magnitude, bits.toInt())) {
            incrementMagnitude(shifted)
        } else {
            shifted
        }
        return fromMagnitude(-1, result)
    }

    /** 按位与（补码语义）。 */
    fun and(other: JSBigInt): JSBigInt = bitwiseCombine(other) { a, b -> a and b }

    /** 按位或（补码语义）。 */
    fun or(other: JSBigInt): JSBigInt = bitwiseCombine(other) { a, b -> a or b }

    /** 按位异或（补码语义）。 */
    fun xor(other: JSBigInt): JSBigInt = bitwiseCombine(other) { a, b -> a xor b }

    /** 按位取反：~x = -x - 1。 */
    fun not(): JSBigInt = negate().subtract(ONE)

    /**
     * BigInt.asUintN 的底层运算：返回 x modulo 2^bits（结果恒为非负）。
     *
     * @return bits 超出实现上限时返回 null（调用方转换为 RangeError）
     */
    fun asUintN(bits: Long): JSBigInt? {
        if (bits <= 0L) return ZERO
        if (bits > MAX_RESULT_BITS.toLong()) return null
        val low = lowBitsMagnitude(magnitude, bits)
        if (sign >= 0) return fromMagnitude(1, low)
        if (low.isEmpty()) return ZERO
        val power = fromMagnitude(1, powerOfTwoBits(bits.toInt()))
        return power.subtract(fromMagnitude(1, low))
    }

    /**
     * BigInt.asIntN 的底层运算：返回 x modulo 2^bits 的有符号解释。
     *
     * @return bits 超出实现上限时返回 null（调用方转换为 RangeError）
     */
    fun asIntN(bits: Long): JSBigInt? {
        if (bits <= 0L) return ZERO
        val unsigned = asUintN(bits) ?: return null
        // 无符号结果小于 2^(bits-1) 时就是最终结果；否则减去 2^bits
        if (unsigned.bitLength.toLong() < bits) return unsigned
        val power = fromMagnitude(1, powerOfTwoBits(bits.toInt()))
        return unsigned.subtract(power)
    }

    /** ToInt64：取低 64 位并按有符号解释。 */
    fun toInt64(): JSBigInt = JSBigInt.of(low64Bits())

    /** ToUint64：取低 64 位并按无符号解释。 */
    fun toUint64(): JSBigInt {
        val low = low64Bits()
        if (low >= 0L) return JSBigInt.of(low)
        return JSBigInt.of(low).add(POWER_OF_TWO_64)
    }

    /**
     * 转换为 Double（Number(bigint) / 数学比较使用）。
     *
     * 按 IEEE 754 的「最近偶数」舍入：保留最高 53 位，并根据舍入位与粘滞位决定进位。
     */
    fun toDouble(): Double {
        if (sign == 0) return 0.0
        val length = bitLength
        // 53 位以内可直接精确转换为 Double；更长时走“最高 53 位 + 最近偶数舍入”
        if (length <= 53) {
            var value = 0L
            for (index in magnitude.indices.reversed()) {
                value = (value shl 32) or (magnitude[index].toLong() and 0xFFFFFFFFL)
            }
            return if (sign < 0) -value.toDouble() else value.toDouble()
        }
        val shift = length - 53
        val topLimbs = shiftRightMagnitude(magnitude, shift)
        var top = 0L
        for (index in topLimbs.indices.reversed()) {
            top = (top shl 32) or (topLimbs[index].toLong() and 0xFFFFFFFFL)
        }
        // 第 shift-1 位是舍入位，更低位存在非零位时为粘滞位
        val roundBit = bitAt(magnitude, shift - 1)
        val sticky = hasNonZeroBitsBelow(magnitude, shift - 1)
        if (roundBit && (sticky || (top and 1L) == 1L)) top += 1
        val result = top.toDouble() * 2.0.pow(shift)
        return if (sign < 0) -result else result
    }

    /**
     * 指数是否可放入 Long（超过 2 个 limb 或最高位溢出时返回 null）。
     */
    fun toLongOrNull(): Long? {
        if (magnitude.size > 2) return null
        var value = 0L
        for (index in magnitude.indices.reversed()) {
            value = (value shl 32) or (magnitude[index].toLong() and 0xFFFFFFFFL)
        }
        if (value < 0L) return null
        return if (sign < 0) -value else value
    }

    /** 是否为奇数（仅对非零值有意义）。 */
    private val isOdd: Boolean
        get() = magnitude.isNotEmpty() && (magnitude[0] and 1) == 1

    // ================================================================
    // 位运算的补码转换
    // ================================================================

    private inline fun bitwiseCombine(
        other: JSBigInt,
        operation: (Int, Int) -> Int,
    ): JSBigInt {
        if (sign == 0 && other.sign == 0) return ZERO
        val size = max(magnitude.size, other.magnitude.size) + 1
        val left = toTwosComplement(size)
        val right = other.toTwosComplement(size)
        val result = IntArray(size)
        for (index in 0 until size) result[index] = operation(left[index], right[index])
        return fromTwosComplement(result)
    }

    /** 把当前值转换为定长 size 个 limb 的补码表示。 */
    private fun toTwosComplement(size: Int): IntArray {
        val result = IntArray(size)
        magnitude.copyInto(result)
        if (sign >= 0) return result
        // 负数：按位取反再加一
        for (index in result.indices) result[index] = result[index].inv()
        var carry = 1L
        var index = 0
        while (carry != 0L && index < size) {
            val sum = (result[index].toLong() and 0xFFFFFFFFL) + carry
            result[index] = sum.toInt()
            carry = sum ushr 32
            index++
        }
        return result
    }

    /** 把定长补码表示还原为符号 + 幅值。 */
    private fun fromTwosComplement(limbs: IntArray): JSBigInt {
        if (limbs.isEmpty()) return ZERO
        if (limbs[limbs.size - 1] >= 0) return fromMagnitude(1, normalize(limbs))
        val result = IntArray(limbs.size)
        for (index in limbs.indices) result[index] = limbs[index].inv()
        var carry = 1L
        var index = 0
        while (carry != 0L && index < result.size) {
            val sum = (result[index].toLong() and 0xFFFFFFFFL) + carry
            result[index] = sum.toInt()
            carry = sum ushr 32
            index++
        }
        return fromMagnitude(-1, normalize(result))
    }

    // ================================================================
    // 幅值运算（静态辅助）
    // ================================================================

    companion object {

        /** 结果允许的最大二进制位数（约 2 MB 幅值），超出时按实现上限抛 RangeError。 */
        const val MAX_RESULT_BITS: Int = 1 shl 24

        val ZERO = JSBigInt(0, IntArray(0))
        val ONE = JSBigInt(1, intArrayOf(1))
        val NEGATIVE_ONE = JSBigInt(-1, intArrayOf(1))

        private val POWER_OF_TWO_64 = JSBigInt(1, powerOfTwoBits(64))

        /** 由 Long 构造。 */
        fun of(value: Long): JSBigInt {
            if (value == 0L) return ZERO
            // 取幅值：-Long.MIN_VALUE 会溢出，因此先加一再取反
            var magnitudeValue = (if (value < 0L) -(value + 1L) else value).toULong()
            if (value < 0L) magnitudeValue += 1uL
            val low = magnitudeValue.toUInt().toInt()
            val high = (magnitudeValue shr 32).toUInt().toInt()
            return if (high == 0) {
                JSBigInt(if (value < 0L) -1 else 1, intArrayOf(low))
            } else {
                JSBigInt(if (value < 0L) -1 else 1, intArrayOf(low, high))
            }
        }

        /** 由 Int 构造。 */
        fun of(value: Int): JSBigInt = of(value.toLong())

        /**
         * 解析 StringIntegerLiteral（不含首尾空白，允许可选正负号）。
         *
         * 空串按规范返回 0n；非法格式（小数、指数、非法进制数字、非十进制带符号等）返回 null。
         */
        fun parse(text: String): JSBigInt? {
            if (text.isEmpty()) return ZERO
            var index = 0
            var sign = 1
            var hasSign = false
            if (text[0] == '+' || text[0] == '-') {
                if (text[0] == '-') sign = -1
                hasSign = true
                index++
            }
            if (index >= text.length) return null
            // 非十进制前缀只允许无符号形式（-0x1、+0x1 非法）
            if (text[index] == '0' && index + 1 < text.length) {
                return when (text[index + 1]) {
                    'x', 'X' -> {
                        if (hasSign) return null
                        parseDigits(text, index + 2, 16)
                    }

                    'o', 'O' -> {
                        if (hasSign) return null
                        parseDigits(text, index + 2, 8)
                    }

                    'b', 'B' -> {
                        if (hasSign) return null
                        parseDigits(text, index + 2, 2)
                    }

                    else -> parseSignedDecimal(text, index, sign)
                }
            }
            return parseSignedDecimal(text, index, sign)
        }

        /**
         * 解析 BigInt 字面量原文（含末尾 `n`）。
         *
         * 字面量允许数字分隔符 `_`（如 `1_000n`），解析前需要移除；
         * 字符串转 BigInt（StringToBigInt）则不允许分隔符。
         *
         * @return 非法原文返回 null（词法/语法阶段本应拒绝，这里只是防御）
         */
        fun parseLiteral(raw: String): JSBigInt? {
            if (!raw.endsWith('n') || raw.length < 2) return null
            val text = raw.substring(0, raw.length - 1).replace("_", "")
            return parse(text)
        }

        /**
         * NumberToBigInt：Double 必须是有限整数。
         *
         * @return NaN、无穷或非整数返回 null（调用方转为 RangeError）
         */
        fun fromDoubleExact(value: Double): JSBigInt? {
            if (value.isNaN() || value.isInfinite()) return null
            if (value == 0.0) return ZERO
            if (value != kotlin.math.floor(value)) return null
            val bits = value.toRawBits()
            val negative = bits < 0L
            val exponentBits = ((bits ushr 52) and 0x7FFL).toInt()
            val fractionBits = bits and ((1L shl 52) - 1L)
            val mantissa: Long
            val binaryExponent: Int
            if (exponentBits == 0) {
                mantissa = fractionBits
                binaryExponent = -1074
            } else {
                mantissa = fractionBits or (1L shl 52)
                binaryExponent = exponentBits - 1075
            }
            var result = of(mantissa)
            if (binaryExponent >= 0) {
                result = fromMagnitude(1, shiftLeftMagnitude(result.magnitude, binaryExponent))
            } else {
                // 整数值的低位必然为 0，右移是精确的
                result = fromMagnitude(1, shiftRightMagnitude(result.magnitude, -binaryExponent))
            }
            return if (negative) result.negate() else result
        }

        /**
         * BigInt 与 Number 的精确数学比较。
         *
         * @return -1 / 0 / 1；Number 为 NaN 时返回 null（所有关系比较均为 false）
         */
        fun compareToDouble(value: JSBigInt, number: Double): Int? {
            if (number.isNaN()) return null
            if (number == Double.POSITIVE_INFINITY) return -1
            if (number == Double.NEGATIVE_INFINITY) return 1
            if (number == 0.0) return value.sign
            val bits = number.toRawBits()
            val negative = bits < 0L
            val exponentBits = ((bits ushr 52) and 0x7FFL).toInt()
            val fractionBits = bits and ((1L shl 52) - 1L)
            val mantissa: Long
            val binaryExponent: Int
            if (exponentBits == 0) {
                mantissa = fractionBits
                binaryExponent = -1074
            } else {
                mantissa = fractionBits or (1L shl 52)
                binaryExponent = exponentBits - 1075
            }
            // |number| = mantissa × 2^binaryExponent，整数值精确比较
            val numberMagnitude = of(mantissa)
            val magnitudeComparison = if (binaryExponent >= 0) {
                compareMagnitudes(
                    value.magnitude,
                    shiftLeftMagnitude(numberMagnitude.magnitude, binaryExponent),
                )
            } else {
                // 两边同乘 2^(-binaryExponent)，避免小数
                compareMagnitudes(
                    shiftLeftMagnitude(value.magnitude, -binaryExponent),
                    numberMagnitude.magnitude,
                )
            }
            val numberSign = if (negative) -1 else 1
            if (value.sign != numberSign) return if (value.sign > numberSign) 1 else -1
            return if (value.sign > 0) magnitudeComparison else -magnitudeComparison
        }

        /** StringToBigInt 的辅助：解析十进制部分（可带符号）。 */
        private fun parseSignedDecimal(text: String, start: Int, sign: Int): JSBigInt? {
            val magnitude = parseDigits(text, start, 10) ?: return null
            return if (sign < 0) magnitude.negate() else magnitude
        }

        /** 按指定进制解析 [start, end) 的数字串；非法返回 null。 */
        private fun parseDigits(text: String, start: Int, radix: Int): JSBigInt? {
            if (start >= text.length) return null
            // 选择一次可转换的最大块，减少大数乘法次数
            var chunkDigits = 1
            var chunkBase = radix
            while (chunkBase.toLong() * radix <= Int.MAX_VALUE) {
                chunkBase *= radix
                chunkDigits++
            }
            var index = start
            var result = ZERO
            while (index < text.length) {
                val end = min(index + chunkDigits, text.length)
                var chunk = 0
                for (position in index until end) {
                    val digit = digitValue(text[position])
                    if (digit < 0 || digit >= radix) return null
                    chunk = chunk * radix + digit
                }
                // 最后一组可能不足 chunkDigits 位
                var factor = 1
                repeat(end - index) { factor *= radix }
                result = fromMagnitude(1, multiplyAddSmall(result.magnitude, factor, chunk))
                index = end
            }
            return result
        }

        /** 数字字符的数值；非法字符返回 -1。 */
        private fun digitValue(character: Char): Int = when (character) {
            in '0'..'9' -> character - '0'
            in 'a'..'z' -> character - 'a' + 10
            in 'A'..'Z' -> character - 'A' + 10
            else -> -1
        }

        /** 使 radix^k 不超过 Int 上限的最大 k。 */
        private fun chunkDigitCount(radix: Int): Int {
            var digits = 1
            var base = radix
            while (base.toLong() * radix <= Int.MAX_VALUE) {
                base *= radix
                digits++
            }
            return digits
        }

        /** radix^exponent（保证不超过 Int 上限）。 */
        private fun powerOf(radix: Int, exponent: Int): Int {
            var result = 1
            repeat(exponent) { result *= radix }
            return result
        }

        /** 由符号与幅值构造；幅值会被规范化，空幅值返回零。 */
        private fun fromMagnitude(sign: Int, magnitude: IntArray): JSBigInt {
            val normalized = normalize(magnitude)
            if (normalized.isEmpty() || sign == 0) return ZERO
            return JSBigInt(if (sign < 0) -1 else 1, normalized)
        }

        /** 去掉高位零 limb；全部为零时返回空数组。 */
        private fun normalize(limbs: IntArray): IntArray {
            var size = limbs.size
            while (size > 0 && limbs[size - 1] == 0) size--
            if (size == limbs.size) return limbs
            return limbs.copyOf(size)
        }

        /** 幅值比较。 */
        private fun compareMagnitudes(left: IntArray, right: IntArray): Int {
            if (left.size != right.size) return if (left.size < right.size) -1 else 1
            for (index in left.indices.reversed()) {
                val x = left[index].toUInt()
                val y = right[index].toUInt()
                if (x != y) return if (x < y) -1 else 1
            }
            return 0
        }

        /** 幅值相加。 */
        private fun addMagnitudes(left: IntArray, right: IntArray): IntArray {
            val size = max(left.size, right.size)
            val result = IntArray(size + 1)
            var carry = 0L
            for (index in 0 until size) {
                val x = if (index < left.size) left[index].toLong() and 0xFFFFFFFFL else 0L
                val y = if (index < right.size) right[index].toLong() and 0xFFFFFFFFL else 0L
                val sum = x + y + carry
                result[index] = sum.toInt()
                carry = sum ushr 32
            }
            result[size] = carry.toInt()
            return normalize(result)
        }

        /** 幅值相减（要求 left ≥ right）。 */
        private fun subtractMagnitudes(left: IntArray, right: IntArray): IntArray {
            val result = IntArray(left.size)
            var borrow = 0L
            for (index in left.indices) {
                val x = left[index].toLong() and 0xFFFFFFFFL
                val y = if (index < right.size) right[index].toLong() and 0xFFFFFFFFL else 0L
                var difference = x - y - borrow
                if (difference < 0L) {
                    difference += 0x100000000L
                    borrow = 1L
                } else {
                    borrow = 0L
                }
                result[index] = difference.toInt()
            }
            return normalize(result)
        }

        /** 幅值相乘（学校算法，ULong 累加保证不溢出）。 */
        private fun multiplyMagnitudes(left: IntArray, right: IntArray): IntArray {
            if (left.isEmpty() || right.isEmpty()) return IntArray(0)
            val result = IntArray(left.size + right.size)
            for (i in left.indices) {
                var carry = 0uL
                val leftLimb = left[i].toUInt().toULong()
                for (j in right.indices) {
                    val current = result[i + j].toUInt().toULong() +
                        leftLimb * right[j].toUInt().toULong() + carry
                    result[i + j] = current.toUInt().toInt()
                    carry = current shr 32
                }
                result[i + right.size] = carry.toUInt().toInt()
            }
            return normalize(result)
        }

        /** 幅值乘小整数再相加：value * factor + addend。 */
        private fun multiplyAddSmall(limbs: IntArray, factor: Int, addend: Int): IntArray {
            if (limbs.isEmpty()) return if (addend == 0) IntArray(0) else intArrayOf(addend)
            val result = IntArray(limbs.size + 1)
            var carry = addend.toLong()
            for (index in limbs.indices) {
                val product = (limbs[index].toLong() and 0xFFFFFFFFL) * factor + carry
                result[index] = product.toInt()
                carry = product ushr 32
            }
            result[limbs.size] = carry.toInt()
            return normalize(result)
        }

        /** 幅值除以小整数：返回商（规范化）与余数。 */
        private fun divideMagnitudeBySmall(limbs: IntArray, divisor: Int): Pair<IntArray, Int> {
            val quotient = IntArray(limbs.size)
            var remainder = 0uL
            val divisorValue = divisor.toUInt().toULong()
            for (index in limbs.indices.reversed()) {
                val current = (remainder shl 32) or limbs[index].toUInt().toULong()
                quotient[index] = (current / divisorValue).toUInt().toInt()
                remainder = current % divisorValue
            }
            return normalize(quotient) to remainder.toInt()
        }

        /** 幅值加一。 */
        private fun incrementMagnitude(limbs: IntArray): IntArray {
            val result = limbs.copyOf(limbs.size + 1)
            var index = 0
            var carry = 1L
            while (carry != 0L && index < result.size) {
                val sum = (result[index].toLong() and 0xFFFFFFFFL) + carry
                result[index] = sum.toInt()
                carry = sum ushr 32
                index++
            }
            return normalize(result)
        }

        /** 左移幅值。 */
        private fun shiftLeftMagnitude(source: IntArray, bits: Int): IntArray {
            if (source.isEmpty() || bits == 0) return source
            val wordShift = bits ushr 5
            val bitShift = bits and 31
            val result = IntArray(source.size + wordShift + 1)
            if (bitShift == 0) {
                source.copyInto(result, wordShift)
            } else {
                var carry = 0
                for (index in source.indices) {
                    val value = source[index]
                    result[index + wordShift] = (value shl bitShift) or carry
                    carry = value ushr (32 - bitShift)
                }
                result[source.size + wordShift] = carry
            }
            return normalize(result)
        }

        /** 逻辑右移幅值。 */
        private fun shiftRightMagnitude(source: IntArray, bits: Int): IntArray {
            if (source.isEmpty() || bits == 0) return source
            val wordShift = bits ushr 5
            val bitShift = bits and 31
            if (wordShift >= source.size) return IntArray(0)
            val result = IntArray(source.size - wordShift)
            if (bitShift == 0) {
                for (index in result.indices) result[index] = source[index + wordShift]
            } else {
                for (index in result.indices) {
                    val low = source[index + wordShift] ushr bitShift
                    val high = if (index + wordShift + 1 < source.size) {
                        source[index + wordShift + 1] shl (32 - bitShift)
                    } else {
                        0
                    }
                    result[index] = low or high
                }
            }
            return normalize(result)
        }

        /** 取绝对值低 [bits] 位。 */
        private fun lowBitsMagnitude(limbs: IntArray, bits: Long): IntArray {
            if (bits <= 0L) return IntArray(0)
            if (bits >= limbs.size.toLong() * 32L) return limbs
            val fullWords = (bits / 32L).toInt()
            val extraBits = (bits % 32L).toInt()
            val resultSize = fullWords + if (extraBits > 0) 1 else 0
            val result = IntArray(resultSize)
            for (index in 0 until fullWords) result[index] = limbs[index]
            if (extraBits > 0) {
                val mask = (1L shl extraBits) - 1L
                result[fullWords] = (limbs[fullWords].toLong() and mask).toInt()
            }
            return normalize(result)
        }

        /** 2^bits 的幅值表示。 */
        private fun powerOfTwoBits(bits: Int): IntArray =
            IntArray(bits / 32 + 1).also { it[bits / 32] = 1 shl (bits % 32) }

        /** 取第 [index] 位（0 起）。 */
        private fun bitAt(limbs: IntArray, index: Int): Boolean {
            if (index < 0) return false
            val word = index ushr 5
            if (word >= limbs.size) return false
            return (limbs[word] ushr (index and 31)) and 1 == 1
        }

        /** 是否存在低于 [index] 的非零位（index 位及更高位不计）。 */
        private fun hasNonZeroBitsBelow(limbs: IntArray, index: Int): Boolean {
            if (index <= 0) return false
            val word = index ushr 5
            val bit = index and 31
            for (position in 0 until word) {
                if (limbs[position] != 0) return true
            }
            if (bit > 0 && (limbs[word] and ((1 shl bit) - 1)) != 0) return true
            return false
        }

        /**
         * 幅值长除法（Knuth 算法 D）。
         *
         * 要求除数非零；除数为单个 limb 时使用短除法快速路径。
         */
        private fun divideMagnitudesKnuth(dividend: IntArray, divisor: IntArray): IntArray {
            if (compareMagnitudes(dividend, divisor) < 0) return IntArray(0)
            if (divisor.size == 1) {
                return divideMagnitudeBySmall(dividend, divisor[0]).first
            }
            val n = divisor.size
            val m = dividend.size - n
            val shift = divisor[n - 1].countLeadingZeroBits()
            // 归一化：让除数最高位为 1
            val v = if (shift == 0) divisor else shiftLeftMagnitude(divisor, shift)
            val u = shiftLeftInto(dividend, shift, dividend.size + 1)
            val quotient = IntArray(m + 1)
            val base = 0x100000000uL
            val vHigh = v[n - 1].toUInt().toULong()
            val vSecond = v[n - 2].toUInt().toULong()
            for (j in m downTo 0) {
                val numerator = (u[j + n].toUInt().toULong() shl 32) or
                    u[j + n - 1].toUInt().toULong()
                var qhat = numerator / vHigh
                var rhat = numerator % vHigh
                // 修正 qhat，保证 qhat * v[n-2] ≤ rhat * B + u[j+n-2]
                while (qhat >= base ||
                    qhat * vSecond > ((rhat shl 32) or u[j + n - 2].toUInt().toULong())
                ) {
                    qhat -= 1uL
                    rhat += vHigh
                    if (rhat >= base) break
                }
                // 乘减：u[j..j+n] -= qhat * v
                var carry = 0L
                var borrow = 0L
                for (index in 0 until n) {
                    val product = qhat.toLong() * (v[index].toLong() and 0xFFFFFFFFL) + carry
                    carry = product ushr 32
                    var difference = (u[j + index].toLong() and 0xFFFFFFFFL) -
                        (product and 0xFFFFFFFFL) - borrow
                    if (difference < 0L) {
                        difference += 0x100000000L
                        borrow = 1L
                    } else {
                        borrow = 0L
                    }
                    u[j + index] = difference.toInt()
                }
                var difference = (u[j + n].toLong() and 0xFFFFFFFFL) - carry - borrow
                u[j + n] = difference.toInt()
                if (difference < 0L) {
                    // qhat 多减了 1：加回除数
                    qhat -= 1uL
                    var addCarry = 0L
                    for (index in 0 until n) {
                        val sum = (u[j + index].toLong() and 0xFFFFFFFFL) +
                            (v[index].toLong() and 0xFFFFFFFFL) + addCarry
                        u[j + index] = sum.toInt()
                        addCarry = sum ushr 32
                    }
                    u[j + n] = ((u[j + n].toLong() and 0xFFFFFFFFL) + addCarry).toInt()
                }
                quotient[j] = qhat.toUInt().toInt()
            }
            return normalize(quotient)
        }

        /** 幅值取模。 */
        private fun remainderMagnitude(dividend: IntArray, divisor: IntArray): IntArray {
            if (compareMagnitudes(dividend, divisor) < 0) return dividend
            val n = divisor.size
            if (n == 1) {
                return divideMagnitudeBySmall(dividend, divisor[0]).second.let {
                    if (it == 0) IntArray(0) else intArrayOf(it)
                }
            }
            val m = dividend.size - n
            val shift = divisor[n - 1].countLeadingZeroBits()
            val v = if (shift == 0) divisor else shiftLeftMagnitude(divisor, shift)
            val u = shiftLeftInto(dividend, shift, dividend.size + 1)
            val base = 0x100000000uL
            val vHigh = v[n - 1].toUInt().toULong()
            val vSecond = v[n - 2].toUInt().toULong()
            for (j in m downTo 0) {
                val numerator = (u[j + n].toUInt().toULong() shl 32) or
                    u[j + n - 1].toUInt().toULong()
                var qhat = numerator / vHigh
                var rhat = numerator % vHigh
                while (qhat >= base ||
                    qhat * vSecond > ((rhat shl 32) or u[j + n - 2].toUInt().toULong())
                ) {
                    qhat -= 1uL
                    rhat += vHigh
                    if (rhat >= base) break
                }
                var carry = 0L
                var borrow = 0L
                for (index in 0 until n) {
                    val product = qhat.toLong() * (v[index].toLong() and 0xFFFFFFFFL) + carry
                    carry = product ushr 32
                    var difference = (u[j + index].toLong() and 0xFFFFFFFFL) -
                        (product and 0xFFFFFFFFL) - borrow
                    if (difference < 0L) {
                        difference += 0x100000000L
                        borrow = 1L
                    } else {
                        borrow = 0L
                    }
                    u[j + index] = difference.toInt()
                }
                var difference = (u[j + n].toLong() and 0xFFFFFFFFL) - carry - borrow
                u[j + n] = difference.toInt()
                if (difference < 0L) {
                    var addCarry = 0L
                    for (index in 0 until n) {
                        val sum = (u[j + index].toLong() and 0xFFFFFFFFL) +
                            (v[index].toLong() and 0xFFFFFFFFL) + addCarry
                        u[j + index] = sum.toInt()
                        addCarry = sum ushr 32
                    }
                    u[j + n] = ((u[j + n].toLong() and 0xFFFFFFFFL) + addCarry).toInt()
                }
            }
            val remainder = IntArray(n)
            for (index in 0 until n) remainder[index] = u[index]
            val normalizedRemainder = if (shift == 0) {
                remainder
            } else {
                shiftRightMagnitude(remainder, shift)
            }
            return normalize(normalizedRemainder)
        }

        /**
         * 把 source 左移 shift 位并写入长度为 size 的数组。
         *
         * 用于归一化被除数时预留最高位的进位 limb。
         */
        private fun shiftLeftInto(source: IntArray, shift: Int, size: Int): IntArray {
            val result = IntArray(size)
            if (source.isEmpty() || shift == 0) {
                source.copyInto(result)
                return result
            }
            var carry = 0
            for (index in source.indices) {
                val value = source[index]
                result[index] = (value shl shift) or carry
                carry = value ushr (32 - shift)
            }
            if (source.size < size) result[source.size] = carry
            return result
        }

    }

    /** 取低 64 位的位模式（负数为补码，按 Long 位模式返回）。 */
    private fun low64Bits(): Long {
        var value = 0L
        val words = min(2, magnitude.size)
        for (index in 0 until words) {
            value = value or (magnitude[index].toLong() shl (32 * index))
        }
        return if (sign < 0) -value else value
    }
}
