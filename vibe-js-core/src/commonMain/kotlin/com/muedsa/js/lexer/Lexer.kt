package com.muedsa.js.lexer

import com.muedsa.js.runtime.regex.JsRegexSyntax

/**
 * 词法分析器：把 JavaScript 源码转换为 [Token] 序列。
 *
 * 设计要点：
 * - 每个 Token 都带 [SourceRange]，用于语法/运行时错误定位；
 * - 换行不生成独立 Token，而是记录在下一个 Token 的 [Token.precededByNewline] 上，
 *   供 Parser 实现自动分号插入（ASI）与 `return` 换行规则；
 * - 所有词法错误统一抛出 [LexerException]，不会泄漏宿主异常。
 *
 * 说明：当前阶段（阶段 A）只覆盖既有语法，正则字面量、模板字符串等
 * 由后续阶段在此基础上扩展。
 */
class Lexer(private val input: String) {

    /** 当前读取位置（字符偏移）。 */
    private var pos = 0

    /** 当前行号，从 1 开始。 */
    private var line = 1

    /** 当前列号，从 1 开始。 */
    private var column = 1

    /** 从上一个 Token 之后是否出现过换行（用于 ASI）。 */
    private var sawNewline = false

    /** 上一个有效 Token 的类型，用于区分正则字面量与除法。 */
    private var lastTokenType: TokenType? = null

    /** 当前字符串字面量是否出现旧式八进制转义（严格模式禁止）。 */
    private var legacyOctalEscape = false

    /** 括号是否为控制语句条件括号（if/while/for/with/switch/catch）。 */
    private val controlParenStack = mutableListOf<Boolean>()

    /** 最近一个右括号是否结束控制语句条件。 */
    private var lastClosedControlParen = false

    /** 花括号是否为语句块（true）或对象字面量（false）。 */
    private val braceStack = mutableListOf<Boolean>()

    /** 最近一个右花括号是否结束语句块。 */
    private var lastClosedBraceWasBlock = false

    /** 括号/方括号深度与 class 体花括号判定状态（用于正则/除号）。 */
    private var parenDepth = 0
    private var bracketDepth = 0
    private var pendingClassBody = false
    private var classBodyParenDepth = 0
    private var classBodyBracketDepth = 0

    private val controlParenKeywords = setOf(
        TokenType.IF, TokenType.WHILE, TokenType.FOR, TokenType.WITH,
        TokenType.SWITCH, TokenType.CATCH,
    )

    /** 关键字表：标识符文本 -> Token 类型。 */
    private val keywords: Map<String, TokenType> = mapOf(
        "var" to TokenType.VAR,
        "let" to TokenType.LET,
        "const" to TokenType.CONST,
        "function" to TokenType.FUNCTION,
        "if" to TokenType.IF,
        "else" to TokenType.ELSE,
        "while" to TokenType.WHILE,
        "for" to TokenType.FOR,
        "return" to TokenType.RETURN,
        "true" to TokenType.TRUE,
        "false" to TokenType.FALSE,
        "null" to TokenType.NULL,
        "new" to TokenType.NEW,
        "this" to TokenType.THIS,
        "switch" to TokenType.SWITCH,
        "case" to TokenType.CASE,
        "default" to TokenType.DEFAULT,
        "break" to TokenType.BREAK,
        "continue" to TokenType.CONTINUE,
        "throw" to TokenType.THROW,
        "try" to TokenType.TRY,
        "catch" to TokenType.CATCH,
        "finally" to TokenType.FINALLY,
        "typeof" to TokenType.TYPEOF,
        "do" to TokenType.DO,
        "delete" to TokenType.DELETE,
        "void" to TokenType.VOID,
        "in" to TokenType.IN,
        "instanceof" to TokenType.INSTANCEOF,
        "debugger" to TokenType.DEBUGGER,
        "with" to TokenType.WITH,
        "class" to TokenType.CLASS,
        "extends" to TokenType.EXTENDS,
        "super" to TokenType.SUPER,
    )

    /**
     * 扫描整个输入，返回 Token 列表（以 EOF 结尾）。
     */
    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()

        while (true) {
            skipWhitespaceAndComments()
            if (pos >= input.length) break

            val startLine = line
            val startColumn = column
            val startOffset = pos
            val newlineBefore = sawNewline
            sawNewline = false

            val token = nextToken(startLine, startColumn, startOffset, newlineBefore)
            tokens.add(token)
            lastTokenType = token.type
        }

