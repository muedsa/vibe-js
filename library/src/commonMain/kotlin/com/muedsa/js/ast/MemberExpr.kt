package com.muedsa.js.ast

/**
 * 成员访问表达式，如: obj.prop, arr[0]
 * @property obj 对象表达式
 * @property property 属性表达式
 * @property computed 是否为计算属性访问(true: arr[0], false: obj.prop)
 */
data class MemberExpr(
    val obj: Expression,
    val property:  Expression,
    val computed: Boolean,
) : Expression()
