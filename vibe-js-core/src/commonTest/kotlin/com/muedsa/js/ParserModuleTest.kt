package com.muedsa.js

import com.muedsa.js.ast.CallExpr
import com.muedsa.js.ast.ClassExpression
import com.muedsa.js.ast.ConditionalExpr
import com.muedsa.js.ast.ExportAllDeclaration
import com.muedsa.js.ast.ExportDefaultDeclaration
import com.muedsa.js.ast.ExportNamedDeclaration
import com.muedsa.js.ast.Expression
import com.muedsa.js.ast.ExpressionStatement
import com.muedsa.js.ast.FunctionDeclaration
import com.muedsa.js.ast.FunctionExpression
import com.muedsa.js.ast.ImportCallExpr
import com.muedsa.js.ast.ImportDeclaration
import com.muedsa.js.ast.ImportMetaExpr
import com.muedsa.js.ast.MemberExpr
import com.muedsa.js.ast.ModuleBindingKind
import com.muedsa.js.ast.ModuleProgram
import com.muedsa.js.ast.NumberLiteral
import com.muedsa.js.ast.ObjectLiteral
import com.muedsa.js.ast.RegExpLiteral
import com.muedsa.js.ast.Statement
import com.muedsa.js.ast.StatementItem
import com.muedsa.js.ast.StringLiteral
import com.muedsa.js.ast.UnaryOp
import com.muedsa.js.ast.VarDeclaration
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模块目标（`Parser.parseModule`）的语法测试。
 *
 * 覆盖 L4.1 原型范围：`import` / `export` 各基础形式、模块早期错误、
 * 以及"脚本目标行为不变"（`parseProgram` 仍然拒绝 `import` / `export`）。
 * 执行语义（链接、live binding、求值顺序）不在本测试范围。
 */
class ParserModuleTest {

    private fun parseModule(code: String) =
        Parser(Lexer(code, moduleContext = true).tokenize()).parseModule()

    private fun parseScript(code: String) = Parser(Lexer(code).tokenize()).parseProgram()

    /** 取模块中第一个模块项的语句（用于只有普通语句的用例）。 */
    private fun firstStatement(module: ModuleProgram): Statement =
        assertIs<StatementItem>(module.items.first()).statement

    /** 取模块中第一个表达式语句的表达式。 */
    private fun firstExpression(module: ModuleProgram): Expression =
        assertIs<ExpressionStatement>(firstStatement(module)).expression

    @Test
    fun `模块恒为严格模式且不依赖 use strict 指令`() {
        // 模块代码始终按严格模式解析：函数内不允许 with，八进制字面量非法
        val module = parseModule("var x = 1;")
        assertTrue(module.strict)
        assertFailsWith<ParseException> { parseModule("with (o) { x = 1; }") }
        assertFailsWith<ParseException> { parseModule("var x = 010;") }
        // 同一段代码作为脚本是合法的（sloppy 脚本允许八进制字面量）
        val script = parseScript("var x = 010;")
        assertTrue(!script.strict)
    }

    @Test
    fun `模块目标禁用 Annex B HTML 注释而脚本目标允许`() {
        // 脚本目标：Annex B 的 `<!--` 与行首 `-->` 按单行注释跳过
        parseScript("<!-- comment\nvar x = 1;")
        parseScript("-->\nvar x = 1;")
        // 模块目标：同样的注释是词法错误（必须在 Lexer 阶段声明模块语境）
        assertFailsWith<LexerException> { parseModule("<!-- comment\nvar x = 1;") }
        assertFailsWith<LexerException> { parseModule("-->\nvar x = 1;") }
        // 普通行注释与块注释在模块中仍然合法
        parseModule("// line\n/* block */ export const a = 1;")
    }