        tokens.add(
            Token(
                type = TokenType.EOF,
                value = "",
                range = SourceRange(
                    SourcePosition(line, column, pos),
                    SourcePosition(line, column, pos),
                ),
                precededByNewline = sawNewline,
            )
        )
        return tokens
    }

    /**
     * 读取下一个 Token。
     *
     * @param startLine 起始行
     * @param startColumn 起始列
     * @param startOffset 起始偏移
     * @param newlineBefore 该 Token 前是否有换行
     */
    private fun nextToken(
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
        newlineBefore: Boolean,
    ): Token {
        val current = peek()

        return when {
            // 数字：整数、小数、科学计数法、前导小数点
            current.isDigit() -> readNumber(startLine, startColumn, startOffset, newlineBefore)
            current == '.' && peek(1).isDigit() ->
                readNumber(startLine, startColumn, startOffset, newlineBefore)

            // 字符串
            current == '"' || current == '\'' ->
                readString(startLine, startColumn, startOffset, newlineBefore)

            // 模板字符串
            current == '`' ->
                readTemplate(startLine, startColumn, startOffset, newlineBefore)

            // 标识符 / 关键字（支持 \uXXXX / \u{...} 转义与 astral 起始）
            isIdentifierStart(current) || (current == '\\' && peek(1) == 'u') ||
                isAstralPairAt(pos) ->
                readIdentifierOrKeyword(startLine, startColumn, startOffset, newlineBefore)

            current == '+' -> {
                advance()
                when (peek()) {
                    '+' -> {
                        advance()
                        makeToken(TokenType.INCREMENT, "++", startLine, startColumn, startOffset, newlineBefore)
                    }

                    '=' -> {
                        advance()
                        makeToken(TokenType.PLUS_ASSIGN, "+=", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.PLUS, "+", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '-' -> {
                advance()
                when (peek()) {
                    '-' -> {
                        advance()
                        makeToken(TokenType.DECREMENT, "--", startLine, startColumn, startOffset, newlineBefore)
                    }

                    '=' -> {
                        advance()
                        makeToken(TokenType.MINUS_ASSIGN, "-=", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.MINUS, "-", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '*' -> {
                advance()
                when (peek()) {
                    '*' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            makeToken(TokenType.EXPONENT_ASSIGN, "**=", startLine, startColumn, startOffset, newlineBefore)
                        } else {
                            makeToken(TokenType.EXPONENT, "**", startLine, startColumn, startOffset, newlineBefore)
                        }
                    }

                    '=' -> {
                        advance()
                        makeToken(TokenType.MULTIPLY_ASSIGN, "*=", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.MULTIPLY, "*", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            // 正则字面量与除法需要结合上下文判断：
            // 在表达式起始位置出现的 `/` 视为正则，否则视为除法/除赋值。
            current == '/' -> {
                if (isRegexAllowed(lastTokenType) && peek(1) != '/') {
                    readRegex(startLine, startColumn, startOffset, newlineBefore)
                } else {
                    advance()
                    if (peek() == '=') {
                        advance()
                        makeToken(TokenType.DIVIDE_ASSIGN, "/=", startLine, startColumn, startOffset, newlineBefore)
                    } else {
                        makeToken(TokenType.DIVIDE, "/", startLine, startColumn, startOffset, newlineBefore)
                    }
                }
            }

            current == '%' -> {
                advance()
                if (peek() == '=') {
                    advance()
                    makeToken(TokenType.MODULO_ASSIGN, "%=", startLine, startColumn, startOffset, newlineBefore)
                } else {
                    makeToken(TokenType.MODULO, "%", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '?' -> {
                advance()
                when {
                    peek() == '?' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            makeToken(TokenType.COALESCE_ASSIGN, "??=", startLine, startColumn, startOffset, newlineBefore)
                        } else {
                            makeToken(TokenType.COALESCE, "??", startLine, startColumn, startOffset, newlineBefore)
                        }
                    }

                    peek() == '.' && !peek(1).isDigit() -> {
                        advance()
                        makeToken(TokenType.QUESTION_DOT, "?.", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.QUESTION, "?", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '=' -> {
                advance()
                when {
                    peek() == '=' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            makeToken(TokenType.STRICT_EQ, "===", startLine, startColumn, startOffset, newlineBefore)
                        } else {
                            makeToken(TokenType.EQ, "==", startLine, startColumn, startOffset, newlineBefore)
                        }
                    }

                    peek() == '>' -> {
                        advance()
                        makeToken(TokenType.ARROW, "=>", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.ASSIGN, "=", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '!' -> {
                advance()
                when {
                    peek() == '=' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            makeToken(TokenType.STRICT_NEQ, "!==", startLine, startColumn, startOffset, newlineBefore)
                        } else {
                            makeToken(TokenType.NEQ, "!=", startLine, startColumn, startOffset, newlineBefore)
                        }
                    }

                    else -> makeToken(TokenType.NOT, "!", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '<' -> {
                advance()
                when {
                    peek() == '<' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            makeToken(TokenType.LEFT_SHIFT_ASSIGN, "<<=", startLine, startColumn, startOffset, newlineBefore)
                        } else {
                            makeToken(TokenType.LEFT_SHIFT, "<<", startLine, startColumn, startOffset, newlineBefore)
                        }
                    }

                    peek() == '=' -> {
                        advance()
                        makeToken(TokenType.LTE, "<=", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.LT, "<", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '>' -> {
                advance()
                when {
                    peek() == '>' -> {
                        advance()
                        when {
                            peek() == '>' -> {
                                advance()
                                if (peek() == '=') {
                                    advance()
                                    makeToken(TokenType.UNSIGNED_RIGHT_SHIFT_ASSIGN, ">>>=", startLine, startColumn, startOffset, newlineBefore)
                                } else {
                                    makeToken(TokenType.UNSIGNED_RIGHT_SHIFT, ">>>", startLine, startColumn, startOffset, newlineBefore)
                                }
                            }

                            peek() == '=' -> {
                                advance()
                                makeToken(TokenType.RIGHT_SHIFT_ASSIGN, ">>=", startLine, startColumn, startOffset, newlineBefore)
                            }

                            else -> makeToken(TokenType.RIGHT_SHIFT, ">>", startLine, startColumn, startOffset, newlineBefore)
                        }
                    }

                    peek() == '=' -> {
                        advance()
                        makeToken(TokenType.GTE, ">=", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.GT, ">", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '&' -> {
                advance()
                when {
                    peek() == '&' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            makeToken(TokenType.LOGICAL_AND_ASSIGN, "&&=", startLine, startColumn, startOffset, newlineBefore)
                        } else {
                            makeToken(TokenType.AND, "&&", startLine, startColumn, startOffset, newlineBefore)
                        }
                    }

                    peek() == '=' -> {
                        advance()
                        makeToken(TokenType.BITWISE_AND_ASSIGN, "&=", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.BITWISE_AND, "&", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '|' -> {
                advance()
                when {
                    peek() == '|' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            makeToken(TokenType.LOGICAL_OR_ASSIGN, "||=", startLine, startColumn, startOffset, newlineBefore)
                        } else {
                            makeToken(TokenType.OR, "||", startLine, startColumn, startOffset, newlineBefore)
                        }
                    }

                    peek() == '=' -> {
                        advance()
                        makeToken(TokenType.BITWISE_OR_ASSIGN, "|=", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> makeToken(TokenType.BITWISE_OR, "|", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '.' -> {
                when {
                    peek(1) == '.' && peek(2) == '.' -> {
                        advance(); advance(); advance()
                        makeToken(TokenType.ELLIPSIS, "...", startLine, startColumn, startOffset, newlineBefore)
                    }

                    else -> {
                        advance()
                        makeToken(TokenType.DOT, ".", startLine, startColumn, startOffset, newlineBefore)
                    }
                }
            }

            current == '^' -> {
                advance()
                if (peek() == '=') {
                    advance()
                    makeToken(TokenType.BITWISE_XOR_ASSIGN, "^=", startLine, startColumn, startOffset, newlineBefore)
                } else {
                    makeToken(TokenType.BITWISE_XOR, "^", startLine, startColumn, startOffset, newlineBefore)
                }
            }

            current == '~' -> {
                advance()
                makeToken(TokenType.BITWISE_NOT, "~", startLine, startColumn, startOffset, newlineBefore)
            }

            current == '(' -> {
                parenDepth++
                controlParenStack.add(lastTokenType in controlParenKeywords)
                advance()
                makeToken(TokenType.LPAREN, "(", startLine, startColumn, startOffset, newlineBefore)
            }

            current == ')' -> {
                if (parenDepth > 0) parenDepth--
                lastClosedControlParen = controlParenStack.removeLastOrNull() == true
                advance()
                makeToken(TokenType.RPAREN, ")", startLine, startColumn, startOffset, newlineBefore)
            }

            current == '{' -> {
                val isClassBody = pendingClassBody &&
                    parenDepth == classBodyParenDepth &&
                    bracketDepth == classBodyBracketDepth
                if (isClassBody) pendingClassBody = false
                braceStack.add(
                    isClassBody || isBlockBrace(
                        previous = lastTokenType,
                        newlineBefore = newlineBefore,
                        previousControlParen = lastClosedControlParen,
                    )
                )
                advance()
                makeToken(TokenType.LBRACE, "{", startLine, startColumn, startOffset, newlineBefore)
            }

            current == '}' -> {
                lastClosedBraceWasBlock = braceStack.removeLastOrNull() ?: true
                advance()
                makeToken(TokenType.RBRACE, "}", startLine, startColumn, startOffset, newlineBefore)
            }

            current == '[' -> {
                bracketDepth++
                advance()
                makeToken(TokenType.LBRACKET, "[", startLine, startColumn, startOffset, newlineBefore)
            }

            current == ']' -> {
                if (bracketDepth > 0) bracketDepth--
                advance()
                makeToken(TokenType.RBRACKET, "]", startLine, startColumn, startOffset, newlineBefore)
            }

            current == ';' -> {
                advance()
                makeToken(TokenType.SEMICOLON, ";", startLine, startColumn, startOffset, newlineBefore)
            }

            current == ',' -> {
                advance()
                makeToken(TokenType.COMMA, ",", startLine, startColumn, startOffset, newlineBefore)
            }

            current == ':' -> {
                advance()
                makeToken(TokenType.COLON, ":", startLine, startColumn, startOffset, newlineBefore)
            }

            else -> throw lexerError(
                message = "Unexpected character '$current'",
                startLine = startLine,
                startColumn = startColumn,
                startOffset = startOffset,
            )
        }
    }

    /**
     * 读取数字字面量。
     *
     * 支持：十进制整数/小数、科学计数法、前导小数点、0x/0o/0b 前缀、数字分隔符 `_`。
     * 返回值保留原始文本（包含 `_`），由 Parser 负责转换为 Double。
     */
    private fun readNumber(
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
        newlineBefore: Boolean,
    ): Token {
        val sb = StringBuilder()

        // 处理 0x / 0o / 0b 前缀
        if (peek() == '0') {
            when (peek(1)) {
                'x', 'X' -> {
                    sb.append(peek()); advance()
                    sb.append(peek()); advance()
                    val digits = scanDigits(sb, ::isHexDigit, startLine, startColumn, startOffset)
                    if (digits == 0) {
                        throw lexerError("Invalid hexadecimal number", startLine, startColumn, startOffset)
                    }
                    rejectIdentifierAfterNumber(startLine, startColumn, startOffset)
                    return makeToken(TokenType.NUMBER_HEX, sb.toString(), startLine, startColumn, startOffset, newlineBefore)
                }

                'o', 'O' -> {
                    sb.append(peek()); advance()
                    sb.append(peek()); advance()
                    val digits = scanDigits(
                        sb,
                        { it in '0'..'7' },
                        startLine,
                        startColumn,
                        startOffset,
                    )
                    if (digits == 0) {
                        throw lexerError("Invalid octal number", startLine, startColumn, startOffset)
                    }
                    rejectIdentifierAfterNumber(startLine, startColumn, startOffset)
                    return makeToken(TokenType.NUMBER_OCT, sb.toString(), startLine, startColumn, startOffset, newlineBefore)
                }

                'b', 'B' -> {
                    sb.append(peek()); advance()
                    sb.append(peek()); advance()
                    val digits = scanDigits(
                        sb,
                        { it == '0' || it == '1' },
                        startLine,
                        startColumn,
                        startOffset,
                    )
                    if (digits == 0) {
                        throw lexerError("Invalid binary number", startLine, startColumn, startOffset)
                    }
                    rejectIdentifierAfterNumber(startLine, startColumn, startOffset)
                    return makeToken(TokenType.NUMBER_BIN, sb.toString(), startLine, startColumn, startOffset, newlineBefore)
                }
            }
        }

        // 旧式八进制（01）与非八进制十进制（09）：整数部分先读取以区分二者
        var hasDigits = false
        val legacyCandidate = peek() == '0' && peek(1).isDigit()
        var nonOctalDecimal = false
        if (legacyCandidate) {
            val digits = scanDigits(sb, { it.isDigit() }, startLine, startColumn, startOffset)
            hasDigits = digits > 0
            if (sb.contains('_')) {
                throw lexerError(
                    "Numeric separator is not allowed in legacy octal literals",
                    startLine,
                    startColumn,
                    startOffset,
                )
            }
            // sb 仅包含整数部分（如 "09"），从第 2 个字符开始检测 8/9
            for (index in 1 until sb.length) {
                if (sb[index] in '8'..'9') nonOctalDecimal = true
            }
            if (!nonOctalDecimal) {
                // legacy octal 不能带小数，但允许 01.a 属性访问
                if (peek() == '.' && peek(1).isDigit()) {
                    throw lexerError("Unexpected number", startLine, startColumn, startOffset)
                }
                rejectIdentifierAfterNumber(startLine, startColumn, startOffset)
                return makeToken(TokenType.NUMBER, sb.toString(), startLine, startColumn, startOffset, newlineBefore)
            }
            // 09.x 非法，09..x 合法
            if (peek() == '.' && !peek(1).isDigit() &&
                (isIdentifierStart(peek(1)) || peek(1) == '\\' || isAstralPairAt(pos + 1))
            ) {
                throw lexerError("Unexpected number", startLine, startColumn, startOffset)
            }
        } else {
            // 整数部分（前导小数点时可以为空）
            val digits = scanDigits(sb, { it.isDigit() }, startLine, startColumn, startOffset)
            hasDigits = digits > 0
            val integerText = sb.toString()
            // 前导 0 后不允许数字分隔符（避免与 legacy octal 混淆）
            if (integerText.length > 1 && integerText[0] == '0' && integerText.contains('_')) {
                throw lexerError(
                    "Numeric separator is not allowed after leading zero",
                    startLine,
                    startColumn,
                    startOffset,
                )
            }
        }

        // 小数部分
        if (peek() == '.') {
            sb.append('.')
            advance()
            val digits = scanDigits(sb, { it.isDigit() }, startLine, startColumn, startOffset)
            if (digits > 0) hasDigits = true
        }

        if (!hasDigits) {
            throw lexerError("Invalid number", startLine, startColumn, startOffset)
        }

        // 指数部分
        if (peek() == 'e' || peek() == 'E') {
            sb.append(peek())
            advance()
            if (peek() == '+' || peek() == '-') {
                sb.append(peek())
                advance()
            }
            val exponentDigits = scanDigits(sb, { it.isDigit() }, startLine, startColumn, startOffset)
            if (exponentDigits == 0) {
                throw lexerError("Invalid exponent", startLine, startColumn, startOffset)
            }
        }

        rejectIdentifierAfterNumber(startLine, startColumn, startOffset)
        return makeToken(TokenType.NUMBER, sb.toString(), startLine, startColumn, startOffset, newlineBefore)
    }

    /**
     * 扫描一段数字并校验数字分隔符：`_` 只能出现在两个数字之间。
     *
     * @return 数字字符数量（不含分隔符）
     */
    private fun scanDigits(
        sb: StringBuilder,
        isDigit: (Char) -> Boolean,
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
    ): Int {
        var digits = 0
        var previousWasDigit = false
        while (true) {
            val c = peek()
            if (isDigit(c)) {
                sb.append(c)
                advance()
                digits++
                previousWasDigit = true
            } else if (c == '_') {
                if (!previousWasDigit || !isDigit(peek(1))) {
                    throw lexerError(
                        "Numeric separator must be between digits",
                        startLine,
                        startColumn,
                        startOffset,
                    )
                }
                sb.append(c)
                advance()
                previousWasDigit = false
            } else {
                break
            }
        }
        return digits
    }

    /** 数字字面量后不允许直接跟标识符（如 3in）。 */
    private fun rejectIdentifierAfterNumber(startLine: Int, startColumn: Int, startOffset: Int) {
        val c = peek()
        if (isIdentifierStart(c) || (c == '\\' && peek(1) == 'u') || isAstralPairAt(pos)) {
            throw lexerError("Identifier directly after number", startLine, startColumn, startOffset)
        }
    }

    /**
     * 读取字符串字面量。
     *
     * 支持单引号/双引号、常见转义、`\xXX`、`\uXXXX`、`\u{...}` 与行续接。
     * 未闭合或包含裸换行时抛出 [LexerException]。
     */
    private fun readString(
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
        newlineBefore: Boolean,
    ): Token {
        legacyOctalEscape = false
        val quote = peek()
        advance()
        val sb = StringBuilder()
        var containsEscape = false

        while (true) {
            if (pos >= input.length) {
                throw lexerError("Unterminated string literal", startLine, startColumn, startOffset)
            }
            val c = peek()
            when {
                c == quote -> {
                    advance()
                    return makeToken(
                        TokenType.STRING,
                        sb.toString(),
                        startLine,
                        startColumn,
                        startOffset,
                        newlineBefore,
                        containsEscape = containsEscape,
                        legacyOctal = legacyOctalEscape,
                    )
                }

                c == '\n' || c == '\r' ->
                    throw lexerError("Unterminated string literal", startLine, startColumn, startOffset)

                c == '\\' -> {
                    containsEscape = true
                    advance()
                    readEscapeSequence(sb)
                }

                else -> {
                    sb.append(c)
                    advance()
                }
            }
        }
    }

    /**
     * 读取一个转义序列（调用时反斜杠已被消费）。
     */
    private fun readEscapeSequence(sb: StringBuilder) {
        if (pos >= input.length) {
            throw lexerError("Unterminated escape sequence", line, column, pos)
        }
        when (val c = peek()) {
            'n' -> { sb.append('\n'); advance() }
            'r' -> { sb.append('\r'); advance() }
            't' -> { sb.append('\t'); advance() }
            'b' -> { sb.append('\b'); advance() }
            'f' -> { sb.append('\u000C'); advance() }
            'v' -> { sb.append('\u000B'); advance() }
            '0' -> {
                advance()
                if (peek().isDigit()) {
                    // 旧式八进制转义（严格模式下由 Parser 报错）
                    legacyOctalEscape = true
                    var value = 0
                    var count = 0
                    while (count < 2 && peek() in '0'..'7') {
                        value = value * 8 + (peek() - '0')
                        advance()
                        count++
                    }
                    sb.append(value.toChar())
                } else {
                    sb.append('\u0000')
                }
            }

            in '1'..'7' -> {
                legacyOctalEscape = true
                var value = 0
                var count = 0
                while (count < 3 && peek() in '0'..'7') {
                    value = value * 8 + (peek() - '0')
                    advance()
                    count++
                }
                sb.append(value.toChar())
            }

            '\\' -> { sb.append('\\'); advance() }
            '\'' -> { sb.append('\''); advance() }
            '"' -> { sb.append('"'); advance() }
            '\n' -> advance()
            '\r' -> {
                advance()
                if (peek() == '\n') advance()
            }

            'x' -> {
                advance()
                sb.append(readHexDigits(2).toChar())
            }

            'u' -> {
                advance()
                readUnicodeEscape(sb)
            }

            '8', '9' -> {
                // NonEscapeSequence 中的十进制数字 8/9 在字符串中非法
                throw lexerError("Invalid escape sequence", line, column, pos)
            }

            else -> {
                // 未知转义：按规范保留字符本身（例如 \q -> q）
                sb.append(c)
                advance()
            }
        }
    }

    /**
     * 读取 `\uXXXX` 或 `\u{XXXXXX}` 形式的 Unicode 转义。
     */
    private fun readUnicodeEscape(sb: StringBuilder) {
        if (peek() == '{') {
            advance()
            var codePoint = 0
            var digits = 0
            while (peek() != '}' && pos < input.length) {
                val d = hexValue(peek())
                if (d < 0) {
                    throw lexerError("Invalid Unicode escape sequence", line, column, pos)
                }
                codePoint = codePoint * 16 + d
                digits++
                if (codePoint > 0x10FFFF) {
                    throw lexerError("Unicode code point out of range", line, column, pos)
                }
                advance()
            }
            if (digits == 0 || peek() != '}') {
                throw lexerError("Invalid Unicode escape sequence", line, column, pos)
            }
            advance()
            appendCodePoint(sb, codePoint)
        } else {
            appendCodePoint(sb, readHexDigits(4))
        }
    }

    /**
     * 读取指定数量的十六进制数字。
     */
    private fun readHexDigits(count: Int): Int {
        var value = 0
        repeat(count) {
            val d = hexValue(peek())
            if (d < 0) {
                throw lexerError("Invalid hexadecimal escape sequence", line, column, pos)
            }
            value = value * 16 + d
            advance()
        }
        return value
    }

    /**
     * 把码点追加到 StringBuilder；超出 BMP 时写入代理对。
     */
    private fun appendCodePoint(sb: StringBuilder, codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            sb.append(codePoint.toChar())
        } else {
            val v = codePoint - 0x10000
            sb.append(((v shr 10) + 0xD800).toChar())
            sb.append(((v and 0x3FF) + 0xDC00).toChar())
        }
    }

    /**
     * 读取正则字面量 `/pattern/flags`。
     *
     * 调用时机由 [isRegexAllowed] 决定：只有当前处于"表达式起始位置"时
     * 才把 `/` 当作正则，否则当作除法/除赋值。
     */
    private fun readRegex(
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
        newlineBefore: Boolean,
    ): Token {
        advance() // '/'
        val pattern = StringBuilder()
        var inCharacterClass = false

        while (true) {
            if (pos >= input.length) {
                throw lexerError("Unterminated regular expression", startLine, startColumn, startOffset)
            }
            val c = peek()
            when {
                c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' ->
                    throw lexerError("Unterminated regular expression", startLine, startColumn, startOffset)

                c == '\\' -> {
                    pattern.append(c)
                    advance()
                    if (pos >= input.length) {
                        throw lexerError("Unterminated regular expression", startLine, startColumn, startOffset)
                    }
                    if (peek() == '\n' || peek() == '\r' || peek() == '\u2028' || peek() == '\u2029') {
                        throw lexerError("Unterminated regular expression", startLine, startColumn, startOffset)
                    }
                    pattern.append(peek())
                    advance()
                }

                c == '[' -> {
                    inCharacterClass = true
                    pattern.append(c)
                    advance()
                }

                c == ']' -> {
                    inCharacterClass = false
                    pattern.append(c)
                    advance()
                }

                c == '/' && !inCharacterClass -> {
                    advance()
                    break
                }

                else -> {
                    pattern.append(c)
                    advance()
                }
            }
        }

        val flags = StringBuilder()
        while (isIdentifierPart(peek())) {
            flags.append(peek())
            advance()
        }
        val flagText = flags.toString()
        val seenFlags = mutableSetOf<Char>()
        for (flag in flagText) {
            if (flag !in "gimsuy" || !seenFlags.add(flag)) {
                throw lexerError("Invalid regular expression flags", startLine, startColumn, startOffset)
            }
        }
        if ('u' in flagText) {
            val error = JsRegexSyntax.unicodeError(pattern.toString())
            if (error != null) {
                throw lexerError(error, startLine, startColumn, startOffset)
            }
        }

        return Token(
            type = TokenType.REGEX,
            value = pattern.toString(),
            range = SourceRange(
                start = SourcePosition(startLine, startColumn, startOffset),
                end = SourcePosition(line, column, pos),
            ),
            precededByNewline = newlineBefore,
            regexFlags = flagText,
        )
    }

    /**
     * 读取模板字符串。
     *
     * 模板被拆分为文本段与表达式源码段，`${...}` 中的表达式由 Parser 递归解析。
     */
    private fun readTemplate(
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
        newlineBefore: Boolean,
    ): Token {
        advance() // '`'
        val parts = mutableListOf<TemplatePart>()
        val text = StringBuilder()
        val rawText = StringBuilder()

        while (true) {
            if (pos >= input.length) {
                throw lexerError("Unterminated template literal", startLine, startColumn, startOffset)
            }
            val c = peek()
            when {
                c == '`' -> {
                    parts.add(TemplatePart.Text(text.toString(), rawText.toString()))
                    advance()
                    return Token(
                        type = TokenType.TEMPLATE,
                        value = "",
                        range = SourceRange(
                            start = SourcePosition(startLine, startColumn, startOffset),
                            end = SourcePosition(line, column, pos),
                        ),
                        precededByNewline = newlineBefore,
                        templateParts = parts,
                    )
                }

                c == '\\' -> {
                    val rawStart = pos
                    advance()
                    legacyOctalEscape = false
                    readEscapeSequence(text)
                    if (legacyOctalEscape) {
                        throw lexerError("Octal escape sequences are not allowed in template literals", line, column, pos)
                    }
                    rawText.append(input, rawStart, pos)
                }

                c == '$' && peek(1) == '{' -> {
                    parts.add(TemplatePart.Text(text.toString(), rawText.toString()))
                    text.clear()
                    rawText.clear()
                    advance() // '$'
                    advance() // '{'
                    val source = readTemplateExpressionSource()
                    parts.add(TemplatePart.ExpressionSource(source))
                }

                else -> {
                    text.append(c)
                    rawText.append(c)
                    advance()
                }
            }
        }
    }

    /**
     * 读取 `${` 与匹配的 `}` 之间的表达式源码。
     *
     * 扫描时跳过字符串、嵌套模板与注释，保证花括号配对正确。
     */
    private fun readTemplateExpressionSource(): String {
        val start = pos
        var depth = 1
        while (pos < input.length) {
            val c = peek()
            when {
                c == '\'' || c == '"' -> skipQuotedString(c)
                c == '`' -> skipNestedTemplate()
                c == '/' && peek(1) == '/' -> {
                    while (pos < input.length && peek() != '\n' && peek() != '\r') advance()
                }

                c == '/' && peek(1) == '*' -> {
                    advance(); advance()
                    while (pos < input.length) {
                        if (peek() == '*' && peek(1) == '/') {
                            advance(); advance()
                            break
                        }
                        advance()
                    }
                }

                c == '{' -> {
                    depth++
                    advance()
                }

                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val source = input.substring(start, pos)
                        advance()
                        return source
                    }
                    advance()
                }

                else -> advance()
            }
        }
        throw lexerError("Unterminated template expression", line, column, pos)
    }

    /** 跳过模板表达式中的字符串字面量。 */
    private fun skipQuotedString(quote: Char) {
        advance()
        while (pos < input.length) {
            when {
                peek() == '\\' -> {
                    advance()
                    if (pos < input.length) advance()
                }

                peek() == quote -> {
                    advance()
                    return
                }

                else -> advance()
            }
        }
    }

    /** 跳过模板表达式中嵌套的模板字符串。 */
    private fun skipNestedTemplate() {
        advance() // '`'
        while (pos < input.length) {
            when {
                peek() == '\\' -> {
                    advance()
                    if (pos < input.length) advance()
                }

                peek() == '`' -> {
                    advance()
                    return
                }

                peek() == '$' && peek(1) == '{' -> {
                    advance(); advance()
                    readTemplateExpressionSource()
                }

                else -> advance()
            }
        }
    }

    /**
     * 判断 `/` 是否应被视为正则字面量起始。
     *
     * 采用常见的"上一个 Token"启发式：表达式起始位置允许正则。
     */
    private fun isRegexAllowed(previous: TokenType?): Boolean {
        if (previous == null) return true
        // 控制语句条件或语句块结束后，`/` 处于语句起始位置
        if (previous == TokenType.RPAREN && lastClosedControlParen && looksLikeRegexLiteral()) return true
        if (previous == TokenType.RBRACE && lastClosedBraceWasBlock && looksLikeRegexLiteral()) return true
        return previous in setOf(
            TokenType.LPAREN, TokenType.LBRACKET, TokenType.LBRACE,
            TokenType.COMMA, TokenType.SEMICOLON, TokenType.COLON, TokenType.QUESTION,
            TokenType.ASSIGN, TokenType.PLUS_ASSIGN, TokenType.MINUS_ASSIGN,
            TokenType.MULTIPLY_ASSIGN, TokenType.DIVIDE_ASSIGN, TokenType.MODULO_ASSIGN,
            TokenType.EXPONENT_ASSIGN, TokenType.LEFT_SHIFT_ASSIGN,
            TokenType.RIGHT_SHIFT_ASSIGN, TokenType.UNSIGNED_RIGHT_SHIFT_ASSIGN,
            TokenType.LOGICAL_AND_ASSIGN, TokenType.LOGICAL_OR_ASSIGN,
            TokenType.COALESCE_ASSIGN, TokenType.BITWISE_AND_ASSIGN,
            TokenType.BITWISE_OR_ASSIGN, TokenType.BITWISE_XOR_ASSIGN,
            TokenType.PLUS, TokenType.MINUS, TokenType.MULTIPLY, TokenType.DIVIDE,
            TokenType.MODULO, TokenType.EXPONENT, TokenType.AND, TokenType.OR,
            TokenType.NOT, TokenType.BITWISE_AND, TokenType.BITWISE_OR,
            TokenType.BITWISE_XOR, TokenType.BITWISE_NOT,
            TokenType.LEFT_SHIFT, TokenType.RIGHT_SHIFT, TokenType.UNSIGNED_RIGHT_SHIFT,
            TokenType.EQ, TokenType.NEQ, TokenType.STRICT_EQ, TokenType.STRICT_NEQ,
            TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE,
            TokenType.COALESCE, TokenType.RETURN, TokenType.CASE, TokenType.THROW,
            TokenType.TYPEOF, TokenType.DELETE, TokenType.VOID, TokenType.IN,
            TokenType.INSTANCEOF, TokenType.NEW, TokenType.DO, TokenType.ELSE,
            TokenType.ARROW, TokenType.DEBUGGER,
        )
    }

    /**
     * 读取标识符或关键字。
     */
    private fun readIdentifierOrKeyword(
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
        newlineBefore: Boolean,
    ): Token {
        val sb = StringBuilder()
        // 标识符里允许 \uXXXX / \u{...} 转义；转义得到的字符按解码后的值参与判断
        var escaped = false
        var first = true
        while (true) {
            val c = peek()
            if (c == '\\') {
                if (peek(1) != 'u') {
                    throw lexerError("Invalid escape in identifier", startLine, startColumn, startOffset)
                }
                advance()
                advance()
                val codePoint = if (peek() == '{') {
                    advance()
                    var value = 0
                    var digits = 0
                    while (peek() != '}' && pos < input.length) {
                        val digit = hexDigitValue(peek())
                        if (digit < 0 || digits >= 6) {
                            throw lexerError("Invalid unicode escape in identifier", startLine, startColumn, startOffset)
                        }
                        value = value * 16 + digit
                        digits++
                        advance()
                    }
                    if (digits == 0 || peek() != '}') {
                        throw lexerError("Invalid unicode escape in identifier", startLine, startColumn, startOffset)
                    }
                    advance() // '}'
                    value
                } else {
                    var value = 0
                    repeat(4) {
                        val digit = hexDigitValue(peek())
                        if (digit < 0) {
                            throw lexerError("Invalid unicode escape in identifier", startLine, startColumn, startOffset)
                        }
                        value = value * 16 + digit
                        advance()
                    }
                    value
                }
                val decoded: String
                val valid: Boolean
                if (codePoint > 0xFFFF) {
                    decoded = buildString {
                        val value = codePoint - 0x10000
                        append(((value shr 10) + 0xD800).toChar())
                        append(((value and 0x3FF) + 0xDC00).toChar())
                    }
                    valid = if (first) {
                        isAstralIdentifierStart(codePoint)
                    } else {
                        isAstralIdentifierPart(codePoint)
                    }
                } else {
                    val character = codePoint.toChar()
                    decoded = character.toString()
                    valid = if (first) isIdentifierStart(character) else isIdentifierPart(character)
                }
                if (!valid) {
                    throw lexerError("Invalid identifier escape", startLine, startColumn, startOffset)
                }
                sb.append(decoded)
                escaped = true
                first = false
                continue
            }
            if (isAstralPairAt(pos)) {
                val codePoint = ((c.code - 0xD800) shl 10) + (peek(1).code - 0xDC00) + 0x10000
                val valid = if (first) isAstralIdentifierStart(codePoint) else isAstralIdentifierPart(codePoint)
                if (!valid) {
                    throw lexerError("Invalid identifier character", startLine, startColumn, startOffset)
                }
                sb.append(c)
                advance()
                sb.append(peek())
                advance()
                first = false
                continue
            }
            if (!isIdentifierPart(c)) break
            sb.append(c)
            advance()
            first = false
        }
        val text = sb.toString()
        // 转义关键字统一先作为标识符输出；属性名/成员访问直接可用，
        // 标识符位置由 Parser 根据 containsEscape 与保留字集合拒绝。
        val type = when {
            lastTokenType == TokenType.DOT -> TokenType.IDENTIFIER
            escaped -> TokenType.IDENTIFIER
            else -> keywords[text] ?: TokenType.IDENTIFIER
        }
        if (type == TokenType.CLASS) {
            pendingClassBody = true
            classBodyParenDepth = parenDepth
            classBodyBracketDepth = bracketDepth
        }
        return makeToken(
            type = type,
            value = text,
            startLine = startLine,
            startColumn = startColumn,
            startOffset = startOffset,
            newlineBefore = newlineBefore,
            containsEscape = escaped,
        )
    }

    /** 当前字符是否为 astral 代理对（用于标识符）。 */
    private fun isAstralPairAt(index: Int): Boolean {
        if (index + 1 >= input.length) return false
        return input[index].isHighSurrogate() && input[index + 1].isLowSurrogate()
    }

    /** astral 平面标识符起止字符的近似范围（覆盖语料中的 Deseret/CJK/数学字母等）。 */
    private fun isAstralIdentifierStart(codePoint: Int): Boolean =
        codePoint in 0x10000..0x1004D ||
            codePoint in 0x10480..0x104FF ||
            codePoint in 0x1D400..0x1D7FF ||
            codePoint in 0x1EE00..0x1EEFF ||
            codePoint in 0x20000..0x2B81D ||
            codePoint in 0x2B820..0x2CEAF ||
            codePoint in 0x2CEB0..0x2EBEF ||
            codePoint in 0x30000..0x3134F

    /** astral 标识符后续字符：含变体选择符等组合字符。 */
    private fun isAstralIdentifierPart(codePoint: Int): Boolean =
        isAstralIdentifierStart(codePoint) || codePoint in 0xE0100..0xE01EF

    private fun hexDigitValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * 判断 `{` 是语句块还是对象字面量，用于正则/除号判定。
     */
    private fun isBlockBrace(
        previous: TokenType?,
        newlineBefore: Boolean,
        previousControlParen: Boolean,
    ): Boolean {
        if (previous == null) return true
        when (previous) {
            TokenType.SEMICOLON, TokenType.LBRACE, TokenType.RBRACE, TokenType.COLON,
            TokenType.ELSE, TokenType.DO, TokenType.TRY, TokenType.FINALLY,
            TokenType.ARROW, TokenType.RPAREN,
            -> return true
            TokenType.RETURN -> return newlineBefore
            else -> Unit
        }
        // 换行后出现的 `{` 通常开启新语句；但赋值/逗号/括号等仍需继续表达式
        if (newlineBefore) {
            return previous !in setOf(
                TokenType.ASSIGN, TokenType.COMMA, TokenType.LPAREN, TokenType.LBRACKET,
                TokenType.COLON, TokenType.RETURN, TokenType.ARROW,
                TokenType.PLUS, TokenType.MINUS, TokenType.MULTIPLY, TokenType.DIVIDE,
                TokenType.MODULO, TokenType.EXPONENT, TokenType.AND, TokenType.OR,
                TokenType.QUESTION, TokenType.EQ, TokenType.NEQ, TokenType.STRICT_EQ,
                TokenType.STRICT_NEQ, TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE,
            )
        }
        return false
    }

    /** 从当前位置的 `/` 向后扫描，判断是否形如完整的正则字面量。 */
    private fun looksLikeRegexLiteral(): Boolean {
        var index = pos + 1
        var inClass = false
        while (index < input.length) {
            val c = input[index]
            when {
                c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' -> return false
                c == '\\' -> index++
                c == '[' -> inClass = true
                c == ']' -> inClass = false
                c == '/' && !inClass -> return true
            }
            index++
        }
        return false
    }

    /**
     * 跳过空白与注释，并记录期间是否出现过换行。
     */
    private fun skipWhitespaceAndComments() {
        while (pos < input.length) {
            when (val c = peek()) {
                '\n', '\r', '\u2028', '\u2029' -> {
                    sawNewline = true
                    advance()
                }

                ' ', '\t', '\u000B', '\u000C', '\u00A0', '\uFEFF',
                '\u1680', in '\u2000'..'\u200A', '\u202F', '\u205F', '\u3000' -> advance()

                '/' -> {
                    when (peek(1)) {
                        '/' -> skipLineComment()
                        '*' -> skipBlockComment()
                        else -> return
                    }
                }

                // Annex B HTML 注释：`<!--` 任意位置，`-->` 仅在行首（可前置空白）
                '<' -> {
                    if (peek(1) == '!' && peek(2) == '-' && peek(3) == '-') {
                        repeat(4) { advance() }
                        skipHtmlCommentTail()
                    } else {
                        return
                    }
                }

                '-' -> {
                    if (peek(1) == '-' && peek(2) == '>' && isAtLineStartIgnoringWhitespace()) {
                        repeat(3) { advance() }
                        skipHtmlCommentTail()
                    } else {
                        return
                    }
                }

                else -> return
            }
        }
    }

    /**
     * 跳过单行注释（不消费行尾换行，交由主循环记录换行）。
     */
    private fun skipLineComment() {
        advance() // '/'
        advance() // '/'
        skipHtmlCommentTail()
    }

    /** 跳至行尾（不消费换行，交由主循环记录）。 */
    private fun skipHtmlCommentTail() {
        while (pos < input.length && peek() != '\n' && peek() != '\r' &&
            peek() != '\u2028' && peek() != '\u2029'
        ) {
            advance()
        }
    }

    /** 当前行从行首到当前位置是否只有空白（用于 Annex B `-->` 注释）。 */
    private fun isAtLineStartIgnoringWhitespace(): Boolean {
        var index = pos - 1
        while (index >= 0) {
            val c = input[index]
            if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') return true
            if (c == '/' && index >= 1 && input[index - 1] == '*') {
                // 跳过紧邻的块注释，再继续检查注释前的行首
                var cursor = index - 2
                while (cursor >= 1) {
                    if (input[cursor - 1] == '/' && input[cursor] == '*') break
                    cursor--
                }
                if (cursor < 1) return false
                index = cursor - 2
                continue
            }
            val isWhitespace = c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' ||
                c == '\u00A0' || c == '\uFEFF' || c.category == CharCategory.SPACE_SEPARATOR
            if (!isWhitespace) return false
            index--
        }
        return true
    }

    /**
     * 跳过块注释；未闭合时抛出词法错误。
     */
    private fun skipBlockComment() {
        val startLine = line
        val startColumn = column
        val startOffset = pos
        advance() // '/'
        advance() // '*'
        while (pos < input.length) {
            if (peek() == '*' && peek(1) == '/') {
                advance()
                advance()
                return
            }
            if (peek() == '\n' || peek() == '\r' || peek() == '\u2028' || peek() == '\u2029') {
                sawNewline = true
            }
            advance()
        }
        throw lexerError("Unterminated block comment", startLine, startColumn, startOffset)
    }

    /** 判断字符是否可作为标识符首字符。 */
    private fun isIdentifierStart(c: Char): Boolean =
        c == '_' || c == '$' || c.isLetter() ||
            c.category == CharCategory.LETTER_NUMBER ||
            c in OTHER_ID_START

    /** 判断字符是否可作为标识符后续字符（Unicode ID_Continue 的常用近似）。 */
    private fun isIdentifierPart(c: Char): Boolean =
        isIdentifierStart(c) || c.isDigit() ||
            c.category == CharCategory.DECIMAL_DIGIT_NUMBER ||
            c.category == CharCategory.NON_SPACING_MARK ||
            c.category == CharCategory.ENCLOSING_MARK ||
            c.category == CharCategory.COMBINING_SPACING_MARK ||
            c.category == CharCategory.CONNECTOR_PUNCTUATION ||
            c == '\u200C' || c == '\u200D' ||
            c in OTHER_ID_CONTINUE

    private companion object {
        /** Unicode Other_ID_Start。 */
        val OTHER_ID_START = setOf('\u1885', '\u1886', '\u2118', '\u212E', '\u309B', '\u309C')

        /** Unicode Other_ID_Continue。 */
        val OTHER_ID_CONTINUE = setOf('\u00B7', '\u0387', '\u19DA') +
            ('\u1369'..'\u1371').toSet()
    }

    /** 判断字符是否为十六进制数字。 */
    private fun isHexDigit(c: Char): Boolean =
        c.isDigit() || (c in 'a'..'f') || (c in 'A'..'F')

    /** 十六进制字符转数值，非法返回 -1。 */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * 读取指定偏移处的字符；越界返回 NUL，便于统一处理。
     */
    private fun peek(offset: Int = 0): Char =
        if (pos + offset < input.length) input[pos + offset] else '\u0000'

    /**
     * 前进一个字符，并维护行列号。
     *
     * 换行规则：`\n` 单独计一行；`\r\n` 只在 `\n` 处计一行；`\r` 单独出现时计一行。
     */
    private fun advance() {
        if (pos >= input.length) return
        val c = input[pos]
        pos++
        when (c) {
            '\n' -> {
                line++
                column = 1
            }

            '\r' -> {
                // \r\n 时由后续的 \n 负责换行；单独的 \r 视为换行
                if (pos >= input.length || input[pos] != '\n') {
                    line++
                }
                column = 1
            }

            '\u2028', '\u2029' -> {
                line++
                column = 1
            }

            else -> column++
        }
    }

    /** 构造带有当前结束位置的 Token。 */
    private fun makeToken(
        type: TokenType,
        value: String,
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
        newlineBefore: Boolean,
        containsEscape: Boolean = false,
        legacyOctal: Boolean = false,
    ): Token = Token(
        type = type,
        value = value,
        range = SourceRange(
            start = SourcePosition(startLine, startColumn, startOffset),
            end = SourcePosition(line, column, pos),
        ),
        precededByNewline = newlineBefore,
        containsEscape = containsEscape,
        legacyOctal = legacyOctal,
    )

    /** 构造词法错误（范围从 start 到当前位置）。 */
    private fun lexerError(
        message: String,
        startLine: Int,
        startColumn: Int,
        startOffset: Int,
    ): LexerException = LexerException(
        message = message,
        range = SourceRange(
            start = SourcePosition(startLine, startColumn, startOffset),
            end = SourcePosition(line, column, pos),
        ),
    )
}
