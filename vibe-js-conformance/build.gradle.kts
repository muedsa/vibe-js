plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.muedsa.js"
version = "0.0.1"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":vibe-js-core"))
    implementation(libs.kotlinx.coroutines.core)
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
val downloadParserCorpus by tasks.registering(JavaExec::class) {
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
 * 使用 `-Pstrict` 时，允许列表（`src/main/resources/parser-pass-allowlist.txt`，
 * 当前为明确排除的 Generator）之外的任何正/负向语法不一致都会导致任务失败。
 */
val parserConformance by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "运行 test262-parser-tests 并生成解析一致性报告（默认报告模式）"
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
val downloadTest262Corpus by tasks.registering(JavaExec::class) {
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
val test262Conformance by tasks.registering(JavaExec::class) {
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
    if (project.hasProperty("strict")) {
        args("--strict")
    }
    inputs.dir(test262Dir)
    outputs.dir(conformanceReportDir)
}

/**
 * 运行 Test262 ES2015 特性子集（报告模式）。
 *
 * 选择 test/language 与 test/built-ins 中 features 全部落在支持集合内的用例；
 * 未支持特性/Generator/模块等继续跳过，用于推进 ES2015 语义收敛。
 */
val test262Es2015Conformance by tasks.registering(JavaExec::class) {
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
    if (project.hasProperty("strict")) {
        args("--strict")
    }
    inputs.dir(test262Dir)
    outputs.dir(es2015ReportDir)
}

/** 下载固定版本的真实 JS 库 UMD 构建。 */
val downloadLibraryCorpus by tasks.registering(JavaExec::class) {    group = "verification"
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
val librarySmoke by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "运行 lodash/mustache/dayjs/qs/validator/underscore/moment/semver/punycode/json5 的 UMD 冒烟"
    dependsOn(downloadLibraryCorpus)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.LibrarySmokeKt")
    args(
        libraryCorpusDir.asFile.absolutePath,
        conformanceReportDir.get().asFile.absolutePath,
    )
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
val differentialConformance by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "与 Node 对比 JSON/Number/RegExp/String/Date 等行为的差分测试"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.muedsa.js.conformance.DifferentialConformanceKt")
    args(conformanceReportDir.get().asFile.absolutePath)
    if (project.hasProperty("strict")) {
        args("--strict")
    }
    outputs.dir(conformanceReportDir)
}
