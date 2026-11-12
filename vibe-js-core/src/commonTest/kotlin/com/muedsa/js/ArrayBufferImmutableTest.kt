package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArrayBufferImmutableTest {

    @Test
    fun `immutable ArrayBuffer 由 transferToImmutable 与 sliceToImmutable 创建`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var source = new ArrayBuffer(4);
            var sourceView = new Uint8Array(source);
            sourceView[0] = 1;
            sourceView[1] = 2;
            sourceView[2] = 3;
            sourceView[3] = 4;
            var immutable = source.transferToImmutable();
            var sliced = new Uint8Array([9, 8, 7, 6]).buffer.sliceToImmutable(1, 3);
            var slicedView = new Uint8Array(sliced);
            """.trimIndent()
        )
        assertTrue(runtime.evalBoolean("immutable.immutable"))
        assertTrue(runtime.evalBoolean("source.detached"))
        assertEquals(4.0, runtime.evalNumber("immutable.byteLength"))
        assertEquals(1.0, runtime.evalNumber("new Uint8Array(immutable)[0]"))
        assertEquals(4.0, runtime.evalNumber("new Uint8Array(immutable)[3]"))
        assertTrue(runtime.evalBoolean("sliced.immutable"))
        assertEquals(2.0, runtime.evalNumber("sliced.byteLength"))
        assertEquals(8.0, runtime.evalNumber("slicedView[0]"))
        assertEquals(7.0, runtime.evalNumber("slicedView[1]"))
    }

    @Test
    fun `immutable ArrayBuffer 拒绝所有写入与再次 transfer`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "(function () { var b = new ArrayBuffer(1).transferToImmutable(); new Uint8Array(b)[0] = 1; })();",
            "(function () { var b = new ArrayBuffer(1).transferToImmutable(); new DataView(b).setUint8(0, 1); })();",
            "(function () { var b = new ArrayBuffer(1).transferToImmutable(); b.transfer(); })();",
            "(function () { var b = new ArrayBuffer(1).transferToImmutable(); b.transferToImmutable(); })();",
        )) {
            assertFailsWith<JSException>(code) { runtime.eval(code) }
        }
    }
}
