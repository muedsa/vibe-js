package com.muedsa.js.conformance

import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Test262 语义运行器。
 *
 * 当前范围：
 * - `--suite es5`：test/language 与 test/built-ins 中带 `es5id` 的脚本；
 * - `--suite es2015`：非 ES5、特性全部受支持的用例（`--include-esid` 时纳入 esid 标注用例）；
 * - `--suite es2016plus`：非 ES5、特性全部受支持且命中 ES2016+ 特性种子的用例；
 * - `--suite module`：带 `flags: [module]` 的模块用例，使用文件型 Provider 与 `Engine.runModule`；
 * - 支持 includes、negative（parse/runtime）、strict/sloppy/raw 变体与 `flags: [async]`；
 * - module、未支持特性与部分 `$262` 宿主钩子由 `Test262Selection` 跳过并记录原因；
 * - 核心库已提供 Reflect/Proxy，运行器不再注入 Reflect 垫片；
 * - 每个变体使用独立 Engine，加载 sta.js/assert.js 与 metadata includes。
 */
fun main(args: Array<String>) {
    val corpusDir = File(args.getOrElse(0) { "corpus/test262" })
    val reportDir = File(args.getOrElse(1) { "build/reports/conformance" })
    val strict = args.contains("--strict")
    // --suite es5（默认）、es2015（按支持特性筛选）、es2016plus（后 ES2015 特性种子）或 module（模块执行）
    val suite = args.indexOf("--suite").takeIf { it >= 0 }?.let { index ->
        args.getOrNull(index + 1)
    } ?: "es5"
    if (suite !in setOf("es5", "es2015", "es2016plus", "module", "sourcephase")) {
        System.err.println("未知的 Test262 子集：$suite（可选 es5 / es2015 / es2016plus / module / sourcephase）")
        exitProcess(3)
    }
    val limit = args.indexOf("--limit").takeIf { it >= 0 }?.let { index ->
        args.getOrNull(index + 1)?.toIntOrNull()
    }
    val jobs = args.indexOf("--jobs").takeIf { it >= 0 }?.let { index ->
        args.getOrNull(index + 1)?.toIntOrNull()
    }
    // --filter 用于本地定向调试：按相对路径子串筛选，并输出完整 JS 调用栈
    val filter = args.indexOf("--filter").takeIf { it >= 0 }?.let { index ->
        args.getOrNull(index + 1)
    }
    // 可选：把无 features 的 esid 用例也纳入 ES2015 子集（用于扩展分析，默认关闭）
    val includeEsid = args.contains("--include-esid")
    // 用例超时/清理时限/步数配额，均可通过命令行覆盖
    val settings = RunnerSettings.fromArgs(args)

    if (!File(corpusDir, "test").isDirectory || !File(corpusDir, "harness").isDirectory) {
        System.err.println(
            "找不到 Test262 语料：${corpusDir.absolutePath}\n" +
                "请先执行 ./gradlew :vibe-js-conformance:downloadTest262Corpus"
        )
        exitProcess(3)
    }

    // 元数据在进入运行前收集：报告必须能回答"哪份引擎、哪份语料、什么配置"
    val metadata = RunMetadata.collect(
        corpusDir = corpusDir,
        args = args,
        runnerConfig = mapOf(
            "suite" to suite,
            "filter" to (filter ?: ""),
            "limit" to (limit?.toString() ?: ""),
            "jobs" to (jobs?.toString() ?: ""),
            "includeEsid" to includeEsid.toString(),
            "caseTimeoutMs" to settings.caseTimeoutMillis.toString(),
            "cleanupTimeoutMs" to settings.cleanupTimeoutMillis.toString(),
            "maxSteps" to (settings.limits.maxSteps?.toString() ?: "unlimited"),
        ),
    )

    val report = runBlocking {
        Test262Runner(corpusDir, limit, jobs, filter, suite, includeEsid, settings, metadata).run()
    }
    reportDir.mkdirs()
    report.writeMarkdown(File(reportDir, "test262-summary.md"))
    report.writeJson(File(reportDir, "test262-summary.json"))
    report.writeFailureLists(reportDir)
    report.writeSkippedList(reportDir)
    report.printSummary()

    if (report.crashedRuns > 0) {
        exitProcess(2)
    }
    if (strict && report.executedRuns == 0) {
        System.err.println("Test262 未执行任何用例，严格模式判定失败")
        exitProcess(1)
    }
    if (strict && report.failedRuns > 0) {
        exitProcess(1)
    }
}

