package com.muedsa.js

import com.muedsa.js.parser.ParseException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * `for await ... of` 与 `%AsyncIteratorPrototype%` 的语义测试。
 *
 * 覆盖同步迭代器回退（AsyncFromSync）、异步迭代器优先、值/结果 await、
 * break 关闭与错误传播。
 */
class ForAwaitTest {

    @Test
    fun `for await 遍历同步可迭代对象并逐个 await 值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var collected = '';
            async function collect() {
                for await (const value of [Promise.resolve('a'), 'b']) {
                    collected += value;
                }
            }
            collect();
            """.trimIndent()
        )
        assertEquals("ab", runtime.evalString("collected"))
    }

    @Test
    fun `for await 优先使用 Symbol asyncIterator`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var collected = '';
            var iterable = {};
            iterable[Symbol.asyncIterator] = function () {
                var index = 0;
                return {
                    next: function () {
                        index += 1;
                        if (index <= 2) {
                            return Promise.resolve({ value: 'v' + index, done: false });
                        }
                        return Promise.resolve({ done: true });
                    }
                };
            };
            async function collect() {
                for await (const value of iterable) {
                    collected += value;
                }
            }
            collect();
            """.trimIndent()
        )
        assertEquals("v1v2", runtime.evalString("collected"))
    }

    @Test
    fun `for await 在 break 时调用异步迭代器的 return`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var returned = 0;
            var iterable = {};
            iterable[Symbol.asyncIterator] = function () {
                return {
                    next: function () {
                        return Promise.resolve({ value: 1, done: false });
                    },
                    return: function () {
                        returned += 1;
                        return Promise.resolve({ done: true });
                    }
                };
            };
            async function run() {
                for await (const value of iterable) {
                    break;
                }
            }
            run();
            """.trimIndent()
        )
        assertEquals(1.0, runtime.evalNumber("returned"))
    }

    @Test
    fun `for await 在异常时调用 return 并保留原始异常`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var returned = 0;
            var message = '';
            var iterable = {};
            iterable[Symbol.asyncIterator] = function () {
                return {
                    next: function () {
                        return Promise.resolve({ value: 1, done: false });
                    },
                    return: function () {
                        returned += 1;
                        throw new Error('close failure');
                    }
                };
            };
            async function run() {
                try {
                    for await (const value of iterable) {
                        throw new Error('body failure');
                    }
                } catch (error) {
                    message = error.message;
                }
            }
            run();
            """.trimIndent()
        )
        assertEquals(1.0, runtime.evalNumber("returned"))
        assertEquals("body failure", runtime.evalString("message"))
    }

    @Test
    fun `for await 支持解构与 const 绑定`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var sum = 0;
            async function run() {
                for await (const [a, b] of [[1, 2], [3, 4]]) {
                    sum += a + b;
                }
            }
            run();
            """.trimIndent()
        )
        assertEquals(10.0, runtime.evalNumber("sum"))
    }

    @Test
    fun `for await 遍历不可迭代对象抛出 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var kind = '';
            async function run() {
                try {
                    for await (const value of 1) {
                    }
                } catch (error) {
                    kind = error instanceof TypeError ? 'type-error' : 'other';
                }
            }
            run();
            """.trimIndent()
        )
        assertEquals("type-error", runtime.evalString("kind"))
    }

    @Test
    fun `for await 在非 async 函数中是语法错误`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<ParseException> {
            runtime.engine.parse("for await (const value of []) {}")
        }
    }

    @Test
    fun `for await 不允许 in 头部`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<ParseException> {
            runtime.engine.parse("async function run() { for await (const key in {}) {} }")
        }
    }

    @Test
    fun `for await 的 next 结果不是对象时抛出 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var kind = '';
            var iterable = {};
            iterable[Symbol.asyncIterator] = function () {
                return {
                    next: function () {
                        return 1;
                    }
                };
            };
            async function run() {
                try {
                    for await (const value of iterable) {
                    }
                } catch (error) {
                    kind = error instanceof TypeError ? 'type-error' : 'other';
                }
            }
            run();
            """.trimIndent()
        )
        assertEquals("type-error", runtime.evalString("kind"))
    }
}
