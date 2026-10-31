package com.muedsa.js.conformance

import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * 下载并解压 tc39/test262-parser-tests 语料。
 *
 * 固定 commit 的 ZIP 来自 codeload，解压时去掉顶层目录。
 * 语料目录已存在必需子目录时直接跳过。
 */
fun main(args: Array<String>) {
    val targetDirectory = File(args.getOrElse(0) { "corpus/test262-parser-tests" })
    val commit = args.getOrElse(1) {
        throw IllegalArgumentException("缺少 test262-parser-tests commit")
    }
    val requiredDirectories = listOf("pass", "pass-explicit", "fail", "early")
    if (requiredDirectories.all { File(targetDirectory, it).isDirectory }) {
        println("语料已存在：${targetDirectory.absolutePath}")
        return
    }

    targetDirectory.mkdirs()
    val archive = File.createTempFile("test262-parser-tests-", ".zip")
    try {
        println("下载 test262-parser-tests@$commit ...")
        val url = URI(
            "https://codeload.github.com/tc39/test262-parser-tests/zip/$commit"
        ).toURL()
        url.openStream().use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val relativePath = entry.name.substringAfter('/', missingDelimiterValue = "")
                if (relativePath.isNotEmpty()) {
                    val target = File(targetDirectory, relativePath)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output) }
                    }
                }
                zip.closeEntry()
            }
        }
    } finally {
        archive.delete()
    }

    val missing = requiredDirectories.filterNot { File(targetDirectory, it).isDirectory }
    check(missing.isEmpty()) {
        "语料下载不完整，缺少目录：${missing.joinToString()}"
    }
    println("语料下载完成：${targetDirectory.absolutePath}")
}
