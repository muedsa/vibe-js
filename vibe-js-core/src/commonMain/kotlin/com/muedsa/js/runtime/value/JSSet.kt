package com.muedsa.js.runtime.value

/**
 * Set 对象。
 *
 * 使用插入顺序的值列表存储；值比较使用 SameValueZero（在内建模块中实现）。
 */
open class JSSet(
    prototype: JSObject?,
) : JSObject(prototype) {
    val values: MutableList<JSValue> = mutableListOf()
}
