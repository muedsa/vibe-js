package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * 控制台输出抽象。
 *
 * 默认实现使用 Kotlin 的 println，宿主可以注入自己的实现（例如输出到 UI 日志面板）。
 */
interface Console {
    fun log(message: String)
    fun error(message: String)
}

/**
 * 单调时钟抽象。
 *
 * 只保证同一来源的读数**单调不减**，起点任意；用于 `performance.now()`、
 * `console.time/timeEnd` 这类只关心差值的场景。系统时间（[HostEnvironment.clock]）
 * 可能被用户或 NTP 回拨，不能用于测量耗时。
 */
fun interface MonotonicClock {
    /** 当前单调时间（毫秒，可含小数）。 */
    fun nowMillis(): Double
}

/**
 * 默认单调时钟：基于 [TimeSource.Monotonic]，起点为首次访问时刻。
 *
 * 取微秒精度再换算为毫秒，保证 `performance.now()` 具备亚毫秒分辨率。
 */
object SystemMonotonicClock : MonotonicClock {

    private val origin = TimeSource.Monotonic.markNow()

    override fun nowMillis(): Double = origin.elapsedNow().inWholeMicroseconds / 1000.0
}

/** 默认控制台：输出到标准输出。 */
class PrintlnConsole : Console {
    override fun log(message: String) {
        println(message)
    }

    override fun error(message: String) {
        println(message)
    }
}

/**
 * 定时任务调度抽象。
 *
 * 默认实现基于 Kotlin 协程；宿主模块的 setTimeout/setInterval 通过它调度，
 * 也可注入自定义实现以对接平台定时器。
 */
interface Scheduler {
    fun schedule(delayMillis: Long, block: suspend () -> Unit): ScheduledTask
}

/** 可取消的定时任务句柄。 */
class ScheduledTask(private val cancelAction: () -> Unit) {
    fun cancel() {
        cancelAction()
    }
}

/**
 * 基于 Kotlin 协程的默认调度器。
 *
 * 说明：这里的 delay 在 JS/Wasm 目标上由 Kotlin 协程实现，
 * 不直接绑定浏览器原生 API，符合"通用库"的定位。
 */
class CoroutineScheduler(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : Scheduler {

    override fun schedule(delayMillis: Long, block: suspend () -> Unit): ScheduledTask {
        val job: Job = scope.launch {
            if (delayMillis > 0) delay(delayMillis)
            block()
        }
        return ScheduledTask { job.cancel() }
    }
}

/**
 * 宿主环境：控制台、墙钟、单调时钟、调度器。
 *
 * 核心运行时只依赖这些抽象；浏览器特性（window/console/timer 等对象）
 * 由独立的宿主模块通过 BuiltinModule 注册。
 *
 * 两种时钟的分工：`Date`/`Date.now()` 用 [clock]（可能回拨），
 * `performance.now()` 与 `console.time` 用 [monotonicClock]（只增不减）。
 */
@OptIn(ExperimentalTime::class)
interface HostEnvironment {
    val console: Console
    val clock: Clock
    val scheduler: Scheduler

    /**
     * 单调时钟：`performance.now()` 与 `console.time` 的差值基准。
     *
     * 默认使用 [SystemMonotonicClock]；宿主可注入自己的实现（例如测试中的可控时钟）。
     * 默认实现保证已有宿主实现无需改动即可编译。
     */
    val monotonicClock: MonotonicClock
        get() = SystemMonotonicClock

    /** 微任务清空后仍未处理的 Promise 拒绝；默认忽略。 */
    fun reportUnhandledRejection(reason: JSValue) {}

    /** 已上报的拒绝随后被 then/catch 处理；默认忽略。 */
    fun reportRejectionHandled(reason: JSValue) {}

    /** 指定 UTC 时刻的本地时区偏移（毫秒，东为正）；默认 0（UTC）。 */
    fun utcOffsetMillis(epochMillis: Long): Long = 0L
}

/** 默认宿主环境；可选注入时区偏移与 Promise 拒绝回调。 */
@OptIn(ExperimentalTime::class)
class DefaultHostEnvironment(
    override val console: Console = PrintlnConsole(),
    override val clock: Clock = Clock.System,
    override val scheduler: Scheduler = CoroutineScheduler(),
    private val timeZoneOffset: (Long) -> Long = { 0L },
    private val unhandledRejectionHandler: ((JSValue) -> Unit)? = null,
    private val rejectionHandledHandler: ((JSValue) -> Unit)? = null,
    override val monotonicClock: MonotonicClock = SystemMonotonicClock,
) : HostEnvironment {

    override fun utcOffsetMillis(epochMillis: Long): Long = timeZoneOffset(epochMillis)

    override fun reportUnhandledRejection(reason: JSValue) {
        unhandledRejectionHandler?.invoke(reason)
    }

    override fun reportRejectionHandled(reason: JSValue) {
        rejectionHandledHandler?.invoke(reason)
    }
}
