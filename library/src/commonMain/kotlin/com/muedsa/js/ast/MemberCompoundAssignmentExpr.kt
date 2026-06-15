package com.muedsa.js.ast

/**
 * 成员复合赋值表达式，如: obj.prop += 5, arr[0] *= 2
 * @property obj 对象表达式
 * @property property 属性表达式
 * @property operator 复合操作符字符串
 * @property value 赋值表达式
 * @property computed 是否为计算属性访问(true: arr[0], false: obj.prop)
 */
data class MemberCompoundAssignmentExpr(
    val obj: Expression,
    val property: Expression,
    val operator: String,
    val value: Expression,
    val computed: Boolean
) : Expression()
