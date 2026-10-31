package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/** Promise capability：构造得到的结果 Promise 及其 resolve/reject 函数。 */
class PromiseCapability(
    var promise: JSValue = JSUndefined,
    var resolve: JSValue = JSUndefined,
    var reject: JSValue = JSUndefined,
)

/**
 * Promise 的解析/拒绝与 then 链调度。
 *
 * 放在 runtime 包中，供解释器（async/await）与 Promise 内建共用。
 */
object PromiseSupport {

    /**
     * NewPromiseCapability(C)：用构造器 C 创建能力记录。
     *
     * executor 把 resolve/reject 记录到 capability；第二次携带非 undefined
     * 参数调用会抛 TypeError（对应 GetCapabilitiesExecutor 函数）。
     */
    suspend fun newPromiseCapability(
        interpreter: Interpreter,
        constructor: JSValue,
    ): PromiseCapability {
        if (!interpreter.isConstructor(constructor)) {
            interpreter.typeError("Promise constructor is not a constructor")
        }
        val capability = PromiseCapability()
        val executor = JSNativeFunction(
            name = "",
            length = 2,
            prototype = interpreter.intrinsics.functionPrototype,
        ) { inner, _, args ->
            val resolve = args.getOrElse(0) { JSUndefined }
            val reject = args.getOrElse(1) { JSUndefined }
            if (capability.resolve !is JSUndefined) {
                inner.typeError("Promise capability executor has already been invoked with non-undefined arguments")
            }
            if (capability.reject !is JSUndefined) {
                inner.typeError("Promise capability executor has already been invoked with non-undefined arguments")
            }
            capability.resolve = resolve
            capability.reject = reject
            JSUndefined
        }
        capability.promise = interpreter.construct(constructor, listOf(executor))
        if (!interpreter.isCallable(capability.resolve)) {
            interpreter.typeError("Promise capability resolve is not a function")
        }
        if (!interpreter.isCallable(capability.reject)) {
            interpreter.typeError("Promise capability reject is not a function")
        }
        return capability
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

        // 规范：对象（含原生 Promise）一律通过 NewPromiseResolveThenableJob 采纳，
        // 保证 then 的访问/调用次序与可观察的构造器调用一致
        if (value is JSObject) {
            val thenValue = try {
                interpreter.getV(value, PropertyKey.Str("then"))
            } catch (error: JSException) {
                reject(interpreter, promise, error.value)
                return
            }
            if (interpreter.isCallable(thenValue)) {
                val (resolveFunction, rejectFunction) = createResolvingFunctions(interpreter, promise)
                // 规范 NewPromiseResolveThenableJob：then 的调用在微任务中进行，
                // 保证 await thenable 与 Promise 链的交错顺序
                interpreter.context.eventLoop.enqueueMicrotask {
                    try {
                        interpreter.callFunction(thenValue, value, listOf(resolveFunction, rejectFunction))
                    } catch (error: JSException) {
                        reject(interpreter, promise, error.value)
                    }
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
        // 环境等内部路径构造的 JSError 可能没有内建原型，拒绝前补齐
        val normalized = interpreter.normalizeErrorValue(reason)
        promise.state = JSPromise.State.REJECTED
        promise.result = normalized
        promise.deferred.completeExceptionally(JSException(normalized))
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
        registerReaction(interpreter, promise, JSPromise.Reaction(onFulfilled, onRejected, child))
        return child
    }

    /**
     * PerformPromiseThen：把回调落到 capability 上（Promise.prototype.then）。
     */
    fun performThen(
        interpreter: Interpreter,
        promise: JSPromise,
        onFulfilled: JSValue?,
        onRejected: JSValue?,
        capability: PromiseCapability,
    ) {
        registerReaction(
            interpreter,
            promise,
            JSPromise.Reaction(onFulfilled, onRejected, child = null, capability = capability),
        )
    }

    private fun registerReaction(
        interpreter: Interpreter,
        promise: JSPromise,
        reaction: JSPromise.Reaction,
    ) {
        if (promise.state == JSPromise.State.PENDING) {
            promise.reactions.add(reaction)
        } else {
            enqueueReaction(interpreter, promise, reaction)
        }
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
            val fulfilled = promise.state == JSPromise.State.FULFILLED
            val handler = if (fulfilled) reaction.onFulfilled else reaction.onRejected
            if (handler == null || handler is JSUndefined || !interpreter.isCallable(handler)) {
                settleReaction(interpreter, reaction, fulfilled, promise.result)
            } else {
                try {
                    val value = interpreter.callFunction(handler, JSUndefined, listOf(promise.result))
                    settleReaction(interpreter, reaction, true, value)
                } catch (error: JSException) {
                    settleReaction(interpreter, reaction, false, error.value)
                }
            }
        }
    }

    /** 把反应结果交给 capability（外部 then）或内部子 Promise。 */
    private suspend fun settleReaction(
        interpreter: Interpreter,
        reaction: JSPromise.Reaction,
        fulfilled: Boolean,
        value: JSValue,
    ) {
        val capability = reaction.capability
        if (capability != null) {
            interpreter.callFunction(
                if (fulfilled) capability.resolve else capability.reject,
                JSUndefined,
                listOf(value),
            )
            return
        }
        val child = reaction.child ?: return
        if (fulfilled) {
            resolve(interpreter, child, value)
        } else {
            reject(interpreter, child, value)
        }
    }

    /**
     * CreateResolvingFunctions：共享 [[AlreadyResolved]] 标记。
     *
     * 首次调用后再次调用 resolve/reject 必须被忽略（例如先 resolve(thenable)
     * 再 reject，后者不能抢先落定 Promise）。
     */
    fun createResolvingFunctions(
        interpreter: Interpreter,
        promise: JSPromise,
    ): Pair<JSNativeFunction, JSNativeFunction> {
        val alreadyResolved = booleanArrayOf(false)
        val resolveFunction = JSNativeFunction(
            name = "",
            length = 1,
            prototype = interpreter.intrinsics.functionPrototype,
        ) { _, _, args ->
            if (!alreadyResolved[0]) {
                alreadyResolved[0] = true
                resolve(interpreter, promise, args.getOrElse(0) { JSUndefined })
            }
            JSUndefined
        }
        val rejectFunction = JSNativeFunction(
            name = "",
            length = 1,
            prototype = interpreter.intrinsics.functionPrototype,
        ) { _, _, args ->
            if (!alreadyResolved[0]) {
                alreadyResolved[0] = true
                reject(interpreter, promise, args.getOrElse(0) { JSUndefined })
            }
            JSUndefined
        }
        return resolveFunction to rejectFunction
    }
}
