package com.muedsa.js.runtime.value

/**
 * arguments 对象。
 *
 * 属性布局（length / 索引 / callee）仍由解释器按规范安装；
 * 单独建类用于：
 * - Object.prototype.toString 的类标签 `[object Arguments]`（来自内部属性 [[Class]]）；
 * - sloppy 模式下索引属性与形参的 [[ParameterMap]] 关联，
 *   使 Object.defineProperty 能按 ES5 10.6 同步形参值并解除映射。
 */
class JSArguments(
    prototype: JSObject?,
) : JSObject(prototype) {

    /** 索引与形参绑定的读写通道（由解释器在创建时注入）。 */
    class ParameterMapping(
        val read: suspend () -> JSValue,
        val write: suspend (JSValue) -> Unit,
    )

    private val parameterMappings = LinkedHashMap<Long, ParameterMapping>()

    override fun toPrimitiveString(): String = "[object Arguments]"

    /** 建立索引到形参的映射。 */
    fun mapParameter(index: Long, mapping: ParameterMapping) {
        parameterMappings[index] = mapping
    }

    /** 查询属性键对应的形参映射；非索引或未映射返回 null。 */
    fun parameterMapping(key: PropertyKey): ParameterMapping? {
        val index = arrayIndex(key) ?: return null
        return parameterMappings[index]
    }

    /** 解除索引的形参映射。 */
    fun unmapParameter(key: PropertyKey) {
        val index = arrayIndex(key) ?: return
        parameterMappings.remove(index)
    }

    override fun deleteOwnProperty(key: PropertyKey): Boolean {
        val removed = super.deleteOwnProperty(key)
        if (removed) unmapParameter(key)
        return removed
    }

    /** 解析数组索引；与普通数组一致，越界或带前导零的字符串返回 null。 */
    private fun arrayIndex(key: PropertyKey): Long? {
        val text = (key as? PropertyKey.Str)?.value ?: return null
        if (text.isEmpty() || text.length > 10) return null
        if (text != "0" && text[0] == '0') return null
        var value = 0L
        for (c in text) {
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return if (value <= 4294967294L) value else null
    }
}
