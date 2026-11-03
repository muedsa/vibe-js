package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.ExecutionLimitException

/**
 * 引擎的执行配额。解析阶段和未接入检查点的内建/宿主内部工作量不计入步数。
 *
 * @property maxSteps 每次 Engine.run 的最大执行检查点数；null 表示不限制。
 * 语句、表达式、循环迭代、调用和微任务都消耗步数，并非 ECMAScript 指令计数。
 * Array.from、迭代值收集、全局正则 match/replace、Set-like 消费也逐轮检查。
 * @property maxCallDepth 最大逻辑调用深度，默认 16384；null 显式关闭限制。
 * 包括原生函数、构造器与 eval，
 * 不包括全局脚本帧。async 继承创建时的逻辑深度，严格模式尾调用复用深度。
 * 解释器分段恢复深层调用以减少物理栈占用；此上限不约束单次内建操作或解析消耗。
 */
data class EngineLimits(
    val maxSteps: Long? = null,
    val maxCallDepth: Int? = 16_384,
) {
    init {
        require(maxSteps == null || maxSteps > 0) { "maxSteps must be > 0 or null" }
        require(maxCallDepth == null || maxCallDepth > 0) { "maxCallDepth must be > 0 or null" }
    }
}

/**
 * 单次 Engine.run 的共享步数预算。
 *
 * 配额耗尽后保存同一个宿主异常；即使独立 async 协程先观察到超限并结束，
 * 外层脚本及微任务检查点仍会抛出该异常，不能借助 Promise 拒绝回调重置预算。
 * 此对象遵循引擎既有的串行执行契约，不提供线程安全保证。
 */
internal class ExecutionBudget(private val maxSteps: Long?) {
    private var remaining = maxSteps ?: 0L
    private var failure: ExecutionLimitException? = null

    /** 执行前消费一步，允许恰好执行 maxSteps 步，避免累加计数溢出。 */
    fun step() {
        ensureAvailable()
        if (maxSteps == null) return
        if (remaining == 0L) {
            val error = ExecutionLimitException(maxSteps)
            failure = error
            throw error
        }
        remaining--
    }

    /** 边界检查不消费步数；已耗尽的预算不会因 JS 返回或捕获异常而恢复。 */
    fun ensureAvailable() {
        failure?.let { throw it }
    }
}
