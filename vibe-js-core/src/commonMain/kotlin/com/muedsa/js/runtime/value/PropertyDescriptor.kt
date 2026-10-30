package com.muedsa.js.runtime.value

/**
 * 属性描述符。
 *
 * - 数据属性：value + writable
 * - 访问器属性：get + set
 * - 通用标志：enumerable、configurable
 *
 * 阶段 A 只实现"读取描述符/写入描述符"的基础能力，
 * getter/setter 的调用由解释器完成（因为需要 suspend 执行）。
 */
class PropertyDescriptor(
    var value: JSValue? = null,
    var get: JSValue? = null,
    var set: JSValue? = null,
    var writable: Boolean = false,
    var enumerable: Boolean = false,
    var configurable: Boolean = false,
) {
    /** 是否为访问器属性。 */
    val isAccessor: Boolean get() = get != null || set != null

    /** 是否为数据属性。 */
    val isData: Boolean get() = !isAccessor

    companion object {
        /** 创建数据属性描述符。 */
        fun data(
            value: JSValue,
            writable: Boolean = true,
            enumerable: Boolean = true,
            configurable: Boolean = true,
        ): PropertyDescriptor = PropertyDescriptor(
            value = value,
            writable = writable,
            enumerable = enumerable,
            configurable = configurable,
        )

        /** 创建访问器属性描述符。 */
        fun accessor(
            get: JSValue?,
            set: JSValue?,
            enumerable: Boolean = true,
            configurable: Boolean = true,
        ): PropertyDescriptor = PropertyDescriptor(
            get = get,
            set = set,
            enumerable = enumerable,
            configurable = configurable,
        )
    }
}
