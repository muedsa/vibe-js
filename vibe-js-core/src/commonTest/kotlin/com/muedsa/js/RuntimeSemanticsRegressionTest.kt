package com.muedsa.js

import com.muedsa.js.lexer.LexerException
import com.muedsa.js.runtime.exception.JSException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 本轮语义收敛的回归测试：
 * 数组异质属性、参数映射、eval 作用域、JSON 严格语法、正则回退与字符串边界。
 */
class RuntimeSemanticsRegressionTest {

    @Test
    fun `数组 length 描述符与稀疏索引遵循 ES5 异质语义`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("Object.defineProperty([], 'length', { value: '2' }).length"))
        assertEquals("RangeError", runtime.evalString(
            "try { Object.defineProperty([], 'length', { value: -1 }); 'none' } catch (e) { e.name }"
        ))
        assertEquals("RangeError", runtime.evalString(
            "try { Object.defineProperty([], 'length', { value: Infinity }); 'none' } catch (e) { e.name }"
        ))
        assertEquals(4294967295.0, runtime.evalNumber("var a = []; a[4294967294] = 1; a.length"))
        assertEquals(true, runtime.evalBoolean(
            "var a = []; a[4294967295] = 'x'; a.length === 0 && a[4294967295] === 'x'"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var a = [1, 2]; Object.defineProperty(a, 'length', { writable: false }); " +
                "try { a.push(3); false } catch (e) { e instanceof TypeError }"
        ))
        assertEquals(0.0, runtime.evalNumber("var a = [1, 2]; a.length = false; a.length"))
        assertEquals(false, runtime.evalBoolean("var a = [1]; Object.freeze(a); delete a[0]"))
    }

    @Test
    fun `数组方法按 HasProperty 与原型链读取元素`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "function cb(v) { return v > 10; }" +
                "var seen = false; var obj = { 1: 11, length: '2' };" +
                "Array.prototype.forEach.call(obj, function (v) { if (v === 11) seen = true; }); seen"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var accessed = false;" +
                "var obj = {}; Object.defineProperty(obj, 'length', {" +
                "  get: function () { accessed = true; return 2; }, configurable: true });" +
                "try { Array.prototype.forEach.call(obj, null); } catch (e) { } accessed"
        ))
        assertEquals(2.0, runtime.evalNumber("Array.prototype.concat.call({ 0: 'a', length: 1 }, ['b']).length"))
        assertEquals(true, runtime.evalBoolean(
            "Array.prototype[1] = 1; var x = [0]; x.length = 2;" +
                "var out = x.concat(); delete Array.prototype[1];" +
                "out[1] === 1 && out.hasOwnProperty('1')"
        ))
    }

    @Test
    fun `sloppy arguments 的参数映射与 defineProperty 联动`() = runTest {
        val runtime = TestRuntime()
        assertEquals(20.0, runtime.evalNumber(
            "(function (a) { Object.defineProperty(arguments, '0', {" +
                " value: 20, writable: false, enumerable: false, configurable: false }); return a; })(0)"
        ))
        assertEquals(true, runtime.evalBoolean(
            "(function (a) { var ok = false;" +
                " Object.defineProperty(arguments, '0', { value: 10, writable: false, configurable: false });" +
                " try { Object.defineProperty(arguments, '0', { value: 20 }); }" +
                " catch (e) { ok = e instanceof TypeError && a === 10; } return ok; })(0)"
        ))
    }

    @Test
    fun `严格 eval 的声明不泄漏且间接 eval 使用全局作用域`() = runTest {
        val runtime = TestRuntime()
        assertEquals("undefined", runtime.evalString(
            "(function () { 'use strict'; eval('var strictLocal = 1;'); return typeof strictLocal; })()"
        ))
        assertEquals("undefined", runtime.evalString(
            "var indirect = eval; indirect('\"use strict\"; var indirectLocal = 1;'); typeof indirectLocal"
        ))
    }

    @Test
    fun `JSON parse 使用严格语法并保持对象原型`() = runTest {
        val runtime = TestRuntime()
        assertEquals("SyntaxError", runtime.evalString(
            "try { JSON.parse(\"'a'\"); 'none' } catch (e) { e.name }"
        ))
        assertEquals("SyntaxError", runtime.evalString(
            "try { JSON.parse('\"' + String.fromCharCode(1) + '\"'); 'none' } catch (e) { e.name }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var o = JSON.parse('{\"__proto__\": []}');" +
                "Object.getPrototypeOf(o) === Object.prototype && o.hasOwnProperty('__proto__')"
        ))
        assertEquals(2.0, runtime.evalNumber("JSON.parse(' [1, 2] ').length"))
    }

    @Test
    fun `正则在 Kotlin 不支持的 JS 语法上回退到内置引擎`() = runTest {
        val runtime = TestRuntime()
        assertEquals(false, runtime.evalBoolean("/a[]/.test('a')"))
        assertEquals(true, runtime.evalBoolean("/a[^]/.test('ab')"))
        assertEquals(true, runtime.evalBoolean("/\\0/.test(String.fromCharCode(0))"))
        assertEquals("A", runtime.evalString("/\\1(A)/.exec('AA')[0]"))
        assertEquals(true, runtime.evalBoolean(
            "var m = /(z)((a+)?(b+)?(c))*/.exec('zaacbbbcac'); m[2] === 'ac' && m[4] === undefined"
        ))
        assertEquals(true, runtime.evalBoolean("var r = /a/g; r.lastIndex = -1; r.exec('ba')[0] === 'a' && r.lastIndex === 2"))
        assertEquals("SyntaxError", runtime.evalString(
            "try { new RegExp('a++'); 'none' } catch (e) { e.name }"
        ))
    }

    @Test
    fun `正则与错误原型的类标签`() = runTest {
        val runtime = TestRuntime()
        assertEquals("[object RegExp]", runtime.evalString("Object.prototype.toString.call(/a/) "))
        assertEquals("[object Object]", runtime.evalString("Object.prototype.toString.call(RegExp.prototype)"))
        assertEquals("[object Object]", runtime.evalString("Object.prototype.toString.call(Error.prototype)"))
        assertEquals("[object Error]", runtime.evalString("Object.prototype.toString.call(new Error('x'))"))
    }

    @Test
    fun `字符串空白、切片默认值与包装对象转换`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "'\\u00A0\\uFEFFabc\\u2028'.trim() === 'abc'"
        ))
        assertEquals("undefined", runtime.evalString("'undefined'.substring(0, undefined)"))
        assertEquals("undefined", runtime.evalString("'undefined'.slice(0, undefined)"))
        assertEquals(0.0, runtime.evalNumber("String.fromCharCode(Infinity).charCodeAt(0)"))
        assertEquals(0.0, runtime.evalNumber("String.fromCharCode(65536).charCodeAt(0)"))
        assertEquals(true, runtime.evalBoolean(
            "var s = new String('ABC'); s.valueOf = function () { return 'ed'; };" +
                "s.toString = function () { return 'ed'; }; s == 'ed'"
        ))
        assertEquals("a.\u03C2", runtime.evalString("'A.\\u03A3'.toLowerCase()"))
    }

    @Test
    fun `JSON reviver 删除属性时保留其余属性的可枚举性`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "{\"b\":2}",
            runtime.evalString(
                "JSON.stringify(JSON.parse('{\"a\":1,\"b\":2}'," +
                    " function (k, v) { return k === 'a' ? undefined : v; }))"
            )
        )
    }

    @Test
    fun `arguments 的映射索引在描述符层面表现为数据属性`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "7,true,true,true",
            runtime.evalString(
                "(function (a) { var d = Object.getOwnPropertyDescriptor(arguments, '0');" +
                    " return [d.value, d.writable, d.enumerable, d.configurable].join(','); })(7)"
            )
        )
    }

    @Test
    fun `不可扩展对象的 __proto__ 赋值抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "TypeError",
            runtime.evalString(
                "try { var x = Object.preventExtensions({}); x.__proto__ = { a: 1 }; 'none' }" +
                    " catch (e) { e.name }"
            )
        )
    }

    @Test
    fun `u 标志正则支持 unicode 码点转义`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("/\\u{1F600}/u.test(String.fromCodePoint(128512))"))
    }

    @Test
    fun `宽松相等不会对对象做多余的 ToPrimitive`() = runTest {
        val runtime = TestRuntime()
        assertEquals(false, runtime.evalBoolean("null == ({})"))
        assertEquals(false, runtime.evalBoolean("undefined == []"))
        assertEquals(false, runtime.evalBoolean(
            "var obj = { toString: function () { throw 1; } }; null == obj"
        ))
    }

    @Test
    fun `Object 原型方法与 instanceof 的接收者检查`() = runTest {
        val runtime = TestRuntime()
        assertEquals("object", runtime.evalString("typeof Object.prototype.valueOf.call(true)"))
        assertEquals("TypeError", runtime.evalString(
            "try { Object.prototype.valueOf.call(null); 'none' } catch (e) { e.name }"
        ))
        assertEquals("TypeError", runtime.evalString(
            "try { Object.prototype.toLocaleString.call(undefined); 'none' } catch (e) { e.name }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var seen = false; var d = Object.getOwnPropertyDescriptor({ x: 1 }, 'x');" +
                "for (var k in d) { if (k === 'value') seen = true; } seen"
        ))
        assertEquals(true, runtime.evalBoolean(
            "function F() {} F.prototype = undefined;" +
                "try { ({} instanceof F); false } catch (e) { e instanceof TypeError }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "function F(x) { this.x = x; } var B = F.bind(null, 7); new B().x === 7"
        ))
        assertEquals(true, runtime.evalBoolean(
            "(function () { 'use strict'; function f() {}" +
                "try { f.caller; return false; } catch (e) { return e instanceof TypeError; } })()"
        ))
        assertEquals("undefined", runtime.evalString("typeof (function f() {}).caller"))
        assertEquals(false, runtime.evalBoolean(
            "var proto = { p: 1 }; var child = Object.create(proto);" +
                "Object.defineProperty(child, 'p', { value: 2, enumerable: false });" +
                "var seen = false; for (var k in child) { if (k === 'p') seen = true; } seen"
        ))
    }

    @Test
    fun `ES2015 匿名函数与类的名称推断`() = runTest {
        val runtime = TestRuntime()
        assertEquals("f", runtime.evalString("var f = function () {}; f.name"))
        assertEquals("g", runtime.evalString("var g; g = () => {}; g.name"))
        assertEquals("m", runtime.evalString("({ m: function () {} }).m.name"))
        assertEquals("c", runtime.evalString("var c = class {}; c.name"))
        assertEquals("d", runtime.evalString("var [d = class {}] = []; d.name"))
        assertEquals("[s]", runtime.evalString("var s = Symbol('s'); ({ [s]() {} })[s].name"))
        assertEquals("", runtime.evalString("var anon = Symbol(); ({ [anon]() {} })[anon].name"))
        assertEquals("get x", runtime.evalString("Object.getOwnPropertyDescriptor({ get x() {} }, 'x').get.name"))
        assertEquals("set y", runtime.evalString("Object.getOwnPropertyDescriptor({ set y(v) {} }, 'y').set.name"))
        // 具名类表达式与静态 name 成员不被推断覆盖
        assertEquals("X", runtime.evalString("var xCls = class X {}; xCls.name"))
        assertEquals("function", runtime.evalString("var sn = class { static name() {} }; typeof sn.name"))
    }

    @Test
    fun `ES2015 迭代器协议与 IteratorClose`() = runTest {
        val runtime = TestRuntime()
        // 解构正常结束时调用 return；迭代器未 done 时 close
        assertEquals("1,2,2,1", runtime.evalString(
            """
            var nextCount = 0, returnCount = 0;
            var iterable = {};
            iterable[Symbol.iterator] = function () {
                return {
                    next: function () { nextCount++; return { done: nextCount > 2, value: nextCount }; },
                    return: function () { returnCount++; return {}; }
                };
            };
            var a, b;
            [a, b] = iterable;
            [a, b, nextCount, returnCount].join(',');
            """.trimIndent()
        ))
        // for-of break 调用 return；return 返回非对象抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
            var closed = 0;
            var iter = { next: function () { return { done: false, value: 1 }; }, return: function () { closed++; return {}; } };
            var o = {}; o[Symbol.iterator] = function () { return iter; };
            for (var x of o) { break; }
            closed === 1
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
            var iter = { next: function () { return { done: false, value: 1 }; }, return: function () { return 0; } };
            var o = {}; o[Symbol.iterator] = function () { return iter; };
            try { for (var x of o) { break; } false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
    }

    @Test
    fun `内建错误与集合接收者校验`() = runTest {
        val runtime = TestRuntime()
        // 原生代码抛出的错误经 catch 后 constructor 身份正确
        assertEquals(true, runtime.evalBoolean(
            "try { Date.prototype.getTime.call(0); false } catch (e) { e.constructor === TypeError }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { Map.prototype.get.call(new WeakMap(), 1); false } catch (e) { e instanceof TypeError }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { Set.prototype.add.call(new WeakSet(), 1); false } catch (e) { e instanceof TypeError }"
        ))
    }

    @Test
    fun `ES2015 方法不可构造且无 prototype`() = runTest {
        val runtime = TestRuntime()
        assertEquals("undefined", runtime.evalString("typeof ({ m() {} }).m.prototype"))
        assertEquals(true, runtime.evalBoolean(
            "try { new ({ m() {} }).m(); false } catch (e) { e instanceof TypeError }"
        ))
        assertEquals(
            "undefined",
            runtime.evalString(
                "typeof Object.getOwnPropertyDescriptor({ get x() {} }, 'x').get.prototype"
            )
        )
        // 冒号形式的普通函数仍是构造器
        assertEquals("object", runtime.evalString("typeof ({ m: function () {} }).m.prototype"))
    }

    @Test
    fun `参数 TDZ 与间接 eval 词法隔离`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "try { (function (a = b, b = 1) {})(); false } catch (e) { e instanceof ReferenceError }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { (function (a = a) {})(); false } catch (e) { e instanceof ReferenceError }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "let outside = 1; (0, eval)('let outside;'); typeof outside === 'number'"
        ))
    }

    @Test
    fun `RegExp 原型访问器与 Date 的 Symbol toPrimitive`() = runTest {
        val runtime = TestRuntime()
        assertEquals("g", runtime.evalString(
            "Object.getOwnPropertyDescriptor(RegExp.prototype, 'flags').get.call({ global: true })"
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { Object.getOwnPropertyDescriptor(RegExp.prototype, 'global').get.call(1);" +
                " false } catch (e) { e instanceof TypeError }"
        ))
        assertEquals(true, runtime.evalBoolean("+new Date(0) === 0"))
        assertEquals(true, runtime.evalBoolean(
            "try { new Date(0)[Symbol.toPrimitive]('bogus'); false }" +
                " catch (e) { e instanceof TypeError }"
        ))
    }

    @Test
    fun `Function 的 Symbol hasInstance 与绑定函数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "class A {}; var a = new A(); Function.prototype[Symbol.hasInstance].call(A, a)"
        ))
        assertEquals(false, runtime.evalBoolean(
            "Function.prototype[Symbol.hasInstance].call(1, {})"
        ))
        assertEquals(true, runtime.evalBoolean(
            "function F() {} var b = F.bind(); var o = new F(); b[Symbol.hasInstance](o)"
        ))
    }

    @Test
    fun `ES2015 内建补充与包装对象标签`() = runTest {
        val runtime = TestRuntime()
        assertEquals(false, runtime.evalBoolean("Number.isSafeInteger('1')"))
        assertEquals(true, runtime.evalBoolean("Number.isSafeInteger(42)"))
        assertEquals(true, runtime.evalBoolean(
            "var d = Object.getOwnPropertyDescriptors({ a: 1 }); d.a.value === 1 && d.a.enumerable"
        ))
        // arguments 自有 @@iterator，Array.from 可按协议读取
        assertEquals("1,2", runtime.evalString(
            "var o = (function () { return arguments; })(1, 2); Array.from(o).join(',')"
        ))
        // 包装对象与原型对象的内建标签；实例自定义标签优先
        assertEquals("[object Number]", runtime.evalString(
            "Object.prototype.toString.call(Number.prototype)"
        ))
        assertEquals("[object String]", runtime.evalString(
            "Object.prototype.toString.call(new String('x'))"
        ))
        assertEquals("[object test262]", runtime.evalString(
            "var x = []; x[Symbol.toStringTag] = 'test262'; Object.prototype.toString.call(x)"
        ))
        // Date.prototype.toJSON 使用 ToObject + ToPrimitive(number) + toISOString
        assertEquals("x", runtime.evalString(
            "Date.prototype.toJSON.call({ toISOString: function () { return 'x'; } })"
        ))
    }

    @Test
    fun `Array species 与 isConcatSpreadable`() = runTest {
        val runtime = TestRuntime()
        // ArraySpeciesCreate 使用 constructor[@@species] 构造结果
        assertEquals(true, runtime.evalBoolean(
            "var arr = [1, 2];" +
                " var Ctor = function (length) { this.length = length; }; Ctor.prototype = [];" +
                " var holder = {}; holder[Symbol.species] = Ctor; arr.constructor = holder;" +
                " var mapped = arr.map(function (x) { return x * 2; });" +
                " mapped instanceof Ctor && mapped.length === 2 && mapped[0] === 2 && mapped[1] === 4"
        ))
        // species 为 null 时回退内建数组
        assertEquals(true, runtime.evalBoolean(
            "var a = [1]; a.constructor = { [Symbol.species]: null };" +
                " var r = a.map(function (x) { return x; });" +
                " Array.isArray(r) && Object.getPrototypeOf(r) === Array.prototype"
        ))
        // species 非构造器抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var b = [1]; b.constructor = { [Symbol.species]: 1 };" +
                " try { b.map(function (x) { return x; }); false } catch (e) { e instanceof TypeError }"
        ))
        // @@isConcatSpreadable 控制展开，并按 n 设置结果长度（空洞计入）
        assertEquals("1,2,3,4", runtime.evalString(
            "var o = { 0: 3, 1: 4, length: 2 }; o[Symbol.isConcatSpreadable] = true;" +
                " [].concat([1, 2], o).join(',')"
        ))
        assertEquals(6.0, runtime.evalNumber(
            "var args = (function (a, b, c) { return arguments; })(1, 2, 3);" +
                " args[Symbol.isConcatSpreadable] = true;" +
                " Object.defineProperty(args, 'length', { value: 6 });" +
                " [].concat(args).length"
        ))
        // 重复形参名不建立 arguments 映射
        assertEquals("1,2,3", runtime.evalString(
            "(function (a, a, a) { return [arguments[0], arguments[1], arguments[2]].join(','); })(1, 2, 3)"
        ))
        // Map/Set/RegExp 的 @@species getter 返回 this
        assertEquals(true, runtime.evalBoolean(
            "Map[Symbol.species] === Map && Set[Symbol.species] === Set && RegExp[Symbol.species] === RegExp"
        ))
    }

    @Test
    fun `Map WeakMap upsert 与 Error cause`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1:3:2", runtime.evalString(
            "var upsertMap = new Map(); upsertMap.set('a', 1);" +
                " [upsertMap.getOrInsert('a', 2), upsertMap.getOrInsert('b', 3)," +
                " upsertMap.size].join(':')"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var computedMap = new Map(); var calls = 0;" +
                " var got = computedMap.getOrInsertComputed(-0, function (k) { calls++; return Object.is(k, 0); });" +
                " got === true && calls === 1 && computedMap.has(0)"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var upsertWeak = new WeakMap(); var weakKey = {}; upsertWeak.set(weakKey, 1);" +
                " upsertWeak.getOrInsert(weakKey, 2) === 1 && upsertWeak.getOrInsert({}, 3) === 3"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var causeValue = { c: 1 }; var causedError = new Error('m', { cause: causeValue });" +
                " causedError.cause === causeValue &&" +
                " Object.getOwnPropertyDescriptor(causedError, 'cause').enumerable === false &&" +
                " !('cause' in new Error('m'))"
        ))
    }

    @Test
    fun `RegExp escape 字面量转义规则`() = runTest {
        val runtime = TestRuntime()
        // 首字符为 ASCII 字母/数字时使用 \xHH
        assertEquals("\\x312a", runtime.evalString("RegExp.escape('12a')"))
        assertEquals("\\.\\*", runtime.evalString("RegExp.escape('.*')"))
        assertEquals("\\/", runtime.evalString("RegExp.escape('/')"))
        assertEquals("\\x20", runtime.evalString("RegExp.escape(' ')"))
        assertEquals("\\u2028", runtime.evalString("RegExp.escape('\\u2028')"))
        assertEquals("\\ud800", runtime.evalString("RegExp.escape('\\uD800')"))
        assertEquals("\\x2c", runtime.evalString("RegExp.escape(',')"))
        assertEquals("你", runtime.evalString("RegExp.escape('你')"))
        // 仅接受字符串参数
        assertEquals(true, runtime.evalBoolean(
            "try { RegExp.escape(1); false } catch (e) { e instanceof TypeError }"
        ))
    }

    @Test
    fun `Promise withResolvers try 与 finally`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            "var resolvedWith = Promise.withResolvers(); var wrValue = 0;" +
                " resolvedWith.promise.then(function (v) { wrValue = v; });" +
                " resolvedWith.resolve(42);"
        )
        assertEquals(42.0, runtime.evalNumber("wrValue"))
        assertEquals(true, runtime.evalBoolean(
            "Promise.withResolvers().resolve.name === '' &&" +
                " Promise.withResolvers().reject.length === 1"
        ))
        runtime.eval(
            "var tryValue = 0; Promise.try(function (a, b) { return a + b; }, 1, 2)" +
                ".then(function (v) { tryValue = v; });"
        )
        assertEquals(3.0, runtime.evalNumber("tryValue"))
        runtime.eval(
            "var sentinelPromise = Promise.resolve(1);" +
                " var trySame = Promise.try(function () { return sentinelPromise; });"
        )
        assertEquals(true, runtime.evalBoolean("trySame === sentinelPromise"))
        // finally 透传值 / 回调抛错 / 回调返回拒绝
        runtime.eval(
            "var passValue = 0; Promise.resolve(7).finally(function () {})" +
                ".then(function (v) { passValue = v; });"
        )
        assertEquals(7.0, runtime.evalNumber("passValue"))
        runtime.eval(
            "var thrownReason = ''; Promise.resolve(1).finally(function () { throw 'x'; })" +
                ".catch(function (e) { thrownReason = e; });"
        )
        assertEquals("x", runtime.evalString("thrownReason"))
        runtime.eval(
            "var finLog = ''; Promise.resolve('v').finally(function () {" +
                " finLog += 'f'; return Promise.reject('bad'); })" +
                ".catch(function (e) { finLog += ':' + e; });"
        )
        assertEquals("f:bad", runtime.evalString("finLog"))
    }

    @Test
    fun `Object groupBy 与 Map groupBy`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "var grouped = Object.groupBy([1, 2, 3, 4], function (v) { return v % 2 ? 'odd' : 'even'; });" +
                " Object.getPrototypeOf(grouped) === null &&" +
                " grouped.odd.join(',') === '1,3' && grouped.even.join(',') === '2,4'"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var mapGrouped = Map.groupBy(['a', 'bb', 'c'], function (v) { return v.length; });" +
                " mapGrouped instanceof Map && mapGrouped.get(1).join(',') === 'a,c' &&" +
                " mapGrouped.get(2)[0] === 'bb'"
        ))
        // 字符串按码点迭代，代理对作为一个元素
        assertEquals(true, runtime.evalBoolean(
            "var codePoints = Object.groupBy('\\uD83D\\uDCA9\\uD83D\\uDE08', function (v) { return v.length; });" +
                " codePoints[2].length === 2"
        ))
        // 回调抛错时关闭迭代器
        assertEquals(true, runtime.evalBoolean(
            "var closed = 0; var iterable = {};" +
                " iterable[Symbol.iterator] = function () { var i = 0;" +
                " return { next: function () { return { value: i++, done: false }; }," +
                " return: function () { closed++; return {}; } }; };" +
                " try { Object.groupBy(iterable, function () { throw new RangeError('x'); }); } catch (e) {}" +
                " closed === 1"
        ))
    }

    @Test
    fun `Set 方法集结果与顺序`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1,2,3", runtime.evalString(
            "Array.from(new Set([1, 2]).union(new Set([2, 3]))).join(',')"
        ))
        // this.size <= other.size 时按接收者顺序，否则按参数 keys 顺序
        assertEquals("2,3", runtime.evalString(
            "Array.from(new Set([1, 2, 3]).intersection(new Set([3, 2, 9]))).join(',')"
        ))
        assertEquals("1,3", runtime.evalString(
            "Array.from(new Set([1, 2, 3]).difference(new Set([2]))).join(',')"
        ))
        assertEquals("1,3", runtime.evalString(
            "Array.from(new Set([1, 2]).symmetricDifference(new Set([2, 3]))).join(',')"
        ))
        assertEquals(true, runtime.evalBoolean(
            "new Set([1, 2]).isSubsetOf(new Set([1, 2, 3])) &&" +
                " new Set([1, 2, 3]).isSupersetOf(new Set([1, 2])) &&" +
                " new Set([1, 2]).isDisjointFrom(new Set([3, 4]))"
        ))
        // set-like 对象（非生成器迭代器）与 -0 归一化
        assertEquals("1,2,3", runtime.evalString(
            "var setLike = { size: 2, has: function (v) { return v === 2; }," +
                " keys: function () { return [2, 3].values(); } };" +
                " Array.from(new Set([1, 2]).union(setLike)).join(',')"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var zero = Array.from(new Set([-0]).union(new Set([1])))[0];" +
                " Object.is(zero, 0) && 1 / zero === Infinity"
        ))
        // 子类接收者返回普通 Set，且不读取 Symbol.species
        assertEquals(true, runtime.evalBoolean(
            "var speciesCalls = 0;" +
                " class MySet extends Set { static get [Symbol.species]() { speciesCalls++; return Set; } }" +
                " var combined = new MySet([1]).union(new Set([2]));" +
                " combined instanceof Set && !(combined instanceof MySet) && speciesCalls === 0"
        ))
    }

    @Test
    fun `数字分隔符词法规则`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1234567.0, runtime.evalNumber("1_234_567"))
        assertEquals(0.000123, runtime.evalNumber("1.2_3e-4"))
        assertEquals(true, runtime.evalBoolean(
            "0x1_0 === 16 && 0b1_0 === 2 && 0o1_0 === 8"
        ))
        for (invalid in listOf(
            "1__0", "1_", "0x_1", "0b1_", "1._0", "1_.0", "1e_1", "0_1", "08_1",
        )) {
            assertFailsWith<LexerException>(invalid) { runtime.eval(invalid) }
        }
    }

    @Test
    fun `Symbol unscopables 与 with 绑定解析`() = runTest {
        val runtime = TestRuntime()
        // Array.prototype[@@unscopables] 属性形状
        assertEquals(true, runtime.evalBoolean(
            "var unscopableList = Array.prototype[Symbol.unscopables];" +
                " Object.getPrototypeOf(unscopableList) === null &&" +
                " unscopableList.values === true && unscopableList.at === true &&" +
                " unscopableList.with === undefined &&" +
                " Object.getOwnPropertyDescriptor(Array.prototype, Symbol.unscopables).writable === false"
        ))
        // with 中数组方法被屏蔽，解析到外层同名变量
        assertEquals("outer", runtime.evalString(
            "var values = 'outer'; var outerArray = [1, 2];" +
                " var captured = ''; with (outerArray) { captured = values; } captured"
        ))
        // unscopables getter 抛错向外传播
        assertEquals(true, runtime.evalBoolean(
            "var throwingEnv = { x: 1 };" +
                " Object.defineProperty(throwingEnv, Symbol.unscopables," +
                " { get: function () { throw new RangeError('boom'); } });" +
                " try { with (throwingEnv) { x; } false } catch (e) { e instanceof RangeError }"
        ))
        // getter 删除绑定：sloppy 读 undefined
        assertEquals(true, runtime.evalBoolean(
            "var deletingEnv = { y: 1 };" +
                " Object.defineProperty(deletingEnv, Symbol.unscopables," +
                " { get: function () { delete deletingEnv.y; return null; } });" +
                " var deletedRead; with (deletingEnv) { deletedRead = y; }" +
                " deletedRead === undefined"
        ))
    }

    @Test
    fun `String replaceAll 分派与替换模式`() = runTest {
        val runtime = TestRuntime()
        assertEquals("X-X-X", runtime.evalString("'a-a-a'.replaceAll('a', 'X')"))
        assertEquals("-a-b-", runtime.evalString("'ab'.replaceAll('', '-')"))
        assertEquals("a[b]c[b]d", runtime.evalString("'abcbd'.replaceAll('b', '[$&]')"))
        assertEquals("0:a,1:b", runtime.evalString(
            "var seen = [];" +
                " 'abc'.replaceAll(/[ab]/g, function (m, i) { seen.push(i + ':' + m); return ''; });" +
                " seen.join(',')"
        ))
        // 非全局正则抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "try { 'a'.replaceAll(/a/, 'x'); false } catch (e) { e instanceof TypeError }"
        ))
        // 自定义 @@replace 优先，且不触发 receiver/replaceValue 的 toString
        assertEquals("custom", runtime.evalString(
            "var searcher = {}; searcher[Symbol.replace] = function () { return 'custom'; };" +
                " var repl = { toString: function () { throw new Error('no'); } };" +
                " 'x'.replaceAll(searcher, repl)"
        ))
    }

    @Test
    fun `String matchAll 与 RegExp matchAll 迭代器`() = runTest {
        val runtime = TestRuntime()
        assertEquals("a1,b2", runtime.evalString(
            "var collected = [];" +
                " for (var m of 'a1b2'.matchAll(/[a-z](\\d)/g)) { collected.push(m[0]); }" +
                " collected.join(',')"
        ))
        // lastIndex 缓存到 matcher，不影响原正则
        assertEquals(true, runtime.evalBoolean(
            "var re = /./g; re.lastIndex = 1; var iter = re[Symbol.matchAll]('abc');" +
                " re.lastIndex = 0; var first = iter.next();" +
                " first.value[0] === 'b' && first.value.index === 1 && iter.next().value[0] === 'c'"
        ))
        // 非全局正则抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "try { 'a'.matchAll(/a/); false } catch (e) { e instanceof TypeError }"
        ))
        // 迭代器自迭代并带 toStringTag
        assertEquals(true, runtime.evalBoolean(
            "var iter = 'a'.matchAll(); iter[Symbol.iterator]() === iter &&" +
                " Object.getPrototypeOf(iter)[Symbol.toStringTag] === 'RegExp String Iterator'"
        ))
        // 空匹配迭代完整
        assertEquals(2.0, runtime.evalNumber("Array.from('a'.matchAll()).length"))
    }

    @Test
    fun `可选链调用保留 this 与短路语义`() = runTest {
        val runtime = TestRuntime()
        val setup = "var chainObj = { b: function () { return this._b; }, _b: { c: 42 } };"
        runtime.eval(setup)
        val expressions = listOf(
            "chainObj?.b().c",
            "(chainObj?.b)().c",
            "chainObj.b?.().c",
            "(chainObj.b)?.().c",
            "chainObj?.b?.().c",
            "(chainObj?.b)?.().c",
        )
        for (expression in expressions) {
            assertEquals(42.0, runtime.evalNumber(expression), expression)
        }
        // 短路时后续键与参数不求值
        assertEquals(true, runtime.evalBoolean(
            "var side = 0; var empty = null;" +
                " var r = empty?.[side++]?.missing(side++);" +
                " r === undefined && side === 0"
        ))
        // delete 可选链
        assertEquals(true, runtime.evalBoolean(
            "var delObj = { x: 1 }; delete delObj?.x; !('x' in delObj)"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var delEmpty = null; delete delEmpty?.x"
        ))
    }

    @Test
    fun `RegExp Symbol 协议与 String 方法分派`() = runTest {
        val runtime = TestRuntime()
        // @@match：全局收集与内建 exec 结果
        assertEquals("a,b", runtime.evalString("'a1b2'.match(/[a-z]/g).join(',')"))
        assertEquals(true, runtime.evalBoolean(
            "var m = /b(c)?/.exec('abc'); m.index === 1 && m[0] === 'bc' && m[1] === 'c'"
        ))
        // @@replace：$&、$`、$' 与捕获组
        assertEquals("a[b]c", runtime.evalString("'abc'.replace(/b/, '[$&]')"))
        assertEquals("aac", runtime.evalString("'abc'.replace(/b/, '$`')"))
        assertEquals("acc", runtime.evalString("'abc'.replace(/b/, \"$'\")"))
        assertEquals("a[c]e", runtime.evalString(
            "'abcde'.replace(/b(c)(z)?(.)/, '[$1]')"
        ))
        // @@split：保留捕获组
        assertEquals("a,c,", runtime.evalString("'abc'.split(/b(c)?/).join(',')"))
        // 自定义 @@match 优先于内建正则
        assertEquals("custom", runtime.evalString(
            "var matcher = {}; matcher[Symbol.match] = function () { return 'custom'; };" +
                " 'x'.match(matcher)"
        ))
        // @@replace 的 flags/global 读取顺序与属性覆盖
        assertEquals("XX", runtime.evalString(
            "var re = /a/g; Object.defineProperty(re, 'flags', { get: function () { return 'g'; } });" +
                " Object.defineProperty(re, 'global', { get: function () { return true; } });" +
                " 'aa'.replace(re, 'X')"
        ))
    }

    @Test
    fun `strict 模式下访问器 setter 写入全局 var`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber(
            "\"use strict\"; var strictX = 0; var strictO = { set v(n) { strictX = n; } }; strictO.v = 1; strictX"
        ))
        assertEquals("1,", runtime.evalString(
            "\"use strict\"; var strictIndices = '';" +
                " var strictAccessor = { set lastIndex(val) { strictIndices += val + ','; }, get lastIndex() { return 0; } };" +
                " strictAccessor.lastIndex = 1; strictIndices"
        ))
    }

    @Test
    fun `change-array-by-copy 返回新数组且忽略 species`() = runTest {
        val runtime = TestRuntime()
        assertEquals("[4,3,2,1]", runtime.evalString(
            "JSON.stringify([1,2,3,4].toReversed())"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var a = [1,2,3]; a.toReversed(); a.join(',') === '1,2,3'"
        ))
        assertEquals("[1,2,3,11]", runtime.evalString(
            "JSON.stringify([3,11,2,1].toSorted(function (x, y) { return x - y; }))"
        ))
        assertEquals("[3,1,1,2]", runtime.evalString(
            "JSON.stringify([3,1,2].toSpliced(1, 0, 1))"
        ))
        assertEquals("[0,9,2]", runtime.evalString(
            "JSON.stringify([0,1,2].with(1, 9))"
        ))
        // 空洞会被 Get 读取并物化为自有 undefined
        assertEquals(true, runtime.evalBoolean(
            "var h = [1, , 3]; var r = h.toReversed();" +
                " r.hasOwnProperty(1) && r[1] === undefined"
        ))
        // with 越界抛 RangeError
        assertEquals(true, runtime.evalBoolean(
            "try { [1].with(3, 0); false } catch (e) { e instanceof RangeError }"
        ))
    }

    @Test
    fun `Object hasOwn fromEntries 与 findLast 及 well-formed 字符串`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "Object.hasOwn({ a: 1 }, 'a') && !Object.hasOwn({}, 'a')"
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { Object.hasOwn(null, 'a'); false } catch (e) { e instanceof TypeError }"
        ))
        assertEquals("a=1,b=2", runtime.evalString(
            "var o = Object.fromEntries([['a', 1], ['b', 2]]); 'a=' + o.a + ',b=' + o.b"
        ))
        // findLast/findLastIndex 从尾部查找，空洞按 undefined 传给回调
        assertEquals(3.0, runtime.evalNumber(
            "[1, 2, 3, 4].findLast(function (x) { return x % 2 === 1; })"
        ))
        assertEquals(1.0, runtime.evalNumber(
            "[1, 2, 3, 4].findLastIndex(function (x) { return x < 3; })"
        ))
        assertEquals("2:3,1:undefined,0:1", runtime.evalString(
            "var holes = [1, , 3]; var seen = [];" +
                " holes.findLast(function (v, i) { seen.push(i + ':' + v); return false; });" +
                " seen.join(',')"
        ))
        // well-formed：孤立代理项检测与替换
        assertEquals(false, runtime.evalBoolean("'\\uD800'.isWellFormed()"))
        assertEquals(true, runtime.evalBoolean("'\\uD83D\\uDE00'.isWellFormed()"))
        assertEquals(true, runtime.evalBoolean(
            "var replaced = '\\uD800'.toWellFormed();" +
                " replaced.length === 1 && replaced.charCodeAt(0) === 0xFFFD"
        ))
    }

    @Test
    fun `Array flat 与 flatMap 的深度与 species`() = runTest {
        val runtime = TestRuntime()
        assertEquals("[1,2,3,[4]]", runtime.evalString(
            "JSON.stringify([1, [2, [3, [4]]]].flat(2))"
        ))
        assertEquals("[1,2,3,4]", runtime.evalString(
            "JSON.stringify([1, [2, [3, [4]]]].flat(Infinity))"
        ))
        assertEquals("[1,3,2,4]", runtime.evalString(
            "JSON.stringify([1, 2].flatMap(function (x) { return [x, x + 2]; }))"
        ))
        // flatMap 只展开一层映射结果
        assertEquals("[[2],[3]]", runtime.evalString(
            "JSON.stringify([1].flatMap(function () { return [[2], [3]]; }))"
        ))
        // species 构造器用于结果容器：构造参数为 0，返回对象即结果
        assertEquals(true, runtime.evalBoolean(
            "var a = [1, [2]]; var instance = []; var calledWith = -1;" +
                " var C = function (len) { calledWith = len; return instance; };" +
                " var holder = {}; holder[Symbol.species] = C; a.constructor = holder;" +
                " var r = a.flat(); r === instance && calledWith === 0 && r.join(',') === '1,2'"
        ))
    }

    @Test
    fun `参数尾随逗号与不可变绑定语义`() = runTest {
        val runtime = TestRuntime()
        // 形参列表允许尾随逗号（含 async 函数）
        assertEquals(3.0, runtime.evalNumber("function add(a, b,) { return a + b; } add(1, 2,)"))
        assertEquals(true, runtime.evalBoolean("(async function (a,) { return a; }) !== null"))
        // const 是严格不可变绑定：sloppy 下的普通赋值与解构赋值都抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "const c = null; try { [c] = [1]; false } catch (e) { e instanceof TypeError }"
        ))
        // 具名函数表达式的名字绑定是非严格不可变：sloppy 静默忽略，严格模式抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var ref = function BindingIdentifier() { BindingIdentifier = 1; return BindingIdentifier; };" +
                " ref() === ref"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var strictRef = function BindingIdentifier() { 'use strict'; BindingIdentifier = 1; };" +
                " try { strictRef(); false } catch (e) { e instanceof TypeError }"
        ))
    }

    @Test
    fun `Promise capability 遵循 SpeciesConstructor 与执行器约束`() = runTest {
        val runtime = TestRuntime()
        // then 通过 @@species 使用子类构造器，结果仍是子类实例
        assertEquals(true, runtime.evalBoolean(
            "class SubPromise extends Promise {};" +
                "var p = SubPromise.resolve(1); p.then(function () {}) instanceof SubPromise"
        ))
        // Promise.resolve 对同构造器的 Promise 原样返回
        assertEquals(true, runtime.evalBoolean(
            "var q = Promise.resolve(1); Promise.resolve(q) === q"
        ))
        // capability executor 第二次携带函数参数调用时抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var C = class extends Promise {" +
                " constructor(executor) { super(executor); executor(function () {}, function () {}); }" +
                " }; try { new C(function () {}).then(); false } catch (e) { e instanceof TypeError }"
        ))
        // catch 泛化为 Invoke(this, 'then', «undefined, onRejected»)
        assertEquals(2.0, runtime.evalNumber(
            "Boolean.prototype.then = function () { return arguments.length; };" +
                "Promise.prototype.catch.call(true)"
        ))
    }

    @Test
    fun `AsyncFunction 原型链与动态构造`() = runTest {
        val runtime = TestRuntime()
        // %AsyncFunction.prototype% 是普通不可调用对象，带 @@toStringTag
        assertEquals("object", runtime.evalString(
            "typeof Object.getPrototypeOf(async function () {})"
        ))
        assertEquals("AsyncFunction", runtime.evalString(
            "var F = async function () {}.constructor; F.prototype[Symbol.toStringTag]"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var F = async function () {}.constructor; Object.getPrototypeOf(F) === Function.prototype"
        ))
        // async 函数没有 own prototype；动态构造保留源码用于 toString
        assertEquals(true, runtime.evalBoolean(
            "var g = (async function () {}).constructor('a', 'return a;');" +
                " g instanceof Function && !g.hasOwnProperty('prototype')"
        ))
        assertEquals("async function anonymous(a\n) {\nreturn a;\n}", runtime.evalString(
            "(async function () {}).constructor('a', 'return a;').toString()"
        ))
    }

    @Test
    fun `模板表达式中的正则字面量不影响花括号配对`() = runTest {
        val runtime = TestRuntime()
        // 正则内的引号曾被当作字符串起点，导致 ${...} 扫描越界
        assertEquals("preapost", runtime.evalString(
            "var s = `pre\${'a'.replace(/'/g, 'b')}post`; s"
        ))
        // 除号不应被误判为正则起点
        assertEquals("3", runtime.evalString("var s = `\${6 / 2}`; s"))
    }

    @Test
    fun `Iterator helpers 先校验参数再读取 next 并在失败时关闭`() = runTest {
        val runtime = TestRuntime()
        // 非可调用 mapper：参数校验失败，调用 return 关闭，且不读取 next
        assertEquals("return", runtime.evalString(
            "var effects = [];" +
                "var o = Object.create(Iterator.prototype);" +
                "Object.defineProperty(o, 'next', { get: function () {" +
                " effects.push('next'); return function () { return { done: true }; }; } });" +
                "o.return = function () { effects.push('return'); return {}; };" +
                "try { o.map(); } catch (e) { e instanceof TypeError; }" +
                "effects.join(',')"
        ))
        // take 先 ToNumber(limit)，再读取 next
        assertEquals("limit,next", runtime.evalString(
            "var effects = [];" +
                "var o = { get next() { effects.push('next');" +
                " return function () { return { done: true }; }; } };" +
                "Iterator.prototype.take.call(o, { valueOf: function () {" +
                " effects.push('limit'); return 0; } });" +
                "effects.join(',')"
        ))
        // next 不可调用在构造 helper 时不报错，首次 next() 才抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var it = Iterator.prototype.map.call({ next: 0 }, function (v) { return v; });" +
                "try { it.next(); false } catch (e) { e instanceof TypeError }"
        ))
        // helper 重入（生成器运行中）抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var entered = 0;" +
                "var iter = { next: function () { entered++; it.next(); return { done: false }; } };" +
                "var it = Iterator.prototype.take.call(iter, 10);" +
                "try { it.next(); false } catch (e) { e instanceof TypeError && entered === 1 }"
        ))
    }

    @Test
    fun `flatMap 内层 step 抛错只关闭外层迭代器`() = runTest {
        val runtime = TestRuntime()
        assertEquals("outer:1,inner:0", runtime.evalString(
            "var outerReturns = 0, innerReturns = 0;" +
                "var outer = { next: function () { return { done: false, value: 1 }; }," +
                " return: function () { outerReturns++; return {}; } };" +
                "var inner = { next: function () { throw new Error('x'); }," +
                " return: function () { innerReturns++; return {}; } };" +
                "var it = Iterator.prototype.flatMap.call(outer, function () { return inner; });" +
                "try { it.next(); } catch (e) {}" +
                "'outer:' + outerReturns + ',inner:' + innerReturns"
        ))
    }

    @Test
    fun `内建迭代器原型与 Iterator 访问器遵循规范`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "var ai = [][Symbol.iterator]();" +
                "var ap = Object.getPrototypeOf(ai);" +
                "ap[Symbol.toStringTag] === 'Array Iterator' &&" +
                "Object.getPrototypeOf(ap) === Iterator.prototype &&" +
                "Object.prototype.toString.call(ai) === '[object Array Iterator]'"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var mi = new Map([[1, 2]])[Symbol.iterator]();" +
                "var si = new Set([1])[Symbol.iterator]();" +
                "var sti = 'ab'[Symbol.iterator]();" +
                "Object.getPrototypeOf(mi)[Symbol.toStringTag] === 'Map Iterator' &&" +
                "Object.getPrototypeOf(si)[Symbol.toStringTag] === 'Set Iterator' &&" +
                "Object.getPrototypeOf(sti)[Symbol.toStringTag] === 'String Iterator'"
        ))
        // constructor 与 @@toStringTag 是访问器，setter 忽略原型属性
        assertEquals(true, runtime.evalBoolean(
            "var c = Object.getOwnPropertyDescriptor(Iterator.prototype, 'constructor');" +
                "var t = Object.getOwnPropertyDescriptor(Iterator.prototype, Symbol.toStringTag);" +
                "typeof c.get === 'function' && typeof c.set === 'function' &&" +
                " c.value === undefined &&" +
                "typeof t.get === 'function' && typeof t.set === 'function' &&" +
                " t.value === undefined &&" +
                "Iterator.prototype.constructor === Iterator &&" +
                "Iterator.prototype[Symbol.toStringTag] === 'Iterator'"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var o = Object.create(Iterator.prototype);" +
                "o[Symbol.toStringTag] = 'Custom';" +
                "o[Symbol.toStringTag] === 'Custom'"
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { Iterator.prototype[Symbol.toStringTag] = 'x'; false }" +
                " catch (e) { e instanceof TypeError }"
        ))
    }

    @Test
    fun `Iterator from 包装非迭代器且延迟校验 next`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "var w = Iterator.from({ next: function () {" +
                " return { done: true, value: undefined }; } });" +
                "var r = w.return();" +
                "r.done === true && r.value === undefined &&" +
                "Object.getPrototypeOf(w) === Object.getPrototypeOf(Iterator.from({}))"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var w = Iterator.from({ next: 0 });" +
                "try { w.next(); false } catch (e) { e instanceof TypeError }"
        ))
    }

    @Test
    fun `内建迭代器 next 位于共享原型并实时读取目标`() = runTest {
        val runtime = TestRuntime()
        // next 是原型上的共享方法；对普通对象调用因缺少内部槽而抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var proto = Object.getPrototypeOf([][Symbol.iterator]());" +
                "var ok = typeof Object.getOwnPropertyDescriptor(proto, 'next').value === 'function' &&" +
                " !Object.prototype.hasOwnProperty.call([][Symbol.iterator](), 'next');" +
                "try { proto.next.call({}); ok = false }" +
                " catch (e) { ok = e instanceof TypeError }" +
                "ok"
        ))
        // 数组迭代器实时读取 length 与元素，耗尽后保持 done
        assertEquals(true, runtime.evalBoolean(
            "var a = []; var it = a[Symbol.iterator]();" +
                "a.push('a');" +
                "var r1 = it.next(); var r2 = it.next();" +
                "a.push('b'); var r3 = it.next();" +
                "r1.value === 'a' && r1.done === false &&" +
                "r2.value === undefined && r2.done === true && r3.done === true"
        ))
        // arguments 的 length 变化在迭代期间可见
        assertEquals(true, runtime.evalBoolean(
            "(function (a, b) {" +
                " var it = arguments[Symbol.iterator](); it.next(); it.next();" +
                " arguments.length = 3; arguments[2] = 9;" +
                " var r = it.next(); var r2 = it.next();" +
                " return r.value === 9 && r.done === false && r2.done === true;" +
                "})(1, 2)"
        ))
        // Map 迭代器在耗尽前可见新增条目，耗尽后固定 done
        assertEquals(true, runtime.evalBoolean(
            "var m = new Map([[1, 1]]); var it = m[Symbol.iterator]();" +
                "it.next(); m.set(2, 2);" +
                "var r = it.next(); var r2 = it.next();" +
                "m.set(3, 3); var r3 = it.next();" +
                "r.value[0] === 2 && r.done === false &&" +
                "r2.done === true && r3.done === true"
        ))
    }

    @Test
    fun `Iterator concat 惰性打开并在 return 时关闭当前内层`() = runTest {
        val runtime = TestRuntime()
        // 各 iterable 仅在需要时按顺序打开
        assertEquals(true, runtime.evalBoolean(
            "var opened = [];" +
                "var a = { [Symbol.iterator]: function () {" +
                " opened.push('a'); return [1][Symbol.iterator](); } };" +
                "var b = { [Symbol.iterator]: function () {" +
                " opened.push('b'); return [2][Symbol.iterator](); } };" +
                "var it = Iterator.concat(a, b);" +
                "var before = opened.length === 0;" +
                "var r1 = it.next(); var afterA = opened.join(',');" +
                "var r2 = it.next(); var afterB = opened.join(',');" +
                "before && r1.value === 1 && afterA === 'a' &&" +
                " r2.value === 2 && afterB === 'a,b'"
        ))
        // return 关闭当前内层；helper 完成后不再转发
        assertEquals("1", runtime.evalString(
            "var closes = 0;" +
                "var inner = { next: function () { return { done: false, value: 1 }; }," +
                " return: function () { closes++; return {}; } };" +
                "var it = Iterator.concat({ [Symbol.iterator]: function () { return inner; } });" +
                "it.next(); it.return(); it.return();" +
                "String(closes)"
        ))
        // 返回值为共享 %IteratorHelperPrototype% 的 helper
        assertEquals(true, runtime.evalBoolean(
            "var proto = Object.getPrototypeOf([].values().map(function (v) { return v; }));" +
                "var it = Iterator.concat();" +
                "Object.getPrototypeOf(it) === proto &&" +
                " it.next === proto.next && it.return === proto.return &&" +
                " Object.prototype.toString.call(it) === '[object Iterator Helper]'"
        ))
        // 参数立即校验：非对象或无 @@iterator 抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var bad = 0;" +
                "try { Iterator.concat(null); } catch (e) { if (e instanceof TypeError) bad++; }" +
                "try { Iterator.concat({}); } catch (e) { if (e instanceof TypeError) bad++; }" +
                "bad === 2"
        ))
    }

    @Test
    fun `Array fromAsync 支持同步、异步与 array-like 输入`() = runTest {
        val runtime = TestRuntime()
        // 同步迭代器的值会逐个 await
        runtime.eval(
            "var out = '';" +
                "Array.fromAsync([Promise.resolve(1), 2]).then(function (a) {" +
                " out = a.join(','); });"
        )
        assertEquals("1,2", runtime.evalString("out"))
        // 异步迭代器的值不再二次 await，保留原 Promise
        runtime.eval(
            "var p = Promise.resolve(9);" +
                "var asyncItems = { [Symbol.asyncIterator]: function () {" +
                " var i = 0;" +
                " return { next: function () {" +
                "  if (i++ === 0) return Promise.resolve({ done: false, value: p });" +
                "  return Promise.resolve({ done: true });" +
                " } };" +
                "} };" +
                "Array.fromAsync(asyncItems).then(function (a) {" +
                " out = (a[0] === p) + ':' + a.length; });"
        )
        assertEquals("true:1", runtime.evalString("out"))
        // array-like：空洞按 undefined，值与映射结果都会被 await
        runtime.eval(
            "Array.fromAsync({ length: 2, 1: Promise.resolve(3) }," +
                " function (v) { return v * 2; }).then(function (a) {" +
                " out = String(a[0]) + ',' + a[1]; });"
        )
        assertEquals("NaN,6", runtime.evalString("out"))
        // 自定义构造器：一次构造并设置 length 与元素
        runtime.eval(
            "function MyArray(n) { this.created = n; }" +
                "Array.fromAsync.call(MyArray, [1, 2]).then(function (a) {" +
                " out = (a instanceof MyArray) + ':' + a.length + ':' + a[1]; });"
        )
        assertEquals("true:2:2", runtime.evalString("out"))
        // 错误以 Promise 拒绝的形式返回
        runtime.eval(
            "Array.fromAsync(null).then(function () { out = 'fulfilled'; }," +
                " function (e) { out = String(e instanceof TypeError); });"
        )
        assertEquals("true", runtime.evalString("out"))
        assertEquals(true, runtime.evalBoolean("typeof Symbol.asyncIterator === 'symbol'"))
    }

    @Test
    fun `数组泛型方法支持 2 的 53 次方级 array-like 长度`() = runTest {
        val runtime = TestRuntime()
        // pop：ToLength 裁剪到 2^53-1，并按 Long 索引读取/删除
        assertEquals(true, runtime.evalBoolean(
            "var o = { length: 2 ** 53 + 2, '9007199254740990': 'x' };" +
                "var v = Array.prototype.pop.call(o);" +
                "v === 'x' && o.length === 2 ** 53 - 2 &&" +
                " !Object.prototype.hasOwnProperty.call(o, '9007199254740990')"
        ))
        // push：无新元素时把 length 收敛到 2^53-1，追加时抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var o = { length: 2 ** 53 };" +
                "Array.prototype.push.call(o);" +
                "var clamped = o.length === 2 ** 53 - 1;" +
                "var threw = false;" +
                "try { Array.prototype.push.call(o, 1); }" +
                " catch (e) { threw = e instanceof TypeError; }" +
                "clamped && threw"
        ))
        // unshift：argCount 为 0 时仅写回裁剪后的 length
        assertEquals(true, runtime.evalBoolean(
            "var o = { length: 2 ** 53 + 2 };" +
                "Array.prototype.unshift.call(o);" +
                "o.length === 2 ** 53 - 1"
        ))
        // reverse/slice：只访问靠近 len-1 的索引，不对全长度循环
        assertEquals(true, runtime.evalBoolean(
            "var stop = {};" +
                "var o = { get '9007199254740990'() { throw stop; }, length: 2 ** 53 + 2 };" +
                "var threw = false;" +
                "try { Array.prototype.reverse.call(o); } catch (e) { threw = e === stop; }" +
                "var s = { length: 2 ** 53 + 2," +
                " '9007199254740989': 'a', '9007199254740990': 'b' };" +
                "var sliced = Array.prototype.slice.call(s, 9007199254740989);" +
                "threw && sliced.join(',') === 'a,b'"
        ))
        // splice：收缩时搬移近端元素并删除尾部
        assertEquals(true, runtime.evalBoolean(
            "var o = { length: 2 ** 53 - 1, '9007199254740990': 'a' };" +
                "var removed = Array.prototype.splice.call(o, 9007199254740990, 1);" +
                "removed[0] === 'a' && o.length === 2 ** 53 - 2 &&" +
                " !Object.prototype.hasOwnProperty.call(o, '9007199254740990')"
        ))
        // includes：len 为 0 时不读取 fromIndex
        assertEquals(true, runtime.evalBoolean(
            "var calls = 0;" +
                "[].includes(0, { valueOf: function () { calls++; } });" +
                "calls === 0"
        ))
    }

    @Test
    fun `AsyncDisposableStack 异步释放与错误聚合`() = runTest {
        val runtime = TestRuntime()
        // defer 结果会被 Await：先完成 Promise 再释放下一个资源
        runtime.eval(
            "var out = '';" +
                "var stack = new AsyncDisposableStack();" +
                "stack.defer(function () { out += '1'; });" +
                "stack.defer(function () {" +
                " return Promise.resolve().then(function () { out += '2'; }); });" +
                "stack.disposeAsync().then(function () { out += '3'; });"
        )
        assertEquals("213", runtime.evalString("out"))
        // use 优先 @@asyncDispose，缺失时回退 @@dispose
        runtime.eval(
            "out = '';" +
                "var both = { [Symbol.asyncDispose]: function () { out += 'a'; }," +
                " [Symbol.dispose]: function () { out += 's'; } };" +
                "var syncOnly = { [Symbol.dispose]: function () { out += 'd'; } };" +
                "var stack = new AsyncDisposableStack();" +
                "stack.use(both); stack.use(syncOnly);" +
                "stack.disposeAsync();"
        )
        assertEquals("da", runtime.evalString("out"))
        // 多个异步释放错误嵌套为 SuppressedError
        runtime.eval(
            "var e1 = new Error('1'), e2 = new Error('2');" +
                "var stack = new AsyncDisposableStack();" +
                "stack.defer(function () { return Promise.reject(e1); });" +
                "stack.defer(function () { throw e2; });" +
                "stack.disposeAsync().then(function () { out = 'fulfilled'; }," +
                " function (e) { out = String(e instanceof SuppressedError &&" +
                " e.error === e1 && e.suppressed === e2); });"
        )
        assertEquals("true", runtime.evalString("out"))
        // move 转移资源并标记旧栈 disposed
        assertEquals(true, runtime.evalBoolean(
            "var stack = new AsyncDisposableStack();" +
                "stack.defer(function () { return undefined; });" +
                "var moved = stack.move();" +
                "stack.disposed === true && moved.disposed === false &&" +
                " moved instanceof AsyncDisposableStack"
        ))
    }

    @Test
    fun `DisposableStack 逆序释放并聚合 SuppressedError`() = runTest {
        val runtime = TestRuntime()
        // use + defer 按加入顺序逆序释放
        assertEquals("2,1", runtime.evalString(
            "var out = [];" +
                "var stack = new DisposableStack();" +
                "stack.defer(function () { out.push(1); });" +
                "stack.use({ [Symbol.dispose]: function () { out.push(2); } });" +
                "stack.dispose();" +
                "out.join(',')"
        ))
        // adopt 回调接收原值；move 转移资源并把旧栈标记为 disposed
        assertEquals(true, runtime.evalBoolean(
            "var seen; var stack = new DisposableStack();" +
                "stack.adopt(5, function (v) { seen = v; });" +
                "var before = stack.disposed;" +
                "var moved = stack.move();" +
                "var after = stack.disposed;" +
                "moved.dispose();" +
                "before === false && after === true && seen === 5 && moved.disposed === true"
        ))
        // 多个释放错误嵌套为 SuppressedError
        assertEquals(true, runtime.evalBoolean(
            "var e1 = new Error('1'), e2 = new Error('2'), e3 = new Error('3');" +
                "var stack = new DisposableStack();" +
                "stack.defer(function () { throw e1; });" +
                "stack.defer(function () { throw e2; });" +
                "stack.defer(function () { throw e3; });" +
                "try { stack.dispose(); false } catch (e) {" +
                " e instanceof SuppressedError && e.error === e1 &&" +
                " e.suppressed instanceof SuppressedError &&" +
                " e.suppressed.error === e2 && e.suppressed.suppressed === e3 }"
        ))
        // 已释放的栈再 use 抛 ReferenceError
        assertEquals(true, runtime.evalBoolean(
            "var stack = new DisposableStack(); stack.dispose();" +
                "try { stack.use({}); false } catch (e) { e instanceof ReferenceError }"
        ))
        // @@dispose 与 dispose 同一函数；Iterator.prototype[@@dispose] 调用 return
        assertEquals("true,1", runtime.evalString(
            "var calls = 0;" +
                "var it = { return: function () { calls++; return {}; } };" +
                "Iterator.prototype[Symbol.dispose].call(it);" +
                "String(DisposableStack.prototype[Symbol.dispose] ===" +
                " DisposableStack.prototype.dispose) + ',' + calls"
        ))
    }

    @Test
    fun `WeakMap 与 WeakSet 支持非注册 Symbol 键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "var s = Symbol('k');" +
                "var map = new WeakMap([[s, 1]]);" +
                "map.get(s) === 1 && map.has(s) &&" +
                " map.getOrInsert(s, 2) === 1 && map.delete(s) && !map.has(s)"
        ))
        assertEquals(true, runtime.evalBoolean(
            "var s = Symbol('k');" +
                "var set = new WeakSet([s]);" +
                "set.has(s) && set.delete(s) && !set.has(s)"
        ))
        // 注册符号不能作为弱引用键
        assertEquals(true, runtime.evalBoolean(
            "var threw = 0;" +
                "try { new WeakMap().set(Symbol.for('reg'), 1); }" +
                " catch (e) { if (e instanceof TypeError) threw++; }" +
                "try { new WeakSet().add(Symbol.for('reg')); }" +
                " catch (e) { if (e instanceof TypeError) threw++; }" +
                "threw === 2"
        ))
    }

    @Test
    fun `Iterator zip 与 zipKeyed 的模式和结果对象`() = runTest {
        val runtime = TestRuntime()
        // shortest：按最短输入结束
        assertEquals("1,4;2,5;3,6", runtime.evalString(
            "var out = [];" +
                "for (var pair of Iterator.zip([[1, 2, 3], [4, 5, 6]]))" +
                " out.push(pair.join(','));" +
                "out.join(';')"
        ))
        // longest + padding：较短输入缺失位置使用 padding
        assertEquals("1,2;9,3", runtime.evalString(
            "var out = [];" +
                "for (var pair of Iterator.zip([[1], [2, 3]]," +
                " { mode: 'longest', padding: [9] })) out.push(pair.join(','));" +
                "out.join(';')"
        ))
        // strict：长度不一致抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "try { Array.from(Iterator.zip([[1], [2, 3]], { mode: 'strict' })); false }" +
                " catch (e) { e instanceof TypeError }"
        ))
        // zipKeyed：null 原型结果对象与键顺序
        assertEquals(true, runtime.evalBoolean(
            "var results = Array.from(Iterator.zipKeyed({ a: [1, 2], b: [3, 4] }));" +
                "Object.getPrototypeOf(results[0]) === null &&" +
                " results[1].a === 2 && results[1].b === 4 &&" +
                " Array.prototype.join.call(Object.keys(results[0]), ',') === 'a,b'"
        ))
        // zipKeyed + longest：padding 按属性包读取
        assertEquals(true, runtime.evalBoolean(
            "var results = Array.from(Iterator.zipKeyed({ a: [1], b: [2, 3] }," +
                " { mode: 'longest', padding: { a: 9 } }));" +
                "results[1].a === 9 && results[1].b === 3"
        ))
        // return() 逆序关闭开放迭代器
        assertEquals("2,1", runtime.evalString(
            "var closed = [];" +
                "function make(n) { return {" +
                " next: function () { return { done: false, value: n }; }," +
                " return: function () { closed.push(n); return {}; } }; }" +
                "var it = Iterator.zip([make(1), make(2)]);" +
                "it.next(); it.return();" +
                "closed.join(',')"
        ))
        // 非法 mode / options 立即抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "var bad = 0;" +
                "try { Iterator.zip([], { mode: 'loose' }); }" +
                " catch (e) { if (e instanceof TypeError) bad++; }" +
                "try { Iterator.zip([], null); }" +
                " catch (e) { if (e instanceof TypeError) bad++; }" +
                "bad === 2"
        ))
    }

    @Test
    fun `Math sumPrecise 精确求和与特殊值`() = runTest {
        val runtime = TestRuntime()
        // 中间溢出后相互抵消仍返回精确舍入结果
        assertEquals("0.30000000000000004", runtime.evalString(
            "String(Math.sumPrecise([1e308, 1e308, 0.1, 0.1, 1e30, 0.1," +
                " -1e30, -1e308, -1e308]))"
        ))
        // 两条超大半和相减的临界舍入
        assertEquals(true, runtime.evalBoolean(
            "var r = Math.sumPrecise([8.98846567431158e+307, 8.988465674311579e+307," +
                " -1.7976931348623157e+308]);" +
                "r === 9.9792015476736e+291"
        ))
        // 空输入与全 -0 返回 -0；-0 与 +0 混合返回 +0
        assertEquals(true, runtime.evalBoolean(
            "Object.is(Math.sumPrecise([]), -0) &&" +
                " Object.is(Math.sumPrecise([-0, -0]), -0) &&" +
                " Object.is(Math.sumPrecise([-0, 0]), 0)"
        ))
        // NaN / 无穷
        assertEquals(true, runtime.evalBoolean(
            "Number.isNaN(Math.sumPrecise([Infinity, -Infinity])) &&" +
                " Math.sumPrecise([1e308, 1e308]) === Infinity &&" +
                " Number.isNaN(Math.sumPrecise([NaN]))"
        ))
        // 非 Number 元素抛 TypeError 并关闭迭代器（不做隐式转换）
        assertEquals(true, runtime.evalBoolean(
            "var closed = false;" +
                "var iter = { [Symbol.iterator]: function () { return this; }," +
                " next: function () { return { done: false, value: {} }; }," +
                " return: function () { closed = true; return {}; } };" +
                "var threw = false;" +
                "try { Math.sumPrecise(iter); } catch (e) { threw = e instanceof TypeError; }" +
                "threw && closed"
        ))
    }

    @Test
    fun `BigInt 字面量可被解析但求值抛出 TypeError`() = runTest {
        val runtime = TestRuntime()
        // 保留语法识别以便解析包含 BigInt 字面量的宿主脚本
        assertEquals("function", runtime.evalString(
            "var f = function () { return 2n; }; typeof f"
        ))
        assertFailsWith<JSException> { runtime.eval("1n;") }
    }
}
