package com.muedsa.js.conformance

/**
 * Test262 用例的 metadata。
 *
 * 只保留运行器实际使用的字段；缺失的键为 null。
 */
internal data class Metadata(
    val es5Id: String?,
    val es6Id: String?,
    val esId: String?,
    val includes: List<String>,
    val flags: Set<String>,
    val features: Set<String>,
    val negativePhase: String?,
    val negativeType: String?,
)

/** 筛选阶段的判定结果：选中，或带明确原因的排除。 */
internal sealed class SelectionDecision {
    data object Selected : SelectionDecision()
    data class Excluded(val reason: String) : SelectionDecision()
}

/**
 * 跳过判定结果。
 *
 * @property reason 跳过原因（写入报告与 `test262-skipped.txt`）
 * @property textMatchOnly 是否仅由源码文本匹配触发：对应能力没有出现在
 *   `metadata.features` 中，判定只能依赖源码文本。这类判定可能因注释或字符串误排，
 *   运行器会单独落盘到 `test262-text-exclusions.txt` 供人工复核
 */
internal data class SkipDecision(
    val reason: String,
    val textMatchOnly: Boolean = false,
)

/**
 * Test262 用例的选择与跳过判定。
 *
 * 从运行器中抽出为纯函数，目的有两个：
 * - 报告能区分"筛选阶段没纳入"和"选中后跳过"，避免把 836 个跳过误当成全仓缺口；
 * - 选择规则可以被单元测试直接覆盖，不必跑两万条语料。
 *
 * 本对象只做判定，不读文件、不执行脚本。
 */
internal object Test262Selection {

    /** 筛选阶段的排除原因。 */
    const val EXCLUDED_NO_METADATA = "无 metadata"
    const val EXCLUDED_MISSING_ES5_ID = "缺少 es5id"
    const val EXCLUDED_ES5_CASE = "ES5 用例（ES2015 子集排除）"
    const val EXCLUDED_UNSUPPORTED_FEATURES = "特性未全部支持"
    const val EXCLUDED_NO_FEATURE_ANNOTATION = "无特性标注且缺少 es6id/esid"
    const val EXCLUDED_PATH = "路径排除"
    const val EXCLUDED_FILTER = "--filter 不匹配"

    /**
     * 判定一个用例是否属于当前子集。
     *
     * @param relativePath 相对语料根目录的路径（分隔符统一为 `/`）
     * @param metadata 解析出的 metadata；文件没有 `---` 元数据块时为 null
     * @param suite `es5` 或 `es2015`
     * @param includeEsid 是否把"无 features 但有 esid"的用例纳入 ES2015 子集
     * @param filter 相对路径子串筛选；null 表示不过滤
     */
    fun decide(
        relativePath: String,
        metadata: Metadata?,
        suite: String,
        includeEsid: Boolean,
        filter: String?,
    ): SelectionDecision {
        if (metadata == null) return SelectionDecision.Excluded(EXCLUDED_NO_METADATA)
        if (suite == "es2015") {
            // ES2015 子集排除 ES5 用例，避免与 ES5 基线重复
            if (metadata.es5Id != null) return SelectionDecision.Excluded(EXCLUDED_ES5_CASE)
            val featuresSupported = metadata.features.isNotEmpty() &&
                metadata.features.all { it in es2015SupportedFeatures }
            val annotated = metadata.features.isEmpty() &&
                (metadata.es6Id != null || (includeEsid && metadata.esId != null))
            if (!featuresSupported && !annotated) {
                return SelectionDecision.Excluded(
                    if (metadata.features.isEmpty()) EXCLUDED_NO_FEATURE_ANNOTATION
                    else EXCLUDED_UNSUPPORTED_FEATURES
                )
            }
            if (es2015ExcludedPathFragments.any { relativePath.contains(it) }) {
                return SelectionDecision.Excluded(EXCLUDED_PATH)
            }
        } else {
            if (metadata.es5Id == null) return SelectionDecision.Excluded(EXCLUDED_MISSING_ES5_ID)
        }
        if (filter != null && !relativePath.contains(filter)) {
            return SelectionDecision.Excluded(EXCLUDED_FILTER)
        }
        return SelectionDecision.Selected
    }

