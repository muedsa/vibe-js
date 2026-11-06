package com.muedsa.js.runtime.value

import com.muedsa.js.ast.BlockStatement
import com.muedsa.js.ast.Expression
import com.muedsa.js.ast.FunctionDeclaration
import com.muedsa.js.ast.Parameter
import com.muedsa.js.runtime.Environment
import com.muedsa.js.lexer.SourceInfo

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
 * @property isGenerator 是否为同步生成器函数（调用返回 [JSGenerator]，不执行函数体）
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
    val isGenerator: Boolean = false,
    isMethod: Boolean = false,
    val lexicalThis: JSValue? = null,
    /** 箭头函数捕获的 this 绑定来源（用于派生构造器中 this 的 TDZ 判定） */
    val lexicalThisSource: ThisBindingSource? = null,
    /** 箭头函数继承的最近非箭头函数（super() 调用需要找到外层构造器） */
    val lexicalFunction: JSFunction? = null,
    /** 箭头函数捕获的 new.target */
    val lexicalNewTarget: JSValue? = null,
    /** 函数体语法上是否允许 new.target（箭头函数继承外层） */
    val allowsNewTarget: Boolean = true,
    /** class 字段初始化器/静态块语境：直接 eval 内也禁止 arguments 引用 */
    val forbidsArguments: Boolean = false,
    /** 字段初始化器的 NamedEvaluation 名称提示（如 x = class {} 的 "x"） */
    val namedEvaluationHint: String? = null,
    /** 闭包捕获的私有名环境（class 成员与嵌套函数使用） */
    val privateEnvironment: PrivateEnvironment? = null,
    val isClassConstructor: Boolean = false,
    val isDefaultDerivedConstructor: Boolean = false,
    homeObject: JSObject? = null,
    val superConstructor: JSValue? = null,
    superBase: JSObject? = null,
    /** 函数定义所在的脚本；跨多次 run 调用时不能使用调用方脚本替代。 */
    val sourceInfo: SourceInfo = SourceInfo(),
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

    /** 标记为方法；普通方法移除已创建的 prototype 属性，生成器方法按规范保留。 */
    fun markAsMethod() {
        isMethod = true
        if (!isGenerator) removeOwnPropertyIgnoringAttributes(PropertyKey.PROTOTYPE)
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

    /** class 实例字段初始化记录（class 求值时按源码顺序创建）。 */
    var instanceFields: List<InstanceField> = emptyList()

    /** 实例私有方法/访问器：构造实例时先于字段安装（品牌检查立即可用）。 */
    var privateMethods: List<Pair<PrivateName, PrivateSlot>> = emptyList()

    override fun toPrimitiveString(): String =
        sourceText
            ?: "function${if (isGenerator) "*" else ""} ${nativeFunctionName()}" +
            "(${params.joinToString(", ") { it.name ?: "[pattern]" }}) { [native code] }"

    /** 私有名（#x）等非法 IdentifierName 在 NativeFunction 形式中省略。 */
    private fun nativeFunctionName(): String {
        if (name.isEmpty()) return ""
        val first = name[0]
        if (first != '_' && first != '$' && !first.isLetter()) return ""
        for (character in name) {
            if (character != '_' && character != '$' && !character.isLetterOrDigit()) return ""
        }
        return name
    }

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
        // 普通方法没有 own "prototype"；生成器方法按规范仍保留 .prototype
        if (functionPrototypeObject != null && (!isMethod || isGenerator)) {
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

/** 箭头函数捕获的 this 绑定来源（Execution Context 实现；支持 this 的 TDZ 语义）。 */
interface ThisBindingSource {
    var boundThis: JSValue
    var thisBound: Boolean
}

/** class 实例字段的初始化记录：公有属性键或私有名，二者恰有一个非空。 */
class InstanceField(
    val key: PropertyKey?,
    val privateName: PrivateName?,
    val initializer: JSFunction,
)
