package com.muedsa.js.runtime.value

import kotlin.test.Test
import kotlin.test.assertEquals

class JSNullTest {

    @Test
    fun `JSNull should convert to primitive boolean as false`() {
        assertEquals(false, JSNull.toPrimitiveBoolean())
    }

    @Test
    fun `JSNull should convert to primitive number as 0`() {
        assertEquals(0.0, JSNull.toPrimitiveNumber())
    }

    @Test
    fun `JSNull should convert to primitive string as 'null'`() {
        assertEquals("null", JSNull.toPrimitiveString())
    }
}
