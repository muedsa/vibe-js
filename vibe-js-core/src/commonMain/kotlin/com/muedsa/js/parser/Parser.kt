package com.muedsa.js.parser

import com.muedsa.js.ast.*
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.lexer.SourcePosition
import com.muedsa.js.lexer.SourceRange
import com.muedsa.js.lexer.TemplatePart
import com.muedsa.js.lexer.Token
import com.muedsa.js.lexer.TokenType

/**
 * JavaScript 语法解析器（递归下降）。
 *
 * 阶段 A 的目标：
 * - 覆盖既有语法 + 空语句；
 * - AST 节点不可变且携带源码范围；
 * - 在解析阶段收集变量提升信息（var/function），避免运行时反复扫描 AST；
 * - 记录脚本/函数的严格模式标志（由 "use strict" 指令决定）。
 *
 * 后续阶段会在此基础上扩展箭头函数、模板字符串、解构、class 等语法。
 */
class Parser(private val tokens: List<Token>) {

    /** 当前解析位置（tokens 下标）。 */
    private var pos = 0

    /** 当前所在的严格模式上下文；解析函数时会临时切换。 */
    private var strict = false

    /** 当前是否处于 async 函数体（用于解析 await）。 */
    private var asyncContext = false

    /** 可作为属性名/对象键的关键字 Token 类型（ES5 允许保留字作为属性名）。 */
    private val keywordTokenTypes: Set<TokenType> = setOf(
        TokenType.VAR, TokenType.LET, TokenType.CONST, TokenType.FUNCTION,
        TokenType.IF, TokenType.ELSE, TokenType.WHILE, TokenType.FOR,
        TokenType.RETURN, TokenType.TRUE, TokenType.FALSE, TokenType.NULL,
        TokenType.NEW, TokenType.THIS, TokenType.SWITCH, TokenType.CASE,
        TokenType.DEFAULT, TokenType.BREAK, TokenType.CONTINUE, TokenType.THROW,
        TokenType.TRY, TokenType.CATCH, TokenType.FINALLY, TokenType.TYPEOF,
        TokenType.DO, TokenType.DELETE, TokenType.VOID, TokenType.IN,
        TokenType.INSTANCEOF, TokenType.DEBUGGER, TokenType.WITH,
        TokenType.CLASS, TokenType.EXTENDS, TokenType.SUPER,
    )

    /** 赋值运算符集合。 */
    private val assignmentOperators: Set<TokenType> = setOf(
        TokenType.ASSIGN,
        TokenType.PLUS_ASSIGN,
        TokenType.MINUS_ASSIGN,
        TokenType.MULTIPLY_ASSIGN,
        TokenType.DIVIDE_ASSIGN,
        TokenType.MODULO_ASSIGN,
        TokenType.EXPONENT_ASSIGN,
        TokenType.LEFT_SHIFT_ASSIGN,
        TokenType.RIGHT_SHIFT_ASSIGN,
        TokenType.UNSIGNED_RIGHT_SHIFT_ASSIGN,
        TokenType.LOGICAL_AND_ASSIGN,
        TokenType.LOGICAL_OR_ASSIGN,
        TokenType.COALESCE_ASSIGN,
        TokenType.BITWISE_XOR_ASSIGN,
        TokenType.BITWISE_AND_ASSIGN,
        TokenType.BITWISE_OR_ASSIGN,
    )

    /**
     * 解析整个脚本，返回 Program 根节点。
     */
    fun parseProgram(): Program {
        strict = false
        val start = peek().range.start
        val body = mutableListOf<Statement>()
        var inDirectivePrologue = true

        while (!isAtEnd()) {
            val statement = parseStatement()
            body.add(statement)

            // 指令序言：开头连续的字符串字面量表达式语句
            if (inDirectivePrologue) {
                if (statement is ExpressionStatement && statement.expression is StringLiteral) {
                    if (statement.expression.value == "use strict") {
                        strict = true
                    }
                } else if (statement !is EmptyStatement) {
                    inDirectivePrologue = false
                }
            }
        }

        val hoist = collectHoisted(body)
        val end = tokens.lastOrNull()?.range?.end ?: start
        return Program(
            body = body,
            strict = strict,
            varDeclarations = hoist.varDeclarations,
            functionDeclarations = hoist.functionDeclarations,
            range = SourceRange(start, end),
        )
    }

    // ================================================================
    // 语句解析
    // ================================================================

    /**
     * 语句分发。
     */
    private fun parseStatement(): Statement {
        return when (peek().type) {
            TokenType.SEMICOLON -> {
                val token = advance()
                EmptyStatement(token.range)
            }

            TokenType.VAR, TokenType.LET, TokenType.CONST -> parseVarDeclaration()
            TokenType.FUNCTION -> parseFunctionDeclaration()
            TokenType.LBRACE -> parseBlockStatement()
            TokenType.IF -> parseIfStatement()
            TokenType.WHILE -> parseWhileStatement()
            TokenType.DO -> parseDoWhileStatement()
            TokenType.FOR -> parseForStatement()
            TokenType.SWITCH -> parseSwitchStatement()
            TokenType.BREAK -> parseBreakStatement()
            TokenType.CONTINUE -> parseContinueStatement()
            TokenType.THROW -> parseThrowStatement()
            TokenType.TRY -> parseTryStatement()
            TokenType.RETURN -> parseReturnStatement()
            TokenType.DEBUGGER -> {
                val token = advance()
                consumeSemicolon()
                DebuggerStatement(token.range)
            }

            TokenType.WITH -> parseWithStatement()
            TokenType.CLASS -> parseClassDeclaration()

            else -> {
                // async function 声明
                if (peek().type == TokenType.IDENTIFIER && peek().value == "async" &&
                    peek(1).type == TokenType.FUNCTION && !peek(1).precededByNewline
                ) {
                    return parseFunctionDeclaration(isAsync = true)
                }
                // 标签语句：identifier ':' statement
                if (peek().type == TokenType.IDENTIFIER && peek(1).type == TokenType.COLON) {
                    return parseLabeledStatement()
                }
                val expression = parseExpression()
                val start = expression.range.start
                consumeSemicolon()
                ExpressionStatement(expression, SourceRange(start, previous().range.end))
            }
        }
    }

    /** 标签语句。 */
    private fun parseLabeledStatement(): LabeledStatement {
        val labelToken = consume(TokenType.IDENTIFIER, "Expected label")
        consume(TokenType.COLON, "Expected ':' after label")
        val body = parseStatement()
        return LabeledStatement(labelToken.value, body, SourceRange(labelToken.range.start, body.range.end))
    }

    /** do-while 语句。 */
    private fun parseDoWhileStatement(): DoWhileStatement {
        val start = consume(TokenType.DO, "Expected 'do'").range.start
        val body = parseStatement()
        consume(TokenType.WHILE, "Expected 'while' after do body")
        consume(TokenType.LPAREN, "Expected '(' after 'while'")
        val condition = parseExpression()
        val end = consume(TokenType.RPAREN, "Expected ')' after do-while condition").range.end
        consumeSemicolon()
        return DoWhileStatement(body, condition, SourceRange(start, end))
    }

