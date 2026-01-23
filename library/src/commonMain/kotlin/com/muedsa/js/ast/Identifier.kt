package com.muedsa.js.ast

/**
 * 标识符表达式，用于表示变量或函数名，如: x, getName
 */
data class Identifier(val name: String) : Expression()