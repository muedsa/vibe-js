@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.ast.ModuleProgram
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleSource

/** 模块图内的求值/链接状态。阶段 3 只创建 NEW 状态。 */
internal enum class ModuleStatus {
    NEW,
    LINKING,
    LINKED,
    EVALUATING,
    EVALUATED,
    ERRORED,
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
    var status: ModuleStatus = ModuleStatus.NEW
}

/** 一次 runModule / 图加载的完整记录集合；不跨调用缓存。 */
internal class ModuleGraph(
    val entry: ModuleRecord,
    val records: Map<String, ModuleRecord>,
)
