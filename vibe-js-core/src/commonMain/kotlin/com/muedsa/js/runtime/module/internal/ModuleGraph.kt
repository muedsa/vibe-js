@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ModuleProgram
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleSource
import com.muedsa.js.runtime.value.JSObject
/** 模块图内的求值/链接状态。阶段 3 只创建 NEW 状态。 */
internal enum class ModuleStatus {
    NEW,
    LINKING,
    LINKED,
    EVALUATING,
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

    /** `import * as ns` 或导入 `export * as ns`：需要目标模块的 namespace。 */
    data class Namespace(
        val target: ModuleRecord,
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

    /** 阶段 7 创建 import.meta 对象后填充；同一模块复用。 */
    var importMeta: JSObject? = null

    /** 求值失败时缓存原始异常；同图内再次求值直接重抛。 */
    var evaluationError: Throwable? = null

    /** 按原说明符查找已加载的依赖；requestedModules 与 dependencies 顺序一致。 */
    fun dependencyFor(specifier: String): ModuleRecord? {
        val index = program.requestedModules.indexOf(specifier)
        return if (index >= 0) dependencies.getOrNull(index) else null
    }
}

/** 一次 runModule / 图加载的完整记录集合；不跨调用缓存。 */
internal class ModuleGraph(
    val entry: ModuleRecord,
    val records: Map<String, ModuleRecord>,
)
