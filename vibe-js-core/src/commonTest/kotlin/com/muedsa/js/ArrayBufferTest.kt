package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * L3.1 ArrayBuffer + DataView 语义测试。
 *
 * 覆盖构造/长度、非法长度、slice 拷贝与 species、isView、DataView 八种
 * 数值类型的读写、大小端、越界 RangeError、访问器、品牌检查与原型链。
 */
class ArrayBufferTest {

    @Test
    fun `ArrayBuffer 构造与 byteLength 遵循 ToIndex`() = runTest {
        val runtime = TestRuntime()
        assertEquals(8.0, runtime.evalNumber("new ArrayBuffer(8).byteLength"))
        assertEquals(0.0, runtime.evalNumber("new ArrayBuffer().byteLength"))
        assertEquals(0.0, runtime.evalNumber("new ArrayBuffer(undefined).byteLength"))
        // ToIndex：向零截断、NaN 归零、字符串转数字、-0.5 截断为 -0 后归零
        assertEquals(2.0, runtime.evalNumber("new ArrayBuffer(2.9).byteLength"))
        assertEquals(0.0, runtime.evalNumber("new ArrayBuffer(NaN).byteLength"))
        assertEquals(3.0, runtime.evalNumber("new ArrayBuffer('3').byteLength"))
        assertEquals(0.0, runtime.evalNumber("new ArrayBuffer(-0.5).byteLength"))
        // 构造器 length 是 1
        assertEquals(1.0, runtime.evalNumber("ArrayBuffer.length"))
    }

