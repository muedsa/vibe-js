package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 属性模型与原型链：new、Object.create、defineProperty、getter/setter、原型链查找。
 */
class PropertyAndPrototypeTest {

    @Test
    fun `new 与构造函数原型方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "hi a",
            runtime.evalString(
                """
                function Person(name) { this.name = name; }
                Person.prototype.greet = function () { return "hi " + this.name; };
                var p = new Person("a");
                p.greet();
                """.trimIndent()
            )
        )
        assertEquals(
            2.0,
            runtime.evalNumber(
                """
                function Box(v) { this.v = v; }
                var b = new Box(2);
                b.v;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `构造函数返回对象时覆盖实例`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber(
                "function F() { this.x = 1; return { y: 2 }; } new F().y"
            )
        )
        assertEquals(
            1.0,
            runtime.evalNumber(
                "function F() { this.x = 1; return 42; } new F().x"
            )
        )
    }

    @Test
    fun `Object_create 与原型链查找`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var o = Object.create({ z: 1 }); o.z"))
        assertEquals(
            false,
            runtime.evalBoolean("var o = Object.create({ z: 1 }); o.hasOwnProperty('z')")
        )
    }

    @Test
    fun `defineProperty 访问器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            7.0,
            runtime.evalNumber(
                """
                var o = {};
                Object.defineProperty(o, "x", { get: function () { return 7; }, enumerable: true });
                o.x;
                """.trimIndent()
            )
        )
        assertEquals(
            "x",
            runtime.evalString(
                """
                var o = {};
                Object.defineProperty(o, "x", { value: 1, enumerable: true });
                Object.keys(o).join(",");
                """.trimIndent()
            )
        )
    }

    @Test
    fun `原型访问器与 setter`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            5.0,
            runtime.evalNumber(
                """
                var proto = {};
                Object.defineProperty(proto, "x", { set: function (v) { this.stored = v * 5; } });
                var o = Object.create(proto);
                o.x = 1;
                o.stored;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `__proto__ 访问器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var o = {}; o.__proto__ = { z: 1 }; o.z"))
        assertEquals(
            true,
            runtime.evalBoolean("var o = {}; Object.getPrototypeOf(o) === Object.prototype")
        )
    }

    @Test
    fun `只读属性赋值`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber(
                """
                var o = {};
                Object.defineProperty(o, "x", { value: 1, writable: false });
                o.x = 2;
                o.x;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `hasOwnProperty 与原型链`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean("var o = { a: 1 }; o.hasOwnProperty('a')")
        )
        assertEquals(
            false,
            runtime.evalBoolean("({}).hasOwnProperty('toString')")
        )
    }

    @Test
    fun `函数 name 与 length 属性`() = runTest {
        val runtime = TestRuntime()
        assertEquals("foo", runtime.evalString("function foo(a, b) {}; foo.name"))
        assertEquals(2.0, runtime.evalNumber("function foo(a, b) {}; foo.length"))
    }

    @Test
    fun `函数 call apply bind`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            3.0,
            runtime.evalNumber("function f(a) { return this.x + a; } f.call({ x: 1 }, 2)")
        )
        assertEquals(
            3.0,
            runtime.evalNumber("function f(a) { return this.x + a; } f.apply({ x: 1 }, [2])")
        )
        assertEquals(
            3.0,
            runtime.evalNumber("function f(a) { return this.x + a; } var g = f.bind({ x: 1 }, 2); g()")
        )
    }
}
