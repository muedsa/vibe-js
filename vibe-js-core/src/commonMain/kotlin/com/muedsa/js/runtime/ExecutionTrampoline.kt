package com.muedsa.js.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * 当前线程内的协程恢复队列，用于把深层 JS 调用的 Kotlin 栈转移到协程状态机。
 *
 * 每隔一段调用深度，解释器显式 yield；恢复动作只入此内部队列，待当前 Kotlin
 * 调用栈退回调度循环后继续。这里不调度 JS 微任务、不切换线程、不使用平台 API。
 * 因而可配置较高的 JS 逻辑调用深度，而不要求宿主提供同样深的物理调用栈。
 */
internal class ExecutionTrampoline : CoroutineDispatcher() {
    private class ResumeTask(
        val context: CoroutineContext,
        val block: Runnable,
        val drainWaiter: Boolean = context[AsyncStartPhase]?.draining == true,
    )

    private val queue = ArrayDeque<ResumeTask>()
    private var running = false

    /** 等待队列清空的调用方不算待执行工作，避免两个等待者互相让出形成活锁。 */
    val hasPendingWork: Boolean get() = queue.any { !it.drainWaiter }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val phase = context[AsyncStartPhase]
        if (running && phase != null && phase.internalSuspensions == 0) {
            // 真正的 await 恢复继续采用原有的同步恢复时序：例如 drainMicrotasks
            // 完成 deferred 时，async 代码必须立即运行到下一个 await。
            resume(ResumeTask(context, block))
            return
        }
        queue.addLast(ResumeTask(context, block))
        if (running) return
        running = true
        try {
            while (queue.isNotEmpty()) resume(queue.removeFirst())
        } finally {
            running = false
        }
    }

    private fun resume(task: ResumeTask) {
        try {
            task.block.run()
        } finally {
            // 返回调度循环说明该段协程已结束或挂起；只有真实挂起才允许 async
            // 调用方取得 Promise，内部栈分段不能冒充 JavaScript 的 await。
            task.context[AsyncStartPhase]?.signalExternalSuspension()
        }
    }
}

/** async 首次真实挂起的握手，区分 JS await 与解释器内部的栈分段。 */
internal class AsyncStartPhase : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AsyncStartPhase>

    val ready = CompletableDeferred<Unit>()
    var internalSuspensions = 0
    var draining = false

    fun signalExternalSuspension() {
        if (internalSuspensions == 0) ready.complete(Unit)
    }
}

/** 为直接的宿主执行入口安装恢复队列；嵌套执行复用原来的队列。 */
internal suspend fun <T> withExecutionTrampoline(block: suspend () -> T): T =
    if (currentCoroutineContext()[ContinuationInterceptor] is ExecutionTrampoline) {
        block()
    } else {
        withContext(ExecutionTrampoline() + AsyncStartPhase()) { block() }
    }

/**
 * 标注仅为解释器内部实现服务的挂起。计数在真正恢复后才减少，因此调度器可以
 * 判断 async 是否仍在执行其同步前缀，而无需侵入 Promise 或宿主挂起函数。
 */
internal suspend fun <T> internalExecutionSuspension(block: suspend () -> T): T {
    val phase = currentCoroutineContext()[AsyncStartPhase]
    if (phase != null) phase.internalSuspensions++
    try {
        return block()
    } finally {
        if (phase != null) phase.internalSuspensions--
    }
}

/** 仅在内部恢复队列存在时分段，不在用户调度器上引入额外调度行为。 */
internal suspend fun yieldExecutionStack() {
    if (currentCoroutineContext()[ContinuationInterceptor] is ExecutionTrampoline) {
        internalExecutionSuspension { yield() }
    }
}

/**
 * 微任务或宿主回调返回前，等待其触发的内部栈分段执行到真实挂起点。
 * 否则深层 await 恢复中的内部 yield 会让后续 JS 微任务越过当前回调。
 */
internal suspend fun drainExecutionStack() {
    val coroutineContext = currentCoroutineContext()
    val dispatcher = coroutineContext[ContinuationInterceptor] as? ExecutionTrampoline ?: return
    val phase = coroutineContext[AsyncStartPhase] ?: return
    val wasDraining = phase.draining
    phase.draining = true
    try {
        while (dispatcher.hasPendingWork) internalExecutionSuspension { yield() }
    } finally {
        phase.draining = wasDraining
    }
}
