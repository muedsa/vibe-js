package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proxy/Reflect 语义补齐测试：IsArray、toString、instanceof、freeze、Reflect.defineProperty。
 */
class ProxyReflectCompletionTest {

    @Test
    fun `Array isArray 与 Object toString 穿透 Proxy`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var arrayProxy = new Proxy([1, 2], {});
            var functionProxy = new Proxy(function () {}, {});
            var isArray = Array.isArray(arrayProxy);
            var arrayTag = Object.prototype.toString.call(arrayProxy);
            var functionTag = Object.prototype.toString.call(functionProxy);
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("isArray"))
        assertEquals("[object Array]", runtime.evalString("arrayTag"))
        assertEquals("[object Function]", runtime.evalString("functionTag"))
    }

    @Test
    fun `instanceof 穿透 Proxy 原型链`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function C() {}
            var instance = new C();
            var proxy = new Proxy(instance, {});
            var result = proxy instanceof C;
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("result"))
    }

    @Test
    fun `Object freeze 对带描述符 trap 的 Proxy 生效`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { a: 1 };
            var proxy = new Proxy(target, {
                getOwnPropertyDescriptor: function (t, key) {
                    return Object.getOwnPropertyDescriptor(t, key);
                }
            });
            Object.freeze(proxy);
            var frozen = Object.isFrozen(proxy);
            var targetFrozen = Object.isFrozen(target);
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("frozen"))
        assertEquals(true, runtime.evalBoolean("targetFrozen"))
    }

    @Test
    fun `Reflect defineProperty 在不可配置属性上返回 false 而不是抛错`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            var first = Reflect.defineProperty(target, 'x', {
                value: 1, writable: false, configurable: false
            });
            var second = Reflect.defineProperty(target, 'x', { value: 2 });
            var threw = false;
            try {
                Reflect.defineProperty(target, 'x', { value: 3 });
            } catch (error) {
                threw = true;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("first"))
        assertEquals(false, runtime.evalBoolean("second"))
        assertEquals(false, runtime.evalBoolean("threw"))
    }

    @Test
    fun `Object defineProperty 在不可配置属性上仍然抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'x', {
                value: 1, writable: false, configurable: false
            });
            var threw = false;
            try {
                Object.defineProperty(target, 'x', { value: 2 });
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `get trap 对不可配置且无 getter 的访问器必须返回 undefined`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'x', {
                get: undefined, set: undefined, configurable: false
            });
            var proxy = new Proxy(target, {
                get: function () { return 1; }
            });
            var threw = false;
            try {
                proxy.x;
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }
}
