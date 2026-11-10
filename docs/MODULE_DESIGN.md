# 模块执行设计与实现方案

> 状态：**阶段 0–7 已作为实验性模块实现落地；模块执行仍不承诺稳定兼容**。Parser、
> 公共 Provider API、内部图加载/链接、ModuleEnvironment、namespace、`import.meta` 与
> `Engine.runModule` 已接通；剩余工作是阶段 8 的错误定位、预算/取消边界、文档与审查。
> 该 API 需要 `@ExperimentalModuleApi` opt-in，不承诺正式版兼容性。
>
> 目标不是做 Node/浏览器级 ESM 运行时，而是给 Kotlin 宿主提供一个**纯 Kotlin、无 IO、可注入加载器**
> 的模块链接/求值核心。首版是同步静态模块 MVP。

## 1. 结论摘要

- 核心负责：模块图、链接、`ResolveExport`、模块环境、namespace、同步求值。
- 宿主负责：说明符规范化、源码读取、网络/文件/打包器、模块 id 的稳定性。
- 首版支持：静态 `import` / `export`、默认导出、re-export、`export *`、`export * as ns`、
  命名空间导入、`import.meta`、循环依赖、live binding。
- 首版不支持：动态 `import()` 执行、Top-Level Await 执行、import attributes/JSON modules、
  文件/URL/网络加载器、CLI、Test262 module harness。
- `Engine.runModule` 每次调用使用独立模块图；同一模块在单次调用内只求值一次。
- 模块共享 `Engine` 的 realm/global，但每个模块有独立 `ModuleEnvironment`。
- 模块顶层 `this = undefined`，恒严格模式。
- 公共 API 标记为实验性，不承诺 PKG1 式稳定契约。

## 2. 与主流引擎的定位

| 维度 | 本设计 | Node/V8 运行时 | 浏览器 | QuickJS/嵌入式 |
|---|---|---|---|---|
| 内部链接/求值 | 规范形状 | 规范实现 | 规范实现 | 规范形状 |
| 说明符解析 | Provider 全权负责 | package + URL | URL + import maps | 宿主 loader 回调 |
| 模块缓存 | 每次 `runModule` 独立图 | 运行时缓存 | module map | 宿主/上下文决定 |
| 动态 `import()` | 后置 | 支持 | 支持 | 视版本/loader |
| TLA | 后置，链接期拒绝 | 支持 | 支持 | 视版本 |
| import attributes | 不支持 | 支持 | 支持 | 视版本 |
| CLI/harness | 首版不做 | Node CLI | 无 CLI | qjs CLI |

结论：**核心模型接近规范，宿主边界接近 QuickJS；首版功能集是受控嵌入式子集，不是通用 ESM 运行时。**

### 2.1 与主流引擎一致

- 内部使用 `ModuleRecord`、`ModuleStatus`、`ResolveExport`、live binding、namespace exotic
  对象和 DFS 后序求值；这些与 V8、JavaScriptCore、SpiderMonkey 的模块实现方向一致。
- 说明符解析、源码读取和模块身份由宿主提供；QuickJS 的 `JS_SetModuleLoaderFunc` 是同类
  host hook，浏览器/Node 则由运行时内置 loader 完成。
- 静态链接错误发生在求值前，模块内 `try/catch` 不能捕获；这与规范模块的链接阶段一致，
  只是本设计把错误暴露为 Kotlin 异常而不是 JS 异常。
- `runModule` 返回入口 namespace，类似动态 `import()` 的解析结果；这是宿主 API 便利性，
  不改变模块求值本身的完成值。

### 2.2 与主流引擎的关键差异

| 差异 | 本设计 | 主流引擎 | 影响 |
|---|---|---|---|
| 模块图生命周期 | 每次 `runModule` 独立图 | Node/浏览器按模块表缓存 | 同一模块重复求值，副作用重复；宿主需自行缓存 |
| 说明符规范化 | Provider 全权负责 | Node package/URL；浏览器 URL/import maps | 宿主需实现相对路径、包解析、URL 规则 |
| 动态 `import()` | 执行到表达式时报错 | 支持 | 路由懒加载、插件动态加载、`await import()` 不可用 |
| Top-Level Await | 链接期拒绝 | 支持 | 大量现代 ESM 包不能直接运行 |
| import attributes | 不支持 | Node 22+/现代浏览器支持 | JSON/WASM 模块导入不可用 |
| 诊断 | MVP 才补 entry range | 文件/行/列/导入链完整 | 链接错误定位弱于主流引擎 |
| 加载方式 | 顺序 DFS | 可并行/预加载 | 网络/文件慢时吞吐较低 |
| CLI/harness | 不做 | Node CLI / Test262 harness | 无法直接作为通用运行时或宣称完整 ESM 一致性 |
| 错误模型 | 链接错误 Kotlin 异常 | 多为 JS `SyntaxError`/宿主错误码 | 嵌入式 API 更自然，但和 JS 生态习惯不同 |

