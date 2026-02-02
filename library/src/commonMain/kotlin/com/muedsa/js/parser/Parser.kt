package com.muedsa.js.parser

import com.muedsa.js.ast.*
import com.muedsa.js.lexer.Token
import com.muedsa.js.lexer.TokenType

/**
 * JavaScript 语法解析器
 * 接收 Token 列表，使用递归下降算法构建 AST (抽象语法树)。
 */
class Parser(private val tokens: List<Token>) {
    private var pos = 0 // 当前解析到的 Token 索引

    /**
     * 解析入口方法
     * 将整个 Token 流解析为一个 BlockStatement（通常代表整个程序体）
     */
    fun parse(): BlockStatement {
        val statements = mutableListOf<Statement>()

        // 循环直到文件结束 (EOF)
        while (!isAtEnd()) {
            statements.add(parseStatement())
        }

        val block = BlockStatement(statements)
        // 设置整个 Block 的源码范围 (Range)
        if (statements.isNotEmpty()) {
            block.range = statements.first().range.first..statements.last().range.last
        } else if (tokens.isNotEmpty()) {
            block.range = tokens.first().range.first..tokens.last().range.last
        }
        block.tokens = tokens
        return block
    }

    /**
     * 语句分发器
     * 根据当前的 Token 类型决定解析哪种语句
     */
    private fun parseStatement(): Statement {
        return when (peek().type) {
            // 变量声明 (var, let, const)
            TokenType.VAR, TokenType.LET, TokenType.CONST -> parseVarDeclaration()
            // 函数声明
            TokenType.FUNCTION -> parseFunctionDeclaration()
            // 代码块 { ... }
            TokenType.LBRACE -> parseBlockStatement()
            // 控制流语句
            TokenType.IF -> parseIfStatement()
            TokenType.WHILE -> parseWhileStatement()
            TokenType.FOR -> parseForStatement()
            TokenType.SWITCH -> parseSwitchStatement()
            TokenType.BREAK -> parseBreakStatement()
            TokenType.CONTINUE -> parseContinueStatement()
            TokenType.THROW -> parseThrowStatement()
            TokenType.TRY -> parseTryStatement()
            TokenType.RETURN -> parseReturnStatement()
            // 默认情况：解析为表达式语句 (例如: a = 1; 或 parse();)
            else -> {
                val startPos = pos
                val expr = parseExpression()
                consumeSemicolon() // 吞掉分号
                val statement = ExpressionStatement(expr)
                statement.range = expr.range.first..previous().range.last
                statement.tokens = tokens.subList(startPos, pos)
                statement
            }
        }
    }

    /**
     * 解析变量声明: var/let/const name = value;
     */
    private fun parseVarDeclaration(): VarDeclaration {
        val startPos = pos
        val kind = advance().value // 获取 var, let 或 const
        val name = consume(TokenType.IDENTIFIER, "Expected identifier").value

        var initializer: Expression? = null
        // 检查是否有初始化赋值
        if (match(TokenType.ASSIGN)) {
            initializer = parseExpression()
        }

        consumeSemicolon()
        val declaration = VarDeclaration(kind, name, initializer)
        declaration.range = tokens[startPos].range.first..previous().range.last
        declaration.tokens = tokens.subList(startPos, pos)
        return declaration
    }

