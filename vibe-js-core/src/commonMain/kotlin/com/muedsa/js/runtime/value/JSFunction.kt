package com.muedsa.js.runtime.value

import com.muedsa.js.ast.BlockStatement
import com.muedsa.js.ast.Expression
import com.muedsa.js.ast.FunctionDeclaration
import com.muedsa.js.ast.Parameter
import com.muedsa.js.runtime.Environment

/**
 * 用户定义的 JavaScript 函数（包括普通函数、箭头函数、类构造器）。
 *
 * @property name 函数名（匿名函数为空串）
 * @property params 形参列表（支持默认值与 rest）
 * @property body 函数体（箭头函数的表达式体为 null）
 * @property expressionBody 箭头函数的表达式体（块体为 null）
 * @property closure 闭包环境
 * @property strict 函数体是否处于严格模式
 * @property isArrow 是否为箭头函数
 * @property isAsync 是否为 async 函数
 * @property lexicalThis 箭头函数捕获的 this
 * @property isClassConstructor 是否为 class 构造器（不能直接调用）
 * @property homeObject 方法所属对象（super 查找基准）
 * @property superConstructor 派生类构造器的父构造器
 * @property varDeclarations 函数体内需要提升的 var（Parser 预计算）
 * @property functionDeclarations 函数体内需要提升的函数声明（Parser 预计算）
 * @property functionPrototypeObject 函数的 `.prototype` 对象（箭头函数为 null）
 */
class JSFunction(
    val name: String,
    val params: List<Parameter>,
    val body: BlockStatement?,
    val closure: Environment,
    val strict: Boolean,
    prototype: JSObject?,
    val functionPrototypeObject: JSObject?,
    val varDeclarations: List<String> = emptyList(),
    val functionDeclarations: List<FunctionDeclaration> = emptyList(),
    val expressionBody: Expression? = null,
    val isArrow: Boolean = false,
    val isAsync: Boolean = false,
    val lexicalThis: JSValue? = null,
    val isClassConstructor: Boolean = false,
    val isDefaultDerivedConstructor: Boolean = false,
    val homeObject: JSObject? = null,
    val superConstructor: JSValue? = null,
    val superBase: JSObject? = null,
) : JSObject(prototype) {

    /** 函数 length：第一个默认值/rest 参数之前的参数个数。 */
    val functionLength: Int = params.takeWhile { it.defaultValue == null && !it.rest }.size

    override fun toPrimitiveString(): String =
        "function ${if (name.isEmpty()) "" else name}(${params.joinToString(", ") { it.name ?: "[pattern]" }}) { [code] }"

    init {
        defineDataProperty(
            PropertyKey.NAME,
            JSString(name),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        defineDataProperty(
            PropertyKey.LENGTH,
            JSNumber(functionLength.toDouble()),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        if (functionPrototypeObject != null) {
            defineDataProperty(
                PropertyKey.PROTOTYPE,
                functionPrototypeObject,
                writable = true,
                enumerable = false,
                configurable = false,
            )
            // 双向连接：Foo.prototype.constructor === Foo
            functionPrototypeObject.defineDataProperty(
                PropertyKey.CONSTRUCTOR,
                this,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
    }
}
