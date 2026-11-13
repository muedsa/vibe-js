package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * L3.2 TypedArray 语义测试。
 *
 * 覆盖：9 种元素类型的读写与转换、越界行为、整数索引异质属性、
 * 四种构造形式与参数校验、set/subarray/slice、map/filter/species、
 * 迭代器与展开、原型链与 ArrayBuffer 集成。
 */
class TypedArrayTest {

    /** 断言一段脚本以指定名称的错误抛出。 */
    private suspend fun assertErrorName(runtime: TestRuntime, code: String, name: String) {
        val error = assertFailsWith<JSException>(code) { runtime.eval(code) }
        assertEquals(name, (error.value as JSError).errorName, code)
    }

    @Test
    fun `九种 TypedArray 类型按元素宽度读写并完成数值转换`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var cases = [
                [Int8Array, [1, 2, -1, 128, -129], [1, 2, -1, -128, 127]],
                [Uint8Array, [1, 2, -1, 256, -1], [1, 2, 255, 0, 255]],
                [Uint8ClampedArray, [1, 2, -1, 256, -1], [1, 2, 0, 255, 0]],
                [Int16Array, [1, -1, 32768, -32769], [1, -1, -32768, 32767]],
                [Uint16Array, [1, -1, 65536, 65541], [1, 65535, 0, 5]],
                [Int32Array, [1, -1, 2147483648, 4294967295], [1, -1, -2147483648, -1]],
                [Uint32Array, [1, -1, 4294967295, 4294967296], [1, 4294967295, 4294967295, 0]],
                [Float32Array, [1.5, -2.5, 2.9], [1.5, -2.5, Math.fround(2.9)]],
                [Float64Array, [Math.PI, -0.5, -0], [Math.PI, -0.5, -0]]
            ];
            var output = [];
            for (var i = 0; i < cases.length; i++) {
                var Ctor = cases[i][0], input = cases[i][1], expected = cases[i][2];
                var array = new Ctor(input.length);
                for (var j = 0; j < input.length; j++) { array[j] = input[j]; }
                var ok = array.length === input.length;
                for (var k = 0; k < input.length; k++) {
                    if (array[k] !== expected[k]) { ok = false; }
                }
                // -0 的符号位必须保留
                if (Ctor === Float64Array && (1 / array[2]) !== -Infinity) { ok = false; }
                output.push(ok);
            }
            output.join(',');
            """.trimIndent()
        )
        assertEquals("true,true,true,true,true,true,true,true,true", result)
    }

    @Test
    fun `Uint8ClampedArray 使用 ToUint8Clamp 的银行家舍入`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8ClampedArray(9);
            array[0] = 0.5;
            array[1] = 1.5;
            array[2] = 2.5;
            array[3] = 3.5;
            array[4] = 1.4;
            array[5] = 254.6;
            array[6] = -5;
            array[7] = 1000;
            array[8] = NaN;
            [array[0], array[1], array[2], array[3], array[4], array[5], array[6], array[7], array[8]].join(',');
            """.trimIndent()
        )
        assertEquals("0,2,2,4,1,255,0,255,0", result)
    }

    @Test
    fun `TypedArray 越界读取返回 undefined 且越界写入静默忽略`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "true,true,true,true,true",
            runtime.evalString(
                """
                var array = new Uint8Array(2);
                array[0] = 7;
                array[9] = 11;
                [
                    array[2] === undefined,
                    array[9] === undefined,
                    array[-1] === undefined,
                    array['1.5'] === undefined,
                    array.length === 2 && array[0] === 7
                ].join(',');
                """.trimIndent()
            )
        )
        // 规范：越界写入静默成功，严格模式也不抛错；值保持不变
        assertEquals(
            "0,7",
            runtime.evalString(
                """
                (function () {
                  'use strict';
                  var array = new Uint8Array(2);
                  array[0] = 7;
                  array[5] = 1;
                  return [array[1], array[0]].join(',');
                })();
                """.trimIndent()
            )
        )
        // 严格模式下访问器只读/越界赋值同样抛 TypeError
        assertErrorName(
            runtime,
            "(function () { 'use strict'; var array = new Uint8Array(2); array.length = 5; })();",
            "TypeError",
        )
    }

    @Test
    fun `TypedArray 索引属性遵循整数索引异质对象规则`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array(3);
            array[1] = 5;
            array.foo = 1;
            Object.defineProperty(array, 'bar', { value: 2, enumerable: false });
            var descriptor = Object.getOwnPropertyDescriptor(array, '1');
            var keys = [];
            for (var key in array) { keys.push(key); }
            [
                descriptor.value,
                descriptor.writable,
                descriptor.enumerable,
                descriptor.configurable,
                Object.keys(array).join(','),
                Object.getOwnPropertyNames(array).join(','),
                keys.join(','),
                ('0' in array),
                ('5' in array),
                ('1.5' in array),
                ('-1' in array),
                delete array[0],
                delete array[9],
                delete array.foo
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "5|true|true|true|0,1,2,foo|0,1,2,foo,bar|0,1,2,foo|true|false|false|false|false|true|true",
            result,
        )
    }

    @Test
    fun `Object defineProperty 对索引按规范校验 value 与属性标志`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "9,false",
            runtime.evalString(
                """
                var array = new Uint8Array(2);
                array[0] = 7;
                Object.defineProperty(array, '0', { value: 9 });
                [array[0], Reflect.defineProperty(array, '5', { value: 1 })].join(',');
                """.trimIndent()
            )
        )
        // 不可写 / 不可枚举 / 不可配置 / 访问器描述符都必须失败
        for (descriptor in listOf(
            "{ writable: false }",
            "{ enumerable: false }",
            "{ configurable: false }",
            "{ get: function () { return 1; } }",
        )) {
            assertErrorName(
                runtime,
                "var array = new Uint8Array(2); Object.defineProperty(array, '0', $descriptor);",
                "TypeError",
            )
        }
        // 越界索引定义失败
        assertErrorName(
            runtime,
            "Object.defineProperty(new Uint8Array(2), '2', { value: 1 });",
            "TypeError",
        )
        // "-0" 属于规范数字索引字符串但不是合法下标，同样不能定义
        assertErrorName(
            runtime,
            "Object.defineProperty(new Uint8Array(2), '-0', { value: 1 });",
            "TypeError",
        )
    }

    @Test
    fun `规范数字索引不泄漏原型链且 TypedArray 作为原型时行为正确`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array([7]);
            var prototype = Object.getPrototypeOf(array);
            prototype['-1'] = 'minus';
            prototype['2'] = 'out-of-bounds';
            prototype['1.5'] = 'fraction';
            prototype['NaN'] = 'nan';
            var wrapper = Object.create(array);
            wrapper.NaN = 100;
            delete wrapper.NaN;
            var wrappedSet = Reflect.set(wrapper, 'NaN', 0);
            // 自定义属性写入不受影响
            array['01'] = 'leading';
            [
                array['-1'] === undefined,
                array['2'] === undefined,
                array['1.5'] === undefined,
                array['NaN'] === undefined,
                ('-1' in array),
                Reflect.has(array, '1.5'),
                Reflect.set(array, '2', 1),
                Reflect.set(wrapper, 'NaN', 0) === true && !wrapper.hasOwnProperty('NaN'),
                wrappedSet === true,
                array['01'],
                '0' in array,
                array[0]
            ].join('|');
            """.trimIndent()
        )
        assertEquals("true|true|true|true|false|false|false|true|true|leading|true|7", result)
    }

    @Test
    fun `TypedArray 支持按长度、数组、TypedArray 与 ArrayBuffer 构造`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(8);
            var windowed = new Uint8Array(buffer, 2, 4);
            var tail = new Uint8Array(buffer, 4);
            var fromArray = new Uint8Array([1, 2, 3]);
            var source = new Uint8Array([1, 2, 256]);
            var fromTyped = new Int16Array(source);
            var arrayLike = new Uint8Array({ length: 2, 0: 7, 1: 8 });
            var fromSet = new Uint8Array(new Set([5, 6]));
            [
                windowed.length, windowed.byteOffset, windowed.byteLength,
                windowed.buffer === buffer, tail.length,
                fromArray.join(','),
                fromTyped[0], fromTyped[1], fromTyped[2],
                arrayLike.join(','),
                fromSet.join(','),
                new Uint8Array().length,
                new Uint8Array(undefined).length,
                new Uint8Array(null).length,
                new Uint8Array('3').length,
                new Uint8Array(2.9).length
            ].join('|');
            """.trimIndent()
        )
        assertEquals("4|2|4|true|4|1,2,3|1|2|0|7,8|5,6|0|0|0|3|2", result)
    }

    @Test
    fun `TypedArray from 与 of 静态方法按构造器创建结果`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var fromArray = Uint8Array.from([1, 2, 3]);
            var fromArrayLike = Uint8Array.from({ length: 2, 0: 4, 1: 5 });
            var fromMapped = Uint8Array.from([1, 2], function (value, index) { return value + index; });
            var ofValues = Int16Array.of(1, -1, 65535);
            [
                fromArray instanceof Uint8Array, fromArray.join(','),
                fromArrayLike.join(','),
                fromMapped.join(','),
                ofValues.join(','),
                Uint8Array.from.length, Uint8Array.of.length,
                Object.prototype.hasOwnProperty.call(Uint8Array, 'from'),
                typeof Object.getPrototypeOf(Uint8Array).from === 'function'
            ].join('|');
            """.trimIndent()
        )
        assertEquals("true|1,2,3|4,5|1,3|1,-1,-1|1|0|false|true", result)
        // 非构造器 this 与非可迭代值必须抛 TypeError
        assertErrorName(runtime, "Uint8Array.from.call({}, [1]);", "TypeError")
        assertErrorName(runtime, "Uint8Array.of.call({}, 1);", "TypeError")
        assertErrorName(runtime, "Uint8Array.from(null);", "TypeError")
    }

    @Test
    fun `findLast findLastIndex toReversed toSorted with 遵循复制语义`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array([1, 2, 3, 4]);
            var reversed = array.toReversed();
            var sorted = new Uint8Array([3, 1, 2]).toSorted();
            var sortedDesc = new Uint8Array([3, 1, 2]).toSorted(function (l, r) { return r - l; });
            var replaced = array.with(1, 9);
            var replacedNegative = array.with(-1, 7);
            [
                array.findLast(function (v) { return v % 2 === 0; }),
                array.findLastIndex(function (v) { return v % 2 === 0; }),
                reversed.join(','),
                array.join(','),
                reversed.buffer === array.buffer,
                sorted.join(','),
                sortedDesc.join(','),
                replaced.join(','),
                replacedNegative.join(','),
                array.join(',')
            ].join('|');
            """.trimIndent()
        )
        assertEquals("4|3|4,3,2,1|1,2,3,4|false|1,2,3|3,2,1|1,9,3,4|1,2,3,7|1,2,3,4", result)
        // 越界 index 抛 RangeError；非函数的 comparefn 抛 TypeError
        assertErrorName(runtime, "new Uint8Array(2).with(2, 1);", "RangeError")
        assertErrorName(runtime, "new Uint8Array(2).with(-3, 1);", "RangeError")
        assertErrorName(runtime, "new Uint8Array(2).toSorted(1);", "TypeError")
    }

    @Test
    fun `TypedArray 构造参数非法时抛 RangeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "new Uint8Array(-1);",
            "new Uint8Array(Infinity);",
            "new Uint8Array(9007199254740992);",
            "new Uint16Array(new ArrayBuffer(4), 1);",
            "new Uint16Array(new ArrayBuffer(4), 0, 3);",
            "new Uint8Array(new ArrayBuffer(4), 5);",
            "new Uint8Array(new ArrayBuffer(4), 1, -1);",
            "new Uint8Array(2).set([1], 3);",
            "new Uint8Array(2).set([1, 2], -1);",
        )) {
            assertErrorName(runtime, code, "RangeError")
        }
    }

    @Test
    fun `TypedArray 构造器不带 new 调用抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "Uint8Array(2);",
            "Int16Array();",
            "Float64Array(1);",
            // %TypedArray% 抽象基类不可直接调用或构造
            "Object.getPrototypeOf(Uint8Array)();",
            "new (Object.getPrototypeOf(Uint8Array))();",
        )) {
            assertErrorName(runtime, code, "TypeError")
        }
    }

    @Test
    fun `TypedArray set 支持类数组、TypedArray 与重叠拷贝`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var target = new Uint8Array(5);
            target.set([1, 2, 3], 1);
            var fromTyped = new Float64Array(3);
            fromTyped.set(new Uint8Array([4, 5, 6]));
            var overlapping = new Uint8Array([1, 2, 3, 4, 5]);
            overlapping.set(overlapping.subarray(0, 3), 1);
            var backward = new Uint8Array([1, 2, 3, 4, 5]);
            backward.set(backward.subarray(1, 4), 0);
            [
                target.join(','),
                fromTyped[0], fromTyped[1], fromTyped[2],
                overlapping.join(','),
                backward.join(',')
            ].join('|');
            """.trimIndent()
        )
        assertEquals("0,1,2,3,0|4|5|6|1,1,2,3,5|2,3,4,4,5", result)
    }

    @Test
    fun `subarray 与源共享缓冲区而 slice 返回独立副本`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(8);
            var array = new Uint8Array(buffer);
            array[0] = 1;
            array[1] = 2;
            array[2] = 3;
            var sub = array.subarray(1, 3);
            var tail = array.subarray(-2);
            var copy = array.slice(0, 3);
            sub[0] = 9;
            copy[0] = 8;
            [
                sub.length, sub.byteOffset, sub.buffer === buffer,
                sub[0], sub[1], array[1],
                tail.length, tail.byteOffset,
                copy.length, copy.buffer === buffer, copy[0], array[0],
                array.slice(-2).join(','),
                array.slice(3, 1).length
            ].join('|');
            """.trimIndent()
        )
        assertEquals("2|1|true|9|3|9|2|6|3|false|8|1|0,0|0", result)
    }

    @Test
    fun `map 与 filter 使用 species 构造结果并校验类型和长度`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array([1, 2, 3]);
            var mapped = array.map(function (value, index) { return value * 2 + index; });
            var filtered = array.filter(function (value) { return value % 2 === 1; });
            var holder = {};
            holder[Symbol.species] = function (length) { return new Uint8Array(length + 1); };
            array.constructor = holder;
            var speciesMapped = array.map(function (value) { return value + 1; });
            [
                mapped instanceof Uint8Array, mapped.join(','),
                filtered instanceof Uint8Array, filtered.join(','),
                speciesMapped.length, speciesMapped.join(',')
            ].join('|');
            """.trimIndent()
        )
        assertEquals("true|2,5,8|true|1,3|4|2,3,4,0", result)
        // species 允许返回不同元素类型（结果按目标类型写入），但长度不足 → TypeError
        assertEquals(
            "true|10,20,30",
            runtime.evalString(
                """
                var array = new Uint8Array([1, 2, 3]);
                var holder = {};
                holder[Symbol.species] = function (length) { return new Int32Array(length); };
                array.constructor = holder;
                var crossType = array.map(function (v) { return v * 10; });
                [crossType instanceof Int32Array, crossType.join(',')].join('|');
                """.trimIndent()
            )
        )
        assertErrorName(
            runtime,
            "var array = new Uint8Array([1, 2, 3]);" +
                " var holder = {};" +
                " holder[Symbol.species] = function () { return new Uint8Array(1); };" +
                " array.constructor = holder;" +
                " array.map(function (v) { return v; });",
            "TypeError",
        )
    }

    @Test
    fun `subarray 通过 species 构造并透传 buffer 参数`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array([1, 2, 3, 4]);
            var seen = null;
            var holder = {};
            holder[Symbol.species] = function (buffer, offset, length) {
                seen = [offset, length].join(',');
                return new Uint8Array(buffer, offset, length);
            };
            array.constructor = holder;
            var sub = array.subarray(1, 3);
            [seen, sub.join(','), sub.buffer === array.buffer].join('|');
            """.trimIndent()
        )
        assertEquals("1,2|2,3|true", result)
    }

    @Test
    fun `TypedArray 可被展开、for of 与 Array from 消费`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array([1, 2, 3]);
            var spread = [...array];
            var forOf = [];
            for (var value of array) { forOf.push(value); }
            var keys = [];
            for (var key of array.keys()) { keys.push(key); }
            var entries = [];
            for (var pair of array.entries()) { entries.push(pair[0] + ':' + pair[1]); }
            var values = [];
            var iterator = array.values();
            var step = iterator.next();
            while (!step.done) { values.push(step.value); step = iterator.next(); }
            [
                spread.join(','),
                forOf.join(','),
                keys.join(','),
                entries.join(','),
                values.join(','),
                Array.from(array).join(','),
                Object.getPrototypeOf(array.values()) === Object.getPrototypeOf([].values()),
                array.values === array[Symbol.iterator]
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "1,2,3|1,2,3|0,1,2|0:1,1:2,2:3|1,2,3|1,2,3|true|true",
            result,
        )
    }

    @Test
    fun `fill copyWithin reverse sort 与 join 系列方法`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var filled = new Uint8Array(5).fill(3);
            var ranged = new Uint8Array([1, 2, 3, 4, 5]);
            ranged.fill(9, 1, 3);
            var copied = new Uint8Array([1, 2, 3, 4, 5]);
            copied.copyWithin(0, 3);
            var reversed = new Uint8Array([1, 2, 3]);
            reversed.reverse();
            var ascending = new Uint8Array([3, 1, 2]);
            ascending.sort();
            var descending = new Uint8Array([3, 1, 2]);
            descending.sort(function (left, right) { return right - left; });
            var withNaN = new Float64Array([NaN, 3, -1, NaN, 2]);
            withNaN.sort();
            var stable = new Float64Array([10, 1, 11, 2]);
            stable.sort(function (left, right) { return (left % 10) - (right % 10); });
            var zeros = new Float64Array([1, 0, -0, 2]);
            zeros.sort();
            [
                filled.join(','),
                ranged.join(','),
                copied.join(','),
                reversed.join(','),
                ascending.join(','),
                descending.join(','),
                withNaN.join(','),
                stable.join(','),
                (1 / zeros[0]) === -Infinity && (1 / zeros[1]) === Infinity,
                new Uint8Array([1, 2, 3]).join('-'),
                new Uint8Array([1, 2, 3]).toString(),
                new Uint8Array([1, 2, 3]).toLocaleString(),
                String(new Uint8Array([4, 5])),
                new Uint8Array(0).join('-')
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "3,3,3,3,3|1,9,9,4,5|4,5,3,4,5|3,2,1|1,2,3|3,2,1|-1,2,3,NaN,NaN|10,1,11,2|" +
                "true|1-2-3|1,2,3|1,2,3|4,5|",
            result,
        )
    }

    @Test
    fun `indexOf lastIndexOf includes 与 find some every reduce 系列方法`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array([1, 2, 3, 2, 1]);
            var floats = new Float64Array([NaN, 1]);
            var found = [];
            array.forEach(function (value, index) { found.push(value + index); });
            [
                array.indexOf(2), array.lastIndexOf(2),
                array.indexOf(2, 2), array.lastIndexOf(2, 2),
                array.indexOf(9), array.includes(3), array.includes(9),
                floats.indexOf(NaN), floats.includes(NaN),
                array.find(function (v) { return v > 2; }),
                array.findIndex(function (v) { return v > 2; }),
                array.some(function (v) { return v === 4; }),
                array.every(function (v) { return v > 0; }),
                array.reduce(function (left, right) { return left + right; }),
                array.reduceRight(function (left, right) { return left - right; }),
                array.reduce(function (left, right) { return left + right; }, 10),
                array.at(0), array.at(-1), array.at(9) === undefined,
                found.join(','),
                new Uint8Array([42, 43]).lastIndexOf(43, undefined),
                new Uint8Array([42, 43]).lastIndexOf(43)
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "1|3|3|1|-1|true|false|-1|true|3|2|false|true|9|-7|19|1|1|true|1,3,5,5,5|-1|1",
            result,
        )
        assertErrorName(
            runtime,
            "new Uint8Array(0).reduce(function (left, right) { return left + right; });",
            "TypeError",
        )
        assertErrorName(
            runtime,
            "new Uint8Array(0).reduceRight(function (left, right) { return left + right; });",
            "TypeError",
        )
    }

    @Test
    fun `TypedArray 原型方法执行品牌检查`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "Uint8Array.prototype.set.call({}, [1]);",
            "Uint8Array.prototype.map.call([], function () {});",
            "Uint8Array.prototype.subarray.call(new DataView(new ArrayBuffer(4)));",
            "Object.getOwnPropertyDescriptor(Object.getPrototypeOf(Uint8Array.prototype), 'length').get.call({});",
            "Object.getOwnPropertyDescriptor(Object.getPrototypeOf(Uint8Array.prototype), 'buffer').get.call([]);",
            "Uint8Array.prototype.join.call({ length: 0 });",
        )) {
            assertErrorName(runtime, code, "TypeError")
        }
        for (code in listOf(
            "new Uint8Array(2).map(1);",
            "new Uint8Array(2).forEach(null);",
            "new Uint8Array(2).filter({});",
            "new Uint8Array(2).sort(1);",
        )) {
            assertErrorName(runtime, code, "TypeError")
        }
    }

    @Test
    fun `TypedArray 原型链、构造器互指与 instanceof 正确`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var typedArrayPrototype = Object.getPrototypeOf(Uint8Array.prototype);
            var instance = new Uint8Array(2);
            [
                typeof TypedArray === 'undefined',
                Object.getPrototypeOf(Int8Array) === Object.getPrototypeOf(Float64Array),
                Object.getPrototypeOf(Int8Array) === typedArrayPrototype.constructor,
                Object.getPrototypeOf(typedArrayPrototype) === Object.prototype,
                Object.getPrototypeOf(instance) === Uint8Array.prototype,
                Uint8Array.prototype.constructor === Uint8Array,
                instance.constructor === Uint8Array,
                instance instanceof Uint8Array,
                instance instanceof Int8Array,
                typedArrayPrototype.toString === Array.prototype.toString,
                Object.prototype.toString.call(instance),
                Uint8Array.prototype[Symbol.toStringTag] === undefined,
                instance[Symbol.toStringTag],
                Uint8Array[Symbol.species] === Uint8Array
            ].join('|');
            """.trimIndent()
        )
        assertEquals(
            "true|true|true|true|true|true|true|true|false|true|[object Uint8Array]" +
                "|true|Uint8Array|true",
            result,
        )
    }

    @Test
    fun `子类继承 TypedArray 构造器时使用子类原型`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            class MyArray extends Uint8Array {}
            var instance = new MyArray(3);
            instance[0] = 7;
            [
                instance.length,
                instance[0],
                instance instanceof MyArray,
                instance instanceof Uint8Array,
                Object.getPrototypeOf(instance) === MyArray.prototype,
                Object.getPrototypeOf(MyArray) === Uint8Array
            ].join('|');
            """.trimIndent()
        )
        assertEquals("3|7|true|true|true|true", result)
    }

    @Test
    fun `BYTES_PER_ELEMENT 在构造器与原型上均为只读常量`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var constructorDescriptor = Object.getOwnPropertyDescriptor(Uint8Array, 'BYTES_PER_ELEMENT');
            var prototypeDescriptor = Object.getOwnPropertyDescriptor(Int16Array.prototype, 'BYTES_PER_ELEMENT');
            [
                Int8Array.BYTES_PER_ELEMENT, Uint8ClampedArray.BYTES_PER_ELEMENT,
                Int16Array.BYTES_PER_ELEMENT, Uint16Array.BYTES_PER_ELEMENT,
                Int32Array.BYTES_PER_ELEMENT, Uint32Array.BYTES_PER_ELEMENT,
                Float32Array.BYTES_PER_ELEMENT, Float64Array.BYTES_PER_ELEMENT,
                Uint8Array.prototype.BYTES_PER_ELEMENT,
                new Float32Array(1).BYTES_PER_ELEMENT,
                constructorDescriptor.writable, constructorDescriptor.enumerable,
                constructorDescriptor.configurable,
                prototypeDescriptor.writable, prototypeDescriptor.configurable
            ].join(',');
            """.trimIndent()
        )
        assertEquals(
            "1,1,2,2,4,4,4,8,1,4,false,false,false,false,false",
            result,
        )
        // 严格模式下写入只读常量抛 TypeError
        assertErrorName(runtime, "(function () { 'use strict'; Uint8Array.BYTES_PER_ELEMENT = 4; })();", "TypeError")
    }

    @Test
    fun `ArrayBuffer isView 识别 TypedArray 并与 DataView 共享字节`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(4);
            var array = new Uint8Array(buffer);
            array[0] = 0x12;
            array[1] = 0x34;
            var view = new DataView(buffer);
            [
                ArrayBuffer.isView(array),
                ArrayBuffer.isView(view),
                ArrayBuffer.isView(buffer),
                ArrayBuffer.isView({}),
                ArrayBuffer.isView(),
                array.buffer === buffer,
                view.getUint8(0), view.getUint8(1),
                new Uint8Array(buffer, 2, 2).byteLength
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,true,false,false,false,true,18,52,2", result)
    }

    @Test
    fun `TypedArray 与 ArrayBuffer slice 的 species 机制共存`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(4);
            var array = new Uint8Array(buffer);
            array[0] = 1;
            array[1] = 2;
            var copy = buffer.slice(0, 2);
            var holder = {};
            holder[Symbol.species] = function (length) { return new ArrayBuffer(length + 1); };
            buffer.constructor = holder;
            var speciesCopy = buffer.slice(1, 3);
            [
                buffer instanceof ArrayBuffer,
                copy.byteLength,
                new Uint8Array(copy)[0], new Uint8Array(copy)[1],
                speciesCopy.byteLength,
                Uint8Array[Symbol.species] === Uint8Array,
                Object.getPrototypeOf(Uint8Array)[Symbol.species] === Object.getPrototypeOf(Uint8Array)
            ].join('|');
            """.trimIndent()
        )
        assertEquals("true|2|1|2|3|true|true", result)
    }

    @Test
    fun `TypedArray 访问器只读且返回内部槽`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var buffer = new ArrayBuffer(8);
            var array = new Int32Array(buffer, 4, 1);
            var prototype = Object.getPrototypeOf(Int32Array.prototype);
            [
                array.buffer === buffer,
                array.byteOffset,
                array.byteLength,
                array.length,
                array.BYTES_PER_ELEMENT,
                Object.getOwnPropertyDescriptor(prototype, 'buffer').set === undefined,
                Object.getOwnPropertyDescriptor(prototype, 'byteLength').set === undefined,
                Object.getOwnPropertyDescriptor(prototype, 'byteOffset').set === undefined,
                Object.getOwnPropertyDescriptor(prototype, 'length').set === undefined
            ].join(',');
            """.trimIndent()
        )
        assertEquals("true,4,4,1,4,true,true,true,true", result)
    }

    @Test
    fun `TypedArray 索引写入仍转换越界值的 valueOf`() = runTest {
        val runtime = TestRuntime()
        // 规范：先执行 ToNumber(value)，再按当前长度检查下标是否有效
        val result = runtime.evalString(
            """
            var called = 0;
            var array = new Uint8Array(1);
            var value = { valueOf: function () { called = called + 1; return 5; } };
            array[0] = value;
            var bounded = called;
            array[5] = value;
            [bounded, called].join(',');
            """.trimIndent()
        )
        assertEquals("1,2", result)
    }

    @Test
    fun `TypedArray 非法 numeric index 仍执行值转换且不写入`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array(2);
            array[0] = 7;
            var called = 0;
            var value = {
              valueOf: function () {
                called = called + 1;
                throw new Error("boom");
              }
            };
            function errorName(fn) {
              try {
                fn();
                return "none";
              } catch (e) {
                return e.name;
              }
            }
            var converted = [
              errorName(function () { array["1.1"] = value; }),
              errorName(function () { array["-1"] = value; }),
              errorName(function () { array["NaN"] = value; }),
              errorName(function () { array["-0"] = value; })
            ].join(",");
            array["-0"] = 9;
            array["1.1"] = 9;
            array["-1"] = 9;
            [converted, called, array[0], array[1]].join("|");
            """.trimIndent()
        )
        assertEquals("Error,Error,Error,Error|4|7|0", result)
    }

    @Test
    fun `TypedArray 自定义属性与数字索引互不干扰`() = runTest {
        val runtime = TestRuntime()
        val result = runtime.evalString(
            """
            var array = new Uint8Array(2);
            array[0] = 1;
            array.custom = 'x';
            array['01'] = 'leading';
            array['1e21'] = 'exponent';
            var keys = [];
            for (var key in array) { keys.push(key); }
            [
                Object.keys(array).join(','),
                array.length,
                array['0'],
                array['01'],
                array['1e21'],
                delete array.custom,
                Object.keys(array).join(',')
            ].join('|');
            """.trimIndent()
        )
        assertEquals("0,1,custom,01,1e21|2|1|leading|exponent|true|0,1,01,1e21", result)
    }
}
