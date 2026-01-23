package com.muedsa.js.runtime.value

import com.muedsa.js.createRuntime
import com.muedsa.js.runtime.exception.JSException
import kotlin.test.*


class JSObjectTest {

    @Test
    fun `JSObject should correctly store and retrieve properties`() {
        val props = mutableMapOf<String, JSValue>(
            "a" to JSObject(),
            "b" to JSString("b"),
        )
        val obj1 = JSObject(properties = props)
        val obj2 = JSObject(lazy { props })
        assertTrue(obj1.hasProperty("a"))
        assertTrue(obj2.hasProperty("b"))
        val obj1a = obj1.getProperty("a")
        val obj1b = obj1.getProperty("b")
        assertTrue(obj2.hasProperty("a"))
        assertTrue(obj2.hasProperty("b"))
        val obj2a = obj2.getProperty("a")
        val obj2b = obj2.getProperty("b")
        assertEquals(obj1a, obj2a)
        assertEquals(obj1b, obj2b)
    }

    @Test
    fun `JSObject should have toString method in prototype`() {
        val jsObject = JSObject()
        val callee = ObjectPrototype.getProperty("toString") as JSNativeFunction
        val result = callee.function(createRuntime(), jsObject, emptyList())
        assertIs<JSString>(result)
        assertEquals("[object Object]", result.value)
    }


    @Test
    fun `JSObject should convert to primitives correctly`() {
        val obj = JSObject()
        assertTrue(obj.toPrimitiveBoolean())
        assertEquals(Double.NaN, obj.toPrimitiveNumber())
        assertEquals("[object Object]", obj.toPrimitiveString())
    }

    @Test
    fun `JSObject should support property inheritance via prototype chain`() {
        val proto = JSObject(properties = mutableMapOf("a" to JSNumber(100.0)))
        val obj = JSObject(
            properties = mutableMapOf("b" to JSNumber(200.0)),
            prototype = proto
        )

        // Own property
        assertTrue(obj.hasProperty("b"))
        assertEquals(JSNumber(200.0), obj.getProperty("b"))

        // Inherited property
        assertFalse(obj.hasProperty("a"))
        assertEquals(JSNumber(100.0), obj.getProperty("a"))

        // Non-existent property
        assertFalse(obj.hasProperty("c"))
        assertEquals(JSUndefined, obj.getProperty("c"))

        // Set property
        obj.setProperty("c", JSString("hello"))
        assertTrue(obj.hasProperty("c"))
        assertEquals(JSString("hello"), obj.getProperty("c"))

        // Shadowing prototype property
        obj.setProperty("a", JSNumber(999.0))
        assertTrue(obj.hasProperty("a"))
        assertEquals(JSNumber(999.0), obj.getProperty("a"))
        assertEquals(JSNumber(100.0), proto.getProperty("a")) // prototype is unchanged
    }

    @Test
    fun `getOwnProperties should only return properties belonging to the object itself`() {
        val proto = JSObject(properties = mutableMapOf("a" to JSNumber(1.0)))
        val obj = JSObject(
            properties = mutableMapOf("b" to JSNumber(2.0)),
            prototype = proto
        )
        val ownProperties = obj.getOwnProperties()
        assertEquals(1, ownProperties.size)
        assertEquals(JSNumber(2.0), ownProperties["b"])
        assertFalse(ownProperties.containsKey("a"))
    }

    @Test
    fun `hasOwnProperty should correctly identify own vs inherited properties`() {
        val runtime = createRuntime()
        val proto = JSObject(properties = mutableMapOf("a" to JSNumber(1.0)))
        val obj = JSObject(
            properties = mutableMapOf("b" to JSNumber(2.0)),
            prototype = proto
        )

        val hasOwnProperty = ObjectPrototype.getProperty("hasOwnProperty")
        assertIs<JSNativeFunction>(hasOwnProperty)

        // Test own property
        var result = hasOwnProperty.function(runtime, obj, listOf(JSString("b")))
        assertIs<JSBoolean>(result)
        assertTrue(result.value)

        // Test inherited property
        result = hasOwnProperty.function(runtime, obj, listOf(JSString("a")))
        assertIs<JSBoolean>(result)
        assertFalse(result.value)

        // Test non-existent property
        result = hasOwnProperty.function(runtime, obj, listOf(JSString("c")))
        assertIs<JSBoolean>(result)
        assertFalse(result.value)
    }

    @Test
    fun `valueOf should return the object itself`() {
        val runtime = createRuntime()
        val obj = JSObject()
        val valueOf = ObjectPrototype.getProperty("valueOf")
        assertIs<JSNativeFunction>(valueOf)
        val result = valueOf.function(runtime, obj, emptyList())
        assertEquals(obj, result)
    }

    @Test
    fun `toString should use Symbol_toStringTag if present`() {
        val runtime = createRuntime()
        val obj = JSObject()
        obj.setProperty("[Symbol.toStringTag]", JSString("MyModule"))
        val toString = ObjectPrototype.getProperty("toString")
        assertIs<JSNativeFunction>(toString)
        val result = toString.function(runtime, obj, emptyList())
        assertIs<JSString>(result)
        assertEquals("[object MyModule]", result.value)
    }

    @Test
    fun `Object constructor should create JSObject instances correctly`() {
        val runtime = createRuntime()

        // Without arguments
        var result = ObjectConstructor.function(runtime, JSUndefined, emptyList())
        assertIs<JSObject>(result)

        // With null/undefined
        result = ObjectConstructor.function(runtime, JSUndefined, listOf(JSUndefined))
        assertIs<JSObject>(result)
        result = ObjectConstructor.function(runtime, JSUndefined, listOf(JSNull))
        assertIs<JSObject>(result)

        // With an object
        val obj = JSObject()
        result = ObjectConstructor.function(runtime, JSUndefined, listOf(obj))
        assertEquals(obj, result)

        // With a primitive (should create a new object)
        result = ObjectConstructor.function(runtime, JSUndefined, listOf(JSNumber(1.0)))
        assertIs<JSObject>(result)
    }

    @Test
    fun `convertJSValueToJSObject should convert JSValue to JSObject or throw TypeError`() {
        val obj = JSObject()
        assertEquals(obj, convertJSValueToJSObject(obj))

        val error = assertFailsWith<JSException> {
            convertJSValueToJSObject(JSUndefined)
        }
        assertNotNull(error.value)
        assertIs<JSError>(error.value)
        assertEquals("TypeError", error.value.getProperty("name").toPrimitiveString())
        assertEquals("Cannot convert undefined or null to object", error.value.getProperty("message").toPrimitiveString())

        assertFailsWith<JSException> {
            convertJSValueToJSObject(JSNull)
        }
    }
}