    /** with 语句。 */
    private fun parseWithStatement(): WithStatement {
        val start = consume(TokenType.WITH, "Expected 'with'").range.start
        if (strict) {
            throw error("Strict mode code may not include a with statement")
        }
        consume(TokenType.LPAREN, "Expected '(' after 'with'")
        val objectExpression = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after with expression")
        val body = parseStatement()
        return WithStatement(objectExpression, body, SourceRange(start, body.range.end))
    }

    /** class 声明。 */
    private fun parseClassDeclaration(): ClassDeclaration {
        val start = consume(TokenType.CLASS, "Expected 'class'").range.start
        val name = consume(TokenType.IDENTIFIER, "Expected class name")
        val superClass = if (match(TokenType.EXTENDS)) parseLeftHandSide() else null
        val members = parseClassBody()
        return ClassDeclaration(
            name = name.value,
            superClass = superClass,
            members = members,
            range = SourceRange(start, previous().range.end),
        )
    }

    /** class 表达式。 */
    private fun parseClassExpression(): ClassExpression {
        val start = consume(TokenType.CLASS, "Expected 'class'").range.start
        val name = if (check(TokenType.IDENTIFIER)) advance().value else null
        val superClass = if (match(TokenType.EXTENDS)) parseLeftHandSide() else null
        val members = parseClassBody()
        return ClassExpression(
            name = name,
            superClass = superClass,
            members = members,
            range = SourceRange(start, previous().range.end),
        )
    }

