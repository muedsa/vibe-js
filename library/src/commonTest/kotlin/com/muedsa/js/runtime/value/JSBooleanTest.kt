package com.muedsa.js.runtime.value

import com.muedsa.js.createRuntime
import com.muedsa.js.runtime.exception.JSException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JSBooleanTest {

    @Test
    fun `JSBoolean should hold correct boolean values`() {
        assertEquals(true, JSBoolean.True.value)
        assertEquals(false, JSBoolean.False.value)
    }

    @Test
    fun `JSBoolean should convert to primitive boolean correctly`() {
        assertEquals(true, JSBoolean.True.toPrimitiveBoolean())
        assertEquals(false, JSBoolean.False.toPrimitiveBoolean())
    }

    @Test
    fun `JSBoolean should convert to primitive number correctly`() {
        assertEquals(1.0, JSBoolean.True.toPrimitiveNumber())
        assertEquals(0.0, JSBoolean.False.toPrimitiveNumber())
    }

    @Test
    fun `JSBoolean should convert to primitive string correctly`() {
        assertEquals("true", JSBoolean.True.toPrimitiveString())
        assertEquals("false", JSBoolean.False.toPrimitiveString())
    }

    @Test
    fun `getJsBoolean should return singleton instances`() {
        assertEquals(JSBoolean.True, JSBoolean.getJsBoolean(true))
        assertEquals(JSBoolean.False, JSBoolean.getJsBoolean(false))
    }

    @Test
    fun `Boolean_prototype_toString should return primitive string value`() {
        val runtime = createRuntime()
        val toString = BooleanPrototype.getProperty("toString")
        assertIs<JSNativeFunction>(toString)

        var result = toString.function(runtime, JSBoolean.True, emptyList())
        assertIs<JSString>(result)
        assertEquals("true", result.value)

        result = toString.function(runtime, JSBoolean.False, emptyList())
        assertIs<JSString>(result)
        assertEquals("false", result.value)

        assertFailsWith<JSException> {
            toString.function(runtime, JSString("not a boolean"), emptyList())
        }
    }

    @Test
    fun `Boolean constructor should correctly convert various types to boolean`() {
        val runtime = createRuntime()

        // Truthy values
        var result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSNumber(1.0)))
        assertEquals(JSBoolean.True, result)
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSString("hello")))
        assertEquals(JSBoolean.True, result)
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSObject()))
        assertEquals(JSBoolean.True, result)
        // "0" is truthy
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSString("0")))
        assertEquals(JSBoolean.True, result)
        // "false" is truthy
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSString("false")))
        assertEquals(JSBoolean.True, result)


        // Falsy values
        result = BooleanConstructor.function(runtime, JSUndefined, emptyList())
        assertEquals(JSBoolean.False, result)
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSUndefined))
        assertEquals(JSBoolean.False, result)
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSNull))
        assertEquals(JSBoolean.False, result)
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSNumber(0.0)))
        assertEquals(JSBoolean.False, result)
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSString.EmptyString))
        assertEquals(JSBoolean.False, result)
        result = BooleanConstructor.function(runtime, JSUndefined, listOf(JSNumber.NaN))
        assertEquals(JSBoolean.False, result)
    }
}
