package com.muedsa.js.conformance

import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.ExecutionLimitException
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * 真实 JS 库 UMD 冒烟运行器。
 *
 * 每个库在独立 Engine 中加载 UMD 构建，然后执行 API 级断言。
 */
fun main(args: Array<String>) {
    val libraryDir = File(args.getOrElse(0) { "corpus/libraries" })
    val reportDir = File(args.getOrElse(1) { "build/reports/conformance" })
    val strict = args.contains("--strict")
    // 用例超时/清理时限/步数配额，均可通过命令行覆盖
    val settings = RunnerSettings.fromArgs(args)
    // 真实库语料没有 .corpus-commit 标记，corpusCommit 会记为未知；路径写入执行配置
    val metadata = RunMetadata.collect(
        corpusDir = libraryDir,
        args = args,
        runnerConfig = mapOf(
            "libraries" to libraryDir.absolutePath,
            "caseTimeoutMs" to settings.caseTimeoutMillis.toString(),
            "cleanupTimeoutMs" to settings.cleanupTimeoutMillis.toString(),
            "maxSteps" to (settings.limits.maxSteps?.toString() ?: "unlimited"),
        ),
    )

    val report = runBlocking { LibrarySmokeRunner(libraryDir, settings, metadata).run() }
    reportDir.mkdirs()
    report.writeMarkdown(File(reportDir, "libraries-summary.md"))
    report.writeJson(File(reportDir, "libraries-summary.json"))
    report.writeFailures(File(reportDir, "libraries-failures.txt"))
    report.printSummary()

    if (report.crashed > 0) {
        exitProcess(2)
    }
    if (strict && (report.results.isEmpty() || report.skipped > 0)) {
        println("存在缺失库文件或库列表为空，严格模式判定失败")
        exitProcess(1)
    }
    if (strict && report.failed > 0) {
        exitProcess(1)
    }
}