    @Test
    fun `副作用导入只产生模块请求`() {
        val module = parseModule("""import "./side-effect.js";""")
        val declaration = assertIs<ImportDeclaration>(module.items.single())
        assertTrue(declaration.isSideEffectOnly)
        assertEquals("./side-effect.js", declaration.moduleRequest)
        assertEquals(listOf("./side-effect.js"), module.requestedModules)
        // 规范里副作用导入仍有 ImportEntry（ImportName 为 all 哨兵、无本地名）
        assertEquals("*", module.importEntries.single().importName)
        assertEquals("", module.importEntries.single().localName)
        // 没有语句需要提升
        assertTrue(module.statements.isEmpty())
    }

    @Test
    fun `默认导入与具名导入的组合`() {
        val module = parseModule("""import a, {b as c} from "./m.js";""")
        val declaration = assertIs<ImportDeclaration>(module.items.single())
        assertEquals(listOf("default", "b"), declaration.specifiers.map { it.importedName })
        assertEquals(listOf("a", "c"), declaration.specifiers.map { it.localName })
        assertNull(declaration.namespaceImport)
        assertEquals(
            listOf("default" to "a", "b" to "c"),
            module.importEntries.map { it.importName to it.localName },
        )
        // 导入绑定进入模块顶层词法作用域
        assertEquals(listOf("a", "c"), module.lexicallyDeclaredNames)
    }

    @Test
    fun `命名空间导入与默认导入组合`() {
        val module = parseModule("""import d, * as ns from "./m.js";""")
        val declaration = assertIs<ImportDeclaration>(module.items.single())
        assertEquals("ns", declaration.namespaceImport)
        assertEquals(listOf("default"), declaration.specifiers.map { it.importedName })
        assertEquals(
            listOf("default" to "d", "*" to "ns"),
            module.importEntries.map { it.importName to it.localName },
        )
    }

    @Test
    fun `具名导入允许空列表与末尾逗号`() {
        val empty = parseModule("""import {} from "./m.js";""")
        val emptyDeclaration = assertIs<ImportDeclaration>(empty.items.single())
        assertTrue(emptyDeclaration.specifiers.isEmpty())
        assertTrue(!emptyDeclaration.isSideEffectOnly)

        val trailing = parseModule("""import {a,} from "./m.js";""")
        assertEquals(listOf("a"), assertIs<ImportDeclaration>(trailing.items.single()).specifiers.map { it.localName })
    }

    @Test
    fun `导出内联声明生成同名导出表项`() {
        val module = parseModule("""export const x = 1, y = 2; export var z = 3;""")
        val declaration = assertIs<ExportNamedDeclaration>(module.items[0])
        assertIs<VarDeclaration>(declaration.declaration)
        assertEquals(
            listOf("x", "y", "z"),
            module.exportEntries.map { it.exportName },
        )
        // 都是本地导出：没有模块请求与导入名
        assertTrue(module.exportEntries.all { it.moduleRequest == null && it.importName == null })
        // let/const 属于词法声明，export var 属于模块 var 作用域
        assertEquals(listOf("x", "y"), module.lexicallyDeclaredNames)
        assertEquals(listOf("z"), module.varDeclarations)
    }

    @Test
    fun `模块顶层函数属于词法声明`() {
        val module = parseModule("""export function f() { var inner = 1; } f();""")
        val declaration = assertIs<ExportNamedDeclaration>(module.items.first())
        assertIs<FunctionDeclaration>(declaration.declaration)
        // 顶层 function 不进入模块 var 作用域，而是词法声明
        assertEquals(listOf("f"), module.functionDeclarations.map { it.name })
        assertTrue(module.varDeclarations.isEmpty())
        assertEquals(listOf("f"), module.lexicallyDeclaredNames)
        // 普通语句按源码顺序保留（import/export 外壳被剥掉）
        assertEquals(2, module.statements.size)
        assertIs<StatementItem>(module.items.last())
    }

    @Test
    fun `未导出的顶层词法声明进入词法名集合`() {
        val module = parseModule("let a = 1; const b = 2; class C {}")
        assertEquals(listOf("a", "b", "C"), module.lexicallyDeclaredNames)
    }

