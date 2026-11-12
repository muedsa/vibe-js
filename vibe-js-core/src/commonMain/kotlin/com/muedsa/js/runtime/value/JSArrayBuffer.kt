package com.muedsa.js.runtime.value

/**
 * ArrayBuffer 对象。
 *
 * 当前（L3.5）支持固定长度缓冲区与分离（detach）语义，仍不支持：
 * - 可调整大小（resizable / [[ArrayBufferMaxByteLength]]）尚未实现；
 * - transfer / transferToFixedLength 已支持固定长度缓冲区。
 *
 * [[ArrayBufferData]] 使用 Kotlin 通用标准库的 [ByteArray] 保存，因此长度上限
 * 受 [Int.MAX_VALUE] 约束；更长的请求由构造器按规范抛 RangeError。
 *
 * 分离语义按规范：
 * - `detach()` 置位后 `IsDetachedBuffer` 为 true，`byteLength`（即
 *   [[ArrayBufferByteLength]]）按规范归 0；
 * - 底层字节不再可访问：所有字节入口都以 `byteLength` 做边界检查，分离后
 *   任何访问都会因长度为 0 被拒绝；JS 可见方法（slice、视图读写等）在调用前
 *   由内建按规范抛 TypeError。
 *
 * 字节访问只对引擎内部（DataView、TypedArray）开放：规范要求的
 * RangeError/TypeError 由内建方法在调用前完成，本类只做防御性越界检查。
 */
class JSArrayBuffer(
    prototype: JSObject?,
    /** 分配时的字节长度（[[ArrayBufferByteLength]] 的初始值）。 */
    byteLength: Int,
) : JSObject(prototype) {

    /** [[ArrayBufferData]]：唯一的底层字节存储，不暴露给脚本。 */
    private val bytes: ByteArray = ByteArray(byteLength)

    /**
     * 分离状态。
     *
     * 对应规范中 [[ArrayBufferData]] 为 null 的状态；分离后 [byteLength] 归 0，
     * 底层 [bytes] 虽仍由本对象持有，但所有入口都会因长度为 0 而拒绝访问。
     */
    var isDetached: Boolean = false
        private set

    /**
     * [[ArrayBufferByteLength]]：分离后按规范为 0。
     *
     * 注意：视图（DataView/TypedArray）的长度在对象构造时已固定，不随本属性
     * 变化；分离后视图自身的 `length`/`byteLength` 访问器另行返回 0。
     */
    val byteLength: Int get() = if (isDetached) 0 else bytes.size

    /**
     * 分离本缓冲区（对应规范 DetachArrayBuffer）。
     *
     * 只应在宿主/测试 harness 中调用：规范没有把 detach 暴露为 JS 方法
     * （transfer 系列方法另有实现）。重复调用是幂等的，方便 `$262.detachArrayBuffer`
     * 之类的宿主钩子重复调用而不改变结果。
     */
    fun detach() {
        isDetached = true
    }

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
     * 供 `ArrayBuffer.prototype.slice` 与 TypedArray 的同类型拷贝使用；
     * 两个区间都必须落在各自长度内。分离后 [byteLength] 为 0，任何非空拷贝
     * 都会被边界检查拒绝（JS 可见路径已在调用前抛过 TypeError）。
     */
    internal fun copyInto(target: JSArrayBuffer, start: Int, targetStart: Int, count: Int) {
        checkBounds(start, count)
        target.checkBounds(targetStart, count)
        bytes.copyInto(target.bytes, targetStart, start, start + count)
    }

    /**
     * 防御性边界检查。
     *
     * 规范级检查（ToIndex + RangeError / IsDetachedBuffer + TypeError）由 DataView
     * 等方法负责，这里的异常只表示内部调用越界，属于实现缺陷而非可被脚本捕获的
     * JS 错误。
     */
    private fun checkBounds(index: Int, count: Int) {
        if (index < 0 || count < 0 || count > byteLength || index > byteLength - count) {
            throw IndexOutOfBoundsException(
                "ArrayBuffer access out of bounds: index=$index, count=$count, byteLength=$byteLength"
            )
        }
    }
}
