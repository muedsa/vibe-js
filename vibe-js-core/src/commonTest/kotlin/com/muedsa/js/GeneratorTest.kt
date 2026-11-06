package com.muedsa.js

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 同步 Generator（L1.1）语义测试。
 *
 * 覆盖 next 序列、next(v) 传值、return/throw、finally 执行、yield* 委托、
 * for...of 与展开语法消费生成器，以及生成器函数的内建原型关系。
 */
class GeneratorTest {

    @Test
    fun `生成器 next 依次产出值并在结束后保持完成状态`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; yield 2; }
            var it = g();
            var log = [];
            log.push(it.next().value);
            log.push(it.next().value);
            log.push(it.next().done);
            log.push(it.next().done);
            var joined = log.join(',');
            """.trimIndent()
        )
        assertEquals("1,2,true,true", runtime.evalString("joined"))
        // 完成后再次 next 仍返回 { value: undefined, done: true }
        assertEquals("undefined", runtime.evalString("String(it.next().value)"))
    }

    @Test
    fun `生成器调用不会执行函数体直到首次 next`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var started = 0;
            function* g() { started++; yield 1; }
            var it = g();
            var afterCall = started;
            it.next();
            var afterNext = started;
            """.trimIndent()
        )
        assertEquals(0.0, runtime.evalNumber("afterCall"))
        assertEquals(1.0, runtime.evalNumber("afterNext"))
    }

    @Test
    fun `next 的参数成为 yield 表达式的值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() {
                var first = yield 1;
                yield first + 1;
            }
            var it = g();
            var a = it.next().value;
            var b = it.next(10).value;
            var result = a + ',' + b;
            """.trimIndent()
        )
        assertEquals("1,11", runtime.evalString("result"))
    }

    @Test
    fun `return 结束生成器并返回给定的值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var bodyRan = 0;
            function* g() { bodyRan++; yield 1; }
            // 尚未启动：return 不执行函数体
            var notStarted = g();
            var r1 = notStarted.return(5);
            var ranAfterReturn = bodyRan;
            // 已挂起：return 结束当前迭代
            var it = g();
            it.next();
            var r2 = it.return(7);
            var r3 = it.next();
            var result = r1.value + ',' + r1.done + ';' + r2.value + ',' + r2.done +
                ';' + r3.done + ';' + ranAfterReturn;
            """.trimIndent()
        )
        assertEquals("5,true;7,true;true;0", runtime.evalString("result"))
    }

    @Test
    fun `return 触发 finally 块执行`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var cleaned = '';
            function* g() {
                try {
                    yield 1;
                    yield 2;
                } finally {
                    cleaned = 'finally';
                }
            }
            var it = g();
            it.next();
            var r = it.return(9);
            var result = cleaned + ':' + r.done + ':' + r.value + ':' + it.next().done;
            """.trimIndent()
        )
        assertEquals("finally:true:9:true", runtime.evalString("result"))
    }

    @Test
    fun `throw 在挂起点抛出异常并可被生成器内部捕获`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() {
                try {
                    yield 1;
                } catch (e) {
                    yield 'caught:' + e.message;
                }
            }
            var it = g();
            var first = it.next().value;
            var second = it.throw(new Error('boom'));
            var result = first + ',' + second.value + ',' + second.done;
            """.trimIndent()
        )
        assertEquals("1,caught:boom,false", runtime.evalString("result"))
    }

    @Test
    fun `throw 未被捕获时传播到调用方并结束生成器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; }
            var it = g();
            it.next();
            var message = '';
            try {
                it.throw(new Error('unhandled'));
            } catch (e) {
                message = e.message;
            }
            var afterThrow = it.next().done;
            var result = message + ':' + afterThrow;
            """.trimIndent()
        )
        assertEquals("unhandled:true", runtime.evalString("result"))
    }

    @Test
    fun `yield星号 按迭代协议委托并返回内层迭代器结果`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            function* inner() { yield 1; yield 2; return 'inner-done'; }
            function* outer() { var r = yield* inner(); log.push(r); yield 3; }
            var it = outer();
            var values = [];
            while (true) {
                var step = it.next();
                if (step.done) break;
                values.push(step.value);
            }
            var result = values.join(',') + ':' + log.join(',');
            """.trimIndent()
        )
        assertEquals("1,2,3:inner-done", runtime.evalString("result"))
    }

    @Test
    fun `yield星号 可委托数组与字符串等内置可迭代对象`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield* [1, 2]; yield* 'ab'; }
            var result = Array.from(g()).join(',');
            """.trimIndent()
        )
        assertEquals("1,2,a,b", runtime.evalString("result"))
    }

    @Test
    fun `for of 可以消费生成器并在 break 时执行 finally`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            function* g() {
                try {
                    yield 1;
                    yield 2;
                } finally {
                    log.push('finally');
                }
            }
            for (var value of g()) {
                log.push(value);
                break;
            }
            var joined = log.join(',');
            """.trimIndent()
        )
        assertEquals("1,finally", runtime.evalString("joined"))
    }

    @Test
    fun `展开语法可以消费生成器的全部产出`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; yield 2; yield 3; }
            var spread = [...g()].join(',');
            var consumed = Array.from(g()).length;
            """.trimIndent()
        )
        assertEquals("1,2,3", runtime.evalString("spread"))
        assertEquals(3.0, runtime.evalNumber("consumed"))
    }

    @Test
    fun `生成器函数与实例的原型关系符合规范`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; }
            var it = g();
            var tagFunction = Object.prototype.toString.call(g);
            var tagInstance = Object.prototype.toString.call(it);
            var hasNext = typeof it.next === 'function';
            var hasReturn = typeof it.return === 'function';
            var hasThrow = typeof it.throw === 'function';
            var instanceProto = Object.getPrototypeOf(it) === g.prototype;
            // g.prototype 的原型是 %GeneratorPrototype%，它自身可迭代（@@iterator 返回 this）
            var generatorProto = Object.getPrototypeOf(g.prototype);
            var selfIterable = generatorProto[Symbol.iterator].call(it) === it;
            var result = tagFunction + ',' + tagInstance + ',' + hasNext + ',' + hasReturn +
                ',' + hasThrow + ',' + instanceProto + ',' + selfIterable;
            """.trimIndent()
        )
        assertEquals(
            "[object GeneratorFunction],[object Generator],true,true,true,true,true",
            runtime.evalString("result"),
        )
    }

    @Test
    fun `生成器函数不能被 new 调用也不能在类型判断中作为构造器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; }
            var name = '';
            try {
                new g();
            } catch (e) {
                name = e.name;
            }
            // Reflect.construct 同样拒绝生成器函数
            var reflected = '';
            try {
                Reflect.construct(g, []);
            } catch (e) {
                reflected = e.name;
            }
            var result = name + ',' + reflected;
            """.trimIndent()
        )
        assertEquals("TypeError,TypeError", runtime.evalString("result"))
    }

    @Test
    fun `生成器方法在挂起期间重复调用 next 抛出 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var reentrant = '';
            function* g() {
                try {
                    itSelf.next();
                } catch (e) {
                    reentrant = e.name;
                }
                yield 1;
            }
            var itSelf = g();
            itSelf.next();
            """.trimIndent()
        )
        assertEquals("TypeError", runtime.evalString("reentrant"))
    }

    @Test
    fun `生成器方法接收者不是生成器对象时抛出 TypeError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; }
            var next = g.prototype.next;
            var result = '';
            try {
                next.call({});
            } catch (e) {
                result = e.name;
            }
            """.trimIndent()
        )
        assertEquals("TypeError", runtime.evalString("result"))
    }

    @Test
    fun `嵌套普通函数中的 yield 只是标识符`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() {
                function helper(yield) { return yield + 1; }
                yield helper(41);
            }
            var it = g();
            var result = it.next().value;
            """.trimIndent()
        )
        assertEquals(42.0, runtime.evalNumber("result"))
    }

    @Test
    fun `GeneratorFunction 构造器可以动态创建生成器函数`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; }
            var GeneratorFunction = Object.getPrototypeOf(g).constructor;
            var dynamic = new GeneratorFunction('a', 'yield a * 2; yield a * 3;');
            var values = [...dynamic(4)].join(',');
            var isGenerator = Object.prototype.toString.call(dynamic);
            var result = values + ':' + isGenerator;
            """.trimIndent()
        )
        assertEquals("8,12:[object GeneratorFunction]", runtime.evalString("result"))
    }

    @Test
    fun `生成器内部可以使用 arguments 与 this`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield arguments.length; yield this.tag; }
            var host = { tag: 'host', run: g };
            var it = host.run(1, 2, 3);
            var result = it.next().value + ':' + it.next().value;
            """.trimIndent()
        )
        assertEquals("3:host", runtime.evalString("result"))
    }

    @Test
    fun `生成器内部的 catch 不会捕获 return 请求但 finally 会执行`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var caught = false;
            var finalized = false;
            function* g() {
                try {
                    yield 1;
                } catch (e) {
                    caught = true;
                } finally {
                    finalized = true;
                }
            }
            var it = g();
            it.next();
            var step = it.return(5);
            var result = caught + ':' + finalized + ':' + step.value + ':' + step.done;
            """.trimIndent()
        )
        assertEquals("false:true:5:true", runtime.evalString("result"))
    }

    @Test
    fun `生成器参数绑定延迟到首次 next 且默认值按顺序求值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var bindings = 0;
            function* g(a = (bindings++, 1), ...rest) {
                yield a + rest.length;
            }
            var it = g(undefined, 7, 8, 9);
            var beforeNext = bindings;
            var value = it.next().value;
            var result = beforeNext + ':' + value;
            """.trimIndent()
        )
        assertEquals("0:4", runtime.evalString("result"))
    }

    @Test
    fun `模板字符串子表达式可以包含 yield`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() {
                var text = 'value=' + `${'$'}{yield 1}`;
                yield text;
            }
            var it = g();
            it.next();
            var result = it.next(42).value;
            """.trimIndent()
        )
        assertEquals("value=42", runtime.evalString("result"))
    }

    @Test
    fun `空生成器首次 next 直接完成`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() {}
            var it = g();
            var first = it.next();
            var result = String(first.value) + ':' + first.done;
            """.trimIndent()
        )
        assertEquals("undefined:true", runtime.evalString("result"))
    }

    @Test
    fun `生成器在首次 next 前抛错时异常直接传播`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { throw new Error('early'); }
            var it = g();
            var message = '';
            try {
                it.next();
            } catch (e) {
                message = e.message;
            }
            var result = message + ':' + it.next().done;
            """.trimIndent()
        )
        assertEquals("early:true", runtime.evalString("result"))
    }

    @Test
    fun `生成器体内 return 结束迭代并给出完成值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; return 42; yield 2; }
            var it = g();
            it.next();
            var last = it.next();
            var result = last.value + ':' + last.done;
            """.trimIndent()
        )
        assertEquals("42:true", runtime.evalString("result"))
    }

    @Test
    fun `生成器委托中的 return 请求按协议转发`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            function* inner() {
                try {
                    yield 1;
                } finally {
                    yield 2;
                }
            }
            function* outer() { yield* inner(); }
            var it = outer();
            var first = it.next();
            var second = it.return(9);
            var third = it.next();
            var result = first.value + ',' + first.done + ';' +
                second.value + ',' + second.done + ';' + third.done;
            """.trimIndent()
        )
        assertEquals("1,false;2,false;true", runtime.evalString("result"))
    }

    @Test
    fun `yield星号 委托结束后的语句继续执行`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* inner() { return 'r'; }
            function* outer() { var r = yield* inner(); yield 'after:' + r; }
            var it = outer();
            var result = it.next().value;
            """.trimIndent()
        )
        assertEquals("after:r", runtime.evalString("result"))
    }

    @Test
    fun `生成器函数的 prototype 上存在 constructor 指回函数自身`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield 1; }
            var result = g.prototype.constructor === g;
            """.trimIndent()
        )
        assertTrue(runtime.evalBoolean("result"))
    }

    @Test
    fun `生成器函数声明提升后可在声明前调用`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var value = later().next().value;
            function* later() { yield 'hoisted'; }
            """.trimIndent()
        )
        assertEquals("hoisted", runtime.evalString("value"))
    }

    @Test
    fun `生成器结果对象具有规范的值与完成标志`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() { yield undefined; }
            var it = g();
            var step = it.next();
            var keys = Object.keys(step).join(',');
            var result = keys + ':' + String(step.value) + ':' + step.done;
            """.trimIndent()
        )
        assertEquals("value,done:undefined:false", runtime.evalString("result"))
    }

    @Test
    fun `生成器内部的 yield 值参与普通表达式运算`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() {
                var total = 0;
                total += yield 1;
                total += yield 2;
                return total;
            }
            var it = g();
            it.next();
            it.next(10);
            var result = it.next(20).value;
            """.trimIndent()
        )
        assertEquals(30.0, runtime.evalNumber("result"))
    }
}
