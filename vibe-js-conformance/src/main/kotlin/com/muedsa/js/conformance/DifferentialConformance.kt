package com.muedsa.js.conformance

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.intellij.lang.annotations.Language
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * 以 Node 为参照的差分测试：同一表达式在 Vibe JS 与 Node 中序列化后比较。
 */
fun main(args: Array<String>) {
    val reportDir = File(args.getOrElse(0) { "build/reports/conformance" })
    val strict = args.contains("--strict")
    val settings = RunnerSettings.fromArgs(args)
    // 差分不依赖语料目录，元数据里记录工具链、配置与 Node 版本
    val metadata = RunMetadata.collect(
        corpusDir = null,
        args = args,
        runnerConfig = mapOf(
            "caseTimeoutMs" to settings.caseTimeoutMillis.toString(),
            "cleanupTimeoutMs" to settings.cleanupTimeoutMillis.toString(),
            "nodeTimeoutMs" to settings.nodeTimeoutMillis.toString(),
            "maxSteps" to (settings.limits.maxSteps?.toString() ?: "unlimited"),
        ),
    )

    val report = runBlocking { DifferentialRunner(settings, metadata).run() }
    reportDir.mkdirs()
    report.writeMarkdown(File(reportDir, "differential-summary.md"))
    report.writeJson(File(reportDir, "differential-summary.json"))
    report.writeMismatches(File(reportDir, "differential-mismatches.txt"))
    report.printSummary()

    if (report.cleanupErrors.isNotEmpty()) {
        // 引擎没能在时限内释放属于运行器缺陷，与用例不一致分开报告
        println("存在引擎清理失败，判定为运行器缺陷")
        exitProcess(2)
    }
    if (report.nodeUnavailable) {
        println("未找到 Node，差分测试已跳过")
        if (strict) exitProcess(1)
        return
    }
    if (strict && report.results.isEmpty()) {
        println("差分用例为空，严格模式判定失败")
        exitProcess(1)
    }
    if (strict && report.mismatches > 0) {
        exitProcess(1)
    }
}

/** GitHub 步骤摘要中单个结果单元格的最大字符数，防止异常大的序列化结果撑爆摘要。 */
private const val MARKDOWN_CELL_LIMIT = 160

/** GitHub 步骤摘要中最多展示的不一致用例数，完整明细保留在报告产物中。 */
private const val MAX_MARKDOWN_MISMATCHES = 50

/**
 * 把结果值压缩为适合 Markdown 表格的单行单元格。
 *
 * 先裁剪再转义竖线，避免裁剪位置落在转义序列中间；
 * 换行替换为空格，保证每条用例只占表格一行。
 */
private fun markdownCell(value: String): String {
    val singleLine = value.replace('\r', ' ').replace('\n', ' ')
    val truncated = if (singleLine.length > MARKDOWN_CELL_LIMIT) {
        singleLine.take(MARKDOWN_CELL_LIMIT) + "…"
    } else {
        singleLine
    }
    return truncated.replace("|", "\\|")
}

internal data class DifferentialCase(
    val name: String,
    @Language("JavaScript") val expression: String,
)

private data class DifferentialResult(
    val name: String,
    val expression: String,
    val vibeValue: String,
    val nodeValue: String,
) {
    val matches: Boolean get() = vibeValue == nodeValue
}

