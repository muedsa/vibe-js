package com.muedsa.js.runtime.value

import com.muedsa.js.createRuntime
import com.muedsa.js.runtime.exception.JSException
import kotlin.test.*

class JSNumberTest {

    private val runtime = createRuntime()
    private val parseInt = NumberConstructor.getProperty("parseInt") as JSNativeFunction

    @Test
    fun `JSNumber should convert to primitive boolean correctly`() {
        assertEquals(true, JSNumber(1.0).toPrimitiveBoolean())
        assertEquals(true, JSNumber(-1.0).toPrimitiveBoolean())
        assertEquals(false, JSNumber(0.0).toPrimitiveBoolean())
        assertEquals(false, JSNumber(-0.0).toPrimitiveBoolean())
        assertEquals(false, JSNumber.NaN.toPrimitiveBoolean())
    }

    @Test
    fun `JSNumber should convert to primitive number correctly`() {
        assertEquals(123.45, JSNumber(123.45).toPrimitiveNumber())
        assertTrue(JSNumber.NaN.toPrimitiveNumber().isNaN())
    }

    @Test
    fun `JSNumber should convert to primitive string correctly`() {
        assertEquals("123", JSNumber(123.0).toPrimitiveString())
        assertEquals("123.45", JSNumber(123.45).toPrimitiveString())
        assertEquals("Infinity", JSNumber.POSITIVE_INFINITY.toPrimitiveString())
        assertEquals("-Infinity", JSNumber.NEGATIVE_INFINITY.toPrimitiveString())
        assertEquals("NaN", JSNumber.NaN.toPrimitiveString())
    }

    @Test
    fun `Number constructor should create JSNumber instances correctly`() {
        // No args
        var result = NumberConstructor.function(runtime, JSUndefined, emptyList())
        assertIs<JSNumber>(result)
        assertEquals(0.0, result.value)

        // With arg
        result = NumberConstructor.function(runtime, JSUndefined, listOf(JSString("  123.45  ")))
        assertIs<JSNumber>(result)
        assertEquals(123.45, result.value)

        // Null -> 0
        result = NumberConstructor.function(runtime, JSUndefined, listOf(JSNull))
        assertIs<JSNumber>(result)
        assertEquals(0.0, result.value)

        // true -> 1
        result = NumberConstructor.function(runtime, JSUndefined, listOf(JSBoolean.True))
        assertIs<JSNumber>(result)
        assertEquals(1.0, result.value)

        // false -> 0
        result = NumberConstructor.function(runtime, JSUndefined, listOf(JSBoolean.False))
        assertIs<JSNumber>(result)
        assertEquals(0.0, result.value)
    }

    @Test
    fun `Number constructor should have correct static properties`() {
        assertEquals(JSNumber.MAX_VALUE, NumberConstructor.getProperty("MAX_VALUE"))
        assertEquals(JSNumber.MIN_VALUE, NumberConstructor.getProperty("MIN_VALUE"))
        assertEquals(JSNumber.POSITIVE_INFINITY, NumberConstructor.getProperty("POSITIVE_INFINITY"))
        assertEquals(JSNumber.NEGATIVE_INFINITY, NumberConstructor.getProperty("NEGATIVE_INFINITY"))
        assertTrue((NumberConstructor.getProperty("NaN") as JSNumber).value.isNaN())
    }

    @Test
    fun `Number_isNaN should correctly identify NaN values`() {
        val isNaN = NumberConstructor.getProperty("isNaN") as JSNativeFunction
        assertEquals(JSBoolean.True, isNaN.function(runtime, JSUndefined, listOf(JSNumber.NaN)))
        assertEquals(JSBoolean.True, isNaN.function(runtime, JSUndefined, listOf(JSString("abc"))))
        assertEquals(JSBoolean.False, isNaN.function(runtime, JSUndefined, listOf(JSNumber(123.0))))
        assertEquals(JSBoolean.False, isNaN.function(runtime, JSUndefined, listOf(JSString("123"))))
        assertEquals(JSBoolean.True, isNaN.function(runtime, JSUndefined, emptyList())) // undefined -> NaN -> true
    }

    @Test
    fun `Number_isFinite should correctly identify finite values`() {
        val isFinite = NumberConstructor.getProperty("isFinite") as JSNativeFunction
        assertEquals(JSBoolean.True, isFinite.function(runtime, JSUndefined, listOf(JSNumber(123.0))))
        assertEquals(JSBoolean.True, isFinite.function(runtime, JSUndefined, listOf(JSString("123"))))
        assertEquals(JSBoolean.False, isFinite.function(runtime, JSUndefined, listOf(JSNumber.POSITIVE_INFINITY)))
        assertEquals(JSBoolean.False, isFinite.function(runtime, JSUndefined, listOf(JSNumber.NEGATIVE_INFINITY)))
        assertEquals(JSBoolean.False, isFinite.function(runtime, JSUndefined, listOf(JSNumber.NaN)))
    }

