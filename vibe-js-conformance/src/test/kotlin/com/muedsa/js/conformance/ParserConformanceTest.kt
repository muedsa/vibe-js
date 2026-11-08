package com.muedsa.js.conformance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 解析一致性运行器的选择器、目标判定与门禁统计测试。
 *
 * 这些规则决定“哪些文件进入基线、按脚本还是模块解析、失败是否阻断 strict”，
 * 一旦误判会直接掩盖模块缺口，因此用纯函数/临时目录测试覆盖，不依赖完整语料。
 */
class ParserConformanceTest {

    @Test
    fun `模块语料按文件名后缀选择解析目标`() {
        assertEquals(ParseGoal.MODULE, parseGoalFor("abc.module.js"))
        assertEquals(ParseGoal.SCRIPT, parseGoalFor("abc.js"))
        // 只有精确的 .module.js 后缀才走模块目标
        assertEquals(ParseGoal.SCRIPT, parseGoalFor("abc.module.js.js"))
    }

    @Test
    fun `目录选择器分别返回脚本与模块文件`() {
        val directory = Files.createTempDirectory("parser-conformance-selection").toFile()
        try {
            File(directory, "b.js").writeText("var b = 1;")
            File(directory, "a.module.js").writeText("export const a = 1;")
            File(directory, "notes.txt").writeText("ignored")
            assertEquals(
                listOf("b.js"),
                selectParserFiles(directory, ParseGoal.SCRIPT).map { it.name },
            )
            assertEquals(
                listOf("a.module.js"),
                selectParserFiles(directory, ParseGoal.MODULE).map { it.name },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `模块目标按 parseModule 解析而脚本目标拒绝模块语法`() {
        assertIs<ParseAttempt.Success>(attemptParse(ParseGoal.MODULE, "export const a = 1;"))
        assertIs<ParseAttempt.Rejected>(attemptParse(ParseGoal.SCRIPT, "export const a = 1;"))
        assertIs<ParseAttempt.Rejected>(attemptParse(ParseGoal.MODULE, "import {b,,c} from 'a';"))
        // 模块恒为严格模式：with 被拒绝
        assertIs<ParseAttempt.Rejected>(attemptParse(ParseGoal.MODULE, "with (a) {}"))
        // Annex B HTML 注释只在脚本目标允许
        assertIs<ParseAttempt.Rejected>(attemptParse(ParseGoal.MODULE, "<!-- x\nvar a;"))
        assertIs<ParseAttempt.Success>(attemptParse(ParseGoal.SCRIPT, "<!-- x\nvar a;"))
        // 顶层 await 合法；本地导出的绑定必须已声明
        assertIs<ParseAttempt.Success>(attemptParse(ParseGoal.MODULE, "await 1;"))
        assertIs<ParseAttempt.Success>(attemptParse(ParseGoal.MODULE, "export {a}; var a = 1;"))
        assertIs<ParseAttempt.Rejected>(attemptParse(ParseGoal.MODULE, "export {a};"))
    }

    @Test
    fun `模块允许列表必须逐条给出路径与原因`() {
        val entries = parseParserModuleAllowlist(
            """
            # 注释行
            pass/a.module.js	语料标记与现行规范不一致
            early/b.module.js	尚未实现该早期错误
            """.trimIndent()
        )
        assertEquals(
            listOf(
                ParserAllowlistEntry("pass/a.module.js", "语料标记与现行规范不一致"),
                ParserAllowlistEntry("early/b.module.js", "尚未实现该早期错误"),
            ),
            entries,
        )
        // 缺少原因、路径非模块文件、重复路径都必须拒绝，避免清单悄悄失效
        assertFailsWith<IllegalArgumentException> {
            parseParserModuleAllowlist("pass/a.module.js")
        }
        assertFailsWith<IllegalArgumentException> {
            parseParserModuleAllowlist("pass/a.module.js\t")
        }
        assertFailsWith<IllegalArgumentException> {
            parseParserModuleAllowlist("pass/a.js\t原因")
        }
        assertFailsWith<IllegalArgumentException> {
            parseParserModuleAllowlist("pass/a.module.js\t原因\npass/a.module.js\t另一个原因")
        }
    }

    @Test
    fun `模块失败计入 strict 门禁而全部通过时门禁放行`() {
        val clean = report()
        assertFalse(clean.hasExpectedFailures())
        assertEquals(262, clean.moduleTotal)
        assertEquals(1907 + 1907 + 682 + 605 + 262, clean.total)

        // 模块正例失败、模块负例被接受、模块 early 被接受都必须让门禁失败
        assertTrue(
            report(
                moduleCategories = listOf(category("pass-module", total = 76, passed = 75, failed = 1)),
            ).hasExpectedFailures()
        )
        assertTrue(
            report(
                moduleCategories = listOf(category("fail-module", total = 47, passed = 46, failed = 1)),
            ).hasExpectedFailures()
        )
        assertTrue(
            report(
                moduleEarly = early(
                    unexpectedAccepted = listOf(TestFailure("early/a.module.js", "接受")),
                ),
            ).hasExpectedFailures()
        )
    }

    @Test
    fun `模块允许列表会写入报告并标记命中情况`() {
        val directory = Files.createTempDirectory("parser-conformance-report").toFile()
        try {
            val report = report(
                moduleCategories = listOf(
                    category("pass-module", total = 76, passed = 75, failed = 0).copy(
                        allowed = 1,
                        allowedFailures = listOf(TestFailure("pass/a.module.js", "已知差异")),
                    ),
                    category("pass-explicit-module", total = 76, passed = 76, failed = 0),
                    category("fail-module", total = 47, passed = 47, failed = 0),
                ),
                moduleAllowlist = listOf(
                    ParserAllowlistEntry("pass/a.module.js", "语料过期 | 待复核"),
                    ParserAllowlistEntry("early/b.module.js", "尚未实现"),
                ),
            )
            report.writeMarkdown(File(directory, "summary.md"))
            report.writeJson(File(directory, "summary.json"))
            val markdown = File(directory, "summary.md").readText()
            // 命中与未命中条目都进入可审计表格，原因中的竖线需转义
            assertTrue(markdown.contains("| `pass/a.module.js` | 语料过期 \\| 待复核 | 是 |"))
            assertTrue(markdown.contains("| `early/b.module.js` | 尚未实现 | 否 |"))
            val json = File(directory, "summary.json").readText()
            assertTrue(json.contains("\"path\": \"pass/a.module.js\""))
            assertTrue(json.contains("\"reason\": \"语料过期 | 待复核\""))
            assertTrue(json.contains("\"hit\": true"))
            assertTrue(json.contains("\"hit\": false"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun category(name: String, total: Int, passed: Int, failed: Int) = CategoryReport(
        name = name,
        total = total,
        passed = passed,
        failed = failed,
        crashes = 0,
        failures = if (failed > 0) listOf(TestFailure("$name/failure", "失败")) else emptyList(),
        elapsedMillis = 0,
    )

    private fun early(unexpectedAccepted: List<TestFailure> = emptyList()) = EarlyReport(
        name = "early-module",
        total = 63,
        parsed = unexpectedAccepted.size,
        rejected = 63 - unexpectedAccepted.size,
        crashes = 0,
        crashSamples = emptyList(),
        acceptedFiles = unexpectedAccepted.map { it.path },
        allowedFiles = emptyList(),
        unexpectedAcceptedFiles = unexpectedAccepted,
        elapsedMillis = 0,
    )

    private fun report(
        moduleCategories: List<CategoryReport> = listOf(
            category("pass-module", total = 76, passed = 76, failed = 0),
            category("pass-explicit-module", total = 76, passed = 76, failed = 0),
            category("fail-module", total = 47, passed = 47, failed = 0),
        ),
        moduleEarly: EarlyReport = early(),
        moduleAllowlist: List<ParserAllowlistEntry> = emptyList(),
    ) = ParserRunReport(
        generatedAt = "test",
        corpus = "corpus",
        categories = listOf(
            category("pass", total = 1907, passed = 1907, failed = 0),
            category("pass-explicit", total = 1907, passed = 1907, failed = 0),
            category("fail", total = 682, passed = 674, failed = 0),
        ),
        early = EarlyReport(
            name = "early",
            total = 605,
            parsed = 5,
            rejected = 600,
            crashes = 0,
            crashSamples = emptyList(),
            acceptedFiles = emptyList(),
            allowedFiles = emptyList(),
            unexpectedAcceptedFiles = emptyList(),
            elapsedMillis = 0,
        ),
        moduleCategories = moduleCategories,
        moduleEarly = moduleEarly,
        moduleAllowlist = moduleAllowlist,
    )
}
