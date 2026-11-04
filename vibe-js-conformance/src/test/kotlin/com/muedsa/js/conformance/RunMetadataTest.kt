package com.muedsa.js.conformance

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 运行元数据的单元测试。
 *
 * 元数据是"报告可审计"的基础：语料 commit 缺失时必须显式记为未知，
 * 命令行参数必须优先于环境变量，否则报告会指向错误的提交。
 */
class RunMetadataTest {

    private fun tempDir(): File = Files.createTempDirectory("vibe-meta-").toFile()

    @Test
    fun `读取语料目录的 commit 标记并去除空白`() {
        val dir = tempDir()
        try {
            File(dir, ".corpus-commit").writeText("91c70759363c46557b694792bf34a2551a7af5ba\n")
            assertEquals(
                "91c70759363c46557b694792bf34a2551a7af5ba",
                RunMetadata.readCorpusCommit(dir),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `缺少标记时语料 commit 记为未知`() {
        val dir = tempDir()
        try {
            assertNull(RunMetadata.readCorpusCommit(dir))
        } finally {
            dir.deleteRecursively()
        }
        // 空标记同样视为未知，避免把空串写进报告
        val emptyDir = tempDir()
        try {
            File(emptyDir, ".corpus-commit").writeText("  \n")
            assertNull(RunMetadata.readCorpusCommit(emptyDir))
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun `命令行参数应覆盖引擎提交与 Gradle 版本`() {
        val metadata = RunMetadata.collect(
            corpusDir = null,
            args = arrayOf("--engine-commit", "deadbeef", "--gradle-version", "9.8.2"),
            runnerConfig = mapOf("suite" to "es5"),
        )

        assertEquals("deadbeef", metadata.engineCommit)
        assertEquals("9.8.2", metadata.gradleVersion)
        assertEquals("es5", metadata.runnerConfig["suite"])
        assertNull(metadata.corpusCommit, "未提供语料目录时不应猜测语料版本")
        assertTrue(metadata.kotlinVersion.isNotBlank())
        assertTrue(metadata.os.contains('/'))
    }

    @Test
    fun `未提供参数时引擎提交仍应有确定值`() {
        val metadata = RunMetadata.collect(corpusDir = null, args = emptyArray(), runnerConfig = emptyMap())

        // 本地 git 或 unknown 都可以，但不能是空白
        assertTrue(metadata.engineCommit.isNotBlank())
        assertNull(metadata.gradleVersion)
    }
}