    @Test
    fun `ArrayBuffer 非法长度抛 RangeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "new ArrayBuffer(-1);",
            "new ArrayBuffer(-Infinity);",
            "new ArrayBuffer(Infinity);",
            "new ArrayBuffer(9007199254740992);",
        )) {
            val error = assertFailsWith<JSException> { runtime.eval(code) }
            assertEquals("RangeError", (error.value as JSError).errorName, code)
        }
    }

    @Test
    fun `ArrayBuffer 不带 new 调用抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> { runtime.eval("ArrayBuffer(8);") }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `ArrayBuffer slice 返回独立副本并按范围归一`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var source = new ArrayBuffer(6);
            var sourceView = new DataView(source);
            var i;
            for (i = 0; i < 6; i++) { sourceView.setUint8(i, (i + 1) * 10); }
            var copy = source.slice(1, 4);
            var copyView = new DataView(copy);
            var bytes = [
                copyView.getUint8(0), copyView.getUint8(1), copyView.getUint8(2)
            ].join(',');
            copyView.setUint8(0, 99);
            var sourceUntouched = sourceView.getUint8(1);
            var fullLength = source.slice().byteLength;
            var tailLength = source.slice(-2).byteLength;
            var reversedLength = source.slice(4, 1).byteLength;
            [copy.byteLength, bytes, sourceUntouched, fullLength, tailLength, reversedLength].join('|');
            """.trimIndent()
        )
        assertEquals("3|20,30,40|20|6|2|0", result)
    }

    @Test
    fun `ArrayBuffer slice 使用 SpeciesConstructor 创建结果`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var source = new ArrayBuffer(4);
            var sourceView = new DataView(source);
            sourceView.setUint8(0, 1);
            sourceView.setUint8(1, 2);
            sourceView.setUint8(2, 3);
            sourceView.setUint8(3, 4);
            var speciesHolder = {};
            speciesHolder[Symbol.species] = function (length) {
                return new ArrayBuffer(length + 1);
            };
            source.constructor = speciesHolder;
            var copy = source.slice(1, 3);
            var copyView = new DataView(copy);
            [copy.byteLength, copyView.getUint8(0), copyView.getUint8(1), copyView.getUint8(2)].join(',');
            """.trimIndent()
        )
        // species 返回更长的缓冲区：保留其长度，只拷贝 newLen 个字节
        assertEquals("3,2,3,0", result)
    }

    @Test
    fun `ArrayBuffer isView 仅识别视图对象`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(8);
            var view = new DataView(buffer);
            [
                ArrayBuffer.isView(view),
                ArrayBuffer.isView(buffer),
                ArrayBuffer.isView({}),
                ArrayBuffer.isView()
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,false,false,false", result)
    }

    @Test
    fun `ArrayBuffer Symbol species 返回构造器自身`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("ArrayBuffer[Symbol.species] === ArrayBuffer"))
    }

    @Test
    fun `DataView 读写八种数值类型`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(26);
            var view = new DataView(buffer);
            view.setInt8(0, -128);
            view.setUint8(1, 255);
            view.setInt16(2, -32768);
            view.setUint16(4, 65535);
            view.setInt32(6, -2147483648);
            view.setUint32(10, 4294967295);
            view.setFloat32(14, 1.5);
            view.setFloat64(18, Math.PI);
            [
                view.getInt8(0),
                view.getUint8(1),
                view.getInt16(2),
                view.getUint16(4),
                view.getInt32(6),
                view.getUint32(10),
                view.getFloat32(14),
                view.getFloat64(18)
            ].join(',');
            """.trimIndent()
        )
        assertEquals("-128,255,-32768,65535,-2147483648,4294967295,1.5,3.141592653589793", result)
    }

    @Test
    fun `DataView 按 littleEndian 参数组合字节`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(24);
            var view = new DataView(buffer);
            view.setUint32(0, 0x11223344);
            view.setUint32(4, 0x11223344, true);
            view.setFloat64(8, 1.5);
            view.setFloat64(16, 1.5, true);
            var bytes = [
                view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3),
                view.getUint8(4), view.getUint8(5), view.getUint8(6), view.getUint8(7)
            ].join(',');
            var words = [
                view.getUint32(8), view.getUint32(12),
                view.getUint32(16, true), view.getUint32(20, true)
            ].join(',');
            var floats = [view.getFloat64(8), view.getFloat64(16, true)].join(',');
            [bytes, words, floats].join('|');
            """.trimIndent()
        )
        assertEquals(
            "17,34,51,68,68,51,34,17|1073217536,0,0,1073217536|1.5,1.5",
            result,
        )
    }

    @Test
    fun `DataView 越界访问抛 RangeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "new DataView(new ArrayBuffer(4), 5);",
            "new DataView(new ArrayBuffer(4), 2, 3);",
            "new DataView(new ArrayBuffer(4), -1);",
            "new DataView(new ArrayBuffer(4)).getUint8(4);",
            "new DataView(new ArrayBuffer(4)).getInt32(1);",
            "new DataView(new ArrayBuffer(4)).setFloat64(0, 1);",
            "new DataView(new ArrayBuffer(4)).setUint16(3, 1);",
        )) {
            val error = assertFailsWith<JSException> { runtime.eval(code) }
            assertEquals("RangeError", (error.value as JSError).errorName, code)
        }
    }

    @Test
    fun `DataView 构造参数与只读访问器`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(8);
            var full = new DataView(buffer);
            var windowed = new DataView(buffer, 2, 3);
            var tail = new DataView(buffer, 3);
            var empty = new DataView(buffer, 8);
            [
                windowed.buffer === buffer,
                windowed.byteOffset,
                windowed.byteLength,
                tail.byteLength,
                empty.byteLength,
                full.byteOffset,
                full.byteLength
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,2,3,5,0,0,8", result)
        // 访问器是只读的（set 为 undefined）
        assertEquals(
            true,
            runtime.evalBoolean(
                "var d = Object.getOwnPropertyDescriptor(DataView.prototype, 'byteLength');" +
                    " d.set === undefined && d.get !== undefined"
            )
        )
    }

    @Test
    fun `DataView 品牌检查与非 ArrayBuffer 参数抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "DataView.prototype.getInt8.call({});",
            "DataView.prototype.setUint8.call(new ArrayBuffer(2), 0, 1);",
            "Object.getOwnPropertyDescriptor(DataView.prototype, 'buffer').get.call(new ArrayBuffer(2));",
            "DataView(new ArrayBuffer(2));",
            "new DataView({});",
            "new DataView();",
            "ArrayBuffer.prototype.slice.call({});",
            // species 返回过小的缓冲区 → TypeError
            "var small = new ArrayBuffer(4);" +
                " var smallCtor = function (length) { return new ArrayBuffer(0); };" +
                " smallCtor[Symbol.species] = smallCtor;" +
                " small.constructor = smallCtor;" +
                " small.slice(0, 4);",
            // species 返回原缓冲区自身 → TypeError
            "var self = new ArrayBuffer(4);" +
                " var selfCtor = function (length) { return self; };" +
                " selfCtor[Symbol.species] = selfCtor;" +
                " self.constructor = selfCtor;" +
                " self.slice(0, 1);",
        )) {
            val error = assertFailsWith<JSException> { runtime.eval(code) }
            assertEquals("TypeError", (error.value as JSError).errorName, code)
        }
    }

    @Test
    fun `ArrayBuffer 与 DataView 的原型链和 toStringTag`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            [
                Object.getPrototypeOf(ArrayBuffer.prototype) === Object.prototype,
                Object.getPrototypeOf(DataView.prototype) === Object.prototype,
                Object.getPrototypeOf(ArrayBuffer) === Function.prototype,
                Object.getPrototypeOf(DataView) === Function.prototype,
                ArrayBuffer.prototype.constructor === ArrayBuffer,
                DataView.prototype.constructor === DataView,
                ArrayBuffer.prototype[Symbol.toStringTag],
                DataView.prototype[Symbol.toStringTag],
                Object.prototype.toString.call(new ArrayBuffer(1)),
                Object.prototype.toString.call(new DataView(new ArrayBuffer(1)))
            ].join(',');
            """.trimIndent()
        )
        assertEquals(
            "true,true,true,true,true,true,ArrayBuffer,DataView," +
                "[object ArrayBuffer],[object DataView]",
            result,
        )
    }

    @Test
    fun `DataView 浮点写入保留负零并正确舍入单精度`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(24);
            var view = new DataView(buffer);
            view.setFloat64(0, -0);
            view.setFloat32(8, 3.141592653589793);
            view.setFloat64(16, 0 / 0);
            var negativeZero = view.getFloat64(0);
            var float32 = view.getFloat32(8);
            var notANumber = view.getFloat64(16);
            [
                (1 / negativeZero) === -Infinity,
                float32 === 3.1415927410125732,
                notANumber !== notANumber
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,true,true", result)
    }

    @Test
    fun `ArrayBuffer 与 DataView 的返回值及索引转换`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(4);
            var view = new DataView(buffer);
            view.setUint8(0, 7);
            view.setUint8(1, 9);
            var setReturn = view.setInt8(2, 1);
            var indexes = [view.getUint8('1'), view.getUint8(1.9), view.getUint8(-0)].join(',');
            [setReturn === undefined, indexes].join('|');
            """.trimIndent()
        )
        assertEquals("true|9,9,7", result)
    }
}
