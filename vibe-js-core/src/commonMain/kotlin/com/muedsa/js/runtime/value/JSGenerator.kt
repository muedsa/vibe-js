package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.JSException
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
    /** `yield value`：函数体在 yield 处挂起，value 为产出值。 */
    class Yielded(val value: JSValue) : GeneratorStep()

    /** 函数体正常返回（含 return 完成与生成器边界捕获的 GeneratorReturn）。 */
    class Returned(val value: JSValue) : GeneratorStep()

    /** 函数体抛出未被捕获的 JS 异常。 */
    class Threw(val exception: JSException) : GeneratorStep()

    /** 宿主执行配额耗尽：原样抛给调用方，不转换为可被 JS 捕获的异常。 */
    class LimitReached(val exception: ExecutionLimitException) : GeneratorStep()
}

/**
 * 生成器对象（同步 Generator，ES2015 25.3）。
 *
 * 暂停/恢复通过两条容量为 1 的 [Channel] 完成：
 * - [GeneratorChannels.requests]：恢复方投递 Next/Return/Throw；
 * - [GeneratorChannels.steps]：函数体投递 Yielded/Returned/Threw。
 *
 * 函数体在首次 `next()` 时才启动（[GeneratorState.SUSPENDED_START]），
 * 在此之前调用 `return()`/`throw()` 不会执行任何函数体代码。
 *
 * @property function 生成器函数对象
 * @property thisValue 创建时绑定的 this
 * @property arguments 创建时捕获的实参（参数绑定延迟到首次 next）
 */
class JSGenerator internal constructor(
    private val interpreter: Interpreter,
    val function: JSFunction,
    internal val thisValue: JSValue,
    internal val arguments: List<JSValue>,
    prototype: JSObject? = null,
) : JSObject(prototype) {

    /** 请求 / 步骤通道组合。 */
    internal val channels = GeneratorChannels()

    /** 规范的 [[GeneratorState]]。 */
    private var state = GeneratorState.SUSPENDED_START

    /** `next(value)`：推进到下一个 yield 或完成。 */
    suspend fun next(value: JSValue = JSUndefined): JSObject {
        checkNotRunning()
        return when (state) {
            GeneratorState.COMPLETED -> createIterResult(JSUndefined, done = true)
            GeneratorState.SUSPENDED_START -> startBody()
            else -> dispatch(GeneratorRequest.Next(value))
        }
    }

    /** `return(value)`：让生成器以给定返回值结束（finally 仍会执行）。 */
    suspend fun returnValue(value: JSValue = JSUndefined): JSObject {
        checkNotRunning()
        return when (state) {
            GeneratorState.COMPLETED -> createIterResult(value, done = true)
            GeneratorState.SUSPENDED_START -> {
                // 尚未启动：规范要求直接完成生成器，不执行函数体
                state = GeneratorState.COMPLETED
                createIterResult(value, done = true)
            }

            else -> dispatch(GeneratorRequest.Return(value))
        }
    }

    /** `throw(value)`：在挂起点抛出异常；未捕获时以异常结束生成器。 */
    suspend fun throwValue(value: JSValue): JSObject {
        checkNotRunning()
        return when (state) {
            GeneratorState.COMPLETED -> throw JSException(value)
            GeneratorState.SUSPENDED_START -> {
                // 尚未启动：直接以该异常结束生成器
                state = GeneratorState.COMPLETED
                throw JSException(value)
            }

            else -> dispatch(GeneratorRequest.Throw(value))
        }
    }

    /** 重入（例如函数体内调用自身的 next）必须抛 TypeError。 */
    private fun checkNotRunning() {
        if (state == GeneratorState.EXECUTING) {
            interpreter.typeError("Generator is already running")
        }
    }

    /** 首次 next：启动函数体协程并等待第一个步骤。 */
    private suspend fun startBody(): JSObject {
        state = GeneratorState.EXECUTING
        // 函数体协程挂在事件循环作用域上（而不是当前 run），
        // 生成器因此可以跨多次脚本执行暂停与恢复。
        interpreter.launchGeneratorBody(this)
        return receiveStep()
    }

    /** 向已挂起的函数体投递恢复请求并等待下一个步骤。 */
    private suspend fun dispatch(request: GeneratorRequest): JSObject {
        state = GeneratorState.EXECUTING
        // 函数体在等待请求时缓冲区必为空；trySend 失败说明协议被破坏，避免永久挂起
        if (!channels.requests.trySend(request).isSuccess) {
            state = GeneratorState.COMPLETED
            throw IllegalStateException("Generator is not waiting for a resume request")
        }
        return receiveStep()
    }

    /** 等待函数体给出的步骤并更新生成器状态。 */
    private suspend fun receiveStep(): JSObject {
        val step = try {
            channels.steps.receive()
        } catch (error: Throwable) {
            // 协程被取消（宿主中止执行）时生成器不再可恢复
            state = GeneratorState.COMPLETED
            throw error
        }
        return when (step) {
            is GeneratorStep.Yielded -> {
                state = GeneratorState.SUSPENDED_YIELD
                createIterResult(step.value, done = false)
            }

            is GeneratorStep.Returned -> {
                state = GeneratorState.COMPLETED
                createIterResult(step.value, done = true)
            }

            is GeneratorStep.Threw -> {
                state = GeneratorState.COMPLETED
                throw step.exception
            }

            is GeneratorStep.LimitReached -> {
                // 配额耗尽不是 JS 错误：原样交给宿主，生成器就此结束
                state = GeneratorState.COMPLETED
                throw step.exception
            }
        }
    }

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
