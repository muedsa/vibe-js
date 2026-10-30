package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.PromiseSupport
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
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
            val resolveFunction = BuiltinHelpers.method(intrinsics, "resolve", 1) { _, _, resolveArgs ->
                PromiseSupport.resolve(interpreter, promise, resolveArgs.getOrElse(0) { JSUndefined })
                JSUndefined
            }
            val rejectFunction = BuiltinHelpers.method(intrinsics, "reject", 1) { _, _, rejectArgs ->
                PromiseSupport.reject(interpreter, promise, rejectArgs.getOrElse(0) { JSUndefined })
                JSUndefined
            }
            try {
                interpreter.callFunction(executor, JSUndefined, listOf(resolveFunction, rejectFunction))
            } catch (error: JSException) {
                PromiseSupport.reject(interpreter, promise, error.value)
            }
            promise
        }

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
                PromiseSupport.then(
                    interpreter,
                    promise,
                    args.getOrNull(0),
                    args.getOrNull(1),
                )
            },
        )
        prototype.defineDataProperty(
            key = PropertyKey.Str("catch"),
            value = BuiltinHelpers.method(intrinsics, "catch", 1) { interpreter, thisValue, args ->
                val promise = requirePromise(interpreter, thisValue, "Promise.prototype.catch")
                PromiseSupport.then(interpreter, promise, null, args.getOrNull(0))
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

        installStaticMethods(intrinsics, promiseConstructor, prototype)
        registry.defineGlobal("Promise", promiseConstructor)
    }

    private fun installStaticMethods(
        intrinsics: com.muedsa.js.runtime.Intrinsics,
        promiseConstructor: JSNativeFunction,
        prototype: JSObject,
    ) {
        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("resolve"),
            value = BuiltinHelpers.method(intrinsics, "resolve", 1) { interpreter, _, args ->
                val value = args.getOrElse(0) { JSUndefined }
                PromiseSupport.toPromise(interpreter, value)
            },
        )
        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("reject"),
            value = BuiltinHelpers.method(intrinsics, "reject", 1) { interpreter, _, args ->
                val promise = JSPromise(prototype)
                PromiseSupport.reject(interpreter, promise, args.getOrElse(0) { JSUndefined })
                promise
            },
        )

        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("all"),
            value = BuiltinHelpers.method(intrinsics, "all", 1) { interpreter, _, args ->
                val values = interpreter.iterateValues(args.getOrElse(0) { JSUndefined })
                val resultPromise = JSPromise(prototype)
                if (values.isEmpty()) {
                    PromiseSupport.resolve(
                        interpreter,
                        resultPromise,
                        JSArray(mutableListOf(), prototype = intrinsics.arrayPrototype),
                    )
                    return@method resultPromise
                }

                val results = arrayOfNulls<JSValue>(values.size)
                var remaining = values.size
                for (index in values.indices) {
                    val itemPromise = PromiseSupport.toPromise(interpreter, values[index])
                    val onFulfilled = JSNativeFunction(
                        name = "",
                        length = 1,
                        prototype = intrinsics.functionPrototype,
                    ) { _, _, handlerArgs ->
                        results[index] = handlerArgs.getOrElse(0) { JSUndefined }
                        remaining--
                        if (remaining == 0) {
                            val resultValues = ArrayList<JSValue?>(results.size)
                            for (value in results) {
                                resultValues.add(value ?: JSUndefined)
                            }
                            PromiseSupport.resolve(
                                interpreter,
                                resultPromise,
                                JSArray(resultValues, prototype = intrinsics.arrayPrototype),
                            )
                        }
                        JSUndefined
                    }
                    val onRejected = JSNativeFunction(
                        name = "",
                        length = 1,
                        prototype = intrinsics.functionPrototype,
                    ) { _, _, handlerArgs ->
                        PromiseSupport.reject(
                            interpreter,
                            resultPromise,
                            handlerArgs.getOrElse(0) { JSUndefined },
                        )
                        JSUndefined
                    }
                    PromiseSupport.then(interpreter, itemPromise, onFulfilled, onRejected)
                }
                resultPromise
            },
        )

        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("race"),
            value = BuiltinHelpers.method(intrinsics, "race", 1) { interpreter, _, args ->
                val values = interpreter.iterateValues(args.getOrElse(0) { JSUndefined })
                val resultPromise = JSPromise(prototype)
                for (value in values) {
                    val itemPromise = PromiseSupport.toPromise(interpreter, value)
                    PromiseSupport.then(
                        interpreter,
                        itemPromise,
                        JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            PromiseSupport.resolve(
                                interpreter,
                                resultPromise,
                                handlerArgs.getOrElse(0) { JSUndefined },
                            )
                            JSUndefined
                        },
                        JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            PromiseSupport.reject(
                                interpreter,
                                resultPromise,
                                handlerArgs.getOrElse(0) { JSUndefined },
                            )
                            JSUndefined
                        },
                    )
                }
                resultPromise
            },
        )

        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("allSettled"),
            value = BuiltinHelpers.method(intrinsics, "allSettled", 1) { interpreter, _, args ->
                val values = interpreter.iterateValues(args.getOrElse(0) { JSUndefined })
                val resultPromise = JSPromise(prototype)
                if (values.isEmpty()) {
                    PromiseSupport.resolve(
                        interpreter,
                        resultPromise,
                        JSArray(mutableListOf(), prototype = intrinsics.arrayPrototype),
                    )
                    return@method resultPromise
                }

                val results = arrayOfNulls<JSValue>(values.size)
                var remaining = values.size
                for (index in values.indices) {
                    val itemPromise = PromiseSupport.toPromise(interpreter, values[index])
                    val onFulfilled = JSNativeFunction(
                        name = "",
                        length = 1,
                        prototype = intrinsics.functionPrototype,
                    ) { _, _, handlerArgs ->
                        val entry = JSObject(prototype = intrinsics.objectPrototype)
                        entry.defineDataProperty(
                            PropertyKey.Str("status"),
                            JSString("fulfilled"),
                            enumerable = true,
                        )
                        entry.defineDataProperty(
                            PropertyKey.Str("value"),
                            handlerArgs.getOrElse(0) { JSUndefined },
                            enumerable = true,
                        )
                        results[index] = entry
                        remaining--
                        if (remaining == 0) resolveResults(interpreter, resultPromise, results)
                        JSUndefined
                    }
                    val onRejected = JSNativeFunction(
                        name = "",
                        length = 1,
                        prototype = intrinsics.functionPrototype,
                    ) { _, _, handlerArgs ->
                        val entry = JSObject(prototype = intrinsics.objectPrototype)
                        entry.defineDataProperty(
                            PropertyKey.Str("status"),
                            JSString("rejected"),
                            enumerable = true,
                        )
                        entry.defineDataProperty(
                            PropertyKey.Str("reason"),
                            handlerArgs.getOrElse(0) { JSUndefined },
                            enumerable = true,
                        )
                        results[index] = entry
                        remaining--
                        if (remaining == 0) resolveResults(interpreter, resultPromise, results)
                        JSUndefined
                    }
                    PromiseSupport.then(interpreter, itemPromise, onFulfilled, onRejected)
                }
                resultPromise
            },
        )

        promiseConstructor.defineDataProperty(
            key = PropertyKey.Str("any"),
            value = BuiltinHelpers.method(intrinsics, "any", 1) { interpreter, _, args ->
                val values = interpreter.iterateValues(args.getOrElse(0) { JSUndefined })
                val resultPromise = JSPromise(prototype)
                if (values.isEmpty()) {
                    PromiseSupport.reject(
                        interpreter,
                        resultPromise,
                        JSError(
                            "AggregateError",
                            "All promises were rejected",
                            intrinsics.errorPrototype,
                        ),
                    )
                    return@method resultPromise
                }

                var remaining = values.size
                for (value in values) {
                    val itemPromise = PromiseSupport.toPromise(interpreter, value)
                    PromiseSupport.then(
                        interpreter,
                        itemPromise,
                        JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, handlerArgs ->
                            PromiseSupport.resolve(
                                interpreter,
                                resultPromise,
                                handlerArgs.getOrElse(0) { JSUndefined },
                            )
                            JSUndefined
                        },
                        JSNativeFunction(
                            name = "",
                            length = 1,
                            prototype = intrinsics.functionPrototype,
                        ) { _, _, _ ->
                            remaining--
                            if (remaining == 0) {
                                PromiseSupport.reject(
                                    interpreter,
                                    resultPromise,
                                    JSError(
                                        "AggregateError",
                                        "All promises were rejected",
                                        intrinsics.errorPrototype,
                                    ),
                                )
                            }
                            JSUndefined
                        },
                    )
                }
                resultPromise
            },
        )
    }

    private suspend fun resolveResults(
        interpreter: Interpreter,
        promise: JSPromise,
        results: Array<JSValue?>,
    ) {
        val values = ArrayList<JSValue?>(results.size)
        for (value in results) {
            values.add(value ?: JSUndefined)
        }
        PromiseSupport.resolve(
            interpreter,
            promise,
            JSArray(values, prototype = interpreter.intrinsics.arrayPrototype),
        )
    }

    private fun requirePromise(interpreter: Interpreter, thisValue: JSValue, name: String): JSPromise =
        thisValue as? JSPromise ?: interpreter.typeError("$name requires that 'this' be a Promise")
}
