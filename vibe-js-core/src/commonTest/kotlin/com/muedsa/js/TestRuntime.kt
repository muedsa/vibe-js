package com.muedsa.js

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey
import kotlin.test.assertIs

/**
 * 测试辅助：每个测试持有独立 Engine，避免全局状态互相影响。
 */
class TestRuntime {

    val engine = Engine()

    /** 执行脚本并返回结果。 */
    suspend fun eval(code: String): JSValue = engine.run(code)

    /** 执行脚本并要求结果为数字。 */
    suspend fun evalNumber(code: String): Double = assertIs<JSNumber>(eval(code)).value

    /** 执行脚本并要求结果为字符串。 */
    suspend fun evalString(code: String): String = assertIs<JSString>(eval(code)).value

    /** 执行脚本并要求结果为布尔值。 */
    suspend fun evalBoolean(code: String): Boolean = assertIs<JSBoolean>(eval(code)).value

    /** 读取全局变量（包括通过 var/function 声明的全局绑定）。 */
    suspend fun global(name: String): JSValue =
        engine.interpreter.getV(engine.globalObject, PropertyKey.Str(name))

    /** 执行脚本后读取全局变量。 */
    suspend fun evalGlobal(code: String, name: String): JSValue {
        engine.run(code)
        return global(name)
    }
}
