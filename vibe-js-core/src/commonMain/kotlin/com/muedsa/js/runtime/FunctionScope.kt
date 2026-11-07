package com.muedsa.js.runtime

/**
 * 函数调用的环境层级（FunctionDeclarationInstantiation 的结果）。
 *
 * 生成器函数（含 async generator）在 [[Call]] 阶段就要完成参数初始化：
 * 默认值求值、this/arguments 绑定与参数 TDZ 都发生在调用时，然后才返回
 * suspendedStart 状态的生成器对象。函数体延迟到首次 next 时执行，
 * 因此需要把这里的环境传递给函数体协程，避免首次 next 重新绑定参数。
 *
 * 普通函数直接在同一解释器内使用该对象，不跨调用保存。
 *
 * @property calleeEnvironment 函数最外层环境（参数初始化期间作为 VariableEnvironment）
 * @property parameterEnvironment 参数环境（默认值/解构/rest 与 arguments 的绑定落点）
 * @property variableEnvironment 函数体 var 环境；无参数表达式时与参数环境相同
 * @property hasParameterExpressions 形参列表是否含默认值/rest/解构（决定环境是否分离）
 */
internal class FunctionScope(
    val calleeEnvironment: DeclarativeEnvironment,
    val parameterEnvironment: DeclarativeEnvironment,
    val variableEnvironment: DeclarativeEnvironment,
    val hasParameterExpressions: Boolean,
)
