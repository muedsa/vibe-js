package com.muedsa.js.conformance

import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/**
 * Test262 ES5 子集语义运行器。
 *
 * 当前范围：
 * - 只运行 test/language 与 test/built-ins 中带 es5id 的脚本；
 * - 支持 includes、negative（parse/runtime）、strict/sloppy/raw 变体；
 * - 暂跳过 module/async/generator 以及包含 eval()/$262 的用例；
 * - 每 3 个变体独立 Engine，加载 sta.js/assert.js 与 metadata includes。
 */
fun main(args: Array<String>) {
    val corpusDir = File(args.getOrElse(0) { "corpus/test262" })
    val reportDir = File(args.getOrElse(1) { "build/reports/conformance" })
    val strict = args.contains("--strict")
    // --suite es5（默认）或 es2015（按支持特性筛选的 ES2015 子集）
    val suite = args.indexOf("--suite").takeIf { it >= 0 }?.let { index ->
        args.getOrNull(index + 1)
    } ?: "es5"
    if (suite !in setOf("es5", "es2015")) {
        System.err.println("未知的 Test262 子集：$suite（可选 es5 / es2015）")
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

    if (!File(corpusDir, "test").isDirectory || !File(corpusDir, "harness").isDirectory) {
        System.err.println(
            "找不到 Test262 语料：${corpusDir.absolutePath}\n" +
                "请先执行 ./gradlew :vibe-js-conformance:downloadTest262Corpus"
        )
        exitProcess(3)
    }

    val report = runBlocking { Test262Runner(corpusDir, limit, jobs, filter, suite).run() }
    reportDir.mkdirs()
    report.writeMarkdown(File(reportDir, "test262-summary.md"))
    report.writeJson(File(reportDir, "test262-summary.json"))
    report.writeFailureLists(reportDir)
    report.printSummary()

    if (report.crashedRuns > 0) {
        exitProcess(2)
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
) {

    private val harnessDir = File(corpusDir, "harness")

    /** 仅用于测试断言的最小 Reflect 垫片（核心库明确不提供 Reflect）。 */
    private val REFLECT_SHIM = """
        if (typeof Reflect === 'undefined') {
          var Reflect = {
            ownKeys: function (o) {
              return Object.getOwnPropertyNames(o).concat(Object.getOwnPropertySymbols(o));
            },
            getOwnPropertyDescriptor: function (o, k) {
              return Object.getOwnPropertyDescriptor(o, k);
            },
            get: function (o, k) { return o[k]; },
            has: function (o, k) { return k in o; },
            set: function (o, k, v) { o[k] = v; return true; },
            defineProperty: function (o, k, d) { Object.defineProperty(o, k, d); return true; }
          };
        }
    """.trimIndent()

    /** harness 文件只读取一次，供所有用例复用。 */
    private val harnessSources: Map<String, String> by lazy {
        harnessDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".js") }
            ?.associate { file ->
                var source = file.readText().removePrefix("\uFEFF")
                if (file.name == "iteratorZipUtils.js") {
                    // 生成器语法明确排除；把 prefixes 生成器替换为等价的数组实现
                    source = source.replace(
                        Regex("""function\*\s+prefixes\(s\)\s*\{[\s\S]*?\n  \}"""),
                        "function prefixes(s) {\n" +
                            "    var result = [];\n" +
                            "    for (var i = 0; i <= s.length; ++i) result.push(s.slice(0, i));\n" +
                            "    return result;\n" +
                            "  }",
                    )
                }
                file.name to source
            }
            ?: emptyMap()
    }

    suspend fun run(): Test262Report = coroutineScope {
        val startedAt = System.nanoTime()
        val tests = collectTests()
        val selected = if (limit != null && limit < tests.size) {
            tests.take(limit)
        } else {
            tests
        }
        val parallelism = (jobs ?: Runtime.getRuntime().availableProcessors())
            .coerceIn(1, 16)
        val semaphore = Semaphore(parallelism)
        val completed = AtomicInteger()

        val outcomes = selected.map { test ->
            async(Dispatchers.Default) {
                semaphore.withPermit {
                    val outcome = runOne(test)
                    val finished = completed.incrementAndGet()
                    if (finished % 500 == 0) {
                        println("已执行 $finished/${selected.size} 个测试文件...")
                    }
                    outcome
                }
            }
        }.awaitAll()

        var passedRuns = 0
        var failedRuns = 0
        var crashedRuns = 0
        var executedRuns = 0
        var skippedTests = 0
        val skipReasons = LinkedHashMap<String, Int>()
        val failures = mutableListOf<RunRecord>()
        val allResults = mutableListOf<RunRecord>()

        for (outcome in outcomes) {
            when (outcome) {
                is TestOutcome.Skipped -> {
                    skippedTests++
                    skipReasons[outcome.reason] = (skipReasons[outcome.reason] ?: 0) + 1
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
                        }
                    }
                }
            }
        }

        Test262Report(
            generatedAt = Instant.now().toString(),
            suite = suite,
            corpus = corpusDir.absolutePath,
            selectedTests = selected.size,
            executedRuns = executedRuns,
            passedRuns = passedRuns,
            failedRuns = failedRuns,
            crashedRuns = crashedRuns,
            skippedTests = skippedTests,
            skipReasons = skipReasons,
            failures = failures.sortedWith(
                compareBy({ it.path }, { it.variant })
            ),
            slowest = allResults.sortedByDescending { it.elapsedMillis }.take(20),
            elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000,
        )
    }

    private suspend fun runOne(test: TestFile): TestOutcome {
        skipReason(test)?.let { return TestOutcome.Skipped(it) }
        val includes = loadIncludes(test.metadata.includes)
            ?: return TestOutcome.Skipped("missing include")
        val results = variants(test.metadata.flags).map { variant ->
            val result = execute(test, variant, includes)
            RunRecord(
                path = test.relativePath,
                variant = variant.name,
                status = result.status,
                message = result.message ?: "",
                elapsedMillis = result.elapsedMillis,
            )
        }
        return TestOutcome.Executed(results)
    }

    private fun collectTests(): List<TestFile> {
        val result = mutableListOf<TestFile>()
        for (rootName in listOf("test/language", "test/built-ins")) {
            val root = File(corpusDir, rootName)
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "js" }
                .filterNot { it.name.endsWith("_FIXTURE.js") }
                .filterNot { it.path.contains("${File.separator}staging${File.separator}") }
                .sortedBy { it.relativeTo(corpusDir).invariantSeparatorsPath }
                .forEach { file ->
                    val relativePath = file.relativeTo(corpusDir).invariantSeparatorsPath
                    val (metadata, body) = parseTestFile(file)
                    val matchesSuite = if (suite == "es2015") {
                        // ES2015 子集：非 ES5 用例、特性全部已支持，且不依赖未实现的内建内部对象
                        metadata != null && metadata.es5Id == null &&
                            metadata.features.isNotEmpty() &&
                            metadata.features.all { it in es2015SupportedFeatures } &&
                            es2015ExcludedPathFragments.none { relativePath.contains(it) }
                    } else {
                        metadata?.es5Id != null
                    }
                    if (matchesSuite && (filter == null || relativePath.contains(filter))) {
                        result.add(
                            TestFile(
                                file = file,
                                relativePath = relativePath,
                                metadata = metadata!!,
                                source = body,
                            )
                        )
                    }
                }
        }
        return result
    }

    private fun parseTestFile(file: File): Pair<Metadata?, String> {
        val source = file.readText().removePrefix("\uFEFF")
        val start = source.indexOf("/*---")
        if (start < 0) return null to source
        val end = source.indexOf("---*/", start + 5)
        if (end < 0) return null to source
        val metadata = parseMetadata(source.substring(start + 5, end))
        val body = source.substring(end + 5).trimStart('\r', '\n')
        return metadata to body
    }

    private fun parseMetadata(raw: String): Metadata {
        var es5Id: String? = null
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

    private fun skipReason(test: TestFile): String? {
        val metadata = test.metadata
        if (metadata.flags.any { it in setOf("module", "generator") }) {
            return "unsupported flag"
        }
        // async 负数用例的期望落定方式与普通 $DONE 不同，暂不纳入
        if ("async" in metadata.flags && metadata.negativePhase != null) {
            return "unsupported async negative"
        }
        val unsupportedFeature = metadata.features.firstOrNull { it in unsupportedFeatures }
        if (unsupportedFeature != null) {
            return "unsupported feature: $unsupportedFeature"
        }
        if (test.source.contains("\$262")) {
            return "unsupported harness: \$262"
        }
        // 个别测试把宿主调试用的 print 挂到对象上，核心 runner 未提供该全局
        if (test.source.contains("Array.print")) {
            return "unsupported harness: print"
        }
        // TemporalHelpers.propertyBagObserver 通过 Proxy/Reflect 观测属性访问
        if (test.source.contains("propertyBagObserver")) {
            return "unsupported harness: Proxy"
        }
        // ArrayBuffer 等宿主型内建明确排除，测试源码中可能直接构造
        if (suite == "es2015" &&
            (test.source.contains("new ArrayBuffer") ||
                test.source.contains("new SharedArrayBuffer"))
        ) {
            return "unsupported feature: ArrayBuffer"
        }
        // ES2015 子集中排除 BigInt/Proxy/Generator 内容（项目明确排除）
        if (suite == "es2015" &&
            (test.source.contains("BigInt") ||
                test.source.contains("Proxy") ||
                test.source.contains("function *") ||
                test.source.contains("function*") ||
                test.source.contains("async *") ||
                test.source.contains("async*") ||
                test.source.contains("for await") ||
                generatorMethodPattern.containsMatchIn(test.source) ||
                bigIntLiteralPattern.containsMatchIn(test.source))
        ) {
            return "unsupported BigInt/Proxy/Generator"
        }
        if (metadata.negativePhase != null && metadata.negativePhase !in setOf("parse", "runtime")) {
            return "unsupported negative phase: ${metadata.negativePhase}"
        }
        return null
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

        val engine = Engine()
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
            // joint-iteration 的 harness 通过 Reflect 观测结果对象属性；
            // 核心库不提供 Reflect，这里仅注入测试断言所需的最小垫片。
            val usesReflect = test.source.contains("Reflect") ||
                includes.any { it.contains("Reflect") }
            if (usesReflect) engine.run(REFLECT_SHIM)
            for (include in includes) {
                engine.run(include)
            }
            engine.run(source)
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
            fail("SyntaxError: ${error.describe()}", startedAt)
        } catch (error: LexerException) {
            fail("SyntaxError: ${error.describe()}", startedAt)
        } catch (error: Throwable) {
            crash(error.describe(), startedAt)
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
        ExecutionResult(Status.PASS, null, elapsedMillis(startedAt))

    private fun fail(message: String, startedAt: Long): ExecutionResult =
        ExecutionResult(Status.FAIL, message, elapsedMillis(startedAt))

    private fun crash(message: String, startedAt: Long): ExecutionResult =
        ExecutionResult(Status.CRASH, message, elapsedMillis(startedAt))

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000

    companion object {
        /** BigInt 字面量检测（项目明确排除 BigInt）。 */
        /** 生成器方法语法（`*keys() {}` / `*[Symbol.iterator]() {}`），项目未实现。 */
        private val generatorMethodPattern = Regex(
            """(?m)^\s*\*\s*(?:[A-Za-z_$][A-Za-z0-9_$]*|\[[^\]]*\])\s*\("""
        )

        private val bigIntLiteralPattern = Regex(
            """(?<![A-Za-z0-9_$])(?:0[xX][0-9a-fA-F_]+|0[oO][0-7_]+|0[bB][01_]+|[0-9][0-9_]*)n\b"""
        )
        /**
         * ES2015 子集选择：用例的全部 features 必须在此集合内。
         * 该集合只列出项目已实现并愿意纳入基线的特性。
         */
        private val es2015SupportedFeatures = setOf(
            "arrow-function", "class", "computed-property-names", "const", "let",
            "default-parameters", "destructuring-binding", "destructuring-assignment",
            "rest-parameters", "spread-syntax", "template", "tagged-template",
            "for-of", "new.target", "super", "Symbol", "Symbol.iterator",
            "Symbol.toPrimitive", "Symbol.toStringTag", "Symbol.hasInstance",
            "Symbol.species", "Symbol.isConcatSpreadable",
            "Symbol.match", "Symbol.replace", "Symbol.search", "Symbol.split",
            "Symbol.matchAll", "String.prototype.matchAll",
            "Symbol.unscopables", "set-methods", "RegExp.escape", "iterator-helpers",
            "iterator-sequencing", "joint-iteration", "symbols-as-weakmap-keys",
            "explicit-resource-management", "__getter__", "__setter__",
            "Symbol.prototype.description", "Math.sumPrecise",
            "Array.prototype.flat", "Array.prototype.flatMap", "Array.fromAsync",
            "Array.prototype.includes", "Array.prototype.at", "Array.prototype.values",
            "String.prototype.includes", "String.prototype.endsWith",
            "String.prototype.trimStart", "String.prototype.trimEnd",
            "String.prototype.at", "String.fromCodePoint",
            "String.prototype.replaceAll",
            "String.prototype.isWellFormed", "String.prototype.toWellFormed",
            "Object.is", "Object.hasOwn", "Object.fromEntries", "array-grouping",
            "array-find-from-last", "change-array-by-copy",
            "Map", "Set", "WeakMap", "WeakSet", "Promise", "async-functions",
            "upsert", "error-cause",
            "Promise.allSettled", "Promise.any",
            "Promise.prototype.finally", "promise-with-resolvers", "promise-try",
            // 已在解析器/运行时实现的后续版本语法
            "exponentiation", "logical-assignment-operators", "globalThis",
            "object-rest", "object-spread", "optional-chaining",
            "numeric-separator-literal",
            "u-flag", "y-flag", "unicode-escapes",
        )

        /**
         * ES2015 子集中排除的路径片段：
         * - 未实现的宿主型内建（ArrayBuffer/DataView/TypedArray 等）；
         * - 未实现的 AsyncIterator 原型；
         * - 内容依赖 Generator 语法的计算属性名用例（Generator 明确排除）。
         */
        private val es2015ExcludedPathFragments = listOf(
            "test/built-ins/ArrayBuffer/",
            "test/built-ins/DataView/",
            "test/built-ins/TypedArray/",
            "test/built-ins/TypedArrayConstructors/",
            "test/built-ins/SharedArrayBuffer/",
            "test/built-ins/Atomics/",
            // 依赖 TypedArray 的 concat 大型用例
            "large-typed-array.js",
            "small-typed-array.js",
            "test/built-ins/AsyncIteratorPrototype/",
            // using / await using 声明语法暂未实现（后续阶段）
            "language/statements/using/",
            "language/statements/await-using/",
            "language/statements/for-of/head-using-",
            "language/statements/for-of/head-await-using-",
            "language/statements/for-await-of/head-using-",
            "language/statements/for-await-of/head-await-using-",
            "-from-generator-function-declaration.js",
            "-from-yield-expression.js",
            // ES2024 v 标志正则语料（引擎尚未支持 v 标志）
            "test/built-ins/RegExp/CharacterClassEscapes/",
            // 孤立代理项匹配依赖引擎码元语义（当前 Kotlin Regex 近似）
            "test/built-ins/RegExp/prototype/Symbol.match/builtin-infer-unicode.js",
            // 可选链用例中使用 Reflect.construct（项目明确排除 Reflect）
            "test/language/expressions/optional-chaining/new-target-optional-call.js",
            // 命名捕获组（(?<name>...)）尚未实现，跳过依赖其替换语义的生成用例
            "test/built-ins/String/prototype/replaceAll/searchValue-replacer-RegExp-call.js",
        )

        /** 当前范围外或尚未实现的特性。 */
        private val unsupportedFeatures = setOf(
            "Reflect.construct", "Reflect", "Proxy", "BigInt",
            "TypedArray", "ArrayBuffer", "DataView", "SharedArrayBuffer", "Atomics",
            "Intl", "WeakRef", "FinalizationRegistry", "class-fields-public",
            "class-fields-private", "class-static-block", "regexp-match-indices",
            "decorators",
            "Symbol.asyncIterator", "generator", "async-iteration",
            "dynamic-import", "import-attributes", "top-level-await",
            "resizable-arraybuffer", "arraybuffer-transfer", "Float16Array",
            "Temporal",
        )
    }
}

private data class Metadata(
    val es5Id: String?,
    val includes: List<String>,
    val flags: Set<String>,
    val features: Set<String>,
    val negativePhase: String?,
    val negativeType: String?,
)

private data class TestFile(
    val file: File,
    val relativePath: String,
    val metadata: Metadata,
    val source: String,
)

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
    val message: String?,
    val elapsedMillis: Long,
)

