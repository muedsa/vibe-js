package com.muedsa.js.runtime.exception

/**
 * 执行步数耗尽的宿主异常，Engine.run 原样抛给 Kotlin 调用方。
 *
 * 不继承 JSException 或 CancellationException：它不是可由 JS catch/finally
 * 处理的语言错误，也不表示调用方协程已经被取消。宿主捕获后可复用同一 Engine，
 * 下一次 run 获得新预算；本次已经产生的全局副作用不回滚。
 *
 * @property maxSteps 本次执行所配置的步数上限。
 */
class ExecutionLimitException internal constructor(val maxSteps: Long) :
    RuntimeException("Maximum execution steps exceeded ($maxSteps)")
