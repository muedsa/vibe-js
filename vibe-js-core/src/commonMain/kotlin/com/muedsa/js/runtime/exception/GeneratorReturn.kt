package com.muedsa.js.runtime.exception

import com.muedsa.js.runtime.value.JSValue

/**
 * 生成器 `return()` 恢复请求的内部控制流信号。
 *
 * 生成器函数体在 `yield` 处收到 Return 请求时抛出该异常：
 * - 它不是 [JSException]，因此脚本的 `catch` 无法捕获；
 * - 它会像 return 完成一样穿过 `try/finally`，让 finally 块正常执行；
 * - 最终由生成器边界（`Interpreter.executeGeneratorBody`）捕获并转换为
 *   `{ value, done: true }` 的返回步骤。
 *
 * @property value `return(value)` 传入的返回值。
 */
internal class GeneratorReturn(
    val value: JSValue,
) : Exception("generator return")
