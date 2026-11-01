package com.muedsa.js.conformance

import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile

/** 语料下载的连接/读取超时，避免网络停滞拖死 CI Job。 */
private const val CONNECT_TIMEOUT_MILLIS = 30_000
private const val READ_TIMEOUT_MILLIS = 60_000

/**
 * 打开固定 URL 的下载流，并设置连接与读取超时。
 *
 * 固定语料源均为 HTTPS，HTTP 错误会由 [InputStream] 打开阶段抛出。
 */
internal fun openDownload(url: String): InputStream {
    val connection = URI(url).toURL().openConnection()
    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
    connection.readTimeout = READ_TIMEOUT_MILLIS
    return connection.getInputStream()
}

/**
 * 解压 ZIP 并去掉顶层目录。
 *
 * 使用 [ZipFile] 读取中央目录：截断/损坏的压缩包会直接抛错，
 * 不会留下看似完整的部分语料；同时拒绝逃逸目标目录的条目路径。
 */
internal fun extractZip(archive: File, targetDirectory: File) {
    val root = targetDirectory.canonicalFile.toPath()
    ZipFile(archive).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val relativePath = entry.name.substringAfter('/', missingDelimiterValue = "")
            if (relativePath.isEmpty()) continue
            val target = File(targetDirectory, relativePath).canonicalFile
            check(target.toPath().startsWith(root)) { "ZIP 条目路径非法：${entry.name}" }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
