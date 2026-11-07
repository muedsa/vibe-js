package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.FunctionScope
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.PromiseCapability
import com.muedsa.js.runtime.PromiseSupport
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.JSException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

/** 生成器恢复请求（对应规范中 GeneratorResumeAbrupt 传入的完成记录）。 */
internal sealed class GeneratorRequest {
    /** `next(value)`：恢复执行并把 value 作为 yield 表达式的值。 */
    class Next(val value: JSValue) : GeneratorRequest()

    /** `return(value)`：恢复执行并在 yield 处抛出内部控制流信号。 */
    class Return(val value: JSValue) : GeneratorRequest()

    /** `throw(value)`：恢复执行并在 yield 处抛出 JS 异常。 */
    class Throw(val value: JSValue) : GeneratorRequest()
}

/** 生成器执行步骤（函数体交还给恢复方的完成记录）。 */
internal sealed class GeneratorStep {
    /**
     * `yield value`：函数体在 yield 处挂起，value 为产出值。
     *
     * @property resultObject 同步 `yield*` 委托时直接交出的内层 IteratorResult 对象：
     *   规范对同步委托使用 `Yield(innerResult)`，外层的 next() 返回的正是该对象本身
     *   （因此 done 属性、value getter 的访问次数都与内层结果一致）
     */
    class Yielded(val value: JSValue, val resultObject: JSObject? = null) : GeneratorStep()

    /**
     * 函数体正常返回（含 return 完成与生成器边界捕获的 GeneratorReturn）。
     *
     * @property awaited async generator 的恢复值是否已在 yield 处等待过
     *   （AsyncGeneratorUnwrapYieldResumption）：已等待时不再重复执行 Await，
     *   否则 thenable 的 then 会被读取两次
     */
    class Returned(val value: JSValue, val awaited: Boolean = false) : GeneratorStep()

    /** 函数体抛出未被捕获的 JS 异常。 */
    class Threw(val exception: JSException) : GeneratorStep()

    /** 宿主执行配额耗尽：原样抛给调用方，不转换为可被 JS 捕获的异常。 */
    class LimitReached(val exception: ExecutionLimitException) : GeneratorStep()
}

/**
 * 生成器对象（同步 Generator，ES2015 27.5；异步 AsyncGenerator，ES2018 27.6）。
 *
 * 暂停/恢复通过两条容量为 1 的 [Channel] 完成：
 * - [GeneratorChannels.requests]：恢复方投递 Next/Return/Throw；
 * - [GeneratorChannels.steps]：函数体投递 Yielded/Returned/Threw。
 *
 * 函数体在首次 `next()` 时才启动（[GeneratorState.SUSPENDED_START]）；
 * 参数环境、默认值与 this/arguments 的绑定已在调用生成器函数时完成
 * （[FunctionScope]），首次 next 只恢复函数体执行，不重复绑定。
 * 在此之前调用 `return()`/`throw()` 不会执行任何函数体代码。
 *
 * 两种生成器复用同一状态机：同步版本直接在调用方协程里等待函数体步骤；
 * 异步版本（[isAsync]）把每个请求变成一个 Promise 任务，由 [driveAsyncQueue]
 * 在事件循环协程中串行驱动，并在独立微任务里落定 Promise。
 *
 * @property function 生成器函数对象
 * @property thisValue 创建时绑定的 this
 * @property scope 调用时完成参数绑定后的参数/var 环境，供首次 next 恢复函数体
 * @property isAsync 是否为 async generator（决定内建原型与 Promise 化的调用协议）
 */
