package com.muedsa.js.conformance

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * 以 Node 为参照的差分测试：同一表达式在 Vibe JS 与 Node 中序列化后比较。
 */
fun main(args: Array<String>) {
    val reportDir = File(args.getOrElse(0) { "build/reports/conformance" })
    val strict = args.contains("--strict")

    val report = runBlocking { DifferentialRunner().run() }
    reportDir.mkdirs()
    report.writeMarkdown(File(reportDir, "differential-summary.md"))
    report.writeJson(File(reportDir, "differential-summary.json"))
    report.writeMismatches(File(reportDir, "differential-mismatches.txt"))
    report.printSummary()

    if (report.nodeUnavailable) {
        println("未找到 Node，差分测试已跳过")
        return
    }
    if (strict && report.mismatches > 0) {
        exitProcess(1)
    }
}

internal data class DifferentialCase(
    val name: String,
    val expression: String,
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
    val nodeVersion: String,
    val nodeUnavailable: Boolean,
    val results: List<DifferentialResult>,
    val elapsedMillis: Long,
) {
    val mismatches: Int get() = results.count { !it.matches }

    fun writeMarkdown(file: File) {
        file.writeText(
            buildString {
                appendLine("# Node 差分基线")
                appendLine()
                appendLine("- 生成时间：$generatedAt")
                appendLine("- Node：${if (nodeUnavailable) "不可用" else nodeVersion}")
                appendLine("- 用例：${results.size}，不一致：$mismatches，耗时：${elapsedMillis}ms")
                appendLine()
                appendLine("| 用例 | Vibe JS | Node |")
                appendLine("|---|---|---|")
                for (result in results) {
                    appendLine(
                        "| ${result.name} | ${result.vibeValue.replace("|", "\\|")} | " +
                            "${result.nodeValue.replace("|", "\\|")} |"
                    )
                }
            }
        )
    }

    fun writeJson(file: File) {
        file.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"generatedAt\": \"${jsonEscape(generatedAt)}\",")
                appendLine("  \"nodeVersion\": \"${jsonEscape(nodeVersion)}\",")
                appendLine("  \"nodeUnavailable\": $nodeUnavailable,")
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
        println("  用例 ${results.size}，不一致 $mismatches，耗时 ${elapsedMillis}ms")
        for (result in results.filter { !it.matches }.take(10)) {
            println("  ✗ ${result.name}: vibe=${result.vibeValue}, node=${result.nodeValue}")
        }
    }
}

private class DifferentialRunner {

    suspend fun run(): DifferentialReport {
        val startedAt = System.nanoTime()
        val cases = differentialCases + extraDifferentialCases
        val vibeResults = mutableListOf<Triple<String, String, String>>()
        val engine = Engine()
        for (case in cases) {
            vibeResults.add(
                Triple(case.name, case.expression, evaluateInVibe(engine, case.expression))
            )
        }
        for (case in asyncDifferentialCases) {
            val display = case.source.replace('\n', ' ').replace('\t', ' ')
            vibeResults.add(Triple(case.name, display, evaluateAsyncInVibe(case.source)))
        }

        val node = try {
            val version = runNode(listOf("--version")).trim()
            val script = File.createTempFile("vibe-diff-", ".js")
            script.writeText(buildNodeScript(cases, asyncDifferentialCases))
            val output = runNode(listOf(script.absolutePath))
            script.delete()
            version to output.lineSequence()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val tab = line.indexOf('\t')
                    if (tab < 0) line to "" else line.substring(0, tab) to line.substring(tab + 1)
                }
        } catch (error: Throwable) {
            null
        }

        if (node == null) {
            return DifferentialReport(
                generatedAt = Instant.now().toString(),
                nodeVersion = "",
                nodeUnavailable = true,
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
            nodeVersion = version,
            nodeUnavailable = false,
            results = results,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    private suspend fun evaluateInVibe(engine: Engine, expression: String): String {
        return try {
            when (val value = engine.run("JSON.stringify(($expression))")) {
                is JSUndefined -> "undefined"
                is JSString -> value.value
                else -> value.toString()
            }
        } catch (error: JSException) {
            "THROW:${thrownName(error.value)}"
        } catch (error: Throwable) {
            "CRASH:${error::class.simpleName}"
        }
    }

    /**
     * 异步用例：脚本执行（含微任务冲刷）后读取 `globalThis.__diffResult`。
     * 每个用例使用独立 Engine，避免全局状态相互影响。
     */
    private suspend fun evaluateAsyncInVibe(source: String): String {
        val engine = Engine()
        return try {
            engine.run(source)
            when (val value = engine.run("JSON.stringify(globalThis.__diffResult)")) {
                is JSUndefined -> "undefined"
                is JSString -> value.value
                else -> value.toString()
            }
        } catch (error: JSException) {
            "THROW:${thrownName(error.value)}"
        } catch (error: Throwable) {
            "CRASH:${error::class.simpleName}"
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
            for (const [name, expression] of cases) {
              try {
                const value = eval(expression);
                const json = JSON.stringify(value);
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
                    try {
                      await (async () => { eval(source); })();
                      // 冲刷所有已入队的微任务（宏任务边界）
                      await new Promise((resolve) => setImmediate(resolve));
                      const json = JSON.stringify(globalThis.__diffResult);
                      process.stdout.write(name + '\t' + (json === undefined ? 'undefined' : json) + '\n');
                    } catch (error) {
                      const errorName = error && error.name ? error.name : 'Error';
                      process.stdout.write(name + '\tTHROW:' + errorName + '\n');
                    }
                    delete globalThis.__diffResult;
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

    private fun runNode(arguments: List<String>): String {
        val process = ProcessBuilder(listOf("node") + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor()
        check(finished == 0) { "node exited with $finished: $output" }
        return output
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