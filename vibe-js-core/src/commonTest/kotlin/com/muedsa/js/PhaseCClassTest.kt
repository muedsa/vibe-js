package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 阶段 C：class / extends / super / new.target / 标签模板。
 */
class PhaseCClassTest {

    @Test
    fun `类声明与实例方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber(
                """
                class A {
                    constructor(x) { this.x = x; }
                    getX() { return this.x; }
                }
                var a = new A(2);
                a.getX();
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean("class B {}; typeof B === 'function'")
        )
        assertEquals(
            true,
            runtime.evalBoolean("class C {}; C.prototype.constructor === C")
        )
    }

    @Test
    fun `静态方法与访问器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber("class B { static f() { return 1; } } B.f()")
        )
        assertEquals(
            1.0,
            runtime.evalNumber("class C { get x() { return 1; } } new C().x")
        )
        assertEquals(
            2.0,
            runtime.evalNumber(
                "class D { set x(v) { this.y = v; } } var a = new D(); a.x = 2; a.y"
            )
        )
    }

    @Test
    fun `继承与 super 方法调用`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "rex!:dog:true:true",
            runtime.evalString(
                """
                class Animal {
                    constructor(name) { this.name = name; }
                    speak() { return this.name; }
                }
                class Dog extends Animal {
                    constructor(name) {
                        super(name);
                        this.kind = 'dog';
                    }
                    speak() { return super.speak() + '!'; }
                }
                var d = new Dog('rex');
                d.speak() + ':' + d.kind + ':' + (d instanceof Animal) + ':' + (d instanceof Dog);
                """.trimIndent()
            )
        )
    }

    @Test
    fun `默认派生构造器与静态继承`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            3.0,
            runtime.evalNumber(
                "class A { constructor(x) { this.x = x; } } class B extends A {} new B(3).x"
            )
        )
        assertEquals(
            "a",
            runtime.evalString(
                "class C { static s() { return 'a'; } } class D extends C {} D.s()"
            )
        )
    }

    @Test
    fun `类表达式与类构造器调用限制`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            3.0,
            runtime.evalNumber("var C = class { m() { return 3; } }; new C().m()")
        )
        val error = assertFailsWith<JSException> { runtime.eval("class A {}; A();") }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `new_target`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            true,
            runtime.evalBoolean(
                "function F() { this.isNew = new.target === F; } new F().isNew"
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean("var f = function () { return new.target === undefined; }; f()")
        )
        assertEquals(
            false,
            runtime.evalBoolean(
                "class A { constructor() { this.isA = new.target === A; } } class B extends A {} new B().isA"
            )
        )
    }

    @Test
    fun `标签模板与 String_raw`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "a|b|c:1,2",
            runtime.evalString(
                """
                function tag(strings, ...values) {
                    return strings.raw.join('|') + ':' + values.join(',');
                }
                tag`a${'$'}{1}b${'$'}{2}c`;
                """.trimIndent()
            )
        )
        assertEquals("a1b", runtime.evalString("String.raw\u0060a\${1}b\u0060"))
    }
}
