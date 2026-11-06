package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Reflect 13 个方法的基础语义、receiver 与 Proxy 联动测试。
 */
class ReflectTest {

    @Test
    fun `Reflect 基础属性操作`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { a: 1 };
            var hasA = Reflect.has(target, 'a');
            var got = Reflect.get(target, 'a');
            var setOk = Reflect.set(target, 'b', 2);
            var keys = Reflect.ownKeys(target).join(',');
            var deleted = Reflect.deleteProperty(target, 'a');
            var hasAfterDelete = Reflect.has(target, 'a');
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("hasA"))
        assertEquals(1.0, runtime.evalNumber("got"))
        assertEquals(true, runtime.evalBoolean("setOk"))
        assertEquals("a,b", runtime.evalString("keys"))
        assertEquals(true, runtime.evalBoolean("deleted"))
        assertEquals(false, runtime.evalBoolean("hasAfterDelete"))
    }

    @Test
    fun `Reflect 描述符与原型操作`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            var defined = Reflect.defineProperty(target, 'x', {
                value: 7, writable: true, enumerable: true, configurable: true
            });
            var descriptor = Reflect.getOwnPropertyDescriptor(target, 'x');
            var proto = { marker: 1 };
            var setProto = Reflect.setPrototypeOf(target, proto);
            var gotProto = Reflect.getPrototypeOf(target);
            var extensible = Reflect.isExtensible(target);
            var prevented = Reflect.preventExtensions(target);
            var extensibleAfter = Reflect.isExtensible(target);
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("defined"))
        assertEquals(7.0, runtime.evalNumber("descriptor.value"))
        assertEquals(true, runtime.evalBoolean("setProto"))
        assertEquals(1.0, runtime.evalNumber("gotProto.marker"))
        assertEquals(true, runtime.evalBoolean("extensible"))
        assertEquals(true, runtime.evalBoolean("prevented"))
        assertEquals(false, runtime.evalBoolean("extensibleAfter"))
    }

    @Test
    fun `Reflect 的 apply 与 construct`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var applied = Reflect.apply(function (a, b) { return a + b; }, null, [2, 3]);

            function Base() {}
            function Other() {}
            Other.prototype.marker = 1;
            var instance = Reflect.construct(Base, [], Other);
            var marker = instance.marker;
            """.trimIndent()
        )
        assertEquals(5.0, runtime.evalNumber("applied"))
        assertEquals(1.0, runtime.evalNumber("marker"))
    }

    @Test
    fun `Reflect 的 set 使用 receiver 调用 setter`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var proto = { set x(v) { this.y = v; } };
            var target = Object.create(proto);
            var receiver = {};
            var ok = Reflect.set(target, 'x', 5, receiver);
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("ok"))
        assertEquals(5.0, runtime.evalNumber("receiver.y"))
    }

    @Test
    fun `Reflect 方法触发 Proxy trap`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = '';
            var proxy = new Proxy({}, {
                get: function () { log += 'get;'; return 42; },
                set: function () { log += 'set;'; return true; },
                has: function () { log += 'has;'; return true; },
                ownKeys: function () { log += 'keys;'; return ['a']; },
                getOwnPropertyDescriptor: function () {
                    log += 'gopd;';
                    return { value: 1, configurable: true, enumerable: true, writable: true };
                }
            });
            var got = Reflect.get(proxy, 'x');
            var setOk = Reflect.set(proxy, 'x', 1);
            var has = Reflect.has(proxy, 'x');
            var keys = Reflect.ownKeys(proxy).join(',');
            var descriptor = Reflect.getOwnPropertyDescriptor(proxy, 'a');
            """.trimIndent()
        )
        assertEquals(42.0, runtime.evalNumber("got"))
        assertEquals(true, runtime.evalBoolean("setOk"))
        assertEquals(true, runtime.evalBoolean("has"))
        assertEquals("a", runtime.evalString("keys"))
        assertEquals(1.0, runtime.evalNumber("descriptor.value"))
        assertEquals("get;set;has;keys;gopd;", runtime.evalString("log"))
    }

    @Test
    fun `Reflect 的 toStringTag 与参数校验`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var tag = Object.prototype.toString.call(Reflect);
            var applyThrew = false;
            try {
                Reflect.apply(1, null, []);
            } catch (error) {
                applyThrew = error instanceof TypeError;
            }
            var getThrew = false;
            try {
                Reflect.get(1, 'x');
            } catch (error) {
                getThrew = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals("[object Reflect]", runtime.evalString("tag"))
        assertEquals(true, runtime.evalBoolean("applyThrew"))
        assertEquals(true, runtime.evalBoolean("getThrew"))
    }
}
