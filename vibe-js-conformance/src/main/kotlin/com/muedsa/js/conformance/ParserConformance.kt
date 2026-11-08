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
 * - *.module.js：按模块目标调用 [Parser.parseModule] 解析（脚本文件仍走
 *   [Parser.parseProgram]），单独成组统计并纳入 strict 门禁。
 *
 * 模块文件恒为严格模式，顶层 `await`、`import`/`export`、Annex B 禁用等规则
 * 与脚本目标不同，因此不能混用同一入口；语料目录中的分类（pass / fail / early）
 * 决定期望结果，文件名后缀决定解析目标。
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
    if (strict && report.total == 0) {
        System.err.println("解析一致性语料为空，严格模式判定失败")
        exitProcess(1)
    }
    if (strict && report.hasExpectedFailures()) {
        System.err.println(
            "解析一致性门禁失败：存在未允许的解析失败或 early 接受项（脚本与模块目标），" +
                "详见报告目录 ${reportDir.absolutePath}"
        )
        exitProcess(1)
    }
}

/**
 * pass / pass-explicit 允许列表（按文件名匹配）：清单已清空，当前 0 条允许。
 *
 * strict 模式下，未列入清单的 pass 文件解析失败一律计为门禁失败；
 * 清单保留用于记录经确认的语料/规范差异，新增条目必须先排除解析器回归。
 */
private val parserPassAllowlist: Set<String> = loadAllowlist("parser-pass-allowlist.txt")

/**
 * fail 负例允许列表（按文件名匹配）：语料按旧版规范标记为负例，
 * 但现行规范已合法（ES2022 公有字段、非严格模式 \8/\9、
 * Annex B 赋值目标与 for-in 初始化器）；strict 模式下这些文件被接受不计为门禁失败。
 */
private val parserFailAllowlist: Set<String> = loadAllowlist("parser-fail-allowlist.txt")

/**
 * early 允许列表（按文件名匹配）：语料按旧版规范标记为早期错误，
 * 但现行 Annex B/规范语义允许（块级重复函数声明、catch 参数与 for-var 同名），
 * 当前共 5 条。strict 模式下只有这些文件可以被接受，其余 early 文件必须被拒绝。
 */
private val parserEarlyAllowlist: Set<String> = loadAllowlist("parser-early-allowlist.txt")

/**
 * 模块允许列表（按“相对语料根目录的路径 + 原因”匹配）。
 *
 * 模块语料在 strict 门禁下同样要求全部通过；只有经确认“短期无法支持或语料过期”
 * 的少量文件才允许逐条登记。条目必须带原因，且路径必须指向 `.module.js`，
 * 避免用目录/后缀等宽泛文本排除整片用例。清单内容会原样写入报告，便于审计。
 */
private val parserModuleAllowlist: List<ParserAllowlistEntry> = loadModuleAllowlist()
private val parserModuleAllowlistPaths: Set<String> =
    parserModuleAllowlist.mapTo(mutableSetOf()) { it.path }

private object AllowlistResources

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

private fun loadModuleAllowlist(): List<ParserAllowlistEntry> {
    val stream = AllowlistResources::class.java.classLoader
        .getResourceAsStream("parser-module-allowlist.txt")
        ?: return emptyList()
    return stream.bufferedReader().use { parseParserModuleAllowlist(it.readText()) }
}

/**
 * 解析目标：决定调用哪个 Parser 入口。
 *
 * 模块与脚本在早期错误、严格模式和顶层声明语义上差异很大，
 * 运行器必须按文件后缀选择目标，而不是按目录统一处理。
 */
internal enum class ParseGoal {
    SCRIPT,
    MODULE,
}

/** 根据语料文件名判断解析目标：`*.module.js` 为模块，其余 `.js` 为脚本。 */
internal fun parseGoalFor(fileName: String): ParseGoal =
    if (fileName.endsWith(".module.js")) ParseGoal.MODULE else ParseGoal.SCRIPT

/**
 * 一次解析尝试的结果。
 *
 * 区分“被语法/词法错误拒绝”和“运行器自身崩溃”：前者是负例的预期结果，
 * 后者必须计入门禁失败并让任务以退出码 2 结束，不能被当成正常拒绝吞掉。
 */
internal sealed interface ParseAttempt {
    /** 解析成功。 */
    data object Success : ParseAttempt

    /** 被 [ParseException] 或 [LexerException] 拒绝（语法/词法错误）。 */
    data class Rejected(val error: Throwable) : ParseAttempt

