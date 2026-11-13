@file:OptIn(ExperimentalModuleApi::class)

package com.muedsa.js.runtime.module.internal

import com.muedsa.js.builtins.JsonDepthLimitException
import com.muedsa.js.builtins.JsonParseException
import com.muedsa.js.builtins.JsonParser
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

        records[recordKey(source)]?.let { return it }

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
        records[recordKey(source)] = record
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
        val prepared = prepareAssetSource(
            source = loaded,
            attributes = attributes,
            specifier = specifier,
            referrer = referrer,
        )
        return loadSource(
            source = prepared,
            specifier = specifier,
            referrer = referrer,
            provider = provider,
        ).also { requestCache[key] = it }
    }

    /**
     * 把非 JS 资产包装成最小 ES module。
     *
     * 当前模块记录仍以 `ModuleProgram` 为核心，因此 JSON/text/bytes 在加载期
     * 转成 synthetic default export 的源码；JSON 先在加载期用严格 JSON parser
     * 校验，保证错误发生在 resolution 阶段而不是模块求值阶段。
     */
    private suspend fun prepareAssetSource(
        source: ModuleSource,
        attributes: Map<String, String>,
        specifier: String,
        referrer: String?,
    ): ModuleSource {
        return when (attributes["type"]) {
            "json" -> {
                limits.checkSourceLength(source.source.length)
                source.copy(
                    source = jsonModuleSource(source, specifier, referrer),
                    assetType = "json",
                )
            }
            "text" -> {
                limits.checkSourceLength(source.source.length)
                source.copy(
                    source = textModuleSource(source.source),
                    assetType = "text",
                )
            }
            "bytes" -> {
                val bytes = source.bytes
                    ?: throw invalidAsset(
                        source,
                        specifier,
                        referrer,
                        "bytes module requires ModuleSource.bytes",
                    )
                limits.checkSourceLength(
                    (bytes.size.toLong() * 4L + 64L)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt(),
                )
                source.copy(
                    source = bytesModuleSource(bytes),
                    assetType = "bytes",
                )
            }
            else -> source
        }
    }

    private suspend fun jsonModuleSource(
        source: ModuleSource,
        specifier: String,
        referrer: String?,
    ): String {
        try {
            JsonParser(
                text = source.source,
                objectPrototype = null,
                arrayPrototype = null,
                maxDepth = limits.maxJsonDepth,
            ).parse()
        } catch (error: JsonParseException) {
            throw invalidAsset(source, specifier, referrer, "Invalid JSON module: ${error.message}")
        } catch (error: JsonDepthLimitException) {
            throw invalidAsset(source, specifier, referrer, error.message ?: "JSON module is too deep")
        }
        return "const __json = JSON.parse(${jsStringLiteral(source.source)});\n" +
            "export default __json;\n"
    }

    private fun textModuleSource(text: String): String =
        "export default ${jsStringLiteral(text)};\n"

    private fun bytesModuleSource(bytes: ByteArray): String {
        val values = bytes.joinToString(",") { (it.toInt() and 0xFF).toString() }
        return "const __bytes = new Uint8Array([$values]);\n" +
            "export default new Uint8Array(__bytes.buffer.transferToImmutable());\n"
    }

    private fun invalidAsset(
        source: ModuleSource,
        specifier: String,
        referrer: String?,
        message: String,
    ): ModuleLinkException = ModuleLinkException(
        kind = ModuleLinkErrorKind.INVALID_MODULE,
        specifier = specifier,
        referrer = referrer,
        moduleId = source.id,
        sourceName = source.sourceName,
        message = message,
    )

    /** 生成可嵌入 JS 字符串字面量的值；转义控制字符与行分隔符。 */
    private fun jsStringLiteral(text: String): String {
        val builder = StringBuilder(text.length + 2)
        builder.append('"')
        for (character in text) {
            when (character) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000B' -> builder.append("\\v")
                '\u000C' -> builder.append("\\f")
                '\u2028' -> builder.append("\\u2028")
                '\u2029' -> builder.append("\\u2029")
                else -> if (character < ' ') {
                    builder.append("\\u")
                    builder.append(character.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(character)
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }

    /** 模块身份：普通模块保持 id；非 JS 资产按 id + assetType 区分。 */
    private fun recordKey(source: ModuleSource): String =
        source.id + (source.assetType?.let { "\u0000$it" } ?: "")

    /** 从入口按依赖闭包收集记录，避免把此前动态导入的无关模块纳入同一图。 */
    private fun graphFor(entry: ModuleRecord): ModuleGraph {
        val reachable = LinkedHashMap<String, ModuleRecord>()
        fun visit(record: ModuleRecord) {
            if (reachable.put(recordKey(record.source), record) != null) return
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
