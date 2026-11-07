package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.toInt32Number
import com.muedsa.js.runtime.toUint32Number
import kotlin.math.floor

/**
 * TypedArray 的元素类型。
 *
 * [typeName] 是规范中的构造器/原型名称，同时用于 `Symbol.toStringTag` 与错误信息；
 * [bytesPerElement] 是每个元素的字节宽度，即 `BYTES_PER_ELEMENT` 的值；
 * [isBigIntContent] 对应规范 Table 62 的 [[ContentType]]：BigInt 视图的元素读写
 * 必须使用 ToBigInt 系列转换，而 Number 视图使用 ToNumber 系列转换。
 *
 * 类型顺序与规范 Table 62 一致（BigInt64/BigUint64 位于 Float32/Float64 之前），
 * 安装构造器时按该顺序注册全局对象。
 */
enum class JSTypedArrayType(
    val typeName: String,
    val bytesPerElement: Int,
    /** [[ContentType]] 是否为 BigInt（只有 BigInt64Array/BigUint64Array 为 true）。 */
    val isBigIntContent: Boolean = false,
) {
    INT8("Int8Array", 1),
    UINT8("Uint8Array", 1),
    UINT8_CLAMPED("Uint8ClampedArray", 1),
    INT16("Int16Array", 2),
    UINT16("Uint16Array", 2),
    INT32("Int32Array", 4),
    UINT32("Uint32Array", 4),
    BIGINT64("BigInt64Array", 8, isBigIntContent = true),
    BIGUINT64("BigUint64Array", 8, isBigIntContent = true),
    FLOAT32("Float32Array", 4),
    FLOAT64("Float64Array", 8),
}

/**
 * TypedArray 对象（整数索引异质对象）。
 *
 * 存储模型：
 * - 元素不存放在属性表里，而是直接映射到 [buffer] 的字节区间
 *   `[byteOffset, byteOffset + byteLength)`；索引属性由本类按规范合成描述符；
 * - 其余属性（例如 `ta.foo = 1`）沿用 [JSObject] 的普通属性表；
 * - `length` / `buffer` / `byteOffset` / `byteLength` / `BYTES_PER_ELEMENT`
 *   不是自有属性，由 `TypedArrayBuiltins` 安装为原型访问器或原型数据属性。
 *
 * 规范行为（[getOwnProperty] / [defineOwnProperty] / [deleteOwnProperty] /
 * [ownPropertyKeys]）参考 Integer-Indexed Exotic Objects：
 * - 规范数字索引字符串（"-0" 除外）走异质路径，其余键走普通属性路径；
 * - 合法索引恒为 `{ writable: true, enumerable: true, configurable: true }`；
 * - 越界或非整数索引视为"不存在"，写入静默失败（严格模式由 Interpreter 抛 TypeError）；
 * - 底层缓冲区分离后 IsValidIntegerIndex 恒为 false：读取 undefined、不存在索引键，
 *   `length`/`byteLength` 访问器返回 0（访问器由 `TypedArrayBuiltins` 实现）。
 *
 * 字节读写复用 [JSDataView] 的位模式组合/拆分：内部持有一个覆盖同一字节区间的
 * 私有视图，元素索引乘元素宽度即为视图内字节偏移。TypedArray 固定使用小端序。
 *
 * BigInt64Array/BigUint64Array 的 [[ContentType]] 是 BigInt：`getElement` 返回
 * [JSBigInt] 原始值，写入由调用方（解释器）先执行 ToBigInt64/ToBigUint64，
 * 再把低 64 位位模式交给 `setBigInt64Element`/`setBigUint64Element`。
 */
