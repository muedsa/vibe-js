@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.SourceInfo
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
 * 模块图加载器：解析入口、递归调用 Provider、按 id/请求去重。
 *
 * 阶段 3 只做加载与解析，不链接、不求值。所有状态（records/requestCache）
 * 属于单次 [load] 调用，不跨 run 缓存。
 */
internal class ModuleGraphLoader(
    private val limits: EngineLimits,
) {
    private data class RequestKey(
        val specifier: String,
        val referrer: String?,
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
        return ModuleGraph(entryRecord, records.toMap())
    }

    suspend fun load(entrySpecifier: String, provider: ModuleProvider): ModuleGraph {
        currentCoroutineContext().ensureActive()
        reset()
        val source = provider.load(ModuleRequest(entrySpecifier, referrer = null))
            ?: throw ModuleLinkException(
                kind = ModuleLinkErrorKind.NOT_FOUND,
                specifier = entrySpecifier,
                referrer = null,
                message = "Module not found: $entrySpecifier",
            )
        return load(source, provider)
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
        )
        records[source.id] = record
        totalSourceLength = nextTotal

        for (dependencySpecifier in program.requestedModules) {
            currentCoroutineContext().ensureActive()
            val key = RequestKey(dependencySpecifier, source.id)
            val dependency = requestCache[key] ?: run {
                val loaded = provider.load(
                    ModuleRequest(dependencySpecifier, referrer = source.id),
                ) ?: throw ModuleLinkException(
                    kind = ModuleLinkErrorKind.NOT_FOUND,
                    specifier = dependencySpecifier,
                    referrer = source.id,
                    message = "Module not found: $dependencySpecifier (referrer=${source.id})",
                )
                loadSource(
                    source = loaded,
                    specifier = dependencySpecifier,
                    referrer = source.id,
                    provider = provider,
                ).also { requestCache[key] = it }
            }
            record.dependencies.add(dependency)
        }
        return record
    }

    private fun parse(source: ModuleSource): com.muedsa.js.ast.ModuleProgram {
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
    }
}
