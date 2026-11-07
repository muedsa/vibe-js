package com.muedsa.js

import com.muedsa.js.parser.ParseException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 同步 Generator（L1.1 / L1.2）语义测试。
 *
 * 覆盖 next 序列、next(v) 传值、return/throw、finally 执行、yield* 委托、
 * for...of 与展开语法消费生成器、生成器函数的内建原型关系，
 * 以及 L1.2 引入的对象字面量/class 生成器方法（含 async *、计算键与上下文关键字方法名）。
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
    fun `生成器调用时完成参数绑定且默认值按顺序求值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var bindings = 0;
            function* g(a = (bindings++, 1), ...rest) {
                yield a + rest.length;
            }
            var it = g(undefined, 7, 8, 9);
            var afterCall = bindings;
            var value = it.next().value;
            var result = afterCall + ':' + value;
            """.trimIndent()
        )
        // 规范：生成器函数被调用时就完成参数绑定，首次 next 只执行函数体
        assertEquals("1:4", runtime.evalString("result"))
    }

    @Test
    fun `生成器调用时求值默认参数且首次 next 不重复绑定`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var calls = 0;
            function* g(a = (calls++, 10)) { yield a; yield calls; }
            var it = g();
            var afterCall = calls;
            var first = it.next().value;
            var afterFirstNext = calls;
            var second = it.next().value;
            var result = afterCall + ':' + first + ':' + afterFirstNext + ':' + second;
            """.trimIndent()
        )
        // 默认值只在调用时求值一次；首次 next 恢复函数体时不得重复求值
        assertEquals("1:10:1:1", runtime.evalString("result"))
    }

    @Test
    fun `生成器默认参数中的 this 与 arguments 在首次 next 前已绑定`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var seen = '';
            function* g(a = (seen = this.tag + ':' + arguments.length, 0)) { yield a; }
            var host = { tag: 'host', run: g };
            var it = host.run(undefined, 2, 3);
            var beforeNext = seen;
            var value = it.next().value;
            var result = beforeNext + ':' + value;
            """.trimIndent()
        )
        // 默认值在调用阶段就能读取生成器的 this 与 arguments
        assertEquals("host:3:0", runtime.evalString("result"))
    }

    @Test
    fun `生成器方法默认参数在调用时可以使用 super`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            class Base {
                label() { return 'base'; }
            }
            class Derived extends Base {
                *m(a = super.label() + ':' + this.tag) {
                    yield a;
                }
            }
            var instance = new Derived();
            instance.tag = 'derived';
            var it = instance.m();
            var result = it.next().value;
            """.trimIndent()
        )
        // 参数绑定发生在调用阶段，此时方法帧已提供 homeObject/super 与 this
        assertEquals("base:derived", runtime.evalString("result"))
    }

    @Test
    fun `生成器 return 前未启动时参数已经完成绑定`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var calls = 0;
            function* g(a = calls++) { yield 1; }
            var it = g();
            var afterCall = calls;
            var step = it.return(5);
            var result = afterCall + ':' + calls + ':' + step.value + ':' + step.done;
            """.trimIndent()
        )
        // 即使从未 next，调用阶段也已经求值默认值；return 不执行函数体
        assertEquals("1:1:5:true", runtime.evalString("result"))
    }    @Test
    fun `生成器默认参数在调用时捕获参数值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var outer = 1;
            function* g(a = outer) { yield a; }
            var it = g();
            outer = 2;
            var result = it.next().value;
            """.trimIndent()
        )
        // 参数值在调用时完成绑定，后续修改外层变量不会影响已绑定的参数
        assertEquals(1.0, runtime.evalNumber("result"))
    }

    @Test
    fun `生成器默认参数抛错在调用时传播而不是首次 next`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var calls = 0;
            function boom() { throw new Error('param'); }
            function* g(a = (calls++, boom())) { yield 1; }
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
        // 参数默认值异常属于调用阶段，生成器对象不会创建
        assertEquals("param:1:no:null", runtime.evalString("result"))
    }

    @Test
    fun `生成器参数 TDZ 在调用时抛 ReferenceError`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g(a = b, b = 1) { yield a; }
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
    fun `生成器形参中的 yield 早期错误在解析期被拒绝`() = runTest {
        val runtime = TestRuntime()
        // 生成器自身形参：默认值、解构、计算属性名与绑定名
        assertFailsWith<ParseException> { runtime.eval("function* g(a = yield) {}") }
        assertFailsWith<ParseException> { runtime.eval("function* g(a = yield 1) {}") }
        assertFailsWith<ParseException> { runtime.eval("function* g(a = yield* other()) {}") }
        assertFailsWith<ParseException> { runtime.eval("function* g({a = yield}) {}") }
        assertFailsWith<ParseException> { runtime.eval("function* g([a = yield]) {}") }
        assertFailsWith<ParseException> { runtime.eval("function* g({[yield]: a}) {}") }
        assertFailsWith<ParseException> { runtime.eval("function* g({yield}) {}") }
        assertFailsWith<ParseException> { runtime.eval("function* g([yield]) {}") }
        assertFailsWith<ParseException> { runtime.eval("function* g(yield) {}") }
        assertFailsWith<ParseException> { runtime.eval("var f = function* (a = yield) {};") }
        assertFailsWith<ParseException> { runtime.eval("var o = { *m(a = yield) {} };") }
        assertFailsWith<ParseException> { runtime.eval("class C { *m(a = yield) {} }") }
        // 生成器体内嵌套的生成器/生成器方法形参同样是 [+Yield] 语境
        assertFailsWith<ParseException> {
            runtime.eval("function* g() { function* h(a = yield) {} }")
        }
        assertFailsWith<ParseException> {
            runtime.eval("function* g() { !function*(a = yield) {}; }")
        }
        assertFailsWith<ParseException> {
            runtime.eval("function* g() { var o = { *m(a = yield) {} }; }")
        }
        assertFailsWith<ParseException> {
            runtime.eval("function* g() { ({ *m(c = yield d) {} }); }")
        }
        // 箭头函数形参继承外层 [+Yield]：yield 表达式出现在形参中仍是早期错误
        assertFailsWith<ParseException> { runtime.eval("function* g() { (a = yield) => 1; }") }
        assertFailsWith<ParseException> { runtime.eval("function* g() { ([a = yield]) => 1; }") }
        assertFailsWith<ParseException> {
            runtime.eval("function* g() { ({[yield]: a}) => 1; }")
        }
        // 生成器体内的简写属性与绑定名不能使用 yield
        assertFailsWith<ParseException> { runtime.eval("function* g() { var o = {yield}; }") }
        assertFailsWith<ParseException> { runtime.eval("function* g() { var yield = 1; }") }
        assertFailsWith<ParseException> { runtime.eval("function* g() { let yield = 1; }") }
    }

    @Test
    fun `生成器体内嵌套普通函数与箭头体的 yield 仍是合法标识符`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() {
                function helper(yield) { return yield + 1; }
                var arrow = () => yield;
                var method = { yield: 3, m() { return 4; } };
                yield helper(41);
            }
            var it = g();
            var result = it.next().value;
            """.trimIndent()
        )
        // 嵌套普通函数是 [~Yield] 函数边界：形参名、箭头表达式体与对象属性名中的 yield 合法
        assertEquals(42.0, runtime.evalNumber("result"))
    }

    @Test
    fun `生成器体内嵌套生成器可以正常使用 yield 表达式`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* outer() {
                function* inner() {
                    var x = yield 5;
                    yield x + 1;
                }
                var it = inner();
                var first = it.next().value;
                var second = it.next(10).value;
                yield first + second;
            }
            var result = outer().next().value;
            """.trimIndent()
        )
        assertEquals(16.0, runtime.evalNumber("result"))
    }

    @Test
    fun `生成器参数默认值中的函数边界可以包含 yield`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g(
                a = function () { var yield = 1; return yield; }(),
                b = function* () { yield 2; },
                c = () => yield
            ) {
                yield a;
                yield typeof b;
                yield typeof c;
            }
            var it = g();
            var result = it.next().value + ':' + it.next().value + ':' + it.next().value;
            """.trimIndent()
        )
        // 普通函数体、生成器函数体与箭头表达式体都是新的 [~Yield] 语境
        assertEquals("1:function:function", runtime.evalString("result"))
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

    // ================================================================
    // L1.2：对象字面量与 class 中的生成器方法
    // ================================================================

    @Test
    fun `对象字面量生成器方法按迭代协议产出值`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var o = {
                *gen() {
                    yield 1;
                    yield 2;
                }
            };
            var it = o.gen();
            var result = it.next().value + ',' + it.next().value + ',' + it.next().done;
            """.trimIndent()
        )
        assertEquals("1,2,true", runtime.evalString("result"))
    }

    @Test
    fun `生成器方法支持计算键字符串键数字键并推断函数名`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var suffix = 'b';
            var o = {
                *['a' + suffix]() { yield 'computed'; },
                *'str'() { yield 'string'; },
                *7() { yield 'number'; }
            };
            var result = o.ab().next().value + ',' + o.str().next().value + ',' +
                o[7]().next().value + ':' + o.ab.name + ',' + o.str.name + ',' + o[7].name;
            """.trimIndent()
        )
        assertEquals("computed,string,number:ab,str,7", runtime.evalString("result"))
    }

    @Test
    fun `class 生成器方法支持实例静态与计算键形式`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            class C {
                constructor() { this.base = 10; }
                *values() { yield this.base; yield this.base + 1; }
                static *tags() { yield 'static'; }
                *['pre' + 'fix']() { yield 'computed'; }
            }
            var c = new C();
            var it = c.values();
            var result = it.next().value + ',' + it.next().value + ',' +
                C.tags().next().value + ',' + c.prefix().next().value;
            """.trimIndent()
        )
        assertEquals("10,11,static,computed", runtime.evalString("result"))
    }

    @Test
    fun `生成器方法的函数标签与实例原型符合规范`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var o = { *g() { yield 1; }, *h() { yield 2; } };
            function* declared() { yield 0; }
            var it = o.g();
            var result = Object.prototype.toString.call(o.g) + ':' +
                Object.prototype.toString.call(it) + ':' +
                (Object.getPrototypeOf(it) === o.g.prototype) + ':' +
                (o.g.prototype.constructor === o.g) + ':' +
                (Object.getPrototypeOf(o.g.prototype) === Object.getPrototypeOf(declared.prototype)) + ':' +
                (o.g.prototype !== o.h.prototype);
            """.trimIndent()
        )
        assertEquals(
            "[object GeneratorFunction]:[object Generator]:true:true:true:true",
            runtime.evalString("result"),
        )
    }

    @Test
    fun `生成器方法不能作为构造器且源码文本保留星号`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var o = { *g(a) { yield a; } };
            class C { *m(b) { yield b; } }
            var objectNew = '';
            var classNew = '';
            try { new o.g(); } catch (e) { objectNew = e.name; }
            try { new C.prototype.m(); } catch (e) { classNew = e.name; }
            var result = objectNew + ',' + classNew + ':' +
                String(o.g).slice(0, 2) + ',' + String(C.prototype.m).slice(0, 2) +
                ':' + o.g.length + ',' + C.prototype.m.length;
            """.trimIndent()
        )
        assertEquals("TypeError,TypeError:*g,*m:1,1", runtime.evalString("result"))
    }

    @Test
    fun `生成器方法保存 this 绑定并支持 super 属性访问`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var proto = { greet() { return 'hi'; } };
            var o = {
                __proto__: proto,
                marker: 'own',
                *m() {
                    yield super.greet();
                    yield this.marker;
                }
            };
            var it = o.m();
            var first = it.next().value + ',' + it.next().value;
            class Base {
                hello() { return 'base'; }
            }
            class Derived extends Base {
                *m() { yield super.hello(); }
            }
            var result = first + ':' + new Derived().m().next().value;
            """.trimIndent()
        )
        assertEquals("hi,own:base", runtime.evalString("result"))
    }

    @Test
    fun `class 私有生成器方法可以被子类方法委托`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            class C {
                *#items() {
                    yield 1;
                    yield 2;
                }
                *all() { yield* this.#items(); }
            }
            var values = [...new C().all()];
            var tag = Object.prototype.toString.call(C.prototype.all);
            var result = values.join(',') + ':' + tag;
            """.trimIndent()
        )
        assertEquals("1,2:[object GeneratorFunction]", runtime.evalString("result"))
    }

    @Test
    fun `yield 后紧跟正则字面量按正则解析`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            function* g() {
                yield /ab+c/i;
                yield /=3/;
            }
            var it = g();
            var first = it.next().value;
            var second = it.next().value;
            var result = first.source + ':' + first.flags + '|' + second.source;
            """.trimIndent()
        )
        assertEquals("ab+c:i|=3", runtime.evalString("result"))
    }

    @Test
    fun `yield 作为标识符或属性名时斜杠仍按除法解析`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var o = { yield: 8 };
            var propertyDivision = o.yield / 2;
            var yield = 10;
            var identifierDivision = yield / 2;
            var result = propertyDivision + ',' + identifierDivision;
            """.trimIndent()
        )
        assertEquals("4,5", runtime.evalString("result"))
    }

    @Test
    fun `yield星号 可以委托给对象与 class 的生成器方法`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            class Box {
                *items() { yield 'x'; yield 'y'; }
            }
            var box = new Box();
            var o = {
                *inner() { yield 1; yield 2; },
                *outer() {
                    yield* this.inner();
                    yield* box.items();
                    yield 'done';
                }
            };
            var values = [];
            var it = o.outer();
            var step = it.next();
            while (!step.done) {
                values.push(step.value);
                step = it.next();
            }
            var result = values.join(',');
            """.trimIndent()
        )
        assertEquals("1,2,x,y,done", runtime.evalString("result"))
    }

    @Test
    fun `生成器方法名可以是 yield 或 static 等上下文关键字`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var o = { *yield() { yield 'y'; } };
            class C { *static() { yield 's'; } }
            var result = o.yield().next().value + ',' +
                C.prototype['static']().next().value + ':' + o.yield.name;
            """.trimIndent()
        )
        assertEquals("y,s:yield", runtime.evalString("result"))
    }

    @Test
    fun `async 生成器语法解析为 async generator 对象并可推进`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            async function* declared() { yield 1; }
            var expression = async function*() { yield 2; };
            var o = { async *gen() { yield 3; } };
            class C { async *items() { yield 4; } }
            var values = '';
            async function collect() {
                values += (await declared().next()).value;
                values += (await expression().next()).value;
                values += (await o.gen().next()).value;
                values += (await new C().items().next()).value;
            }
            collect();
            """.trimIndent()
        )
        assertEquals("1234", runtime.evalString("values"))
    }

    @Test
    fun `生成器 return 请求会关闭迭代器并释放 using 资源`() = runTest {
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
            function* g() {
                using outer = makeResource('outer');
                for (var value of makeIterable()) {
                    yield value;
                }
            }
            var it = g();
            var first = it.next().value;
            var returned = it.return(7);
            var result = log.join(',') + ':' + first + ':' + returned.done + ':' + returned.value;
            """.trimIndent()
        )
        assertEquals("iterator:1,dispose:outer:1:true:7", runtime.evalString("result"))
    }

    @Test
    fun `生成器 throw 请求同样关闭迭代器并释放 using 资源`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            function makeIterable() {
                var index = 0;
                return {
                    [Symbol.iterator]() { return this; },
                    next() { index++; return { value: index, done: false }; },
                    return() { log.push('iterator'); return {}; }
                };
            }
            function makeResource() {
                return { [Symbol.dispose]() { log.push('dispose'); } };
            }
            function* g() {
                using outer = makeResource();
                for (var value of makeIterable()) {
                    yield value;
                }
            }
            var it = g();
            it.next();
            var name = '';
            try { it.throw(new Error('boom')); } catch (e) { name = e.message; }
            var result = log.join(',') + ':' + name;
            """.trimIndent()
        )
        assertEquals("iterator,dispose:boom", runtime.evalString("result"))
    }

    @Test
    fun `生成器在解构默认值中收到 return 请求会关闭迭代器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
            var log = [];
            function makeIterable() {
                var index = 0;
                return {
                    [Symbol.iterator]() { return this; },
                    next() {
                        index++;
                        return { value: index === 1 ? undefined : index, done: false };
                    },
                    return() { log.push('closed'); return {}; }
                };
            }
            function* g() {
                var [first = yield 'default', second] = makeIterable();
                return first + ':' + second;
            }
            var it = g();
            var step = it.next();
            var returned = it.return(3);
            var result = step.value + ':' + log.join(',') + ':' + returned.done;
            """.trimIndent()
        )
        assertEquals("default:closed:true", runtime.evalString("result"))
    }

    @Test
    fun `生成器方法语法错误在解析期被拒绝`() = runTest {
        val runtime = TestRuntime()
        // class 构造器不能是生成器
        assertFailsWith<ParseException> { runtime.eval("class C { *constructor() {} }") }
        // `*` 不能与 get/set 组合
        assertFailsWith<ParseException> { runtime.eval("var o = { get *a() {} };") }
        assertFailsWith<ParseException> { runtime.eval("var o = { *get a() {} };") }
        assertFailsWith<ParseException> { runtime.eval("class C { set *a(v) {} }") }
        // 生成器方法必须紧跟参数列表
        assertFailsWith<ParseException> { runtime.eval("var o = { *a: 1 };") }
        assertFailsWith<ParseException> { runtime.eval("var o = { *a };") }
        assertFailsWith<ParseException> { runtime.eval("class C { *a = 1; }") }
        // 生成器方法的形参是 [+Yield] 语境：yield 不能作为参数名
        assertFailsWith<ParseException> { runtime.eval("var o = { *g(yield) {} };") }
        assertFailsWith<ParseException> { runtime.eval("class C { *g(yield) {} }") }
        // 生成器方法使用 UniqueFormalParameters：即使 sloppy 也不允许重名
        assertFailsWith<ParseException> { runtime.eval("var o = { *g(a, a) {} };") }
        assertFailsWith<ParseException> { runtime.eval("class C { *g(a, a) {} }") }
    }
}
