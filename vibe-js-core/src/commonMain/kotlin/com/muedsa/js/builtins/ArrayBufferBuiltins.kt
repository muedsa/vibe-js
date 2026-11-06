package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSDataView
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSTypedArray
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * ArrayBuffer 构造器与 ArrayBuffer.prototype。
 *
 * 首版（L3.1）不支持 resizable / detachable / transfer：
 * - `byteLength` 为只读访问器；
 * - `slice` 通过 SpeciesConstructor 创建副本，默认回到 %ArrayBuffer%；
 * - `isView` 识别 DataView 与全部 TypedArray（L3.2 接入）。
 */
object ArrayBufferBuiltins {

    /**
     * 底层 [ByteArray] 的长度上限。
     *
     * ToIndex 允许到 2^53-1，但 Kotlin 通用标准库的 ByteArray 以 Int 索引，
     * 超出上限的分配请求按规范语义抛 RangeError（"分配失败"）。
     */
    private const val MAX_BYTE_LENGTH = Int.MAX_VALUE

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.arrayBufferPrototype

        val constructor = BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = "ArrayBuffer",
            length = 1,
            prototypeObject = prototype,
        ) { interpreter, _, args, newTarget ->
            // 规范：ArrayBuffer 只能作为构造函数调用
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor ArrayBuffer requires 'new'")
            }
            // 规范：length 为 undefined 时按 0 处理，否则 ToIndex
            val byteLength = interpreter.toIndex(args.getOrElse(0) { JSUndefined })
            // 规范顺序：ToIndex 之后先由 OrdinaryCreateFromConstructor 解析实例原型
            // （可能触发 NewTarget.prototype getter），最后才分配字节存储
            val instancePrototype =
                interpreter.prototypeFromNewTarget(newTarget, prototype)
            if (byteLength > MAX_BYTE_LENGTH.toLong()) {
                interpreter.rangeError("Array buffer allocation failed")
            }
            JSArrayBuffer(
                prototype = instancePrototype,
                byteLength = byteLength.toInt(),
            )
        }
        intrinsics.arrayBufferConstructor = constructor

        // ArrayBuffer.prototype.byteLength 访问器：品牌检查后返回长度
        prototype.defineAccessorProperty(
            key = PropertyKey.Str("byteLength"),
            get = BuiltinHelpers.method(intrinsics, "get byteLength", 0) { interpreter, thisValue, _ ->
                val buffer = requireArrayBuffer(
                    interpreter,
                    thisValue,
                    "ArrayBuffer.prototype.byteLength",
                )
                JSNumber(buffer.byteLength.toDouble())
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        // ArrayBuffer.prototype.slice(start, end)：按 species 创建副本并拷贝
        prototype.defineDataProperty(
            key = PropertyKey.Str("slice"),
            value = BuiltinHelpers.method(intrinsics, "slice", 2) { interpreter, thisValue, args ->
                val buffer = requireArrayBuffer(
                    interpreter,
                    thisValue,
                    "ArrayBuffer.prototype.slice",
                )
                val length = buffer.byteLength
                val first = relativeIndex(
                    interpreter,
                    args.getOrElse(0) { JSUndefined },
                    length,
                )
                val endArgument = args.getOrNull(1)
                val last = if (endArgument == null || endArgument is JSUndefined) {
                    length.toLong()
                } else {
                    relativeIndex(interpreter, endArgument, length)
                }
                val newLength = (last - first).coerceAtLeast(0L)
                // SpeciesConstructor(O, %ArrayBuffer%)：读取 constructor / @@species；
                // 简化点：不实现 IsDetachedBuffer 分支（首版没有分离能力）。
                val species = interpreter.speciesConstructor(
                    buffer,
                    intrinsics.arrayBufferConstructor,
                )
                val created = interpreter.construct(
                    species,
                    listOf(JSNumber(newLength.toDouble())),
                )
                val result = created as? JSArrayBuffer
                    ?: interpreter.typeError(
                        "Species constructor did not return an ArrayBuffer"
                    )
                if (result === buffer) {
                    interpreter.typeError("Species constructor returned the same ArrayBuffer")
                }
                if (result.byteLength.toLong() < newLength) {
                    interpreter.typeError("Species constructor returned a too-small ArrayBuffer")
                }
                buffer.copyInto(
                    target = result,
                    start = first.toInt(),
                    targetStart = 0,
                    count = newLength.toInt(),
                )
                result
            },
        )

        // ArrayBuffer.isView(arg)：DataView 与 9 种 TypedArray 都是视图对象
        constructor.defineDataProperty(
            key = PropertyKey.Str("isView"),
            value = BuiltinHelpers.method(intrinsics, "isView", 1) { _, _, args ->
                val argument = args.getOrElse(0) { JSUndefined }
                JSBoolean.getJsBoolean(argument is JSDataView || argument is JSTypedArray)
            },
        )

        // ArrayBuffer[@@species]：getter 直接返回 this（与 Map/Set 一致）
        constructor.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolSpecies),
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.species]", 0) { _, thisValue, _ ->
                thisValue
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        // @@toStringTag 写在原型上（规范：writable=false, enumerable=false, configurable=true）
        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("ArrayBuffer"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        registry.defineGlobal("ArrayBuffer", constructor)
    }

    /** 品牌检查：ArrayBuffer.prototype 上的方法只接受真正的 ArrayBuffer。 */
    private fun requireArrayBuffer(
        interpreter: Interpreter,
        thisValue: JSValue,
        name: String,
    ): JSArrayBuffer = thisValue as? JSArrayBuffer
        ?: interpreter.typeError("$name requires that 'this' be an ArrayBuffer")

    /**
     * slice 的 start/end 归一：ToIntegerOrInfinity 后按规范处理负数与上限。
     *
     * 结果落在 [0, length]；-Infinity 归一为 0，+Infinity 归一为 length。
     */
    private suspend fun relativeIndex(
        interpreter: Interpreter,
        value: JSValue,
        length: Int,
    ): Long {
        val number = interpreter.toNumber(value)
        val integer = when {
            number.isNaN() || number == 0.0 -> 0.0
            !number.isFinite() -> number
            else -> kotlin.math.truncate(number)
        }
        return when {
            integer < 0.0 -> kotlin.math.max(length + integer, 0.0).toLong()
            else -> kotlin.math.min(integer, length.toDouble()).toLong()
        }
    }
}