    /** 非预期的异常（解析器缺陷、资源限制等）。 */
    data class Crash(val error: Throwable) : ParseAttempt
}

/**
 * 按目标解析源码。
 *
 * 独立成函数便于单元测试直接覆盖“模块目标接受 import/export、脚本目标拒绝”
 * 等关键差异，不需要依赖完整语料。
 */
internal fun attemptParse(goal: ParseGoal, source: String): ParseAttempt {
    return try {
        // 模块目标必须在词法阶段就声明：HTML 注释在 Parser 看到之前已被丢弃，
        // 若等到 parseModule 再判断就无法区分脚本/模块的 Annex B 差异。
        val tokens = Lexer(source, moduleContext = goal == ParseGoal.MODULE).tokenize()
        val parser = Parser(tokens)
        if (goal == ParseGoal.MODULE) {
            parser.parseModule()
        } else {
            parser.parseProgram()
        }
        ParseAttempt.Success
    } catch (error: ParseException) {
        ParseAttempt.Rejected(error)
    } catch (error: LexerException) {
        ParseAttempt.Rejected(error)
    } catch (error: Throwable) {
        ParseAttempt.Crash(error)
    }
}

/**
 * 选出某个目录下指定目标的 `.js` 语料，按文件名排序保证报告可复现。
 */
internal fun selectParserFiles(directory: File, goal: ParseGoal): List<File> {
    if (!directory.isDirectory) return emptyList()
    return directory.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".js") && parseGoalFor(it.name) == goal }
        ?.sortedBy { it.name }
        ?: emptyList()
}

/** 模块允许列表条目：相对路径（POSIX 分隔符）+ 允许原因。 */
internal data class ParserAllowlistEntry(
    val path: String,
    val reason: String,
)

/**
 * 解析模块允许列表文本。
 *
 * 格式：每行 `相对路径<TAB>原因`；空行与 `#` 开头的注释行忽略。
 * 路径与原因都不可为空，且只允许 `.module.js` 文件，防止误写宽泛规则。
 * 格式错误直接抛异常，避免清单悄悄失效。
 */
internal fun parseParserModuleAllowlist(text: String): List<ParserAllowlistEntry> {
    val entries = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val separator = line.indexOf('\t')
            require(separator > 0) {
                "模块允许列表条目必须用制表符分隔路径与原因：$line"
            }
            val path = line.substring(0, separator).trim()
            val reason = line.substring(separator + 1).trim()
            require(path.isNotEmpty()) { "模块允许列表条目缺少路径：$line" }
            require(reason.isNotEmpty()) { "模块允许列表条目缺少原因：$line" }
            require(path.endsWith(".module.js")) { "模块允许列表只能记录 .module.js：$path" }
            require(!path.startsWith("/") && !path.contains('\\')) {
                "模块允许列表路径必须是相对语料根目录的 POSIX 路径：$path"
            }
            ParserAllowlistEntry(path, reason)
        }
        .toList()
    val duplicates = entries.groupBy { it.path }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "模块允许列表存在重复路径：${duplicates.joinToString()}" }
    return entries
}

