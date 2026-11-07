package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proxy 不变量与边界的回归测试（L2.4）。
 *
 * 覆盖 ECMAScript 10.5.x 中 [[GetOwnProperty]]、[[DefineOwnProperty]]、
 * [[Delete]]、[[OwnPropertyKeys]]、[[Get]]/[[Set]] 的结果校验，
 * 以及 Function.prototype.toString、JSON.stringify 与撤销后的所有 trap。
 */
class ProxyInvariantTest {

    @Test
    fun `getOwnPropertyDescriptor 不得凭空报告 target 没有的不可配置属性`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var proxy = new Proxy({}, {
                getOwnPropertyDescriptor: function () {
                    return { value: 1, configurable: false };
                }
            });
            var threw = false;
            try {
                Object.getOwnPropertyDescriptor(proxy, 'x');
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `getOwnPropertyDescriptor 对可配置属性返回 undefined 合法`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { a: 1 };
            var proxy = new Proxy(target, {
                getOwnPropertyDescriptor: function () { return undefined; }
            });
            var descriptor = Object.getOwnPropertyDescriptor(proxy, 'a');
            """.trimIndent()
        )
        assertEquals("undefined", runtime.evalString("typeof descriptor"))
    }

    @Test
    fun `getOwnPropertyDescriptor 报告不可配置且不可写而 target 可写时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'a', { value: 1, writable: true, configurable: false });
            var proxy = new Proxy(target, {
                getOwnPropertyDescriptor: function () {
                    return { value: 1, writable: false, configurable: false };
                }
            });
            var threw = false;
            try {
                Object.getOwnPropertyDescriptor(proxy, 'a');
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `defineProperty 不得在不可扩展 target 上报告新属性`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.preventExtensions(target);
            var proxy = new Proxy(target, {
                defineProperty: function () { return true; }
            });
            var threw = false;
            try {
                Object.defineProperty(proxy, 'x', { value: 1 });
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `defineProperty 不得把 target 的可配置属性改成不可配置`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'x', {
                value: 1, writable: true, enumerable: true, configurable: true
            });
            var proxy = new Proxy(target, {
                defineProperty: function () { return true; }
            });
            var threw = false;
            try {
                Object.defineProperty(proxy, 'x', { configurable: false });
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `defineProperty 返回 false 时对可配置属性保持返回 false`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { x: 1 };
            var proxy = new Proxy(target, {
                defineProperty: function () { return false; }
            });
            var result = Reflect.defineProperty(proxy, 'x', { value: 2 });
            """.trimIndent()
        )
        assertEquals(false, runtime.evalBoolean("result"))
        assertEquals(1.0, runtime.evalNumber("target.x"))
    }

    @Test
    fun `deleteProperty 声称删除不可配置属性时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'x', { value: 1, configurable: false });
            var proxy = new Proxy(target, {
                deleteProperty: function () { return true; }
            });
            var threw = false;
            try {
                Reflect.deleteProperty(proxy, 'x');
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `deleteProperty 对不可配置属性返回 false 合法且不抛错`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'x', { value: 1, configurable: false });
            var proxy = new Proxy(target, {
                deleteProperty: function () { return false; }
            });
            var result = Reflect.deleteProperty(proxy, 'x');
            """.trimIndent()
        )
        assertEquals(false, runtime.evalBoolean("result"))
        assertEquals(true, runtime.evalBoolean("target.x === 1"))
    }

    @Test
    fun `deleteProperty 成功删除可配置属性合法`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { x: 1 };
            var proxy = new Proxy(target, {
                deleteProperty: function (t, key) { return delete t[key]; }
            });
            var result = Reflect.deleteProperty(proxy, 'x');
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("result"))
        assertEquals(false, runtime.evalBoolean("'x' in target"))
    }

    @Test
    fun `ownKeys 返回重复键时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var proxy = new Proxy({}, {
                ownKeys: function () { return ['a', 'a']; }
            });
            var threw = false;
            try {
                Object.getOwnPropertyNames(proxy);
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `ownKeys 省略 target 的不可配置键时抛 TypeError`() = runTest {
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
                Object.getOwnPropertyNames(proxy);
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `ownKeys 对不可扩展 target 返回多余键时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { a: 1 };
            Object.preventExtensions(target);
            var proxy = new Proxy(target, {
                ownKeys: function () { return ['a', 'b']; }
            });
            var threw = false;
            try {
                Object.getOwnPropertyNames(proxy);
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `ownKeys 返回非字符串或符号元素时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var proxy = new Proxy({}, {
                ownKeys: function () { return [1]; }
            });
            var threw = false;
            try {
                Object.getOwnPropertyNames(proxy);
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `ownKeys 与 getOwnPropertyDescriptor 可以提供合法的虚拟属性`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var proxy = new Proxy({}, {
                ownKeys: function () { return ['virtual']; },
                getOwnPropertyDescriptor: function () {
                    return { value: 1, writable: true, enumerable: true, configurable: true };
                },
                get: function () { return 1; }
            });
            var keys = Object.keys(proxy).join(',');
            """.trimIndent()
        )
        assertEquals("virtual", runtime.evalString("keys"))
    }

    @Test
    fun `get trap 对不可配置不可写属性返回不同值时抛 TypeError`() = runTest {
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
    fun `set trap 对不可配置不可写属性返回 true 时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = {};
            Object.defineProperty(target, 'fixed', { value: 1, writable: false, configurable: false });
            var proxy = new Proxy(target, {
                set: function () { return true; }
            });
            var threw = false;
            try {
                proxy.fixed = 2;
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `set trap 对可写属性返回 true 合法`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = { writable: 1 };
            var proxy = new Proxy(target, {
                set: function (t, key, value) { t[key] = value; return true; }
            });
            proxy.writable = 2;
            """.trimIndent()
        )
        assertEquals(2.0, runtime.evalNumber("target.writable"))
    }

    @Test
    fun `撤销后所有 trap 都抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var handle = Proxy.revocable(function () {}, {});
            var proxy = handle.proxy;
            handle.revoke();
            var failures = [];
            function check(name, action) {
                try {
                    action();
                    failures.push(name + ':no-throw');
                } catch (error) {
                    if (!(error instanceof TypeError)) {
                        failures.push(name + ':' + error.name);
                    }
                }
            }
            check('get', function () { proxy.x; });
            check('set', function () { proxy.x = 1; });
            check('has', function () { 'x' in proxy; });
            check('delete', function () { delete proxy.x; });
            check('ownKeys', function () { Object.keys(proxy); });
            check('gopd', function () { Object.getOwnPropertyDescriptor(proxy, 'x'); });
            check('define', function () { Object.defineProperty(proxy, 'x', { value: 1 }); });
            check('getPrototypeOf', function () { Object.getPrototypeOf(proxy); });
            check('setPrototypeOf', function () { Object.setPrototypeOf(proxy, {}); });
            check('isExtensible', function () { Object.isExtensible(proxy); });
            check('preventExtensions', function () { Object.preventExtensions(proxy); });
            check('apply', function () { proxy(); });
            check('construct', function () { new proxy(); });
            failures.join(',');
            """.trimIndent()
        )
        assertEquals("", runtime.evalString("failures.join(',')"))
    }

    @Test
    fun `Function prototype toString 对可调用 Proxy 返回 native code`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var target = function () {};
            var touched = false;
            target.toString = function () { touched = true; return 'custom'; };
            var proxy = new Proxy(target, {});
            var text = Function.prototype.toString.call(proxy);
            var nonCallableThrew = false;
            try {
                Function.prototype.toString.call(new Proxy({}, {}));
            } catch (error) {
                nonCallableThrew = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals("function () { [native code] }", runtime.evalString("text"))
        assertEquals(false, runtime.evalBoolean("touched"))
        assertEquals(true, runtime.evalBoolean("nonCallableThrew"))
    }

    @Test
    fun `JSON stringify 遵循 Proxy 的 ownKeys getOwnPropertyDescriptor 与 get trap`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = '';
            var target = { hidden: 1 };
            var proxy = new Proxy(target, {
                ownKeys: function () { log += 'k'; return ['virtual']; },
                getOwnPropertyDescriptor: function () {
                    log += 'd';
                    return { value: 1, writable: true, enumerable: true, configurable: true };
                },
                get: function (t, key) { if (key !== 'toJSON') log += 'g'; return 7; }
            });
            var json = JSON.stringify(proxy);
            """.trimIndent()
        )
        assertEquals("{\"virtual\":7}", runtime.evalString("json"))
        assertEquals("kdg", runtime.evalString("log"))
    }

    @Test
    fun `JSON stringify 把 Proxy 数组按数组序列化`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var proxy = new Proxy([], {
                get: function (target, key) {
                    if (key === 'length') return 2;
                    return Number(key);
                }
            });
            var json = JSON.stringify(proxy);
            var nested = JSON.stringify([[proxy]]);
            """.trimIndent()
        )
        assertEquals("[0,1]", runtime.evalString("json"))
        assertEquals("[[[0,1]]]", runtime.evalString("nested"))
    }

    @Test
    fun `JSON stringify 对已撤销 Proxy 抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var handle = Proxy.revocable([], {});
            handle.revoke();
            var threw = false;
            try {
                JSON.stringify(handle.proxy);
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("threw"))
    }

    @Test
    fun `Array isArray 对已撤销 Proxy 抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var handle = Proxy.revocable([], {});
            var active = Array.isArray(handle.proxy);
            handle.revoke();
            var threw = false;
            try {
                Array.isArray(handle.proxy);
            } catch (error) {
                threw = error instanceof TypeError;
            }
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("active"))
        assertEquals(true, runtime.evalBoolean("threw"))
    }
}
