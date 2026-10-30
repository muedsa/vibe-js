package com.muedsa.js.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * 控制台输出抽象。
 *
 * 默认实现使用 Kotlin 的 println，宿主可以注入自己的实现（例如输出到 UI 日志面板）。
 */
interface Console {
    fun log(message: String)
    fun error(message: String)
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
 * 阶段 A 只提供接口与默认实现；setTimeout / Promise 会在后续阶段使用。
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
 * 宿主环境：控制台、时钟、调度器。
 *
 * 核心运行时只依赖这些抽象；浏览器特性（window/console/timer 等对象）
 * 由独立的宿主模块通过 BuiltinModule 注册。
 */
@OptIn(ExperimentalTime::class)
interface HostEnvironment {
    val console: Console
    val clock: Clock
    val scheduler: Scheduler
}

/** 默认宿主环境。 */
@OptIn(ExperimentalTime::class)
class DefaultHostEnvironment(
    override val console: Console = PrintlnConsole(),
    override val clock: Clock = Clock.System,
    override val scheduler: Scheduler = CoroutineScheduler(),
) : HostEnvironment
