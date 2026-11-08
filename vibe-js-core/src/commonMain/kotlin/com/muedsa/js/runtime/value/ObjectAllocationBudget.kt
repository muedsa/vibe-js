package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.ResourceLimitException
import com.muedsa.js.runtime.exception.ResourceLimitKind

/**
 * 单次 Engine.run 的 JavaScript 对象分配计数器。
 *
 * 计量方式是简单的对象个数（不是内存字节数），因此 JVM/JS/Wasm/Native 上的
 * 结果完全一致，也不会因平台 GC 或对象布局差异产生波动。
 *
 * 预算只在 [begin] 与 [end] 之间生效：引擎初始化时创建的内建对象、事件循环
 * 关闭后的宿主对象都不计入。计数器由 Engine 通过原型链挂到每个 realm 的
 * 根原型上，因此天然按引擎隔离，不依赖全局单例；多引擎并发时互不干扰。
 */
internal class ObjectAllocationBudget(private val maxObjects: Int?) {

    /** 当前单次 run 已统计的对象数；预算关闭时保持 0。 */
    private var allocated = 0

    /** 是否处于单次 Engine.run 的执行窗口内。 */
    private var active = false

    /** 开始一轮新的执行窗口：重置计数并启用（仅当配置了上限）。 */
    fun begin() {
        allocated = 0
        active = maxObjects != null
    }

    /** 结束当前执行窗口；窗口外的对象创建不计入任何预算。 */
    fun end() {
        active = false
    }

    /**
     * 登记一次对象分配。
     *
     * 仅在 [begin] 与 [end] 之间生效；超限抛出宿主控制异常
     * [ResourceLimitException]（kind = [ResourceLimitKind.OBJECT_COUNT]），
     * 不能被 JavaScript try/catch 捕获。
     */
    fun allocate() {
        if (!active) return
        val limit = maxObjects ?: return
        allocated++
        if (allocated > limit) {
            throw ResourceLimitException(
                kind = ResourceLimitKind.OBJECT_COUNT,
                limitValue = limit.toLong(),
                actualValue = allocated.toLong(),
            )
        }
    }

    /** 当前窗口已统计的对象数（供测试与诊断使用）。 */
    val allocatedCount: Int get() = allocated
}