其他需要明确记录的差异：

- `import.meta.url` 在缺少 provider URL 时回退为 `module:<id>`，不是文件 URL 或页面 URL。
- 模块内普通函数体中的 `await`、零参数 `import()` 目前 parser 比 V8/Node 宽松，首版前
  需要收紧；否则会出现“能解析但运行不标准”。
- 首版不提供 Engine 级/进程级模块表；如果宿主期望 Node 式单例模块，需要自行实现缓存，
  或等待后续设计。

### 2.3 适用性判断

**适合**：Kotlin 宿主嵌入执行受控模块、插件/配置脚本、由宿主自行解析说明符、需要资源预算
和取消传播的场景。

**不适合**：直接运行任意 npm ESM 包、浏览器式懒加载、依赖 TLA/import attributes 的现代应用、
期望模块只求值一次的 Node 式宿主。

## 3. 总体架构

```text
Engine.runModule(entry, provider)
  └─ ModuleRunner
       ├─ ModuleLoader      provider.load / id 去重 / 预算
       ├─ ModuleLinker      import/export 表 / ResolveExport / 链接错误
       ├─ ModuleEnvironment 模块顶层绑定 / import 转发 / TDZ
       ├─ JSModuleNamespace 导出命名空间 exotic 对象
       └─ Interpreter       模块声明实例化 / 模块体同步求值
```

建议包结构：

```text
com.muedsa.js.runtime.module          public API：ModuleRequest/ModuleSource/ModuleProvider/...
com.muedsa.js.runtime.module.internal 内部：ModuleRecord/ModuleGraph/ModuleLinker/...
```

公共 API 尽量少；内部类型不暴露 ModuleProgram 之外的可变状态。

## 4. 公共 API 契约

### 4.1 实验性标记

```kotlin
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
annotation class ExperimentalModuleApi
```

`ModuleRequest`、`ModuleSource`、`ModuleProvider`、`Engine.runModule`、`ModuleLinkException`
都标记为实验性。

### 4.2 Provider 类型

```kotlin
data class ModuleRequest(
    /** 源码中写的原始说明符，例如 "./lib/greet.mjs"。 */
    val specifier: String,
    /** 发起请求的模块 id；入口请求为 null。 */
    val referrer: String? = null,
)

data class ModuleSource(
    /** 宿主规范化的稳定唯一 id；相同 id 表示同一个模块。 */
    val id: String,
    /** 已解码的 UTF-8 文本；BOM 由宿主剥离。 */
    val source: String,
    /** 错误栈显示名；不参与模块身份。 */
    val sourceName: String = id,
    /** import.meta.url；为空时使用 module:<id>。 */
    val url: String? = null,
)

fun interface ModuleProvider {
    /**
     * 返回 null 表示找不到；异常原样透传，核心不包装、不吞掉。
     * 实现必须可协作取消。
     */
    suspend fun load(request: ModuleRequest): ModuleSource?
}

object EmptyModuleProvider : ModuleProvider {
    override suspend fun load(request: ModuleRequest): ModuleSource? = null
}

class InMemoryModuleProvider(
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
```

约束：

- 核心不拼 `./`、`../`、URL、平台路径；provider 负责。
- `id` 必须非空且稳定；核心只按 `id` 去重。
- `sourceName` 只影响错误栈；`url` 只影响 `import.meta.url`。
- provider 可以跨 `runModule` 复用；核心不缓存 provider 实例。
- `InMemoryModuleProvider` 使用精确 id，不做相对路径解析；宿主需要自行规范化。

### 4.3 Engine 入口（目标 API，尚未实现）

```kotlin
suspend fun Engine.runModule(
    entry: ModuleSource,
    provider: ModuleProvider = EmptyModuleProvider,
): JSObject

suspend fun Engine.runModule(
    entrySpecifier: String,
    provider: ModuleProvider,
): JSObject
```

> 阶段 7 已实现上述两个重载；调用方需要 opt-in `@ExperimentalModuleApi`。

- 两个重载都返回**入口模块的 namespace 对象**。
- `ModuleSource` 入口允许省略 provider；如果源码包含静态 import，缺失 provider 会链接失败。
- `entrySpecifier` 入口的 provider 必须能加载入口；`ModuleRequest.referrer = null`。
- `runModule` 复用 `eventLoop.runTask`、execution budget、对象分配预算和微任务清理。
- 每次调用新建模块图和新预算；失败只丢弃本次图，Engine 可继续使用。
- 不建议把 `ModuleProgram` 或内部 `ModuleRecord` 暴露给宿主。

## 5. Parser/AST 前置修改

### 5.1 `requestedModules` 修复

