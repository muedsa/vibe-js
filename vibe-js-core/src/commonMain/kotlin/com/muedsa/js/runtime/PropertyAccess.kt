package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * 对象属性访问抽象。
 *
 * 全局对象环境（ObjectEnvironment）需要把变量读写映射到全局对象的属性上，
 * 但属性访问语义（getter/setter、原型链）由解释器实现。
 * 通过该接口解耦，避免 Environment 直接依赖 Interpreter。
 */
interface PropertyAccess {

    /** 读取对象属性（含 getter 调用）。 */
    suspend fun getProperty(base: JSObject, key: PropertyKey): JSValue

    /** 写入对象属性（含 setter 调用）。 */
    suspend fun setProperty(base: JSObject, key: PropertyKey, value: JSValue, strict: Boolean)

    /** 对象（含原型链）是否存在该属性。 */
    fun hasProperty(base: JSObject, key: PropertyKey): Boolean

    /** 当前执行上下文是否处于严格模式（with 环境的 GetBindingValue 需要）。 */
    fun isStrictContext(): Boolean = false
}
