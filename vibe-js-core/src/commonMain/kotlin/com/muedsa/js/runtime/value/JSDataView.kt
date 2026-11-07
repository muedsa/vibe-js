package com.muedsa.js.runtime.value

/**
 * DataView 对象：对 [JSArrayBuffer] 的一段窗口提供按类型读写。
 *
 * 本类只负责按 IEEE-754 位模式、64 位整数位模式与大小端组合/拆分字节；规范级前置检查
 * （品牌检查、IsDetachedBuffer、ToIndex、越界 RangeError、参数 ToNumber/ToBigInt）
 * 全部由 `DataViewBuiltins` 完成。
 *
 * 分离语义（L3.5）：`buffer.isDetached` 为 true 时所有 get/set 方法按规范抛 TypeError，
 * `byteOffset`/`byteLength` 访问器返回 0；本类自身不再重复判断，避免两处逻辑漂移。
 *
 * 位模式转换使用 Kotlin 通用标准库的 [Float.fromBits] / [Double.fromBits]
 * 与 [Float.toRawBits] / [Double.toRawBits]，在 JVM/JS/Wasm/Native 上保持一致。
 */
class JSDataView(
    prototype: JSObject?,
    /** [[ViewedArrayBuffer]]：被查看的缓冲区。 */
    val buffer: JSArrayBuffer,
    /** [[ByteOffset]]：窗口在缓冲区中的起始绝对下标。 */
    val byteOffset: Int,
    /** [[ByteLength]]：窗口长度。 */
    val byteLength: Int,
) : JSObject(prototype) {

    /** 被查看的缓冲区是否已分离（供内建访问器与读写前置检查使用）。 */
    val isDetached: Boolean get() = buffer.isDetached

    // ================================================================
    // 读取
    // ================================================================

    /** getInt8：有符号单字节。 */
    internal fun getInt8(index: Int): Int =
        buffer.getByte(byteOffset + index).toInt()

    /** getUint8：无符号单字节。 */
    internal fun getUint8(index: Int): Int = byteAt(index)

    /** getInt16：按大小端组合两个字节后按有符号 16 位解释。 */
    internal fun getInt16(index: Int, littleEndian: Boolean): Int =
        readBits16(index, littleEndian).toShort().toInt()

    /** getUint16：按大小端组合两个字节。 */
    internal fun getUint16(index: Int, littleEndian: Boolean): Int =
        readBits16(index, littleEndian)

    /** getInt32：按大小端组合四个字节。 */
    internal fun getInt32(index: Int, littleEndian: Boolean): Int =
        readBits32(index, littleEndian)

    /** getUint32：按大小端组合四个字节后按无符号 32 位解释（以 Long 返回）。 */
    internal fun getUint32(index: Int, littleEndian: Boolean): Long =
        readBits32(index, littleEndian).toLong() and 0xFFFFFFFFL

    /** getFloat32：四个字节按 IEEE-754 单精度位模式解释。 */
    internal fun getFloat32(index: Int, littleEndian: Boolean): Double =
        Float.fromBits(readBits32(index, littleEndian)).toDouble()

    /** getFloat64：八个字节按 IEEE-754 双精度位模式解释。 */
    internal fun getFloat64(index: Int, littleEndian: Boolean): Double =
        Double.fromBits(readBits64(index, littleEndian))

    /** getBigInt64：八个字节按有符号 64 位整数解释。 */
    internal fun getBigInt64(index: Int, littleEndian: Boolean): JSBigInt =
        JSBigInt.of(readBits64(index, littleEndian))

    /** getBigUint64：八个字节按无符号 64 位整数解释。 */
    internal fun getBigUint64(index: Int, littleEndian: Boolean): JSBigInt =
        JSBigInt.of(readBits64(index, littleEndian)).toUint64()

    // ================================================================
    // 写入
    // ================================================================

    /** setInt8：写入低 8 位。 */
    internal fun setInt8(index: Int, value: Int) {
        writeByteAt(index, value)
    }

    /** setUint8：写入低 8 位（与 setInt8 位模式相同）。 */
    internal fun setUint8(index: Int, value: Int) {
        writeByteAt(index, value)
    }

    /** setInt16：写入低 16 位。 */
    internal fun setInt16(index: Int, value: Int, littleEndian: Boolean) {
        writeBits16(index, value, littleEndian)
    }

    /** setUint16：写入低 16 位。 */
    internal fun setUint16(index: Int, value: Int, littleEndian: Boolean) {
        writeBits16(index, value, littleEndian)
    }

    /** setInt32：写入完整 32 位。 */
    internal fun setInt32(index: Int, value: Int, littleEndian: Boolean) {
        writeBits32(index, value, littleEndian)
    }

    /** setUint32：写入无符号 32 位值的低 32 位。 */
    internal fun setUint32(index: Int, value: Long, littleEndian: Boolean) {
        writeBits32(index, value.toInt(), littleEndian)
    }

    /** setFloat32：把数值转成 IEEE-754 单精度位模式后写入。 */
    internal fun setFloat32(index: Int, value: Double, littleEndian: Boolean) {
        writeBits32(index, value.toFloat().toRawBits(), littleEndian)
    }

    /** setFloat64：把数值转成 IEEE-754 双精度位模式后写入。 */
    internal fun setFloat64(index: Int, value: Double, littleEndian: Boolean) {
        writeBits64(index, value.toRawBits(), littleEndian)
    }

    /** setBigInt64：写入已按 ToBigInt64 取出的低 64 位位模式。 */
    internal fun setBigInt64(index: Int, bits: Long, littleEndian: Boolean) {
        writeBits64(index, bits, littleEndian)
    }

    /** setBigUint64：写入已按 ToBigUint64 取出的低 64 位位模式（与 setBigInt64 相同）。 */
    internal fun setBigUint64(index: Int, bits: Long, littleEndian: Boolean) {
        writeBits64(index, bits, littleEndian)
    }

    // ================================================================
    // 字节组合/拆分
    // ================================================================

    /** 读取窗口内 [index] 处的字节并按无符号解释。 */
    private fun byteAt(index: Int): Int =
        buffer.getByte(byteOffset + index).toInt() and 0xFF

    /** 写入窗口内 [index] 处的字节（只取低 8 位）。 */
    private fun writeByteAt(index: Int, value: Int) {
        buffer.setByte(byteOffset + index, value.toByte())
    }

    /** 组合 16 位：小端时低字节在前。 */
    private fun readBits16(index: Int, littleEndian: Boolean): Int {
        val first = byteAt(index)
        val second = byteAt(index + 1)
        return if (littleEndian) first or (second shl 8) else (first shl 8) or second
    }

    /** 组合 32 位：小端时低字节在前。 */
    private fun readBits32(index: Int, littleEndian: Boolean): Int {
        val first = byteAt(index)
        val second = byteAt(index + 1)
        val third = byteAt(index + 2)
        val fourth = byteAt(index + 3)
        return if (littleEndian) {
            first or (second shl 8) or (third shl 16) or (fourth shl 24)
        } else {
            (first shl 24) or (second shl 16) or (third shl 8) or fourth
        }
    }

    /** 组合 64 位：小端时低 32 位字在前。 */
    private fun readBits64(index: Int, littleEndian: Boolean): Long {
        val first = readBits32(index, littleEndian).toLong() and 0xFFFFFFFFL
        val second = readBits32(index + 4, littleEndian).toLong() and 0xFFFFFFFFL
        return if (littleEndian) first or (second shl 32) else (first shl 32) or second
    }

    /** 拆分 16 位。 */
    private fun writeBits16(index: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            writeByteAt(index, value)
            writeByteAt(index + 1, value ushr 8)
        } else {
            writeByteAt(index, value ushr 8)
            writeByteAt(index + 1, value)
        }
    }

    /** 拆分 32 位。 */
    private fun writeBits32(index: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            writeByteAt(index, value)
            writeByteAt(index + 1, value ushr 8)
            writeByteAt(index + 2, value ushr 16)
            writeByteAt(index + 3, value ushr 24)
        } else {
            writeByteAt(index, value ushr 24)
            writeByteAt(index + 1, value ushr 16)
            writeByteAt(index + 2, value ushr 8)
            writeByteAt(index + 3, value)
        }
    }

    /** 拆分 64 位：小端时低 32 位字在前。 */
    private fun writeBits64(index: Int, value: Long, littleEndian: Boolean) {
        val high = (value ushr 32).toInt()
        val low = value.toInt()
        if (littleEndian) {
            writeBits32(index, low, true)
            writeBits32(index + 4, high, true)
        } else {
            writeBits32(index, high, false)
            writeBits32(index + 4, low, false)
        }
    }
}
