package com.muedsa.js.parser

import com.muedsa.js.ast.*
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.lexer.SourcePosition
import com.muedsa.js.lexer.SourceRange
import com.muedsa.js.lexer.TemplatePart
import com.muedsa.js.lexer.Token
import com.muedsa.js.lexer.TokenType
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.exception.ResourceLimitKind

/**
 * JavaScript 语法解析器（递归下降）。
 *
 * 设计要点：
 * - 覆盖既有语法 + 空语句；
 * - AST 节点不可变且携带源码范围；
 * - 在解析阶段收集变量提升信息（var/function），避免运行时反复扫描 AST；
 * - 记录脚本/函数的严格模式标志（由 "use strict" 指令决定）。
 *
 * 已支持箭头函数、模板字符串、解构、class 等语法；扩展时保持错误位置与提升信息不变。
 */
class Parser(
    private val tokens: List<Token>,
    strictContext: Boolean = false,
    superPropertyContext: Boolean = false,
    superCallContext: Boolean = false,
    newTargetContext: Boolean = false,
    /** 直接 eval 继承 class 字段初始化器/静态块的 arguments 限制 */
    argumentsForbiddenContext: Boolean = false,
    /** 直接 eval 继承调用方词法可见的私有名（null 表示普通脚本解析） */
    evalPrivateNames: Set<String>? = null,
    /** 原始源码（Function.prototype.toString 的源码文本切片使用；null 表示不可用） */
    private val source: String? = null,
    /** 子表达式（模板字符串 ${...}）继承的生成器语境：其中 yield 仍是关键字。 */
    yieldContext: Boolean = false,
    /** 语法嵌套深度上限；null 表示不检查（Lexer 会在括号/模板层先行检查）。 */
    private val maxNestingDepth: Int? = null,
    /** 从外层模板表达式继承的起始递归深度，避免跨模板层级时深度清零。 */
    initialNestingDepth: Int = 0,
    /** AST 节点总数上限；null 表示不限制（由 EngineLimits.maxAstNodes 传入）。 */
    private val maxAstNodes: Int? = null,
    /**
     * 外层解析器已经创建的 AST 节点数。
     *
     * 模板字符串的 `${...}` 子表达式由独立 Parser 解析；子解析器用该值把新节点
     * 接续到外层总数上，保证整个脚本共享同一个 maxAstNodes 预算。顶层解析为 0。
     */
    private val initialAstNodeCount: Int = 0,
    /**
     * 是否按 Module 目标解析。
     *
     * [parseModule] 会在开始解析前置位；模板字符串的 `${...}` 子解析器通过该参数
     * 继承模块语境（`import.meta` / `import()` / 模块顶层 await 在模板表达式中同样合法）。
     * 脚本目标保持 false，所有模块专属规则都不会生效。
     */
    private var moduleContext: Boolean = false,
) {

    /**
     * 当前解析器实例已经创建的 AST 节点数（不含 [initialAstNodeCount]）。
     *
     * 只在启用 maxAstNodes 时累加；模板子解析器结束后由父解析器把差值并入自身计数。
     */
    private var astNodeCount = 0

    /** 当前解析器累计的 AST 节点总数（含从外层继承的起点）。 */
    internal fun astNodeTotal(): Int = initialAstNodeCount + astNodeCount

    /**
     * 登记并返回一个新创建的 AST 节点。
     *
     * 构造完成后调用；超限时立即抛出，阻止继续创建后续节点，已分配的子树由
     * 异常传播后丢弃。异常携带当前源码位置，便于宿主定位触发预算的语法位置。
     * 预算是宿主控制异常，不能被 JS catch 捕获。
     */
    private fun <T : ASTNode> trackAstNode(node: T): T {
        val limit = maxAstNodes ?: return node
        astNodeCount++
        val total = initialAstNodeCount + astNodeCount
        if (total > limit) {
            val position = if (isAtEnd()) {
                "line ${tokens.lastOrNull()?.range?.start?.line ?: 1}, " +
                    "column ${tokens.lastOrNull()?.range?.start?.column ?: 1}"
            } else {
                val start = peek().range.start
                "line ${start.line}, column ${start.column}"
            }
            throw ResourceLimitException(
                kind = ResourceLimitKind.AST_NODES,
                limitValue = limit.toLong(),
                actualValue = total.toLong(),
                detail = position,
            )
        }
        return node
    }

    /**
     * 当前表达式递归深度。
     *
     * 顶层表达式从 0 开始，每个嵌套表达式层加 1；模板子解析器继承外层深度，
     * 使跨模板层级的表达式嵌套仍能累计到同一上限。
     */
    private var syntaxNestingDepth = initialNestingDepth - 1

    /** 当前语句列表是否允许 using 声明（脚本体、eval 顶层与 case 子句不允许）。 */
    private var usingDeclarationAllowed = false

    /** 当前解析位置（tokens 下标）。 */
    private var pos = 0

    /** 按源码范围切片；源码不可用或范围越界时返回 null。 */
    private fun sourceSlice(range: SourceRange): String? {
        val text = source ?: return null
        val start = range.start.offset
        val end = range.end.offset
        if (start < 0 || end > text.length || end < start) return null
        return text.substring(start, end)
    }

    /** 解析起点的严格模式（直接 eval 可继承调用方严格模式）。 */
    private val initialStrict = strictContext

    /** 当前所在的严格模式上下文；解析函数时会临时切换。 */
    private var strict = strictContext

    /** 当前是否处于 async 函数体（用于解析 await）。 */
    private var asyncContext = false

    /**
     * 模板字符串子解析器继承父解析器的 await/函数/class 字段语境。
     *
     * 这些是 Parser 内部状态，不扩展 public 构造函数；模板子解析器创建后立即调用。
     */
    internal fun inheritTemplateState(
        asyncContext: Boolean,
        functionDepth: Int,
        classFieldInitializerDepth: Int,
        inFormalParameters: Boolean,
        awaitIdentifierForbidden: Boolean,
    ) {
        this.asyncContext = asyncContext
        this.functionDepth = functionDepth
        this.classFieldInitializerDepth = classFieldInitializerDepth
        this.inFormalParameters = inFormalParameters
        this.awaitIdentifierForbidden = awaitIdentifierForbidden
    }

    /** 当前模块是否出现顶层 await（用于 MVP 链接期拒绝 TLA）。 */
    private var moduleHasTopLevelAwait = false

    /**
     * 当前是否处于同步生成器函数的参数/函数体（用于解析 yield）。
     *
     * 与 [asyncContext] 一样属于上下文相关关键字：非生成器语境中 yield 仍是标识符。
     * 初始值来自构造参数，使模板字符串的 ${...} 子表达式继承外层生成器语境。
     */
    private var generatorContext = yieldContext

    /**
     * 当前是否正在解析形参列表（含默认值、解构模式与计算属性名）。
     *
     * 生成器形参是 [+Yield] 语境，但 YieldExpression 不允许出现在形参中；
     * async 形参同理不允许 AwaitExpression（规范 Contains 静态语义）。
     * 箭头函数的形参继承外层 [Yield]/[Await] 语境，因此不解除该标志；
     * 普通函数/方法的函数体是新的 [~Yield, ~Await] 边界，解析函数体时会清除。
     */
    private var inFormalParameters = false

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

    /**
     * class 静态初始化块内禁止引用 arguments（箭头函数不作为解除限制的函数边界）。
     * 该限制跨普通函数/方法边界时解除。
     */
    private var argumentsForbidden = argumentsForbiddenContext

    /**
     * class 静态初始化块内禁止把 await 用作标识符。
     * 函数/方法/箭头的函数体恢复 [~Await] 语境；箭头参数仍沿用外层限制。
     */
    private var awaitIdentifierForbidden = false

    /**
     * 当前嵌套的 class 字段初始化表达式层数。
     *
     * 字段初始化表达式是 [~Await] 语境：模块里 `await` 虽可作顶层 await 表达式，
     * 但 `class C { x = await 1 }` 仍是语法错误。进入普通函数体（含箭头函数体）
     * 会重置该层数，因为函数体有自己独立的 [Await] 语境。
     */
    private var classFieldInitializerDepth = 0

    /**
     * 正在解析的 class 静态初始化块所在的 functionDepth。
     * 块内 return 非法，但块内嵌套函数/方法中的 return 合法。
     */
    private val staticBlockDepths = mutableListOf<Int>()

    /** 私有名作用域栈：每个 class 体一层，用于重名与未声明引用的早期错误。 */
    private val privateNameScopes = mutableListOf<PrivateNameScope>()

    /** 直接 eval 代码可引用的外层私有名（来自调用方私有环境）。 */
    private val externalPrivateNames = evalPrivateNames

    /** 私有元素类别（用于允许 getter/setter 成对同名）。 */
    private enum class PrivateElementKind { FIELD, METHOD, GETTER, SETTER }

    /** class 体的私有名作用域：声明与延迟解析的引用。 */
    private class PrivateNameScope(
        val parent: PrivateNameScope?,
        val declarations: MutableMap<String, MutableSet<PrivateElementKind>> = mutableMapOf(),
        /** 每种元素声明的静态性：getter/setter 成对时要求一致 */
        val staticness: MutableMap<String, MutableMap<PrivateElementKind, Boolean>> = mutableMapOf(),
        val references: MutableList<Pair<String, SourceRange>> = mutableListOf(),
    )

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
        validateStaticBlockBindingName(token.value)
        return token
    }

    /** class 静态初始化块内的绑定名限制（参考 ContainsArguments/Await 静态语义）。 */
    private fun validateStaticBlockBindingName(name: String) {
        if (argumentsForbidden && name == "arguments") {
            throw error("Unexpected 'arguments' in class static block")
        }
        if (awaitIdentifierForbidden && name == "await") {
            throw error("Unexpected 'await' in class static block")
        }
    }

    /** class 静态初始化块内的标识符引用限制。 */
    private fun validateStaticBlockReferenceName(name: String) {
        if (argumentsForbidden && name == "arguments") {
            throw error("Unexpected 'arguments' in class static block")
        }
        if (awaitIdentifierForbidden && name == "await") {
            throw error("Unexpected 'await' in class static block")
        }
    }

    /** 声明私有名：禁止 #constructor，重名仅允许同静态性的 getter/setter 成对。 */
    private fun declarePrivateName(
        name: String,
        kind: PrivateElementKind,
        isStatic: Boolean,
        range: SourceRange,
    ) {
        if (name == "constructor") {
            throw ParseException("Classes may not have a private field named '#constructor'", range)
        }
        val scope = privateNameScopes.lastOrNull()
            ?: throw ParseException("Private names are only allowed in class bodies", range)
        val kinds = scope.declarations.getOrPut(name) { mutableSetOf() }
        val kindsStaticness = scope.staticness.getOrPut(name) { mutableMapOf() }
        val allowed = when {
            kinds.isEmpty() -> true
            kind == PrivateElementKind.GETTER && kinds == setOf(PrivateElementKind.SETTER) ->
                kindsStaticness[PrivateElementKind.SETTER] == isStatic

            kind == PrivateElementKind.SETTER && kinds == setOf(PrivateElementKind.GETTER) ->
                kindsStaticness[PrivateElementKind.GETTER] == isStatic

            else -> false
        }
        if (!allowed) {
            throw ParseException("Identifier '#$name' has already been declared", range)
        }
        kinds.add(kind)
        kindsStaticness[kind] = isStatic
    }

    /** 记录私有名引用；未声明检查在 class 体解析结束后统一完成。 */
    private fun recordPrivateNameReference(name: String, range: SourceRange) {
        val scope = privateNameScopes.lastOrNull()
        if (scope == null) {
            // 直接 eval：引用调用方词法作用域内的私有名
            val external = externalPrivateNames
            if (external != null && name in external) return
            throw ParseException(
                "Private field '#$name' must be declared in an enclosing class",
                range,
            )
        }
        scope.references.add(name to range)
    }

    /** 校验并合并子作用域引用；未解析的引用交给外层 class，最外层仍无声明则报错。 */
    private fun closePrivateNameScope(scope: PrivateNameScope) {
        for ((name, range) in scope.references) {
            if (name in scope.declarations) continue
            val parent = scope.parent
            if (parent != null) {
                parent.references.add(name to range)
            } else if (externalPrivateNames?.contains(name) != true) {
                throw ParseException(
                    "Private field '#$name' must be declared in an enclosing class",
                    range,
                )
            }
        }
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
     * 进入一层表达式递归并检查上限；超限抛宿主控制异常而不是语法错误。
     *
     * Lexer 已负责括号、块、数组/对象字面量与模板表达式的深度检查；
     * 这里补足无括号的递归语法（如连续箭头函数、连续一元运算、条件表达式链），
     * 保证在宿主栈溢出前给出可预期的资源限制异常。
     */
    private inline fun <T> withSyntaxNesting(block: () -> T): T {
        val limit = maxNestingDepth ?: return block()
        val next = syntaxNestingDepth + 1
        if (next > limit) {
            val token = peek()
            throw ResourceLimitException(
                kind = ResourceLimitKind.NESTING_DEPTH,
                limitValue = limit.toLong(),
                actualValue = next.toLong(),
                detail = "line ${token.range.start.line}, column ${token.range.start.column}",
            )
        }
        syntaxNestingDepth = next
        try {
            return block()
        } finally {
            syntaxNestingDepth = next - 1
        }
    }

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
        return trackAstNode(Program(
            body = body,
            strict = strict,
            varDeclarations = hoist.varDeclarations,
            functionDeclarations = hoist.functionDeclarations,
            range = SourceRange(start, end),
            hasUsingDeclaration = body.any { it is UsingDeclaration },
        ))
    }

    // ================================================================
    // 语句解析
    // ================================================================

    /**
     * 语句分发。
     */
    private fun parseStatement(): Statement {
        if (usingDeclarationAllowed) {
            if (isAwaitUsingDeclarationStart()) {
                return parseUsingDeclaration(isAwait = true, requireTerminator = true)
            }
            if (isUsingDeclarationStart()) {
                return parseUsingDeclaration(isAwait = false, requireTerminator = true)
            }
        }
        var annexBFunction = false
        var singleStatementAtStart = false
        var letAsExpression = false
        if (singleStatementContext) {
            singleStatementAtStart = true
            val tokenType = peek().type
            // 单语句上下文禁止 let 词法声明；但 sloppy 下 let 可作为标识符表达式，
            // 仅 `let [` 受 ExpressionStatement 前瞻限制（即使有换行也不能退化为表达式）。
            if (tokenType == TokenType.LET && isLetDeclarationStart()) {
                if (!allowLetAsIdentifier() || peek(1).type == TokenType.LBRACKET) {
                    throw error("Lexical declaration cannot appear in a single-statement context")
                }
                letAsExpression = true
            }
            if (tokenType == TokenType.CONST || tokenType == TokenType.CLASS) {
                throw error("Lexical declaration cannot appear in a single-statement context")
            }
            if (isAwaitUsingDeclarationStart() || isUsingDeclarationStart()) {
                throw error("Lexical declaration cannot appear in a single-statement context")
            }
            if (tokenType == TokenType.FUNCTION && (!allowFunctionInSingleStatement || strict)) {
                throw error("Function declaration cannot appear in a single-statement context")
            }
            // 生成器声明与 async 函数一样，不存在 Annex B 单语句例外
            if (tokenType == TokenType.FUNCTION && peek(1).type == TokenType.MULTIPLY) {
                throw error("Generator declaration cannot appear in a single-statement context")
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
                trackAstNode(EmptyStatement(token.range))
            }

            TokenType.VAR, TokenType.LET, TokenType.CONST ->
                if (peek().type == TokenType.LET && allowLetAsIdentifier() &&
                    (!isLetDeclarationStart() || letAsExpression)
                ) {
                    val expression = parseExpression()
                    val start = expression.range.start
                    consumeSemicolon()
                    trackAstNode(ExpressionStatement(expression, SourceRange(start, previous().range.end)))
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
                trackAstNode(DebuggerStatement(token.range))
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
                trackAstNode(ExpressionStatement(expression, SourceRange(start, previous().range.end)))
            }
        }
    }

    /** 标签语句。 */
    private fun parseLabeledStatement(singleContextAtStart: Boolean): LabeledStatement {
        val labelToken = consume(TokenType.IDENTIFIER, "Expected label")
        validateIdentifierToken(labelToken)
        validateIdentifierName(labelToken.value, strict)
        // 静态初始化块处于 [+Await] 语境，await 会被当作 await 表达式而非标签
        if (awaitIdentifierForbidden && labelToken.value == "await") {
            throw error("Unexpected 'await' in class static block")
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
        return trackAstNode(LabeledStatement(labelToken.value, body, SourceRange(labelToken.range.start, body.range.end)))
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

    /** 是否允许 `in` 作为关系运算符（for-in 旧式初始化器按 [~In] 解析）。 */
    private var inOperatorAllowed = true

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
        /** Annex B：包围函数的全部形参名（含默认/解构），用于 skip-param 规则 */
        val annexParameterNames: Set<String> = emptySet(),
        /** Annex B：包围函数是否创建 arguments 绑定（普通函数，非箭头/静态块） */
        val hasArgumentsBinding: Boolean = false,
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
        annexParameterNames: Set<String> = emptySet(),
        hasArgumentsBinding: Boolean = false,
    ) {
        declScopes.add(
            DeclScope(
                kind,
                parameterNames,
                catchHasPattern,
                annexParameterNames,
                hasArgumentsBinding,
            )
        )
    }

    private fun popDeclScope() {
        if (declScopes.isNotEmpty()) declScopes.removeAt(declScopes.lastIndex)
    }

    /** 严格模式绑定名校验（eval/arguments 不能作为绑定名）。 */
    private fun validateStrictBindingName(name: String) {
        validateStaticBlockBindingName(name)
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

    /**
     * Annex B 扩展资格：模拟把块级函数声明替换为 `var F` 是否会产生早期错误。
     *
     * 从当前作用域向外直到最近的函数体/程序作用域，检查同名词法绑定；
     * 解构 catch 参数视为词法绑定，简单 catch 参数按 Annex B 允许同名。
     */
    private fun isAnnexBVarEligible(name: String, allowSameBlockFunction: Boolean): Boolean {
        val ownScopeIndex = declScopes.lastIndex
        for (index in declScopes.indices.reversed()) {
            val scope = declScopes[index]
            if (name in scope.lexicalNames) {
                // 同一块内的同名块级函数不构成冲突；外层块的同名函数仍构成冲突
                val sameBlockFunction = allowSameBlockFunction && index == ownScopeIndex &&
                    name in scope.functionNames
                if (!sameBlockFunction) return false
            }
            if (scope.kind == DeclScopeKind.CATCH && scope.catchHasPattern &&
                name in scope.parameterNames
            ) {
                return false
            }
            if (scope.kind == DeclScopeKind.PROGRAM || scope.kind == DeclScopeKind.FUNCTION_BODY) {
                // FunctionDeclarationInstantiation 的 skip 规则：
                // 同名形参或 arguments 绑定存在时不建立 var 扩展
                if (scope.kind == DeclScopeKind.FUNCTION_BODY) {
                    if (name in scope.annexParameterNames) return false
                    if (name == "arguments" && scope.hasArgumentsBinding) return false
                }
                break
            }
        }
        return true
    }

    /** 登记 function 声明：块级按词法声明，其他位置按 var 声明。 */
    private fun declareFunctionBinding(
        name: String,
        isAsync: Boolean = false,
        isGenerator: Boolean = false,
    ) {
        val scope = declScopes.lastOrNull()
        if (scope != null && scope.treatsFunctionsAsLexical) {
            // 生成器与 async 函数一样，不受 sloppy 模式同名重复的 Annex B 放宽
            declareLexicalBinding(
                name,
                isFunctionDeclaration = true,
                isAsyncFunction = isAsync || isGenerator,
            )
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
        val previousUsingAllowed = usingDeclarationAllowed
        usingDeclarationAllowed = false
        return try {
            parseStatement()
        } finally {
            usingDeclarationAllowed = previousUsingAllowed
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
                            val target = property.argument
                            // AssignmentRestProperty 只接受 DestructuringAssignmentTarget，
                            // 不能带默认值，也不能是数组/对象模式
                            if (target is AssignmentExpr) {
                                throw error("Rest property may not have a default initializer")
                            }
                            if (target !is Identifier && target !is MemberExpr) {
                                throw error("Invalid rest property target")
                            }
                            validateAssignmentPattern(target)
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
                            // BindingRestProperty 只允许 BindingIdentifier
                            if (property.argument !is Identifier) {
                                throw error("Object rest binding must be an identifier")
                            }
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
        return trackAstNode(DoWhileStatement(body, condition, SourceRange(start, end)))
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
        return trackAstNode(WithStatement(objectExpression, body, SourceRange(start, body.range.end)))
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
            return trackAstNode(ClassDeclaration(
                name = name.value,
                superClass = superClass,
                members = members,
                range = SourceRange(start, previous().range.end),
                sourceText = sourceSlice(SourceRange(start, previous().range.end)),
            ))
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
                validateStaticBlockBindingName(it.value)
                if (it.value in STRICT_RESTRICTED_NAMES) {
                    throw error("Unexpected eval or arguments in strict mode")
                }
            }
            val superClass = if (match(TokenType.EXTENDS)) parseLeftHandSide() else null
            val members = parseClassBody(derived = superClass != null)
            return trackAstNode(ClassExpression(
                name = nameToken?.value,
                superClass = superClass,
                members = members,
                range = SourceRange(start, previous().range.end),
                sourceText = sourceSlice(SourceRange(start, previous().range.end)),
            ))
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
        // class 体是私有名的语法作用域（允许引用外层 class 的私有名）
        val privateScope = PrivateNameScope(parent = privateNameScopes.lastOrNull())
        privateNameScopes.add(privateScope)
        try {
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                if (match(TokenType.SEMICOLON)) continue
                val memberStart = peek().range.start

                // `static { ... }` 静态初始化块：与 static 方法修饰符区分
                if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                    peek().value == "static" && peek(1).type == TokenType.LBRACE
                ) {
                    advance() // static
                    val bodyInfo = parseFunctionBody(
                        allowSuperProperty = true,
                        allowSuperCall = false,
                        allowNewTarget = true,
                        staticBlock = true,
                    )
                    val function = trackAstNode(FunctionExpression(
                        name = "",
                        params = emptyList(),
                        body = bodyInfo.body,
                        strict = true,
                        isAsync = false,
                        varDeclarations = bodyInfo.varDeclarations,
                        functionDeclarations = bodyInfo.functionDeclarations,
                        range = SourceRange(memberStart, bodyInfo.body.range.end),
                        isMethodDefinition = true,
                    ))
                    members.add(
                        trackAstNode(ClassMember(
                            kind = ClassMemberKind.STATIC_BLOCK,
                            isStatic = true,
                            key = trackAstNode(Identifier("static", SourceRange(memberStart, memberStart))),
                            computed = false,
                            value = function,
                            range = SourceRange(memberStart, bodyInfo.body.range.end),
                        ))
                    )
                    continue
                }

                var isStatic = false
                if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                    peek().value == "static" && canStartClassMember(1)
                ) {
                    isStatic = true
                    advance()
                }

                var kind = ClassMemberKind.METHOD
                var isAsync = false
                var modifierStart: SourcePosition? = null
                if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                    canStartClassMember(1)
                ) {
                    // `get`/`set` 后跟 `*` 时不是访问器：访问器与生成器不能组合。
                    // 按规范这里应把 get/set 解析成字段名，再由 ASI 结束字段
                    // （见 grammar-field-named-get-followed-by-generator-asi 用例）。
                    val generatorMarkerNext = peek(1).type == TokenType.MULTIPLY
                    when (peek().value) {
                        "get" -> if (!generatorMarkerNext) {
                            kind = ClassMemberKind.GET
                            modifierStart = peek().range.start
                            advance()
                        }

                        "set" -> if (!generatorMarkerNext) {
                            kind = ClassMemberKind.SET
                            modifierStart = peek().range.start
                            advance()
                        }

                        "async" -> {
                            isAsync = true
                            modifierStart = peek().range.start
                            advance()
                        }
                    }
                }
                // Function.prototype.toString 的源码文本：static 修饰符不计入
                // （`*` 属于方法自身语法，因此 `*m(){}` 的文本从 `*` 开始）
                val memberTextStart = modifierStart ?: peek().range.start

                // 生成器方法检测：`*m() {}`、`static *m() {}`、`async *m() {}`
                var isGenerator = false
                if (check(TokenType.MULTIPLY)) {
                    advance()
                    isGenerator = true
                    if (kind != ClassMemberKind.METHOD) {
                        throw error("A class method cannot be both an accessor and a generator")
                    }
                }

                val (key, computed) = if (check(TokenType.PRIVATE_IDENTIFIER)) {
                    val token = advance()
                    trackAstNode(PrivateIdentifier(token.value, token.range)) to false
                } else {
                    parsePropertyKeyExpression()
                }
                // 生成器方法必须紧跟参数列表：`*m` 后面不能是字段初始化或 `:`
                if (isGenerator && !check(TokenType.LPAREN)) {
                    throw error("Expected '(' after generator method name")
                }
                val privateKey = key as? PrivateIdentifier
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
                        isGenerator -> throw error("Class constructor may not be a generator")
                        else -> {
                            if (constructorSeen) {
                                throw error("A class may only have one constructor")
                            }
                            constructorSeen = true
                            isConstructor = true
                        }
                    }
                }

                // class 字段：没有参数列表（可选初始化表达式，ASI 结束）
                if (!check(TokenType.LPAREN)) {
                    if (kind != ClassMemberKind.METHOD || isAsync || isGenerator) {
                        throw error("Expected '(' in class member")
                    }
                    if (isConstructor) {
                        throw error("Classes may not have a field named 'constructor'")
                    }
                    // 静态字段同样不能命名为 constructor（静态方法允许）
                    if (isStatic && !computed && keyName == "constructor") {
                        throw error("Classes may not have a field named 'constructor'")
                    }
                    privateKey?.let {
                        declarePrivateName(it.name, PrivateElementKind.FIELD, isStatic, it.range)
                    }
                    var initializer: Expression? = null
                    if (match(TokenType.ASSIGN)) {
                        initializer = withFieldInitializerContext { parseAssignment() }
                    }
                    consumeSemicolon()
                    val function = trackAstNode(FunctionExpression(
                        name = if (!computed && key is Identifier) key.name else "",
                        params = emptyList(),
                        // 无初始化表达式时使用空块体（求值为 undefined）
                        body = trackAstNode(BlockStatement(emptyList(), SourceRange.NONE)),
                        strict = true,
                        isAsync = false,
                        varDeclarations = emptyList(),
                        functionDeclarations = emptyList(),
                        range = SourceRange(key.range.start, previous().range.end),
                        isMethodDefinition = true,
                        fieldInitializer = initializer,
                    ))
                    members.add(
                        trackAstNode(ClassMember(
                            kind = ClassMemberKind.FIELD,
                            isStatic = isStatic,
                            key = key,
                            computed = computed,
                            value = function,
                            range = SourceRange(memberStart, previous().range.end),
                        ))
                    )
                    continue
                }

                privateKey?.let {
                    val elementKind = when (kind) {
                        ClassMemberKind.GET -> PrivateElementKind.GETTER
                        ClassMemberKind.SET -> PrivateElementKind.SETTER
                        else -> PrivateElementKind.METHOD
                    }
                    declarePrivateName(it.name, elementKind, isStatic, it.range)
                }
                consume(TokenType.LPAREN, "Expected '(' in class member")
                val allowSuperCall = isConstructor && classDerived
                val (params, bodyInfo) = withFunctionContext(true, allowSuperCall, true) {
                    // 形参沿用函数自身的 [Yield] 语境：生成器方法是 [+Yield]，
                    // 普通方法/访问器是 [~Yield]（yield 仍可作为绑定名）
                    val parsedParams = withGeneratorContext(isGenerator) {
                        withAsyncContext(isAsync) { parseParametersAfterOpen() }
                    }
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
                        isGenerator = isGenerator,
                    )
                    parsedParams to parsedBody
                }
                // 类成员总是严格模式，且使用 UniqueFormalParameters
                validateFunctionParameters(params, strict = true, isMethod = true)
                val function = trackAstNode(FunctionExpression(
                    name = if (!computed && key is Identifier) key.name else "",
                    params = params,
                    body = bodyInfo.body,
                    strict = true,
                    isAsync = isAsync,
                    isGenerator = isGenerator,
                    varDeclarations = bodyInfo.varDeclarations,
                    functionDeclarations = bodyInfo.functionDeclarations,
                    range = SourceRange(key.range.start, bodyInfo.body.range.end),
                    isMethodDefinition = true,
                    sourceText = sourceSlice(
                        SourceRange(memberTextStart, bodyInfo.body.range.end)
                    ),
                ))
                members.add(
                    trackAstNode(ClassMember(
                        kind = if (isConstructor) ClassMemberKind.CONSTRUCTOR else kind,
                        isStatic = isStatic,
                        key = key,
                        computed = computed,
                        value = function,
                        range = SourceRange(memberStart, bodyInfo.body.range.end),
                    ))
                )
            }
            consume(TokenType.RBRACE, "Expected '}' after class body")
        } finally {
            privateNameScopes.removeAt(privateNameScopes.lastIndex)
            strict = outerStrict
            classDerived = outerDerived
        }
        closePrivateNameScope(privateScope)
        return members
    }

    /** 判断 class 成员修饰符之后是否跟着成员名。 */
    private fun canStartClassMember(offset: Int): Boolean {
        val token = peek(offset)
        return when {
            // `*` 是生成器方法标记：`static *m() {}`、`async *m() {}`
            token.type == TokenType.MULTIPLY -> true
            token.type in setOf(
                TokenType.IDENTIFIER, TokenType.PRIVATE_IDENTIFIER, TokenType.STRING,
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
                    trackAstNode(Identifier(nameToken.value, nameToken.range))
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
                    trackAstNode(VariableDeclarator(
                        name = target.name,
                        pattern = null,
                        initializer = initializer,
                        range = SourceRange(targetStart, end),
                    ))
                } else {
                    trackAstNode(VariableDeclarator(
                        name = null,
                        pattern = target,
                        initializer = initializer,
                        range = SourceRange(targetStart, end),
                    ))
                }
            )
        } while (match(TokenType.COMMA))

        if (requireSemicolon) {
            consumeSemicolon()
        }

        return trackAstNode(VarDeclaration(kind, declarations, SourceRange(start, previous().range.end)))
    }

    /** 函数声明：`function name(params) { ... }`（可选 async / 生成器 `*`）。 */
    private fun parseFunctionDeclaration(
        isAsync: Boolean = false,
        annexBLegacy: Boolean = false,
    ): FunctionDeclaration {
        val start = peek().range.start
        if (isAsync) advance() // async
        consume(TokenType.FUNCTION, "Expected 'function'")
        // 生成器声明：函数关键字后紧跟 `*`（与乘号无关的上下文相关语法）
        val isGenerator = match(TokenType.MULTIPLY)
        // async generator 的语法解析与同步生成器一致；运行期语义属于 L1.4，
        // 调用时由解释器给出明确的“不支持”错误（见 Interpreter.callUserFunctionOnce）
        // 声明名沿用外层 [?Yield] 语境：顶层 sloppy 允许 function* yield(){}，
        // 但在生成器函数体内 yield 已是关键字
        val name = consumeBindingIdentifier("Expected function name")
        // Annex B：在登记函数名之前判定扩展资格，避免把函数自身的词法绑定当作冲突
        val blockScoped = annexBLegacy ||
            declScopes.lastOrNull()?.treatsFunctionsAsLexical == true
        // Annex B.3.3 只覆盖普通 FunctionDeclaration，生成器与 async 函数不建立 var 绑定
        val annexBVarEligible = !isAsync && !isGenerator && !strict && blockScoped &&
            isAnnexBVarEligible(
                name.value,
                allowSameBlockFunction = !annexBLegacy && blockScoped,
            )
        // 函数声明登记到外层作用域；Annex B 的 if 分支函数不参与早期冲突检查
        if (!annexBLegacy) declareFunctionBinding(name.value, isAsync, isGenerator)
        val params = withFunctionContext(false, false, true) {
            withGeneratorContext(isGenerator) {
                withAsyncContext(isAsync) { parseParameters() }
            }
        }
        val bodyInfo = parseFunctionBody(isAsync, params = params, isGenerator = isGenerator)
        validateFunctionParameters(params, bodyInfo.strict)
        validateIdentifierName(name.value, bodyInfo.strict)
        if (bodyInfo.strict && name.value in STRICT_RESTRICTED_NAMES) {
            throw error("Unexpected eval or arguments in strict mode")
        }
        return trackAstNode(FunctionDeclaration(
            name = name.value,
            params = params,
            body = bodyInfo.body,
            strict = bodyInfo.strict,
            isAsync = isAsync,
            isGenerator = isGenerator,
            varDeclarations = bodyInfo.varDeclarations,
            functionDeclarations = bodyInfo.functionDeclarations,
            range = SourceRange(start, bodyInfo.body.range.end),
            sourceText = sourceSlice(SourceRange(start, bodyInfo.body.range.end)),
            annexBLegacy = annexBLegacy,
            annexBVarEligible = annexBVarEligible,
        ))
    }

    /** 函数表达式：`function [*] [name](params) { ... }`（调用前已由 parsePrimary 消费 function）。 */
    private fun parseFunctionExpression(startPos: SourcePosition, isAsync: Boolean = false): FunctionExpression {
        // 生成器函数表达式：function 关键字后的 `*`
        val isGenerator = match(TokenType.MULTIPLY)
        // async generator 表达式的语法与同步生成器一致；运行期语义属于 L1.4，
        // 调用时由解释器给出明确的“不支持”错误（见 Interpreter.callUserFunctionOnce）
        // 普通函数表达式的名字是 [~Yield] 语境；生成器自身的名字是 [+Yield] 语境
        val nameGeneratorContext = isGenerator
        val nameToken = withGeneratorContext(nameGeneratorContext) {
            if (check(TokenType.IDENTIFIER) ||
                (check(TokenType.LET) && allowLetAsIdentifier())
            ) {
                advance()
            } else {
                null
            }
        }
        // 函数表达式名属于函数自身绑定作用域：静态块内的 await 限制在此不适用
        nameToken?.let {
            withGeneratorContext(nameGeneratorContext) { validateIdentifierToken(it) }
        }
        // AsyncFunctionExpression / AsyncGeneratorExpression 的 BindingIdentifier
        // 不得为 await（即使外层不是 async 语境）；声明形式在 sloppy 下允许，
        // 因此只在表达式路径上做这条早期错误检查。
        if (isAsync && nameToken?.value == "await") {
            throw ParseException(
                "await is not a valid binding identifier for async function expressions",
                nameToken.range,
            )
        }
        val params = withFunctionContext(false, false, true) {
            withGeneratorContext(isGenerator) {
                withAsyncContext(isAsync) { parseParameters() }
            }
        }
        val bodyInfo = parseFunctionBody(isAsync, params = params, isGenerator = isGenerator)
        validateFunctionParameters(params, bodyInfo.strict)
        nameToken?.let { validateIdentifierName(it.value, bodyInfo.strict) }
        if (bodyInfo.strict && nameToken?.value in STRICT_RESTRICTED_NAMES) {
            throw error("Unexpected eval or arguments in strict mode")
        }
        return trackAstNode(FunctionExpression(
            name = nameToken?.value,
            params = params,
            body = bodyInfo.body,
            strict = bodyInfo.strict,
            isAsync = isAsync,
            isGenerator = isGenerator,
            varDeclarations = bodyInfo.varDeclarations,
            functionDeclarations = bodyInfo.functionDeclarations,
            range = SourceRange(startPos, bodyInfo.body.range.end),
            sourceText = sourceSlice(SourceRange(startPos, bodyInfo.body.range.end)),
        ))
    }

    /**
     * class 字段初始化表达式的解析上下文：
     * - 禁止引用 arguments（等价 ContainsArguments 早期错误）；
     * - 允许 super 属性，禁止 super() 调用；
     * - 允许 new.target（运行期取 undefined）；
     * - 恢复 [~Await] 语境，await 只能作为普通标识符。
     */
    private inline fun <T> withFieldInitializerContext(block: () -> T): T {
        val outerArgumentsForbidden = argumentsForbidden
        val outerAwaitForbidden = awaitIdentifierForbidden
        val outerGenerator = generatorContext
        val outerSuperProperty = superPropertyAllowed
        val outerSuperCall = superCallAllowed
        val outerNewTarget = newTargetAllowed
        val outerAsync = asyncContext
        argumentsForbidden = true
        awaitIdentifierForbidden = false
        classFieldInitializerDepth++
        // 字段初始化表达式是 [~Yield] 语境：yield 只能作为标识符（严格模式下直接报错）
        generatorContext = false
        superPropertyAllowed = true
        superCallAllowed = false
        newTargetAllowed = true
        asyncContext = false
        return try {
            block()
        } finally {
            classFieldInitializerDepth--
            argumentsForbidden = outerArgumentsForbidden
            awaitIdentifierForbidden = outerAwaitForbidden
            generatorContext = outerGenerator
            superPropertyAllowed = outerSuperProperty
            superCallAllowed = outerSuperCall
            newTargetAllowed = outerNewTarget
            asyncContext = outerAsync
        }
    }

    /** 解析函数体，并返回函数体元信息（严格模式、变量提升）。
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
        // 普通函数/方法/构造器是静态块限制的函数边界
        val outerArgumentsForbidden = argumentsForbidden
        val outerAwaitForbidden = awaitIdentifierForbidden
        superPropertyAllowed = allowSuperProperty
        superCallAllowed = allowSuperCall
        newTargetAllowed = allowNewTarget
        argumentsForbidden = false
        awaitIdentifierForbidden = false
        return try {
            block()
        } finally {
            superPropertyAllowed = outerSuperProperty
            superCallAllowed = outerSuperCall
            newTargetAllowed = outerNewTarget
            argumentsForbidden = outerArgumentsForbidden
            awaitIdentifierForbidden = outerAwaitForbidden
        }
    }

    private fun parseFunctionBody(
        isAsync: Boolean = false,
        allowSuperProperty: Boolean = false,
        allowSuperCall: Boolean = false,
        allowNewTarget: Boolean = true,
        params: List<Parameter> = emptyList(),
        staticBlock: Boolean = false,
        isArrow: Boolean = false,
        isGenerator: Boolean = false,
    ): FunctionBodyInfo {
        val bodyStart = pos
        val bodyStrict = detectUseStrictDirective(bodyStart)
        val hasNonSimpleParams = params.any { it.rest || it.defaultValue != null || it.pattern != null }
        if (bodyStrict && hasNonSimpleParams) {
            throw error("Illegal 'use strict' directive in function with non-simple parameter list")
        }
        val outerStrict = strict
        val outerAsync = asyncContext
        val outerGenerator = generatorContext
        val outerInFormalParameters = inFormalParameters
        val outerSuperProperty = superPropertyAllowed
        val outerSuperCall = superCallAllowed
        val outerNewTarget = newTargetAllowed
        val outerArgumentsForbidden = argumentsForbidden
        val outerAwaitForbidden = awaitIdentifierForbidden
        // 函数体有独立的 [Await] 语境，不再受外层 class 字段初始化器限制
        val outerClassFieldInitializerDepth = classFieldInitializerDepth
        strict = outerStrict || bodyStrict
        asyncContext = isAsync
        generatorContext = isGenerator
        // 函数体是形参早期错误规则的边界：yield/await 在此恢复为普通表达式语境
        inFormalParameters = false
        classFieldInitializerDepth = 0
        superPropertyAllowed = allowSuperProperty
        superCallAllowed = allowSuperCall
        newTargetAllowed = allowNewTarget
        if (staticBlock) {
            // 静态初始化块体复用函数体的声明作用域/标签隔离机制，
            // 但 arguments 与 await 标识符仍受限制
            argumentsForbidden = true
            awaitIdentifierForbidden = true
        } else {
            // 箭头函数不解除 arguments 限制（ContainsArguments 会进入箭头体），
            // 但所有函数体的 await 标识符语境都恢复为 [~Await]
            if (!isArrow) argumentsForbidden = false
            awaitIdentifierForbidden = false
        }

        // 函数边界重置循环/switch/标签上下文
        val outerFunctionDepth = functionDepth
        val outerLoopDepth = loopDepth
        val outerSwitchDepth = switchDepth
        val outerLabels = labelScopes.toList()
        val outerInLabelChain = inLabelChain
        val outerLabelChainAllowsFunction = labelChainAllowsFunction
        functionDepth++
        if (staticBlock) staticBlockDepths.add(functionDepth)
        loopDepth = 0
        switchDepth = 0
        labelScopes.clear()
        inLabelChain = false

        // 形参与函数体顶层词法声明（let/const/class）不得重名；即使是非简单参数
        // 列表（运行时使用独立参数环境），该早期错误仍然适用。var/函数声明不受限制。
        val parameterNames = collectParameterNames(params).toSet()
        pushDeclScope(
            DeclScopeKind.FUNCTION_BODY,
            parameterNames,
            annexParameterNames = parameterNames,
            hasArgumentsBinding = !isArrow && !staticBlock,
        )
        val bodyScope = declScopes.last()
        val body = try {
            parseBlockStatement(trackScope = false, environmentScope = bodyScope)
        } finally {
            popDeclScope()
            if (staticBlock) staticBlockDepths.removeAt(staticBlockDepths.lastIndex)
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
            argumentsForbidden = outerArgumentsForbidden
            awaitIdentifierForbidden = outerAwaitForbidden
            classFieldInitializerDepth = outerClassFieldInitializerDepth
            inFormalParameters = outerInFormalParameters
        }
        val innerStrict = strict
        strict = outerStrict
        asyncContext = outerAsync
        generatorContext = outerGenerator

        val hoist = collectHoisted(body.statements, innerStrict)
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

    /** 标识符引用/绑定位置的额外校验：转义关键字与 async/generator/模块上下文中的关键字。 */
    private fun validateIdentifierToken(token: Token) {
        validateEscapedKeyword(token)
        // 模块代码中 await 始终是保留字（含嵌套函数与转义写法），不能作为绑定名或引用
        if (moduleContext && token.value == "await") {
            throw ParseException("await is not a valid identifier in module code", token.range)
        }
        if (asyncContext && token.value == "await") {
            throw ParseException("await is not a valid identifier in async context", token.range)
        }
        // 生成器语境中 yield 是关键字，不能作为标识符引用或绑定名
        if (generatorContext && token.value == "yield") {
            throw ParseException("yield is not a valid identifier in generator context", token.range)
        }
    }

    /**
     * 在 async 上下文中解析参数（await 不能作为绑定名）。
     *
     * 普通函数/方法的形参是 [~Await] 语境：即使外层是 async 函数体，
     * 也必须显式切回 false，否则嵌套函数的形参会被错误地按 async 关键字处理。
     */
    private inline fun <T> withAsyncContext(isAsync: Boolean, block: () -> T): T {
        val outer = asyncContext
        asyncContext = isAsync
        return try {
            block()
        } finally {
            asyncContext = outer
        }
    }

    /**
     * 在指定的生成器语境中解析函数参数。
     *
     * 函数边界总是把 [generatorContext] 重置为参数自身的取值：普通函数（isGenerator=false）
     * 的参数是 [~Yield] 语境，yield 可以继续作为绑定名；生成器参数是 [+Yield] 语境。
     */
    private inline fun <T> withGeneratorContext(isGenerator: Boolean, block: () -> T): T {
        val outer = generatorContext
        generatorContext = isGenerator
        return try {
            block()
        } finally {
            generatorContext = outer
        }
    }

    /**
     * 解析函数参数（左括号已消费），支持默认值、rest 与解构。
     *
     * 解析期间置位 [inFormalParameters]：默认值/解构模式/计算属性名中的
     * yield 与 await 需要按形参早期错误规则校验。
     */
    private fun parseParametersAfterOpen(): List<Parameter> {
        val outerInFormalParameters = inFormalParameters
        inFormalParameters = true
        return try {
            parseParametersAfterOpenBody()
        } finally {
            inFormalParameters = outerInFormalParameters
        }
    }

    /** 参数列表的实际解析（由 [parseParametersAfterOpen] 包裹上下文标志）。 */
    private fun parseParametersAfterOpenBody(): List<Parameter> {
        val params = mutableListOf<Parameter>()
        if (!check(TokenType.RPAREN)) {
            do {
                val start = peek().range.start
                if (match(TokenType.ELLIPSIS)) {
                    if (check(TokenType.LBRACKET) || check(TokenType.LBRACE)) {
                        val target = parseBindingPatternLiteral()
                        validateBindingPattern(target)
                        params.add(
                            trackAstNode(Parameter(
                                name = null,
                                pattern = target,
                                defaultValue = null,
                                rest = true,
                                range = SourceRange(start, target.range.end),
                            ))
                        )
                    } else {
                        val name = consumeBindingIdentifier("Expected rest parameter name")
                        params.add(
                            trackAstNode(Parameter(
                                name = name.value,
                                pattern = null,
                                defaultValue = null,
                                rest = true,
                                range = SourceRange(start, name.range.end),
                            ))
                        )
                    }
                    break
                }

                val target: Expression = when {
                    check(TokenType.LBRACKET) || check(TokenType.LBRACE) -> parseBindingPatternLiteral()
                    else -> {
                        val name = consumeBindingIdentifier("Expected parameter name")
                        trackAstNode(Identifier(name.value, name.range))
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
                        trackAstNode(Parameter(
                            name = target.name,
                            pattern = null,
                            defaultValue = defaultValue,
                            rest = false,
                            range = SourceRange(start, end),
                        ))
                    )
                } else {
                    params.add(
                        trackAstNode(Parameter(
                            name = null,
                            pattern = target,
                            defaultValue = defaultValue,
                            rest = false,
                            range = SourceRange(start, end),
                        ))
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
    /**
     * 判断当前位置是否是 `using BindingIdentifier`（不含换行，且后随绑定起始）。
     *
     * allowOf=false 用于 for-of 头部：`for (using of of [..])` 中的 using 应视为
     * 普通标识符左值（lookahead 限制），而不是 using 声明。
     */
    private fun isUsingDeclarationStart(offset: Int = 0, allowOf: Boolean = true): Boolean {
        val token = peek(offset)
        if (token.type != TokenType.IDENTIFIER || token.containsEscape || token.value != "using") {
            return false
        }
        val next = peek(offset + 1)
        if (next.precededByNewline) return false
        if (next.type != TokenType.IDENTIFIER && next.type != TokenType.LET) return false
        if (!allowOf && next.type == TokenType.IDENTIFIER && !next.containsEscape &&
            next.value == "of"
        ) {
            return false
        }
        return true
    }

    /** 判断当前位置是否是 `await using ...`（仅 async 语境）。 */
    private fun isAwaitUsingDeclarationStart(offset: Int = 0): Boolean {
        if (!asyncContext) return false
        val awaitToken = peek(offset)
        if (awaitToken.type != TokenType.IDENTIFIER || awaitToken.containsEscape ||
            awaitToken.value != "await"
        ) {
            return false
        }
        val usingToken = peek(offset + 1)
        if (usingToken.precededByNewline) return false
        return isUsingDeclarationStart(offset + 1)
    }

    /**
     * 解析 using / await using 声明。
     *
     * 绑定必须是标识符（不允许解构模式），且每个绑定都必须有初始化表达式。
     */
    private fun parseUsingDeclaration(isAwait: Boolean, requireTerminator: Boolean): UsingDeclaration {
        val start = peek().range.start
        if (isAwait) {
            if (moduleContext && functionDepth == 0) moduleHasTopLevelAwait = true
            advance() // await
        }
        advance() // using
        val declarations = mutableListOf<UsingDeclarator>()
        do {
            if (check(TokenType.LBRACKET) || check(TokenType.LBRACE)) {
                throw error("using declarations may not use binding patterns")
            }
            val nameToken = consumeBindingIdentifier("Expected binding identifier in using declaration")
            validateIdentifierName(nameToken.value, strict)
            declareLexicalBinding(nameToken.value)
            if (!match(TokenType.ASSIGN)) {
                throw error("Missing initializer in using declaration")
            }
            val initializer = parseAssignment()
            declarations.add(
                trackAstNode(UsingDeclarator(
                    name = nameToken.value,
                    initializer = initializer,
                    range = SourceRange(nameToken.range.start, initializer.range.end),
                ))
            )
        } while (match(TokenType.COMMA))
        if (requireTerminator) consumeSemicolon()
        return trackAstNode(UsingDeclaration(
            declarations = declarations,
            isAwait = isAwait,
            range = SourceRange(start, previous().range.end),
        ))
    }

    private fun parseBlockStatement(
        trackScope: Boolean = true,
        environmentScope: DeclScope? = null,
    ): BlockStatement {
        val start = consume(TokenType.LBRACE, "Expected '{'").range.start
        if (trackScope) pushDeclScope(DeclScopeKind.BLOCK)
        // 块内登记的词法名决定解释期是否需要独立环境（无则复用外层）
        val blockScope = if (trackScope) declScopes.last() else null
        val statements = mutableListOf<Statement>()
        // 块语句列表允许 using 声明（函数体同样复用该方法）
        val previousUsingAllowed = usingDeclarationAllowed
        usingDeclarationAllowed = true
        try {
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                statements.add(parseStatement())
            }
        } finally {
            usingDeclarationAllowed = previousUsingAllowed
            if (trackScope) popDeclScope()
        }
        val end = consume(TokenType.RBRACE, "Expected '}'").range.end
        // trackScope=false 时由调用方提供声明作用域（函数体的 FUNCTION_BODY）；
        // 无法提供作用域时保守保留独立环境
        val scope = blockScope ?: environmentScope
        val requiresEnvironment = scope?.lexicalNames?.isNotEmpty() ?: true
        return trackAstNode(BlockStatement(
            statements = statements,
            range = SourceRange(start, end),
            requiresEnvironment = requiresEnvironment,
            hasUsingDeclaration = statements.any { it is UsingDeclaration },
        ))
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
        return trackAstNode(IfStatement(condition, consequent, alternate, SourceRange(start, end)))
    }

    /** while 语句。 */
    private fun parseWhileStatement(): WhileStatement {
        val start = consume(TokenType.WHILE, "Expected 'while'").range.start
        consume(TokenType.LPAREN, "Expected '(' after 'while'")
        val condition = parseExpression()
        consume(TokenType.RPAREN, "Expected ')' after while condition")
        val body = withLoopContext { parseSingleStatement() }
        return trackAstNode(WhileStatement(condition, body, SourceRange(start, body.range.end)))
    }

    /** for / for-in 语句。 */
    private fun parseForStatement(): Statement {
        val start = consume(TokenType.FOR, "Expected 'for'").range.start

        // `for await` 只在 async 上下文中把 await 当关键字；同步上下文里它是普通标识符。
        // 转义写法（aw\u0061it）按标识符处理，不构成 for-await。
        val isAwait = asyncContext &&
            check(TokenType.IDENTIFIER) &&
            peek().value == "await" &&
            !peek().containsEscape
        if (isAwait) {
            if (moduleContext && functionDepth == 0) moduleHasTopLevelAwait = true
            advance()
        }
        consume(TokenType.LPAREN, "Expected '(' after 'for'")

        when (forHeaderKind()) {
            ForHeaderKind.IN -> {
                if (isAwait) throw error("for await may not be used with 'in'")
                return parseForInOfStatement(start, isOf = false)
            }

            ForHeaderKind.OF -> return parseForInOfStatement(start, isOf = true, isAwait = isAwait)
            ForHeaderKind.NONE -> {
                if (isAwait) throw error("for await requires 'of'")
            }
        }

        // for 头部词法声明与循环体 var 的冲突检查需要一个独立作用域帧
        pushDeclScope(DeclScopeKind.FOR)
        try {
            var init: Statement? = null
            if (!match(TokenType.SEMICOLON)) {
                val letIsExpression = check(TokenType.LET) && allowLetAsIdentifier() && !isLetDeclarationStart()
                init = if (isAwaitUsingDeclarationStart()) {
                    parseUsingDeclaration(isAwait = true, requireTerminator = false)
                } else if (isUsingDeclarationStart()) {
                    parseUsingDeclaration(isAwait = false, requireTerminator = false)
                } else if ((check(TokenType.VAR) || check(TokenType.CONST) ||
                        (check(TokenType.LET) && !letIsExpression))
                ) {
                    parseVarDeclaration(requireSemicolon = false)
                } else {
                    val expression = parseExpression()
                    trackAstNode(ExpressionStatement(expression, expression.range))
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
            return trackAstNode(ForStatement(init, condition, update, body, SourceRange(start, body.range.end)))
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
     * 该 token 能否结束一个 LeftHandSideExpression。
     *
     * for 头部的 `of` 只有在左值表达式已经完整时才可能是 for-of 运算符；
     * 声明关键字、点号、逗号、运算符等之后的 `of` 都是普通标识符。
     */
    private fun canEndLeftHandSideExpression(type: TokenType): Boolean = when (type) {
        TokenType.IDENTIFIER, TokenType.PRIVATE_IDENTIFIER,
        TokenType.NUMBER, TokenType.NUMBER_HEX, TokenType.NUMBER_OCT, TokenType.NUMBER_BIN,
        TokenType.BIGINT, TokenType.STRING, TokenType.REGEX, TokenType.TEMPLATE,
        TokenType.TRUE, TokenType.FALSE, TokenType.NULL, TokenType.THIS, TokenType.SUPER,
        TokenType.RPAREN, TokenType.RBRACKET, TokenType.RBRACE,
        -> true

        else -> false
    }

    /**
     * 判断 `for (` 之后是普通 for、for-in 还是 for-of。
     *
     * 在括号深度 0 处、遇到分号之前出现 `in` / `of` 即为对应形式。
     * 条件表达式的第一个分支是 [+In] 语境，其中的 `in` 不算 for-in 分隔符
     * （例如 `for (true ? '' in f() : g(); ;)`）。
     */
    private fun forHeaderKind(): ForHeaderKind {
        var index = pos
        var depth = 0
        var conditionalDepth = 0
        while (index < tokens.size) {
            when (tokens[index].type) {
                TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE -> depth++
                TokenType.RPAREN, TokenType.RBRACKET, TokenType.RBRACE -> {
                    if (depth == 0) return ForHeaderKind.NONE
                    depth--
                }

                TokenType.SEMICOLON -> if (depth == 0) return ForHeaderKind.NONE
                TokenType.QUESTION -> if (depth == 0) conditionalDepth++
                TokenType.COLON -> if (depth == 0 && conditionalDepth > 0) conditionalDepth--
                TokenType.IN -> if (depth == 0 && conditionalDepth == 0) return ForHeaderKind.IN
                TokenType.IDENTIFIER -> if (depth == 0 && !tokens[index].containsEscape &&
                    tokens[index].value == "of"
                ) {
                    // `of` 不是保留字：只有“前一个 token 能结束左值表达式”时才是
                    // for-of 运算符。声明绑定名（var/let/const of）、属性名（a.of）
                    // 与表达式开头的 of 都只是普通标识符。
                    val previous = tokens.getOrNull(index - 1)
                    val next = tokens.getOrNull(index + 1)
                    val isDeclarationBinding = previous != null && when (previous.type) {
                        // `var of` / `const of`：of 是绑定名
                        TokenType.VAR, TokenType.CONST -> true
                        // `let` 只有位于头部第一个 token 时才是声明关键字；
                        // `for (var let of ...)` 中的 let 是绑定名，of 才是 for-of 运算符
                        TokenType.LET -> index - 1 == pos
                        else -> false
                    }
                    val isPropertyName = previous != null && previous.type in setOf(
                        TokenType.DOT, TokenType.QUESTION_DOT,
                    )
                    // `for (using of = ...)` 是普通 for 中的 using 声明（绑定名 of）；
                    // 其余 `for (using of ...)` 按 Test262 的前瞻规则解释为
                    // “标识符 using + for-of 运算符”。
                    val isUsingBinding = previous != null &&
                        previous.type == TokenType.IDENTIFIER &&
                        !previous.containsEscape && previous.value == "using" &&
                        next?.type == TokenType.ASSIGN
                    // `for (async of => {})`：这是 async 箭头函数，不是 for-of
                    val isAsyncArrowParameter = previous != null &&
                        previous.type == TokenType.IDENTIFIER &&
                        !previous.containsEscape && previous.value == "async" &&
                        next?.type == TokenType.ARROW
                    val previousCanEndLeftHandSide = previous != null && (
                        canEndLeftHandSideExpression(previous.type) ||
                            // `for (var let of ...)`：let 作为绑定名也能结束左值
                            (previous.type == TokenType.LET && index - 1 != pos)
                        )
                    if (!isDeclarationBinding && !isPropertyName && !isUsingBinding &&
                        !isAsyncArrowParameter && previousCanEndLeftHandSide
                    ) {
                        return ForHeaderKind.OF
                    }
                }

                TokenType.EOF -> return ForHeaderKind.NONE
                else -> Unit
            }
            index++
        }
        return ForHeaderKind.NONE
    }

    /** for-in / for-of 头部（`for (` 已消费）。 */
    private fun parseForInOfStatement(
        start: SourcePosition,
        isOf: Boolean,
        isAwait: Boolean = false,
    ): Statement {
        pushDeclScope(DeclScopeKind.FOR)
        try {
            return parseForInOfStatementBody(start, isOf, isAwait)
        } finally {
            popDeclScope()
        }
    }

    private fun parseForInOfStatementBody(
        start: SourcePosition,
        isOf: Boolean,
        isAwait: Boolean = false,
    ): Statement {
        // for-of 左值不允许以 let 开头（Lookahead 限制）
        if (isOf && check(TokenType.LET) && !isLetDeclarationStart()) {
            throw error("The left-hand side of a for-of loop may not start with 'let'")
        }
        var declarationKind: DeclarationKind? = null
        var variableName: String? = null
        var pattern: Expression? = null
        var target: Expression? = null
        var usingMode: UsingMode? = null
        var forInInitializer: Expression? = null

        val usingHead = isUsingDeclarationStart(allowOf = false)
        if (isAwaitUsingDeclarationStart() || usingHead) {
            if (!isOf) {
                throw error("using declarations are not allowed in for-in statements")
            }
            val isAwait = isAwaitUsingDeclarationStart()
            if (isAwait) advance() // await
            advance() // using
            if (check(TokenType.LBRACKET) || check(TokenType.LBRACE)) {
                throw error("using declarations may not use binding patterns")
            }
            val nameToken = consumeBindingIdentifier("Expected binding identifier after 'using'")
            validateIdentifierName(nameToken.value, strict)
            variableName = nameToken.value
            declarationKind = DeclarationKind.CONST
            usingMode = if (isAwait) UsingMode.ASYNC else UsingMode.SYNC
            if (match(TokenType.ASSIGN)) {
                throw error("for-of loop variable declaration may not have an initializer")
            }
        }

        val letIsExpressionInHeader = check(TokenType.LET) && allowLetAsIdentifier() && when (peek(1).type) {
            TokenType.IDENTIFIER, TokenType.LET, TokenType.LBRACKET, TokenType.LBRACE -> false
            else -> true
        }
        if (usingMode != null) {
            // using 头部已在上方解析，跳过普通声明/左值分支
        } else if (check(TokenType.VAR) || check(TokenType.CONST) ||
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
            // 兼容旧式 for (var k = 0 in obj)：初始化表达式按 [~In] 解析并忽略；
            // for-of 的声明形式不允许初始化器
            if (match(TokenType.ASSIGN)) {
                if (isOf) {
                    throw error("for-of loop variable declaration may not have an initializer")
                }
                // Annex B 仅允许 sloppy 下 `for (var x = init in obj)`；
                // let/const 与解构模式一律早期错误
                if (declarationKind != DeclarationKind.VAR || pattern != null || strict) {
                    throw error("for-in loop variable declaration may not have an initializer")
                }
                val previousInAllowed = inOperatorAllowed
                inOperatorAllowed = false
                try {
                    forInInitializer = parseAssignment()
                } finally {
                    inOperatorAllowed = previousInAllowed
                }
            }
        } else {
            val defaultsBefore = patternDefaultsSeen
            val targetStartToken = peek()
            val expression = withPatternSyntax { parseLeftHandSide() }
            if (expression is ArrayLiteral || expression is ObjectLiteral) {
                validateAssignmentPattern(expression)
            } else if (expression !is Identifier && expression !is MemberExpr &&
                !(expression is CallExpr && !strict)
            ) {
                throw error("Invalid left-hand side in for-in/of")
            }
            // for-of 左值不允许裸 async（Lookahead 限制）；
            // for-await-of 的 Lookahead 限制不包含 async，`for await (async of ...)` 合法。
            // 转义写法或括号包裹是不同 token 序列，允许作为普通标识符
            if (isOf && !isAwait && expression is Identifier && expression.name == "async" &&
                !targetStartToken.containsEscape && expression.range !in parenthesizedRanges
            ) {
                throw error("The left-hand side of a for-of loop may not be 'async'")
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
            trackAstNode(ForOfStatement(
                declarationKind = declarationKind,
                variableName = variableName,
                pattern = pattern,
                target = target,
                right = right,
                body = body,
                range = range,
                usingMode = usingMode,
                isAwait = isAwait,
            ))
        } else {
            trackAstNode(ForInStatement(
                declarationKind = declarationKind,
                variableName = variableName,
                pattern = pattern,
                target = target,
                right = right,
                body = body,
                range = range,
                initializer = forInInitializer,
            ))
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
        // case 子句的 StatementList 不允许 using 声明
        val previousUsingAllowed = usingDeclarationAllowed
        usingDeclarationAllowed = false
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
                        cases.add(trackAstNode(SwitchCase(test, consequent, SourceRange(caseStart, end))))
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
                        cases.add(trackAstNode(SwitchCase(null, consequent, SourceRange(caseStart, end))))
                    }

                    else -> throw error("Expected 'case' or 'default' in switch statement")
                }
            }
        } finally {
            usingDeclarationAllowed = previousUsingAllowed
            switchDepth--
            popDeclScope()
        }

        val end = consume(TokenType.RBRACE, "Expected '}' after switch body").range.end
        return trackAstNode(SwitchStatement(discriminant, cases, SourceRange(start, end)))
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
        return trackAstNode(BreakStatement(label, SourceRange(start, previous().range.end)))
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
        return trackAstNode(ContinueStatement(label, SourceRange(start, previous().range.end)))
    }

    /** throw 语句。 */
    private fun parseThrowStatement(): ThrowStatement {
        val start = consume(TokenType.THROW, "Expected 'throw'").range.start
        if (peek().precededByNewline) {
            throw error("Illegal newline after throw")
        }
        val argument = parseExpression()
        consumeSemicolon()
        return trackAstNode(ThrowStatement(argument, SourceRange(start, argument.range.end)))
    }

    /** return 语句。 */
    private fun parseReturnStatement(): ReturnStatement {
        val start = consume(TokenType.RETURN, "Expected 'return'").range.start
        // 静态初始化块体不是函数体：块内直接 return 非法，嵌套函数内的 return 合法
        val inStaticBlockBody = staticBlockDepths.isNotEmpty() && functionDepth == staticBlockDepths.last()
        if (functionDepth == 0 || inStaticBlockBody) {
            throw error("Illegal return statement")
        }
        var value: Expression? = null
        // ASI：return 后如果有换行，则返回 undefined
        if (!check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !isAtEnd() && !peek().precededByNewline) {
            value = parseExpression()
        }
        consumeSemicolon()
        return trackAstNode(ReturnStatement(value, SourceRange(start, previous().range.end)))
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
            handler = trackAstNode(CatchClause(param, pattern, body, SourceRange(catchStart, body.range.end)))
        }

        var finalizer: BlockStatement? = null
        if (match(TokenType.FINALLY)) {
            finalizer = parseBlockStatement()
        }

        if (handler == null && finalizer == null) {
            throw error("Missing catch or finally after try")
        }

        val end = finalizer?.range?.end ?: handler?.range?.end ?: block.range.end
        return trackAstNode(TryStatement(block, handler, finalizer, SourceRange(start, end)))
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
        return trackAstNode(SequenceExpr(
            expressions,
            SourceRange(first.range.start, expressions.last().range.end),
        ))
    }

    /** 赋值表达式（右结合，箭头函数与 yield 也在此层解析）。 */
    private fun parseAssignment(): Expression {
        // 生成器语境中的 yield 是关键字：它只能出现在赋值表达式层
        if (generatorContext) {
            parseYieldExpressionOrNull()?.let { return it }
        }
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
            } else if (left !is Identifier && left !is MemberExpr &&
                !(left is CallExpr && !strict &&
                    token.value != "&&=" && token.value != "||=" && token.value != "??=")
            ) {
                // Annex B web-compat：sloppy 下 CallExpression 可作普通/复合赋值目标，
                // 运行时抛 ReferenceError；逻辑赋值仍要求简单目标（早期错误）
                throw error("Invalid assignment target")
            }
            validateStrictAssignmentTarget(left)
            advance()
            val value = parseAssignment()
            // 目标中的简写默认值属于合法模式，不向后续表达式传播
            patternDefaultsSeen = defaultsBefore
            return trackAstNode(AssignmentExpr(
                target = left,
                operator = token.value,
                value = value,
                range = SourceRange(left.range.start, value.range.end),
                lhsParenthesized = left.range in parenthesizedRanges,
            ))
        }
        if (patternSyntaxDepth == 0 && patternDefaultsSeen > defaultsBefore) {
            throw error("Invalid shorthand property initializer")
        }
        return left
    }

    /**
     * 尝试解析 `yield` / `yield*` 表达式；当前位置不是 `yield` 时返回 null。
     *
     * 语法（生成器语境）：
     * - `yield`：产出 undefined；
     * - `yield AssignmentExpression`（实参不能跨行）；
     * - `yield* AssignmentExpression`（`*` 必须与 yield 同一行）。
     */
    private fun parseYieldExpressionOrNull(): Expression? {
        val token = peek()
        if (token.type != TokenType.IDENTIFIER || token.value != "yield" || token.containsEscape) {
            return null
        }
        // 生成器形参（含解构/默认值/计算属性名）不允许 YieldExpression；
        // 嵌套箭头函数的形参继承外层 [+Yield]，由同一标志覆盖。
        if (inFormalParameters) {
            throw ParseException(
                "Yield expression is not allowed in generator parameters",
                token.range,
            )
        }
        val start = token.range.start
        advance() // yield
        if (check(TokenType.MULTIPLY) && !peek().precededByNewline) {
            advance() // *
            val argument = parseAssignment()
            return trackAstNode(YieldExpression(
                argument = argument,
                delegate = true,
                range = SourceRange(start, argument.range.end),
            ))
        }
        // 换行或后续 Token 不能开始表达式时按无实参的 yield 处理（yield 自身可被 ASI 结束）
        if (peek().precededByNewline || !canStartYieldArgument()) {
            return trackAstNode(YieldExpression(
                argument = null,
                delegate = false,
                range = SourceRange(start, previous().range.end),
            ))
        }
        val argument = parseAssignment()
        return trackAstNode(YieldExpression(
            argument = argument,
            delegate = false,
            range = SourceRange(start, argument.range.end),
        ))
    }

    /** 判断当前 Token 是否可以开始 yield 的实参（AssignmentExpression）。 */
    private fun canStartYieldArgument(): Boolean = when (peek().type) {
        TokenType.NUMBER, TokenType.NUMBER_HEX, TokenType.NUMBER_OCT, TokenType.NUMBER_BIN,
        TokenType.BIGINT, TokenType.STRING, TokenType.NULL, TokenType.TRUE, TokenType.FALSE,
        TokenType.IDENTIFIER, TokenType.LET, TokenType.PRIVATE_IDENTIFIER,
        TokenType.THIS, TokenType.NEW, TokenType.SUPER, TokenType.FUNCTION, TokenType.CLASS,
        TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE, TokenType.REGEX,
        TokenType.TEMPLATE, TokenType.NOT, TokenType.MINUS, TokenType.PLUS,
        TokenType.BITWISE_NOT, TokenType.TYPEOF, TokenType.DELETE, TokenType.VOID,
        TokenType.INCREMENT, TokenType.DECREMENT -> true

        else -> false
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
                withAsyncContext(true) {
                    validateIdentifierToken(paramToken)
                    validateStaticBlockBindingName(paramToken.value)
                }
                consume(TokenType.ARROW, "Expected '=>'")
                return parseArrowBody(
                    start,
                    listOf(trackAstNode(Parameter(paramToken.value, null, null, false, paramToken.range))),
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
            validateStaticBlockBindingName(token.value)
            consume(TokenType.ARROW, "Expected '=>'")
            return parseArrowBody(
                token.range.start,
                listOf(trackAstNode(Parameter(token.value, null, null, false, token.range))),
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
                isArrow = true,
            )
            validateFunctionParameters(params, bodyInfo.strict, isArrow = true)
            return trackAstNode(ArrowFunction(
                params = params,
                body = bodyInfo.body,
                expressionBody = null,
                strict = bodyInfo.strict,
                isAsync = isAsync,
                varDeclarations = bodyInfo.varDeclarations,
                functionDeclarations = bodyInfo.functionDeclarations,
                range = SourceRange(start, bodyInfo.body.range.end),
                allowsNewTarget = newTargetAllowed,
                sourceText = sourceSlice(SourceRange(start, bodyInfo.body.range.end)),
            ))
        }

        val outerAsync = asyncContext
        asyncContext = isAsync
        // 箭头函数体是 await 标识符限制的边界（[~Await] 语境）；同样是 [~Yield] 语境
        val outerAwaitForbidden = awaitIdentifierForbidden
        awaitIdentifierForbidden = false
        val outerGenerator = generatorContext
        generatorContext = false
        // 箭头表达式体是形参早期错误规则的边界：其中 yield/await 恢复为普通表达式语境
        val outerInFormalParameters = inFormalParameters
        inFormalParameters = false
        // 箭头函数体同样有独立的 [Await] 语境，不受外层 class 字段初始化器限制
        val outerClassFieldInitializerDepth = classFieldInitializerDepth
        classFieldInitializerDepth = 0
        // 表达式体同样是函数边界：await 不能再被误判为模块顶层 TLA。
        val outerFunctionDepth = functionDepth
        functionDepth++
        // 表达式体沿用当前严格模式上下文
        validateFunctionParameters(params, strict, isArrow = true)
        try {
            // 箭头表达式体不会经过普通的一元/条件递归路径，需要单独计入嵌套深度，
            // 否则 `x => x => ...` 这类无括号链会绕过 maxNestingDepth。
            val expression = withSyntaxNesting { parseAssignment() }
            return trackAstNode(ArrowFunction(
                params = params,
                body = null,
                expressionBody = expression,
                strict = strict,
                isAsync = isAsync,
                varDeclarations = emptyList(),
                functionDeclarations = emptyList(),
                range = SourceRange(start, expression.range.end),
                allowsNewTarget = newTargetAllowed,
                sourceText = sourceSlice(SourceRange(start, expression.range.end)),
            ))
        } finally {
            functionDepth = outerFunctionDepth
            asyncContext = outerAsync
            awaitIdentifierForbidden = outerAwaitForbidden
            classFieldInitializerDepth = outerClassFieldInitializerDepth
            generatorContext = outerGenerator
            inFormalParameters = outerInFormalParameters
        }
    }

    /** 三元条件表达式。 */
    private fun parseConditional(): Expression {
        val condition = parseCoalesce()
        if (!match(TokenType.QUESTION)) return condition

        val thenBranch = parseAssignment()
        consume(TokenType.COLON, "Expected ':' in conditional expression")
        val elseBranch = parseAssignment()
        return trackAstNode(ConditionalExpr(
            condition,
            thenBranch,
            elseBranch,
            SourceRange(condition.range.start, elseBranch.range.end),
        ))
    }

    /** `??` 空值合并（左结合）。 */
    private fun parseCoalesce(): Expression {
        var expression = parseLogicalOr()
        if (check(TokenType.COALESCE) && isUnparenthesizedLogicalChain(expression)) {
            throw error("Coalesce expression cannot be mixed with logical operators without parentheses")
        }
        while (match(TokenType.COALESCE)) {
            // 右侧是 BitwiseORExpression：`??` 不得与未加括号的 &&/|| 混用
            val right = parseBitwiseOr()
            if (isUnparenthesizedLogicalChain(right)) {
                throw error(
                    "Coalesce expression cannot be mixed with logical operators without parentheses"
                )
            }
            expression = trackAstNode(CoalesceExpr(
                expression,
                right,
                SourceRange(expression.range.start, right.range.end),
            ))
        }
        return expression
    }

    /** 未加括号的 &&/|| 链（不能直接作为 ?? 的操作数）。 */
    private fun isUnparenthesizedLogicalChain(expression: Expression): Boolean =
        expression is BinaryOp &&
            (expression.operator == "||" || expression.operator == "&&") &&
            expression.range !in parenthesizedRanges

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

    private fun parseComparison(): Expression {
        // 私有名品牌检查：PrivateIdentifier in ShiftExpression 是关系表达式的一种，
        // 结果可继续参与后续关系运算
        var expression: Expression = if (check(TokenType.PRIVATE_IDENTIFIER)) {
            val privateToken = advance()
            if (!check(TokenType.IN)) {
                throw error("Unexpected private name")
            }
            advance() // in
            val right = parseShift()
            recordPrivateNameReference(privateToken.value, privateToken.range)
            trackAstNode(PrivateInExpr(
                privateToken.value,
                right,
                SourceRange(privateToken.range.start, right.range.end),
            ))
        } else {
            parseShift()
        }
        while (true) {
            val operator = when (peek().type) {
                TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE,
                TokenType.INSTANCEOF -> advance()
                TokenType.IN -> if (inOperatorAllowed) advance() else break
                else -> break
            }
            val right = parseShift()
            expression = trackAstNode(BinaryOp(
                expression,
                operator.value,
                right,
                SourceRange(expression.range.start, right.range.end),
            ))
        }
        return expression
    }

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
        // 括号包裹会消除“一元表达式不能作为 ** 左操作数”的限制：(-1n) ** 2n 合法
        if (base is UnaryOp && base.range !in parenthesizedRanges) {
            throw error("Unary expression cannot be used as the left operand of '**'")
        }
        advance()
        val exponent = parseExponentiation()
        return trackAstNode(BinaryOp(base, "**", exponent, SourceRange(base.range.start, exponent.range.end)))
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
            expression = trackAstNode(BinaryOp(
                expression,
                operator,
                right,
                SourceRange(expression.range.start, right.range.end),
            ))
        }
        return expression
    }

    /** 一元运算与自增自减前缀。 */
    private fun parseUnary(): Expression = withSyntaxNesting { parseUnaryImpl() }

    private fun parseUnaryImpl(): Expression {
        val token = peek()

        // async 形参（含解构/默认值/计算属性名）不允许 AwaitExpression；
        // 嵌套箭头函数的形参继承外层 [+Await]，由同一标志覆盖。
        if (asyncContext && inFormalParameters && token.type == TokenType.IDENTIFIER &&
            token.value == "await" && !token.containsEscape
        ) {
            throw error("Await expression is not allowed in async function parameters")
        }

        // async 函数中的 await；模块顶层走单独的 TLA 分支
        if (asyncContext && functionDepth > 0 && token.type == TokenType.IDENTIFIER &&
            token.value == "await"
        ) {
            advance()
            val operand = parseUnary()
            return trackAstNode(UnaryOp("await", operand, SourceRange(token.range.start, operand.range.end)))
        }

        // 模块代码中的 await：
        // - 模块顶层（functionDepth == 0）的 await 是 TLA，记录标记供链接期拒绝；
        // - 非 async 函数/方法/箭头体是 [~Await] 语境，必须按保留字拒绝；
        // - 形参列表、class 字段初始化器与 static 块是 [~Await] 语境。
        // - 转义形式（aw\u0061it）不构成 await 运算符。
        if (moduleContext && functionDepth == 0 && !inFormalParameters &&
            classFieldInitializerDepth == 0 && !awaitIdentifierForbidden &&
            token.type == TokenType.IDENTIFIER &&
            token.value == "await" && !token.containsEscape
        ) {
            moduleHasTopLevelAwait = true
            advance()
            val operand = parseUnary()
            return trackAstNode(UnaryOp("await", operand, SourceRange(token.range.start, operand.range.end)))
        }

        return when (token.type) {
            TokenType.INCREMENT, TokenType.DECREMENT -> {
                advance()
                val operand = parseUnary()
                if (operand !is Identifier && operand !is MemberExpr &&
                    !(operand is CallExpr && !strict)
                ) {
                    throw error("Invalid left-hand side expression in prefix operation")
                }
                validateStrictAssignmentTarget(operand)
                trackAstNode(UpdateExpr(
                    target = operand,
                    operator = token.value,
                    prefix = true,
                    range = SourceRange(token.range.start, operand.range.end),
                ))
            }

            TokenType.NOT, TokenType.MINUS, TokenType.PLUS,
            TokenType.BITWISE_NOT, TokenType.TYPEOF,
            TokenType.DELETE, TokenType.VOID -> {
                advance()
                val operand = parseUnary()
                if (token.type == TokenType.DELETE && operand is MemberExpr &&
                    operand.property is PrivateIdentifier
                ) {
                    throw error("Private fields can not be deleted")
                }
                if (token.type == TokenType.DELETE && strict && operand is Identifier) {
                    throw error("Delete of an unqualified identifier in strict mode")
                }
                trackAstNode(UnaryOp(token.value, operand, SourceRange(token.range.start, operand.range.end)))
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
            if (expression !is Identifier && expression !is MemberExpr &&
                !(expression is CallExpr && !strict)
            ) {
                throw error("Invalid left-hand side expression in postfix operation")
            }
            validateStrictAssignmentTarget(expression)
            advance()
            expression = trackAstNode(UpdateExpr(
                target = expression,
                operator = token.value,
                prefix = false,
                range = SourceRange(expression.range.start, token.range.end),
            ))
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
                    expression = trackAstNode(CallExpr(
                        expression,
                        arguments,
                        SourceRange(expression.range.start, previous().range.end),
                    ))
                }

                match(TokenType.DOT) -> {
                    if (check(TokenType.PRIVATE_IDENTIFIER)) {
                        if (expression is SuperExpr) {
                            throw error("'super' keyword unexpected here")
                        }
                        val privateToken = advance()
                        recordPrivateNameReference(privateToken.value, privateToken.range)
                        expression = trackAstNode(MemberExpr(
                            obj = expression,
                            property = trackAstNode(PrivateIdentifier(privateToken.value, privateToken.range)),
                            computed = false,
                            range = SourceRange(expression.range.start, privateToken.range.end),
                        ))
                    } else {
                        val propertyToken = parsePropertyName()
                        expression = trackAstNode(MemberExpr(
                            obj = expression,
                            property = trackAstNode(Identifier(propertyToken.value, propertyToken.range)),
                            computed = false,
                            range = SourceRange(expression.range.start, propertyToken.range.end),
                        ))
                    }
                }

                match(TokenType.LBRACKET) -> {
                    val property = parseExpression()
                    val close = consume(TokenType.RBRACKET, "Expected ']' after member expression")
                    expression = trackAstNode(MemberExpr(
                        obj = expression,
                        property = property,
                        computed = true,
                        range = SourceRange(expression.range.start, close.range.end),
                    ))
                }

                check(TokenType.TEMPLATE) -> {
                    // 可选链尾部不能直接接标签模板（括号包裹后合法）
                    if (expression is OptionalChainExpr && expression.range !in parenthesizedRanges) {
                        throw error("Invalid tagged template on optional chain")
                    }
                    val templateToken = advance()
                    val template = parseTemplateLiteral(templateToken, tagged = true) as TemplateLiteral
                    expression = trackAstNode(TaggedTemplate(
                        tag = expression,
                        quasis = template.quasis,
                        rawQuasis = template.rawQuasis,
                        expressions = template.expressions,
                        range = SourceRange(expression.range.start, templateToken.range.end),
                    ))
                }

                else -> return expression
            }
        }
    }

    /** new 表达式。 */
    private fun parseNewExpression(): Expression {
        val start = consume(TokenType.NEW, "Expected 'new'").range.start

        // `import.source(...)` 是 CallExpression，不能作为 `new` 的构造器。
        if (check(TokenType.IDENTIFIER) && peek().value == "import" &&
            !peek().containsEscape && peek(1).type == TokenType.DOT &&
            peek(2).type == TokenType.IDENTIFIER && !peek(2).containsEscape &&
            peek(2).value == "source"
        ) {
            throw error("import.source() cannot be used with 'new'")
        }
        // new.target 元属性
        if (match(TokenType.DOT)) {
            val property = consume(TokenType.IDENTIFIER, "Expected 'target' after 'new.'")
            if (property.value != "target" || property.containsEscape) {
                throw error("Expected 'target' after 'new.'")
            }
            if (!newTargetAllowed) {
                throw error("new.target expression is not allowed here")
            }
            return trackAstNode(NewTargetExpr(SourceRange(start, property.range.end)))
        }

        var constructor: Expression =
            if (check(TokenType.NEW)) parseNewExpression() else parsePrimary()

        // new 的构造器可以带成员链，例如 new a.b.C()
        while (true) {
            when {
                match(TokenType.DOT) -> {
                    if (check(TokenType.PRIVATE_IDENTIFIER)) {
                        val privateToken = advance()
                        recordPrivateNameReference(privateToken.value, privateToken.range)
                        constructor = trackAstNode(MemberExpr(
                            constructor,
                            trackAstNode(PrivateIdentifier(privateToken.value, privateToken.range)),
                            false,
                            SourceRange(constructor.range.start, privateToken.range.end),
                        ))
                    } else {
                        val propertyToken = parsePropertyName()
                        constructor = trackAstNode(MemberExpr(
                            constructor,
                            trackAstNode(Identifier(propertyToken.value, propertyToken.range)),
                            false,
                            SourceRange(constructor.range.start, propertyToken.range.end),
                        ))
                    }
                }

                match(TokenType.LBRACKET) -> {
                    val property = parseExpression()
                    val close = consume(TokenType.RBRACKET, "Expected ']' after member expression")
                    constructor = trackAstNode(MemberExpr(
                        constructor,
                        property,
                        true,
                        SourceRange(constructor.range.start, close.range.end),
                    ))
                }

                // new tag`...`：标签模板属于 MemberExpression，构造发生在其结果上
                check(TokenType.TEMPLATE) -> {
                    val templateToken = advance()
                    val template = parseTemplateLiteral(templateToken, tagged = true) as TemplateLiteral
                    constructor = trackAstNode(TaggedTemplate(
                        tag = constructor,
                        quasis = template.quasis,
                        rawQuasis = template.rawQuasis,
                        expressions = template.expressions,
                        range = SourceRange(constructor.range.start, templateToken.range.end),
                    ))
                }

                else -> break
            }
        }
        if (constructor is SuperExpr) {
            throw error("'super' keyword unexpected here")
        }
        // `new import("m")`：import() 不是 MemberExpression，不能作为 new 的构造器。
        // 括号形式 `new (import("m"))` 与成员链 `new import("m").x` 是合法语法，
        // 运行期才会因不可构造抛 TypeError，因此只拦截未被括号包裹的裸 import()。
        if (constructor is ImportCallExpr && constructor.range !in parenthesizedRanges) {
            throw error("Cannot use new with import()")
        }

        if (match(TokenType.LPAREN)) {
            val arguments = parseArgumentsAfterOpen()
            return trackAstNode(NewExpr(
                constructor,
                arguments,
                SourceRange(start, previous().range.end),
            ))
        }
        // `new a?.b` 非法；`new a()?.b` 由外层可选链处理
        if (check(TokenType.QUESTION_DOT)) {
            throw error("Invalid optional chain from new expression")
        }
        return trackAstNode(NewExpr(constructor, emptyList(), SourceRange(start, constructor.range.end)))
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

                check(TokenType.PRIVATE_IDENTIFIER) -> {
                    val privateToken = advance()
                    recordPrivateNameReference(privateToken.value, privateToken.range)
                    operations.add(
                        OptionalChainMember(
                            property = trackAstNode(PrivateIdentifier(privateToken.value, privateToken.range)),
                            computed = false,
                            optional = true,
                        )
                    )
                }

                else -> {
                    val propertyToken = parsePropertyName()
                    operations.add(
                        OptionalChainMember(
                            property = trackAstNode(Identifier(propertyToken.value, propertyToken.range)),
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
                        if (check(TokenType.PRIVATE_IDENTIFIER)) {
                            val privateToken = advance()
                            recordPrivateNameReference(privateToken.value, privateToken.range)
                            operations.add(
                                OptionalChainMember(
                                    property = trackAstNode(PrivateIdentifier(privateToken.value, privateToken.range)),
                                    computed = false,
                                    optional = false,
                                )
                            )
                        } else {
                            val propertyToken = parsePropertyName()
                            operations.add(
                                OptionalChainMember(
                                    property = trackAstNode(Identifier(propertyToken.value, propertyToken.range)),
                                    computed = false,
                                    optional = false,
                                )
                            )
                        }
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
        return trackAstNode(OptionalChainExpr(
            base = base,
            operations = operations,
            range = SourceRange(base.range.start, previous().range.end),
        ))
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
                        trackAstNode(SpreadElement(argument, SourceRange(spreadStart, argument.range.end)))
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
                trackAstNode(NumberLiteral(parseNumberLiteral(token), token.range))
            }

            TokenType.BIGINT -> {
                advance()
                trackAstNode(BigIntLiteral(token.value, token.range))
            }

            TokenType.STRING -> {
                if (strict && token.legacyOctal) {
                    throw error("Octal escape sequences are not allowed in strict mode")
                }
                advance()
                trackAstNode(StringLiteral(token.value, token.range, containsEscape = token.containsEscape))
            }

            TokenType.TRUE -> {
                advance()
                trackAstNode(BooleanLiteral(true, token.range))
            }

            TokenType.FALSE -> {
                advance()
                trackAstNode(BooleanLiteral(false, token.range))
            }

            TokenType.NULL -> {
                advance()
                trackAstNode(NullLiteral(token.range))
            }

            TokenType.THIS -> {
                advance()
                trackAstNode(ThisExpr(token.range))
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
                trackAstNode(SuperExpr(token.range))
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
                trackAstNode(Identifier("let", token.range))
            }

            TokenType.PRIVATE_IDENTIFIER ->
                throw error("Unexpected private name")

            TokenType.IDENTIFIER -> {
                // 模块表达式层的 import 元属性与动态导入；脚本目标仅放行
                // import() / import.source()，静态 import/export 仍按保留字拒绝。
                if (token.value == "import" && !token.containsEscape) {
                    if (moduleContext) {
                        when (peek(1).type) {
                            TokenType.DOT -> return parseImportMetaExpression(token.range.start)
                            TokenType.LPAREN -> return parseImportCallExpression(token.range.start)
                            else -> Unit
                        }
                    } else if (peek(1).type == TokenType.LPAREN) {
                        // 动态 import() 在脚本目标中同样合法；普通静态 import
                        // 仍由脚本声明路径拒绝。
                        return parseImportCallExpression(token.range.start)
                    } else if (
                        peek(1).type == TokenType.DOT &&
                        peek(2).type == TokenType.IDENTIFIER &&
                        !peek(2).containsEscape &&
                        peek(2).value == "source" &&
                        peek(3).type == TokenType.LPAREN
                    ) {
                        return parseImportSourceCallExpression(token.range.start)
                    }
                }
                validateIdentifierToken(token)
                validateIdentifierName(token.value, strict)
                validateStaticBlockReferenceName(token.value)
                if (token.value == "async" && !token.containsEscape &&
                    peek(1).type == TokenType.FUNCTION && !peek(1).precededByNewline
                ) {
                    advance() // async
                    advance() // function
                    parseFunctionExpression(token.range.start, isAsync = true)
                } else {
                    advance()
                    trackAstNode(Identifier(token.value, token.range))
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
                trackAstNode(RegExpLiteral(token.value, token.regexFlags, token.range))
            }

            TokenType.TEMPLATE -> {
                advance()
                parseTemplateLiteral(token)
            }

            else -> throw error("Unexpected token '${token.value}'")
        }
    }

    /**
     * `import.meta` 元属性（`import` 已定位但尚未消费）。
     *
     * 语法要求 `import` 后必须紧跟 `.` 与无转义的 `meta`；`import.foo`、
     * `import[0]` 等形态都是早期错误。脚本目标不会走到这里（`import` 仍是保留字）。
     */
    private fun parseImportMetaExpression(start: SourcePosition): Expression {
        advance() // import
        consume(TokenType.DOT, "Expected '.' after 'import'")
        val meta = peek()
        if (meta.type == TokenType.IDENTIFIER && !meta.containsEscape &&
            meta.value == "defer" && peek(1).type == TokenType.LPAREN
        ) {
            advance() // defer
            return parseImportCallArguments(start, isDeferred = true)
        }
        if (meta.type == TokenType.IDENTIFIER && !meta.containsEscape &&
            meta.value == "source"
        ) {
            advance() // source
            if (!check(TokenType.LPAREN)) {
                throw ParseException("Expected '(' after 'import.source'", peek().range)
            }
            return parseImportCallArguments(start, isSource = true)
        }
        if (meta.type != TokenType.IDENTIFIER || meta.containsEscape || meta.value != "meta") {
            throw ParseException("Expected 'meta' after 'import.'", meta.range)
        }
        advance()
        return trackAstNode(ImportMetaExpr(SourceRange(start, meta.range.end)))
    }

    /**
     * 动态导入 `import(specifier[, options])`（`import` 已定位但尚未消费）。
     *
     * 规范要求 1..2 个实参；零参数在解析期直接拒绝。允许尾逗号；
     * 实参必须是 AssignmentExpression，因此 `...x` 展开是早期错误。
     */
    private fun parseImportCallExpression(start: SourcePosition): Expression {
        advance() // import
        return parseImportCallArguments(start, isDeferred = false)
    }

    /** 脚本目标中的 `import.source(...)`；语法与模块目标一致，仅允许 source 形式。 */
    private fun parseImportSourceCallExpression(start: SourcePosition): Expression {
        advance() // import
        consume(TokenType.DOT, "Expected '.' after 'import'")
        val source = consume(TokenType.IDENTIFIER, "Expected 'source' after 'import.'")
        if (source.value != "source" || source.containsEscape) {
            throw ParseException("Expected 'source' after 'import.'", source.range)
        }
        if (!check(TokenType.LPAREN)) {
            throw ParseException("Expected '(' after 'import.source'", peek().range)
        }
        return parseImportCallArguments(start, isSource = true)
    }

    private fun parseImportCallArguments(
        start: SourcePosition,
        isDeferred: Boolean = false,
        isSource: Boolean = false,
    ): Expression {
        consume(TokenType.LPAREN, "Expected '(' after 'import'")
        if (check(TokenType.RPAREN)) {
            throw error("import() requires a module specifier")
        }
        val arguments = mutableListOf<Expression>()
        if (isSource) {
            if (check(TokenType.ELLIPSIS)) {
                throw error("Spread arguments are not allowed in import.source()")
            }
            arguments.add(parseAssignment())
            if (match(TokenType.COMMA)) {
                throw error("import.source() accepts exactly 1 argument")
            }
        } else if (!check(TokenType.RPAREN)) {
            while (true) {
                if (check(TokenType.ELLIPSIS)) {
                    throw error("Spread arguments are not allowed in import()")
                }
                // 第三个实参从语法上就不属于 ImportCall 文法，在解析前给出位置准确的诊断
                if (arguments.size >= 2) {
                    throw error("import() accepts at most 2 arguments")
                }
                arguments.add(parseAssignment())
                if (!match(TokenType.COMMA)) break
                // 允许尾逗号：`import("m",)`
                if (check(TokenType.RPAREN)) break
            }
        }
        val close = consume(TokenType.RPAREN, "Expected ')' after import() arguments")
        return trackAstNode(
            ImportCallExpr(
                arguments = arguments,
                range = SourceRange(start, close.range.end),
                isDeferred = isDeferred,
                isSource = isSource,
            )
        )
    }

    /**
     * 模板字符串：解析 `${...}` 中的表达式源码。
     *
     * [tagged] 为 true 时允许非法转义（cooked=null）；普通模板在解析期报 SyntaxError。
     */
    private fun parseTemplateLiteral(token: Token, tagged: Boolean = false): Expression {
        val quasis = mutableListOf<String?>()
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
                        Lexer(
                            part.source,
                            maxNestingDepth = maxNestingDepth,
                            initialNestingDepth = part.nestingDepth,
                            // 模板表达式继承模块语境：模块中的 HTML 注释在子表达式里同样非法
                            moduleContext = moduleContext,
                        ).tokenize()
                    } catch (error: LexerException) {
                        throw ParseException(error.message ?: "Invalid template expression", error.range)
                    }
                    // 模板嵌入的是表达式，不能按 Program 解析（对象字面量会被当作块）；
                    // 生成器语境需要继承，${yield ...} 中的 yield 仍是关键字
                    val inheritedNodeCount = astNodeTotal()
                    val subParser = Parser(
                        subTokens,
                        strictContext = strict,
                        superPropertyContext = superPropertyAllowed,
                        superCallContext = superCallAllowed,
                        newTargetContext = newTargetAllowed,
                        argumentsForbiddenContext = argumentsForbidden,
                        evalPrivateNames = externalPrivateNames,
                        yieldContext = generatorContext,
                        maxNestingDepth = maxNestingDepth,
                        initialNestingDepth = part.nestingDepth,
                        maxAstNodes = maxAstNodes,
                        // 子表达式与整个脚本共享同一预算：把父解析器已创建的节点作为起点
                        initialAstNodeCount = inheritedNodeCount,
                        // 模板表达式继承模块语境（import.meta / import() / 顶层 await）
                        moduleContext = moduleContext,
                    )
                    subParser.inheritTemplateState(
                        asyncContext = asyncContext,
                        functionDepth = functionDepth,
                        classFieldInitializerDepth = classFieldInitializerDepth,
                        inFormalParameters = inFormalParameters,
                        awaitIdentifierForbidden = awaitIdentifierForbidden,
                    )
                    val subExpression = subParser.parseExpressionInput()
                    if (subParser.moduleHasTopLevelAwait) moduleHasTopLevelAwait = true
                    // 子解析器新增的节点计入父解析器，后续模板与节点继续共用剩余预算。
                    astNodeCount += subParser.astNodeTotal() - inheritedNodeCount
                    expressions.add(subExpression)
                }
            }
        }

        // 保证 quasis 数量为 expressions + 1
        while (quasis.size < expressions.size + 1) quasis.add("")
        while (rawQuasis.size < quasis.size) rawQuasis.add(quasis[rawQuasis.size] ?: "")
        if (!tagged && quasis.any { it == null }) {
            throw ParseException("Invalid escape sequence in template literal", token.range)
        }
        return trackAstNode(TemplateLiteral(quasis, rawQuasis, expressions, token.range))
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
                elements.add(trackAstNode(SpreadElement(argument, SourceRange(spreadStart, argument.range.end))))
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
        return trackAstNode(ArrayLiteral(elements, SourceRange(start, end), restFollowedByComma))
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
                properties.add(trackAstNode(ObjectSpreadProperty(argument, SourceRange(spreadStart, argument.range.end))))
                if (!match(TokenType.COMMA)) break
                restFollowedByComma = true
                if (check(TokenType.RBRACE)) break
                continue
            }
            val propertyStart = peek().range.start

            // async 方法检测：`async m() {}`、`async *m() {}`
            var isAsync = false
            if (isAsyncMethodStart()) {
                isAsync = true
                advance()
            }

            // 生成器方法检测：`*m() {}`。方法起始位置的 `*` 只可能是生成器标记，
            // 因此先消费 `*`，再由后面的“属性名 + (”决定是否为合法方法。
            var isGenerator = false
            if (check(TokenType.MULTIPLY)) {
                advance()
                isGenerator = true
            }

            // getter / setter 检测：`get x() {}`、`set x(v) {}`；
            // `*` 不能与访问器组合（`*get x() {}` 里的 get 是方法名）
            var kind = PropertyKind.INIT
            if (!isGenerator && peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
                (peek().value == "get" || peek().value == "set") &&
                canStartAccessorProperty(1)
            ) {
                kind = if (peek().value == "get") PropertyKind.GET else PropertyKind.SET
                advance()
            }

            val (key, computed) = parsePropertyKeyExpression()
            // 生成器方法必须紧跟参数列表：`{*a}`、`{*a: 1}` 都不是合法方法
            if (isGenerator && !check(TokenType.LPAREN)) {
                throw error("Expected '(' after generator method name")
            }

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
                        // 形参沿用函数自身的 [Yield] 语境：生成器方法是 [+Yield]，
                        // 普通方法/访问器是 [~Yield]（yield 仍可作为绑定名）
                        val parsedParams = withGeneratorContext(isGenerator) {
                            withAsyncContext(isAsync) { parseParametersAfterOpen() }
                        }
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
                            isGenerator = isGenerator,
                        )
                        parsedParams to parsedBody
                    }
                    // 对象方法使用 UniqueFormalParameters（即使 sloppy 也不允许重名）
                    validateFunctionParameters(params, bodyInfo.strict, isMethod = true)
                    // 计算键的方法名由运行时 SetFunctionName 推断
                    val name = if (!computed && key is Identifier) key.name else ""
                    trackAstNode(FunctionExpression(
                        name = name,
                        params = params,
                        body = bodyInfo.body,
                        strict = bodyInfo.strict,
                        isAsync = isAsync,
                        isGenerator = isGenerator,
                        varDeclarations = bodyInfo.varDeclarations,
                        functionDeclarations = bodyInfo.functionDeclarations,
                        range = SourceRange(key.range.start, bodyInfo.body.range.end),
                        isMethodDefinition = true,
                        sourceText = sourceSlice(
                            SourceRange(propertyStart, bodyInfo.body.range.end)
                        ),
                    ))
                }

                kind == PropertyKind.INIT && key is Identifier && !computed -> {
                    // 简写属性 { a } / 解构默认值 { a = 1 } 是对标识符的引用，
                    // 因此转义关键字（如 { bre\u0061k }）在此非法
                    lastPropertyKeyToken?.let { validateIdentifierToken(it) }
                    validateIdentifierName(key.name, strict)
                    validateStaticBlockReferenceName(key.name)
                    val identifier = trackAstNode(Identifier(key.name, key.range))
                    if (match(TokenType.ASSIGN)) {
                        if (patternSyntaxDepth == 0) {
                            throw error("Invalid shorthand property initializer")
                        }
                        patternDefaultsSeen++
                        val defaultValue = parseAssignment()
                        trackAstNode(AssignmentExpr(
                            target = identifier,
                            operator = "=",
                            value = defaultValue,
                            range = SourceRange(identifier.range.start, defaultValue.range.end),
                        ))
                    } else {
                        identifier
                    }
                }

                else -> throw error("Expected ':' after object key")
            }

            properties.add(
                trackAstNode(ObjectProperty(
                    key = key,
                    kind = kind,
                    value = value,
                    computed = computed,
                    range = SourceRange(propertyStart, value.range.end),
                    method = isMethodSyntax,
                ))
            )
            if (!match(TokenType.COMMA)) break
            if (check(TokenType.RBRACE)) break
        }

        val end = consume(TokenType.RBRACE, "Expected '}' after object literal").range.end
        val literalRange = SourceRange(start, end)
        if (hasDuplicateProto) duplicateProtoLiteralRanges.add(literalRange)
        return trackAstNode(ObjectLiteral(properties, literalRange, restFollowedByComma))
    }

    /** 判断 `get`/`set` 之后是否跟着一个可访问器属性名。 */
    /**
     * 对象字面量中的 `async` 是否开始异步方法。
     *
     * AsyncMethod 要求 `async` 与属性名之间无换行，且属性名后紧跟 `(`；
     * 计算属性名需要先匹配到对应的 `]`。`async *m() {}`（async 生成器方法）
     * 在 `async` 与属性名之间多一个 `*`，其余规则相同。
     */
    private fun isAsyncMethodStart(): Boolean {
        if (peek().type != TokenType.IDENTIFIER || peek().containsEscape ||
            peek().value != "async"
        ) {
            return false
        }
        val nameToken = peek(1)
        if (nameToken.precededByNewline) return false
        // async 生成器方法：`async` 后紧跟 `*`，属性名在 `*` 之后
        if (nameToken.type == TokenType.MULTIPLY) {
            return isPropertyKeyFollowedByParen(2)
        }
        return isPropertyKeyFollowedByParen(1)
    }

    /**
     * 判断从偏移 [offset] 开始是否为「属性名 (」形式。
     *
     * 计算属性名需要先匹配到对应的 `]`，再检查其后的 `(`。
     */
    private fun isPropertyKeyFollowedByParen(offset: Int): Boolean {
        if (!canStartAccessorProperty(offset)) return false
        var index = offset
        if (peek(index).type == TokenType.LBRACKET) {
            var depth = 0
            while (true) {
                when (peek(index).type) {
                    TokenType.LBRACKET -> depth++
                    TokenType.RBRACKET -> {
                        depth--
                        if (depth == 0) {
                            index++
                            break
                        }
                    }

                    TokenType.EOF -> return false
                    else -> Unit
                }
                index++
            }
        } else {
            index++
        }
        return peek(index).type == TokenType.LPAREN
    }

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
                trackAstNode(Identifier(token.value, token.range)) to false
            }

            token.type == TokenType.STRING -> {
                if (strict && token.legacyOctal) {
                    throw error("Octal escape sequences are not allowed in strict mode")
                }
                advance()
                trackAstNode(StringLiteral(token.value, token.range)) to false
            }

            token.type == TokenType.BIGINT -> {
                advance()
                trackAstNode(BigIntLiteral(token.value, token.range)) to false
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
                trackAstNode(NumberLiteral(parseNumberLiteral(token), token.range)) to false
            }

            token.type in keywordTokenTypes -> {
                advance()
                trackAstNode(StringLiteral(token.value, token.range)) to false
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
     * - 只有直接位于当前作用域语句列表中的函数声明提升到当前作用域；
     *   块/switch/catch 内的函数声明属于块级声明，由运行时在块入口实例化；
     * - sloppy 模式下块级函数同时在包围 var 作用域建立同名 var 绑定（Annex B.3.3）；
     * - 不进入嵌套函数体（嵌套函数有自己的作用域）。
     */
    private fun collectHoisted(
        statements: List<Statement>,
        strictMode: Boolean = strict,
    ): HoistInfo {
        val varNames = LinkedHashSet<String>()
        val functions = mutableListOf<FunctionDeclaration>()

        fun scan(statement: Statement, inBlockScope: Boolean) {
            when (statement) {
                is VarDeclaration -> {
                    if (statement.kind == DeclarationKind.VAR) {
                        statement.declarations.forEach { declaration ->
                            declaration.name?.let { varNames.add(it) }
                            declaration.pattern?.let { collectPatternNames(it, varNames) }
                        }
                    }
                }

                is FunctionDeclaration -> {
                    if (inBlockScope) {
                        // Annex B：sloppy 下块级普通函数在包围 var 作用域建立同名未初始化 var；
                        // async 函数声明始终是块级词法绑定；词法冲突时不建立 var 绑定
                        if (!strictMode && !statement.isAsync && statement.annexBVarEligible) {
                            varNames.add(statement.name)
                        }
                    } else {
                        functions.add(statement)
                    }
                }

                is BlockStatement -> statement.statements.forEach { scan(it, true) }
                is IfStatement -> {
                    // Annex B 的 if/else 分支函数声明按块级绑定处理
                    scan(statement.consequent, inBlockScope || statement.consequent is FunctionDeclaration)
                    statement.alternate?.let {
                        scan(it, inBlockScope || it is FunctionDeclaration)
                    }
                }

                is WhileStatement -> scan(statement.body, inBlockScope)
                is DoWhileStatement -> scan(statement.body, inBlockScope)
                is ForStatement -> {
                    statement.init?.let { scan(it, inBlockScope) }
                    scan(statement.body, inBlockScope)
                }

                is ForInStatement -> {
                    if (statement.declarationKind == DeclarationKind.VAR) {
                        statement.variableName?.let { varNames.add(it) }
                        statement.pattern?.let { collectPatternNames(it, varNames) }
                    }
                    scan(statement.body, inBlockScope)
                }

                is ForOfStatement -> {
                    if (statement.declarationKind == DeclarationKind.VAR) {
                        statement.variableName?.let { varNames.add(it) }
                        statement.pattern?.let { collectPatternNames(it, varNames) }
                    }
                    scan(statement.body, inBlockScope)
                }

                is LabeledStatement -> scan(statement.body, inBlockScope)
                is WithStatement -> scan(statement.body, inBlockScope)

                is SwitchStatement -> statement.cases.forEach { case ->
                    case.consequent.forEach { scan(it, true) }
                }

                is TryStatement -> {
                    // try/catch/finally 都是独立块作用域
                    scan(statement.block, true)
                    statement.handler?.let { scan(it.body, true) }
                    statement.finalizer?.let { scan(it, true) }
                }

                else -> Unit
            }
        }

        statements.forEach { scan(it, false) }
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
                TokenType.NUMBER -> if (token.legacyOctal) {
                    text.toLong(8).toDouble()
                } else {
                    text.toDouble()
                }
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

    // ================================================================
    // 模块目标（L4.1 原型：只解析，不接入执行）
    // ================================================================

    /**
     * 解析整个模块，返回 [ModuleProgram] 根节点。
     *
     * 与 [parseProgram] 的关系：
     * - 独立入口，脚本目标的行为完全不变（`import`/`export` 在脚本中仍然是保留字错误）；
     * - 模块恒为严格模式（ECMAScript 16.2.1.6），不依赖 `"use strict"` 指令；
     * - 顶层 `function` 声明在模块中属于**词法**声明（不是 var 声明），
     *   因此单独返回 `functionDeclarations`，不会混进 `varDeclarations`。
     *
     * 已实现的早期错误：导入绑定重复、导出名重复、`var` 与顶层词法声明重名、
     * 顶层函数重复声明、本地导出（`export {a}`）的绑定未声明、模块说明符的八进制转义；
     * `await` 在模块中作为保留字；模块顶层 `super` / `new.target` 被拒绝；
     * Annex B 的 sloppy 语法因模块恒为严格模式而全部禁用（HTML 注释需由
     * `Lexer(moduleContext = true)` 在词法阶段拒绝）。
     *
     * 表达式层（L4.1b）：`import.meta` 解析为 [ImportMetaExpr]，`import(...)` 解析为
     * [ImportCallExpr]；模块顶层 `await` 解析为 `UnaryOp("await", ...)` 并记录
     * [ModuleProgram.hasTopLevelAwait]；非 async 函数体内的 `await` 是语法错误。
     * 以上节点暂不参与求值，链接/执行语义属于 L4.2/L4.3。
     *
     * @param sourceInfo 错误栈显示来源；不参与语法/模块身份判定
     */
    fun parseModule(sourceInfo: SourceInfo = SourceInfo()): ModuleProgram {
        // 模块代码恒为严格模式：先置位，后续所有语句都按严格模式解析与校验
        strict = true
        // 置位模块语境：表达式层识别 import.meta / import()，并启用模块专属早期错误
        moduleContext = true
        moduleHasTopLevelAwait = false
        val start = peek().range.start
        val items = mutableListOf<ModuleItem>()
        val statements = mutableListOf<Statement>()
        val importEntries = mutableListOf<ImportEntry>()
        val exportEntries = mutableListOf<ExportEntry>()
        // 模块顶层词法名：import 绑定 + let/const/class + 顶层 function（含 export 包裹的）
        val lexicalNames = LinkedHashSet<String>()
        // 模块请求去重（源码顺序）；链接阶段按此顺序加载依赖
        val requestedModules = LinkedHashSet<String>()
        // 每个请求的导入属性；同一说明符带不同属性视为冲突（MVP 限制）
        val requestedAttributes = LinkedHashMap<String, Map<String, String>>()
        // 同一说明符可以同时有 eager 与 defer 两条边，不能只保留一个相位。
        val requestedPhases = LinkedHashMap<String, MutableSet<ImportPhase>>()
        val requestedEagerModules = LinkedHashSet<String>()
        val requestedDeferredModules = LinkedHashSet<String>()
        val requestedRequests = mutableListOf<ModuleRequestInfo>()
        val recordedRequestPhases = mutableSetOf<Pair<String, ImportPhase>>()
        fun recordRequest(
            specifier: String,
            attributes: Map<String, String>,
            phase: ImportPhase,
        ) {
            val existing = requestedAttributes[specifier]
            if (existing != null && existing != attributes) {
                throw ParseException(
                    "Conflicting import attributes for module '$specifier'",
                    peek().range,
                )
            }
            requestedAttributes[specifier] = attributes
            requestedModules.add(specifier)
            requestedPhases.getOrPut(specifier) { LinkedHashSet() }.add(phase)
            when (phase) {
                ImportPhase.EAGER -> requestedEagerModules.add(specifier)
                ImportPhase.DEFER -> requestedDeferredModules.add(specifier)
                ImportPhase.SOURCE -> Unit
            }
            if (recordedRequestPhases.add(specifier to phase)) {
                requestedRequests.add(ModuleRequestInfo(specifier, phase))
            }
        }
        // 导出名去重：同一个导出名只能出现一次（含 default）
        val exportedNames = mutableSetOf<String>()

        pushDeclScope(DeclScopeKind.PROGRAM)
        // 保留程序作用域引用：模块顶层词法名（含未 export 的 let/const/class/using）
        // 与 var 名由 declareLexicalBinding / declareVarBinding 直接登记在这里，
        // popDeclScope 之后仍需读取它们做导出绑定校验。
        val moduleScope = declScopes.last()
        // 模块顶层是 [+Await] 语境：允许 for await / await using 与顶层 await 表达式；
        // 同时允许 using 声明（脚本顶层不允许，只有块/函数体/模块顶层允许）。
        val outerAsyncContext = asyncContext
        val outerUsingDeclarationAllowed = usingDeclarationAllowed
        asyncContext = true
        usingDeclarationAllowed = true
        try {
            while (!isAtEnd()) {
                val result = if (isModuleDeclarationStart()) {
                    parseModuleDeclaration()
                } else {
                    // import() / import.meta 由表达式层解析；其余 import/export 用法
                    // 在 parseStatement 内按保留字或声明语法报错
                    ModuleParseResult(StatementItem(parseStatement()))
                }
                items.add(result.item)
                // statements 只保留"去掉 import/export 外壳"的语句，供提升与早期错误使用
                when (val item = result.item) {
                    is StatementItem -> statements.add(item.statement)
                    is ExportNamedDeclaration -> item.declaration?.let { statements.add(it) }
                    is ExportDefaultDeclaration -> item.declaration?.let { statements.add(it) }
                    is ImportDeclaration, is ExportAllDeclaration -> Unit
                }
                importEntries.addAll(
                    result.imports.map {
                        ImportEntry(
                            it.moduleRequest,
                            it.importName,
                            it.localName,
                            result.item.range,
                            it.attributes,
                            it.phase,
                        )
                    }
                )
                for (entry in result.exports) {
                    // export * 不产生具名导出（ExportName 为 null），但仍要进入导出表，
                    // 由链接阶段做星号解析
                    val exportName = entry.exportName
                    if (exportName != null && !exportedNames.add(exportName)) {
                        throw ParseException(
                            "Duplicate export of '$exportName'",
                            result.item.range,
                        )
                    }
                    exportEntries.add(
                        ExportEntry(
                            entry.exportName,
                            entry.moduleRequest,
                            entry.importName,
                            entry.localName,
                            result.item.range,
                            entry.attributes,
                        )
                    )
                }
                lexicalNames.addAll(result.lexicalNames)
                for (entry in result.imports) {
                    recordRequest(entry.moduleRequest, result.attributes, entry.phase)
                }
                for (entry in result.exports) {
                    entry.moduleRequest?.let {
                        recordRequest(it, result.attributes, ImportPhase.EAGER)
                    }
                }
                for (specifier in result.moduleRequests) {
                    recordRequest(specifier, result.attributes, result.phase)
                }
            }
        } finally {
            asyncContext = outerAsyncContext
            usingDeclarationAllowed = outerUsingDeclarationAllowed
            popDeclScope()
        }
        checkDeferredProtoDuplicates()

        val hoist = collectHoisted(statements)
        // 模块顶层 function 是词法声明：未被 export 包裹的顶层函数同样要计入词法名，
        // 供 var 冲突检查与模块声明实例化（ModuleDeclarationInstantiation）使用。
        // 重复的顶层函数名在脚本里合法（var 语义），在模块里属于词法重名早期错误。
        val topLevelFunctionNames = mutableSetOf<String>()
        for (declaration in hoist.functionDeclarations) {
            if (!topLevelFunctionNames.add(declaration.name)) {
                throw ParseException(
                    "Identifier '${declaration.name}' has already been declared",
                    declaration.range,
                )
            }
            lexicalNames.add(declaration.name)
        }
        // 程序作用域已登记全部顶层词法绑定（包括未 export 的 let/const/class/using），
        // 合并进词法名集合，供 var 冲突检查与 lexicallyDeclaredNames 使用。
        lexicalNames.addAll(moduleScope.lexicalNames)
        // 模块顶层 function 是词法声明，因此 var 与任意顶层词法名重名都是早期错误。
        // 脚本里 `var f; function f(){}` 合法，但在模块里 `function f` 已经是词法绑定。
        for (name in hoist.varDeclarations) {
            if (name in lexicalNames) {
                throw ParseException("Identifier '$name' has already been declared", peek().range)
            }
        }
        // 本地导出（`export {a}`）的绑定名必须在模块顶层声明过：
        // 导入绑定、let/const/class、顶层 function 与 var 都算已声明；
        // 间接导出（`export {a} from "m"`）与 `export *` 不要求本地绑定。
        // 该检查必须等整个模块解析完再做，因为声明可以出现在导出语句之后。
        val declaredNames = lexicalNames + moduleScope.varNames
        for (item in items) {
            if (item !is ExportNamedDeclaration || item.source != null) continue
            for (specifier in item.specifiers) {
                if (specifier.localName !in declaredNames) {
                    throw ParseException(
                        "Exported binding '${specifier.localName}' is not declared",
                        specifier.range,
                    )
                }
            }
        }
        val end = tokens.lastOrNull()?.range?.end ?: start
        return ModuleProgram(
            items = items,
            statements = statements,
            varDeclarations = hoist.varDeclarations,
            functionDeclarations = hoist.functionDeclarations,
            lexicallyDeclaredNames = lexicalNames.toList(),
            importEntries = importEntries,
            exportEntries = exportEntries,
            requestedModules = requestedModules.toList(),
            hasUsingDeclaration = statements.any { it is UsingDeclaration },
            range = SourceRange(start, end),
            bindings = collectModuleBindings(items),
            hasTopLevelAwait = moduleHasTopLevelAwait,
            sourceInfo = sourceInfo,
            requestedAttributes = requestedAttributes.toMap(),
            requestedPhases = requestedPhases.mapValues { it.value.toSet() },
            requestedEagerModules = requestedEagerModules.toList(),
            requestedDeferredModules = requestedDeferredModules.toList(),
            requestedRequests = requestedRequests.toList(),
        )
    }

    /** 汇总模块顶层绑定，供 ModuleDeclarationInstantiation 直接使用。 */
    private fun collectModuleBindings(items: List<ModuleItem>): List<ModuleBinding> {
        val bindings = mutableListOf<ModuleBinding>()
        fun add(kind: ModuleBindingKind, name: String, range: SourceRange, imported: ImportedBinding? = null) {
            bindings.add(ModuleBinding(kind, name, imported, range))
        }
        fun addStatement(statement: Statement) {
            when (statement) {
                is VarDeclaration -> {
                    val kind = when (statement.kind) {
                        DeclarationKind.VAR -> ModuleBindingKind.VAR
                        DeclarationKind.LET -> ModuleBindingKind.LET
                        DeclarationKind.CONST -> ModuleBindingKind.CONST
                    }
                    val names = LinkedHashSet<String>()
                    for (declarator in statement.declarations) {
                        val pattern = declarator.pattern
                        if (pattern != null) {
                            collectPatternNames(pattern, names)
                        } else {
                            declarator.name?.let { names.add(it) }
                        }
                    }
                    for (name in names) add(kind, name, statement.range)
                }

                is FunctionDeclaration -> add(ModuleBindingKind.FUNCTION, statement.name, statement.range)
                is ClassDeclaration -> add(ModuleBindingKind.CLASS, statement.name, statement.range)
                is UsingDeclaration -> {
                    for (declarator in statement.declarations) {
                        add(ModuleBindingKind.USING, declarator.name, declarator.range)
                    }
                }

                else -> Unit
            }
        }

        for (item in items) {
            when (item) {
                is ImportDeclaration -> {
                    for (specifier in item.specifiers) {
                        add(
                            ModuleBindingKind.IMPORT,
                            specifier.localName,
                            specifier.range,
                            ImportedBinding(item.moduleRequest, specifier.importedName),
                        )
                    }
                    item.namespaceImport?.let { localName ->
                        add(
                            ModuleBindingKind.IMPORT,
                            localName,
                            item.range,
                            ImportedBinding(item.moduleRequest, "*"),
                        )
                    }
                }

                is ExportNamedDeclaration -> item.declaration?.let(::addStatement)
                is ExportDefaultDeclaration -> {
                    val declaration = item.declaration
                    if (declaration != null) {
                        addStatement(declaration)
                    } else {
                        add(ModuleBindingKind.DEFAULT, "*default*", item.range)
                    }
                }

                is StatementItem -> addStatement(item.statement)
                is ExportAllDeclaration -> Unit
            }
        }
        return bindings
    }

    /** 模块声明解析结果：模块项本身，以及它贡献的导入/导出表项与顶层词法名。 */
    private class ModuleParseResult(
        val item: ModuleItem,
        val imports: List<ImportEntry> = emptyList(),
        val exports: List<ExportEntry> = emptyList(),
        val lexicalNames: List<String> = emptyList(),
        /** 本声明贡献的模块请求；即使是空子句也必须保留。 */
        val moduleRequests: List<String> = emptyList(),
        /** 本声明的 `with { ... }` 属性；仅静态 import/export 声明非空。 */
        val attributes: Map<String, String> = emptyMap(),
        /** 本声明请求的相位；`import defer` 为 DEFER，其余为 EAGER。 */
        val phase: ImportPhase = ImportPhase.EAGER,
    )

    /**
     * 当前是否位于模块声明（`import` / `export`）的起点。
     *
     * `import` / `export` 在 Lexer 里不是关键字（没有对应 [TokenType]），
     * 而是由 Parser 按保留字拒绝；这里用同样的方式识别，避免改动词法层。
     * `import(` 与 `import.` 是表达式层语法（[parseImportCallExpression] /
     * [parseImportMetaExpression]），不算声明起点。
     */
    private fun isModuleDeclarationStart(): Boolean {
        val token = peek()
        if (token.type != TokenType.IDENTIFIER || token.containsEscape) return false
        return when (token.value) {
            "export" -> true
            "import" -> peek(1).type != TokenType.LPAREN && peek(1).type != TokenType.DOT
            else -> false
        }
    }

    /** 解析一个模块声明（`import` 或 `export`）。 */
    private fun parseModuleDeclaration(): ModuleParseResult {
        val token = peek()
        return when (token.value) {
            "import" -> parseImportDeclaration()
            "export" -> parseExportDeclaration()
            else -> throw error("Expected 'import' or 'export'")
        }
    }

    /** `as` 关键字（作为标识符上下文关键字出现）。 */
    private fun isAsKeyword(offset: Int = 0): Boolean {
        val token = peek(offset)
        return token.type == TokenType.IDENTIFIER && !token.containsEscape && token.value == "as"
    }

    /** `from` 关键字。 */
    private fun consumeFrom(): Token {
        val token = peek()
        if (token.type != TokenType.IDENTIFIER || token.containsEscape || token.value != "from") {
            throw error("Expected 'from'")
        }
        return advance()
    }

    /**
     * 模块说明符：必须是字符串字面量。
     *
     * 模块恒为严格模式，八进制转义（`import "a\01"`）是语法错误。
     */
    private fun parseModuleSpecifier(): String {
        val token = consume(TokenType.STRING, "Expected module specifier string")
        if (token.legacyOctal) {
            throw error("Octal escape sequences are not allowed in module specifiers")
        }
        return token.value
    }

    /**
     * `with { key: "value", ... }` 导入属性子句。
     *
     * 没有 `with` 时返回空表。键是 IdentifierName 或字符串字面量，值必须是字符串字面量；
     * 重复键是早期错误。属性不参与模块身份，由 Provider 决定如何解释。
     */
    private fun parseImportAttributes(): Map<String, String> {
        if (!check(TokenType.WITH)) return emptyMap()
        advance()
        consume(TokenType.LBRACE, "Expected '{' after 'with'")
        val attributes = LinkedHashMap<String, String>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            val keyToken = peek()
            val key = when {
                keyToken.type == TokenType.STRING -> {
                    if (keyToken.legacyOctal) {
                        throw error("Octal escape sequences are not allowed in import attribute keys")
                    }
                    if (!isWellFormedUnicode(keyToken.value)) {
                        throw ParseException(
                            "Import attribute key must be well-formed Unicode",
                            keyToken.range,
                        )
                    }
                    advance()
                    keyToken.value
                }

                keyToken.type == TokenType.IDENTIFIER || keyToken.type in keywordTokenTypes -> {
                    advance()
                    keyToken.value
                }

                else -> throw ParseException("Expected import attribute key", keyToken.range)
            }
            consume(TokenType.COLON, "Expected ':' after import attribute key")
            val valueToken = consume(TokenType.STRING, "Expected string value for import attribute '$key'")
            if (valueToken.legacyOctal) {
                throw error("Octal escape sequences are not allowed in import attribute values")
            }
            if (!isWellFormedUnicode(valueToken.value)) {
                throw ParseException(
                    "Import attribute value must be well-formed Unicode",
                    valueToken.range,
                )
            }
            if (attributes.containsKey(key)) {
                throw ParseException("Duplicate import attribute '$key'", keyToken.range)
            }
            attributes[key] = valueToken.value
            if (!match(TokenType.COMMA)) break
        }
        consume(TokenType.RBRACE, "Expected '}' after import attributes")
        return attributes
    }

    /**
     * 导入/导出名（ModuleExportName）：IdentifierName 或字符串字面量。
     *
     * 字符串形式对应 ES2022 的任意模块命名空间名（`export { x as "a-b" }`），
     * 这里直接取其解码后的值。
     */
    private fun consumeModuleExportName(message: String): Token {
        val token = peek()
        if (token.type == TokenType.IDENTIFIER || token.type in keywordTokenTypes) {
            return advance()
        }
        if (token.type == TokenType.STRING) {
            if (token.legacyOctal) {
                throw error("Octal escape sequences are not allowed in export names")
            }
            // ModuleExportName 的 StringLiteral 必须满足 IsStringWellFormedUnicode
            if (!isWellFormedUnicode(token.value)) {
                throw ParseException(
                    "Module export name must be well-formed Unicode",
                    token.range,
                )
            }
            return advance()
        }
        throw ParseException("$message (Token: ${token.value})", token.range)
    }

    /** 字符串是否由合法 UTF-16 序列组成（不允许孤立代理项）。 */
    private fun isWellFormedUnicode(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char.isHighSurrogate() -> {
                    if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) {
                        return false
                    }
                    index += 2
                }

                char.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }

    /** `import` 声明：副作用、默认、具名、命名空间四种形态及其组合。 */
    private fun parseImportDeclaration(): ModuleParseResult {
        val start = advance().range.start // import
        // `import defer * as ns`：只有 `defer` 后紧跟 `*` 才是 defer 语法；
        // `import defer from "m"` 仍是名为 defer 的默认导入。
        val isDeferSyntax = peek().type == TokenType.IDENTIFIER &&
            !peek().containsEscape &&
            peek().value == "defer" &&
            peek(1).type == TokenType.MULTIPLY
        // `import source x from "m"`；注意 `import source from "m"` 仍是名为
        // source 的默认导入，因此必须看到第二个 from 才能判定为 source phase。
        val isSourceSyntax = peek().type == TokenType.IDENTIFIER &&
            !peek().containsEscape &&
            peek().value == "source" &&
            peek(1).type == TokenType.IDENTIFIER &&
            peek(2).type == TokenType.IDENTIFIER &&
            !peek(2).containsEscape &&
            peek(2).value == "from"
        var phase = ImportPhase.EAGER
        if (isDeferSyntax) {
            advance() // defer
            phase = ImportPhase.DEFER
        } else if (isSourceSyntax) {
            advance() // source
            val localToken = consumeBindingIdentifier("Expected source import binding name")
            validateIdentifierName(localToken.value, strict)
            declareLexicalBinding(localToken.value)
            return finishImportDeclaration(
                start = start,
                specifiers = listOf(
                    ImportSpecifier("source", localToken.value, localToken.range)
                ),
                namespaceImport = null,
                names = listOf("source" to localToken.value),
                lexicalNames = listOf(localToken.value),
                phase = ImportPhase.SOURCE,
            )
        }
        if (phase == ImportPhase.DEFER && !check(TokenType.MULTIPLY)) {
            throw error("Expected '*' after 'import defer'")
        }
        // 纯副作用导入：import "m";
        if (check(TokenType.STRING)) {
            val moduleRequest = parseModuleSpecifier()
            val attributes = parseImportAttributes()
            consumeSemicolon()
            return ModuleParseResult(
                item = ImportDeclaration(
                    moduleRequest = moduleRequest,
                    specifiers = emptyList(),
                    namespaceImport = null,
                    isSideEffectOnly = true,
                    range = SourceRange(start, previous().range.end),
                    attributes = attributes,
                ),
                // 规范里副作用导入仍然产生 ImportEntry：ImportName 为 all、LocalName 为空；
                // 这里用 "*" / "" 表示该哨兵值，模块求值器只据此触发依赖求值。
                imports = listOf(
                    ImportEntry(
                        moduleRequest,
                        "*",
                        "",
                        attributes = attributes,
                        phase = ImportPhase.EAGER,
                    )
                ),
                moduleRequests = listOf(moduleRequest),
                attributes = attributes,
            )
        }

        val specifiers = mutableListOf<ImportSpecifier>()
        val names = mutableListOf<Pair<String, String>>() // importName to localName
        val lexical = mutableListOf<String>()
        var namespaceImport: String? = null

        // 默认导入：import d ...（后面可能跟 `,` 再接具名/命名空间导入）
        if (!check(TokenType.LBRACE) && !check(TokenType.MULTIPLY)) {
            val defaultToken = consumeBindingIdentifier("Expected default import name")
            validateIdentifierName(defaultToken.value, strict)
            declareLexicalBinding(defaultToken.value)
            specifiers.add(ImportSpecifier("default", defaultToken.value, defaultToken.range))
            names.add("default" to defaultToken.value)
            lexical.add(defaultToken.value)
            if (!match(TokenType.COMMA)) {
                return finishImportDeclaration(start, specifiers, namespaceImport, names, lexical, phase)
            }
        }

        if (match(TokenType.MULTIPLY)) {
            // import * as ns from "m"
            if (!isAsKeyword()) throw error("Expected 'as' after '*'")
            advance()
            val namespaceToken = consumeBindingIdentifier("Expected namespace import name")
            validateIdentifierName(namespaceToken.value, strict)
            declareLexicalBinding(namespaceToken.value)
            namespaceImport = namespaceToken.value
            names.add("*" to namespaceToken.value)
            lexical.add(namespaceToken.value)
        } else {
            // import {a, b as c} from "m"
            consume(TokenType.LBRACE, "Expected '{' or '*' in import declaration")
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                val importedToken = consumeModuleExportName("Expected imported name")
                val importedName = importedToken.value
                // 没有 `as` 时导入名同时是本地绑定名，因此必须是合法绑定标识符
                val localToken = if (isAsKeyword()) {
                    advance()
                    consumeBindingIdentifier("Expected import binding name")
                } else {
                    if (importedToken.type != TokenType.IDENTIFIER) {
                        throw ParseException(
                            "Expected import binding name (Token: $importedName)",
                            importedToken.range,
                        )
                    }
                    importedToken
                }
                // 无 `as` 时导入名同时是本地绑定名：复用标识符校验（模块中 await 为保留字等）
                validateIdentifierToken(localToken)
                validateIdentifierName(localToken.value, strict)
                declareLexicalBinding(localToken.value)
                specifiers.add(
                    ImportSpecifier(importedName, localToken.value, importedToken.range)
                )
                names.add(importedName to localToken.value)
                lexical.add(localToken.value)
                if (!match(TokenType.COMMA)) break
            }
            consume(TokenType.RBRACE, "Expected '}' after import list")
        }
        return finishImportDeclaration(start, specifiers, namespaceImport, names, lexical, phase)
    }

    /** 解析 import 声明尾部的 `from "m" ;` 并组装结果。 */
    private fun finishImportDeclaration(
        start: SourcePosition,
        specifiers: List<ImportSpecifier>,
        namespaceImport: String?,
        names: List<Pair<String, String>>,
        lexicalNames: List<String>,
        phase: ImportPhase,
    ): ModuleParseResult {
        consumeFrom()
        val moduleRequest = parseModuleSpecifier()
        val attributes = parseImportAttributes()
        consumeSemicolon()
        return ModuleParseResult(
            item = ImportDeclaration(
                moduleRequest = moduleRequest,
                specifiers = specifiers,
                namespaceImport = namespaceImport,
                isSideEffectOnly = false,
                range = SourceRange(start, previous().range.end),
                attributes = attributes,
                phase = phase,
            ),
            imports = names.map { (importName, localName) ->
                ImportEntry(
                    moduleRequest,
                    importName,
                    localName,
                    attributes = attributes,
                    phase = phase,
                )
            },
            lexicalNames = lexicalNames,
            moduleRequests = listOf(moduleRequest),
            attributes = attributes,
            phase = phase,
        )
    }

    /** `export` 声明：default、`*`、具名子句、内联声明四种形态。 */
    private fun parseExportDeclaration(): ModuleParseResult {
        val start = advance().range.start // export
        return when {
            check(TokenType.DEFAULT) -> parseExportDefault(start)
            check(TokenType.MULTIPLY) -> parseExportAll(start)
            check(TokenType.LBRACE) -> parseExportClause(start)
            isInlineExportDeclarationStart() -> parseInlineExportDeclaration(start)
            else -> throw error("Expected declaration or statement after 'export'")
        }
    }

    /** 是否为 `export` 后直接跟声明（var/let/const/function/class/async function）的形态。 */
    private fun isInlineExportDeclarationStart(): Boolean {
        val token = peek()
        return when (token.type) {
            TokenType.VAR, TokenType.LET, TokenType.CONST,
            TokenType.FUNCTION, TokenType.CLASS -> true

            else -> token.type == TokenType.IDENTIFIER && !token.containsEscape &&
                token.value == "async" && peek(1).type == TokenType.FUNCTION &&
                !peek(1).precededByNewline
        }
    }

    /** `export var/let/const/function/class ...`：内联声明的导出名等于绑定名。 */
    private fun parseInlineExportDeclaration(start: SourcePosition): ModuleParseResult {
        val declaration: Statement = when {
            check(TokenType.VAR) || check(TokenType.LET) || check(TokenType.CONST) ->
                parseVarDeclaration()

            check(TokenType.FUNCTION) -> parseFunctionDeclaration()
            check(TokenType.CLASS) -> parseClassDeclaration()
            else -> parseFunctionDeclaration(isAsync = true)
        }
        val exports = mutableListOf<ExportEntry>()
        val lexical = mutableListOf<String>()
        when (declaration) {
            is VarDeclaration -> {
                val names = LinkedHashSet<String>()
                for (declarator in declaration.declarations) {
                    val pattern = declarator.pattern
                    if (pattern != null) {
                        collectPatternNames(pattern, names)
                    } else {
                        declarator.name?.let { names.add(it) }
                    }
                }
                for (name in names) {
                    exports.add(ExportEntry(name, null, null, name))
                    // 只有 let/const 是词法声明；export var 仍属于模块 var 作用域
                    if (declaration.kind != DeclarationKind.VAR) lexical.add(name)
                }
            }

            is FunctionDeclaration -> {
                // 模块顶层 function 是词法声明
                exports.add(ExportEntry(declaration.name, null, null, declaration.name))
                lexical.add(declaration.name)
            }

            is ClassDeclaration -> {
                exports.add(ExportEntry(declaration.name, null, null, declaration.name))
                lexical.add(declaration.name)
            }

            else -> Unit
        }
        return ModuleParseResult(
            item = ExportNamedDeclaration(
                declaration = declaration,
                specifiers = emptyList(),
                source = null,
                range = SourceRange(start, previous().range.end),
            ),
            exports = exports,
            lexicalNames = lexical,
        )
    }

    /** `export {a as b}` 与 `export {a as b} from "m"`。 */
    private fun parseExportClause(start: SourcePosition): ModuleParseResult {
        consume(TokenType.LBRACE, "Expected '{' in export clause")
        val specifiers = mutableListOf<ExportSpecifier>()
        // 无 from 时字符串不能作为本地绑定名（ExportClause 的早期错误）
        var stringLocalNameRange: SourceRange? = null
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            val localToken = consumeModuleExportName("Expected export name")
            if (localToken.type == TokenType.STRING && stringLocalNameRange == null) {
                stringLocalNameRange = localToken.range
            }
            var exportName = localToken.value
            if (isAsKeyword()) {
                advance()
                exportName = consumeModuleExportName("Expected exported name").value
            }
            specifiers.add(ExportSpecifier(localToken.value, exportName, localToken.range))
            if (!match(TokenType.COMMA)) break
        }
        consume(TokenType.RBRACE, "Expected '}' after export list")
        var source: String? = null
        val fromToken = peek()
        if (fromToken.type == TokenType.IDENTIFIER && !fromToken.containsEscape &&
            fromToken.value == "from"
        ) {
            advance()
            source = parseModuleSpecifier()
        }
        if (source == null) {
            stringLocalNameRange?.let {
                throw ParseException(
                    "String literal module export names must be followed by a 'from' clause",
                    it,
                )
            }
            if (check(TokenType.WITH)) {
                throw error("Import attributes require a 'from' clause")
            }
        }
        val attributes = if (source != null) parseImportAttributes() else emptyMap()
        consumeSemicolon()
        val moduleRequest = source
        val exports = specifiers.map { specifier ->
            if (moduleRequest != null) {
                // 间接导出：左侧是导入名，没有本地绑定
                ExportEntry(
                    specifier.exportName,
                    moduleRequest,
                    specifier.localName,
                    null,
                    attributes = attributes,
                )
            } else {
                ExportEntry(specifier.exportName, null, null, specifier.localName)
            }
        }
        return ModuleParseResult(
            item = ExportNamedDeclaration(
                declaration = null,
                specifiers = specifiers,
                source = source,
                range = SourceRange(start, previous().range.end),
                attributes = attributes,
            ),
            exports = exports,
            moduleRequests = source?.let { listOf(it) } ?: emptyList(),
            attributes = attributes,
        )
    }

    /** `export * from "m"` 与 `export * as ns from "m"`。 */
    private fun parseExportAll(start: SourcePosition): ModuleParseResult {
        consume(TokenType.MULTIPLY, "Expected '*' after 'export'")
        var namespaceName: String? = null
        if (isAsKeyword()) {
            advance()
            namespaceName = consumeModuleExportName("Expected namespace export name").value
        }
        consumeFrom()
        val moduleRequest = parseModuleSpecifier()
        val attributes = parseImportAttributes()
        consumeSemicolon()
        // 普通 export * 不产生具名导出（ExportName 为 null），由链接阶段做星号解析
        val export = if (namespaceName != null) {
            ExportEntry(namespaceName, moduleRequest, "*", null, attributes = attributes)
        } else {
            ExportEntry(null, moduleRequest, "*", null, attributes = attributes)
        }
        return ModuleParseResult(
            item = ExportAllDeclaration(
                source = moduleRequest,
                namespaceName = namespaceName,
                range = SourceRange(start, previous().range.end),
                attributes = attributes,
            ),
            exports = listOf(export),
            moduleRequests = listOf(moduleRequest),
            attributes = attributes,
        )
    }

    /**
     * `export default ...`。
     *
     * 具名函数/类声明按"声明 + 导出该名字"处理；匿名函数/类与普通表达式按
     * "求值点创建并绑定 `*default*`"处理（规范 16.2.1.5 的 ExportEntry 形态）。
     */
    private fun parseExportDefault(start: SourcePosition): ModuleParseResult {
        advance() // default
        val isAsyncFunction = peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
            peek().value == "async" && peek(1).type == TokenType.FUNCTION &&
            !peek(1).precededByNewline
        if (check(TokenType.FUNCTION) || isAsyncFunction) {
            if (isNamedFunctionAfterFunctionKeyword()) {
                val declaration = parseFunctionDeclaration(isAsync = isAsyncFunction)
                return ModuleParseResult(
                    item = ExportDefaultDeclaration(
                        declaration = declaration,
                        expression = null,
                        range = SourceRange(start, previous().range.end),
                    ),
                    exports = listOf(
                        ExportEntry("default", null, null, declaration.name)
                    ),
                    lexicalNames = listOf(declaration.name),
                )
            }
            // 匿名函数：按函数表达式解析（parseFunctionExpression 要求 function 已消费），
            // 绑定在模块求值点建立
            val functionStart = peek().range.start
            if (isAsyncFunction) advance() // async
            consume(TokenType.FUNCTION, "Expected 'function'")
            val expression = parseFunctionExpression(
                startPos = functionStart,
                isAsync = isAsyncFunction,
            )
            // 匿名函数是 HoistableDeclaration：按 `*default*` 函数声明实例化，
            // 不能在求值点才创建，否则自导入/前置调用会读到 TDZ。
            val declaration = FunctionDeclaration(
                name = "*default*",
                params = expression.params,
                body = expression.body,
                strict = expression.strict,
                isAsync = expression.isAsync,
                isGenerator = expression.isGenerator,
                varDeclarations = expression.varDeclarations,
                functionDeclarations = expression.functionDeclarations,
                range = expression.range,
                sourceText = expression.sourceText,
            )
            return ModuleParseResult(
                item = ExportDefaultDeclaration(
                    declaration = declaration,
                    expression = null,
                    range = SourceRange(start, previous().range.end),
                ),
                exports = listOf(ExportEntry("default", null, null, "*default*")),
                lexicalNames = listOf("*default*"),
            )
        }
        if (check(TokenType.CLASS)) {
            if (peek(1).type == TokenType.IDENTIFIER) {
                val declaration = parseClassDeclaration()
                return ModuleParseResult(
                    item = ExportDefaultDeclaration(
                        declaration = declaration,
                        expression = null,
                        range = SourceRange(start, previous().range.end),
                    ),
                    exports = listOf(
                        ExportEntry("default", null, null, declaration.name)
                    ),
                    lexicalNames = listOf(declaration.name),
                )
            }
            // 匿名类：`export default class {}` / `export default class extends B {}`
            // （parseClassExpression 自己消费 class 关键字与可选的类名）
            val expression = parseClassExpression()
            // ClassDeclaration 形式同样不要求分号
            return anonymousDefaultDeclaration(start, expression)
        }
        val expression = parseAssignment()
        consumeSemicolon()
        return anonymousDefaultDeclaration(start, expression)
    }

    /** 匿名默认导出：`*default*` 在模块求值点初始化。 */
    private fun anonymousDefaultDeclaration(
        start: SourcePosition,
        expression: Expression,
    ): ModuleParseResult = ModuleParseResult(
        item = ExportDefaultDeclaration(
            declaration = null,
            expression = expression,
            range = SourceRange(start, previous().range.end),
        ),
        exports = listOf(ExportEntry("default", null, null, "*default*")),
    )

    /**
     * `export default function` 之后是否为具名声明。
     *
     * 当前 token 可能是 `function` 或 `async`；跳过 `function` 与可选的 `*` 后，
     * 只有标识符才是具名声明，`(` 表示匿名。
     */
    private fun isNamedFunctionAfterFunctionKeyword(): Boolean {
        var index = 0
        if (peek().type == TokenType.IDENTIFIER && !peek().containsEscape &&
            peek().value == "async" && peek(1).type == TokenType.FUNCTION &&
            !peek(1).precededByNewline
        ) {
            index++
        }
        if (peek(index).type != TokenType.FUNCTION) return false
        index++
        if (peek(index).type == TokenType.MULTIPLY) index++
        return peek(index).type == TokenType.IDENTIFIER
    }
}