    @Test
    fun `默认导出表达式使用内部名字`() {
        val module = parseModule("export default 1;")
        val declaration = assertIs<ExportDefaultDeclaration>(module.items.single())
        assertNull(declaration.declaration)
        assertIs<NumberLiteral>(declaration.expression)
        assertEquals("default", module.exportEntries.single().exportName)
        assertEquals("*default*", module.exportEntries.single().localName)
        // 表达式形态不产生顶层声明
        assertTrue(module.lexicallyDeclaredNames.isEmpty())
    }

    @Test
    fun `具名默认导出函数同时建立本地绑定`() {
        val module = parseModule("export default function named() {}")
        val declaration = assertIs<ExportDefaultDeclaration>(module.items.single())
        val function = assertIs<FunctionDeclaration>(declaration.declaration)
        assertEquals("named", function.name)
        assertEquals("named", module.exportEntries.single().localName)
        assertEquals(listOf("named"), module.lexicallyDeclaredNames)
    }

    @Test
    fun `匿名默认导出函数与类按表达式处理`() {
        val function = parseModule("export default function () {}")
        val functionDeclaration = assertIs<ExportDefaultDeclaration>(function.items.single())
        assertIs<FunctionExpression>(functionDeclaration.expression)

        val clazz = parseModule("export default class {}")
        val classDeclaration = assertIs<ExportDefaultDeclaration>(clazz.items.single())
        assertIs<ClassExpression>(classDeclaration.expression)

        // 带继承的匿名类同样是表达式
        val derived = parseModule("export default class extends Base {}")
        assertIs<ClassExpression>(assertIs<ExportDefaultDeclaration>(derived.items.single()).expression)
    }

    @Test
    fun `导出子句的重命名与再导出`() {
        val local = parseModule("var a = 1; export {a as b};")
        val specifier = assertIs<ExportNamedDeclaration>(local.items.last()).specifiers.single()
        assertEquals("a", specifier.localName)
        assertEquals("b", specifier.exportName)
        val localEntry = local.exportEntries.single()
        assertEquals("b", localEntry.exportName)
        assertEquals("a", localEntry.localName)
        assertNull(localEntry.moduleRequest)

        val reExport = parseModule("""export {default as d} from "./m.js";""")
        val entry = reExport.exportEntries.single()
        assertEquals("d", entry.exportName)
        assertEquals("./m.js", entry.moduleRequest)
        assertEquals("default", entry.importName)
        assertNull(entry.localName)
        // 间接导出不建立本地绑定
        assertTrue(reExport.lexicallyDeclaredNames.isEmpty())
    }

    @Test
    fun `本地导出的绑定必须已在模块顶层声明`() {
        // 未声明的本地导出是早期错误；声明可以出现在导出语句之后
        assertFailsWith<ParseException> { parseModule("export {a};") }
        assertFailsWith<ParseException> { parseModule("export {a as b};") }
        parseModule("export {a}; var a = 1;")
        parseModule("export {f}; function f() {}")
        parseModule("export {C}; class C {}")
        parseModule("""export {a as b}; import {a} from "./m.js";""")
        // 块级声明不属于模块作用域，不能作为本地导出的绑定
        assertFailsWith<ParseException> { parseModule("export {a}; { let a = 1; }") }
        // 间接导出与星号导出不要求本地绑定
        parseModule("""export {a as b} from "./m.js";""")
        parseModule("""export * from "./m.js";""")
        parseModule("""export * as ns from "./m.js";""")
    }

