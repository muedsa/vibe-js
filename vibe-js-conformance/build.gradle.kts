plugins {
    alias(libs.plugins.kotlin.jvm)
}

// group/version 由根 build.gradle.kts 统一设置（0.0.0-SNAPSHOT）；该模块不发布

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":vibe-js-core"))
    implementation(libs.kotlinx.coroutines.core)
    // 运行器自身的测试：分类、超时与关闭行为需要独立于语料验证
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * 给运行器任务追加通用参数：用例超时、清理时限、Node 超时与步数配额。
 *
 * 均可用 -P 覆盖，例如 `-PcaseTimeoutMs=10000 -PmaxSteps=1000000`；
 * 不传时由运行器使用自身默认值。
 */
fun JavaExec.applyRunnerSettings() {
    // 报告元数据：Gradle 版本无法在运行时得知，由构建脚本传入；
    // CI 上的引擎提交优先用 GITHUB_SHA，本地缺省时由运行器回退到 git rev-parse
    args("--gradle-version", project.gradle.gradleVersion)
    System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() }?.let { sha ->
        args("--engine-commit", sha)
    }
    listOf(
        "caseTimeoutMs" to "--case-timeout-ms",
        "cleanupTimeoutMs" to "--cleanup-timeout-ms",
        "nodeTimeoutMs" to "--node-timeout-ms",
        "maxSteps" to "--max-steps",
    ).forEach { (property, argument) ->
        project.findProperty(property)?.let { args(argument, it.toString()) }
    }
}

/** 固定的 test262-parser-tests 版本，保证基线可复现。 */
val parserCorpusCommit = "0e808c74fbec780646434cad17bb22dc52461003"
val parserCorpusDir = layout.projectDirectory.dir("corpus/test262-parser-tests")
val conformanceReportDir = layout.buildDirectory.dir("reports/conformance")

/**
 * 下载 tc39/test262-parser-tests 语料。
 *
 * 语料不入库（见 .gitignore），由该任务按固定 commit 下载并解压；
 * 已存在时跳过，避免重复下载。
 */
val downloadParserCorpus = tasks.register<JavaExec>("downloadParserCorpus") {
    group = "verification"
    description = "下载固定版本的 tc39/test262-parser-tests 语料"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.DownloadCorpusKt")
    args(parserCorpusDir.asFile.absolutePath, parserCorpusCommit)
    inputs.property("corpusCommit", parserCorpusCommit)
    outputs.dir(parserCorpusDir)
    // 不在这里做 onlyIf/闭包判断，避免 Configuration Cache 序列化脚本对象；
    // DownloadCorpus 自身会在语料完整时直接跳过下载。
}

/**
 * 运行解析一致性基线。
 *
 * 默认只生成报告（退出码 0）；发生崩溃时退出码 2。
 * 脚本语料走 `Parser.parseProgram`，`.module.js` 走 `Parser.parseModule` 并按模块目标
 * 单独统计。使用 `-Pstrict` 时，允许列表之外的任何正/负向语法不一致都会导致任务失败：
 * 脚本见 `parser-pass-allowlist.txt`（已清空）、`parser-fail-allowlist.txt` 与
 * `parser-early-allowlist.txt`；模块见 `parser-module-allowlist.txt`（逐条路径 + 原因）。
 */
val parserConformance = tasks.register<JavaExec>("parserConformance") {
    group = "verification"
    description = "运行 test262-parser-tests 脚本与模块语料并生成解析一致性报告（默认报告模式）"
    dependsOn(downloadParserCorpus)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.ParserConformanceKt")
    args(
        parserCorpusDir.asFile.absolutePath,
        conformanceReportDir.get().asFile.absolutePath,
    )
    if (project.hasProperty("strict")) {
        args("--strict")
    }
    inputs.dir(parserCorpusDir)
    outputs.dir(conformanceReportDir)
}

/** 固定的 test262 版本，保证语义基线可复现。 */
val test262Commit = "91c70759363c46557b694792bf34a2551a7af5ba"
val test262Dir = layout.projectDirectory.dir("corpus/test262")
val libraryCorpusDir = layout.projectDirectory.dir("corpus/libraries")

/**
 * 下载 tc39/test262 语料（含 test/ 与 harness/）。
 */
val downloadTest262Corpus = tasks.register<JavaExec>("downloadTest262Corpus") {
    group = "verification"
    description = "下载固定版本的 tc39/test262 语料"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.DownloadTest262Kt")
    args(test262Dir.asFile.absolutePath, test262Commit)
    inputs.property("test262Commit", test262Commit)
    outputs.dir(test262Dir)
}

