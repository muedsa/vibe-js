package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Reflect 内建对象。
 *
 * 13 个方法都是内部方法的可观察包装：参数校验、调用内部方法、返回布尔/描述符。
 * 与对应的 Object.* 不同，Reflect 方法在失败时返回 false 而不是抛 TypeError
 * （target 非对象等参数错误仍然抛 TypeError）。
 */
object ReflectBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val reflect = JSObject(prototype = intrinsics.objectPrototype)
        reflect.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("Reflect"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        fun defineMethod(
            name: String,
            length: Int,
            body: suspend (Interpreter, List<JSValue>) -> JSValue,
        ) {
            reflect.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, name, length) { interpreter, _, args ->
                    body(interpreter, args)
                },
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }

        fun targetOf(interpreter: Interpreter, args: List<JSValue>, name: String): JSObject {
            val target = args.getOrNull(0)
            if (target !is JSObject) {
                interpreter.typeError("Reflect.$name called on non-object")
            }
            return target
        }

        defineMethod("apply", 3) { interpreter, args ->
            val target = args.getOrNull(0)
            if (target == null || !interpreter.isCallable(target)) {
                interpreter.typeError("Reflect.apply target is not callable")
            }
            val thisArgument = args.getOrElse(1) { JSUndefined }
            val argumentsList = interpreter.createListFromArrayLike(args.getOrElse(2) { JSUndefined })
            interpreter.callFunction(target, thisArgument, argumentsList)
        }

        defineMethod("construct", 2) { interpreter, args ->
            val target = args.getOrNull(0)
            if (target == null || !interpreter.isConstructor(target)) {
                interpreter.typeError("Reflect.construct target is not a constructor")
            }
            val argumentsList = interpreter.createListFromArrayLike(args.getOrElse(1) { JSUndefined })
            val newTarget = args.getOrNull(2) ?: target
            if (!interpreter.isConstructor(newTarget)) {
                interpreter.typeError("Reflect.construct newTarget is not a constructor")
            }
            interpreter.construct(target, argumentsList, newTarget)
        }

        defineMethod("defineProperty", 3) { interpreter, args ->
            val target = targetOf(interpreter, args, "defineProperty")
            val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
            val descriptor = interpreter.toPropertyDescriptor(args.getOrElse(2) { JSUndefined })
                ?: interpreter.typeError("Reflect.defineProperty requires a property descriptor")
            JSBoolean.getJsBoolean(interpreter.internalDefineOwnProperty(target, key, descriptor))
        }

        defineMethod("deleteProperty", 2) { interpreter, args ->
            val target = targetOf(interpreter, args, "deleteProperty")
            val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
            JSBoolean.getJsBoolean(interpreter.internalDeleteProperty(target, key))
        }

        defineMethod("get", 2) { interpreter, args ->
            val target = targetOf(interpreter, args, "get")
            val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
            val receiver = args.getOrNull(2) ?: target
            interpreter.getWithReceiver(target, key, receiver)
        }

        defineMethod("getOwnPropertyDescriptor", 2) { interpreter, args ->
            val target = targetOf(interpreter, args, "getOwnPropertyDescriptor")
            val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
            val descriptor = interpreter.internalGetOwnProperty(target, key)
            if (descriptor == null) JSUndefined else interpreter.fromPropertyDescriptor(descriptor)
        }

        defineMethod("getPrototypeOf", 1) { interpreter, args ->
            val target = targetOf(interpreter, args, "getPrototypeOf")
            interpreter.internalGetPrototypeOf(target) ?: JSNull
        }

        defineMethod("has", 2) { interpreter, args ->
            val target = targetOf(interpreter, args, "has")
            val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
            JSBoolean.getJsBoolean(interpreter.internalHasProperty(target, key))
        }

        defineMethod("isExtensible", 1) { interpreter, args ->
            val target = targetOf(interpreter, args, "isExtensible")
            JSBoolean.getJsBoolean(interpreter.internalIsExtensible(target))
        }

        defineMethod("ownKeys", 1) { interpreter, args ->
            val target = targetOf(interpreter, args, "ownKeys")
            val keys = interpreter.internalOwnPropertyKeys(target)
                .map { interpreter.propertyKeyToValue(it) }
            JSArray(keys.toMutableList(), prototype = intrinsics.arrayPrototype)
        }

        defineMethod("preventExtensions", 1) { interpreter, args ->
            val target = targetOf(interpreter, args, "preventExtensions")
            JSBoolean.getJsBoolean(interpreter.internalPreventExtensions(target))
        }

        defineMethod("set", 3) { interpreter, args ->
            val target = targetOf(interpreter, args, "set")
            val key = interpreter.toPropertyKey(args.getOrElse(1) { JSUndefined })
            val value = args.getOrElse(2) { JSUndefined }
            val receiver = args.getOrNull(3) ?: target
            JSBoolean.getJsBoolean(interpreter.setWithReceiver(target, key, value, receiver))
        }

        defineMethod("setPrototypeOf", 2) { interpreter, args ->
            val target = targetOf(interpreter, args, "setPrototypeOf")
            val protoValue = args.getOrElse(1) { JSUndefined }
            val proto = when (protoValue) {
                is JSNull -> null
                is JSObject -> protoValue
                else -> interpreter.typeError("Reflect.setPrototypeOf prototype must be an object or null")
            }
            JSBoolean.getJsBoolean(interpreter.internalSetPrototypeOf(target, proto))
        }

        intrinsics.globalObject.defineDataProperty(
            key = PropertyKey.Str("Reflect"),
            value = reflect,
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }
}
