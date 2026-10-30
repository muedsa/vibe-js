package com.muedsa.js.runtime.value

/**
 * Map 对象。
 *
 * 使用插入顺序的键值对列表存储；键比较使用 SameValueZero（在内建模块中实现）。
 */
open class JSMap(
    prototype: JSObject?,
) : JSObject(prototype) {
    val entries: MutableList<Pair<JSValue, JSValue>> = mutableListOf()
}
