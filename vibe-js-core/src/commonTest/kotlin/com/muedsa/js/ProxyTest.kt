package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proxy 的 trap 分派、不变量与可撤销代理测试。
 */
class ProxyTest {

    @Test
    fun `Proxy 的 get 与 set trap 生效`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = '';
            var target = { value: 1 };
            var proxy = new Proxy(target, {
                get: function (t, key, receiver) {
                    log += 'get:' + String(key) + ';';
                    return t[key];
                },
                set: function (t, key, value, receiver) {
                    log += 'set:' + String(key) + ';';
                    t[key] = value;
                    return true;
                }
            });
            proxy.value = 2;
            var read = proxy.value;
            """.trimIndent()
        )
        assertEquals(2.0, runtime.evalNumber("read"))
        assertEquals("set:value;get:value;", runtime.evalString("log"))
    }

    @Test
    fun `没有 trap 时转发到 target`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { a: 1 };
            var proxy = new Proxy(target, {});
            proxy.b = 2;
            var sum = proxy.a + proxy.b;
            var keys = Object.keys(proxy).join(',');
            var hasB = 'b' in proxy;
            delete proxy.b;
            var hasAfterDelete = 'b' in proxy;
            """.trimIndent()
        )
        assertEquals(3.0, runtime.evalNumber("sum"))
        assertEquals("a,b", runtime.evalString("keys"))
        assertEquals(true, runtime.evalBoolean("hasB"))
        assertEquals(false, runtime.evalBoolean("hasAfterDelete"))
    }

    @Test
    fun `has 与 deleteProperty trap 生效`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = '';
            var target = { a: 1 };
            var proxy = new Proxy(target, {
                has: function (t, key) { log += 'has:' + String(key) + ';'; return key === 'virtual'; },
                deleteProperty: function (t, key) { log += 'delete:' + String(key) + ';'; return true; }
            });
            var hasA = 'a' in proxy;
            var hasVirtual = 'virtual' in proxy;
            var deleted = delete proxy.a;
            """.trimIndent()
        )
        assertEquals(false, runtime.evalBoolean("hasA"))
        assertEquals(true, runtime.evalBoolean("hasVirtual"))
        assertEquals(true, runtime.evalBoolean("deleted"))
        assertEquals("has:a;has:virtual;delete:a;", runtime.evalString("log"))
    }

    @Test
    fun `ownKeys 与 getOwnPropertyDescriptor trap 生效`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { hidden: 1 };
            var proxy = new Proxy(target, {
                ownKeys: function (t) { return ['x', 'y']; },
                getOwnPropertyDescriptor: function (t, key) {
                    return { value: key, enumerable: true, configurable: true, writable: true };
                },
                get: function (t, key) { return key; }
            });
            var keys = Object.keys(proxy).join(',');
            var descriptor = Object.getOwnPropertyDescriptor(proxy, 'x');
            var values = Object.values(proxy).join(',');
            """.trimIndent()
        )
        assertEquals("x,y", runtime.evalString("keys"))
        assertEquals("x", runtime.evalString("descriptor.value"))
        assertEquals(true, runtime.evalBoolean("descriptor.enumerable"))
        assertEquals("x,y", runtime.evalString("values"))
    }

    @Test
    fun `defineProperty 与 getPrototypeOf trap 生效`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            var proto = { marker: 1 };
            var defined = '';
            var proxy = new Proxy(target, {
                defineProperty: function (t, key, descriptor) {
                    defined += String(key) + ':' + descriptor.value + ';';
                    return true;
                },
                getPrototypeOf: function (t) { return proto; }
            });
            Object.defineProperty(proxy, 'a', { value: 7, configurable: true });
            var protoResult = Object.getPrototypeOf(proxy);
            var marker = protoResult.marker;
            """.trimIndent()
        )
        assertEquals("a:7;", runtime.evalString("defined"))
        assertEquals(1.0, runtime.evalNumber("marker"))
    }

    @Test
    fun `isExtensible 与 preventExtensions trap 的不变量`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            var proxy = new Proxy(target, {
                isExtensible: function (t) { return false; }
            });
            var isExtensibleThrew = false;
            try {
                Object.isExtensible(proxy);
            } catch (error) {
                isExtensibleThrew = error instanceof TypeError;
            }

            var target2 = {};
            var proxy2 = new Proxy(target2, {
                preventExtensions: function (t) { return true; }
            });
            var preventExtensionsThrew = false;
            try {
                Object.preventExtensions(proxy2);
            } catch (error) {
                preventExtensionsThrew = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("isExtensibleThrew"))
        assertEquals(true, runtime.evalBoolean("preventExtensionsThrew"))
    }

    @Test
    fun `apply 与 construct trap 生效`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var callable = new Proxy(function (a, b) { return a + b; }, {
                apply: function (target, thisArg, args) { return target(args[0], args[1]) * 10; }
            });
            var applied = callable(1, 2);
            var typeOfCallable = typeof callable;

            function Point(x) { this.x = x; }
            var Constructable = new Proxy(Point, {
                construct: function (target, args, newTarget) { return { x: args[0] * 2 }; }
            });
            var instance = new Constructable(3);
            """.trimIndent()
        )
        assertEquals(30.0, runtime.evalNumber("applied"))
        assertEquals("function", runtime.evalString("typeOfCallable"))
        assertEquals(6.0, runtime.evalNumber("instance.x"))
    }

    @Test
    fun `可撤销代理在 revoke 后抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var revocable = Proxy.revocable({ a: 1 }, {});
            var before = revocable.proxy.a;
            revocable.revoke();
            var threw = false;
            try {
                revocable.proxy.a;
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(1.0, runtime.evalNumber("before"))
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `get 不变量：不可配置不可写属性必须返回相同值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'fixed', { value: 1, writable: false, configurable: false });
            var proxy = new Proxy(target, {
                get: function () { return 2; }
            });
            var threw = false;
            try {
                proxy.fixed;
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `ownKeys 不变量：不得遗漏不可配置属性`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'fixed', { value: 1, configurable: false });
            var proxy = new Proxy(target, {
                ownKeys: function () { return []; }
            });
            var threw = false;
            try {
                Object.keys(proxy);
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `has 不变量：不得对不可配置属性返回 false`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'fixed', { value: 1, configurable: false });
            var proxy = new Proxy(target, {
                has: function () { return false; }
            });
            var threw = false;
            try {
                'fixed' in proxy;
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `preventExtensions 不变量：target 仍可扩展时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            var proxy = new Proxy(target, {
                preventExtensions: function () { return true; }
            });
            var threw = false;
            try {
                Object.preventExtensions(proxy);
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }
}
