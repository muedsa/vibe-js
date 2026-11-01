package com.muedsa.js.runtime.value

/**
 * `Number.prototype.toString(radix)` 在 2..36 进制下的最短往返表示。
 *
 * 规范要求：找到位数 k 最少的 s，使 s × radix^(n−k) 与 x 的最近 double 表示一致；
 * 若存在多个，取最接近 x 的 s（并列取偶数）。实现步骤：
 * 1. 把 |x| 精确分解为 m × 2^e；
 * 2. 用二进制大整数按位生成 radix 数字流（整数部分反复除以 radix，
 *    小数部分反复乘 radix 并取高位），因此不受浮点舍入与 2^63 上限影响；
 * 3. 从 1 位有效数字起，取最近的 radix 表示，并检查它是否落在 x 的
 *    舍入区间（相邻 double 的中点）内；首个满足的位数即最短表示；
 * 4. 去掉尾随零并按小数点位置格式化。
 *
 * 典型输入（0.1、次正规数、超过 2^53 的整数）不会下溢或溢出。
 */
internal object NumberRadixFormatting {

    private const val LIMB_BITS = 32
    private const val LIMB_MASK = 0xFFFF_FFFFL
    private const val MAX_SIGNIFICANT_DIGITS = 80
    private const val DIGIT_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** 返回正有限值在指定进制下的最短表示（不含符号）。 */
    fun toString(value: Double, radix: Int): String {
        val decomposition = Decomposition.of(value)
        val source = DigitSource(radix, decomposition)
        val bounds = roundingBounds(decomposition)

        var significand = BigInt.ZERO
        var chosenSignificand: BigInt? = null
        var chosenExponent = 0
        for (length in 1..MAX_SIGNIFICANT_DIGITS) {
            significand = significand.multiplySmall(radix).addSmall(source.nextSignificantDigit())
            val exponent = source.pointPosition - length
            // 最近候选：比较 2x 与 (2S+1) × radix^exponent 的中点
            val comparison = compareBinaryVersusRadix(
                decomposition.mantissaValue,
                decomposition.exponent + 1,
                significand.shiftLeft(1).addSmall(1),
                radix,
                exponent,
            )
            val candidate = when {
                comparison > 0 -> significand.addSmall(1)
                comparison < 0 -> significand
                // 恰好位于两个候选中间：选择有效数字为偶数的候选（round-half-even）
                else -> if (significand.isEven) significand else significand.addSmall(1)
            }
            if (inRoundingInterval(candidate, exponent, bounds, radix, decomposition.mantissa)) {
                chosenSignificand = candidate
                chosenExponent = exponent
                break
            }
        }

        val chosen = chosenSignificand
            ?: error("unreachable: radix representation exceeds $MAX_SIGNIFICANT_DIGITS digits")
        // 去掉尾随零并把它们折算进指数（不改变输出文本）
        var digitsValue = chosen
        var exponent = chosenExponent
        while (!digitsValue.isZero) {
            val (quotient, remainder) = digitsValue.divModSmall(radix)
            if (remainder != 0) break
            digitsValue = quotient
            exponent++
        }
        return format(digitsValue.toRadixString(radix), exponent)
    }

    /** 按“整数/小数点位置”规则拼接最终文本（radix ≠ 10 时不使用指数形式）。 */
    private fun format(digits: String, exponent: Int): String {
        val digitCount = digits.length
        val pointPosition = exponent + digitCount
        return when {
            pointPosition >= digitCount -> digits + "0".repeat(pointPosition - digitCount)
            pointPosition > 0 ->
                digits.substring(0, pointPosition) + "." + digits.substring(pointPosition)

            else -> "0." + "0".repeat(-pointPosition) + digits
        }
    }

