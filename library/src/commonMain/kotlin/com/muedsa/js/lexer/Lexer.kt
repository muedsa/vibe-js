package com.muedsa.js.lexer

@Suppress("EMPTY_RANGE")
class Lexer(private val input: String) {
    private var pos = 0
    private var line = 1
    private var column = 1

    private val keywords = mapOf(
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
        "typeof" to TokenType.TYPEOF
    )

    fun tokenize(): List<Token> {
        val tokens = mutableListOf<Token>()

        while (pos < input.length) {
            skipWhitespaceAndComments()

            if (pos >= input.length) break

            val token = nextToken()
            if (token.type != TokenType.NEWLINE) {
                tokens.add(token)
            }
        }

        tokens.add(Token(TokenType.EOF, "", line, column, pos until pos))
        return tokens
    }

    private fun nextToken(): Token {
        val startLine = line
        val startColumn = column
        val startPos = pos
        val current = peek()

        return when {
            current.isDigit() -> readNumber()
            current == '"' || current == '\'' -> readString()
            current.isLetter() || current == '_' || current == '$' -> readIdentifierOrKeyword()
            current == '+' -> {
                advance()
                when (peek()) {
                    '+' -> {
                        advance()
                        Token(TokenType.INCREMENT, "++", startLine, startColumn, startPos until pos)
                    }

                    '=' -> {
                        advance()
                        Token(TokenType.PLUS_ASSIGN, "+=", startLine, startColumn, startPos until pos)
                    }

                    else -> Token(TokenType.PLUS, "+", startLine, startColumn, startPos until pos)
                }
            }

            current == '-' -> {
                advance()
                when (peek()) {
                    '-' -> {
                        advance()
                        Token(TokenType.DECREMENT, "--", startLine, startColumn, startPos until pos)
                    }

                    '=' -> {
                        advance()
                        Token(TokenType.MINUS_ASSIGN, "-=", startLine, startColumn, startPos until pos)
                    }

                    else -> Token(TokenType.MINUS, "-", startLine, startColumn, startPos until pos)
                }
            }

            current == '*' -> {
                advance()
                when (peek()) {
                    '*' -> {
                        advance()
                        Token(TokenType.EXPONENT, "**", startLine, startColumn, startPos until pos)
                    }

                    '=' -> {
                        advance()
                        Token(TokenType.MULTIPLY_ASSIGN, "*=", startLine, startColumn, startPos until pos)
                    }

                    else -> Token(TokenType.MULTIPLY, "*", startLine, startColumn, startPos until pos)
                }
            }

            current == '/' -> {
                advance()
                if (peek() == '=') {
                    advance()
                    Token(TokenType.DIVIDE_ASSIGN, "/=", startLine, startColumn, startPos until pos)
                } else {
                    Token(TokenType.DIVIDE, "/", startLine, startColumn, startPos until pos)
                }
            }

            current == '%' -> {
                advance()
                if (peek() == '=') {
                    advance()
                    Token(TokenType.MODULO_ASSIGN, "%=", startLine, startColumn, startPos until pos)
                } else {
                    Token(TokenType.MODULO, "%", startLine, startColumn, startPos until pos)
                }
            }

            current == '?' -> {
                advance()
                if (peek() == '?') {
                    advance()
                    Token(TokenType.COALESCE, "??", startLine, startColumn, startPos until pos)
                } else {
                    Token(TokenType.QUESTION, "?", startLine, startColumn, startPos until pos)
                }
            }

            current == '=' -> {
                advance()
                when {
                    peek() == '=' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            Token(TokenType.STRICT_EQ, "===", startLine, startColumn, startPos until pos)
                        } else {
                            Token(TokenType.EQ, "==", startLine, startColumn, startPos until pos)
                        }
                    }

                    peek() == '>' -> {
                        advance()
                        Token(TokenType.ARROW, "=>", startLine, startColumn, startPos until pos)
                    }

                    else -> Token(TokenType.ASSIGN, "=", startLine, startColumn, startPos until pos)
                }
            }

