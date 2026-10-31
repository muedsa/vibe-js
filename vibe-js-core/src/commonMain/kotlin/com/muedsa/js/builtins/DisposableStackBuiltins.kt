package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * DisposableStack（显式资源管理，同步版本）。
 */
internal object DisposableStackBuiltins {

    /** [[DisposableState]] 与 [[DisposeCapability]]。 */
    private class DisposeState {
        var disposed: Boolean = false
        var capability: DisposeCapability = DisposeCapability()
    }

    /** 资源按加入顺序保存，释放时逆序调用。 */
    private class DisposeCapability {
        val resources: MutableList<suspend (Interpreter) -> Unit> = ArrayList()
    }

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = JSObject(prototype = intrinsics.objectPrototype)
        val constructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "DisposableStack",
            length = 0,
            prototypeObject = prototype,
        ) { interpreter, _, _ ->
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor DisposableStack requires 'new'")
            }
            val stack = JSObject(prototype = prototype)
            stack.internalState = DisposeState()
            stack
        }

        prototype.defineDataProperty(PropertyKey.CONSTRUCTOR, constructor, true, false, true)
        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("DisposableStack"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        prototype.defineDataProperty(
            PropertyKey.Str("use"),
            BuiltinHelpers.method(intrinsics, "use", 1) { interpreter, thisValue, args ->
                val state = requireState(interpreter, thisValue)
                if (state.disposed) {
                    interpreter.referenceError("DisposableStack has already been disposed")
                }
                val value = args.getOrElse(0) { JSUndefined }
                if (value is JSNull || value is JSUndefined) {
                    return@method value
                }
                if (value !is JSObject) {
                    interpreter.typeError("DisposableStack.prototype.use requires an object")
                }
                val method = getDisposeMethod(interpreter, value)
                    ?: interpreter.typeError("Value is not disposable")
                state.capability.resources.add { inner ->
                    inner.callFunction(method, value, emptyList())
                }
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
                    interpreter.referenceError("DisposableStack has already been disposed")
                }
                val value = args.getOrElse(0) { JSUndefined }
                val onDispose = args.getOrElse(1) { JSUndefined }
                if (!interpreter.isCallable(onDispose)) {
                    interpreter.typeError("DisposableStack.prototype.adopt requires a callable")
                }
                state.capability.resources.add { inner ->
                    inner.callFunction(onDispose, JSUndefined, listOf(value))
                }
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
                    interpreter.referenceError("DisposableStack has already been disposed")
                }
                val onDispose = args.getOrElse(0) { JSUndefined }
                if (!interpreter.isCallable(onDispose)) {
                    interpreter.typeError("DisposableStack.prototype.defer requires a callable")
                }
                state.capability.resources.add { inner ->
                    inner.callFunction(onDispose, JSUndefined, emptyList())
                }
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
                    interpreter.referenceError("DisposableStack has already been disposed")
                }
                val moved = JSObject(prototype = prototype)
                val movedState = DisposeState()
                movedState.capability = state.capability
                state.capability = DisposeCapability()
                state.disposed = true
                moved.internalState = movedState
                moved
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

        val disposeFunction = BuiltinHelpers.method(intrinsics, "dispose", 0) { interpreter, thisValue, _ ->
            val state = requireState(interpreter, thisValue)
            if (!state.disposed) {
                state.disposed = true
                disposeResources(interpreter, state.capability)
            }
            JSUndefined
        }
        prototype.defineDataProperty(
            PropertyKey.Str("dispose"),
            disposeFunction,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        // 规范：@@dispose 与 dispose 是同一个内建函数
        prototype.defineDataProperty(
            PropertyKey.Sym(intrinsics.symbolDispose),
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

        registry.defineGlobal("DisposableStack", constructor)
    }

    private fun requireState(interpreter: Interpreter, thisValue: JSValue): DisposeState {
        val stack = thisValue as? JSObject
        return stack?.internalState as? DisposeState
            ?: interpreter.typeError("DisposableStack method called on incompatible receiver")
    }

    private suspend fun getDisposeMethod(interpreter: Interpreter, value: JSObject): JSValue? {
        val method = interpreter.getV(value, PropertyKey.Sym(interpreter.intrinsics.symbolDispose))
        if (method is JSUndefined || method is JSNull) return null
        if (!interpreter.isCallable(method)) {
            interpreter.typeError("Symbol.dispose is not a function")
        }
        return method
    }

    /** DisposeResources：逆序释放，多个错误按 SuppressedError 嵌套。 */
    private suspend fun disposeResources(interpreter: Interpreter, capability: DisposeCapability) {
        var pending: JSException? = null
        while (capability.resources.isNotEmpty()) {
            val dispose = capability.resources.removeAt(capability.resources.size - 1)
            try {
                dispose(interpreter)
            } catch (error: JSException) {
                pending = if (pending == null) {
                    error
                } else {
                    createSuppressedError(interpreter, error.value, pending!!.value)
                }
            }
        }
        pending?.let { throw it }
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
