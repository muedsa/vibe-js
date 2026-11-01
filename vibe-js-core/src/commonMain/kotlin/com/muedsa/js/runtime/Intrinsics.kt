package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 内建对象原型集合（每个 Engine 实例一份，保证多引擎隔离）。
 *
 * 这些对象是内建构造器与实例的原型；创建顺序很重要：
 * Object.prototype -> Function.prototype -> 其他原型。
 *
 * 阶段 A 只创建骨架，具体方法由 CoreBuiltins 安装。
 */
class Intrinsics(val globalObject: GlobalObject) {

    lateinit var objectPrototype: JSObject
    lateinit var functionPrototype: JSNativeFunction

    /** %eval% 内建函数：直接 eval 判定使用，不受全局属性被覆盖影响。 */
    var evalFunction: JSValue? = null

    /**
     * 标签模板对象缓存（[[TemplateMap]]）：按 AST 节点身份（===）缓存，
     * 同一站点重复求值返回同一冻结数组；每个 Engine（realm）一份。
     */
    val templateCache: MutableList<Pair<Any, JSArray>> = mutableListOf()

    /** %ThrowTypeError% 内建函数：caller/arguments 访问器与严格 arguments.callee 共用同一对象。 */
    var throwTypeErrorFunction: JSNativeFunction? = null

    /** %Function% 构造器：AsyncFunction 等派生构造器以它为 [[Prototype]]。 */
    var functionConstructor: JSValue? = null

    /** 全局 parseInt/parseFloat：Number.parseInt 与全局属性必须是同一函数对象。 */
    var parseIntFunction: JSValue? = null
    var parseFloatFunction: JSValue? = null

    /** Array.prototype.values（arguments 的 @@iterator 复用同一函数）。 */
    var arrayValuesFunction: JSValue? = null
    lateinit var arrayPrototype: JSArray
    lateinit var stringPrototype: JSObject
    lateinit var numberPrototype: JSObject
    lateinit var booleanPrototype: JSObject
    lateinit var symbolPrototype: JSObject
    lateinit var regexpPrototype: JSObject
    lateinit var datePrototype: JSObject
    lateinit var errorPrototype: JSObject
    lateinit var typeErrorPrototype: JSObject
    lateinit var rangeErrorPrototype: JSObject
    lateinit var referenceErrorPrototype: JSObject
    lateinit var syntaxErrorPrototype: JSObject
    lateinit var evalErrorPrototype: JSObject
    lateinit var uriErrorPrototype: JSObject
    /** %SuppressedError.prototype%：DisposableStack 资源释放错误聚合。 */
    lateinit var suppressedErrorPrototype: JSObject
    lateinit var jsonObject: JSObject
    lateinit var mathObject: JSObject
    lateinit var mapPrototype: JSObject
    lateinit var setPrototype: JSObject
    lateinit var weakMapPrototype: JSObject
    lateinit var weakSetPrototype: JSObject
    lateinit var promisePrototype: JSObject

    /** %IteratorPrototype%：所有迭代器共享的原型（含 @@iterator 与辅助方法）。 */
    lateinit var iteratorPrototype: JSObject
    /** %ArrayIteratorPrototype%：数组迭代器专用原型。 */
    lateinit var arrayIteratorPrototype: JSObject
    /** %StringIteratorPrototype%：字符串迭代器原型。 */
    lateinit var stringIteratorPrototype: JSObject
    /** %MapIteratorPrototype%：Map 迭代器原型。 */
    lateinit var mapIteratorPrototype: JSObject
    /** %SetIteratorPrototype%：Set 迭代器原型。 */
    lateinit var setIteratorPrototype: JSObject
    /** %RegExpStringIteratorPrototype%：String.prototype.matchAll 迭代器原型。 */
    lateinit var regexpStringIteratorPrototype: JSObject
    /** %IteratorHelperPrototype%：map/filter 等辅助迭代器的原型。 */
    lateinit var iteratorHelperPrototype: JSObject
    /** %WrapForValidIteratorPrototype%：Iterator.from 包装非迭代器时的原型。 */
    lateinit var wrapForValidIteratorPrototype: JSObject
    /** %Iterator% 构造器（抽象类，供 extends Iterator 使用）。 */
    lateinit var iteratorConstructor: JSNativeFunction

    /** %AsyncFunction.prototype%：普通对象，async 函数的 [[Prototype]]。 */
    lateinit var asyncFunctionPrototype: JSObject

    /** %Promise% 构造器（SpeciesConstructor 的默认值）。 */
    lateinit var promiseConstructor: JSNativeFunction

    /** %AggregateError% 构造器（Promise.any 聚合拒绝原因）。 */
    lateinit var aggregateErrorConstructor: JSNativeFunction

    lateinit var symbolIterator: JSSymbol
    lateinit var symbolAsyncIterator: JSSymbol
    lateinit var symbolDispose: JSSymbol
    lateinit var symbolAsyncDispose: JSSymbol
    lateinit var symbolToStringTag: JSSymbol
    lateinit var symbolSpecies: JSSymbol
    lateinit var symbolHasInstance: JSSymbol
    lateinit var symbolToPrimitive: JSSymbol
    lateinit var symbolIsConcatSpreadable: JSSymbol
    lateinit var symbolUnscopables: JSSymbol
    lateinit var symbolMatch: JSSymbol
    lateinit var symbolReplace: JSSymbol
    lateinit var symbolSearch: JSSymbol
    lateinit var symbolSplit: JSSymbol
    lateinit var symbolMatchAll: JSSymbol

