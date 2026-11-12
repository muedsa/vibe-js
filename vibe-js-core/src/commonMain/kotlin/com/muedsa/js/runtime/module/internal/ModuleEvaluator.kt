@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.value.JSObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * 模块图求值器。
 *
 * 同步模块仍按 DFS 后序同步执行；带 TLA 或异步依赖的模块通过 completion 等待，
 * 启动依赖时使用 UNDISPATCHED，让依赖的同步前缀先执行到首个 await，再继续兄弟模块。
 */
internal class ModuleEvaluator(
    private val interpreter: Interpreter,
) {
    private val instantiator = ModuleDeclarationInstantiator(interpreter)
    private var asyncOrder = 0

    suspend fun evaluate(graph: ModuleGraph): JSObject {
        // 先建齐所有环境与 namespace，保证 import 转发和嵌套 namespace 可用。
        for (record in graph.records.values) {
            if (record.environment == null) instantiator.instantiate(record)
        }
        val completion = evaluateModule(graph.entry, mutableListOf())
        completion.await()
        return graph.entry.namespace
            ?: throw IllegalStateException("Module namespace is not initialized: ${graph.entry.id}")
    }

    private suspend fun evaluateModule(
        record: ModuleRecord,
        stack: MutableList<ModuleRecord>,
    ): CompletableDeferred<Unit> {
        when (record.status) {
            ModuleStatus.EVALUATED -> return completedCompletion()

            ModuleStatus.ERRORED -> {
                val error = record.evaluationError
                    ?: IllegalStateException("Module errored without an exception: ${record.id}")
                return failedCompletion(error)
            }

            ModuleStatus.EVALUATING -> {
                // 同步循环：当前模块正在执行，调用方可以继续使用已经存在的环境。
                return completedCompletion()
            }

            ModuleStatus.EVALUATING_ASYNC -> {
                return record.evaluationCompletion ?: completedCompletion()
            }

            else -> Unit
        }

        val completion = CompletableDeferred<Unit>()
        record.evaluationCompletion = completion
        record.cycleRoot = record
        record.status = ModuleStatus.EVALUATING
        stack.add(record)

        val asyncDependencies = mutableListOf<CompletableDeferred<Unit>>()
        try {
            for (dependency in record.dependencies) {
                val dependencyInStack =
                    dependency.status == ModuleStatus.EVALUATING && stack.contains(dependency)
                val dependencyCompletion = evaluateModule(dependency, stack)
                if (dependencyCompletion.isCompleted) {
                    dependencyCompletion.getCompletionExceptionOrNull()?.let { throw it }
                }

                if (dependencyInStack) {
                    // 当前依赖是 DFS 栈中的祖先：标记这条回边所在的循环。
                    val root = dependency
                    val rootIndex = stack.indexOf(root)
                    if (rootIndex >= 0) {
                        for (index in rootIndex until stack.size) {
                            stack[index].cycleRoot = root
                        }
                    }
                    continue
                }

                val cycleRoot = dependency.cycleRoot ?: dependency
                // 循环根自己的依赖仍等待实际依赖；循环外的导入方等待 cycle root。
                val waitTarget = if (cycleRoot === record) dependency else cycleRoot
                val waitCompletion = if (waitTarget === dependency) {
                    dependencyCompletion
                } else {
                    waitTarget.evaluationCompletion ?: dependencyCompletion
                }
                if (waitTarget.status == ModuleStatus.EVALUATING_ASYNC) {
                    record.pendingAsyncDependencies++
                    waitTarget.asyncParentModules.add(record)
                    asyncDependencies.add(waitCompletion)
                }
            }
        } catch (error: Throwable) {
            stack.remove(record)
            record.status = ModuleStatus.ERRORED
            record.evaluationError = error
            completion.completeExceptionally(error)
            throw error
        }
        stack.remove(record)

        val hasTLA = record.program.hasTopLevelAwait
        if (record.pendingAsyncDependencies == 0 && !hasTLA) {
            return try {
                interpreter.runModule(record)
                record.status = ModuleStatus.EVALUATED
                completion.complete(Unit)
                completion
            } catch (error: CancellationException) {
                record.status = ModuleStatus.NEW
                completion.completeExceptionally(error)
                throw error
            } catch (error: Throwable) {
                record.status = ModuleStatus.ERRORED
                record.evaluationError = error
                completion.completeExceptionally(error)
                throw error
            }
        }

        record.status = ModuleStatus.EVALUATING_ASYNC
        record.asyncEvaluationOrder = asyncOrder++
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (dependency in asyncDependencies) {
                    dependency.await()
                }
                interpreter.runModule(record)
                record.status = ModuleStatus.EVALUATED
                completion.complete(Unit)
            } catch (error: CancellationException) {
                record.status = ModuleStatus.NEW
                completion.completeExceptionally(error)
                throw error
            } catch (error: Throwable) {
                record.status = ModuleStatus.ERRORED
                record.evaluationError = error
                completion.completeExceptionally(error)
            }
        }
        return completion
    }

    private fun completedCompletion(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }

    private fun failedCompletion(error: Throwable): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { completeExceptionally(error) }
}
