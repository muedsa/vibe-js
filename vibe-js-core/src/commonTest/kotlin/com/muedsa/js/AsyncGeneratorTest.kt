package com.muedsa.js

import com.muedsa.js.parser.ParseException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * async generator（L1.4）语义测试。
 *
 * 覆盖 Promise 化的 next/return/throw、await 与 yield 交错、next(v) 传值、
 * return 触发的 finally 与 using/IteratorClose 清理、throw 的内部捕获、
 * for await 消费、yield* 的同步/异步委托、内建原型链、不可 new 与动态构造器。
 *
 * 所有用例都在一次 `eval` 中启动 async 函数，Engine.run 返回前会清空微任务队列，
 * 因此断言读取到的全局变量已经是最终结果。
 */
class AsyncGeneratorTest {

    @Test
    fun `async 生成器 next 返回 Promise 并依次产出值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() { yield 1; yield 2; }
            var log = [];
            var it = g();
            (async function () {
                var first = await it.next();
                log.push(first.value, first.done);
                var second = await it.next();
                log.push(second.value, second.done);
                var third = await it.next();
                log.push(String(third.value), third.done);
                var fourth = await it.next();
                log.push(String(fourth.value), fourth.done);
            })();
            """.trimIndent()
        )
        assertEquals("1,false,2,false,undefined,true,undefined,true", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `async 生成器调用时求值默认参数且首次 next 不重复绑定`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var calls = 0;
            async function* g(a = (calls++, 10)) { yield a; yield calls; }
            var it = g();
            var afterCall = calls;
            var result = '';
            (async function () {
                var first = await it.next();
                var second = await it.next();
                result = afterCall + ':' + first.value + ':' + calls + ':' + second.value;
            })();
            """.trimIndent()
        )
        // 默认值只在调用时求值一次；首次 next 恢复函数体时不得重复求值
        assertEquals("1:10:1:1", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器默认参数中的 this 与 arguments 在首次 next 前已绑定`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var seen = '';
            async function* g(a = (seen = this.tag + ':' + arguments.length, 0)) { yield a; }
            var host = { tag: 'async-host', run: g };
            var it = host.run(undefined, 2, 3);
            var beforeNext = seen;
            var result = '';
            (async function () {
                result = beforeNext + ':' + (await it.next()).value;
            })();
            """.trimIndent()
        )
        assertEquals("async-host:3:0", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器默认参数在调用时捕获参数值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var outer = 1;
            async function* g(a = outer) { yield a; }
            var it = g();
            outer = 2;
            var result = '';
            (async function () {
                result = String((await it.next()).value);
            })();
            """.trimIndent()
        )
        assertEquals("1", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器 return 前未启动时参数已经完成绑定`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var calls = 0;
            async function* g(a = calls++) { yield 1; }
            var it = g();
            var afterCall = calls;
            var result = '';
            (async function () {
                var step = await it.return(5);
                result = afterCall + ':' + calls + ':' + step.value + ':' + step.done;
            })();
            """.trimIndent()
        )
        assertEquals("1:1:5:true", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器默认参数抛错在调用时传播而不是首次 next`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var calls = 0;
            function boom() { throw new Error('async-param'); }
            async function* g(a = (calls++, boom())) { yield 1; }
            var message = '';
            var created = 'yes';
            var it = null;
            try {
                it = g();
            } catch (e) {
                message = e.message;
                created = 'no';
            }
            var result = message + ':' + calls + ':' + created + ':' + String(it);
            """.trimIndent()
        )
        // async generator 的参数初始化同样同步完成，不会变成 next() 的 Promise 拒绝
        assertEquals("async-param:1:no:null", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器参数 TDZ 在调用时抛 ReferenceError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g(a = b, b = 1) { yield a; }
            var name = '';
            try {
                g();
            } catch (e) {
                name = e.name;
            }
            """.trimIndent()
        )
        assertEquals("ReferenceError", runtime.evalString("name"))
    }

    @Test
    fun `async 生成器形参中的 await 早期错误在解析期被拒绝`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<ParseException> { runtime.eval("async function* g(a = await 1) {}") }
        assertFailsWith<ParseException> { runtime.eval("async function* g({a = await 1}) {}") }
        assertFailsWith<ParseException> { runtime.eval("async function* g([a = await 1]) {}") }
        assertFailsWith<ParseException> { runtime.eval("async function* g({[await x]: a}) {}") }
        assertFailsWith<ParseException> {
            runtime.eval("var o = { async *g(a = await 1) {} };")
        }
        assertFailsWith<ParseException> {
            runtime.eval("class C { async *g(a = await 1) {} }")
        }
        // 嵌套 async 箭头形参同样是 [+Await] 语境
        assertFailsWith<ParseException> {
            runtime.eval("async function* g() { var f = async (a = await 1) => a; }")
        }
    }

    @Test
    fun `async 生成器体内嵌套普通函数的形参可以包含 await 标识符`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() {
                function inner(await) { return await; }
                var o = { m(await) { return await + 1; } };
                class C { m(await) { return await + 2; } }
                yield inner(7);
                yield o.m(7);
                yield new C().m(7);
            }
            var result = '';
            (async function () {
                var it = g();
                var first = (await it.next()).value;
                var second = (await it.next()).value;
                var third = (await it.next()).value;
                result = first + ',' + second + ',' + third;
            })();
            """.trimIndent()
        )
        // 嵌套普通函数/方法/class 方法是 [~Await] 语境：await 仍可作为绑定名与引用
        assertEquals("7,8,9", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器形参中的 await 在嵌套 async 箭头函数体内合法`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g(a = async () => await 1) { yield a; }
            async function* h(a = function () { return 2; }()) { yield a; }
            """.trimIndent()
        )
        assertEquals("function,function", runtime.evalString("typeof g + ',' + typeof h"))
    }    @Test
    fun `async 生成器 next 返回的 Promise 在微任务中落定而不是同步`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() { yield 1; }
            var it = g();
            var p = it.next();
            var isPromise = p instanceof Promise;
            var syncState = 'pending';
            p.then(function () { syncState = 'settled'; });
            var beforeDrain = syncState;
            """.trimIndent()
        )
        // next 同步返回 Promise：调用栈上尚未落定，微任务清空后才是 settled
        assertTrue(runtime.evalBoolean("isPromise"))
        assertEquals("pending", runtime.evalString("beforeDrain"))
        assertEquals("settled", runtime.evalString("syncState"))
    }

    @Test
    fun `async 生成器结果 Promise 的落定顺序与规范一致`() = runTest {
        val runtime = TestRuntime()
        // 规范在结果解析前插入一个 Promise 作业，因此 p 的 then 排在 1 之后、2 之前
        runtime.eval(
            """
            async function* g() { yield 1; }
            var it = g();
            var p = it.next();
            var log = [];
            Promise.resolve().then(function () { log.push(1); })
                .then(function () { log.push(2); })
                .then(function () { log.push(3); });
            p.then(function () { log.push('p'); });
            """.trimIndent()
        )
        assertEquals("1,p,2,3", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `async 生成器内 await 与 yield 可以交错执行`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            async function* g() {
                log.push('start');
                var value = await Promise.resolve('awaited');
                log.push(value);
                yield value;
                var next = await Promise.resolve('second');
                log.push(next);
                yield next;
                log.push('done');
            }
            var collected = '';
            (async function () {
                for await (const value of g()) {
                    collected += value + ';';
                }
            })();
            """.trimIndent()
        )
        assertEquals("start,awaited,second,done", runtime.evalString("log.join(',')"))
        assertEquals("awaited;second;", runtime.evalString("collected"))
    }

    @Test
    fun `async 生成器中 yield 的值会被 await 解包`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() {
                yield Promise.resolve(42);
                try {
                    yield Promise.reject(new Error('bad'));
                } catch (e) {
                    yield 'caught:' + e.message;
                }
            }
            var log = [];
            (async function () {
                var it = g();
                log.push((await it.next()).value);
                log.push((await it.next()).value);
                log.push((await it.next()).done);
            })();
            """.trimIndent()
        )
        assertEquals("42,caught:bad,true", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `next 的参数成为 yield 表达式的值且不会被解包`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() {
                var first = yield 1;
                yield first + 1;
                var second = yield 2;
                yield second instanceof Promise;
            }
            var log = [];
            (async function () {
                var it = g();
                await it.next();
                log.push((await it.next(10)).value);
                await it.next();
                log.push((await it.next(Promise.resolve(5))).value);
            })();
            """.trimIndent()
        )
        assertEquals("11,true", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `return 结束 async 生成器并触发 finally 清理`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var cleaned = '';
            var bodyRan = 0;
            async function* g() {
                bodyRan++;
                try {
                    yield 1;
                    yield 2;
                } finally {
                    cleaned = 'finally';
                }
            }
            var stale = g();
            var startedLog = [];
            (async function () {
                var returned = await stale.return(5);
                startedLog.push(returned.value, returned.done, String(bodyRan));
                var it = g();
                await it.next();
                var step = await it.return(7);
                startedLog.push(step.value, step.done);
                var after = await it.next();
                startedLog.push(after.done);
            })();
            """.trimIndent()
        )
        // 未启动的生成器 return 不执行函数体；已挂起的生成器 return 会执行 finally
        assertEquals("5,true,0,7,true,true", runtime.evalString("startedLog.join(',')"))
        assertEquals("finally", runtime.evalString("cleaned"))
    }

    @Test
    fun `return 在 finally 中再次 yield 时返回值延后到下一次请求`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() {
                try {
                    yield 1;
                } finally {
                    yield 2;
                }
            }
            var log = [];
            (async function () {
                var it = g();
                log.push((await it.next()).value);
                var returned = await it.return(9);
                log.push(returned.value, returned.done);
                var resumed = await it.next();
                log.push(resumed.value, resumed.done);
            })();
            """.trimIndent()
        )
        // 与引擎实现的 Channel 状态机一致：finally 的 yield 先交出，挂起的 return 值随后完成
        assertEquals("1,2,false,9,true", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `throw 在挂起点抛出异常并可在 async 生成器内部捕获`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() {
                try {
                    yield 1;
                } catch (e) {
                    yield 'caught:' + e.message;
                }
            }
            var log = [];
            (async function () {
                var it = g();
                await it.next();
                var step = await it.throw(new Error('boom'));
                log.push(step.value, step.done);
            })();
            """.trimIndent()
        )
        assertEquals("caught:boom,false", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `throw 未被捕获时以 Promise 拒绝并结束 async 生成器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() { yield 1; }
            var log = [];
            (async function () {
                var it = g();
                await it.next();
                try {
                    await it.throw(new Error('unhandled'));
                    log.push('no-rejection');
                } catch (e) {
                    log.push(e.message);
                }
                var after = await it.next();
                log.push(after.done);
            })();
            """.trimIndent()
        )
        assertEquals("unhandled,true", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `for await 可以消费 async 生成器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* numbers() {
                yield 1;
                yield 2;
                yield 3;
            }
            var collected = [];
            (async function () {
                for await (const value of numbers()) {
                    collected.push(value);
                }
            })();
            """.trimIndent()
        )
        assertEquals("1,2,3", runtime.evalString("collected.join(',')"))
    }

    @Test
    fun `for await 在 break 时调用 async 生成器的 return`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            async function* g() {
                try {
                    yield 1;
                    yield 2;
                } finally {
                    log.push('finally');
                }
            }
            (async function () {
                for await (const value of g()) {
                    log.push(value);
                    break;
                }
            })();
            """.trimIndent()
        )
        assertEquals("1,finally", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `yield星号 委托同步可迭代对象并解包值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* inner() { yield 1; yield 2; return 'inner-done'; }
            async function* outer() {
                var result = yield* inner();
                yield 'result:' + result;
                yield* [Promise.resolve(3), 4];
            }
            var log = [];
            (async function () {
                for await (const value of outer()) {
                    log.push(value);
                }
            })();
            """.trimIndent()
        )
        assertEquals("1,2,result:inner-done,3,4", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `yield星号 优先使用 Symbol asyncIterator 委托`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            var iterable = {};
            iterable[Symbol.asyncIterator] = function () {
                var index = 0;
                return {
                    next: function () {
                        index += 1;
                        if (index <= 2) {
                            return Promise.resolve({ value: 'async' + index, done: false });
                        }
                        return Promise.resolve({ done: true, value: 'async-done' });
                    }
                };
            };
            iterable[Symbol.iterator] = function () {
                throw new Error('sync iterator must not be used');
            };
            async function* g() {
                var result = yield* iterable;
                log.push(result);
            }
            (async function () {
                for await (const value of g()) {
                    log.push(value);
                }
            })();
            """.trimIndent()
        )
        assertEquals("async1,async2,async-done", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `async 生成器函数与实例的原型链符合规范`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() { yield 1; }
            var it = g();
            var tagFunction = Object.prototype.toString.call(g);
            var tagInstance = Object.prototype.toString.call(it);
            var protoFromFunction = Object.getPrototypeOf(g);
            var asyncGeneratorPrototype = Object.getPrototypeOf(g.prototype);
            var result = [
                tagFunction,
                tagInstance,
                Object.getPrototypeOf(it) === g.prototype,
                Object.getPrototypeOf(protoFromFunction) === Function.prototype,
                protoFromFunction.prototype === asyncGeneratorPrototype, // 两个函数的内部原型是同一对象
                protoFromFunction.constructor.name,
                asyncGeneratorPrototype.constructor === protoFromFunction,
                asyncGeneratorPrototype[Symbol.asyncIterator].call(it) === it,
                typeof Object.getPrototypeOf(asyncGeneratorPrototype)[Symbol.asyncIterator] === 'function',
                Object.prototype.hasOwnProperty.call(asyncGeneratorPrototype, Symbol.asyncIterator),
                Object.prototype.hasOwnProperty.call(g.prototype, 'constructor')
            ].join(',');
            """.trimIndent()
        )
        assertEquals(
            "[object AsyncGeneratorFunction],[object AsyncGenerator],true,true,true," +
                "AsyncGeneratorFunction,true,true,true,true,true",
            runtime.evalString("result"),
        )
    }

    @Test
    fun `async 生成器函数不能被 new 调用`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() { yield 1; }
            var o = { async *m() { yield 2; } };
            class C { async *items() { yield 3; } }
            var names = [];
            function attempt(fn) {
                try { new fn(); } catch (e) { names.push(e.name); }
            }
            attempt(g);
            attempt(o.m);
            attempt(C.prototype.items);
            var reflected = '';
            try { Reflect.construct(g, []); } catch (e) { reflected = e.name; }
            var result = names.join(',') + ':' + reflected;
            """.trimIndent()
        )
        assertEquals("TypeError,TypeError,TypeError:TypeError", runtime.evalString("result"))
    }

    @Test
    fun `AsyncGeneratorFunction 构造器可以动态创建 async 生成器函数`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var AsyncGeneratorFunction = Object.getPrototypeOf(async function*() {}).constructor;
            var dynamic = new AsyncGeneratorFunction('a', 'yield a; yield a * 2;');
            var tag = Object.prototype.toString.call(dynamic);
            var values = [];
            var result = '';
            (async function () {
                for await (const value of dynamic(4)) {
                    values.push(value);
                }
                result = tag + ':' + values.join(',');
            })();
            """.trimIndent()
        )
        assertEquals("[object AsyncGeneratorFunction]:4,8", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器方法与同步生成器方法共享函数原型但实例原型不同`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* syncGen() { yield 1; }
            async function* asyncGen() { yield 1; }
            var o = { *syncMethod() { yield 1; }, async *asyncMethod() { yield 2; } };
            var results = [
                Object.prototype.toString.call(o.asyncMethod),
                Object.prototype.toString.call(asyncGen.prototype),
                Object.getPrototypeOf(o.asyncMethod) === Object.getPrototypeOf(asyncGen),
                asyncGen.prototype !== o.asyncMethod.prototype,
                Object.getPrototypeOf(syncGen.prototype) !== Object.getPrototypeOf(asyncGen.prototype),
                String(o.asyncMethod).slice(0, 9)
            ].join(',');
            """.trimIndent()
        )
        assertEquals(
            "[object AsyncGeneratorFunction],[object AsyncGenerator],true,true,true,async *as",
            runtime.evalString("results"),
        )
    }

    @Test
    fun `async 生成器 return 请求会关闭迭代器并释放 using 资源`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            function makeIterable() {
                var index = 0;
                return {
                    [Symbol.iterator]() { return this; },
                    next() { index++; return { value: index, done: false }; },
                    return() { log.push('iterator:' + index); return {}; }
                };
            }
            function makeResource(name) {
                return { [Symbol.dispose]() { log.push('dispose:' + name); } };
            }
            async function* g() {
                using outer = makeResource('outer');
                for (var value of makeIterable()) {
                    yield value;
                }
            }
            var first = 0;
            var returned = null;
            (async function () {
                var it = g();
                first = (await it.next()).value;
                returned = await it.return(7);
            })();
            """.trimIndent()
        )
        // 同步迭代器的 return 在 for-of 中关闭，using 资源随函数体结束释放
        assertEquals("iterator:1,dispose:outer", runtime.evalString("log.join(',')"))
        assertEquals(1.0, runtime.evalNumber("first"))
        assertEquals(true, runtime.evalBoolean("returned.done"))
        assertEquals(7.0, runtime.evalNumber("returned.value"))
    }

    @Test
    fun `同步与异步生成器的方法互斥使用对方原型`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* syncGen() { yield 1; }
            async function* asyncGen() { yield 1; }
            var results = [];
            var asyncInstance = asyncGen();
            var syncInstance = syncGen();
            // %GeneratorPrototype% 与 %AsyncGeneratorPrototype% 都不是全局对象，只能经实例原型取得
            var generatorPrototype = Object.getPrototypeOf(syncGen.prototype);
            try {
                generatorPrototype.next.call(asyncInstance);
            } catch (e) {
                results.push(e.name);
            }
            // 同步生成器原型的 next 无法处理 async generator 的 Promise 语义
            var asyncGeneratorPrototype = Object.getPrototypeOf(asyncGen.prototype);
            try {
                asyncGeneratorPrototype.next.call(syncInstance);
            } catch (e) {
                results.push(e.name);
            }
            try {
                asyncGeneratorPrototype.return.call({});
            } catch (e) {
                results.push(e.name);
            }
            var result = results.join(',');
            """.trimIndent()
        )
        assertEquals("TypeError,TypeError,TypeError", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器的 return 完成值同样经过 await`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* resolves() { return Promise.resolve(8); }
            async function* rejects() { return Promise.reject(new Error('bad')); }
            var log = [];
            (async function () {
                var step = await resolves().next();
                log.push(step.value, step.done);
                try {
                    await rejects().next();
                    log.push('no-rejection');
                } catch (e) {
                    log.push(e.message);
                }
            })();
            """.trimIndent()
        )
        assertEquals("8,true,bad", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `async 生成器函数体抛出的异常在首次 next 时拒绝 Promise`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() { throw new Error('early'); }
            var log = [];
            (async function () {
                var it = g();
                try {
                    await it.next();
                    log.push('no-rejection');
                } catch (e) {
                    log.push(e.message);
                }
                var after = await it.next();
                log.push(after.done);
            })();
            """.trimIndent()
        )
        assertEquals("early,true", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `async 生成器可以多次排队 next 请求并按顺序处理`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* g() {
                await Promise.resolve();
                yield 1;
                yield 2;
                yield 3;
            }
            var log = [];
            (async function () {
                var it = g();
                var a = it.next();
                var b = it.next();
                var c = it.next();
                log.push((await a).value, (await b).value, (await c).value);
                var d = await it.next();
                log.push(String(d.value), d.done);
            })();
            """.trimIndent()
        )
        assertEquals("1,2,3,undefined,true", runtime.evalString("log.join(',')"))
    }
}
