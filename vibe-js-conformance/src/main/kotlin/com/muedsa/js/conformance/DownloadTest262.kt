package com.muedsa.js.conformance

import java.io.File

/**
 * 下载并解压 tc39/test262 语料。
 *
 * 成功后写入 `.corpus-commit` 标记；仅当标记与 commit 匹配时跳过，
 * 否则重建语料目录，避免复用旧版本或半成品。语料不入库。
 */
fun main(args: Array<String>) {
    val targetDirectory = File(args.getOrElse(0) { "corpus/test262" })
    val commit = args.getOrElse(1) {
        throw IllegalArgumentException("缺少 test262 commit")
    }
    val requiredDirectories = listOf("test", "harness")
    val stamp = File(targetDirectory, ".corpus-commit")
    val complete = stamp.isFile && stamp.readText().trim() == commit &&
        requiredDirectories.all { File(targetDirectory, it).isDirectory }
    if (complete) {
        println("Test262 语料已存在：${targetDirectory.absolutePath}")
        return
    }
    // 缺少完整性标记或 commit 变化时重建语料，避免复用旧版本/半成品目录
    if (targetDirectory.exists()) {
        println("语料需要重建：${targetDirectory.absolutePath}")
        check(targetDirectory.deleteRecursively()) {
            "无法清理旧语料：${targetDirectory.absolutePath}"
        }
    }
    targetDirectory.mkdirs()
    val archive = File.createTempFile("test262-", ".zip")
    try {
        println("下载 test262@$commit ...")
        openDownload("https://codeload.github.com/tc39/test262/zip/$commit").use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        extractZip(archive, targetDirectory)
    } finally {
        archive.delete()
    }

    val missing = requiredDirectories.filterNot { File(targetDirectory, it).isDirectory }
    check(missing.isEmpty()) {
        "Test262 语料下载不完整，缺少目录：${missing.joinToString()}"
    }
    stamp.writeText(commit)
    println("Test262 语料下载完成：${targetDirectory.absolutePath}")
}