package com.muedsa.js.ast

/**
 * switch语句的case分支
 * @property test 可选的测试表达式
 * @property consequent 当测试表达式匹配时执行的语句列表
 */
data class SwitchCase(
    val test: Expression?,
    val consequent: List<Statement>
)