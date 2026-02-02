package com.muedsa.js.ast

/**
 * 成员赋值表达式，如: obj.prop = value, arr[0] = 10
 * @property obj 对象表达式
 * @property property 属性表达式
 * @property value 赋值表达式
 * @property computed 是否为计算属性访问(true: arr[0], false: obj.prop)
 */
data class MemberAssignmentExpr(
    val obj: Expression,
    val property:  Expression,
    val value: Expression,
    val computed: Boolean
) : Expression()
