package com.muedsa.js.conformance

import java.io.File

/**
 * 一次运行的元数据。
 *
 * 报告只写"通过多少"无法审计：必须同时记录**哪份引擎、哪份语料、什么工具链、
 * 用了什么筛选配置**，否则同一份报告在不同提交之间无法比较。
 *
 * @property engineCommit 被测引擎的提交（来源见 [RunMetadata.collect]）
 * @property corpusCommit 语料目录 `.corpus-commit` 标记的提交；无标记时为 null
 * @property kotlinVersion 运行器编译所用的 Kotlin 版本
 * @property javaVersion 运行器所在的 JVM 版本
 * @property gradleVersion 构建脚本传入的 Gradle 版本；未传入时为 null
 * @property os 操作系统与架构，用于解释性能差异
 * @property runnerConfig 本次筛选与执行参数（suite/filter/limit/jobs/超时等）
 */
internal data class RunMetadata(
    val engineCommit: String,
    val corpusCommit: String?,
    val kotlinVersion: String,
    val javaVersion: String,
    val gradleVersion: String?,
    val os: String,
    val runnerConfig: Map<String, String>,
) {
    companion object {
        /** 无法确定引擎提交时的占位值。 */
        const val UNKNOWN_COMMIT = "unknown"

        /**
         * 收集元数据。
         *
         * 引擎提交按"命令行参数 → `GITHUB_SHA` 环境变量 → `git rev-parse` → unknown"
         * 的顺序解析；语料提交直接读语料目录下的 `.corpus-commit`。
         *
         * @param corpusDir 语料目录；为 null 时不读取语料标记
         * @param args 运行器参数，用于读取 `--engine-commit` 与 `--gradle-version`
         * @param runnerConfig 本次的筛选/执行配置，原样写入报告
         */
        fun collect(
            corpusDir: File?,
            args: Array<String>,
            runnerConfig: Map<String, String>,
        ): RunMetadata {
            return RunMetadata(
                engineCommit = resolveEngineCommit(args),
                corpusCommit = corpusDir?.let { readCorpusCommit(it) },
                kotlinVersion = KotlinVersion.CURRENT.toString(),
                javaVersion = System.getProperty("java.version") ?: "unknown",
                gradleVersion = args.stringOption("--gradle-version"),
                os = buildString {
                    append(System.getProperty("os.name") ?: "unknown")
                    append('/')
                    append(System.getProperty("os.arch") ?: "unknown")
                },
                runnerConfig = runnerConfig,
            )
        }

        /**
         * 读取语料目录的 `.corpus-commit` 标记。
         *
         * 语料不入库，标记由下载任务写入；缺失时返回 null，而不是猜测版本。
         */
        fun readCorpusCommit(directory: File): String? {
            val stamp = File(directory, ".corpus-commit")
            if (!stamp.isFile) return null
            return stamp.readText().trim().ifEmpty { null }
        }

        /** 解析引擎提交：参数优先，其次 CI 环境变量，最后尝试本地 git。 */
        private fun resolveEngineCommit(args: Array<String>): String {
            args.stringOption("--engine-commit")?.takeIf { it.isNotBlank() }?.let { return it }
            System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() }?.let { return it }
            return gitHead() ?: UNKNOWN_COMMIT
        }

        /**
         * 本地兜底：读取当前仓库 HEAD。
         *
         * 只在缺少 CI 环境变量时执行，失败（非 git 检出、无 git）不影响运行。
         */
        private fun gitHead(): String? {
            return runCatching {
                val process = ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start()
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return null
                }
                if (process.exitValue() != 0) return null
                process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
            }.getOrNull()
        }
    }
}

/** 读取 `--name value` 形式的字符串参数；不存在时返回 null。 */
internal fun Array<String>.stringOption(name: String): String? {
    val index = indexOf(name)
    if (index < 0) return null
    return getOrNull(index + 1)
}