            current == '!' -> {
                advance()
                when {
                    peek() == '=' -> {
                        advance()
                        if (peek() == '=') {
                            advance()
                            Token(TokenType.STRICT_NEQ, "!==", startLine, startColumn, startPos until pos)
                        } else {
                            Token(TokenType.NEQ, "!=", startLine, startColumn, startPos until pos)
                        }
                    }

                    else -> Token(TokenType.NOT, "!", startLine, startColumn, startPos until pos)
                }
            }

            current == '<' -> {
                advance()
                when {
                    peek() == '<' -> {
                        advance()
                        Token(TokenType.LEFT_SHIFT, "<<", startLine, startColumn, startPos until pos)
                    }

                    peek() == '=' -> {
                        advance()
                        Token(TokenType.LTE, "<=", startLine, startColumn, startPos until pos)
                    }

                    else -> Token(TokenType.LT, "<", startLine, startColumn, startPos until pos)
                }
            }

            current == '>' -> {
                advance()
                when {
                    peek() == '>' -> {
                        advance()
                        if (peek() == '>') {
                            advance()
                            Token(TokenType.UNSIGNED_RIGHT_SHIFT, ">>>", startLine, startColumn, startPos until pos)
                        } else {
                            Token(TokenType.RIGHT_SHIFT, ">>", startLine, startColumn, startPos until pos)
                        }
                    }

                    peek() == '=' -> {
                        advance()
                        Token(TokenType.GTE, ">=", startLine, startColumn, startPos until pos)
                    }

                    else -> Token(TokenType.GT, ">", startLine, startColumn, startPos until pos)
                }
            }

            current == '&' -> {
                advance()
                if (peek() == '&') {
                    advance()
                    Token(TokenType.AND, "&&", startLine, startColumn, startPos until pos)
                } else {
                    Token(TokenType.BITWISE_AND, "&", startLine, startColumn, startPos until pos)
                }
            }

            current == '|' -> {
                advance()
                if (peek() == '|') {
                    advance()
                    Token(TokenType.OR, "||", startLine, startColumn, startPos until pos)
                } else {
                    Token(TokenType.BITWISE_OR, "|", startLine, startColumn, startPos until pos)
                }
            }

            current == '^' -> {
                advance()
                Token(TokenType.BITWISE_XOR, "^", startLine, startColumn, startPos until pos)
            }

            current == '~' -> {
                advance()
                Token(TokenType.BITWISE_NOT, "~", startLine, startColumn, startPos until pos)
            }

            current == '(' -> {
                advance()
                Token(TokenType.LPAREN, "(", startLine, startColumn, startPos until pos)
            }

            current == ')' -> {
                advance()
                Token(TokenType.RPAREN, ")", startLine, startColumn, startPos until pos)
            }

            current == '{' -> {
                advance()
                Token(TokenType.LBRACE, "{", startLine, startColumn, startPos until pos)
            }

            current == '}' -> {
                advance()
                Token(TokenType.RBRACE, "}", startLine, startColumn, startPos until pos)
            }

            current == '[' -> {
                advance()
                Token(TokenType.LBRACKET, "[", startLine, startColumn, startPos until pos)
            }

            current == ']' -> {
                advance()
                Token(TokenType.RBRACKET, "]", startLine, startColumn, startPos until pos)
            }

            current == ';' -> {
                advance()
                Token(TokenType.SEMICOLON, ";", startLine, startColumn, startPos until pos)
            }

            current == ',' -> {
                advance()
                Token(TokenType.COMMA, ",", startLine, startColumn, startPos until pos)
            }

            current == '.' -> {
                advance()
                Token(TokenType.DOT, ".", startLine, startColumn, startPos until pos)
            }

            current == ':' -> {
                advance()
                Token(TokenType.COLON, ":", startLine, startColumn, startPos until pos)
            }

            current == '\n' -> {
                advance()
                Token(TokenType.NEWLINE, "\n", startLine, startColumn, startPos until pos)
            }

