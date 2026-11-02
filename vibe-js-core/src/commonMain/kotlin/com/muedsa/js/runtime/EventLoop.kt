package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.EventLoopClosedException
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSValue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * 微任务 / 宏任务事件循环。
 *
 * 设计说明：
 * - Promise.then 等微任务进入 [enqueueMicrotask] 队列；
 * - setTimeout 等宏任务交给 [Scheduler]（宿主实现）；
 * - 所有执行片段复用同一恢复队列，async 以 UNDISPATCHED 保持同步前缀。
 * - 自有 Job 和定时器登记共同定义 close / runUntilIdle 的生命周期边界。
 */
@OptIn(ExperimentalAtomicApi::class)
class EventLoop(
    private val scheduler: Scheduler = CoroutineScheduler(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {
    /** 引擎拥有自己的子 Job；关闭不会取消调用方传入的整个宿主 scope。 */
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val closed = AtomicBoolean(false)
    private val changes = MutableStateFlow(0L)
    private val operations = MutableStateFlow<Set<Job>>(emptySet())
    private val timers = MutableStateFlow<Set<TimerEntry>>(emptySet())
    private val checkpointScheduled = AtomicBoolean(false)
    private val dispatcher = ExecutionTrampoline { onIdle() }
    val scope: CoroutineScope = CoroutineScope(scope.coroutineContext + lifetime + dispatcher + Entry(this))
    private var draining = false

    /** 只标记真正执行运行时代码的协程，用于拒绝会等待自身的重入和关闭。 */
    private class Entry(val loop: EventLoop) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Entry>
    }

    /** 任务快照用于观察与等待；并发读到的是瞬时状态，不是全局对象的访问锁。 */
    val pendingTasks: PendingTasks get() = PendingTasks(
        microtasks = microtasks.size,
        timers = timers.value.size,
        asyncTasks = lifetime.children.count { !it.isCompleted },
        executions = operations.value.size,
        rejectionNotifications = unhandledRejections.size,
    )
    val isClosed: Boolean get() = closed.load()

    private fun pulse() { changes.update { it + 1 } }
    internal fun ensureOpen() { if (closed.load() || !lifetime.isActive) throw EventLoopClosedException() }

    /**
     * 宿主执行入口：完整调用互斥，所有恢复片段通过同一分段队列串行执行。
     * 为调用创建可取消的子 Job，close 可以停止在途 run，而不会取消宿主的父 Job。
     */
    suspend fun <T> runTask(block: suspend () -> T): T = executeTask(null, block)

    private suspend fun <T> executeTask(timer: TimerEntry?, block: suspend () -> T): T {
        ensureOpen()
        val caller = currentCoroutineContext()
        check(caller[Entry]?.loop !== this) { "Cannot reenter the same event loop execution" }
        caller.ensureActive()
        val operation = Job(caller[Job])
        operations.update { it + operation }
        // 先登记在途入口，再移除定时器，避免 runUntilIdle 看到虚假的空闲窗口。
        if (timer != null) timers.update { it - timer }
        pulse()
        if (closed.load()) operation.cancel(EventLoopClosedException())
        try {
            return withContext(operation + dispatcher + AsyncStartPhase() + Entry(this)) {
                runtimeMutex.withLock {
                    ensureOpen()
                    try {
                        block().also { currentCoroutineContext().ensureActive() }
                    } catch (error: CancellationException) {
                        discardPendingMicrotasks()
                        throw error
                    } catch (error: ExecutionLimitException) {
                        discardPendingMicrotasks()
                        throw error
                    }
                }
            }
        } finally {
            operation.complete()
            operations.update { it - operation }
            pulse()
        }
    }

    /** 等待全部定时器、async、在途入口和微任务结束；interval 或未完成的宿主等待不会被跳过。 */
    suspend fun runUntilIdle() {
        check(currentCoroutineContext()[Entry]?.loop !== this) { "Cannot wait for the current event loop task" }
        while (true) {
            ensureOpen()
            val observed = changes.value
            if (microtasks.size > 0 || unhandledRejections.size > 0) {
                drainMicrotasks()
                continue
            }
            // 分类快照不是单个原子状态；只有观察期间未收到变化通知才能确认空闲。
            if (pendingTasks.isIdle && changes.value == observed) return
            // 在检查前读取版本，避免检查与订阅之间的完成通知丢失。
            if (changes.value == observed) changes.first { it != observed }
        }
    }

    /**
     * 停止接收任务，取消定时器与协程并等待在途代码清理。重复调用仍等待同一次关闭。
     * 取消是协作式的：阻塞宿主代码需要自己支持取消；不能从本循环任务内等待自身退出。
     */
    suspend fun close() {
        check(currentCoroutineContext()[Entry]?.loop !== this) { "Cannot close an event loop from its own task" }
        closed.store(true)
        timers.value.forEach { it.cancel() }
        lifetime.cancel(EventLoopClosedException())
        operations.value.forEach { it.cancel(EventLoopClosedException()) }
        discardPendingMicrotasks()
        pulse()
        lifetime.join()
        while (true) {
            val observed = changes.value
            val current = operations.value
            if (current.isEmpty()) break
            current.forEach { it.join() }
            // Job 已完成时，入口的 finally 仍可能等待宿主调度器恢复，不能忙等占住它。
            if (operations.value.isNotEmpty() && changes.value == observed) changes.first { it != observed }
        }
        discardPendingMicrotasks()
        pulse()
    }

    /** 外部 async 恢复后自动建立微任务检查点，无需再执行一个空脚本才能推进 Promise。 */
    private fun onIdle() {
        pulse()
        if (closed.load() || !lifetime.isActive || draining ||
            (microtasks.size == 0 && unhandledRejections.size == 0) ||
            !checkpointScheduled.compareAndSet(false, true)) return
        scope.launch(AsyncStartPhase() + Entry(this)) {
            try {
                drainMicrotasksInternal()
            } catch (_: CancellationException) {
                discardPendingMicrotasks()
            } catch (_: ExecutionLimitException) {
                discardPendingMicrotasks()
            }
        }.invokeOnCompletion {
            checkpointScheduled.store(false)
            pulse()
        }
    }

    /** 调度登记先于宿主 schedule，兼容零延迟或同步触发的自定义调度器。 */
    private inner class TimerEntry {
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val handle = AtomicReference<ScheduledTask?>(null)
        fun cancel() {
            cancelled.store(true)
            // clearInterval 在回调内部只能阻止后续 tick，不能取消正在执行的 JS 代码。
            val scheduled = handle.exchange(null)
            if (!started.load()) scheduled?.cancel()
            timers.update { it - this }
            pulse()
        }
    }

    /** 微任务队列（FIFO）。 */
    private val microtasks = RuntimeQueue<suspend () -> Unit>()

    /** 当前 Engine.run 的预算；仅在持有 runtimeMutex 的执行期间安装。 */
    internal var executionBudget: ExecutionBudget? = null

    /**
     * 启动 async 运行时任务，保持同步执行到首次 await 的既有语义。
     *
     * 共享预算已经记录超限原因，由 Engine.run 的边界检查交给宿主；此处必须
     * 包住整个任务（含 JS 异常转 Promise 拒绝的路径），避免配额刚好在 reject
     * 调用时耗尽，逸出独立 launch 并被协程全局异常处理器再次上报。
     */
    internal suspend fun launchAsyncTask(block: suspend () -> Unit): Job {
        ensureOpen()
        val phase = AsyncStartPhase()
        val task = scope.launch(
            context = dispatcher + phase + Entry(this),
            start = CoroutineStart.UNDISPATCHED,
        ) {
            try {
                currentCoroutineContext().ensureActive()
                ensureOpen()
                block()
            } catch (_: ExecutionLimitException) {
                // Engine.run / drainMicrotasks 会观察到共享预算中的同一个失败。
            } finally {
                phase.ready.complete(Unit)
            }
        }
        phase.signalExternalSuspension()
        // 深层 async 的内部 yield 不能使调用方提前拿到 Promise；真正的宿主
        // 挂起或 JS await 已发出 ready，此处立即返回，与原有时序相同。
        internalExecutionSuspension { phase.ready.await() }
        return task
    }

    /**
     * 完整宿主入口之间的互斥锁，保留兼容性；仅持锁不能保证 async 恢复的串行化。
     * 扩展宿主应使用 runTask，同时接入共享恢复队列和生命周期追踪，不直接操作此锁。
     */
    val runtimeMutex: Mutex = Mutex()

    /** 已拒绝且尚无订阅的 Promise（在微任务队列清空后上报宿主）。 */
    private val unhandledRejections = RuntimeQueue<JSPromise>()

    /** 未处理拒绝的宿主回调；由 Engine 注入。 */
    internal var unhandledRejectionReporter: ((JSValue) -> Unit)? = null

    /** 已上报拒绝随后被处理时的宿主回调；由 Engine 注入。 */
    internal var rejectionHandledReporter: ((JSValue) -> Unit)? = null

    /** 登记一个未处理的拒绝，等待本轮微任务结束后确认。 */
    fun trackUnhandledRejection(promise: JSPromise) {
        ensureOpen()
        unhandledRejections.addLast(promise)
        if (closed.load()) { discardPendingMicrotasks(); throw EventLoopClosedException() }
        pulse()
    }

    /** 给已上报的拒绝补挂处理时通知宿主（只通知一次）。 */
    fun reportRejectionHandled(promise: JSPromise) {
        if (!promise.unhandledReported || promise.rejectionHandledReported) return
        promise.rejectionHandledReported = true
        val reporter = rejectionHandledReporter ?: return
        try {
            reporter(promise.result)
        } catch (error: CancellationException) {
            // 宿主中止执行的控制信号必须继续传播，不能当作日志回调失败忽略。
            throw error
        } catch (error: ExecutionLimitException) {
            throw error
        } catch (_: Throwable) {
            // 宿主回调异常不应破坏事件循环
        }
    }

    /** 排队一个微任务。 */
    fun enqueueMicrotask(task: suspend () -> Unit) {
        ensureOpen()
        microtasks.addLast(task)
        if (closed.load()) { discardPendingMicrotasks(); throw EventLoopClosedException() }
        pulse()
    }

    /**
     * 宿主取消当前执行时放弃本轮剩余任务与拒绝通知。
     *
     * 由 Engine 在持有 runtimeMutex 时调用，不执行任何用户回调；已产生的
     * Promise 状态和全局写入保持原样，这不是事务回滚或事件循环关闭。
     */
    internal fun discardPendingMicrotasks() {
        microtasks.clear()
        unhandledRejections.clear()
        pulse()
    }

    /**
     * 清空微任务队列。
     *
     * 微任务执行过程中可能继续入队，因此需要循环到队列为空。
     * 取消或配额耗尽直接退出，不能把宿主控制异常当作普通 JS 回调错误忽略。
     */
    suspend fun drainMicrotasks() {
        if (currentCoroutineContext()[Entry]?.loop === this) {
            drainMicrotasksInternal()
        } else {
            runTask { drainMicrotasksInternal() }
        }
    }

    private suspend fun drainMicrotasksInternal() {
        if (draining) return
        draining = true
        try {
            drainQueue()
        } finally {
            draining = false
            pulse()
        }
    }

    private suspend fun drainQueue() {
        // 即使队列为空，也要观察脚本最后一次宿主调用发出的取消。
        currentCoroutineContext().ensureActive()
        executionBudget?.ensureAvailable()
        while (microtasks.size > 0) {
            // 原生微任务可以不断重新入队而不经过 AST，需在此独立检查。
            currentCoroutineContext().ensureActive()
            executionBudget?.step()
            val task = microtasks.removeFirstOrNull() ?: break
            try {
                task()
            } catch (_: JSException) {
                // 微任务中的异常在阶段 C 会进入 Promise 拒绝回调
            }
            // 内部栈分段不是 JS await，必须完成当前回调的同步工作再取下一个微任务。
            drainExecutionStack()
            executionBudget?.ensureAvailable()
        }
        currentCoroutineContext().ensureActive()
        reportUnhandledRejections()
        executionBudget?.ensureAvailable()
    }

    /** 微任务清空后上报仍未订阅的拒绝；已订阅或已上报的跳过。 */
    private fun reportUnhandledRejections() {
        if (unhandledRejections.size == 0) return
        val reporter = unhandledRejectionReporter
        while (unhandledRejections.size > 0) {
            val promise = unhandledRejections.removeFirstOrNull() ?: break
            if (promise.handled || promise.unhandledReported ||
                promise.state != JSPromise.State.REJECTED
            ) {
                continue
            }
            promise.unhandledReported = true
            if (reporter != null) {
                try {
                    reporter(promise.result)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ExecutionLimitException) {
                    throw error
                } catch (_: Throwable) {
                    // 宿主回调异常不应破坏事件循环
                }
            }
        }
    }

    /**
     * 让出一次微任务周期。
     *
     * 供 `await` 使用：即使等待值已经就绪，也必须在下一个微任务继续执行。
     */
    suspend fun awaitMicrotask() {
        val deferred = CompletableDeferred<Unit>()
        enqueueMicrotask { deferred.complete(Unit) }
        deferred.await()
    }

    /** 排队一个宏任务；底层 Scheduler 只负责唤醒，实际执行统一进入 runTask。 */
    fun schedule(delayMillis: Long, task: suspend () -> Unit): ScheduledTask {
        ensureOpen()
        val entry = TimerEntry()
        timers.update { it + entry }
        pulse()
        try {
            val handle = scheduler.schedule(delayMillis) {
                if (!entry.started.compareAndSet(false, true) || entry.cancelled.load() || closed.load()) return@schedule
                try {
                    executeTask(entry) {
                        if (!entry.cancelled.load()) {
                            var aborted = false
                            try {
                                task()
                            } catch (error: CancellationException) {
                                aborted = true
                                throw error
                            } catch (error: ExecutionLimitException) {
                                aborted = true
                                throw error
                            } finally {
                                if (!aborted && !closed.load()) drainMicrotasksInternal()
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    if (!closed.load()) throw error
                } finally {
                    entry.handle.store(null)
                    timers.update { it - entry }
                    pulse()
                }
            }
            entry.handle.store(handle)
            if (entry.cancelled.load() || closed.load()) entry.cancel()
            // 同步触发的 Scheduler 可能已完成回调，返回句柄无需继续保留其闭包。
            if (entry.started.load()) entry.handle.store(null)
        } catch (error: Throwable) {
            entry.cancel()
            throw error
        }
        return ScheduledTask { entry.cancel() }
    }

    /** 周期定时器复用 Scheduler，在本次回调和微任务完成后安排下一次，避免重叠执行。 */
    fun scheduleInterval(delayMillis: Long, task: suspend () -> Unit): ScheduledTask {
        val cancelled = AtomicBoolean(false)
        val current = AtomicReference<ScheduledTask?>(null)
        fun scheduleNext() {
            if (cancelled.load() || closed.load()) return
            val next = schedule(delayMillis.coerceAtLeast(1)) {
                task()
                drainMicrotasks()
                scheduleNext()
            }
            current.store(next)
            if (cancelled.load() || closed.load()) next.cancel()
        }
        ensureOpen()
        scheduleNext()
        return ScheduledTask {
            cancelled.store(true)
            current.load()?.cancel()
        }
    }
}

/** 任务分类快照；asyncTasks 包含等待中的运行时协程，executions 包含等待执行锁的宿主入口。 */
data class PendingTasks(
    val microtasks: Int,
    val timers: Int,
    val asyncTasks: Int,
    val executions: Int,
    val rejectionNotifications: Int,
) {
    val total: Int get() = microtasks + timers + asyncTasks + executions + rejectionNotifications
    val isIdle: Boolean get() = total == 0
}
