package com.muedsa.js.ast

/**
 * switch语句，如: switch (expr) { case 1: statements; break; default: statements; }
 * @property discriminant 判别表达式
 * @property cases case分支列表
 */
data class SwitchStatement(
    val discriminant: Expression,
    val cases: List<SwitchCase>
) : Statement()