class JSGenerator internal constructor(
    private val interpreter: Interpreter,
    val function: JSFunction,
    internal val thisValue: JSValue,
    internal val scope: FunctionScope,
    prototype: JSObject? = null,
    val isAsync: Boolean = false,
) : JSObject(prototype) {

    /** 请求 / 步骤通道组合。 */
    internal val channels = GeneratorChannels()

    /** 规范的 [[GeneratorState]]。 */
    private var state = GeneratorState.SUSPENDED_START

    /**
     * 规范 [[AsyncGeneratorQueue]]：async generator 的待处理请求。
     *
     * 队列项携带各自的 Promise capability，由唯一的驱动协程按 FIFO 顺序处理；
     * 因此在函数体 await 挂起期间再次调用 `next()` 只会排队，不会并发恢复函数体。
     */
    private val asyncQueue = ArrayDeque<AsyncRequest>()

    /** async 驱动协程是否在运行（包含等待函数体步骤的挂起期间）。 */
    private var asyncDriverRunning = false

    // ================================================================
    // 同步 Generator
    // ================================================================

    /** `next(value)`：推进到下一个 yield 或完成。 */
    suspend fun next(value: JSValue = JSUndefined): JSObject {
        checkNotRunning()
        return stepResult(advance(GeneratorRequest.Next(value)))
    }

    /** `return(value)`：让生成器以给定返回值结束（finally 仍会执行）。 */
    suspend fun returnValue(value: JSValue = JSUndefined): JSObject {
        checkNotRunning()
        return stepResult(advance(GeneratorRequest.Return(value)))
    }

    /** `throw(value)`：在挂起点抛出异常；未捕获时以异常结束生成器。 */
    suspend fun throwValue(value: JSValue): JSObject {
        checkNotRunning()
        return stepResult(advance(GeneratorRequest.Throw(value)))
    }

    /** 重入（例如函数体内调用自身的 next）必须抛 TypeError。 */
    private fun checkNotRunning() {
        if (state == GeneratorState.EXECUTING) {
            interpreter.typeError("Generator is already running")
        }
    }

    /**
     * 同步生成器的一步：按规范状态机推进并等待函数体步骤。
     *
     * 已完成的生成器不再执行函数体：`next()` 返回 { undefined, true }，
     * `return(value)` 返回 { value, true }，`throw(value)` 直接抛出该值。
     */
    private suspend fun advance(request: GeneratorRequest): GeneratorStep = when (state) {
        GeneratorState.COMPLETED -> when (request) {
            is GeneratorRequest.Next -> GeneratorStep.Returned(JSUndefined)
            is GeneratorRequest.Return -> GeneratorStep.Returned(request.value)
            is GeneratorRequest.Throw -> throw JSException(request.value)
        }

        GeneratorState.SUSPENDED_START -> when (request) {
            // 尚未启动：return/throw 直接完成生成器，不执行任何函数体代码
            is GeneratorRequest.Return -> {
                state = GeneratorState.COMPLETED
                GeneratorStep.Returned(request.value)
            }

            is GeneratorRequest.Throw -> {
                state = GeneratorState.COMPLETED
                throw JSException(request.value)
            }

            is GeneratorRequest.Next -> startBody()
        }

        GeneratorState.SUSPENDED_YIELD -> dispatch(request)

        // advance 只由单一线程（调用方协程或 async 驱动）串行调用，重入会先被 checkNotRunning 拦截
        GeneratorState.EXECUTING -> throw IllegalStateException("Generator is already running")
    }

    /** 首次 next：启动函数体协程并等待第一个步骤。 */
    private suspend fun startBody(): GeneratorStep {
        state = GeneratorState.EXECUTING
        // 函数体协程挂在事件循环作用域上（而不是当前 run），
        // 生成器因此可以跨多次脚本执行暂停与恢复。
        interpreter.launchGeneratorBody(this)
        return receiveStep()
    }

    /** 向已挂起的函数体投递恢复请求并等待下一个步骤。 */
    private suspend fun dispatch(request: GeneratorRequest): GeneratorStep {
        state = GeneratorState.EXECUTING
        // 函数体在等待请求时缓冲区必为空；trySend 失败说明协议被破坏，避免永久挂起
        if (!channels.requests.trySend(request).isSuccess) {
            state = GeneratorState.COMPLETED
            throw IllegalStateException("Generator is not waiting for a resume request")
        }
        return receiveStep()
    }

    /** 等待函数体给出的步骤并更新生成器状态。 */
    private suspend fun receiveStep(): GeneratorStep {
        val step = try {
            channels.steps.receive()
        } catch (error: Throwable) {
            // 协程被取消（宿主中止执行）时生成器不再可恢复
            state = GeneratorState.COMPLETED
            throw error
        }
        state = when (step) {
            is GeneratorStep.Yielded -> GeneratorState.SUSPENDED_YIELD
            // 完成与异常（含宿主配额耗尽）都结束生成器
            is GeneratorStep.Returned, is GeneratorStep.Threw, is GeneratorStep.LimitReached ->
                GeneratorState.COMPLETED
        }
        return step
    }

    /** 把函数体步骤转换为同步生成器的 { value, done } 结果对象。 */
    private fun stepResult(step: GeneratorStep): JSObject = when (step) {
        // 同步 yield* 委托直接把内层 IteratorResult 交给调用方（规范 Yield(innerResult)）
        is GeneratorStep.Yielded -> step.resultObject
            ?: createIterResult(step.value, done = false)

        is GeneratorStep.Returned -> createIterResult(step.value, done = true)
        is GeneratorStep.Threw -> throw step.exception
        is GeneratorStep.LimitReached -> throw step.exception
    }

    // ================================================================
    // async Generator（L1.4）
    // ================================================================

    /** `next(value)`：请求入队，返回一个在微任务中落定的 Promise。 */
    suspend fun nextAsync(value: JSValue = JSUndefined): JSValue =
        enqueueAsync(GeneratorRequest.Next(value))

    /** `return(value)`：请求入队；函数体内的 finally 与 using/IteratorClose 清理照常执行。 */
    suspend fun returnAsync(value: JSValue = JSUndefined): JSValue =
        enqueueAsync(GeneratorRequest.Return(value))

    /** `throw(value)`：把异常投递到挂起点，可被函数体内的 catch 捕获。 */
    suspend fun throwAsync(value: JSValue): JSValue =
        enqueueAsync(GeneratorRequest.Throw(value))

    /**
     * 把恢复请求加入队列并确保驱动协程在运行。
     *
     * 首次请求会同步执行函数体到首个 yield 或 await（与规范一致），
     * 但结果 Promise 始终延后到独立微任务中落定。
     */
    private suspend fun enqueueAsync(request: GeneratorRequest): JSValue {
        val capability = PromiseSupport.newPromiseCapability(
            interpreter,
            interpreter.intrinsics.promiseConstructor,
        )
        asyncQueue.addLast(AsyncRequest(request, capability))
        if (!asyncDriverRunning) {
            asyncDriverRunning = true
            interpreter.launchAsyncGeneratorDriver(this)
        }
        return capability.promise
    }

    /**
     * async generator 驱动循环：按 FIFO 串行处理队列中的请求。
     *
     * 函数体 await 挂起时本协程同样挂起在步骤通道上，因此队列中的后续请求
     * 会等待前一个请求落定后才恢复函数体（规范的中止语义）。
     */
    internal suspend fun driveAsyncQueue() {
        try {
            while (true) {
                val entry = asyncQueue.removeFirstOrNull() ?: break
                val step = try {
                    advanceAsync(entry.request)
                } catch (error: CancellationException) {
                    // 宿主取消：原样传播，交由事件循环终止整轮执行
                    throw error
                } catch (error: ExecutionLimitException) {
                    // 配额耗尽不属于 JS 错误，交给事件循环的终止边界
                    throw error
                } catch (error: Throwable) {
                    // 与 async 函数边界一致：内部错误转换为 InternalError 拒绝
                    GeneratorStep.Threw(internalError(error))
                }
                when (step) {
                    is GeneratorStep.Yielded ->
                        settleAsync(entry, step.value, done = false, error = null)

                    is GeneratorStep.Returned -> settleReturnAsync(entry, step)

                    is GeneratorStep.Threw ->
                        settleAsync(entry, JSUndefined, done = false, error = step.exception)

                    is GeneratorStep.LimitReached -> throw step.exception
                }
            }
        } finally {
            // 退出前队列一定为空（或进程被取消）：若期间又有请求入队，由调用方重新启动驱动
            asyncDriverRunning = false
        }
    }

    /**
     * async generator 的一步：状态机推进。
     *
     * 与同步版本的唯一差别是已完成的生成器不再抛异常，而是把完成记录
     * 交给驱动协程转换为 Promise 的兑现/拒绝。
     */
    private suspend fun advanceAsync(request: GeneratorRequest): GeneratorStep = when (state) {
        GeneratorState.COMPLETED -> when (request) {
            is GeneratorRequest.Next -> GeneratorStep.Returned(JSUndefined)
            is GeneratorRequest.Return -> GeneratorStep.Returned(request.value)
            is GeneratorRequest.Throw -> GeneratorStep.Threw(JSException(request.value))
        }

        GeneratorState.SUSPENDED_START -> when (request) {
            is GeneratorRequest.Return -> {
                state = GeneratorState.COMPLETED
                GeneratorStep.Returned(request.value)
            }

            is GeneratorRequest.Throw -> {
                state = GeneratorState.COMPLETED
                GeneratorStep.Threw(JSException(request.value))
            }

            is GeneratorRequest.Next -> startBody()
        }

        GeneratorState.SUSPENDED_YIELD -> dispatch(request)

        GeneratorState.EXECUTING -> throw IllegalStateException("Generator is already running")
    }

    /**
     * 在独立微任务中落定请求的 Promise（AsyncGeneratorResolve/Reject）。
     *
     * 规范把结果解析放在 Promise 作业里：即使在调用 `next()` 的同步栈上就取到了值，
     * 也必须多经过一轮微任务才可观察。这里用 capability 的 resolve/reject 函数，
     * 保持与 `Promise` 内建一致的落定时机与拒绝诊断。
     */
    private fun settleAsync(
        entry: AsyncRequest,
        value: JSValue,
        done: Boolean,
        error: JSException?,
    ) {
        interpreter.context.eventLoop.enqueueMicrotask {
            // 微任务可能在其他解释器执行期间运行，使用独立调用帧避免栈信息互相覆盖
            val settleInterpreter = interpreter.forkForCallback()
            if (error != null) {
                PromiseSupport.callReject(settleInterpreter, entry.capability.reject, error)
            } else {
                settleInterpreter.callFunction(
                    entry.capability.resolve,
                    JSUndefined,
                    listOf(createIterResult(value, done)),
                )
            }
        }
    }

    /**
     * 函数体以 return 完成时落定请求（AsyncGeneratorAwaitReturn）。
     *
     * 规范要求 return 完成值先经过 Await：thenable 会被解包，
     * 拒绝则以该原因拒绝本次请求的 Promise。
     * 生成器在 yield 处恢复时，恢复值已在 AsyncGeneratorUnwrapYieldResumption 中
     * 等待过（[GeneratorStep.Returned.awaited]），此时直接落定，避免重复读取 then。
     */
    private suspend fun settleReturnAsync(entry: AsyncRequest, step: GeneratorStep.Returned) {
        if (step.awaited) {
            settleAsync(entry, step.value, done = true, error = null)
            return
        }
        val awaited = try {
            interpreter.awaitAsyncGeneratorReturnValue(step.value)
        } catch (error: JSException) {
            settleAsync(entry, JSUndefined, done = false, error = error)
            return
        }
        settleAsync(entry, awaited, done = true, error = null)
    }

    /** 内部错误转换为可被 JS 捕获的 InternalError（与 async 函数边界一致）。 */
    private fun internalError(error: Throwable): JSException = JSException(
        JSError(
            errorName = "InternalError",
            messageText = error.message ?: error::class.simpleName ?: "Internal error",
            prototype = interpreter.intrinsics.errorPrototype,
        )
    )

    /** CreateIterResultObject：{ value, done }（属性顺序与规范一致）。 */
    private fun createIterResult(value: JSValue, done: Boolean): JSObject {
        val result = JSObject(prototype = interpreter.intrinsics.objectPrototype)
        result.defineDataProperty(
            PropertyKey.Str("value"),
            value,
            writable = true,
            enumerable = true,
            configurable = true,
        )
        result.defineDataProperty(
            PropertyKey.Str("done"),
            JSBoolean.getJsBoolean(done),
            writable = true,
            enumerable = true,
            configurable = true,
        )
        return result
    }

    /** async generator 的队列项：恢复请求 + 结果 Promise capability。 */
    private class AsyncRequest(
        val request: GeneratorRequest,
        val capability: PromiseCapability,
    )
}

/** 生成器暂停/恢复使用的两条通道。 */
internal class GeneratorChannels {
    /** 恢复方 -> 函数体。 */
    val requests = Channel<GeneratorRequest>(capacity = 1)

    /** 函数体 -> 恢复方。 */
    val steps = Channel<GeneratorStep>(capacity = 1)
}

/** 规范 [[GeneratorState]]。 */
private enum class GeneratorState {
    /** 尚未启动（suspendedStart）。 */
    SUSPENDED_START,

    /** 在 yield 处挂起（suspendedYield）。 */
    SUSPENDED_YIELD,

    /** 正在执行（executing）。 */
    EXECUTING,

    /** 已完成（completed）。 */
    COMPLETED,
}
