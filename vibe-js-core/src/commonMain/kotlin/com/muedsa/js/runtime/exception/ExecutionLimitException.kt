package com.muedsa.js.runtime.exception

/**
 * 执行限制耗尽的宿主异常，Engine.run 原样抛给 Kotlin 调用方。
 *
 * 不继承 JSException 或 CancellationException：它不是可由 JS catch/finally
 * 处理的语言错误，也不表示调用方协程已经被取消。宿主捕获后可复用同一 Engine，
 * 下一次 run 获得新预算；本次已经产生的全局副作用不回滚。
 *
 * 解析期、正则回溯与事件循环任务队列的资源限制通过子类
 * [ResourceLimitException] 复用同一宿主控制语义，因此同样不能被 JS catch 吞掉。
 *
 * @property limitName 限制项名称，例如 maxSteps、maxRegexSteps。
 * @property limitValue 本次执行配置的上限。
 * @property actualValue 触发限制时观察到的实际值。
 * @property maxSteps 兼容既有步数 API；值等于 [limitValue]。
 */
open class ExecutionLimitException internal constructor(
    val limitName: String,
    val limitValue: Long,
    val actualValue: Long,
    message: String,
) : RuntimeException(message) {

    /** 步数限制的兼容访问器；非步数限制时返回对应的上限值。 */
    val maxSteps: Long get() = limitValue

    /** 仅步数限制使用：保持原有异常消息不变。 */
    internal constructor(maxSteps: Long) : this(
        limitName = "maxSteps",
        limitValue = maxSteps,
        actualValue = maxSteps,
        message = "Maximum execution steps exceeded ($maxSteps)",
    )
}
