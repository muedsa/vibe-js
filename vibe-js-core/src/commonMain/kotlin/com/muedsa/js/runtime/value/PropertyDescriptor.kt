package com.muedsa.js.runtime.value

/**
 * 属性描述符。
 *
 * - 数据属性：value + writable
 * - 访问器属性：get + set
 * - 通用标志：enumerable、configurable
 *
 * [accessor] 显式记录描述符属于访问器类型：
 * 访问器描述符允许 get/set 都为 undefined（此时 get/set 字段均为 null），
 * 仅靠 get != null 无法与空数据描述符区分。
 */
class PropertyDescriptor(
    var value: JSValue? = null,
    var get: JSValue? = null,
    var set: JSValue? = null,
    var writable: Boolean = false,
    var enumerable: Boolean = false,
    var configurable: Boolean = false,
    var accessor: Boolean = false,
) {
    /** 是否为访问器属性。 */
    val isAccessor: Boolean get() = accessor || get != null || set != null

    /** 是否为数据属性。 */
    val isData: Boolean get() = !isAccessor

    /** 复制一份描述符（避免调用方修改内部存储）。 */
    fun copy(): PropertyDescriptor = PropertyDescriptor(
        value = value,
        get = get,
        set = set,
        writable = writable,
        enumerable = enumerable,
        configurable = configurable,
        accessor = accessor,
    )

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
            accessor = true,
        )
    }
}