    /** IEEE 754 分解：value = mantissa × 2^exponent（value 为正有限数）。 */
    private class Decomposition(
        val mantissa: Long,
        val exponent: Int,
        val exponentBits: Int,
    ) {
        val mantissaValue: BigInt = BigInt.of(mantissa)

        companion object {
            fun of(value: Double): Decomposition {
                val bits = value.toRawBits()
                val exponentBits = ((bits ushr 52) and 0x7FFL).toInt()
                val fractionBits = bits and ((1L shl 52) - 1L)
                return if (exponentBits == 0) {
                    // 次正规数没有隐含首位 1，指数固定为 -1074
                    Decomposition(fractionBits, -1074, exponentBits)
                } else {
                    Decomposition(fractionBits or (1L shl 52), exponentBits - 1075, exponentBits)
                }
            }
        }
    }

    /** x 的舍入区间端点：low = lowNumerator × 2^lowExponent，high 同理。 */
    private class Bounds(
        val lowNumerator: BigInt,
        val lowExponent: Int,
        val highNumerator: BigInt,
        val highExponent: Int,
    )

    /**
     * 计算 x 的舍入区间：相邻 double 的中点。
     *
     * 2 的幂（非最小正规数）的下方间距减半；最小正规数与次正规数使用正常间距。
     */
    private fun roundingBounds(decomposition: Decomposition): Bounds {
        val mantissa = decomposition.mantissa
        val exponent = decomposition.exponent
        val lowNumerator: Long
        val lowExponent: Int
        if (decomposition.exponentBits > 1 && mantissa == (1L shl 52)) {
            lowNumerator = 4L * mantissa - 1L
            lowExponent = exponent - 2
        } else {
            lowNumerator = 2L * mantissa - 1L
            lowExponent = exponent - 1
        }
        return Bounds(
            BigInt.of(lowNumerator),
            lowExponent,
            BigInt.of(2L * mantissa + 1L),
            exponent - 1,
        )
    }

    /**
     * 候选值 candidate × radix^exponent 是否位于 x 的舍入区间内。
     *
     * 端点恰好落在中点时，仅当 x 的有效数字为偶数（round-half-even 选择 x）才接受。
     */
    private fun inRoundingInterval(
        candidate: BigInt,
        exponent: Int,
        bounds: Bounds,
        radix: Int,
        mantissa: Long,
    ): Boolean {
        val lowComparison = compareBinaryVersusRadix(
            bounds.lowNumerator,
            bounds.lowExponent,
            candidate,
            radix,
            exponent,
        )
        if (lowComparison > 0) return false
        val highComparison = compareBinaryVersusRadix(
            bounds.highNumerator,
            bounds.highExponent,
            candidate,
            radix,
            exponent,
        )
        if (highComparison < 0) return false
        return mantissa % 2L == 0L || (lowComparison != 0 && highComparison != 0)
    }

    /**
     * 比较 binaryNumerator × 2^binaryExponent 与 radixNumerator × radix^radixExponent。
     *
     * 返回负数/0/正数表示左侧小于/等于/大于右侧；通过交叉乘幂化为整数比较。
     */
    private fun compareBinaryVersusRadix(
        binaryNumerator: BigInt,
        binaryExponent: Int,
        radixNumerator: BigInt,
        radix: Int,
        radixExponent: Int,
    ): Int {
        var left = binaryNumerator
        var right = radixNumerator
        if (binaryExponent >= 0) {
            left = left.shiftLeft(binaryExponent)
        } else {
            right = right.shiftLeft(-binaryExponent)
        }
        if (radixExponent >= 0) {
            right = right.multiplyByPower(radix, radixExponent)
        } else {
            left = left.multiplyByPower(radix, -radixExponent)
        }
        return left.compareTo(right)
    }

    /** 按需生成 |x| 在指定进制下的有效数字，以及小数点位置 n。 */
    private class DigitSource(radix: Int, decomposition: Decomposition) {
        private val radix = radix
        private val integerDigits: IntArray
        private var integerIndex = 0
        private var fractionNumerator: BigInt
        private var fractionShift: Int
        private var pendingFirstSignificant = -1

        /** 小数点位置：x ≥ 1 时为整数位数，x < 1 时为负的前导零个数。 */
        val pointPosition: Int

