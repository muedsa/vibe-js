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
    /** 自定义 [[Construct]]（如 bind 生成的绑定函数）；null 表示按普通内建构造规则处理。 */
    val constructFunction: (suspend (interpreter: Interpreter, args: List<JSValue>) -> JSValue)? = null,
    /** bind 生成的绑定函数所指向的目标函数（供 @@hasInstance 解包）。 */
    val boundTarget: JSValue? = null,
    val function: suspend (interpreter: Interpreter, thisValue: JSValue, args: List<JSValue>) -> JSValue,
) : JSObject(prototype) {

    override fun toPrimitiveString(): String = "function $name() { [native code] }"

    init {
        // 规范顺序：length 在 name 之前
        defineDataProperty(
            PropertyKey.LENGTH,
            JSNumber(length.toDouble()),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        defineDataProperty(
            PropertyKey.NAME,
            JSString(name),
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
