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
 *
 * `hasXxx` 记录规范意义上的"字段是否存在"：Proxy trap 的 Desc 参数与
 * 冻结/密封产生的描述符只携带用户显式给出的字段，ValidateAndApplyPropertyDescriptor
 * 与 FromPropertyDescriptor 都依赖这一点，不能把"未提供"误判为"值为 false"。
 * 默认值全部为 false（空描述符）；`data`/`accessor` 工厂构造完整描述符、
 * `toPropertyDescriptor` 按实际读取到的字段设置这些标记。
 */
class PropertyDescriptor(
    var value: JSValue? = null,
    var get: JSValue? = null,
    var set: JSValue? = null,
    var writable: Boolean = false,
    var enumerable: Boolean = false,
    var configurable: Boolean = false,
    var accessor: Boolean = false,
    /** 描述符是否显式包含 [[Value]] 字段。 */
    var hasValue: Boolean = false,
    /** 描述符是否显式包含 [[Writable]] 字段。 */
    var hasWritable: Boolean = false,
    /** 描述符是否显式包含 [[Get]] 字段。 */
    var hasGet: Boolean = false,
    /** 描述符是否显式包含 [[Set]] 字段。 */
    var hasSet: Boolean = false,
    /** 描述符是否显式包含 [[Enumerable]] 字段。 */
    var hasEnumerable: Boolean = false,
    /** 描述符是否显式包含 [[Configurable]] 字段。 */
    var hasConfigurable: Boolean = false,
) {
    /** 是否为访问器属性。 */
    val isAccessor: Boolean get() = accessor || get != null || set != null

    /** 是否为数据属性。 */
    val isData: Boolean get() = !isAccessor

    /** 是否显式包含任一数据字段（[[Value]] 或 [[Writable]]）。 */
    val hasDataFields: Boolean get() = hasValue || hasWritable

    /** 是否显式包含任一访问器字段（[[Get]] 或 [[Set]]）。 */
    val hasAccessorFields: Boolean get() = hasGet || hasSet

    /** 描述符是否不包含任何字段（ValidateAndApply 的空描述符直接成功）。 */
    val isEmpty: Boolean get() =
        !hasValue && !hasWritable && !hasGet && !hasSet && !hasEnumerable && !hasConfigurable

    /** 复制一份描述符（避免调用方修改内部存储）。 */
    fun copy(): PropertyDescriptor = PropertyDescriptor(
        value = value,
        get = get,
        set = set,
        writable = writable,
        enumerable = enumerable,
        configurable = configurable,
        accessor = accessor,
        hasValue = hasValue,
        hasWritable = hasWritable,
        hasGet = hasGet,
        hasSet = hasSet,
        hasEnumerable = hasEnumerable,
        hasConfigurable = hasConfigurable,
    )

    companion object {
        /** 创建数据属性描述符（完整描述符）。 */
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
            hasValue = true,
            hasWritable = true,
            hasEnumerable = true,
            hasConfigurable = true,
        )

        /** 创建访问器属性描述符（完整描述符）。 */
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
            hasGet = true,
            hasSet = true,
            hasEnumerable = true,
            hasConfigurable = true,
        )

        /**
         * 创建不含任何字段的空描述符。
         *
         * Proxy 的 Desc 参数、冻结/密封算法都只携带部分字段，
         * 用该工厂显式记录"字段不存在"，避免默认 false 被当成显式值。
         */
        fun partial(): PropertyDescriptor = PropertyDescriptor(
            hasValue = false,
            hasWritable = false,
            hasGet = false,
            hasSet = false,
            hasEnumerable = false,
            hasConfigurable = false,
        )
    }
}