            else -> {
                advance()
                throw RuntimeException("Unexpected character: $current at line $line, column $column")
            }
        }
    }

    private fun readNumber(): Token {
        val startLine = line
        val startColumn = column
        val startPos = pos
        val sb = StringBuilder()

        if (peek() == '0') {
            val nextChar = peek(1)
            when (nextChar) {
                'x', 'X' -> {
                    sb.append(peek()) // 0
                    advance()
                    sb.append(peek()) // x or X
                    advance()
                    if (pos >= input.length || !isHexDigit(peek())) {
                        throw RuntimeException("Invalid hexadecimal number at line $line, column $column")
                    }
                    while (pos < input.length && isHexDigit(peek())) {
                        sb.append(peek())
                        advance()
                    }
                    return Token(
                        TokenType.NUMBER_HEX,
                        sb.toString(),
                        startLine,
                        startColumn,
                        startPos until pos
                    )
                }
                'o', 'O' -> {
                    sb.append(peek()) // 0
                    advance()
                    sb.append(peek()) // o or O
                    advance()
                    while (pos < input.length && isOctalDigit(peek())) {
                        sb.append(peek())
                        advance()
                    }
                    return Token(
                        TokenType.NUMBER_OCT,
                        sb.toString(),
                        startLine,
                        startColumn,
                        startPos until pos
                    )
                }
                'b', 'B' -> {
                    sb.append(peek()) // 0
                    advance()
                    sb.append(peek()) // b or B
                    advance()
                    while (pos < input.length && isBinaryDigit(peek())) {
                        sb.append(peek())
                        advance()
                    }
                    return Token(
                        TokenType.NUMBER_BIN,
                        sb.toString(),
                        startLine,
                        startColumn,
                        startPos until pos
                    )
                }
            }
        }

        while (pos < input.length && (peek().isDigit() || peek() == '.')) {
            sb.append(peek())
            advance()
        }

        return Token(TokenType.NUMBER, sb.toString(), startLine, startColumn, startPos until pos)
    }

    private fun isHexDigit(c: Char): Boolean {
        return c.isDigit() || (c in 'a'..'f') || (c in 'A'..'F')
    }

    private fun isOctalDigit(c: Char): Boolean {
        return c in '0'..'7'
    }

    private fun isBinaryDigit(c: Char): Boolean {
        return c == '0' || c == '1'
    }

    private fun readString(): Token {
        val startLine = line
        val startColumn = column
        val startPos = pos
        val quote = peek()
        advance()
        val sb = StringBuilder()

        while (pos < input.length && peek() != quote) {
            if (peek() == '\\') {
                advance()
                when (peek()) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '\'' -> sb.append('\'')
                    else -> sb.append(peek())
                }
            } else {
                sb.append(peek())
            }
            advance()
        }

        advance()
        return Token(TokenType.STRING, sb.toString(), startLine, startColumn, startPos until pos)
    }

    private fun readIdentifierOrKeyword(): Token {
        val startLine = line
        val startColumn = column
        val startPos = pos
        val sb = StringBuilder()

        while (pos < input.length && (peek().isLetterOrDigit() || peek() == '_' || peek() == '$')) {
            sb.append(peek())
            advance()
        }

        val value = sb.toString()
        val type = keywords[value] ?: TokenType.IDENTIFIER

        return Token(type, value, startLine, startColumn, startPos until pos)
    }

    private fun peek(offset: Int = 0): Char =
        if (pos + offset < input.length) input[pos + offset] else '\u0000'

    private fun advance() {
        if (pos < input.length) {
            if (input[pos] == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            pos++
        }
    }

    private fun skipWhitespaceAndComments() {
        while (pos < input.length) {
            when {
                peek() == ' ' || peek() == '\t' || peek() == '\r' -> advance()
                peek() == '/' && peek(1) == '/' -> {
                    advance()
                    advance()
                    while (pos < input.length && peek() != '\n') {
                        advance()
                    }
                }

                peek() == '/' && peek(1) == '*' -> {
                    advance()
                    advance()
                    while (pos < input.length) {
                        if (peek() == '*' && peek(1) == '/') {
                            advance()
                            advance()
                            break
                        }
                        advance()
                    }
                }

                else -> return
            }
        }
    }
}