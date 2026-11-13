@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSObject

/**
 * 一次运行的模块宿主状态。
 *
 * 复用同一 [ModuleGraphResolver]，使静态入口与后续动态 `import()` 共享记录缓存；
 * 图、链接与求值状态只在本次运行内有效，不会跨 `Engine.runModule` 泄漏。
 */
internal class ModuleRuntime(
    private val provider: ModuleProvider,
    private val limits: EngineLimits,
) {
    private val resolver = ModuleGraphResolver(limits, this)

    suspend fun loadEntry(entry: ModuleSource): ModuleGraph = resolver.load(entry, provider)

    suspend fun loadEntry(entrySpecifier: String): ModuleGraph = resolver.load(entrySpecifier, provider)

    /** `import.meta.resolve`：只询问宿主解析结果，不加载/执行模块。 */
    suspend fun resolve(specifier: String, referrer: ModuleRecord): String? =
        provider.resolve(ModuleRequest(specifier, referrer = referrer.id))

    /**
     * 动态导入：加载、链接并求值目标图，返回入口 namespace。
     *
     * 链接/求值异常按原样抛出，由调用方转换成 Promise 拒绝原因。
     */
    suspend fun importModule(
        interpreter: Interpreter,
        specifier: String,
        referrer: ModuleRecord?,
        attributes: Map<String, String>,
        deferred: Boolean = false,
        source: Boolean = false,
    ): JSObject {
        val graph = resolver.loadDynamic(specifier, referrer?.id, attributes, provider)
        val evaluator = ModuleEvaluator(interpreter)
        if (source) {
            // source phase 只拿 source 对象，不链接、不求值。
            return evaluator.evaluateSource(graph)
        }
        ModuleLinker.link(graph)
        return if (deferred) {
            evaluator.evaluateDeferred(graph)
        } else {
            evaluator.evaluate(graph)
        }
    }
}