    @Test
    fun `本地导出不能把字符串字面量用作绑定名`() {
        // ExportDeclaration 的早期错误：无 from 时 ReferencedBindings 不能含字符串
        assertFailsWith<ParseException> {
            parseModule("let a = 1; export { \"a\" };")
        }
        assertFailsWith<ParseException> {
            parseModule("let a = 1; export { \"a\" as b };")
        }
        // 带 from 的再导出允许字符串导入名/导出名
        parseModule("export { \"a\" } from \"./m.js\";")
        parseModule("export { \"a\" as \"b\" } from \"./m.js\";")
        // 本地绑定名是标识符、仅导出名是字符串时合法
        parseModule("let a = 1; export { a as \"b\" };")
    }

    @Test
    fun `模块导入导出名必须是 well-formed Unicode`() {
        val invalid = listOf(
            "export { \"\\uD83C\" } from \"./m.js\";",
            "export { a as \"\\uD83C\" } from \"./m.js\";",
            "import { \"\\uD83C\" as x } from \"./m.js\";",
            "export * as \"\\uD83C\" from \"./m.js\";",
            "let a = 1; export { a as \"\\uD83C\" };",
        )
        for (code in invalid) {
            assertFailsWith<ParseException>(code) { parseModule(code) }
        }
        // 合法的 astral 名称（U+1F319）可以导出
        parseModule("let a = 1; export { a as \"\\uD83C\\uDF19\" };")
    }

    @Test
    fun `export default 后的正则字面量按表达式解析`() {
        // `default` 之后是 AssignmentExpression 起始位置，`/` 必须读成正则而不是除号
        val module = parseModule("export default /foo/;")
        val declaration = assertIs<ExportDefaultDeclaration>(module.items.single())
        assertIs<RegExpLiteral>(declaration.expression)
    }

    @Test
    fun `星号导出与命名空间导出`() {
        val star = parseModule("""export * from "./m.js";""")
        assertIs<ExportAllDeclaration>(star.items.single())
        val starEntry = star.exportEntries.single()
        assertNull(starEntry.exportName)
        assertEquals("./m.js", starEntry.moduleRequest)
        assertEquals("*", starEntry.importName)
        assertEquals(listOf("./m.js"), star.requestedModules)

        val namespace = parseModule("""export * as ns from "./m.js";""")
        val namespaceEntry = namespace.exportEntries.single()
        assertEquals("ns", namespaceEntry.exportName)
        assertEquals("./m.js", namespaceEntry.moduleRequest)
        assertEquals("*", namespaceEntry.importName)
        assertNull(namespaceEntry.localName)
    }

    @Test
    fun `重复导出名是早期错误`() {
        assertFailsWith<ParseException> { parseModule("export const a = 1; export const a = 2;") }
        assertFailsWith<ParseException> { parseModule("export default 1; export default 2;") }
        assertFailsWith<ParseException> { parseModule("export default 1; export {x as default};") }
    }

    @Test
    fun `导入绑定与顶层声明冲突是早期错误`() {
        assertFailsWith<ParseException> {
            parseModule("""import {a} from "./m.js"; let a = 1;""")
        }
        assertFailsWith<ParseException> {
            parseModule("""import {a} from "./m.js"; var a = 1;""")
        }
        assertFailsWith<ParseException> {
            parseModule("""import {a, a as b} from "./m.js"; import {b} from "./n.js";""")
        }
    }

    @Test
    fun `模块顶层函数与var声明重名是早期错误`() {
        // 脚本里合法（都属于 var 声明域）
        parseScript("var f; function f() {}")
        // 模块里 function 是词法声明，与 var 重名是早期错误
        assertFailsWith<ParseException> { parseModule("var f; function f() {}") }
        assertFailsWith<ParseException> { parseModule("function f() {} var f;") }
    }

    @Test
    fun `脚本目标仍然拒绝import与export`() {
        assertFailsWith<ParseException> { parseScript("""import {a} from "./m.js";""") }
        assertFailsWith<ParseException> { parseScript("export const a = 1;") }
    }

