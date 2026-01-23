package com.muedsa.js.ast

/**
 * 对象字面量表达式，如: {name: "John", age: 30}
 * @property properties 对象属性映射，键为属性名，值为属性表达式
 */
data class ObjectLiteral(val properties: Map<String, Expression>) : Expression()