现状：`requestedModules` 只从 `ImportEntry` / `ExportEntry` 收集，导致：

```js
import {} from "./dep.mjs";   // 不产生 ImportEntry
export {} from "./dep.mjs";   // 不产生 ExportEntry
```

两种形式都不会进入 `requestedModules`，依赖会被漏加载。

修复要求：

- 从每条 `ImportDeclaration.moduleRequest`、`ExportNamedDeclaration.source`、
  `ExportAllDeclaration.source` 收集模块请求，而不只从 entry 收集。
- 保持源码顺序、去重。
- `requestedModules` 是链接阶段的权威依赖列表。
- 增加 `import {} from` / `export {} from` 的单测；现有 262 个模块语料必须保持通过。

### 5.2 `parseModule` 的 SourceInfo

```kotlin
fun Parser.parseModule(
    sourceInfo: SourceInfo = SourceInfo(),
): ModuleProgram
```

- `ModuleProgram.sourceInfo` 真正写入错误栈显示名。
- `Engine` 模块入口传入 `ModuleSource.sourceName`。
- `parseProgram` 签名和行为完全不变。

### 5.3 `ImportEntry` / `ExportEntry` 源码范围

```kotlin
class ImportEntry(
    val moduleRequest: String,
    val importName: String,
    val localName: String,
    val range: SourceRange,
)

class ExportEntry(
    val exportName: String?,
    val moduleRequest: String?,
    val importName: String?,
    val localName: String?,
    val range: SourceRange,
)
```

链接错误至少能带：

- 说明符
- 发起模块 id / sourceName
- 导出名
- 声明 range（行/列）

这会增加 parser 改动，但连接错误可诊断性明显低于主流引擎，不建议长期缺失。

### 5.4 类型化模块声明

`ModuleProgram` 目前只有 `lexicallyDeclaredNames`、`varDeclarations`、`functionDeclarations`
等扁平信息，无法直接判断每个名字的绑定种类。

建议新增：

```kotlin
enum class ModuleBindingKind {
    IMPORT,
    LET,
    CONST,
    CLASS,
    FUNCTION,
    VAR,
    USING,
    /** 匿名 export default 的 *default* 绑定。 */
    DEFAULT,
}

/** 仅用于 IMPORT 绑定：本地名指向哪个模块的哪个导出名。 */
data class ImportedBinding(
    val moduleRequest: String,
    val importName: String,
)

class ModuleBinding(
    val kind: ModuleBindingKind,
    val name: String,
    val imported: ImportedBinding?, // 仅 IMPORT 使用
    val range: SourceRange,
)
```

`ModuleProgram` 增加：

```kotlin
val bindings: List<ModuleBinding>
```

Parser 预计算，不在运行时扫描 AST。需要覆盖：

- 具名/默认/命名空间 import
- `var` / `let` / `const` / `class` / `function`
- `export default function/class` 的本地名
- 匿名 `export default` 的 `*default*`
- 顶层 `using` / `await using`（语法已存在，MVP 不执行）

### 5.5 TLA 标记与语法收紧

`ModuleProgram` 增加：

```kotlin
val hasTopLevelAwait: Boolean
```

同时修复两个与主流引擎不一致的宽松点（阶段 1 已实现）：

1. 模块中的普通函数/箭头函数体不应因为 `moduleContext` 而接受 `await`；只允许
   `asyncContext` 或顶层模块上下文。`function f() { await 1; }` 已是语法错误。
2. `import()` 零参数在解析期直接拒绝，并保留 1–2 个实参与尾逗号的测试。

TLA 首版处理：

- `hasTopLevelAwait == true` 的模块在链接阶段抛 `ModuleLinkException`，
- 错误信息明确写“Top-Level Await is not supported in this build”，
- 不执行任何模块体。

`import()` 语法可以保留解析；执行到 `ImportCallExpr` 时沿用现有
`SyntaxError("Dynamic import is not supported")`。未执行到的死代码不阻止模块运行。

## 6. 链接设计

### 6.1 内部模型

```kotlin
internal enum class ModuleStatus {
    NEW,
    LINKING,
    LINKED,
    EVALUATING,
    EVALUATED,
    ERRORED,
}

internal class ModuleRecord(
    val source: ModuleSource,
    val program: ModuleProgram,
    var status: ModuleStatus = ModuleStatus.NEW,
    var environment: ModuleEnvironment? = null,
    var namespace: JSObject? = null,
    var importMeta: JSObject? = null,
    var evaluationError: JSException? = null,
) {
    val id: String get() = source.id
    val sourceName: String get() = source.sourceName

    /** 加载阶段按 requestedModules 顺序填充；循环引用时允许先建 record 再回填。 */
    val dependencies: MutableList<ModuleRecord> = mutableListOf()
}

internal sealed interface ResolveExportResult {
    data class Resolved(
        val module: ModuleRecord,
        val bindingName: String,
    ) : ResolveExportResult

    /** `export * as ns`：解析到目标模块的 namespace，而非普通绑定。 */
    data class Namespace(
        val module: ModuleRecord,
    ) : ResolveExportResult

    data object Ambiguous : ResolveExportResult
    data object NotFound : ResolveExportResult
}
```

