package com.muedsa.js.conformance

import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * test262-parser-tests 解析一致性运行器。
 *
 * 只做 Lexer + Parser，不执行脚本；因此适合作为大规模语法语料的快速基线。
 * - pass / pass-explicit：必须解析成功；
 * - fail：必须被词法或语法错误拒绝；
 * - early：语法合法但存在早期错误，本运行器只统计“解析成功/被拒绝”，不计入通过率；
 * - *.module.js：当前不支持模块，统一跳过并单独计数。
 */
fun main(args: Array<String>) {
    val corpusDir = File(args.getOrElse(0) { "corpus/test262-parser-tests" })
    val reportDir = File(args.getOrElse(1) { "build/reports/conformance" })
    val strict = args.contains("--strict")

    if (!corpusDir.isDirectory) {
        System.err.println(
            "找不到语料目录：${corpusDir.absolutePath}\n" +
                "请先执行 ./gradlew :vibe-js-conformance:downloadParserCorpus"
        )
        exitProcess(3)
    }

    val report = ParserConformanceRunner(corpusDir).run()
    reportDir.mkdirs()
    report.writeMarkdown(File(reportDir, "parser-summary.md"))
    report.writeJson(File(reportDir, "parser-summary.json"))
    report.writeFailureLists(reportDir)
    report.printSummary()

    if (report.crashCount > 0) {
        exitProcess(2)
    }
    if (strict && report.hasExpectedFailures()) {
        exitProcess(1)
    }
}

/**
 * 解析器允许列表（按文件名匹配）：项目明确排除的语法（当前为 Generator）
 * 在 strict 模式下不计为门禁失败。
 */
private val parserPassAllowlist: Set<String> = loadParserPassAllowlist()

/**
 * fail 负例允许列表（按文件名匹配）：语料早于 ES2022 公有字段，
 * 这类文件在现行规范下合法，strict 模式下不计为门禁失败。
 */
private val parserFailAllowlist: Set<String> = loadAllowlist("parser-fail-allowlist.txt")

private object AllowlistResources

private fun loadParserPassAllowlist(): Set<String> = loadAllowlist("parser-pass-allowlist.txt")

private fun loadAllowlist(resource: String): Set<String> {
    val stream = AllowlistResources::class.java.classLoader
        .getResourceAsStream(resource)
        ?: return emptySet()
    return stream.bufferedReader().useLines { lines ->
        lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
    }
}

