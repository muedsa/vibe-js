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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * 串行协程恢复队列，用于把深层 JS 调用的 Kotlin 栈转移到协程状态机。
 *
 * 每隔一段调用深度，解释器显式 yield；恢复动作只入此内部队列，待当前 Kotlin
 * 调用栈退回调度循环后继续。跨线程的恢复先原子入队，同一时刻仅一个线程消费；
 * 队列本身不切换到指定线程、不使用平台 API，空闲时由 EventLoop 建立微任务检查点。
 * 因而可配置较高的 JS 逻辑调用深度，而不要求宿主提供同样深的物理调用栈。
 */
@OptIn(ExperimentalAtomicApi::class)
internal class ExecutionTrampoline(private val onIdle: (() -> Unit)? = null) : CoroutineDispatcher() {
    private class ResumeTask(
        val context: CoroutineContext,
        val block: Runnable,
        val drainWaiter: Boolean = context[AsyncStartPhase]?.draining == true,
    )

    private val internalQueue = RuntimeQueue<ResumeTask>()
    private val externalQueue = RuntimeQueue<ResumeTask>()
    private val waiters = RuntimeQueue<ResumeTask>()
    private val running = AtomicBoolean(false)

    /** 等待队列清空的调用方不算待执行工作，避免两个等待者互相让出形成活锁。 */
    val hasPendingWork: Boolean get() = internalQueue.size > 0 || externalQueue.size > 0

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val task = ResumeTask(context, block)
        when {
            task.drainWaiter -> waiters.addLast(task)
            (context[AsyncStartPhase]?.internalSuspensions ?: 0) > 0 -> internalQueue.addLast(task)
            else -> externalQueue.addLast(task)
        }
        if (!running.compareAndSet(false, true)) return
        while (true) {
            // 内部分段优先完成当前同步工作；等待者最后恢复，不能阻止实际工作出队。
            val next = internalQueue.removeFirstOrNull() ?: externalQueue.removeFirstOrNull()
                ?: waiters.removeFirstOrNull()
            if (next != null) {
                resume(next)
                continue
            }
            onIdle?.invoke()
            if (hasPendingWork || waiters.size > 0) continue
            running.store(false)
            // 发布空闲状态与生产者入队可能交错，重新争取执行权避免丢失唤醒。
            if (!(hasPendingWork || waiters.size > 0) || !running.compareAndSet(false, true)) return
        }
    }

    private fun resume(task: ResumeTask) {
        val frameElement = task.context[ModuleFrameElement]
        val currentFrame = frameElement?.interpreter?.frame
        // 只在该续体不属于当前模块 frame 时切换；函数 frame 已由调用方恢复，
        // 不能在函数体内部被模块 frame 覆盖。
        val needsSwitch = frameElement != null &&
            (currentFrame == null || currentFrame.moduleFrameOwner !== frameElement.record)
        val previousFrame = currentFrame
        if (needsSwitch) frameElement.interpreter.frame = frameElement.frame
        try {
            task.block.run()
        } finally {
            if (needsSwitch) frameElement.interpreter.frame = previousFrame
            // 返回调度循环说明该段协程已结束或挂起；只有真实挂起才允许 async
            // 调用方取得 Promise，内部栈分段不能冒充 JavaScript 的 await。
            task.context[AsyncStartPhase]?.signalExternalSuspension()
        }
    }
}

/** async 首次真实挂起的握手，区分 JS await 与解释器内部的栈分段。 */
internal class ModuleFrameElement(
    val interpreter: Interpreter,
    val frame: ExecutionContext,
    val record: com.muedsa.js.runtime.module.internal.ModuleRecord,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModuleFrameElement>
}

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