    /** %RegExp% 构造器（RegExp @@split 的 SpeciesConstructor 默认值）。 */
    lateinit var regexpConstructor: JSNativeFunction

    /**
     * 创建原型骨架。必须在安装任何内建之前调用一次。
     */
    fun initialize() {
        // 1. Object.prototype 是原型链的根
        objectPrototype = JSObject(prototype = null)

        // 2. Function.prototype 本身是可调用对象
        functionPrototype = JSNativeFunction(
            name = "",
            length = 0,
            prototype = objectPrototype,
        ) { _, _, _ -> JSUndefined }

        // 3. 全局对象的 [[Prototype]] 是 Object.prototype
        globalObject.prototype = objectPrototype

        // 4. 其他标准原型
        arrayPrototype = JSArray(prototype = objectPrototype)

        stringPrototype = JSObject(prototype = objectPrototype)
        stringPrototype.defineDataProperty(
            key = PropertyKey.LENGTH,
            value = JSNumber.ZERO,
            writable = false,
            enumerable = false,
            configurable = false,
        )

        numberPrototype = JSObject(prototype = objectPrototype)
        booleanPrototype = JSObject(prototype = objectPrototype)
        symbolPrototype = JSObject(prototype = objectPrototype)
        // RegExp.prototype 是普通对象；source/global 等由 RegExpBuiltins 安装为访问器
        regexpPrototype = JSObject(prototype = objectPrototype)
        datePrototype = JSObject(prototype = objectPrototype)

        errorPrototype = JSObject(prototype = objectPrototype)
        errorPrototype.defineDataProperty(
            key = PropertyKey.NAME,
            value = JSString("Error"),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        errorPrototype.defineDataProperty(
            key = PropertyKey.MESSAGE,
            value = JSString(""),
            writable = true,
            enumerable = false,
            configurable = true,
        )

        typeErrorPrototype = createErrorPrototype("TypeError")
        rangeErrorPrototype = createErrorPrototype("RangeError")
        referenceErrorPrototype = createErrorPrototype("ReferenceError")
        syntaxErrorPrototype = createErrorPrototype("SyntaxError")
        evalErrorPrototype = createErrorPrototype("EvalError")
        uriErrorPrototype = createErrorPrototype("URIError")
        suppressedErrorPrototype = createErrorPrototype("SuppressedError")

        jsonObject = JSObject(prototype = objectPrototype)
        mathObject = JSObject(prototype = objectPrototype)
        mapPrototype = JSObject(prototype = objectPrototype)
        setPrototype = JSObject(prototype = objectPrototype)
        weakMapPrototype = JSObject(prototype = objectPrototype)
        weakSetPrototype = JSObject(prototype = objectPrototype)
        promisePrototype = JSObject(prototype = objectPrototype)
        iteratorPrototype = JSObject(prototype = objectPrototype)
        arrayIteratorPrototype = JSObject(prototype = iteratorPrototype)
        stringIteratorPrototype = JSObject(prototype = iteratorPrototype)
        mapIteratorPrototype = JSObject(prototype = iteratorPrototype)
        setIteratorPrototype = JSObject(prototype = iteratorPrototype)
        regexpStringIteratorPrototype = JSObject(prototype = iteratorPrototype)
        iteratorHelperPrototype = JSObject(prototype = iteratorPrototype)
        wrapForValidIteratorPrototype = JSObject(prototype = iteratorPrototype)
        // 规范：%AsyncFunction.prototype% 是普通对象（非函数），其 [[Prototype]] 为 Function.prototype
        asyncFunctionPrototype = JSObject(prototype = functionPrototype)

        symbolIterator = JSSymbol("Symbol.iterator")
        symbolAsyncIterator = JSSymbol("Symbol.asyncIterator")
        symbolDispose = JSSymbol("Symbol.dispose")
        symbolAsyncDispose = JSSymbol("Symbol.asyncDispose")
        symbolToStringTag = JSSymbol("Symbol.toStringTag")
        symbolSpecies = JSSymbol("Symbol.species")
        symbolHasInstance = JSSymbol("Symbol.hasInstance")
        symbolToPrimitive = JSSymbol("Symbol.toPrimitive")
        symbolIsConcatSpreadable = JSSymbol("Symbol.isConcatSpreadable")
        symbolUnscopables = JSSymbol("Symbol.unscopables")
        symbolMatch = JSSymbol("Symbol.match")
        symbolReplace = JSSymbol("Symbol.replace")
        symbolSearch = JSSymbol("Symbol.search")
        symbolSplit = JSSymbol("Symbol.split")
        symbolMatchAll = JSSymbol("Symbol.matchAll")
    }

    /** 创建错误子类原型（链到 Error.prototype）。 */
    private fun createErrorPrototype(name: String): JSObject {
        val prototype = JSObject(prototype = errorPrototype)
        prototype.defineDataProperty(
            key = PropertyKey.NAME,
            value = JSString(name),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        prototype.defineDataProperty(
            key = PropertyKey.MESSAGE,
            value = JSString(""),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        return prototype
    }
}