private class LibrarySmokeRunner(
    private val libraryDir: File,
    private val settings: RunnerSettings = RunnerSettings(),
    private val metadata: RunMetadata,
) {

    /** 用例执行器：负责引擎创建、超时、分类与关闭。 */
    private val runtime = CaseRuntime(settings)

    suspend fun run(): LibraryReport {
        val startedAt = System.nanoTime()
        val results = mutableListOf<LibraryResult>()
        for (spec in librarySpecs) {
            val file = File(libraryDir, "${spec.name}.js")
            if (!file.isFile) {
                results.add(LibraryResult(spec.name, "skip", "缺少库文件", emptyList()))
                continue
            }
            val dependencyFiles = spec.dependencies.map { File(libraryDir, "$it.js") }
            val missingDependency = dependencyFiles.indexOfFirst { !it.isFile }
            if (missingDependency >= 0) {
                results.add(
                    LibraryResult(
                        spec.name,
                        "skip",
                        "缺少依赖库文件：${spec.dependencies[missingDependency]}",
                        emptyList(),
                    )
                )
                continue
            }
            val source = file.readText().removePrefix("\uFEFF")
            // 每个库独立引擎，并在 finally 中关闭：加载失败或超时都不会泄漏事件循环
            val run = runtime.withEngine { engine ->
                runLibrary(engine, spec, source, dependencyFiles)
            }
            val result = when (val outcome = run.outcome) {
                is CaseOutcome.Success -> outcome.value
                is CaseOutcome.Failure -> LibraryResult(
                    spec.name,
                    "crash",
                    "${outcome.kind.label}：${outcome.message}",
                    emptyList(),
                )
            }
            // 清理失败单独标记：即使库本身通过，未能释放引擎也是运行器缺陷
            results.add(
                run.cleanupFailure?.let { cleanup ->
                    LibraryResult(
                        spec.name,
                        "crash",
                        "清理失败：$cleanup；原状态=${result.status}",
                        emptyList(),
                    )
                } ?: result
            )
        }
        return LibraryReport(
            generatedAt = Instant.now().toString(),
            metadata = metadata,
            results = results,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    /** 在独立引擎中加载 UMD 构建并执行全部断言。 */
    private suspend fun runLibrary(
        engine: Engine,
        spec: LibrarySpec,
        source: String,
        dependencyFiles: List<File>,
    ): LibraryResult {
        try {
            // 依赖库（如 acorn）需要与目标库共享同一全局对象
            for (dependencyFile in dependencyFiles) {
                engine.run(dependencyFile.readText().removePrefix("\uFEFF"))
            }
            engine.run(source)
            val globalType = engine.run("typeof ${spec.globalName}") as? JSString
            if (globalType?.value == "undefined") {
                return LibraryResult(spec.name, "load-fail", "全局变量 ${spec.globalName} 未定义", emptyList())
            }
        } catch (error: JSException) {
            return LibraryResult(spec.name, "load-fail", "加载失败：$error", emptyList())
        } catch (error: ParseException) {
            return LibraryResult(spec.name, "load-fail", "解析失败：${error.message}", emptyList())
        } catch (error: LexerException) {
            return LibraryResult(spec.name, "load-fail", "词法失败：${error.message}", emptyList())
        }

        val caseResults = mutableListOf<CaseResult>()
        for (case in spec.cases) {
            caseResults.add(runCase(engine, case))
        }
        return LibraryResult(spec.name, "loaded", null, caseResults)
    }

    /** 执行单条断言；JS 层失败记 fail，资源/内部异常记 crash。 */
    private suspend fun runCase(engine: Engine, case: SmokeCase): CaseResult {
        return try {
            if (case.async) {
                // 异步用例：先清理结果变量，再执行脚本；Engine.run 会在结束时冲刷微任务
                engine.run("globalThis.__libResult = undefined;")
                engine.run(case.source)
                val result = engine.run("globalThis.__libResult")
                if (result is JSBoolean && result.value) {
                    CaseResult(case.name, "pass", null)
                } else {
                    CaseResult(case.name, "fail", "异步结果不为 true：$result")
                }
            } else {
                engine.run(case.source)
                CaseResult(case.name, "pass", null)
            }
        } catch (error: CancellationException) {
            // 宿主取消/引擎关闭不能被记成用例失败
            throw error
        } catch (error: ExecutionLimitException) {
            CaseResult(case.name, "crash", "${FailureKind.STEP_LIMIT.label}：${error.message}")
        } catch (error: JSException) {
            CaseResult(case.name, "fail", error.toString())
        } catch (error: ParseException) {
            CaseResult(case.name, "fail", "解析失败：${error.message}")
        } catch (error: LexerException) {
            CaseResult(case.name, "fail", "词法失败：${error.message}")
        } catch (error: Throwable) {
            CaseResult(case.name, "crash", error.reportLine())
        }
    }
}

private data class CaseResult(
    val name: String,
    val status: String,
    val message: String?,
)

private data class LibraryResult(
    val name: String,
    val status: String,
    val message: String?,
    val cases: List<CaseResult>,
)

private data class LibraryReport(
    val generatedAt: String,
    val metadata: RunMetadata,
    val results: List<LibraryResult>,
    val elapsedMillis: Long,
) {
    val passed: Int
        get() = results.sumOf { result -> result.cases.count { it.status == "pass" } }
    val failed: Int
        get() = results.sumOf { result ->
            result.cases.count { it.status == "fail" || it.status == "crash" } +
                if (result.status == "load-fail" || result.status == "crash") 1 else 0
        }
    val crashed: Int
        get() = results.sumOf { result ->
            result.cases.count { it.status == "crash" } + if (result.status == "crash") 1 else 0
        }
    val skipped: Int
        get() = results.count { it.status == "skip" }

    fun writeMarkdown(file: File) {
        file.writeText(
            buildString {
                appendLine("# 真实 JS 库 UMD 冒烟")
                appendLine()
                appendLine("- 生成时间：$generatedAt")
                appendLine("- 引擎 commit：`${metadata.engineCommit}`")
                appendLine("- 工具链：Kotlin ${metadata.kotlinVersion} / Java ${metadata.javaVersion} / " +
                    "Gradle ${metadata.gradleVersion ?: "未知"} / ${metadata.os}")
                appendLine("- 通过：$passed，失败：$failed，崩溃：$crashed，跳过：$skipped，耗时：${elapsedMillis}ms")
                appendLine()
                appendLine("| 库 | 状态 | 用例 | 详情 |")
                appendLine("|---|---|---|---|")
                for (result in results) {
                    val detail = result.message ?: result.cases.joinToString(
                        separator = "; ",
                        transform = { case ->
                            if (case.status == "pass") "${case.name}=ok"
                            else "${case.name}=${case.message}"
                        },
                    )
                    appendLine("| ${result.name} | ${result.status} | ${result.cases.size} | ${detail.replace("|", "\\|")} |")
                }
            }
        )
    }

    fun writeJson(file: File) {
        file.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"generatedAt\": \"${jsonEscape(generatedAt)}\",")
                appendLine("  \"metadata\": {")
                appendLine("    \"engineCommit\": \"${jsonEscape(metadata.engineCommit)}\",")
                appendLine("    \"kotlinVersion\": \"${jsonEscape(metadata.kotlinVersion)}\",")
                appendLine("    \"javaVersion\": \"${jsonEscape(metadata.javaVersion)}\",")
                appendLine("    \"gradleVersion\": ${metadata.gradleVersion?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
                appendLine("    \"os\": \"${jsonEscape(metadata.os)}\"")
                appendLine("  },")
                appendLine("  \"passed\": $passed,")
                appendLine("  \"failed\": $failed,")
                appendLine("  \"crashed\": $crashed,")
                appendLine("  \"skipped\": $skipped,")
                appendLine("  \"elapsedMillis\": $elapsedMillis,")
                appendLine("  \"libraries\": [")
                results.forEachIndexed { index, result ->
                    appendLine("    {")
                    appendLine("      \"name\": \"${jsonEscape(result.name)}\",")
                    appendLine("      \"status\": \"${result.status}\",")
                    appendLine("      \"message\": ${result.message?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
                    appendLine("      \"cases\": [")
                    result.cases.forEachIndexed { caseIndex, case ->
                        append(
                            "        {\"name\": \"${jsonEscape(case.name)}\", " +
                                "\"status\": \"${case.status}\", " +
                                "\"message\": ${case.message?.let { "\"${jsonEscape(it)}\"" } ?: "null"}}"
                        )
                        appendLine(if (caseIndex < result.cases.lastIndex) "," else "")
                    }
                    appendLine("      ]")
                    append("    }")
                    appendLine(if (index < results.lastIndex) "," else "")
                }
                appendLine("  ]")
                appendLine("}")
            }
        )
    }

    fun writeFailures(file: File) {
        file.writeText(
            buildString {
                fun oneLine(text: String?): String =
                    (text ?: "").replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim()
                for (result in results) {
                    if (result.status != "loaded") {
                        appendLine("${result.name}\t${result.status}\t${oneLine(result.message)}")
                    }
                    for (case in result.cases) {
                        if (case.status != "pass") {
                            appendLine("${result.name}\t${case.name}\t${case.status}\t${oneLine(case.message)}")
                        }
                    }
                }
            }
        )
    }

    fun printSummary() {
        println("真实 JS 库 UMD 冒烟")
        for (result in results) {
            println("  ${result.name}: ${result.status} (${result.cases.count { it.status == "pass" }}/${result.cases.size})")
        }
        println("  通过 $passed，失败 $failed，崩溃 $crashed，跳过 $skipped，耗时 ${elapsedMillis}ms")
    }
}

private fun jsonEscape(text: String): String {
    val builder = StringBuilder(text.length + 8)
    for (character in text) {
        when (character) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> if (character < ' ') {
                builder.append("\\u")
                builder.append(character.code.toString(16).padStart(4, '0'))
            } else {
                builder.append(character)
            }
        }
    }
    return builder.toString()
}