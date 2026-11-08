package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSProxy
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Proxy 构造器与 Proxy.revocable。
 *
 * Proxy 没有 prototype 属性；构造器只提供 [[Construct]]，直接调用会抛 TypeError。
 * trap 的实际执行与不变量校验位于 Interpreter 的内部方法分派中。
 */
object ProxyBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics

        val constructor = JSNativeFunction(
            name = "Proxy",
            length = 2,
            prototype = intrinsics.functionPrototype,
            constructFunction = { interpreter, args, _ -> createProxy(interpreter, args) },
            function = { interpreter, _, _ -> interpreter.typeError("Proxy is not a function") },
        )

        constructor.defineDataProperty(
            key = PropertyKey.Str("revocable"),
            value = BuiltinHelpers.method(intrinsics, "revocable", 2) { interpreter, _, args ->
                val proxy = createProxy(interpreter, args)
                val result = JSObject(prototype = intrinsics.objectPrototype)
                result.defineDataProperty(PropertyKey.Str("proxy"), proxy)
                result.defineDataProperty(
                    key = PropertyKey.Str("revoke"),
                    // 规范：revoke 函数名为空字符串
                    value = BuiltinHelpers.method(intrinsics, "", 0) { _, _, _ ->
                        proxy.revoked = true
                        JSUndefined
                    },
                )
                result
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        intrinsics.globalObject.defineDataProperty(
            key = PropertyKey.Str("Proxy"),
            value = constructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }

    /** 校验 target/handler 均为对象并创建代理。 */
    private fun createProxy(interpreter: Interpreter, args: List<JSValue>): JSProxy {
        val target = args.getOrNull(0)
        val handler = args.getOrNull(1)
        if (target !is JSObject) {
            interpreter.typeError("Cannot create proxy with a non-object as target")
        }
        if (handler !is JSObject) {
            interpreter.typeError("Cannot create proxy with a non-object as handler")
        }
        // Proxy 的 [[Prototype]] 由 target 动态决定，JSObject 基类构造时没有原型；
        // 这里显式登记一次分配，避免通过 Proxy 循环绕过对象分配预算。
        val proxy = JSProxy(target, handler)
        proxy.attachAllocationBudget(interpreter.context.objectAllocations)
        return proxy
    }
}
