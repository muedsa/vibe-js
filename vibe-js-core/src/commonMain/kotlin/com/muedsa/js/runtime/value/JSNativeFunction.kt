package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.Interpreter

/**
 * 原生（Kotlin 实现）函数。
 *
 * @property name 函数名（用于 name 属性与错误信息）
 * @property length 形参个数（用于 length 属性）
 * @property functionPrototypeObject 作为构造函数时的 `.prototype` 对象
 * @property function 实际执行逻辑；suspend 以支持 await / 异步内建
 */
class JSNativeFunction(
    val name: String,
    val length: Int = 0,
    prototype: JSObject? = null,
    val functionPrototypeObject: JSObject? = null,
    val function: suspend (interpreter: Interpreter, thisValue: JSValue, args: List<JSValue>) -> JSValue,
) : JSObject(prototype) {

    override fun toPrimitiveString(): String = "function $name() { [native code] }"

    init {
        defineDataProperty(
            PropertyKey.NAME,
            JSString(name),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        defineDataProperty(
            PropertyKey.LENGTH,
            JSNumber(length.toDouble()),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        if (functionPrototypeObject != null) {
            defineDataProperty(
                PropertyKey.PROTOTYPE,
                functionPrototypeObject,
                writable = false,
                enumerable = false,
                configurable = false,
            )
            functionPrototypeObject.defineDataProperty(
                PropertyKey.CONSTRUCTOR,
                this,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
    }
}