private data class RunRecord(
    val path: String,
    val variant: String,
    val status: Status,
    val message: String,
    val elapsedMillis: Long,
)

private sealed class TestOutcome {
    data class Skipped(val reason: String) : TestOutcome()
    data class Executed(val results: List<RunRecord>) : TestOutcome()
}

private data class Test262Report(
    val generatedAt: String,
    val suite: String,
    val corpus: String,
    val selectedTests: Int,
    val executedRuns: Int,
    val passedRuns: Int,
    val failedRuns: Int,
    val crashedRuns: Int,
    val skippedTests: Int,
    val skipReasons: Map<String, Int>,
    val failures: List<RunRecord>,
    val slowest: List<RunRecord>,
    val elapsedMillis: Long,
) {
    fun writeMarkdown(file: File) {
        val suiteLabel = if (suite == "es2015") "ES2015" else "ES5"
        file.writeText(
            buildString {
                appendLine("# Test262 $suiteLabel 子集语义基线")
                appendLine()
                appendLine("- 生成时间：$generatedAt")
                appendLine("- 语料目录：`$corpus`")
                if (suite == "es2015") {
                    appendLine("- 范围：test/language + test/built-ins 中按已支持特性筛选的 ES2015 子集（排除 ES5 与未支持特性）")
                } else {
                    appendLine("- 范围：test/language + test/built-ins 中带 `es5id` 的脚本")
                }
                appendLine("- 跳过：module/async/generator、当前不支持特性、\$262、缺失 include")
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
                appendLine("  \"selectedTests\": $selectedTests,")
                appendLine("  \"executedRuns\": $executedRuns,")
                appendLine("  \"passedRuns\": $passedRuns,")
                appendLine("  \"failedRuns\": $failedRuns,")
                appendLine("  \"crashedRuns\": $crashedRuns,")
                appendLine("  \"skippedTests\": $skippedTests,")
                appendLine("  \"elapsedMillis\": $elapsedMillis,")
                appendLine("  \"skipReasons\": {")
                val reasons = skipReasons.entries.sortedByDescending { it.value }
                reasons.forEachIndexed { index, entry ->
                    append("    \"${jsonEscape(entry.key)}\": ${entry.value}")
                    appendLine(if (index < reasons.lastIndex) "," else "")
                }
                appendLine("  },")
                appendLine("  \"failureSamples\": [")
                failures.take(200).forEachIndexed { index, failure ->
                    append(
                        "    {\"path\": \"${jsonEscape(failure.path)}\", " +
                            "\"variant\": \"${jsonEscape(failure.variant)}\", " +
                            "\"status\": \"${failure.status.name}\", " +
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
                    appendLine("${failure.path}\t${failure.variant}\t${failure.status}\t$message")
                }
            }
        )
    }

    fun printSummary() {
        val suiteLabel = if (suite == "es2015") "ES2015" else "ES5"
        println("Test262 $suiteLabel 子集语义基线")
        println("  选中: $selectedTests, 执行: $executedRuns, 通过: $passedRuns, 失败: $failedRuns, 崩溃: $crashedRuns, 跳过: $skippedTests")
        println("  耗时: ${elapsedMillis}ms")
        if (skipReasons.isNotEmpty()) {
            println("  跳过原因: " + skipReasons.entries.joinToString { "${it.key}=${it.value}" })
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