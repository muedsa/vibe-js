package com.muedsa.js

import com.muedsa.js.runtime.BuiltinModule
import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSUndefined
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * L3.5 ArrayBuffer 分离（detach）语义测试。
 *
 * 覆盖 detach 后：
 * - ArrayBuffer 的 byteLength / slice；
 * - DataView 的访问器、读写方法与构造；
 * - TypedArray 的 length / byteLength / byteOffset / 元素读写 / set / 原型方法；
 * - ArrayBuffer.isView 与 Object.prototype.toString；
 * - 重复 detach 的幂等性，以及在分离缓冲区上创建新视图。
 *
 * JS 脚本无法直接触发 detach（规范未暴露该方法），因此测试通过读取全局变量拿到
 * [JSArrayBuffer] 句柄后调用 `detach()`，再继续用 JS 断言规范行为。
 */
class ArrayBufferDetachTest {

    /** 读取全局变量中的 ArrayBuffer 并分离它。 */
    private suspend fun detachGlobalBuffer(runtime: TestRuntime, name: String) {
        val buffer = assertIs<JSArrayBuffer>(runtime.global(name))
        buffer.detach()
    }

    /** 断言 JS 片段抛出指定名称的内建错误。 */
    private suspend fun assertErrorName(
        runtime: TestRuntime,
        code: String,
        expected: String,
    ) {
        val error = assertFailsWith<JSException> { runtime.eval(code) }
        assertEquals(expected, (error.value as JSError).errorName, code)
    }

