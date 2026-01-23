package com.muedsa.js.ast

import com.muedsa.js.lexer.Token

/**
 * 抽象语法树(AST)的所有节点的基类
 */
sealed class ASTNode {
    var range: IntRange = IntRange.EMPTY
    var tokens: List<Token> = emptyList()
}
