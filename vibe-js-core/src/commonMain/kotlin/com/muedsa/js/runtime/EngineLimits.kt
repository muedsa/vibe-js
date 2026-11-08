package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.exception.ResourceLimitKind

/**
 * 引擎的执行配额。解析阶段和未接入检查点的内建/宿主内部工作量不计入步数。
 *
 * 全部上限都可在构造时显式设为 null 以关闭；默认值仅为防御性上限，
 * 远高于现有脚本、语料与真实库的实际用量，不改变既有行为。
 *
 * @property maxSteps 每次 Engine.run 的最大执行检查点数；null 表示不限制。
 * 语句、表达式、循环迭代、调用和微任务都消耗步数，并非 ECMAScript 指令计数。
 * Array.from、迭代值收集、全局正则 match/replace、Set-like 消费也逐轮检查。
 * @property maxCallDepth 最大逻辑调用深度，默认 16384；null 显式关闭限制。
 * 包括原生函数、构造器与 eval，
 * 不包括全局脚本帧。async 继承创建时的逻辑深度，严格模式尾调用复用深度。
 * 解释器分段恢复深层调用以减少物理栈占用；此上限不约束单次内建操作或解析消耗。
 * @property maxSourceLength 单段源码的最大字符数，默认 10,000,000；null 表示不限制。
 * 同时约束 Engine.parse、直接/间接 eval 与 Function 构造器读取的源码；
 * 超限抛出 [ResourceLimitException]（kind = [ResourceLimitKind.SOURCE_LENGTH]）。
 * @property maxNestingDepth 词法与语法嵌套的最大深度，默认 256；null 表示不限制。
 * 统计括号、块、数组/对象字面量、模板表达式与递归下降解析层级；
 * 超限抛出 [ResourceLimitException]（kind = [ResourceLimitKind.NESTING_DEPTH]）。
 * @property maxJsonDepth JSON 文本的最大容器嵌套深度，默认 1000；null 表示不限制。
 * JSON.parse / JSON.rawJSON 超限时按现有错误模型抛 JS RangeError。
 * @property maxRegexSteps 纯 Kotlin 回退正则引擎单次匹配的最大步数，默认 10,000,000；
 * null 表示不限制。Kotlin Regex 主路径由平台实现，不消耗此预算。
 * 超限抛出 [ResourceLimitException]（kind = [ResourceLimitKind.REGEX_STEPS]），
 * 属于宿主控制异常，不能被 JS catch/finally 吞掉。
 * @property maxPendingTasks 事件循环同时排队的微任务、定时器与未处理拒绝通知总数上限，
 * 默认 100,000；null 表示不限制。执行中的 async 协程和宿主入口不计入队列长度。
 * 超限抛出 [ResourceLimitException]（kind = [ResourceLimitKind.PENDING_TASKS]）。
 */
data class EngineLimits(
    val maxSteps: Long? = null,
    val maxCallDepth: Int? = 16_384,
    val maxSourceLength: Int? = 10_000_000,
    val maxNestingDepth: Int? = 256,
    val maxJsonDepth: Int? = 1_000,
    val maxRegexSteps: Long? = 10_000_000,
    val maxPendingTasks: Int? = 100_000,
) {
    init {
        require(maxSteps == null || maxSteps > 0) { "maxSteps must be > 0 or null" }
        require(maxCallDepth == null || maxCallDepth > 0) { "maxCallDepth must be > 0 or null" }
        require(maxSourceLength == null || maxSourceLength > 0) {
            "maxSourceLength must be > 0 or null"
        }
        require(maxNestingDepth == null || maxNestingDepth > 0) {
            "maxNestingDepth must be > 0 or null"
        }
        require(maxJsonDepth == null || maxJsonDepth > 0) {
            "maxJsonDepth must be > 0 or null"
        }
        require(maxRegexSteps == null || maxRegexSteps > 0) {
            "maxRegexSteps must be > 0 or null"
        }
        require(maxPendingTasks == null || maxPendingTasks > 0) {
            "maxPendingTasks must be > 0 or null"
        }
    }

    /** 在进入词法/语法分析前检查源码字符数；超限直接抛宿主异常。 */
    internal fun checkSourceLength(sourceLength: Int) {
        val limit = maxSourceLength ?: return
        if (sourceLength > limit) {
            throw ResourceLimitException(
                kind = ResourceLimitKind.SOURCE_LENGTH,
                limitValue = limit.toLong(),
                actualValue = sourceLength.toLong(),
            )
        }
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