class JSTypedArray(
    prototype: JSObject?,
    /** 元素类型（决定 [[TypedArrayName]] 与元素宽度）。 */
    val type: JSTypedArrayType,
    /** [[ViewedArrayBuffer]]：底层缓冲区。 */
    val buffer: JSArrayBuffer,
    /** [[ByteOffset]]：视图在缓冲区中的起始字节下标。 */
    val byteOffset: Int,
    /** [[ArrayLength]]：元素个数（不是字节长度）。 */
    val length: Int,
) : JSObject(prototype) {

    companion object {
        /** TypedArray 统一使用小端序（与主流宿主一致）。 */
        private const val LITTLE_ENDIAN = true
    }

    /** 每个元素的字节宽度（`BYTES_PER_ELEMENT`）。 */
    val bytesPerElement: Int get() = type.bytesPerElement

    /**
     * [[ByteLength]]：视图占用的字节数。
     *
     * 这是对象构造时固定的原始长度；分离后内建访问器按规范返回 0，但本属性
     * 保持不变，供 `subarray` 等需要"分离前长度"的算法使用。
     */
    val byteLength: Int get() = length * type.bytesPerElement

    /**
     * 底层缓冲区是否已分离。
     *
     * 分离后 Integer-Indexed 异质对象的元素读写、`length`/`byteLength` 访问器
     * 都按规范切换到 0/undefined 分支。
     */
    val isDetached: Boolean get() = buffer.isDetached

    /**
     * 内部字节视图：只用于按元素类型读写，不对外暴露。
     *
     * 构造参数已由调用方保证落在缓冲区范围内，这里不做重复校验。
     */
    private val view: JSDataView = JSDataView(
        prototype = null,
        buffer = buffer,
        byteOffset = byteOffset,
        byteLength = byteLength,
    )

    // ================================================================
    // 数字索引判定（CanonicalNumericIndexString）
    // ================================================================

    /** 数字索引属性的判定结果。 */
    internal sealed interface IndexKey {
        /** 不是规范数字索引字符串：按普通自有属性处理。 */
        data object NotIndex : IndexKey

        /** 是规范数字索引字符串，但不是合法元素下标：属性"不存在"。 */
        data object Invalid : IndexKey

        /** 合法元素下标。 */
        data class Valid(val index: Int) : IndexKey
    }

    /**
     * 判定属性键是否命中整数索引异质路径。
     *
     * 规范数字索引字符串指 `ToString(ToNumber(s)) === s` 的字符串（"-0" 除外，
     * 因为 `ToString(-0)` 是 "0"）；命中后还要求是非负整数且小于 [length]。
     *
     * 底层缓冲区分离后 IsValidIntegerIndex 恒为 false，因此所有规范数字索引
     * 都归入 [IndexKey.Invalid]：读取得到 undefined，属性不存在。
     */
    internal fun classifyIndex(key: PropertyKey): IndexKey {
        val text = (key as? PropertyKey.Str)?.value ?: return IndexKey.NotIndex
        val numeric = canonicalNumericIndexString(text) ?: return IndexKey.NotIndex
        // IsValidIntegerIndex：分离缓冲区上的任何数字索引都无效
        if (buffer.isDetached) return IndexKey.Invalid
        // IsInteger：NaN 与小数都不是合法下标
        if (numeric.isNaN() || numeric != floor(numeric)) return IndexKey.Invalid
        // IsValidIntegerIndex：-0、负数与越界都非法（-0 实际已在规范字符串判定被排除）
        if (numeric == 0.0 && 1.0 / numeric < 0.0) return IndexKey.Invalid
        if (numeric < 0.0 || numeric >= length.toDouble()) return IndexKey.Invalid
        return IndexKey.Valid(numeric.toInt())
    }

    // ================================================================
    // 元素读写（调用方负责确保 index 合法）
    // ================================================================

    /** 按元素类型读取下标 [index] 的值。 */
    internal fun getElement(index: Int): JSValue {
        // 分离后 IsValidIntegerIndex 恒为 false，内建按规范读到 undefined；
        // 回调中途分离缓冲区的用例（如 forEach 的 callbackfn-detachbuffer）依赖该分支。
        if (buffer.isDetached) return JSUndefined
        val byteIndex = index * bytesPerElement
        return when (type) {
            JSTypedArrayType.INT8 -> JSNumber(view.getInt8(byteIndex).toDouble())
            JSTypedArrayType.UINT8 -> JSNumber(view.getUint8(byteIndex).toDouble())
            JSTypedArrayType.UINT8_CLAMPED -> JSNumber(view.getUint8(byteIndex).toDouble())
            JSTypedArrayType.INT16 ->
                JSNumber(view.getInt16(byteIndex, LITTLE_ENDIAN).toDouble())

            JSTypedArrayType.UINT16 ->
                JSNumber(view.getUint16(byteIndex, LITTLE_ENDIAN).toDouble())

            JSTypedArrayType.INT32 ->
                JSNumber(view.getInt32(byteIndex, LITTLE_ENDIAN).toDouble())

            JSTypedArrayType.UINT32 ->
                JSNumber(view.getUint32(byteIndex, LITTLE_ENDIAN).toDouble())

            JSTypedArrayType.BIGINT64 ->
                view.getBigInt64(byteIndex, LITTLE_ENDIAN)

            JSTypedArrayType.BIGUINT64 ->
                view.getBigUint64(byteIndex, LITTLE_ENDIAN)

            JSTypedArrayType.FLOAT32 ->
                JSNumber(view.getFloat32(byteIndex, LITTLE_ENDIAN))

            JSTypedArrayType.FLOAT64 ->
                JSNumber(view.getFloat64(byteIndex, LITTLE_ENDIAN))
        }
    }

    /**
     * 写入 1/2/4 字节整数元素（只保留低位）。
     *
     * 调用方已完成 ToInt32（Int8/Uint8/Int16/Uint16/Int32 共用该入口）；
     * Uint32 走 [setUint32Element] 以保留无符号 32 位值。
     */
    internal fun setIntElement(index: Int, value: Int) {
        // 分离后 Integer-Indexed [[Set]] 静默失败（规范返回 true），写入直接丢弃
        if (buffer.isDetached) return
        val byteIndex = index * bytesPerElement
        when (type) {
            JSTypedArrayType.INT8, JSTypedArrayType.UINT8 ->
                view.setInt8(byteIndex, value)

            JSTypedArrayType.INT16, JSTypedArrayType.UINT16 ->
                view.setInt16(byteIndex, value, LITTLE_ENDIAN)

            JSTypedArrayType.INT32 ->
                view.setInt32(byteIndex, value, LITTLE_ENDIAN)

            else -> error("setIntElement 不支持的元素类型：$type")
        }
    }

    /** 写入 Uint32 元素（低 32 位）。 */
    internal fun setUint32Element(index: Int, value: Long) {
        if (buffer.isDetached) return
        view.setUint32(index * bytesPerElement, value, LITTLE_ENDIAN)
    }

    /** 写入 Uint8Clamped 元素：先按 ToUint8Clamp 舍入为 0..255。 */
    internal fun setUint8ClampedElement(index: Int, value: Double) {
        if (buffer.isDetached) return
        view.setUint8(index * bytesPerElement, toUint8Clamp(value))
    }

    /** 写入 Float32 元素（数值先转换为单精度）。 */
    internal fun setFloat32Element(index: Int, value: Double) {
        if (buffer.isDetached) return
        view.setFloat32(index * bytesPerElement, value, LITTLE_ENDIAN)
    }

    /** 写入 Float64 元素。 */
    internal fun setFloat64Element(index: Int, value: Double) {
        if (buffer.isDetached) return
        view.setFloat64(index * bytesPerElement, value, LITTLE_ENDIAN)
    }

    /**
     * 写入 BigInt64 元素。
     *
     * [bits] 是调用方执行 ToBigInt64 后得到的低 64 位位模式（Long 的补码形式），
     * 因此这里不再做数值转换，直接按小端序写入 8 字节。
     */
    internal fun setBigInt64Element(index: Int, bits: Long) {
        if (buffer.isDetached) return
        view.setBigInt64(index * bytesPerElement, bits, LITTLE_ENDIAN)
    }

    /**
     * 写入 BigUint64 元素。
     *
     * 与 [setBigInt64Element] 位模式完全相同：ToBigInt64 与 ToBigUint64 只差
     * 数学解释，低 64 位字节一致。
     */
    internal fun setBigUint64Element(index: Int, bits: Long) {
        if (buffer.isDetached) return
        view.setBigUint64(index * bytesPerElement, bits, LITTLE_ENDIAN)
    }

    /**
     * 字节级拷贝另一个同类型 TypedArray 的全部元素。
     *
     * 用于 `new T(另一个同类型 TypedArray)` 与 `set` 的快速路径；
     * 长度不匹配时只拷贝两者较短的部分（调用方已按规范做长度检查）。
     */
    internal fun copyElementsFrom(source: JSTypedArray, count: Int) {
        source.buffer.copyInto(
            target = buffer,
            start = source.byteOffset,
            targetStart = byteOffset,
            count = count * bytesPerElement,
        )
    }

    // ================================================================
    // Integer-Indexed Exotic [[GetOwnProperty]] / [[HasProperty]] /
    // [[DefineOwnProperty]] / [[Delete]] / [[OwnPropertyKeys]]
    // ================================================================

    override fun getOwnProperty(key: PropertyKey): PropertyDescriptor? =
        when (val classified = classifyIndex(key)) {
            JSTypedArray.IndexKey.NotIndex -> super.getOwnProperty(key)
            JSTypedArray.IndexKey.Invalid -> null
            is JSTypedArray.IndexKey.Valid -> PropertyDescriptor.data(
                value = getElement(classified.index),
                writable = true,
                enumerable = true,
                configurable = true,
            )
        }

    override fun hasOwnProperty(key: PropertyKey): Boolean =
        when (classifyIndex(key)) {
            JSTypedArray.IndexKey.NotIndex -> super.hasOwnProperty(key)
            JSTypedArray.IndexKey.Invalid -> false
            is JSTypedArray.IndexKey.Valid -> true
        }

    /**
     * 非挂起的定义入口。
     *
     * JS 可见路径（`Object.defineProperty`、赋值、`Object.freeze` 等）都会经过
     * [com.muedsa.js.runtime.Interpreter.defineOwnPropertyOn]，那里可以执行
     * ToNumber（可能调用 valueOf/toString）。本方法只处理已经转换成数字的值，
     * 其余情况返回 false，避免在非挂起上下文里执行 JS。
     */
    override fun defineOwnProperty(key: PropertyKey, descriptor: PropertyDescriptor): Boolean =
        when (val classified = classifyIndex(key)) {
            JSTypedArray.IndexKey.NotIndex -> super.defineOwnProperty(key, descriptor)
            JSTypedArray.IndexKey.Invalid -> false
            is JSTypedArray.IndexKey.Valid -> {
                // 规范：禁止把索引改成不可配置/不可枚举/不可写，也禁止访问器描述符
                if (descriptor.isAccessor ||
                    !descriptor.configurable || !descriptor.enumerable || !descriptor.writable
                ) {
                    return false
                }
                val value = descriptor.value
                if (type.isBigIntContent) {
                    // BigInt 视图：值必须是 BigInt（Number 按规范应抛 TypeError，
                    // JS 可见路径已在挂起端完成 ToBigInt，这里无法再执行转换）
                    if (value != null && value !is JSBigInt && value !is JSUndefined) return false
                    writeBigInt(classified.index, value as? JSBigInt ?: JSBigInt.ZERO)
                } else {
                    // Number 视图：值必须是 Number 或 undefined（BigInt 不得隐式转 Number）
                    if (value != null && value !is JSNumber && value !is JSUndefined) return false
                    val number = (value as? JSNumber)?.value ?: Double.NaN
                    writeNumber(classified.index, number)
                }
                true
            }
        }

    override fun deleteOwnProperty(key: PropertyKey): Boolean =
        when (classifyIndex(key)) {
            JSTypedArray.IndexKey.NotIndex -> super.deleteOwnProperty(key)
            // 规范：非法数字索引本来就不存在，删除视为成功
            JSTypedArray.IndexKey.Invalid -> true
            // 合法索引不可删除
            is JSTypedArray.IndexKey.Valid -> false
        }

    override fun ownPropertyKeys(): List<PropertyKey> {
        val ordinary = super.ownPropertyKeys()
        val keys = ArrayList<PropertyKey>(length + ordinary.size)
        // 规范：分离缓冲区上的 [[OwnPropertyKeys]] 不产生任何整数索引键，
        // 因此 Object.keys/for-in 在分离后只看到普通属性
        if (!buffer.isDetached) {
            // 整数索引按升序排在普通字符串键之前
            for (index in 0 until length) {
                keys.add(PropertyKey.Str(index.toString()))
            }
        }
        for (key in ordinary) {
            // 规范数字索引字符串无法成为普通属性；过滤掉以防内部入口写入后泄漏
            if (key is PropertyKey.Str && canonicalNumericIndexString(key.value) != null) continue
            keys.add(key)
        }
        return keys
    }

    /** 按类型把已转换的数值写入元素（供非挂起定义入口使用）。 */
    private fun writeNumber(index: Int, value: Double) {
        when (type) {
            JSTypedArrayType.INT8, JSTypedArrayType.UINT8,
            JSTypedArrayType.INT16, JSTypedArrayType.UINT16,
            JSTypedArrayType.INT32,
            -> setIntElement(index, toInt32Number(value))

            JSTypedArrayType.UINT32 -> setUint32Element(index, toUint32Number(value))
            JSTypedArrayType.UINT8_CLAMPED -> setUint8ClampedElement(index, value)
            JSTypedArrayType.FLOAT32 -> setFloat32Element(index, value)
            JSTypedArrayType.FLOAT64 -> setFloat64Element(index, value)
            // BigInt 视图不接受 Number：调用方（defineOwnProperty）已分支到 writeBigInt
            JSTypedArrayType.BIGINT64, JSTypedArrayType.BIGUINT64 ->
                error("BigInt 视图不能写入 Number 值")
        }
    }

    /**
     * 把 BigInt 写入 BigInt64/BigUint64 元素（非挂起定义入口使用）。
     *
     * 入参已经过 JS 侧 ToBigInt 转换，这里只需取低 64 位位模式；
     * ToBigInt64 与 ToBigUint64 的位模式相同。
     */
    private fun writeBigInt(index: Int, value: JSBigInt) {
        val bits = value.toInt64Bits()
        when (type) {
            JSTypedArrayType.BIGINT64 -> setBigInt64Element(index, bits)
            JSTypedArrayType.BIGUINT64 -> setBigUint64Element(index, bits)
            else -> error("非 BigInt 视图不能写入 BigInt 值")
        }
    }
}

