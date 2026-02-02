package com.muedsa.js.parser

import com.muedsa.js.ast.*
import com.muedsa.js.lexer.Lexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ParserTest {

    private fun parse(code: String): BlockStatement {
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()
        val parser = Parser(tokens)
        return parser.parse()
    }

    private fun parseExpr(code: String): Expression {
        val program = parse("$code;")
        return (program.statements[0] as ExpressionStatement).expression
    }

    @Test
    fun `test variable declaration`() {
        val program = parse("var x = 10; let y; const z = 20;")
        assertEquals(3, program.statements.size)

        val varStmt = program.statements[0] as VarDeclaration
        assertEquals("var", varStmt.kind)
        assertEquals(1, varStmt.declarations.size)
        assertEquals("x", varStmt.declarations[0].name)
        assertIs<NumberLiteral>(varStmt.declarations[0].initializer)

        val letStmt = program.statements[1] as VarDeclaration
        assertEquals("let", letStmt.kind)
        assertEquals(1, letStmt.declarations.size)
        assertEquals("y", letStmt.declarations[0].name)
        assertEquals(null, letStmt.declarations[0].initializer)

        val constStmt = program.statements[2] as VarDeclaration
        assertEquals("const", constStmt.kind)
        assertEquals(1, constStmt.declarations.size)
        assertEquals("z", constStmt.declarations[0].name)
        assertIs<NumberLiteral>(constStmt.declarations[0].initializer)
    }

    @Test
    fun `test function declaration`() {
        val code = """
            function add(a, b) {
                return a + b;
            }
        """.trimIndent()
        val program = parse(code)

        val func = program.statements[0] as FunctionDeclaration
        assertEquals("add", func.name)
        assertEquals(listOf("a", "b"), func.params)

        val bodyStmt = func.body.statements[0] as ReturnStatement
        assertIs<BinaryOp>(bodyStmt.value)
    }

    @Test
    fun `test binary expression`() {
        val program = parse("a + b * c;")
        val stmt = program.statements[0] as ExpressionStatement
        val expr = stmt.expression as BinaryOp

        // Precedence check: a + (b * c)
        assertEquals("+", expr.operator)
        assertIs<Identifier>(expr.left)

        val right = expr.right as BinaryOp
        assertEquals("*", right.operator)
        assertIs<Identifier>(right.left)
        assertIs<Identifier>(right.right)
    }

    @Test
    fun `test assignment expression`() {
        val program = parse("x = y = 1;")
        val stmt = program.statements[0] as ExpressionStatement
        val expr = stmt.expression as AssignmentExpr

        // Right associativity: x = (y = 1)
        assertEquals("x", expr.target)

        val right = expr.value as AssignmentExpr
        assertEquals("y", right.target)
        assertIs<NumberLiteral>(right.value)
    }

    @Test
    fun `test if statement`() {
        val program = parse("if (cond) { x = 1; } else if (cond2) { x = 2; } else { x = 3; }")

        val ifStmt = program.statements[0] as IfStatement
        assertIs<Identifier>(ifStmt.condition)
        assertIs<BlockStatement>(ifStmt.consequent)

        val elseIf = ifStmt.alternate as IfStatement
        assertIs<Identifier>(elseIf.condition)

        val elseBlock = elseIf.alternate as BlockStatement
        assertEquals(1, elseBlock.statements.size)
    }

    @Test
    fun `test while statement`() {
        val program = parse("while (x > 0) { x--; }")
        val stmt = program.statements[0] as WhileStatement

        assertIs<BinaryOp>(stmt.condition)
        assertIs<BlockStatement>(stmt.body)
    }

    @Test
    fun `test for statement`() {
        val program = parse("for (var i = 0; i < 10; i++) { }")
        val stmt = program.statements[0] as ForStatement

        val init = stmt.init as VarDeclaration
        assertEquals("i", init.declarations[0].name)

        val test = stmt.condition as BinaryOp
        assertEquals("<", test.operator)

        val update = stmt.update as UpdateExpr
        assertEquals("++", update.operator)
    }

    @Test
    fun `test switch statement`() {
        val code = """
            switch (x) {
                case 1:
                    break;
                case 2:
                default:
                    return;
            }
        """.trimIndent()
        val program = parse(code)
        val stmt = program.statements[0] as SwitchStatement

        assertIs<Identifier>(stmt.discriminant)
        assertEquals(3, stmt.cases.size)

        assertEquals(1, stmt.cases[0].consequent.size) // break
        assertEquals(0, stmt.cases[1].consequent.size) // fallthrough
        assertEquals(1, stmt.cases[2].consequent.size) // return
        assertEquals(null, stmt.cases[2].test) // default
    }

    @Test
    fun `test try statement`() {
        val program = parse("try { } catch (e) { } finally { }")
        val stmt = program.statements[0] as TryStatement

        assertNotNull(stmt.block)
        assertNotNull(stmt.handler)
        assertEquals("e", stmt.handler.param)
        assertNotNull(stmt.finalizer)
    }

    @Test
    fun `test object literal`() {
        val program = parse("var obj = { x: 1 };")
        val stmt = program.statements[0] as VarDeclaration
        val obj = stmt.declarations[0].initializer as ObjectLiteral

        assertEquals(1, obj.properties.size)
    }

    @Test
    fun `test array literal`() {
        val program = parse("[1, 2, 4]")
        val stmt = program.statements[0] as ExpressionStatement
        val arr = stmt.expression as ArrayLiteral

        assertEquals(3, arr.elements.size)
        assertIs<NumberLiteral>(arr.elements[0])
        assertIs<NumberLiteral>(arr.elements[1])
        assertIs<NumberLiteral>(arr.elements[2])
    }

    @Test
    fun `test member expression`() {
        val program = parse("obj.prop; obj['prop'];")
        val stmt1 = program.statements[0] as ExpressionStatement
        val expr1 = stmt1.expression as MemberExpr
        assertIs<Identifier>(expr1.property)
        assertEquals(false, expr1.computed)

        val stmt2 = program.statements[1] as ExpressionStatement
        val expr2 = stmt2.expression as MemberExpr
        assertIs<StringLiteral>(expr2.property)
        assertEquals(true, expr2.computed)
    }

    @Test
    fun `test call expression`() {
        val program = parse("func(1, 2);")
        val stmt = program.statements[0] as ExpressionStatement
        val expr = stmt.expression as CallExpr

        assertIs<Identifier>(expr.callee)
        assertEquals(2, expr.arguments.size)
    }

    @Test
    fun `test new expression`() {
        val program = parse("new Class();")
        val stmt = program.statements[0] as ExpressionStatement
        val expr = stmt.expression as NewExpr

        assertIs<Identifier>(expr.objConstructor)
        assertEquals(0, expr.arguments.size)
    }

    @Test
    fun `test numeric literals`() {
        val code = "0xFF; 0o77; 0b11;"
        val program = parse(code)

        assertEquals(3, program.statements.size)

        val hex = (program.statements[0] as ExpressionStatement).expression as NumberLiteral
        assertEquals(255.0, hex.value)

        val oct = (program.statements[1] as ExpressionStatement).expression as NumberLiteral
        assertEquals(63.0, oct.value)

        val bin = (program.statements[2] as ExpressionStatement).expression as NumberLiteral
        assertEquals(3.0, bin.value)
    }

    @Test
    fun `test empty program`() {
        val program = parse("")
        assertEquals(0, program.statements.size)
    }

    @Test
    fun `test optional semicolons`() {
        val code = "var x = 1 var y = 2"
        val program = parse(code)
        assertEquals(2, program.statements.size)
    }

    @Test
    fun `test trailing commas`() {
        val arrayProgram = parse("[1, 2, ]")
        val array = (arrayProgram.statements[0] as ExpressionStatement).expression as ArrayLiteral
        assertEquals(2, array.elements.size)

        val objectProgram = parse("var o = { a: 1, b: 2, };")
        val obj = ((objectProgram.statements[0] as VarDeclaration).declarations[0].initializer as ObjectLiteral)
        assertEquals(2, obj.properties.size)
    }

    @Test
    fun `test deeply nested expression`() {
        // (((1)))
        val code = "((((1))))"
        val program = parse(code)
        val stmt = program.statements[0] as ExpressionStatement
        // 解析器为AST（抽象语法树）拆除了括号，但结构解析正确
        assertIs<NumberLiteral>(stmt.expression)
    }

    @Test
    fun `test operator precedence complex`() {
        // 1 + 2 * 3 -> 1 + (2 * 3)
        val program = parse("1 + 2 * 3")
        val binary = (program.statements[0] as ExpressionStatement).expression as BinaryOp
        assertEquals("+", binary.operator)
        assertIs<BinaryOp>(binary.right)
        assertEquals("*", binary.right.operator)
    }

    @Test
    fun `test multiple variable declarations`() {
        val program = parse("var a = 1, b = 2, c;")
        val stmt = program.statements[0] as VarDeclaration
        assertEquals("var", stmt.kind)
        assertEquals(3, stmt.declarations.size)

        assertEquals("a", stmt.declarations[0].name)
        assertIs<NumberLiteral>(stmt.declarations[0].initializer)

        assertEquals("b", stmt.declarations[1].name)
        assertIs<NumberLiteral>(stmt.declarations[1].initializer)

        assertEquals("c", stmt.declarations[2].name)
        assertEquals(null, stmt.declarations[2].initializer)
    }

    @Test
    fun `test function expression`() {
        val program = parse("var f = function(a) { return a; };")
        val varDecl = program.statements[0] as VarDeclaration
        val funcExpr = varDecl.declarations[0].initializer as FunctionExpression

        assertEquals(null, funcExpr.name) // Anonymous
        assertEquals(1, funcExpr.params.size)
        assertEquals("a", funcExpr.params[0])
        assertEquals(1, funcExpr.body.statements.size)
    }

    @Test
    fun `test named function expression`() {
        val program = parse("var f = function myName() {};")
        val funcExpr = (program.statements[0] as VarDeclaration).declarations[0].initializer as FunctionExpression
        assertEquals("myName", funcExpr.name)
    }

    @Test
    fun `test ternary operator`() {
        val expr = parseExpr("condition ? trueVal : falseVal") as ConditionalExpr

        assertIs<Identifier>(expr.condition)
        assertIs<Identifier>(expr.thenBranch)
        assertIs<Identifier>(expr.elseBranch)
    }

    @Test
    fun `test ternary precedence`() {
        // a = b ? c : d  =>  a = (b ? c : d)
        val expr = parseExpr("a = b ? c : d") as AssignmentExpr
        assertIs<ConditionalExpr>(expr.value)

        // a ? b : c ? d : e => a ? b : (c ? d : e) (Right associative)
        val ternary = parseExpr("a ? b : c ? d : e") as ConditionalExpr
        assertIs<ConditionalExpr>(ternary.elseBranch)
    }

    @Test
    fun `test coalesce operator`() {
        val expr = parseExpr("a ?? b") as CoalesceExpr
        assertIs<Identifier>(expr.left)
        assertIs<Identifier>(expr.right)
    }

    @Test
    fun `test logical precedence`() {
        // a || b && c  =>  a || (b && c)
        val expr = parseExpr("a || b && c") as BinaryOp
        assertEquals("||", expr.operator)

        val right = expr.right as BinaryOp
        assertEquals("&&", right.operator)
    }

    @Test
    fun `test compound assignment`() {
        val expr = parseExpr("a += 10") as CompoundAssignmentExpr
        assertEquals("a", expr.target)
        assertEquals("+=", expr.operator)
        assertIs<NumberLiteral>(expr.value)
    }

    @Test
    fun `test unary operators`() {
        var expr = parseExpr("!a") as UnaryOp
        assertEquals("!", expr.operator)

        expr = parseExpr("-a") as UnaryOp
        assertEquals("-", expr.operator)

        expr = parseExpr("typeof a") as UnaryOp
        assertEquals("typeof", expr.operator)

        expr = parseExpr("~a") as UnaryOp
        assertEquals("~", expr.operator)
    }

    @Test
    fun `test prefix update`() {
        val expr = parseExpr("++a") as UpdateExpr
        assertEquals("++", expr.operator)
        assertEquals(true, expr.prefix)
    }

    @Test
    fun `test postfix update`() {
        val expr = parseExpr("a--") as UpdateExpr
        assertEquals("--", expr.operator)
        assertEquals(false, expr.prefix)
    }

    @Test
    fun `test bitwise operators`() {
        // a | b & c  => a | (b & c)  (& has higher precedence than |)
        val expr = parseExpr("a | b & c") as BinaryOp
        assertEquals("|", expr.operator)

        val right = expr.right as BinaryOp
        assertEquals("&", right.operator)

        // Precedence: & > ^ > |
        val xorExpr = parseExpr("a & b ^ c") as BinaryOp
        assertEquals("^", xorExpr.operator)
        assertIs<BinaryOp>(xorExpr.left)
        assertEquals("&", xorExpr.left.operator)
    }

    @Test
    fun `test shift operators`() {
        val expr = parseExpr("a << b >> c") as BinaryOp
        // Left associative: (a << b) >> c
        assertEquals(">>", expr.operator)
        assertIs<BinaryOp>(expr.left)
        assertEquals("<<", expr.left.operator)
    }
}
