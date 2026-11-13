@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ImportPhase
import com.muedsa.js.ast.ModuleProgram
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSObject
import kotlinx.coroutines.CompletableDeferred
/** 模块图内的求值/链接状态。 */
internal enum class ModuleStatus {
    NEW,
    LINKING,
    LINKED,
    EVALUATING,
    EVALUATING_ASYNC,
    EVALUATED,
    ERRORED,
}

/** 链接阶段解析后的 import 绑定；求值阶段据此建立 live binding。 */
internal sealed interface ModuleImportBinding {
    /** 具名/默认导入：目标模块中的本地绑定。 */
    data class Resolved(
        val target: ModuleRecord,
        val bindingName: String,
    ) : ModuleImportBinding

    /** `import source x` / re-export source binding：绑定目标模块的 source 对象。 */
    data class Source(
        val target: ModuleRecord,
    ) : ModuleImportBinding

    /** `import * as ns` 或导入 `export * as ns`：需要目标模块的 namespace。 */
    data class Namespace(
        val target: ModuleRecord,
        val phase: ImportPhase = ImportPhase.EAGER,
    ) : ModuleImportBinding
}

/**
 * 单次模块图内的模块记录。
 *
 * [dependencies] 按 requestedModules 的源码顺序填充；循环依赖时先创建 record，
 * 再通过 [ModuleGraph.records] 找到已存在的 record。保留完整 [source] 是为了后续
 * `import.meta.url`、错误定位与函数源码切片，而不是只存 id/长度。
 */
internal class ModuleRecord(
    val source: ModuleSource,
    val program: ModuleProgram,
    /** 产生该记录的一次运行宿主状态；直接构造 resolver 的测试为 null。 */
    val runtime: ModuleRuntime? = null,
) {
    val id: String get() = source.id
    val sourceName: String get() = source.sourceName
    val sourceLength: Int get() = source.source.length
    val dependencies: MutableList<ModuleRecord> = mutableListOf()

    /** 本地 import 名 → 链接后的目标绑定；阶段 5 的声明实例化直接使用。 */
    val importBindings: MutableMap<String, ModuleImportBinding> = LinkedHashMap()

    /** namespace 的排序、去重导出名；链接阶段填充。 */
    var exportedNames: List<String> = emptyList()
    var status: ModuleStatus = ModuleStatus.NEW

    /** 声明实例化后的模块环境；阶段 5 填充。 */
    var environment: ModuleEnvironment? = null

    /** 阶段 6 创建 namespace exotic 对象后填充。 */
    var namespace: JSObject? = null

    /** defer 导入使用的延迟 namespace；与 eager namespace 身份不同。 */
    var deferredNamespace: JSObject? = null

    /** host module source 表示；source phase 导入和 source binding 使用。 */
    var moduleSourceObject: JSObject? = null

    /** 阶段 7 创建 import.meta 对象后填充；同一模块复用。 */
    var importMeta: JSObject? = null

    /** 求值失败时缓存原始异常；同图内再次求值直接重抛。 */
    var evaluationError: Throwable? = null

    /** 本次求值的完成信号；同步模块完成后立即 complete。 */
    var evaluationCompletion: CompletableDeferred<Unit>? = null

    /** 异步依赖数量；大于 0 表示模块体需等待依赖完成。 */
    var pendingAsyncDependencies: Int = 0

    /** 等待本模块完成的其他异步模块；循环/错误传播的调试信息。 */
    val asyncParentModules: MutableList<ModuleRecord> = mutableListOf()

    /** 异步求值的调度序号；用于诊断和稳定排序。 */
    var asyncEvaluationOrder: Int? = null

    /** 循环求值的根模块；非循环模块在求值开始时指向自身。 */
    var cycleRoot: ModuleRecord? = null

    var dfsIndex: Int = -1
    var dfsAncestorIndex: Int = -1

    /** 按原说明符查找已加载的依赖；requestedModules 与 dependencies 顺序一致。 */
    fun dependencyFor(specifier: String): ModuleRecord? {
        val index = program.requestedModules.indexOf(specifier)
        return if (index >= 0) dependencies.getOrNull(index) else null
    }

    /** 某个请求的相位集合；同一说明符可同时被 eager 和 defer 导入。 */
    fun phasesFor(specifier: String): Set<ImportPhase> =
        program.requestedPhases[specifier] ?: setOf(ImportPhase.EAGER)

    /** 该请求是否需要在导入方求值时立即求值。 */
    fun hasEagerPhase(specifier: String): Boolean = ImportPhase.EAGER in phasesFor(specifier)
}

/** 一次 runModule / 图加载的完整记录集合；不跨调用缓存。 */
internal class ModuleGraph(
    val entry: ModuleRecord,
    val records: Map<String, ModuleRecord>,
)
