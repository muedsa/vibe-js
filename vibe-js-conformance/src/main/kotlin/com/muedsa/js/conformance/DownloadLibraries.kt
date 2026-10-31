package com.muedsa.js.conformance

import java.io.File
import java.net.URI

/**
 * 下载固定版本的真实 JS 库 UMD 构建。
 */
fun main(args: Array<String>) {
    val targetDirectory = File(args.getOrElse(0) { "corpus/libraries" })
    targetDirectory.mkdirs()
    for (spec in librarySpecs) {
        val target = File(targetDirectory, "${spec.name}.js")
        if (target.isFile && target.length() > 0) {
            println("${spec.name}: 已存在")
            continue
        }
        println("下载 ${spec.name} ...")
        URI(spec.url).toURL().openStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }
    println("库语料下载完成：${targetDirectory.absolutePath}")
}