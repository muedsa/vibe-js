package com.muedsa.js.runtime.exception

/**
 * 资源限制类别及其在异常消息中显示的限制名。
 *
 * 名称与 [com.muedsa.js.runtime.EngineLimits] 的配置属性保持一致，
 * 宿主日志和测试可以直接据此区分是哪一项配额被耗尽。
 */
enum class ResourceLimitKind(val limitName: String) {
    /** Engine.parse / eval 的源码字符数上限。 */
    SOURCE_LENGTH("maxSourceLength"),

    /** 词法括号与递归下降解析器的语法嵌套深度上限。 */
    NESTING_DEPTH("maxNestingDepth"),

    /**
     * JSON.parse / JSON.rawJSON 的容器嵌套深度上限。
     *
     * 按现有错误模型转换为 JS RangeError，不通过 [ResourceLimitException] 抛出；
     * 保留该枚举值用于宿主日志/诊断映射。
     */
    JSON_DEPTH("maxJsonDepth"),

    /** 回退正则引擎单次匹配的最大回溯步数。 */
    REGEX_STEPS("maxRegexSteps"),

    /** 事件循环排队任务数上限。 */
    PENDING_TASKS("maxPendingTasks"),

    /** 单次解析创建的 AST 节点总数上限。 */
    AST_NODES("maxAstNodes"),

    /** 单次 Engine.run 内创建 JavaScript 对象数量上限。 */
    OBJECT_COUNT("maxObjectCount"),

    /** 单次微任务清空循环执行的任务数上限（阻断自驱动 async 泵）。 */
    MICROTASKS_PER_RUN("maxMicrotasksPerRun"),
}

/**
 * 解析期、对象分配、正则或事件循环资源耗尽的宿主异常。
 *
 * 继承 [ExecutionLimitException] 以复用"不可被 JS catch/finally 处理、直接中止
 * 当前 Engine.run"的宿主控制语义；调用方可以按 [kind] 区分具体限制项。
 * 异常消息包含限制名、配置上限与实际值，便于日志和测试断言。
 *
 * @property kind 具体的资源限制类别。
 * @param limitValue 配置的上限值。
 * @param actualValue 触发限制时观察到的实际值。
 * @param detail 可选的位置等补充信息（如 "line 1, column 3"）。
 */
class ResourceLimitException internal constructor(
    val kind: ResourceLimitKind,
    limitValue: Long,
    actualValue: Long,
    detail: String? = null,
) : ExecutionLimitException(
    limitName = kind.limitName,
    limitValue = limitValue,
    actualValue = actualValue,
    message = buildString {
        append("Resource limit exceeded: ")
        append(kind.limitName)
        append(" (limit=")
        append(limitValue)
        append(", actual=")
        append(actualValue)
        append(')')
        if (!detail.isNullOrBlank()) {
            append(" at ")
            append(detail)
        }
    },
)