    /**
     * 判定选中后的用例是否跳过，返回跳过原因。
     *
     * 这里的判断包含对源码的文本匹配（Proxy/Generator 等），
     * 因此可能因注释或字符串误排；[Test262Conformance] 会把每条跳过原因落盘，
     * 便于人工审计误排。
     *
     * @param source 去掉 metadata 后的可执行源码
     * @param suite 当前子集
     * @param relativePath 相对语料根目录的路径；用于已修复路径的定向放行
     */
    fun skipReason(
        source: String,
        metadata: Metadata,
        suite: String,
        relativePath: String = "",
    ): String? = skipDecision(source, metadata, suite, relativePath)?.reason

    /**
     * 判定选中后的用例是否跳过，并标记该判定是否只依赖源码文本。
     *
     * 与 [skipReason] 的区别：调用方可以据此把"文本匹配排除"单独落盘复核。
     */
    fun skipDecision(
        source: String,
        metadata: Metadata,
        suite: String,
        relativePath: String = "",
    ): SkipDecision? {
        if (metadata.flags.any { it in setOf("module", "generator") }) {
            return SkipDecision("unsupported flag")
        }
        // async 负数用例的期望落定方式与普通 $DONE 不同，暂不纳入
        if ("async" in metadata.flags && metadata.negativePhase != null) {
            return SkipDecision("unsupported async negative")
        }
        val unsupportedFeature = metadata.features.firstOrNull { it in unsupportedFeatures }
        if (unsupportedFeature != null) {
            return SkipDecision("unsupported feature: $unsupportedFeature")
        }
        if (source.contains("\$262")) {
            // 当前只放行 runner 真正提供的宿主钩子：evalScript 与 detachArrayBuffer；
            // 其余钩子（agent/createRealm 等）仍跳过
            val members = dollar262MemberPattern.findAll(source)
                .map { it.groupValues[1] }
                .toSet()
            if (members.isEmpty() || members.any { it !in supportedDollar262Members }) {
                return SkipDecision("unsupported harness: \$262", textMatchOnly = true)
            }
        }
        // 个别测试把宿主调试用的 print 挂到对象上，核心 runner 未提供该全局
        if (source.contains("Array.print")) {
            return SkipDecision("unsupported harness: print", textMatchOnly = true)
        }
        // 未实现的宿主型内建：测试源码中可能直接构造（ArrayBuffer 已支持，SharedArrayBuffer 仍排除）
        if (suite == "es2015" && source.contains("new SharedArrayBuffer")) {
            return SkipDecision("unsupported feature: SharedArrayBuffer", textMatchOnly = true)
        }
        // ES2015 子集中排除 Generator 内容（Proxy 已在 L2.4 纳入支持范围，
        // BigInt 已在 L3.3、TypedArray/ArrayBuffer/DataView 已在 L3.5 纳入）。
        // 例外：L1.6 已实现 %AsyncIteratorPrototype% 的 @@asyncIterator/@@asyncDispose，
        // 该目录用例虽然使用 async generator 语法，仍纳入基线以保持修复项的回归覆盖。
        if (suite == "es2015" &&
            !relativePath.startsWith(asyncIteratorPrototypePath) &&
            (source.contains("function *") ||
                source.contains("function*") ||
                generatorFunctionPattern.containsMatchIn(source) ||
                source.contains("async *") ||
                source.contains("async*") ||
                source.contains("for await") ||
                generatorMethodPattern.containsMatchIn(source))
        ) {
            return SkipDecision("unsupported Generator", textMatchOnly = true)
        }
        if (metadata.negativePhase != null && metadata.negativePhase !in setOf("parse", "runtime")) {
            return SkipDecision("unsupported negative phase: ${metadata.negativePhase}")
        }
        return null
    }

