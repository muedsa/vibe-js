package com.muedsa.js.runtime

import com.muedsa.js.ast.Program
import com.muedsa.js.builtins.CoreBuiltins
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.module.EmptyModuleProvider
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.module.internal.ModuleEvaluator
import com.muedsa.js.runtime.module.internal.ModuleGraph
import com.muedsa.js.runtime.module.internal.ModuleLinker
import com.muedsa.js.runtime.module.internal.ModuleRuntime
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
 * @param limits 执行步数与调用深度限制；每次 run 单独计算步数。
 */
class Engine(
    val host: HostEnvironment = DefaultHostEnvironment(),
    private val parseCacheSize: Int = 0,
    val limits: EngineLimits = EngineLimits(),
) {

    /** 全局对象。 */
    val globalObject = GlobalObject()

    /** 内建原型集合。 */
    val intrinsics = Intrinsics(globalObject)

    /** 内建注册表。 */
    val registry = BuiltinRegistry(intrinsics, host)

    /** 事件循环。 */
    val eventLoop = EventLoop(
        host.scheduler,
        maxPendingTasks = limits.maxPendingTasks,
        maxMicrotasksPerRun = limits.maxMicrotasksPerRun,
    )

    /** 引擎上下文。 */
    val context = EngineContext(globalObject, intrinsics, registry, eventLoop, host, limits)

    /** 解释器。 */
    val interpreter = Interpreter(context)

    /** 解析缓存及其互斥量：run(source) 可能被并发调用。 */
    private val parseCacheMutex = Mutex()
    private data class ParseCacheKey(val source: String, val sourceInfo: SourceInfo)
    private val parseCache = LinkedHashMap<ParseCacheKey, Program>()

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

        // 4. 把对象分配预算挂到 realm 根原型。
        // 初始化期间创建的内建对象发生在预算启用前，不计入任何单次 run；
        // 运行期对象在构造时沿原型链继承该预算，天然按引擎隔离。
        globalObject.allocationBudget = context.objectAllocations
        intrinsics.objectPrototype.allocationBudget = context.objectAllocations
    }

    /**
     * 预解析一段脚本，得到可重复执行的 AST。
     *
     * 解析阶段不接触全局环境，可在进入事件循环前完成；同一 AST 可多次执行，
     * 每次执行都会重新做全局声明实例化（与重复执行同一 Script 的语义一致）。
     *
     * @param sourceName 运行时错误栈中显示的脚本名，默认 <anonymous>。
     * @param lineOffset 显示行号时增加的非负偏移；AST 行列仍相对于 source。
     */
    fun parse(source: String, sourceName: String = "<anonymous>", lineOffset: Int = 0): Program {
        // 先在进入词法分析前拒绝超长源码，避免为不可信输入分配 Token 列表。
        limits.checkSourceLength(source.length)
        val sourceInfo = SourceInfo(sourceName, lineOffset)
        val tokens = Lexer(source, maxNestingDepth = limits.maxNestingDepth).tokenize()
        val parsed = Parser(
            tokens,
            source = source,
            maxNestingDepth = limits.maxNestingDepth,
            maxAstNodes = limits.maxAstNodes,
        ).parseProgram()
        return Program(
            parsed.body, parsed.strict, parsed.varDeclarations, parsed.functionDeclarations,
            parsed.range, parsed.hasUsingDeclaration, sourceInfo,
        )
    }

    /**
     * 执行一段脚本，返回最后一个表达式的值（通常是 undefined）。
     *
     * 开启 [parseCacheSize] 时，源码、脚本名和行偏移都相同才复用已解析的 AST。
     * [sourceName] 与非负的 [lineOffset] 含义同 [parse]。
     */
    suspend fun run(source: String, sourceName: String = "<anonymous>", lineOffset: Int = 0): JSValue {
        // 已取消的调用不应继续解析源码或改动 AST 缓存。
        currentCoroutineContext().ensureActive()
        eventLoop.ensureOpen()
        if (parseCacheSize == 0) return run(parse(source, sourceName, lineOffset))
        // 来源属于 AST 的诊断信息，必须纳入缓存键，避免同一源码串用另一个文件名。
        val key = ParseCacheKey(source, SourceInfo(sourceName, lineOffset))
        val program = parseCacheMutex.withLock {
            val cached = parseCache.remove(key)
            if (cached != null) {
                parseCache[key] = cached
                cached
            } else {
                parse(source, sourceName, lineOffset).also { compiled ->
                    if (!source.contains('`')) {
                        parseCache[key] = compiled
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
     *
     * 协程取消原样传播给宿主，不转换为可被 JS catch 捕获的异常。取消时丢弃
     * 尚未执行的微任务，释放运行时锁；已经完成的全局写入不会回滚。
     */
    suspend fun run(program: Program): JSValue {
        currentCoroutineContext().ensureActive()
        // 解释器与全局环境不是线程安全的：与定时器回调等宿主任务串行执行
        return eventLoop.runTask {
            // 预算在取得锁后建立，覆盖主脚本与本轮微任务；其他 run 不共享计数。
            val previousBudget = eventLoop.executionBudget
            val budget = ExecutionBudget(limits.maxSteps)
            eventLoop.executionBudget = budget
            // 对象分配预算同样按单次 run 重置；窗口外的内建/宿主对象不计入。
            context.objectAllocations.begin()
            try {
                var aborted = false
                try {
                    interpreter.runProgram(program, context.globalEnvironment).also {
                        // async 协程可能已耗尽共享预算但没有通过同步调用栈抛出异常。
                        budget.ensureAvailable()
                    }
                } catch (error: CancellationException) {
                    // 取消后不再执行用户微任务，防止清理阶段再次陷入脚本死循环，
                    // 也避免微任务异常覆盖宿主抛出的原始取消对象。
                    aborted = true
                    throw error
                } catch (error: ExecutionLimitException) {
                    // 步数耗尽与取消一样直接中止，不再运行用户清理代码。
                    aborted = true
                    throw error
                } finally {
                    // 正常完成和 JS 抛错仍保持原有的微任务检查点语义。
                    if (!aborted) eventLoop.drainMicrotasks()
                }
            } catch (error: CancellationException) {
                // 同时覆盖脚本执行和微任务冲刷阶段的取消；下一次 run 不应继续
                // 消费本轮遗留的任务。定时器与独立 async 协程的生命周期另行管理。
                eventLoop.discardPendingMicrotasks()
                throw error
            } catch (error: ExecutionLimitException) {
                eventLoop.discardPendingMicrotasks()
                throw error
            } catch (error: JSException) {
                throw error
            } catch (error: Throwable) {
                // 宿主异常（除零、栈溢出、未实现等）统一包装为 JS 异常，
                // 避免泄漏到调用方；细分错误类型时保持这一层包装与错误栈来源。
                throw JSException(
                    JSError(
                        errorName = "InternalError",
                        messageText = error.message ?: error::class.simpleName ?: "Internal error",
                        prototype = intrinsics.errorPrototype,
                    ),
                    stack = interpreter.hostFailureStack ?: emptyList(),
                )
            } finally {
                // 包括取消、配额耗尽及 JS 抛错，均不能把旧预算带入下一次调用。
                context.objectAllocations.end()
                eventLoop.executionBudget = previousBudget
            }
        }
    }

    /**
     * 执行一个模块图，返回入口模块的 namespace。
     *
     * 模块图、链接和求值状态都属于本次调用；失败不会污染下一次调用。
     * provider 负责说明符规范化与实际读取，核心不引入平台 IO。
     */
    @ExperimentalModuleApi
    suspend fun runModule(
        entry: ModuleSource,
        provider: ModuleProvider = EmptyModuleProvider,
    ): JSObject {
        currentCoroutineContext().ensureActive()
        eventLoop.ensureOpen()
        val runtime = ModuleRuntime(provider, limits)
        val graph = runtime.loadEntry(entry)
        return executeModuleGraph(graph, runtime)
    }

    /** 从入口说明符加载并执行模块图。 */
    @ExperimentalModuleApi
    suspend fun runModule(
        entrySpecifier: String,
        provider: ModuleProvider,
    ): JSObject {
        currentCoroutineContext().ensureActive()
        eventLoop.ensureOpen()
        val runtime = ModuleRuntime(provider, limits)
        val graph = runtime.loadEntry(entrySpecifier)
        return executeModuleGraph(graph, runtime)
    }

    @OptIn(ExperimentalModuleApi::class)
    private suspend fun executeModuleGraph(graph: ModuleGraph, runtime: ModuleRuntime): JSObject {
        ModuleLinker.link(graph)
        interpreter.moduleRuntime = runtime
        return eventLoop.runTask {
            val previousBudget = eventLoop.executionBudget
            val budget = ExecutionBudget(limits.maxSteps)
            eventLoop.executionBudget = budget
            context.objectAllocations.begin()
            try {
                var aborted = false
                try {
                    try {
                        ModuleEvaluator(interpreter).evaluate(graph).also {
                            budget.ensureAvailable()
                        }
                    } catch (error: CancellationException) {
                        aborted = true
                        throw error
                    } catch (error: ExecutionLimitException) {
                        aborted = true
                        throw error
                    } finally {
                        if (!aborted) eventLoop.drainMicrotasks()
                    }
                } catch (error: CancellationException) {
                    eventLoop.discardPendingMicrotasks()
                    throw error
                } catch (error: ExecutionLimitException) {
                    eventLoop.discardPendingMicrotasks()
                    throw error
                } catch (error: ModuleLinkException) {
                    throw error
                } catch (error: ResourceLimitException) {
                    throw error
                } catch (error: JSException) {
                    throw error
                } catch (error: Throwable) {
                    throw JSException(
                        JSError(
                            errorName = "InternalError",
                            messageText = error.message ?: error::class.simpleName ?: "Internal error",
                            prototype = intrinsics.errorPrototype,
                        ),
                        stack = interpreter.hostFailureStack ?: emptyList(),
                    )
                }
            } finally {
                context.objectAllocations.end()
                eventLoop.executionBudget = previousBudget
            }
        }
    }

    /** 关闭本引擎的事件循环并等待其运行时任务完成清理。 */
    suspend fun close() { eventLoop.close() }

    /**
     * 安装一个内建模块（宿主模块或第三方扩展）。
     */
    fun install(module: BuiltinModule) {
        registry.install(module)
    }
}
