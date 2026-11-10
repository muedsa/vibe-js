package com.muedsa.js.runtime.module

import com.muedsa.js.lexer.SourceRange

/**
 * 实验性模块执行 API。
 *
 * 提供模块图/Provider、链接结果与错误契约；签名和行为都不承诺稳定兼容，
 * 宿主需要显式 opt-in。
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Module execution is experimental and has no compatibility guarantee.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalModuleApi

/**
 * 一次模块请求。
 *
 * [specifier] 是源码中写的原始说明符；核心不解释路径、URL 或平台语义，
 * 只把它连同 [referrer] 原样交给 [ModuleProvider]。入口请求的 referrer 为 null。
 */
@ExperimentalModuleApi
public data class ModuleRequest(
    public val specifier: String,
    public val referrer: String? = null,
)

/**
 * Provider 解析/读取后的模块源码。
 *
 * [id] 必须是宿主侧稳定且唯一的模块标识；同一个 id 表示同一个模块。
 * [source] 是宿主已经解码的文本，UTF-8 解码与 BOM 剥离属于宿主职责。
 * [sourceName] 只影响错误栈；[url] 只影响 `import.meta.url`，两者都不参与模块身份。
 */
@ExperimentalModuleApi
public data class ModuleSource(
    public val id: String,
    public val source: String,
    public val sourceName: String = id,
    public val url: String? = null,
)

/**
 * 宿主模块加载器。
 *
 * 返回 null 表示找不到；异常原样透传，核心不包装、不吞掉。实现必须可协作取消。
 * 相对说明符规范化、文件/URL/网络访问、包解析都由实现负责。
 */
@ExperimentalModuleApi
public fun interface ModuleProvider {
    public suspend fun load(request: ModuleRequest): ModuleSource?
}

/** 没有依赖的入口可使用的空 Provider。 */
@ExperimentalModuleApi
public object EmptyModuleProvider : ModuleProvider {
    override suspend fun load(request: ModuleRequest): ModuleSource? = null
}

/**
 * 纯内存 Provider，按精确 id 查找。
 *
 * 不做 `./`、`../`、URL 或平台路径规范化；需要相对路径的宿主必须提供自己的
 * Provider 或先规范化 id。
 */
@ExperimentalModuleApi
public class InMemoryModuleProvider(
    private val modules: Map<String, String>,
) : ModuleProvider {
    override suspend fun load(request: ModuleRequest): ModuleSource? {
        val source = modules[request.specifier] ?: return null
        return ModuleSource(
            id = request.specifier,
            source = source,
            sourceName = request.specifier,
            url = null,
        )
    }
}

/** 静态链接阶段的错误类别。 */
@ExperimentalModuleApi
public enum class ModuleLinkErrorKind {
    NOT_FOUND,
    MISSING_EXPORT,
    AMBIGUOUS_EXPORT,
    INVALID_MODULE,
    TOO_MANY_MODULES,
    TOTAL_SOURCE_TOO_LARGE,
    UNSUPPORTED_TLA,
}

/**
 * 模块链接阶段的宿主异常。
 *
 * 静态链接错误发生在模块代码执行前，不能被模块内 `try/catch` 捕获；调用方通过
 * [kind] 区分具体原因。求值期异常仍按 JS 异常原样透传。
 */
@ExperimentalModuleApi
public class ModuleLinkException(
    public val kind: ModuleLinkErrorKind,
    public val specifier: String? = null,
    public val referrer: String? = null,
    public val moduleId: String? = null,
    public val exportName: String? = null,
    /** 触发错误的模块源码名；来自 ModuleSource.sourceName。 */
    public val sourceName: String? = null,
    /** 触发错误的 import/export 声明范围。 */
    public val range: SourceRange? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
