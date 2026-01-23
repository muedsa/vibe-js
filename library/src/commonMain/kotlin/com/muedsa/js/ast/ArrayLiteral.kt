package com.muedsa.js.ast

/**
 * 数组字面量表达式，如: [1, 2, 3]
 * @property elements 数组元素表达式列表
 */
data class ArrayLiteral(val elements: List<Expression>) : Expression()