/**
 * 运行 Test262 ES5 语义子集。
 *
 * 默认报告模式（退出码 0）；崩溃退出码 2；-Pstrict 时存在失败退出码 1。
 * 可通过 -Ptest262Limit=1000 限制用例数，便于先行验证。
 */
val test262Conformance = tasks.register<JavaExec>("test262Conformance") {
    group = "verification"
    description = "运行 Test262 ES5 子集并生成语义一致性报告（默认报告模式）"
    dependsOn(downloadTest262Corpus)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.Test262ConformanceKt")
    args(
        test262Dir.asFile.absolutePath,
        conformanceReportDir.get().asFile.absolutePath,
    )
    if (project.hasProperty("test262Limit")) {
        args("--limit", project.property("test262Limit").toString())
    }
    if (project.hasProperty("test262Jobs")) {
        args("--jobs", project.property("test262Jobs").toString())
    }
    if (project.hasProperty("test262Filter")) {
        args("--filter", project.property("test262Filter").toString())
    }
    applyRunnerSettings()
    if (project.hasProperty("strict")) {
        args("--strict")
    }
    inputs.dir(test262Dir)
    outputs.dir(conformanceReportDir)
}

/**
 * 运行 Test262 ES2015 特性子集（CI 门禁使用 -Pstrict）。
 *
 * 选择 test/language 与 test/built-ins 中 features 全部落在支持集合内的用例；
 * 未支持特性/模块等继续跳过，用于推进 ES2015 语义收敛。
 */
val test262Es2015Conformance = tasks.register<JavaExec>("test262Es2015Conformance") {
    group = "verification"
    description = "运行 Test262 ES2015 特性子集并生成语义报告（报告模式）"
    dependsOn(downloadTest262Corpus)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.Test262ConformanceKt")
    val es2015ReportDir = conformanceReportDir.map { it.dir("test262-es2015") }
    args(
        test262Dir.asFile.absolutePath,
        es2015ReportDir.get().asFile.absolutePath,
        "--suite", "es2015",
        // 无 features 标注的 esid 用例同样属于已实现的核心语义，
        // 纳入默认基线以扩大回归覆盖面（差异分析见 docs/CONFORMANCE.md）
        "--include-esid",
    )
    if (project.hasProperty("test262Limit")) {
        args("--limit", project.property("test262Limit").toString())
    }
    if (project.hasProperty("test262Jobs")) {
        args("--jobs", project.property("test262Jobs").toString())
    }
    if (project.hasProperty("test262Filter")) {
        args("--filter", project.property("test262Filter").toString())
    }
    applyRunnerSettings()
    if (project.hasProperty("strict")) {
        args("--strict")
    }
    inputs.dir(test262Dir)
    outputs.dir(es2015ReportDir)
}

/** 下载固定版本的真实 JS 库 UMD 构建。 */
val downloadLibraryCorpus = tasks.register<JavaExec>("downloadLibraryCorpus") {
    group = "verification"
    description = "下载固定版本的真实 JS 库 UMD 构建"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.DownloadLibrariesKt")
    args(libraryCorpusDir.asFile.absolutePath)
    outputs.dir(libraryCorpusDir)
}

/**
 * 真实 JS 库 UMD 冒烟。
 *
 * 默认报告模式；崩溃退出码 2；-Pstrict 时失败退出码 1。
 */
val librarySmoke = tasks.register<JavaExec>("librarySmoke") {
    group = "verification"
    description = "运行 lodash/mustache/dayjs/qs/validator/underscore/moment/semver/punycode/json5/big.js/diff/lz-string/marked/bignumber.js/decimal.js/crypto-js 的 UMD 冒烟"
    dependsOn(downloadLibraryCorpus)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.LibrarySmokeKt")
    args(
        libraryCorpusDir.asFile.absolutePath,
        conformanceReportDir.get().asFile.absolutePath,
    )
    applyRunnerSettings()
    if (project.hasProperty("strict")) {
        args("--strict")
    }
    inputs.dir(libraryCorpusDir)
    outputs.dir(conformanceReportDir)
}

/**
 * 以 Node 为参照的差分测试。
 *
 * 默认报告模式；-Pstrict 时存在不一致退出码 1。
 */
val differentialConformance = tasks.register<JavaExec>("differentialConformance") {
    group = "verification"
    description = "与 Node 对比 JSON/Number/RegExp/String/Date 等行为的差分测试"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.DifferentialConformanceKt")
    args(conformanceReportDir.get().asFile.absolutePath)
    applyRunnerSettings()
    if (project.hasProperty("strict")) {
        args("--strict")
    }
    outputs.dir(conformanceReportDir)
}
