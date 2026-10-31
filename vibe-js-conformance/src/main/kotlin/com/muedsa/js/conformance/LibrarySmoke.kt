package com.muedsa.js.conformance

import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
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

    val report = runBlocking { LibrarySmokeRunner(libraryDir).run() }
    reportDir.mkdirs()
    report.writeMarkdown(File(reportDir, "libraries-summary.md"))
    report.writeJson(File(reportDir, "libraries-summary.json"))
    report.writeFailures(File(reportDir, "libraries-failures.txt"))
    report.printSummary()

    if (report.crashed > 0) {
        exitProcess(2)
    }
    if (strict && report.failed > 0) {
        exitProcess(1)
    }
}

private class LibrarySmokeRunner(
    private val libraryDir: File,
) {

    suspend fun run(): LibraryReport {
        val startedAt = System.nanoTime()
        val results = mutableListOf<LibraryResult>()
        for (spec in librarySpecs) {
            val file = File(libraryDir, "${spec.name}.js")
            if (!file.isFile) {
                results.add(LibraryResult(spec.name, "skip", "缺少库文件", emptyList()))
                continue
            }
            val source = file.readText().removePrefix("\uFEFF")
            val engine = Engine()
            try {
                engine.run(source)
                val globalType = engine.run("typeof ${spec.globalName}") as? JSString
                if (globalType?.value == "undefined") {
                    results.add(LibraryResult(spec.name, "load-fail", "全局变量 ${spec.globalName} 未定义", emptyList()))
                    continue
                }
            } catch (error: JSException) {
                results.add(
                    LibraryResult(
                        spec.name,
                        "load-fail",
                        "加载失败：$error",
                        emptyList(),
                    )
                )
                continue
            } catch (error: ParseException) {
                results.add(
                    LibraryResult(spec.name, "load-fail", "解析失败：${error.message}", emptyList())
                )
                continue
            } catch (error: LexerException) {
                results.add(
                    LibraryResult(spec.name, "load-fail", "词法失败：${error.message}", emptyList())
                )
                continue
            } catch (error: Throwable) {
                results.add(
                    LibraryResult(
                        spec.name,
                        "crash",
                        "加载崩溃：${error.describeThrowable()}",
                        emptyList(),
                    )
                )
                continue
            }

            val caseResults = mutableListOf<CaseResult>()
            for (case in spec.cases) {
                caseResults.add(
                    try {
                        engine.run(case.source)
                        CaseResult(case.name, "pass", null)
                    } catch (error: JSException) {
                        CaseResult(case.name, "fail", error.toString())
                    } catch (error: ParseException) {
                        CaseResult(case.name, "fail", "解析失败：${error.message}")
                    } catch (error: LexerException) {
                        CaseResult(case.name, "fail", "词法失败：${error.message}")
                    } catch (error: Throwable) {
                        CaseResult(case.name, "crash", error.describeThrowable())
                    }
                )
            }
            results.add(LibraryResult(spec.name, "loaded", null, caseResults))
        }
        return LibraryReport(
            generatedAt = Instant.now().toString(),
            results = results,
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
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

    fun writeMarkdown(file: File) {
        file.writeText(
            buildString {
                appendLine("# 真实 JS 库 UMD 冒烟")
                appendLine()
                appendLine("- 生成时间：$generatedAt")
                appendLine("- 通过：$passed，失败：$failed，崩溃：$crashed，耗时：${elapsedMillis}ms")
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
                appendLine("  \"passed\": $passed,")
                appendLine("  \"failed\": $failed,")
                appendLine("  \"crashed\": $crashed,")
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
        println("  通过 $passed，失败 $failed，崩溃 $crashed，耗时 ${elapsedMillis}ms")
    }
}

private fun JSValue.describeValue(): String {
    if (this is JSError) {
        return if (messageText.isEmpty()) errorName else "$errorName: $messageText"
    }
    if (this is JSObject) {
        val name = (getOwnDataValue(PropertyKey.Str("name")) as? JSString)?.value
        val message = (getOwnDataValue(PropertyKey.Str("message")) as? JSString)?.value
        if (name != null || message != null) {
            return listOfNotNull(name, message).joinToString(": ")
        }
    }
    return toString()
}

private fun Throwable.describeThrowable(): String {
    val firstLine = message?.lineSequence()?.firstOrNull()?.trim()
    val type = this::class.simpleName ?: "Throwable"
    return if (firstLine.isNullOrEmpty()) type else "$type: $firstLine"
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