private class Test262Runner(
    private val corpusDir: File,
    private val limit: Int?,
    private val jobs: Int?,
    private val filter: String? = null,
    private val suite: String = "es5",
    private val includeEsid: Boolean = false,
    private val settings: RunnerSettings = RunnerSettings(),
    private val metadata: RunMetadata,
) {

    private val harnessDir = File(corpusDir, "harness")

    /** 用例执行器：统一负责引擎创建、超时、分类与关闭。 */
    private val runtime = CaseRuntime(settings)

    /**
     * harness 文件只读取一次，供所有用例复用。
     *
     * 当前只对 regExpUtils.js 做注入：属性转义语料需要构造上百万码点的字符串，
     * 解释执行原实现过慢，改为调用运行器注册的宿主实现。
     */
    private val harnessSources: Map<String, String> by lazy {
        harnessDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".js") }
            ?.associate { file ->
                var source = file.readText().removePrefix("\uFEFF")
                if (file.name == "regExpUtils.js") {
                    // 属性转义语料需要构造上百万码点的字符串；解释执行原实现过慢，
                    // 改为调用 runner 注入的宿主实现（语义保持一致）
                    source += "\nfunction buildString(args) { return __buildTest262String(args); }\n"
                }
                file.name to source
            }
            ?: emptyMap()
    }

    suspend fun run(): Test262Report = coroutineScope {
        val startedAt = System.nanoTime()
        val selection = collectTests()
        val tests = selection.selected
        val selected = if (limit != null && limit < tests.size) {
            tests.take(limit)
        } else {
            tests
        }
        val parallelism = (jobs ?: Runtime.getRuntime().availableProcessors())
            .coerceIn(1, 16)
        val completed = AtomicInteger()

        // 有界工作队列：只创建 parallelism 个 worker 协程，由它们按索引领取任务。
        // 旧实现会为全部选中用例（两万以上）一次性创建协程，内存与调度开销都随语料线性增长。
        val outcomes = arrayOfNulls<TestOutcome>(selected.size)
        val nextIndex = AtomicInteger(0)
        coroutineScope {
            repeat(parallelism) {
                launch(Dispatchers.Default) {
                    while (true) {
                        val index = nextIndex.getAndIncrement()
                        if (index >= selected.size) return@launch
                        outcomes[index] = runOne(selected[index])
                        val finished = completed.incrementAndGet()
                        if (finished % 500 == 0) {
                            println("已执行 $finished/${selected.size} 个测试文件...")
                        }
                    }
                }
            }
        }
        val ordered = outcomes.map { checkNotNull(it) { "用例结果缺失" } }

        var passedRuns = 0
        var failedRuns = 0
        var crashedRuns = 0
        var executedRuns = 0
        var skippedTests = 0
        val skipReasons = LinkedHashMap<String, Int>()
        val failureKinds = LinkedHashMap<String, Int>()
        val failures = mutableListOf<RunRecord>()
        val allResults = mutableListOf<RunRecord>()
        val skippedRecords = mutableListOf<SkipRecord>()
        val textMatchExclusions = mutableListOf<SkipRecord>()

        for (outcome in ordered) {
            when (outcome) {
                is TestOutcome.Skipped -> {
                    skippedTests++
                    skipReasons[outcome.reason] = (skipReasons[outcome.reason] ?: 0) + 1
                    // 逐条落盘，便于审计文本筛选是否误排（见 test262-skipped.txt）
                    val record = SkipRecord(outcome.path, outcome.reason, outcome.textMatchOnly)
                    skippedRecords.add(record)
                    if (outcome.textMatchOnly) {
                        textMatchExclusions.add(record)
                    }
                }

                is TestOutcome.Executed -> {
                    executedRuns += outcome.results.size
                    allResults.addAll(outcome.results)
                    for (result in outcome.results) {
                        when (result.status) {
                            Status.PASS -> passedRuns++
                            Status.FAIL -> failedRuns++
                            Status.CRASH -> crashedRuns++
                        }
                        if (result.status != Status.PASS) {
                            failures.add(result)
                            // 分类计数：区分断言失败、超时、清理失败等，便于判断是引擎缺陷还是运行器失控
                            val label = result.kind?.label ?: "未分类"
                            failureKinds[label] = (failureKinds[label] ?: 0) + 1
                        }
                    }
                }
            }
        }

        Test262Report(
            generatedAt = Instant.now().toString(),
            metadata = metadata,
            suite = suite,
            corpus = corpusDir.absolutePath,
            selectionExcluded = selection.excluded,
            selectedTests = selected.size,
            executedRuns = executedRuns,
            passedRuns = passedRuns,
            failedRuns = failedRuns,
            crashedRuns = crashedRuns,
            skippedTests = skippedTests,
            skipReasons = skipReasons,
            skippedRecords = skippedRecords.sortedBy { it.path },
            textMatchExclusions = textMatchExclusions.sortedBy { it.path },
            harnessShims = activeHarnessShims(),
            failureKinds = failureKinds,
            failures = failures.sortedWith(
                compareBy({ it.path }, { it.variant })
            ),
            slowest = allResults.sortedByDescending { it.elapsedMillis }.take(20),
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    /**
     * 列出本次实际生效的 harness 替换与注入。
     *
     * 这些改写会影响"测试到底验证了什么"，必须在报告里单列，而不是只写在文档里。
     * Reflect/Proxy 已由核心内建直接提供，运行器不再注入垫片，因此本节不再列出。
     */
    private fun activeHarnessShims(): List<String> = listOf(
        "\$262.evalScript 宿主钩子：同一 realm 内执行新 Script",
        "\$262.detachArrayBuffer 宿主钩子：把 ArrayBuffer 置为分离（detach）状态",
        "\$DONE 注入：驱动 flags: [async] 用例",
        "regExpUtils.buildString：改由宿主构造大码点字符串",
        "严格模式前缀：strict 变体在源码前插入 \"use strict\"",
    )

    private suspend fun runOne(test: TestFile): TestOutcome {
        Test262Selection.skipDecision(test.source, test.metadata, suite, test.relativePath)
            ?.let { return TestOutcome.Skipped(test.relativePath, it.reason, it.textMatchOnly) }
        val includes = loadIncludes(test.metadata.includes)
            ?: return TestOutcome.Skipped(test.relativePath, "missing include")
        val results = variants(test.metadata.flags).map { variant ->
            val result = execute(test, variant, includes)
            RunRecord(
                path = test.relativePath,
                variant = variant.name,
                status = result.status,
                kind = result.kind,
                message = result.message ?: "",
                elapsedMillis = result.elapsedMillis,
            )
        }
        return TestOutcome.Executed(results)
    }

    /**
     * 收集当前子集的用例，并统计筛选阶段被排除的数量。
     *
     * "筛选期排除"与"选中后跳过"是两件事：前者说明用例不在本次口径内，
     * 后者说明口径内但当前能力不支持。报告必须分开呈现，否则容易被误读成全仓缺口。
     */
    private fun collectTests(): SelectionStats {
        val selected = mutableListOf<TestFile>()
        val excluded = LinkedHashMap<String, Int>()
        val roots = when (suite) {
            "es2015", "es2016plus" -> listOf("test/language", "test/built-ins", "test/annexB")
            "sourcephase" -> listOf("test/language", "test/built-ins", "test/staging")
            else -> listOf("test/language", "test/built-ins")
        }
        for (rootName in roots) {
            val root = File(corpusDir, rootName)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "js" }
                .filterNot { it.name.endsWith("_FIXTURE.js") }
                .filterNot {
                    suite != "sourcephase" &&
                        it.path.contains("${File.separator}staging${File.separator}")
                }
                .sortedBy { it.relativeTo(corpusDir).invariantSeparatorsPath }
                .forEach { file ->
                    val relativePath = file.relativeTo(corpusDir).invariantSeparatorsPath
                    val (metadata, body, prefix) = parseTestFile(file)
                    val decision = Test262Selection.decide(
                        relativePath = relativePath,
                        metadata = metadata,
                        suite = suite,
                        includeEsid = includeEsid,
                        filter = filter,
                    )
                    when (decision) {
                        is SelectionDecision.Selected -> {
                            val parsed = checkNotNull(metadata) { "选中的用例必须有 metadata" }
                            // hashbang 用例的可执行前缀位于 metadata 之前，需保留
                            val source = if ("hashbang" in parsed.features) prefix + body else body
                            selected.add(
                                TestFile(
                                    file = file,
                                    relativePath = relativePath,
                                    metadata = parsed,
                                    source = source,
                                )
                            )
                        }

                        is SelectionDecision.Excluded ->
                            excluded[decision.reason] = (excluded[decision.reason] ?: 0) + 1
                    }
                }
        }
        return SelectionStats(selected, excluded)
    }

    private fun parseTestFile(file: File): Triple<Metadata?, String, String> {
        val source = file.readText().removePrefix("\uFEFF")
        val start = source.indexOf("/*---")
        if (start < 0) return Triple(null, source, "")
        val end = source.indexOf("---*/", start + 5)
        if (end < 0) return Triple(null, source, "")
        val metadata = parseMetadata(source.substring(start + 5, end))
        val body = source.substring(end + 5).trimStart('\r', '\n')
        return Triple(metadata, body, source.substring(0, start))
    }

    private fun parseMetadata(raw: String): Metadata {
        var es5Id: String? = null
        var es6Id: String? = null
        var esId: String? = null
        val includes = mutableListOf<String>()
        val flags = mutableSetOf<String>()
        val features = mutableSetOf<String>()
        var negativePhase: String? = null
        var negativeType: String? = null
        var currentKey: String? = null

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val trimmed = line.trimStart()
            if (trimmed.startsWith("#")) continue
            val indent = line.length - trimmed.length
            if (indent == 0) {
                val colon = trimmed.indexOf(':')
                if (colon <= 0) {
                    currentKey = null
                    continue
                }
                val key = trimmed.substring(0, colon).trim()
                val value = trimmed.substring(colon + 1).trim()
                currentKey = key
                when (key) {
                    "es5id" -> es5Id = value.trim('\'', '"')
                    "es6id" -> es6Id = value.trim('\'', '"')
                    "esid" -> esId = value.trim('\'', '"')
                    "includes" -> includes.addAll(parseInlineList(value))
                    "flags" -> flags.addAll(parseInlineList(value))
                    "features" -> features.addAll(parseInlineList(value))
                    else -> Unit
                }
            } else {
                when (currentKey) {
                    "includes" -> if (trimmed.startsWith("-")) {
                        includes.add(trimmed.removePrefix("-").trim().trim('\'', '"'))
                    }

                    "flags" -> if (trimmed.startsWith("-")) {
                        flags.add(trimmed.removePrefix("-").trim().trim('\'', '"'))
                    }

                    "features" -> if (trimmed.startsWith("-")) {
                        features.add(trimmed.removePrefix("-").trim().trim('\'', '"'))
                    }

                    "negative" -> when {
                        trimmed.startsWith("phase:") ->
                            negativePhase = trimmed.substringAfter(':').trim().trim('\'', '"')

                        trimmed.startsWith("type:") ->
                            negativeType = trimmed.substringAfter(':').trim().trim('\'', '"')
                    }

                    else -> Unit
                }
            }
        }
        return Metadata(
            es5Id = es5Id,
            es6Id = es6Id,
            esId = esId,
            includes = includes,
            flags = flags,
            features = features,
            negativePhase = negativePhase,
            negativeType = negativeType,
        )
    }

    private fun parseInlineList(value: String): List<String> {
        val trimmed = value.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return emptyList()
        return trimmed.substring(1, trimmed.length - 1)
            .split(',')
            .map { it.trim().trim('\'', '"') }
            .filter { it.isNotEmpty() }
    }


    private fun loadIncludes(names: List<String>): List<String>? {
        val allNames = LinkedHashSet<String>()
        allNames.add("sta.js")
        allNames.add("assert.js")
        allNames.addAll(names)
        val contents = mutableListOf<String>()
        for (name in allNames) {
            contents.add(harnessSources[name] ?: return null)
        }
        return contents
    }

    private fun variants(flags: Set<String>): List<Variant> {
        return when {
            "module" in flags -> listOf(Variant("module", injectStrict = false))
            "raw" in flags -> listOf(Variant("raw", injectStrict = false))
            "onlyStrict" in flags -> listOf(Variant("strict", injectStrict = true))
            "noStrict" in flags -> listOf(Variant("sloppy", injectStrict = false))
            else -> listOf(
                Variant("sloppy", injectStrict = false),
                Variant("strict", injectStrict = true),
            )
        }
    }

    private suspend fun execute(
        test: TestFile,
        variant: Variant,
        includes: List<String>,
    ): ExecutionResult {
        val startedAt = System.nanoTime()
        val source = if (variant.injectStrict) "\"use strict\";\n${test.source}" else test.source
        val negativePhase = test.metadata.negativePhase

        if ("module" in test.metadata.flags) {
            if (negativePhase == "parse") {
                return try {
                    Parser(Lexer(source, moduleContext = true).tokenize()).parseModule()
                    fail("期望解析失败，但模块解析器接受了该文件", startedAt)
                } catch (_: ParseException) {
                    pass(startedAt)
                } catch (_: LexerException) {
                    pass(startedAt)
                } catch (error: Throwable) {
                    crash("模块解析崩溃：${error.describe()}", startedAt)
                }
            }
            val run = runtime.withEngine { engine ->
                executeModuleInEngine(engine, test, source, includes, startedAt)
            }
            val result = when (val outcome = run.outcome) {
                is CaseOutcome.Success -> outcome.value
                is CaseOutcome.Failure -> crash(
                    message = "${outcome.kind.label}：${outcome.message}",
                    startedAt = startedAt,
                    kind = outcome.kind,
                )
            }
            return run.cleanupFailure?.let { cleanup ->
                crash(
                    message = "清理失败：$cleanup；原结果=${result.status}" +
                        (result.message?.takeIf { it.isNotEmpty() }?.let { "：$it" } ?: ""),
                    startedAt = startedAt,
                    kind = FailureKind.CLEANUP,
                )
            } ?: result
        }

        if (negativePhase == "parse") {
            return try {
                parse(source)
                fail("期望解析失败，但解析器接受了该文件", startedAt)
            } catch (_: ParseException) {
                pass(startedAt)
            } catch (_: LexerException) {
                pass(startedAt)
            } catch (error: Throwable) {
                crash("解析崩溃：${error.describe()}", startedAt)
            }
        }

        // 用例在独立引擎中执行；创建、超时、分类与关闭统一交给 CaseRuntime。
        // 这样"用例超时"和"引擎清理失败"不会再被混进普通断言失败。
        val run = runtime.withEngine { engine ->
            executeInEngine(engine, test, source, includes, startedAt)
        }
        val result = when (val outcome = run.outcome) {
            is CaseOutcome.Success -> outcome.value
            is CaseOutcome.Failure -> crash(
                message = "${outcome.kind.label}：${outcome.message}",
                startedAt = startedAt,
                kind = outcome.kind,
            )
        }
        // 清理失败单独升级为 CRASH：引擎没能在时限内释放本身是运行器缺陷，
        // 但原始结果保留在消息里，便于区分"用例通过但清理失败"。
        return run.cleanupFailure?.let { cleanup ->
            crash(
                message = "清理失败：$cleanup；原结果=${result.status}" +
                    (result.message?.takeIf { it.isNotEmpty() }?.let { "：$it" } ?: ""),
                startedAt = startedAt,
                kind = FailureKind.CLEANUP,
            )
        } ?: result
    }

    /**
     * 模块用例：在独立 Engine/Provider 中执行入口模块。
     *
     * includes 仍按 Test262 语义作为经典脚本先执行；模块源码本身走 `Engine.runModule`。
     */
    @OptIn(ExperimentalModuleApi::class)
    private suspend fun executeModuleInEngine(
        engine: Engine,
        test: TestFile,
        source: String,
        includes: List<String>,
        startedAt: Long,
    ): ExecutionResult {
        val negativePhase = test.metadata.negativePhase
        registerBuildString(engine)
        registerDollar262(engine)
        val asyncDone = if ("async" in test.metadata.flags) CompletableDeferred<JSValue>() else null
        if (asyncDone != null) {
            engine.globalObject.defineDataProperty(
                key = PropertyKey.Str("\$DONE"),
                value = JSNativeFunction(
                    name = "\$DONE",
                    length = 1,
                    prototype = engine.intrinsics.functionPrototype,
                ) { _, _, args ->
                    if (!asyncDone.isCompleted) {
                        asyncDone.complete(args.getOrElse(0) { JSUndefined })
                    }
                    JSUndefined
                },
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
        return try {
            for (include in includes) {
                engine.run(include)
            }
            val entry = ModuleSource(
                id = test.relativePath,
                source = source,
                sourceName = test.relativePath,
                url = test.file.toURI().toString(),
            )
            val provider = Test262ModuleProvider(corpusDir, test.file)
            try {
                engine.runModule(entry, provider)
                if (negativePhase == "resolution") {
                    fail("期望模块解析/链接失败，但模块图正常链接", startedAt)
                } else if (negativePhase == "runtime") {
                    fail("期望抛出 ${test.metadata.negativeType ?: "Error"}，但模块正常结束", startedAt)
                } else if (asyncDone != null) {
                    if (!asyncDone.isCompleted) {
                        fail("异步模块用例未调用 \$DONE", startedAt)
                    } else {
                        val asyncError = asyncDone.await()
                        if (engine.interpreter.toBoolean(asyncError)) {
                            fail("异步失败：${asyncError.describe()}", startedAt)
                        } else {
                            pass(startedAt)
                        }
                    }
                } else {
                    pass(startedAt)
                }
            } catch (error: ModuleLinkException) {
                if (negativePhase == "resolution") {
                    val expected = test.metadata.negativeType
                    if (expected == null || expected == "SyntaxError") pass(startedAt)
                    else fail("期望 $expected，实际链接错误：${error.message}", startedAt)
                } else {
                    fail("模块链接失败：${error.message}", startedAt)
                }
            }
        } catch (error: JSException) {
            if (negativePhase == "runtime") {
                val expected = test.metadata.negativeType
                val actual = errorName(error.value)
                if (expected != null && actual != null && expected == actual) {
                    pass(startedAt)
                } else {
                    fail(
                        "期望抛出 $expected，实际抛出 ${actual ?: error.value.describe()}：${error.value.describe()}",
                        startedAt,
                    )
                }
            } else {
                fail(if (filter != null) error.toString() else error.value.describe(), startedAt)
            }
        } catch (error: ParseException) {
            // module 依赖的早期语法错误发生在 resolution/instantiation 前，
            // Test262 的 resolution 负数用例同样以 SyntaxError 期望它。
            if (negativePhase == "resolution") pass(startedAt)
            else fail("SyntaxError: ${error.describe()}", startedAt, FailureKind.PARSE)
        } catch (error: LexerException) {
            if (negativePhase == "resolution") pass(startedAt)
            else fail("SyntaxError: ${error.describe()}", startedAt, FailureKind.PARSE)
        }
    }

    /**
     * 在已创建的引擎中执行单个用例。
     *
     * 只判定 JS 语义层面的结果（断言、负数用例、异步 `$DONE`）；
     * 步数耗尽、超时、宿主取消与内部异常一律向上抛出，由 [CaseRuntime] 分类。
     */
    @OptIn(ExperimentalModuleApi::class)
    private suspend fun executeInEngine(
        engine: Engine,
        test: TestFile,
        source: String,
        includes: List<String>,
        startedAt: Long,
    ): ExecutionResult {
        val negativePhase = test.metadata.negativePhase
        // 属性转义语料的 buildString 由宿主实现，避免在解释器中逐码点构造字符串
        registerBuildString(engine)
        registerDollar262(engine)
        // 脚本目标中的 import.source() 需要宿主模块解析；普通脚本不使用该运行时。
        engine.installModuleProvider(Test262ModuleProvider(corpusDir, test.file))
        // flags: [async] 的用例通过 $DONE 报告结果；runner 注入最小实现
        val asyncDone = if ("async" in test.metadata.flags) CompletableDeferred<JSValue>() else null
        if (asyncDone != null) {
            engine.globalObject.defineDataProperty(
                key = PropertyKey.Str("\$DONE"),
                value = JSNativeFunction(
                    name = "\$DONE",
                    length = 1,
                    prototype = engine.intrinsics.functionPrototype,
                ) { _, _, args ->
                    if (!asyncDone.isCompleted) {
                        asyncDone.complete(args.getOrElse(0) { JSUndefined })
                    }
                    JSUndefined
                },
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
        return try {
            for (include in includes) {
                engine.run(include)
            }
            // 主用例走预解析路径：解析错误仍在同一 try 内等价抛出，
            // 同时让全部语料覆盖 Engine.run(Program) 的语义
            val program = engine.parse(source)
            engine.run(program)
            if (negativePhase == "runtime") {
                fail("期望抛出 ${test.metadata.negativeType ?: "Error"}，但脚本正常结束", startedAt)
            } else if (asyncDone != null) {
                // run() 返回时微任务队列已清空；未调用 $DONE 视为失败
                if (!asyncDone.isCompleted) {
                    fail("异步用例未调用 \$DONE", startedAt)
                } else {
                    val asyncError = asyncDone.await()
                    if (engine.interpreter.toBoolean(asyncError)) {
                        fail("异步失败：${asyncError.describe()}", startedAt)
                    } else {
                        pass(startedAt)
                    }
                }
            } else {
                pass(startedAt)
            }
        } catch (error: JSException) {
            if (negativePhase == "runtime") {
                val expected = test.metadata.negativeType
                val actual = errorName(error.value)
                if (expected != null && actual != null && expected == actual) {
                    pass(startedAt)
                } else {
                    fail(
                        "期望抛出 $expected，实际抛出 ${actual ?: error.value.describe()}：${error.value.describe()}",
                        startedAt,
                    )
                }
            } else {
                // 定向调试时输出带栈的完整摘要，方便定位内建实现
                fail(if (filter != null) error.toString() else error.value.describe(), startedAt)
            }
        } catch (error: ParseException) {
            fail("SyntaxError: ${error.describe()}", startedAt, FailureKind.PARSE)
        } catch (error: LexerException) {
            fail("SyntaxError: ${error.describe()}", startedAt, FailureKind.PARSE)
        }
        // 其余异常（步数耗尽/取消/内部错误）不再在这里吞掉，交给 CaseRuntime 分类。
    }

    /** 以同一 realm 的新 Script 执行 evalScript 源码。 */
    private fun registerDollar262(engine: Engine) {
        val host = JSObject(prototype = engine.intrinsics.objectPrototype)
        host.defineDataProperty(
            key = PropertyKey.Str("AbstractModuleSource"),
            value = engine.intrinsics.abstractModuleSource,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        host.defineDataProperty(
            key = PropertyKey.Str("evalScript"),
            value = JSNativeFunction(
                name = "evalScript",
                length = 1,
                prototype = engine.intrinsics.functionPrototype,
            ) { interpreter, _, args ->
                val sourceText = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val program = try {
                    Parser(Lexer(sourceText).tokenize(), source = sourceText).parseProgram()
                } catch (error: ParseException) {
                    throw interpreter.syntaxError(error.message ?: "Invalid evalScript source")
                } catch (error: LexerException) {
                    throw interpreter.syntaxError(error.message ?: "Invalid evalScript source")
                }
                engine.interpreter.runProgram(program, engine.context.globalEnvironment)
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        // $262.detachArrayBuffer：Test262 宿主钩子，调用 JSArrayBuffer 的内部 detach 状态。
        // 规范没有把 detach 暴露给 JS，因此只能由运行器通过引擎内部 API 提供；
        // 重复分离保持幂等，方便 harness 的 $DETACHBUFFER 被多次调用。
        host.defineDataProperty(
            key = PropertyKey.Str("detachArrayBuffer"),
            value = JSNativeFunction(
                name = "detachArrayBuffer",
                length = 1,
                prototype = engine.intrinsics.functionPrototype,
            ) { interpreter, _, args ->
                val buffer = args.getOrElse(0) { JSUndefined } as? JSArrayBuffer
                    ?: interpreter.typeError(
                        "\$262.detachArrayBuffer requires an ArrayBuffer argument"
                    )
                buffer.detach()
                JSUndefined
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
        engine.globalObject.defineDataProperty(
            key = PropertyKey.Str("\$262"),
            value = host,
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }

    private fun registerBuildString(engine: Engine) {
        engine.globalObject.defineDataProperty(
            key = PropertyKey.Str("__buildTest262String"),
            value = JSNativeFunction(
                name = "__buildTest262String",
                length = 1,
                prototype = engine.intrinsics.functionPrototype,
            ) { interpreter, _, args ->
                val options = interpreter.toObject(args.getOrElse(0) { JSUndefined })
                val builder = StringBuilder()
                val loneCodePoints = interpreter.getV(options, PropertyKey.Str("loneCodePoints"))
                val loneCount = interpreter.toNumber(
                    interpreter.getV(loneCodePoints, PropertyKey.Str("length"))
                ).toInt()
                for (index in 0 until loneCount) {
                    val codePoint = interpreter.toNumber(
                        interpreter.getV(loneCodePoints, PropertyKey.Str(index.toString()))
                    ).toInt()
                    appendCodePoint(builder, codePoint)
                }
                val ranges = interpreter.getV(options, PropertyKey.Str("ranges"))
                val rangeCount = interpreter.toNumber(
                    interpreter.getV(ranges, PropertyKey.Str("length"))
                ).toInt()
                for (index in 0 until rangeCount) {
                    val range = interpreter.getV(ranges, PropertyKey.Str(index.toString()))
                    val start = interpreter.toNumber(
                        interpreter.getV(range, PropertyKey.Str("0"))
                    ).toLong()
                    val end = interpreter.toNumber(
                        interpreter.getV(range, PropertyKey.Str("1"))
                    ).toLong()
                    var codePoint = start
                    while (codePoint <= end) {
                        appendCodePoint(builder, codePoint.toInt())
                        codePoint++
                    }
                }
                JSString(builder.toString())
            },
            writable = true,
            enumerable = false,
            configurable = true,
        )
    }

    private fun appendCodePoint(builder: StringBuilder, codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            builder.append(codePoint.toChar())
        } else {
            val value = codePoint - 0x10000
            builder.append(((value shr 10) + 0xD800).toChar())
            builder.append(((value and 0x3FF) + 0xDC00).toChar())
        }
    }

    private fun parse(source: String) {
        Parser(Lexer(source).tokenize()).parseProgram()
    }

    private fun errorName(value: JSValue): String? {
        if (value is JSError) return value.errorName
        if (value is JSObject) {
            // 沿原型链查找 name；Test262Error 等自定义构造函数没有 name 属性，
            // 但原型的 constructor 指向同名函数
            var current: JSObject? = value
            while (current != null) {
                (current.getOwnDataValue(PropertyKey.Str("name")) as? JSString)?.let {
                    return it.value
                }
                val constructor = current.getOwnDataValue(PropertyKey.Str("constructor"))
                if (constructor is JSObject) {
                    (constructor.getOwnDataValue(PropertyKey.Str("name")) as? JSString)?.let {
                        return it.value
                    }
                }
                current = current.prototype
            }
        }
        return null
    }

    private fun pass(startedAt: Long): ExecutionResult =
        ExecutionResult(Status.PASS, null, null, elapsedMillis(startedAt))

    private fun fail(
        message: String,
        startedAt: Long,
        kind: FailureKind = FailureKind.ASSERTION,
    ): ExecutionResult =
        ExecutionResult(Status.FAIL, kind, message, elapsedMillis(startedAt))

    private fun crash(
        message: String,
        startedAt: Long,
        kind: FailureKind = FailureKind.INTERNAL,
    ): ExecutionResult =
        ExecutionResult(Status.CRASH, kind, message, elapsedMillis(startedAt))

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

}


private data class TestFile(
    val file: File,
    val relativePath: String,
    val metadata: Metadata,
    val source: String,
)

/**
 * Test262 module 用例的宿主 Provider。
 *
 * 只允许读取语料根目录内的相对模块；fixture 与动态 import 都走同一解析规则。
 */
@OptIn(ExperimentalModuleApi::class)
private class Test262ModuleProvider(
    private val corpusRoot: File,
    entryFile: File,
) : ModuleProvider {
    private val rootPath = corpusRoot.canonicalFile.toPath()
    private val entryDirectory = entryFile.canonicalFile.parentFile

    override suspend fun load(request: ModuleRequest): ModuleSource? {
        if (request.specifier == "<module source>") {
            return ModuleSource(
                id = "<module source>",
                source = "",
                sourceName = "<module source>",
                moduleSourceClassName = "Module Source",
            )
        }
        if (!request.specifier.startsWith(".")) return null
        val base = request.referrer
            ?.let { File(corpusRoot, it).canonicalFile.parentFile }
            ?: entryDirectory
        val resolved = File(base, request.specifier).canonicalFile
        if (!resolved.toPath().startsWith(rootPath) || !resolved.isFile) return null
        val relativePath = resolved.toRelativeString(corpusRoot.canonicalFile)
            .replace(File.separatorChar, '/')
        val bytes = if (request.attributes["type"] == "bytes") resolved.readBytes() else null
        return ModuleSource(
            id = relativePath,
            source = if (bytes != null) "" else resolved.readText(),
            sourceName = relativePath,
            url = resolved.toURI().toString(),
            bytes = bytes,
        )
    }
}

private data class Variant(
    val name: String,
    val injectStrict: Boolean,
)

private enum class Status {
    PASS,
    FAIL,
    CRASH,
}

private data class ExecutionResult(
    val status: Status,
    /** 失败分类；PASS 时为 null。 */
    val kind: FailureKind?,
    val message: String?,
    val elapsedMillis: Long,
)

private data class RunRecord(
    val path: String,
    val variant: String,
    val status: Status,
    val kind: FailureKind?,
    val message: String,
    val elapsedMillis: Long,
)

private sealed class TestOutcome {
    data class Skipped(
        val path: String,
        val reason: String,
        /** 是否仅由源码文本匹配触发（features 未标注对应特性）。 */
        val textMatchOnly: Boolean = false,
    ) : TestOutcome()
    data class Executed(val results: List<RunRecord>) : TestOutcome()
}

/** 筛选阶段的统计：选中列表 + 各排除原因的数量。 */
private data class SelectionStats(
    val selected: List<TestFile>,
    val excluded: Map<String, Int>,
)

/** 一条被跳过的用例，用于落盘审计文本筛选是否误排。 */
private data class SkipRecord(
    val path: String,
    val reason: String,
    /** 是否仅由源码文本匹配触发。 */
    val textMatchOnly: Boolean = false,
)

private data class Test262Report(
    val generatedAt: String,
    val metadata: RunMetadata,
    val suite: String,
    val corpus: String,
    /** 筛选阶段被排除的数量（按原因）；与"选中后跳过"分开统计。 */
    val selectionExcluded: Map<String, Int>,
    val selectedTests: Int,
    val executedRuns: Int,
    val passedRuns: Int,
    val failedRuns: Int,
    val crashedRuns: Int,
    val skippedTests: Int,
    val skipReasons: Map<String, Int>,
    /** 被跳过的用例明细，写入 test262-skipped.txt 供审计。 */
    val skippedRecords: List<SkipRecord>,
    /** 仅由源码文本匹配排除的用例，写入 test262-text-exclusions.txt 供复核。 */
    val textMatchExclusions: List<SkipRecord>,
    /** 本次生效的 harness 替换与注入说明。 */
    val harnessShims: List<String>,
    /** 失败分类计数（断言失败/超时/步数耗尽/清理失败…），用于区分引擎缺陷与运行器失控。 */
    val failureKinds: Map<String, Int>,
    val failures: List<RunRecord>,
    val slowest: List<RunRecord>,
    val elapsedMillis: Long,
) {
    fun writeMarkdown(file: File) {
        val suiteLabel = when (suite) {
            "es2015" -> "ES2015"
            "es2016plus" -> "ES2016+"
            "module" -> "module"
            "sourcephase" -> "source-phase"
            else -> "ES5"
        }
        file.writeText(
            buildString {
                appendLine("# Test262 $suiteLabel 子集语义基线")
                appendLine()
                appendLine("- 生成时间：$generatedAt")
                appendLine("- 引擎 commit：`${metadata.engineCommit}`")
                appendLine("- 语料 commit：`${metadata.corpusCommit ?: "未知（缺少 .corpus-commit）"}`")
                appendLine("- 工具链：Kotlin ${metadata.kotlinVersion} / Java ${metadata.javaVersion} / " +
                    "Gradle ${metadata.gradleVersion ?: "未知"} / ${metadata.os}")
                appendLine("- 筛选配置：${metadata.runnerConfig.entries.joinToString { "${it.key}=${it.value}" }}")
                appendLine("- 语料目录：`$corpus`")
                when (suite) {
                    "es2015" -> appendLine("- 范围：test/language + test/built-ins 中按已支持特性筛选的 ES2015 子集（排除 ES5 与未支持特性）")
                    "es2016plus" -> appendLine("- 范围：非 ES5、全部特性已支持且命中 ES2016+ 特性种子的用例")
                    "module" -> appendLine("- 范围：带 `flags: [module]` 的 Test262 module 语料；默认 TLA 验收目录可用 --filter 定向")
                    "sourcephase" -> appendLine("- 范围：features 含 source-phase-imports 的 script/module/staging 语料")
                    else -> appendLine("- 范围：test/language + test/built-ins 中带 `es5id` 的脚本")
                }
                if (suite == "module" || suite == "sourcephase") {
                    appendLine("- 跳过：当前未支持的 module 特性、运行器未提供的 \$262 钩子、缺失 include")
                } else {
                    appendLine("- 跳过：不支持的 flag、当前不支持特性、运行器未提供的 \$262 钩子、缺失 include")
                }
                appendLine()
                appendLine("| 指标 | 数值 |")
                appendLine("|---|---:|")
                appendLine("| 选中测试文件 | $selectedTests |")
                appendLine("| 执行次数（含 strict/sloppy） | $executedRuns |")
                appendLine("| 通过 | $passedRuns |")
                appendLine("| 失败 | $failedRuns |")
                appendLine("| 崩溃 | $crashedRuns |")
                appendLine("| 跳过测试文件 | $skippedTests |")
                appendLine("| 耗时(ms) | $elapsedMillis |")
                appendLine()
                if (selectionExcluded.isNotEmpty()) {
                    appendLine("## 筛选统计（未纳入本次口径）")
                    appendLine()
                    appendLine("| 原因 | 数量 |")
                    appendLine("|---|---:|")
                    for ((reason, count) in selectionExcluded.entries.sortedByDescending { it.value }) {
                        appendLine("| ${reason.replace("|", "\\|")} | $count |")
                    }
                    appendLine()
                    appendLine("以上用例在筛选阶段即被排除，与下方\"跳过原因\"（已选中但当前能力不支持）不是同一类。")
                    appendLine()
                }
                if (skipReasons.isNotEmpty()) {
                    appendLine("## 跳过原因")
                    appendLine()
                    appendLine("| 原因 | 数量 |")
                    appendLine("|---|---:|")
                    for ((reason, count) in skipReasons.entries.sortedByDescending { it.value }) {
                        appendLine("| ${reason.replace("|", "\\|")} | $count |")
                    }
                    appendLine()
                }
                if (textMatchExclusions.isNotEmpty()) {
                    appendLine("## 文本匹配排除（供复核）")
                    appendLine()
                    appendLine(
                        "以下 ${textMatchExclusions.size} 条由源码文本匹配排除，metadata.features " +
                            "未标注对应特性；可能因注释或字符串误排，明细见 `test262-text-exclusions.txt`。"
                    )
                    appendLine()
                }
                if (failureKinds.isNotEmpty()) {
                    appendLine("## 失败分类")
                    appendLine()
                    appendLine("| 分类 | 数量 |")
                    appendLine("|---|---:|")
                    for ((kind, count) in failureKinds.entries.sortedByDescending { it.value }) {
                        appendLine("| ${kind.replace("|", "\\|")} | $count |")
                    }
                    appendLine()
                }
                if (harnessShims.isNotEmpty()) {
                    appendLine("## harness 替换与注入")
                    appendLine()
                    for (shim in harnessShims) {
                        appendLine("- $shim")
                    }
                    appendLine()
                }
                if (slowest.isNotEmpty()) {
                    appendLine("## 最慢用例")
                    appendLine()
                    appendLine("| 测试 | 变体 | 耗时(ms) |")
                    appendLine("|---|---|---:|")
                    for (run in slowest) {
                        appendLine("| ${run.path} | ${run.variant} | ${run.elapsedMillis} |")
                    }
                    appendLine()
                }
                appendLine("失败样例见 `test262-failures.txt` 与 `test262-summary.json`。")
            }
        )
    }

    fun writeJson(file: File) {
        file.writeText(
            buildString {
                appendLine("{")
                appendLine("  \"generatedAt\": \"${jsonEscape(generatedAt)}\",")
                appendLine("  \"suite\": \"${jsonEscape(suite)}\",")
                appendLine("  \"corpus\": \"${jsonEscape(corpus)}\",")
                appendLine("  \"metadata\": {")
                appendLine("    \"engineCommit\": \"${jsonEscape(metadata.engineCommit)}\",")
                appendLine("    \"corpusCommit\": ${metadata.corpusCommit?.let { "\"${jsonEscape(it)}\"" } ?: "null"},")
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
                appendLine("  \"selectionExcluded\": {")
                val exclusions = selectionExcluded.entries.sortedByDescending { it.value }
                exclusions.forEachIndexed { index, entry ->
                    append("    \"${jsonEscape(entry.key)}\": ${entry.value}")
                    appendLine(if (index < exclusions.lastIndex) "," else "")
                }
                appendLine("  },")
                appendLine("  \"harnessShims\": [")
                harnessShims.forEachIndexed { index, shim ->
                    append("    \"${jsonEscape(shim)}\"")
                    appendLine(if (index < harnessShims.lastIndex) "," else "")
                }
                appendLine("  ],")
                appendLine("  \"selectedTests\": $selectedTests,")
                appendLine("  \"executedRuns\": $executedRuns,")
                appendLine("  \"passedRuns\": $passedRuns,")
                appendLine("  \"failedRuns\": $failedRuns,")
                appendLine("  \"crashedRuns\": $crashedRuns,")
                appendLine("  \"skippedTests\": $skippedTests,")
                appendLine("  \"textMatchExclusionCount\": ${textMatchExclusions.size},")
                appendLine("  \"elapsedMillis\": $elapsedMillis,")
                appendLine("  \"skipReasons\": {")
                val reasons = skipReasons.entries.sortedByDescending { it.value }
                reasons.forEachIndexed { index, entry ->
                    append("    \"${jsonEscape(entry.key)}\": ${entry.value}")
                    appendLine(if (index < reasons.lastIndex) "," else "")
                }
                appendLine("  },")
                appendLine("  \"failureKinds\": {")
                val kinds = failureKinds.entries.sortedByDescending { it.value }
                kinds.forEachIndexed { index, entry ->
                    append("    \"${jsonEscape(entry.key)}\": ${entry.value}")
                    appendLine(if (index < kinds.lastIndex) "," else "")
                }
                appendLine("  },")
                appendLine("  \"failureSamples\": [")
                failures.take(200).forEachIndexed { index, failure ->
                    append(
                        "    {\"path\": \"${jsonEscape(failure.path)}\", " +
                            "\"variant\": \"${jsonEscape(failure.variant)}\", " +
                            "\"status\": \"${failure.status.name}\", " +
                            "\"kind\": ${failure.kind?.let { "\"${jsonEscape(it.label)}\"" } ?: "null"}, " +
                            "\"message\": \"${jsonEscape(failure.message)}\"}"
                    )
                    appendLine(if (index < minOf(failures.size, 200) - 1) "," else "")
                }
                appendLine("  ]")
                appendLine("}")
            }
        )
    }

    fun writeFailureLists(directory: File) {
        // 即使本次零失败也要覆盖旧文件，避免本地报告残留上一次运行的失败；
        // 消息中的换行/制表符替换为空格，保证每条失败占一行便于聚类
        File(directory, "test262-failures.txt").writeText(
            buildString {
                for (failure in failures) {
                    val message = failure.message
                        .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                        .trim()
                    appendLine("${failure.path}\t${failure.variant}\t${failure.status}\t" +
                        "${failure.kind?.label ?: "-"}\t$message")
                }
            }
        )
    }

    /**
     * 写出被跳过用例的明细。
     *
     * 文本筛选（`$262` 宿主钩子、`Array.print`、SharedArrayBuffer）可能因注释或字符串误排，
     * 把每条跳过记录落盘后，人工只需 grep 该文件即可复核。
     */
    fun writeSkippedList(directory: File) {
        File(directory, "test262-skipped.txt").writeText(
            buildString {
                for (record in skippedRecords) {
                    appendLine("${record.path}\t${record.reason}")
                }
            }
        )
        // 文本匹配排除单独落盘：这些判定没有 metadata.features 支撑，可能因注释/字符串误排
        File(directory, "test262-text-exclusions.txt").writeText(
            buildString {
                appendLine("# 仅由源码文本匹配排除的用例（metadata.features 未标注对应特性），供人工复核")
                appendLine("# path\treason")
                for (record in textMatchExclusions) {
                    appendLine("${record.path}\t${record.reason}")
                }
            }
        )
    }

    fun printSummary() {
        val suiteLabel = when (suite) {
            "es2015" -> "ES2015"
            "es2016plus" -> "ES2016+"
            "module" -> "module"
            "sourcephase" -> "source-phase"
            else -> "ES5"
        }
        println("Test262 $suiteLabel 子集语义基线")
        println("  选中: $selectedTests, 执行: $executedRuns, 通过: $passedRuns, 失败: $failedRuns, 崩溃: $crashedRuns, 跳过: $skippedTests")
        println("  耗时: ${elapsedMillis}ms")
        if (skipReasons.isNotEmpty()) {
            println("  跳过原因: " + skipReasons.entries.joinToString { "${it.key}=${it.value}" })
        }
        if (textMatchExclusions.isNotEmpty()) {
            println("  文本匹配排除（待复核）: ${textMatchExclusions.size}，见 test262-text-exclusions.txt")
        }
        if (failureKinds.isNotEmpty()) {
            println("  失败分类: " + failureKinds.entries.joinToString { "${it.key}=${it.value}" })
        }
    }
}

private fun Throwable.describe(): String {
    val firstLine = message?.lineSequence()?.firstOrNull()?.trim()
    val type = this::class.simpleName ?: "Throwable"
    return if (firstLine.isNullOrEmpty()) type else "$type: $firstLine"
}

private fun JSValue.describe(): String {
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