    @Test
    fun `脚本目标拒绝import元属性与动态导入`() {
        // 本切片只把两种表达式接入模块目标；脚本中 import 仍是保留字
        assertFailsWith<ParseException> { parseScript("import.meta.url;") }
        assertFailsWith<ParseException> { parseScript("import(\"./m.js\");") }
        // 模块目标下二者都已可用
        parseModule("import.meta.url;")
        parseModule("import(\"./m.js\");")
    }

    @Test
    fun `模块请求去重且保持源码顺序`() {
        val module = parseModule(
            """
            import "./a.js";
            import {x} from "./b.js";
            export {y} from "./a.js";
            export * from "./c.js";
            import {z} from "./b.js";
            """.trimIndent()
        )
        assertEquals(listOf("./a.js", "./b.js", "./c.js"), module.requestedModules)
    }

    @Test
    fun `导入声明只能出现在模块顶层`() {
        assertFailsWith<ParseException> {
            parseModule("""function f() { import {a} from "./m.js"; }""")
        }
        assertFailsWith<ParseException> {
            parseModule("""{ export const a = 1; }""")
        }
    }

    @Test
    fun `模块说明符必须是字符串字面量`() {
        assertFailsWith<ParseException> { parseModule("import {a} from m;") }
        assertFailsWith<ParseException> { parseModule("import a;") }
        assertFailsWith<ParseException> { parseModule("import {a} from \"m\" extra;") }
    }

    @Test
    fun `默认导出字符串字面量不产生顶层声明`() {
        val module = parseModule("""export default "text";""")
        val expression = assertIs<ExportDefaultDeclaration>(module.items.single()).expression
        assertEquals("text", assertIs<StringLiteral>(expression).value)
        assertEquals("*default*", module.exportEntries.single().localName)
    }

    // ================================================================
    // L4.1b：模块表达式层语法与剩余早期错误
    // ================================================================

    @Test
    fun `import元属性解析为独立节点`() {
        val module = parseModule("const url = import.meta.url;")
        val declaration = assertIs<VarDeclaration>(firstStatement(module))
        val member = assertIs<MemberExpr>(declaration.declarations.single().initializer)
        val meta = assertIs<ImportMetaExpr>(member.obj)
        // 位置覆盖整个 import.meta（起始列 13，结束列 24；结束位置是最后一个字符之后）
        assertEquals(1, meta.range.start.line)
        assertEquals(13, meta.range.start.column)
        assertEquals(24, meta.range.end.column)
        // 元属性不是导入声明，不产生模块请求
        assertTrue(module.requestedModules.isEmpty())
    }

    @Test
    fun `import元属性必须紧跟meta`() {
        assertFailsWith<ParseException> { parseModule("import.foo;") }
        assertFailsWith<ParseException> { parseModule("import.meta2;") }
        assertFailsWith<ParseException> { parseModule("import[\"meta\"];") }
        // meta 不能使用转义写法
        assertFailsWith<ParseException> { parseModule("import.m\\u0065ta;") }
    }

    @Test
    fun `import元属性与动态导入在嵌套函数和模板中可用`() {
        parseModule("function f() { return import.meta.url; }")
        parseModule("const f = () => import.meta;")
        parseModule("const p = import(\"./m.js\");")
        // 模板表达式由子 Parser 解析，必须继承模块语境
        parseModule("const u = `\${import.meta.url}`;")
        parseModule("const p = `\${import(\"./m.js\")}`;")
    }

    @Test
    fun `动态导入要求至少一个参数且允许一个两个参数与尾逗号`() {
        assertFailsWith<ParseException> { parseModule("import();") }

        val one = assertIs<ImportCallExpr>(firstExpression(parseModule("import(\"./m.js\");")))
        assertEquals(1, one.arguments.size)
        assertIs<StringLiteral>(one.specifier)
        assertNull(one.options)

        val two = assertIs<ImportCallExpr>(
            firstExpression(parseModule("import(\"./m.js\", { with: { type: \"json\" } });"))
        )
        assertEquals(2, two.arguments.size)
        assertIs<StringLiteral>(two.specifier)
        assertIs<ObjectLiteral>(two.options)

        // 一个实参与两个实参都允许尾逗号
        val trailing = assertIs<ImportCallExpr>(firstExpression(parseModule("import(\"./m.js\",);")))
        assertEquals(1, trailing.arguments.size)
        val trailingTwo = assertIs<ImportCallExpr>(
            firstExpression(parseModule("import(\"./m.js\", {},);"))
        )
        assertEquals(2, trailingTwo.arguments.size)
    }

