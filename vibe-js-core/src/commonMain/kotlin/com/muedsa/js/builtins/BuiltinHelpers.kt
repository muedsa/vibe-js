package com.muedsa.js.builtins

import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.Intrinsics
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSValue

/**
 * 内建函数构造辅助。
 *
 * 所有内建函数默认挂在 Function.prototype 上，保证它们自身也拥有
 * call/apply/bind 等方法。
 *
 * 该对象对宿主模块与第三方扩展公开，便于注册自定义内建函数。
 */
object BuiltinHelpers {

    /**
     * 创建原生方法。
     *
     * @param intrinsics 用于取 Function.prototype
     * @param name 函数名
     * @param length 形参个数
     * @param body 实现
     */
    fun method(
        intrinsics: Intrinsics,
        name: String,
        length: Int = 0,
        body: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
    ): JSNativeFunction = JSNativeFunction(
        name = name,
        length = length,
        prototype = intrinsics.functionPrototype,
        function = body,
    )

    /**
     * 创建原生构造器。
     *
     * @param prototypeObject 构造器的 .prototype 属性对象
     */
    fun constructor(
        intrinsics: Intrinsics,
        name: String,
        length: Int,
        prototypeObject: JSObject,
        body: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue,
    ): JSNativeFunction = JSNativeFunction(
        name = name,
        length = length,
        prototype = intrinsics.functionPrototype,
        functionPrototypeObject = prototypeObject,
        function = body,
    )
}
