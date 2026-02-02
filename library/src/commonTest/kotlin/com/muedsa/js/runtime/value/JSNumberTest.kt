package com.muedsa.js.runtime.value

import com.muedsa.js.createRuntime
import com.muedsa.js.runtime.exception.JSException
import kotlin.test.*

class JSNumberTest {

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
        val runtime = createRuntime()
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
        val runtime = createRuntime()
        val isNaN = NumberConstructor.getProperty("isNaN") as JSNativeFunction
        assertEquals(JSBoolean.True, isNaN.function(runtime, JSUndefined, listOf(JSNumber.NaN)))
        assertEquals(JSBoolean.True, isNaN.function(runtime, JSUndefined, listOf(JSString("abc"))))
        assertEquals(JSBoolean.False, isNaN.function(runtime, JSUndefined, listOf(JSNumber(123.0))))
        assertEquals(JSBoolean.False, isNaN.function(runtime, JSUndefined, listOf(JSString("123"))))
        assertEquals(JSBoolean.True, isNaN.function(runtime, JSUndefined, emptyList())) // undefined -> NaN -> true
    }

    @Test
    fun `Number_isFinite should correctly identify finite values`() {
        val runtime = createRuntime()
        val isFinite = NumberConstructor.getProperty("isFinite") as JSNativeFunction
        assertEquals(JSBoolean.True, isFinite.function(runtime, JSUndefined, listOf(JSNumber(123.0))))
        assertEquals(JSBoolean.True, isFinite.function(runtime, JSUndefined, listOf(JSString("123"))))
        assertEquals(JSBoolean.False, isFinite.function(runtime, JSUndefined, listOf(JSNumber.POSITIVE_INFINITY)))
        assertEquals(JSBoolean.False, isFinite.function(runtime, JSUndefined, listOf(JSNumber.NEGATIVE_INFINITY)))
        assertEquals(JSBoolean.False, isFinite.function(runtime, JSUndefined, listOf(JSNumber.NaN)))
    }

    @Test
    fun `Number_isInteger should correctly identify integer values`() {
        val runtime = createRuntime()
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
        val runtime = createRuntime()
        val parseInt = NumberConstructor.getProperty("parseInt") as JSNativeFunction
        var result = parseInt.function(runtime, JSUndefined, listOf(JSString("123.45")))
        assertIs<JSNumber>(result)
        assertEquals(123.0, result.value)

        result = parseInt.function(runtime, JSUndefined, listOf(JSString("  123 kg")))
        assertIs<JSNumber>(result)
        assertEquals(123.0, result.value)

        result = parseInt.function(runtime, JSUndefined, listOf(JSString("12.99")))
        assertIs<JSNumber>(result)
        assertEquals(12.0, result.value)

        result = parseInt.function(runtime, JSUndefined, listOf(JSString("kg123")))
        assertIs<JSNumber>(result)
        assertTrue(result.value.isNaN())

        result = parseInt.function(runtime, JSUndefined, listOf(JSString("")))
        assertIs<JSNumber>(result)
        assertTrue(result.value.isNaN())
    }

    @Test
    fun `Number_parseFloat should parse floats correctly from strings`() {
        val runtime = createRuntime()
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