    @Test
    fun `Number_isInteger should correctly identify integer values`() {
        val isInteger = NumberConstructor.getProperty("isInteger") as JSNativeFunction
        assertEquals(JSBoolean.True, isInteger.function(runtime, JSUndefined, listOf(JSNumber(123.0))))
        assertEquals(JSBoolean.False, isInteger.function(runtime, JSUndefined, listOf(JSNumber(123.4))))
        assertEquals(JSBoolean.False, isInteger.function(runtime, JSUndefined, listOf(JSString("123")))) // spec says false for non-numbers
        assertEquals(JSBoolean.False, isInteger.function(runtime, JSUndefined, listOf(JSNumber.POSITIVE_INFINITY)))
        assertEquals(JSBoolean.True, isInteger.function(runtime, JSUndefined, listOf(JSNumber(-10.0))))
        assertEquals(JSBoolean.True, isInteger.function(runtime, JSUndefined, listOf(JSNumber(0.0))))
    }

    @Test
    fun `Number_parseInt should parse integers correctly from strings`() {
        // Basic base 10
        checkParseInt("123", null, 123.0)
        checkParseInt("  123", null, 123.0)
        checkParseInt("123  ", null, 123.0)
        checkParseInt("+123", null, 123.0)
        checkParseInt("-123", null, -123.0)
        checkParseInt("123.45", null, 123.0)
        checkParseInt("123abc", null, 123.0)
        checkParseInt("  123 kg", null, 123.0)

        // Hex detection
        checkParseInt("0x10", null, 16.0)
        checkParseInt("0X10", null, 16.0)
        checkParseInt("  0x10", null, 16.0)
        checkParseInt("0xABC", null, 2748.0)
        checkParseInt("-0x10", null, -16.0)

        // Explicit radix
        checkParseInt("10", 10, 10.0)
        checkParseInt("10", 2, 2.0)
        checkParseInt("10", 8, 8.0)
        checkParseInt("10", 16, 16.0)
        checkParseInt("10", 36, 36.0)
        checkParseInt("Z", 36, 35.0)
        checkParseInt("z", 36, 35.0)

        // Radix 0 or missing
        checkParseInt("10", 0, 10.0)
        checkParseInt("0x10", 0, 16.0)

        // Invalid radix
        checkParseInt("10", 1, Double.NaN)
        checkParseInt("10", 37, Double.NaN)
        checkParseInt("10", -1, Double.NaN)

        // Stops at invalid char
        checkParseInt("1012", 2, 5.0)
        checkParseInt("123", 2, 1.0)
        checkParseInt("3", 2, Double.NaN)

        // Whitespace and sign
        checkParseInt("   -10", 10, -10.0)
        checkParseInt(" \t \n 11", 2, 3.0)

        // Empty and junk
        checkParseInt("", null, Double.NaN)
        checkParseInt("kg123", null, Double.NaN)
    }

    private fun checkParseInt(input: String, radix: Int?, expected: Double) {
        val args = mutableListOf<JSValue>(JSString(input))
        if (radix != null) {
            args.add(JSNumber(radix.toDouble()))
        }
        val res = parseInt.function(runtime, JSUndefined, args)
        assertIs<JSNumber>(res)
        if (expected.isNaN()) {
            assertTrue(res.value.isNaN(), "Expected NaN for input '$input' radix $radix, but got ${res.value}")
        } else {
            assertEquals(expected, res.value, "Failed for input '$input' radix $radix")
        }
    }

    @Test
    fun `Number_parseFloat should parse floats correctly from strings`() {
        val parseFloat = NumberConstructor.getProperty("parseFloat") as JSNativeFunction
        val result = parseFloat.function(runtime, JSUndefined, listOf(JSString("123.45")))
        assertIs<JSNumber>(result)
        assertEquals(123.45, result.value)
    }

    @Test
    fun `Number_prototype_toString should return correct string representation`() {
        val runtime = createRuntime()
        val toString = NumberPrototype.getProperty("toString") as JSNativeFunction
        val result = toString.function(runtime, JSNumber(123.0), emptyList())
        assertIs<JSString>(result)
        assertEquals("123", result.value)
    }

    @Test
    fun `convertJSValueToJSNumber should convert JSValue to JSNumber or throw TypeError`() {
        val num = JSNumber(1.0)
        assertEquals(num, convertJSValueToJSNumber(num, "test"))
        assertFailsWith<JSException> {
            convertJSValueToJSNumber(JSString("foo"), "test")
        }
    }
}