    /** 生成器方法语法（`*keys() {}` / `*[Symbol.iterator]() {}`），项目未实现。 */
    private val generatorMethodPattern = Regex(
        """(?m)(?:^\s*|[{(,;]\s*|\*/\s*|\bstatic\s+|\basync\s+)\*\s*(?:/\*[\s\S]*?\*/\s*)*(?:[A-Za-z_$][A-Za-z0-9_$]*|\[[^\]]*\]|#[^\s(]*)\s*(?:/\*[\s\S]*?\*/\s*)*\("""
    )

    /** 生成器函数声明/表达式（允许 function 与 * 之间夹注释）。 */
    private val generatorFunctionPattern = Regex(
        """\bfunction\s*(?:/\*[\s\S]*?\*/\s*)*\*"""
    )

    /**
     * 已由 L1.6 修复并纳入基线的异步迭代器路径。
     *
     * 该目录的用例验证 `%AsyncIteratorPrototype%` 上的 @@asyncIterator/@@asyncDispose
     * 语义，源码使用 async generator 语法；放行后由常规特性筛选与其它跳过规则继续约束。
     */
    internal const val asyncIteratorPrototypePath = "test/built-ins/AsyncIteratorPrototype/"

    /** `$262.<member>` 访问的成员名：当前放行 evalScript 与 detachArrayBuffer。 */
    private val dollar262MemberPattern =
        Regex("""\$262\s*\.\s*([A-Za-z_$][A-Za-z0-9_$]*)""")

    /** runner 在 `$262` 上提供的宿主钩子；其余成员按不支持跳过。 */
    private val supportedDollar262Members = setOf("evalScript", "detachArrayBuffer")

    /**
     * ES2015 子集选择：用例的全部 features 必须在此集合内。
     * 该集合只列出项目已实现并愿意纳入基线的特性。
     */
    private val es2015SupportedFeatures = setOf(
        "arrow-function", "class", "computed-property-names", "const", "let",
        "default-parameters", "destructuring-binding", "destructuring-assignment",
        "rest-parameters", "spread-syntax", "template", "tagged-template",
        // 核心库已提供完整 Reflect（ReflectBuiltins）：L1.4 验证后纳入基线，
        // 其中 GeneratorFunction/AsyncGeneratorFunction 的 is-a-constructor 用例
        // 依赖 Reflect.construct 与 wellKnownIntrinsicObjects harness
        "Reflect", "Reflect.construct",
        // L2.4 补齐 Proxy 不变量并启用 Test262 Proxy 用例；
        // proxy-missing-checks 覆盖 GetOwnProperty/DefineOwnProperty/Delete 的补充校验
        "Proxy", "proxy-missing-checks",
        // L3.3 已实现 BigInt 运行时：字面量/typeof/算术/转换/内建与 Test262 BigInt 用例
        "BigInt",
        // L3.5 已实现 ArrayBuffer detach、DataView 与 TypedArray（含 BigInt 视图）语义
        "TypedArray", "ArrayBuffer", "DataView",
        "for-of", "new.target", "super", "Symbol", "Symbol.iterator",
    "Symbol.asyncIterator",
        "Symbol.toPrimitive", "Symbol.toStringTag", "Symbol.hasInstance",
        "Symbol.species", "Symbol.isConcatSpreadable",
        "Symbol.match", "Symbol.replace", "Symbol.search", "Symbol.split",
        "Symbol.matchAll", "String.prototype.matchAll",
        "Symbol.unscopables", "set-methods", "RegExp.escape", "iterator-helpers",
        "iterator-sequencing", "joint-iteration", "symbols-as-weakmap-keys",
        "explicit-resource-management", "__getter__", "__setter__", "iterator-chunking",
        "await-dictionary", "iterator-includes", "Iterator.prototype.join", "Error.isError",
        "hashbang", "optional-catch-binding", "stable-array-sort", "json-superset",
        "error-stack-accessor", "json-parse-with-source", "for-in-order", "string-trimming",
        "regexp-dotall", "class-static-block", "regexp-match-indices", "regexp-named-groups",
        "regexp-modifiers", "regexp-duplicate-named-groups", "regexp-lookbehind",
        "regexp-unicode-property-escapes", "regexp-v-flag", "tail-call-optimization",
        "__proto__", "AggregateError", "coalesce-expression",
        "u180e", "legacy-regexp", "caller", "well-formed-json-stringify",
        "class-fields-public", "class-static-fields-public",
        "class-fields-private", "class-methods-private", "class-static-fields-private",
        "class-static-methods-private", "class-fields-private-in", "nonextensible-applies-to-private",
        "Symbol.prototype.description", "Math.sumPrecise",
        "Array.prototype.flat", "Array.prototype.flatMap", "Array.fromAsync",
        "Array.prototype.includes", "Array.prototype.at", "Array.prototype.values",
        "String.prototype.includes", "String.prototype.endsWith",
        "String.prototype.trimStart", "String.prototype.trimEnd",
        "String.prototype.at", "String.fromCodePoint",
        "String.prototype.replaceAll",
        "String.prototype.isWellFormed", "String.prototype.toWellFormed",
        "Object.is", "Object.hasOwn", "Object.fromEntries", "array-grouping",
        "array-find-from-last", "change-array-by-copy",
        "Map", "Set", "WeakMap", "WeakSet", "Promise", "async-functions",
        "upsert", "error-cause",
        "Promise.allSettled", "Promise.any",
        "Promise.prototype.finally", "promise-with-resolvers", "promise-try",
        // 已在解析器/运行时实现的后续版本语法
        "exponentiation", "logical-assignment-operators", "globalThis",
        "object-rest", "object-spread", "optional-chaining",
        "numeric-separator-literal",
        "u-flag", "y-flag", "unicode-escapes",
    )