`ModuleRecord` 只在一次 `runModule` 内存在，不做全局缓存。

### 6.2 加载图

```kotlin
suspend fun loadGraph(entry: ModuleSource, provider: ModuleProvider): ModuleRecord
```

- 解析入口，创建 `ModuleRecord`。
- 按 `requestedModules` 顺序 DFS。
- 单次图内按 `(specifier, referrer)` 缓存 provider 结果。
- 再按 `source.id` 去重。
- provider 返回 null → `ModuleLinkException(NOT_FOUND)`。
- provider 抛异常 → 原样透传。
- 模块源码解析失败 → `ParseException`，带 `sourceName`。
- 每个 `load` 前后 `ensureActive()`；provider 必须协作取消。
- `maxModules` 在创建 record 前检查；`maxTotalModuleSourceLength` 在累加源码时检查。

### 6.3 `ResolveExport`

按规范递归实现，带 `resolveSet`：

```kotlin
internal fun resolveExport(
    module: ModuleRecord,
    exportName: String,
    resolveSet: MutableList<Pair<ModuleRecord, String>> = mutableListOf(),
): ResolveExportResult
```

规则：

- 本地导出：返回 `Resolved(module, localName)`。
- 间接导出：递归目标模块的 `importName`。
- `export *`：遍历目标模块的导出名，排除 `default`；同名来自不同目标且不一致 → `Ambiguous`。
- `export * as ns`：返回 `Namespace(target)`，不参与普通星号歧义。
- 循环：`resolveSet` 中重复出现同一 `(module, exportName)` → `NotFound`，避免无限递归。
- 缓存仅在解析完成且不依赖未完成递归时写入；保守实现可以先不缓存，正确性优先。

### 6.4 链接阶段

当前实现范围（阶段 4）：解析可达图、拒绝 TLA、为每条 import 解析目标导出、填充 namespace 导出名；
ModuleEnvironment/声明实例化与求值属于阶段 5+。

- 解析整个可达图。
- 对每个模块创建环境骨架和声明绑定（见第 7 节）。
- 为每条 import 解析目标导出；缺失/歧义立即抛 `ModuleLinkException`。
- 检查 TLA 标记；有 TLA 直接拒绝。
- 记录 namespace 元数据（导出名集合），但不创建 JS 对象、不执行模块体。
- 链接失败快速失败，不进入求值。
- 所有模块链接完成后，才允许进入求值。

## 7. 模块环境与求值

### 7.1 `ModuleEnvironment`

`DeclarativeEnvironment` 已改为 `open`，新增 `ModuleEnvironment`：

```kotlin
internal class ModuleEnvironment(
    parent: Environment? = null,
    val record: ModuleRecord,
) : DeclarativeEnvironment(parent, isVarScope = true)
```

- 本地 `let/const/class` 继续使用父类 Map 与 TDZ。
- `import` 名维护在 `ModuleRecord.importBindings`，只读转发目标绑定。
- `hasBinding` / `getBinding`：import 名实时读取目标模块环境；本地走父类。
- `setBinding`：import 名抛 TypeError；本地走父类。
- `deleteBinding`：import 名返回 false。
- 模块环境自身是 var 作用域，顶层 var/function 不进入 globalThis；父环境为 `context.globalEnvironment`。
- 模块顶层 `this = undefined` 由阶段 7 的执行帧设置。

```kotlin
internal data class ModuleImportBinding(
    /** 链接阶段解析后的目标模块，而不是原始说明符。 */
    val target: ModuleRecord,
    /** 目标模块中的本地绑定名（已经是 ResolveExport 的结果）。 */
    val bindingName: String,
)
```

### 7.2 声明实例化

阶段 5 已实现内部 `ModuleDeclarationInstantiator(interpreter)`：

- 创建 `ModuleEnvironment`，父环境为 `context.globalEnvironment`。
- 创建 `var` 绑定，初始化为 `undefined`。
- 创建 function 声明绑定并初始化函数对象；模块顶层 function 按词法绑定处理。
- 创建 `let` / `const` / `class` / `using` 未初始化绑定，进入 TDZ。
- 创建 `*default*` 未初始化绑定；匿名 `export default` 在求值点初始化。
- import 名只登记到 `ModuleRecord.importBindings`；命名空间导入的 namespace 值在阶段 6 创建。
- 不执行任何用户语句。
- 绑定种类全部来自 `ModuleProgram.bindings`，不在运行时扫描 AST。

