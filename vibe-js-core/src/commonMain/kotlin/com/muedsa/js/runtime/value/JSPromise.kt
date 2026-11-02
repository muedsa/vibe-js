package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.PromiseCapability
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

    /**
     * then 的响应记录。
     *
     * child 用于内部派生的子 Promise；capability 用于 Promise.prototype.then
     * 依据 SpeciesConstructor 得到的自定义 capability（此时 child 为 null）。
     */
    data class Reaction(
        val onFulfilled: JSValue?,
        val onRejected: JSValue?,
        val child: JSPromise?,
        val capability: PromiseCapability? = null,
    )

    var state: State = State.PENDING
    var result: JSValue = JSUndefined

    /** 拒绝诊断与 reason 分开保存，使 throw 原始值也可经内部 Promise 转发保留位置。 */
    internal var rejection: JSException? = null

    /** 是否已有 then/catch 订阅（有订阅则不属于未处理拒绝）。 */
    var handled: Boolean = false

    /** 是否已向宿主上报未处理拒绝（避免重复上报）。 */
    var unhandledReported: Boolean = false

    /** 已上报后又订阅时，是否已通知宿主 rejectionhandled。 */
    var rejectionHandledReported: Boolean = false
    val deferred = CompletableDeferred<JSValue>()
    val reactions: MutableList<Reaction> = mutableListOf()

    /** 等待 Promise 落定；拒绝时抛出 JSException。 */
    suspend fun awaitResult(): JSValue = deferred.await()

    /** 拒绝该 Promise（供运行时/内建使用）。 */
    fun rejectWith(reason: JSValue) {
        if (state != State.PENDING) return
        state = State.REJECTED
        result = reason
        val error = JSException(reason)
        rejection = error
        deferred.completeExceptionally(error)
    }

    override fun toPrimitiveString(): String = "[object Promise]"
}
