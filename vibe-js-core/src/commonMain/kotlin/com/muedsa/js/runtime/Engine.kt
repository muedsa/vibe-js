package com.muedsa.js.runtime

import com.muedsa.js.builtins.CoreBuiltins
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSValue

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
 */
class Engine(
    val host: HostEnvironment = DefaultHostEnvironment(),
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

    init {
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
     * 执行一段脚本，返回最后一个表达式的值（通常是 undefined）。
     */
    suspend fun run(source: String): JSValue {
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parseProgram()
        try {
            return interpreter.runProgram(program, context.globalEnvironment)
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

    /**
     * 安装一个内建模块（宿主模块或第三方扩展）。
     */
    fun install(module: BuiltinModule) {
        registry.install(module)
    }
}