    /** class 成员列表（类体默认严格模式）。 */
    private fun parseClassBody(): List<ClassMember> {
        consume(TokenType.LBRACE, "Expected '{' after class header")
        val outerStrict = strict
        strict = true
        val members = mutableListOf<ClassMember>()
        try {
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                if (match(TokenType.SEMICOLON)) continue
                val memberStart = peek().range.start

                var isStatic = false
                if (peek().type == TokenType.IDENTIFIER && peek().value == "static" &&
                    canStartClassMember(1)
                ) {
                    isStatic = true
                    advance()
                }

                var kind = ClassMemberKind.METHOD
                var isAsync = false
                if (peek().type == TokenType.IDENTIFIER && canStartClassMember(1)) {
                    when (peek().value) {
                        "get" -> {
                            kind = ClassMemberKind.GET
                            advance()
                        }

                        "set" -> {
                            kind = ClassMemberKind.SET
                            advance()
                        }

                        "async" -> {
                            isAsync = true
                            advance()
                        }
                    }
                }

                val (key, computed) = parsePropertyKeyExpression()
                val isConstructor = !isStatic && !computed && kind == ClassMemberKind.METHOD &&
                    key is Identifier && key.name == "constructor"

                consume(TokenType.LPAREN, "Expected '(' in class member")
                val params = parseParametersAfterOpen()
                val bodyInfo = parseFunctionBody(isAsync)
                val function = FunctionExpression(
                    name = if (key is Identifier) key.name else "",
                    params = params,
                    body = bodyInfo.body,
                    strict = true,
                    isAsync = isAsync,
                    varDeclarations = bodyInfo.varDeclarations,
                    functionDeclarations = bodyInfo.functionDeclarations,
                    range = SourceRange(key.range.start, bodyInfo.body.range.end),
                )
                members.add(
                    ClassMember(
                        kind = if (isConstructor) ClassMemberKind.CONSTRUCTOR else kind,
                        isStatic = isStatic,
                        key = key,
                        computed = computed,
                        value = function,
                        range = SourceRange(memberStart, bodyInfo.body.range.end),
                    )
                )
            }
            consume(TokenType.RBRACE, "Expected '}' after class body")
        } finally {
            strict = outerStrict
        }
        return members
    }

    /** 判断 class 成员修饰符之后是否跟着成员名。 */
    private fun canStartClassMember(offset: Int): Boolean {
        val token = peek(offset)
        return when {
            token.type in setOf(
                TokenType.IDENTIFIER, TokenType.STRING,
                TokenType.NUMBER, TokenType.NUMBER_HEX,
                TokenType.NUMBER_OCT, TokenType.NUMBER_BIN,
                TokenType.LBRACKET,
            ) -> true

            else -> token.type in keywordTokenTypes
        }
    }

    /**
     * 变量声明：`var/let/const a = 1, b;`。
     *
     * @param requireSemicolon for 循环初始化中不需要自行消费分号
     */
    private fun parseVarDeclaration(requireSemicolon: Boolean = true): VarDeclaration {
        val start = peek().range.start
        val kindToken = advance()
        val kind = when (kindToken.type) {
            TokenType.VAR -> DeclarationKind.VAR
            TokenType.LET -> DeclarationKind.LET
            else -> DeclarationKind.CONST
        }

        val declarations = mutableListOf<VariableDeclarator>()
        do {
            val targetStart = peek().range.start
            val target: Expression = when {
                check(TokenType.LBRACKET) -> parseArrayLiteral()
                check(TokenType.LBRACE) -> parseObjectLiteral()
                else -> {
                    val nameToken = consume(TokenType.IDENTIFIER, "Expected identifier in declaration")
                    Identifier(nameToken.value, nameToken.range)
                }
            }
            var initializer: Expression? = null
            if (match(TokenType.ASSIGN)) {
                initializer = parseAssignment()
            }
            val end = initializer?.range?.end ?: target.range.end
            declarations.add(
                if (target is Identifier) {
                    VariableDeclarator(
                        name = target.name,
                        pattern = null,
                        initializer = initializer,
                        range = SourceRange(targetStart, end),
                    )
                } else {
                    VariableDeclarator(
                        name = null,
                        pattern = target,
                        initializer = initializer,
                        range = SourceRange(targetStart, end),
                    )
                }
            )
        } while (match(TokenType.COMMA))

        if (requireSemicolon) {
            consumeSemicolon()
        }

        return VarDeclaration(kind, declarations, SourceRange(start, previous().range.end))
    }

    /** 函数声明：`function name(params) { ... }`（可选 async）。 */
    private fun parseFunctionDeclaration(isAsync: Boolean = false): FunctionDeclaration {
        val start = peek().range.start
        if (isAsync) advance() // async
        consume(TokenType.FUNCTION, "Expected 'function'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name")
        val params = parseParameters()
        val bodyInfo = parseFunctionBody(isAsync)
        return FunctionDeclaration(
            name = name.value,
            params = params,
            body = bodyInfo.body,
            strict = bodyInfo.strict,
            isAsync = isAsync,
            varDeclarations = bodyInfo.varDeclarations,
            functionDeclarations = bodyInfo.functionDeclarations,
            range = SourceRange(start, bodyInfo.body.range.end),
        )
    }

    /** 函数表达式：`function [name](params) { ... }`（调用前已由 parsePrimary 消费 function）。 */
    private fun parseFunctionExpression(startPos: SourcePosition, isAsync: Boolean = false): FunctionExpression {
        val nameToken = if (check(TokenType.IDENTIFIER)) advance() else null
        val params = parseParameters()
        val bodyInfo = parseFunctionBody(isAsync)
        return FunctionExpression(
            name = nameToken?.value,
            params = params,
            body = bodyInfo.body,
            strict = bodyInfo.strict,
            isAsync = isAsync,
            varDeclarations = bodyInfo.varDeclarations,
            functionDeclarations = bodyInfo.functionDeclarations,
            range = SourceRange(startPos, bodyInfo.body.range.end),
        )
    }

    /**
     * 解析函数体，并返回函数体元信息（严格模式、变量提升）。
     *
     * 严格模式标志需要在解析函数体之前确定，以便嵌套函数正确继承，
     * 因此这里先扫描函数体开头的指令序言，再正式解析。
     */
    private fun parseFunctionBody(isAsync: Boolean = false): FunctionBodyInfo {
        val bodyStart = pos
        val bodyStrict = detectUseStrictDirective(bodyStart)
        val outerStrict = strict
        val outerAsync = asyncContext
        strict = outerStrict || bodyStrict
        asyncContext = isAsync

        val body = parseBlockStatement()
        val innerStrict = strict
        strict = outerStrict
        asyncContext = outerAsync

        val hoist = collectHoisted(body.statements)
        return FunctionBodyInfo(
            body = body,
            strict = innerStrict,
            varDeclarations = hoist.varDeclarations,
            functionDeclarations = hoist.functionDeclarations,
        )
    }

    /** 解析函数参数（左括号已消费），支持默认值、rest 与解构。 */
    private fun parseParametersAfterOpen(): List<Parameter> {
        val params = mutableListOf<Parameter>()
        if (!check(TokenType.RPAREN)) {
            do {
                val start = peek().range.start
                if (match(TokenType.ELLIPSIS)) {
                    val name = consume(TokenType.IDENTIFIER, "Expected rest parameter name")
                    params.add(
                        Parameter(
                            name = name.value,
                            pattern = null,
                            defaultValue = null,
                            rest = true,
                            range = SourceRange(start, name.range.end),
                        )
                    )
                    break
                }

                val target: Expression = when {
                    check(TokenType.LBRACKET) -> parseArrayLiteral()
                    check(TokenType.LBRACE) -> parseObjectLiteral()
                    else -> {
                        val name = consume(TokenType.IDENTIFIER, "Expected parameter name")
                        Identifier(name.value, name.range)
                    }
                }
                var defaultValue: Expression? = null
                if (match(TokenType.ASSIGN)) {
                    defaultValue = parseAssignment()
                }
                val end = defaultValue?.range?.end ?: target.range.end
                if (target is Identifier) {
                    params.add(
                        Parameter(
                            name = target.name,
                            pattern = null,
                            defaultValue = defaultValue,
                            rest = false,
                            range = SourceRange(start, end),
                        )
                    )
                } else {
                    params.add(
                        Parameter(
                            name = null,
                            pattern = target,
                            defaultValue = defaultValue,
                            rest = false,
                            range = SourceRange(start, end),
                        )
                    )
                }
            } while (match(TokenType.COMMA))
        }
        consume(TokenType.RPAREN, "Expected ')' after parameters")
        return params
    }

    /** 解析参数列表。 */
    private fun parseParameters(): List<Parameter> {
        consume(TokenType.LPAREN, "Expected '(' before parameters")
        return parseParametersAfterOpen()
    }

    /** 块语句 `{ ... }`。 */
    private fun parseBlockStatement(): BlockStatement {
        val start = consume(TokenType.LBRACE, "Expected '{'").range.start
        val statements = mutableListOf<Statement>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            statements.add(parseStatement())
        }
        val end = consume(TokenType.RBRACE, "Expected '}'").range.end
        return BlockStatement(statements, SourceRange(start, end))
    }

    /** if 语句。 */
    private fun parseIfStatement(): IfStatement {
        val start = consume(TokenType.IF, "Expected 'if'").range.start
        consume(TokenType.LPAREN, "Expected '(' after 'if'")
        val condition = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after if condition")
        val consequent = parseStatement()
        var alternate: Statement? = null
        if (match(TokenType.ELSE)) {
            alternate = parseStatement()
        }
        val end = (alternate ?: consequent).range.end
        return IfStatement(condition, consequent, alternate, SourceRange(start, end))
    }

    /** while 语句。 */
    private fun parseWhileStatement(): WhileStatement {
        val start = consume(TokenType.WHILE, "Expected 'while'").range.start
        consume(TokenType.LPAREN, "Expected '(' after 'while'")
        val condition = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after while condition")
        val body = parseStatement()
        return WhileStatement(condition, body, SourceRange(start, body.range.end))
    }

    /** for / for-in 语句。 */
    private fun parseForStatement(): Statement {
        val start = consume(TokenType.FOR, "Expected 'for'").range.start
        consume(TokenType.LPAREN, "Expected '(' after 'for'")

        when (forHeaderKind()) {
            ForHeaderKind.IN -> return parseForInOfStatement(start, isOf = false)
            ForHeaderKind.OF -> return parseForInOfStatement(start, isOf = true)
            ForHeaderKind.NONE -> Unit
        }

        var init: Statement? = null
        if (!match(TokenType.SEMICOLON)) {
            init = if (check(TokenType.VAR) || check(TokenType.LET) || check(TokenType.CONST)) {
                parseVarDeclaration(requireSemicolon = false)
            } else {
                val expression = parseExpression()
                ExpressionStatement(expression, expression.range)
            }
            consume(TokenType.SEMICOLON, "Expected ';' after for loop initializer")
        }

        var condition: Expression? = null
        if (!check(TokenType.SEMICOLON)) {
            condition = parseExpression()
        }
        consume(TokenType.SEMICOLON, "Expected ';' after for loop condition")

        var update: Expression? = null
        if (!check(TokenType.RPAREN)) {
            update = parseExpression()
        }
        consume(TokenType.RPAREN, "Expected ')' after for loop")

        val body = parseStatement()
        return ForStatement(init, condition, update, body, SourceRange(start, body.range.end))
    }

    /** for 头部类型。 */
    private enum class ForHeaderKind {
        NONE,
        IN,
        OF,
    }

    /**
     * 判断 `for (` 之后是普通 for、for-in 还是 for-of。
     *
     * 在括号深度 0 处、遇到分号之前出现 `in` / `of` 即为对应形式。
     */
    private fun forHeaderKind(): ForHeaderKind {
        var index = pos
        var depth = 0
        while (index < tokens.size) {
            when (tokens[index].type) {
                TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE -> depth++
                TokenType.RPAREN, TokenType.RBRACKET, TokenType.RBRACE -> {
                    if (depth == 0) return ForHeaderKind.NONE
                    depth--
                }

                TokenType.SEMICOLON -> if (depth == 0) return ForHeaderKind.NONE
                TokenType.IN -> if (depth == 0) return ForHeaderKind.IN
                TokenType.IDENTIFIER -> if (depth == 0 && tokens[index].value == "of") {
                    return ForHeaderKind.OF
                }

                TokenType.EOF -> return ForHeaderKind.NONE
                else -> Unit
            }
            index++
        }
        return ForHeaderKind.NONE
    }

    /** for-in / for-of 头部（`for (` 已消费）。 */
    private fun parseForInOfStatement(start: SourcePosition, isOf: Boolean): Statement {
        var declarationKind: DeclarationKind? = null
        var variableName: String? = null
        var pattern: Expression? = null
        var target: Expression? = null

        if (check(TokenType.VAR) || check(TokenType.LET) || check(TokenType.CONST)) {
            val kindToken = advance()
            declarationKind = when (kindToken.type) {
                TokenType.VAR -> DeclarationKind.VAR
                TokenType.LET -> DeclarationKind.LET
                else -> DeclarationKind.CONST
            }
            when {
                check(TokenType.LBRACKET) -> pattern = parseArrayLiteral()
                check(TokenType.LBRACE) -> pattern = parseObjectLiteral()
                else -> variableName = consume(
                    TokenType.IDENTIFIER,
                    "Expected variable name in for-in/of",
                ).value
            }
            // 兼容旧式 for (var k = 0 in obj)：初始化表达式仅解析并忽略
            if (match(TokenType.ASSIGN)) {
                parseAssignment()
            }
        } else {
            val expression = parseLeftHandSide()
            if (expression !is Identifier && expression !is MemberExpr &&
                expression !is ArrayLiteral && expression !is ObjectLiteral
            ) {
                throw error("Invalid left-hand side in for-in/of")
            }
            target = expression
        }

        if (isOf) {
            val ofToken = peek()
            if (ofToken.type == TokenType.IDENTIFIER && ofToken.value == "of") {
                advance()
            } else {
                throw error("Expected 'of' in for-of statement")
            }
        } else {
            consume(TokenType.IN, "Expected 'in' in for-in statement")
        }

        val right = parseAssignment()
        consume(TokenType.RPAREN, "Expected ')' after for-in/of")
        val body = parseStatement()
        val range = SourceRange(start, body.range.end)
        return if (isOf) {
            ForOfStatement(
                declarationKind = declarationKind,
                variableName = variableName,
                pattern = pattern,
                target = target,
                right = right,
                body = body,
                range = range,
            )
        } else {
            ForInStatement(
                declarationKind = declarationKind,
                variableName = variableName,
                pattern = pattern,
                target = target,
                right = right,
                body = body,
                range = range,
            )
        }
    }

    /** switch 语句。 */
    private fun parseSwitchStatement(): SwitchStatement {
        val start = consume(TokenType.SWITCH, "Expected 'switch'").range.start
        consume(TokenType.LPAREN, "Expected '(' after 'switch'")
        val discriminant = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after switch expression")
        consume(TokenType.LBRACE, "Expected '{' after switch")

        val cases = mutableListOf<SwitchCase>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            when {
                match(TokenType.CASE) -> {
                    val caseStart = previous().range.start
                    val test = parseExpression()
                    consume(TokenType.COLON, "Expected ':' after case expression")
                    val consequent = mutableListOf<Statement>()
                    while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) &&
                        !check(TokenType.RBRACE) && !isAtEnd()
                    ) {
                        consequent.add(parseStatement())
                    }
                    val end = consequent.lastOrNull()?.range?.end ?: previous().range.end
                    cases.add(SwitchCase(test, consequent, SourceRange(caseStart, end)))
                }

                match(TokenType.DEFAULT) -> {
                    val caseStart = previous().range.start
                    consume(TokenType.COLON, "Expected ':' after default")
                    val consequent = mutableListOf<Statement>()
                    while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) &&
                        !check(TokenType.RBRACE) && !isAtEnd()
                    ) {
                        consequent.add(parseStatement())
                    }
                    val end = consequent.lastOrNull()?.range?.end ?: previous().range.end
                    cases.add(SwitchCase(null, consequent, SourceRange(caseStart, end)))
                }

                else -> throw error("Expected 'case' or 'default' in switch statement")
            }
        }

        val end = consume(TokenType.RBRACE, "Expected '}' after switch body").range.end
        return SwitchStatement(discriminant, cases, SourceRange(start, end))
    }

    /** break 语句（可带标签）。 */
    private fun parseBreakStatement(): BreakStatement {
        val start = consume(TokenType.BREAK, "Expected 'break'").range.start
        var label: String? = null
        if (check(TokenType.IDENTIFIER) && !peek().precededByNewline) {
            label = advance().value
        }
        consumeSemicolon()
        return BreakStatement(label, SourceRange(start, previous().range.end))
    }

    /** continue 语句（可带标签）。 */
    private fun parseContinueStatement(): ContinueStatement {
        val start = consume(TokenType.CONTINUE, "Expected 'continue'").range.start
        var label: String? = null
        if (check(TokenType.IDENTIFIER) && !peek().precededByNewline) {
            label = advance().value
        }
        consumeSemicolon()
        return ContinueStatement(label, SourceRange(start, previous().range.end))
    }

    /** throw 语句。 */
    private fun parseThrowStatement(): ThrowStatement {
        val start = consume(TokenType.THROW, "Expected 'throw'").range.start
        if (peek().precededByNewline) {
            throw error("Illegal newline after throw")
        }
        val argument = parseExpression()
        consumeSemicolon()
        return ThrowStatement(argument, SourceRange(start, argument.range.end))
    }

    /** return 语句。 */
    private fun parseReturnStatement(): ReturnStatement {
        val start = consume(TokenType.RETURN, "Expected 'return'").range.start
        var value: Expression? = null
        // ASI：return 后如果有换行，则返回 undefined
        if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !isAtEnd() && !peek().precededByNewline) {
            value = parseExpression()
        }
        consumeSemicolon()
        return ReturnStatement(value, SourceRange(start, previous().range.end))
    }

    /** try-catch-finally 语句。 */
    private fun parseTryStatement(): TryStatement {
        val start = consume(TokenType.TRY, "Expected 'try'").range.start
        val block = parseBlockStatement()

        var handler: CatchClause? = null
        if (match(TokenType.CATCH)) {
            val catchStart = previous().range.start
            var param: String? = null
            var pattern: Expression? = null
            if (match(TokenType.LPAREN)) {
                when {
                    check(TokenType.LBRACKET) -> pattern = parseArrayLiteral()
                    check(TokenType.LBRACE) -> pattern = parseObjectLiteral()
                    else -> param = consume(
                        TokenType.IDENTIFIER,
                        "Expected catch parameter",
                    ).value
                }
                consume(TokenType.RPAREN, "Expected ')' after catch parameter")
            }
            val body = parseBlockStatement()
            handler = CatchClause(param, pattern, body, SourceRange(catchStart, body.range.end))
        }

        var finalizer: BlockStatement? = null
        if (match(TokenType.FINALLY)) {
            finalizer = parseBlockStatement()
        }

        if (handler == null && finalizer == null) {
            throw error("Missing catch or finally after try")
        }

        val end = finalizer?.range?.end ?: handler?.range?.end ?: block.range.end
        return TryStatement(block, handler, finalizer, SourceRange(start, end))
    }

    // ================================================================
    // 表达式解析（按优先级从低到高）
    // ================================================================

    /** 逗号表达式（最低优先级）。 */
    private fun parseExpression(): Expression {
        val first = parseAssignment()
        if (!check(TokenType.COMMA)) return first

        val expressions = mutableListOf(first)
        while (match(TokenType.COMMA)) {
            expressions.add(parseAssignment())
        }
        return SequenceExpr(
            expressions,
            SourceRange(first.range.start, expressions.last().range.end),
        )
    }

    /** 赋值表达式（右结合，箭头函数也在此层解析）。 */
    private fun parseAssignment(): Expression {
        parseArrowFunctionOrNull()?.let { return it }
        val left = parseConditional()
        val token = peek()
        if (token.type in assignmentOperators) {
            if (left !is Identifier && left !is MemberExpr &&
                left !is ArrayLiteral && left !is ObjectLiteral
            ) {
                throw error("Invalid assignment target")
            }
            advance()
            val value = parseAssignment()
            return AssignmentExpr(
                target = left,
                operator = token.value,
                value = value,
                range = SourceRange(left.range.start, value.range.end),
            )
        }
        return left
    }

    /**
     * 尝试解析箭头函数；不是箭头函数时返回 null。
     */
    private fun parseArrowFunctionOrNull(): Expression? {
        // async 箭头函数：async x => / async (x) =>
        if (peek().type == TokenType.IDENTIFIER && peek().value == "async" &&
            !peek(1).precededByNewline
        ) {
            val next = peek(1)
            if (next.type == TokenType.IDENTIFIER && peek(2).type == TokenType.ARROW &&
                !peek(2).precededByNewline
            ) {
                val start = advance().range.start
                val paramToken = advance()
                consume(TokenType.ARROW, "Expected '=>'")
                return parseArrowBody(
                    start,
                    listOf(Parameter(paramToken.value, null, null, false, paramToken.range)),
                    isAsync = true,
                )
            }
            if (next.type == TokenType.LPAREN && isArrowAfterParens(1)) {
                val start = advance().range.start
                val params = parseParameters()
                consume(TokenType.ARROW, "Expected '=>'")
                return parseArrowBody(start, params, isAsync = true)
            }
        }

        // 单参数箭头：x =>
        if (peek().type == TokenType.IDENTIFIER && peek(1).type == TokenType.ARROW &&
            !peek(1).precededByNewline
        ) {
            val token = advance()
            consume(TokenType.ARROW, "Expected '=>'")
            return parseArrowBody(
                token.range.start,
                listOf(Parameter(token.value, null, null, false, token.range)),
                isAsync = false,
            )
        }

        // 括号参数箭头：(a, b = 1, ...rest) =>
        if (peek().type == TokenType.LPAREN && isArrowAfterParens(0)) {
            val start = peek().range.start
            val params = parseParameters()
            consume(TokenType.ARROW, "Expected '=>'")
            return parseArrowBody(start, params, isAsync = false)
        }

        return null
    }

    /** 从指定偏移处的 `(` 开始，判断匹配的 `)` 之后是否紧跟 `=>`。 */
    private fun isArrowAfterParens(offset: Int): Boolean {
        var index = pos + offset
        if (index >= tokens.size || tokens[index].type != TokenType.LPAREN) return false
        var depth = 0
        while (index < tokens.size) {
            when (tokens[index].type) {
                TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE -> depth++
                TokenType.RPAREN, TokenType.RBRACKET, TokenType.RBRACE -> {
                    depth--
                    if (depth == 0) {
                        val next = tokens.getOrNull(index + 1) ?: return false
                        return next.type == TokenType.ARROW && !next.precededByNewline
                    }
                }

                TokenType.EOF -> return false
                else -> Unit
            }
            index++
        }
        return false
    }

    /** 解析箭头函数体（块体或表达式体）。 */
    private fun parseArrowBody(
        start: SourcePosition,
        params: List<Parameter>,
        isAsync: Boolean,
    ): ArrowFunction {
        if (check(TokenType.LBRACE)) {
            val bodyInfo = parseFunctionBody(isAsync)
            return ArrowFunction(
                params = params,
                body = bodyInfo.body,
                expressionBody = null,
                strict = bodyInfo.strict,
                isAsync = isAsync,
                varDeclarations = bodyInfo.varDeclarations,
                functionDeclarations = bodyInfo.functionDeclarations,
                range = SourceRange(start, bodyInfo.body.range.end),
            )
        }

        val outerAsync = asyncContext
        asyncContext = isAsync
        try {
            val expression = parseAssignment()
            return ArrowFunction(
                params = params,
                body = null,
                expressionBody = expression,
                strict = strict,
                isAsync = isAsync,
                varDeclarations = emptyList(),
                functionDeclarations = emptyList(),
                range = SourceRange(start, expression.range.end),
            )
        } finally {
            asyncContext = outerAsync
        }
    }

    /** 三元条件表达式。 */
    private fun parseConditional(): Expression {
        val condition = parseCoalesce()
        if (!match(TokenType.QUESTION)) return condition

        val thenBranch = parseAssignment()
        consume(TokenType.COLON, "Expected ':' in conditional expression")
        val elseBranch = parseAssignment()
        return ConditionalExpr(
            condition,
            thenBranch,
            elseBranch,
            SourceRange(condition.range.start, elseBranch.range.end),
        )
    }

    /** `??` 空值合并（左结合）。 */
    private fun parseCoalesce(): Expression {
        var expression = parseLogicalOr()
        while (match(TokenType.COALESCE)) {
            val right = parseLogicalOr()
            expression = CoalesceExpr(
                expression,
                right,
                SourceRange(expression.range.start, right.range.end),
            )
        }
        return expression
    }

    private fun parseLogicalOr(): Expression =
        parseLeftAssociative(setOf(TokenType.OR), ::parseLogicalAnd)

    private fun parseLogicalAnd(): Expression =
        parseLeftAssociative(setOf(TokenType.AND), ::parseBitwiseOr)

    private fun parseBitwiseOr(): Expression =
        parseLeftAssociative(setOf(TokenType.BITWISE_OR), ::parseBitwiseXor)

    private fun parseBitwiseXor(): Expression =
        parseLeftAssociative(setOf(TokenType.BITWISE_XOR), ::parseBitwiseAnd)

    private fun parseBitwiseAnd(): Expression =
        parseLeftAssociative(setOf(TokenType.BITWISE_AND), ::parseEquality)

    private fun parseEquality(): Expression =
        parseLeftAssociative(
            setOf(TokenType.EQ, TokenType.NEQ, TokenType.STRICT_EQ, TokenType.STRICT_NEQ),
            ::parseComparison,
        )

    private fun parseComparison(): Expression =
        parseLeftAssociative(
            setOf(
                TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE,
                TokenType.IN, TokenType.INSTANCEOF,
            ),
            ::parseShift,
        )

    private fun parseShift(): Expression =
        parseLeftAssociative(
            setOf(TokenType.LEFT_SHIFT, TokenType.RIGHT_SHIFT, TokenType.UNSIGNED_RIGHT_SHIFT),
            ::parseAdditive,
        )

    private fun parseAdditive(): Expression =
        parseLeftAssociative(setOf(TokenType.PLUS, TokenType.MINUS), ::parseMultiplicative)

    private fun parseMultiplicative(): Expression =
        parseLeftAssociative(
            setOf(TokenType.MULTIPLY, TokenType.DIVIDE, TokenType.MODULO),
            ::parseExponentiation,
        )

    /** `**` 幂运算（右结合）。 */
    private fun parseExponentiation(): Expression {
        val base = parseUnary()
        if (!match(TokenType.EXPONENT)) return base
        val exponent = parseExponentiation()
        return BinaryOp(base, "**", exponent, SourceRange(base.range.start, exponent.range.end))
    }

    /** 通用左结合二元运算解析。 */
    private fun parseLeftAssociative(
        operators: Set<TokenType>,
        operandParser: () -> Expression,
    ): Expression {
        var expression = operandParser()
        while (peek().type in operators) {
            val operator = advance().value
            val right = operandParser()
            expression = BinaryOp(
                expression,
                operator,
                right,
                SourceRange(expression.range.start, right.range.end),
            )
        }
        return expression
    }

    /** 一元运算与自增自减前缀。 */
    private fun parseUnary(): Expression {
        val token = peek()

        // async 函数中的 await
        if (asyncContext && token.type == TokenType.IDENTIFIER && token.value == "await") {
            advance()
            val operand = parseUnary()
            return UnaryOp("await", operand, SourceRange(token.range.start, operand.range.end))
        }

        return when (token.type) {
            TokenType.INCREMENT, TokenType.DECREMENT -> {
                advance()
                val operand = parseUnary()
                if (operand !is Identifier && operand !is MemberExpr) {
                    throw error("Invalid left-hand side expression in prefix operation")
                }
                UpdateExpr(
                    target = operand,
                    operator = token.value,
                    prefix = true,
                    range = SourceRange(token.range.start, operand.range.end),
                )
            }

            TokenType.NOT, TokenType.MINUS, TokenType.PLUS,
            TokenType.BITWISE_NOT, TokenType.TYPEOF,
            TokenType.DELETE, TokenType.VOID -> {
                advance()
                val operand = parseUnary()
                UnaryOp(token.value, operand, SourceRange(token.range.start, operand.range.end))
            }

            else -> parsePostfix()
        }
    }

    /** 后缀自增/自减（换行会阻止后缀语义）。 */
    private fun parsePostfix(): Expression {
        var expression = parseLeftHandSide()
        val token = peek()
        if ((token.type == TokenType.INCREMENT || token.type == TokenType.DECREMENT) &&
            !token.precededByNewline
        ) {
            if (expression !is Identifier && expression !is MemberExpr) {
                throw error("Invalid left-hand side expression in postfix operation")
            }
            advance()
            expression = UpdateExpr(
                target = expression,
                operator = token.value,
                prefix = false,
                range = SourceRange(expression.range.start, token.range.end),
            )
        }
        return expression
    }

    /** 成员访问与函数调用链。 */
    private fun parseLeftHandSide(): Expression {
        var expression = if (check(TokenType.NEW)) parseNewExpression() else parsePrimary()

        while (true) {
            when {
                match(TokenType.LPAREN) -> {
                    val arguments = parseArgumentsAfterOpen()
                    expression = CallExpr(
                        expression,
                        arguments,
                        SourceRange(expression.range.start, previous().range.end),
                    )
                }

                match(TokenType.DOT) -> {
                    val propertyToken = parsePropertyName()
                    expression = MemberExpr(
                        obj = expression,
                        property = Identifier(propertyToken.value, propertyToken.range),
                        computed = false,
                        range = SourceRange(expression.range.start, propertyToken.range.end),
                    )
                }

                match(TokenType.LBRACKET) -> {
                    val property = parseExpression()
                    val close = consume(TokenType.RBRACKET, "Expected ']' after member expression")
                    expression = MemberExpr(
                        obj = expression,
                        property = property,
                        computed = true,
                        range = SourceRange(expression.range.start, close.range.end),
                    )
                }

                check(TokenType.TEMPLATE) -> {
                    val templateToken = advance()
                    val template = parseTemplateLiteral(templateToken) as TemplateLiteral
                    expression = TaggedTemplate(
                        tag = expression,
                        quasis = template.quasis,
                        expressions = template.expressions,
                        range = SourceRange(expression.range.start, templateToken.range.end),
                    )
                }

                else -> return expression
            }
        }
    }

    /** new 表达式。 */
    private fun parseNewExpression(): Expression {
        val start = consume(TokenType.NEW, "Expected 'new'").range.start

        // new.target 元属性
        if (match(TokenType.DOT)) {
            val property = consume(TokenType.IDENTIFIER, "Expected 'target' after 'new.'")
            if (property.value != "target") {
                throw error("Expected 'target' after 'new.'")
            }
            return NewTargetExpr(SourceRange(start, property.range.end))
        }

        var constructor: Expression =
            if (check(TokenType.NEW)) parseNewExpression() else parsePrimary()

        // new 的构造器可以带成员链，例如 new a.b.C()
        while (true) {
            when {
                match(TokenType.DOT) -> {
                    val propertyToken = parsePropertyName()
                    constructor = MemberExpr(
                        constructor,
                        Identifier(propertyToken.value, propertyToken.range),
                        false,
                        SourceRange(constructor.range.start, propertyToken.range.end),
                    )
                }

                match(TokenType.LBRACKET) -> {
                    val property = parseExpression()
                    val close = consume(TokenType.RBRACKET, "Expected ']' after member expression")
                    constructor = MemberExpr(
                        constructor,
                        property,
                        true,
                        SourceRange(constructor.range.start, close.range.end),
                    )
                }

                else -> break
            }
        }

        if (match(TokenType.LPAREN)) {
            val arguments = parseArgumentsAfterOpen()
            return NewExpr(
                constructor,
                arguments,
                SourceRange(start, previous().range.end),
            )
        }
        return NewExpr(constructor, emptyList(), SourceRange(start, constructor.range.end))
    }

    /** 解析函数调用参数（左括号已消费）。 */
    private fun parseArgumentsAfterOpen(): List<Expression> {
        val arguments = mutableListOf<Expression>()
        if (!check(TokenType.RPAREN)) {
            do {
                if (check(TokenType.ELLIPSIS)) {
                    val spreadStart = advance().range.start
                    val argument = parseAssignment()
                    arguments.add(
                        SpreadElement(argument, SourceRange(spreadStart, argument.range.end))
                    )
                } else {
                    arguments.add(parseAssignment())
                }
            } while (match(TokenType.COMMA) && !check(TokenType.RPAREN))
        }
        consume(TokenType.RPAREN, "Expected ')' after arguments")
        return arguments
    }

    /** 基础表达式。 */
    private fun parsePrimary(): Expression {
        val token = peek()
        return when (token.type) {
            TokenType.NUMBER, TokenType.NUMBER_HEX,
            TokenType.NUMBER_OCT, TokenType.NUMBER_BIN -> {
                advance()
                NumberLiteral(parseNumberLiteral(token), token.range)
            }

            TokenType.STRING -> {
                advance()
                StringLiteral(token.value, token.range)
            }

            TokenType.TRUE -> {
                advance()
                BooleanLiteral(true, token.range)
            }

            TokenType.FALSE -> {
                advance()
                BooleanLiteral(false, token.range)
            }

            TokenType.NULL -> {
                advance()
                NullLiteral(token.range)
            }

            TokenType.THIS -> {
                advance()
                ThisExpr(token.range)
            }

            TokenType.SUPER -> {
                advance()
                SuperExpr(token.range)
            }

            TokenType.CLASS -> parseClassExpression()

            TokenType.FUNCTION -> {
                advance()
                parseFunctionExpression(token.range.start, isAsync = false)
            }

            TokenType.IDENTIFIER -> {
                if (token.value == "async" && peek(1).type == TokenType.FUNCTION &&
                    !peek(1).precededByNewline
                ) {
                    advance() // async
                    advance() // function
                    parseFunctionExpression(token.range.start, isAsync = true)
                } else {
                    advance()
                    Identifier(token.value, token.range)
                }
            }

            TokenType.LPAREN -> {
                advance()
                val expression = parseExpression()
                consume(TokenType.RPAREN, "Expected ')' after expression")
                expression
            }

            TokenType.LBRACKET -> parseArrayLiteral()
            TokenType.LBRACE -> parseObjectLiteral()

            TokenType.REGEX -> {
                advance()
                RegExpLiteral(token.value, token.regexFlags, token.range)
            }

            TokenType.TEMPLATE -> {
                advance()
                parseTemplateLiteral(token)
            }

            else -> throw error("Unexpected token '${token.value}'")
        }
    }

    /** 模板字符串：解析 `${...}` 中的表达式源码。 */
    private fun parseTemplateLiteral(token: Token): Expression {
        val quasis = mutableListOf<String>()
        val expressions = mutableListOf<Expression>()

        for (part in token.templateParts) {
            when (part) {
                is TemplatePart.Text -> quasis.add(part.value)
                is TemplatePart.ExpressionSource -> {
                    val subTokens = try {
                        Lexer(part.source).tokenize()
                    } catch (error: LexerException) {
                        throw ParseException(error.message ?: "Invalid template expression", error.range)
                    }
                    val subProgram = Parser(subTokens).parseProgram()
                    val statement = subProgram.body.singleOrNull()
                    if (statement !is ExpressionStatement) {
                        throw ParseException("Invalid template expression", token.range)
                    }
                    expressions.add(statement.expression)
                }
            }
        }

        // 保证 quasis 数量为 expressions + 1
        if (quasis.size < expressions.size + 1) {
            while (quasis.size < expressions.size + 1) quasis.add("")
        }
        return TemplateLiteral(quasis, expressions, token.range)
    }

    /** 数组字面量（null 元素表示空洞，SpreadElement 表示展开）。 */
    private fun parseArrayLiteral(): Expression {
        val start = consume(TokenType.LBRACKET, "Expected '['").range.start
        val elements = mutableListOf<Expression?>()

        while (!check(TokenType.RBRACKET) && !isAtEnd()) {
            if (check(TokenType.COMMA)) {
                elements.add(null)
                advance()
                continue
            }
            if (check(TokenType.ELLIPSIS)) {
                val spreadStart = advance().range.start
                val argument = parseAssignment()
                elements.add(SpreadElement(argument, SourceRange(spreadStart, argument.range.end)))
            } else {
                elements.add(parseAssignment())
            }
            if (match(TokenType.COMMA)) {
                if (check(TokenType.RBRACKET)) break
            } else {
                break
            }
        }

        val end = consume(TokenType.RBRACKET, "Expected ']' after array literal").range.end
        return ArrayLiteral(elements, SourceRange(start, end))
    }

    /** 对象字面量。 */
    private fun parseObjectLiteral(): Expression {
        val start = consume(TokenType.LBRACE, "Expected '{'").range.start
        val properties = mutableListOf<ObjectLiteralElement>()

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (check(TokenType.ELLIPSIS)) {
                val spreadStart = advance().range.start
                val argument = parseAssignment()
                properties.add(ObjectSpreadProperty(argument, SourceRange(spreadStart, argument.range.end)))
                if (!match(TokenType.COMMA)) break
                if (check(TokenType.RBRACE)) break
                continue
            }
            val propertyStart = peek().range.start

            // getter / setter 检测：`get x() {}`、`set x(v) {}`
            var kind = PropertyKind.INIT
            if (peek().type == TokenType.IDENTIFIER &&
                (peek().value == "get" || peek().value == "set") &&
                canStartAccessorProperty(1)
            ) {
                kind = if (peek().value == "get") PropertyKind.GET else PropertyKind.SET
                advance()
            }

            val (key, computed) = parsePropertyKeyExpression()

            val value: Expression = when {
                match(TokenType.COLON) -> parseAssignment()

                match(TokenType.LPAREN) -> {
                    val params = parseParametersAfterOpen()
                    val bodyInfo = parseFunctionBody()
                    val name = if (key is Identifier) key.name else ""
                    FunctionExpression(
                        name = name,
                        params = params,
                        body = bodyInfo.body,
                        strict = bodyInfo.strict,
                        isAsync = false,
                        varDeclarations = bodyInfo.varDeclarations,
                        functionDeclarations = bodyInfo.functionDeclarations,
                        range = SourceRange(key.range.start, bodyInfo.body.range.end),
                    )
                }

                kind == PropertyKind.INIT && key is Identifier && !computed -> {
                    // 简写属性 { a } / 解构默认值 { a = 1 }
                    val identifier = Identifier(key.name, key.range)
                    if (match(TokenType.ASSIGN)) {
                        val defaultValue = parseAssignment()
                        AssignmentExpr(
                            target = identifier,
                            operator = "=",
                            value = defaultValue,
                            range = SourceRange(identifier.range.start, defaultValue.range.end),
                        )
                    } else {
                        identifier
                    }
                }

                else -> throw error("Expected ':' after object key")
            }

            properties.add(
                ObjectProperty(
                    key = key,
                    kind = kind,
                    value = value,
                    computed = computed,
                    range = SourceRange(propertyStart, value.range.end),
                )
            )
            if (!match(TokenType.COMMA)) break
            if (check(TokenType.RBRACE)) break
        }

        val end = consume(TokenType.RBRACE, "Expected '}' after object literal").range.end
        return ObjectLiteral(properties, SourceRange(start, end))
    }

    /** 判断 `get`/`set` 之后是否跟着一个可访问器属性名。 */
    private fun canStartAccessorProperty(offset: Int): Boolean {
        val token = peek(offset)
        return when {
            token.type in setOf(
                TokenType.IDENTIFIER, TokenType.STRING,
                TokenType.NUMBER, TokenType.NUMBER_HEX,
                TokenType.NUMBER_OCT, TokenType.NUMBER_BIN,
                TokenType.LBRACKET,
            ) -> true

            else -> token.type in keywordTokenTypes
        }
    }

    /** 解析对象属性键，返回键表达式与是否计算键。 */
    private fun parsePropertyKeyExpression(): Pair<Expression, Boolean> {
        if (match(TokenType.LBRACKET)) {
            val expression = parseAssignment()
            consume(TokenType.RBRACKET, "Expected ']' after computed property key")
            return expression to true
        }

        val token = peek()
        return when {
            token.type == TokenType.IDENTIFIER -> {
                advance()
                Identifier(token.value, token.range) to false
            }

            token.type == TokenType.STRING -> {
                advance()
                StringLiteral(token.value, token.range) to false
            }

            token.type in setOf(
                TokenType.NUMBER, TokenType.NUMBER_HEX,
                TokenType.NUMBER_OCT, TokenType.NUMBER_BIN,
            ) -> {
                advance()
                NumberLiteral(parseNumberLiteral(token), token.range) to false
            }

            token.type in keywordTokenTypes -> {
                advance()
                StringLiteral(token.value, token.range) to false
            }

            else -> throw error("Expected property key (identifier, string, or number)")
        }
    }

    /** 成员访问的属性名（允许关键字，如 obj.default）。 */
    private fun parsePropertyName(): Token {
        val token = peek()
        if (token.type == TokenType.IDENTIFIER || token.type in keywordTokenTypes) {
            advance()
            return token
        }
        throw error("Expected property name")
    }

    // ================================================================
    // 变量提升信息收集
    // ================================================================

    /**
     * 收集当前作用域内需要提升的声明：
     * - var 声明会穿透普通块；
     * - 函数声明提升到当前作用域；
     * - 不进入嵌套函数体（嵌套函数有自己的作用域）。
     */
    private fun collectHoisted(statements: List<Statement>): HoistInfo {
        val varNames = LinkedHashSet<String>()
        val functions = mutableListOf<FunctionDeclaration>()

        fun scan(statement: Statement) {
            when (statement) {
                is VarDeclaration -> {
                    if (statement.kind == DeclarationKind.VAR) {
                        statement.declarations.forEach { declaration ->
                            declaration.name?.let { varNames.add(it) }
                            declaration.pattern?.let { collectPatternNames(it, varNames) }
                        }
                    }
                }

                is FunctionDeclaration -> functions.add(statement)
                is BlockStatement -> statement.statements.forEach { scan(it) }
                is IfStatement -> {
                    scan(statement.consequent)
                    statement.alternate?.let { scan(it) }
                }

                is WhileStatement -> scan(statement.body)
                is DoWhileStatement -> scan(statement.body)
                is ForStatement -> {
                    statement.init?.let { scan(it) }
                    scan(statement.body)
                }

                is ForInStatement -> {
                    if (statement.declarationKind == DeclarationKind.VAR) {
                        statement.variableName?.let { varNames.add(it) }
                        statement.pattern?.let { collectPatternNames(it, varNames) }
                    }
                    scan(statement.body)
                }

                is ForOfStatement -> {
                    if (statement.declarationKind == DeclarationKind.VAR) {
                        statement.variableName?.let { varNames.add(it) }
                        statement.pattern?.let { collectPatternNames(it, varNames) }
                    }
                    scan(statement.body)
                }

                is LabeledStatement -> scan(statement.body)
                is WithStatement -> scan(statement.body)

                is SwitchStatement -> statement.cases.forEach { case ->
                    case.consequent.forEach { scan(it) }
                }

                is TryStatement -> {
                    scan(statement.block)
                    statement.handler?.let { scan(it.body) }
                    statement.finalizer?.let { scan(it) }
                }

                else -> Unit
            }
        }

        statements.forEach { scan(it) }
        return HoistInfo(varNames.toList(), functions)
    }

    /** 从解构模式中收集绑定的变量名。 */
    private fun collectPatternNames(expression: Expression, result: MutableSet<String>) {
        when (expression) {
            is Identifier -> result.add(expression.name)
            is ArrayLiteral -> expression.elements.forEach { element ->
                element?.let { collectPatternNames(it, result) }
            }

            is ObjectLiteral -> expression.properties.forEach { property ->
                when (property) {
                    is ObjectProperty -> collectPatternNames(property.value, result)
                    is ObjectSpreadProperty -> collectPatternNames(property.argument, result)
                }
            }

            is AssignmentExpr -> collectPatternNames(expression.target, result)
            is SpreadElement -> collectPatternNames(expression.argument, result)
            else -> Unit
        }
    }

    /**
     * 扫描指定位置（应为函数体的 `{`）之后是否包含 "use strict" 指令。
     *
     * 该检查在正式解析函数体之前执行，以便让嵌套函数继承正确的严格模式。
     */
    private fun detectUseStrictDirective(blockStart: Int): Boolean {
        var index = blockStart
        if (index >= tokens.size || tokens[index].type != TokenType.LBRACE) return false
        index++

        while (index < tokens.size) {
            val token = tokens[index]
            when (token.type) {
                TokenType.SEMICOLON -> index++
                TokenType.STRING -> {
                    if (token.value == "use strict") return true
                    index++
                    if (index < tokens.size && tokens[index].type == TokenType.SEMICOLON) index++
                }

                else -> return false
            }
        }
        return false
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 解析数字 Token 为 Double。 */
    private fun parseNumberLiteral(token: Token): Double {
        val text = token.value.replace("_", "")
        return try {
            when (token.type) {
                TokenType.NUMBER_HEX -> text.substring(2).toLong(16).toDouble()
                TokenType.NUMBER_OCT -> text.substring(2).toLong(8).toDouble()
                TokenType.NUMBER_BIN -> text.substring(2).toLong(2).toDouble()
                else -> text.toDouble()
            }
        } catch (_: NumberFormatException) {
            throw ParseException("Invalid number literal '${token.value}'", token.range)
        }
    }

    /** 如果当前 Token 类型匹配任意一个给定类型，则消费并返回 true。 */
    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    /** 当前 Token 是否为指定类型。 */
    private fun check(type: TokenType): Boolean = !isAtEnd() && peek().type == type

    /** 消费当前 Token。 */
    private fun advance(): Token {
        if (!isAtEnd()) pos++
        return previous()
    }

    /** 查看当前 Token（可指定偏移）。 */
    private fun peek(offset: Int = 0): Token {
        val index = pos + offset
        return if (index in tokens.indices) tokens[index] else tokens.last()
    }

    /** 上一个已消费的 Token。 */
    private fun previous(): Token = tokens[(pos - 1).coerceAtLeast(0)]

    /** 是否到达 EOF。 */
    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    /** 消费指定类型的 Token，否则抛出语法错误。 */
    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw error(message)
    }

    /**
     * 尝试消费分号。
     *
     * 支持自动分号插入（ASI）的常见情形：
     * - 显式分号；
     * - 行尾换行；
     * - 右花括号或文件结束。
     * 其余情况视为语法错误。
     */
    private fun consumeSemicolon() {
        if (check(TokenType.SEMICOLON)) {
            advance()
            return
        }
        val token = peek()
        if (token.type == TokenType.EOF || token.type == TokenType.RBRACE || token.precededByNewline) {
            return
        }
        throw error("Expected ';'")
    }

    /** 构造带位置信息的语法错误。 */
    private fun error(message: String): ParseException {
        val token = peek()
        val tokenText = if (token.type == TokenType.EOF) "EOF" else token.value
        return ParseException("$message (Token: $tokenText)", token.range)
    }

    /** 函数体解析结果。 */
    private class FunctionBodyInfo(
        val body: BlockStatement,
        val strict: Boolean,
        val varDeclarations: List<String>,
        val functionDeclarations: List<FunctionDeclaration>,
    )

    /** 变量提升信息。 */
    private class HoistInfo(
        val varDeclarations: List<String>,
        val functionDeclarations: List<FunctionDeclaration>,
    )
}
