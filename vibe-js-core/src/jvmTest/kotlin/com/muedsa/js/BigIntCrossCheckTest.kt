package com.muedsa.js

import com.muedsa.js.runtime.value.JSBigInt
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * JSBigInt 与 JDK BigInteger 的差分交叉验证（仅 JVM 测试）。
 *
 * 核心代码禁止依赖 java.math，但测试可以用它作为参照实现，
 * 用随机大数覆盖加减乘除、位运算、移位、进制转换、asIntN/asUintN 与 toDouble 舍入。
 */
class BigIntCrossCheckTest {

    private val random = Random(20261107)

    /** 将 JDK BigInteger 转成 JSBigInt（十进制字符串往返顺带验证 toString/parse）。 */
    private fun toJs(value: BigInteger): JSBigInt =
        assertNotNull(JSBigInt.parse(value.toString()), "解析失败：$value")

    /** 将 JSBigInt 转回 JDK BigInteger 以便比较。 */
    private fun jsToBig(value: JSBigInt): BigInteger = BigInteger(value.toString(10))

    /** 生成随机符号、随机位宽的大整数。 */
    private fun randomBigInt(maxBits: Int): BigInteger {
        val bits = random.nextInt(1, maxBits + 1)
        val bytes = ByteArray((bits + 7) / 8)
        random.nextBytes(bytes)
        val positive = BigInteger(1, bytes).mod(BigInteger.ONE.shiftLeft(bits))
        return if (random.nextBoolean()) positive.negate() else positive
    }

    @Test
    fun `随机大整数四则运算与 JDK BigInteger 一致`() {
        repeat(80) {
            val left = randomBigInt(1024)
            val right = randomBigInt(512)
            val jsLeft = toJs(left)
            val jsRight = toJs(right)

            assertEquals(left + right, jsToBig(jsLeft.add(jsRight)))
            assertEquals(left - right, jsToBig(jsLeft.subtract(jsRight)))
            assertEquals(left * right, jsToBig(jsLeft.multiply(jsRight)))
            assertEquals(
                left.compareTo(right),
                jsLeft.compareTo(jsRight).let { if (it < 0) -1 else if (it > 0) 1 else 0 },
            )
            if (right.signum() != 0) {
                assertEquals(left / right, jsToBig(jsLeft.divide(jsRight)))
                assertEquals(left % right, jsToBig(jsLeft.remainder(jsRight)))
            }
        }
    }

    @Test
    fun `随机大整数位运算与移位和 JDK BigInteger 一致`() {
        repeat(80) {
            val left = randomBigInt(768)
            val right = randomBigInt(768)
            val jsLeft = toJs(left)
            val jsRight = toJs(right)

            assertEquals(left.and(right), jsToBig(jsLeft.and(jsRight)))
            assertEquals(left.or(right), jsToBig(jsLeft.or(jsRight)))
            assertEquals(left.xor(right), jsToBig(jsLeft.xor(jsRight)))
            assertEquals(left.not(), jsToBig(jsLeft.not()))

            val shift = random.nextInt(-300, 301)
            val expected = left.shiftLeft(shift)
            val actual = jsLeft.shiftLeft(shift.toLong())
            assertNotNull(actual, "移位结果不应超限：$shift")
            assertEquals(expected, jsToBig(actual))
        }
    }

    @Test
    fun `随机大整数进制转换与 JDK BigInteger 一致`() {
        repeat(40) {
            val value = randomBigInt(512)
            val jsValue = toJs(value)
            for (radix in listOf(2, 3, 8, 10, 16, 26, 36)) {
                assertEquals(value.toString(radix), jsValue.toString(radix))
            }
            // 十进制字符串解析往返
            assertEquals(value.toString(), jsValue.toString(10))
        }
    }

    @Test
    fun `随机大整数幂运算与 JDK BigInteger 一致`() {
        repeat(30) {
            val base = randomBigInt(64)
            val exponent = random.nextInt(0, 12)
            val expected = base.pow(exponent)
            val actual = toJs(base).pow(JSBigInt.of(exponent))
            assertNotNull(actual, "幂结果不应超限")
            assertEquals(expected, jsToBig(actual))
        }
    }

    @Test
    fun `随机大整数 toDouble 按最近偶数舍入`() {
        repeat(100) {
            val value = randomBigInt(1200)
            val expected = BigDecimal(value).toDouble()
            assertEquals(expected, toJs(value).toDouble(), "value=$value")
        }
        // 边界值：2^53 ± 1、Double.MAX_VALUE 与溢出为 Infinity
        assertEquals(
            BigDecimal(BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE)).toDouble(),
            toJs(BigInteger.ONE.shiftLeft(53).add(BigInteger.ONE)).toDouble(),
        )
        val maxFinite = BigInteger.ONE.shiftLeft(53).subtract(BigInteger.ONE).shiftLeft(971)
        assertEquals(Double.MAX_VALUE, toJs(maxFinite).toDouble())
        assertEquals(
            Double.POSITIVE_INFINITY,
            toJs(BigInteger.ONE.shiftLeft(1024).subtract(BigInteger.ONE)).toDouble(),
        )
    }

    @Test
    fun `随机 asIntN 与 asUintN 与模运算一致`() {
        repeat(80) {
            val value = randomBigInt(400)
            val bits = random.nextInt(0, 200)
            val modulus = BigInteger.ONE.shiftLeft(bits)
            val jsValue = toJs(value)
            val unsigned = jsValue.asUintN(bits.toLong())
            assertNotNull(unsigned)
            assertEquals(value.mod(modulus), jsToBig(unsigned))
            val signed = jsValue.asIntN(bits.toLong())
            assertNotNull(signed)
            val expectedSigned = if (bits == 0) {
                BigInteger.ZERO
            } else if (value.mod(modulus) >= BigInteger.ONE.shiftLeft(bits - 1)) {
                value.mod(modulus).subtract(modulus)
            } else {
                value.mod(modulus)
            }
            assertEquals(expectedSigned, jsToBig(signed))
        }
    }
}
