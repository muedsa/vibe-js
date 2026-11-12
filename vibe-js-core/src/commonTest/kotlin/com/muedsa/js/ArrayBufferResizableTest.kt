package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArrayBufferResizableTest {

    @Test
    fun `resizable ArrayBuffer 构造与 resize`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var buffer = new ArrayBuffer(4, { maxByteLength: 8 });
            var view = new Uint8Array(buffer);
            view[0] = 1;
            buffer.resize(8);
            var grewLength = new Uint8Array(buffer).length;
            var grewZero = new Uint8Array(buffer)[4];
            buffer.resize(2);
            var shrunk = new Uint8Array(buffer);
            """.trimIndent()
        )
        assertTrue(runtime.evalBoolean("buffer.resizable"))
        assertEquals(2.0, runtime.evalNumber("buffer.byteLength"))
        assertEquals(8.0, runtime.evalNumber("buffer.maxByteLength"))
        assertEquals(1.0, runtime.evalNumber("shrunk[0]"))
        assertEquals(8.0, runtime.evalNumber("grewLength"))
        assertEquals(0.0, runtime.evalNumber("grewZero"))
    }

    @Test
    fun `固定长度 ArrayBuffer 拒绝 resize`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval("new ArrayBuffer(4).resize(2);")
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }
}
