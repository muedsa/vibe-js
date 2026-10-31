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
    name: String,
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
    isMethod: Boolean = false,
    val lexicalThis: JSValue? = null,
    /** 箭头函数捕获的 new.target */
    val lexicalNewTarget: JSValue? = null,
    /** 函数体语法上是否允许 new.target（箭头函数继承外层） */
    val allowsNewTarget: Boolean = true,
    val isClassConstructor: Boolean = false,
    val isDefaultDerivedConstructor: Boolean = false,
    homeObject: JSObject? = null,
    val superConstructor: JSValue? = null,
    superBase: JSObject? = null,
) : JSObject(prototype) {

    /** 方法所属对象（super 查找基准）；对象字面量方法在构造后补充。 */
    var homeObject: JSObject? = homeObject
        private set

    /** 构造器/方法的 super 基对象（对象方法动态使用 homeObject.prototype）。 */
    var superBase: JSObject? = superBase
        private set

    /** 为对象字面量方法补充 HomeObject 与初始 super 基。 */
    fun setMethodHome(home: JSObject, base: JSObject?) {
        homeObject = home
        superBase = base
    }

    /** 函数名；匿名函数为空串，NamedEvaluation 时可更新。 */
    var name: String = name
        private set

    /** 是否为方法（无 prototype、不可构造）；对象字面量方法在构造后标记。 */
    var isMethod: Boolean = isMethod
        private set

    /** 标记为方法并移除已创建的 prototype 属性。 */
    fun markAsMethod() {
        isMethod = true
        removeOwnPropertyIgnoringAttributes(PropertyKey.PROTOTYPE)
    }

    /** ES2015 SetFunctionName：更新 name 自有属性（configurable，允许重定义）。 */
    fun setName(newName: String) {
        if (name == newName) return
        name = newName
        val existing = getOwnProperty(PropertyKey.NAME)
        // 类静态 name()/get name() 已覆盖内建 name 属性时，保留静态成员
        val builtInName = existing != null && existing.isData && !existing.writable
        if (!builtInName) return
        defineDataProperty(
            PropertyKey.NAME,
            JSString(newName),
            writable = false,
            enumerable = false,
            configurable = true,
        )
    }

    /** 函数 length：第一个默认值/rest 参数之前的参数个数。 */
    val functionLength: Int = params.takeWhile { it.defaultValue == null && !it.rest }.size

    /** 动态构造（Function/AsyncFunction）保留的源码文本，供 Function.prototype.toString 使用。 */
    var sourceText: String? = null

    override fun toPrimitiveString(): String =
        sourceText
            ?: "function ${if (name.isEmpty()) "" else name}(${params.joinToString(", ") { it.name ?: "[pattern]" }}) { [native code] }"

    init {
        // 规范顺序：length 在 name 之前
        defineDataProperty(
            PropertyKey.LENGTH,
            JSNumber(functionLength.toDouble()),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        defineDataProperty(
            PropertyKey.NAME,
            JSString(name),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        if (functionPrototypeObject != null && !isMethod) {
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