### 7.3 求值（阶段 7 已实现）

`ModuleEvaluator` 先实例化整个图，再按 DFS 后序执行模块体；`Interpreter.runModule` 使用
`this = undefined`、严格模式和模块环境，按 `ModuleProgram.items` 顺序执行普通语句/导出声明，
匿名 `export default` 在源码位置写入 `*default*` 绑定。

```text
evaluate(record):
  if EVALUATED: return
  if ERRORED: rethrow evaluationError
  if EVALUATING: return            # 循环，依赖 TDZ 保证访问顺序
  status = EVALUATING
  for dependency in dependencies: evaluate(dependency)
  execute module body in record.environment, this = undefined, strict = true
  status = EVALUATED
```

- 求值使用 `Interpreter` 的模块专用入口，复用语句执行与 `Completion`。
- 模块体按 `ModuleProgram.items` 源码顺序执行。
- 顶层 `var` / function / let / const / class 全部落在模块环境，不污染 globalThis。
- 求值异常记录到 `record.evaluationError`，状态置 `ERRORED`；同一图内后续引用重抛。
- `CancellationException` 原样传播，不写入 `evaluationError`，也不转换成 JS 异常。
- 链接失败、TLA 拒绝在求值前发生。
- 模块内未执行的死代码中的 `import()` 不影响模块；执行到表达式时抛现有 SyntaxError。
- 每次 `runModule` 的模块图独立，副作用不会跨调用复用。
- `runModule` 返回入口 namespace；返回值不依赖模块体最后表达式。

### 7.4 `import.meta`（阶段 7 已实现）

- 每个 `ModuleRecord` 缓存一个 `import.meta` 对象。
- 空原型、普通对象。
- `url` 属性来自 `ModuleSource.url`；为空时使用 `"module:" + id`。
- `import.meta` 对象在同一模块内复用；不同模块不共享。
- 不实现 `import.meta.resolve`。

## 8. 模块命名空间（阶段 6 已实现）

新增 `JSModuleNamespace`，作为专用 exotic 对象：

- 空原型、不可扩展。
- `[[Get]]` 实时解析导出绑定；未初始化时按 TDZ 抛 ReferenceError。
- `[[HasProperty]]` 只对导出名返回 true。
- `[[OwnPropertyKeys]]` 返回排序后的导出名；包含 `Symbol.toStringTag`。
- `[[GetOwnProperty]]` 返回 `{ value: 当前值, writable: true, enumerable: true, configurable: false }`，
  value 在读取时重新计算，不能缓存成快照。
- `[[Set]]` / `[[Delete]]` / 不兼容的 `[[DefineOwnProperty]]` 抛 TypeError 或返回 false。
- `Symbol.toStringTag` 为 `"Module"`。
- `export * as ns` 的 namespace 对象按同一规则创建。
- namespace 对象在声明实例化时创建并缓存；同一 `runModule` 图内同一模块复用。

## 9. 错误、预算与取消

### 9.1 错误模型

| 场景 | 错误 |
|---|---|
| 模块源码解析失败 | 现有 `ParseException`（带 sourceName） |
| provider 返回 null | `ModuleLinkException(NOT_FOUND)` |
| 导出缺失/歧义 | `ModuleLinkException(MISSING_EXPORT/AMBIGUOUS_EXPORT)` |
| 模块图超限 | `ModuleLinkException(TOO_MANY_MODULES/TOTAL_SOURCE_TOO_LARGE)` |
| TLA | `ModuleLinkException(UNSUPPORTED_TLA)` |
| provider 自身异常 | 原样透传 |
| 模块求值异常 | 原 JS 异常原样透传 |
| 动态 import 执行 | 现有 `SyntaxError("Dynamic import is not supported")` |

`ModuleLinkException` 建议字段：

