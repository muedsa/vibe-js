package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Promise 的解析/拒绝与 then 链调度。
 *
 * 放在 runtime 包中，供解释器（async/await）与 Promise 内建共用。
 */
object PromiseSupport {

    /** 把值转换为 Promise。 */
    suspend fun toPromise(interpreter: Interpreter, value: JSValue): JSPromise {
        if (value is JSPromise) return value
        val promise = JSPromise(interpreter.intrinsics.promisePrototype)
        resolve(interpreter, promise, value)
        return promise
    }

    /**
     * Promise 解析过程（简化版，覆盖 Promise 与 thenable）。
     */
    suspend fun resolve(interpreter: Interpreter, promise: JSPromise, value: JSValue) {
        if (promise.state != JSPromise.State.PENDING) return

        if (value === promise) {
            reject(
                interpreter,
                promise,
                JSError(
                    "TypeError",
                    "Chaining cycle detected for promise",
                    interpreter.intrinsics.typeErrorPrototype,
                ),
            )
            return
        }

        if (value is JSPromise) {
            when (value.state) {
                JSPromise.State.FULFILLED -> fulfill(interpreter, promise, value.result)
                JSPromise.State.REJECTED -> reject(interpreter, promise, value.result)
                JSPromise.State.PENDING -> then(
                    interpreter,
                    value,
                    resolverFunction(interpreter, promise),
                    rejecterFunction(interpreter, promise),
                )
            }
            return
        }

        if (value is JSObject) {
            val thenValue = try {
                interpreter.getV(value, PropertyKey.Str("then"))
            } catch (error: JSException) {
                reject(interpreter, promise, error.value)
                return
            }
            if (interpreter.isCallable(thenValue)) {
                val resolveFunction = resolverFunction(interpreter, promise)
                val rejectFunction = rejecterFunction(interpreter, promise)
                try {
                    interpreter.callFunction(thenValue, value, listOf(resolveFunction, rejectFunction))
                } catch (error: JSException) {
                    reject(interpreter, promise, error.value)
                }
                return
            }
        }

        fulfill(interpreter, promise, value)
    }

    /** 兑现 Promise。 */
    fun fulfill(interpreter: Interpreter, promise: JSPromise, value: JSValue) {
        if (promise.state != JSPromise.State.PENDING) return
        promise.state = JSPromise.State.FULFILLED
        promise.result = value
        promise.deferred.complete(value)
        flushReactions(interpreter, promise)
    }

    /** 拒绝 Promise。 */
    fun reject(interpreter: Interpreter, promise: JSPromise, reason: JSValue) {
        if (promise.state != JSPromise.State.PENDING) return
        promise.state = JSPromise.State.REJECTED
        promise.result = reason
        promise.deferred.completeExceptionally(JSException(reason))
        flushReactions(interpreter, promise)
    }

    /**
     * 注册 then 回调，返回新的 Promise。
     */
    fun then(
        interpreter: Interpreter,
        promise: JSPromise,
        onFulfilled: JSValue?,
        onRejected: JSValue?,
    ): JSPromise {
        val child = JSPromise(interpreter.intrinsics.promisePrototype)
        val reaction = JSPromise.Reaction(onFulfilled, onRejected, child)
        if (promise.state == JSPromise.State.PENDING) {
            promise.reactions.add(reaction)
        } else {
            enqueueReaction(interpreter, promise, reaction)
        }
        return child
    }

    private fun flushReactions(interpreter: Interpreter, promise: JSPromise) {
        val reactions = promise.reactions.toList()
        promise.reactions.clear()
        for (reaction in reactions) {
            enqueueReaction(interpreter, promise, reaction)
        }
    }

    private fun enqueueReaction(
        interpreter: Interpreter,
        promise: JSPromise,
        reaction: JSPromise.Reaction,
    ) {
        interpreter.context.eventLoop.enqueueMicrotask {
            val handler = if (promise.state == JSPromise.State.FULFILLED) {
                reaction.onFulfilled
            } else {
                reaction.onRejected
            }
            if (handler == null || handler is JSUndefined || !interpreter.isCallable(handler)) {
                if (promise.state == JSPromise.State.FULFILLED) {
                    resolve(interpreter, reaction.child, promise.result)
                } else {
                    reject(interpreter, reaction.child, promise.result)
                }
            } else {
                try {
                    val value = interpreter.callFunction(handler, JSUndefined, listOf(promise.result))
                    resolve(interpreter, reaction.child, value)
                } catch (error: JSException) {
                    reject(interpreter, reaction.child, error.value)
                }
            }
        }
    }

    private fun resolverFunction(interpreter: Interpreter, promise: JSPromise): JSNativeFunction =
        JSNativeFunction(
            name = "resolve",
            length = 1,
            prototype = interpreter.intrinsics.functionPrototype,
        ) { _, _, args ->
            resolve(interpreter, promise, args.getOrElse(0) { JSUndefined })
            JSUndefined
        }

    private fun rejecterFunction(interpreter: Interpreter, promise: JSPromise): JSNativeFunction =
        JSNativeFunction(
            name = "reject",
            length = 1,
            prototype = interpreter.intrinsics.functionPrototype,
        ) { _, _, args ->
            reject(interpreter, promise, args.getOrElse(0) { JSUndefined })
            JSUndefined
        }
}
