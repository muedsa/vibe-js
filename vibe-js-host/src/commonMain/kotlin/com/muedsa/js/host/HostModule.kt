package com.muedsa.js.host

import com.muedsa.js.builtins.BuiltinHelpers
import com.muedsa.js.runtime.BuiltinModule
import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Console
import com.muedsa.js.runtime.EventLoop
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
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
import kotlinx.coroutines.sync.withLock

/** Base64 标准字母表（WHATWG forgiving-base64）。 */
private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * 浏览器风格的宿主环境模块。
 *
 * 提供：
 * - `window` / `self` 别名（指向 globalThis）
 * - `console`（log/error/warn/info/debug/time/timeEnd）
 * - `setTimeout` / `clearTimeout` / `queueMicrotask`
 * - `atob` / `btoa`
 * - `performance.now()` / `performance.timeOrigin`
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

    /** performance 时间原点（宿主时钟毫秒）；首次访问时确定。 */
    private var performanceOriginMillis: Double? = null

    /** console.time 的标签起始时刻（宿主时钟毫秒）。 */
    private val consoleTimers: MutableMap<String, Long> = mutableMapOf()

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
        installPerformance(registry)
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

        // console.time/timeEnd：使用宿主时钟，输出格式由宿主定义
        consoleObject.defineDataProperty(
            key = PropertyKey.Str("time"),
            value = BuiltinHelpers.method(intrinsics, "console.time", 0) { interpreter, _, args ->
                val label = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: "default"
                if (consoleTimers.containsKey(label)) {
                    console.log("Warning: Timer '$label' already exists")
                } else {
                    consoleTimers[label] = interpreter.currentTimeMillis().toLong()
                }
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        consoleObject.defineDataProperty(
            key = PropertyKey.Str("timeEnd"),
            value = BuiltinHelpers.method(intrinsics, "console.timeEnd", 0) { interpreter, _, args ->
                val label = args.getOrNull(0)?.let { interpreter.toStringValue(it) } ?: "default"
                val started = consoleTimers.remove(label)
                if (started == null) {
                    console.log("Warning: Timer '$label' does not exist")
                } else {
                    val elapsed = interpreter.currentTimeMillis().toLong() - started
                    console.log("$label: ${elapsed}ms")
                }
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )

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

    /** performance.now 的时间原点；首次访问使用当前时钟值。 */
    private fun performanceOrigin(interpreter: Interpreter): Double {
        val existing = performanceOriginMillis
        if (existing != null) return existing
        val current = interpreter.currentTimeMillis()
        performanceOriginMillis = current
        return current
    }

    private fun installPerformance(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val performanceObject = JSObject(prototype = intrinsics.objectPrototype)
        performanceObject.defineDataProperty(
            key = PropertyKey.Str("now"),
            value = BuiltinHelpers.method(intrinsics, "performance.now", 0) { interpreter, _, _ ->
                JSNumber(interpreter.currentTimeMillis() - performanceOrigin(interpreter))
            },
            writable = true,
            enumerable = true,
            configurable = true,
        )
        performanceObject.defineAccessorProperty(
            key = PropertyKey.Str("timeOrigin"),
            get = BuiltinHelpers.method(intrinsics, "get timeOrigin", 0) { interpreter, _, _ ->
                JSNumber(performanceOrigin(interpreter))
            },
            set = null,
            enumerable = true,
            configurable = true,
        )
        performanceObject.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("Performance"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        registry.defineGlobal(
            name = "performance",
            value = performanceObject,
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
                // 浏览器语义：延迟截断到 [0, 2^31-1]
                val delayMillis = delay.toLong().coerceIn(0L, Int.MAX_VALUE.toLong())
                val task = eventLoop.schedule(delayMillis) {
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
                    // 浏览器语义：clearTimeout 与 clearInterval 可互相取消
                    timers.remove(id)?.cancel()
                    intervals.remove(id)?.cancel()
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
                // 浏览器语义：周期延迟至少 1ms，且不超过 2^31-1
                val delayMillis = (args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: 0.0)
                    .toLong().coerceIn(1L, Int.MAX_VALUE.toLong())
                val extraArguments = if (args.size > 2) {
                    args.subList(2, args.size).toList()
                } else {
                    emptyList()
                }
                val id = nextTimerId++
                val job = eventLoop.scope.launch {
                    while (isActive) {
                        delay(delayMillis)
                        // 解释器不是线程安全的：与 Engine.run / 其它定时任务串行执行
                        eventLoop.runtimeMutex.withLock {
                            try {
                                interpreter.callFunction(callback, JSUndefined, extraArguments)
                            } catch (error: JSException) {
                                console.error(error.toString())
                            }
                            eventLoop.drainMicrotasks()
                        }
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
                    // 浏览器语义：clearInterval 与 clearTimeout 可互相取消
                    intervals.remove(id)?.cancel()
                    timers.remove(id)?.cancel()
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
                // WHATWG：按 Latin-1 码元编码，超过 U+00FF 的码元抛 InvalidCharacterError
                val bytes = ByteArray(text.length)
                for (index in text.indices) {
                    val code = text[index].code
                    if (code > 0xFF) {
                        throw invalidCharacterError(intrinsics)
                    }
                    bytes[index] = code.toByte()
                }
                JSString(encodeBase64(bytes))
            },
        )

        registry.defineGlobal(
            name = "atob",
            value = BuiltinHelpers.method(intrinsics, "atob", 1) { interpreter, _, args ->
                val raw = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                // WHATWG forgiving-base64：先剥离 ASCII 空白，再按宽松规则解码
                val text = buildString(raw.length) {
                    for (character in raw) {
                        if (!isAsciiWhitespace(character)) append(character)
                    }
                }
                val bytes = try {
                    decodeBase64(text)
                } catch (_: IllegalArgumentException) {
                    throw invalidCharacterError(intrinsics)
                }
                // WHATWG：每个字节对应一个 Latin-1 码元（而非 UTF-8 解码）
                JSString(buildString(bytes.size) {
                    for (byte in bytes) append((byte.toInt() and 0xFF).toChar())
                })
            },
        )
    }

    private fun invalidCharacterError(intrinsics: Intrinsics): JSException =
        JSException(
            JSError(
                "InvalidCharacterError",
                "The string to be encoded or decoded is not correctly encoded",
                intrinsics.errorPrototype,
            )
        )

    /** WHATWG ASCII whitespace：TAB/LF/FF/CR/SPACE。 */
    private fun isAsciiWhitespace(character: Char): Boolean =
        character == '\t' || character == '\n' || character == '\u000C' ||
            character == '\r' || character == ' '

    private fun encodeBase64(bytes: ByteArray): String {
        val builder = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xFF
            val second = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val third = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            val chunk = (first shl 16) or (second shl 8) or third
            builder.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
            builder.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
            builder.append(
                if (index + 1 < bytes.size) BASE64_ALPHABET[(chunk shr 6) and 0x3F] else '='
            )
            builder.append(if (index + 2 < bytes.size) BASE64_ALPHABET[chunk and 0x3F] else '=')
            index += 3
        }
        return builder.toString()
    }

    /**
     * 宽松 base64 解码：允许缺省尾部填充与任意位置的 ASCII 空白，
     * 忽略末端未使用的比特；长度余 1 或填充位置非法时抛 IllegalArgumentException。
     */
    private fun decodeBase64(text: String): ByteArray {
        if (text.isEmpty()) return ByteArray(0)
        if (text.length % 4 == 1) throw IllegalArgumentException("invalid base64 length")
        var dataLength = text.length
        while (dataLength > 0 && text[dataLength - 1] == '=') dataLength--
        val padding = text.length - dataLength
        if (padding > 2) throw IllegalArgumentException("invalid padding")
        if (padding != 0 && text.length % 4 != 0) throw IllegalArgumentException("invalid padding")
        val output = ByteArray(dataLength * 3 / 4)
        var outputIndex = 0
        var buffer = 0
        var bits = 0
        for (index in 0 until dataLength) {
            val value = BASE64_ALPHABET.indexOf(text[index])
            if (value < 0) throw IllegalArgumentException("invalid base64 character")
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output[outputIndex++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return if (outputIndex == output.size) output else output.copyOf(outputIndex)
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
