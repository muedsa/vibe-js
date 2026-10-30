package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 内建模块。
 *
 * 核心标准库、宿主环境（window/console/timer）以及第三方扩展
 * 都通过该接口向引擎注册能力，从而保持核心与宿主特性解耦。
 */
interface BuiltinModule {
    fun install(registry: BuiltinRegistry)
}

/**
 * 内建注册表。
 *
 * - [defineGlobal]：注册全局变量/全局对象（成为全局对象的属性）；
 * - [defineIntrinsic]：注册供内建之间互相引用的内部对象
 *   （例如 Object.prototype、Array.prototype）。
 */
class BuiltinRegistry(val intrinsics: Intrinsics) {

    private val intrinsicValues: MutableMap<String, JSValue> = LinkedHashMap()

    /** 全局对象。 */
    val globalObject: GlobalObject get() = intrinsics.globalObject

    /** 注册全局属性。 */
    fun defineGlobal(
        name: String,
        value: JSValue,
        writable: Boolean = true,
        enumerable: Boolean = false,
        configurable: Boolean = true,
    ) {
        globalObject.defineDataProperty(
            key = PropertyKey.Str(name),
            value = value,
            writable = writable,
            enumerable = enumerable,
            configurable = configurable,
        )
    }

    /** 注册内部对象（非全局）。 */
    fun defineIntrinsic(name: String, value: JSValue) {
        intrinsicValues[name] = value
    }

    /** 获取内部对象。 */
    fun intrinsic(name: String): JSValue? = intrinsicValues[name]

    /** 安装一个内建模块。 */
    fun install(module: BuiltinModule) {
        module.install(this)
    }
}
