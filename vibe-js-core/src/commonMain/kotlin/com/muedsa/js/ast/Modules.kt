package com.muedsa.js.ast

import com.muedsa.js.lexer.SourceInfo
import com.muedsa.js.lexer.SourceRange

/**
 * ES Module 的语法树节点。
 *
 * 设计说明（L4 原型，仅解析，不参与求值）：
 * - 模块项 [ModuleItem] **不继承** [Statement]：`import` / `export` 声明在规范里是
 *   ModuleItem，不是可执行语句。这样既贴合 ECMAScript 文法
 *   （`ModuleItem : ImportDeclaration | ExportDeclaration | StatementListItem`，
 *   见 ES2024 16.2.1），也避免给 [Statement] 增加子类——解释器里对 `Statement`
 *   的 `when` 是穷尽式（`Interpreter.kt` 的 `executeStatementInternal`），新增子类
 *   会强制改动解释器执行路径。
 * - 导出声明通过 [ExportNamedDeclaration.declaration] / [ExportDefaultDeclaration.declaration]
 *   包装真实语句，模块求值器（L4.3）按 [ModuleProgram.items] 的源码顺序逐项求值，
 *   普通语句直接交给现有 `Interpreter.executeStatements` 的单语句入口。
 * - [ImportEntry] / [ExportEntry] 是规范 Module Record 的静态语义表
 *   （[[ImportEntries]] / [[LocalExportEntries]] 等），由 Parser 预计算，
 *   供 L4.2 链接阶段直接使用，避免运行时再扫描 AST。
 */

/**
 * 模块项。
 *
 * @see StatementItem
 * @see ImportDeclaration
 * @see ExportNamedDeclaration
 * @see ExportDefaultDeclaration
 * @see ExportAllDeclaration
 */
sealed class ModuleItem : ASTNode()

/**
 * 普通语句项：把 [Statement] 包进模块项层次。
 *
 * 包装而不让 `Statement` 直接出现在 [ModuleProgram.items] 里，是为了让模块求值器
 * 能在一个密封类型上做穷尽分派，不必关心"这条语句是否被 export 包裹"。
 */
class StatementItem(
    val statement: Statement,
    override val range: SourceRange = statement.range,
) : ModuleItem()

/**
 * 具名导入/导出说明符。
 *
 * @property importedName 导入名：`default`、普通标识符或 `*`（命名空间导入）
 * @property localName 本地绑定名
 */
class ImportSpecifier(
    val importedName: String,
    val localName: String,
    override val range: SourceRange,
) : ASTNode()

/** 导入相位：决定目标模块何时参与求值，而不是模块本身的身份。 */
enum class ImportPhase {
    /** 普通导入；导入方求值时目标模块也完成求值。 */
    EAGER,

    /** `import defer`；只创建延迟 namespace，首次访问才触发求值。 */
    DEFER,
}

/** 一条 ModuleRequest Record；同说明符可因 phase 不同而出现多次。 */
class ModuleRequestInfo(
    val specifier: String,
    val phase: ImportPhase,
)

/**
 * `import` 声明。
 *
 * 支持的形式（ES2015 基础子集）：
 * - `import "m"`（副作用导入，[isSideEffectOnly] 为 true）
 * - `import d from "m"`（默认导入：[specifiers] 中 importedName 为 `default`）
 * - `import {a, b as c} from "m"`（具名导入，允许空 `{}` 与末尾逗号）
 * - `import * as ns from "m"`（命名空间导入）
 * - `import d, {a} from "m"` / `import d, * as ns from "m"`
 * - `import { "字符串名" as x } from "m"`（任意模块命名空间名）
 *
 * `import.meta` 与 `import()` 属于表达式层语法，分别见 [ImportMetaExpr] 与 [ImportCallExpr]。
 *
 * @property moduleRequest 模块说明符（字符串字面量解码后的值）
 * @property specifiers 具名导入项（含默认导入）
 * @property namespaceImport `import * as ns` 的本地名；无命名空间导入时为 null
 */
class ImportDeclaration(
    val moduleRequest: String,
    val specifiers: List<ImportSpecifier>,
    val namespaceImport: String?,
    /** 是否为纯副作用导入 `import "m"`（`import {} from "m"` 不算，它没有导入表项） */
    val isSideEffectOnly: Boolean,
    override val range: SourceRange,
    /** `with { ... }` 导入属性；键与值都是已解码字符串。 */
    val attributes: Map<String, String> = emptyMap(),
    /** 该声明的导入相位；只有 `import defer * as ns` 为 DEFER。 */
    val phase: ImportPhase = ImportPhase.EAGER,
) : ModuleItem()

