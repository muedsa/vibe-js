package com.muedsa.js.runtime

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSPromise
import com.muedsa.js.runtime.value.JSValue

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 微任务 / 宏任务事件循环。
 *
 * 设计说明：
 * - Promise.then 等微任务进入 [enqueueMicrotask] 队列；
 * - setTimeout 等宏任务交给 [Scheduler]（宿主实现）；
 * - scope 用于运行 async 函数体（Unconfined 保证同步执行到第一个 await）。
 */
class EventLoop(
    private val scheduler: Scheduler = CoroutineScheduler(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
) {

    /** 微任务队列（FIFO）。 */
    private val microtasks: ArrayDeque<suspend () -> Unit> = ArrayDeque()

    /**
     * 运行时互斥锁：解释器、环境与微任务队列都不是线程安全的。
     *
     * 宿主通过 Engine.run 执行脚本、定时器回调在后台线程恢复执行时，
     * 必须持有该锁串行化（Engine.run、EventLoop.schedule 与 HostModule 的
     * setInterval 回调均使用此锁）。
     */
    val runtimeMutex: Mutex = Mutex()

    /** 已拒绝且尚无订阅的 Promise（在微任务队列清空后上报宿主）。 */
    private val unhandledRejections: ArrayDeque<JSPromise> = ArrayDeque()

    /** 未处理拒绝的宿主回调；由 Engine 注入。 */
    internal var unhandledRejectionReporter: ((JSValue) -> Unit)? = null

    /** 已上报拒绝随后被处理时的宿主回调；由 Engine 注入。 */
    internal var rejectionHandledReporter: ((JSValue) -> Unit)? = null

    /** 登记一个未处理的拒绝，等待本轮微任务结束后确认。 */
    fun trackUnhandledRejection(promise: JSPromise) {
        unhandledRejections.addLast(promise)
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
        } catch (_: Throwable) {
            // 宿主回调异常不应破坏事件循环
        }
    }

    /** 排队一个微任务。 */
    fun enqueueMicrotask(task: suspend () -> Unit) {
        microtasks.addLast(task)
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
    }

    /**
     * 清空微任务队列。
     *
     * 微任务执行过程中可能继续入队，因此需要循环到队列为空。
     * 阶段 A 忽略未处理的 Promise 拒绝（后续阶段接入 unhandled rejection 处理）。
     */
    suspend fun drainMicrotasks() {
        // 即使队列为空，也要观察脚本最后一次宿主调用发出的取消。
        currentCoroutineContext().ensureActive()
        while (microtasks.isNotEmpty()) {
            // 原生微任务可以不断重新入队而不经过 AST，需在此独立检查。
            currentCoroutineContext().ensureActive()
            val task = microtasks.removeFirst()
            try {
                task()
            } catch (_: JSException) {
                // 微任务中的异常在阶段 C 会进入 Promise 拒绝回调
            }
        }
        currentCoroutineContext().ensureActive()
        reportUnhandledRejections()
    }

    /** 微任务清空后上报仍未订阅的拒绝；已订阅或已上报的跳过。 */
    private fun reportUnhandledRejections() {
        if (unhandledRejections.isEmpty()) return
        val reporter = unhandledRejectionReporter
        while (unhandledRejections.isNotEmpty()) {
            val promise = unhandledRejections.removeFirst()
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

    /** 排队一个宏任务（定时器）。 */
    fun schedule(delayMillis: Long, task: suspend () -> Unit): ScheduledTask =
        scheduler.schedule(delayMillis) {
            // 定时任务可能在任意线程恢复，与 Engine.run 串行执行
            runtimeMutex.withLock {
                task()
                drainMicrotasks()
            }
        }
}
