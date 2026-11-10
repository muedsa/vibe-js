@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.value.JSObject
import kotlinx.coroutines.CancellationException

/**
 * 模块图求值器（阶段 7）。
 *
 * 先把所有可达模块的声明实例化好，再按 DFS 后序执行模块体；循环用 EVALUATING
 * 状态截断。求值失败缓存到 [ModuleRecord.evaluationError]，同图内重复求值重抛。
 */
internal class ModuleEvaluator(
    private val interpreter: Interpreter,
) {
    private val instantiator = ModuleDeclarationInstantiator(interpreter)

    suspend fun evaluate(graph: ModuleGraph): JSObject {
        // 先建齐所有环境与 namespace，保证 import 转发和嵌套 namespace 可用。
        for (record in graph.records.values) {
            if (record.environment == null) instantiator.instantiate(record)
        }
        evaluateModule(graph.entry)
        return graph.entry.namespace
            ?: throw IllegalStateException("Module namespace is not initialized: ${graph.entry.id}")
    }

    private suspend fun evaluateModule(record: ModuleRecord) {
        when (record.status) {
            ModuleStatus.EVALUATED -> return
            ModuleStatus.ERRORED -> throw record.evaluationError
                ?: IllegalStateException("Module errored without an exception: ${record.id}")

            ModuleStatus.EVALUATING -> return
            else -> Unit
        }

        record.status = ModuleStatus.EVALUATING
        try {
            for (dependency in record.dependencies) {
                evaluateModule(dependency)
            }
            interpreter.runModule(record)
            record.status = ModuleStatus.EVALUATED
        } catch (error: CancellationException) {
            // 取消不缓存为求值错误，避免污染后续 run。
            record.status = ModuleStatus.NEW
            throw error
        } catch (error: Throwable) {
            record.status = ModuleStatus.ERRORED
            record.evaluationError = error
            throw error
        }
    }
}
