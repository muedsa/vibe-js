package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.PromiseSupport
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * AsyncDisposableStack（显式资源管理的异步版本）。
 */
internal object AsyncDisposableStackBuiltins {

    private class AsyncDisposeState {
        var disposed: Boolean = false
        var capability: AsyncDisposeCapability = AsyncDisposeCapability()
    }

    /** dispose 为 null 表示 use(null/undefined) 的占位资源（仅触发一次 Await）。 */
    private class AsyncResource(
        val dispose: (suspend (Interpreter) -> JSValue)?,
    )

    private class AsyncDisposeCapability {
        val resources: MutableList<AsyncResource> = ArrayList()
    }

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = JSObject(prototype = intrinsics.objectPrototype)
        val constructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "AsyncDisposableStack",
            length = 0,
            prototypeObject = prototype,
        ) { interpreter, _, _ ->
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor AsyncDisposableStack requires 'new'")
            }
            val stack = JSObject(prototype = prototype)
            stack.internalState = AsyncDisposeState()
            stack
        }

        prototype.defineDataProperty(PropertyKey.CONSTRUCTOR, constructor, true, false, true)
        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("AsyncDisposableStack"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("use"),
            BuiltinHelpers.method(intrinsics, "use", 1) { interpreter, thisValue, args ->
                val state = requireState(interpreter, thisValue)
                if (state.disposed) {
                    interpreter.referenceError("AsyncDisposableStack has already been disposed")
                }
                val value = args.getOrElse(0) { JSUndefined }
                if (value is JSNull || value is JSUndefined) {
                    state.capability.resources.add(AsyncResource(dispose = null))
                    return@method value
                }
                if (value !is JSObject) {
                    interpreter.typeError("AsyncDisposableStack.prototype.use requires an object")
                }
                val method = getDisposeMethod(interpreter, value)
                    ?: interpreter.typeError("Value is not disposable")
                state.capability.resources.add(
                    AsyncResource { inner -> inner.callFunction(method, value, emptyList()) },
                )
                value
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("adopt"),
            BuiltinHelpers.method(intrinsics, "adopt", 2) { interpreter, thisValue, args ->
                val state = requireState(interpreter, thisValue)
                if (state.disposed) {
                    interpreter.referenceError("AsyncDisposableStack has already been disposed")
                }
                val value = args.getOrElse(0) { JSUndefined }
                val onDispose = args.getOrElse(1) { JSUndefined }
                if (!interpreter.isCallable(onDispose)) {
                    interpreter.typeError("AsyncDisposableStack.prototype.adopt requires a callable")
                }
                state.capability.resources.add(
                    AsyncResource { inner ->
                        inner.callFunction(onDispose, JSUndefined, listOf(value))
                    },
                )
                value
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("defer"),
            BuiltinHelpers.method(intrinsics, "defer", 1) { interpreter, thisValue, args ->
                val state = requireState(interpreter, thisValue)
                if (state.disposed) {
                    interpreter.referenceError("AsyncDisposableStack has already been disposed")
                }
                val onDispose = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(onDispose)) {
                    interpreter.typeError("AsyncDisposableStack.prototype.defer requires a callable")
                }
                state.capability.resources.add(
                    AsyncResource { inner ->
                        inner.callFunction(onDispose, JSUndefined, emptyList())
                    },
                )
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("move"),
            BuiltinHelpers.method(intrinsics, "move", 0) { interpreter, thisValue, _ ->
                val state = requireState(interpreter, thisValue)
                if (state.disposed) {
                    interpreter.referenceError("AsyncDisposableStack has already been disposed")
                }
                val moved = JSObject(prototype = prototype)
                val movedState = AsyncDisposeState()
                movedState.capability = state.capability
                state.capability = AsyncDisposeCapability()
                state.disposed = true
                moved.internalState = movedState
                moved
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        val disposeFunction = BuiltinHelpers.method(intrinsics, "disposeAsync", 0) { interpreter, thisValue, _ ->
            val promise = JSPromise(intrinsics.promisePrototype)
            val (resolveFunction, rejectFunction) =
                PromiseSupport.createResolvingFunctions(interpreter, promise)
            interpreter.context.eventLoop.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val state = (thisValue as? JSObject)?.internalState as? AsyncDisposeState
                        ?: interpreter.typeError(
                            "AsyncDisposableStack method called on incompatible receiver"
                        )
                    if (!state.disposed) {
                        state.disposed = true
                        disposeResources(interpreter, state.capability)
                    }
                    interpreter.callFunction(resolveFunction, JSUndefined, listOf(JSUndefined))
                } catch (error: JSException) {
                    interpreter.callFunction(rejectFunction, JSUndefined, listOf(error.value))
                }
            }
            promise
        }
        prototype.defineDataProperty(
            PropertyKey.Str("disposeAsync"),
            disposeFunction,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        // 规范：@@asyncDispose 与 disposeAsync 是同一个内建函数
        prototype.defineDataProperty(
            PropertyKey.Sym(intrinsics.symbolAsyncDispose),
            disposeFunction,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        prototype.defineAccessorProperty(
            key = PropertyKey.Str("disposed"),
            get = BuiltinHelpers.method(intrinsics, "get disposed", 0) { interpreter, thisValue, _ ->
                JSBoolean.getJsBoolean(requireState(interpreter, thisValue).disposed)
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        registry.defineGlobal("AsyncDisposableStack", constructor)
    }

    private fun requireState(interpreter: Interpreter, thisValue: JSValue): AsyncDisposeState {
        val stack = thisValue as? JSObject
        return stack?.internalState as? AsyncDisposeState
            ?: interpreter.typeError("AsyncDisposableStack method called on incompatible receiver")
    }

    /** 异步 hint：优先 @@asyncDispose，再回退 @@dispose。 */
    private suspend fun getDisposeMethod(interpreter: Interpreter, value: JSObject): JSValue? {
        val asyncMethod = interpreter.getV(
            value, PropertyKey.Sym(interpreter.intrinsics.symbolAsyncDispose),
        )
        val method = if (asyncMethod is JSUndefined || asyncMethod is JSNull) {
            interpreter.getV(value, PropertyKey.Sym(interpreter.intrinsics.symbolDispose))
        } else {
            asyncMethod
        }
        if (method is JSUndefined || method is JSNull) return null
        if (!interpreter.isCallable(method)) {
            interpreter.typeError("Dispose method is not a function")
        }
        return method
    }

    /** DisposeResources：逐个 Await 释放结果，错误嵌套为 SuppressedError。 */
    private suspend fun disposeResources(interpreter: Interpreter, capability: AsyncDisposeCapability) {
        var pending: JSException? = null
        var needsAwait = false
        var hasAwaited = false
        while (capability.resources.isNotEmpty()) {
            val resource = capability.resources.removeAt(capability.resources.size - 1)
            if (resource.dispose == null) {
                needsAwait = true
                continue
            }
            needsAwait = true
            try {
                val result = resource.dispose(interpreter)
                awaitValue(interpreter, result)
                hasAwaited = true
            } catch (error: JSException) {
                pending = if (pending == null) {
                    error
                } else {
                    createSuppressedError(interpreter, error.value, pending!!.value)
                }
            }
        }
        if (needsAwait && !hasAwaited) {
            awaitValue(interpreter, JSUndefined)
        }
        pending?.let { throw it }
    }

    /** Await：至少让出一个微任务周期。 */
    private suspend fun awaitValue(interpreter: Interpreter, value: JSValue): JSValue {
        val promise = if (value is JSPromise) {
            value
        } else {
            val created = JSPromise(interpreter.intrinsics.promisePrototype)
            PromiseSupport.resolve(interpreter, created, value)
            created
        }
        val result = try {
            promise.awaitResult()
        } catch (error: JSException) {
            interpreter.context.eventLoop.awaitMicrotask()
            throw error
        }
        interpreter.context.eventLoop.awaitMicrotask()
        return result
    }

    private fun createSuppressedError(
        interpreter: Interpreter,
        error: JSValue,
        suppressed: JSValue,
    ): JSException {
        val objectValue = JSObject(prototype = interpreter.intrinsics.suppressedErrorPrototype)
        objectValue.defineDataProperty(
            key = PropertyKey.Str("error"),
            value = error,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        objectValue.defineDataProperty(
            key = PropertyKey.Str("suppressed"),
            value = suppressed,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        return JSException(objectValue)
    }
}