    @Test
    fun `动态导入超过两个参数是早期错误`() {
        val error = assertFailsWith<ParseException> {
            parseModule("import(\"a\", \"b\", \"c\");")
        }
        assertTrue(error.message.orEmpty().contains("at most 2"))
        // 错误位置指向第三个实参
        assertEquals(1, error.range.start.line)
        assertEquals(18, error.range.start.column)
    }

    @Test
    fun `动态导入参数必须是表达式而非展开`() {
        val error = assertFailsWith<ParseException> { parseModule("import(...args);") }
        assertTrue(error.message.orEmpty().contains("Spread"))
        assertFailsWith<ParseException> { parseModule("import(\"m\", ...options);") }
    }

    @Test
    fun `模块顶层await解析为await表达式`() {
        val await = assertIs<UnaryOp>(firstExpression(parseModule("await fetchData();")))
        assertEquals("await", await.operator)
        assertIs<CallExpr>(await.operand)
    }

    @Test
    fun `模块顶层await可出现在导出与条件表达式`() {
        val exported = parseModule("export default await 1;")
        assertIs<UnaryOp>(assertIs<ExportDefaultDeclaration>(exported.items.single()).expression)

        val conditional = parseModule("const x = await a ? await b : await c;")
        val initializer = assertIs<VarDeclaration>(firstStatement(conditional))
            .declarations.single().initializer
        val ternary = assertIs<ConditionalExpr>(initializer)
        assertIs<UnaryOp>(ternary.condition)
        assertIs<UnaryOp>(ternary.thenBranch)
        assertIs<UnaryOp>(ternary.elseBranch)
    }

    @Test
    fun `模块顶层与 async 函数体内的 await 可解析而非 async 函数体拒绝`() {
        val top = assertIs<UnaryOp>(firstExpression(parseModule("await 1;")))
        assertEquals("await", top.operator)

        parseModule("async function g() { return await 2; }")
        parseModule("const g = async () => await 2;")
        assertFailsWith<ParseException> { parseModule("function f() { return await 1; }") }
        assertFailsWith<ParseException> { parseModule("const f = () => await 1;") }
        assertFailsWith<ParseException> {
            parseModule("function f() { return `\${await 1}`; }")
        }
    }

    @Test
    fun `模块顶层await在模板表达式中可解析`() {
        parseModule("const x = `\${await 1}`;")
    }

    @Test
    fun `模块顶层await后的正则字面量可解析`() {
        // await 与 yield 一样是上下文相关关键字，`/` 必须按正则字面量读取
        parseModule("await /a/;")
        parseModule("const p = await /a/g;")
        parseModule("export default await /a/;")
    }

    @Test
    fun `new 不能直接以 import 调用作为构造器`() {
        // import() 是 CallExpression 而不是 MemberExpression，裸 new import() 是语法错误
        assertFailsWith<ParseException> { parseModule("new import(\"m\");") }
        assertFailsWith<ParseException> { parseModule("new import(\"m\", {});") }
        assertFailsWith<ParseException> { parseModule("new import(\"m\")();") }
        // 括号形式与成员链形式是合法语法，运行期才因不可构造抛 TypeError
        parseModule("new (import(\"m\"));")
        parseModule("new import(\"m\").x;")
    }

