package com.muedsa.js

import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 词法与语法错误路径、以及正则/除号、ASI 等容易出错的解析场景。
 */
class ParserEdgeTest {

    // ================================================================
    // 词法边界
    // ================================================================

    @Test
    fun `正则字面量与除号可正确区分`() = runTest {
        val runtime = TestRuntime()
        assertEquals(5.0, runtime.evalNumber("var a = 10, b = 2; a / b / 1"))
        assertEquals(1.0, runtime.evalNumber("(1 + 2) / 3"))
        assertEquals(2.0, runtime.evalNumber("function f(x) { return x / 2; } f(4)"))
        assertEquals(true, runtime.evalBoolean("var r = /a\\/b/; r.test('a/b')"))
        assertEquals(true, runtime.evalBoolean("var x = 4; var r = /a/; x / 2 === 2 && r.test('a')"))
    }

    @Test
    fun `未闭合正则与模板字符串抛词法错误`() {
        assertFailsWith<LexerException> {
            com.muedsa.js.lexer.Lexer("var r = /abc").tokenize()
        }
        assertFailsWith<LexerException> {
            com.muedsa.js.lexer.Lexer("`abc").tokenize()
        }
        assertFailsWith<LexerException> {
            com.muedsa.js.lexer.Lexer("`a\${1}").tokenize()
        }
    }

    @Test
    fun `字符串与模板的转义序列`() = runTest {
        val runtime = TestRuntime()
        assertEquals("A", runtime.evalString("'\\x41'"))
        assertEquals("\u0000", runtime.evalString("'\\0'"))
        assertEquals("a\nb", runtime.evalString("`a\nb`"))
        assertEquals("a`b", runtime.evalString("`a\\`b`"))
        assertEquals("1:2", runtime.evalString("var a = 1; `\${a}:\${2}`"))
    }