private class ParserConformanceRunner(
    private val root: File,
) {

    fun run(): ParserRunReport {
        // 脚本目标：保持原有 pass / pass-explicit / fail / early 统计口径不变
        val pass = runExpectSuccess(
            name = "pass",
            directory = File(root, "pass"),
            goal = ParseGoal.SCRIPT,
            isAllowlisted = { file -> file.name in parserPassAllowlist },
        )
        val passExplicit = runExpectSuccess(
            name = "pass-explicit",
            directory = File(root, "pass-explicit"),
            goal = ParseGoal.SCRIPT,
            isAllowlisted = { file -> file.name in parserPassAllowlist },
        )
        val fail = runExpectRejection(
            name = "fail",
            directory = File(root, "fail"),
            goal = ParseGoal.SCRIPT,
            isAllowlisted = { file -> file.name in parserFailAllowlist },
        )
        val early = runEarly(
            name = "early",
            directory = File(root, "early"),
            goal = ParseGoal.SCRIPT,
            isAllowlisted = { file -> file.name in parserEarlyAllowlist },
        )
        // 模块目标：同一批目录中的 *.module.js 用 Parser.parseModule 解析
        val modulePass = runExpectSuccess(
            name = "pass-module",
            directory = File(root, "pass"),
            goal = ParseGoal.MODULE,
            isAllowlisted = ::isModuleAllowlisted,
        )
        val modulePassExplicit = runExpectSuccess(
            name = "pass-explicit-module",
            directory = File(root, "pass-explicit"),
            goal = ParseGoal.MODULE,
            isAllowlisted = ::isModuleAllowlisted,
        )
        val moduleFail = runExpectRejection(
            name = "fail-module",
            directory = File(root, "fail"),
            goal = ParseGoal.MODULE,
            isAllowlisted = ::isModuleAllowlisted,
        )
        val moduleEarly = runEarly(
            name = "early-module",
            directory = File(root, "early"),
            goal = ParseGoal.MODULE,
            isAllowlisted = ::isModuleAllowlisted,
        )
        return ParserRunReport(
            generatedAt = Instant.now().toString(),
            corpus = root.absolutePath,
            categories = listOf(pass, passExplicit, fail),
            early = early,
            moduleCategories = listOf(modulePass, modulePassExplicit, moduleFail),
            moduleEarly = moduleEarly,
            moduleAllowlist = parserModuleAllowlist,
        )
    }

    private fun runExpectSuccess(
        name: String,
        directory: File,
        goal: ParseGoal,
        isAllowlisted: (File) -> Boolean,
    ): CategoryReport {
        val files = selectParserFiles(directory, goal)
        var passed = 0
        var failed = 0
        var allowed = 0
        var crashes = 0
        val failures = mutableListOf<TestFailure>()
        val allowedFailures = mutableListOf<TestFailure>()
        val startedAt = System.nanoTime()
        for (file in files) {
            when (val attempt = attemptParse(goal, file.readText())) {
                ParseAttempt.Success -> passed++
                is ParseAttempt.Rejected -> {
                    val item = failure(file, attempt.error)
                    if (isAllowlisted(file)) {
                        allowed++
                        allowedFailures.add(item)
                    } else {
                        failed++
                        failures.add(item)
                    }
                }
                is ParseAttempt.Crash -> {
                    failed++
                    crashes++
                    failures.add(failure(file, attempt.error))
                }
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

    private fun runExpectRejection(
        name: String,
        directory: File,
        goal: ParseGoal,
        isAllowlisted: (File) -> Boolean,
    ): CategoryReport {
        val files = selectParserFiles(directory, goal)
        var passed = 0
        var failed = 0
        var allowed = 0
        var crashes = 0
        val failures = mutableListOf<TestFailure>()
        val allowedFailures = mutableListOf<TestFailure>()
        val startedAt = System.nanoTime()
        for (file in files) {
            when (val attempt = attemptParse(goal, file.readText())) {
                ParseAttempt.Success -> {
                    val item = TestFailure(
                        path = relativePath(file),
                        message = "期望解析失败，但解析器接受了该文件",
                    )
                    if (isAllowlisted(file)) {
                        allowed++
                        allowedFailures.add(item)
                    } else {
                        failed++
                        failures.add(item)
                    }
                }
                is ParseAttempt.Rejected -> passed++
                is ParseAttempt.Crash -> {
                    failed++
                    crashes++
                    failures.add(failure(file, attempt.error))
                }
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

    private fun runEarly(
        name: String,
        directory: File,
        goal: ParseGoal,
        isAllowlisted: (File) -> Boolean,
    ): EarlyReport {
        val files = selectParserFiles(directory, goal)
        var parsed = 0
        var rejected = 0
        var crashes = 0
        val crashSamples = mutableListOf<TestFailure>()
        val acceptedFiles = mutableListOf<String>()
        val allowedFiles = mutableListOf<TestFailure>()
        val unexpectedAcceptedFiles = mutableListOf<TestFailure>()
        val startedAt = System.nanoTime()
        for (file in files) {
            when (val attempt = attemptParse(goal, file.readText())) {
                ParseAttempt.Success -> {
                    parsed++
                    acceptedFiles.add(relativePath(file))
                    val item = TestFailure(
                        path = relativePath(file),
                        message = "期望早期错误，但解析器接受了该文件",
                    )
                    if (isAllowlisted(file)) {
                        allowedFiles.add(item)
                    } else {
                        unexpectedAcceptedFiles.add(item)
                    }
                }
                is ParseAttempt.Rejected -> rejected++
                is ParseAttempt.Crash -> {
                    crashes++
                    crashSamples.add(failure(file, attempt.error))
                }
            }
        }
        return EarlyReport(
            name = name,
            total = files.size,
            parsed = parsed,
            rejected = rejected,
            crashes = crashes,
            crashSamples = crashSamples,
            acceptedFiles = acceptedFiles,
            allowedFiles = allowedFiles,
            unexpectedAcceptedFiles = unexpectedAcceptedFiles,
            elapsedMillis = elapsedMillis(startedAt),
        )
    }

    private fun isModuleAllowlisted(file: File): Boolean =
        relativePath(file) in parserModuleAllowlistPaths

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

internal data class TestFailure(
    val path: String,
    val message: String,
)

internal data class CategoryReport(
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

internal data class EarlyReport(
    /** 报告分组名：脚本为 `early`，模块为 `early-module`。 */
    val name: String,
    val total: Int,
    val parsed: Int,
    val rejected: Int,
    val crashes: Int,
    val crashSamples: List<TestFailure>,
    val acceptedFiles: List<String>,
    /** 允许列表内的已知差异（现行 Annex B 语义允许，语料仍标记为早期错误） */
    val allowedFiles: List<TestFailure>,
    /** 不在允许列表内、却被解析器接受的 early 文件（strict 门禁失败项） */
    val unexpectedAcceptedFiles: List<TestFailure>,
    val elapsedMillis: Long,
)

internal data class ParserRunReport(
    val generatedAt: String,
    val corpus: String,
    /** 脚本目标类别：pass / pass-explicit / fail。 */
    val categories: List<CategoryReport>,
    /** 脚本目标早期错误。 */
    val early: EarlyReport,
    /** 模块目标类别：pass-module / pass-explicit-module / fail-module。 */
    val moduleCategories: List<CategoryReport>,
    /** 模块目标早期错误。 */
    val moduleEarly: EarlyReport,
    /** 模块允许列表（逐条路径 + 原因），原样写入报告。 */
    val moduleAllowlist: List<ParserAllowlistEntry>,
) {
    val crashCount: Int
        get() = categories.sumOf { it.crashes } +
            early.crashes +
            moduleCategories.sumOf { it.crashes } +
            moduleEarly.crashes
    val total: Int
        get() = categories.sumOf { it.total } + early.total + moduleTotal
    val moduleTotal: Int
        get() = moduleCategories.sumOf { it.total } + moduleEarly.total

    /** 本次运行实际命中（即确实被容忍）的模块允许列表路径。 */
    val usedModuleAllowlistPaths: Set<String>
        get() = (moduleCategories.flatMap { it.allowedFailures } + moduleEarly.allowedFiles)
            .mapTo(mutableSetOf()) { it.path }

    fun hasExpectedFailures(): Boolean =
        categories.any { it.failed > 0 } ||
            early.unexpectedAcceptedFiles.isNotEmpty() ||
            moduleCategories.any { it.failed > 0 } ||
            moduleEarly.unexpectedAcceptedFiles.isNotEmpty()

    fun writeMarkdown(file: File) {
        file.writeText(
            buildString {
                appendLine("# 解析一致性基线（test262-parser-tests）")
                appendLine()
                appendLine("- 生成时间：$generatedAt")
                appendLine("- 语料目录：`$corpus`")
                appendLine(
                    "- 范围：只解析、不执行；脚本用 `Parser.parseProgram`，" +
                        "模块（`.module.js`）用 `Parser.parseModule`，按目标分别统计"
                )
                appendLine()
                appendLine("## 脚本目标")
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
                val passAllowed = categories
                    .filter { it.name == "pass" || it.name == "pass-explicit" }
                    .sumOf { it.allowed }
                val failAllowed = categories.find { it.name == "fail" }?.allowed ?: 0
                val passAllowlistNote = if (parserPassAllowlist.isEmpty()) {
                    "，清单已清空"
                } else {
                    "，清单 ${parserPassAllowlist.size} 条"
                }
                appendLine(
                    "“允许”为允许列表内的已知差异：pass/pass-explicit $passAllowed 条" +
                        "$passAllowlistNote；fail $failAllowed 条（语料过期负例），不计入门禁失败。"
                )
                appendLine()
                appendLine(
                    "早期错误用例（early）：共 ${early.total}，解析成功 ${early.parsed}，" +
                        "被拒绝 ${early.rejected}，崩溃 ${early.crashes}。"
                )
                appendLine(
                    "其中允许列表 ${early.allowedFiles.size} 条（清单 ${parserEarlyAllowlist.size} 条，" +
                        "均为现行 Annex B/规范允许、旧版语料仍标记为早期错误的用例），" +
                        "意外接受 ${early.unexpectedAcceptedFiles.size} 条（strict 门禁失败项）。"
                )
                appendLine(
                    "strict 模式要求 early 只接受允许列表内的文件；" +
                        "Generator 形参 yield、async 形参 await 等早期错误必须被拒绝。"
                )
                appendLine()
                appendLine("## 模块目标（.module.js）")
                appendLine()
                appendLine(
                    "模块代码恒为严格模式；顶层 `await`、`import`/`export` 与 Annex B 禁用" +
                        "均按模块语义校验。"
                )
                appendLine()
                appendLine("| 类别 | 总数 | 通过 | 失败 | 允许 | 通过率 | 崩溃 | 耗时(ms) |")
                appendLine("|---|---:|---:|---:|---:|---:|---:|---:|")
                for (category in moduleCategories) {
                    appendLine(
                        "| ${category.name} | ${category.total} | ${category.passed} | " +
                            "${category.failed} | ${category.allowed} | ${formatPercent(category.passRate)} | " +
                            "${category.crashes} | ${category.elapsedMillis} |"
                    )
                }
                appendLine()
                appendLine(
                    "早期错误用例（early-module）：共 ${moduleEarly.total}，" +
                        "解析成功 ${moduleEarly.parsed}（其中允许列表 ${moduleEarly.allowedFiles.size} 条），" +
                        "被拒绝 ${moduleEarly.rejected}，崩溃 ${moduleEarly.crashes}。"
                )
                appendLine(
                    "意外接受 ${moduleEarly.unexpectedAcceptedFiles.size} 条（strict 门禁失败项）；" +
                        "模块 early 用例与脚本一样要求被解析器拒绝。"
                )
                appendLine()
                appendLine("模块合计：$moduleTotal；脚本合计：${total - moduleTotal}。")
                appendLine()
                if (moduleAllowlist.isEmpty()) {
                    appendLine("模块允许列表：空（模块语料全部按严格门禁要求通过）。")
                } else {
                    appendLine(
                        "模块允许列表：${moduleAllowlist.size} 条，" +
                            "本次命中 ${usedModuleAllowlistPaths.size} 条" +
                            "（清单文件 `parser-module-allowlist.txt`，逐条路径 + 原因）："
                    )
                    appendLine()
                    appendLine("| 路径 | 原因 | 本次命中 |")
                    appendLine("|---|---|---|")
                    for (entry in moduleAllowlist) {
                        // Markdown 表格需要转义竖线，避免原因文本破坏表格结构
                        val reason = entry.reason.replace("|", "\\|")
                        appendLine(
                            "| `${entry.path}` | $reason | " +
                                if (entry.path in usedModuleAllowlistPaths) "是 |" else "否 |"
                        )
                    }
                }
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
                appendLine("  \"moduleTotal\": $moduleTotal,")
                appendLine("  \"categories\": [")
                appendCategoryListJson(categories)
                appendLine("  ],")
                appendLine("  \"moduleCategories\": [")
                appendCategoryListJson(moduleCategories)
                appendLine("  ],")
                appendLine("  \"early\": {")
                appendEarlyJson(early)
                appendLine("  },")
                appendLine("  \"moduleEarly\": {")
                appendEarlyJson(moduleEarly)
                appendLine("  },")
                appendLine("  \"moduleAllowlist\": [")
                moduleAllowlist.forEachIndexed { index, entry ->
                    appendLine(
                        "    {\"path\": \"${jsonEscape(entry.path)}\", " +
                            "\"reason\": \"${jsonEscape(entry.reason)}\", " +
                            "\"hit\": ${entry.path in usedModuleAllowlistPaths}}" +
                            if (index < moduleAllowlist.lastIndex) "," else ""
                    )
                }
                appendLine("  ]")
                appendLine("}")
            }
        )
    }

    fun writeFailureLists(directory: File) {
        // 始终覆盖：零失败时也要清空上一次运行的残留内容
        for (category in categories + moduleCategories) {
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
        writeEarlyFailureLists(directory, early, prefix = "early")
        writeEarlyFailureLists(directory, moduleEarly, prefix = "module-early")
    }

    private fun writeEarlyFailureLists(directory: File, early: EarlyReport, prefix: String) {
        File(directory, "$prefix-accepted.txt").writeText(
            early.acceptedFiles.joinToString(
                separator = "\n",
                postfix = if (early.acceptedFiles.isEmpty()) "" else "\n",
            )
        )
        // strict 门禁失败项：不在允许列表内、却被接受的 early 文件
        File(directory, "failures-$prefix-unexpected.txt").writeText(
            buildString {
                for (failure in early.unexpectedAcceptedFiles) {
                    appendLine("${failure.path}\t${failure.message}")
                }
            }
        )
        File(directory, "allowlisted-$prefix.txt").writeText(
            buildString {
                for (failure in early.allowedFiles) {
                    appendLine("${failure.path}\t${failure.message}")
                }
            }
        )
        File(directory, "failures-$prefix-crashes.txt").writeText(
            buildString {
                for (failure in early.crashSamples) {
                    appendLine("${failure.path}\t${failure.message}")
                }
            }
        )
    }

    fun printSummary() {
        println("test262-parser-tests 解析一致性基线")
        println("  脚本目标：")
        for (category in categories) {
            println(
                "    ${category.name}: ${category.passed}/${category.total} " +
                    "(${formatPercent(category.passRate)}), 失败 ${category.failed}, " +
                    "允许 ${category.allowed}, 崩溃 ${category.crashes}, ${category.elapsedMillis}ms"
            )
        }
        println(
            "    early: 解析成功 ${early.parsed}/${early.total}, " +
                "拒绝 ${early.rejected}, 允许 ${early.allowedFiles.size}, " +
                "意外接受 ${early.unexpectedAcceptedFiles.size}, 崩溃 ${early.crashes}"
        )
        println("  模块目标（.module.js）：")
        for (category in moduleCategories) {
            println(
                "    ${category.name}: ${category.passed}/${category.total} " +
                    "(${formatPercent(category.passRate)}), 失败 ${category.failed}, " +
                    "允许 ${category.allowed}, 崩溃 ${category.crashes}, ${category.elapsedMillis}ms"
            )
        }
        println(
            "    early-module: 解析成功 ${moduleEarly.parsed}/${moduleEarly.total}, " +
                "拒绝 ${moduleEarly.rejected}, 允许 ${moduleEarly.allowedFiles.size}, " +
                "意外接受 ${moduleEarly.unexpectedAcceptedFiles.size}, 崩溃 ${moduleEarly.crashes}"
        )
        println("  模块合计: $moduleTotal")
        println(
            "  模块允许列表: ${moduleAllowlist.size} 条，本次命中 ${usedModuleAllowlistPaths.size} 条"
        )
        if (early.unexpectedAcceptedFiles.isNotEmpty()) {
            println("  警告：检测到 ${early.unexpectedAcceptedFiles.size} 个未列入允许列表的 early 接受项")
        }
        if (moduleEarly.unexpectedAcceptedFiles.isNotEmpty()) {
            println(
                "  警告：检测到 ${moduleEarly.unexpectedAcceptedFiles.size} 个未列入允许列表的" +
                    "模块 early 接受项"
            )
        }
        if (crashCount > 0) {
            println("  警告：检测到 $crashCount 个崩溃，详见报告")
        }
    }
}

/** 把一组类别写成 JSON 数组元素（调用方负责外层 `[` `]`）。 */
private fun StringBuilder.appendCategoryListJson(categories: List<CategoryReport>) {
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
}

/** 把早期错误报告写成 JSON 对象内容（调用方负责外层 `{` `}`）。 */
private fun StringBuilder.appendEarlyJson(early: EarlyReport) {
    appendLine("    \"name\": \"${jsonEscape(early.name)}\",")
    appendLine("    \"total\": ${early.total},")
    appendLine("    \"parsed\": ${early.parsed},")
    appendLine("    \"rejected\": ${early.rejected},")
    appendLine("    \"allowed\": ${early.allowedFiles.size},")
    appendLine("    \"unexpectedAccepted\": ${early.unexpectedAcceptedFiles.size},")
    appendLine("    \"crashes\": ${early.crashes},")
    appendLine("    \"elapsedMillis\": ${early.elapsedMillis},")
    appendLine("    \"allowlistedPaths\": [")
    early.allowedFiles.forEachIndexed { index, failure ->
        appendLine(
            "      \"${jsonEscape(failure.path)}\"" +
                if (index < early.allowedFiles.lastIndex) "," else ""
        )
    }
    appendLine("    ],")
    appendLine("    \"unexpectedAcceptedPaths\": [")
    early.unexpectedAcceptedFiles.forEachIndexed { index, failure ->
        appendLine(
            "      \"${jsonEscape(failure.path)}\"" +
                if (index < early.unexpectedAcceptedFiles.lastIndex) "," else ""
        )
    }
    appendLine("    ]")
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