    @Test
    fun `匿名默认导出函数与类后可直接跟下一条语句`() {
        // HoistableDeclaration / ClassDeclaration 形式不要求分号，ASI 不是必要条件
        parseModule("export default function () {} if (true) {}")
        parseModule("export default function* () {} if (true) {}")
        parseModule("export default class {} if (true) {}")
        parseModule("export default class extends Base {} var x = 1;")
    }

    @Test
    fun `模块顶层支持 for await 与 using 声明`() {
        // 模块顶层是 [+Await] 语境，for await / await using 与顶层 await 一样可解析
        parseModule("for await (const x of []) {}")
        parseModule("for await (x of []) {}")
        parseModule("using x = null;")
        parseModule("await using x = null;")
        parseModule("for (await using of of []) {}")
        // 脚本顶层仍不允许 using 声明（与 V8 行为一致）
        assertFailsWith<ParseException> { parseScript("using x = null;") }
    }

    @Test
    fun `模块中await不能作为绑定名或标识符`() {
        val invalid = listOf(
            "var await = 1;",
            "let await = 1;",
            "const await = 1;",
            "function await() {}",
            "function f(await) {}",
            "const f = await => 1;",
            "class await {}",
            "try {} catch (await) {}",
            "import {await} from \"./m.js\";",
            "var x = await;",
            "await: 1;",
            // 转义写法在模块中同样不能绕过保留字限制
            "var aw\\u0061it = 1;",
        )
        for (code in invalid) {
            assertFailsWith<ParseException>(code) { parseModule(code) }
        }
    }

    @Test
    fun `模块中await仍可作为属性名`() {
        parseModule("obj.await;")
        parseModule("const o = { await: 1 };")
        parseModule("class C { await() {} }")
    }

    @Test
    fun `模块中class字段与static块内await仍是保留字`() {
        assertFailsWith<ParseException> { parseModule("class C { x = await 1; }") }
        assertFailsWith<ParseException> { parseModule("class C { static { await 1; } }") }
        assertFailsWith<ParseException> { parseModule("function f(x = await 1) {}") }
    }

    @Test
    fun `模块顶层super是早期错误`() {
        assertFailsWith<ParseException> { parseModule("super.x;") }
        assertFailsWith<ParseException> { parseModule("super();") }
        // 箭头函数继承外层 super 语境，模块顶层同样非法
        assertFailsWith<ParseException> { parseModule("const f = () => super.x;") }
    }

    @Test
    fun `模块顶层new_target是早期错误`() {
        assertFailsWith<ParseException> { parseModule("new.target;") }
        assertFailsWith<ParseException> { parseModule("const f = () => new.target;") }
    }

    @Test
    fun `模块函数内的super属性与new_target合法`() {
        // 普通函数允许 new.target，方法允许 super 属性，派生类构造器允许 super()
        parseModule("function F() { return new.target; }")
        parseModule("const o = { m() { return super.x; } };")
        parseModule("class B {} class D extends B { constructor() { super(); } m() { return super.x; } }")
    }

    @Test
    fun `模块禁用AnnexB的单语句函数声明`() {
        assertFailsWith<ParseException> { parseModule("if (x) function f() {}") }
        assertFailsWith<ParseException> { parseModule("if (x) function f() {} else function g() {}") }
        assertFailsWith<ParseException> { parseModule("while (x) function f() {}") }
        assertFailsWith<ParseException> { parseModule("label: function f() {}") }
        // 旧式 for-in 初始化器同样是 Annex B 的 sloppy 扩展
        assertFailsWith<ParseException> { parseModule("for (var x = 0 in obj) {}") }
        // 同样的 if 分支函数声明与 for-in 初始化器在 sloppy 脚本中按 Annex B 合法
        parseScript("if (x) function f() {}")
        parseScript("for (var x = 0 in obj) {}")
    }

    @Test
    fun `模块禁用sloppy块级函数重复声明`() {
        assertFailsWith<ParseException> {
            parseModule("{ function f() {} function f() {} }")
        }
        // sloppy 脚本允许块级同名普通函数（Annex B.3.3）
        parseScript("{ function f() {} function f() {} }")
    }