    /**
     * 解析函数声明: function name(p1, p2) { ... }
     */
    private fun parseFunctionDeclaration(): FunctionDeclaration {
        val startPos = pos
        consume(TokenType.FUNCTION, "Expected 'function'")
        val name = consume(TokenType.IDENTIFIER, "Expected function name").value

        consume(TokenType.LPAREN, "Expected '(' after function name")
        val params = mutableListOf<String>()

        // 解析参数列表
        if (!check(TokenType.RPAREN)) {
            do {
                params.add(consume(TokenType.IDENTIFIER, "Expected parameter name").value)
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RPAREN, "Expected ')' after parameters")

        val body = parseBlockStatement()

        val declaration = FunctionDeclaration(name, params, body)
        declaration.range = tokens[startPos].range.first..body.range.last
        declaration.tokens = tokens.subList(startPos, pos)
        return declaration
    }

    /**
     * 解析函数表达式: function (p1, p2) { ... }
     * 通常作为值使用，如 let f = function() {}
     */
    private fun parseFunctionExpression(): FunctionExpression {
        // 当这个方法被调用时，'function' 关键字已经被 consume 了 (在 parsePrimary 中 match 的)
        // 或者如果是单独调用，需要确保 startToken 逻辑正确。
        // 在当前的 Parser 结构中，它是通过 match(FUNCTION) -> parseFunctionExpression 调用的
        // 所以 previous() 就是 'function' token。
        val startPos = pos - 1

        var name: String? = null
        // 函数表达式可以有名字 (Named Function Expression)
        if (check(TokenType.IDENTIFIER)) {
            name = advance().value
        }

        consume(TokenType.LPAREN, "Expected '(' after function name")
        val params = mutableListOf<String>()

        if (!check(TokenType.RPAREN)) {
            do {
                params.add(consume(TokenType.IDENTIFIER, "Expected parameter name").value)
            } while (match(TokenType.COMMA))
        }

        consume(TokenType.RPAREN, "Expected ')' after parameters")

        val body = parseBlockStatement()

        val expr = FunctionExpression(name, params, body)
        expr.range = tokens[startPos].range.first..body.range.last
        expr.tokens = tokens.subList(startPos, pos)
        return expr
    }

    /**
     * 解析块级语句: { stmt1; stmt2; }
     */
    private fun parseBlockStatement(): BlockStatement {
        val startPos = pos
        consume(TokenType.LBRACE, "Expected '{'")
        val statements = mutableListOf<Statement>()

        // 读取语句直到遇到 '}' 或文件结束
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            statements.add(parseStatement())
        }

        consume(TokenType.RBRACE, "Expected '}'")
        val block = BlockStatement(statements)
        block.range = tokens[startPos].range.first..previous().range.last
        block.tokens = tokens.subList(startPos, pos)
        return block
    }

