package com.muedsa.js.runtime.value

/**
 * ArrayBuffer 对象。
 *
 * 首版（L3.1）只实现固定长度缓冲区，不支持：
 * - 可调整大小（resizable / [[ArrayBufferMaxByteLength]]）；
 * - 分离（detach / [[ArrayBufferDetachKey]]）；
 * - transfer / transferToFixedLength。
 *
 * [[ArrayBufferData]] 使用 Kotlin 通用标准库的 [ByteArray] 保存，因此长度上限
 * 受 [Int.MAX_VALUE] 约束；更长的请求由构造器按规范抛 RangeError。
 *
 * 字节访问只对引擎内部（DataView，后续 TypedArray）开放：规范要求的
 * RangeError 由内建方法在调用前完成，本类只做防御性越界检查。
 */
class JSArrayBuffer(
    prototype: JSObject?,
    /** 字节长度（[[ArrayBufferByteLength]]）。 */
    val byteLength: Int,
) : JSObject(prototype) {

    /** [[ArrayBufferData]]：唯一的底层字节存储，不暴露给脚本。 */
    private val bytes: ByteArray = ByteArray(byteLength)

    /**
     * 是否已分离（[[ArrayBufferData]] 为 null 的状态）。
     *
     * L3.1 没有 detach/transfer，恒为 false；保留该属性便于后续扩展与
     * DataView 的分离检查直接接入。
     */
    val isDetached: Boolean get() = false

    /** 读取指定绝对下标的字节。 */
    internal fun getByte(index: Int): Byte {
        checkBounds(index, 1)
        return bytes[index]
    }

    /** 写入指定绝对下标的字节。 */
    internal fun setByte(index: Int, value: Byte) {
        checkBounds(index, 1)
        bytes[index] = value
    }

    /**
     * 把本缓冲区 [start, start + count) 的字节拷贝到 [target] 的 [targetStart]。
     *
     * 供 `ArrayBuffer.prototype.slice` 使用；两个区间都必须落在各自长度内。
     */
    internal fun copyInto(target: JSArrayBuffer, start: Int, targetStart: Int, count: Int) {
        checkBounds(start, count)
        target.checkBounds(targetStart, count)
        bytes.copyInto(target.bytes, targetStart, start, start + count)
    }

    /**
     * 防御性边界检查。
     *
     * 规范级检查（ToIndex + RangeError）由 DataView 等方法负责，这里的异常
     * 只表示内部调用越界，属于实现缺陷而非可被脚本捕获的 JS 错误。
     */
    private fun checkBounds(index: Int, count: Int) {
        if (index < 0 || count < 0 || count > byteLength || index > byteLength - count) {
            throw IndexOutOfBoundsException(
                "ArrayBuffer access out of bounds: index=$index, count=$count, byteLength=$byteLength"
            )
        }
    }
}
