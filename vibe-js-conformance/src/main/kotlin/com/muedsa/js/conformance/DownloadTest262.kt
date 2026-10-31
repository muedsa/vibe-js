package com.muedsa.js.conformance

import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

/**
 * 下载并解压 tc39/test262 语料。
 *
 * 只校验 `test/` 与 `harness/` 目录，语料不入库。
 */
fun main(args: Array<String>) {
    val targetDirectory = File(args.getOrElse(0) { "corpus/test262" })
    val commit = args.getOrElse(1) {
        throw IllegalArgumentException("缺少 test262 commit")
    }
    val requiredDirectories = listOf("test", "harness")
    if (requiredDirectories.all { File(targetDirectory, it).isDirectory }) {
        println("Test262 语料已存在：${targetDirectory.absolutePath}")
        return
    }

    targetDirectory.mkdirs()
    val archive = File.createTempFile("test262-", ".zip")
    try {
        println("下载 test262@$commit ...")
        val url = URI("https://codeload.github.com/tc39/test262/zip/$commit").toURL()
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
        "Test262 语料下载不完整，缺少目录：${missing.joinToString()}"
    }
    println("Test262 语料下载完成：${targetDirectory.absolutePath}")
}