    /**
     * 解析 If 语句: if (cond) stmt else stmt
     */
    private fun parseIfStatement(): IfStatement {
        val startPos = pos
        consume(TokenType.IF, "Expected 'if'")
        consume(TokenType.LPAREN, "Expected '(' after 'if'")
        val condition = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after if condition")

        val consequent = parseStatement()

        var alternate: Statement? = null
        // 检查是否有 else 分支
        if (match(TokenType.ELSE)) {
            alternate = parseStatement()
        }

        val statement = IfStatement(condition, consequent, alternate)
        val endRange = alternate?.range?.last ?: consequent.range.last
        statement.range = tokens[startPos].range.first..endRange
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    /**
     * 解析 While 循环: while (cond) stmt
     */
    private fun parseWhileStatement(): WhileStatement {
        val startPos = pos
        consume(TokenType.WHILE, "Expected 'while'")
        consume(TokenType.LPAREN, "Expected '(' after 'while'")
        val condition = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after while condition")

        val body = parseStatement()

        val statement = WhileStatement(condition, body)
        statement.range = tokens[startPos].range.first..body.range.last
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    /**
     * 解析 For 循环: for (init; test; update) body
     */
    private fun parseForStatement(): ForStatement {
        val startPos = pos
        consume(TokenType.FOR, "Expected 'for'")
        consume(TokenType.LPAREN, "Expected '(' after 'for'")

        // 1. 初始化部分 (init)
        var init: Statement? = null
        if (match(TokenType.SEMICOLON)) {
            // 空的初始化
        } else if (check(TokenType.VAR) || check(TokenType.LET) || check(TokenType.CONST)) {
            init = parseVarDeclaration() // 例如: for(var i=0; ...)
        } else {
            val expr = parseExpression()
            consume(TokenType.SEMICOLON, "Expected ';' after for loop init")
            val statement = ExpressionStatement(expr)
            statement.range = expr.range.first..previous().range.last
            init = statement
        }

        // 2. 条件判断部分 (condition)
        var condition: Expression? = null
        if (!check(TokenType.SEMICOLON)) {
            condition = parseExpression()
        }
        consume(TokenType.SEMICOLON, "Expected ';' after for loop condition")

        // 3. 更新部分 (update)
        var update: Expression? = null
        if (!check(TokenType.RPAREN)) {
            update = parseExpression()
        }
        consume(TokenType.RPAREN, "Expected ')' after for loop")

        val body = parseStatement()

        val statement = ForStatement(init, condition, update, body)
        statement.range = tokens[startPos].range.first..body.range.last
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    /**
     * 解析 Switch 语句
     */
    private fun parseSwitchStatement(): SwitchStatement {
        val startPos = pos
        consume(TokenType.SWITCH, "Expected 'switch'")
        consume(TokenType.LPAREN, "Expected '(' after switch")
        val discriminant = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after switch expression")
        consume(TokenType.LBRACE, "Expected '{' after switch")

        val cases = mutableListOf<SwitchCase>()

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            when {
                match(TokenType.CASE) -> {
                    val test = parseExpression()
                    consume(TokenType.COLON, "Expected ':' after case expression")
                    val statements = mutableListOf<Statement>()

                    // 读取 case 下的所有语句，直到遇到下一个 case/default 或 switch 结束
                    while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) && !check(TokenType.RBRACE) && !isAtEnd()) {
                        statements.add(parseStatement())
                    }

                    cases.add(SwitchCase(test, statements))
                }

                match(TokenType.DEFAULT) -> {
                    consume(TokenType.COLON, "Expected ':' after default")
                    val statements = mutableListOf<Statement>()

                    while (!check(TokenType.CASE) && !check(TokenType.DEFAULT) && !check(TokenType.RBRACE) && !isAtEnd()) {
                        statements.add(parseStatement())
                    }

                    cases.add(SwitchCase(null, statements))
                }

                else -> throw createParseException("Expected 'case' or 'default' in switch statement")
            }
        }

        consume(TokenType.RBRACE, "Expected '}' after switch body")
        val statement = SwitchStatement(discriminant, cases)
        statement.range = tokens[startPos].range.first..previous().range.last
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    private fun parseBreakStatement(): BreakStatement {
        val startPos = pos
        consume(TokenType.BREAK, "Expected 'break'")
        consumeSemicolon()
        val statement = BreakStatement()
        statement.range = tokens[startPos].range.first..previous().range.last
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    private fun parseContinueStatement(): ContinueStatement {
        val startPos = pos
        consume(TokenType.CONTINUE, "Expected 'continue'")
        consumeSemicolon()
        val statement = ContinueStatement()
        statement.range = tokens[startPos].range.first..previous().range.last
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    private fun parseThrowStatement(): ThrowStatement {
        val startPos = pos
        consume(TokenType.THROW, "Expected 'throw'")
        val argument = parseExpression()
        consumeSemicolon()
        val statement = ThrowStatement(argument)
        statement.range = tokens[startPos].range.first..previous().range.last
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    /**
     * 解析 Try-Catch-Finally 语句
     */
    private fun parseTryStatement(): TryStatement {
        val startPos = pos
        consume(TokenType.TRY, "Expected 'try'")

        val tryBlock = parseBlockStatement()

        var catchClause: CatchClause? = null
        if (match(TokenType.CATCH)) {
            val catchStart = previous().range.first
            var param: String? = null
            // 检查是否有 catch 参数 (e)
            if (match(TokenType.LPAREN)) {
                if (check(TokenType.IDENTIFIER)) {
                    param = advance().value
                }
                consume(TokenType.RPAREN, "Expected ')' after catch parameter")
            }
            val catchBlock = parseBlockStatement()
            catchClause = CatchClause(param, catchBlock, catchStart..previous().range.last)
        }

        var finallyBlock: BlockStatement? = null
        if (match(TokenType.FINALLY)) {
            finallyBlock = parseBlockStatement()
        }

        if (catchClause == null && finallyBlock == null) {
            throw createParseException("Missing catch or finally after try")
        }

        val statement = TryStatement(tryBlock, catchClause, finallyBlock)
        statement.range = tokens[startPos].range.first..previous().range.last
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    private fun parseReturnStatement(): ReturnStatement {
        val startPos = pos
        consume(TokenType.RETURN, "Expected 'return'")

        var value: Expression? = null
        // 检查是否有返回值 (如果后面不是分号或代码块结束，则解析表达式)
        if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !isAtEnd()) {
            value = parseExpression()
        }

        consumeSemicolon()
        val statement = ReturnStatement(value)
        statement.range = tokens[startPos].range.first..previous().range.last
        statement.tokens = tokens.subList(startPos, pos)
        return statement
    }

    // ==========================================
    // 表达式解析部分 (Expression Parsing)
    // 按照运算符优先级从低到高依次调用，形成递归层级。
    // ==========================================

    private fun parseExpression(): Expression {
        return parseAssignment()
    }

    /**
     * 解析赋值表达式 (=, +=, -= 等)
     * 优先级极低，右结合
     */
    private fun parseAssignment(): Expression {
        var expr = parseConditional() // 先解析优先级更高的
        if (check(TokenType.ASSIGN)) {
            when (expr) {
                is Identifier -> {
                    val startPos = pos
                    advance() // consume '='
                    val value = parseAssignment() // 递归调用自身以支持右结合 (a = b = c)
                    val newExpr = AssignmentExpr(expr.name, value)
                    newExpr.range = expr.range.first..value.range.last
                    newExpr.tokens = buildList {
                        addAll(expr.tokens)
                        addAll(tokens.subList(startPos, pos))
                    }
                    expr = newExpr
                }

                is MemberExpr -> {
                    val startPos = pos
                    advance()
                    val value = parseAssignment()
                    val newExpr = MemberAssignmentExpr(expr.obj, expr.property, value, expr.computed)
                    newExpr.range = newExpr.range.first..value.range.last
                    newExpr.tokens = buildList {
                        addAll(expr.tokens)
                        addAll(tokens.subList(startPos, pos))
                    }
                    expr = newExpr
                }

                else -> {
                    throw createParseException("Invalid assignment target")
                }
            }
        } else if (match(
                TokenType.PLUS_ASSIGN, TokenType.MINUS_ASSIGN, TokenType.MULTIPLY_ASSIGN,
                TokenType.DIVIDE_ASSIGN, TokenType.MODULO_ASSIGN
            )
        ) {
            // 处理复合赋值 (+=, -= 等)
            if (expr is Identifier) {
                val startPos = pos
                val operator = previous().value
                val value = parseAssignment()
                val newExpr = CompoundAssignmentExpr(expr.name, operator, value)
                newExpr.range = newExpr.range.first..value.range.last
                newExpr.tokens = buildList {
                    addAll(expr.tokens)
                    addAll(tokens.subList(startPos, pos))
                }
                expr = newExpr
            } else {
                throw createParseException("Invalid assignment target")
            }
        }

        return expr
    }

    /**
     * 解析三元条件运算符: condition ? trueExpr : falseExpr
     */
    private fun parseConditional(): Expression {
        var expr = parseCoalesce()
        val startPos = pos
        if (match(TokenType.QUESTION)) {
            val thenBranch = parseExpression()
            consume(TokenType.COLON, "Expected ':' in conditional expression")
            val elseBranch = parseConditional()
            val newExpr = ConditionalExpr(expr, thenBranch, elseBranch)
            newExpr.range = expr.range.first..elseBranch.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析空值合并运算符: ??
     */
    private fun parseCoalesce(): Expression {
        var expr = parseLogicalOr()
        val startPos = pos
        while (match(TokenType.COALESCE)) {
            val right = parseLogicalOr()
            val newExpr = CoalesceExpr(expr, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析逻辑或: ||
     */
    private fun parseLogicalOr(): Expression {
        var expr = parseLogicalAnd()
        val startPos = pos
        while (match(TokenType.OR)) {
            val operator = previous().value
            val right = parseLogicalAnd()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析逻辑与: &&
     */
    private fun parseLogicalAnd(): Expression {
        var expr = parseBitwiseOr()
        val startPos = pos
        while (match(TokenType.AND)) {
            val operator = previous().value
            val right = parseBitwiseOr()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    // 按位运算优先级: | -> ^ -> &

    private fun parseBitwiseOr(): Expression {
        var expr = parseBitwiseXor()
        val startPos = pos
        while (match(TokenType.BITWISE_OR)) {
            val operator = previous().value
            val right = parseBitwiseXor()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    private fun parseBitwiseXor(): Expression {
        var expr = parseBitwiseAnd()
        val startPos = pos
        while (match(TokenType.BITWISE_XOR)) {
            val operator = previous().value
            val right = parseBitwiseAnd()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    private fun parseBitwiseAnd(): Expression {
        var expr = parseEquality()
        val startPos = pos
        while (match(TokenType.BITWISE_AND)) {
            val operator = previous().value
            val right = parseEquality()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析相等性比较: ==, !=, ===, !==
     */
    private fun parseEquality(): Expression {
        var expr = parseComparison()
        val startPos = pos
        while (match(TokenType.EQ, TokenType.NEQ, TokenType.STRICT_EQ, TokenType.STRICT_NEQ)) {
            val operator = previous().value
            val right = parseComparison()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析大小比较: <, <=, >, >=
     */
    private fun parseComparison(): Expression {
        var expr = parseShift()
        val startPos = pos
        while (match(TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE)) {
            val operator = previous().value
            val right = parseShift()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析位移运算: <<, >>, >>>
     */
    private fun parseShift(): Expression {
        var expr = parseAdditive()
        val startPos = pos
        while (match(TokenType.LEFT_SHIFT, TokenType.RIGHT_SHIFT, TokenType.UNSIGNED_RIGHT_SHIFT)) {
            val operator = previous().value
            val right = parseAdditive()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析加减法: +, -
     */
    private fun parseAdditive(): Expression {
        var expr = parseMultiplicative()
        val startPos = pos
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            val operator = previous().value
            val right = parseMultiplicative()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析乘除模: *, /, %
     */
    private fun parseMultiplicative(): Expression {
        var expr = parseExponentiation()
        val startPos = pos
        while (match(TokenType.MULTIPLY, TokenType.DIVIDE, TokenType.MODULO)) {
            val operator = previous().value
            val right = parseExponentiation()
            val newExpr = BinaryOp(expr, operator, right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析指数运算: **
     */
    private fun parseExponentiation(): Expression {
        var expr = parseUnary()
        val startPos = pos
        if (match(TokenType.EXPONENT)) {
            val right = parseExponentiation()
            val newExpr = BinaryOp(expr, "**", right)
            newExpr.range = expr.range.first..right.range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析一元运算 (前缀): ++, --, !, -, +, ~, new
     */
    private fun parseUnary(): Expression {
        val startPos = pos
        // 前缀 ++
        if (match(TokenType.INCREMENT)) {
            val operand = parseUnary()
            if (operand is Identifier || operand is MemberExpr) {
                val expr = UpdateExpr("++", operand, true) // prefix = true
                expr.range = tokens[startPos].range.first..operand.range.last
                expr.tokens = buildList {
                    addAll(tokens.subList(startPos, startPos + 1)) // ++
                    addAll(operand.tokens)
                }
                return expr
            } else {
                throw createParseException("Invalid left-hand side expression in prefix operation")
            }
        }
        // 前缀 --
        if (match(TokenType.DECREMENT)) {
            val operand = parseUnary()
            if (operand is Identifier || operand is MemberExpr) {
                val expr = UpdateExpr("--", operand, true) // prefix = true
                expr.range = tokens[startPos].range.first..operand.range.last
                expr.tokens = buildList {
                    addAll(tokens.subList(startPos, startPos + 1)) // --
                    addAll(operand.tokens)
                }
                return expr
            } else {
                throw createParseException("Invalid left-hand side expression in prefix operation")
            }
        }

        // 其他一元操作符 (!, -, +, ~, typeof)
        if (match(TokenType.NOT, TokenType.MINUS, TokenType.PLUS, TokenType.BITWISE_NOT, TokenType.TYPEOF)) {
            val operator = previous().value
            val operand = parseUnary()
            val expr = UnaryOp(operator, operand)
            expr.range = tokens[startPos].range.first..previous().range.last
            expr.tokens = tokens.subList(startPos, pos)
            return expr
        }

        return parsePostfix()
    }

    /**
     * 解析后缀运算和函数调用: a++, a--, functionCall()
     */
    private fun parsePostfix(): Expression {
        var expr = parseMember()
        // 后缀 ++
        if ((expr is Identifier || expr is MemberExpr) && match(TokenType.INCREMENT)) {
            val newExpr = UpdateExpr("++", expr, false) // prefix = false
            newExpr.range = expr.range.first..previous().range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                add(previous())
            }
            expr = newExpr
        } else if ((expr is Identifier || expr is MemberExpr) && match(TokenType.DECREMENT)) {
            // 后缀 --
            val newExpr = UpdateExpr("--", expr, false) // prefix = false
            newExpr.range = expr.range.first..previous().range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                add(previous())
            }
            expr = newExpr
        }

        // 函数调用链 call()()
        while (match(TokenType.LPAREN)) {
            val startPos = pos - 1
            val args = mutableListOf<Expression>()
            if (!check(TokenType.RPAREN)) {
                do {
                    args.add(parseExpression())
                } while (match(TokenType.COMMA))
            }
            consume(TokenType.RPAREN, "Expected ')' after function arguments")
            val newExpr = CallExpr(expr, args)
            newExpr.range = expr.range.first..previous().range.last
            newExpr.tokens = buildList {
                addAll(expr.tokens)
                addAll(tokens.subList(startPos, pos))
            }
            expr = newExpr
        }

        return expr
    }

    /**
     * 解析成员访问: obj.prop 或 obj['prop']
     */
    private fun parseMember(): Expression {
        val startPos = pos
        var expr = if (match(TokenType.NEW)) {
            val constructor = parseMember() // 递归调用以支持 new new A
            val args = if (match(TokenType.LPAREN)) {
                val argList = mutableListOf<Expression>()
                if (!check(TokenType.RPAREN)) {
                    do {
                        argList.add(parseExpression())
                    } while (match(TokenType.COMMA))
                }
                consume(TokenType.RPAREN, "Expected ')'")
                argList
            } else {
                emptyList()
            }
            val newExpr = NewExpr(constructor, args)
            newExpr.range = tokens[startPos].range.first..previous().range.last
            newExpr.tokens = tokens.subList(startPos, pos)
            newExpr
        } else {
            parsePrimary()
        }

        while (true) {
            when {
                // 点号访问
                match(TokenType.DOT) -> {
                    val propertyPos = pos
                    val propertyToken = consume(TokenType.IDENTIFIER, "Expected property name after '.'")
                    val property = Identifier(propertyToken.value)
                    property.tokens = listOf(propertyToken)
                    property.range = propertyPos .. propertyPos
                    val newExpr = MemberExpr(expr, property, false)
                    newExpr.range = expr.range.first..previous().range.last
                    newExpr.tokens = tokens.subList(startPos, pos)
                    expr = newExpr
                }

                // 括号访问 (Computed property)
                match(TokenType.LBRACKET) -> {
                    val index = parseExpression()
                    consume(TokenType.RBRACKET, "Expected ']' after computed member expression")
                    val newExpr = MemberExpr(expr, index, true)
                    newExpr.range = expr.range.first..previous().range.last
                    newExpr.tokens = tokens.subList(startPos, pos)
                    expr = newExpr
                }

                else -> break
            }
        }

        return expr
    }

    /**
     * 解析最基础的表达式 (Primary Expression)
     * 包括：字面量(数字/字符串/布尔/Null)、this、括号表达式、数组字面量、对象字面量等
     */
    private fun parsePrimary(): Expression {
        val startPos = pos
        val expr: Expression = when {
            match(TokenType.NUMBER) -> NumberLiteral(
                try {
                    previous().value.toDouble()
                } catch (_: Throwable) {
                    throw createParseException("Invalid number ${previous().value}")
                }
            )
            match(TokenType.NUMBER_HEX) -> {
                val value = previous().value
                val number = value.substring(2).toLong(16).toDouble()
                NumberLiteral(number)
            }

            match(TokenType.NUMBER_OCT) -> {
                val value = previous().value
                val number = value.substring(2).toLong(8).toDouble()
                NumberLiteral(number)
            }

            match(TokenType.NUMBER_BIN) -> {
                val value = previous().value
                val number = value.substring(2).toLong(2).toDouble()
                NumberLiteral(number)
            }

            match(TokenType.STRING) -> StringLiteral(previous().value)
            match(TokenType.TRUE) -> BooleanLiteral(true)
            match(TokenType.FALSE) -> BooleanLiteral(false)
            match(TokenType.NULL) -> NullLiteral()
            match(TokenType.THIS) -> ThisExpr()
            match(TokenType.FUNCTION) -> parseFunctionExpression()
            match(TokenType.IDENTIFIER) -> Identifier(previous().value)
            // 括号表达式 (Expression)
            match(TokenType.LPAREN) -> {
                val e = parseExpression()
                consume(TokenType.RPAREN, "Expected ')' after expression")
                // Parenthesized expression range should probably include parens?
                // But the returned node is the inner expression.
                // If we return 'e', we keep 'e's range.
                // The user probably wants the range of the expression *within* the parens for the node itself.
                // But if we want to map "source code", (1+2) should map to 1+2 ?
                // Usually parens are not nodes.
                // So let's keep 'e'.
                // But wait, if I select (1+2) in source, I might expect the node 1+2.
                // So keeping 'e' as is is standard.
                e
            }

            // 数组字面量 [1, 2]
            match(TokenType.LBRACKET) -> {
                val elements = mutableListOf<Expression>()
                if (!check(TokenType.RBRACKET)) {
                    do {
                        elements.add(parseExpression())
                        if (!check(TokenType.COMMA)) {
                            break
                        }
                        advance()
                        if (check(TokenType.RBRACKET)) {
                            break
                        }
                    } while (true)
                }
                consume(TokenType.RBRACKET, "Expected ']' after array literal")
                ArrayLiteral(elements)
            }

            // 对象字面量 { a: 1, b: 2 }
            match(TokenType.LBRACE) -> {
                val properties = mutableMapOf<String, Expression>()
                if (!check(TokenType.RBRACE)) {
                    do {
                        val key = parseObjectKey()
                        consume(TokenType.COLON, "Expected ':' after object key")
                        val value = parseExpression()
                        properties[key] = value
                        if (!check(TokenType.COMMA)) {
                            break
                        }
                        advance()
                        if (check(TokenType.RBRACE)) {
                            break
                        }
                    } while (true)
                }
                consume(TokenType.RBRACE, "Expected '}' after object literal")
                ObjectLiteral(properties)
            }

            else -> throw createParseException("Unexpected token: ${peek().value}")
        }

        // 如果具体的解析方法（如 parseFunctionExpression, parseAssignment 等）已经设置了 range (即 range 不为空)，
        // 那么我们就保留它。
        // 如果 range 还是空的（例如字面量、ArrayLiteral、ObjectLiteral 在 when 块里是直接创建的，没有设置 range），
        // 则在这里统一设置。
        if (expr.range == IntRange.EMPTY) {
            expr.range = tokens[startPos].range.first..previous().range.last
            expr.tokens = tokens.subList(startPos, pos)
        }

        return expr
    }

    /**
     * 解析对象字面量的键 (Key)
     * 支持 标识符、字符串、数字 作为 Key
     */
    private fun parseObjectKey(): String {
        return when {
            check(TokenType.IDENTIFIER) -> advance().value
            check(TokenType.STRING) -> advance().value
            check(TokenType.NUMBER) -> advance().value
            else -> throw createParseException("Expected property key (identifier, string, or number)")
        }
    }

    /**
     * 检查当前 Token 是否匹配任意给定类型
     * 如果匹配，消费该 Token 并返回 true
     */
    private fun match(vararg types: TokenType): Boolean {
        for (type in types) {
            if (check(type)) {
                advance()
                return true
            }
        }
        return false
    }

    /**
     * 检查当前 Token 是否是指定类型 (不消费 Token)
     */
    private fun check(type: TokenType): Boolean = if (isAtEnd()) false else peek().type == type

    /**
     * 消费当前 Token 并将位置后移
     */
    private fun advance(): Token {
        if (!isAtEnd()) pos++
        return previous()
    }

    private fun isAtEnd(): Boolean = peek().type == TokenType.EOF

    private fun peek(): Token = tokens[pos]

    private fun previous(): Token = tokens[pos - 1]

    /**
     * 消费指定类型的令牌，如果不匹配则抛出异常并包含位置信息
     */
    private fun consume(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        throw createParseException(message)
    }

    /**
     * 创建包含位置信息的解析异常
     */
    private fun createParseException(message: String): ParseException {
        val token = peek()
        return ParseException(message, token.line, token.column, token)
    }

    /**
     * 尝试消费一个分号 (如果存在)
     * 用于语句末尾，支持自动分号插入(ASI)的简易模拟(这里仅跳过显式分号)
     */
    private fun consumeSemicolon() {
        if (check(TokenType.SEMICOLON)) {
            advance()
        }
    }
}
