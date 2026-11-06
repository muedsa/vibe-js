package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NewTarget 语义测试：Reflect.construct / new.target / super / bound / species。
 */
class NewTargetTest {

    @Test
    fun `Reflect construct 对内建构造器使用 NewTarget 原型`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function Sub() {}
            var cases = [
                [Object, []],
                [Array, []],
                [Boolean, [true]],
                [Number, [1]],
                [String, ['x']],
                [Date, []],
                [RegExp, ['a']],
                [Map, []],
                [Set, []],
                [WeakMap, []],
                [WeakSet, []],
                [Promise, [function () {}]],
                [Error, ['x']],
                [TypeError, ['x']],
                [AggregateError, [[], 'x']],
                [SuppressedError, [1, 2, 'x']],
                [DisposableStack, []],
                [AsyncDisposableStack, []],
                [Function, ['return 1']],
                [Object.getPrototypeOf(async function () {}).constructor, ['return 1']],
                [Iterator, []]
            ];
            var failures = [];
            for (var i = 0; i < cases.length; i++) {
                var ctor = cases[i][0];
                try {
                    var instance = Reflect.construct(ctor, cases[i][1], Sub);
                    if (!(instance instanceof Sub)) {
                        failures.push(ctor.name + ':prototype');
                    }
                } catch (error) {
                    failures.push(ctor.name + ':' + error.message);
                }
            }
            failures.join(',');
            """.trimIndent()
        )
        assertEquals("", runtime.evalString("failures.join(',')"))
    }

    @Test
    fun `Reflect construct 让 new_target 指向传入的构造器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var seen = null;
            function F() { seen = new.target; }
            function Sub() {}
            Reflect.construct(F, [], Sub);
            var direct = null;
            function G() { direct = new.target; }
            new G();
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("seen === Sub"))
        assertEquals(true, runtime.evalBoolean("direct === G"))
    }

    @Test
    fun `super 链上的 new_target 保持为最外层 NewTarget`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var baseSeen = null;
            var derivedSeen = null;
            class Base {
                constructor() { baseSeen = new.target; }
            }
            class Derived extends Base {
                constructor() { derivedSeen = new.target; super(); }
            }
            function Other() {}
            var instance = Reflect.construct(Derived, [], Other);
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("baseSeen === Other"))
        assertEquals(true, runtime.evalBoolean("derivedSeen === Other"))
        assertEquals(true, runtime.evalBoolean("instance instanceof Other"))
    }

    @Test
    fun `bound function 透传 NewTarget`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var seen = null;
            function F() { seen = new.target; }
            var Bound = F.bind(null);
            function Sub() {}
            var instance = Reflect.construct(Bound, [], Sub);
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("seen === Sub"))
        assertEquals(true, runtime.evalBoolean("instance instanceof Sub"))
    }

    @Test
    fun `内建子类的 species 创建使用子类原型`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            class MyArray extends Array {}
            var arr = new MyArray(1, 2, 3);
            var mapped = arr.map(function (x) { return x * 2; });
            var filtered = arr.filter(function (x) { return x > 1; });
            var sliced = arr.slice(1);
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("mapped instanceof MyArray"))
        assertEquals(true, runtime.evalBoolean("filtered instanceof MyArray"))
        assertEquals(true, runtime.evalBoolean("sliced instanceof MyArray"))
    }

    @Test
    fun `Object 与 RegExp 在 NewTarget 为子类时新建对象`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function Sub() {}
            var plain = {};
            var objectInstance = Reflect.construct(Object, [plain], Sub);
            var objectIsNew = objectInstance !== plain;

            var pattern = /a/;
            var regexpInstance = Reflect.construct(RegExp, [pattern], Sub);
            var regexpIsNew = regexpInstance !== pattern;
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("objectIsNew"))
        assertEquals(true, runtime.evalBoolean("objectInstance instanceof Sub"))
        assertEquals(true, runtime.evalBoolean("regexpIsNew"))
        assertEquals(true, runtime.evalBoolean("regexpInstance instanceof Sub"))
    }

    @Test
    fun `直接调用内建构造器不受 NewTarget 影响`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var object = Object(1);
            var array = Array(2);
            var string = String(3);
            var number = Number('4');
            var boolean = Boolean(0);
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("object instanceof Number"))
        assertEquals(2.0, runtime.evalNumber("array.length"))
        assertEquals("3", runtime.evalString("string"))
        assertEquals(4.0, runtime.evalNumber("number"))
        assertEquals(false, runtime.evalBoolean("boolean"))
    }
}