```kotlin
enum class ModuleLinkErrorKind {
    NOT_FOUND,
    MISSING_EXPORT,
    AMBIGUOUS_EXPORT,
    INVALID_MODULE,
    TOO_MANY_MODULES,
    TOTAL_SOURCE_TOO_LARGE,
    UNSUPPORTED_TLA,
}

class ModuleLinkException(
    val kind: ModuleLinkErrorKind,
    val specifier: String? = null,
    val referrer: String? = null,
    val moduleId: String? = null,
    val exportName: String? = null,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

静态链接错误不被模块内 `try/catch` 捕获；错误发生在模块代码执行前。

### 9.2 预算

`EngineLimits` 新增：

```kotlin
val maxModules: Int? = 10_000,
val maxTotalModuleSourceLength: Int? = 10_000_000,
```

- `maxModules` 统计入口 + 所有唯一 record。
- `maxTotalModuleSourceLength` 累加 `ModuleSource.source.length`。
- 单模块仍受 `maxSourceLength` / `maxAstNodes` / `maxNestingDepth`。
- `maxSteps` 覆盖链接/求值中的解释器检查点；不覆盖 provider IO 时间。
- provider 的取消与 `runModule` 调用方取消联动；每次 load 前后检查 `ensureActive()`。
- 失败只丢弃当前模块图；Engine 继续可用，下一次调用使用新预算。
- 对象分配预算沿用现有 `EngineContext.objectAllocations`；namespace/环境对象在本次 run 内创建。

## 10. Host 边界

- 核心只定义 `ModuleProvider`，不出现 `Path`、`URL`、`Files`、网络 API。
- `InMemoryModuleProvider` 是首版参考实现，精确 id、不做相对路径规范化。
- `FileModuleProvider`、URL/fetch provider、Node fs provider 全部后置到 `:vibe-js-host`。
- 文件加载器必须做根目录约束、路径穿越、大小限制、编码/BOM、符号链接和取消处理。
- CLI 与 Test262 module harness 不属于首版。

## 11. 测试策略

### 11.1 Parser/AST

- `import {} from` / `export {} from` 进入 `requestedModules`。
- `parseModule(sourceInfo)` 错误栈带 sourceName。
- `ImportEntry` / `ExportEntry` range 正确。
- 类型化绑定覆盖 import/var/let/const/class/function/default。
- `hasTopLevelAwait` 正例/反例。
- 普通函数内 `await`、零参数 `import()` 被拒绝。
- 262 个 `.module.js` parser 语料保持通过；`parseProgram` 零回归。

### 11.2 模块图

- 正常图、菱形依赖、重复请求、循环依赖。
- 同一 specifier 从不同 referrer 请求。
- provider 返回 null、抛异常、返回重复 id。
- 解析失败、TLA、超限、取消。
- 加载阶段不执行任何模块体。

### 11.3 链接

- 本地导出、默认导出、命名 re-export、`export *`、`export * as ns`。
- 星号歧义、缺失导出、重复导出、循环 `ResolveExport`。
- namespace 导出名集合、`default` 排除。
- 链接错误字段（specifier/referrer/exportName/range）。

### 11.4 环境与命名空间

- import 只读；赋值 TypeError。
- live binding：目标模块更新后，导入方读取到新值。
- TDZ、`const`、函数提升、顶层 `var` 不污染 globalThis。
- namespace live getter、不可扩展、own keys 排序、写/删 TypeError。
- `import.meta.url` 与回退值、同模块对象复用。

### 11.5 求值

- 静态 import/export 端到端。
- `export default` 表达式、具名/匿名函数、类。
- re-export、`export *`、namespace import。
- 循环依赖、部分求值、TDZ、求值错误缓存。
- Engine 失败后复用、预算耗尽后复用。
- 跨 JVM/JS/Wasm/Native 的 commonTest。

## 12. 实施任务

估算仅供相对排序，不构成排期承诺。阶段 0–8 约 35–50 人日；后续能力另计。

当前进度：M0.1/M0.3、M1.1–M1.7、M2.1–M2.6、M3.1–M3.6、M4.1–M4.6、M5.1–M5.7、M6.1–M6.5、M7.1–M7.8 已完成；
阶段 8（错误定位、预算/取消/失败恢复、文档审查）待执行。M0.2 的完整图 fixture 仍未单独建。

### 阶段 0：契约与脚手架（1–2 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M0.1 | 冻结公共 API、错误模型、预算契约 | 本文评审通过；`ModuleRequest`/`ModuleSource`/`ModuleProvider` 签名不再反复 |
| M0.2 | 建立模块 fixture 清单 | 正常图、菱形、循环、星号歧义、缺失、TLA、超限各至少一条 |
| M0.3 | 明确不做的项 | 动态 import、TLA、attributes、CLI、harness 写入设计文档 |

### 阶段 1：Parser/AST 前置（4–6 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M1.1 | 修复 `requestedModules` 空子句 | `import {} from` / `export {} from` 被加载；单测 + 262 语料 |
| M1.2 | `parseModule(sourceInfo)` | `ModuleProgram.sourceInfo` 生效；错误栈带 sourceName |
| M1.3 | 给 Import/Export entry 加 range | 链接错误能定位声明 |
| M1.4 | 预计算类型化模块绑定 | `ModuleProgram.bindings` 覆盖 import/var/let/const/class/function/using/default |
| M1.5 | 预计算 `hasTopLevelAwait` | 正例/反例；链接期拒绝使用该标记 |
| M1.6 | 收紧普通函数内 `await` 与零参数 `import()` | 与 V8/Node 行为一致；把现有“宽松点合法”的断言改为 SyntaxError，保留覆盖，不删除用例；模块语料不回归 |
| M1.7 | 更新 AST/Parser KDoc | 移除过期的“字符串导入名未覆盖”等注释 |

验收：parser 1,907 脚本、262 模块语料、`parseProgram` 现有测试全绿。

### 阶段 2：公共 API 与 Provider（2–3 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M2.1 | 新建 `runtime.module` 包与实验性注解 | API 可从 Kotlin 宿主引用 |
| M2.2 | `ModuleRequest`/`ModuleSource`/`ModuleProvider`/`EmptyModuleProvider` | KDoc 说明 id/url/sourceName 契约 |
| M2.3 | `InMemoryModuleProvider` | 精确 id 测试；重复 id 语义 |
| M2.4 | `ModuleLinkException` 与错误 kind | 字段/消息测试 |
| M2.5 | `EngineLimits` 新增字段与校验 | 默认 10k / 10M；0/负数拒绝 |
| M2.6 | `Engine.runModule` 签名与入口校验 | **推迟到 M7**：当前不暴露会抛错的公共桩；链接/求值可用后再实现入口 |

### 阶段 3：模块图加载（3–4 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M3.1 | 内部 `ModuleRecord` / `ModuleGraph` / `ModuleStatus` | 状态初始化正确 |
| M3.2 | 入口解析与 provider 递归加载 | 按 requestedModules 顺序 DFS |
| M3.3 | 请求缓存与 id 去重 | 同请求只 load 一次；同 id 复用 record |
| M3.4 | 缺失/解析/Provider 异常处理 | null → ModuleLinkException；异常透传 |
| M3.5 | `maxModules` / 总源码预算 | 边界值、取消检查点 |
| M3.6 | 图加载测试 | 菱形、循环、重复、缺失、超限、取消 |

验收：加载阶段无任何模块体执行；错误发生在求值前。

### 阶段 4：链接与 `ResolveExport`（7–10 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M4.1 | 本地导出/间接导出解析 | `ResolveExport` 返回 `Resolved(module,bindingName)` |
| M4.2 | `export *` / `export * as ns` | default 排除、星号歧义、嵌套 namespace |
| M4.3 | `resolveSet` 与循环处理 | 循环不无限递归；重复解析稳定 |
| M4.4 | 缺失/歧义/重复导出错误 | 错误字段完整 |
| M4.5 | namespace 导出名集合 | 排序、去重、default 规则 |
| M4.6 | 链接测试矩阵 | 菱形、链式 re-export、星号歧义、循环、缺失 |

验收：只链接不求值；所有静态错误在求值前暴露。

### 阶段 5：模块环境与声明实例化（5–7 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M5.1 | 将 `DeclarativeEnvironment` 改为 `open` | 现有环境测试全绿 |
| M5.2 | 新建 `ModuleEnvironment` | import 名转发；本地绑定复用父类 |
| M5.3 | 类型化声明实例化 | var/function/let/const/class/import 正确落点 |
| M5.4 | import 只读与命名空间导入 | 写 import 抛 TypeError；TDZ 正确 |
| M5.5 | `*default*` 与默认导出绑定 | 匿名/具名 default 都正确 |
| M5.6 | 顶层 `this`/严格模式 | 模块帧 `this=undefined`；不污染全局 |
| M5.7 | 环境测试 | live binding、TDZ、顶层 var 隔离 |

### 阶段 6：模块命名空间（2–3 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M6.1 | `JSModuleNamespace` exotic 对象 | 空原型、不可扩展 |
| M6.2 | live get / descriptor / ownKeys | 读取实时绑定；keys 排序；描述符符合规范 |
| M6.3 | 写/删/重定义防护 | 正确抛 TypeError/返回 false |
| M6.4 | `export * as ns` 嵌套 namespace | 同一模块缓存；声明实例化时创建 |
| M6.5 | namespace 测试 | 属性描述符、`Object.keys`、`Symbol.toStringTag` |

### 阶段 7：同步求值与 `runModule`（6–9 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M7.1 | `Interpreter` 模块声明实例化/求值入口 | 复用 Completion/语句执行；不复制解释器 |
| M7.2 | DFS 后序求值与状态机 | 循环、重复求值、错误缓存 |
| M7.3 | 模块项执行与导出更新 | default/re-export/star/namespace 端到端 |
| M7.4 | `import.meta` 创建/缓存/url 回退 | 同模块复用；`module:<id>` |
| M7.5 | TLA 链接期拒绝、动态 import 运行期错误 | 错误信息明确；死代码不误报 |
| M7.6 | `runModule` 接入 eventLoop/预算/微任务 | 与 `run` 串行；返回入口 namespace |
| M7.7 | 失败后 Engine 复用 | 链接/求值失败不毒化 Engine |
| M7.8 | 跨 JVM/JS/Wasm/Native 测试 | commonTest 全绿 |

验收：静态模块 MVP 端到端可用；无 TLA/动态 import/attributes。

### 阶段 8：加固、文档与门禁（3–5 人日）

| ID | 任务 | 产出/验收 |
|---|---|---|
| M8.1 | 模块错误栈/sourceName/range | 解析、链接、求值错误可定位 |
| M8.2 | 预算/取消/失败恢复边界 | 超限、取消、Engine 复用测试 |
| M8.3 | parser 回归 | 脚本/模块语料 strict 全绿；`parseProgram` 零改动 |
| M8.4 | 文档 | README/COMPATIBILITY/CONFORMANCE/ARCHITECTURE 说明实验性模块 |
| M8.5 | 代码审查与清理 | 不引入平台 API；公共类型默认 internal |
| M8.6 | 性能 sanity | 不承诺优化；记录大图/深链的耗时 |

### 后续阶段（不在 MVP）

| ID | 能力 | 参考人日 | 前置 |
|---|---|---:|---|
| L1 | 动态 `import()`：Promise、图复用、错误传播 | 3–5 | M7 |
| L2 | Top-Level Await：异步求值状态机 | 8–15 | L1/M7 |
| L3 | import attributes / JSON modules | 3–5 | M1/M4 |
| L4 | 文件/URL/网络 Provider（仅 host） | 3–5 | M2/M3 |
| L5 | Test262 module harness + 全量 module 门禁 | 5–9 | L1/L2 |
| L6 | CLI 与退出码 | 2–4 | L4/L5 |

### 任务依赖

```text
M0 -> M1 -> M3 -> M4 -> M5 -> M7 -> M8
M0 -> M2 -> M3
M4 -> M6 -> M7
M7 -> L1/L2/L3/L4/L5/L6
```

- M1 与 M2 可以并行。
- M3 依赖 M1（静态表）与 M2（public provider 类型）。
- M4 依赖 M1（range/静态表）与 M3（模块图）。
- M5 与 M6 可以在 M4 后并行；M7 需要两者完成。
- M8 是 MVP 收口；L 系列只在 MVP 稳定后启动。

## 13. MVP 验收清单

- [ ] `Engine.runModule` 两个重载可用，返回入口 namespace。
- [ ] 静态 import/export/default/re-export/`export *`/`export * as ns` 端到端。
- [ ] 循环依赖、live binding、TDZ、顶层 `this=undefined` 正确。
- [ ] namespace 对象为专用 exotic 对象，属性实时读取。
- [ ] `import.meta.url` 使用 provider URL 或 `module:<id>`。
- [ ] TLA 在链接期明确拒绝；动态 import 执行时报 SyntaxError。
- [ ] 每次 `runModule` 独立图；失败后 Engine 可复用。
- [ ] `maxModules` / 总源码长度、单模块预算、取消生效。
- [ ] JVM/JS/Wasm/Native 测试通过；现有 parser/脚本门禁不回归。
- [ ] 文档明确“受控嵌入式 MVP，不是通用 ESM 运行时”。

## 14. 风险与开放问题

1. **`ResolveExport` + namespace + 循环**是最高风险区，应分配最多测试时间。
2. **per-run 模块图**与 Node/浏览器单例语义不同；宿主若期望模块只求值一次，需要自行缓存或后续增加 Engine 级模块表。
3. **Provider 解析负担**：相对路径、package exports、URL、import maps 都不在核心；必须提供参考实现和明确契约。
4. **源码范围**：M1.3 后链接错误可定位；若省略，诊断质量会明显低于主流引擎。
5. **TLA/dynamic import/attributes 缺失**：不能运行大量现代 ESM 包；这是首版范围，不是实现缺陷。
6. **`import.meta` 回退 `module:<id>` 非标准**：宿主应尽量提供 URL。
7. **顺序加载**可能慢于 Node/浏览器的并行加载；MVP 优先确定性，后续可评估有界并发。
8. **`parseModule` 语法宽松点**：普通函数内 `await`、零参数 `import()` 必须在执行前收紧。
9. **`requestedModules` 空子句**是当前 parser 的真实缺口，必须优先修。
10. **模块图无全局缓存**：同一 `Engine` 连续 `runModule` 会重新求值；副作用重复是预期行为，需要文档化。

## 15. 与路线图的关系

- 阶段 0–7 的 Parser、Provider API、内部图加载、静态链接、模块环境/声明实例化、namespace 与求值已按本文落地；阶段 8 的加固与审查仍待执行。
- `docs/OPTIMIZATION_ROADMAP.md` 把模块 Parser 前置标为实验性实现；模块执行、加载器、CLI 和 harness 仍无计划。
- 只有需求方明确继续重启时，才把阶段 2+ 转为实际排期。
- 首版完成后仍不应宣称“完整 ESM 运行时”；动态 import、TLA、import attributes、宿主文件加载器和 Test262 module harness 是后续能力。
