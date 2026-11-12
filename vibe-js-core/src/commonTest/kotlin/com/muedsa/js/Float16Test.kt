package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Float16Test {

    @Test
    fun `Float16Array 与 DataView 使用 binary16 位模式`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var array = new Float16Array([1.5, -0, 65504, 65520, Infinity]);
            var buffer = new ArrayBuffer(2);
            var view = new DataView(buffer);
            view.setFloat16(0, 1.5, true);
            var littleEndianValue = view.getFloat16(0, true);
            var bits = new Uint16Array(buffer)[0];
            var rounded = Math.f16round(1.1);
            """.trimIndent()
        )
        assertEquals(1.5, runtime.evalNumber("array[0]"))
        assertTrue(runtime.evalBoolean("Object.is(array[1], -0)"))
        assertEquals(65504.0, runtime.evalNumber("array[2]"))
        assertTrue(runtime.evalBoolean("array[3] === Infinity"))
        assertTrue(runtime.evalBoolean("array[4] === Infinity"))
        assertEquals(1.5, runtime.evalNumber("littleEndianValue"))
        assertEquals(0x3E00.toDouble(), runtime.evalNumber("bits"))
        assertEquals(1.099609375, runtime.evalNumber("rounded"))
    }
}
