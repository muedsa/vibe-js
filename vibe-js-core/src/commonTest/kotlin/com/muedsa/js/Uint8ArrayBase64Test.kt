package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Uint8ArrayBase64Test {

    @Test
    fun `Uint8Array base64 与 hex 往返`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var decoded = Uint8Array.fromBase64('Zm9vYmFy');
            var encoded = decoded.toBase64();
            var url = new Uint8Array([199, 239, 242]).toBase64({ alphabet: 'base64url' });
            var noPadding = new Uint8Array([255]).toBase64({ omitPadding: true });
            var hex = Uint8Array.fromHex('666f6f');
            var hexText = hex.toHex();
            var target = new Uint8Array(5);
            var result = target.setFromBase64('Zm9vYmE=');
            var hexTarget = new Uint8Array(2);
            var hexResult = hexTarget.setFromHex('aabb');
            """.trimIndent()
        )
        assertEquals(6.0, runtime.evalNumber("decoded.length"))
        assertEquals(102.0, runtime.evalNumber("decoded[0]"))
        assertEquals(114.0, runtime.evalNumber("decoded[5]"))
        assertEquals("Zm9vYmFy", runtime.evalString("encoded"))
        assertEquals("x-_y", runtime.evalString("url"))
        assertEquals("/w", runtime.evalString("noPadding"))
        assertEquals("666f6f", runtime.evalString("hexText"))
        assertEquals(3.0, runtime.evalNumber("hex.length"))
        assertEquals(5.0, runtime.evalNumber("result.written"))
        assertEquals(8.0, runtime.evalNumber("result.read"))
        assertEquals(97.0, runtime.evalNumber("target[4]"))
        assertEquals(2.0, runtime.evalNumber("hexResult.written"))
        assertEquals(4.0, runtime.evalNumber("hexResult.read"))
    }

    @Test
    fun `Uint8Array base64 拒绝非字符串与非法输入`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "Uint8Array.fromBase64({ toString: function () { return 'Zg=='; } });",
            "Uint8Array.fromBase64('Zg==', { alphabet: 'other' });",
            "Uint8Array.fromBase64('Zg=;');",
        )) {
            assertFailsWith<JSException>(code) { runtime.eval(code) }
        }
        val invalidHex = assertFailsWith<JSException> { runtime.eval("Uint8Array.fromHex('abc');") }
        assertEquals("SyntaxError", (invalidHex.value as JSError).errorName)
    }
}
