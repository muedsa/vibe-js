@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ImportPhase
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.value.JSObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * 模块图求值器。
 *
 * 同步模块仍按 DFS 后序同步执行；带 TLA 或异步依赖的模块通过 completion 等待，
 * 启动依赖时使用 UNDISPATCHED，让依赖的同步前缀先执行到首个 await，再继续兄弟模块。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ModuleEvaluator(
    private val interpreter: Interpreter,
) {
    private val instantiator = ModuleDeclarationInstantiator(interpreter)
    private var asyncOrder = 0

    /**
     * 动态 `import.defer`：只加载/链接并返回延迟 namespace；若存在 TLA
     * 传递依赖，先求值这些异步依赖，再 resolve namespace。
     */
    suspend fun evaluateDeferred(graph: ModuleGraph): JSObject {
        for (record in graph.records.values) {
            if (record.environment == null) instantiator.instantiate(record)
        }
        val asyncDependencies = gatherAsynchronousTransitiveDependencies(
            graph.entry,
            mutableSetOf(),
        )
        if (asyncDependencies.isEmpty()) {
            return graph.entry.deferredNamespace
                ?: throw IllegalStateException("Deferred namespace is not initialized: ${graph.entry.id}")
        }
        val completions = asyncDependencies.map { dependency ->
            evaluateModule(dependency, mutableListOf())
        }
        for (completion in completions) completion.await()
        return graph.entry.deferredNamespace
            ?: throw IllegalStateException("Deferred namespace is not initialized: ${graph.entry.id}")
    }

    /** 动态 `import.source`：只实例化 source 记录并返回 source 对象，不求值。 */
    suspend fun evaluateSource(graph: ModuleGraph): JSObject {
        for (record in graph.records.values) {
            if (record.environment == null) instantiator.instantiate(record)
        }
        validateSourceBindings(graph)
        return graph.entry.moduleSourceObject
            ?: throw interpreter.syntaxError(
                "Source phase is not available for module: ${graph.entry.id}"
            )
    }

    suspend fun evaluate(graph: ModuleGraph): JSObject {
        // 先建齐所有环境与 namespace，保证 import 转发和嵌套 namespace 可用。
        for (record in graph.records.values) {
            if (record.environment == null) instantiator.instantiate(record)
        }
        validateSourceBindings(graph)
        val completion = evaluateModule(graph.entry, mutableListOf())
        completion.await()
        return graph.entry.namespace
            ?: throw IllegalStateException("Module namespace is not initialized: ${graph.entry.id}")
    }

    /** source phase binding 的目标没有 source 表示时，按规范在求值前抛 SyntaxError。 */
    private fun validateSourceBindings(graph: ModuleGraph) {
        for (record in graph.records.values) {
            for (binding in record.importBindings.values) {
                if (binding is ModuleImportBinding.Source &&
                    binding.target.moduleSourceObject == null
                ) {
                    throw interpreter.syntaxError(
                        "Source phase is not available for module: ${binding.target.id}"
                    )
                }
            }
        }
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
        val evaluationModules = evaluationModules(record)
        try {
            for (dependency in evaluationModules) {
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

    /**
     * 构造当前模块的求值列表。
     *
     * eager 请求按源码顺序加入；defer 请求只把其异步传递依赖加入，
     * 从而保证 defer 模块本身保持延迟，但其 TLA 依赖仍然提前启动。
     */
    private fun evaluationModules(record: ModuleRecord): List<ModuleRecord> {
        val result = mutableListOf<ModuleRecord>()
        val seen = mutableSetOf<ModuleRecord>()
        fun add(module: ModuleRecord) {
            if (seen.add(module)) result.add(module)
        }
        for (request in record.program.requestedRequests) {
            val dependency = record.dependencyFor(request.specifier) ?: continue
            when (request.phase) {
                ImportPhase.EAGER -> add(dependency)
                ImportPhase.DEFER -> {
                    for (module in gatherAsynchronousTransitiveDependencies(
                        dependency,
                        mutableSetOf(),
                    )) {
                        add(module)
                    }
                }
                // source phase 既不求值自身，也不参与 TLA 传递依赖。
                ImportPhase.SOURCE -> Unit
            }
        }
        return result
    }

    private fun gatherAsynchronousTransitiveDependencies(
        module: ModuleRecord,
        seen: MutableSet<ModuleRecord>,
    ): List<ModuleRecord> {
        if (!seen.add(module)) return emptyList()
        if (module.status == ModuleStatus.EVALUATING) return emptyList()
        if (module.status == ModuleStatus.EVALUATED &&
            module.cycleRoot?.status != ModuleStatus.EVALUATING_ASYNC
        ) {
            return emptyList()
        }
        if (module.program.hasTopLevelAwait) return listOf(module)
        val result = mutableListOf<ModuleRecord>()
        for (request in module.program.requestedRequests) {
            val dependency = module.dependencyFor(request.specifier) ?: continue
            for (found in gatherAsynchronousTransitiveDependencies(dependency, seen)) {
                if (found !in result) result.add(found)
            }
        }
        return result
    }

    private fun completedCompletion(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }

    private fun failedCompletion(error: Throwable): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { completeExceptionally(error) }

    /**
     * 同步求值路径：供 deferred namespace 的同步属性访问使用。
     *
     * 调用前必须确认图中没有 TLA / 正在异步求值的模块；同步路径不接受
     * 挂起。同步与异步求值共享 ModuleStatus、环境、namespace 和错误缓存。
     */
    internal fun evaluateSync(record: ModuleRecord) {
        if (record.environment == null) instantiator.instantiate(record)
        if (record.status == ModuleStatus.ERRORED) {
            throw record.evaluationError
                ?: IllegalStateException("Module errored without an exception: ${record.id}")
        }
        if (!readyForSyncExecution(record, mutableSetOf())) {
            interpreter.typeError("Cannot evaluate module synchronously: ${record.id}")
        }
        evaluateSyncInternal(record, mutableSetOf())
    }

    private fun evaluateSyncInternal(record: ModuleRecord, stack: MutableSet<ModuleRecord>) {
        when (record.status) {
            ModuleStatus.EVALUATED -> return
            ModuleStatus.ERRORED -> {
                throw record.evaluationError
                    ?: IllegalStateException("Module errored without an exception: ${record.id}")
            }
            ModuleStatus.EVALUATING,
            ModuleStatus.EVALUATING_ASYNC,
            -> {
                // ReadyForSyncExecution 已保证可以进入同步路径；这里只剩
                // 正在同一同步调用栈中的循环。
                if (record in stack) return
                interpreter.typeError("Cannot evaluate module synchronously: ${record.id}")
            }
            else -> Unit
        }

        stack.add(record)
        record.status = ModuleStatus.EVALUATING
        try {
            for (specifier in record.program.requestedEagerModules) {
                val dependency = record.dependencyFor(specifier) ?: continue
                if (dependency.environment == null) instantiator.instantiate(dependency)
                evaluateSyncInternal(dependency, stack)
            }
            interpreter.runModuleSync(record)
            record.status = ModuleStatus.EVALUATED
            record.evaluationError = null
        } catch (error: Throwable) {
            record.status = ModuleStatus.ERRORED
            record.evaluationError = error
            throw error
        } finally {
            stack.remove(record)
        }
    }

    private fun readyForSyncExecution(
        record: ModuleRecord,
        seen: MutableSet<ModuleRecord>,
    ): Boolean {
        if (!seen.add(record)) return true
        when (record.status) {
            ModuleStatus.EVALUATED -> return true
            ModuleStatus.EVALUATING,
            ModuleStatus.EVALUATING_ASYNC,
            -> return false
            else -> Unit
        }
        if (record.program.hasTopLevelAwait) return false
        // ReadyForSyncExecution 遍历所有 ModuleRequest，包括 defer 边：
        // 目标可能通过 deferred 循环依赖指向正在求值的祖先。
        for (request in record.program.requestedRequests) {
            val dependency = record.dependencyFor(request.specifier) ?: continue
            if (!readyForSyncExecution(dependency, seen)) return false
        }
        return true
    }
}
