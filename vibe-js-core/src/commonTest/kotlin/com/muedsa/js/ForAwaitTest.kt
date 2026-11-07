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

    @Test
    fun `AsyncIteratorPrototype 提供 Symbol asyncDispose 并调用 return 解包结果`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* generator() {}
            var proto = Object.getPrototypeOf(Object.getPrototypeOf(generator.prototype));
            var method = proto[Symbol.asyncDispose];
            var descriptor = Object.getOwnPropertyDescriptor(proto, Symbol.asyncDispose);
            var log = [];
            log.push(typeof method);
            log.push(method.name);
            log.push(method.length);
            log.push(descriptor.writable, descriptor.enumerable, descriptor.configurable);

            // return 存在：调用一次并丢弃返回值，统一兑现为 undefined
            var calls = 0;
            var iterator = Object.create(proto);
            iterator.return = function () { calls += 1; return Promise.resolve({ done: true }); };
            var promise = iterator[Symbol.asyncDispose]();
            log.push(promise instanceof Promise);

            // 没有 return：直接兑现 undefined
            var plain = Object.create(proto);
            var failures = [];
            (async function () {
                log.push(String(await promise));
                log.push(calls);
                log.push(String(await plain[Symbol.asyncDispose]()));

                // return 抛错 / 拒绝 / getter 抛错都变成结果 Promise 的拒绝
                var throwing = Object.create(proto);
                throwing.return = function () { throw new Error('close-throw'); };
                try { await throwing[Symbol.asyncDispose](); } catch (e) { failures.push(e.message); }
                var rejecting = Object.create(proto);
                rejecting.return = function () { return Promise.reject(new Error('close-reject')); };
                try { await rejecting[Symbol.asyncDispose](); } catch (e) { failures.push(e.message); }
                var getterThrows = Object.create(proto);
                Object.defineProperty(getterThrows, 'return', {
                    get: function () { throw new Error('close-getter'); }
                });
                try { await getterThrows[Symbol.asyncDispose](); } catch (e) { failures.push(e.message); }
                log.push(failures.join(','));
            })();
            """.trimIndent()
        )
        assertEquals(
            "function,[Symbol.asyncDispose],0,true,false,true,true,undefined,1,undefined," +
                "close-throw,close-reject,close-getter",
            runtime.evalString("log.join(',')"),
        )
    }

    @Test
    fun `await using 异步生成器时通过继承的 asyncDispose 执行 return 并触发 finally`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            async function* gen() {
                log.push('start');
                try {
                    yield 1;
                    log.push('unreachable');
                } finally {
                    log.push('finally');
                }
            }
            (async function () {
                await using it = gen();
                log.push('body:' + (await it.next()).value);
                log.push('end');
            })();
            """.trimIndent()
        )
        // 释放发生在函数体结束之后：asyncDispose 调用 return() 并 await 其 Promise，
        // 生成器在 finally 中完成，因此 finally 记录排在 end 之后
        assertEquals("start,body:1,end,finally", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `await using 对没有 return 的异步迭代器兑现 undefined 且不读取 dispose`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            var proto = Object.getPrototypeOf(Object.getPrototypeOf((async function* () {}).prototype));
            var iterator = Object.create(proto);
            var result = 'pending';
            (async function () {
                await using resource = {
                    [Symbol.asyncDispose]: function () {
                        log.push('asyncDispose');
                        return Promise.resolve().then(function () { log.push('settled'); });
                    },
                    [Symbol.dispose]: function () { log.push('syncDispose'); }
                };
                await using bare = iterator;
                log.push('body');
                result = await bare[Symbol.asyncDispose]();
            })();
            """.trimIndent()
        )
        // await using 优先 @@asyncDispose 并 await 返回值；裸异步迭代器没有 return 时兑现 undefined
        assertEquals("body,asyncDispose,settled", runtime.evalString("log.join(',')"))
        assertEquals("undefined", runtime.evalString("String(result)"))
    }

    @Test
    fun `for of 的 await using 在每轮迭代结束时释放异步资源`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            function makeResource(name) {
                return {
                    [Symbol.asyncDispose]: function () {
                        log.push('dispose:' + name);
                        return Promise.resolve().then(function () { log.push('settled:' + name); });
                    }
                };
            }
            (async function () {
                for (await using resource of [makeResource('a'), makeResource('b')]) {
                    log.push('body');
                }
                log.push('after');
            })();
            """.trimIndent()
        )
        assertEquals(
            "body,dispose:a,settled:a,body,dispose:b,settled:b,after",
            runtime.evalString("log.join(',')"),
        )
    }

    @Test
    fun `for await 同步迭代器的值等待失败时关闭迭代器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            var iterable = {
                [Symbol.iterator]() {
                    return {
                        next() {
                            return { value: Promise.reject(new Error('value-reject')), done: false };
                        },
                        return() {
                            log.push('closed');
                            return {};
                        }
                    };
                }
            };
            (async function () {
                try {
                    for await (const value of iterable) {
                        log.push('unreachable');
                    }
                } catch (e) {
                    log.push('caught:' + e.message);
                }
            })();
            """.trimIndent()
        )
        // AsyncFromSyncIteratorContinuation 在第 6/13 步关闭同步迭代器，原错误继续传播
        assertEquals("closed,caught:value-reject", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `for await 包装同步迭代器时按规范读取 Promise constructor`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            var index = 0;
            var iterable = {
                [Symbol.iterator]() {
                    return {
                        next() {
                            log.push('next');
                            index += 1;
                            if (index === 1) {
                                return { value: Promise.resolve(0), done: false };
                            }
                            return { done: true };
                        }
                    };
                }
            };
            Object.defineProperty(Promise.prototype, 'constructor', {
                get: function () { log.push('constructor'); return Promise; },
                configurable: true
            });
            (async function () {
                for await (var value of iterable) {
                    log.push('loop');
                }
                log.push('post');
            })();
            """.trimIndent()
        )
        // value 的 PromiseResolve 与 Await(nextResult) 各读取一次 constructor；
        // done 迭代只有 Await(nextResult) 读取
        assertEquals(
            "next,constructor,constructor,loop,next,constructor,post",
            runtime.evalString("log.join(',')"),
        )
    }

    @Test
    fun `for await 同步迭代器 next 未传参时不补 undefined`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var lengths = [];
            var iterable = {
                [Symbol.iterator]() {
                    return {
                        next() {
                            lengths.push(arguments.length);
                            return { done: true };
                        }
                    };
                }
            };
            (async function () {
                for await (var value of iterable) {}
            })();
            """.trimIndent()
        )
        assertEquals("0", runtime.evalString("lengths.join(',')"))
    }
}
