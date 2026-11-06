package com.muedsa.js.runtime.value

/**
 * Proxy exotic object。
 *
 * 本类只保存规范中的 [[ProxyTarget]]、[[ProxyHandler]] 与撤销状态；
 * 所有属性/调用/构造操作都由解释器的内部方法按 trap 分派，
 * 因此不要直接调用 [JSObject] 的非挂起存储方法。
 */
class JSProxy(
    var target: JSObject,
    var handler: JSObject,
) : JSObject(prototype = null) {

    /** 撤销后任何 trap 操作都抛 TypeError。 */
    var revoked: Boolean = false

    override fun toPrimitiveString(): String = "[object Object]"
}