    @Test
    fun `detach 后 ArrayBuffer 的 byteLength 归零且 slice 抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval("var buffer = new ArrayBuffer(8);")
        detachGlobalBuffer(runtime, "buffer")

        // byteLength 访问器按规范 IsDetachedBuffer 分支返回 0
        assertEquals(0.0, runtime.evalNumber("buffer.byteLength"))
        assertEquals(
            true,
            runtime.evalBoolean(
                "Object.getOwnPropertyDescriptor(" +
                    "ArrayBuffer.prototype, 'byteLength').get.call(buffer) === 0"
            )
        )
        // slice 必须先抛 TypeError，且不再读取 start/end 的可观察转换
        assertErrorName(
            runtime,
            "var called = 0;" +
                " buffer.slice({ valueOf: function () { called += 1; return 0; } });",
            "TypeError",
        )
        assertEquals(0.0, runtime.evalNumber("called"))
    }

    @Test
    fun `detach 后 DataView 读写方法抛 TypeError 且访问器归零`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var buffer = new ArrayBuffer(8);
            var view = new DataView(buffer, 2, 4);
            var detachedBuffer = buffer;
            """.trimIndent()
        )
        detachGlobalBuffer(runtime, "buffer")

        // buffer 访问器不受影响；byteOffset / byteLength 按 IsViewOutOfBounds 归零
        assertEquals(true, runtime.evalBoolean("view.buffer === detachedBuffer"))
        assertEquals(0.0, runtime.evalNumber("view.byteOffset"))
        assertEquals(0.0, runtime.evalNumber("view.byteLength"))

        for (code in listOf(
            "view.getInt8(0);",
            "view.getUint32(0, true);",
            "view.getFloat64(0);",
            "view.getBigInt64(0);",
            "view.setInt8(0, 1);",
            "view.setFloat32(0, 1.5);",
            "view.setBigUint64(0, 1n);",
        )) {
            assertErrorName(runtime, code, "TypeError")
        }
    }

    @Test
    fun `detach 后 TypedArray 的 length 与 byteLength 归零且元素读取为 undefined`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var buffer = new ArrayBuffer(8);
            var array = new Uint8Array(buffer, 1, 3);
            var bigintArray = new BigInt64Array(buffer, 0, 1);
            """.trimIndent()
        )
        detachGlobalBuffer(runtime, "buffer")

        assertEquals(
            "0,0,0,true,true,true",
            runtime.evalString(
                """
                [
                    array.length,
                    array.byteLength,
                    array.byteOffset,
                    array[0] === undefined,
                    array[2] === undefined,
                    bigintArray[0] === undefined
                ].join(',');
                """.trimIndent()
            )
        )
        // 整数索引属性不再存在：in / 属性描述符 / 自有键都不暴露索引
        assertEquals(
            "false,true,0",
            runtime.evalString(
                """
                [
                    0 in array,
                    Object.getOwnPropertyDescriptor(array, '0') === undefined,
                    Object.getOwnPropertyNames(array).length
                ].join(',');
                """.trimIndent()
            )
        )
    }

    @Test
    fun `detach 后 TypedArray 的 set 抛 TypeError 且元素赋值静默忽略`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var buffer = new ArrayBuffer(4);
            var array = new Uint8Array(buffer);
            array[0] = 7;
            """.trimIndent()
        )
        detachGlobalBuffer(runtime, "buffer")

        // TypedArray.prototype.set 的规范第 3 步检查 IsDetachedBuffer
        assertErrorName(runtime, "array.set([1, 2]);", "TypeError")
        assertErrorName(runtime, "array.set(new Uint8Array(2));", "TypeError")
        // 元素赋值走 Integer-Indexed [[Set]]：分离后静默失败，不抛错也不写入
        assertEquals(
            true,
            runtime.evalBoolean(
                "array[0] = 9; array[0] === undefined"
            )
        )
        // 严格模式下同样静默；Reflect.set 按规范返回 true
        assertEquals(
            "true,true,true",
            runtime.evalString(
                """
                [
                    (function () { 'use strict'; array[0] = 9; return array[0] === undefined; })(),
                    Reflect.set(array, '0', 11),
                    array[0] === undefined
                ].join(',');
                """.trimIndent()
            )
        )
    }

    @Test
    fun `detach 后 ArrayBuffer isView 与 Object prototype toString 保持正常`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var buffer = new ArrayBuffer(8);
            var view = new DataView(buffer);
            var array = new Uint8Array(buffer);
            """.trimIndent()
        )
        detachGlobalBuffer(runtime, "buffer")

        assertEquals(
            "true,true,false,true,true,[object ArrayBuffer],[object DataView],[object Uint8Array]",
            runtime.evalString(
                """
                [
                    ArrayBuffer.isView(view),
                    ArrayBuffer.isView(array),
                    ArrayBuffer.isView(buffer),
                    view.buffer === buffer,
                    array.buffer === buffer,
                    Object.prototype.toString.call(buffer),
                    Object.prototype.toString.call(view),
                    Object.prototype.toString.call(array)
                ].join(',');
                """.trimIndent()
            )
        )
    }

    @Test
    fun `重复 detach 幂等且既有视图保持分离状态`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var buffer = new ArrayBuffer(4);
            var view = new DataView(buffer);
            var array = new Int16Array(buffer);
            """.trimIndent()
        )
        repeat(3) { detachGlobalBuffer(runtime, "buffer") }

        assertEquals(
            "0,0,0,0,0",
            runtime.evalString(
                """
                [
                    buffer.byteLength,
                    view.byteLength,
                    array.byteLength,
                    array.length,
                    array.byteOffset
                ].join(',');
                """.trimIndent()
            )
        )
        assertErrorName(runtime, "array.set([1]);", "TypeError")
        assertErrorName(runtime, "buffer.slice();", "TypeError")
    }

    @Test
    fun `detach 后创建 DataView 与 TypedArray 视图抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var buffer = new ArrayBuffer(8);
            var array = new Uint8Array(buffer);
            """.trimIndent()
        )
        detachGlobalBuffer(runtime, "buffer")

        for (code in listOf(
            "new DataView(buffer);",
            "new DataView(buffer, 0, 0);",
            "new Uint8Array(buffer);",
            "new Uint8Array(buffer, 0, 0);",
            "new Int32Array(buffer);",
            "new BigInt64Array(buffer);",
            // 以分离的 TypedArray 作为源构造新视图同样属于 out-of-bounds
            "new Uint8Array(array);",
            "new Float64Array(array);",
        )) {
            assertErrorName(runtime, code, "TypeError")
        }
    }

    @Test
    fun `detach 后 TypedArray 原型方法按 ValidateTypedArray 抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var buffer = new ArrayBuffer(4);
            var array = new Uint8Array(buffer);
            """.trimIndent()
        )
        detachGlobalBuffer(runtime, "buffer")

        for (code in listOf(
            "array.copyWithin(0, 0);",
            "array.fill(1);",
            "array.sort();",
            "array.reverse();",
            "array.slice();",
            "array.subarray();",
            "array.join(',');",
            "array.indexOf(0);",
            "array.lastIndexOf(0);",
            "array.includes(0);",
            "array.every(function () { return true; });",
            "array.forEach(function () {});",
            "array.map(function (value) { return value; });",
            "array.filter(function () { return true; });",
            "array.reduce(function (a, b) { return a; });",
            "array.find(function () { return true; });",
            "array.keys();",
            "array.values();",
            "array.entries();",
            "array.toLocaleString();",
            "array.toString();",
        )) {
            assertErrorName(runtime, code, "TypeError")
        }
    }

    /**
     * 安装测试专用的 `__detach(buffer)` 宿主函数。
     *
     * 规范没有把 detach 暴露给 JS；Test262 通过 `$262.detachArrayBuffer` 提供。
     * 这里用最小 BuiltinModule 模拟同一钩子，便于验证"参数转换/species 构造期间
     * 发生分离"这类只有宿主能触发、但顺序可观察的语义。
     */
    private fun installDetachHook(runtime: TestRuntime) {
        runtime.engine.install(object : BuiltinModule {
            override fun install(registry: BuiltinRegistry) {
                registry.defineGlobal(
                    name = "__detach",
                    value = JSNativeFunction(
                        name = "__detach",
                        length = 1,
                        prototype = registry.intrinsics.functionPrototype,
                    ) { interpreter, _, args ->
                        val buffer = args.getOrElse(0) { JSUndefined } as? JSArrayBuffer
                            ?: interpreter.typeError("__detach requires an ArrayBuffer")
                        buffer.detach()
                        JSUndefined
                    },
                )
            }
        })
    }

    @Test
    fun `species 构造期间 detach 时 ArrayBuffer slice 抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        installDetachHook(runtime)
        runtime.eval(
            """
            var buffer = new ArrayBuffer(4);
            var holder = {};
            holder[Symbol.species] = function (length) {
                __detach(buffer);
                return new ArrayBuffer(length);
            };
            buffer.constructor = holder;
            """.trimIndent()
        )
        // 源缓冲区在 species 构造期间被分离，拷贝前的复检必须抛 TypeError
        assertErrorName(runtime, "buffer.slice(0, 1);", "TypeError")
        assertEquals(0.0, runtime.evalNumber("buffer.byteLength"))
    }

    @Test
    fun `参数转换期间 detach 时 DataView 与 TypedArray 的顺序与规范一致`() = runTest {
        val runtime = TestRuntime()
        installDetachHook(runtime)
        runtime.eval(
            """
            var buffer = new ArrayBuffer(8);
            var view = new DataView(buffer);
            var array = new Uint8Array(buffer);
            """.trimIndent()
        )
        // DataView.setXxx：ToNumber(value) 先执行（valueOf 里触发 detach），随后检查分离并抛 TypeError
        assertErrorName(
            runtime,
            "view.setInt8(0, { valueOf: function () { __detach(buffer); return 1; } });",
            "TypeError",
        )
        // getXxx：越界检查晚于分离检查，因此分离时抛 TypeError 而不是 RangeError
        assertErrorName(runtime, "view.getInt8(64);", "TypeError")
        // TypedArray 元素读取在分离后返回 undefined，写入静默忽略
        assertEquals(
            "true,true",
            runtime.evalString(
                "[(array[0] === undefined), ((array[0] = 1) === 1)].join(',')"
            )
        )
    }
}
