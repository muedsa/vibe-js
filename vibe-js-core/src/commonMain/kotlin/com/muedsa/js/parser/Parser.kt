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
class Parser(
    private val tokens: List<Token>,
    strictContext: Boolean = false,
    superPropertyContext: Boolean = false,
    superCallContext: Boolean = false,
    newTargetContext: Boolean = false,
) {

    /** 当前解析位置（tokens 下标）。 */
    private var pos = 0

    /** 解析起点的严格模式（直接 eval 可继承调用方严格模式）。 */
    private val initialStrict = strictContext

    /** 当前所在的严格模式上下文；解析函数时会临时切换。 */
    private var strict = strictContext

    /** 当前是否处于 async 函数体（用于解析 await）。 */
    private var asyncContext = false

    /** 函数嵌套深度；顶层脚本为 0，用于检查 return 位置。 */
    private var functionDepth = 0

    /** 当前函数内的循环深度（进入函数时重置）。 */
    private var loopDepth = 0

    /** 当前函数内的 switch 深度（进入函数时重置）。 */
    private var switchDepth = 0

    /** 当前可见的标签（进入函数时清空）。 */
    private val labelScopes = mutableListOf<LabelScope>()

    /** 标签及其是否标记迭代语句（continue 只能指向迭代标签）。 */
    private data class LabelScope(val name: String, val iteration: Boolean)

    /** 严格模式下不允许作为赋值目标/绑定名的标识符。 */
    private val STRICT_RESTRICTED_NAMES = setOf("eval", "arguments")

    /** 无论是否严格模式都不能作为标识符的未来保留字（词法上未单独建 Token 的部分）。 */
    private val ALWAYS_RESERVED_WORDS = setOf("enum", "export", "import")

    /** 严格模式下不能作为标识符的未来保留字。 */
    private val STRICT_FUTURE_RESERVED_WORDS = setOf(
        "implements", "interface", "let", "package", "private", "protected",
        "public", "static", "yield",
    )

    /** 始终保留的关键字与字面量关键字；转义形式不能作为标识符/引用。 */
    private val ALWAYS_RESERVED_KEYWORDS = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "finally",
        "for", "function", "if", "import", "in", "instanceof", "new", "return",
        "super", "switch", "this", "throw", "try", "typeof", "var", "void",
        "while", "with", "enum", "true", "false", "null",
    )

    /** sloppy 模式下 `let` 可作为普通标识符；转义形式仍视为关键字。 */
    private fun allowLetAsIdentifier(): Boolean = !strict && !peek().containsEscape

    /** `let` 之后是否构成词法声明（绑定额或解构模式）。 */
    private fun isLetDeclarationStart(): Boolean = when (peek(1).type) {
        TokenType.IDENTIFIER, TokenType.LET, TokenType.LBRACKET, TokenType.LBRACE -> true
        else -> false
    }

    /** 读取绑定标识符：sloppy 下允许 let。 */
    private fun consumeBindingIdentifier(message: String): Token {
        val token = when {
            check(TokenType.IDENTIFIER) -> advance()
            check(TokenType.LET) && allowLetAsIdentifier() -> advance()
            else -> throw error(message)
        }
        validateIdentifierToken(token)
        return token
    }

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
        // 严格模式指令作用于整个脚本，需在解析任何语句前预扫描
        strict = initialStrict || detectUseStrictDirectiveAt(0)
        val start = peek().range.start
        val body = mutableListOf<Statement>()
        var inDirectivePrologue = true

        pushDeclScope(DeclScopeKind.PROGRAM)
        try {
            while (!isAtEnd()) {
                val statement = parseStatement()
                body.add(statement)

                // 指令序言：开头连续的字符串字面量表达式语句
                if (inDirectivePrologue) {
                    if (statement is ExpressionStatement && statement.expression is StringLiteral) {
                        // 只有源码字面形式完全一致（无转义）才是指令
                        if (!statement.expression.containsEscape &&
                            statement.expression.value == "use strict"
                        ) {
                            strict = true
                        }
                    } else {
                        // 空语句不是 ExpressionStatement，也会结束指令序言
                        inDirectivePrologue = false
                    }
                }
            }
        } finally {
            popDeclScope()
        }
        checkDeferredProtoDuplicates()

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
        var annexBFunction = false
        var singleStatementAtStart = false
        if (singleStatementContext) {
            singleStatementAtStart = true
            val tokenType = peek().type
            if ((tokenType == TokenType.LET && isLetDeclarationStart()) ||
                tokenType == TokenType.CONST || tokenType == TokenType.CLASS
            ) {
                throw error("Lexical declaration cannot appear in a single-statement context")
            }
            if (tokenType == TokenType.FUNCTION && (!allowFunctionInSingleStatement || strict)) {
                throw error("Function declaration cannot appear in a single-statement context")
            }
            // async function 声明不存在 Annex B 单语句例外
            if (tokenType == TokenType.IDENTIFIER && !peek().containsEscape &&
                peek().value == "async" && peek(1).type == TokenType.FUNCTION &&
                !peek(1).precededByNewline
            ) {
                throw error("Async function declaration cannot appear in a single-statement context")
            }
            // Annex B 的 if 分支函数声明不参与 var/词法冲突检查
            annexBFunction = tokenType == TokenType.FUNCTION && allowFunctionInSingleStatement
            // 只对当前语句生效；块/复合语句内部的子语句不再继承该上下文
            singleStatementContext = false
            allowFunctionInSingleStatement = false
        }
        return when (peek().type) {
            TokenType.SEMICOLON -> {
                val token = advance()
                EmptyStatement(token.range)
            }

            TokenType.VAR, TokenType.LET, TokenType.CONST ->
                if (peek().type == TokenType.LET && allowLetAsIdentifier() && !isLetDeclarationStart()) {
                    val expression = parseExpression()
                    val start = expression.range.start
                    consumeSemicolon()
                    ExpressionStatement(expression, SourceRange(start, previous().range.end))
                } else {
                    parseVarDeclaration()
                }
            TokenType.FUNCTION -> parseFunctionDeclaration(annexBLegacy = annexBFunction)
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
                if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                    peek().value == "async" && peek(1).type == TokenType.FUNCTION &&
                    !peek(1).precededByNewline
                ) {
                    return parseFunctionDeclaration(isAsync = true)
                }
                // 标签语句：identifier ':' statement
                if (peek().type == TokenType.IDENTIFIER && peek(1).type == TokenType.COLON) {
                    return parseLabeledStatement(singleStatementAtStart)
                }
                val expression = parseExpression()
                val start = expression.range.start
                consumeSemicolon()
                ExpressionStatement(expression, SourceRange(start, previous().range.end))
            }
        }
    }

    /** 标签语句。 */
    private fun parseLabeledStatement(singleContextAtStart: Boolean): LabeledStatement {
        val labelToken = consume(TokenType.IDENTIFIER, "Expected label")
        validateIdentifierToken(labelToken)
        validateIdentifierName(labelToken.value, strict)
        if (strict && labelToken.value in STRICT_RESTRICTED_NAMES) {
            throw error("Unexpected eval or arguments in strict mode")
        }
        if (labelScopes.any { it.name == labelToken.value }) {
            throw error("Label '${labelToken.value}' has already been declared")
        }
        consume(TokenType.COLON, "Expected ':' after label")
        val iteration = isIterationLabelBody()
        // 标签链的起点决定 sloppy 函数声明是否合法：
        // 语句列表位置的 `a: b: function f(){}` 允许，单语句体中的标签链不允许。
        val chainStart = !inLabelChain
        if (chainStart) {
            inLabelChain = true
            labelChainAllowsFunction = !singleContextAtStart
        }
        labelScopes.add(LabelScope(labelToken.value, iteration))
        val body = try {
            parseSingleStatement(allowFunctionDeclaration = labelChainAllowsFunction)
        } finally {
            labelScopes.removeAt(labelScopes.lastIndex)
            if (chainStart) inLabelChain = false
        }
        return LabeledStatement(labelToken.value, body, SourceRange(labelToken.range.start, body.range.end))
    }

    /**
     * 判断标签体是否为迭代语句；支持 `a: b: while (...) {}` 这类标签链。
     */
    private fun isIterationLabelBody(): Boolean {
        var index = pos
        while (index + 1 < tokens.size &&
            tokens[index].type == TokenType.IDENTIFIER &&
            tokens[index + 1].type == TokenType.COLON
        ) {
            index += 2
        }
        return index < tokens.size && tokens[index].type in setOf(
            TokenType.WHILE, TokenType.DO, TokenType.FOR,
        )
    }

    /** 当前是否允许 SuperProperty（类/对象方法体内，箭头函数继承）。 */
    private var superPropertyAllowed = superPropertyContext

    /** 当前是否允许 SuperCall（仅派生类构造函数）。 */
    private var superCallAllowed = superCallContext

    /** 当前是否允许 new.target（普通函数体，箭头函数继承外层）。 */
    private var newTargetAllowed = newTargetContext

    /** 当前是否在派生类中（用于解析类成员时的 super() 校验）。 */
    private var classDerived = false

    /** 被括号包裹的表达式范围；解构模式中不允许括号化目标。 */
    private val parenthesizedRanges = mutableSetOf<SourceRange>()

    /** 尚未确认是否为赋值/绑定模式的重复 __proto__ 对象字面量范围。 */
    private val duplicateProtoLiteralRanges = mutableSetOf<SourceRange>()

    /** 最近解析的属性键 Token（用于禁止简写属性引用转义关键字）。 */
    private var lastPropertyKeyToken: Token? = null

    /** 解析模式语法时允许对象简写默认值（{a = 1}）。 */
    private var patternSyntaxDepth = 0

    /** 在模式深度内已解析的简写默认值数量，用于表达式上下文报错。 */
    private var patternDefaultsSeen = 0

    /** 解析绑定模式（数组/对象字面量），并消费其中的默认值计数。 */
    private fun parseBindingPatternLiteral(): Expression {
        val defaultsBefore = patternDefaultsSeen
        val pattern = withPatternSyntax {
            if (check(TokenType.LBRACKET)) parseArrayLiteral() else parseObjectLiteral()
        }
        patternDefaultsSeen = defaultsBefore
        // 绑定模式中的对象字面量允许重复 __proto__ 属性名
        clearDuplicateProtoRanges(pattern)
        return pattern
    }

    /** 模式中的对象字面量不适用 ObjectLiteral 的重复 __proto__ 早期错误。 */
    private fun clearDuplicateProtoRanges(expression: Expression) {
        when (expression) {
            is ObjectLiteral -> {
                duplicateProtoLiteralRanges.remove(expression.range)
                expression.properties.forEach { property ->
                    when (property) {
                        is ObjectProperty -> clearDuplicateProtoRanges(property.value)
                        is ObjectSpreadProperty -> clearDuplicateProtoRanges(property.argument)
                    }
                }
            }

            is ArrayLiteral -> expression.elements.forEach { it?.let { clearDuplicateProtoRanges(it) } }
            is AssignmentExpr -> clearDuplicateProtoRanges(expression.target)
            is SpreadElement -> clearDuplicateProtoRanges(expression.argument)
            else -> Unit
        }
    }

    /** 解析结束时统一报告未被模式化使用的重复 __proto__ 对象字面量。 */
    private fun checkDeferredProtoDuplicates() {
        val range = duplicateProtoLiteralRanges.firstOrNull() ?: return
        throw ParseException("Duplicate __proto__ fields are not allowed in object literal", range)
    }

    private inline fun <T> withPatternSyntax(block: () -> T): T {
        patternSyntaxDepth++
        return try {
            block()
        } finally {
            patternSyntaxDepth--
        }
    }

    /** 当前是否位于单语句上下文（if/循环/with/label 的 body）。 */
    private var singleStatementContext = false

    /** 单语句上下文中是否允许 sloppy 的函数声明（Annex B 的 if 分支）。 */
    private var allowFunctionInSingleStatement = false

    /** 声明追踪作用域类型（用于重复绑定与 var/词法冲突的早期错误）。 */
    private enum class DeclScopeKind { PROGRAM, FUNCTION_BODY, BLOCK, SWITCH, CATCH, FOR }

    /**
     * 声明追踪帧。
     *
     * - lexicalNames：let/const/class 以及块级 function 的名称；
     * - functionNames：块级 function 名称，sloppy 模式下允许同类重名；
     * - varNames：本作用域内出现的 var（含嵌套块传播上来的）；
     * - parameterNames：函数形参或 catch 参数，用于参数与函数体词法声明的冲突检查。
     */
    private class DeclScope(
        val kind: DeclScopeKind,
        val parameterNames: Set<String> = emptySet(),
        val catchHasPattern: Boolean = false,
    ) {
        val lexicalNames = mutableSetOf<String>()
        val functionNames = mutableSetOf<String>()
        val asyncFunctionNames = mutableSetOf<String>()
        val varNames = mutableSetOf<String>()

        /** 块/switch/catch 中的 function 声明按词法声明处理。 */
        val treatsFunctionsAsLexical: Boolean
            get() = kind == DeclScopeKind.BLOCK || kind == DeclScopeKind.SWITCH ||
                kind == DeclScopeKind.CATCH
    }

    /** 当前声明追踪作用域栈。 */
    private val declScopes = mutableListOf<DeclScope>()

    /** 是否位于标签链内部，以及该标签链是否允许 sloppy 函数声明。 */
    private var inLabelChain = false
    private var labelChainAllowsFunction = false

    private fun pushDeclScope(
        kind: DeclScopeKind,
        parameterNames: Set<String> = emptySet(),
        catchHasPattern: Boolean = false,
    ) {
        declScopes.add(DeclScope(kind, parameterNames, catchHasPattern))
    }

    private fun popDeclScope() {
        if (declScopes.isNotEmpty()) declScopes.removeAt(declScopes.lastIndex)
    }

    /** 严格模式绑定名校验（eval/arguments 不能作为绑定名）。 */
    private fun validateStrictBindingName(name: String) {
        if (strict && name in STRICT_RESTRICTED_NAMES) {
            throw error("Unexpected eval or arguments in strict mode")
        }
    }

    /**
     * 登记词法绑定（let/const/class，或块级 function 声明）。
     *
     * 重复检查覆盖：同一作用域内的词法重名、与已出现的 var 冲突、
     * 以及函数体中形参/catch 参数与顶层词法声明的冲突。
     */
    private fun declareLexicalBinding(
        name: String,
        isFunctionDeclaration: Boolean = false,
        isAsyncFunction: Boolean = false,
    ) {
        validateStrictBindingName(name)
        if (!isFunctionDeclaration && name == "let") {
            throw error("let is not a valid lexical binding name")
        }
        val scope = declScopes.lastOrNull() ?: return
        if (name in scope.parameterNames) {
            throw error("Identifier '$name' has already been declared")
        }
        if (name in scope.lexicalNames) {
            // sloppy 模式下普通块级 function 允许同名重复；async function 不适用
            if (isFunctionDeclaration && !strict && name in scope.functionNames &&
                !isAsyncFunction && name !in scope.asyncFunctionNames
            ) {
                return
            }
            throw error("Identifier '$name' has already been declared")
        }
        if (name in scope.varNames) {
            throw error("Identifier '$name' has already been declared")
        }
        scope.lexicalNames.add(name)
        if (isFunctionDeclaration) {
            scope.functionNames.add(name)
            if (isAsyncFunction) scope.asyncFunctionNames.add(name)
        }
    }

    /** 登记 var 绑定：向最近的函数体/程序作用域传播并逐层检查词法冲突。 */
    private fun declareVarBinding(name: String) {
        validateStrictBindingName(name)
        for (index in declScopes.indices.reversed()) {
            val scope = declScopes[index]
            if (scope.kind == DeclScopeKind.CATCH &&
                scope.catchHasPattern && name in scope.parameterNames
            ) {
                // 解构 catch 参数不能与 catch 块内的 var 重名（简单参数按 Annex B 允许）
                throw error("Identifier '$name' has already been declared")
            }
            if (name in scope.lexicalNames) {
                throw error("Identifier '$name' has already been declared")
            }
            scope.varNames.add(name)
            if (scope.kind == DeclScopeKind.PROGRAM || scope.kind == DeclScopeKind.FUNCTION_BODY) {
                break
            }
        }
    }

    /** 登记 function 声明：块级按词法声明，其他位置按 var 声明。 */
    private fun declareFunctionBinding(name: String, isAsync: Boolean = false) {
        val scope = declScopes.lastOrNull()
        if (scope != null && scope.treatsFunctionsAsLexical) {
            declareLexicalBinding(name, isFunctionDeclaration = true, isAsyncFunction = isAsync)
        } else {
            declareVarBinding(name)
        }
    }

    /** 收集模式中的全部绑定名（保留重复项，用于重复绑定检查）。 */
    private fun collectPatternNamesWithDuplicates(
        expression: Expression,
        result: MutableList<String>,
    ) {
        when (expression) {
            is Identifier -> result.add(expression.name)
            is ArrayLiteral -> expression.elements.forEach { element ->
                element?.let { collectPatternNamesWithDuplicates(it, result) }
            }

            is ObjectLiteral -> expression.properties.forEach { property ->
                when (property) {
                    is ObjectProperty -> collectPatternNamesWithDuplicates(property.value, result)
                    is ObjectSpreadProperty -> collectPatternNamesWithDuplicates(property.argument, result)
                }
            }

            is AssignmentExpr -> collectPatternNamesWithDuplicates(expression.target, result)
            is SpreadElement -> collectPatternNamesWithDuplicates(expression.argument, result)
            else -> Unit
        }
    }

    /** 收集参数绑定的全部名称（保留重复项）。 */
    private fun collectParameterNames(params: List<Parameter>): List<String> {
        val names = mutableListOf<String>()
        for (param in params) {
            if (param.name != null) {
                names.add(param.name)
            } else {
                param.pattern?.let { collectPatternNamesWithDuplicates(it, names) }
            }
        }
        return names
    }

    /** 解析单语句体，并记录上下文以拒绝词法声明等早期错误。 */
    private fun parseSingleStatement(allowFunctionDeclaration: Boolean = false): Statement {
        val previousContext = singleStatementContext
        val previousAllowFunction = allowFunctionInSingleStatement
        singleStatementContext = true
        allowFunctionInSingleStatement = allowFunctionDeclaration
        return try {
            parseStatement()
        } finally {
            singleStatementContext = previousContext
            allowFunctionInSingleStatement = previousAllowFunction
        }
    }

    /** 在循环上下文中解析语句体。 */
    private inline fun <T> withLoopContext(block: () -> T): T {
        loopDepth++
        return try {
            block()
        } finally {
            loopDepth--
        }
    }

    /** 在 switch 上下文中解析语句体（允许无标签 break）。 */
    private inline fun <T> withSwitchContext(block: () -> T): T {
        switchDepth++
        return try {
            block()
        } finally {
            switchDepth--
        }
    }

    /** 严格模式下不允许对 eval/arguments 赋值或自增。 */
    private fun validateStrictAssignmentTarget(target: Expression) {
        if (!strict) return
        when (target) {
            is Identifier -> if (target.name in STRICT_RESTRICTED_NAMES) {
                throw error("Unexpected eval or arguments in strict mode")
            }

            is AssignmentExpr -> validateStrictAssignmentTarget(target.target)
            is ArrayLiteral -> for (element in target.elements) {
                when (element) {
                    null -> Unit
                    is SpreadElement -> validateStrictAssignmentTarget(element.argument)
                    else -> validateStrictAssignmentTarget(element)
                }
            }

            is ObjectLiteral -> for (property in target.properties) {
                when (property) {
                    is ObjectProperty -> validateStrictAssignmentTarget(property.value)
                    is ObjectSpreadProperty -> validateStrictAssignmentTarget(property.argument)
                }
            }

            else -> Unit
        }
    }

    /** 校验 getter/setter 的形参数量。 */
    private fun validateAccessorParameters(params: List<Parameter>, getter: Boolean) {
        if (getter) {
            if (params.isNotEmpty()) {
                throw error("Getter must not have any formal parameters")
            }
        } else if (params.size != 1 || params[0].rest) {
            throw error("Setter must have exactly one formal parameter")
        }
    }

    /** 校验赋值模式（`=` 左侧的解构）：允许标识符、成员访问与嵌套模式。 */
    private fun validateAssignmentPattern(pattern: Expression) {
        if (pattern.range in parenthesizedRanges &&
            pattern !is Identifier && pattern !is MemberExpr
        ) {
            throw error("Invalid destructuring target")
        }
        when (pattern) {
            is Identifier, is MemberExpr -> Unit
            is AssignmentExpr -> {
                if (pattern.operator != "=") {
                    throw error("Invalid destructuring target")
                }
                validateAssignmentPattern(pattern.target)
            }
            is ArrayLiteral -> {
                if (pattern.restFollowedByComma) throw error("Rest element must be last element")
                var restSeen = false
                for (element in pattern.elements) {
                    if (restSeen) throw error("Rest element must be last")
                    when (element) {
                        null -> Unit
                        is SpreadElement -> {
                            restSeen = true
                            if (element.argument is AssignmentExpr) {
                                throw error("Rest element may not have a default initializer")
                            }
                            validateAssignmentPattern(element.argument)
                        }

                        else -> validateAssignmentPattern(element)
                    }
                }
            }

            is ObjectLiteral -> {
                if (pattern.restFollowedByComma) {
                    throw error("Rest element may not be followed by a comma")
                }
                var restSeen = false
                for (property in pattern.properties) {
                    if (restSeen) throw error("Rest element must be last")
                    when (property) {
                        is ObjectProperty -> {
                            if (property.kind != PropertyKind.INIT) {
                                throw error("Invalid destructuring assignment target")
                            }
                            validateAssignmentPattern(property.value)
                        }

                        is ObjectSpreadProperty -> {
                            restSeen = true
                            validateAssignmentPattern(property.argument)
                        }
                    }
                }
            }

            else -> throw error("Invalid assignment target")
        }
    }

    /** 校验声明/参数中的绑定模式：只允许标识符、数组/对象模式与默认值。 */
    private fun validateBindingPattern(pattern: Expression) {
        if (pattern.range in parenthesizedRanges) {
            throw error("Invalid destructuring target")
        }
        when (pattern) {
            is Identifier -> Unit
            is AssignmentExpr -> validateBindingPattern(pattern.target)
            is ArrayLiteral -> {
                if (pattern.restFollowedByComma) throw error("Rest element must be last element")
                var restSeen = false
                for (element in pattern.elements) {
                    if (restSeen) throw error("Rest element must be last")
                    when (element) {
                        null -> Unit
                        is SpreadElement -> {
                            restSeen = true
                            if (element.argument is AssignmentExpr) {
                                throw error("Rest element may not have a default initializer")
                            }
                            validateBindingPattern(element.argument)
                        }

                        else -> validateBindingPattern(element)
                    }
                }
            }

            is ObjectLiteral -> {
                if (pattern.restFollowedByComma) {
                    throw error("Rest element may not be followed by a comma")
                }
                var restSeen = false
                for (property in pattern.properties) {
                    if (restSeen) throw error("Rest element must be last")
                    when (property) {
                        is ObjectProperty -> {
                            if (property.kind != PropertyKind.INIT) {
                                throw error("Invalid destructuring target")
                            }
                            validateBindingPattern(property.value)
                        }

                        is ObjectSpreadProperty -> {
                            restSeen = true
                            validateBindingPattern(property.argument)
                        }
                    }
                }
            }

            else -> throw error("Invalid destructuring target")
        }
    }

    /** do-while 语句。 */
    private fun parseDoWhileStatement(): DoWhileStatement {
        val start = consume(TokenType.DO, "Expected 'do'").range.start
        val body = withLoopContext { parseSingleStatement() }
        consume(TokenType.WHILE, "Expected 'while' after do body")
        consume(TokenType.LPAREN, "Expected '(' after 'while'")
        val condition = parseExpression()
        val end = consume(TokenType.RPAREN, "Expected ')' after do-while condition").range.end
        // do-while 结尾的分号按规范可省略（即使同一行后接语句）
        match(TokenType.SEMICOLON)
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
        val body = parseSingleStatement()
        return WithStatement(objectExpression, body, SourceRange(start, body.range.end))
    }

    /** class 声明。 */
    private fun parseClassDeclaration(): ClassDeclaration {
        val start = consume(TokenType.CLASS, "Expected 'class'").range.start
        val outerStrict = strict
        strict = true
        try {
            val name = consume(TokenType.IDENTIFIER, "Expected class name")
            validateIdentifierToken(name)
            validateIdentifierName(name.value, strict = true)
            if (name.value in STRICT_RESTRICTED_NAMES) {
                throw error("Unexpected eval or arguments in strict mode")
            }
            // 类声明以词法绑定形式登记到外层作用域
            declareLexicalBinding(name.value)
            val superClass = if (match(TokenType.EXTENDS)) parseLeftHandSide() else null
            val members = parseClassBody(derived = superClass != null)
            return ClassDeclaration(
                name = name.value,
                superClass = superClass,
                members = members,
                range = SourceRange(start, previous().range.end),
            )
        } finally {
            strict = outerStrict
        }
    }

    /** class 表达式。 */
    private fun parseClassExpression(): ClassExpression {
        val start = consume(TokenType.CLASS, "Expected 'class'").range.start
        val outerStrict = strict
        strict = true
        try {
            val nameToken = if (check(TokenType.IDENTIFIER)) advance() else null
            nameToken?.let {
                validateIdentifierToken(it)
                validateIdentifierName(it.value, strict = true)
                if (it.value in STRICT_RESTRICTED_NAMES) {
                    throw error("Unexpected eval or arguments in strict mode")
                }
            }
            val superClass = if (match(TokenType.EXTENDS)) parseLeftHandSide() else null
            val members = parseClassBody(derived = superClass != null)
            return ClassExpression(
                name = nameToken?.value,
                superClass = superClass,
                members = members,
                range = SourceRange(start, previous().range.end),
            )
        } finally {
            strict = outerStrict
        }
    }

    /** class 成员列表（类体默认严格模式）。 */
    private fun parseClassBody(derived: Boolean): List<ClassMember> {
        consume(TokenType.LBRACE, "Expected '{' after class header")
        val outerStrict = strict
        val outerDerived = classDerived
        strict = true
        classDerived = derived
        val members = mutableListOf<ClassMember>()
        var constructorSeen = false
        try {
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                if (match(TokenType.SEMICOLON)) continue
                val memberStart = peek().range.start

                var isStatic = false
                if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                    peek().value == "static" && canStartClassMember(1)
                ) {
                    isStatic = true
                    advance()
                }

                var kind = ClassMemberKind.METHOD
                var isAsync = false
                if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                    canStartClassMember(1)
                ) {
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
                val keyName = when (key) {
                    is Identifier -> key.name
                    is StringLiteral -> key.value
                    else -> null
                }
                if (isStatic && !computed && keyName == "prototype") {
                    throw error("Classes may not have a static property named 'prototype'")
                }
                // 非静态的 "constructor" 只允许普通方法，且最多一个
                var isConstructor = false
                if (!isStatic && !computed && keyName == "constructor") {
                    when {
                        kind == ClassMemberKind.GET || kind == ClassMemberKind.SET ->
                            throw error("Class constructor may not be an accessor")

                        isAsync -> throw error("Class constructor may not be an async method")
                        else -> {
                            if (constructorSeen) {
                                throw error("A class may only have one constructor")
                            }
                            constructorSeen = true
                            isConstructor = true
                        }
                    }
                }

                consume(TokenType.LPAREN, "Expected '(' in class member")
                val allowSuperCall = isConstructor && classDerived
                val (params, bodyInfo) = withFunctionContext(true, allowSuperCall, true) {
                    val parsedParams = withAsyncContext(isAsync) { parseParametersAfterOpen() }
                    when (kind) {
                        ClassMemberKind.GET -> validateAccessorParameters(parsedParams, getter = true)
                        ClassMemberKind.SET -> validateAccessorParameters(parsedParams, getter = false)
                        else -> Unit
                    }
                    val parsedBody = parseFunctionBody(
                        isAsync = isAsync,
                        allowSuperProperty = true,
                        allowSuperCall = allowSuperCall,
                        allowNewTarget = true,
                        params = parsedParams,
                    )
                    parsedParams to parsedBody
                }
                // 类成员总是严格模式，且使用 UniqueFormalParameters
                validateFunctionParameters(params, strict = true, isMethod = true)
                val function = FunctionExpression(
                    name = if (!computed && key is Identifier) key.name else "",
                    params = params,
                    body = bodyInfo.body,
                    strict = true,
                    isAsync = isAsync,
                    varDeclarations = bodyInfo.varDeclarations,
                    functionDeclarations = bodyInfo.functionDeclarations,
                    range = SourceRange(key.range.start, bodyInfo.body.range.end),
                    isMethodDefinition = true,
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
            classDerived = outerDerived
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
                TokenType.NUMBER_OCT, TokenType.NUMBER_BIN, TokenType.BIGINT,
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
                check(TokenType.LBRACKET) || check(TokenType.LBRACE) -> parseBindingPatternLiteral()
                else -> {
                    val nameToken = consumeBindingIdentifier("Expected identifier in declaration")
                    Identifier(nameToken.value, nameToken.range)
                }
            }
            if (target !is Identifier) {
                validateBindingPattern(target)
            } else {
                validateIdentifierName(target.name, strict)
                if (strict && target.name in STRICT_RESTRICTED_NAMES) {
                    throw error("Unexpected eval or arguments in strict mode")
                }
                if (kind != DeclarationKind.VAR && target.name == "let") {
                    throw error("let is not a valid lexical binding name")
                }
            }
            // 登记声明：var 允许重名；let/const 需要检查重复与 var 冲突
            val boundNames = mutableListOf<String>()
            if (target is Identifier) {
                boundNames.add(target.name)
            } else {
                collectPatternNamesWithDuplicates(target, boundNames)
            }
            for (name in boundNames) {
                if (kind == DeclarationKind.VAR) declareVarBinding(name) else declareLexicalBinding(name)
            }
            var initializer: Expression? = null
            if (match(TokenType.ASSIGN)) {
                initializer = parseAssignment()
            }
            if (initializer == null) {
                // const 与解构声明必须有初始化表达式
                if (kind == DeclarationKind.CONST) {
                    throw error("Missing initializer in const declaration")
                }
                if (target !is Identifier) {
                    throw error("Missing initializer in destructuring declaration")
                }
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
    private fun parseFunctionDeclaration(
        isAsync: Boolean = false,
        annexBLegacy: Boolean = false,
    ): FunctionDeclaration {
        val start = peek().range.start
        if (isAsync) advance() // async
        consume(TokenType.FUNCTION, "Expected 'function'")
        val name = consumeBindingIdentifier("Expected function name")
        // 函数声明登记到外层作用域；Annex B 的 if 分支函数不参与早期冲突检查
        if (!annexBLegacy) declareFunctionBinding(name.value, isAsync)
        val params = withFunctionContext(false, false, true) {
            withAsyncContext(isAsync) { parseParameters() }
        }
        val bodyInfo = parseFunctionBody(isAsync, params = params)
        validateFunctionParameters(params, bodyInfo.strict)
        validateIdentifierName(name.value, bodyInfo.strict)
        if (bodyInfo.strict && name.value in STRICT_RESTRICTED_NAMES) {
            throw error("Unexpected eval or arguments in strict mode")
        }
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
        val nameToken = if (check(TokenType.IDENTIFIER) ||
            (check(TokenType.LET) && allowLetAsIdentifier())
        ) {
            advance()
        } else {
            null
        }
        nameToken?.let { validateIdentifierToken(it) }
        val params = withFunctionContext(false, false, true) {
            withAsyncContext(isAsync) { parseParameters() }
        }
        val bodyInfo = parseFunctionBody(isAsync, params = params)
        validateFunctionParameters(params, bodyInfo.strict)
        nameToken?.let { validateIdentifierName(it.value, bodyInfo.strict) }
        if (bodyInfo.strict && nameToken?.value in STRICT_RESTRICTED_NAMES) {
            throw error("Unexpected eval or arguments in strict mode")
        }
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
    /** 在给定 super/new.target 上下文下解析参数与函数体。 */
    private inline fun <T> withFunctionContext(
        allowSuperProperty: Boolean,
        allowSuperCall: Boolean,
        allowNewTarget: Boolean,
        block: () -> T,
    ): T {
        val outerSuperProperty = superPropertyAllowed
        val outerSuperCall = superCallAllowed
        val outerNewTarget = newTargetAllowed
        superPropertyAllowed = allowSuperProperty
        superCallAllowed = allowSuperCall
        newTargetAllowed = allowNewTarget
        return try {
            block()
        } finally {
            superPropertyAllowed = outerSuperProperty
            superCallAllowed = outerSuperCall
            newTargetAllowed = outerNewTarget
        }
    }

    private fun parseFunctionBody(
        isAsync: Boolean = false,
        allowSuperProperty: Boolean = false,
        allowSuperCall: Boolean = false,
        allowNewTarget: Boolean = true,
        params: List<Parameter> = emptyList(),
    ): FunctionBodyInfo {
        val bodyStart = pos
        val bodyStrict = detectUseStrictDirective(bodyStart)
        val hasNonSimpleParams = params.any { it.rest || it.defaultValue != null || it.pattern != null }
        if (bodyStrict && hasNonSimpleParams) {
            throw error("Illegal 'use strict' directive in function with non-simple parameter list")
        }
        val outerStrict = strict
        val outerAsync = asyncContext
        val outerSuperProperty = superPropertyAllowed
        val outerSuperCall = superCallAllowed
        val outerNewTarget = newTargetAllowed
        strict = outerStrict || bodyStrict
        asyncContext = isAsync
        superPropertyAllowed = allowSuperProperty
        superCallAllowed = allowSuperCall
        newTargetAllowed = allowNewTarget

        // 函数边界重置循环/switch/标签上下文
        val outerFunctionDepth = functionDepth
        val outerLoopDepth = loopDepth
        val outerSwitchDepth = switchDepth
        val outerLabels = labelScopes.toList()
        val outerInLabelChain = inLabelChain
        val outerLabelChainAllowsFunction = labelChainAllowsFunction
        functionDepth++
        loopDepth = 0
        switchDepth = 0
        labelScopes.clear()
        inLabelChain = false

        val parameterNames = collectParameterNames(params).toSet()
        pushDeclScope(DeclScopeKind.FUNCTION_BODY, parameterNames)
        val body = try {
            parseBlockStatement(trackScope = false)
        } finally {
            popDeclScope()
            functionDepth = outerFunctionDepth
            loopDepth = outerLoopDepth
            switchDepth = outerSwitchDepth
            labelScopes.clear()
            labelScopes.addAll(outerLabels)
            inLabelChain = outerInLabelChain
            labelChainAllowsFunction = outerLabelChainAllowsFunction
            superPropertyAllowed = outerSuperProperty
            superCallAllowed = outerSuperCall
            newTargetAllowed = outerNewTarget
        }
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

    /**
     * 严格模式函数参数校验：
     * - 禁止重复参数名（strict / 箭头 / 方法 / 非简单参数列表）；
     * - 禁止解构参数内部重复绑定；
     * - 禁止使用 eval / arguments 作为参数名。
     */
    private fun validateFunctionParameters(
        params: List<Parameter>,
        strict: Boolean,
        isArrow: Boolean = false,
        isMethod: Boolean = false,
    ) {
        // 非简单参数列表（默认值/rest/解构）即使是 sloppy 也不允许重名
        val hasNonSimple = params.any { it.rest || it.defaultValue != null || it.pattern != null }
        val rejectDuplicates = strict || isArrow || isMethod || hasNonSimple
        val seen = mutableSetOf<String>()
        for (param in params) {
            val names = mutableListOf<String>()
            if (param.name != null) {
                names.add(param.name)
            } else {
                // 模式内部重复绑定始终非法（例如 ({a, a}) => 1）
                param.pattern?.let { collectPatternNamesWithDuplicates(it, names) }
                if (names.size != names.toSet().size) {
                    throw error("Duplicate parameter name not allowed in this context")
                }
            }
            for (name in names) {
                validateIdentifierName(name, strict)
                if (strict && name in STRICT_RESTRICTED_NAMES) {
                    throw error("Unexpected eval or arguments in strict mode")
                }
                if (rejectDuplicates && !seen.add(name)) {
                    throw error("Duplicate parameter name not allowed in this context")
                }
            }
        }
    }

    /** 校验标识符是否可用作绑定/引用（保留字、严格模式未来保留字）。 */
    private fun validateIdentifierName(name: String, strict: Boolean) {
        if (name in ALWAYS_RESERVED_WORDS) {
            throw error("Unexpected reserved word '$name'")
        }
        if (strict && name in STRICT_FUTURE_RESERVED_WORDS) {
            throw error("Unexpected reserved word '$name' in strict mode")
        }
    }

    /** 转义关键字不能在标识符位置使用；属性名/成员访问不受此限制。 */
    private fun validateEscapedKeyword(token: Token) {
        if (!token.containsEscape) return
        if (token.value in ALWAYS_RESERVED_KEYWORDS ||
            (strict && token.value in STRICT_FUTURE_RESERVED_WORDS)
        ) {
            throw ParseException("Keyword must not contain escaped characters", token.range)
        }
    }

    /** 标识符引用/绑定位置的额外校验：转义关键字与 async 上下文中的 await。 */
    private fun validateIdentifierToken(token: Token) {
        validateEscapedKeyword(token)
        if (asyncContext && token.value == "await") {
            throw ParseException("await is not a valid identifier in async context", token.range)
        }
    }

    /** 在 async 上下文中解析参数（await 不能作为绑定名）。 */
    private inline fun <T> withAsyncContext(isAsync: Boolean, block: () -> T): T {
        if (!isAsync) return block()
        val outer = asyncContext
        asyncContext = true
        return try {
            block()
        } finally {
            asyncContext = outer
        }
    }

    /** 解析函数参数（左括号已消费），支持默认值、rest 与解构。 */
    private fun parseParametersAfterOpen(): List<Parameter> {
        val params = mutableListOf<Parameter>()
        if (!check(TokenType.RPAREN)) {
            do {
                val start = peek().range.start
                if (match(TokenType.ELLIPSIS)) {
                    if (check(TokenType.LBRACKET) || check(TokenType.LBRACE)) {
                        val target = parseBindingPatternLiteral()
                        validateBindingPattern(target)
                        params.add(
                            Parameter(
                                name = null,
                                pattern = target,
                                defaultValue = null,
                                rest = true,
                                range = SourceRange(start, target.range.end),
                            )
                        )
                    } else {
                        val name = consumeBindingIdentifier("Expected rest parameter name")
                        params.add(
                            Parameter(
                                name = name.value,
                                pattern = null,
                                defaultValue = null,
                                rest = true,
                                range = SourceRange(start, name.range.end),
                            )
                        )
                    }
                    break
                }

                val target: Expression = when {
                    check(TokenType.LBRACKET) || check(TokenType.LBRACE) -> parseBindingPatternLiteral()
                    else -> {
                        val name = consumeBindingIdentifier("Expected parameter name")
                        Identifier(name.value, name.range)
                    }
                }
                if (target !is Identifier) {
                    validateBindingPattern(target)
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
                // 允许尾随逗号：(a, b,)；rest 参数后的逗号仍由 RPAREN 检查拒绝
            } while (match(TokenType.COMMA) && !check(TokenType.RPAREN))
        }
        consume(TokenType.RPAREN, "Expected ')' after parameters")
        return params
    }

    /** 解析参数列表。 */
    private fun parseParameters(): List<Parameter> {
        consume(TokenType.LPAREN, "Expected '(' before parameters")
        return parseParametersAfterOpen()
    }

    /** 块语句 `{ ... }`；函数体/catch 块复用外层声明作用域时传 trackScope = false。 */
    private fun parseBlockStatement(trackScope: Boolean = true): BlockStatement {
        val start = consume(TokenType.LBRACE, "Expected '{'").range.start
        if (trackScope) pushDeclScope(DeclScopeKind.BLOCK)
        val statements = mutableListOf<Statement>()
        try {
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                statements.add(parseStatement())
            }
        } finally {
            if (trackScope) popDeclScope()
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
        val consequent = parseSingleStatement(allowFunctionDeclaration = true)
        var alternate: Statement? = null
        if (match(TokenType.ELSE)) {
            alternate = parseSingleStatement(allowFunctionDeclaration = true)
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
        val body = withLoopContext { parseSingleStatement() }
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

        // for 头部词法声明与循环体 var 的冲突检查需要一个独立作用域帧
        pushDeclScope(DeclScopeKind.FOR)
        try {
            var init: Statement? = null
            if (!match(TokenType.SEMICOLON)) {
                val letIsExpression = check(TokenType.LET) && allowLetAsIdentifier() && !isLetDeclarationStart()
                init = if ((check(TokenType.VAR) || check(TokenType.CONST) ||
                        (check(TokenType.LET) && !letIsExpression))
                ) {
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

            val body = withLoopContext { parseSingleStatement() }
            return ForStatement(init, condition, update, body, SourceRange(start, body.range.end))
        } finally {
            popDeclScope()
        }
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
                TokenType.IDENTIFIER -> if (depth == 0 && !tokens[index].containsEscape &&
                    tokens[index].value == "of"
                ) {
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
        pushDeclScope(DeclScopeKind.FOR)
        try {
            return parseForInOfStatementBody(start, isOf)
        } finally {
            popDeclScope()
        }
    }

    private fun parseForInOfStatementBody(start: SourcePosition, isOf: Boolean): Statement {
        // for-of 左值不允许以 let 开头（Lookahead 限制）
        if (isOf && check(TokenType.LET) && !isLetDeclarationStart()) {
            throw error("The left-hand side of a for-of loop may not start with 'let'")
        }
        var declarationKind: DeclarationKind? = null
        var variableName: String? = null
        var pattern: Expression? = null
        var target: Expression? = null

        val letIsExpressionInHeader = check(TokenType.LET) && allowLetAsIdentifier() && when (peek(1).type) {
            TokenType.IDENTIFIER, TokenType.LET, TokenType.LBRACKET, TokenType.LBRACE -> false
            else -> true
        }
        if (check(TokenType.VAR) || check(TokenType.CONST) ||
            (check(TokenType.LET) && !letIsExpressionInHeader)
        ) {
            val kindToken = advance()
            declarationKind = when (kindToken.type) {
                TokenType.VAR -> DeclarationKind.VAR
                TokenType.LET -> DeclarationKind.LET
                else -> DeclarationKind.CONST
            }
            when {
                check(TokenType.LBRACKET) || check(TokenType.LBRACE) -> {
                    pattern = parseBindingPatternLiteral()
                    validateBindingPattern(pattern)
                }

                else -> {
                    val nameToken = consumeBindingIdentifier("Expected variable name in for-in/of")
                    variableName = nameToken.value
                    validateIdentifierName(nameToken.value, strict)
                    if (strict && nameToken.value in STRICT_RESTRICTED_NAMES) {
                        throw error("Unexpected eval or arguments in strict mode")
                    }
                }
            }
            // 兼容旧式 for (var k = 0 in obj)：初始化表达式仅解析并忽略；
            // for-of 的声明形式不允许初始化器
            if (match(TokenType.ASSIGN)) {
                if (isOf) {
                    throw error("for-of loop variable declaration may not have an initializer")
                }
                parseAssignment()
            }
        } else {
            val defaultsBefore = patternDefaultsSeen
            val expression = withPatternSyntax { parseLeftHandSide() }
            if (expression is ArrayLiteral || expression is ObjectLiteral) {
                validateAssignmentPattern(expression)
            } else if (expression !is Identifier && expression !is MemberExpr) {
                throw error("Invalid left-hand side in for-in/of")
            }
            // 严格模式下 for-in/of 左值同样适用简单赋值目标限制
            validateStrictAssignmentTarget(expression)
            patternDefaultsSeen = defaultsBefore
            target = expression
        }

        // 登记 for 头部声明：let/const 检查重复，var 向函数作用域传播
        if (declarationKind != null) {
            val names = mutableListOf<String>()
            if (variableName != null) {
                names.add(variableName)
            } else {
                pattern?.let { collectPatternNamesWithDuplicates(it, names) }
            }
            for (name in names) {
                if (declarationKind == DeclarationKind.VAR) {
                    declareVarBinding(name)
                } else {
                    declareLexicalBinding(name)
                }
            }
        }

        if (isOf) {
            val ofToken = peek()
            if (ofToken.type == TokenType.IDENTIFIER && !ofToken.containsEscape &&
                ofToken.value == "of"
            ) {
                advance()
            } else {
                throw error("Expected 'of' in for-of statement")
            }
        } else {
            consume(TokenType.IN, "Expected 'in' in for-in statement")
        }

        // for-in 右侧是 Expression（允许逗号），for-of 右侧是 AssignmentExpression
        val right = if (isOf) parseAssignment() else parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after for-in/of")
        val body = withLoopContext { parseSingleStatement() }
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
        // switch 各 case 共享同一词法作用域
        pushDeclScope(DeclScopeKind.SWITCH)
        switchDepth++
        try {
            var sawDefault = false
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
                        if (sawDefault) throw error("More than one default clause in switch statement")
                        sawDefault = true
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
        } finally {
            switchDepth--
            popDeclScope()
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
        if (label != null) {
            if (labelScopes.none { it.name == label }) {
                throw error("Undefined label '$label'")
            }
        } else if (loopDepth == 0 && switchDepth == 0) {
            throw error("Illegal break statement")
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
        if (label != null) {
            if (labelScopes.none { it.name == label && it.iteration }) {
                throw error("Continue target must be an iteration label")
            }
        } else if (loopDepth == 0) {
            throw error("Illegal continue statement")
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
        if (functionDepth == 0) {
            throw error("Illegal return statement")
        }
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
                    check(TokenType.LBRACKET) -> {
                        pattern = parseBindingPatternLiteral()
                        validateBindingPattern(pattern)
                    }

                    check(TokenType.LBRACE) -> {
                        pattern = parseBindingPatternLiteral()
                        validateBindingPattern(pattern)
                    }

                    else -> {
                        val token = consume(TokenType.IDENTIFIER, "Expected catch parameter")
                        validateIdentifierToken(token)
                        validateIdentifierName(token.value, strict)
                        if (strict && token.value in STRICT_RESTRICTED_NAMES) {
                            throw error("Unexpected eval or arguments in strict mode")
                        }
                        param = token.value
                    }
                }
                consume(TokenType.RPAREN, "Expected ')' after catch parameter")
            }
            // catch 参数与 catch 块顶层词法声明共享冲突检查
            val catchNames = mutableListOf<String>()
            if (param != null) catchNames.add(param)
            pattern?.let { collectPatternNamesWithDuplicates(it, catchNames) }
            val seenCatchNames = mutableSetOf<String>()
            for (name in catchNames) {
                validateStrictBindingName(name)
                if (!seenCatchNames.add(name)) {
                    throw error("Identifier '$name' has already been declared")
                }
            }
            pushDeclScope(
                DeclScopeKind.CATCH,
                parameterNames = seenCatchNames,
                catchHasPattern = pattern != null,
            )
            val body = try {
                parseBlockStatement(trackScope = false)
            } finally {
                popDeclScope()
            }
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
        val defaultsBefore = patternDefaultsSeen
        val left = withPatternSyntax { parseConditional() }
        val token = peek()
        if (token.type in assignmentOperators) {
            if (left is ArrayLiteral || left is ObjectLiteral) {
                // 解构目标只允许出现在简单赋值 = 左侧，且必须符合模式语法
                if (token.value != "=") {
                    throw error("Invalid assignment target")
                }
                validateAssignmentPattern(left)
                // 赋值模式不是 ObjectLiteral，允许重复 __proto__ 属性名
                clearDuplicateProtoRanges(left)
            } else if (left !is Identifier && left !is MemberExpr) {
                throw error("Invalid assignment target")
            }
            validateStrictAssignmentTarget(left)
            advance()
            val value = parseAssignment()
            // 目标中的简写默认值属于合法模式，不向后续表达式传播
            patternDefaultsSeen = defaultsBefore
            return AssignmentExpr(
                target = left,
                operator = token.value,
                value = value,
                range = SourceRange(left.range.start, value.range.end),
            )
        }
        if (patternSyntaxDepth == 0 && patternDefaultsSeen > defaultsBefore) {
            throw error("Invalid shorthand property initializer")
        }
        return left
    }

    /**
     * 尝试解析箭头函数；不是箭头函数时返回 null。
     */
    private fun parseArrowFunctionOrNull(): Expression? {
        // async 箭头函数：async x => / async (x) =>
        if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
            peek().value == "async" && !peek(1).precededByNewline
        ) {
            val next = peek(1)
            if (next.type == TokenType.IDENTIFIER && peek(2).type == TokenType.ARROW &&
                !peek(2).precededByNewline
            ) {
                val start = advance().range.start
                val paramToken = advance()
                withAsyncContext(true) { validateIdentifierToken(paramToken) }
                consume(TokenType.ARROW, "Expected '=>'")
                return parseArrowBody(
                    start,
                    listOf(Parameter(paramToken.value, null, null, false, paramToken.range)),
                    isAsync = true,
                )
            }
            if (next.type == TokenType.LPAREN && isArrowAfterParens(1)) {
                val start = advance().range.start
                val params = withAsyncContext(true) { parseParameters() }
                consume(TokenType.ARROW, "Expected '=>'")
                return parseArrowBody(start, params, isAsync = true)
            }
        }

        // 单参数箭头：x =>
        if (peek().type == TokenType.IDENTIFIER && peek(1).type == TokenType.ARROW &&
            !peek(1).precededByNewline
        ) {
            val token = advance()
            validateIdentifierToken(token)
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
            val bodyInfo = parseFunctionBody(
                isAsync = isAsync,
                allowSuperProperty = superPropertyAllowed,
                allowSuperCall = superCallAllowed,
                allowNewTarget = newTargetAllowed,
                params = params,
            )
            validateFunctionParameters(params, bodyInfo.strict, isArrow = true)
            return ArrowFunction(
                params = params,
                body = bodyInfo.body,
                expressionBody = null,
                strict = bodyInfo.strict,
                isAsync = isAsync,
                varDeclarations = bodyInfo.varDeclarations,
                functionDeclarations = bodyInfo.functionDeclarations,
                range = SourceRange(start, bodyInfo.body.range.end),
                allowsNewTarget = newTargetAllowed,
            )
        }

        val outerAsync = asyncContext
        asyncContext = isAsync
        // 表达式体沿用当前严格模式上下文
        validateFunctionParameters(params, strict, isArrow = true)
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
                allowsNewTarget = newTargetAllowed,
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

    /** `**` 幂运算（右结合）；一元表达式不能作为左操作数（早期错误）。 */
    private fun parseExponentiation(): Expression {
        val base = parseUnary()
        if (!check(TokenType.EXPONENT)) return base
        if (base is UnaryOp) {
            throw error("Unary expression cannot be used as the left operand of '**'")
        }
        advance()
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
                validateStrictAssignmentTarget(operand)
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
                if (token.type == TokenType.DELETE && strict && operand is Identifier) {
                    throw error("Delete of an unqualified identifier in strict mode")
                }
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
            validateStrictAssignmentTarget(expression)
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
                check(TokenType.QUESTION_DOT) -> {
                    if (expression is SuperExpr) {
                        throw error("'super' keyword unexpected here")
                    }
                    expression = parseOptionalChain(expression)
                }

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
                    // 可选链尾部不能直接接标签模板（括号包裹后合法）
                    if (expression is OptionalChainExpr && expression.range !in parenthesizedRanges) {
                        throw error("Invalid tagged template on optional chain")
                    }
                    val templateToken = advance()
                    val template = parseTemplateLiteral(templateToken) as TemplateLiteral
                    expression = TaggedTemplate(
                        tag = expression,
                        quasis = template.quasis,
                        rawQuasis = template.rawQuasis,
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
            if (property.value != "target" || property.containsEscape) {
                throw error("Expected 'target' after 'new.'")
            }
            if (!newTargetAllowed) {
                throw error("new.target expression is not allowed here")
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
        if (constructor is SuperExpr) {
            throw error("'super' keyword unexpected here")
        }

        if (match(TokenType.LPAREN)) {
            val arguments = parseArgumentsAfterOpen()
            return NewExpr(
                constructor,
                arguments,
                SourceRange(start, previous().range.end),
            )
        }
        // `new a?.b` 非法；`new a()?.b` 由外层可选链处理
        if (check(TokenType.QUESTION_DOT)) {
            throw error("Invalid optional chain from new expression")
        }
        return NewExpr(constructor, emptyList(), SourceRange(start, constructor.range.end))
    }

    /** 解析从 `?.` 开始的可选链，并收集同链上的普通成员/调用。 */
    private fun parseOptionalChain(base: Expression): Expression {
        val operations = mutableListOf<OptionalChainOperation>()
        while (match(TokenType.QUESTION_DOT)) {
            when {
                match(TokenType.LPAREN) -> {
                    operations.add(OptionalChainCall(parseArgumentsAfterOpen(), optional = true))
                }

                match(TokenType.LBRACKET) -> {
                    val property = parseExpression()
                    consume(TokenType.RBRACKET, "Expected ']' after member expression")
                    operations.add(
                        OptionalChainMember(
                            property = property,
                            computed = true,
                            optional = true,
                        )
                    )
                }

                else -> {
                    val propertyToken = parsePropertyName()
                    operations.add(
                        OptionalChainMember(
                            property = Identifier(propertyToken.value, propertyToken.range),
                            computed = false,
                            optional = true,
                        )
                    )
                }
            }
            // 同一链上的普通成员/调用继续并入；遇到下一个 ?. 交回外层循环
            while (true) {
                when {
                    match(TokenType.LPAREN) -> {
                        operations.add(OptionalChainCall(parseArgumentsAfterOpen(), optional = false))
                    }

                    match(TokenType.DOT) -> {
                        val propertyToken = parsePropertyName()
                        operations.add(
                            OptionalChainMember(
                                property = Identifier(propertyToken.value, propertyToken.range),
                                computed = false,
                                optional = false,
                            )
                        )
                    }

                    match(TokenType.LBRACKET) -> {
                        val property = parseExpression()
                        consume(TokenType.RBRACKET, "Expected ']' after member expression")
                        operations.add(
                            OptionalChainMember(
                                property = property,
                                computed = true,
                                optional = false,
                            )
                        )
                    }

                    else -> break
                }
            }
        }
        return OptionalChainExpr(
            base = base,
            operations = operations,
            range = SourceRange(base.range.start, previous().range.end),
        )
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
                // 严格模式禁止旧式八进制字面量（0 开头后跟数字）
                val raw = token.value
                if (strict && token.type == TokenType.NUMBER && raw.length > 1 &&
                    raw[0] == '0' && raw[1].isDigit()
                ) {
                    throw error("Octal literals are not allowed in strict mode")
                }
                advance()
                NumberLiteral(parseNumberLiteral(token), token.range)
            }

            TokenType.BIGINT -> {
                advance()
                BigIntLiteral(token.value, token.range)
            }

            TokenType.STRING -> {
                if (strict && token.legacyOctal) {
                    throw error("Octal escape sequences are not allowed in strict mode")
                }
                advance()
                StringLiteral(token.value, token.range, containsEscape = token.containsEscape)
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
                val next = peek().type
                if (!superPropertyAllowed) {
                    throw error("'super' keyword unexpected here")
                }
                if (next == TokenType.LPAREN && !superCallAllowed) {
                    throw error("'super' keyword unexpected here")
                }
                if (next != TokenType.DOT && next != TokenType.LBRACKET && next != TokenType.LPAREN) {
                    throw error("'super' keyword unexpected here")
                }
                SuperExpr(token.range)
            }

            TokenType.CLASS -> parseClassExpression()

            TokenType.FUNCTION -> {
                advance()
                parseFunctionExpression(token.range.start, isAsync = false)
            }

            TokenType.LET -> {
                if (!allowLetAsIdentifier()) {
                    throw error("Unexpected token 'let'")
                }
                advance()
                Identifier("let", token.range)
            }

            TokenType.IDENTIFIER -> {
                validateIdentifierToken(token)
                validateIdentifierName(token.value, strict)
                if (token.value == "async" && !token.containsEscape &&
                    peek(1).type == TokenType.FUNCTION && !peek(1).precededByNewline
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
                // 仅括号内的裸 super 非法；`(super())` 作为调用表达式是合法的
                if (expression is SuperExpr) {
                    throw error("'super' keyword unexpected here")
                }
                parenthesizedRanges.add(expression.range)
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
        val rawQuasis = mutableListOf<String>()
        val expressions = mutableListOf<Expression>()

        for (part in token.templateParts) {
            when (part) {
                is TemplatePart.Text -> {
                    quasis.add(part.value)
                    rawQuasis.add(part.raw)
                }
                is TemplatePart.ExpressionSource -> {
                    val subTokens = try {
                        Lexer(part.source).tokenize()
                    } catch (error: LexerException) {
                        throw ParseException(error.message ?: "Invalid template expression", error.range)
                    }
                    // 模板嵌入的是表达式，不能按 Program 解析（对象字面量会被当作块）
                    expressions.add(Parser(subTokens).parseExpressionInput())
                }
            }
        }

        // 保证 quasis 数量为 expressions + 1
        while (quasis.size < expressions.size + 1) quasis.add("")
        while (rawQuasis.size < quasis.size) rawQuasis.add(quasis[rawQuasis.size])
        return TemplateLiteral(quasis, rawQuasis, expressions, token.range)
    }

    /** 解析单个表达式输入（供模板字符串 ${...} 使用）。 */
    fun parseExpressionInput(): Expression {
        val expression = parseExpression()
        if (!isAtEnd()) {
            throw error("Unexpected token after expression")
        }
        checkDeferredProtoDuplicates()
        return expression
    }

    /** 数组字面量（null 元素表示空洞，SpreadElement 表示展开）。 */
    private fun parseArrayLiteral(): Expression {
        val start = consume(TokenType.LBRACKET, "Expected '['").range.start
        val elements = mutableListOf<Expression?>()
        var restFollowedByComma = false

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
                if (match(TokenType.COMMA)) {
                    restFollowedByComma = true
                    if (check(TokenType.RBRACKET)) break
                    continue
                }
                break
            }
            elements.add(parseAssignment())
            if (match(TokenType.COMMA)) {
                if (check(TokenType.RBRACKET)) break
            } else {
                break
            }
        }

        val end = consume(TokenType.RBRACKET, "Expected ']' after array literal").range.end
        return ArrayLiteral(elements, SourceRange(start, end), restFollowedByComma)
    }

    /** 对象字面量。 */
    private fun parseObjectLiteral(): Expression {
        val start = consume(TokenType.LBRACE, "Expected '{'").range.start
        val properties = mutableListOf<ObjectLiteralElement>()
        // `__proto__: value` 形式的数据属性最多出现一次（B.3.1）；
        // 若该字面量最终作为赋值/绑定模式使用则不报错，因此延迟到解析结束。
        var protoDataPropertyCount = 0
        var hasDuplicateProto = false
        var restFollowedByComma = false

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (check(TokenType.ELLIPSIS)) {
                val spreadStart = advance().range.start
                val argument = parseAssignment()
                properties.add(ObjectSpreadProperty(argument, SourceRange(spreadStart, argument.range.end)))
                if (!match(TokenType.COMMA)) break
                restFollowedByComma = true
                if (check(TokenType.RBRACE)) break
                continue
            }
            val propertyStart = peek().range.start

            // async 方法检测：`async m() {}`
            var isAsync = false
            if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                peek().value == "async" && canStartAccessorProperty(1)
            ) {
                isAsync = true
                advance()
            }

            // getter / setter 检测：`get x() {}`、`set x(v) {}`
            var kind = PropertyKind.INIT
            if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                (peek().value == "get" || peek().value == "set") &&
                canStartAccessorProperty(1)
            ) {
                kind = if (peek().value == "get") PropertyKind.GET else PropertyKind.SET
                advance()
            }

            val (key, computed) = parsePropertyKeyExpression()

            var isMethodSyntax = false
            val value: Expression = when {
                match(TokenType.COLON) -> {
                    // 普通对象字面量中位于原型链语义的 "__proto__" 数据属性不可重复
                    val literalKeyName = when (key) {
                        is Identifier -> key.name
                        is StringLiteral -> key.value
                        else -> null
                    }
                    if (!computed && literalKeyName == "__proto__") {
                        protoDataPropertyCount++
                        if (protoDataPropertyCount > 1) hasDuplicateProto = true
                    }
                    parseAssignment()
                }

                match(TokenType.LPAREN) -> {
                    isMethodSyntax = true
                    val (params, bodyInfo) = withFunctionContext(true, false, true) {
                        val parsedParams = withAsyncContext(isAsync) { parseParametersAfterOpen() }
                        when (kind) {
                            PropertyKind.GET -> validateAccessorParameters(parsedParams, getter = true)
                            PropertyKind.SET -> validateAccessorParameters(parsedParams, getter = false)
                            else -> Unit
                        }
                        val parsedBody = parseFunctionBody(
                            isAsync = isAsync,
                            allowSuperProperty = true,
                            allowSuperCall = false,
                            allowNewTarget = true,
                            params = parsedParams,
                        )
                        parsedParams to parsedBody
                    }
                    // 对象方法使用 UniqueFormalParameters（即使 sloppy 也不允许重名）
                    validateFunctionParameters(params, bodyInfo.strict, isMethod = true)
                    // 计算键的方法名由运行时 SetFunctionName 推断
                    val name = if (!computed && key is Identifier) key.name else ""
                    FunctionExpression(
                        name = name,
                        params = params,
                        body = bodyInfo.body,
                        strict = bodyInfo.strict,
                        isAsync = isAsync,
                        varDeclarations = bodyInfo.varDeclarations,
                        functionDeclarations = bodyInfo.functionDeclarations,
                        range = SourceRange(key.range.start, bodyInfo.body.range.end),
                        isMethodDefinition = true,
                    )
                }

                kind == PropertyKind.INIT && key is Identifier && !computed -> {
                    // 简写属性 { a } / 解构默认值 { a = 1 } 是对标识符的引用，
                    // 因此转义关键字（如 { bre\u0061k }）在此非法
                    lastPropertyKeyToken?.let { validateIdentifierToken(it) }
                    validateIdentifierName(key.name, strict)
                    val identifier = Identifier(key.name, key.range)
                    if (match(TokenType.ASSIGN)) {
                        if (patternSyntaxDepth == 0) {
                            throw error("Invalid shorthand property initializer")
                        }
                        patternDefaultsSeen++
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
                    method = isMethodSyntax,
                )
            )
            if (!match(TokenType.COMMA)) break
            if (check(TokenType.RBRACE)) break
        }

        val end = consume(TokenType.RBRACE, "Expected '}' after object literal").range.end
        val literalRange = SourceRange(start, end)
        if (hasDuplicateProto) duplicateProtoLiteralRanges.add(literalRange)
        return ObjectLiteral(properties, literalRange, restFollowedByComma)
    }

    /** 判断 `get`/`set` 之后是否跟着一个可访问器属性名。 */
    private fun canStartAccessorProperty(offset: Int): Boolean {
        val token = peek(offset)
        return when {
            token.type in setOf(
                TokenType.IDENTIFIER, TokenType.STRING,
                TokenType.NUMBER, TokenType.NUMBER_HEX,
                TokenType.NUMBER_OCT, TokenType.NUMBER_BIN, TokenType.BIGINT,
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
            lastPropertyKeyToken = null
            return expression to true
        }

        val token = peek()
        lastPropertyKeyToken = token
        return when {
            token.type == TokenType.IDENTIFIER ||
                (token.type == TokenType.LET && allowLetAsIdentifier()) -> {
                advance()
                Identifier(token.value, token.range) to false
            }

            token.type == TokenType.STRING -> {
                if (strict && token.legacyOctal) {
                    throw error("Octal escape sequences are not allowed in strict mode")
                }
                advance()
                StringLiteral(token.value, token.range) to false
            }

            token.type == TokenType.BIGINT -> {
                advance()
                BigIntLiteral(token.value, token.range) to false
            }

            token.type in setOf(
                TokenType.NUMBER, TokenType.NUMBER_HEX,
                TokenType.NUMBER_OCT, TokenType.NUMBER_BIN,
            ) -> {
                if (strict && token.type == TokenType.NUMBER && token.value.length > 1 &&
                    token.value[0] == '0' && token.value[1].isDigit()
                ) {
                    throw error("Octal literals are not allowed in strict mode")
                }
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
        if (blockStart >= tokens.size || tokens[blockStart].type != TokenType.LBRACE) return false
        return detectUseStrictDirectiveAt(blockStart + 1)
    }

    /** 从语句列表起点预扫描 "use strict" 指令（用于脚本与函数体）。 */
    private fun detectUseStrictDirectiveAt(statementStart: Int): Boolean {
        var index = statementStart
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.type != TokenType.STRING) return false
            // 转义形式（如 'use\x20strict'）不构成严格模式指令
            if (!token.containsEscape && token.value == "use strict") return true
            index++
            if (index < tokens.size && tokens[index].type == TokenType.SEMICOLON) index++
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