    @Test
    fun `关键字可以作为属性名`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            6.0,
            runtime.evalNumber("var o = { if: 1, class: 2, default: 3 }; o.if + o.class + o.default")
        )
        assertEquals(1.0, runtime.evalNumber("var o = { var: 1 }; o['var']"))
    }

    @Test
    fun `HTML 注释按 Annex B 规则被忽略`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            2.0,
            runtime.evalNumber("var x = 1; <!-- HTML comment\nx + 1")
        )
        assertEquals(
            2.0,
            runtime.evalNumber("var x = 1;\n  --> HTML close comment\nx + 1")
        )
    }

    @Test
    fun `do-while 结尾分号可以省略`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber("var r = 0; do { r++; } while (false) r")
        )
        assertEquals(
            2.0,
            runtime.evalNumber("var r = 0; do { r++; } while (r < 2) r")
        )
    }

    @Test
    fun `转义形式的 use strict 不启用严格模式`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber(
                "var o = { a: 1 }; var r = 0; 'use\\x20strict'; with (o) { r = a; } r"
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean(
                "function f() { 'use\\x20strict'; return this === globalThis; } f()"
            )
        )
    }

    @Test
    fun `数字字面量各进制与前导小数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(255.0, runtime.evalNumber("0xff"))
        assertEquals(5.0, runtime.evalNumber("0b101"))
        assertEquals(15.0, runtime.evalNumber("0o17"))
        assertEquals(0.5, runtime.evalNumber(".5"))
        assertEquals(1000.0, runtime.evalNumber("1e3"))
    }

    // ================================================================
    // 语法错误
    // ================================================================

    @Test
    fun `非法赋值与自增目标`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("1 = 2;") }
        assertFailsWith<ParseException> { parser("var a = 1; ++a + 1 = 2;") }
        assertFailsWith<ParseException> { parser("++1;") }
        assertFailsWith<ParseException> { parser("a++ ++;") }
    }

    @Test
    fun `for 头部与 for-in of 左值错误`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("for (foo() in {}) {}") }
        assertFailsWith<ParseException> { parser("for (var i = 0; i < 1 i++) {}") }
        assertFailsWith<ParseException> { parser("for (var i = 0; i < 1; i++ {}") }
        assertFailsWith<ParseException> { parser("for (foo() of []) {}") }
    }

    @Test
    fun `try switch 对象字面量与类的语法错误`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("try {}") }
        assertFailsWith<ParseException> { parser("switch (1) { foo: }") }
        assertFailsWith<ParseException> { parser("var o = { a: };") }
        assertFailsWith<ParseException> { parser("class A { m( }") }
        assertFailsWith<ParseException> { parser("function f(a b) {}") }
        assertFailsWith<ParseException> { parser("if (1 { }") }
    }

    @Test
    fun `new target 与模板表达式错误`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("new.foo;") }
        assertFailsWith<ParseException> { parser("var s = `\${}`;") }
    }

    @Test
    fun `const 与解构声明必须初始化`() {
        val parser = { code: String -> com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram() }
        assertFailsWith<ParseException> { parser("const a;") }
        assertFailsWith<ParseException> { parser("var [a];") }
        assertFailsWith<ParseException> { parser("let {a};") }
    }

    @Test
    fun `严格模式删除标识符在解析期抛 ParseException`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<ParseException> {
            runtime.eval("\"use strict\"; var x = 1; delete x;")
        }
    }

    @Test
    fun `return break continue 的上下文错误在解析期拒绝`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("return 1;") }
        assertFailsWith<ParseException> { parser("break;") }
        assertFailsWith<ParseException> { parser("continue;") }
        assertFailsWith<ParseException> { parser("outer: { break missing; }") }
        assertFailsWith<ParseException> { parser("outer: { continue outer; }") }
        assertFailsWith<ParseException> {
            parser("while (true) { function f() { break; } }")
        }
    }

    @Test
    fun `合法的标签 break 与循环 continue`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber("var r = 0; outer: { r = 1; break outer; r = 2; } r")
        )
        assertEquals(
            2.0,
            runtime.evalNumber("var n = 0; while (true) { n++; if (n === 2) break; } n")
        )
        assertEquals(
            0.0,
            runtime.evalNumber("var n = 0; for (var i = 0; i < 3; i++) { continue; n++; } n")
        )
    }

    @Test
    fun `解构模式与声明目标校验`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("for (var x = 1 of y);") }
        assertFailsWith<ParseException> { parser("var [a.b] = value;") }
        assertFailsWith<ParseException> { parser("[a] += value;") }
        assertFailsWith<ParseException> { parser("[...rest, tail] = value;") }
        assertFailsWith<ParseException> { parser("({ get a() {} } = value);") }
        // 合法的解构赋值与默认值
        parser("var a, b, rest; [a, , b] = [1, 2, 3];")
        parser("var arr = [1]; var first, others; [first, ...others] = arr;")
        parser("var target = {}; ({ a = 1 } = target);")
    }

    @Test
    fun `getter 与 setter 形参数量校验`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("var o = { get x(v) { return 1; } };") }
        assertFailsWith<ParseException> { parser("var o = { set x() {} };") }
        assertFailsWith<ParseException> { parser("var o = { set x(...v) {} };") }
        assertFailsWith<ParseException> { parser("class A { get x(v) { return 1; } }") }
        assertFailsWith<ParseException> { parser("class A { set x() {} }") }
    }

    @Test
    fun `类外使用 super 在解析期抛 ParseException`() = runTest {
        val runtime = TestRuntime()
        assertFailsWith<ParseException> { runtime.eval("super.x;") }
    }

    @Test
    fun `括号化 super 调用合法而括号化裸 super 非法`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        // 构造函数体与箭头函数中的 (super()) 都是合法调用表达式
        parser("class A extends B { constructor() { (super()); } }")
        parser("class A extends B { constructor() { () => (super()); } }")
        // 括号化裸 super 及其成员访问/调用仍然非法
        assertFailsWith<ParseException> {
            parser("class A extends B { constructor() { (super)(); } }")
        }
        assertFailsWith<ParseException> {
            parser("class A extends B { constructor() { (super).a; } }")
        }
    }

    @Test
    fun `重复词法声明与 var 冲突在解析期拒绝`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("let a; let a;") }
        assertFailsWith<ParseException> { parser("const a = 1; var a;") }
        assertFailsWith<ParseException> { parser("var a; let a;") }
        assertFailsWith<ParseException> { parser("function a(){} let a;") }
        assertFailsWith<ParseException> { parser("{ let a; { var a; } }") }
        assertFailsWith<ParseException> { parser("function f(a){ let a; }") }
        assertFailsWith<ParseException> { parser("switch(1){ case 1: let a; case 2: let a; }") }
        assertFailsWith<ParseException> { parser("for (let a = 0;;) { var a; }") }
        assertFailsWith<ParseException> { parser("for (const let of a);") }
        // var 允许重复；不同块可以同名遮蔽
        parser("var a; var a; function f(a){ let b; }")
        parser("{ let a; } { let a; }")
    }

    @Test
    fun `解构与参数中的重复绑定按上下文拒绝`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("let {a: b, c: b} = 1;") }
        assertFailsWith<ParseException> { parser("let [a, ...a] = 1;") }
        assertFailsWith<ParseException> { parser("function f([a, a]){}") }
        assertFailsWith<ParseException> { parser("({a, a}) => 1;") }
        assertFailsWith<ParseException> { parser("({ a(b, b){} });") }
        assertFailsWith<ParseException> { parser("try {} catch ({a, a}) {}") }
        // sloppy 普通函数允许重复简单参数；var 解构允许重复名
        parser("function f(a, a){}")
        parser("var [a, a] = 1;")
    }

    @Test
    fun `catch 参数与块内声明冲突按 Annex B 处理`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("try {} catch (a) { let a; }") }
        assertFailsWith<ParseException> { parser("try {} catch (a) { function a(){} }") }
        assertFailsWith<ParseException> { parser("try {} catch ({a}) { var a; }") }
        // 简单参数按 Annex B 可与块内 var 同名
        parser("try {} catch (a) { var a; }")
        parser("try {} catch (a) { { let a; } }")
    }

    @Test
    fun `标签重复与单语句位置函数声明在解析期拒绝`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("a: a: ;") }
        assertFailsWith<ParseException> { parser("a: { b: { a: ; } }") }
        assertFailsWith<ParseException> { parser("if (1) b: function f(){}") }
        assertFailsWith<ParseException> { parser("while (1) b: function f(){}") }
        // sloppy 语句列表位置的标签函数声明是 Annex B 允许的
        parser("b: function f(){}")
        parser("if (1) function f(){}")
    }

    @Test
    fun `对象字面量重复 proto 数据属性拒绝而模式允许`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("({ __proto__: 1, __proto__: 2 });") }
        assertFailsWith<ParseException> { parser("({ \"__proto__\": 1, __proto__: 2 });") }
        // 方法与访问器不占用数据属性名额
        parser("({ __proto__() {}, __proto__: 1 });")
        parser("({ get __proto(){}, __proto__: 1 });")
        // 赋值/绑定模式允许重复属性名
        parser("var {__proto__: a, __proto__: b} = {__proto__: 1};")
        parser("({__proto__: a, __proto__: b} = {__proto__: 1});")
    }

    @Test
    fun `类构造器与严格参数早期错误`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> { parser("class A { constructor(){} constructor(){} }") }
        assertFailsWith<ParseException> { parser("class A { constructor(){} \"constructor\"(){} }") }
        assertFailsWith<ParseException> { parser("class A { get constructor(){} }") }
        assertFailsWith<ParseException> { parser("class A { async constructor(){} }") }
        // static 的 constructor 是普通方法；计算键不构成构造器
        parser("class A { constructor(){} static constructor(){} get [\"constructor\"](){} }")
        assertFailsWith<ParseException> { parser("function f([]){ 'use strict'; }") }
        assertFailsWith<ParseException> { parser("function f(a = 1){ 'use strict'; }") }
        parser("function f(a = 1){}")
        // 严格模式下解构绑定名不能是 eval/arguments
        assertFailsWith<ParseException> { parser("'use strict'; let [eval] = 1;") }
        assertFailsWith<ParseException> { parser("'use strict'; const {a: arguments} = 1;") }
    }

    @Test
    fun `严格模式指令预扫描作用于整个脚本`() {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        // 指令之前的 legacy 八进制转义同样受严格模式约束
        assertFailsWith<ParseException> { parser("\"\\1\"; 'use strict';") }
        // 非指令序言位置的 use strict 不改变脚本模式
        parser("var x = 1; 'use strict'; delete x;")
    }

    @Test
    fun `转义关键字可作为属性名但不能作为标识符`() = runTest {
        val parser = { code: String ->
            com.muedsa.js.parser.Parser(com.muedsa.js.lexer.Lexer(code).tokenize()).parseProgram()
        }
        // 属性名、成员访问中的 IdentifierName 允许转义关键字
        parser("var y = { bre\\u0061k: x } = { break: 42 };")
        parser("({ def\\u{61}ult: 1 });")
        parser("var o = { x: 1 }; o.bre\\u0061k;")
        // 标识符位置拒绝始终保留的关键字（含简写属性引用）
        assertFailsWith<ParseException> { parser("var bre\\u0061k = 1;") }
        assertFailsWith<ParseException> { parser("({ bre\\u0061k });") }
        assertFailsWith<ParseException> { parser("({ x() { ret\\u0075rn 1; } });") }
        // 严格模式未来保留字的转义形式按模式区分
        parser("var l\\u0065t = 1;")
        parser("var st\\u0061tic = 1;")
        assertFailsWith<ParseException> { parser("'use strict'; var l\\u0065t = 1;") }
    }

    @Test
    fun `正则 u 标志的码点转义范围校验`() {
        assertFailsWith<LexerException> { Lexer("/\\u{110000}/u").tokenize() }
        Lexer("/\\u{10FFFF}/u").tokenize()
        // 非 u 模式不受码点转义范围限制
        Lexer("/\\u{110000}/").tokenize()
    }

    @Test
    fun `非法正则标志在运行时抛 SyntaxError`() = runTest {
        val runtime = TestRuntime()
        // 字面量 flags 在词法阶段拒绝
        assertFailsWith<LexerException> { Lexer("var r = /a/z;").tokenize() }
        // 运行时构造器仍抛 JS SyntaxError
        val error = assertFailsWith<JSException> { runtime.eval("var r = new RegExp('a', 'z');") }
        assertEquals("SyntaxError", (error.value as JSError).errorName)
    }

    // ================================================================
    // 自动分号插入
    // ================================================================

    @Test
    fun `ASI 在函数调用与后缀运算中的行为`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            1.0,
            runtime.evalNumber("var b = function (x) { return x; }; var a; a = b\n(1); a")
        )
        assertEquals(
            3.0,
            runtime.evalNumber("var a = 1, b = 2; a\n++b; b")
        )
        assertEquals(
            "undefined",
            runtime.evalString("function f() { return\n1; } typeof f()")
        )
    }
    // ================================================================
    // Unicode 标识符、sloppy let、rest 解构与早期错误
    // ================================================================

    private fun parse(code: String) = Parser(Lexer(code).tokenize()).parseProgram()

    @Test
    fun `Unicode 标识符与 u 转义标识符可解析`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("var \\u{41}BC = 2; ABC + 1"))
        parse("var 𞸊 = 1; var 𞸋 = 2;")
    }

    @Test
    fun `sloppy 模式下 let 可作为普通标识符`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("var let = 1; let + 1"))
        parse("let = 1;")
        parse("(let[a] = b);")
        parse("for (a in let) {}")
    }

    @Test
    fun `rest 参数支持解构模式`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("function f(...[a, b]) { return a + b; } f(1, 2, 3)"))
        parse("function f(...{ length }) {}")
        parse("(...[]) => 1")
    }

    @Test
    fun `单语句上下文拒绝词法声明与函数声明`() {
        assertFailsWith<ParseException> { parse("if (1) let x = 10;") }
        assertFailsWith<ParseException> { parse("for (;;) function f() {}") }
        assertFailsWith<ParseException> { parse("with (true) class A {}") }
        // Annex B：sloppy 的 if 分支仍允许函数声明
        parse("if (1) function f() {}")
    }

    @Test
    fun `对象简写默认值只在解构模式中合法`() {
        assertFailsWith<ParseException> { parse("({ a = 0 });") }
        assertFailsWith<ParseException> { parse("obj = { x = 0 };") }
        parse("var { a = 1 } = {};")
        parse("({ a = 1 } = {});")
        parse("function f({ a = 1 }) {}")
    }

    @Test
    fun `括号化解构模式与 rest 尾逗号被拒绝`() {
        assertFailsWith<ParseException> { parse("([a]) = [];") }
        assertFailsWith<ParseException> { parse("var [(a)] = [];") }
        assertFailsWith<ParseException> { parse("var { a: (b) } = {};") }
        assertFailsWith<ParseException> { parse("([a, ...b,]) => 0;") }
        // 括号化简单赋值目标仍合法
        parse("(a) = 1;")
    }

    @Test
    fun `for-of 左值不能以 let 开头`() {
        assertFailsWith<ParseException> { parse("for (let.a of 0);") }
        parse("for (let a of []) {}")
    }

    @Test
    fun `super 与 new target 的位置校验`() {
        assertFailsWith<ParseException> { parse("function f() { super; }") }
        assertFailsWith<ParseException> { parse("function f() { (super)(); }") }
        assertFailsWith<ParseException> { parse("function f() { new super(); }") }
        assertFailsWith<ParseException> { parse("function f() { new.\\u0074arget; }") }
        parse("class A extends B { constructor() { super(); } }")
        parse("class A extends B { m() { return new super.c(); } }")
    }

    @Test
    fun `u 标志正则的早期错误`() {
        assertFailsWith<LexerException> { parse("/\\1/u;") }
        assertFailsWith<LexerException> { parse("/{*/u;") }
        assertFailsWith<LexerException> { parse("/(?!.){0,}?/u;") }
        assertFailsWith<LexerException> { parse("/}?/u;") }
        // 合法 u 模式
        parse("/\\u{1F600}/u;")
        parse("/(a)\\1/u;")
    }
}
