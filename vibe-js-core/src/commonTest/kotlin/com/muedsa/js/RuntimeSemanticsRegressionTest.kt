package com.muedsa.js

import com.muedsa.js.lexer.LexerException
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
}