private class ParserConformanceRunner(
    private val root: File,
) {

    fun run(): ParserRunReport {
        val pass = runExpectSuccess("pass", File(root, "pass"))
        val passExplicit = runExpectSuccess("pass-explicit", File(root, "pass-explicit"))
        val fail = runExpectRejection("fail", File(root, "fail"))
        val early = runEarly(File(root, "early"))
        val moduleSkipped = countModuleFiles()
        return ParserRunReport(
            generatedAt = Instant.now().toString(),
            corpus = root.absolutePath,
            categories = listOf(pass, passExplicit, fail),
            early = early,
            moduleSkipped = moduleSkipped,
        )
    }

    private fun runExpectSuccess(name: String, directory: File): CategoryReport {
        val files = scriptFiles(directory)
        val allowGenerator = name == "pass" || name == "pass-explicit"
        var passed = 0
        var failed = 0
        var allowed = 0
        var crashes = 0
        val failures = mutableListOf<TestFailure>()
        val allowedFailures = mutableListOf<TestFailure>()
        val startedAt = System.nanoTime()
        for (file in files) {
            try {
                parse(file)
                passed++
            } catch (error: ParseException) {
                val item = failure(file, error)
                if (allowGenerator && file.name in parserPassAllowlist) {
                    allowed++
                    allowedFailures.add(item)
                } else {
                    failed++
                    failures.add(item)
                }
            } catch (error: LexerException) {
                val item = failure(file, error)
                if (allowGenerator && file.name in parserPassAllowlist) {
                    allowed++
                    allowedFailures.add(item)
                } else {
                    failed++
                    failures.add(item)
                }
            } catch (error: Throwable) {
                failed++
                crashes++
                failures.add(failure(file, error))
            }
        }
        return CategoryReport(
            name = name,
            total = files.size,
            passed = passed,
            failed = failed,
            crashes = crashes,
            failures = failures,
            elapsedMillis = elapsedMillis(startedAt),
            allowed = allowed,
            allowedFailures = allowedFailures,
        )
    }

    private fun runExpectRejection(name: String, directory: File): CategoryReport {
        val files = scriptFiles(directory)
        var passed = 0
        var failed = 0
        var allowed = 0
        var crashes = 0
        val failures = mutableListOf<TestFailure>()
        val allowedFailures = mutableListOf<TestFailure>()
        val startedAt = System.nanoTime()
        for (file in files) {
            try {
                parse(file)
                val item = TestFailure(
                    path = relativePath(file),
                    message = "期望解析失败，但解析器接受了该文件",
                )
                if (file.name in parserFailAllowlist) {
                    allowed++
                    allowedFailures.add(item)
                } else {
                    failed++
                    failures.add(item)
                }
            } catch (_: ParseException) {
                passed++
            } catch (_: LexerException) {
                passed++
            } catch (error: Throwable) {
                failed++
                crashes++
                failures.add(failure(file, error))
            }
        }
        return CategoryReport(
            name = name,
            total = files.size,
            passed = passed,
            failed = failed,
            crashes = crashes,
            failures = failures,
            elapsedMillis = elapsedMillis(startedAt),
            allowed = allowed,
            allowedFailures = allowedFailures,
        )
    }

    private fun runEarly(directory: File): EarlyReport {
        val files = scriptFiles(directory)
        var parsed = 0
        var rejected = 0
        var crashes = 0
        val crashSamples = mutableListOf<TestFailure>()
        val acceptedFiles = mutableListOf<String>()
        val startedAt = System.nanoTime()
        for (file in files) {
            try {
                parse(file)
                parsed++
                acceptedFiles.add(relativePath(file))
            } catch (_: ParseException) {
                rejected++
            } catch (_: LexerException) {
                rejected++
            } catch (error: Throwable) {
                crashes++
                crashSamples.add(failure(file, error))
            }
        }
        return EarlyReport(
            total = files.size,
            parsed = parsed,
            rejected = rejected,
            crashes = crashes,
            crashSamples = crashSamples,
            acceptedFiles = acceptedFiles,
            elapsedMillis = elapsedMillis(startedAt),
        )
    }

    private fun parse(file: File) {
        val source = file.readText()
        val tokens = Lexer(source).tokenize()
        Parser(tokens).parseProgram()
    }

    private fun scriptFiles(directory: File): List<File> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".js") && !it.name.contains(".module.") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun countModuleFiles(): Int {
        return listOf("pass", "pass-explicit", "fail", "early").sumOf { name ->
            File(root, name).listFiles()
                ?.count { it.isFile && it.name.endsWith(".js") && it.name.contains(".module.") }
                ?: 0
        }
    }

    private fun relativePath(file: File): String =
        file.relativeTo(root).invariantSeparatorsPath

    private fun failure(file: File, error: Throwable): TestFailure {
        val message = buildString {
            append(error::class.simpleName ?: "Throwable")
            val detail = error.message?.lineSequence()?.firstOrNull()?.trim()
            if (!detail.isNullOrEmpty()) {
                append(": ")
                append(detail.take(300))
            }
        }
        return TestFailure(path = relativePath(file), message = message)
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000
}

private data class TestFailure(
    val path: String,
    val message: String,
)

private data class CategoryReport(
    val name: String,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val crashes: Int,
    val failures: List<TestFailure>,
    val elapsedMillis: Long,
    val allowed: Int = 0,
    val allowedFailures: List<TestFailure> = emptyList(),
) {
    val passRate: Double
        get() = if (total == 0) 0.0 else passed.toDouble() / total
}

private data class EarlyReport(
    val total: Int,
    val parsed: Int,
    val rejected: Int,
    val crashes: Int,
    val crashSamples: List<TestFailure>,
    val acceptedFiles: List<String>,
    val elapsedMillis: Long,
)

