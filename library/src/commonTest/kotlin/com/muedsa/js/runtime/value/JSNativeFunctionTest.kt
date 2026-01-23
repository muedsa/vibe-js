package com.muedsa.js.runtime.value

import com.muedsa.js.createRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JSNativeFunctionTest {

    @Test
    fun `JSNativeFunction should convert to primitives correctly`() {
        val func = JSNativeFunction(name = "test") { _, _, _ -> JSUndefined }
        
        assertEquals(true, func.toPrimitiveBoolean())
        assertTrue(func.toPrimitiveNumber().isNaN())
        assertEquals("function test() { [native code] }", func.toPrimitiveString())
    }

    @Test
    fun `JSNativeFunction should be executable and return values`() {
        val runtime = createRuntime()
        var called = false
        val func = JSNativeFunction(name = "test") { _, _, _ -> 
            called = true
            JSNumber(42.0)
        }
        
        val result = func.function(runtime, JSUndefined, emptyList())
        assertTrue(called)
        assertEquals(JSNumber(42.0), result)
    }
}
