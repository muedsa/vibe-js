package com.muedsa.js.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LexerTest {

    @Test
    fun `test keywords`() {
        val lexer =
            Lexer("var let const function if else while for return true false null new this switch case default break continue throw try catch finally")
        val tokens = lexer.tokenize()

        val expectedTypes = listOf(
            TokenType.VAR, TokenType.LET, TokenType.CONST, TokenType.FUNCTION,
            TokenType.IF, TokenType.ELSE, TokenType.WHILE, TokenType.FOR,
            TokenType.RETURN, TokenType.TRUE, TokenType.FALSE, TokenType.NULL,
            TokenType.NEW, TokenType.THIS, TokenType.SWITCH, TokenType.CASE,
            TokenType.DEFAULT, TokenType.BREAK, TokenType.CONTINUE, TokenType.THROW,
            TokenType.TRY, TokenType.CATCH, TokenType.FINALLY, TokenType.EOF
        )

        assertEquals(expectedTypes.size, tokens.size)
        for (i in expectedTypes.indices) {
            assertEquals(expectedTypes[i], tokens[i].type, "Token at index $i mismatch")
        }
    }

    @Test
    fun `test identifiers`() {
        val code = "x myVar _private special variable123 $100"
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        val expectedValues = listOf("x", "myVar", "_private", "special", "variable123", "$100")

        assertEquals(expectedValues.size + 1, tokens.size) // +1 for EOF
        for (i in expectedValues.indices) {
            assertEquals(TokenType.IDENTIFIER, tokens[i].type, "Token at index $i should be IDENTIFIER")
            assertEquals(expectedValues[i], tokens[i].value)
        }
    }

    @Test
    fun `test number literals`() {
        val lexer = Lexer("123 45.67 0 0.05 0xFF 0o77 0b11")
        val tokens = lexer.tokenize()

        val expected = listOf(
            TokenType.NUMBER to "123",
            TokenType.NUMBER to "45.67",
            TokenType.NUMBER to "0",
            TokenType.NUMBER to "0.05",
            TokenType.NUMBER_HEX to "0xFF",
            TokenType.NUMBER_OCT to "0o77",
            TokenType.NUMBER_BIN to "0b11"
        )

        assertEquals(expected.size + 1, tokens.size)
        for (i in expected.indices) {
            assertEquals(expected[i].first, tokens[i].type, "Token at index $i type mismatch")
            assertEquals(expected[i].second, tokens[i].value, "Token at index $i value mismatch")
        }
    }

    @Test
    fun `test string literals`() {
        val code = " \"hello\" 'world' \"text with \\\"quotes\\\"\" 'text with \\'quotes\\'' "
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        val expectedValues = listOf(
            "hello",
            "world",
            "text with \"quotes\"",
            "text with 'quotes'"
        )

        assertEquals(expectedValues.size + 1, tokens.size)
        for (i in expectedValues.indices) {
            assertEquals(TokenType.STRING, tokens[i].type)
            assertEquals(expectedValues[i], tokens[i].value)
        }
    }

    @Test
    fun `test string escape sequences`() {
        val code = " \"line\nbreak\" \"tab\tchar\" \"back\\\\slash\" "
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        val expectedValues = listOf("line\nbreak", "tab\tchar", "back\\slash")

        assertEquals(expectedValues.size + 1, tokens.size)
        for (i in expectedValues.indices) {
            assertEquals(TokenType.STRING, tokens[i].type)
            assertEquals(expectedValues[i], tokens[i].value)
        }
    }

    @Test
    fun `test operators`() {
        val code = "+ - * / % = == === != !== < <= > >= && || ! ++ --" // Added ++ --
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        val expectedTypes = listOf(
            TokenType.PLUS, TokenType.MINUS, TokenType.MULTIPLY, TokenType.DIVIDE, TokenType.MODULO,
            TokenType.ASSIGN, TokenType.EQ, TokenType.STRICT_EQ, TokenType.NEQ, TokenType.STRICT_NEQ,
            TokenType.LT, TokenType.LTE, TokenType.GT, TokenType.GTE,
            TokenType.AND, TokenType.OR, TokenType.NOT,
            TokenType.INCREMENT, TokenType.DECREMENT,
            TokenType.EOF
        )

        assertEquals(expectedTypes.size, tokens.size)
        for (i in expectedTypes.indices) {
            assertEquals(expectedTypes[i], tokens[i].type)
        }
    }

    @Test
    fun `test bitwise operators`() {
        val code = "& | ^ ~ << >> >>>"
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        val expectedTypes = listOf(
            TokenType.BITWISE_AND, TokenType.BITWISE_OR, TokenType.BITWISE_XOR, TokenType.BITWISE_NOT,
            TokenType.LEFT_SHIFT, TokenType.RIGHT_SHIFT, TokenType.UNSIGNED_RIGHT_SHIFT,
            TokenType.EOF
        )

        assertEquals(expectedTypes.size, tokens.size)
        for (i in expectedTypes.indices) {
            assertEquals(expectedTypes[i], tokens[i].type)
        }
    }

    @Test
    fun `test compound assignment operators`() {
        val code = "+= -= *= /= %="
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        val expectedTypes = listOf(
            TokenType.PLUS_ASSIGN, TokenType.MINUS_ASSIGN, TokenType.MULTIPLY_ASSIGN,
            TokenType.DIVIDE_ASSIGN, TokenType.MODULO_ASSIGN,
            TokenType.EOF
        )

        assertEquals(expectedTypes.size, tokens.size)
        for (i in expectedTypes.indices) {
            assertEquals(expectedTypes[i], tokens[i].type)
        }
    }

    @Test
    fun `test delimiters`() {
        val code = "( ) { } [ ] ; , . : ?"
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        val expectedTypes = listOf(
            TokenType.LPAREN, TokenType.RPAREN, TokenType.LBRACE, TokenType.RBRACE,
            TokenType.LBRACKET, TokenType.RBRACKET, TokenType.SEMICOLON, TokenType.COMMA,
            TokenType.DOT, TokenType.COLON, TokenType.QUESTION,
            TokenType.EOF
        )

        assertEquals(expectedTypes.size, tokens.size)
        for (i in expectedTypes.indices) {
            assertEquals(expectedTypes[i], tokens[i].type)
        }
    }

    @Test
    fun `test arrow function`() {
        val code = "=>"
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        assertEquals(TokenType.ARROW, tokens[0].type)
        assertEquals(TokenType.EOF, tokens[1].type)
    }

    @Test
    fun `test comments`() {
        val code = """
            var x = 1; // Single line comment
            /* Multi-line
               comment */
            var y = 2;
        """.trimIndent()
        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        // Should ignore comments
        val expectedTypes = listOf(
            TokenType.VAR, TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.NUMBER, TokenType.SEMICOLON,
            TokenType.VAR, TokenType.IDENTIFIER, TokenType.ASSIGN, TokenType.NUMBER, TokenType.SEMICOLON,
            TokenType.EOF
        )

        assertEquals(expectedTypes.size, tokens.size)
        for (i in expectedTypes.indices) {
            assertEquals(expectedTypes[i], tokens[i].type)
        }
    }

    @Test
    fun `test token range`() {
        val input = "var x = 123;"
        val lexer = Lexer(input)
        val tokens = lexer.tokenize()

        // var (0..3)
        assertEquals(0 until 3, tokens[0].range)
        // x (4..5)
        assertEquals(4 until 5, tokens[1].range)
        // = (6..7)
        assertEquals(6 until 7, tokens[2].range)
        // 123 (8..11)
        assertEquals(8 until 11, tokens[3].range)
        // ; (11..12)
        assertEquals(11 until 12, tokens[4].range)
    }

    @Test
    fun `test empty input`() {
        val lexer = Lexer("")
        val tokens = lexer.tokenize()
        assertEquals(1, tokens.size)
        assertEquals(TokenType.EOF, tokens[0].type)
    }

    @Test
    fun `test whitespace only`() {
        val lexer = Lexer("   \t\n  \r ")
        val tokens = lexer.tokenize()
        assertEquals(1, tokens.size)
        assertEquals(TokenType.EOF, tokens[0].type)
    }

    @Test
    fun `test unclosed string`() {
        // Current implementation does not throw, but reads until EOF
        val lexer = Lexer("\"hello")
        val tokens = lexer.tokenize()
        assertEquals(2, tokens.size)
        assertEquals(TokenType.STRING, tokens[0].type)
        assertEquals("hello", tokens[0].value)
        assertEquals(TokenType.EOF, tokens[1].type)
    }

    @Test
    fun `test unclosed block comment`() {
        val lexer = Lexer("/* unclosed comment")
        val tokens = lexer.tokenize()
        assertEquals(1, tokens.size)
        assertEquals(TokenType.EOF, tokens[0].type)
    }

    @Test
    fun `test invalid character`() {
        val lexer = Lexer("@")
        assertFailsWith<RuntimeException> { lexer.tokenize() }
    }

    @Test
    fun `test invalid hex`() {
        val lexer = Lexer("0xz")
        assertFailsWith<RuntimeException> { lexer.tokenize() }
    }
}
