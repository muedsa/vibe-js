package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSDataView
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

    @Test
    fun `JSDataView 宿主构造拒绝越界窗口`() {
        val buffer = JSArrayBuffer(null, 8)
        assertFailsWith<IllegalArgumentException> {
            JSDataView(null, buffer, 0, 9)
        }
        assertFailsWith<IllegalArgumentException> {
            JSDataView(null, buffer, -1, 1)
        }
        JSDataView(null, buffer, 8, 0)
    }

    @Test
    fun `JSArrayBuffer 宿主构造与 resize 不变量`() {
        assertFailsWith<IllegalArgumentException> {
            JSArrayBuffer(null, 4, maxByteLength = 3, isResizable = true)
        }
        assertFailsWith<IllegalArgumentException> {
            JSArrayBuffer(null, -1)
        }

        val fixed = JSArrayBuffer(null, 4)
        assertFailsWith<IllegalStateException> {
            fixed.resize(2)
        }

        val resizable = JSArrayBuffer(null, 4, maxByteLength = 8, isResizable = true)
        assertFailsWith<IllegalArgumentException> {
            resizable.resize(-1)
        }
        assertFailsWith<IllegalArgumentException> {
            resizable.resize(9)
        }
    }
}
