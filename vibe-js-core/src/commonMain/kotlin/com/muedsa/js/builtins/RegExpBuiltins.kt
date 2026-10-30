package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSRegExp
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * RegExp 构造器与 RegExp.prototype。
 */
object RegExpBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val regexpPrototype = intrinsics.regexpPrototype

        val regexpConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "RegExp",
            length = 2,
            prototypeObject = regexpPrototype,
        ) { interpreter, _, args ->
            val patternValue = args.getOrNull(0) ?: JSUndefined
            val flagsValue = args.getOrNull(1)
            val hasFlags = flagsValue != null && flagsValue !is JSUndefined
            when {
                patternValue is JSRegExp && !hasFlags -> patternValue
                else -> {
                    val pattern = if (patternValue is JSUndefined) {
                        ""
                    } else {
                        interpreter.toStringValue(patternValue)
                    }
                    val flags = if (hasFlags) interpreter.toStringValue(flagsValue) else ""
                    interpreter.createRegExp(pattern, flags)
                }
            }
        }

        regexpPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = regexpConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Str("exec"),
            value = BuiltinHelpers.method(intrinsics, "exec", 1) { interpreter, thisValue, args ->
                val regexp = requireRegExp(interpreter, thisValue, "RegExp.prototype.exec")
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                exec(interpreter, regexp, input)
            },
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("RegExp"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Str("test"),
            value = BuiltinHelpers.method(intrinsics, "test", 1) { interpreter, thisValue, args ->
                val regexp = requireRegExp(interpreter, thisValue, "RegExp.prototype.test")
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSBoolean.getJsBoolean(exec(interpreter, regexp, input) !is JSNull)
            },
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                when (thisValue) {
                    is JSRegExp -> JSString(thisValue.toPrimitiveString())
                    is JSObject -> {
                        val source = interpreter.toStringValue(
                            interpreter.getV(thisValue, PropertyKey.Str("source"))
                        )
                        val flags = interpreter.toStringValue(
                            interpreter.getV(thisValue, PropertyKey.Str("flags"))
                        )
                        JSString("/$source/$flags")
                    }

                    else -> interpreter.typeError("RegExp.prototype.toString requires that 'this' be a RegExp")
                }
            },
        )

        registry.defineGlobal("RegExp", regexpConstructor)
    }

    private fun requireRegExp(interpreter: Interpreter, thisValue: JSValue, name: String): JSRegExp =
        thisValue as? JSRegExp ?: interpreter.typeError("$name requires that 'this' be a RegExp")

    /**
     * 执行正则匹配并构造结果数组（含 index/input）。
     */
    private fun exec(interpreter: Interpreter, regexp: JSRegExp, input: String): JSValue {
        val useLastIndex = regexp.global || regexp.sticky
        val start = if (useLastIndex) regexp.getLastIndex() else 0
        if (start < 0 || start > input.length) {
            if (useLastIndex) regexp.setLastIndex(0)
            return JSNull
        }

        val match = try {
            regexp.regex.find(input, start)
        } catch (_: Throwable) {
            interpreter.typeError("Regular expression execution failed")
        }

        if (match == null || (regexp.sticky && match.range.first != start)) {
            if (useLastIndex) regexp.setLastIndex(0)
            return JSNull
        }

        if (useLastIndex) {
            regexp.setLastIndex(match.range.first + match.value.length)
        }

        val values = ArrayList<JSValue?>(match.groupValues.size)
        for (group in match.groupValues) {
            values.add(JSString(group))
        }
        val result = JSArray(values, prototype = interpreter.intrinsics.arrayPrototype)
        result.defineDataProperty(
            key = PropertyKey.Str("index"),
            value = JSNumber(match.range.first.toDouble()),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        result.defineDataProperty(
            key = PropertyKey.Str("input"),
            value = JSString(input),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        return result
    }
}