        init {
            val mantissa = decomposition.mantissaValue
            if (decomposition.exponent >= 0) {
                // 整数：value = mantissa × 2^exponent，直接反复除以 radix 取数字
                var remaining = mantissa.shiftLeft(decomposition.exponent)
                val reversed = ArrayList<Int>()
                while (!remaining.isZero) {
                    val (quotient, remainder) = remaining.divModSmall(radix)
                    reversed.add(remainder)
                    remaining = quotient
                }
                integerDigits = IntArray(reversed.size) { reversed[reversed.size - 1 - it] }
                fractionNumerator = BigInt.ZERO
                fractionShift = 0
                pointPosition = integerDigits.size
            } else {
                // 小数：value = (整数部分) + R / 2^shift
                fractionShift = -decomposition.exponent
                var remaining = mantissa.shiftRight(fractionShift)
                fractionNumerator = mantissa.maskLowBits(fractionShift)
                val reversed = ArrayList<Int>()
                while (!remaining.isZero) {
                    val (quotient, remainder) = remaining.divModSmall(radix)
                    reversed.add(remainder)
                    remaining = quotient
                }
                integerDigits = IntArray(reversed.size) { reversed[reversed.size - 1 - it] }
                if (integerDigits.isNotEmpty()) {
                    pointPosition = integerDigits.size
                } else {
                    // 纯小数：跳过前导零，直到首个非零有效数字
                    var zeros = 0
                    while (true) {
                        val digit = nextFractionDigit()
                        if (digit != 0) {
                            pendingFirstSignificant = digit
                            break
                        }
                        zeros++
                    }
                    pointPosition = -zeros
                }
            }
        }

        /** 下一个有效数字（整数部分按高位到低位，随后是小数部分）。 */
        fun nextSignificantDigit(): Int {
            if (pendingFirstSignificant >= 0) {
                val digit = pendingFirstSignificant
                pendingFirstSignificant = -1
                return digit
            }
            if (integerIndex < integerDigits.size) return integerDigits[integerIndex++]
            return nextFractionDigit()
        }

        /** 小数部分：R ← R × radix，数字为 R / 2^shift 的整数部分，余数保留。 */
        private fun nextFractionDigit(): Int {
            if (fractionShift == 0) return 0
            fractionNumerator = fractionNumerator.multiplySmall(radix)
            val digit = fractionNumerator.shiftRight(fractionShift).toIntValue()
            fractionNumerator = fractionNumerator.maskLowBits(fractionShift)
            return digit
        }
    }

    /**
     * 无符号二进制大整数（32 位小端 limb，空数组表示 0）。
     *
     * 仅提供进制转换所需的移位、小整数乘加/除取余、比较与数字输出。
     */
    private class BigInt private constructor(private val limbs: IntArray) {

        val isZero: Boolean get() = limbs.isEmpty()

        val isEven: Boolean get() = limbs.isEmpty() || (limbs[0] and 1) == 0

        fun shiftLeft(bits: Int): BigInt {
            if (bits == 0 || limbs.isEmpty()) return this
            val limbShift = bits ushr 5
            val bitShift = bits and 31
            val result = IntArray(limbs.size + limbShift + 1)
            var carry = 0L
            for (index in limbs.indices) {
                val value = limbs[index].toLong() and LIMB_MASK
                val shifted = (value shl bitShift) or carry
                result[index + limbShift] = shifted.toInt()
                carry = if (bitShift == 0) 0L else value ushr (LIMB_BITS - bitShift)
            }
            result[limbs.size + limbShift] = carry.toInt()
            return BigInt(normalize(result))
        }

        fun shiftRight(bits: Int): BigInt {
            if (bits == 0 || limbs.isEmpty()) return this
            val limbShift = bits ushr 5
            if (limbShift >= limbs.size) return ZERO
            val bitShift = bits and 31
            val result = IntArray(limbs.size - limbShift)
            var carry = 0L
            for (index in limbs.size - 1 downTo limbShift) {
                val value = limbs[index].toLong() and LIMB_MASK
                result[index - limbShift] =
                    if (bitShift == 0) value.toInt() else ((value ushr bitShift) or carry).toInt()
                carry = if (bitShift == 0) {
                    0L
                } else {
                    (value and ((1L shl bitShift) - 1L)) shl (LIMB_BITS - bitShift)
                }
            }
            return BigInt(normalize(result))
        }

