package com.muedsa.js.runtime.value

/**
 * 原始值包装对象（对应 Object(primitive) / new String(...) / new Number(...) / new Boolean(...)）。
 *
 * 内部保存原始值；ToPrimitive 会直接取出该原始值，
 * 因此字符串/数字/布尔原型方法可以正常作用于包装对象。
 */
class JSBoxedPrimitive(
    val primitive: JSValue,
    prototype: JSObject?,
) : JSObject(prototype) {

    override fun toPrimitiveString(): String = primitive.toPrimitiveString()
}
