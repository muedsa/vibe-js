package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * L3.4 BigInt64Array / BigUint64Array 与 DataView BigInt 读写语义测试。
 *
 * 覆盖：构造与 BYTES_PER_ELEMENT、BigInt 元素读写与补码环绕、Number 元素抛 TypeError、
 * 越界读写、DataView 大小端读写与转换顺序、set/subarray/slice/克隆、
 * ArrayBuffer.isView 与 @@toStringTag、原型链、map/filter/sort/迭代、共享缓冲区、
 * ArrayBuffer 构造的偏移对齐与范围校验。
 */
class BigInt64ArrayTest {

    /** 断言一段脚本以指定名称的错误抛出。 */
    private suspend fun assertErrorName(runtime: TestRuntime, code: String, name: String) {
        val error = assertFailsWith<JSException>(code) { runtime.eval(code) }
        assertEquals(name, (error.value as JSError).errorName, code)
    }

    @Test
    fun `BigInt64Array 构造器与 BYTES_PER_ELEMENT 属性`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var constructorDescriptor = Object.getOwnPropertyDescriptor(BigInt64Array, 'BYTES_PER_ELEMENT');
            var prototypeDescriptor = Object.getOwnPropertyDescriptor(BigUint64Array.prototype, 'BYTES_PER_ELEMENT');
            [
                typeof BigInt64Array,
                BigInt64Array.name,
                BigInt64Array.length,
                BigUint64Array.name,
                BigUint64Array.length,
                BigInt64Array.BYTES_PER_ELEMENT,
                BigUint64Array.BYTES_PER_ELEMENT,
                BigInt64Array.prototype.BYTES_PER_ELEMENT,
                BigUint64Array.prototype.BYTES_PER_ELEMENT,
                new BigInt64Array(1).BYTES_PER_ELEMENT,
                constructorDescriptor.writable,
                constructorDescriptor.enumerable,
                constructorDescriptor.configurable,
                prototypeDescriptor.writable,
                prototypeDescriptor.enumerable,
                prototypeDescriptor.configurable,
                Object.prototype.hasOwnProperty.call(BigInt64Array, 'BYTES_PER_ELEMENT'),
                Object.prototype.hasOwnProperty.call(BigUint64Array.prototype, 'BYTES_PER_ELEMENT')
            ].join(',');
            """.trimIndent()
        )
        assertEquals(
            "function,BigInt64Array,3,BigUint64Array,3,8,8,8,8,8," +
                "false,false,false,false,false,false,true,true",
            result,
        )
    }

    @Test
    fun `BigInt 视图支持长度、数组、TypedArray 与 ArrayBuffer 构造`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var fromArray = new BigInt64Array([1n, -2n, 3n]);
            var fromTyped = new BigInt64Array(new BigUint64Array([1n, 2n]));
            var buffer = new ArrayBuffer(24);
            var fromBuffer = new BigInt64Array(buffer);
            var partial = new BigInt64Array(buffer, 8, 2);
            [
                fromArray.length, fromArray[0], fromArray[1], fromArray[2],
                fromTyped.length, fromTyped[0], fromTyped[1],
                fromBuffer.length, fromBuffer.byteLength, fromBuffer.byteOffset,
                partial.length, partial.byteOffset, partial.byteLength,
                BigInt64Array.of(4n, 5n).join('-'),
                BigInt64Array.from([6n, 7n]).join('-')
            ].join('|');
            """.trimIndent()
        )
        assertEquals("3|1|-2|3|2|1|2|3|24|0|2|8|16|4-5|6-7", result)
        // Object.defineProperty 的索引定义同样走 ToBigInt 写入路径
        assertEquals(
            "123",
            runtime.evalString(
                "var defined = new BigInt64Array(1);" +
                    " Object.defineProperty(defined, '0', { value: 123n });" +
                    " String(defined[0]);"
            ),
        )
    }

    @Test
    fun `BigInt 视图按补码环绕读写 64 位整数`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var signed = new BigInt64Array(4);
            signed[0] = 9223372036854775808n;
            signed[1] = -9223372036854775809n;
            signed[2] = 18446744073709551616n;
            signed[3] = -18446744073709551615n;
            var unsigned = new BigUint64Array(3);
            unsigned[0] = -1n;
            unsigned[1] = 18446744073709551616n;
            unsigned[2] = -18446744073709551617n;
            [
                String(signed[0]), String(signed[1]), String(signed[2]), String(signed[3]),
                String(unsigned[0]), String(unsigned[1]), String(unsigned[2]),
                signed[0] === BigInt.asIntN(64, 9223372036854775808n),
                unsigned[0] === BigInt.asUintN(64, -1n)
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "-9223372036854775808|9223372036854775807|0|1|" +
                "18446744073709551615|0|18446744073709551615|true|true",
            result,
        )
    }

    @Test
    fun `BigInt 视图拒绝 Number 元素并按规范抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            // 数组/可迭代对象中的 Number 元素按 ToBigInt 转换失败
            "new BigInt64Array([1]);",
            "new BigInt64Array([1n, 2]);",
            "new BigInt64Array(new Uint8Array([1]));",
            // 写入、fill、with、map 结果同样必须能转换为 BigInt
            "(new BigInt64Array(2))[0] = 1;",
            "Object.defineProperty(new BigInt64Array(1), '0', { value: 1 });",
            "new BigInt64Array(2).fill(1);",
            "new BigInt64Array(2).with(0, 1);",
            "new BigInt64Array(2).map(function () { return 1; });",
            // 构造长度参数必须是 Number（ToIndex 不接受 BigInt）
            "new BigInt64Array(1n);",
        )) {
            assertErrorName(runtime, code, "TypeError")
        }
    }

    @Test
    fun `BigInt 视图越界读写与数值视图一致`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new BigInt64Array(2);
            array[0] = 7n;
            array[9] = 11n;
            var called = 0;
            var value = { valueOf: function () { called = called + 1; return 5n; } };
            array[5] = value;
            [
                array[2] === undefined,
                array[9] === undefined,
                array[-1] === undefined,
                array['1.5'] === undefined,
                array.length === 2 && array[0] === 7n,
                called
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,true,true,true,true,1", result)
        // 规范：越界写入静默成功，但 ToBigInt 仍先执行一次
        runtime.eval(
            """
            (function () {
              'use strict';
              var array = new BigInt64Array(2);
              array[5] = 1n;
            })();
            """.trimIndent()
        )
    }

    @Test
    fun `DataView BigInt 方法按大小端读写 64 位整数`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(32);
            var view = new DataView(buffer);
            view.setBigInt64(0, -2n);
            view.setBigInt64(8, 0x0102030405060708n, true);
            view.setBigUint64(16, 18446744073709551615n);
            view.setBigUint64(24, -1n, true);
            [
                view.getBigInt64(0) === -2n,
                view.getBigUint64(0) === 18446744073709551614n,
                view.getUint8(0) === 255 && view.getUint8(7) === 254,
                view.getUint8(8) === 8 && view.getUint8(15) === 1,
                view.getBigInt64(8, true) === 0x0102030405060708n,
                view.getBigUint64(16) === 18446744073709551615n,
                view.getBigUint64(24, true) === 18446744073709551615n,
                view.getBigInt64(24) === -1n,
                DataView.prototype.getBigInt64.length,
                DataView.prototype.setBigInt64.length,
                DataView.prototype.getBigUint64.length,
                DataView.prototype.setBigUint64.length
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,true,true,true,true,true,true,true,1,2,1,2", result)
    }

    @Test
    fun `DataView BigInt 方法越界抛 RangeError 且值转换先行`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(8);
            var view = new DataView(buffer);
            var called = 0;
            var value = { valueOf: function () { called = called + 1; return 1n; } };
            function errorName(fn) { try { fn(); return 'none'; } catch (e) { return e.name; } }
            [
                called,
                errorName(function () { view.setBigInt64(8, value); }),
                called,
                // 值转换（ToBigInt）早于越界检查：Number 先抛 TypeError
                errorName(function () { view.setBigInt64(8, 1); }),
                errorName(function () { view.getBigInt64(4); }),
                errorName(function () { view.getBigUint64(-1); }),
                errorName(function () { DataView.prototype.getBigInt64.call({}, 0); }),
                errorName(function () { view.setBigUint64(0, 1); })
            ].join(',');
            """.trimIndent()
        )
        assertEquals("0,RangeError,1,TypeError,RangeError,RangeError,TypeError,TypeError", result)
    }

    @Test
    fun `BigInt 视图的 set subarray slice 与克隆行为`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(32);
            var base = new BigInt64Array(buffer, 0, 4);
            base[0] = 1n; base[1] = 2n; base[2] = 3n; base[3] = 4n;
            var sub = base.subarray(1, 3);
            sub[0] = 20n;
            var copy = base.slice(1, 3);
            copy[0] = 99n;
            var clone = new BigInt64Array(base);
            clone[0] = 42n;
            var target = new BigInt64Array(2);
            target.set([7n, 8n]);
            var fromTyped = new BigInt64Array(4);
            fromTyped.set(base);
            [
                base.buffer === buffer,
                sub.buffer === buffer,
                sub.length,
                sub.byteOffset,
                sub.byteLength,
                String(base[1]),
                String(sub[0]),
                String(copy[0]),
                String(base[1]),
                copy.buffer === buffer,
                String(base[0]),
                String(clone[0]),
                target.join('-'),
                fromTyped.join('-')
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "true|true|2|8|16|20|20|99|20|false|1|42|7-8|1-20-3-4",
            result,
        )
    }

    @Test
    fun `ArrayBuffer isView 与 Object prototype toString 识别 BigInt 视图`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(8);
            var signed = new BigInt64Array(buffer);
            var unsigned = new BigUint64Array(buffer);
            [
                ArrayBuffer.isView(signed),
                ArrayBuffer.isView(unsigned),
                ArrayBuffer.isView(new DataView(buffer)),
                ArrayBuffer.isView(buffer),
                Object.prototype.toString.call(signed),
                Object.prototype.toString.call(unsigned),
                Object.prototype.toString.call(BigInt64Array.prototype),
                BigInt64Array.prototype[Symbol.toStringTag] === undefined,
                signed[Symbol.toStringTag],
                unsigned[Symbol.toStringTag]
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "true|true|true|false|[object BigInt64Array]|[object BigUint64Array]|" +
                "[object Object]|true|BigInt64Array|BigUint64Array",
            result,
        )
    }

    @Test
    fun `BigInt 视图原型链、构造器互指与 instanceof`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var typedArrayPrototype = Object.getPrototypeOf(BigInt64Array.prototype);
            var instance = new BigInt64Array(1);
            [
                Object.getPrototypeOf(BigInt64Array) === Object.getPrototypeOf(Uint8Array),
                Object.getPrototypeOf(BigInt64Array) === typedArrayPrototype.constructor,
                Object.getPrototypeOf(BigUint64Array) === Object.getPrototypeOf(BigInt64Array),
                Object.getPrototypeOf(typedArrayPrototype) === Object.prototype,
                BigInt64Array.prototype.constructor === BigInt64Array,
                BigUint64Array.prototype.constructor === BigUint64Array,
                instance.constructor === BigInt64Array,
                instance instanceof BigInt64Array,
                instance instanceof Uint8Array,
                new BigUint64Array(1) instanceof BigInt64Array,
                BigInt64Array[Symbol.species] === BigInt64Array
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "true|true|true|true|true|true|true|true|false|false|true",
            result,
        )
    }

    @Test
    fun `BigInt 视图的 map filter sort 与迭代保持 BigInt 元素`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var base = new BigInt64Array([3n, -1n, 2n, -4n]);
            var mapped = base.map(function (value, index) { return value * 2n + BigInt(index); });
            var filtered = base.filter(function (value) { return value > 0n; });
            var sorted = new BigInt64Array([3n, -1n, 2n, -4n]).sort();
            var toSorted = base.toSorted();
            var reversed = base.toReversed();
            var filled = new BigInt64Array(3).fill(5n, 1);
            var withValue = base.with(1, 9n);
            var reduced = base.reduce(function (a, b) { return a + b; }, 0n);
            var from = Array.from(base);
            var spread = [...base];
            var found = base.find(function (value) { return value < 0n; });
            [
                mapped.join(','), filtered.join(','), sorted.join(','), toSorted.join(','),
                reversed.join(','), filled.join(','), withValue.join(','), String(reduced),
                from.length, typeof from[0], typeof spread[1],
                String(found), base.includes(-1n), base.indexOf(2n), base.at(-4),
                base.every(function (value) { return typeof value === 'bigint'; })
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "6,-1,6,-5|3,2|-4,-1,2,3|-4,-1,2,3|-4,2,-1,3|0,5,5|3,9,2,-4|0|" +
                "4|bigint|bigint|-1|true|2|3|true",
            result,
        )
    }

    @Test
    fun `BigInt 视图与 DataView Uint8Array 共享底层字节`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(24);
            var signed = new BigInt64Array(buffer);
            var unsigned = new BigUint64Array(buffer);
            var bytes = new Uint8Array(buffer);
            var view = new DataView(buffer);
            signed[0] = -1n;
            unsigned[1] = 18446744073709551615n;
            // TypedArray 固定使用小端序，因此这里显式传 littleEndian = true
            view.setBigInt64(16, -2n, true);
            [
                signed[0] === -1n,
                unsigned[0] === 18446744073709551615n,
                signed[1] === -1n,
                unsigned[1] === 18446744073709551615n,
                view.getBigInt64(16, true) === -2n,
                signed[2] === -2n,
                bytes[16] === 254 && bytes[23] === 255,
                unsigned.byteLength === 24 && signed.length === 3
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,true,true,true,true,true,true,true", result)
    }

    @Test
    fun `BigInt 视图的 ArrayBuffer 构造校验偏移对齐与范围`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            function errorName(fn) { try { fn(); return 'none'; } catch (e) { return e.name; } }
            [
                errorName(function () { new BigInt64Array(new ArrayBuffer(12)); }),
                errorName(function () { new BigUint64Array(new ArrayBuffer(12)); }),
                errorName(function () { new BigInt64Array(new ArrayBuffer(16), 4); }),
                errorName(function () { new BigInt64Array(new ArrayBuffer(16), 8, 2); }),
                errorName(function () { new BigInt64Array(-1); }),
                new BigInt64Array(new ArrayBuffer(16), 8).length,
                new BigInt64Array(new ArrayBuffer(16), 16).length,
                new BigUint64Array(new ArrayBuffer(16)).byteLength
            ].join(',');
            """.trimIndent()
        )
        assertEquals(
            "RangeError,RangeError,RangeError,RangeError,RangeError,1,0,16",
            result,
        )
    }
}