private data class DifferentialReport(
    val generatedAt: String,
    val metadata: RunMetadata,
    val nodeVersion: String,
    val nodeUnavailable: Boolean,
    /** Node 不可用或超时的具体原因；正常时为 null。 */
    val nodeError: String? = null,
    /** 引擎清理失败记录；非空表示运行器未能按时释放引擎。 */
    val cleanupErrors: List<String> = emptyList(),
    val results: List<DifferentialResult>,
    val elapsedMillis: Long,
) {
    val mismatches: Int get() = results.count { !it.matches }

    fun writeMarkdown(file: File) {
        val mismatched = results.filter { !it.matches }
        file.writeText(
            buildString {
                appendLine("# Node 差分基线")
                appendLine()
                appendLine("- 生成时间：$generatedAt")
                appendLine("- 引擎 commit：`${metadata.engineCommit}`")
                appendLine("- 工具链：Kotlin ${metadata.kotlinVersion} / Java ${metadata.javaVersion} / " +
                    "Gradle ${metadata.gradleVersion ?: "未知"} / ${metadata.os}")
                appendLine("- 执行配置：${metadata.runnerConfig.entries.joinToString { "${it.key}=${it.value}" }}")
                appendLine("- Node：${if (nodeUnavailable) "不可用" else nodeVersion}")
                if (nodeError != null) {
                    appendLine("- Node 故障：${nodeError.replace('\n', ' ')}")
                }
                if (cleanupErrors.isNotEmpty()) {
                    appendLine("- 引擎清理失败：${cleanupErrors.size} 条")
                    for (error in cleanupErrors.take(20)) {
                        appendLine("  - ${error.replace('\n', ' ')}")
                    }
                }
                appendLine("- 用例：${results.size}，不一致：$mismatches，耗时：${elapsedMillis}ms")
                appendLine()
                when {
                    nodeUnavailable -> appendLine("Node 不可用，未执行差分。")
                    results.isEmpty() -> appendLine("差分用例为空。")
                    mismatched.isEmpty() -> appendLine("全部用例与 Node 结果一致。")
                    else -> {
                        // 步骤摘要只展示不一致项并限制长度，避免异常大的结果把摘要撑爆；
                        // 完整逐用例结果仍写入 differential-summary.json。
                        appendLine("| 用例 | Vibe JS | Node |")
                        appendLine("|---|---|---|")
                        for (result in mismatched.take(MAX_MARKDOWN_MISMATCHES)) {
                            appendLine(
                                "| ${markdownCell(result.name)} | ${markdownCell(result.vibeValue)} | " +
                                    "${markdownCell(result.nodeValue)} |"
                            )
                        }
                        if (mismatched.size > MAX_MARKDOWN_MISMATCHES) {
                            appendLine()
                            appendLine(
                                "仅展示前 $MAX_MARKDOWN_MISMATCHES 条，" +
                                    "其余见 differential-mismatches.txt。"
                            )
                        }
                    }
                }
                if (results.isNotEmpty()) {
                    appendLine()
                    appendLine("完整逐用例结果见 differential-summary.json。")
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
                appendLine("    \"os\": \"${jsonEscape(metadata.os)}\",")
                appendLine("    \"runnerConfig\": {")
                val configs = metadata.runnerConfig.entries.toList()
                configs.forEachIndexed { index, entry ->
                    append("      \"${jsonEscape(entry.key)}\": \"${jsonEscape(entry.value)}\"")
                    appendLine(if (index < configs.lastIndex) "," else "")
                }
                appendLine("    }")
                appendLine("  },")
                appendLine("  \"nodeVersion\": \"${jsonEscape(nodeVersion)}\",")
                appendLine("  \"nodeUnavailable\": $nodeUnavailable,")
                appendLine("  \"nodeError\": ${nodeError?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
                appendLine("  \"cleanupErrors\": [")
                cleanupErrors.forEachIndexed { index, error ->
                    append("    \"${jsonEscape(error)}\"")
                    appendLine(if (index < cleanupErrors.lastIndex) "," else "")
                }
                appendLine("  ],")
                appendLine("  \"mismatches\": $mismatches,")
                appendLine("  \"elapsedMillis\": $elapsedMillis,")
                appendLine("  \"results\": [")
                results.forEachIndexed { index, result ->
                    append(
                        "    {\"name\": \"${jsonEscape(result.name)}\", " +
                            "\"expression\": \"${jsonEscape(result.expression)}\", " +
                            "\"vibe\": \"${jsonEscape(result.vibeValue)}\", " +
                            "\"node\": \"${jsonEscape(result.nodeValue)}\"}"
                    )
                    appendLine(if (index < results.lastIndex) "," else "")
                }
                appendLine("  ]")
                appendLine("}")
            }
        )
    }

    fun writeMismatches(file: File) {
        val mismatches = results.filter { !it.matches }
        // 即使零不一致也覆盖旧文件，避免本地报告残留
        file.writeText(
            buildString {
                for (result in mismatches) {
                    appendLine("${result.name}\t${result.expression}\tVIBE=${result.vibeValue}\tNODE=${result.nodeValue}")
                }
            }
        )
    }

    fun printSummary() {
        println("Node 差分基线")
        println("  Node: ${if (nodeUnavailable) "不可用" else nodeVersion}")
        if (nodeError != null) {
            println("  Node 故障: $nodeError")
        }
        if (cleanupErrors.isNotEmpty()) {
            println("  引擎清理失败: ${cleanupErrors.size} 条")
        }
        println("  用例 ${results.size}，不一致 $mismatches，耗时 ${elapsedMillis}ms")
        for (result in results.filter { !it.matches }.take(10)) {
            println("  ✗ ${result.name}: vibe=${result.vibeValue}, node=${result.nodeValue}")
        }
    }
}

private class DifferentialRunner(
    private val settings: RunnerSettings,
    private val metadata: RunMetadata,
) {

    /** 用例执行器：负责引擎创建、超时、分类与关闭。 */
    private val runtime = CaseRuntime(settings)

    suspend fun run(): DifferentialReport {
        val startedAt = System.nanoTime()
        // S2 的 L 系列用例单独成文件，再与既有语料合并
        val cases = differentialCases + extraDifferentialCases + lSeriesDifferentialCases
        val asyncCases = asyncDifferentialCases + lSeriesAsyncDifferentialCases
        // 名称重复会导致 Node 结果按名称合并后错配，直接在运行前拒绝
        val duplicateNames = (cases.map { it.name } + asyncCases.map { it.name })
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicateNames.isEmpty()) {
            "duplicate differential case names: ${duplicateNames.joinToString()}"
        }
        val vibeResults = mutableListOf<Triple<String, String, String>>()
        val cleanupErrors = mutableListOf<String>()
        // 同步用例共享同一引擎：部分用例刻意依赖前一个用例留下的全局状态
        //（例如 microtask-state-set 设置、microtask-state-read 读取并删除），
        // 因此不能按用例重建。超时只取消当前 run，引擎随后仍可复用；
        // 若超时后引擎可能残留任务，则重建引擎避免后续用例被阻塞。
        var engine = createEngine()
        try {
            for (case in cases) {
                when (val outcome = runtime.runUnderTimeout { evaluateSync(engine, case.expression) }) {
                    is CaseOutcome.Success ->
                        vibeResults.add(Triple(case.name, case.expression, outcome.value))

                    is CaseOutcome.Failure -> {
                        vibeResults.add(
                            Triple(case.name, case.expression, "${outcome.kind.name}:${outcome.message}")
                        )
                        if (outcome.kind == FailureKind.TIMEOUT) {
                            runtime.closeEngine(engine)?.let { cleanupErrors.add("${case.name}：$it") }
                            engine = createEngine()
                        }
                    }
                }
            }
        } finally {
            runtime.closeEngine(engine)?.let { cleanupErrors.add("共享引擎：$it") }
        }
        for (case in asyncCases) {
            val display = case.source.replace('\n', ' ').replace('\t', ' ')
            vibeResults.add(Triple(case.name, display, evaluateAsyncInVibe(case, cleanupErrors)))
        }

        var nodeError: String? = null
        val node = try {
            val version = runNodeProcess(listOf("--version"), settings.nodeTimeoutMillis).trim()
            val script = File.createTempFile("vibe-diff-", ".js")
            try {
                script.writeText(buildNodeScript(cases, asyncCases))
                val output = runNodeProcess(listOf(script.absolutePath), settings.nodeTimeoutMillis)
                version to output.lineSequence()
                    .filter { it.isNotBlank() }
                    .associate { line ->
                        val tab = line.indexOf('\t')
                        if (tab < 0) line to "" else line.substring(0, tab) to line.substring(tab + 1)
                    }
            } finally {
                // 成功、失败与超时都要删除临时脚本，避免异常路径泄漏
                script.delete()
            }
        } catch (error: CancellationException) {
            // 宿主取消不是 Node 故障，原样传播
            throw error
        } catch (error: NodeTimeoutException) {
            nodeError = "${FailureKind.NODE_TIMEOUT.label}：${error.message}"
            null
        } catch (error: Throwable) {
            nodeError = error.reportLine()
            null
        }

        if (node == null) {
            return DifferentialReport(
                generatedAt = Instant.now().toString(),
                metadata = metadata,
                nodeVersion = "",
                nodeUnavailable = true,
                nodeError = nodeError,
                cleanupErrors = cleanupErrors,
                results = emptyList(),
                elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
            )
        }

        val (version, nodeValues) = node
        val results = vibeResults.map { (name, expression, vibe) ->
            DifferentialResult(
                name = name,
                expression = expression,
                vibeValue = vibe,
                nodeValue = nodeValues[name] ?: "<missing>",
            )
        }
        return DifferentialReport(
            generatedAt = Instant.now().toString(),
            metadata = metadata,
            nodeVersion = version,
            nodeUnavailable = false,
            cleanupErrors = cleanupErrors,
            results = results,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    /**
     * 创建差分引擎并安装运行器宿主钩子。
     *
     * `__detach` 与 Node 侧注入的同名函数一致，用于覆盖 ArrayBuffer 分离语义；
     * 核心内建不包含该函数。
     */
    private fun createEngine(): Engine =
        Engine(limits = settings.limits).also { it.install(DifferentialDetachModule) }

    /**
     * 同步用例：在共享引擎中求值并序列化。
     *
     * JS 抛错保留原有的 `THROW:` 前缀，以便与 Node 侧结果直接比较。
     */
    private suspend fun evaluateSync(engine: Engine, expression: String): String {
        return try {
            when (val value = engine.run("JSON.stringify(($expression))")) {
                is JSUndefined -> "undefined"
                is JSString -> value.value
                else -> value.toString()
            }
        } catch (error: JSException) {
            "THROW:${thrownName(error.value)}"
        }
    }

    /**
     * 异步用例：脚本执行（含微任务冲刷）后读取 `globalThis.__diffResult`。
     * 每个用例使用独立 Engine，避免全局状态相互影响；清理失败记入 [cleanupErrors]。
     */
    private suspend fun evaluateAsyncInVibe(
        case: AsyncDifferentialCase,
        cleanupErrors: MutableList<String>,
    ): String {
        val run = runtime.withEngine { engine ->
            // 异步用例同样需要 detach 钩子，保证与 Node 每个 vm 上下文一致
            engine.install(DifferentialDetachModule)
            try {
                engine.run(case.source)
                when (val value = engine.run("JSON.stringify(globalThis.__diffResult)")) {
                    is JSUndefined -> "undefined"
                    is JSString -> value.value
                    else -> value.toString()
                }
            } catch (error: JSException) {
                "THROW:${thrownName(error.value)}"
            }
        }
        run.cleanupFailure?.let { cleanupErrors.add("${case.name}：$it") }
        return when (val outcome = run.outcome) {
            is CaseOutcome.Success -> outcome.value
            is CaseOutcome.Failure -> "${outcome.kind.name}:${outcome.message}"
        }
    }

    private fun thrownName(value: com.muedsa.js.runtime.value.JSValue): String {
        if (value is JSError) return value.errorName
        if (value is JSObject) {
            return (value.getOwnDataValue(com.muedsa.js.runtime.value.PropertyKey.Str("name")) as? JSString)?.value
                ?: "Error"
        }
        return "Error"
    }

    private fun buildNodeScript(
        cases: List<DifferentialCase>,
        asyncCases: List<AsyncDifferentialCase>,
    ): String {
        val builder = StringBuilder()
        builder.appendLine("const vm = require('vm');")
        // 与 Vibe 侧 __detach 宿主钩子对齐：transfer 会分离原缓冲区
        builder.appendLine("const detachBuffer = (buffer) => { structuredClone(buffer, { transfer: [buffer] }); };")
        builder.appendLine("const cases = [")
        for (case in cases) {
            builder.append("  [")
            builder.append(kotlinStringLiteral(case.name))
            builder.append(", ")
            builder.append(kotlinStringLiteral(case.expression))
            builder.appendLine("],")
        }
        builder.appendLine("];")
        builder.appendLine(
            """
            const syncContext = vm.createContext({ __detach: detachBuffer }, { microtaskMode: 'afterEvaluate' });
            for (const [name, expression] of cases) {
              try {
                // 与 Vibe 一致：在裸上下文执行 JSON.stringify((expression))
                const json = vm.runInContext('JSON.stringify((' + expression + '))', syncContext);
                process.stdout.write(name + '\t' + (json === undefined ? 'undefined' : json) + '\n');
              } catch (error) {
                const errorName = error && error.name ? error.name : 'Error';
                process.stdout.write(name + '\tTHROW:' + errorName + '\n');
              }
            }
            """.trimIndent()
        )
        if (asyncCases.isNotEmpty()) {
            builder.appendLine("const asyncCases = [")
            for (case in asyncCases) {
                builder.append("  [")
                builder.append(kotlinStringLiteral(case.name))
                builder.append(", ")
                builder.append(kotlinStringLiteral(case.source))
                builder.appendLine("],")
            }
            builder.appendLine("];")
            builder.appendLine(
                """
                (async () => {
                  for (const [name, source] of asyncCases) {
                    // 每个异步用例使用独立 vm 上下文，与 Vibe 每例新建 Engine 对齐
                    const context = vm.createContext({ __detach: detachBuffer });
                    try {
                      vm.runInContext(source, context);
                      // 冲刷所有已入队的微任务（宏任务边界）
                      await new Promise((resolve) => setImmediate(resolve));
                      const json = JSON.stringify(context.__diffResult);
                      process.stdout.write(name + '\t' + (json === undefined ? 'undefined' : json) + '\n');
                    } catch (error) {
                      const errorName = error && error.name ? error.name : 'Error';
                      process.stdout.write(name + '\tTHROW:' + errorName + '\n');
                    }
                  }
                })();
                """.trimIndent()
            )
        }
        return builder.toString()
    }

    private fun kotlinStringLiteral(text: String): String = jsonString(text)

    private fun jsonString(text: String): String {
        val builder = StringBuilder("\"")
        for (character in text) {
            when (character) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> builder.append(character)
            }
        }
        builder.append('"')
        return builder.toString()
    }
}

private val differentialCases = listOf(
    DifferentialCase("float-add", "0.1 + 0.2"),
    DifferentialCase("large-number", "1e21"),
    DifferentialCase("small-number", "1e-7"),
    DifferentialCase("negative-zero", "-0"),
    DifferentialCase("negative-zero-string", "String(-0)"),
    DifferentialCase("radix-2", "(255).toString(2)"),
    DifferentialCase("radix-16", "(255).toString(16)"),
    DifferentialCase("object-key-order", "JSON.stringify({ b: 1, 2: 2, 1: 3, a: 4 })"),
    DifferentialCase("string-escape", "JSON.stringify('a\"b\\nc')"),
    DifferentialCase("control-char-escape", "JSON.stringify('\\u0001')"),
    DifferentialCase("emoji-length", "String.fromCodePoint(128512).length"),
    DifferentialCase("emoji-codepoint", "String.fromCodePoint(128512).codePointAt(0)"),
    DifferentialCase("split-capture", "'a1b2'.split(/(\\d)/).join('|')"),
    DifferentialCase("split-end-anchor", "'ab'.split(/$/).join(',')"),
    DifferentialCase("array-hole-includes", "[1, , 3].includes(undefined)"),
    DifferentialCase("array-keys", "Object.keys([1, 2]).join(',')"),
    DifferentialCase("json-array-undefined", "JSON.stringify([1, undefined, function () {}])"),
    DifferentialCase("date-iso", "new Date(0).toISOString()"),
    DifferentialCase("date-utc-overflow", "Date.UTC(2020, 12, 1)"),
    DifferentialCase("equality", "(null == 0) + ':' + ([] == false)"),
    DifferentialCase("math-extremes", "String(Math.max()) + ':' + String(Math.min())"),
    DifferentialCase("parse-int", "parseInt('08') + ':' + parseInt('0x10')"),
    DifferentialCase("replace-capture", "'abc'.replace(/(b)/, '[$1]')"),
    DifferentialCase("replace-anchor", "'a1b'.replace(/\\d/, '[$`]')"),
    DifferentialCase("typeof-symbol", "typeof Symbol('x')"),
    DifferentialCase("object-tag", "Object.prototype.toString.call(new Map())"),
    // Number 精确十进制展开：覆盖常规值、次正规数、极值与舍入进位
    DifferentialCase(
        "num-toexponential-wide",
        "(function () { var values = [123.456, 0.0001, 0.9999, 25, 12345, 1e21, 1e-21, 5e-324, " +
            "1.7976931348623157e308, 0.1, 2.675]; " +
            "var digits = [0, 1, 2, 3, 4, 16, 17, 18, 20, 100]; var out = []; " +
            "for (var i = 0; i < values.length; i++) { for (var j = 0; j < digits.length; j++) { " +
            "out.push(values[i].toExponential(digits[j])); } } return out.join('|'); })()"
    ),
    DifferentialCase(
        "num-toprecision-wide",
        "(function () { var values = [123.456, 0.0001, 0.9999, 1.2345e27, 1e-21, 1e21, 0.000001, " +
            "5e-324, 0.1]; var out = []; " +
            "for (var i = 0; i < values.length; i++) { " +
            "for (var p = 1; p <= 21; p++) { out.push(values[i].toPrecision(p)); } } " +
            "return out.join('|'); })()"
    ),
    DifferentialCase(
        "num-tofixed-wide",
        "(function () { var values = [1.005, 2.675, 123.456, 0.0001, 1e20, 1e21, 5e-324, " +
            "-0.0001, 0.9999, 1.7976931348623157e308]; " +
            "var digits = [0, 1, 2, 3, 5, 10, 20, 50, 100]; var out = []; " +
            "for (var i = 0; i < values.length; i++) { for (var j = 0; j < digits.length; j++) { " +
            "out.push(values[i].toFixed(digits[j])); } } return out.join('|'); })()"
    ),
    // 三种格式化方法 × 多组取值/位数的矩阵，覆盖往返与舍入边界
    DifferentialCase(
        "num-format-matrix",
        "(function () { var values = [0.1, 0.2, 0.3, 1 / 3, 2 / 3, Math.PI, 1e-7, 1e-6, 1e21, 1e22, " +
            "1.7976931348623157e308, 5e-324, 9007199254740992, 9007199254740993, 1.25, 2.675, " +
            "0.9999999999999999, 123456789.123456789, -1.5, -0.0001]; " +
            "var expDigits = [0, 1, 2, 5, 15, 16, 17, 20, 50, 100]; " +
            "var precDigits = [1, 2, 3, 4, 5, 10, 15, 16, 17, 20, 21, 50, 100]; " +
            "var fixedDigits = [0, 1, 2, 5, 10, 20, 50, 100]; var out = []; " +
            "for (var i = 0; i < values.length; i++) { " +
            "for (var j = 0; j < expDigits.length; j++) { out.push(values[i].toExponential(expDigits[j])); } " +
            "for (var k = 0; k < precDigits.length; k++) { out.push(values[i].toPrecision(precDigits[k])); } " +
            "for (var m = 0; m < fixedDigits.length; m++) { out.push(values[i].toFixed(fixedDigits[m])); } " +
            "} return out.join('|'); })()"
    ),
)

private fun jsonEscape(text: String): String {
    val builder = StringBuilder(text.length + 8)
    for (character in text) {
        when (character) {
            '\\' -> builder.append("\\\\")
            '"' -> builder.append("\\\"")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> builder.append(character)
        }
    }
    return builder.toString()
}