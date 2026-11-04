package com.muedsa.js.conformance

import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.ExecutionLimitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一致性运行器的公共执行设施。
 *
 * 目标是把"用例执行"和"运行器自身故障"分开：
 * - 用例内部的 JS 断言失败、解析失败由各运行器自己判定；
 * - 步数耗尽、用例超时、引擎清理失败、Node 进程超时由本文件统一分类，
 *   这样报告里能直接看出"是引擎缺陷还是运行器失控"。
 *
 * 注意：超时是**协作式**的。解释器在检查点观察取消，因此同步死循环会在
 * 下一个检查点中止；但单次内建调用（正则回溯、超大字符串分配、解析）内部
 * 没有检查点，超时只能等到该调用返回后才生效。需要硬时限时必须把用例放进
 * 独立进程，那属于单独的隔离任务（见路线文档 T1 的可选项）。
 */

/** 用例失败的分类，用于报告聚类与定位。 */
internal enum class FailureKind(val label: String) {
    /** JS 断言失败或负数用例抛出类型不符。 */
    ASSERTION("断言失败"),

    /** 源码在解析/词法阶段失败。 */
    PARSE("解析失败"),

    /** 执行步数预算耗尽（仅在显式配置 maxSteps 时出现）。 */
    STEP_LIMIT("步数耗尽"),

    /** 用例超过协作式超时时间未结束。 */
    TIMEOUT("用例超时"),

    /** 运行器内部异常（例如栈溢出、未预期异常）。 */
    INTERNAL("运行器内部异常"),

    /** 引擎关闭失败或超过清理时限。 */
    CLEANUP("引擎清理失败"),

    /** Node 子进程超时。 */
    NODE_TIMEOUT("Node 超时"),
}

/** 单个用例的执行结果：成功携带运行器自己的结果对象，失败携带分类。 */
internal sealed class CaseOutcome<out T> {
    data class Success<T>(val value: T) : CaseOutcome<T>()
    data class Failure(val kind: FailureKind, val message: String) : CaseOutcome<Nothing>()
}

/**
 * 一次引擎内执行的结果。
 *
 * [cleanupFailure] 单独保存而不是覆盖 [outcome]：清理失败说明运行器没能在时限内
 * 释放引擎，调用方可以选择把它升级为失败，同时保留原始结果用于排查。
 */
internal data class CaseRun<out T>(
    val outcome: CaseOutcome<T>,
    val cleanupFailure: String? = null,
)

/**
 * 运行器配置。
 *
 * @property caseTimeoutMillis 单次用例（含微任务冲刷）的协作式超时
 * @property cleanupTimeoutMillis 引擎关闭的等待上限
 * @property nodeTimeoutMillis Node 子进程的等待上限
 * @property limits 注入每个 Engine 的执行配额；默认不限制步数
 */
internal data class RunnerSettings(
    val caseTimeoutMillis: Long = DEFAULT_CASE_TIMEOUT_MILLIS,
    val cleanupTimeoutMillis: Long = DEFAULT_CLEANUP_TIMEOUT_MILLIS,
    val nodeTimeoutMillis: Long = DEFAULT_NODE_TIMEOUT_MILLIS,
    val limits: EngineLimits = EngineLimits(),
) {
    init {
        require(caseTimeoutMillis > 0) { "caseTimeoutMillis must be > 0" }
        require(cleanupTimeoutMillis > 0) { "cleanupTimeoutMillis must be > 0" }
        require(nodeTimeoutMillis > 0) { "nodeTimeoutMillis must be > 0" }
    }

    companion object {
        /**
         * 默认用例超时。
         *
         * 已知最慢用例是 decodeURI/decodeURIComponent 的四层嵌套循环（约 130 万次迭代），
         * 并行负载下实测 18–26 秒；60 秒留出约两倍余量，同时能把真正失控的用例限制住。
         */
        const val DEFAULT_CASE_TIMEOUT_MILLIS = 60_000L

        /** 引擎关闭的默认等待上限；正常引擎没有在途任务时会立即返回。 */
        const val DEFAULT_CLEANUP_TIMEOUT_MILLIS = 5_000L

        /** Node 子进程的默认等待上限；差分脚本全量约 10 秒。 */
        const val DEFAULT_NODE_TIMEOUT_MILLIS = 120_000L

        /**
         * 从命令行参数解析配置。
         *
         * 支持 `--case-timeout-ms`、`--cleanup-timeout-ms`、`--node-timeout-ms`
         * 与 `--max-steps`；缺省时使用默认值，非法数字按缺省处理。
         */
        fun fromArgs(args: Array<String>): RunnerSettings {
            return RunnerSettings(
                caseTimeoutMillis = args.longOption("--case-timeout-ms")
                    ?: DEFAULT_CASE_TIMEOUT_MILLIS,
                cleanupTimeoutMillis = args.longOption("--cleanup-timeout-ms")
                    ?: DEFAULT_CLEANUP_TIMEOUT_MILLIS,
                nodeTimeoutMillis = args.longOption("--node-timeout-ms")
                    ?: DEFAULT_NODE_TIMEOUT_MILLIS,
                limits = EngineLimits(maxSteps = args.longOption("--max-steps")),
            )
        }
    }
}

