package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.JSException
import kotlinx.coroutines.CompletableDeferred

/**
 * Promise 对象。
 *
 * 状态与结果由内建模块/运行时维护；deferred 用于 await 挂起。
 */
class JSPromise(
    prototype: JSObject?,
) : JSObject(prototype) {

    enum class State {
        PENDING,
        FULFILLED,
        REJECTED,
    }

    /** then 的响应记录。 */
    data class Reaction(
        val onFulfilled: JSValue?,
        val onRejected: JSValue?,
        val child: JSPromise,
    )

    var state: State = State.PENDING
    var result: JSValue = JSUndefined
    val deferred = CompletableDeferred<JSValue>()
    val reactions: MutableList<Reaction> = mutableListOf()

    /** 等待 Promise 落定；拒绝时抛出 JSException。 */
    suspend fun awaitResult(): JSValue = deferred.await()

    /** 拒绝该 Promise（供运行时/内建使用）。 */
    fun rejectWith(reason: JSValue) {
        if (state != State.PENDING) return
        state = State.REJECTED
        result = reason
        deferred.completeExceptionally(JSException(reason))
    }

    override fun toPrimitiveString(): String = "[object Promise]"
}
