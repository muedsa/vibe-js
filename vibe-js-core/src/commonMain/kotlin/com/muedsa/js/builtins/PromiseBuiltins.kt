package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.PromiseCapability
import com.muedsa.js.runtime.PromiseSupport
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Promise 构造器与 Promise.prototype。
 */
object PromiseBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.promisePrototype

        val promiseConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Promise",
            length = 1,
            prototypeObject = prototype,
        ) { interpreter, _, args ->
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor Promise requires 'new'")
            }
            val executor = args.getOrNull(0) ?: JSUndefined
            if (!interpreter.isCallable(executor)) {
                interpreter.typeError("Promise resolver is not a function")
            }
            val promise = JSPromise(prototype)
            // CreateResolvingFunctions：共享 [[AlreadyResolved]]，重复 resolve/reject 被忽略
            val (resolveFunction, rejectFunction) = PromiseSupport.createResolvingFunctions(interpreter, promise)
            try {
                interpreter.callFunction(executor, JSUndefined, listOf(resolveFunction, rejectFunction))
            } catch (error: JSException) {
                PromiseSupport.reject(interpreter, promise, error.value)
            }
            promise
        }

        // SpeciesConstructor 的默认构造器
        intrinsics.promiseConstructor = promiseConstructor

        prototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = promiseConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("Promise"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        prototype.defineDataProperty(
            key = PropertyKey.Str("then"),
            value = BuiltinHelpers.method(intrinsics, "then", 2) { interpreter, thisValue, args ->
                val promise = requirePromise(interpreter, thisValue, "Promise.prototype.then")
                // 规范：按 SpeciesConstructor 创建 capability，回调结果交给其 resolve/reject
                val species = interpreter.speciesConstructor(promise, intrinsics.promiseConstructor)
                val capability = PromiseSupport.newPromiseCapability(interpreter, species)
                PromiseSupport.performThen(
                    interpreter,
                    promise,
                    args.getOrNull(0) ?: JSUndefined,
                    args.getOrNull(1) ?: JSUndefined,
                    capability,
                )
                capability.promise
            },
        )
        prototype.defineDataProperty(
            key = PropertyKey.Str("catch"),
            value = BuiltinHelpers.method(intrinsics, "catch", 1) { interpreter, thisValue, args ->
                // 规范 25.6.5.1：泛化为 Invoke(this, "then", «undefined, onRejected»)
                if (thisValue is JSUndefined || thisValue is JSNull) {
                    interpreter.typeError("Promise.prototype.catch called on null or undefined")
                }
                val thenValue = interpreter.getV(thisValue, PropertyKey.Str("then"))
                if (!interpreter.isCallable(thenValue)) {
                    interpreter.typeError("Promise.prototype.catch requires 'then' to be callable")
                }
                interpreter.callFunction(
                    thenValue,
                    thisValue,
                    listOf(JSUndefined, args.getOrNull(0) ?: JSUndefined),
                )
            },
        )
        prototype.defineDataProperty(
            key = PropertyKey.Str("finally"),
            value = BuiltinHelpers.method(intrinsics, "finally", 1) { interpreter, thisValue, args ->
                val promise = requirePromise(interpreter, thisValue, "Promise.prototype.finally")
                val callback = args.getOrNull(0) ?: JSUndefined
                val onFulfilled = JSNativeFunction(
                    name = "",
                    length = 1,
                    prototype = intrinsics.functionPrototype,
                ) { inner, _, handlerArgs ->
                    if (inner.isCallable(callback)) {
                        inner.callFunction(callback, JSUndefined, emptyList())
                    }
                    handlerArgs.getOrElse(0) { JSUndefined }
                }
                val onRejected = JSNativeFunction(
                    name = "",
                    length = 1,
                    prototype = intrinsics.functionPrototype,
                ) { inner, _, handlerArgs ->
                    if (inner.isCallable(callback)) {
                        inner.callFunction(callback, JSUndefined, emptyList())
                    }
                    throw JSException(handlerArgs.getOrElse(0) { JSUndefined })
                }
                PromiseSupport.then(interpreter, promise, onFulfilled, onRejected)
            },
        )

        // Promise[@@species]：返回 this，子类因此默认沿用自身作为 then 的结果构造器
        promiseConstructor.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolSpecies),
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.species]", 0) { _, thisValue, _ ->
                thisValue
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        installStaticMethods(intrinsics, promiseConstructor)
        registry.defineGlobal("Promise", promiseConstructor)
    }

    private fun installStaticMethods(
        intrinsics: com.muedsa.js.runtime.Intrinsics,
        promiseConstructor: JSNativeFunction,
    ) {
        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("resolve"),
            value = BuiltinHelpers.method(intrinsics, "resolve", 1) { interpreter, thisValue, args ->
                if (!interpreter.isConstructor(thisValue)) {
                    interpreter.typeError("Promise.resolve requires a constructor as this")
                }
                val capability = PromiseSupport.newPromiseCapability(interpreter, thisValue)
                val value = args.getOrElse(0) { JSUndefined }
                // 规范：同构造器的 Promise 原样返回
                if (value is JSPromise) {
                    val valueConstructor = interpreter.getV(value, PropertyKey.CONSTRUCTOR)
                    if (valueConstructor === thisValue) return@method value
                }
                interpreter.callFunction(capability.resolve, JSUndefined, listOf(value))
                capability.promise
            },
        )
        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("reject"),
            value = BuiltinHelpers.method(intrinsics, "reject", 1) { interpreter, thisValue, args ->
                if (!interpreter.isConstructor(thisValue)) {
                    interpreter.typeError("Promise.reject requires a constructor as this")
                }
                val capability = PromiseSupport.newPromiseCapability(interpreter, thisValue)
                interpreter.callFunction(
                    capability.reject,
                    JSUndefined,
                    listOf(args.getOrElse(0) { JSUndefined }),
                )
                capability.promise
            },
        )

        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("all"),
            value = BuiltinHelpers.method(intrinsics, "all", 1) { interpreter, thisValue, args ->
                if (!interpreter.isConstructor(thisValue)) {
                    interpreter.typeError("Promise.all requires a constructor as this")
                }
                val capability = PromiseSupport.newPromiseCapability(interpreter, thisValue)
                // GetPromiseResolve 的 abrupt completion 转为 capability 拒绝
                val resolveMethod = try {
                    interpreter.getV(thisValue, PropertyKey.Str("resolve"))
                } catch (error: JSException) {
                    rejectCapability(interpreter, capability, error.value)
                    return@method capability.promise
                }
                if (!interpreter.isCallable(resolveMethod)) {
                    rejectCapability(
                        interpreter,
                        capability,
                        typeErrorValue(interpreter, "Promise.resolve is not a function"),
                    )
                    return@method capability.promise
                }
                val results = ArrayList<JSValue?>()
                // remainingElementsCount 初始为 1，循环结束后减 1，保证空迭代器与
                // 同步 thenable 都只解析一次
                var remaining = 1
                try {
                    interpreter.consumeIterator(args.getOrElse(0) { JSUndefined }) { item ->
                        val itemPromise = interpreter.callFunction(
                            resolveMethod,
                            thisValue,
                            listOf(item),
                        )
                        val thenMethod = interpreter.getV(itemPromise, PropertyKey.Str("then"))
                        if (!interpreter.isCallable(thenMethod)) {
                            interpreter.typeError("Promise.prototype.then is not a function")
                        }
                        val index = results.size
                        results.add(JSUndefined)
                        remaining++
                        val onFulfilled = JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            results[index] = handlerArgs.getOrElse(0) { JSUndefined }
                            remaining--
                            if (remaining == 0) {
                                resolveCapability(
                                    interpreter,
                                    capability,
                                    JSArray(results.toMutableList(), prototype = intrinsics.arrayPrototype),
                                )
                            }
                            JSUndefined
                        }
                        val onRejected = JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            rejectCapability(
                                interpreter,
                                capability,
                                handlerArgs.getOrElse(0) { JSUndefined },
                            )
                            JSUndefined
                        }
                        interpreter.callFunction(
                            thenMethod,
                            itemPromise,
                            listOf(onFulfilled, onRejected),
                        )
                    }
                } catch (error: JSException) {
                    // 迭代器已在 consumeIterator 中关闭；错误转为 capability 的拒绝
                    rejectCapability(interpreter, capability, error.value)
                    return@method capability.promise
                }
                remaining--
                if (remaining == 0) {
                    // capability.resolve 自身抛错属于 abrupt completion：转为拒绝且不关闭迭代器
                    try {
                        resolveCapability(
                            interpreter,
                            capability,
                            JSArray(mutableListOf(), prototype = intrinsics.arrayPrototype),
                        )
                    } catch (error: JSException) {
                        rejectCapability(interpreter, capability, error.value)
                    }
                }
                capability.promise
            },
        )

        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("race"),
            value = BuiltinHelpers.method(intrinsics, "race", 1) { interpreter, thisValue, args ->
                if (!interpreter.isConstructor(thisValue)) {
                    interpreter.typeError("Promise.race requires a constructor as this")
                }
                val capability = PromiseSupport.newPromiseCapability(interpreter, thisValue)
                // GetPromiseResolve 的 abrupt completion 转为 capability 拒绝
                val resolveMethod = try {
                    interpreter.getV(thisValue, PropertyKey.Str("resolve"))
                } catch (error: JSException) {
                    rejectCapability(interpreter, capability, error.value)
                    return@method capability.promise
                }
                if (!interpreter.isCallable(resolveMethod)) {
                    rejectCapability(
                        interpreter,
                        capability,
                        typeErrorValue(interpreter, "Promise.resolve is not a function"),
                    )
                    return@method capability.promise
                }
                try {
                    interpreter.consumeIterator(args.getOrElse(0) { JSUndefined }) { item ->
                        val itemPromise = interpreter.callFunction(
                            resolveMethod,
                            thisValue,
                            listOf(item),
                        )
                        val thenMethod = interpreter.getV(itemPromise, PropertyKey.Str("then"))
                        if (!interpreter.isCallable(thenMethod)) {
                            interpreter.typeError("Promise.prototype.then is not a function")
                        }
                        interpreter.callFunction(
                            thenMethod,
                            itemPromise,
                            listOf(
                                JSNativeFunction(
                                    name = "",
                                    length = 1,
                                    prototype = intrinsics.functionPrototype,
                                ) { _, _, handlerArgs ->
                                    resolveCapability(
                                        interpreter,
                                        capability,
                                        handlerArgs.getOrElse(0) { JSUndefined },
                                    )
                                    JSUndefined
                                },
                                JSNativeFunction(
                                    name = "",
                                    length = 1,
                                    prototype = intrinsics.functionPrototype,
                                ) { _, _, handlerArgs ->
                                    rejectCapability(
                                        interpreter,
                                        capability,
                                        handlerArgs.getOrElse(0) { JSUndefined },
                                    )
                                    JSUndefined
                                },
                            ),
                        )
                    }
                } catch (error: JSException) {
                    rejectCapability(interpreter, capability, error.value)
                }
                capability.promise
            },
        )

        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("allSettled"),
            value = BuiltinHelpers.method(intrinsics, "allSettled", 1) { interpreter, thisValue, args ->
                if (!interpreter.isConstructor(thisValue)) {
                    interpreter.typeError("Promise.allSettled requires a constructor as this")
                }
                val capability = PromiseSupport.newPromiseCapability(interpreter, thisValue)
                val resolveMethod = try {
                    interpreter.getV(thisValue, PropertyKey.Str("resolve"))
                } catch (error: JSException) {
                    rejectCapability(interpreter, capability, error.value)
                    return@method capability.promise
                }
                if (!interpreter.isCallable(resolveMethod)) {
                    rejectCapability(
                        interpreter,
                        capability,
                        typeErrorValue(interpreter, "Promise.resolve is not a function"),
                    )
                    return@method capability.promise
                }
                val results = ArrayList<JSValue?>()
                var remaining = 1
                try {
                    interpreter.consumeIterator(args.getOrElse(0) { JSUndefined }) { item ->
                        val itemPromise = interpreter.callFunction(resolveMethod, thisValue, listOf(item))
                        val thenMethod = interpreter.getV(itemPromise, PropertyKey.Str("then"))
                        if (!interpreter.isCallable(thenMethod)) {
                            interpreter.typeError("Promise.prototype.then is not a function")
                        }
                        val index = results.size
                        results.add(JSUndefined)
                        remaining++
                        val alreadyCalled = booleanArrayOf(false)
                        val onFulfilled = JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            if (!alreadyCalled[0]) {
                                alreadyCalled[0] = true
                                val entry = JSObject(prototype = intrinsics.objectPrototype)
                                entry.defineDataProperty(
                                    PropertyKey.Str("status"),
                                    JSString("fulfilled"),
                                    writable = true,
                                    enumerable = true,
                                    configurable = true,
                                )
                                entry.defineDataProperty(
                                    PropertyKey.Str("value"),
                                    handlerArgs.getOrElse(0) { JSUndefined },
                                    writable = true,
                                    enumerable = true,
                                    configurable = true,
                                )
                                results[index] = entry
                                remaining--
                                if (remaining == 0) {
                                    resolveCapability(
                                        interpreter,
                                        capability,
                                        JSArray(results.toMutableList(), prototype = intrinsics.arrayPrototype),
                                    )
                                }
                            }
                            JSUndefined
                        }
                        val onRejected = JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            if (!alreadyCalled[0]) {
                                alreadyCalled[0] = true
                                val entry = JSObject(prototype = intrinsics.objectPrototype)
                                entry.defineDataProperty(
                                    PropertyKey.Str("status"),
                                    JSString("rejected"),
                                    writable = true,
                                    enumerable = true,
                                    configurable = true,
                                )
                                entry.defineDataProperty(
                                    PropertyKey.Str("reason"),
                                    handlerArgs.getOrElse(0) { JSUndefined },
                                    writable = true,
                                    enumerable = true,
                                    configurable = true,
                                )
                                results[index] = entry
                                remaining--
                                if (remaining == 0) {
                                    resolveCapability(
                                        interpreter,
                                        capability,
                                        JSArray(results.toMutableList(), prototype = intrinsics.arrayPrototype),
                                    )
                                }
                            }
                            JSUndefined
                        }
                        interpreter.callFunction(thenMethod, itemPromise, listOf(onFulfilled, onRejected))
                    }
                } catch (error: JSException) {
                    rejectCapability(interpreter, capability, error.value)
                    return@method capability.promise
                }
                remaining--
                if (remaining == 0) {
                    // capability.resolve 抛错时转为拒绝，且不关闭已完成的迭代器
                    try {
                        resolveCapability(
                            interpreter,
                            capability,
                            JSArray(results.toMutableList(), prototype = intrinsics.arrayPrototype),
                        )
                    } catch (error: JSException) {
                        rejectCapability(interpreter, capability, error.value)
                    }
                }
                capability.promise
            },
        )

        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("any"),
            value = BuiltinHelpers.method(intrinsics, "any", 1) { interpreter, thisValue, args ->
                if (!interpreter.isConstructor(thisValue)) {
                    interpreter.typeError("Promise.any requires a constructor as this")
                }
                val capability = PromiseSupport.newPromiseCapability(interpreter, thisValue)
                val resolveMethod = try {
                    interpreter.getV(thisValue, PropertyKey.Str("resolve"))
                } catch (error: JSException) {
                    rejectCapability(interpreter, capability, error.value)
                    return@method capability.promise
                }
                if (!interpreter.isCallable(resolveMethod)) {
                    rejectCapability(
                        interpreter,
                        capability,
                        typeErrorValue(interpreter, "Promise.resolve is not a function"),
                    )
                    return@method capability.promise
                }
                val errors = ArrayList<JSValue?>()
                var remaining = 1

                suspend fun rejectWithAggregateError() {
                    val aggregate = interpreter.construct(
                        intrinsics.aggregateErrorConstructor,
                        listOf(
                            JSArray(errors.toMutableList(), prototype = intrinsics.arrayPrototype),
                            JSString("All promises were rejected"),
                        ),
                    )
                    rejectCapability(interpreter, capability, aggregate)
                }

                try {
                    interpreter.consumeIterator(args.getOrElse(0) { JSUndefined }) { item ->
                        val itemPromise = interpreter.callFunction(resolveMethod, thisValue, listOf(item))
                        val thenMethod = interpreter.getV(itemPromise, PropertyKey.Str("then"))
                        if (!interpreter.isCallable(thenMethod)) {
                            interpreter.typeError("Promise.prototype.then is not a function")
                        }
                        val index = errors.size
                        errors.add(JSUndefined)
                        remaining++
                        val alreadyCalled = booleanArrayOf(false)
                        val onFulfilled = JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            resolveCapability(
                                interpreter,
                                capability,
                                handlerArgs.getOrElse(0) { JSUndefined },
                            )
                            JSUndefined
                        }
                        val onRejected = JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            if (!alreadyCalled[0]) {
                                alreadyCalled[0] = true
                                errors[index] = handlerArgs.getOrElse(0) { JSUndefined }
                                remaining--
                                if (remaining == 0) rejectWithAggregateError()
                            }
                            JSUndefined
                        }
                        interpreter.callFunction(thenMethod, itemPromise, listOf(onFulfilled, onRejected))
                    }
                } catch (error: JSException) {
                    rejectCapability(interpreter, capability, error.value)
                    return@method capability.promise
                }
                remaining--
                if (remaining == 0) {
                    try {
                        rejectWithAggregateError()
                    } catch (error: JSException) {
                        rejectCapability(interpreter, capability, error.value)
                    }
                }
                capability.promise
            },
        )
    }

    /** 创建 TypeError 值（用于把错误传给 capability.reject，而不是抛出）。 */
    private fun typeErrorValue(interpreter: Interpreter, message: String): JSError =
        JSError("TypeError", message, interpreter.intrinsics.typeErrorPrototype)

    private suspend fun resolveCapability(
        interpreter: Interpreter,
        capability: PromiseCapability,
        value: JSValue,
    ) {
        interpreter.callFunction(capability.resolve, JSUndefined, listOf(value))
    }

    private suspend fun rejectCapability(
        interpreter: Interpreter,
        capability: PromiseCapability,
        reason: JSValue,
    ) {
        interpreter.callFunction(capability.reject, JSUndefined, listOf(reason))
    }

    private fun requirePromise(interpreter: Interpreter, thisValue: JSValue, name: String): JSPromise =
        thisValue as? JSPromise ?: interpreter.typeError("$name requires that 'this' be a Promise")
}
