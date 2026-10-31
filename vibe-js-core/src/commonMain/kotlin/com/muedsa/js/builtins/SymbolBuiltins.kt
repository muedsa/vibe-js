package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Symbol 构造器与 Symbol.prototype。
 */
object SymbolBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val symbolPrototype = intrinsics.symbolPrototype

        val globalRegistry = mutableMapOf<String, JSSymbol>()
        val keyRegistry = mutableMapOf<JSSymbol, String>()

        val symbolConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Symbol",
            length = 0,
            prototypeObject = symbolPrototype,
        ) { interpreter, _, args ->
            if (interpreter.nativeConstructCall) {
                interpreter.typeError("Symbol is not a constructor")
            }
            val description = args.getOrNull(0)
                ?.takeUnless { it is JSUndefined }
                ?.let { interpreter.toStringValue(it) }
            JSSymbol(description)
        }

        symbolPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = symbolConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        symbolPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                val symbol = thisSymbolValue(thisValue)
                    ?: interpreter.typeError("Symbol.prototype.toString requires that 'this' be a Symbol")
                JSString(symbol.toPrimitiveString())
            },
        )
        symbolPrototype.defineDataProperty(
            key = PropertyKey.Str("valueOf"),
            value = BuiltinHelpers.method(intrinsics, "valueOf", 0) { interpreter, thisValue, _ ->
                thisSymbolValue(thisValue)
                    ?: interpreter.typeError("Symbol.prototype.valueOf requires that 'this' be a Symbol")
            },
        )
        // ES2015 Symbol.prototype[@@toPrimitive]：返回 Symbol 原始值
        symbolPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToPrimitive),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.toPrimitive]", 1) { interpreter, thisValue, _ ->
                thisSymbolValue(thisValue)
                    ?: interpreter.typeError("Symbol.prototype[Symbol.toPrimitive] requires that 'this' be a Symbol")
            },
            writable = false,
        )
        symbolPrototype.defineAccessorProperty(
            key = PropertyKey.Str("description"),
            get = BuiltinHelpers.method(intrinsics, "get description", 0) { interpreter, thisValue, _ ->
                val symbol = thisSymbolValue(thisValue)
                    ?: interpreter.typeError("Symbol.prototype.description requires that 'this' be a Symbol")
                symbol.description?.let { JSString(it) } ?: JSUndefined
            },
            set = null,
            enumerable = false,
            configurable = true,
        )
        symbolPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("Symbol"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        // 知名 Symbol
        fun wellKnown(name: String, symbol: JSSymbol) {
            symbolConstructor.defineDataProperty(
                key = PropertyKey.Str(name),
                value = symbol,
                writable = false,
                enumerable = false,
                configurable = false,
            )
        }
        wellKnown("iterator", intrinsics.symbolIterator)
        wellKnown("toStringTag", intrinsics.symbolToStringTag)
        wellKnown("species", intrinsics.symbolSpecies)
        wellKnown("hasInstance", intrinsics.symbolHasInstance)
        wellKnown("toPrimitive", intrinsics.symbolToPrimitive)
        wellKnown("isConcatSpreadable", intrinsics.symbolIsConcatSpreadable)
        wellKnown("unscopables", intrinsics.symbolUnscopables)
        wellKnown("match", intrinsics.symbolMatch)
        wellKnown("replace", intrinsics.symbolReplace)
        wellKnown("search", intrinsics.symbolSearch)
        wellKnown("split", intrinsics.symbolSplit)
        wellKnown("matchAll", intrinsics.symbolMatchAll)

        symbolConstructor.defineDataProperty(
            key = PropertyKey.Str("for"),
            value = BuiltinHelpers.method(intrinsics, "for", 1) { interpreter, _, args ->
                val key = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                globalRegistry.getOrPut(key) {
                    JSSymbol(key).also { keyRegistry[it] = key }
                }
            },
        )
        symbolConstructor.defineDataProperty(
            key = PropertyKey.Str("keyFor"),
            value = BuiltinHelpers.method(intrinsics, "keyFor", 1) { interpreter, _, args ->
                val symbol = args.getOrNull(0) as? JSSymbol
                    ?: interpreter.typeError("Symbol.keyFor requires that the first argument be a symbol")
                keyRegistry[symbol]?.let { JSString(it) } ?: JSUndefined
            },
        )

        registry.defineGlobal("Symbol", symbolConstructor)
    }

    /** 提取 this 的 Symbol 原始值（支持 Symbol 包装对象）。 */
    private fun thisSymbolValue(value: com.muedsa.js.runtime.value.JSValue): JSSymbol? = when (value) {
        is JSSymbol -> value
        is JSBoxedPrimitive -> value.primitive as? JSSymbol
        else -> null
    }
}