    @Test
    fun `模块顶层重复函数声明是早期错误`() {
        // 脚本中顶层 function 属于 var 语义，可以重复；模块中属于词法声明
        parseScript("function f() {} function f() {}")
        assertFailsWith<ParseException> { parseModule("function f() {} function f() {}") }
        assertFailsWith<ParseException> { parseModule("export function f() {} function f() {}") }
    }

    @Test
    fun `模块早期错误位置指向出错的token`() {
        val meta = assertFailsWith<ParseException> { parseModule("import.foo;") }
        assertEquals(1, meta.range.start.line)
        assertEquals(8, meta.range.start.column)

        val binding = assertFailsWith<ParseException> { parseModule("var await = 1;") }
        assertEquals(1, binding.range.start.line)
        assertEquals(5, binding.range.start.column)

        val parameter = assertFailsWith<ParseException> { parseModule("function f(await) {}") }
        assertEquals(1, parameter.range.start.line)
        assertEquals(12, parameter.range.start.column)
    }

    @Test
    fun `空子句导入导出仍进入模块请求`() {
        val module = parseModule(
            """
            import {} from "./a.js";
            export {} from "./b.js";
            """.trimIndent(),
        )
        assertEquals(listOf("./a.js", "./b.js"), module.requestedModules)
        assertTrue(module.importEntries.isEmpty())
        assertTrue(module.exportEntries.isEmpty())
    }

    @Test
    fun `parseModule 接受并保存 SourceInfo`() {
        val sourceInfo = SourceInfo(name = "mod.mjs", lineOffset = 3)
        val module = Parser(
            Lexer("export const x = 1;", moduleContext = true).tokenize(),
        ).parseModule(sourceInfo)
        assertEquals(sourceInfo, module.sourceInfo)
    }

    @Test
    fun `ImportEntry 与 ExportEntry 携带声明范围`() {
        val module = parseModule(
            """
            import { a } from "./a.js";
            export { a } from "./b.js";
            """.trimIndent(),
        )
        assertEquals(1, module.importEntries.single().range.start.line)
        assertEquals(2, module.exportEntries.single().range.start.line)
    }

    @Test
    fun `模块顶层绑定预计算为类型化元数据`() {
        val module = parseModule(
            """
            import { a as b } from "./m.js";
            export const c = 1;
            let d;
            var e;
            function f() {}
            class G {}
            export default 1;
            """.trimIndent(),
        )
        val kinds = module.bindings.associate { it.name to it.kind }
        assertEquals(ModuleBindingKind.IMPORT, kinds["b"])
        assertEquals(ModuleBindingKind.CONST, kinds["c"])
        assertEquals(ModuleBindingKind.LET, kinds["d"])
        assertEquals(ModuleBindingKind.VAR, kinds["e"])
        assertEquals(ModuleBindingKind.FUNCTION, kinds["f"])
        assertEquals(ModuleBindingKind.CLASS, kinds["G"])
        assertEquals(ModuleBindingKind.DEFAULT, kinds["*default*"])
        assertEquals("./m.js", module.bindings.single { it.name == "b" }.imported?.moduleRequest)
    }

    @Test
    fun `hasTopLevelAwait 只标记模块顶层 await`() {
        assertTrue(parseModule("await 1;").hasTopLevelAwait)
        assertTrue(parseModule("export default await 1;").hasTopLevelAwait)
        assertTrue(parseModule("if (x) { await y; }").hasTopLevelAwait)
        assertTrue(parseModule("for await (const x of xs) {}").hasTopLevelAwait)
        assertTrue(parseModule("const t = `\${await 1}`;").hasTopLevelAwait)
        assertTrue(!parseModule("async function f() { await 1; }").hasTopLevelAwait)
        assertTrue(!parseModule("function f() { return 1; }").hasTopLevelAwait)
    }
}
