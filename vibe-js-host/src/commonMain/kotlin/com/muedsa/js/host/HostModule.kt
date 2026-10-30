package com.muedsa.js.host

import com.muedsa.js.builtins.BuiltinHelpers
import com.muedsa.js.runtime.BuiltinModule
import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Console
import com.muedsa.js.runtime.EventLoop
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.PrintlnConsole
import com.muedsa.js.runtime.ScheduledTask
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64

/**
 * 浏览器风格的宿主环境模块。
 *
 * 提供：
 * - `window` / `self` 别名（指向 globalThis）
 * - `console`（log/error/warn/info/debug）
 * - `setTimeout` / `clearTimeout` / `queueMicrotask`
 * - `atob` / `btoa`
 *
 * 说明：不绑定任何平台原生 API；定时任务通过核心 EventLoop 的 Scheduler 执行，
 * console 输出通过可注入的 [Console] 实现。
 *
 * 使用方式：
 * ```
 * val engine = Engine()
 * engine.install(HostModule(engine.eventLoop, engine.host.console))
 * ```
 */
class HostModule(
    private val eventLoop: EventLoop,
    private val console: Console = PrintlnConsole(),
) : BuiltinModule {

    private var nextTimerId: Int = 1
    private val timers: MutableMap<Int, ScheduledTask> = mutableMapOf()
    private val intervals: MutableMap<Int, Job> = mutableMapOf()

    override fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val globalObject = registry.globalObject

        // window / self 指向全局对象
        registry.defineGlobal(
            name = "window",
            value = globalObject,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        registry.defineGlobal(
            name = "self",
            value = globalObject,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        installConsole(registry)
        installTimers(registry)
        installEncoding(registry)
    }

    private fun installConsole(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val consoleObject = JSObject(prototype = intrinsics.objectPrototype)

        fun method(name: String, length: Int, output: (String) -> Unit) {
            consoleObject.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, "console.$name", length) { interpreter, _, args ->
                    output(formatArguments(interpreter, args))
                    JSUndefined
                },
            )
        }

        method("log", 1) { console.log(it) }
        method("info", 1) { console.log(it) }
        method("debug", 1) { console.log(it) }
        method("warn", 1) { console.log(it) }
        method("error", 1) { console.error(it) }

        consoleObject.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("console"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        registry.defineGlobal(
            name = "console",
            value = consoleObject,
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }

    private fun installTimers(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics

        registry.defineGlobal(
            name = "setTimeout",
            value = BuiltinHelpers.method(intrinsics, "setTimeout", 2) { interpreter, _, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("setTimeout requires a function as the first argument")
                }
                val delay = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: 0.0
                val extraArguments = if (args.size > 2) {
                    args.subList(2, args.size).toList()
                } else {
                    emptyList()
                }
                val id = nextTimerId++
                val task = eventLoop.schedule(delay.toLong().coerceAtLeast(0)) {
                    timers.remove(id)
                    try {
                        interpreter.callFunction(callback, JSUndefined, extraArguments)
                    } catch (error: JSException) {
                        console.error(error.toString())
                    }
                    eventLoop.drainMicrotasks()
                }
                timers[id] = task
                JSNumber(id.toDouble())
            },
        )

        registry.defineGlobal(
            name = "clearTimeout",
            value = BuiltinHelpers.method(intrinsics, "clearTimeout", 1) { interpreter, _, args ->
                val id = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() }
                if (id != null) {
                    timers.remove(id)?.cancel()
                }
                JSUndefined
            },
        )

        registry.defineGlobal(
            name = "setInterval",
            value = BuiltinHelpers.method(intrinsics, "setInterval", 2) { interpreter, _, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("setInterval requires a function as the first argument")
                }
                val delayMillis = (args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: 0.0)
                    .toLong().coerceAtLeast(1)
                val extraArguments = if (args.size > 2) {
                    args.subList(2, args.size).toList()
                } else {
                    emptyList()
                }
                val id = nextTimerId++
                val job = eventLoop.scope.launch {
                    while (isActive) {
                        delay(delayMillis)
                        try {
                            interpreter.callFunction(callback, JSUndefined, extraArguments)
                        } catch (error: JSException) {
                            console.error(error.toString())
                        }
                        eventLoop.drainMicrotasks()
                    }
                }
                intervals[id] = job
                JSNumber(id.toDouble())
            },
        )

        registry.defineGlobal(
            name = "clearInterval",
            value = BuiltinHelpers.method(intrinsics, "clearInterval", 1) { interpreter, _, args ->
                val id = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() }
                if (id != null) {
                    intervals.remove(id)?.cancel()
                }
                JSUndefined
            },
        )

        registry.defineGlobal(
            name = "queueMicrotask",
            value = BuiltinHelpers.method(intrinsics, "queueMicrotask", 1) { interpreter, _, args ->
                val callback = args.getOrNull(0) ?: JSUndefined
                if (!interpreter.isCallable(callback)) {
                    interpreter.typeError("queueMicrotask requires a function as the first argument")
                }
                eventLoop.enqueueMicrotask {
                    interpreter.callFunction(callback, JSUndefined, emptyList())
                }
                JSUndefined
            },
        )
    }

    private fun installEncoding(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics

        registry.defineGlobal(
            name = "btoa",
            value = BuiltinHelpers.method(intrinsics, "btoa", 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSString(Base64.encode(text.encodeToByteArray()))
            },
        )

        registry.defineGlobal(
            name = "atob",
            value = BuiltinHelpers.method(intrinsics, "atob", 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                try {
                    JSString(Base64.decode(text).decodeToString())
                } catch (_: Throwable) {
                    throw JSException(
                        JSError(
                            "InvalidCharacterError",
                            "The string to be decoded is not correctly encoded",
                            intrinsics.errorPrototype,
                        )
                    )
                }
            },
        )
    }

    private suspend fun formatArguments(interpreter: Interpreter, args: List<JSValue>): String {
        val parts = ArrayList<String>(args.size)
        for (argument in args) {
            parts.add(
                if (argument is JSString) {
                    argument.value
                } else {
                    interpreter.toStringValue(argument)
                }
            )
        }
        return parts.joinToString(" ")
    }
}