/** 读取 `--name value` 形式的长整型参数；不存在或无法解析时返回 null。 */
private fun Array<String>.longOption(name: String): Long? {
    val index = indexOf(name)
    if (index < 0) return null
    return getOrNull(index + 1)?.toLongOrNull()
}

/**
 * 用例执行器：负责引擎的创建、超时、分类与关闭。
 *
 * 同一实例可被多个协程并发使用（不持有可变状态），每个用例独立创建引擎。
 */
internal class CaseRuntime(private val settings: RunnerSettings) {

    /**
     * 在独立引擎中执行 [block]，并在结束时无条件关闭引擎。
     *
     * 宿主取消（父协程被取消）会原样向上传播，不会被当成用例失败；
     * 引擎关闭放在 `NonCancellable` + 独立时限中，避免取消导致引擎泄漏。
     */
    suspend fun <T : Any> withEngine(block: suspend (Engine) -> T): CaseRun<T> {
        val engine = Engine(limits = settings.limits)
        var cleanupFailure: String? = null
        val outcome = try {
            runUnderTimeout { block(engine) }
        } finally {
            // finally 中不能用会被取消的上下文，否则超时用例的引擎永远不会被关闭。
            cleanupFailure = closeEngine(engine)
        }
        return CaseRun(outcome, cleanupFailure)
    }

    /**
     * 在已有引擎上执行 [block]，只做超时与异常分类，不负责创建/关闭引擎。
     *
     * 供已经持有引擎、但希望统一分类语义的调用方使用。
     */
    suspend fun <T : Any> runUnderTimeout(block: suspend () -> T): CaseOutcome<T> {
        val timed = withTimeoutOrNull(settings.caseTimeoutMillis) {
            try {
                CaseOutcome.Success(block())
            } catch (error: CancellationException) {
                // 超时与宿主取消都走这里：继续抛出，由 withTimeoutOrNull 或调用方处理。
                throw error
            } catch (error: Throwable) {
                classify(error)
            }
        }
        return timed ?: CaseOutcome.Failure(
            FailureKind.TIMEOUT,
            "超过 ${settings.caseTimeoutMillis}ms 未结束",
        )
    }

    /**
     * 关闭引擎并返回清理失败描述。
     *
     * 返回 null 表示正常关闭；非 null 表示超时或关闭抛错，调用方应升级为失败。
     * 供"共享引擎"的调用方在批次结束时统一关闭。
     */
    suspend fun closeEngine(engine: Engine): String? {
        return try {
            val closed = withContext(NonCancellable) {
                withTimeoutOrNull(settings.cleanupTimeoutMillis) {
                    engine.close()
                    true
                }
            }
            if (closed == null) {
                "引擎关闭超过 ${settings.cleanupTimeoutMillis}ms 未完成"
            } else {
                null
            }
        } catch (error: Throwable) {
            "引擎关闭失败：${error.reportLine()}"
        }
    }

    /** 把执行期异常映射为分类；JS 层错误由各运行器自行判定，不应走到这里。 */
    private fun classify(error: Throwable): CaseOutcome<Nothing> = when (error) {
        is ExecutionLimitException -> CaseOutcome.Failure(
            FailureKind.STEP_LIMIT,
            error.message ?: "执行步数耗尽",
        )

        is ParseException -> CaseOutcome.Failure(FailureKind.PARSE, error.message ?: "解析失败")
        is LexerException -> CaseOutcome.Failure(FailureKind.PARSE, error.message ?: "词法失败")
        else -> CaseOutcome.Failure(FailureKind.INTERNAL, error.reportLine())
    }
}

/** 单行异常摘要：类型名 + 首行消息，避免报告被多行栈撑爆。 */
internal fun Throwable.reportLine(): String {
    val firstLine = message?.lineSequence()?.firstOrNull()?.trim()
    val type = this::class.simpleName ?: "Throwable"
    return if (firstLine.isNullOrEmpty()) type else "$type: $firstLine"
}

/** Node 子进程超时：与普通失败区分，报告里需要单独计数。 */
internal class NodeTimeoutException(message: String) : RuntimeException(message)

/**
 * 运行一次 Node 进程并返回合并后的输出。
 *
 * 输出重定向到临时文件而不是管道：先 `readText()` 再 `waitFor()` 在输出超过
 * 管道缓冲区时会死锁，重定向后只需等待进程结束。超时则强制终止并抛
 * [NodeTimeoutException]，临时文件在 finally 中删除。
 */
internal fun runNodeProcess(
    arguments: List<String>,
    timeoutMillis: Long,
    workingDirectory: File? = null,
): String {
    val outputFile = File.createTempFile("vibe-node-", ".log")
    try {
        val builder = ProcessBuilder(listOf("node") + arguments)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
        if (workingDirectory != null) builder.directory(workingDirectory)
        val process = builder.start()
        val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            // 终止后短暂等待，避免僵尸进程；失败不影响超时结论。
            process.waitFor(2, TimeUnit.SECONDS)
            throw NodeTimeoutException("超过 ${timeoutMillis}ms 未结束，已强制终止")
        }
        val output = outputFile.readText()
        val exitCode = process.exitValue()
        check(exitCode == 0) { "node exited with $exitCode: $output" }
        return output
    } finally {
        outputFile.delete()
    }
}