    /**
     * ES2015 子集中排除的路径片段：
     * - 未实现的宿主型内建（SharedArrayBuffer/Atomics 等）；
     * - 内容依赖 Generator 语法的计算属性名用例（Generator 明确排除）；
     * - 依赖 TypedArray 但不属于 L3.5 范围的 Object.seal 整数索引语义用例。
     *
     * %AsyncIteratorPrototype% 已在 L1.6 实现，路径排除已移除（见 [asyncIteratorPrototypePath]）。
     */
    private val es2015ExcludedPathFragments = listOf(
        "test/built-ins/SharedArrayBuffer/",
        "test/built-ins/Atomics/",
        "-from-generator-function-declaration.js",
        "-from-yield-expression.js",
        // V8/最新规范允许子类实例调用 RegExp.prototype.compile，测试基于旧草案
        "test/annexB/built-ins/RegExp/prototype/compile/this-subclass-instance.js",
        // 可选链用例中使用 Reflect.construct（项目明确排除 Reflect）
        "test/language/expressions/optional-chaining/new-target-optional-call.js",
        // TypedArray 的 Object.seal 用例无 features 标注，依赖 Object.seal 对整数索引
        // 异质对象的完整语义（不在 L3.5 范围）
        "test/built-ins/Object/seal/seal-int8array.js",
        "test/built-ins/Object/seal/seal-int16array.js",
        "test/built-ins/Object/seal/seal-int32array.js",
        "test/built-ins/Object/seal/seal-uint8array.js",
        "test/built-ins/Object/seal/seal-uint8clampedarray.js",
        "test/built-ins/Object/seal/seal-uint16array.js",
        "test/built-ins/Object/seal/seal-uint32array.js",
        "test/built-ins/Object/seal/seal-float32array.js",
        "test/built-ins/Object/seal/seal-float64array.js",
        "test/built-ins/Object/seal/seal-bigint64array.js",
        "test/built-ins/Object/seal/seal-biguint64array.js",
    )

    /** 当前范围外或尚未实现的特性（Reflect、Proxy、BigInt、TypedArray 已纳入支持范围）。 */
    private val unsupportedFeatures = setOf(
        "SharedArrayBuffer", "Atomics",
        "Intl", "WeakRef", "FinalizationRegistry",
        "decorators",
        "generator", "async-iteration",
        "dynamic-import", "import-attributes", "top-level-await",
        "resizable-arraybuffer", "arraybuffer-transfer", "Float16Array",
        "Temporal",
    )
}