/**
 * 导出说明符：`export { local as exported }`。
 *
 * 带 `from` 时 [localName] 是**导入名**、[moduleRequest] 非 null；
 * 不带 `from` 时 [localName] 是本地绑定名、[moduleRequest] 为 null。
 */
class ExportSpecifier(
    val localName: String,
    val exportName: String,
    override val range: SourceRange,
) : ASTNode()

/**
 * `export` 具名声明。
 *
 * 三种形态：
 * - `export var/let/const/function/class ...`：[declaration] 非 null，[specifiers] 为空；
 * - `export {a as b}`：[declaration] 为 null、[source] 为 null、[specifiers] 非空；
 * - `export {a as b} from "m"`：[source] 非 null。
 */
class ExportNamedDeclaration(
    val declaration: Statement?,
    val specifiers: List<ExportSpecifier>,
    val source: String?,
    override val range: SourceRange,
    /** `export ... from "m" with { ... }` 的导入属性；本地导出为空。 */
    val attributes: Map<String, String> = emptyMap(),
) : ModuleItem()

/**
 * `export default` 声明。
 *
 * 两种形态（规范 16.2.3.7 的 HoistableDeclaration / ClassDeclaration / AssignmentExpression）：
 * - 具名函数/类声明：[declaration] 非 null，同时导出 `*default*` 与具名的本地绑定；
 * - 匿名函数/类或任意表达式：[expression] 非 null，`*default*` 在求值点初始化。
 *
 * 具体导出表项见 [ModuleProgram.exportEntries]（`ExportEntry("default", ...)`）。
 */
class ExportDefaultDeclaration(
    val declaration: Statement?,
    val expression: Expression?,
    override val range: SourceRange,
) : ModuleItem()

/**
 * `export * from "m"` 与 `export * as ns from "m"`。
 *
 * @property namespaceName `export * as ns` 的导出名；普通 `export *` 为 null
 */
class ExportAllDeclaration(
    val source: String,
    val namespaceName: String?,
    override val range: SourceRange,
    /** `export * from "m" with { ... }` 的导入属性。 */
    val attributes: Map<String, String> = emptyMap(),
) : ModuleItem()

/**
 * 规范 ImportEntry（ES2024 16.2.1.5）：[[ModuleRequest]] / [[ImportName]] / [[LocalName]]。
 *
 * @property moduleRequest 模块说明符
 * @property importName `default`、具体导出名或 `*`（命名空间导入）
 * @property localName 本地绑定名
 */
class ImportEntry(
    val moduleRequest: String,
    val importName: String,
    val localName: String,
    val range: SourceRange = SourceRange.NONE,
    /** 该导入请求的 `with { ... }` 属性。 */
    val attributes: Map<String, String> = emptyMap(),
    /** 该导入表项的相位。 */
    val phase: ImportPhase = ImportPhase.EAGER,
)

/**
 * 规范 ExportEntry（ES2024 16.2.1.5）：
 * [[ExportName]] / [[ModuleRequest]] / [[ImportName]] / [[LocalName]]。
 *
 * @property exportName 导出名；`export *` 为 null（不产生具名导出）
 * @property moduleRequest 间接导出（`export ... from`）的模块说明符；本地导出为 null
 * @property importName 间接导出的导入名；本地导出为 null
 * @property localName 本地导出的本地绑定名；间接/星号导出为 null
 */
