package com.muedsa.js

import com.muedsa.js.ast.ClassExpression
import com.muedsa.js.ast.ExportAllDeclaration
import com.muedsa.js.ast.ExportDefaultDeclaration
import com.muedsa.js.ast.ExportNamedDeclaration
import com.muedsa.js.ast.FunctionDeclaration
import com.muedsa.js.ast.FunctionExpression
import com.muedsa.js.ast.ImportDeclaration
import com.muedsa.js.ast.NumberLiteral
import com.muedsa.js.ast.StatementItem
import com.muedsa.js.ast.StringLiteral
import com.muedsa.js.ast.VarDeclaration
import com.muedsa.js.lexer.Lexer
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

    private fun parseModule(code: String) = Parser(Lexer(code).tokenize()).parseModule()

    private fun parseScript(code: String) = Parser(Lexer(code).tokenize()).parseProgram()

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
    fun `动态导入与import元属性给出明确诊断`() {
        val dynamicImport = assertFailsWith<ParseException> { parseModule("""import("./m.js");""") }
        assertTrue(dynamicImport.message.orEmpty().contains("Dynamic import"))
        val meta = assertFailsWith<ParseException> { parseModule("import.meta.url;") }
        assertTrue(meta.message.orEmpty().contains("import.meta"))
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
}
