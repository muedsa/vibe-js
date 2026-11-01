package com.muedsa.js.conformance

import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 下载固定版本的真实 JS 库 UMD 构建。
 *
 * 每个文件先写临时文件再原子替换；`.urls` 清单记录固定 URL，
 * 版本变化时触发重新下载，避免复用旧版本。
 */
fun main(args: Array<String>) {
    val targetDirectory = File(args.getOrElse(0) { "corpus/libraries" })
    targetDirectory.mkdirs()
    // 清单记录固定下载 URL；版本变化时强制重新下载，避免复用旧版本文件
    val manifest = File(targetDirectory, ".urls")
    val previous = if (manifest.isFile) {
        manifest.readLines().mapNotNull { line ->
            line.split('	').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
    } else {
        emptyMap()
    }
    val current = sortedMapOf<String, String>()
    for (spec in librarySpecs) {
        val target = File(targetDirectory, "${spec.name}.js")
        val cachedUrl = previous[spec.name]
        if (target.isFile && target.length() > 0 && (cachedUrl == null || cachedUrl == spec.url)) {
            println("${spec.name}: 已存在")
        } else {
            println("下载 ${spec.name} ...")
            downloadAtomically(spec.url, target)
        }
        current[spec.name] = spec.url
    }
    manifest.writeText(current.entries.joinToString("\n") { "${it.key}\t${it.value}" } + "\n")
    println("库语料下载完成：${targetDirectory.absolutePath}")
}

/**
 * 先写入同目录临时文件，完整下载后再原子替换目标。
 * 网络中断时目标文件保持不变，避免残留非空半成品被后续运行误判为已下载。
 */
private fun downloadAtomically(url: String, target: File) {
    val temp = File.createTempFile("${target.name}-", ".part", target.parentFile)
    try {
        URI(url).toURL().openStream().use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        check(temp.length() > 0) { "下载内容为空：$url" }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        if (temp.exists()) temp.delete()
    }
}