class ExportEntry(
    val exportName: String?,
    val moduleRequest: String?,
    val importName: String?,
    val localName: String?,
    val range: SourceRange = SourceRange.NONE,
    /** 间接导出的 `with { ... }` 属性；本地导出为空。 */
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * 模块顶层绑定种类。
 *
 * 由 Parser 预计算，供模块声明实例化直接使用；运行时不再扫描 AST 判断
 * `var` / `let` / `const` / `class` / `function` / `import`。
 */
enum class ModuleBindingKind {
    IMPORT,
    LET,
    CONST,
    CLASS,
    FUNCTION,
    VAR,
    USING,
    /** 匿名 `export default` 的 `*default*` 绑定。 */
    DEFAULT,
}

/** 仅用于 IMPORT 绑定：本地名指向哪个模块的哪个导出名。 */
class ImportedBinding(
    val moduleRequest: String,
    val importName: String,
)

/** 模块顶层绑定的类型化元数据。 */
class ModuleBinding(
    val kind: ModuleBindingKind,
    val name: String,
    val imported: ImportedBinding?,
    override val range: SourceRange,
) : ASTNode()

/**
 * `import.meta` 元属性表达式。
 *
 * 语法（ES2024 13.3.12）：`import . meta`，`meta` 必须是无转义的标识符名。
 * 只在 Module 目标下合法；脚本目标由 Parser 按保留字拒绝。
 * 求值语义（宿主提供的模块元数据对象）属于 L4.3，本切片只负责表达式层语法。
 */
class ImportMetaExpr(
    override val range: SourceRange,
) : Expression()

/**
 * 动态导入调用 `import(specifier[, options])`。
 *
 * 语法层宽容点：
 * - 参数个数允许 0..2（规范 13.3.12 要求至少一个实参、至多两个；运行期 L4.3
 *   在真正执行时再按规范拒绝零参数形态）；
 * - 允许尾逗号与第二个可选参数（导入属性对象）。
 *
 * 与普通 [CallExpr] 分开建模，避免解释器把 `import` 当成普通标识符调用，
 * 同时保留 `import` 自身在脚本目标中作为保留字的诊断。
 *
 * @property arguments 实参表达式（0..2 个）
 */
class ImportCallExpr(
    val arguments: List<Expression>,
    override val range: SourceRange,
    /** `import.defer(...)` 为 true；普通 `import(...)` 为 false。 */
    val isDeferred: Boolean = false,
) : Expression() {

    /** 模块说明符（第一个实参）；`import()` 零参数时为 null。 */
    val specifier: Expression? get() = arguments.getOrNull(0)

    /** 导入属性/选项对象（第二个实参）；未提供时为 null。 */
    val options: Expression? get() = arguments.getOrNull(1)
}

/**
 * 模块根节点（Module 目标）。
 *
 * 与 [Program]（Script 目标）并列而不是合并：模块与脚本在严格模式、顶层声明种类、
 * `this`/`var` 落点、`import.meta` 上下文上都不同，早期错误集合也不同；分开可以
 * 保证 [`Parser.parseProgram`] 的脚本路径完全不变（parser 一致性基线不受影响）。
 *
 * @property items 模块项（源码顺序，求值的权威顺序）
 * @property statements 去掉导入/导出外壳后的语句序列，**仅用于**变量提升与早期错误检查；
 *   求值必须按 [items] 顺序进行，因为 `export default <表达式>` 的求值位置在源码中
 * @property varDeclarations 模块 var 作用域的 var 名（含 `export var`）
 * @property functionDeclarations 模块顶层函数声明（在模块中属于**词法**声明，提升到模块环境）
 * @property lexicallyDeclaredNames 模块顶层词法声明名：import 绑定 + let/const/class/function
 * @property bindings 模块顶层类型化绑定，供 ModuleDeclarationInstantiation 直接使用
 * @property hasTopLevelAwait 是否包含模块顶层 await；MVP 链接期据此拒绝 TLA
 * @property hasUsingDeclaration 顶层是否直接包含 using 声明
 */
class ModuleProgram(
    val items: List<ModuleItem>,
    val statements: List<Statement>,
    val varDeclarations: List<String>,
    val functionDeclarations: List<FunctionDeclaration>,
    val lexicallyDeclaredNames: List<String>,
    val importEntries: List<ImportEntry>,
    val exportEntries: List<ExportEntry>,
    /** 模块请求的去重列表（源码顺序），链接阶段按此顺序加载依赖。 */
    val requestedModules: List<String>,
    val hasUsingDeclaration: Boolean,
    override val range: SourceRange,
    /** 模块顶层类型化绑定；用于 ModuleDeclarationInstantiation。 */
    val bindings: List<ModuleBinding> = emptyList(),
    /** 是否包含模块顶层 await（TLA）；MVP 在链接期拒绝。 */
    val hasTopLevelAwait: Boolean = false,
    /** 引擎预解析时绑定的模块来源（路径/URL）；Parser 直接调用使用匿名来源。 */
    val sourceInfo: SourceInfo = SourceInfo(),
    /** 每个模块请求的导入属性；键为说明符，值按 `with` 子句的源码顺序。 */
    val requestedAttributes: Map<String, Map<String, String>> = emptyMap(),
    /** 每个模块请求出现过的相位；同一说明符可同时有 EAGER 与 DEFER 边。 */
    val requestedPhases: Map<String, Set<ImportPhase>> = emptyMap(),
    /** 按源码顺序首次出现的 eager 请求；决定导入方的求值顺序。 */
    val requestedEagerModules: List<String> = emptyList(),
    /** 按源码顺序首次出现的 defer 请求；供 TLA 传递依赖收集使用。 */
    val requestedDeferredModules: List<String> = emptyList(),
    /** 按源码顺序的 ModuleRequest 列表；同说明符不同 phase 分别保留。 */
    val requestedRequests: List<ModuleRequestInfo> = emptyList(),
) : ASTNode() {

    /** 模块代码恒为严格模式（ECMAScript 16.2.1.6：Module code is always strict mode code）。 */
    val strict: Boolean get() = true
}
