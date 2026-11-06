package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArrayBuffer
import com.muedsa.js.runtime.value.JSDataView
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * DataView 构造器与 DataView.prototype。
 *
 * 构造签名：`new DataView(buffer, byteOffset = 0, byteLength = buffer.byteLength - byteOffset)`；
 * 读写方法的签名：`getXxx(byteOffset, littleEndian = false)` 与
 * `setXxx(byteOffset, value, littleEndian = false)`。所有数值按 IEEE-754 位模式
 * 与大小端在 [JSDataView] 中完成。
 */
object DataViewBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val prototype = intrinsics.dataViewPrototype

        val constructor = BuiltinHelpers.constructorWithNewTarget(
            intrinsics = intrinsics,
            name = "DataView",
            length = 1,
            prototypeObject = prototype,
        ) { interpreter, _, args, newTarget ->
            // 规范：DataView 只能作为构造函数调用
            if (!interpreter.nativeConstructCall) {
                interpreter.typeError("Constructor DataView requires 'new'")
            }
            // 规范：buffer 必须是带 [[ArrayBufferData]] 的对象（原始值/普通对象都抛 TypeError）
            val buffer = args.getOrElse(0) { JSUndefined } as? JSArrayBuffer
                ?: interpreter.typeError(
                    "First argument to DataView constructor must be an ArrayBuffer"
                )
            val bufferByteLength = buffer.byteLength.toLong()
            val byteOffset = interpreter.toIndex(args.getOrElse(1) { JSUndefined })
            if (byteOffset > bufferByteLength) {
                interpreter.rangeError(
                    "Start offset $byteOffset is outside the bounds of the DataView"
                )
            }
            val lengthArgument = args.getOrNull(2)
            val byteLength = if (lengthArgument == null || lengthArgument is JSUndefined) {
                // 缺省：窗口延伸到缓冲区末尾
                bufferByteLength - byteOffset
            } else {
                val requested = interpreter.toIndex(lengthArgument)
                if (byteOffset + requested > bufferByteLength) {
                    interpreter.rangeError("Invalid DataView length $requested")
                }
                requested
            }
            JSDataView(
                prototype = interpreter.prototypeFromNewTarget(newTarget, prototype),
                buffer = buffer,
                byteOffset = byteOffset.toInt(),
                byteLength = byteLength.toInt(),
            )
        }
        intrinsics.dataViewConstructor = constructor

        // 三个只读访问器：buffer / byteOffset / byteLength
        fun defineGetter(name: String, value: (JSDataView) -> JSValue) {
            prototype.defineAccessorProperty(
                key = PropertyKey.Str(name),
                get = BuiltinHelpers.method(intrinsics, "get $name", 0) { interpreter, thisValue, _ ->
                    val view = requireDataView(
                        interpreter,
                        thisValue,
                        "get DataView.prototype.$name",
                    )
                    value(view)
                },
                set = null,
                enumerable = false,
                configurable = true,
            )
        }
        defineGetter("buffer") { it.buffer }
        defineGetter("byteOffset") { JSNumber(it.byteOffset.toDouble()) }
        defineGetter("byteLength") { JSNumber(it.byteLength.toDouble()) }

        /** 定义一个读取方法：签名固定为 (byteOffset, littleEndian)。 */
        fun defineReadMethod(
            name: String,
            elementSize: Int,
            read: (JSDataView, index: Int, littleEndian: Boolean) -> JSValue,
        ) {
            prototype.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, name, 1) { interpreter, thisValue, args ->
                    val access = prepareViewAccess(
                        interpreter = interpreter,
                        thisValue = thisValue,
                        args = args,
                        methodName = name,
                        elementSize = elementSize,
                        // 读取方法签名：(byteOffset, littleEndian)
                        littleEndianArgIndex = 1,
                    )
                    read(access.view, access.index, access.littleEndian)
                },
            )
        }

        /** 定义一个写入方法：签名固定为 (byteOffset, value, littleEndian)，返回 undefined。 */
        fun defineWriteMethod(
            name: String,
            elementSize: Int,
            write: suspend (JSDataView, Int, Boolean, JSNumber, Interpreter) -> Unit,
        ) {
            prototype.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, name, 2) { interpreter, thisValue, args ->
                    val access = prepareViewAccess(
                        interpreter = interpreter,
                        thisValue = thisValue,
                        args = args,
                        methodName = name,
                        elementSize = elementSize,
                        // 写入方法签名：(byteOffset, value, littleEndian)
                        littleEndianArgIndex = 2,
                        valueArgIndex = 1,
                    )
                    write(
                        access.view,
                        access.index,
                        access.littleEndian,
                        requireNotNull(access.number),
                        interpreter,
                    )
                    JSUndefined
                },
            )
        }

        defineReadMethod("getInt8", 1) { view, index, _ ->
            JSNumber(view.getInt8(index).toDouble())
        }
        defineReadMethod("getUint8", 1) { view, index, _ ->
            JSNumber(view.getUint8(index).toDouble())
        }
        defineReadMethod("getInt16", 2) { view, index, littleEndian ->
            JSNumber(view.getInt16(index, littleEndian).toDouble())
        }
        defineReadMethod("getUint16", 2) { view, index, littleEndian ->
            JSNumber(view.getUint16(index, littleEndian).toDouble())
        }
        defineReadMethod("getInt32", 4) { view, index, littleEndian ->
            JSNumber(view.getInt32(index, littleEndian).toDouble())
        }
        defineReadMethod("getUint32", 4) { view, index, littleEndian ->
            JSNumber(view.getUint32(index, littleEndian).toDouble())
        }
        defineReadMethod("getFloat32", 4) { view, index, littleEndian ->
            JSNumber(view.getFloat32(index, littleEndian))
        }
        defineReadMethod("getFloat64", 8) { view, index, littleEndian ->
            JSNumber(view.getFloat64(index, littleEndian))
        }

        defineWriteMethod("setInt8", 1) { view, index, _, value, interpreter ->
            view.setInt8(index, interpreter.toInt32(value))
        }
        defineWriteMethod("setUint8", 1) { view, index, _, value, interpreter ->
            view.setUint8(index, interpreter.toInt32(value))
        }
        defineWriteMethod("setInt16", 2) { view, index, littleEndian, value, interpreter ->
            view.setInt16(index, interpreter.toInt32(value), littleEndian)
        }
        defineWriteMethod("setUint16", 2) { view, index, littleEndian, value, interpreter ->
            view.setUint16(index, interpreter.toInt32(value), littleEndian)
        }
        defineWriteMethod("setInt32", 4) { view, index, littleEndian, value, interpreter ->
            view.setInt32(index, interpreter.toInt32(value), littleEndian)
        }
        defineWriteMethod("setUint32", 4) { view, index, littleEndian, value, interpreter ->
            view.setUint32(index, interpreter.toUint32(value), littleEndian)
        }
        defineWriteMethod("setFloat32", 4) { view, index, littleEndian, value, interpreter ->
            view.setFloat32(index, interpreter.toNumber(value), littleEndian)
        }
        defineWriteMethod("setFloat64", 8) { view, index, littleEndian, value, interpreter ->
            view.setFloat64(index, interpreter.toNumber(value), littleEndian)
        }

        // @@toStringTag 写在原型上（规范：writable=false, enumerable=false, configurable=true）
        prototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("DataView"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        registry.defineGlobal("DataView", constructor)
    }

    /** GetViewValue/SetViewValue 的前置检查结果。 */
    private class ViewAccess(
        val view: JSDataView,
        /** 窗口内相对下标。 */
        val index: Int,
        /** 是否小端（单字节方法恒为 false）。 */
        val littleEndian: Boolean,
        /** 写入方法已转换的数值；读取方法为 null。 */
        val number: JSNumber?,
    )

    /**
     * 读写方法的公共前置检查（对应规范 GetViewValue / SetViewValue 的前几步）：
     * 品牌检查 → ToIndex(byteOffset) → ToBoolean(littleEndian)
     * →（写入时）ToNumber(value) → 越界 RangeError。
     *
     * 规范中 SetViewValue 的 ToNumber(value) 早于越界检查，但晚于 byteOffset 的
     * ToIndex，因此写入方法的 value 也必须在这里转换。
     *
     * @param littleEndianArgIndex littleEndian 在实参中的下标（读取方法为 1，写入方法为 2）；
     *   单字节方法传入 -1，不读取该参数（规范对 INT8/UINT8 传 true，无可观察差异）。
     * @param valueArgIndex 写入方法中 value 的下标；读取方法传 -1，不做值转换。
     */
    private suspend fun prepareViewAccess(
        interpreter: Interpreter,
        thisValue: JSValue,
        args: List<JSValue>,
        methodName: String,
        elementSize: Int,
        littleEndianArgIndex: Int,
        valueArgIndex: Int = -1,
    ): ViewAccess {
        val view = requireDataView(
            interpreter,
            thisValue,
            "DataView.prototype.$methodName",
        )
        val index = interpreter.toIndex(args.getOrElse(0) { JSUndefined })
        val littleEndian = elementSize > 1 && littleEndianArgIndex >= 0 &&
            interpreter.toBoolean(args.getOrElse(littleEndianArgIndex) { JSUndefined })
        val number = if (valueArgIndex >= 0) {
            JSNumber(interpreter.toNumber(args.getOrElse(valueArgIndex) { JSUndefined }))
        } else {
            null
        }
        if (index + elementSize > view.byteLength.toLong()) {
            interpreter.rangeError("Offset is outside the bounds of the DataView")
        }
        return ViewAccess(view, index.toInt(), littleEndian, number)
    }

    /** 品牌检查：DataView.prototype 上的方法只接受真正的 DataView。 */
    private fun requireDataView(
        interpreter: Interpreter,
        thisValue: JSValue,
        name: String,
    ): JSDataView = thisValue as? JSDataView
        ?: interpreter.typeError("$name requires that 'this' be a DataView")
}