/**
 * CanonicalNumericIndexString：字符串是某个数字的规范字符串表示时返回该数字。
 *
 * 注意规范第 1 步专门把 "-0" 映射为 -0（否则 `ToString(-0)` 是 "0" 会被误判），
 * 由此 "-0" 也走整数索引异质路径，但作为下标永远非法。
 * 其余字符串使用 [NumberFormatting.toString] 比较，保证与 JS 的 Number::toString 一致：
 * "+1"、"01"、"1." 等都不属于规范数字索引字符串。
 */
internal fun canonicalNumericIndexString(text: String): Double? {
    if (text == "-0") return -0.0
    // 双精度最短规范表示最长 24 个字符，超长输入直接排除，避免无谓解析
    if (text.isEmpty() || text.length > 32) return null
    val number = text.toDoubleOrNull() ?: return null
    return if (NumberFormatting.toString(number) == text) number else null
}

/**
 * ToUint8Clamp：NaN/负数归 0，超过 255 归 255，恰好 .5 时取偶数。
 */
private fun toUint8Clamp(number: Double): Int {
    if (number.isNaN() || number <= 0.0) return 0
    if (number >= 255.0) return 255
    val floorValue = floor(number).toInt()
    val fraction = number - floorValue
    return when {
        fraction < 0.5 -> floorValue
        fraction > 0.5 -> floorValue + 1
        // 恰好一半：取偶数（银行家舍入）
        floorValue % 2 == 0 -> floorValue
        else -> floorValue + 1
    }
}
