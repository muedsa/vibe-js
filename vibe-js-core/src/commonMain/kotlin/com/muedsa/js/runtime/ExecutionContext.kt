package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.ThisBindingSource

/**
 * 执行上下文。
 *
 * @property lexicalEnv 当前词法环境（块作用域切换会修改）
 * @property thisValue 当前 this
 * @property strict 当前是否严格模式
 * @property function 当前执行的函数（全局脚本为 null）
 */
class ExecutionContext(
    var lexicalEnv: Environment,
    var thisValue: JSValue,
    val strict: Boolean,
    val function: JSFunction? = null,
    val newTarget: JSValue? = null,
    /** 派生类构造器在 super() 之前 this 未初始化 */
    var thisInitialized: Boolean = true,
) : ThisBindingSource {

    override var boundThis: JSValue
        get() = thisValue
        set(value) {
            thisValue = value
        }

    override var thisBound: Boolean
        get() = thisInitialized
        set(value) {
            thisInitialized = value
        }
}
