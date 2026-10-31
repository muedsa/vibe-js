package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.PropertyKey

/**
 * %AsyncFunction% 构造器与 %AsyncFunction.prototype%。
 *
 * 二者都不是全局对象：只能通过 async 函数的 constructor 属性访问。
 * AsyncFunction.prototype 是普通对象（不可调用），其 [[Prototype]] 为 Function.prototype。
 */
object AsyncFunctionBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.asyncFunctionPrototype

        val asyncFunctionConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "AsyncFunction",
            length = 1,
            prototypeObject = prototype,
        ) { interpreter, _, args ->
            // 与 Function 构造器相同的参数/函数体转换顺序
            val params = if (args.size > 1) {
                args.dropLast(1).map { interpreter.toStringValue(it) }
            } else {
                emptyList()
            }
            val body = if (args.isEmpty()) "" else interpreter.toStringValue(args.last())
            interpreter.createDynamicAsyncFunction(params, body)
        }

        // 规范：AsyncFunction.prototype.constructor 不可写、不可枚举、可配置
        prototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = asyncFunctionConstructor,
            writable = false,
            enumerable = false,
            configurable = true,
        )
        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("AsyncFunction"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
    }
}
