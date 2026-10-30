package com.muedsa.js.runtime

import com.muedsa.js.ast.Program
import com.muedsa.js.runtime.value.JSValue

/**
 * 执行器抽象。
 *
 * 当前实现是 AST 解释器（Interpreter）。
 * 未来如果需要性能优化，可以新增"编译为内部指令/闭包"的执行器实现，
 * 只要满足该接口即可替换，不影响 Parser、内建对象和公共 API。
 */
interface Executor {
    suspend fun run(program: Program, environment: Environment): JSValue
}
