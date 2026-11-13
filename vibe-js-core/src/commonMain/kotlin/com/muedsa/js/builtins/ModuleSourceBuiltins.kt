package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.PropertyKey

/**
 * %AbstractModuleSource% 内在对象。
 *
 * 该构造器不是全局属性，仅由 host/Test262 harness 通过 intrinsic 暴露；
 * module source 对象的直接原型链必须经过它的 prototype。
 */
internal object ModuleSourceBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = JSObject(prototype = intrinsics.objectPrototype)
        val constructor = JSNativeFunction(
            name = "AbstractModuleSource",
            length = 0,
            prototype = intrinsics.functionPrototype,
            functionPrototypeObject = prototype,
            constructFunction = { interpreter, _, _ ->
                interpreter.typeError("AbstractModuleSource is not constructable")
            },
            function = { interpreter, _, _ ->
                interpreter.typeError("AbstractModuleSource is not callable")
            },
        )

        // 规范要求 prototype.@@toStringTag 是 getter；无内部槽时返回 undefined。
        prototype.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            get = JSNativeFunction(
                name = "get [Symbol.toStringTag]",
                length = 0,
                prototype = intrinsics.functionPrototype,
            ) { _, thisValue, _ ->
                if (thisValue is JSObject) {
                    thisValue.moduleSourceClassName?.let(::JSString) ?: JSUndefined
                } else {
                    JSUndefined
                }
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        intrinsics.abstractModuleSource = constructor
        registry.defineIntrinsic("AbstractModuleSource", constructor)
    }
}