private data class ParserRunReport(
    val generatedAt: String,
    val corpus: String,
    val categories: List<CategoryReport>,
    val early: EarlyReport,
    val moduleSkipped: Int,
) {
    val crashCount: Int
        get() = categories.sumOf { it.crashes } + early.crashes

    fun hasExpectedFailures(): Boolean = categories.any { it.failed > 0 }

    fun writeMarkdown(file: File) {
        file.writeText(
            buildString {
                appendLine("# 解析一致性基线（test262-parser-tests）")
                appendLine()
                appendLine("- 生成时间：$generatedAt")
                appendLine("- 语料目录：`$corpus`")
                appendLine("- 范围：只解析、不执行，脚本代码；`.module.js` 跳过")
                appendLine()
                appendLine("| 类别 | 总数 | 通过 | 失败 | 允许 | 通过率 | 崩溃 | 耗时(ms) |")
                appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
                for (category in categories) {
                    appendLine(
                        "| ${category.name} | ${category.total} | ${category.passed} | " +
                            "${category.failed} | ${category.allowed} | ${formatPercent(category.passRate)} | " +
                            "${category.crashes} | ${category.elapsedMillis} |"
                    )
                }
                appendLine()
                appendLine("“允许”为允许列表中的明确排除项（Generator 语法与公有字段的过期负例），不计入门禁失败。")
                appendLine()
                appendLine(
                    "早期错误用例（early）：共 ${early.total}，解析成功 ${early.parsed}，" +
                        "被拒绝 ${early.rejected}，崩溃 ${early.crashes}。"
                )
                appendLine("模块用例跳过：$moduleSkipped。")
                appendLine()
                appendLine("失败样例见 `failures-*.txt` 与 `parser-summary.json`。")
            }
        )
    }

    fun writeJson(file: File) {
        file.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"generatedAt\": \"${jsonEscape(generatedAt)}\",")
                appendLine("  \"corpus\": \"${jsonEscape(corpus)}\",")
                appendLine("  \"moduleSkipped\": $moduleSkipped,")
                appendLine("  \"categories\": [")
                categories.forEachIndexed { index, category ->
                    appendLine("    {")
                    appendLine("      \"name\": \"${jsonEscape(category.name)}\",")
                    appendLine("      \"total\": ${category.total},")
                    appendLine("      \"passed\": ${category.passed},")
                    appendLine("      \"failed\": ${category.failed},")
                    appendLine("      \"allowed\": ${category.allowed},")
                    appendLine("      \"crashes\": ${category.crashes},")
                    appendLine("      \"passRate\": ${category.passRate},")
                    appendLine("      \"elapsedMillis\": ${category.elapsedMillis},")
                    appendLine("      \"allowlistedPaths\": [")
                    category.allowedFailures.forEachIndexed { allowedIndex, failure ->
                        appendLine(
                            "        \"${jsonEscape(failure.path)}\"" +
                                if (allowedIndex < category.allowedFailures.lastIndex) "," else ""
                        )
                    }
                    appendLine("      ],")
                    appendLine("      \"failureSamples\": [")
                    category.failures.take(50).forEachIndexed { failureIndex, failure ->
                        appendLine(
                            "        {\"path\": \"${jsonEscape(failure.path)}\", " +
                                "\"message\": \"${jsonEscape(failure.message)}\"}" +
                                if (failureIndex < minOf(category.failures.size, 50) - 1) "," else ""
                        )
                    }
                    appendLine("      ]")
                    append("    }")
                    appendLine(if (index < categories.lastIndex) "," else "")
                }
                appendLine("  ],")
                appendLine("  \"early\": {")
                appendLine("    \"total\": ${early.total},")
                appendLine("    \"parsed\": ${early.parsed},")
                appendLine("    \"rejected\": ${early.rejected},")
                appendLine("    \"crashes\": ${early.crashes},")
                appendLine("    \"elapsedMillis\": ${early.elapsedMillis}")
                appendLine("  }")
                appendLine("}")
            }
        )
    }

    fun writeFailureLists(directory: File) {
        // 始终覆盖：零失败时也要清空上一次运行的残留内容
        for (category in categories) {
            File(directory, "failures-${category.name}.txt").writeText(
                buildString {
                    for (failure in category.failures) {
                        appendLine("${failure.path}\t${failure.message}")
                    }
                }
            )
            File(directory, "allowlisted-${category.name}.txt").writeText(
                buildString {
                    for (failure in category.allowedFailures) {
                        appendLine("${failure.path}\t${failure.message}")
                    }
                }
            )
        }
        // early 被接受的文件（语法合法但应触发早期错误），便于针对性补校验
        File(directory, "early-accepted.txt").writeText(
            early.acceptedFiles.joinToString(separator = "\n", postfix = if (early.acceptedFiles.isEmpty()) "" else "\n")
        )
        File(directory, "failures-early-crashes.txt").writeText(
            buildString {
                for (failure in early.crashSamples) {
                    appendLine("${failure.path}\t${failure.message}")
                }
            }
        )
    }

    fun printSummary() {
        println("test262-parser-tests 解析一致性基线")
        for (category in categories) {
            println(
                "  ${category.name}: ${category.passed}/${category.total} " +
                    "(${formatPercent(category.passRate)}), 失败 ${category.failed}, " +
                    "允许 ${category.allowed}, 崩溃 ${category.crashes}, ${category.elapsedMillis}ms"
            )
        }
        println(
            "  early: 解析成功 ${early.parsed}/${early.total}, " +
                "拒绝 ${early.rejected}, 崩溃 ${early.crashes}"
        )
        println("  module 跳过: $moduleSkipped")
        if (crashCount > 0) {
            println("  警告：检测到 $crashCount 个崩溃，详见报告")
        }
    }
}

private fun formatPercent(value: Double): String {
    val percent = value * 100.0
    return ((percent * 100).toInt() / 100.0).toString() + "%"
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