        fun maskLowBits(bits: Int): BigInt {
            if (bits <= 0 || limbs.isEmpty()) return ZERO
            val limbCount = (bits + LIMB_BITS - 1) ushr 5
            if (limbCount > limbs.size) return this
            val result = IntArray(limbCount)
            limbs.copyInto(result, 0, 0, limbCount)
            val extraBits = bits and 31
            if (extraBits != 0) {
                result[limbCount - 1] = result[limbCount - 1] and ((1 shl extraBits) - 1)
            }
            return BigInt(normalize(result))
        }

        fun multiplySmall(factor: Int): BigInt {
            if (factor == 0 || limbs.isEmpty()) return ZERO
            if (factor == 1) return this
            val result = IntArray(limbs.size + 1)
            var carry = 0L
            for (index in limbs.indices) {
                val product = (limbs[index].toLong() and LIMB_MASK) * factor + carry
                result[index] = product.toInt()
                carry = product ushr LIMB_BITS
            }
            result[limbs.size] = carry.toInt()
            return BigInt(normalize(result))
        }

        fun addSmall(value: Int): BigInt {
            if (value == 0) return this
            val result = IntArray(limbs.size + 1)
            limbs.copyInto(result)
            var carry = value.toLong() and LIMB_MASK
            var index = 0
            while (carry != 0L && index < result.size) {
                val sum = (result[index].toLong() and LIMB_MASK) + carry
                result[index] = sum.toInt()
                carry = sum ushr LIMB_BITS
                index++
            }
            return BigInt(normalize(result))
        }

        fun divModSmall(divisor: Int): Pair<BigInt, Int> {
            if (limbs.isEmpty()) return ZERO to 0
            val result = IntArray(limbs.size)
            var remainder = 0L
            for (index in limbs.size - 1 downTo 0) {
                val current = (remainder shl LIMB_BITS) or (limbs[index].toLong() and LIMB_MASK)
                result[index] = (current / divisor).toInt()
                remainder = current % divisor
            }
            return BigInt(normalize(result)) to remainder.toInt()
        }

        fun multiplyByPower(base: Int, exponent: Int): BigInt {
            if (exponent == 0 || limbs.isEmpty()) return this
            var result = this
            repeat(exponent) { result = result.multiplySmall(base) }
            return result
        }

        fun compareTo(other: BigInt): Int {
            if (limbs.size != other.limbs.size) return limbs.size.compareTo(other.limbs.size)
            for (index in limbs.size - 1 downTo 0) {
                val left = limbs[index].toLong() and LIMB_MASK
                val right = other.limbs[index].toLong() and LIMB_MASK
                if (left != right) return left.compareTo(right)
            }
            return 0
        }

        fun toIntValue(): Int {
            var value = 0L
            for (index in limbs.indices.reversed()) {
                value = (value shl LIMB_BITS) or (limbs[index].toLong() and LIMB_MASK)
            }
            return value.toInt()
        }

        fun toRadixString(radix: Int): String {
            if (limbs.isEmpty()) return "0"
            val builder = StringBuilder()
            var current: BigInt = this
            while (!current.isZero) {
                val (quotient, remainder) = current.divModSmall(radix)
                builder.append(DIGIT_CHARS[remainder])
                current = quotient
            }
            return builder.reverse().toString()
        }

        companion object {
            val ZERO = BigInt(IntArray(0))

            fun of(value: Long): BigInt {
                if (value == 0L) return ZERO
                val low = value.toInt()
                val high = (value ushr 32).toInt()
                return if (high == 0) BigInt(intArrayOf(low)) else BigInt(intArrayOf(low, high))
            }

            private fun normalize(values: IntArray): IntArray {
                var length = values.size
                while (length > 0 && values[length - 1] == 0) length--
                return if (length == values.size) values else values.copyOf(length)
            }
        }
    }
}
