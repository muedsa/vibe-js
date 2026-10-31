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
    lateinit var jsonObject: JSObject
    lateinit var mathObject: JSObject
    lateinit var mapPrototype: JSObject
    lateinit var setPrototype: JSObject
    lateinit var weakMapPrototype: JSObject
    lateinit var weakSetPrototype: JSObject
    lateinit var promisePrototype: JSObject

    /** %AsyncFunction.prototype%：普通对象，async 函数的 [[Prototype]]。 */
    lateinit var asyncFunctionPrototype: JSObject

    /** %Promise% 构造器（SpeciesConstructor 的默认值）。 */
    lateinit var promiseConstructor: JSNativeFunction

    /** %AggregateError% 构造器（Promise.any 聚合拒绝原因）。 */
    lateinit var aggregateErrorConstructor: JSNativeFunction

    lateinit var symbolIterator: JSSymbol
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

        jsonObject = JSObject(prototype = objectPrototype)
        mathObject = JSObject(prototype = objectPrototype)
        mapPrototype = JSObject(prototype = objectPrototype)
        setPrototype = JSObject(prototype = objectPrototype)
        weakMapPrototype = JSObject(prototype = objectPrototype)
        weakSetPrototype = JSObject(prototype = objectPrototype)
        promisePrototype = JSObject(prototype = objectPrototype)
        // 规范：%AsyncFunction.prototype% 是普通对象（非函数），其 [[Prototype]] 为 Function.prototype
        asyncFunctionPrototype = JSObject(prototype = functionPrototype)

        symbolIterator = JSSymbol("Symbol.iterator")
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
