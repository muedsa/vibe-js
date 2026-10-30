package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSValue

/**
 * 语句执行完成状态。
 *
 * 设计说明：
 * - return / break / continue 使用值类型传递，避免用异常做控制流；
 * - break / continue 携带可选标签，用于标签语句；
 * - throw 仍使用 JSException，因为原生函数/宿主代码也需要能够抛出 JS 异常。
 */
sealed class Completion {

    /** 正常结束，携带语句的完成值（通常是 undefined）。 */
    class Normal(val value: JSValue) : Completion()

    /** return 结束。 */
    class Return(val value: JSValue) : Completion()

    /** break 结束（label 为 null 表示普通 break）。 */
    data class Break(val label: String? = null) : Completion()

    /** continue 结束（label 为 null 表示普通 continue）。 */
    data class Continue(val label: String? = null) : Completion()

    companion object {
        /** 常用的"正常结束且值为 undefined"。 */
        fun normalUndefined(): Completion = Normal(com.muedsa.js.runtime.value.JSUndefined)
    }
}
