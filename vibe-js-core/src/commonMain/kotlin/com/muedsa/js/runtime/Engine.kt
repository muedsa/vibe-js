package com.muedsa.js.runtime

import com.muedsa.js.ast.Program
import com.muedsa.js.builtins.CoreBuiltins
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * JavaScript 引擎门面。
 *
 * 使用示例：
 * ```
 * val engine = Engine()
 * val result = engine.run("1 + 2")   // suspend
 * ```
 *
 * 设计要点：
 * - 核心只安装 ECMAScript 标准内建；
 * - 宿主能力（console、window、timer 等）通过 [install] 注册 BuiltinModule；
 * - 所有执行都是 suspend，便于后续接入 async/await；
 * - 同一个 Engine 可重复执行多段脚本，共享全局环境。
 *
 * @param parseCacheSize 相同源码的 AST LRU 缓存容量；0（默认）表示不缓存。
 * 含反引号的源码不缓存，确保标签模板站点对象仍按每次解析隔离。
 */
class Engine(
    val host: HostEnvironment = DefaultHostEnvironment(),
    private val parseCacheSize: Int = 0,
) {

    /** 全局对象。 */
    val globalObject = GlobalObject()

    /** 内建原型集合。 */
    val intrinsics = Intrinsics(globalObject)

    /** 内建注册表。 */
    val registry = BuiltinRegistry(intrinsics)

    /** 事件循环。 */
    val eventLoop = EventLoop(host.scheduler)

    /** 引擎上下文。 */
    val context = EngineContext(globalObject, intrinsics, registry, eventLoop, host)

    /** 解释器。 */
    val interpreter = Interpreter(context)

    /** 解析缓存及其互斥量：run(source) 可能被并发调用。 */
    private val parseCacheMutex = Mutex()
    private val parseCache = LinkedHashMap<String, Program>()

    init {
        require(parseCacheSize >= 0) { "parseCacheSize must be >= 0" }
        // 未处理拒绝在每轮微任务清空后交给宿主回调
        eventLoop.unhandledRejectionReporter = { reason -> host.reportUnhandledRejection(reason) }
        eventLoop.rejectionHandledReporter = { reason -> host.reportRejectionHandled(reason) }

        // 1. 创建原型骨架
        intrinsics.initialize()

        // 2. 构造全局环境：全局 let/const -> 全局对象环境（var/function）
        context.globalEnvironment = DeclarativeEnvironment(
            parent = ObjectEnvironment(
                bindingObject = globalObject,
                parent = null,
                access = interpreter,
                isVarScope = true,
            ),
            isVarScope = false,
        )

        // 3. 安装 ECMAScript 标准内建
        CoreBuiltins.install(registry)
    }

    /**
     * 预解析一段脚本，得到可重复执行的 AST。
     *
     * 解析阶段不接触全局环境，可在进入事件循环前完成；同一 AST 可多次执行，
     * 每次执行都会重新做全局声明实例化（与重复执行同一 Script 的语义一致）。
     */
    fun parse(source: String): Program {
        val tokens = Lexer(source).tokenize()
        return Parser(tokens, source = source).parseProgram()
    }

    /**
     * 执行一段脚本，返回最后一个表达式的值（通常是 undefined）。
     *
     * 开启 [parseCacheSize] 时，相同源码会复用已解析的 AST。
     */
    suspend fun run(source: String): JSValue {
        if (parseCacheSize == 0) return run(parse(source))
        val program = parseCacheMutex.withLock {
            val cached = parseCache.remove(source)
            if (cached != null) {
                parseCache[source] = cached
                cached
            } else {
                parse(source).also { compiled ->
                    if (!source.contains('`')) {
                        parseCache[source] = compiled
                        while (parseCache.size > parseCacheSize) {
                            parseCache.remove(parseCache.keys.first())
                        }
                    }
                }
            }
        }
        return run(program)
    }

    /**
     * 执行预解析得到的脚本 AST。
     */
    suspend fun run(program: Program): JSValue {
        // 解释器与全局环境不是线程安全的：与定时器回调等宿主任务串行执行
        return eventLoop.runtimeMutex.withLock {
            try {
                interpreter.runProgram(program, context.globalEnvironment)
            } catch (error: JSException) {
                throw error
            } catch (error: Throwable) {
                // 宿主异常（除零、栈溢出、未实现等）统一包装为 JS 异常，
                // 避免泄漏到调用方；后续阶段会进一步细分错误类型。
                throw JSException(
                    JSError(
                        errorName = "InternalError",
                        messageText = error.message ?: error::class.simpleName ?: "Internal error",
                    )
                )
            } finally {
                // 脚本结束后冲刷微任务（Promise.then 等）
                eventLoop.drainMicrotasks()
            }
        }
    }

    /**
     * 安装一个内建模块（宿主模块或第三方扩展）。
     */
    fun install(module: BuiltinModule) {
        registry.install(module)
    }
}
