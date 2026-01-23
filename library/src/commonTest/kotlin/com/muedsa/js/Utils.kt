package com.muedsa.js

import com.muedsa.js.ast.BlockStatement
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.Token
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.initBrowserEnv

fun createRuntime(): Interpreter = Interpreter()

fun createBrowserRuntime(): Interpreter =
    Interpreter().initBrowserEnv()

fun tokenizeCode(code: String): List<Token> =
    Lexer(code).tokenize()

fun parseCode(tokens: List<Token>): BlockStatement = Parser(tokens).parse()