package com.muedsa.js

import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
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
            """
                var a = [1, 2]; Object.defineProperty(a, 'length', { writable: false });
                try { a.push(3); false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
        assertEquals(0.0, runtime.evalNumber("var a = [1, 2]; a.length = false; a.length"))
        assertEquals(false, runtime.evalBoolean("var a = [1]; Object.freeze(a); delete a[0]"))
    }

    @Test
    fun `数组方法按 HasProperty 与原型链读取元素`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                function cb(v) { return v > 10; }
                var seen = false; var obj = { 1: 11, length: '2' };
                Array.prototype.forEach.call(obj, function (v) { if (v === 11) seen = true; }); seen
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var accessed = false;
                var obj = {}; Object.defineProperty(obj, 'length', {
                    get: function () { accessed = true; return 2; }, configurable: true });
                try { Array.prototype.forEach.call(obj, null); } catch (e) { } accessed
            """.trimIndent()
        ))
        assertEquals(2.0, runtime.evalNumber("Array.prototype.concat.call({ 0: 'a', length: 1 }, ['b']).length"))
        assertEquals(true, runtime.evalBoolean(
            """
                Array.prototype[1] = 1; var x = [0]; x.length = 2;
                var out = x.concat(); delete Array.prototype[1];
                out[1] === 1 && out.hasOwnProperty('1')
            """.trimIndent()
        ))
    }

    @Test
    fun `sloppy arguments 的参数映射与 defineProperty 联动`() = runTest {
        val runtime = TestRuntime()
        assertEquals(20.0, runtime.evalNumber(
            """
                (function (a) { Object.defineProperty(arguments, '0', {
                        value: 20, writable: false, enumerable: false, configurable: false }); return a; })(0)
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                (function (a) { var ok = false;
                    Object.defineProperty(arguments, '0', { value: 10, writable: false, configurable: false });
                    try { Object.defineProperty(arguments, '0', { value: 20 }); }
                    catch (e) { ok = e instanceof TypeError && a === 10; } return ok; })(0)
            """.trimIndent()
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
            """
                var o = JSON.parse('{"__proto__": []}');
                Object.getPrototypeOf(o) === Object.prototype && o.hasOwnProperty('__proto__')
            """.trimIndent()
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
    fun `正则非 m 模式美元锚点只在输入末尾匹配`() = runTest {
        val runtime = TestRuntime()
        // Java 的 $ 会匹配末尾换行之前；JS 非 m 模式只匹配输入末尾
        assertEquals("1:1", runtime.evalString(
            "var r = /$/g; var m = r.exec('\\n'); m.index + ':' + r.lastIndex"
        ))
        assertEquals(true, runtime.evalBoolean(
            "/$/.test('a\\n') && !/a$/.test('a\\n') && /a$/m.test('a\\n')"
        ))
        // 字符类与转义中的 $ 是字面量，不是锚点
        assertEquals(true, runtime.evalBoolean(
            $$"/[$]/.test('$') && /\\$/.test('$') && !/\\$/.test('a')"
        ))
    }

    @Test
    fun `正则多行锚点按 JS 行终止符集合匹配`() = runTest {
        val runtime = TestRuntime()
        // NEL(U+0085) 不是 JS 行终止符，Java MULTILINE 却会在此断行
        assertEquals("0", runtime.evalString(
            """
            var r = /^/gm;
            var a = [];
            var m;
            while ((m = r.exec('a\u0085b')) !== null) {
              a.push(m.index);
              r.lastIndex = m.index + 1;
            }
            a.join(',')
            """.trimIndent()
        ))
        // CRLF 在 JS 中是两个独立行终止符，^ 可在 \\r 与 \\n 之间匹配
        assertEquals("0,2,3", runtime.evalString(
            """
            var r = /^/gm;
            var a = [];
            var m;
            while ((m = r.exec('a\r\nb')) !== null) {
              a.push(m.index);
              r.lastIndex = m.index + 1;
            }
            a.join(',')
            """.trimIndent()
        ))
        assertEquals("1,2,4", runtime.evalString(
            """
            var r = /$/gm;
            var a = [];
            var m;
            while ((m = r.exec('a\r\nb')) !== null) {
              a.push(m.index);
              r.lastIndex = m.index + 1;
            }
            a.join(',')
            """.trimIndent()
        ))
    }

    @Test
    fun `正则 Annex B 转义保持 JS 字面量语义`() = runTest {
        val runtime = TestRuntime()
        // \v 是垂直制表符，不是 Java 的垂直空白类
        assertEquals(true, runtime.evalBoolean(
            "/\\v/.test(String.fromCharCode(11)) && !/\\v/.test('\\t')"
        ))
        // 非字母控制转义匹配字面反斜杠 + c
        assertEquals(true, runtime.evalBoolean(
            "/\\c/.test('\\\\c') && !/\\c/.test('c') && /\\c1/.test('\\\\c1')"
        ))
        // 非法 \x 是 IdentityEscape 'x'，后续 {2} 作为量词
        assertEquals(true, runtime.evalBoolean(
            "/\\x{2}/.test('xx') && /\\x/.test('x') && !/\\x{2}/.test('A')"
        ))
        // 非法 \u 是 IdentityEscape 'u'
        assertEquals(true, runtime.evalBoolean("/\\u/.test('u') && !/\\u/.test('A')"))
        // Annex B ClassControlLetter：字符类内数字/_ 构成控制转义；其他非法 \c 为字面 c
        assertEquals(true, runtime.evalBoolean(
            """
                /[\c0]/.test('\x10') && /[\c_]/.test('\x1f') && /[\c!]/.test('c') &&
                /[\c!]/.test('!') && /[\c]/.test('c') && !/[\c0]/.test('0')
            """.trimIndent()
        ))
        // 非 v 模式字符类中的 && 是字面 &，不是 Java 的集合交集
        assertEquals(true, runtime.evalBoolean(
            """
                /[a&&b]/.test('&') && /[a&&b]/.test('a') && !/[a&&b]/.test('c') &&
                /[\w&&\d]/.test('&') && /[\w&&\d]/.test('a')
            """.trimIndent()
        ))
    }

    @Test
    fun `正则词边界只把 ASCII 单词字符视为单词`() = runTest {
        val runtime = TestRuntime()
        // Java 的 \b 把非 ASCII 字母当单词字符；JS 只认 ASCII
        assertEquals(true, runtime.evalBoolean(
            """
                !/\bé/.test('é') && /\Bé/.test('é') && /\bé/.test('aé') &&
                /\b\w/.test('a') && !/\b\w/u.test('é') && !/\B\w/u.test('é')
            """.trimIndent()
        ))
    }

    @Test
    fun `正则组名使用 Unicode 17 标识符规则`() = runTest {
        val runtime = TestRuntime()
        assertEquals("x", runtime.evalString("new RegExp('(?<π>x)').exec('x').groups.π"))
        assertEquals("x", runtime.evalString("new RegExp('(?<𐐀>x)').exec('x').groups['𐐀']"))
        assertEquals("a", runtime.evalString("new RegExp('(?<_$1x>a)').exec('a').groups['_$1x']"))
        assertEquals("SyntaxError", runtime.evalString(
            "try { new RegExp('(?<1a>x)'); 'none' } catch (e) { e.name }"
        ))
    }

    @Test
    fun `正则属性转义在 i+u 下折叠大小写与 astral`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                /\p{Lu}/iu.test('ß') && /\p{Ll}/iu.test('ẞ') &&
                /\p{Lu}/iu.test('𐐨') && /\p{Ll}/iu.test('𐐀') &&
                /\P{Lu}/iu.test('𐐀') && !/\p{Lu}/iu.test('😀')
            """.trimIndent()
        ))
    }

    @Test
    fun `正则字符类范围在 i+u 下折叠 astral 与 Kelvin`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                /[\u{10400}-\u{10427}]/iu.test('𐐨') &&
                /[\u{10428}]/iu.test('𐐀') && /[A-Z]/iu.test('\u212A') &&
                /[K-K]/iu.test('K') && new RegExp('[\\u{10428}]', 'iv').test('𐐀')
            """.trimIndent()
        ))
    }

    @Test
    fun `正则 astral 字面量与反向引用在 i+u 下折叠`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                /𐐀/iu.test('𐐨') && /(?<=𐐀)b/iu.test('𐐨b') &&
                /(𐐀)\1/iu.test('𐐀𐐨') && /(?<=(𐐀)\1)b/iu.test('𐐀𐐨b') &&
                /(K)\1/iu.test('KK') && /(ß)\1/iu.test('ßẞ')
            """.trimIndent()
        ))
    }


    @Test
    fun `v 模式 q 字符串在 i 下按码点折叠`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                /[\q{ab}]/iv.test('AB') && !/[\q{ab}]/v.test('AB') &&
                /[\q{𐐨}]/iv.test('𐐀') && /[\q{𐐨𐐀}]/iv.test('𐐀𐐨')
            """.trimIndent()
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
            """
                var s = new String('ABC'); s.valueOf = function () { return 'ed'; };
                s.toString = function () { return 'ed'; }; s == 'ed'
            """.trimIndent()
        ))
        assertEquals("a.\u03C2", runtime.evalString("'A.\\u03A3'.toLowerCase()"))
    }

    @Test
    fun `JSON reviver 删除属性时保留其余属性的可枚举性`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "{\"b\":2}",
            runtime.evalString(
                """
                    JSON.stringify(JSON.parse('{"a":1,"b":2}',
                    function (k, v) { return k === 'a' ? undefined : v; }))
                """.trimIndent()
            )
        )
    }

    @Test
    fun `arguments 的映射索引在描述符层面表现为数据属性`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "7,true,true,true",
            runtime.evalString(
                """
                    (function (a) { var d = Object.getOwnPropertyDescriptor(arguments, '0');
                        return [d.value, d.writable, d.enumerable, d.configurable].join(','); })(7)
                """.trimIndent()
            )
        )
    }

    @Test
    fun `不可扩展对象的 __proto__ 赋值抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "TypeError",
            runtime.evalString(
                """
                    try { var x = Object.preventExtensions({}); x.__proto__ = { a: 1 }; 'none' }
                    catch (e) { e.name }
                """.trimIndent()
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
            """
                var seen = false; var d = Object.getOwnPropertyDescriptor({ x: 1 }, 'x');
                for (var k in d) { if (k === 'value') seen = true; } seen
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                function F() {} F.prototype = undefined;
                try { ({} instanceof F); false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            "function F(x) { this.x = x; } var B = F.bind(null, 7); new B().x === 7"
        ))
        assertEquals(
            "function () { [native code] }",
            runtime.evalString("(function () {}).bind(null).toString()"),
        )
        assertEquals(true, runtime.evalBoolean(
            """
                (function () { 'use strict'; function f() {}
                    try { f.caller; return false; } catch (e) { return e instanceof TypeError; } })()
            """.trimIndent()
        ))
        assertEquals("undefined", runtime.evalString("typeof (function f() {}).caller"))
        assertEquals(false, runtime.evalBoolean(
            """
                var proto = { p: 1 }; var child = Object.create(proto);
                Object.defineProperty(child, 'p', { value: 2, enumerable: false });
                var seen = false; for (var k in child) { if (k === 'p') seen = true; } seen
            """.trimIndent()
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
            """
                try { Object.getOwnPropertyDescriptor(RegExp.prototype, 'global').get.call(1);
                    false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean("+new Date(0) === 0"))
        assertEquals(true, runtime.evalBoolean(
            """
                try { new Date(0)[Symbol.toPrimitive]('bogus'); false }
                catch (e) { e instanceof TypeError }
            """.trimIndent()
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
            """
                var arr = [1, 2];
                var Ctor = function (length) { this.length = length; }; Ctor.prototype = [];
                var holder = {}; holder[Symbol.species] = Ctor; arr.constructor = holder;
                var mapped = arr.map(function (x) { return x * 2; });
                mapped instanceof Ctor && mapped.length === 2 && mapped[0] === 2 && mapped[1] === 4
            """.trimIndent()
        ))
        // species 为 null 时回退内建数组
        assertEquals(true, runtime.evalBoolean(
            """
                var a = [1]; a.constructor = { [Symbol.species]: null };
                var r = a.map(function (x) { return x; });
                Array.isArray(r) && Object.getPrototypeOf(r) === Array.prototype
            """.trimIndent()
        ))
        // species 非构造器抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var b = [1]; b.constructor = { [Symbol.species]: 1 };
                try { b.map(function (x) { return x; }); false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
        // @@isConcatSpreadable 控制展开，并按 n 设置结果长度（空洞计入）
        assertEquals("1,2,3,4", runtime.evalString(
            """
                var o = { 0: 3, 1: 4, length: 2 }; o[Symbol.isConcatSpreadable] = true;
                [].concat([1, 2], o).join(',')
            """.trimIndent()
        ))
        assertEquals(6.0, runtime.evalNumber(
            """
                var args = (function (a, b, c) { return arguments; })(1, 2, 3);
                args[Symbol.isConcatSpreadable] = true;
                Object.defineProperty(args, 'length', { value: 6 });
                [].concat(args).length
            """.trimIndent()
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
            """
                var upsertMap = new Map(); upsertMap.set('a', 1);
                [upsertMap.getOrInsert('a', 2), upsertMap.getOrInsert('b', 3),
                    upsertMap.size].join(':')
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var computedMap = new Map(); var calls = 0;
                var got = computedMap.getOrInsertComputed(-0, function (k) { calls++; return Object.is(k, 0); });
                got === true && calls === 1 && computedMap.has(0)
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var upsertWeak = new WeakMap(); var weakKey = {}; upsertWeak.set(weakKey, 1);
                upsertWeak.getOrInsert(weakKey, 2) === 1 && upsertWeak.getOrInsert({}, 3) === 3
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var causeValue = { c: 1 }; var causedError = new Error('m', { cause: causeValue });
                causedError.cause === causeValue &&
                Object.getOwnPropertyDescriptor(causedError, 'cause').enumerable === false &&
                !('cause' in new Error('m'))
            """.trimIndent()
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
            """
                var resolvedWith = Promise.withResolvers(); var wrValue = 0;
                resolvedWith.promise.then(function (v) { wrValue = v; });
                resolvedWith.resolve(42);
            """.trimIndent()
        )
        assertEquals(42.0, runtime.evalNumber("wrValue"))
        assertEquals(true, runtime.evalBoolean(
            """
                Promise.withResolvers().resolve.name === '' &&
                Promise.withResolvers().reject.length === 1
            """.trimIndent()
        ))
        runtime.eval(
            """
                var tryValue = 0; Promise.try(function (a, b) { return a + b; }, 1, 2)
                .then(function (v) { tryValue = v; });
            """.trimIndent()
        )
        assertEquals(3.0, runtime.evalNumber("tryValue"))
        runtime.eval(
            """
                var sentinelPromise = Promise.resolve(1);
                var trySame = Promise.try(function () { return sentinelPromise; });
            """.trimIndent()
        )
        assertEquals(true, runtime.evalBoolean("trySame === sentinelPromise"))
        // finally 透传值 / 回调抛错 / 回调返回拒绝
        runtime.eval(
            """
                var passValue = 0; Promise.resolve(7).finally(function () {})
                .then(function (v) { passValue = v; });
            """.trimIndent()
        )
        assertEquals(7.0, runtime.evalNumber("passValue"))
        runtime.eval(
            """
                var thrownReason = ''; Promise.resolve(1).finally(function () { throw 'x'; })
                .catch(function (e) { thrownReason = e; });
            """.trimIndent()
        )
        assertEquals("x", runtime.evalString("thrownReason"))
        runtime.eval(
            """
                var finLog = ''; Promise.resolve('v').finally(function () {
                    finLog += 'f'; return Promise.reject('bad'); })
                .catch(function (e) { finLog += ':' + e; });
            """.trimIndent()
        )
        assertEquals("f:bad", runtime.evalString("finLog"))
    }

    @Test
    fun `Object groupBy 与 Map groupBy`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var grouped = Object.groupBy([1, 2, 3, 4], function (v) { return v % 2 ? 'odd' : 'even'; });
                Object.getPrototypeOf(grouped) === null &&
                grouped.odd.join(',') === '1,3' && grouped.even.join(',') === '2,4'
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var mapGrouped = Map.groupBy(['a', 'bb', 'c'], function (v) { return v.length; });
                mapGrouped instanceof Map && mapGrouped.get(1).join(',') === 'a,c' &&
                mapGrouped.get(2)[0] === 'bb'
            """.trimIndent()
        ))
        // 字符串按码点迭代，代理对作为一个元素
        assertEquals(true, runtime.evalBoolean(
            """
                var codePoints = Object.groupBy('\uD83D\uDCA9\uD83D\uDE08', function (v) { return v.length; });
                codePoints[2].length === 2
            """.trimIndent()
        ))
        // 回调抛错时关闭迭代器
        assertEquals(true, runtime.evalBoolean(
            """
                var closed = 0; var iterable = {};
                iterable[Symbol.iterator] = function () { var i = 0;
                    return { next: function () { return { value: i++, done: false }; },
                        return: function () { closed++; return {}; } }; };
                try { Object.groupBy(iterable, function () { throw new RangeError('x'); }); } catch (e) {}
                closed === 1
            """.trimIndent()
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
            """
                new Set([1, 2]).isSubsetOf(new Set([1, 2, 3])) &&
                new Set([1, 2, 3]).isSupersetOf(new Set([1, 2])) &&
                new Set([1, 2]).isDisjointFrom(new Set([3, 4]))
            """.trimIndent()
        ))
        // set-like 对象（非生成器迭代器）与 -0 归一化
        assertEquals("1,2,3", runtime.evalString(
            """
                var setLike = { size: 2, has: function (v) { return v === 2; },
                    keys: function () { return [2, 3].values(); } };
                Array.from(new Set([1, 2]).union(setLike)).join(',')
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var zero = Array.from(new Set([-0]).union(new Set([1])))[0];
                Object.is(zero, 0) && 1 / zero === Infinity
            """.trimIndent()
        ))
        // 子类接收者返回普通 Set，且不读取 Symbol.species
        assertEquals(true, runtime.evalBoolean(
            """
                var speciesCalls = 0;
                class MySet extends Set { static get [Symbol.species]() { speciesCalls++; return Set; } }
                var combined = new MySet([1]).union(new Set([2]));
                combined instanceof Set && !(combined instanceof MySet) && speciesCalls === 0
            """.trimIndent()
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
            """
                var unscopableList = Array.prototype[Symbol.unscopables];
                Object.getPrototypeOf(unscopableList) === null &&
                unscopableList.values === true && unscopableList.at === true &&
                unscopableList.with === undefined &&
                Object.getOwnPropertyDescriptor(Array.prototype, Symbol.unscopables).writable === false
            """.trimIndent()
        ))
        // with 中数组方法被屏蔽，解析到外层同名变量
        assertEquals("outer", runtime.evalString(
            """
                var values = 'outer'; var outerArray = [1, 2];
                var captured = ''; with (outerArray) { captured = values; } captured
            """.trimIndent()
        ))
        // unscopables getter 抛错向外传播
        assertEquals(true, runtime.evalBoolean(
            """
                var throwingEnv = { x: 1 };
                Object.defineProperty(throwingEnv, Symbol.unscopables,
                { get: function () { throw new RangeError('boom'); } });
                try { with (throwingEnv) { x; } false } catch (e) { e instanceof RangeError }
            """.trimIndent()
        ))
        // getter 删除绑定：sloppy 读 undefined
        assertEquals(true, runtime.evalBoolean(
            """
                var deletingEnv = { y: 1 };
                Object.defineProperty(deletingEnv, Symbol.unscopables,
                { get: function () { delete deletingEnv.y; return null; } });
                var deletedRead; with (deletingEnv) { deletedRead = y; }
                deletedRead === undefined
            """.trimIndent()
        ))
    }

    @Test
    fun `String replaceAll 分派与替换模式`() = runTest {
        val runtime = TestRuntime()
        assertEquals("X-X-X", runtime.evalString("'a-a-a'.replaceAll('a', 'X')"))
        assertEquals("-a-b-", runtime.evalString("'ab'.replaceAll('', '-')"))
        assertEquals("a[b]c[b]d", runtime.evalString("'abcbd'.replaceAll('b', '[$&]')"))
        assertEquals("0:a,1:b", runtime.evalString(
            """
                var seen = [];
                'abc'.replaceAll(/[ab]/g, function (m, i) { seen.push(i + ':' + m); return ''; });
                seen.join(',')
            """.trimIndent()
        ))
        // 非全局正则抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "try { 'a'.replaceAll(/a/, 'x'); false } catch (e) { e instanceof TypeError }"
        ))
        // 自定义 @@replace 优先，且不触发 receiver/replaceValue 的 toString
        assertEquals("custom", runtime.evalString(
            """
                var searcher = {}; searcher[Symbol.replace] = function () { return 'custom'; };
                var repl = { toString: function () { throw new Error('no'); } };
                'x'.replaceAll(searcher, repl)
            """.trimIndent()
        ))
    }

    @Test
    fun `String matchAll 与 RegExp matchAll 迭代器`() = runTest {
        val runtime = TestRuntime()
        assertEquals("a1,b2", runtime.evalString(
            """
                var collected = [];
                for (var m of 'a1b2'.matchAll(/[a-z](\d)/g)) { collected.push(m[0]); }
                collected.join(',')
            """.trimIndent()
        ))
        // lastIndex 缓存到 matcher，不影响原正则
        assertEquals(true, runtime.evalBoolean(
            """
                var re = /./g; re.lastIndex = 1; var iter = re[Symbol.matchAll]('abc');
                re.lastIndex = 0; var first = iter.next();
                first.value[0] === 'b' && first.value.index === 1 && iter.next().value[0] === 'c'
            """.trimIndent()
        ))
        // 非全局正则抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "try { 'a'.matchAll(/a/); false } catch (e) { e instanceof TypeError }"
        ))
        // 迭代器自迭代并带 toStringTag
        assertEquals(true, runtime.evalBoolean(
            """
                var iter = 'a'.matchAll(); iter[Symbol.iterator]() === iter &&
                Object.getPrototypeOf(iter)[Symbol.toStringTag] === 'RegExp String Iterator'
            """.trimIndent()
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
            """
                var side = 0; var empty = null;
                var r = empty?.[side++]?.missing(side++);
                r === undefined && side === 0
            """.trimIndent()
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
            """
                var matcher = {}; matcher[Symbol.match] = function () { return 'custom'; };
                'x'.match(matcher)
            """.trimIndent()
        ))
        // @@replace 的 flags/global 读取顺序与属性覆盖
        assertEquals("XX", runtime.evalString(
            """
                var re = /a/g; Object.defineProperty(re, 'flags', { get: function () { return 'g'; } });
                Object.defineProperty(re, 'global', { get: function () { return true; } });
                'aa'.replace(re, 'X')
            """.trimIndent()
        ))
    }

    @Test
    fun `strict 模式下访问器 setter 写入全局 var`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber(
            "\"use strict\"; var strictX = 0; var strictO = { set v(n) { strictX = n; } }; strictO.v = 1; strictX"
        ))
        assertEquals("1,", runtime.evalString(
            """
                "use strict"; var strictIndices = '';
                var strictAccessor = { set lastIndex(val) { strictIndices += val + ','; }, get lastIndex() { return 0; } };
                strictAccessor.lastIndex = 1; strictIndices
            """.trimIndent()
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
            """
                var h = [1, , 3]; var r = h.toReversed();
                r.hasOwnProperty(1) && r[1] === undefined
            """.trimIndent()
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
            """
                var holes = [1, , 3]; var seen = [];
                holes.findLast(function (v, i) { seen.push(i + ':' + v); return false; });
                seen.join(',')
            """.trimIndent()
        ))
        // well-formed：孤立代理项检测与替换
        assertEquals(false, runtime.evalBoolean("'\\uD800'.isWellFormed()"))
        assertEquals(true, runtime.evalBoolean("'\\uD83D\\uDE00'.isWellFormed()"))
        assertEquals(true, runtime.evalBoolean(
            """
                var replaced = '\uD800'.toWellFormed();
                replaced.length === 1 && replaced.charCodeAt(0) === 0xFFFD
            """.trimIndent()
        ))
    }

    @Test
    fun `Object fromEntries next 抛错不关闭迭代器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var source = {
                    [Symbol.iterator]: function () {
                        return {
                            next: function () {
                                log.push('next');
                                throw new Error('next');
                            },
                            return: function () {
                                log.push('return');
                                return { done: true };
                            }
                        };
                    }
                };
                try {
                    Object.fromEntries(source);
                } catch (error) {
                    log.push(error.message);
                }
            """.trimIndent()
        )
        assertEquals("next,next", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Object fromEntries next 结果非对象不关闭迭代器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var source = {
                    [Symbol.iterator]: function () {
                        return {
                            next: function () {
                                log.push('next');
                                return 1;
                            },
                            return: function () {
                                log.push('return');
                                return { done: true };
                            }
                        };
                    }
                };
                try {
                    Object.fromEntries(source);
                } catch (error) {
                    log.push(error.name);
                }
            """.trimIndent()
        )
        assertEquals("next,TypeError", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Object fromEntries done getter 抛错不关闭迭代器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var source = {
                    [Symbol.iterator]: function () {
                        return {
                            next: function () {
                                return {
                                    get done() {
                                        log.push('done');
                                        throw new Error('done');
                                    }
                                };
                            },
                            return: function () {
                                log.push('return');
                                return { done: true };
                            }
                        };
                    }
                };
                try {
                    Object.fromEntries(source);
                } catch (error) {
                    log.push(error.message);
                }
            """.trimIndent()
        )
        assertEquals("done,done", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Object fromEntries next getter 抛错不关闭迭代器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var source = {
                    [Symbol.iterator]: function () {
                        return {
                            get next() {
                                log.push('next');
                                throw new Error('next-getter');
                            },
                            return: function () {
                                log.push('return');
                                return { done: true };
                            }
                        };
                    }
                };
                try {
                    Object.fromEntries(source);
                } catch (error) {
                    log.push(error.message);
                }
            """.trimIndent()
        )
        assertEquals("next,next-getter", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Object fromEntries 键错误时 return 抛错保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var entry = {};
                Object.defineProperty(entry, 0, {
                    get: function () {
                        throw new Error('key');
                    }
                });
                var index = 0;
                var source = {
                    [Symbol.iterator]: function () {
                        return {
                            next: function () {
                                return index++ === 0 ? { value: entry, done: false } : { done: true };
                            },
                            return: function () {
                                log.push('return');
                                throw new Error('close');
                            }
                        };
                    }
                };
                try {
                    Object.fromEntries(source);
                } catch (error) {
                    log.push(error.message);
                }
            """.trimIndent()
        )
        assertEquals("return,key", runtime.evalString("log.join(',')"))
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
            """
                var a = [1, [2]]; var instance = []; var calledWith = -1;
                var C = function (len) { calledWith = len; return instance; };
                var holder = {}; holder[Symbol.species] = C; a.constructor = holder;
                var r = a.flat(); r === instance && calledWith === 0 && r.join(',') === '1,2'
            """.trimIndent()
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
            """
                var ref = function BindingIdentifier() { BindingIdentifier = 1; return BindingIdentifier; };
                ref() === ref
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var strictRef = function BindingIdentifier() { 'use strict'; BindingIdentifier = 1; };
                try { strictRef(); false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
    }

    @Test
    fun `Promise capability 遵循 SpeciesConstructor 与执行器约束`() = runTest {
        val runtime = TestRuntime()
        // then 通过 @@species 使用子类构造器，结果仍是子类实例
        assertEquals(true, runtime.evalBoolean(
            """
                class SubPromise extends Promise {};
                var p = SubPromise.resolve(1); p.then(function () {}) instanceof SubPromise
            """.trimIndent()
        ))
        // Promise.resolve 对同构造器的 Promise 原样返回
        assertEquals(true, runtime.evalBoolean(
            "var q = Promise.resolve(1); Promise.resolve(q) === q"
        ))
        // capability executor 第二次携带函数参数调用时抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var C = class extends Promise {
                    constructor(executor) { super(executor); executor(function () {}, function () {}); }
                }; try { new C(function () {}).then(); false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
        // catch 泛化为 Invoke(this, 'then', «undefined, onRejected»)
        assertEquals(2.0, runtime.evalNumber(
            """
                Boolean.prototype.then = function () { return arguments.length; };
                Promise.prototype.catch.call(true)
            """.trimIndent()
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
        // %AsyncFunction% 的 [[Prototype]] 是 %Function%（而非 Function.prototype）
        assertEquals(true, runtime.evalBoolean(
            "var F = async function () {}.constructor; Object.getPrototypeOf(F) === Function"
        ))
        // async 函数没有 own prototype；动态构造保留源码用于 toString
        assertEquals(true, runtime.evalBoolean(
            """
                var g = (async function () {}).constructor('a', 'return a;');
                g instanceof Function && !g.hasOwnProperty('prototype')
            """.trimIndent()
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
            $$"var s = `pre${'a'.replace(/'/g, 'b')}post`; s"
        ))
        // 除号不应被误判为正则起点
        assertEquals("3", runtime.evalString($$"var s = `${6 / 2}`; s"))
    }

    @Test
    fun `Iterator helpers 先校验参数再读取 next 并在失败时关闭`() = runTest {
        val runtime = TestRuntime()
        // 非可调用 mapper：参数校验失败，调用 return 关闭，且不读取 next
        assertEquals("return", runtime.evalString(
            """
                var effects = [];
                var o = Object.create(Iterator.prototype);
                Object.defineProperty(o, 'next', { get: function () {
                        effects.push('next'); return function () { return { done: true }; }; } });
                o.return = function () { effects.push('return'); return {}; };
                try { o.map(); } catch (e) { e instanceof TypeError; }
                effects.join(',')
            """.trimIndent()
        ))
        // take 先 ToNumber(limit)，再读取 next
        assertEquals("limit,next", runtime.evalString(
            """
                var effects = [];
                var o = { get next() { effects.push('next');
                        return function () { return { done: true }; }; } };
                Iterator.prototype.take.call(o, { valueOf: function () {
                        effects.push('limit'); return 0; } });
                effects.join(',')
            """.trimIndent()
        ))
        // next 不可调用在构造 helper 时不报错，首次 next() 才抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var it = Iterator.prototype.map.call({ next: 0 }, function (v) { return v; });
                try { it.next(); false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
        // helper 重入（生成器运行中）抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var entered = 0;
                var iter = { next: function () { entered++; it.next(); return { done: false }; } };
                var it = Iterator.prototype.take.call(iter, 10);
                try { it.next(); false } catch (e) { e instanceof TypeError && entered === 1 }
            """.trimIndent()
        ))
    }

    @Test
    fun `flatMap 内层 step 抛错只关闭外层迭代器`() = runTest {
        val runtime = TestRuntime()
        assertEquals("outer:1,inner:0", runtime.evalString(
            """
                var outerReturns = 0, innerReturns = 0;
                var outer = { next: function () { return { done: false, value: 1 }; },
                    return: function () { outerReturns++; return {}; } };
                var inner = { next: function () { throw new Error('x'); },
                    return: function () { innerReturns++; return {}; } };
                var it = Iterator.prototype.flatMap.call(outer, function () { return inner; });
                try { it.next(); } catch (e) {}
                'outer:' + outerReturns + ',inner:' + innerReturns
            """.trimIndent()
        ))
    }

    @Test
    fun `内建迭代器原型与 Iterator 访问器遵循规范`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var ai = [][Symbol.iterator]();
                var ap = Object.getPrototypeOf(ai);
                ap[Symbol.toStringTag] === 'Array Iterator' &&
                Object.getPrototypeOf(ap) === Iterator.prototype &&
                Object.prototype.toString.call(ai) === '[object Array Iterator]'
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var mi = new Map([[1, 2]])[Symbol.iterator]();
                var si = new Set([1])[Symbol.iterator]();
                var sti = 'ab'[Symbol.iterator]();
                Object.getPrototypeOf(mi)[Symbol.toStringTag] === 'Map Iterator' &&
                Object.getPrototypeOf(si)[Symbol.toStringTag] === 'Set Iterator' &&
                Object.getPrototypeOf(sti)[Symbol.toStringTag] === 'String Iterator'
            """.trimIndent()
        ))
        // constructor 与 @@toStringTag 是访问器，setter 忽略原型属性
        assertEquals(true, runtime.evalBoolean(
            """
                var c = Object.getOwnPropertyDescriptor(Iterator.prototype, 'constructor');
                var t = Object.getOwnPropertyDescriptor(Iterator.prototype, Symbol.toStringTag);
                typeof c.get === 'function' && typeof c.set === 'function' &&
                c.value === undefined &&
                typeof t.get === 'function' && typeof t.set === 'function' &&
                t.value === undefined &&
                Iterator.prototype.constructor === Iterator &&
                Iterator.prototype[Symbol.toStringTag] === 'Iterator'
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var o = Object.create(Iterator.prototype);
                o[Symbol.toStringTag] = 'Custom';
                o[Symbol.toStringTag] === 'Custom'
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                try { Iterator.prototype[Symbol.toStringTag] = 'x'; false }
                catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
    }

    @Test
    fun `Iterator from 包装非迭代器且延迟校验 next`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var w = Iterator.from({ next: function () {
                        return { done: true, value: undefined }; } });
                var r = w.return();
                r.done === true && r.value === undefined &&
                Object.getPrototypeOf(w) === Object.getPrototypeOf(Iterator.from({}))
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var w = Iterator.from({ next: 0 });
                try { w.next(); false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
    }

    @Test
    fun `内建迭代器 next 位于共享原型并实时读取目标`() = runTest {
        val runtime = TestRuntime()
        // next 是原型上的共享方法；对普通对象调用因缺少内部槽而抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var proto = Object.getPrototypeOf([][Symbol.iterator]());
                var ok = typeof Object.getOwnPropertyDescriptor(proto, 'next').value === 'function' &&
                !Object.prototype.hasOwnProperty.call([][Symbol.iterator](), 'next');
                try { proto.next.call({}); ok = false }
                catch (e) { ok = e instanceof TypeError }
                ok
            """.trimIndent()
        ))
        // 数组迭代器实时读取 length 与元素，耗尽后保持 done
        assertEquals(true, runtime.evalBoolean(
            """
                var a = []; var it = a[Symbol.iterator]();
                a.push('a');
                var r1 = it.next(); var r2 = it.next();
                a.push('b'); var r3 = it.next();
                r1.value === 'a' && r1.done === false &&
                r2.value === undefined && r2.done === true && r3.done === true
            """.trimIndent()
        ))
        // arguments 的 length 变化在迭代期间可见
        assertEquals(true, runtime.evalBoolean(
            """
                (function (a, b) {
                    var it = arguments[Symbol.iterator](); it.next(); it.next();
                    arguments.length = 3; arguments[2] = 9;
                    var r = it.next(); var r2 = it.next();
                    return r.value === 9 && r.done === false && r2.done === true;
                })(1, 2)
            """.trimIndent()
        ))
        // Map 迭代器在耗尽前可见新增条目，耗尽后固定 done
        assertEquals(true, runtime.evalBoolean(
            """
                var m = new Map([[1, 1]]); var it = m[Symbol.iterator]();
                it.next(); m.set(2, 2);
                var r = it.next(); var r2 = it.next();
                m.set(3, 3); var r3 = it.next();
                r.value[0] === 2 && r.done === false &&
                r2.done === true && r3.done === true
            """.trimIndent()
        ))
    }

    @Test
    fun `Iterator concat 惰性打开并在 return 时关闭当前内层`() = runTest {
        val runtime = TestRuntime()
        // 各 iterable 仅在需要时按顺序打开
        assertEquals(true, runtime.evalBoolean(
            """
                var opened = [];
                var a = { [Symbol.iterator]: function () {
                        opened.push('a'); return [1][Symbol.iterator](); } };
                var b = { [Symbol.iterator]: function () {
                        opened.push('b'); return [2][Symbol.iterator](); } };
                var it = Iterator.concat(a, b);
                var before = opened.length === 0;
                var r1 = it.next(); var afterA = opened.join(',');
                var r2 = it.next(); var afterB = opened.join(',');
                before && r1.value === 1 && afterA === 'a' &&
                r2.value === 2 && afterB === 'a,b'
            """.trimIndent()
        ))
        // return 关闭当前内层；helper 完成后不再转发
        assertEquals("1", runtime.evalString(
            """
                var closes = 0;
                var inner = { next: function () { return { done: false, value: 1 }; },
                    return: function () { closes++; return {}; } };
                var it = Iterator.concat({ [Symbol.iterator]: function () { return inner; } });
                it.next(); it.return(); it.return();
                String(closes)
            """.trimIndent()
        ))
        // 返回值为共享 %IteratorHelperPrototype% 的 helper
        assertEquals(true, runtime.evalBoolean(
            """
                var proto = Object.getPrototypeOf([].values().map(function (v) { return v; }));
                var it = Iterator.concat();
                Object.getPrototypeOf(it) === proto &&
                it.next === proto.next && it.return === proto.return &&
                Object.prototype.toString.call(it) === '[object Iterator Helper]'
            """.trimIndent()
        ))
        // 参数立即校验：非对象或无 @@iterator 抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var bad = 0;
                try { Iterator.concat(null); } catch (e) { if (e instanceof TypeError) bad++; }
                try { Iterator.concat({}); } catch (e) { if (e instanceof TypeError) bad++; }
                bad === 2
            """.trimIndent()
        ))
    }

    @Test
    fun `Array fromAsync 支持同步、异步与 array-like 输入`() = runTest {
        val runtime = TestRuntime()
        // 同步迭代器的值会逐个 await
        runtime.eval(
            """
                var out = '';
                Array.fromAsync([Promise.resolve(1), 2]).then(function (a) {
                    out = a.join(','); });
            """.trimIndent()
        )
        assertEquals("1,2", runtime.evalString("out"))
        // 异步迭代器的值不再二次 await，保留原 Promise
        runtime.eval(
            """
                var p = Promise.resolve(9);
                var asyncItems = { [Symbol.asyncIterator]: function () {
                        var i = 0;
                        return { next: function () {
                                if (i++ === 0) return Promise.resolve({ done: false, value: p });
                                return Promise.resolve({ done: true });
                            } };
                    } };
                Array.fromAsync(asyncItems).then(function (a) {
                    out = (a[0] === p) + ':' + a.length; });
            """.trimIndent()
        )
        assertEquals("true:1", runtime.evalString("out"))
        // array-like：空洞按 undefined，值与映射结果都会被 await
        runtime.eval(
            """
                Array.fromAsync({ length: 2, 1: Promise.resolve(3) },
                function (v) { return v * 2; }).then(function (a) {
                    out = String(a[0]) + ',' + a[1]; });
            """.trimIndent()
        )
        assertEquals("NaN,6", runtime.evalString("out"))
        // 自定义构造器：一次构造并设置 length 与元素
        runtime.eval(
            """
                function MyArray(n) { this.created = n; }
                Array.fromAsync.call(MyArray, [1, 2]).then(function (a) {
                    out = (a instanceof MyArray) + ':' + a.length + ':' + a[1]; });
            """.trimIndent()
        )
        assertEquals("true:2:2", runtime.evalString("out"))
        // 错误以 Promise 拒绝的形式返回
        runtime.eval(
            """
                Array.fromAsync(null).then(function () { out = 'fulfilled'; },
                function (e) { out = String(e instanceof TypeError); });
            """.trimIndent()
        )
        assertEquals("true", runtime.evalString("out"))
        assertEquals(true, runtime.evalBoolean("typeof Symbol.asyncIterator === 'symbol'"))
    }

    @Test
    fun `Array fromAsync 写入 String 包装对象 length 失败`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var out = '';
                Array.fromAsync.call(String, [1]).then(function () {
                    out = 'fulfilled';
                }, function (error) {
                    out = error.name;
                });
            """.trimIndent()
        )
        assertEquals("TypeError", runtime.evalString("out"))
    }

    @Test
    fun `Array fromAsync 同步迭代 Proxy 结果 done 为 true 时不读取 value`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var out = -1;
                var result = new Proxy({ done: true }, {
                    get: function (target, key) {
                        if (key === 'value') {
                            out = 1;
                        }
                        return target[key];
                    }
                });
                var iterator = {
                    next: function () {
                        return result;
                    }
                };
                Array.fromAsync({ [Symbol.iterator]: function () { return iterator; } }).then(function () {
                    if (out === -1) {
                        out = 0;
                    }
                });
            """.trimIndent()
        )
        assertEquals(0.0, runtime.evalNumber("out"))
    }

    @Test
    fun `Array fromAsync 同步迭代 Proxy 结果 done 为 true 时忽略 value getter 抛错`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var out = '';
                var result = new Proxy({ done: true }, {
                    get: function (target, key) {
                        if (key === 'value') {
                            throw new Error('unexpected-value');
                        }
                        return target[key];
                    }
                });
                var iterator = {
                    next: function () {
                        return result;
                    }
                };
                Array.fromAsync({ [Symbol.iterator]: function () { return iterator; } }).then(function (values) {
                    out = values.length;
                }, function (error) {
                    out = error.message;
                });
            """.trimIndent()
        )
        assertEquals(0.0, runtime.evalNumber("out"))
    }

    @Test
    fun `Array fromAsync 写入继承的不可写 length 失败`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var out = '';
                var proto = {};
                Object.defineProperty(proto, 'length', {
                    value: 0,
                    writable: false
                });
                var result = Object.create(proto);
                function Result() {
                    return result;
                }
                Array.fromAsync.call(Result, [1]).then(function () {
                    out = 'fulfilled';
                }, function (error) {
                    out = error.name;
                });
            """.trimIndent()
        )
        assertEquals("TypeError", runtime.evalString("out"))
    }

    @Test
    fun `Array fromAsync 写入 Uint8Array length 失败`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var out = '';
                Array.fromAsync.call(Uint8Array, [1]).then(function () {
                    out = 'fulfilled';
                }, function (error) {
                    out = error.name;
                });
            """.trimIndent()
        )
        assertEquals("TypeError", runtime.evalString("out"))
    }

    @Test
    fun `Array fromAsync 同步值拒绝只关闭一次迭代器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return index++ === 0
                            ? { value: Promise.reject(new Error('value')), done: false }
                            : { done: true };
                    },
                    return: function () {
                        log.push('return');
                        return { done: true };
                    }
                };
                Array.fromAsync({ [Symbol.iterator]: function () { return iterator; } }).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("return,reject:value", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 同步值 thenable 拒绝只关闭一次迭代器`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        if (index++ === 0) {
                            return {
                                done: false,
                                value: {
                                    then: function (resolve, reject) {
                                        reject(new Error('thenable'));
                                    }
                                }
                            };
                        }
                        return { done: true };
                    },
                    return: function () {
                        log.push('return');
                        return { done: true };
                    }
                };
                Array.fromAsync({ [Symbol.iterator]: function () { return iterator; } }).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("return,reject:thenable", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 同步 return 非函数时保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return index++ === 0 ? { value: 1, done: false } : { done: true };
                    },
                    return: 1
                };
                Array.fromAsync(
                    { [Symbol.iterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 同步 return getter 抛错保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return index++ === 0 ? { value: 1, done: false } : { done: true };
                    },
                    get return() {
                        throw new Error('return-getter');
                    }
                };
                Array.fromAsync(
                    { [Symbol.iterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 异步 return 非函数时保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return Promise.resolve(
                            index++ === 0 ? { value: 1, done: false } : { done: true }
                        );
                    },
                    return: 1
                };
                Array.fromAsync(
                    { [Symbol.asyncIterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 异步 return getter 抛错保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return Promise.resolve(
                            index++ === 0 ? { value: 1, done: false } : { done: true }
                        );
                    },
                    get return() {
                        throw new Error('return-getter');
                    }
                };
                Array.fromAsync(
                    { [Symbol.asyncIterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 异步 return 拒绝时保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return Promise.resolve(
                            index++ === 0 ? { value: 1, done: false } : { done: true }
                        );
                    },
                    return: function () {
                        return Promise.reject(new Error('return-reject'));
                    }
                };
                Array.fromAsync(
                    { [Symbol.asyncIterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 异步 return 抛错时保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return Promise.resolve(
                            index++ === 0 ? { value: 1, done: false } : { done: true }
                        );
                    },
                    return: function () {
                        throw new Error('return-throw');
                    }
                };
                Array.fromAsync(
                    { [Symbol.asyncIterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }
    @Test
    fun `Array fromAsync 异步 return 解析为原始值时保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return Promise.resolve(
                            index++ === 0 ? { value: 1, done: false } : { done: true }
                        );
                    },
                    return: function () {
                        return Promise.resolve(1);
                    }
                };
                Array.fromAsync(
                    { [Symbol.asyncIterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 异步 return 解析为非 done 对象时保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return Promise.resolve(
                            index++ === 0 ? { value: 1, done: false } : { done: true }
                        );
                    },
                    return: function () {
                        return Promise.resolve({ done: false });
                    }
                };
                Array.fromAsync(
                    { [Symbol.asyncIterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 同步 return 解析为原始值时保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return index++ === 0 ? { value: 1, done: false } : { done: true };
                    },
                    return: function () {
                        return 1;
                    }
                };
                Array.fromAsync(
                    { [Symbol.iterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 同步 return 解析为非 done 对象时保留原错误`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var log = [];
                var index = 0;
                var iterator = {
                    next: function () {
                        return index++ === 0 ? { value: 1, done: false } : { done: true };
                    },
                    return: function () {
                        return { done: false };
                    }
                };
                Array.fromAsync(
                    { [Symbol.iterator]: function () { return iterator; } },
                    function () { throw new Error('map'); }
                ).then(
                    function () {
                        log.push('fulfilled');
                    },
                    function (error) {
                        log.push('reject:' + error.message);
                    }
                );
            """.trimIndent()
        )
        assertEquals("reject:map", runtime.evalString("log.join(',')"))
    }

    @Test
    fun `Array fromAsync 同步迭代器 done 为 true 时不读取 value`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var out = '';
                var source = {
                    [Symbol.iterator]: function () {
                        return {
                            next: function () {
                                return {
                                    done: true,
                                    get value() {
                                        throw new Error('value');
                                    }
                                };
                            }
                        };
                    }
                };
                Array.fromAsync(source).then(function (a) {
                    out = String(a.length);
                }, function (e) {
                    out = e.message;
                });
            """.trimIndent()
        )
        assertEquals("0", runtime.evalString("out"))
    }

    @Test
    fun `数组泛型方法支持 2 的 53 次方级 array-like 长度`() = runTest {
        val runtime = TestRuntime()
        // pop：ToLength 裁剪到 2^53-1，并按 Long 索引读取/删除
        assertEquals(true, runtime.evalBoolean(
            """
                var o = { length: 2 ** 53 + 2, '9007199254740990': 'x' };
                var v = Array.prototype.pop.call(o);
                v === 'x' && o.length === 2 ** 53 - 2 &&
                !Object.prototype.hasOwnProperty.call(o, '9007199254740990')
            """.trimIndent()
        ))
        // push：无新元素时把 length 收敛到 2^53-1，追加时抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var o = { length: 2 ** 53 };
                Array.prototype.push.call(o);
                var clamped = o.length === 2 ** 53 - 1;
                var threw = false;
                try { Array.prototype.push.call(o, 1); }
                catch (e) { threw = e instanceof TypeError; }
                clamped && threw
            """.trimIndent()
        ))
        // unshift：argCount 为 0 时仅写回裁剪后的 length
        assertEquals(true, runtime.evalBoolean(
            """
                var o = { length: 2 ** 53 + 2 };
                Array.prototype.unshift.call(o);
                o.length === 2 ** 53 - 1
            """.trimIndent()
        ))
        // reverse/slice：只访问靠近 len-1 的索引，不对全长度循环
        assertEquals(true, runtime.evalBoolean(
            """
                var stop = {};
                var o = { get '9007199254740990'() { throw stop; }, length: 2 ** 53 + 2 };
                var threw = false;
                try { Array.prototype.reverse.call(o); } catch (e) { threw = e === stop; }
                var s = { length: 2 ** 53 + 2,
                    '9007199254740989': 'a', '9007199254740990': 'b' };
                var sliced = Array.prototype.slice.call(s, 9007199254740989);
                threw && sliced.join(',') === 'a,b'
            """.trimIndent()
        ))
        // splice：收缩时搬移近端元素并删除尾部
        assertEquals(true, runtime.evalBoolean(
            """
                var o = { length: 2 ** 53 - 1, '9007199254740990': 'a' };
                var removed = Array.prototype.splice.call(o, 9007199254740990, 1);
                removed[0] === 'a' && o.length === 2 ** 53 - 2 &&
                !Object.prototype.hasOwnProperty.call(o, '9007199254740990')
            """.trimIndent()
        ))
        // includes：len 为 0 时不读取 fromIndex
        assertEquals(true, runtime.evalBoolean(
            """
                var calls = 0;
                [].includes(0, { valueOf: function () { calls++; } });
                calls === 0
            """.trimIndent()
        ))
    }

    @Test
    fun `RegExp dotAll 与空非捕获组编译`() = runTest {
        val runtime = TestRuntime()
        // s 标志下 . 匹配行终止符
        assertEquals(true, runtime.evalBoolean("/a.b/s.test('a\\nb') && !/a.b/.test('a\\nb')"))
        // 空非捕获组在字面量与 compile 中都可编译
        assertEquals("(?:)", runtime.evalString("/(?:)/.source"))
        assertEquals("gimsuy", runtime.evalString(
            "var re = /(?:)/; re.compile('(?:)', 'imsuyg'); re.flags"
        ))
        // dotAll 访问器：RegExp.prototype 返回 undefined，其他普通对象抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var desc = Object.getOwnPropertyDescriptor(RegExp.prototype, 'dotAll');
                desc.get.call(RegExp.prototype) === undefined
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var get = Object.getOwnPropertyDescriptor(RegExp.prototype, 'dotAll').get;
                try { get.call({}); false } catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
    }

    @Test
    fun `Annex B RegExp compile 与 escape-unescape`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var re = /abc/gim; re.lastIndex = 5;
                re.compile('def', 'i') === re && re.source === 'def' &&
                re.flags === 'i' && re.lastIndex === 0 && re.test('DEF')
            """.trimIndent()
        ))
        // @@split 先构造 splitter，再转换 limit
        assertEquals("3|,bb,", runtime.evalString(
            """
                var re = /a/;
                var out = re[Symbol.split]('abba',
                { valueOf: function () { re.compile('b'); return -1; } });
                String(out.length) + '|' + out.join(',')
            """.trimIndent()
        ))
        assertEquals("%uD834%uDF06%u0100%20", runtime.evalString(
            "escape('\\uD834\\uDF06\\u0100 ')"
        ))
        assertEquals(4.0, runtime.evalNumber(
            "unescape('%uD834%uDF06%u0100%20').length"
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                unescape('%41%42') === 'AB' && unescape('%u0041') === 'A' &&
                unescape('%zz') === '%zz'
            """.trimIndent()
        ))
    }

    @Test
    fun `属性枚举顺序与 for-in 继承顺序`() = runTest {
        val runtime = TestRuntime()
        assertEquals("1,2,b,a|1,2,b,a", runtime.evalString(
            """
                var o = { b: 1, a: 2, 2: 3, 1: 4 };
                var inOrder = [];
                for (var key in o) inOrder.push(key);
                Object.keys(o).join(',') + '|' + inOrder.join(',')
            """.trimIndent()
        ))
        // 原型上的可枚举属性排在自有属性之后，且被自有同名属性遮蔽
        assertEquals("a,b,c", runtime.evalString(
            """
                var proto = { b: 0, c: 0 };
                var o = Object.create(proto);
                o.a = 1; o.b = 2;
                var order = []; for (var k in o) order.push(k);
                order.join(',')
            """.trimIndent()
        ))
    }

    @Test
    fun `JSON rawJSON 与 reviver source 上下文`() = runTest {
        val runtime = TestRuntime()
        assertEquals("[1,\"x\",null,true]", runtime.evalString(
            """
                var raw = JSON.rawJSON(1);
                JSON.isRawJSON(raw) && Object.getPrototypeOf(raw) === null &&
                JSON.stringify([raw, JSON.rawJSON('"x"'),
                    JSON.rawJSON(null), JSON.rawJSON(true)])
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var bad = 0;
                try { JSON.rawJSON([]); } catch (e) { if (e instanceof SyntaxError) bad++; }
                try { JSON.rawJSON(' 1'); } catch (e) { if (e instanceof SyntaxError) bad++; }
                try { JSON.rawJSON(Symbol('x')); }
                catch (e) { if (e instanceof TypeError) bad++; }
                bad === 3
            """.trimIndent()
        ))
        // reviver context.source 仅对原始字面量提供
        assertEquals("1.1e1|\"foo\"|1", runtime.evalString(
            """
                var out = [];
                JSON.parse('[1.1e1, "foo", {"x": 1}]',
                function (k, v, context) {
                    if (k === '0' || k === '1' || k === 'x') out.push(context.source);
                    return v; });
                out.join('|')
            """.trimIndent()
        ))
    }

    @Test
    fun `Error prototype stack 访问器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var e = new Error('msg');
                typeof e.stack === 'string' &&
                !Object.prototype.hasOwnProperty.call(e, 'stack') &&
                typeof Object.getOwnPropertyDescriptor(Error.prototype, 'stack').get ===
                'function'
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var e = new TypeError('m');
                e.stack = 'custom';
                e.stack === 'custom' &&
                Object.getOwnPropertyDescriptor(e, 'stack').enumerable &&
                delete e.stack && typeof e.stack === 'string'
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var threw = 0;
                try { new Error().stack = null; } catch (e) { if (e instanceof TypeError) threw++; }
                try { Error.prototype.stack = 'x'; } catch (e) {
                    if (e instanceof TypeError) threw++; }
                threw === 2
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            "Object.create(new Error()).stack === undefined"
        ))
    }

    @Test
    fun `可选 catch 绑定、稳定排序与 JSON 超集`() = runTest {
        val runtime = TestRuntime()
        assertEquals("caught", runtime.evalString(
            """
                var out = '';
                try { throw new Error('x'); } catch { out = 'caught'; }
                out
            """.trimIndent()
        ))
        assertEquals("BDEAC", runtime.evalString(
            """
                var a = [{ n: 'A', r: 2 }, { n: 'B', r: 3 }, { n: 'C', r: 2 },
                    { n: 'D', r: 3 }, { n: 'E', r: 3 }];
                a.sort(function (x, y) { return y.r - x.r; });
                a.map(function (e) { return e.n; }).join('')
            """.trimIndent()
        ))
        assertEquals(2.0, runtime.evalNumber("'\u2028'.length + '\u2029'.length"))
    }

    @Test
    fun `hashbang 注释仅允许位于源文件开头`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber(
                """
                #! /usr/bin/env node
                1
                """.trimIndent()
            )
        )
        assertEquals(2.0, runtime.evalNumber("#!line\r2"))
        assertFailsWith<LexerException> { runtime.eval(" #! not at start") }
        assertFailsWith<LexerException> {
            runtime.eval(
                """
                // comment
                #! not at start
                """.trimIndent()
            )
        }
    }

    @Test
    fun `Iterator includes 与 join 及 Error isError`() = runTest {
        val runtime = TestRuntime()
        // includes：命中后关闭迭代器；支持 SameValueZero 与 skippedElements
        assertEquals(true, runtime.evalBoolean(
            """
                var closed = false; var count = 0;
                var it = { __proto__: Iterator.prototype,
                    next: function () { return { done: false, value: ++count }; },
                    return: function () { closed = true; return {}; } };
                it.includes(3) && closed && count === 3
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                [1, NaN, -0].values().includes(NaN) &&
                [1, NaN, -0].values().includes(0) &&
                [1, 2, 3].values().includes(2, 1) &&
                ![1, 2].values().includes(9)
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { [].values().includes(0, -1); false } catch (e) { e instanceof RangeError }"
        ))
        // join：nullish 为空串，可自定义分隔符
        assertEquals("one,,two,", runtime.evalString(
            "['one', null, 'two', undefined].values().join()"
        ))
        assertEquals("a-b-c", runtime.evalString(
            "['a', 'b', 'c'].values().join('-')"
        ))
        // Error.isError：仅真正具有 [[ErrorData]] 的对象为 true
        assertEquals(true, runtime.evalBoolean(
            """
                Error.isError(new Error()) && Error.isError(new TypeError()) &&
                !Error.isError({}) && !Error.isError(Error.prototype) &&
                !Error.isError(1) && !Error.isError(null)
            """.trimIndent()
        ))
    }

    @Test
    fun `Promise allKeyed 与 allSettledKeyed`() = runTest {
        val runtime = TestRuntime()
        runtime.eval(
            """
                var out = '';
                Promise.allKeyed({ a: Promise.resolve(1), b: 2 }).then(function (r) {
                    out = (Object.getPrototypeOf(r) === null) + ':' + r.a + ',' + r.b; });
            """.trimIndent()
        )
        assertEquals("true:1,2", runtime.evalString("out"))
        runtime.eval(
            """
                Promise.allSettledKeyed({ a: Promise.resolve(3), b: Promise.reject(4) })
                .then(function (r) {
                    out = r.a.status + ':' + r.a.value + ',' + r.b.status + ':' + r.b.reason; });
            """.trimIndent()
        )
        assertEquals("fulfilled:3,rejected:4", runtime.evalString("out"))
        // 非对象参数以拒绝形式返回 TypeError
        runtime.eval(
            """
                Promise.allKeyed(123).then(function () { out = 'fulfilled'; },
                function (e) { out = String(e instanceof TypeError); });
            """.trimIndent()
        )
        assertEquals("true", runtime.evalString("out"))
        // 仅遍历自有可枚举键，结果保持键顺序
        runtime.eval(
            """
                var obj = Object.create(null);
                obj.a = Promise.resolve(5); obj.b = 6; obj.hidden = 1;
                Object.defineProperty(obj, 'hidden', { enumerable: false });
                Promise.allKeyed(obj).then(function (r) {
                    out = Object.keys(r).join(',') + ':' + r.a + ',' + r.b; });
            """.trimIndent()
        )
        assertEquals("a,b:5,6", runtime.evalString("out"))
    }

    @Test
    fun `Iterator chunks 与 windows 分块语义`() = runTest {
        val runtime = TestRuntime()
        // chunks：末尾允许不足块，每块为新数组
        assertEquals("0,1;2,3;4", runtime.evalString(
            """
                Array.from([0, 1, 2, 3, 4].values().chunks(2))
                .map(function (c) { return c.join(','); }).join(';')
            """.trimIndent()
        ))
        // windows：默认仅完整窗口
        assertEquals("0,1;1,2;2,3;3,4", runtime.evalString(
            """
                Array.from([0, 1, 2, 3, 4].values().windows(2))
                .map(function (w) { return w.join(','); }).join(';')
            """.trimIndent()
        ))
        // allow-partial：末尾不足窗口仍输出一次
        assertEquals("1:0,1,2", runtime.evalString(
            """
                var out = Array.from([0, 1, 2].values().windows(10, 'allow-partial'));
                out.length + ':' + out[0].join(',')
            """.trimIndent()
        ))
        // 参数校验：不做类型转换；范围与 undersized 模式非法时分别抛错
        assertEquals(true, runtime.evalBoolean(
            """
                var bad = 0;
                try { [].values().chunks({ valueOf: function () { return 2; } }); }
                catch (e) { if (e instanceof TypeError) bad++; }
                try { [].values().chunks(0); }
                catch (e) { if (e instanceof RangeError) bad++; }
                try { [].values().windows(1, 'x'); }
                catch (e) { if (e instanceof TypeError) bad++; }
                bad === 3
            """.trimIndent()
        ))
    }

    @Test
    fun `Annex B 对象访问器方法与描述符保留`() = runTest {
        val runtime = TestRuntime()
        assertEquals("g2,s", runtime.evalString(
            """
                var o = {};
                o.__defineGetter__('z', function () { return 'g2'; });
                o.__defineSetter__('w', function (v) { this.y = v; });
                o.w = 's';
                o.z + ',' + o.y
            """.trimIndent()
        ))
        // 重定义单侧访问器时保留另一侧；lookup 沿原型链查找
        assertEquals(true, runtime.evalBoolean(
            """
                var setter = function (v) { this.y = v; };
                var proto = {};
                Object.defineProperty(proto, 'a',
                { get: function () { return 1; }, set: setter, configurable: true });
                var child = Object.create(proto);
                Object.defineProperty(child, 'a',
                { get: function () { return 1; }, set: setter, configurable: true });
                child.__defineGetter__('a', function () { return 2; });
                var inheritedGetter = proto.__lookupGetter__('a');
                child.a === 2 && child.__lookupSetter__('a') === setter &&
                typeof inheritedGetter === 'function'
            """.trimIndent()
        ))
        // 不可配置属性无法通过 __defineGetter__ 重定义
        assertEquals(true, runtime.evalBoolean(
            """
                var o = Object.defineProperty({}, 'x', { value: 1, configurable: false });
                try { o.__defineGetter__('x', function () { return 2; }); false }
                catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
    }

    @Test
    fun `AsyncDisposableStack 异步释放与错误聚合`() = runTest {
        val runtime = TestRuntime()
        // defer 结果会被 Await：先完成 Promise 再释放下一个资源
        runtime.eval(
            """
                var out = '';
                var stack = new AsyncDisposableStack();
                stack.defer(function () { out += '1'; });
                stack.defer(function () {
                    return Promise.resolve().then(function () { out += '2'; }); });
                stack.disposeAsync().then(function () { out += '3'; });
            """.trimIndent()
        )
        assertEquals("213", runtime.evalString("out"))
        // use 优先 @@asyncDispose，缺失时回退 @@dispose
        runtime.eval(
            """
                out = '';
                var both = { [Symbol.asyncDispose]: function () { out += 'a'; },
                    [Symbol.dispose]: function () { out += 's'; } };
                var syncOnly = { [Symbol.dispose]: function () { out += 'd'; } };
                var stack = new AsyncDisposableStack();
                stack.use(both); stack.use(syncOnly);
                stack.disposeAsync();
            """.trimIndent()
        )
        assertEquals("da", runtime.evalString("out"))
        // 多个异步释放错误嵌套为 SuppressedError
        runtime.eval(
            """
                var e1 = new Error('1'), e2 = new Error('2');
                var stack = new AsyncDisposableStack();
                stack.defer(function () { return Promise.reject(e1); });
                stack.defer(function () { throw e2; });
                stack.disposeAsync().then(function () { out = 'fulfilled'; },
                function (e) { out = String(e instanceof SuppressedError &&
                    e.error === e1 && e.suppressed === e2); });
            """.trimIndent()
        )
        assertEquals("true", runtime.evalString("out"))
        // move 转移资源并标记旧栈 disposed
        assertEquals(true, runtime.evalBoolean(
            """
                var stack = new AsyncDisposableStack();
                stack.defer(function () { return undefined; });
                var moved = stack.move();
                stack.disposed === true && moved.disposed === false &&
                moved instanceof AsyncDisposableStack
            """.trimIndent()
        ))
    }

    @Test
    fun `DisposableStack 逆序释放并聚合 SuppressedError`() = runTest {
        val runtime = TestRuntime()
        // use + defer 按加入顺序逆序释放
        assertEquals("2,1", runtime.evalString(
            """
                var out = [];
                var stack = new DisposableStack();
                stack.defer(function () { out.push(1); });
                stack.use({ [Symbol.dispose]: function () { out.push(2); } });
                stack.dispose();
                out.join(',')
            """.trimIndent()
        ))
        // adopt 回调接收原值；move 转移资源并把旧栈标记为 disposed
        assertEquals(true, runtime.evalBoolean(
            """
                var seen; var stack = new DisposableStack();
                stack.adopt(5, function (v) { seen = v; });
                var before = stack.disposed;
                var moved = stack.move();
                var after = stack.disposed;
                moved.dispose();
                before === false && after === true && seen === 5 && moved.disposed === true
            """.trimIndent()
        ))
        // 多个释放错误嵌套为 SuppressedError
        assertEquals(true, runtime.evalBoolean(
            """
                var e1 = new Error('1'), e2 = new Error('2'), e3 = new Error('3');
                var stack = new DisposableStack();
                stack.defer(function () { throw e1; });
                stack.defer(function () { throw e2; });
                stack.defer(function () { throw e3; });
                try { stack.dispose(); false } catch (e) {
                    e instanceof SuppressedError && e.error === e1 &&
                    e.suppressed instanceof SuppressedError &&
                    e.suppressed.error === e2 && e.suppressed.suppressed === e3 }
            """.trimIndent()
        ))
        // 已释放的栈再 use 抛 ReferenceError
        assertEquals(true, runtime.evalBoolean(
            """
                var stack = new DisposableStack(); stack.dispose();
                try { stack.use({}); false } catch (e) { e instanceof ReferenceError }
            """.trimIndent()
        ))
        // @@dispose 与 dispose 同一函数；Iterator.prototype[@@dispose] 调用 return
        assertEquals("true,1", runtime.evalString(
            """
                var calls = 0;
                var it = { return: function () { calls++; return {}; } };
                Iterator.prototype[Symbol.dispose].call(it);
                String(DisposableStack.prototype[Symbol.dispose] ===
                DisposableStack.prototype.dispose) + ',' + calls
            """.trimIndent()
        ))
    }

    @Test
    fun `WeakMap 与 WeakSet 支持非注册 Symbol 键`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var s = Symbol('k');
                var map = new WeakMap([[s, 1]]);
                map.get(s) === 1 && map.has(s) &&
                map.getOrInsert(s, 2) === 1 && map.delete(s) && !map.has(s)
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var s = Symbol('k');
                var set = new WeakSet([s]);
                set.has(s) && set.delete(s) && !set.has(s)
            """.trimIndent()
        ))
        // 注册符号不能作为弱引用键
        assertEquals(true, runtime.evalBoolean(
            """
                var threw = 0;
                try { new WeakMap().set(Symbol.for('reg'), 1); }
                catch (e) { if (e instanceof TypeError) threw++; }
                try { new WeakSet().add(Symbol.for('reg')); }
                catch (e) { if (e instanceof TypeError) threw++; }
                threw === 2
            """.trimIndent()
        ))
    }

    @Test
    fun `Iterator zip 与 zipKeyed 的模式和结果对象`() = runTest {
        val runtime = TestRuntime()
        // shortest：按最短输入结束
        assertEquals("1,4;2,5;3,6", runtime.evalString(
            """
                var out = [];
                for (var pair of Iterator.zip([[1, 2, 3], [4, 5, 6]]))
                out.push(pair.join(','));
                out.join(';')
            """.trimIndent()
        ))
        // longest + padding：较短输入缺失位置使用 padding
        assertEquals("1,2;9,3", runtime.evalString(
            """
                var out = [];
                for (var pair of Iterator.zip([[1], [2, 3]],
                { mode: 'longest', padding: [9] })) out.push(pair.join(','));
                out.join(';')
            """.trimIndent()
        ))
        // strict：长度不一致抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                try { Array.from(Iterator.zip([[1], [2, 3]], { mode: 'strict' })); false }
                catch (e) { e instanceof TypeError }
            """.trimIndent()
        ))
        // zipKeyed：null 原型结果对象与键顺序
        assertEquals(true, runtime.evalBoolean(
            """
                var results = Array.from(Iterator.zipKeyed({ a: [1, 2], b: [3, 4] }));
                Object.getPrototypeOf(results[0]) === null &&
                results[1].a === 2 && results[1].b === 4 &&
                Array.prototype.join.call(Object.keys(results[0]), ',') === 'a,b'
            """.trimIndent()
        ))
        // zipKeyed + longest：padding 按属性包读取
        assertEquals(true, runtime.evalBoolean(
            """
                var results = Array.from(Iterator.zipKeyed({ a: [1], b: [2, 3] },
                { mode: 'longest', padding: { a: 9 } }));
                results[1].a === 9 && results[1].b === 3
            """.trimIndent()
        ))
        // return() 逆序关闭开放迭代器
        assertEquals("2,1", runtime.evalString(
            """
                var closed = [];
                function make(n) { return {
                        next: function () { return { done: false, value: n }; },
                        return: function () { closed.push(n); return {}; } }; }
                var it = Iterator.zip([make(1), make(2)]);
                it.next(); it.return();
                closed.join(',')
            """.trimIndent()
        ))
        // 非法 mode / options 立即抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var bad = 0;
                try { Iterator.zip([], { mode: 'loose' }); }
                catch (e) { if (e instanceof TypeError) bad++; }
                try { Iterator.zip([], null); }
                catch (e) { if (e instanceof TypeError) bad++; }
                bad === 2
            """.trimIndent()
        ))
    }

    @Test
    fun `Math sumPrecise 精确求和与特殊值`() = runTest {
        val runtime = TestRuntime()
        // 中间溢出后相互抵消仍返回精确舍入结果
        assertEquals("0.30000000000000004", runtime.evalString(
            """
                String(Math.sumPrecise([1e308, 1e308, 0.1, 0.1, 1e30, 0.1,
                    -1e30, -1e308, -1e308]))
            """.trimIndent()
        ))
        // 两条超大半和相减的临界舍入
        assertEquals(true, runtime.evalBoolean(
            """
                var r = Math.sumPrecise([8.98846567431158e+307, 8.988465674311579e+307,
                    -1.7976931348623157e+308]);
                r === 9.9792015476736e+291
            """.trimIndent()
        ))
        // 空输入与全 -0 返回 -0；-0 与 +0 混合返回 +0
        assertEquals(true, runtime.evalBoolean(
            """
                Object.is(Math.sumPrecise([]), -0) &&
                Object.is(Math.sumPrecise([-0, -0]), -0) &&
                Object.is(Math.sumPrecise([-0, 0]), 0)
            """.trimIndent()
        ))
        // NaN / 无穷
        assertEquals(true, runtime.evalBoolean(
            """
                Number.isNaN(Math.sumPrecise([Infinity, -Infinity])) &&
                Math.sumPrecise([1e308, 1e308]) === Infinity &&
                Number.isNaN(Math.sumPrecise([NaN]))
            """.trimIndent()
        ))
        // 非 Number 元素抛 TypeError 并关闭迭代器（不做隐式转换）
        assertEquals(true, runtime.evalBoolean(
            """
                var closed = false;
                var iter = { [Symbol.iterator]: function () { return this; },
                    next: function () { return { done: false, value: {} }; },
                    return: function () { closed = true; return {}; } };
                var threw = false;
                try { Math.sumPrecise(iter); } catch (e) { threw = e instanceof TypeError; }
                threw && closed
            """.trimIndent()
        ))
    }

    @Test
    fun `class 公有字段按源码顺序初始化且支持 this super 与命名推断`() = runTest {
        val runtime = TestRuntime()
        // 静态字段与静态块按源码顺序执行；实例字段在构造时初始化
        assertEquals(true, runtime.evalBoolean(
            """
                var seq = [];
                class C1 { static a = seq.push('a'); static { seq.push('b'); } static c = seq.push('c'); }
                var c = new C1(); seq.join(',') === 'a,b,c' && C1.a === 1 && C1.c === 3
            """.trimIndent()
        ))
        // 实例字段按序初始化：可引用 this 与前置字段；未初始化字段仍创建自有属性
        assertEquals(true, runtime.evalBoolean(
            """
                class C2 { a = 1; b = this.a + 1; c; }
                var o = new C2(); o.a === 1 && o.b === 2 && o.c === undefined && o.hasOwnProperty('c')
            """.trimIndent()
        ))
        // 基类字段先于构造器体；派生类字段在 super() 返回后、构造器体剩余部分之前
        assertEquals(true, runtime.evalBoolean(
            """
                var order = [];
                class Base { constructor() { order.push('base-body'); } }
                class Sub extends Base {
                    x = order.push('derived-field');
                    constructor() { super(); order.push('derived-body'); }
                }
                var b2order = [];
                class Base2 { f = b2order.push('base-field'); constructor() { b2order.push('base-body'); } }
                new Sub(); new Base2();
                order.join(',') === 'base-body,derived-field,derived-body' &&
                b2order.join(',') === 'base-field,base-body'
            """.trimIndent()
        ))
        // super 属性、new.target 与匿名函数命名推断
        assertEquals(true, runtime.evalBoolean(
            """
                class E { get v() { return 5; } }
                class F extends E { x = super.v; y = new.target; f = function () {}; g = () => {}; }
                var o = new F();
                o.x === 5 && o.y === undefined && o.f.name === 'f' && o.g.name === 'g'
            """.trimIndent()
        ))
        // 计算键在类定义阶段按序求值
        assertEquals(true, runtime.evalBoolean(
            """
                var keys = [];
                class G { [(keys.push('k1'), 'a')] = 1; static [(keys.push('k2'), 'b')] = 2; }
                keys.join(',') === 'k1,k2' && new G().a === 1 && G.b === 2
            """.trimIndent()
        ))
        // 字段描述符为可写/可枚举/可配置
        assertEquals(true, runtime.evalBoolean(
            """
                var d = Object.getOwnPropertyDescriptor(new (class { x = 1 })(), 'x');
                d.writable && d.enumerable && d.configurable
            """.trimIndent()
        ))
    }

    @Test
    fun `class 字段早期错误与 arguments await 限制`() = runTest {
        val runtime = TestRuntime()
        // arguments 在字段初始化器中非法（箭头不解除、普通函数解除）
        assertFailsWith<ParseException> { runtime.eval("class C { x = arguments; }") }
        assertFailsWith<ParseException> { runtime.eval("class C { x = () => arguments; }") }
        assertEquals(true, runtime.evalBoolean(
            "class C1 { x = function () { return arguments.length; }; } new C1().x(1, 2) === 2"
        ))
        // 字段名为 constructor、静态字段名为 prototype、访问器缺参数列表均非法
        assertFailsWith<ParseException> { runtime.eval("class C { constructor = 1; }") }
        assertFailsWith<ParseException> { runtime.eval("class C { static prototype = 1; }") }
        assertFailsWith<ParseException> { runtime.eval("class C { get x = 1; }") }
        // await 作为标识符合法；await 表达式在字段初始化器中非法
        assertEquals(true, runtime.evalBoolean("class C2 { x = typeof await; } new C2().x === 'undefined'"))
        assertFailsWith<ParseException> {
            runtime.eval("async function f() { class C { x = await 1; } }")
        }
        // super() 调用与块内 arguments 引用限制
        assertFailsWith<ParseException> { runtime.eval("class C extends Object { x = super(); }") }
        assertEquals(true, runtime.evalBoolean(
            "class C3 { static { class D { x = await; } } } true"
        ))
    }


    @Test
    fun `class 私有字段支持读写品牌检查与 in 运算`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                class C { #x = 1; get() { return this.#x; } set(v) { this.#x = v; } has(o) { return #x in o; } }
                var c = new C(); var before = c.get(); c.set(5);
                before === 1 && c.get() === 5 && c.has(c) === true && c.has({}) === false
            """.trimIndent()
        ))
        // 品牌检查失败与跨实例访问（同一 class 的不同实例共享私有名）
        assertEquals(true, runtime.evalBoolean(
            """
                class D { #x = 1; read(o) { return o.#x; } }
                var d1 = new D(); var d2 = new D();
                d1.read(d2) === 1 && (function () {
                    try { d1.read({}); return false } catch (e) { return e instanceof TypeError }
                })()
            """.trimIndent()
        ))
        // 字段初始化顺序：#a 在 #b 尚未初始化时不可访问
        assertEquals(true, runtime.evalBoolean(
            "class E { #a; #b = this.#a; } var e = new E(); e instanceof E"
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                class F { #a = this.#b; #b = 1; }
                var threw = false; try { new F(); } catch (e) { threw = e instanceof TypeError } threw
            """.trimIndent()
        ))
        // 私有字段不受 Object.freeze/preventExtensions 影响
        assertEquals(true, runtime.evalBoolean(
            """
                class G { #x = 1; constructor() { Object.freeze(this); } bump() { this.#x++; return this.#x; } }
                new G().bump() === 2
            """.trimIndent()
        ))
    }

    @Test
    fun `class 私有方法与访问器`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                class C { #m() { return this.v } #get() { return 'g' } #set(v) { this.v = v }
                    run() { this.#set('v'); return this.#m() + this.#get(); }
                    get #value() { return 7 } set #value(v) { this.stored = v }
                    access() { var before = this.#value; this.#value = 9; return [before, this.stored]; }
                }
                var c = new C(); var acc = c.access();
                c.run() === 'vg' && acc[0] === 7 && acc[1] === 9
            """.trimIndent()
        ))
        // 私有方法不可写、访问器缺 setter 时写入抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                class D { #m() {} tryAssign() { try { this.#m = 1; return false } catch (e) { return e instanceof TypeError } }
                    get #g() { return 1 } trySet() { try { this.#g = 2; return false } catch (e) { return e instanceof TypeError } } }
                var d = new D(); d.tryAssign() === true && d.trySet() === true
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                class E { #m() { return 1 } call(o) { return o.#m() } }
                new E().call(new E()) === 1 &&
                (function () { try { new E().call({}); return false } catch (e) { return e instanceof TypeError } })()
            """.trimIndent()
        ))
        // 私有方法名带 # 前缀；方法不可构造、不可写
        assertEquals("#m", runtime.evalString(
            "class F { #m() {} name() { return this.#m.name } } new F().name()"
        ))
    }

    @Test
    fun `class 私有静态成员与嵌套类作用域`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                class C { static #x = 1; static #m() { return this.#x }
                    static get() { this.#x = 2; return this.#m() }
                    static has(o) { return #x in o }
                }
                C.get() === 2 && C.has(C) === true && C.has(C.prototype) === false
            """.trimIndent()
        ))
        // 嵌套 class 可以访问外层 class 的私有名
        assertEquals(true, runtime.evalBoolean(
            """
                class Outer { #x = 5; make() { return class { read(o) { return o.#x } } } }
                var Reader = new Outer().make(); Reader.prototype.constructor;
                new Reader().read(new Outer()) === 5
            """.trimIndent()
        ))
        // 私有名按 class 求值唯一：同名不同 class 不共享
        assertEquals(true, runtime.evalBoolean(
            """
                function make() { return class { #x = 1; has(o) { return #x in o } } }
                var A = make(); var B = make(); new A().has(new A()) === true && new A().has(new B()) === false
            """.trimIndent()
        ))
    }

    @Test
    fun `class 私有名的早期错误`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<ParseException> { runtime.eval("class C { #x; #x; }") }
        assertFailsWith<ParseException> { runtime.eval("class C { #x; #x() {} }") }
        assertFailsWith<ParseException> { runtime.eval("class C { #constructor; }") }
        assertFailsWith<ParseException> { runtime.eval("class C { m() { return this.#x } }") }
        assertFailsWith<ParseException> { runtime.eval("class C { m() { return #x in this } }") }
        assertFailsWith<ParseException> { runtime.eval("class C { #x; m() { delete this.#x } }") }
        assertFailsWith<ParseException> { runtime.eval("this.#x;") }
        assertFailsWith<ParseException> { runtime.eval("class C extends Object { m() { return super.#x } }") }
        // getter/setter 成对声明合法；实例与静态同名私有仍属重名
        runtime.eval("class C { get #x() { return 1 } set #x(v) {} }")
        assertFailsWith<ParseException> { runtime.eval("class C { #y; static #y; }") }
        // 私有名可在嵌套 class 中解析到外层声明
        runtime.eval("class Z { #z; m() { return class { r(o) { return o.#z } } } }")
    }

    @Test
    fun `using 声明在块结束时逆序释放资源`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var log = [];
                var r1 = { [Symbol.dispose]() { log.push(this) } };
                var r2 = { [Symbol.dispose]() { log.push(this) } };
                { using a = r1, b = r2; }
                log.length === 2 && log[0] === r2 && log[1] === r1
            """.trimIndent()
        ))
        // 初始化时读取释放方法一次，且以资源为 this 调用
        assertEquals(true, runtime.evalBoolean(
            """
                var reads = 0; var self = false;
                var resource = { get [Symbol.dispose]() { reads++; return function () { self = this === resource } } };
                { using x = resource; } reads === 1 && self
            """.trimIndent()
        ))
        // null/undefined 初始化器合法；释放方法为 null/undefined 时初始化即抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            "{ using a = null; using b = undefined; } true"
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { { using x = { [Symbol.dispose]: null }; } false } catch (e) { e instanceof TypeError }"
        ))
        // 绑定不可变
        assertEquals(true, runtime.evalBoolean(
            "{ using x = null; try { x = 1; false } catch (e) { e instanceof TypeError } }"
        ))
        // 语句完成值为空，不影响 eval 完成值
        assertEquals(4.0, runtime.evalNumber("4; { using x = null; }"))
    }

    @Test
    fun `using 异常释放按 SuppressedError 聚合`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                class MyError extends Error {}
                var e1 = new MyError(), e2 = new MyError(), e3 = new MyError();
                try {
                    using a = { [Symbol.dispose]() { throw e1 } };
                    using b = { [Symbol.dispose]() { throw e2 } };
                    throw e3;
                } catch (e) {
                    e instanceof SuppressedError && e.error === e1 &&
                    e.suppressed instanceof SuppressedError &&
                    e.suppressed.error === e2 && e.suppressed.suppressed === e3
                }
            """.trimIndent()
        ))
        // 仅一个错误时按原样抛出
        assertEquals(true, runtime.evalBoolean(
            """
                class E2 extends Error {} var single = new E2();
                try { using a = { [Symbol.dispose]() { throw single } }; } catch (e) { e === single }
            """.trimIndent()
        ))
        // 后续初始化器抛错时，已声明的资源仍会释放
        assertEquals(true, runtime.evalBoolean(
            """
                var disposed = false;
                try { { using a = { [Symbol.dispose]() { disposed = true } }; using b = (() => { throw 1 })(); } } catch (e) {}
                disposed
            """.trimIndent()
        ))
    }

    @Test
    fun `await using 释放与 for-of using 每轮释放`() = runTest {
        val runtime = TestRuntime()
        // await using：优先 @@asyncDispose，并 Await 返回值
        assertEquals(true, runtime.evalBoolean(
            """
                var order = [];
                async function f() {
                    using a = { [Symbol.dispose]() { order.push('sync') } };
                    await using b = { [Symbol.asyncDispose]() { order.push('async-start'); return Promise.resolve().then(() => order.push('async-end')) } };
                }
                f().then(() => { order.push('done') }); true
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var log2 = [];
                async function g() {
                    { await using x = { [Symbol.dispose]() { log2.push('disposed') } }; log2.push('body'); }
                    log2.push('after');
                }
                g().then(() => log2.push('done')); true
            """.trimIndent()
        ))
        // for-of using：每轮结束释放
        assertEquals(true, runtime.evalBoolean(
            """
                var seen = [];
                for (using r of [{ [Symbol.dispose]() { seen.push('a') } }, { [Symbol.dispose]() { seen.push('b') } }]) { seen.push('body') }
                seen.join(',') === 'body,a,body,b'
            """.trimIndent()
        ))
        // for (using ...; ...) 资源在循环结束时释放
        assertEquals(true, runtime.evalBoolean(
            """
                var items = [];
                for (using r = { [Symbol.dispose]() { items.push('disposed') } }; items.length < 2; ) { items.push('body') }
                items.join(',') === 'body,body,disposed'
            """.trimIndent()
        ))
    }

    @Test
    fun `using 语法与早期错误`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<ParseException> { runtime.eval("using x = null;") }
        assertFailsWith<ParseException> { runtime.eval("{ using x; }") }
        assertFailsWith<ParseException> { runtime.eval("{ using [] = null; }") }
        assertFailsWith<ParseException> { runtime.eval("{ using {} = null; }") }
        assertFailsWith<ParseException> { runtime.eval("for (using x in []) { }") }
        assertFailsWith<ParseException> { runtime.eval("{ if (true) using x = null; }") }
        assertFailsWith<ParseException> { runtime.eval("switch (1) { case 1: using x = null; }") }
        // using 在模块/函数/块/for 头允许
        runtime.eval("function f() { using x = null; } f();")
        runtime.eval("for (using x = null; false; ) { }")
        // 绑定 TDZ（使用前未初始化）
        assertEquals(true, runtime.evalBoolean(
            "{ var ok = false; try { x; using x = null; } catch (e) { ok = e instanceof ReferenceError } ok }"
        ))
        // 匿名函数命名推断
        assertEquals("fn,arrow", runtime.evalString(
            """
                var names = [];
                Function.prototype[Symbol.dispose] = function () {};
                { using fn = function () {}; names.push(fn.name) }
                { using arrow = () => {}; names.push(arrow.name) }
                names.join(',')
            """.trimIndent()
        ))
    }

    @Test
    fun `空值合并不得与未加括号的逻辑运算符混用`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<ParseException> { runtime.eval("a || b ?? c;") }
        assertFailsWith<ParseException> { runtime.eval("a ?? b || c;") }
        assertFailsWith<ParseException> { runtime.eval("a && b ?? c;") }
        assertFailsWith<ParseException> { runtime.eval("a ?? b && c;") }
        assertEquals(true, runtime.evalBoolean("((null || 1) ?? 2) === 1"))
        assertEquals(true, runtime.evalBoolean("((null && 1) ?? 2) === 2"))
        assertEquals(true, runtime.evalBoolean("(null ?? (0 || 2)) === 2"))
    }

    @Test
    fun `__proto__ 访问器阻止原型环并遵循 ToObject 语义`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var o = {};
                (function () { try { o.__proto__ = o; return false } catch (e) { return e instanceof TypeError } })()
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var get = Object.getOwnPropertyDescriptor(Object.prototype, '__proto__').get;
                var set = Object.getOwnPropertyDescriptor(Object.prototype, '__proto__').set;
                (function () { try { get.call(undefined); return false } catch (e) { return e instanceof TypeError } })() &&
                (function () { try { set.call(null); return false } catch (e) { return e instanceof TypeError } })()
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            "var a = {}, b = {}; a.__proto__ = b; Object.getPrototypeOf(a) === b"
        ))
    }

    @Test
    fun `AggregateError 的 message 与 cause 属性按需创建`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var errors = [1, 2];
                var cause = { x: 1 };
                var e = new AggregateError(errors, 'msg', { cause: cause });
                e.errors.length === 2 && e.errors[1] === 2 &&
                e.message === 'msg' && e.cause === cause &&
                Object.keys(e).length === 0 &&
                e instanceof AggregateError && e instanceof Error
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var a = new AggregateError([], undefined);
                var b = new AggregateError([]);
                !a.hasOwnProperty('message') && !b.hasOwnProperty('message') &&
                !b.hasOwnProperty('cause') &&
                new AggregateError([], 'm', { cause: undefined }).hasOwnProperty('cause')
            """.trimIndent()
        ))
    }

    @Test
    fun `JSON 序列化转义孤立代理项`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                JSON.stringify(String.fromCharCode(0xD834)) === '"' + String.fromCharCode(92) + 'ud834"' &&
                JSON.stringify(String.fromCharCode(0xD834, 0xDD1E)) === '"𝄞"'
            """.trimIndent()
        ))
    }

    @Test
    fun `RegExp 遗留静态属性与点号码元语义`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            $$"""
                'abc'.match(/b(c)/);
                RegExp.input === 'abc' && RegExp.$_ === 'abc' &&
                RegExp.lastMatch === 'bc' && RegExp['$&'] === 'bc' &&
                RegExp.lastParen === 'c' && RegExp['$+'] === 'c' &&
                RegExp.leftContext === 'a' && RegExp['$`'] === 'a' &&
                RegExp.rightContext === '' && RegExp["$'"] === '' &&
                RegExp.$1 === 'c' && RegExp.$2 === ''
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            "RegExp.input = 123; RegExp.input === '123'"
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var get = Object.getOwnPropertyDescriptor(RegExp, 'input').get;
                (function () { try { get.call({}); return false } catch (e) { return e instanceof TypeError } })()
            """.trimIndent()
        ))
        // 无 u 模式 . 按码元匹配 astral 字符
        assertEquals(1.0, runtime.evalNumber(
            "'\\uD835\\uDC01'.match(/./)[0].length"
        ))
        assertEquals(2.0, runtime.evalNumber(
            "'\\uD835\\uDC01'.match(/./u)[0].length"
        ))
    }

    @Test
    fun `RegExp 作用域修饰符按组生效并可嵌套覆盖`() = runTest {
        val runtime = TestRuntime()
        // i/m/s 仅在组内生效
        assertEquals(true, runtime.evalBoolean(
            "/(?i:a)b/.test('Ab') && !/(?i:a)b/.test('aB')"
        ))
        assertEquals(true, runtime.evalBoolean(
            "/(?s:.)/.test('\\n') && !/./.test('\\n') && !/(?-s:.)/s.test('\\n')"
        ))
        assertEquals(true, runtime.evalBoolean(
            "/(?s:.)x/.test('\\nx') && !/.(?-s:.)x/s.test('\\nx')"
        ))
        assertEquals(true, runtime.evalBoolean(
            "/(?m:^a)/.test('b\\na') && !/(?-m:^a)/m.test('b\\na')"
        ))
        // 嵌套覆盖：内层 (?-i:) 恢复区分大小写
        assertEquals(true, runtime.evalBoolean(
            "/(?i:(?-i:a))/.test('a') && !/(?i:(?-i:a))/.test('A')"
        ))
        // i 修饰符影响 \w/\b 的 Canonicalize：只有 u 模式的简单折叠会落到 ASCII
        assertEquals(true, runtime.evalBoolean(
            "/(?i:\\w)/u.test('\u017F') && !/(?i:\\w)/.test('\u017F') && !/\\w/u.test('\u017F')"
        ))
        // 语法错误在求值时抛 SyntaxError
        assertEquals(true, runtime.evalBoolean(
            """
                var bad = ['(?ii:a)', '(?i-i:a)', '(?-:a)', '(?d:a)', '(?i:a']; var ok = true;
                for (var i = 0; i < bad.length; i++) {
                    try { new RegExp(bad[i]); ok = false; } catch (e) { if (!(e instanceof SyntaxError)) ok = false; }
                } ok
            """.trimIndent()
        ))
    }

    @Test
    fun `RegExp 后向断言支持变长与反向捕获语义`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                /(?<=a)b/.test('ab') && !/(?<=a)b/.test('cb') &&
                /(?<!a)b/.test('cb') && !/(?<!a)b/.test('ab') &&
                /(?<=a+)b/.test('aaab') && /(?<=fo*)o/.test('fooo')
            """.trimIndent()
        ))
        // 反向匹配的贪婪与迭代顺序：组内量词从右向左覆盖捕获
        assertEquals(true, runtime.evalBoolean(
            """
                'abcdef'.match(/(?<=(\w){3})def/)[1] === 'a' &&
                'abcdef'.match(/(?<=(\w{2}))def/)[1] === 'bc'
            """.trimIndent()
        ))
        // 备选分支按模式顺序在反向匹配中尝试
        assertEquals(true, runtime.evalBoolean(
            """
                var m = 'xabcd'.match(/.*(?<=(..|...|....))(.*)/);
                m[1] === 'cd' && m[2] === ''
            """.trimIndent()
        ))
        // 嵌套 lookaround 与锚点
        assertEquals(true, runtime.evalBoolean(
            """
                'foo'.match(/^f.o(?<=foo)$/) !== null &&
                'foo'.match(/^f.o(?<!foo)$/) === null &&
                'abcCd'.match(/(?<=\1(\w))d/i)[1] === 'C'
            """.trimIndent()
        ))
        // 自引用数字反向引用按空串匹配
        assertEquals(true, runtime.evalBoolean(
            "/(abc\\1)/.exec('abc')[1] === 'abc'"
        ))
    }

    @Test
    fun `RegExp 重复命名捕获组仅允许互斥分支`() = runTest {
        val runtime = TestRuntime()
        // 同一分支中的重复组名是语法错误；不同备选分支合法
        assertEquals(true, runtime.evalBoolean(
            "try { new RegExp('(?<x>a)(?<x>b)'); false } catch (e) { e instanceof SyntaxError }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "new RegExp('(?<x>a)|(?<x>b)') instanceof RegExp"
        ))
        // groups 取实际参与匹配的候选组
        assertEquals(true, runtime.evalBoolean(
            """
                var m1 = /(?<x>a)|(?<x>b)/.exec('bab');
                var m2 = /(?<x>b)|(?<x>a)/.exec('bab');
                m1[1] === undefined && m1[2] === 'b' && m1.groups.x === 'b' &&
                m2[1] === 'b' && m2.groups.x === 'b'
            """.trimIndent()
        ))
        // 命名反向引用动态指向参与匹配的候选
        assertEquals(true, runtime.evalBoolean(
            """
                /(?:(?<x>a)|(?<x>b))\k<x>/.exec('aa')[0] === 'aa' &&
                /(?:(?<x>a)|(?<x>b))\k<x>/.exec('bb')[0] === 'bb' &&
                /(?:(?<x>a)|(?<x>b))\k<x>/.exec('ab') === null
            """.trimIndent()
        ))
        // d 标志下 indices.groups 对应参与匹配候选的区间
        assertEquals(true, runtime.evalBoolean(
            """
                var ind = /(?<x>a)|(?<x>b)/d.exec('b').indices.groups.x;
                ind[0] === 0 && ind[1] === 1
            """.trimIndent()
        ))
        // groups 属性按首次声明顺序枚举
        assertEquals(true, runtime.evalBoolean(
            """
                var keys = []; var m = /(?<a>x)|(?<b>y)|(?<a>z)/.exec('z');
                for (var k in m.groups) keys.push(k);
                keys.join(',') === 'a,b' && m.groups.a === 'z' && m.groups.b === undefined
            """.trimIndent()
        ))
    }

    @Test
    fun `RegExp 命名捕获组与命名替换`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var m = /(?<year>\d{4})-(?<month>\d{2})/.exec('2024-05');
                Object.getPrototypeOf(m.groups) === null && m.groups.year === '2024' &&
                Object.keys(m.groups).join(',') === 'year,month'
            """.trimIndent()
        ))
        // 无命名组时 groups 为自有 undefined 属性
        assertEquals(true, runtime.evalBoolean(
            "var m = /a/.exec('a'); m.hasOwnProperty('groups') && m.groups === undefined"
        ))
        // 未参与匹配的命名组为 undefined
        assertEquals(true, runtime.evalBoolean(
            "/(?<a>x)?(?<b>y)/.exec('y').groups.a === undefined"
        ))
        // 前向引用、自引用与命名反向引用
        assertEquals(true, runtime.evalBoolean(
            """
                /\k<a>(?<a>x)/.test('x') &&
                /(?<a>\k<a>\w)../.exec('bab').groups.a === 'b' &&
                /(?<b>.).\k<b>/.test('bab')
            """.trimIndent()
        ))
        // $<name> 替换与函数替换参数
        assertEquals("05/2024", runtime.evalString(
            "'2024-05'.replace(/(?<year>\\d{4})-(?<month>\\d{2})/, '$<month>/$<year>')"
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var seen; 'ab'.replace(/(?<first>a)(?<second>b)/, function () {
                    seen = arguments[arguments.length - 1];
                    return seen.first + seen.second;
                }); seen.first === 'a' && seen.second === 'b'
            """.trimIndent()
        ))
        // 无命名组时 $<name> 保持字面量；\\k 为 IdentityEscape
        assertEquals("$<a>", runtime.evalString("'x'.replace(/x/, '$<a>')"))
        assertEquals(true, runtime.evalBoolean("/\\k<a>/.test('k<a>')"))
        // d 标志下 indices.groups
        assertEquals(true, runtime.evalBoolean(
            """
                var ind = /(?<a>.)/d.exec('x').indices;
                ind.groups.a[0] === 0 && ind.groups.a[1] === 1
            """.trimIndent()
        ))
        // 非法组名/悬挂引用在表达式求值时抛 SyntaxError
        assertEquals(true, runtime.evalBoolean(
            "try { new RegExp('(?<>a)'); false } catch (e) { e instanceof SyntaxError }"
        ))
        assertEquals(true, runtime.evalBoolean(
            "try { new RegExp('(?<a>.)\\\\k<b>'); false } catch (e) { e instanceof SyntaxError }"
        ))
        // u 模式禁止 IdentityEscape
        assertEquals(true, runtime.evalBoolean(
            "try { new RegExp('(?<a>\\\\a)', 'u'); false } catch (e) { e instanceof SyntaxError }"
        ))
    }

    @Test
    fun `RegExp d 标志生成 indices 数组与 hasIndices 访问器`() = runTest {
        val runtime = TestRuntime()
        // exec 结果：indices 元素为 [start, end]，未参与匹配的组为 undefined
        assertEquals(true, runtime.evalBoolean(
            """
                var m = /b(c)?/d.exec('abd');
                m.indices.length === m.length && m.indices[0][0] === 1 &&
                m.indices[0][1] === 2 && m.indices[1] === undefined
            """.trimIndent()
        ))
        // indices 与 indices.groups 的属性描述符（CreateDataProperty 语义）
        assertEquals(true, runtime.evalBoolean(
            """
                var m = /a(d)?/d.exec('a');
                var desc = Object.getOwnPropertyDescriptor(m, 'indices');
                desc.writable && desc.enumerable && desc.configurable &&
                m.indices.hasOwnProperty('groups') && m.indices.groups === undefined
            """.trimIndent()
        ))
        // hasIndices 访问器与 flags 的 dgimsuy 顺序
        assertEquals(true, runtime.evalBoolean(
            """
                /a/d.hasIndices === true && /a/.hasIndices === false &&
                new RegExp('', 'yusmigd').flags === 'dgimsuy'
            """.trimIndent()
        ))
        // 无 d 标志不产生 indices；重复标志抛 SyntaxError
        assertEquals(false, runtime.evalBoolean("'indices' in /a/.exec('a')"))
        assertEquals(true, runtime.evalBoolean(
            "try { new RegExp('a', 'dd'); false } catch (e) { e instanceof SyntaxError }"
        ))
    }

    @Test
    fun `BigInt 字面量在解析与求值阶段均可用`() = runTest {
        val runtime = TestRuntime()
        // L3.3 起 BigInt 字面量具备完整运行时语义（此前仅保留语法识别）
        assertEquals("function", runtime.evalString(
            "var f = function () { return 2n; }; typeof f"
        ))
        assertEquals("2", runtime.evalString("String((function () { return 2n; })())"))
        assertEquals("bigint", runtime.evalString("typeof 1n"))
    }

    @Test
    fun `class 静态初始化块按源码顺序执行且 this super 与 new target 语义正确`() = runTest {
        val runtime = TestRuntime()
        // 顺序执行；this 为类构造器；super 基为父构造器；new.target 为 undefined
        assertEquals(true, runtime.evalBoolean(
            """
                var order = [];
                var Base = function () {};
                Base.value = 42;
                class C extends Base {
                    static { order.push('first'); this.own = 1; }
                    static { order.push(super.value); }
                    static { order.push(new.target === undefined ? 'target-undefined' : 'target'); }
                    static {}
                }
                order.join(',') === 'first,42,target-undefined' && C.own === 1
            """.trimIndent()
        ))
        // 每个块拥有独立的函数级作用域，var 不外泄；块内可见类名
        assertEquals(true, runtime.evalBoolean(
            """
                var captured;
                class D { static { var inner = 'block'; captured = D.name; } }
                captured === 'D' && typeof inner === 'undefined' && typeof D.static === 'undefined'
            """.trimIndent()
        ))
        // 块内抛出异常会中止类求值并向外传播
        assertEquals("boom", runtime.evalString(
            """
                var message;
                try { class E { static { throw 'boom'; } } } catch (e) { message = e; }
                message
            """.trimIndent()
        ))
    }

    @Test
    fun `class 静态初始化块的 arguments await return 与 super 调用限制`() = runTest {
        val runtime = TestRuntime()
        // arguments 引用与绑定在块内非法（箭头不解除），但普通函数/方法内合法
        assertFailsWith<ParseException> { runtime.eval("class C { static { arguments; } }") }
        assertFailsWith<ParseException> { runtime.eval("class C { static { (() => arguments); } }") }
        assertFailsWith<ParseException> { runtime.eval("class C { static { (x = arguments) => x; } }") }
        assertFailsWith<ParseException> { runtime.eval("class C { static { class A { [arguments]() {} } } }") }
        assertEquals(true, runtime.evalBoolean(
            """
                var seen;
                class C1 { static { (function () { seen = arguments; })('ok'); } }
                seen.length === 1 && seen[0] === 'ok'
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var methodArgs;
                class C2 { static { var o = { m: function () { methodArgs = arguments; } }; o.m(1, 2); } }
                methodArgs.length === 2
            """.trimIndent()
        ))
        // await 在块内非法；绑定名 await 在块内非法，但在箭头函数体内恢复
        assertFailsWith<ParseException> { runtime.eval("class C { static { await 0; } }") }
        assertFailsWith<ParseException> {
            runtime.eval("async function f() { class C { static { await 0; } } }")
        }
        assertFailsWith<ParseException> { runtime.eval("class C { static { class await {} } }") }
        assertFailsWith<ParseException> { runtime.eval("class C { static { (await) => 0; } }") }
        runtime.eval("class C3 { static { (() => { class await {} }); } }")
        // return 在块内非法，嵌套函数内合法；super() 调用非法
        assertFailsWith<ParseException> {
            runtime.eval("function f() { class C { static { return; } } }")
        }
        assertFailsWith<ParseException> { runtime.eval("class C extends Object { static { super(); } }") }
        assertEquals(true, runtime.evalBoolean(
            "class C4 { static { var f = function () { return 1; }; this.v = f(); } } C4.v === 1"
        ))
        // arguments 可作为标签（标签不是标识符引用）
        assertEquals(true, runtime.evalBoolean(
            """
                var count = 0;class C5 { static { arguments: for (;;) { count++; break arguments; } } }count === 1
            """.trimIndent()
        ))
    }

    @Test
    fun `RegExp v 标志支持扩展字符类的并集交集差集与嵌套`() = runTest {
        val runtime = TestRuntime()
        // 交集与差集可嵌套普通字符类
        assertEquals(true, runtime.evalBoolean(
            """
                /^[[a-z]&&[^aeiou]]$/v.test('b') && !/^[[a-z]&&[^aeiou]]$/v.test('a') &&
                /^[[a-z]--[aeiou]]$/v.test('z') && !/^[[a-z]--[aeiou]]$/v.test('e') &&
                /^[[a-c]&&[b-d]]$/v.test('b') && !/^[[a-c]&&[b-d]]$/v.test('a')
            """.trimIndent()
        ))
        // 集合转义、属性转义、否定类与 astral 码点
        assertEquals(true, runtime.evalBoolean(
            """
                /^[\d&&[0-4]]+$/v.test('1234') && !/^[\d&&[0-4]]+$/v.test('5') &&
                /^[^a]$/v.test('b') && !/^[^a]$/v.test('a') &&
                /^[[\u{1F600}-\u{1F64F}]--[\u{1F610}]]$/v.test('\u{1F601}') &&
                !/^[[\u{1F600}-\u{1F64F}]--[\u{1F610}]]$/v.test('\u{1F610}')
            """.trimIndent()
        ))
        // 转义的语法字符与属性集合交集
        assertEquals(true, runtime.evalBoolean(
            """
                /^[\-\{\}\(\)\/\|]+$/v.test('-{}()/|') &&
                /^[\p{ASCII}&&\p{Letter}]$/v.test('A') &&
                /^[a&b]$/v.test('&')
            """.trimIndent()
        ))
        // 标志、访问器与 u/v 互斥
        assertEquals(true, runtime.evalBoolean(
            """
                var r = /a/v; r.unicodeSets === true && r.unicode === false &&
                r.flags === 'v' && new RegExp('a', 'v').unicodeSets === true
            """.trimIndent()
        ))
        assertFailsWith<LexerException> { runtime.eval("/a/uv;") }
        assertFailsWith<LexerException> { runtime.eval("/[]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[^]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[a-]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[-a]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[a&&b&&c]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[a&&b--c]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[!!]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[a-z&&b]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[a&&b-c]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[(]/v;") }
        assertFailsWith<JSException> { runtime.eval("new RegExp('[]', 'v')") }
    }

    @Test
    fun `严格模式尾调用在深递归下不增长调用栈`() = runTest {
        val runtime = TestRuntime()
        assertEquals(0.0, runtime.evalNumber(
            "(function f(n) { 'use strict'; if (n === 0) return 0; return f(n - 1); })(4000)"
        ))
        // 互递归与条件/逻辑/逗号表达式中的尾位置
        assertEquals(1.0, runtime.evalNumber(
            """
                var count = 0;
                function even(n) { 'use strict'; if (n === 0) { count++; return 0; } return odd(n - 1); }
                function odd(n) { 'use strict'; if (n === 0) { count++; return 0; } return even(n - 1); }
                even(4000); count
            """.trimIndent()
        ))
        assertEquals(1.0, runtime.evalNumber(
            """
                (function f(n) { 'use strict'; if (n === 0) return 1;
                    return true ? f(n - 1) : 0; })(3)
            """.trimIndent()
        ))
        // 直接 eval 的判定基于内建 %eval%，全局 eval 被覆盖后调用新函数
        assertEquals(3.0, runtime.evalNumber(
            """
                var originalEval = eval; eval = function () { return 1; };
                originalEval('1 + 1') + eval(0)
            """.trimIndent()
        ))
    }

    @Test
    fun `标识符按 Unicode 17 表支持 astral 字面量与转义`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("var \\u{1D49C} = 1; \\u{1D49C}"))
        assertEquals(2.0, runtime.evalNumber("var 𝒜 = 2; 𝒜"))
        assertEquals(3.0, runtime.evalNumber("var a\\u{1E4D0} = 3; a\\u{1E4D0}"))
        // 非 ID_Continue 字符不能参与标识符
        assertFailsWith<LexerException> { runtime.eval("var a\\u{00A0} = 1;") }
    }

    @Test
    fun `RegExp v 标志支持属性字符串与 q 字符串析取`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                /^\p{Basic_Emoji}$/v.test('\u231A') && !/^\p{Basic_Emoji}$/v.test('A') &&
                /^\p{RGI_Emoji}$/v.test('\u{1F1E7}\u{1F1EA}') &&
                /^\p{RGI_Emoji}$/v.test('\u{1F468}\u200D\u{1F469}\u200D\u{1F467}')
            """.trimIndent()
        ))
        // \q 析取、空串与集合运算
        assertEquals(true, runtime.evalBoolean(
            """
                /^[\q{a|ab}]$/v.test('ab') && /^[\q{a|ab}]$/v.test('a') &&
                /^[\q{}]$/v.test('') &&
                /^[\d--\q{0|2|4|9\uFE0F\u20E3}]+$/v.test('13579') &&
                !/^[\d--\q{0|2|4|9\uFE0F\u20E3}]+$/v.test('0') &&
                /^[\q{ab}&&\q{ab|cd}]$/v.test('ab') &&
                !/^[\q{ab}&&\q{ab|cd}]$/v.test('cd')
            """.trimIndent()
        ))
        // 否定类与 \P 不允许包含字符串；u 模式不允许属性字符串
        assertFailsWith<LexerException> { runtime.eval("/[^\\q{ab}]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/\\P{Basic_Emoji}/v;") }
        assertFailsWith<LexerException> { runtime.eval("/[\\P{RGI_Emoji}]/v;") }
        assertFailsWith<LexerException> { runtime.eval("/\\p{Basic_Emoji}/u;") }
    }

    @Test
    fun `Unicode 属性转义支持二进制属性 通用类别 脚本与码点级匹配`() = runTest {
        val runtime = TestRuntime()
        // 字母属性跨 BMP 与 astral 码点，非字母不匹配
        assertEquals(true, runtime.evalBoolean(
            """
                /\p{Letter}/u.test('A') && /\p{L}/u.test('\u00E9') &&
                /\p{Letter}/u.test('\u{10400}') && !/\p{Letter}/u.test('0')
            """.trimIndent()
        ))
        // 通用类别支持短别名；i 模式对有效集合做大小写折叠（含 \P 的补集）
        assertEquals(true, runtime.evalBoolean(
            "/\\p{General_Category=Uppercase_Letter}/u.test('A')"
        ), "General_Category=Uppercase_Letter")
        assertEquals(true, runtime.evalBoolean("!/\\p{gc=Lu}/u.test('a')"), "gc=Lu")
        assertEquals(true, runtime.evalBoolean("/\\p{Lu}/iu.test('a')"), "Lu+iu")
        assertEquals(true, runtime.evalBoolean("/\\P{Lu}/iu.test('A')"), "PLu+iu")
        assertEquals(true, runtime.evalBoolean("!/\\p{ASCII}/iu.test('\\u00E9')"), "ASCII+iu")
        // 脚本与 Script_Extensions 值别名；\P 取补集并匹配完整码点
        assertEquals(true, runtime.evalBoolean(
            """
                /\p{Script=Greek}/u.test('\u03B1') && !/\p{Script=Greek}/u.test('a') &&
                /\p{scx=Latn}/u.test('a') && /\P{ASCII}/u.test('\u{1F600}')
            """.trimIndent()
        ))
        // Emoji、ASCII、Any、Assigned 等二进制属性
        assertEquals(true, runtime.evalBoolean(
            """
                /\p{Emoji}/u.test('\u{1F600}') && /\p{ASCII}/u.test('\u007F') &&
                !/\p{ASCII}/u.test('\u0080') && /\p{Any}/u.test('\u{10FFFF}') &&
                /\p{Assigned}/u.test('A') && !/\p{Assigned}/u.test('\u{10FFFF}')
            """.trimIndent()
        ))
        // 字符类内组合属性与补集，末尾连字符按字面量处理
        assertEquals(true, runtime.evalBoolean(
            "/[\\p{Hex}\\P{Hex}]/u.test('\\u{1D306}') && /[\\p{L}-]/u.test('-')"
        ))
        // 非法属性名、带值的二进制属性、空白与字符类范围端点均为早期错误
        assertFailsWith<LexerException> { runtime.eval("/\\p{NoSuchProperty}/u;") }
        assertFailsWith<LexerException> { runtime.eval("/\\p{ASCII=F}/u;") }
        assertFailsWith<LexerException> { runtime.eval("/\\p{ Letter }/u;") }
        assertFailsWith<LexerException> { runtime.eval("/\\p{General_Category}/u;") }
        assertFailsWith<LexerException> { runtime.eval("/[\\p{L}-z]/u;") }
        assertFailsWith<LexerException> { runtime.eval("/[z-\\p{L}]/u;") }
        // 构造器路径同样校验，并在运行时抛出 SyntaxError
        assertEquals(true, runtime.evalBoolean(
            "new RegExp('\\\\p{Letter}', 'u').test('A')"
        ))
        assertFailsWith<JSException> { runtime.eval("new RegExp('\\\\p{NoSuch}', 'u')") }
        // 非 u 模式下 \p 是 IdentityEscape：整体按字面量 p{L} 匹配
        assertEquals(true, runtime.evalBoolean("/\\p{L}/.test('p{L}')"))
    }

    @Test
    fun `class 构造器原型描述符 静态 prototype 限制与类名 TDZ`() = runTest {
        val runtime = TestRuntime()
        // class 构造器的 prototype 是不可写属性
        assertEquals(false, runtime.evalBoolean(
            "class A {}; Object.getOwnPropertyDescriptor(A, 'prototype').writable"
        ))
        // 构造器内部 [[Prototype]] 指向父构造器，原型对象 [[Prototype]] 指向父原型
        assertEquals(true, runtime.evalBoolean(
            """
                class Base {} class Derived extends Base {}
                Object.getPrototypeOf(Derived) === Base &&
                Object.getPrototypeOf(Derived.prototype) === Base.prototype
            """.trimIndent()
        ))
        // 静态成员不得命名为 prototype：字面量键是早期错误，计算键是运行时 TypeError
        assertFailsWith<ParseException> {
            runtime.eval("class BadLiteral { static prototype() {} }")
        }
        assertEquals("TypeError", runtime.evalString(
            "try { class BadComputed { static ['prototype']() {} } 'none' } catch (e) { e.name }"
        ))
        // Get(superclass, 'prototype') 必须为对象或 null
        assertEquals("TypeError", runtime.evalString(
            """
                function F() {} F.prototype = 1;
                try { class G extends F {} 'none' } catch (e) { e.name }
            """.trimIndent()
        ))
        // 类名在 heritage 求值时处于 TDZ
        assertEquals("ReferenceError", runtime.evalString(
            "try { class Self extends Self {} 'none' } catch (e) { e.name }"
        ))
        // class 声明的绑定可重新赋值
        assertEquals(true, runtime.evalBoolean("class Mutable {} Mutable = 5; Mutable === 5"))
    }

    @Test
    fun `super 属性读取使用实际 this 且构造器 super 调用受 this 限制`() = runTest {
        val runtime = TestRuntime()
        // super getter 的 this 为实例
        assertEquals(42.0, runtime.evalNumber(
            """
                class Base { get value() { return this.stored; } }
                class Sub extends Base {
                    constructor() { super(); this.stored = 42; }
                    read() { return super.value; }
                } new Sub().read()
            """.trimIndent()
        ))
        // 派生构造器在 super() 前访问 super 属性抛 ReferenceError
        val restricted = TestRuntime()
        assertEquals("ReferenceError", restricted.evalString(
            """
                class Base { static m() {} }
                class Sub extends Base { constructor() { super.m(); super(); } }
                try { new Sub(); 'none' } catch (e) { e.name }
            """.trimIndent()
        ))
    }

    @Test
    fun `块级函数声明按块作用域实例化并遵循 sloppy Annex B`() = runTest {
        val runtime = TestRuntime()
        // sloppy：块外 var 绑定在块执行前为 undefined，块执行后指向块级函数
        assertEquals(true, runtime.evalBoolean(
            """
                var before = typeof hoisted;
                { function hoisted() { return 7; } }
                before === 'undefined' && hoisted() === 7
            """.trimIndent()
        ))
        // 块级函数闭包捕获块环境（可访问块内 let）
        assertEquals(3.0, runtime.evalNumber(
            """
                var result;
                function outer() { var x = 1; { let y = 2; function inner() { return x + y; } result = inner(); } }
                outer(); result
            """.trimIndent()
        ))
        // sloppy 下同名块级函数后者生效
        assertEquals(2.0, runtime.evalNumber(
            "var value; { function pick() { return 1; } function pick() { return 2; } value = pick(); } value"
        ))
        // strict：块级函数不外泄到外围作用域
        assertEquals("ReferenceError", runtime.evalString(
            "'use strict'; try { { function hidden() {} } hidden; 'none' } catch (e) { e.name }"
        ))
    }

    @Test
    fun `with 环境中的调用接收者与 var 初始化引用解析`() = runTest {
        val runtime = TestRuntime()
        // 标识符调用以 with 绑定对象作为 this
        assertEquals(true, runtime.evalBoolean(
            """
                var obj = { m: function () { return this === obj; } };
                var callResult; with (obj) { callResult = m(); } callResult
            """.trimIndent()
        ))
        // var 初始化先解析引用再求值初始化器
        assertEquals(true, runtime.evalBoolean(
            """
                var holder = { test262id: 1 };
                with (holder) { var test262id = delete holder.test262id; }
                holder.test262id === true && typeof test262id === 'undefined'
            """.trimIndent()
        ))
    }

    @Test
    fun `标签模板保留成员接收者并支持 new 构造链`() = runTest {
        val runtime = TestRuntime()
        // obj.method`...` 的 this 为 obj
        assertEquals(3.0, runtime.evalNumber(
            "var o = { v: 3, f: function () { return this.v; } }; o.f`x`"
        ))
        // new tag`...` 构造的是标签返回的函数
        assertEquals(true, runtime.evalBoolean(
            """
                function C(x) { this.arg = x; }
                var tag = function () { return C; };
                var instance = new tag`t`('a');
                instance instanceof C && instance.arg === 'a'
            """.trimIndent()
        ))
    }

    @Test
    fun `模板字面量行延续的 TRV 保留反斜杠与 U2028`() = runTest {
        val runtime = TestRuntime()
        // 源码中构造 `\` + U+2028 + 反引号：cooked 为空串，raw 为反斜杠加行分隔符
        val source = "(function (cs) {" +
            " return cs[0] === '' && cs.raw[0].charCodeAt(0) === 0x5C &&" +
            " cs.raw[0].charCodeAt(1) === 0x2028; })`\\" + "\u2028" + "`"
        assertEquals(true, runtime.evalBoolean(source))
    }

    @Test
    fun `条件表达式 +In 分支可用于普通 for 初始化`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var condCount = 0; var elseCount = 0;
                function makeObject() { condCount++; return {}; }
                function makeElse() { elseCount++; }
                for (true ? '' in makeObject() : makeElse(); false; ) { }
                condCount === 1 && elseCount === 0
            """.trimIndent()
        ))
    }

    @Test
    fun `非简单参数列表允许函数体词法声明同名且保留 arguments 对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var captured;
                function f(x = (captured = arguments, 1)) { let arguments; }
                f(); typeof captured === 'object' && captured.length === 0
            """.trimIndent()
        ))
    }

    @Test
    fun `catch 参数同名 var 的 for-in 写入更新 catch 绑定`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            """
                var before, during, after;
                try { throw 'exception'; } catch (err) {
                    before = err;
                    for (var err in { propertyName: null }) { during = err; }
                    after = err;
                }
                before === 'exception' && during === 'propertyName' && after === 'propertyName'
            """.trimIndent()
        ))
    }

    @Test
    fun `全局词法声明限制与 eval 全局声明可定义性检查`() = runTest {
        // 全局 let 不得遮蔽不可配置的全局属性（GlobalDeclarationInstantiation）
        val restricted = TestRuntime()
        val restrictedError = assertFailsWith<JSException> { restricted.eval("let undefined;") }
        val restrictedValue = restrictedError.value
        assertEquals(
            true,
            restrictedValue is com.muedsa.js.runtime.value.JSError &&
                restrictedValue.errorName == "SyntaxError"
        )
        // 非扩展全局对象上间接 eval 的 var 声明抛 TypeError
        val nonExtensible = TestRuntime()
        assertEquals("TypeError", nonExtensible.evalString(
            """
                Object.preventExtensions(globalThis);
                try { (0, eval)('var unlikelyVariableName;'); 'none' } catch (e) { e.name }
            """.trimIndent()
        ))
        // 函数声明与不可配置全局函数冲突时先抛 TypeError，且不创建后续 var 绑定
        val evalRuntime = TestRuntime()
        assertEquals(true, evalRuntime.evalBoolean(
            """
                try { eval('var shouldNotBeDefined; function NaN() {}'); } catch (e) { }
                Object.getOwnPropertyDescriptor(this, 'shouldNotBeDefined') === undefined
            """.trimIndent()
        ))
    }

    @Test
    fun `String prototype normalize 支持四种规范化形式`() = runTest {
        val runtime = TestRuntime()
        // NFD 完全分解；NFC 重新组合；NFKC/NFKD 使用兼容分解
        assertEquals(true, runtime.evalBoolean(
            """
                var s = '\u00C5\u2126';
                s.normalize('NFD') === 'A\u030A\u03A9' &&
                s.normalize('NFC') === '\u00C5\u03A9'
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                '\u1E9B\u0323'.normalize('NFKD') === 's\u0323\u0307' &&
                '\u1E9B\u0323'.normalize('NFKC') === '\u1E69'
            """.trimIndent()
        ))
        // 非法形式抛 RangeError；this 先做 ToString
        assertEquals("RangeError", runtime.evalString(
            "try { 'foo'.normalize('bar'); 'none' } catch (e) { e.name }"
        ))
        assertEquals("Test262Error", runtime.evalString(
            """
                var o = { toString: function () { var e = new Error('x'); e.name = 'Test262Error'; throw e; } };
                try { String.prototype.normalize.call(o); 'none' } catch (e) { e.name }
            """.trimIndent()
        ))
    }

    @Test
    fun `跨脚本全局声明实例化冲突与绑定描述符`() = runTest {
        // 先前脚本的 var/function 属性不可配置，后续 let 同名声明抛 SyntaxError
        val lexAfterVar = TestRuntime()
        lexAfterVar.eval("var conflictVar = 1;")
        val lexError = assertFailsWith<JSException> { lexAfterVar.eval("let conflictVar;") }
        assertEquals(
            true,
            lexError.value is com.muedsa.js.runtime.value.JSError &&
                lexError.value.errorName == "SyntaxError"
        )
        // 先前脚本的 let 绑定，后续 var/function 声明同样抛 SyntaxError
        val varAfterLex = TestRuntime()
        varAfterLex.eval("let conflictLet;")
        assertEquals(
            true,
            assertFailsWith<JSException> { varAfterLex.eval("var conflictLet;") }
                .let { it.value is com.muedsa.js.runtime.value.JSError && it.value.errorName == "SyntaxError" }
        )
        assertEquals(
            true,
            assertFailsWith<JSException> { varAfterLex.eval("function conflictLet() {}") }
                .let { it.value is com.muedsa.js.runtime.value.JSError && it.value.errorName == "SyntaxError" }
        )
        // 可配置的同名全局属性被函数声明按 CreateGlobalFunctionBinding 重定义
        val functionBinding = TestRuntime()
        functionBinding.eval(
            """
                Object.defineProperty(this, 'redeclared',
                { value: 0, writable: true, enumerable: false, configurable: true });
            """.trimIndent()
        )
        assertEquals(true, functionBinding.evalBoolean(
            """
                function redeclared() { return 1; }
                var desc = Object.getOwnPropertyDescriptor(this, 'redeclared');
                typeof redeclared === 'function' && desc.writable === true &&
                desc.enumerable === true && desc.configurable === false
            """.trimIndent()
        ))
        // 不可配置但可写且可枚举的同名属性只更新值，描述符保持不变
        functionBinding.eval(
            """
                Object.defineProperty(this, 'kept',
                { value: 0, writable: true, enumerable: true, configurable: false });
            """.trimIndent()
        )
        assertEquals(true, functionBinding.evalBoolean(
            """
                function kept() { return 2; }
                var keptDesc = Object.getOwnPropertyDescriptor(this, 'kept');
                kept() === 2 && keptDesc.writable === true &&
                keptDesc.enumerable === true && keptDesc.configurable === false
            """.trimIndent()
        ))
        // 不可配置且不可枚举的属性拒绝函数声明（CanDeclareGlobalFunction=false）
        functionBinding.eval(
            """
                Object.defineProperty(this, 'rejected',
                { value: 0, writable: true, enumerable: false, configurable: false });
            """.trimIndent()
        )
        assertEquals(
            true,
            assertFailsWith<JSException> { functionBinding.eval("function rejected() {}") }
                .let {
                    it.value is com.muedsa.js.runtime.value.JSError &&
                        it.value.errorName == "TypeError"
                }
        )
        // 间接 eval 创建的全局 var 按 D=true 可删除
        val evalBinding = TestRuntime()
        assertEquals(true, evalBinding.evalBoolean(
            """
                (0, eval)('var evalCreated = 1;');
                delete globalThis.evalCreated === true && typeof evalCreated === 'undefined'
            """.trimIndent()
        ))
    }

    @Test
    fun `Date UTC 默认参数 双精度运算与 TimeClip`() = runTest {
        val runtime = TestRuntime()
        assertEquals(0.0, runtime.evalNumber("Date.UTC(1970)"))
        assertEquals(1467331200000.0, runtime.evalNumber("Date.UTC(2016, 6)"))
        // 非有限参数经 MakeDay/MakeTime 返回 NaN，结果再经 TimeClip 越界返回 NaN
        assertEquals(true, runtime.evalBoolean(
            """
                Number.isNaN(Date.UTC(Infinity, 0)) &&
                Number.isNaN(Date.UTC(2016, 0, 1, Infinity)) &&
                Number.isNaN(Date.UTC(275760, 8, 13, 0, 0, 0, 1))
            """.trimIndent()
        ))
        // 大数值按 IEEE 754 双精度顺序运算
        assertEquals(
            29312.0,
            runtime.evalNumber(
                "Date.UTC(1970, 0, 1, 80063993375, 29, 1, -288230376151711740)"
            )
        )
        assertEquals(
            34447360.0,
            runtime.evalNumber(
                "Date.UTC(1970, 0, 213503982336, 0, 0, 0, -18446744073709552000)"
            )
        )
    }

    @Test
    fun `Date 设置器的无效日期与参数转换顺序`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean(
            "var d = new Date(NaN); Number.isNaN(d.setDate(1)) && Number.isNaN(d.getTime())"
        ))
        // 内部时间无效也要先完成参数的 ToNumber（副作用可见）
        assertEquals(true, runtime.evalBoolean(
            """
                var called = false;
                var arg = { valueOf: function () { called = true; return 1; } };
                new Date(NaN).setMonth(arg); called
            """.trimIndent()
        ))
        // setFullYear 在无效日期上以 +0 为基准
        assertEquals(2020.0, runtime.evalNumber(
            "var e = new Date(NaN); e.setFullYear(2020); e.getUTCFullYear()"
        ))
        assertEquals(true, runtime.evalBoolean("Number.isNaN(new Date(NaN).setFullYear(Infinity))"))
    }

    @Test
    fun `Date 解析 toString 往返 扩展年份与取值范围`() = runTest {
        val runtime = TestRuntime()
        assertEquals(0.0, runtime.evalNumber("Date.parse(new Date(0).toString())"))
        assertEquals(0.0, runtime.evalNumber("Date.parse(new Date(0).toUTCString())"))
        // 扩展年份 0 必须带 + 号；超出 ±8.64e15 的解析结果为 NaN
        assertEquals(true, runtime.evalBoolean(
            """
                Number.isNaN(Date.parse('-000000-03-31T00:45Z')) &&
                Number.isNaN(Date.parse('-271821-04-19T23:59:59.999Z')) &&
                Date.parse('-271821-04-20T00:00:00.000Z') === -8640000000000000
            """.trimIndent()
        ))
        // 24:00 仅在分秒毫秒为 0 时顺延次日；小数秒超过 3 位按截断解析；
        // 时区偏移超出 23:59 视为非法
        assertEquals(true, runtime.evalBoolean(
            """
                Date.parse('2020-01-01T24:00:00Z') === Date.UTC(2020, 0, 2) &&
                Date.parse('2020-01-01T24:00Z') === Date.UTC(2020, 0, 2) &&
                Number.isNaN(Date.parse('2020-01-01T24:00:00.001Z')) &&
                Number.isNaN(Date.parse('2020-01-01T24:00:00.0009Z')) &&
                Number.isNaN(Date.parse('2020-01-01T24:01:00Z')) &&
                Date.parse('2020-01-01T00:00:00.123456789Z') === Date.UTC(2020, 0, 1) + 123 &&
                Number.isNaN(Date.parse('2020-01-01T00:00:00.Z')) &&
                Number.isNaN(Date.parse('2020-01-01T00:00:00+24:00')) &&
                Number.isNaN(Date.parse('2020-01-01T00:00:00+00:60'))
            """.trimIndent()
        ))
    }

    @Test
    fun `String 大小写使用 Unicode 17 完整映射与词尾 Sigma 规则`() = runTest {
        val runtime = TestRuntime()
        // SpecialCasing 完整映射：ß → SS、连字 ﬁ → FI、İ 小写为 i + 组合点
        assertEquals("SS", runtime.evalString("'ß'.toUpperCase()"))
        assertEquals("SS", runtime.evalString("'ß'.toLocaleUpperCase()"))
        assertEquals("FI", runtime.evalString("'ﬁ'.toUpperCase()"))
        assertEquals("i̇", runtime.evalString("'İ'.toLowerCase()"))
        // Unicode 17 新增/修正的大小写配对（V8 的 Unicode 16 表尚缺 A7CE/A7D2/A7D4 三组）
        assertEquals("Ꟁ", runtime.evalString("'ꟁ'.toUpperCase()"))
        assertEquals("ꟁ", runtime.evalString("'Ꟁ'.toLowerCase()"))
        assertEquals("꟎", runtime.evalString("'꟏'.toUpperCase()"))
        assertEquals("꟏", runtime.evalString("'꟎'.toLowerCase()"))
        assertEquals("꟒", runtime.evalString("'ꟓ'.toUpperCase()"))
        assertEquals("ꟓ", runtime.evalString("'꟒'.toLowerCase()"))
        assertEquals("꟔", runtime.evalString("'ꟕ'.toUpperCase()"))
        assertEquals("ꟕ", runtime.evalString("'꟔'.toLowerCase()"))
        // 辅助平面（Deseret）按码点转换，不拆散代理对
        assertEquals("𐐀", runtime.evalString("'𐐨'.toUpperCase()"))
        assertEquals("𐐨", runtime.evalString("'𐐀'.toLowerCase()"))
        // Final_Sigma：前方需有 Cased（可隔 Case_Ignorable），后方不得再有 Cased
        assertEquals("ας", runtime.evalString("'ΑΣ'.toLowerCase()"))
        assertEquals("ασα", runtime.evalString("'ΑΣΑ'.toLowerCase()"))
        assertEquals("σ", runtime.evalString("'Σ'.toLowerCase()"))
        assertEquals("ας.", runtime.evalString("'ΑΣ.'.toLowerCase()"))
        assertEquals("άς", runtime.evalString("'ΆΣ'.toLowerCase()"))
        assertEquals("άσα", runtime.evalString("'ΆΣΑ'.toLowerCase()"))
        assertEquals("́σ", runtime.evalString("'́Σ'.toLowerCase()"))
        // 非 Case_Ignorable 的符号会阻止词尾判定
        assertEquals("a😀σ", runtime.evalString("'A😀Σ'.toLowerCase()"))
        // U+0345 同时是 Cased 与 Case_Ignorable，在前后扫描中按可忽略处理
        assertEquals("ͅσ", runtime.evalString("'ͅΣ'.toLowerCase()"))
        assertEquals("ᾳς", runtime.evalString("'ᾼΣ'.toLowerCase()"))
        assertEquals("aςͅ", runtime.evalString("'AΣͅ'.toLowerCase()"))
        assertEquals("aσͅα", runtime.evalString("'AΣͅΑ'.toLowerCase()"))
    }

    @Test
    fun `Number 字符串转换严格匹配 StrDecimalLiteral`() = runTest {
        val runtime = TestRuntime()
        // 平台 double 解析会接受 JVM 类型后缀（8d）等，必须按 JS 语法整体校验
        assertEquals(true, runtime.evalBoolean(
            """
                Number.isNaN(Number('8d')) && Number.isNaN(Number('8f')) &&
                Number.isNaN(Number('1e')) && Number.isNaN(Number('.e3')) &&
                Number.isNaN(Number('1_000')) && Number.isNaN(Number('1.2.3')) &&
                Number.isNaN(Number('+0x10')) && Number.isNaN(Number('-0b1')) &&
                Number('1.e3') === 1000 && Number('.5') === 0.5 && Number('+1') === 1 &&
                Number(' 0x10 ') === 16 && Number('Infinity') === Infinity &&
                Number('-Infinity') === -Infinity && Object.is(Number('-0'), -0)
            """.trimIndent()
        ))
    }

    @Test
    fun `Array 泛型方法遵循原型链 稀疏与数组型对象语义`() = runTest {
        val runtime = TestRuntime()
        // pop 通过 Get 读取原型链元素，并删除自有属性
        assertEquals(true, runtime.evalBoolean(
            """
                Array.prototype[1] = 1; var x = [0]; x.length = 2;
                var popped = x.pop(); delete Array.prototype[1];
                popped === 1 && x.length === 1 && !x.hasOwnProperty('1')
            """.trimIndent()
        ), "pop-prototype")

        // shift 使用 Get(0)，空洞搬移时保持空洞
        assertEquals(true, runtime.evalBoolean(
            """
                var a = [1, , 3]; var first = a.shift();
                first === 1 && a.length === 2 && !a.hasOwnProperty('0') && a[1] === 3
            """.trimIndent()
        ), "shift-holes")

        // 不可写 length 的 pop 在写入 length 时抛 TypeError
        assertEquals("TypeError", runtime.evalString(
            """
                var b = [1]; Object.defineProperty(b, 'length', { writable: false });
                try { b.pop(); 'none' } catch (e) { e.name }
            """.trimIndent()
        ))
        // fill/copyWithin 返回 ToObject(this)，且支持数组型对象
        assertEquals(true, runtime.evalBoolean(
            """
                var o = { length: 2 }; var filled = Array.prototype.fill.call(o, 7);
                var p = { 0: 1, 1: 2, length: 2 };
                var copied = Array.prototype.copyWithin.call(p, 0, 1);
                filled === o && o[0] === 7 && o[1] === 7 && copied === p && p[0] === 2
            """.trimIndent()
        ), "fill-copy-return")

        // 基元 this 被包装为对象
        assertEquals(true, runtime.evalBoolean(
            """
                var boxed = Array.prototype.fill.call(true, 1);
                (boxed instanceof Boolean) && boxed.length === undefined
            """.trimIndent()
        ), "fill-primitive")

        // find 对空洞也调用谓词；indexOf 在 length 为 0 时不读取 fromIndex
        assertEquals(true, runtime.evalBoolean(
            """
                var called = 0; [, , 'x'].find(function () { called++; });
                var probed = false;
                var arg = { valueOf: function () { probed = true; return 1; } };
                [].indexOf(1, arg); called === 3 && !probed
            """.trimIndent()
        ), "find-holes-indexof")

        // splice 删除不可配置元素抛 TypeError；被删除元素可来自原型链
        assertEquals(true, runtime.evalBoolean(
            """
                var c = [1, 2]; Object.defineProperty(c, '1', { configurable: false });
                var threw = false; try { c.splice(1); } catch (e) { threw = e instanceof TypeError; }
                Array.prototype[1] = 'p'; var d = [0]; d.length = 2;
                var removed = d.splice(1); delete Array.prototype[1];
                threw && removed.length === 1 && removed[0] === 'p' && d.length === 1
            """.trimIndent()
        ), "splice-delete")

    }

    @Test
    fun `Number toPrecision 非有限值与负零格式化`() = runTest {
        val runtime = TestRuntime()
        // 规范顺序：非有限值在精度范围检查之前返回 ToString(x)
        assertEquals("Infinity", runtime.evalString("(Infinity).toPrecision(1000)"))
        assertEquals("-Infinity", runtime.evalString("(-Infinity).toPrecision(1000)"))
        assertEquals("NaN", runtime.evalString("NaN.toPrecision(2)"))
        // ±0 统一输出正零格式
        assertEquals("0.0", runtime.evalString("(-0).toPrecision(2)"))
        assertEquals("0", runtime.evalString("(-0).toPrecision(1)"))
    }

    @Test
    fun `Number toExponential 与 toPrecision 按精确十进制值舍入`() = runTest {
        val runtime = TestRuntime()
        // 123.456 的精确值为 123.4560000000000030695...，低位必须基于精确展开舍入
        assertEquals("1.235e+2", runtime.evalString("(123.456).toExponential(3)"))
        assertEquals("1.23456000000000003e+2", runtime.evalString("(123.456).toExponential(17)"))
        assertEquals("1.23456000000000003070e+2", runtime.evalString("(123.456).toExponential(20)"))
        // 舍入进位导致最高位溢出时提升指数
        assertEquals("1.00e+0", runtime.evalString("(0.9999).toExponential(2)"))
        assertEquals("3e+1", runtime.evalString("(25).toExponential(0)"))
        // 极小值同样使用精确展开（0.0001 的双精度值略大于 1e-4）
        assertEquals("1.00000000000000005e-4", runtime.evalString("(0.0001).toExponential(17)"))
        assertEquals(
            "9.999999999999999e-22",
            runtime.evalString("(0.000000000000000000001).toPrecision(16)")
        )
        // toPrecision 有效数字 4 位以上走精确路径
        assertEquals("1.234e+27", runtime.evalString("(1.2345e+27).toPrecision(4)"))
        assertEquals("1.23449999999999996e+27", runtime.evalString("(1.2345e+27).toPrecision(18)"))
        // 次正规数在 0..2 位小数时同样必须使用精确展开（近似路径会下溢为 Infinity）
        assertEquals("5e-324", runtime.evalString("(5e-324).toExponential(0)"))
        assertEquals("4.9e-324", runtime.evalString("(5e-324).toExponential(1)"))
        // 未指定 fractionDigits 时使用最短往返表示
        assertEquals("1.23456e+2", runtime.evalString("(123.456).toExponential()"))
        assertEquals("5e-324", runtime.evalString("(5e-324).toExponential()"))
        // 指数形式的选择取决于舍入后的十进制指数（1e-6 在 17 位时进位消失）
        assertEquals(
            "0.000001000000000000000",
            runtime.evalString("(0.000001).toPrecision(16)")
        )
        assertEquals(
            "9.9999999999999995e-7",
            runtime.evalString("(0.000001).toPrecision(17)")
        )
        assertEquals("123.456000000000003", runtime.evalString("(123.456).toPrecision(18)"))
    }

    @Test
    fun `Number toFixed 按精确十进制值舍入并保留负零结果符号`() = runTest {
        val runtime = TestRuntime()
        // 0.9999 的精确值小于 1，但保留 0 位小数时进位为 1
        assertEquals("1", runtime.evalString("(0.9999).toFixed(0)"))
        // 1.005 的双精度值略小于 1.005，保留两位小数为 1.00
        assertEquals("1.00", runtime.evalString("(1.005).toFixed(2)"))
        // 大数高位与 20 位小数的精确展开
        assertEquals("100000000000000000000.00", runtime.evalString("(1e20).toFixed(2)"))
        assertEquals("123.45600000000000306954", runtime.evalString("(123.456).toFixed(20)"))
        // 负值即使舍入为 0 也保留负号
        assertEquals("-0.00", runtime.evalString("(-0.0001).toFixed(2)"))
        // ≥ 1e21 直接返回 Number::toString
        assertEquals("1e+21", runtime.evalString("(1e21).toFixed(2)"))
    }

    @Test
    fun `Number toString 非十进制使用最短往返表示`() = runTest {
        val runtime = TestRuntime()
        // 0.1 的精确二进制展开需要 52 位有效数字，不能被截断
        assertEquals(
            "0.0001100110011001100110011001100110011001100110011001101",
            runtime.evalString("(0.1).toString(2)")
        )
        assertEquals("1010.1", runtime.evalString("(10.5).toString(2)"))
        assertEquals("ff.8", runtime.evalString("(255.5).toString(16)"))
        // 非 2 的幂进制按最近可往返值舍入
        assertEquals(
            "0.0022002200220022002200220022002201",
            runtime.evalString("(0.1).toString(3)")
        )
        assertEquals("3f.gez4w97ry", runtime.evalString("(123.456).toString(36)"))
        // 超过 2^63 的整数不再溢出，输出完整数字
        assertEquals("3635c9adc5dea00000", runtime.evalString("(1e21).toString(16)"))
        assertEquals("70", runtime.evalString("String((1e21).toString(2).length)"))
        assertEquals("20000000000000", runtime.evalString("(2 ** 53).toString(16)"))
        // 负号与次正规数
        assertEquals(
            "-0.0001100110011001100110011001100110011001100110011001101",
            runtime.evalString("(-0.1).toString(2)")
        )
        assertEquals(
            "0." + "0".repeat(1073) + "1",
            runtime.evalString("(5e-324).toString(2)")
        )
        // 非 2 的幂进制：最近候选不可往返时仍需接受同长度的另一侧候选
        assertEquals(
            "0.1111111111111111111111111111111112",
            runtime.evalString("(0.5).toString(3)")
        )
        // V8 的非 2 的幂进制输出可能无法解析回原 double；此处按规范的最短往返结果断言
        assertEquals(
            "0.022222222222222222222223",
            runtime.evalString("(0.1).toString(5)")
        )
        assertEquals("344015313561621001500000", runtime.evalString("(1e20).toString(7)"))
    }

    @Test
    fun `RegExp source 访问器的转义与接收者校验`() = runTest {
        val runtime = TestRuntime()
        assertEquals("(?:)", runtime.evalString("new RegExp('').source"))
        assertEquals("(?:)", runtime.evalString("RegExp.prototype.source"))
        // EscapeRegExpPattern：斜杠与行终止符转义，已有反斜杠转义保持原样
        assertEquals("a\\/b", runtime.evalString("new RegExp('a/b').source"))
        assertEquals(
            "a\\nb",
            runtime.evalString("new RegExp('a' + String.fromCharCode(10) + 'b').source")
        )
        // 非 RegExp 接收者抛 TypeError；RegExp.prototype 上的标志访问器返回 undefined
        assertEquals("TypeError", runtime.evalString(
            """
                try { Object.getOwnPropertyDescriptor(RegExp.prototype, 'source').get.call({});
                    'none' } catch (e) { e.name }
            """.trimIndent()
        ))
        assertEquals("undefined", runtime.evalString("typeof RegExp.prototype.global"))
        assertEquals("undefined", runtime.evalString("typeof RegExp.prototype.sticky"))
    }

    @Test
    fun `JSON 序列化包装对象与 reviver 内部化语义`() = runTest {
        val runtime = TestRuntime()
        // 包装对象按内部槽展开
        assertEquals(true, runtime.evalBoolean(
            """
                JSON.stringify(new Number(8.5)) === '8.5' &&
                JSON.stringify(new String('str')) === '"str"' &&
                JSON.stringify(new Boolean(true)) === 'true'
            """.trimIndent()
        ))
        // space 为包装对象时按 ToNumber/ToString 转换
        assertEquals(true, runtime.evalBoolean(
            """
                JSON.stringify({ a: 1 }, null, new Number(2)) === JSON.stringify({ a: 1 }, null, 2) &&
                JSON.stringify({ a: 1 }, null, new String('xx')) ===
                JSON.stringify({ a: 1 }, null, 'xx')
            """.trimIndent()
        ))
        // replacer 数组中的包装数字按 ToString 转成属性名
        assertEquals(true, runtime.evalBoolean(
            """
                var num = new Number(10);
                num.toString = function () { return 'toString'; };
                num.valueOf = function () { throw new Error('should not be called'); };
                JSON.stringify({ 10: 1, toString: 2, valueOf: 3 }, [num]) === '{"toString":2}'
            """.trimIndent()
        ))
        // reviver 遍历使用快照键，且 Get 可读取原型链；非可配置属性不被覆盖
        val reviverRuntime = TestRuntime()
        assertEquals(true, reviverRuntime.evalBoolean(
            """
                Object.prototype.b = 3;
                var json = '{"a": 1, "b": 2}';
                var obj = JSON.parse(json, function (key, value) {
                    if (key === 'a') { delete this.b; }
                    return value;
                });
                delete Object.prototype.b;
                obj.a === 1 && obj.hasOwnProperty('b') && obj.b === 3
            """.trimIndent()
        ))
        assertEquals(true, reviverRuntime.evalBoolean(
            """
                var arr = JSON.parse('[1, 2]', function (key, value) {
                    if (key === '0') { Object.defineProperty(this, '1', { configurable: false }); }
                    if (key === '1') return 22;
                    return value;
                });
                arr[0] === 1 && arr[1] === 2
            """.trimIndent()
        ))
        // reviver 的包装对象属性为可枚举数据属性
        assertEquals(true, reviverRuntime.evalBoolean(
            """
                var wrapper;
                JSON.parse('2', function () { wrapper = this; });
                Object.keys(wrapper).length === 1 && wrapper[''] === 2
            """.trimIndent()
        ))
    }

    @Test
    fun `Number 字符串化采用最短十进制表示`() = runTest {
        val runtime = TestRuntime()
        assertEquals("5e-324", runtime.evalString("(5e-324).toString()"))
        assertEquals("-5e-324", runtime.evalString("(-5e-324).toString()"))
        // JSON 与数组 join 复用同一数字格式化
        assertEquals("5e-324", runtime.evalString("JSON.stringify(5e-324)"))
        assertEquals("5e-324", runtime.evalString("[5e-324].join()"))
        assertEquals("0.30000000000000004", runtime.evalString("(0.1 + 0.2).toString()"))
    }

    @Test
    fun `String 原型方法要求 ObjectCoercible 与 pad 默认填充`() = runTest {
        val runtime = TestRuntime()
        // String.prototype 方法对 null/undefined 接收者抛 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                function throwsTypeError(fn) {
                    try { fn.call(null); return false; } catch (e) { return e instanceof TypeError; }
                }
                throwsTypeError(String.prototype.charAt) &&
                throwsTypeError(String.prototype.charCodeAt) &&
                throwsTypeError(String.prototype.concat) &&
                throwsTypeError(String.prototype.indexOf) &&
                throwsTypeError(String.prototype.lastIndexOf) &&
                throwsTypeError(String.prototype.slice) &&
                throwsTypeError(String.prototype.substring) &&
                throwsTypeError(String.prototype.toLowerCase) &&
                throwsTypeError(String.prototype.toUpperCase) &&
                throwsTypeError(String.prototype.padStart) &&
                throwsTypeError(String.prototype.localeCompare)
            """.trimIndent()
        ))
        // 省略 fillString 时使用空格；pad 系列 length 为 1
        assertEquals(true, runtime.evalBoolean(
            """
                'abc'.padStart(5) === '  abc' && 'abc'.padEnd(5) === 'abc  ' &&
                'abc'.padStart(5, undefined) === '  abc' &&
                String.prototype.padStart.length === 1 &&
                String.prototype.padEnd.length === 1
            """.trimIndent()
        ))
        // localeCompare 对规范等价字符串返回 0
        assertEquals(true, runtime.evalBoolean(
            """
                'o\u0308'.localeCompare('\u00F6') === 0 &&
                '\u00E4\u0323'.localeCompare('a\u0323\u0308') === 0
            """.trimIndent()
        ))
    }

    @Test
    fun `String replace 与 split 的求值顺序`() = runTest {
        val runtime = TestRuntime()
        // 非函数替换值在搜索前 ToString（即使没有匹配）
        assertEquals(1.0, runtime.evalNumber(
            """
                var calls = 0;
                var replaceValue = { toString: function () { calls += 1; return 'b'; } };
                ''.replace('a', replaceValue); calls
            """.trimIndent()
        ))
        // separator 的 ToString 在 limit 为 0 之前执行
        assertEquals("ExpectedError", runtime.evalString(
            """
                function ExpectedError() {}
                var separator = { toString: function () { throw new ExpectedError(); } };
                try { 'foo'.split(separator, 0); 'none' } catch (e) { e.constructor.name }
            """.trimIndent()
        ))
        // ToUint32(limit) 先于 ToString(separator)
        assertEquals("limit", runtime.evalString(
            """
                var log = 'none';
                var limit = { valueOf: function () { log = 'limit'; throw new Error('limit'); } };
                var separator = { toString: function () { log = 'separator'; return ','; } };
                try { 'a'.split(separator, limit); } catch (e) { }
                log
            """.trimIndent()
        ))
    }

    @Test
    fun `非 u 正则支持 Annex B IdentityEscape`() = runTest {
        val runtime = TestRuntime()
        // \x/\u 后缺位时按 IdentityEscape 匹配字面字符；合法转义保持原义
        assertEquals(true, runtime.evalBoolean(
            """
                /\x/.test('x') && !/\x/.test('y') &&
                /[\x]/.test('x') && /\u/.test('u') &&
                /\xA0/.test('\u00A0') &&
                'x'.split(/\x/).length === 2 &&
                'x'.split(/\x/)[0] === ''
            """.trimIndent()
        ))
        // 非法结构仍按语法错误拒绝
        assertFailsWith<JSException> { runtime.eval("new RegExp('(')") }
        assertFailsWith<LexerException> { runtime.eval("/[/;") }
    }

    @Test
    fun `Function toString 使用源码文本与 bind length 语义`() = runTest {
        val runtime = TestRuntime()
        // 函数声明/方法保留源码文本（含注释）
        assertEquals(true, runtime.evalBoolean(
            """
                function /*c*/ foo(a) { return a; }
                var ok1 = foo.toString() === 'function /*c*/ foo(a) { return a; }';
                var o = { /*a*/ m /*b*/ ( /*c*/ ) { /*d*/ } };
                var ok2 = o.m.toString() === 'm /*b*/ ( /*c*/ ) { /*d*/ }';
                ok1 && ok2
            """.trimIndent()
        ))
        // class 构造器的源码文本是整段 class 定义
        assertEquals(true, runtime.evalBoolean(
            """
                class A { constructor(x) { this.x = x; } }
                A.toString() === 'class A { constructor(x) { this.x = x; } }'
            """.trimIndent()
        ))
        // 动态函数按规范拼接源码文本
        assertEquals(true, runtime.evalBoolean(
            "Function('a', 'b').toString() === 'function anonymous(a\\n) {\\nb\\n}'"
        ))
        // bind length：仅自有 length、ToIntegerOrInfinity、保留 Infinity
        assertEquals(true, runtime.evalBoolean(
            """
                var f = function () {};
                Object.defineProperty(f, 'length', { value: Infinity });
                var infinite = f.bind().length === Infinity;
                Object.defineProperty(f, 'length', { value: 5 });
                var subtracted = f.bind(null, 1, 2).length === 3;
                function bar() {}
                Object.setPrototypeOf(bar, { length: 42 });
                delete bar.length;
                var notOwn = Function.prototype.bind.call(bar, null, 1).length === 0;
                infinite && subtracted && notOwn
            """.trimIndent()
        ))
        // caller/arguments 访问器与严格 arguments.callee 共用 %ThrowTypeError%
        assertEquals(true, runtime.evalBoolean(
            """
                var thrower = Object.getOwnPropertyDescriptor(Function.prototype, 'caller').get;
                var calleeThrower = (function () { 'use strict';
                    return Object.getOwnPropertyDescriptor(arguments, 'callee').get; })();
                thrower === calleeThrower &&
                Object.getOwnPropertyDescriptor(Function.prototype, 'arguments').get === thrower
            """.trimIndent()
        ))
    }

    @Test
    fun `Date 年份 格式化与 setter 长度语义`() = runTest {
        val runtime = TestRuntime()
        // setFullYear 不应用 0..99 -> 1900+ 映射；Annex B setYear 应用 MakeFullYear
        assertEquals(1.0, runtime.evalNumber("var d = new Date(0); d.setFullYear(1); d.getFullYear()"))
        assertEquals(true, runtime.evalBoolean(
            "var e = new Date(0); e.setYear(1); e.getFullYear() === 1901"
        ))
        // setter length 描述符
        assertEquals(true, runtime.evalBoolean(
            """
                Date.prototype.setFullYear.length === 3 &&
                Date.prototype.setUTCFullYear.length === 3 &&
                Date.prototype.setMonth.length === 2 &&
                Date.prototype.setHours.length === 4 &&
                Date.prototype.setMinutes.length === 3 &&
                Date.prototype.setSeconds.length === 2 &&
                Date.prototype.setMilliseconds.length === 1
            """.trimIndent()
        ))
        // toString 使用本地时间格式；负年份至少 4 位
        assertEquals(true, runtime.evalBoolean(
            """
                /\w{3} \w{3} \d{2} \d{4} \d{2}:\d{2}:\d{2} GMT\+0000 \(Coordinated Universal Time\)/.test(new Date(0).toString()) &&
                new Date('-000001-07-01T00:00Z').toString().split(' ')[3] === '-0001'
            """.trimIndent()
        ))
        // ISO 允许省略月/日；无效日期 getTimezoneOffset 返回 NaN
        assertEquals(true, runtime.evalBoolean(
            """
                new Date('1970').toISOString() === '1970-01-01T00:00:00.000Z' &&
                Number.isNaN(new Date(NaN).getTimezoneOffset())
            """.trimIndent()
        ))
        // ISO 扩展年份：正负号 + 至少 6 位数字
        assertEquals(true, runtime.evalBoolean(
            """
                new Date(253402300800000).toISOString() === '+010000-01-01T00:00:00.000Z' &&
                new Date(-62167219200000).toISOString() === '0000-01-01T00:00:00.000Z' &&
                new Date(-8.64e15).toISOString() === '-271821-04-20T00:00:00.000Z'
            """.trimIndent()
        ))
    }

    @Test
    fun `Date setter 参数为 NaN 或显式 undefined 时使日期失效`() = runTest {
        val runtime = TestRuntime()
        // 返回值为 NaN 且内部时间同样失效（否则后续 getTime/toISOString 仍是旧值）
        assertEquals(true, runtime.evalBoolean(
            """
                (function () { var d = new Date(Date.UTC(2020, 0, 1));
                    var r = d.setUTCDate(NaN);
                    return Number.isNaN(r) && Number.isNaN(d.getTime()); })()
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                (function () { var d = new Date(Date.UTC(2020, 0, 1, 12));
                    var r = d.setUTCHours(undefined);
                    return Number.isNaN(r) && Number.isNaN(d.getTime()); })()
            """.trimIndent()
        ))
        // 可选参数缺省（未传）仍保留原值
        assertEquals(true, runtime.evalBoolean(
            """
                (function () { var d = new Date(Date.UTC(2020, 0, 1, 12, 30)); d.setUTCHours(1);
                    return d.toISOString() === '2020-01-01T01:30:00.000Z'; })()
            """.trimIndent()
        ))
        // 原时间无效时按规范直接返回 NaN，不覆盖 valueOf 副作用写入的时间
        assertEquals(true, runtime.evalBoolean(
            """
                (function () { var d = new Date(NaN); var value = { valueOf: function () {
                            d.setTime(0); return 1; } }; var r = d.setUTCDate(value);
                    return Number.isNaN(r) && d.getTime() === 0; })()
            """.trimIndent()
        ))
    }

    @Test
    fun `Error isPrototypeOf slice 与 toExponential 边界`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "Error",
            runtime.evalString("Error.prototype.toString.call({ name: undefined, message: undefined })")
        )
        assertEquals(
            "TypeError",
            runtime.evalString(
                "try { Error.prototype.toString.call(1); 'none' } catch (e) { e.name }"
            )
        )
        assertEquals(false, runtime.evalBoolean("Object.prototype.isPrototypeOf.call({}, {})"))
        assertEquals(
            "TypeError",
            runtime.evalString(
                "try { Object.prototype.isPrototypeOf.call(null, {}); 'none' } catch (e) { e.name }"
            )
        )
        // slice 的 end 显式 undefined 等价于省略
        assertEquals(2.0, runtime.evalNumber("[0,1,2,3,4].slice(3, undefined).length"))
        // toExponential 非有限值先于范围检查
        assertEquals("Infinity", runtime.evalString("(Infinity).toExponential(1000)"))
        assertEquals("NaN", runtime.evalString("NaN.toExponential(2)"))
    }

    @Test
    fun `Array from of 内建身份与 unmapped arguments`() = runTest {
        val runtime = TestRuntime()
        // array-like 路径以 length 构造结果对象
        assertEquals(3.0, runtime.evalNumber(
            """
                function C() { this.args = arguments; }
                Array.from.call(C, { length: 3 }).args[0]
            """.trimIndent()
        ))
        // Array.of 通过 Set 写入 length，可命中自定义 setter
        assertEquals(1.0, runtime.evalNumber(
            """
                var hits = 0;
                function P() { Object.defineProperty(this, 'length', { set: function () { hits++; } }); }
                Array.of.call(P, 1, 2); hits
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            "Number.parseInt === parseInt && Number.parseFloat === parseFloat"
        ))
        // 非简单参数的 unmapped arguments 的 callee 为 %ThrowTypeError%
        assertEquals(true, runtime.evalBoolean(
            """
                function f(a = 0) { return arguments; }
                Object.getOwnPropertyDescriptor(f(), 'callee').get ===
                Object.getOwnPropertyDescriptor(Function.prototype, 'caller').get
            """.trimIndent()
        ))
        // entries 迭代期间删除未来键时跳过
        assertEquals(true, runtime.evalBoolean(
            """
                var o = { a: 'A', get b() { delete this.c; return 'B'; }, c: 'C' };
                Object.entries(o).length === 2
            """.trimIndent()
        ))
        // 非法数组 length 优先抛 RangeError
        assertEquals("RangeError", runtime.evalString(
            """
                try { Object.defineProperty([], 'length', { value: -1, configurable: true });
                    'none' } catch (e) { e.name }
            """.trimIndent()
        ))
    }

    @Test
    fun `参数环境与函数体 var 环境分离`() = runTest {
        val runtime = TestRuntime()
        // 参数默认值中的闭包看不到函数体 var；函数体闭包可见
        assertEquals(true, runtime.evalBoolean(
            """
                var x = 'outside';
                var probeParams, probeBody;
                (function (_ = probeParams = function () { return x; }) {
                    var x = 'inside';
                    probeBody = function () { return x; };
                }());
                probeParams() === 'outside' && probeBody() === 'inside'
            """.trimIndent()
        ))
        // 参数默认值中的 eval var 写入函数 VariableEnvironment，函数体可见
        assertEquals(true, runtime.evalBoolean(
            "function f(p = eval('var created = 1')) { return created; } f() === 1"
        ))
        // 参数默认值中的 eval var 对参数闭包同样可见（落在参数环境的父级）
        assertEquals(true, runtime.evalBoolean(
            """
                function f(p = eval('var created = 1'), q = function () { return created; })
                { return q(); } f() === 1
            """.trimIndent()
        ))
        // eval 的 var 声明与已初始化形参同名时抛 SyntaxError
        assertEquals("SyntaxError", runtime.evalString(
            "try { (function (a = 1, b = eval('var a = 2')) {})(); 'none' } catch (e) { e.name }"
        ))
        // 隐式 arguments 绑定同样阻止参数初始化阶段的 eval 声明（Test262 规范期望）
        assertEquals("SyntaxError", runtime.evalString(
            "try { (function (p = eval('var arguments = 1')) {})(); 'none' } catch (e) { e.name }"
        ))
        // arguments 对象在参数默认值中可见（显式传入 undefined 才触发默认值）
        assertEquals(true, runtime.evalBoolean(
            "function g(a = arguments) { return a; } g(undefined, 2).length === 2"
        ))
        // 函数体 var 与形参同名时以形参当前值初始化，后续赋值正常覆盖
        assertEquals(true, runtime.evalBoolean(
            "function f(a = 5) { var a; return a; } f() === 5 && f(6) === 6"
        ))
        assertEquals(true, runtime.evalBoolean(
            "function f({x} = {x: 3}) { var x = 4; return x; } f() === 4"
        ))
        // var arguments 以 arguments 对象初始化；箭头函数没有自己的 arguments
        assertEquals(true, runtime.evalBoolean(
            "function f(a = 1) { var arguments; return arguments.length; } f(5) === 1"
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                var af = (x = 1) => { var arguments; return typeof arguments; };
                af() === 'undefined'
            """.trimIndent()
        ))
    }

    @Test
    fun `Array sort 按 SortIndexedProperties 读写属性并处理空洞与 undefined`() = runTest {
        val runtime = TestRuntime()
        // 收集阶段 Get 一次，写回通过 Set；getter 修改 length 不由 sort 截断
        assertEquals(true, runtime.evalBoolean(
            """
                var a = [undefined, 'c', , 'b', undefined, , 'a', 'd'];
                Object.defineProperty(a, '2', {
                    get: function () { a.length += 2; return this.foo; },
                    set: function (v) { this.foo = v; } });
                a.sort();
                a.length === 10 && a[0] === 'a' && a[1] === 'b' && a[3] === 'd' &&
                !(7 in a) && a[2] === 'c' && a.length === 12
            """.trimIndent()
        ))
        // 默认比较把 undefined 排在末尾，尾部空洞保持缺失
        assertEquals(true, runtime.evalBoolean(
            """
                var b = [undefined, 'b', , 'a']; b.sort();
                b[0] === 'a' && b[1] === 'b' && b[2] === undefined &&
                !(3 in b) && b.length === 4
            """.trimIndent()
        ))
        // 比较函数返回 NaN 视为相等，排序保持稳定
        assertEquals(true, runtime.evalBoolean(
            "[3, 1, 2].sort(function () { return NaN; }).join(',') === '3,1,2'"
        ))
        // sort 是泛型方法：直接读写 array-like 的索引
        assertEquals(true, runtime.evalBoolean(
            """
                var o = { 0: 'b', 1: 'a', length: 2 };
                Array.prototype.sort.call(o);
                o[0] === 'a' && o[1] === 'b' && o.length === 2
            """.trimIndent()
        ))
    }

    @Test
    fun `URI 编解码与数字解析的规范边界`() = runTest {
        val runtime = TestRuntime()
        // encodeURI 不编码保留字符，encodeURIComponent 编码
        assertEquals(true, runtime.evalBoolean(
            """
                encodeURI(';/?:@&=+$,#') === ';/?:@&=+$,#' &&
                encodeURIComponent(';') === '%3B'
            """.trimIndent()
        ))
        // 孤立代理项抛 URIError
        assertEquals("URIError", runtime.evalString(
            "try { encodeURI('\\uD800'); 'none' } catch (e) { e.name }"
        ))
        assertEquals("URIError", runtime.evalString(
            "try { encodeURIComponent('a\\uDC00'); 'none' } catch (e) { e.name }"
        ))
        // UTF-8 编码与解码
        assertEquals(true, runtime.evalBoolean(
            """
                encodeURIComponent('中') === '%E4%B8%AD' &&
                decodeURIComponent('%E4%B8%AD') === '中'
            """.trimIndent()
        ))
        // decodeURI 保留保留字符的原始转义大小写
        assertEquals(true, runtime.evalBoolean(
            """
                decodeURI('%3b%2f') === '%3b%2f' &&
                decodeURIComponent('%3b%2f') === ';/' &&
                decodeURI('%E4%B8%AD%3b') === '中%3b'
            """.trimIndent()
        ))
        // 非法 UTF-8 序列抛 URIError
        assertEquals("URIError", runtime.evalString(
            "try { decodeURIComponent('%80'); 'none' } catch (e) { e.name }"
        ))
        assertEquals("URIError", runtime.evalString(
            "try { decodeURIComponent('%C0%AF'); 'none' } catch (e) { e.name }"
        ))
        assertEquals("URIError", runtime.evalString(
            "try { decodeURI('%E4%B8'); 'none' } catch (e) { e.name }"
        ))
        // parseInt 的 radix 按 ToInt32；parseFloat 只接受 ASCII 数字
        assertEquals(true, runtime.evalBoolean(
            """
                parseInt('11', Infinity) === 11 && parseInt('11', NaN) === 11 &&
                parseInt('11', 4294967306) === 11
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            "isNaN(parseFloat('\\u0661\\u0662\\u0663')) && parseFloat('1\\u0661') === 1"
        ))
    }

    @Test
    fun `mapped arguments 在 defineProperty 后保持或解除映射`() = runTest {
        val runtime = TestRuntime()
        // 置为不可配置后映射仍生效，参数写入反映到 arguments，描述符为数据属性
        assertEquals(true, runtime.evalBoolean(
            """
                (function (a) {
                    Object.defineProperty(arguments, '0', { configurable: false });
                    a = 2;
                    var d = Object.getOwnPropertyDescriptor(arguments, '0');
                    return arguments[0] === 2 && d.value === 2 && d.writable &&
                    d.enumerable && !d.configurable;
                })(1)
            """.trimIndent()
        ))
        // 不可配置后再次尝试 configurable: true 抛 TypeError
        assertEquals("TypeError", runtime.evalString(
            """
                (function (a) {
                    Object.defineProperty(arguments, '0', { configurable: false });
                    try { Object.defineProperty(arguments, '0', { configurable: true });
                        return 'none'; } catch (e) { return e.name; } })(1)
            """.trimIndent()
        ))
        // 修改 value 同步形参；writable:false 解除映射并保留最终描述符
        assertEquals(true, runtime.evalBoolean(
            """
                (function (a) {
                    Object.defineProperty(arguments, '0', { configurable: false, enumerable: false });
                    Object.defineProperty(arguments, '0', { value: 2 });
                    var synced = a === 2;
                    Object.defineProperty(arguments, '0', { writable: false });
                    a = 3;
                    var d = Object.getOwnPropertyDescriptor(arguments, '0');
                    return synced && arguments[0] === 2 && d.value === 2 &&
                    !d.writable && !d.enumerable && !d.configurable;
                })(1)
            """.trimIndent()
        ))
    }

    @Test
    fun `Map 与 Set 的 forEach 和迭代器实时遍历新增与删除`() = runTest {
        val runtime = TestRuntime()
        // 遍历期间新增的值会被访问
        assertEquals(true, runtime.evalBoolean(
            """
                var s = new Set([1]); var seen = [];
                s.forEach(function (v) {
                    seen.push(v); if (v === 1) s.add(2); if (v === 2) s.add(3); });
                seen.join(',') === '1,2,3' && s.size === 3
            """.trimIndent()
        ))
        // 删除未访问的值后不再回调；重删重加作为新记录再次访问
        assertEquals(true, runtime.evalBoolean(
            """
                var s = new Set([1, 2, 3]); var seen = [];
                s.forEach(function (v) { seen.push(v);
                    if (v === 1) { s.delete(2); s.add(2); } });
                seen.join(',') === '1,3,2'
            """.trimIndent()
        ))
        // Map：删除未来键跳过，重加访问，size 实时
        assertEquals(true, runtime.evalBoolean(
            """
                var m = new Map([['a', 1], ['b', 2]]); var seen = [];
                m.forEach(function (v, k) { seen.push(k + v);
                    if (k === 'a') { m.delete('b'); m.set('b', 3); } });
                seen.join(',') === 'a1,b3' && m.size === 2
            """.trimIndent()
        ))
        // 迭代器同样实时：删除未来条目跳过、新增条目被访问
        assertEquals(true, runtime.evalBoolean(
            """
                var m = new Map([['a', 1], ['b', 2]]); var it = m.entries(); var seen = [];
                seen.push(it.next().value[0]); m.delete('b'); m.set('c', 3);
                var n; while (!(n = it.next()).done) seen.push(n.value[0]);
                seen.join(',') === 'a,c'
            """.trimIndent()
        ))
    }

    @Test
    fun `标签模板对象按站点缓存并冻结`() = runTest {
        val runtime = TestRuntime()
        // 同一站点重复求值复用同一冻结对象；不同站点相互独立
        assertEquals(true, runtime.evalBoolean(
            $$"""
                var seen = []; function tag(t) { seen.push(t); }
                function f() { tag`a${1}b`; }
                f(); f(); tag`a${1}b`;
                seen[0] === seen[1] && seen[1] !== seen[2] &&
                Object.isFrozen(seen[0]) && Object.isFrozen(seen[0].raw) &&
                seen[0][0] === 'a' && seen[0].raw[0] === 'a'
            """.trimIndent()
        ))
        // 循环中的同一站点复用；不同函数中的同文本站点互相独立
        assertEquals(true, runtime.evalBoolean(
            $$"""
                var objs = []; function tag2(t) { objs.push(t); }
                for (var i = 0; i < 2; i++) { tag2`x${i}`; }
                function g() { tag2`x${1}`; } g(); g();
                objs[0] === objs[1] && objs[2] === objs[3] && objs[1] !== objs[2]
            """.trimIndent()
        ))
    }

    @Test
    fun `单语句上下文中的 let 可作为标识符表达式并触发 ASI`() = runTest {
        val runtime = TestRuntime()
        // let 后换行：表达式语句 let + ASI，后续块/赋值作为独立语句执行
        assertEquals(true, runtime.evalBoolean(
            """
            var body = 0;
            for (var x in null) let
            { body = 1; }
            body === 1
            """.trimIndent()
        ))
        assertEquals(true, runtime.evalBoolean(
            """
            var value = 0;
            for (var x in null) let
            value = 1;
            value === 1
            """.trimIndent()
        ))
        // `let [` 受 ExpressionStatement 前瞻限制，仍需报早期错误
        assertEquals("SyntaxError", runtime.evalString(
            """
                try { eval('for (var x in null) let\n[1] = [];'); 'none' }
                catch (e) { e.name }
            """.trimIndent()
        ))
    }

    @Test
    fun `对象字面量 async 方法的换行与简写早期错误`() = runTest {
        val runtime = TestRuntime()
        // async 与属性名之间不允许换行（此时 async 退化为简写属性，后续缺少逗号）
        assertEquals("SyntaxError", runtime.evalString(
            "try { eval('({ async\\nfoo() { } })'); 'none' } catch (e) { e.name }"
        ))
        // async 不能作为另一个标识符简写的前缀
        assertEquals("SyntaxError", runtime.evalString(
            "try { eval('({ async async })'); 'none' } catch (e) { e.name }"
        ))
        // 合法形式：普通/计算键 async 方法、async 数据属性、名为 async 的普通方法
        assertEquals(true, runtime.evalBoolean(
            """
                var a = { async foo() { return 1; } };
                var b = { async ['x' + 'y']() { return 2; } };
                ({ async: 3 }).async === 3 &&
                typeof ({ async() { return 4; } }).async === 'function' &&
                typeof a.foo === 'function' && typeof b.xy === 'function'
            """.trimIndent()
        ))
    }

    @Test
    fun `Array reduce 与 toString 及小型内建边界`() = runTest {
        val runtime = TestRuntime()
        // reduce 的初始元素只 Get 一次（getter 不被重复调用）
        assertEquals(true, runtime.evalBoolean(
            """
                var first = 0, second = 0; var arr = [, , ];
                Object.defineProperty(arr, '0', { get: function () { first++; return 11; },
                    configurable: true });
                Object.defineProperty(arr, '1', { get: function () { second++; return 9; },
                    configurable: true });
                var seen = [];
                arr.reduce(function (p, v, i) { seen.push(i); return p + v; });
                first === 1 && second === 1 && seen.join(',') === '1' && arr.length === 2
            """.trimIndent()
        ))
        // reduceRight 按捕获 len 迭代，getter 缩短 length 后 HasProperty 跳过已删除索引
        assertEquals(true, runtime.evalBoolean(
            """
                var called = 0; var a = [0, 1, 2, 3];
                Object.defineProperty(a, '4', { get: function () { a.length = 2; return 10; },
                    configurable: true });
                var r = a.reduceRight(function (p, v) { called++; return p + v; });
                r === 11 && called === 2
            """.trimIndent()
        ))
        // Array.prototype.toString 对非数组 this 退回 Object.prototype.toString
        assertEquals(true, runtime.evalBoolean(
            """
                Array.prototype.toString.call(true) === '[object Boolean]' &&
                Array.prototype.toString.call({}) === '[object Object]'
            """.trimIndent()
        ))
        // lastIndexOf 显式 undefined fromIndex 视为 0
        assertEquals(-1.0, runtime.evalNumber("[1, 2, 2].lastIndexOf(2, undefined)"))
        // __proto__ 冒号形式不参与函数名推断
        assertEquals(true, runtime.evalBoolean(
            """
                var o = { __proto__: function () {} };
                Object.getPrototypeOf(o).name !== '__proto__'
            """.trimIndent()
        ))
        // Set.keys 与 values 是同一函数对象；AsyncFunction 是 Function 的子类
        assertEquals(true, runtime.evalBoolean(
            "Set.prototype.keys === Set.prototype.values"
        ))
        assertEquals(true, runtime.evalBoolean(
            """
                async function foo() {};
                var AF = foo.constructor;
                Object.getPrototypeOf(AF) === Function &&
                Object.getPrototypeOf(AF.prototype) === Function.prototype &&
                foo instanceof Function
            """.trimIndent()
        ))
    }

    @Test
    fun `delete 与计算成员访问先校验基对象`() = runTest {
        val runtime = TestRuntime()
        // delete 属性引用：null/undefined 基对象抛 TypeError
        assertEquals("TypeError", runtime.evalString(
            "try { delete Object[0][0]; 'none' } catch (e) { e.name }"
        ))
        assertEquals("TypeError", runtime.evalString(
            "var base = null; try { delete base[0]; 'none' } catch (e) { e.name }"
        ))
        // 计算键在 ToObject(base) 之后才 ToPropertyKey
        assertEquals("TypeError", runtime.evalString(
            """
                var base = null;
                var key = { toString: function () { throw new Error('key'); } };
                try { base[key]; 'none' } catch (e) { e.name }
            """.trimIndent()
        ))
        // 原始值基对象按 ToObject 处理，删除不存在属性返回 true
        assertEquals(true, runtime.evalBoolean("delete true.x"))
    }

    @Test
    fun `旧式八进制与字符串转义及 lookbehind 量化早期错误`() = runTest {
        val runtime = TestRuntime()
        // sloppy 旧式八进制字面量按 8 进制求值；严格模式拒绝
        assertEquals(56.0, runtime.evalNumber("070"))
        assertEquals("SyntaxError", runtime.evalString(
            "try { eval('\"use strict\"; 070;'); 'none' } catch (e) { e.name }"
        ))
        // \8/\9 与 \0 后接数字：sloppy 保留字符，严格模式拒绝
        assertEquals(true, runtime.evalBoolean(
            """
                '\8' === '8' && '\9' === '9' &&
                '\08'.length === 2 && '\08'[0] === '\0'
            """.trimIndent()
        ))
        assertEquals("SyntaxError", runtime.evalString(
            "try { eval('\"use strict\"; \"\\\\8\";'); 'none' } catch (e) { e.name }"
        ))
        // 旧式八进制转义位数上限：\400 = \40 + '0'
        assertEquals(true, runtime.evalBoolean(
            "'\\400' === ' 0' && '\\377'.charCodeAt(0) === 255"
        ))
        // lookbehind 不可被量化
        assertEquals("SyntaxError", runtime.evalString(
            "try { eval('/.(?<=.)?/'); 'none' } catch (e) { e.name }"
        ))
        assertEquals("SyntaxError", runtime.evalString(
            "try { eval('/.(?<!.){2,3}/u'); 'none' } catch (e) { e.name }"
        ))
        // for (async of => {}) 是 async 箭头函数；裸 async 左值的 for-of 非法
        assertEquals(true, runtime.evalBoolean(
            """
                var counter = 0; for (async of => {}; counter < 3; ++counter) {}
                counter === 3
            """.trimIndent()
        ))
        assertEquals("SyntaxError", runtime.evalString(
            """
                try { eval('var async; for (async of [1]) ;'); 'none' }
                catch (e) { e.name }
            """.trimIndent()
        ))
    }

    @Test
    fun `sloppy eval 新建 var 绑定可删除`() = runTest {
        val runtime = TestRuntime()
        // eval 新建 var：delete 后闭包引用抛 ReferenceError
        assertEquals(true, runtime.evalBoolean(
            """
                var initial = null, postDeletion;
                (function () {
                    eval('initial = x; delete x; postDeletion = function () { x; }; var x;');
                })();
                initial === undefined && (function () {
                    try { postDeletion(); return false; }
                    catch (e) { return e.name === 'ReferenceError'; } })()
            """.trimIndent()
        ))
        // eval 新建函数声明绑定同样可删除
        assertEquals(true, runtime.evalBoolean(
            """
                var captured = null, probe = null;
                (function () {
                    eval('captured = h; delete h; probe = function () { h; }; function h() {}');
                })();
                typeof captured === 'function' && (function () {
                    try { probe(); return false; }
                    catch (e) { return e.name === 'ReferenceError'; } })()
            """.trimIndent()
        ))
        // 普通函数内的 var 绑定不可删除
        assertEquals(false, runtime.evalBoolean(
            "(function () { var y = 1; return delete y; })()"
        ))
    }

    @Test
    fun `switch 中 async 函数为块级绑定且 catch 解构默认值捕获参数环境`() = runTest {
        val runtime = TestRuntime()
        // async 函数声明不参与 Annex B var 提升
        assertEquals("ReferenceError", runtime.evalString(
            """
                try { eval('switch (0) { default: async function x() {} } x;'); 'none' }
                catch (e) { e.name }
            """.trimIndent()
        ))
        // switch 块内仍可访问
        assertEquals(true, runtime.evalBoolean(
            """
                var probe = 'none';
                switch (0) { default: async function f() {} probe = typeof f; }
                probe === 'function'
            """.trimIndent()
        ))
        // catch 解构默认值中的闭包捕获 catch 参数环境
        assertEquals("inside", runtime.evalString(
            """
                var probeParam; var x = 'outside';
                try { throw ['inside']; }
                catch ([x, _ = probeParam = function () { return x; }]) {}
                probeParam()
            """.trimIndent()
        ))
    }

    @Test
    fun `super 赋值与自增的求值顺序及未初始化 this`() = runTest {
        val runtime = TestRuntime()
        // 简单赋值：RHS 先于 ToObject(null) 的 TypeError
        assertEquals(true, runtime.evalBoolean(
            """
                var count = 0;
                class C1 { static m() { super.x = (count += 1); } }
                Object.setPrototypeOf(C1, null);
                try { C1.m(); false; } catch (e) { e.name === 'TypeError' && count === 1 }
            """.trimIndent()
        ))
        // 计算键的 ToPropertyKey 延迟到 RHS 之后
        assertEquals(true, runtime.evalBoolean(
            """
                function DummyError() {}
                var prop = { toString: function () { throw new Error('key'); } };
                class C2 extends (class {}) { m() { super[prop] = (function () {
                            throw new DummyError(); })(); } }
                try { (new C2()).m(); false; }
                catch (e) { e instanceof DummyError }
            """.trimIndent()
        ))
        // 自增：GetSuperBase 先于 ToPropertyKey（toString 中切换原型不影响已捕获 base）
        assertEquals(true, runtime.evalBoolean(
            """
                var proto = { p: 1 }, proto2 = { p: -1 };
                var obj = { __proto__: proto, m() { return ++super[key]; } };
                var key = { toString: function () {
                        Object.setPrototypeOf(obj, proto2); return 'p'; } };
                obj.m() === 2
            """.trimIndent()
        ))
        // 派生构造器 super() 之前：属性表达式不求值，直接 ReferenceError
        assertEquals("ReferenceError", runtime.evalString(
            """
                class Base { constructor() { throw new Error('base'); } }
                class Derived extends Base { constructor() { super[super()]++; } }
                try { new Derived(); 'none' } catch (e) { e.name }
            """.trimIndent()
        ))
    }

    @Test
    fun `Annex B 块级函数 var 扩展与 CallExpression 赋值目标`() = runTest {
        val runtime = TestRuntime()
        // if 分支函数：执行前 var 绑定为 undefined，执行后更新为函数
        assertEquals(true, runtime.evalBoolean(
            """
                (function () { var init, changed, after;
                    eval('init = f; f = 123; changed = f; if (true) function f() {} else function _f() {} after = f;');
                    return init === undefined && changed === 123 &&
                    typeof after === 'function'; })()
            """.trimIndent()
        ))
        // 参数同名时 FunctionDeclarationInstantiation 跳过扩展
        assertEquals(123.0, runtime.evalNumber(
            "(function (f) { { function f() {} } return f; })(123)"
        ))
        // arguments 绑定存在时跳过扩展
        assertEquals(true, runtime.evalBoolean(
            """
                (function () { { function arguments() {} }
                    return arguments.toString() === '[object Arguments]'; })()
            """.trimIndent()
        ))
        // 嵌套块：内层同名函数因外层块级函数而不参与扩展
        assertEquals(1.0, runtime.evalNumber(
            """
                (function () { { function f() { return 1; } { function f() { return 2; } }
                        return f(); } })()
            """.trimIndent()
        ))
        // CallExpression 赋值目标：先求值 LHS，抛 ReferenceError，不执行 RHS
        assertEquals(true, runtime.evalBoolean(
            """
                var fCalled = false, gCalled = false; function f() { fCalled = true; return {}; } function g() { gCalled = true; } try { f() = g(); false; } catch (e) { e.name === 'ReferenceError' && fCalled && !gCalled }
            """.trimIndent()
        ))
        // for-in 旧式初始化器按 [~In] 解析，且只执行一次
        assertEquals(true, runtime.evalBoolean(
            "var effects = 0; for (var a = ++effects in {}); effects === 1"
        ))
        // Annex B HTMLCloseComment：含换行的多行注释后、行首有其它 token 也生效
        assertEquals(3.0, runtime.evalNumber(
            """
                var counter = 0;
                0/*
                */-->
                counter += 1;
                0/* optional
                MultiLineCommentChars */-->
                counter += 1;
                0/*
                */ /* delimiter */-->
                counter += 1;
                counter
            """.trimIndent()
        ))
    }

    @Test
    fun `标签模板非法转义 cooked 为 undefined 且普通模板报错`() = runTest {
        val runtime = TestRuntime()
        // 标签模板：非法转义段 cooked=undefined，raw 保留原始文本
        assertEquals(true, runtime.evalBoolean(
            """
                var cookedOk = false, rawOk = false;
                (function (s) { cookedOk = s[0] === undefined;
                    rawOk = s.raw[0] === '\\01'; })`\01`;
                cookedOk && rawOk
            """.trimIndent()
        ))
        // 普通模板仍为解析期 SyntaxError
        assertEquals("SyntaxError", runtime.evalString(
            "try { eval('`\\\\01`'); 'none' } catch (e) { e.name }"
        ))
    }

    @Test
    fun `引用不可解析时的赋值在 RHS 创建同名全局属性后仍按严格模式处理`() = runTest {
        val runtime = TestRuntime()
        // 严格模式：LHS 引用在 RHS 前已不可解析，必须抛 ReferenceError
        assertEquals("ReferenceError", runtime.evalString(
            """
                (function () { 'use strict';
                    try { undeclaredProbe = (globalThis.undeclaredProbe = 5); 'none'; }
                    catch (e) { return e.name; } })()
            """.trimIndent()
        ))
        // sloppy：按不可解析引用创建/写入全局属性
        assertEquals(5.0, runtime.evalNumber(
            """
                (function () { sloppyProbe = (globalThis.sloppyProbe = 5);
                    return globalThis.sloppyProbe; })()
            """.trimIndent()
        ))
    }

    @Test
    fun `正则超大数量词编译成功且不匹配`() = runTest {
        val runtime = TestRuntime()
        // 数量词上限为任意十进制整数；超过输入长度的最小次数必然失败
        assertEquals("false,false,false", runtime.evalString(
            """
                (function () { var re = new RegExp('b' + Number.MAX_SAFE_INTEGER, 'u');
                    return [re.test(''), re.test('a'), re.test('b')].join(','); })()
            """.trimIndent()
        ))
    }

    @Test
    fun `零宽迭代在可选量词中被丢弃并回滚捕获`() = runTest {
        val runtime = TestRuntime()
        // ? / {0,1} 允许零次：零宽 lookahead 迭代被丢弃，捕获保持 undefined
        assertEquals(true, runtime.evalBoolean(
            """
                var a = 'abc'.match(/(?:(?=(abc)))?a/);
                var b = 'abc'.match(/(?:(?=(abc))){0,1}a/);
                a[1] === undefined && b[1] === undefined
            """.trimIndent()
        ))
        // {1,1} 为必要迭代：捕获保留
        assertEquals(true, runtime.evalBoolean(
            "var c = 'abc'.match(/(?:(?=(abc))){1,1}a/); c[1] === 'abc'"
        ))
        // nullable quantifier：可选迭代不得匹配空串，驱动内部懒量词消费字符
        assertEquals("ab", runtime.evalString("/(a?b??)*/.exec('ab')[0]"))
    }

    @Test
    fun `Unicode 忽略大小写使用简单折叠映射`() = runTest {
        val runtime = TestRuntime()
        // Canonicalize 应用 CaseFolding.txt 的简单/通用映射（含特殊希腊字母与连字）
        assertEquals(true, runtime.evalBoolean(
            """
                /[\u0390]/ui.test('\u1FD3') && /[\u1FD3]/ui.test('\u0390') &&
                /[\u03B0]/ui.test('\u1FE3') && /[\u1FE3]/ui.test('\u03B0') &&
                /[\uFB05]/ui.test('\uFB06') && /[\uFB06]/ui.test('\uFB05') &&
                /\u212A/ui.test('k')
            """.trimIndent()
        ))
    }

    @Test
    fun `RegExp 忽略大小写区分非 u 大写映射与 u 简单折叠`() = runTest {
        val runtime = TestRuntime()
        // 非 u 模式只做单码点大写映射：U+212A/U+017F 不回退到 ASCII，ß 与 ẞ 不相等
        assertEquals(
            "false,false,false",
            runtime.evalString(
                "[/k/i.test('\\u212A'), /s/i.test('\\u017F'), /\\u00DF/i.test('\\u1E9E')].join(',')"
            )
        )
        // 非 u 字符类、范围与 \w 同样按 Canonicalize 判定
        assertEquals(
            "false,false,false",
            runtime.evalString(
                "[/[k]/i.test('\\u212A'), /[a-z]/i.test('\\u212A'), /\\w/i.test('\\u212A')].join(',')"
            )
        )
        // u 模式使用 CaseFolding 简单折叠：Kelvin/长 s 折叠到 k/s，İ 不折叠到 i
        assertEquals(
            "true,true,true,false",
            runtime.evalString(
                """
                    [/\u212A/iu.test('k'), /[a-z]/iu.test('\u017F'), /\w/iu.test('\u212A'),
                        /i/iu.test('\u0130')].join(',')
                """.trimIndent()
            )
        )
        // u 模式下希腊 sigma 折叠后仍非 ASCII 单词字符
        assertEquals(false, runtime.evalBoolean("/\\w/iu.test('\\u03C2')"))
        assertEquals(true, runtime.evalBoolean("/\\W/iu.test('\\u03C2')"))
    }

    @Test
    fun `String 大小写转换覆盖特殊映射与词尾 Sigma 上下文`() = runTest {
        val runtime = TestRuntime()
        // 多码点特殊映射
        assertEquals("SS", runtime.evalString("'ß'.toUpperCase()"))
        assertEquals("FF", runtime.evalString("'ﬀ'.toUpperCase()"))
        assertEquals("\u02BCN", runtime.evalString("'ŉ'.toUpperCase()"))
        // İ 的小写为 i + 组合点
        assertEquals("i\u0307", runtime.evalString("'İ'.toLowerCase()"))
        // 词尾 Sigma：仅词尾（忽略 Case_Ignorable）使用 ς
        assertEquals("\u03B1\u03C2", runtime.evalString("'ΑΣ'.toLowerCase()"))
        assertEquals("\u03B1\u03C3\u03B1", runtime.evalString("'ΑΣΑ'.toLowerCase()"))
        assertEquals("\u03B1\u03C2.", runtime.evalString("'ΑΣ.'.toLowerCase()"))
        // 标题格字母与 astral/Cherokee 的完整映射
        assertEquals("\u01C4", runtime.evalString("'ǅ'.toUpperCase()"))
        assertEquals("\u01C6", runtime.evalString("'ǅ'.toLowerCase()"))
        assertEquals("\uD801\uDC28", runtime.evalString("'𐐀'.toLowerCase()"))
        assertEquals("\uABB3", runtime.evalString("'Ꮳ'.toLowerCase()"))
    }

    @Test
    fun `无词法声明的块与循环头复用外层环境保持语义`() = runTest {
        val runtime = TestRuntime()
        // 块内 var 仍提升到函数作用域
        assertEquals(1.0, runtime.evalNumber("(function () { { var x = 1; } return x; })()"))
        // 块内 let 仍隔离，块结束后外层绑定不变
        assertEquals(
            "outer",
            runtime.evalString("(function () { var x = 'outer'; { let x = 'inner'; } return x; })()")
        )
        // 循环头的 let 仍按每迭代创建绑定（闭包捕获不同值）
        assertEquals(
            "0,1,2",
            runtime.evalString(
                """
                    (function () { var out = []; for (let i = 0; i < 3; i++) { out.push(i); }
                        return out.join(','); })()
                """.trimIndent()
            )
        )
        // 无词法循环头时 var 仍写入函数作用域
        assertEquals(
            3.0,
            runtime.evalNumber("(function () { for (var i = 0; i < 3; i++) {} return i; })()")
        )
    }

    @Test
    fun `函数体无词法声明时复用 var 环境保持语义`() = runTest {
        val runtime = TestRuntime()
        // 函数体内 let 仍有 TDZ 且与嵌套块隔离
        assertEquals(
            "ReferenceError",
            runtime.evalString(
                "(function () { try { x; } catch (e) { return e.name; } let x = 1; })()"
            )
        )
        assertEquals(
            "undefined",
            runtime.evalString("(function () { { let x = 1; } return typeof x; })()")
        )
        // 直接 eval 的 var 仍落到函数 var 作用域
        assertEquals(7.0, runtime.evalNumber("(function () { eval('var x = 7'); return x; })()"))
        // 函数声明提升仍可见，箭头表达式体正常求值
        assertEquals(
            "function",
            runtime.evalString("(function () { return typeof f; function f() {} })()")
        )
        assertEquals(6.0, runtime.evalNumber("((x) => x * 2)(3)"))
    }

    @Test
    fun `Math hypot 按最大绝对值缩放避免溢出`() = runTest {
        val runtime = TestRuntime()
        // 经典 3-4-5 与 Infinity/NaN 优先级
        assertEquals("5", runtime.evalString("String(Math.hypot(3, 4))"))
        assertEquals("Infinity", runtime.evalString("String(Math.hypot(NaN, Infinity))"))
        assertEquals("0", runtime.evalString("String(Math.hypot())"))
        // 大数参数不能因中间平方溢出
        assertEquals(
            "1.4142135623730951e+308",
            runtime.evalString("String(Math.hypot(1e308, 1e308))")
        )
        // 负数与符号零
        assertEquals("13", runtime.evalString("String(Math.hypot(-5, 12))"))
        assertEquals("Infinity", runtime.evalString("String(1 / Math.hypot(-0, -0))"))
    }
}