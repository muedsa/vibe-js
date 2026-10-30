package com.muedsa.js.runtime

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
) {
    /**
     * 全局词法环境。
     *
     * 结构：DeclarativeEnvironment（全局 let/const）
     *        -> ObjectEnvironment（全局 var/function，委托给 globalObject）
     */
    lateinit var globalEnvironment: Environment
}
