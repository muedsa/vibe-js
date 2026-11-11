@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleLinkErrorKind
import com.muedsa.js.runtime.module.ModuleLinkException
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleRequest
import com.muedsa.js.runtime.module.ModuleSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 模块图解析器：解析入口、递归调用宿主 Provider、按 id/请求去重。
 *
 * 它不读取文件/URL/网络，也不是平台加载器；只把 Provider 返回的源码解析为
 * 内部模块图。所有状态（records/requestCache）属于单次 [load] 调用，不跨 run 缓存。
 */
internal class ModuleGraphResolver(
    private val limits: EngineLimits,
    private val runtime: ModuleRuntime? = null,
) {
    private data class RequestKey(
        val specifier: String,
        val referrer: String?,
        val attributes: Map<String, String>,
    )

    private val records = LinkedHashMap<String, ModuleRecord>()
    private val requestCache = HashMap<RequestKey, ModuleRecord>()
    private var totalSourceLength: Long = 0

    suspend fun load(entry: ModuleSource, provider: ModuleProvider): ModuleGraph {
        currentCoroutineContext().ensureActive()
        reset()
        val entryRecord = loadSource(
            source = entry,
            specifier = entry.id,
            referrer = null,
            provider = provider,
        )
        return graphFor(entryRecord)
    }

    suspend fun load(entrySpecifier: String, provider: ModuleProvider): ModuleGraph {
        currentCoroutineContext().ensureActive()
        reset()
        val entryRecord = loadRequest(
            specifier = entrySpecifier,
            referrer = null,
            attributes = emptyMap(),
            provider = provider,
        )
        return graphFor(entryRecord)
    }

    /**
     * 动态 `import()`：加载说明符对应的模块及其静态依赖，返回只包含可达模块的图。
     *
     * 不重置记录缓存，因此同一次运行内重复动态导入会复用已加载模块；也不执行链接/
     * 求值，由调用方在合适的执行上下文中完成。
     */
    suspend fun loadDynamic(
        specifier: String,
        referrer: String,
        attributes: Map<String, String>,
        provider: ModuleProvider,
    ): ModuleGraph {
        currentCoroutineContext().ensureActive()
        val record = loadRequest(
            specifier = specifier,
            referrer = referrer,
            attributes = attributes,
            provider = provider,
        )
        return graphFor(record)
    }

    /** 同一 loader 可重复使用；每次公开 load 都从空图开始。 */
    private fun reset() {
        records.clear()
        requestCache.clear()
        totalSourceLength = 0
    }

    private suspend fun loadSource(
        source: ModuleSource,
        specifier: String,
        referrer: String?,
        provider: ModuleProvider,
    ): ModuleRecord {
        currentCoroutineContext().ensureActive()
        if (source.id.isBlank()) {
            throw ModuleLinkException(
                kind = ModuleLinkErrorKind.INVALID_MODULE,
                specifier = specifier,
                referrer = referrer,
                moduleId = source.id,
                message = "Module id must not be blank (specifier=$specifier)",
            )
        }

        records[source.id]?.let { return it }

        limits.checkSourceLength(source.source.length)
        val maxModules = limits.maxModules
        if (maxModules != null && records.size >= maxModules) {
            throw ModuleLinkException(
                kind = ModuleLinkErrorKind.TOO_MANY_MODULES,
                specifier = specifier,
                referrer = referrer,
                moduleId = source.id,
                message = "Module graph exceeds maxModules=$maxModules",
            )
        }
        val nextTotal = totalSourceLength + source.source.length
        val maxTotal = limits.maxTotalModuleSourceLength
        if (maxTotal != null && nextTotal > maxTotal) {
            throw ModuleLinkException(
                kind = ModuleLinkErrorKind.TOTAL_SOURCE_TOO_LARGE,
                specifier = specifier,
                referrer = referrer,
                moduleId = source.id,
                message = "Module graph source exceeds maxTotalModuleSourceLength=$maxTotal",
            )
        }

        val program = parse(source)
        val record = ModuleRecord(
            source = source,
            program = program,
            runtime = runtime,
        )
        records[source.id] = record
        totalSourceLength = nextTotal

        for (dependencySpecifier in program.requestedModules) {
            currentCoroutineContext().ensureActive()
            val attributes = program.requestedAttributes[dependencySpecifier] ?: emptyMap()
            val dependency = loadRequest(
                specifier = dependencySpecifier,
                referrer = source.id,
                attributes = attributes,
                provider = provider,
            )
            record.dependencies.add(dependency)
        }
        return record
    }

    private suspend fun loadRequest(
        specifier: String,
        referrer: String?,
        attributes: Map<String, String>,
        provider: ModuleProvider,
    ): ModuleRecord {
        val key = RequestKey(specifier, referrer, attributes)
        requestCache[key]?.let { return it }
        val loaded = provider.load(
            ModuleRequest(specifier, referrer = referrer, attributes = attributes),
        ) ?: throw ModuleLinkException(
            kind = ModuleLinkErrorKind.NOT_FOUND,
            specifier = specifier,
            referrer = referrer,
            message = "Module not found: $specifier" +
                (referrer?.let { " (referrer=$it)" } ?: ""),
        )
        return loadSource(
            source = loaded,
            specifier = specifier,
            referrer = referrer,
            provider = provider,
        ).also { requestCache[key] = it }
    }

    /** 从入口按依赖闭包收集记录，避免把此前动态导入的无关模块纳入同一图。 */
    private fun graphFor(entry: ModuleRecord): ModuleGraph {
        val reachable = LinkedHashMap<String, ModuleRecord>()
        fun visit(record: ModuleRecord) {
            if (reachable.put(record.id, record) != null) return
            record.dependencies.forEach(::visit)
        }
        visit(entry)
        return ModuleGraph(entry, reachable)
    }

    private fun parse(source: ModuleSource): com.muedsa.js.ast.ModuleProgram {
        try {
            val tokens = Lexer(
                source.source,
                maxNestingDepth = limits.maxNestingDepth,
                moduleContext = true,
            ).tokenize()
            return Parser(
                tokens,
                source = source.source,
                maxNestingDepth = limits.maxNestingDepth,
                maxAstNodes = limits.maxAstNodes,
            ).parseModule(SourceInfo(source.sourceName))
        } catch (error: ParseException) {
            throw ParseException(
                message = error.detail,
                range = error.range,
                sourceName = error.sourceName ?: source.sourceName,
            )
        } catch (error: LexerException) {
            throw ParseException(
                message = error.detail,
                range = error.range,
                sourceName = source.sourceName,
            )
        }
    }
}
