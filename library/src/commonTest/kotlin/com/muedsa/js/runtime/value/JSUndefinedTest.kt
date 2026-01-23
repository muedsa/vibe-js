package com.muedsa.js.runtime.value

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JSUndefinedTest {

    @Test
    fun `JSUndefined should convert to primitive boolean as false`() {
        assertEquals(false, JSUndefined.toPrimitiveBoolean())
    }

    @Test
    fun `JSUndefined should convert to primitive number as NaN`() {
        assertTrue(JSUndefined.toPrimitiveNumber().isNaN())
    }

    @Test
    fun `JSUndefined should convert to primitive string as 'undefined'`() {
        assertEquals("undefined", JSUndefined.toPrimitiveString())
    }
}
