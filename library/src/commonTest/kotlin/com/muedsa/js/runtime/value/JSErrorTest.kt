package com.muedsa.js.runtime.value

import com.muedsa.js.createRuntime
import com.muedsa.js.runtime.exception.JSException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JSErrorTest {

    @Test
    fun `JSError should store name and message properties`() {
        val error = JSError("TypeError", "Something went wrong")
        assertEquals(JSString("TypeError"), error.getProperty("name"))
        assertEquals(JSString("Something went wrong"), error.getProperty("message"))
    }

    @Test
    fun `JSError should convert to primitive string correctly`() {
        val error = JSError("Error", "msg")
        assertEquals("Error: msg", error.toPrimitiveString())
    }

    @Test
    fun `JSError should handle non-string name and message properties`() {
        val error = JSError("Error", "msg")
        error.setProperty("name", JSNumber(123.0))
        error.setProperty("message", JSBoolean.True)
        // toPrimitiveString implementation uses property values directly if they were updated in properties map?
        // JSError.toPrimitiveString() is override fun toPrimitiveString() = "$name: $message"
        // It uses the constructor properties, not the dynamic properties map.
        // Wait, JSError is a data class, so name/message are immutable properties of the class.
        // But JSObject.properties is mutable.
        // Let's check JSError implementation.
        // data class JSError(val name: String, val message: String) : JSObject(...)
        // override fun toPrimitiveString() = "$name: $message"
        // So changing "name" property in the map won't change toPrimitiveString() result because it uses the kotlin property.
        // However, Error.prototype.toString() uses getProperty("name").

        // This test validates that distinction or behavior.
        assertEquals("Error: msg", error.toPrimitiveString())
    }

    @Test
    fun `Error constructor should create JSError instances correctly`() {
        val runtime = createRuntime()

        // No args
        var result = ErrorConstructor.function(runtime, JSUndefined, emptyList())
        assertIs<JSError>(result)
        assertEquals("Error", result.name)
        assertEquals("", result.message)

        // With message
        result = ErrorConstructor.function(runtime, JSUndefined, listOf(JSString("fail")))
        assertIs<JSError>(result)
        assertEquals("Error", result.name)
        assertEquals("fail", result.message)
    }

    @Test
    fun `Error_prototype_toString should format name and message correctly`() {
        val runtime = createRuntime()
        val toString = ErrorPrototype.getProperty("toString") as JSNativeFunction

        // Normal error
        val error = JSError("MyError", "oops")
        assertEquals(JSString("MyError: oops"), toString.function(runtime, error, emptyList()))

        // Missing message
        error.setProperty("message", JSString(""))
        assertEquals(JSString("MyError"), toString.function(runtime, error, emptyList()))

        // Missing name
        error.setProperty("message", JSString("oops"))
        error.setProperty("name", JSString(""))
        assertEquals(JSString("oops"), toString.function(runtime, error, emptyList()))

        // Incompatible receiver
        assertFailsWith<JSException> {
            toString.function(runtime, JSNumber(1.0), emptyList())
        }
    }
}
