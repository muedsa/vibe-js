package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.ObjectAllocationBudget

/**
 * 引擎上下文：把运行时需要的共享组件集中在一起。
 *
 * 该对象由 Engine 创建，Interpreter、内建模块、宿主模块都通过它访问
 * 全局对象、原型集合、注册表、事件循环与宿主能力。
 */
class EngineContext(
    val globalObject: GlobalObject,
    val intrinsics: Intrinsics,
    val registry: BuiltinRegistry,
    val eventLoop: EventLoop,
    val host: HostEnvironment,
    val limits: EngineLimits = EngineLimits(),
) {
    /**
     * 单次 Engine.run 的 JavaScript 对象分配预算。
     *
     * 由 Engine 在取得事件循环执行权后启用、在归还执行权前关闭；
     * 通过 realm 根原型挂到对象原型链上，保证多引擎隔离。
     */
    internal val objectAllocations = ObjectAllocationBudget(limits.maxObjectCount)
    /**
     * 全局词法环境。
     *
     * 结构：DeclarativeEnvironment（全局 let/const）
     *        -> ObjectEnvironment（全局 var/function，委托给 globalObject）
     */
    lateinit var globalEnvironment: Environment
}
