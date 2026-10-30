package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * ECMAScript 全局对象上的标准函数与常量。
 *
 * 注意：console / window / setTimeout 等宿主能力不在这里，
 * 它们由独立的宿主模块注册。
 */
object GlobalBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics

        // 不可写、不可配置的全局常量
        registry.defineGlobal(
            name = "undefined",
            value = JSUndefined,
            writable = false,
            enumerable = false,
            configurable = false,
        )
        registry.defineGlobal(
            name = "NaN",
            value = JSNumber.NAN,
            writable = false,
            enumerable = false,
            configurable = false,
        )
        registry.defineGlobal(
            name = "Infinity",
            value = JSNumber.POSITIVE_INFINITY,
            writable = false,
            enumerable = false,
            configurable = false,
        )

        // globalThis 指向全局对象自身
        registry.defineGlobal(
            name = "globalThis",
            value = registry.globalObject,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        // parseInt / parseFloat
        registry.defineGlobal(
            name = "parseInt",
            value = BuiltinHelpers.method(intrinsics, "parseInt", length = 2) { interpreter, _, args ->
                NumberParsing.parseInt(interpreter, args)
            },
        )
        registry.defineGlobal(
            name = "parseFloat",
            value = BuiltinHelpers.method(intrinsics, "parseFloat", length = 1) { interpreter, _, args ->
                NumberParsing.parseFloat(interpreter, args)
            },
        )

        // 全局 isNaN / isFinite（会做 ToNumber 转换）
        registry.defineGlobal(
            name = "isNaN",
            value = BuiltinHelpers.method(intrinsics, "isNaN", length = 1) { interpreter, _, args ->
                JSBoolean.getJsBoolean(interpreter.toNumber(args.getOrElse(0) { JSUndefined }).isNaN())
            },
        )
        registry.defineGlobal(
            name = "isFinite",
            value = BuiltinHelpers.method(intrinsics, "isFinite", length = 1) { interpreter, _, args ->
                JSBoolean.getJsBoolean(interpreter.toNumber(args.getOrElse(0) { JSUndefined }).isFinite())
            },
        )

        // Math / JSON 对象（原型骨架已在 Intrinsics 创建，方法由对应模块安装）
        registry.defineGlobal("Math", intrinsics.mathObject)
        registry.defineGlobal("JSON", intrinsics.jsonObject)
    }
}
