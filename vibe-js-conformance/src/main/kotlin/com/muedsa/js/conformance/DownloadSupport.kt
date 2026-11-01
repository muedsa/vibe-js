package com.muedsa.js.conformance

import java.io.InputStream
import java.net.URI

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
