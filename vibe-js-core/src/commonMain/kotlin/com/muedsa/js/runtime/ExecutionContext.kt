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
 * @property isConstructor 当前是否在构造器调用中（构造器返回不做尾调用优化）
 */
class ExecutionContext(
    var lexicalEnv: Environment,
    var thisValue: JSValue,
    val strict: Boolean,
    val function: JSFunction? = null,
    val newTarget: JSValue? = null,
    /** 派生类构造器在 super() 之前 this 未初始化 */
    var thisInitialized: Boolean = true,
    val isConstructor: Boolean = false,
    /**
     * VariableEnvironment：var/function 声明的落点（直接 eval 使用）。
     *
     * 参数含默认值/解构时，参数初始化阶段指向函数的最外层环境，
     * 参数绑定完成后切换为独立的 var 环境（null 表示回退到词法环境链的 var 作用域）。
     */
    var variableEnv: Environment? = null,
    /** 是否正处于参数初始化阶段（参数默认值/解构求值期间） */
    var inParameterInitialization: Boolean = false,
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
