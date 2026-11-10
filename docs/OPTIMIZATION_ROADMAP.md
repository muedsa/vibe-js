# 后续路线（未完成工作）

> 更新：2026-11-10。代码基线：见最近提交。
>
> 本文只列**尚未完成**的工作。已实现能力只作为后续工作的依赖与不可破坏契约出现，不逐项重复。
>
> **2026-11-10 调整：模块阶段 0–8 已作为实验性实现完成**（Parser 静态表、Provider API、内部图加载、静态链接、ModuleEnvironment、namespace、`import.meta`、`Engine.runModule`、同步求值，以及错误定位/预算/取消/失败恢复加固）。API 需要 `@ExperimentalModuleApi` opt-in，不承诺正式版兼容性。模块文件/URL 加载器、Test262 module harness、TLA 执行与动态 `import()` 执行仍无计划；核心继续不引入平台文件/URL IO。

## 1. 当前基线

### 1.1 验证数字

| 检查 | 当前值 | 说明 |
|---|---|---|
| core JVM 单元测试 | **1065 / 1065** | 2026-11-10 本地运行 |
| core JS / Wasm（Node） | 各 **1049 / 1049** | 2026-11-10 复跑 |
| host JVM / JS / Wasm / Native | 各 **38 / 38** | 2026-11-09 新增 TextEncoder/TextDecoder、structuredClone、URL 测试；Windows Native 已复跑 |
| conformance 单元测试 | **39 / 39** | ParserConformanceTest 6 + RunSupportTest 10 + Test262SelectionTest 19 + RunMetadataTest 4 |
| parser 脚本 | **1,907 / 1,907**；fail 674 / 682（允许 8）；early 5 / 605（允许 5） | pass / pass-explicit 各 1,907，允许 0 |
| parser 模块 | pass-module **76 / 76**、pass-explicit-module **76 / 76**、fail-module **47 / 47**、early-module **63 / 63**（拒绝） | 262 个 `.module.js`，允许列表 0 条 |
| Test262 ES5 | **15,248 / 15,248**，失败 0，跳过 0 | 2026-11-10 复跑 |
| Test262 ES2015 | **61,786 / 61,786**，失败 0，崩溃 0 | 选中 32,293；跳过 **324**（module flag 322 + `$262`/`print` 2） |
| Node 差分 | **591 / 591** | 含 L 系列同步与异步用例 |
| 真实库 UMD 冒烟 | **375 / 375**（102 个库） | 2026-11-10 新增 base64-js/jmespath/jssha、d3-array/d3-hierarchy/earcut、d3-dsv/cbor-js/humanize-duration、msgpack-lite/d3-delaunay/topojson-client |

> 数字口径以 [CONFORMANCE.md](CONFORMANCE.md) 为准。ES5、ES2015、parser、真实库与差分已在当前基线复跑。

### 1.2 已实现能力摘要

- **语言**：ES5 + ES2015 常用语法，以及 `using` / `await using`、严格模式尾调用优化。
- **生成器与异步迭代**：同步/异步 Generator、`yield` / `yield*`、`for await...of`。
- **元编程**：Proxy 13 个 trap、`Proxy.revocable`、不变量校验；Reflect 13 个方法。
- **二进制与数值**：BigInt、11 种 TypedArray、ArrayBuffer、DataView、detach 语义。
- **集合与内建**：Iterator helpers、DisposableStack / AsyncDisposableStack、Promise 组合、`RegExp.escape`、ES2025 Set 方法等。
- **宿主生态**：`:vibe-js-host` 提供 TextEncoder/TextDecoder（UTF-8、fatal/ignoreBOM/stream）、structuredClone（对象/数组/Map/Set/Date/RegExp/二进制/循环）、URL/URLSearchParams 常用子集。
- **模块（实验性）**：`Parser.parseModule()` 可解析 import/export 基础形式、`import.meta`、`import()`、模块顶层 `await` 语法，并预计算静态表；`Engine.runModule` 已接通 Provider、加载/链接、环境、namespace、`import.meta` 与同步求值，但 API 需 opt-in，TLA/动态 import 执行、宿主加载器、module harness 仍无计划。
- **资源约束**：`EngineLimits` 已覆盖源码长度、嵌套深度、AST 节点、对象数量、JSON 深度、正则步数、排队任务、单轮微任务等预算；`JSON.stringify` 与 `JSON.parse` 共用 `maxJsonDepth`，显式降低 `maxRegexSteps` 时嵌套量词正则路由到受预算约束的回退引擎。

### 1.3 当前边界

当前有两个执行入口：`Engine.run(source)`（单脚本文本）与实验性 `Engine.runModule(...)`（宿主提供 Provider，核心不引入平台 IO）。模块已具备同步链接/求值、live binding、namespace 与 `import.meta`，但 TLA、动态 `import()` 执行、宿主文件/URL 加载器、module harness 仍无计划；宿主生态已提供 UTF-8 编解码、structuredClone 与 URL 子集；WeakRef / FinalizationRegistry、Intl、真实 DOM/Web API、SharedArrayBuffer/Atomics、resizable/transfer ArrayBuffer 不在范围。

## 2. 排序原则

模块阶段 0–8 已作为**实验性实现**落地；同一 Engine 可执行 Script 或同步模块图，但模块 API 不承诺正式兼容性，加载器/TLA/动态 import 仍无计划。

| 队列 | 范围 | 说明 |
|---|---|---|
| **维护主线** | `DOC-2`（收口）→ `S6.2` → `S2.2` | 保持文档/门禁绿色，持续补运行时边界与真实库覆盖 |
| **观测** | `S5.2` | 只做性能复测，B2 仍无计划 |
| **实验性实现** | 模块阶段 0–8（已完成） | Parser/Provider/图加载/链接/环境/namespace/import.meta/runModule + 错误定位/预算/取消/恢复加固；opt-in；无加载器/harness/TLA 执行 |
| **无计划** | 模块加载器、TLA 执行、动态 import、module harness、`M2`、`B2`、`PKG1`、范围外能力 | 不估算、不排期、不设启动条件 |

### 2.1 为什么模块加载器仍不设默认主线

阶段 0–8 已补齐核心内的同步模块执行；以下边界仍然成立：

1. **核心只接受宿主源码/Provider**：`Engine.runModule` 由宿主提供 `ModuleSource`/`ModuleProvider`；核心不读取文件、URL 或网络。
2. **模块加载器涉及平台差异**：文件系统、URL、包解析在 JVM/Android/Native/Node 与浏览器/Wasm 上差异很大，放进核心会破坏“核心无平台 API”的约束。
3. **平台矩阵不适合核心做 IO**：JVM/Android/Native/Node 可以读文件，浏览器/Wasm 不能；需要加载器的宿主应自行实现 Provider。
4. **执行能力仍有边界**：TLA、动态 `import()` 执行、模块 Test262 module harness 需要异步执行状态机/宿主加载策略，当前没有排期。
5. **实验性 API 不承诺兼容**：`Engine.runModule` 与 Provider 类型需要 `@ExperimentalModuleApi` opt-in；签名、行为与错误分类仍可能调整。

## 3. 推进列表

> `DOC-1`、`MOD-1`、`MOD-2`、首轮 `S6.2` 与 `S2.2` 四批已经完成，记录见 §12；下表只保留仍需持续执行的项。

| 排位 | 编号 | 任务 | 优先级 | 人日 | 触发条件 | 验收摘要 |
|---:|---|---|---|---:|---|---|
| 0 | `S6.2` | 门禁维护 | P0（维护） | 1–2 / 批 | 核心、语料、依赖变更后 | parser/Test262/真实库/差分 strict 全绿；allowlist 可审计 |
| 1 | `S2.2-B5` | 运行时边界覆盖 | P1 | 2–4 | 下一批覆盖扩展 | 新增 5–10 条无 DOM 边界用例，或 3–5 个有新增特性的 UMD 库；strict 全绿 |
| 2 | `S5.2` | 性能复测（只观测） | P2（观测） | 1–2 / 次 | 重大运行时改动、性能反馈 | 重跑八组基准；报告落盘；不做优化承诺 |

> `S5.2` 不产生 B2 排期；`B2` 仍为无计划。

## 4. 里程碑

### M1：模块稳定化 + 基线收口（已完成）

`DOC-1 + MOD-1 + MOD-2 + S6.2`

- 已更新路线图、README、CONFORMANCE 中的模块口径；
- 已固定 `ModuleProvider` 契约并补宿主实现示例；
- 已补模块执行回归矩阵并跨 JVM/JS/Wasm/Native 验证；
- 已保持 parser/Test262/真实库/差分 strict 全绿；
- 模块保持 experimental，不承诺正式版兼容性；不新增 CLI/平台加载器/TLA 执行承诺。

### M2：覆盖扩展（持续推进）

`S2.2`

- 每批新增 3–5 个无 DOM 依赖 UMD 库，或 5–10 条运行时边界用例；
- 优先补 Generator / BigInt / TypedArray / Proxy / async 边界；
- 不通过删除用例或放宽断言换绿。

### M3：性能观测（首轮已完成）

`S5.2`

- 只做测量与报告；
- B2 不恢复，避免把优化重新拉回排期。

## 5. 任务示例（当前维护/观测项）

### 5.1 S6.2 门禁维护

```text
# allowlist 逐条路径 + 原因，不允许目录/后缀/通配符
test/language/module-code/import-attributes/import-attribute-empty.js<TAB>暂不支持 import attributes
```

```powershell
.\gradlew :vibe-js-conformance:parserConformance -Pstrict --console=plain
.\gradlew :vibe-js-conformance:test262Conformance -Pstrict --console=plain
.\gradlew :vibe-js-conformance:test262Es2015Conformance -Pstrict --console=plain
.\gradlew :vibe-js-conformance:differentialConformance -Pstrict --console=plain
.\gradlew :vibe-js-conformance:librarySmoke -Pstrict --console=plain
```

### 5.2 S2.2 差分与真实库覆盖

```js
// 同一表达式在 Node 与 Vibe 分别执行并比较序列化结果
JSON.stringify([1, , 3].includes(undefined)); // true
JSON.stringify(new Uint8Array([1, 2, 3]));     // {"0":1,"1":2,"2":3}
```

```js
// lodash UMD 加载后调用 API
const _ = globalThis._;
console.log(JSON.stringify(_.chunk([1, 2, 3, 4], 2))); // [[1,2],[3,4]]
```

### 5.3 S5.2 性能复测

```js
// 属性读取
const object = { x: 1 };
let sum = 0;
for (let i = 0; i < 1_000_000; i += 1) {
    sum += object.x;
}
```

```js
// 数组循环
const values = new Array(1_000_000).fill(1);
let total = 0;
for (let i = 0; i < values.length; i += 1) {
    total += values[i];
}
```

```kotlin
// 引擎创建
repeat(100) {
    Engine()
}
```

产物：`benchmarkEnvironment` + `jvmBenchmarkBenchmark` 报告，标注 JDK/OS/CPU/内存/GC 与 commit，只做纵向对比；模块路径用 `-PbenchmarkFilter=ModuleBenchmark` 单独观测。

## 6. 无计划：模块后续能力（加载器/TLA）

### 6.1 任务状态

| 编号 | 工作 | 处理 |
|---|---|---|
| `L4.1c` | 模块语法宽松点收敛 | **阶段 0–1 已完成**：静态表、类型化声明、SourceInfo/range、TLA 标记、await/import() 收紧 |
| `L4.2` | 模块链接（Module Record / ResolveExport） | **阶段 4 已完成（实验性）** |
| `L4.3` | 模块求值（live binding / 命名空间） | **阶段 7 已完成（实验性、同步子集）** |
| `L4.4` | 模块加载器抽象与宿主文件加载器 | **无计划**：Provider 抽象已有，文件/URL 加载器不在核心 |
| `L4.6a+` | Test262 `module-code` parse 门禁 | **无计划** |
| `L4.6b` | Test262 module harness | **无计划** |
| `L4.7` | 顶层 await 执行 | **无计划**；链接期拒绝 |

保留现状：

- `Parser.parseModule()`、262 个模块语料门禁与 `ParserModuleTest` 继续保留；
- 实验性 `Engine.runModule` / Provider / namespace / `import.meta` 保留，需 opt-in；
- `ImportCallExpr` 执行期仍按未支持报错；
- 不新增模块加载器、module harness 或 TLA 执行。

> 模块阶段 0–8 的 API 与实施细节见 §6.2；后续只保留加载器/TLA 的设计参考。

### 6.2 模块实验性实现的参考方案

> 阶段 0–8 已实现同步模块图执行；本节保留加载器/TLA 的后续设计参考与已实现语义摘要。类名、包名和签名仍可能调整。原则仍然是：**核心只定义纯 Kotlin 抽象并负责链接/求值；文件、URL、网络等 IO 全部由宿主提供。**
>
> 完整的设计决策、API 契约、实施顺序与详细任务见 [MODULE_DESIGN.md](MODULE_DESIGN.md)。
>
> 阶段 0–8 的 Parser、Provider API、内部图加载、静态链接、模块环境、namespace、`import.meta` 与同步求值（静态表、类型化声明、SourceInfo/range、TLA 标记、语法收紧、
> `ModuleProvider`/`InMemoryModuleProvider`/模块图预算/递归加载/ResolveExport/ModuleEnvironment/JSModuleNamespace/runModule）已落地；
> 以下宿主文件/URL 加载器、TLA 执行与动态 import 仍是未实现的设计储备。

#### 6.2.1 核心 API：ModuleRequest / ModuleSource / ModuleProvider

```kotlin
package com.muedsa.js.runtime.module

/**
 * 一次模块请求。
 *
 * specifier 是源码中写的原始说明符（例如 "./lib/greet.mjs"）；
 * 核心不解释路径、URL 或平台语义，只把它原样交给 ModuleProvider。
 */
data class ModuleRequest(
    val specifier: String,
    val referrer: String? = null,
)

/**
 * 宿主解析后的模块源码。
 *
 * id 必须是宿主侧稳定且唯一的模块标识；同一个 id 表示同一个模块。
 * sourceName 用于错误栈；url 用于 import.meta.url，可为空。
 */
data class ModuleSource(
    val id: String,
    val source: String,
    val sourceName: String = id,
    val url: String? = null,
)

/**
 * 宿主源码提供者。返回 null 表示“找不到”，由核心转换为链接错误。
 * load 必须是 suspend、可协作取消的；核心不假定它读取的是文件还是网络。
 */
fun interface ModuleProvider {
    suspend fun load(request: ModuleRequest): ModuleSource?
}

/** 单模块入口（无 import）可用的空提供者。 */
object EmptyModuleProvider : ModuleProvider {
    override suspend fun load(request: ModuleRequest): ModuleSource? = null
}
```

目标入口（当前不存在，仅作设计参考）：

```kotlin
// 入口已经是源码：不需要 provider，除非源码中包含 import
suspend fun Engine.runModule(
    entry: ModuleSource,
    provider: ModuleProvider = EmptyModuleProvider,
): JSValue

// 入口是宿主标识：由 provider 负责解析并加载
suspend fun Engine.runModule(
    entrySpecifier: String,
    provider: ModuleProvider,
): JSValue
```

约束：

- `ModuleSource.id` 由宿主负责规范化；核心不拼接 `./`、`../`、URL 或文件路径。
- `ModuleSource.source` 是已经解码的 UTF-8 文本；BOM 由宿主剥离。
- `sourceName` 只影响错误栈；`url` 只影响 `import.meta.url`，两者都不参与模块身份判定。
- `ModuleProvider` 是 Kotlin 层接口，不是 `BuiltinModule`，不会注册到 JS 全局对象。

#### 6.2.2 内存提供者（推荐的默认实现）

```kotlin
/**
 * 纯内存模块表，适合浏览器/Wasm、测试和宿主自带打包器的场景。
 * 注意：相对说明符的规范化由宿主完成；这里用精确 id 演示最小实现。
 */
class InMemoryModuleProvider(
    private val modules: Map<String, String>,
) : ModuleProvider {
    override suspend fun load(request: ModuleRequest): ModuleSource? {
        val id = request.specifier
        val source = modules[id] ?: return null
        return ModuleSource(id = id, source = source)
    }
}
```

```kotlin
val provider = InMemoryModuleProvider(
    mapOf(
        "./main.mjs" to """
            import { greet } from "./lib/greet.mjs";
            console.log(greet("vibe"));
        """.trimIndent(),
        "./lib/greet.mjs" to """
            export function greet(name) {
                return `hi ${name}`;
            }
        """.trimIndent(),
    ),
)

engine.runModule("./main.mjs", provider)
// 目标输出：hi vibe
```

浏览器/Wasm 没有文件系统，这一类内存表就是首选；`fetch` 加载器也只能由宿主提供，核心不感知网络。

#### 6.2.3 核心内部模型（建议）

```kotlin
internal enum class ModuleStatus {
    NEW, LINKING, LINKED, EVALUATING, EVALUATED, ERRORED
}

internal class ModuleRecord(
    val id: String,
    val sourceName: String,
    val program: ModuleProgram,
    var status: ModuleStatus = ModuleStatus.NEW,
    var environment: ModuleEnvironment? = null,
    var namespace: JSModuleNamespace? = null,
    var evaluationError: JSException? = null,
)

internal sealed interface ResolveExportResult {
    data class Resolved(
        val module: ModuleRecord,
        val bindingName: String,
    ) : ResolveExportResult

    data object Ambiguous : ResolveExportResult
    data object NotFound : ResolveExportResult
}
```

- `ModuleEnvironment`：模块顶层声明、导入绑定（只读 live binding）、导出绑定（可更新）与 `*default*`。
- `JSModuleNamespace`：建议新增的命名空间 exotic 对象；null 原型、不可扩展、属性为 live getter、`Symbol.toStringTag` 为 `"Module"`。
- `ModuleRecord` 只属于一次 `runModule` 的模块图，不做全局缓存；provider 可以跨多次调用复用。

#### 6.2.4 链接流程（L4.2）

1. 解析入口 `ModuleSource.source`：`Lexer(moduleContext = true)` + `Parser.parseModule()`；失败直接抛现有 `ParseException`。
2. 从入口开始按 `requestedModules` 递归调用 `provider.load(ModuleRequest(specifier, referrer = current.id))`；按 `id` 去重，形成模块图。
3. `null` 视为找不到模块；链接阶段报错，不进入求值。
4. 对每个 `ImportEntry` / `ExportEntry` 建绑定：
   - 本地导出：`localName` 指向模块环境绑定；
   - `export ... from`：间接导出，记录目标模块与导入名；
   - `export *` / `export * as ns`：按规范展开，处理重名歧义与 `default` 排除规则。
5. 实现 `ResolveExport`：
   - `Resolved(module, bindingName)`；
   - `Ambiguous`：同一导出名来自多个星号导出且无法唯一确定；
   - `NotFound`：显式导入/导出未找到。
6. 为每个模块准备命名空间元数据，但不创建 JS 对象、不执行模块体。
7. 链接阶段错误统一在求值前抛出；脚本 `parseProgram` 路径零改动。

#### 6.2.5 求值流程（L4.3）

1. 从入口做 DFS 后序求值；用 `ModuleStatus` 检测循环与重复求值。
2. 建立模块环境：
   - 顶层 `this` 为 `undefined`；
   - 恒严格模式；
   - 导入绑定只读，读取时实时转发到目标模块绑定；
   - 导出绑定在声明初始化后更新，`export default` 使用 `*default*`。
3. 创建或复用命名空间对象；属性读取走 live binding，写入按规范抛 TypeError。
4. `import.meta` 使用 `ModuleSource.url` 构造元数据对象；没有 `url` 时由核心给出稳定的回退值。
5. 求值失败时记录 `evaluationError`，同一模块图内后续引用直接重抛；循环依赖按规范处理 TDZ 与部分求值状态。
6. 动态 `import()`（若未来也要支持）复用同一 `ModuleProvider`，返回 Promise；本轮不设计 TLA 执行。

#### 6.2.6 宿主加载器（L4.4）

```kotlin
// 仅放在 :vibe-js-host 的 JVM/Android 源集；核心不出现 Path/Files
class FileModuleProvider(
    private val root: Path,
) : ModuleProvider {
    override suspend fun load(request: ModuleRequest): ModuleSource? {
        val target = root.resolve(request.specifier).normalize()
        require(target.startsWith(root)) { "module path escapes root" }
        if (!Files.exists(target)) return null
        return ModuleSource(
            id = target.toString(),
            source = Files.readString(target),
            sourceName = target.fileName.toString(),
            url = target.toUri().toString(),
        )
    }
}
```

平台分工：

- **JVM / Android**：可用 `java.nio.file`；阻塞 IO 建议放在宿主自己的 `Dispatchers.IO` 上下文。
- **Native**：使用平台文件 API 或 `kotlinx-io`；放宿主目标源集，核心不感知。
- **Node（js/wasmJs）**：可提供 `fs` 适配；浏览器目标不能使用。
- **浏览器 / Wasm**：只能使用内存 `ModuleProvider`、打包后的模块表或宿主注入的 `fetch`。
- **安全**：文件加载器必须做根目录约束、路径穿越检查、大小限制与编码校验；不得把任意路径直接交给核心。

#### 6.2.7 预算、错误与可观测性

建议新增的 `EngineLimits` 字段（当前不存在）：

```kotlin
data class EngineLimits(
    // ...现有字段...
    val maxModules: Int? = 10_000,
    val maxTotalModuleSourceLength: Int? = 10_000_000,
)
```

- 单模块源码仍受 `maxSourceLength` / `maxAstNodes` 约束；整个模块图受 `maxModules` 与总源码长度约束。
- `maxSteps` 覆盖链接/求值中的解释器检查点，不覆盖 provider 的 IO 时间。
- provider 的取消必须与 `Engine.runModule` 的调用方取消联动；核心在每次 `load` 前后检查取消。
- 建议错误映射：

| 场景 | 建议错误 |
|---|---|
| provider 返回 `null` | `ModuleNotFoundError`（JS `Error`，消息含说明符与 referrer） |
| 模块源码解析失败 | 现有 `ParseException`（不转换为 JS 错误） |
| 导出名重复/歧义 | `SyntaxError` |
| 导入绑定不存在 | `SyntaxError` |
| 求值期异常 | 原 JS 异常，保持栈与模块名 |
| provider IO 异常 | 宿主异常或包装为 JS `Error`；不得静默吞掉 |

#### 6.2.8 测试与实施顺序

如果未来重启，建议按以下顺序落地（估算仅供参考，不构成排期）：

> 以下是早期粗粒度参考；实际实施以 [MODULE_DESIGN.md](MODULE_DESIGN.md) 的阶段 0–8 为准。
> Test262 module harness 在详细设计中被后置，不属于静态模块 MVP。

| 阶段 | 内容 | 参考人日 |
|---|---|---:|
| 0 | 冻结 `ModuleRequest` / `ModuleSource` / `ModuleProvider` API；实现 `InMemoryModuleProvider` | 1–2 |
| 1 | 模块图、`ResolveExport`、循环依赖、歧义导出（只链接不求值） | 8–14 |
| 2 | 模块环境、live binding、命名空间、`export default`、`import.meta` | 6–12 |
| 3 | 宿主文件/URL 加载器与缓存策略 | 3–5 |
| 4 | Test262 module harness、module-code parse 门禁、语法收尾 | 5–9 |

测试重点：

- `InMemoryModuleProvider` 覆盖：正常图、循环依赖、重复请求、星号导出歧义、重命名再导出、缺失模块、解析失败；
- 求值覆盖：live binding、命名空间不可扩展、`export default`、模块顶层 `this`、TDZ、求值失败后的状态；
- 宿主加载器：临时目录、路径穿越、UTF-8/BOM、缺失文件、IO 错误、取消；
- 最后才接入 Test262 module 用例与 module-code parse 门禁，避免在执行语义未稳定时被大量语料淹没。

#### 6.2.9 开放问题

1. `specifier` 的规范化（`.`、`..`、URL、平台分隔符）由 provider 全权负责，核心只按 `id` 去重；是否需要在 `ModuleRequest` 中暴露更多上下文？
2. `ModuleSource` 是否应直接携带 `ByteArray` 而不是 `String`？当前建议由宿主解码为 UTF-8 文本，核心不处理编码。
3. import attributes / JSON modules / `import.meta.resolve` 是否进入 `ModuleRequest` 扩展？当前随模块方向无计划。
4. 动态 `import()` 的 provider 生命周期：是否允许在模块求值期间再次调用 `load`？建议允许，但必须受 `maxModules` 与取消约束。
5. 模块图缓存：建议每次 `runModule` 使用独立图缓存；provider 可跨调用复用，但核心不保留全局模块图。
6. 路径安全：文件加载器的根目录约束、符号链接、大小写敏感与 Windows 路径必须由宿主测试覆盖。
7. 顶层 await 执行与同步求值的关系：如果未来支持 TLA，需要把求值状态机改成异步；当前明确无计划。

平台分工的底线不变：**核心不读文件、不解析路径、不访问网络；`Engine.run(source)` 仍是单脚本文本入口。**

## 7. 无计划：长期与范围外

以下项目不估算、不排期、不设启动条件；只有需求方明确重新立项时才恢复评估。

| 项目 | 处理 | 说明 |
|---|---|---|
| `M2` 真实弱引用（WeakMap/WeakSet GC 语义） | **无计划** | 当前仍是强引用存储；JVM MVP 参考 12–18 人日 |
| `B2` 性能优化 | **无计划** | 保留 `S5.2` 复测作为观测，不承诺优化；参考 25–48 人日 |
| `PKG1.2–1.6` API 契约与发布准备 | **无计划** | explicitApi、Dokka、BCV、CHANGELOG、host Android 目标、发布冒烟均不排期；参考 7–13 人日 |
| Intl | **无计划 / 范围外** | `toLocaleString` 保持简化行为 |
| 真实 DOM / Web API | **无计划 / 范围外** | fetch、localStorage、document 等由宿主自行提供 |
| SharedArrayBuffer / Atomics | **无计划 / 范围外** | 单线程模型不引入共享内存 |
| resizable / transfer ArrayBuffer | **无计划 / 范围外** | 只支持固定长度与宿主 detach |
| Temporal / Float16Array / 装饰器 / ShadowRealm | **无计划 / 范围外** | 不在当前语言范围 |
| import attributes / `import.meta.resolve` / `export using` | **无计划** | 随模块方向一起无计划 |
| WeakRef / FinalizationRegistry | **无计划** | 并入 M2 弱引用方向 |

## 8. 验证命令

在仓库根目录用 PowerShell 执行。新增行为优先写 commonTest；测试方法名使用中文字符串与 kotlin-test 断言。
**不要通过删除既有用例、扩大无理由 allowlist 或降低默认调用深度来换取通过。**

```powershell
# 核心与宿主常规验证
.\gradlew :vibe-js-core:jvmTest :vibe-js-host:jvmTest :vibe-js-conformance:test --continue --console=plain

# 其他目标（涉及值模型/容器/调度改动时）
.\gradlew :vibe-js-core:jsNodeTest :vibe-js-core:wasmJsNodeTest :vibe-js-host:jsNodeTest :vibe-js-host:wasmJsNodeTest --continue --console=plain

# parser 门禁（脚本 + 模块语料都在同一任务内；模块语料是现状保护，不是执行承诺）
.\gradlew :vibe-js-conformance:parserConformance -Pstrict --console=plain

# Test262 ES5 / ES2015 门禁
.\gradlew :vibe-js-conformance:test262Conformance :vibe-js-conformance:test262Es2015Conformance -Pstrict --continue --console=plain

# 定向验证（示例）
.\gradlew :vibe-js-conformance:test262Es2015Conformance -Pstrict -Ptest262Filter=Proxy --console=plain
.\gradlew :vibe-js-conformance:test262Es2015Conformance -Pstrict -Ptest262Filter=generators --console=plain

# 真实库与 Node 差分
.\gradlew :vibe-js-conformance:librarySmoke :vibe-js-conformance:differentialConformance -Pstrict --continue --console=plain

# 基准（性能复测时使用）
.\gradlew :vibe-js-core:benchmarkEnvironment :vibe-js-core:jvmBenchmarkBenchmark
.\gradlew :vibe-js-core:jvmBenchmarkBenchmark -PbenchmarkFilter=ObjectEnvironmentBenchmark
```

## 9. 不可破坏契约

| 领域 | 约束 |
|---|---|
| 执行入口 | `Engine.run(source)` 为脚本入口；实验性 `Engine.runModule(...)` 为同步模块入口，需 opt-in；两者共享同一 Engine 但模块图不跨调用缓存 |
| 核心平台边界 | 核心 `commonMain` 不引用平台 IO/文件/网络 API；文件/URL 加载只能由宿主 Provider 提供；浏览器/Wasm 不得假设文件系统 |
| 模块解析与执行现状 | `Parser.parseModule()`、262 个模块语料门禁、Provider/链接/环境/namespace/import.meta/runModule 保留；不新增核心加载器/harness/TLA 执行 |
| 模块后续能力 | 文件/URL 加载器、`L4.6a+`/`L4.6b` module harness、`L4.7` TLA 执行不进入排期；文档不得写成“下一版本承诺” |
| 资源约束 | `EngineLimits` 的默认值不得回退；`ResourceLimitException` 不可被 JS `catch`/`finally` 吞掉；超限行为与分类可预期 |
| 协作式取消与超时 | 解释器检查点观察取消；宿主取消原样传播；JS `catch`/`finally` 不吞宿主中止；剩余微任务丢弃 |
| 执行配额 | 步数超限抛宿主 `ExecutionLimitException`；深度超限抛 JS `RangeError`；async 继承创建时的逻辑深度 |
| 错误栈 | 创建/首次抛出时快照；重抛与 Promise 转发不覆盖原栈；保留万层同步/async 回归 |
| 事件循环生命周期 | `close()`、`pendingTasks`、`runUntilIdle()`、统一恢复队列；同一引擎串行执行；关闭等待清理且不取消宿主父 Job |
| 集合迭代 | 实时语义：遍历中新增可见、删除跳过、重删重加再次访问、`done` 后不复活；`size` O(1) |
| 宿主计时 | `Date` 用墙钟；`performance.now()` / `console.time` 用单调时钟；`timeOrigin` 在模块安装时固定 |
| 数值转换 | `ToNumeric` 对对象必须使用 number 提示；`ToNumber(BigInt)` 抛 TypeError；BigInt 与 Number 混用抛 TypeError |
| Generator / AsyncGenerator | 参数绑定、默认参数、this/arguments 在调用生成器函数时完成；首次 `next` 前不执行函数体；async generator 请求 FIFO 串行；取消与配额异常不转换为 JS 异常 |
| Proxy | 13 个内部方法/trap 与不变量必须走 `Interpreter` 分派；撤销后全部 TypeError；内建不得绕过内部方法 |
| 二进制与 BigInt | 11 种 TypedArray 的整数索引 exotic 语义不变；固定小端序；detach 后长度/读写/键集合按规范；BigInt 受 `MAX_RESULT_BITS` 限制 |
| Test262 口径 | 已实现特性不得用宽泛文本/路径排除；过滤变更必须可审计、可回滚；对外数字必须带子集/跳过口径 |
| 文档口径 | 未实现能力必须标注“未实现”；无计划能力必须标注“无计划”；数字必须与 §1.1 和 [CONFORMANCE.md](CONFORMANCE.md) 一致 |
| 发布 | 当前仍为 `0.0.0-SNAPSHOT`；PKG1 与正式版发布准备无计划 |

## 10. 风险与约束

1. **模块执行仍有限**：阶段 0–8 已实现同步模块图的链接/环境/namespace/`import.meta`/求值，但 TLA、动态 `import()` 执行、宿主文件/URL 加载器、module harness 仍未实现；实验性 API 不承诺兼容性。
2. **KMP 与文件 IO 不匹配**：核心不能假设文件系统；JVM/Android/Native/Node 与浏览器/Wasm 的 IO 能力差异大，未来若重启模块方向，只能采用“核心抽象 + 宿主加载器”或纯内存模块表。
3. **`Engine.run()` 的边界**：多次 `run` 共享全局环境，但不是模块；全局 `let`/`const`/`class` 重复声明仍可能冲突，宿主需要自行管理脚本边界。
4. **维护主线依赖语料稳定**：`S6.2`/`S2.2` 受 Test262/真实库下载与版本影响，报告必须带 commit 与跳过口径。
5. **R1 不是进程级隔离**：源码/AST/对象/微任务预算覆盖了解释器内主要路径，但不限制宿主阻塞调用、单次内建内部工作与全部内存；Kotlin Regex 快路径也不消耗 `maxRegexSteps`。
6. **L4.5 宿主子集边界**：TextEncoder/TextDecoder 只支持 UTF-8；structuredClone 不支持函数、Symbol、Proxy、Promise、Generator、WeakMap/WeakSet 与 transfer；URL 不实现 IDNA 与完整 WHATWG 状态机。这些能力只放在 `:vibe-js-host`，核心仍无平台 API。
7. **B2 无计划但 S5.2 仍要做**：保留性能观测，避免以后重新评估时没有可比数据；S5 已记录 propertyGet +12.7%、propertySet +9.6%、数组循环 −19%、engineCreate +37%。
8. **平台差异**：JS/Wasm 单线程；Native/Android/Apple 仅 CI 或历史记录，文档结论必须带“本地/CI”标注。
9. **BigInt 内存上限**：`MAX_RESULT_BITS = 2^24`；大数运算可能触发 RangeError，这是规范允许的实现定义限制。
10. **WeakMap/WeakSet 仍是强引用**：不能当作长期缓存自动清理机制；M2 无计划。
11. **范围外能力不要混入缺口**：Intl、DOM/Web API、SAB/Atomics、resizable/transfer 等应标注“无计划/范围外”，不能作为回归处理。
12. **文档一致性**：README、COMPATIBILITY、CONFORMANCE 中不得再出现“模块执行 P0/下一阶段”的承诺；Parser 前置可标为实验性实现，但执行/加载/harness 必须标为未实现/无计划。

## 11. 关键文件入口

| 区域 | 文件 | 对应工作 |
|---|---|---|
| 单脚本执行 | [Engine.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Engine.kt)、[Interpreter.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Interpreter.kt) | `Engine.run(source)` 现状与回归 |
| 模块解析（阶段 0–1） | [Parser.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/parser/Parser.kt)、[Modules.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/ast/Modules.kt)、[Lexer.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/lexer/Lexer.kt) | 阶段 0–1 已完成；执行/加载无计划 |
| 模块 Provider API（阶段 2） | `runtime/module/ModuleApi.kt`、[EngineLimits.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EngineLimits.kt) | 实验性 Provider/错误/预算契约；`runModule` 已接入 |
| 模块图加载（阶段 3） | `runtime/module/internal/ModuleGraph.kt`、`ModuleGraphResolver.kt` | 内部递归加载/去重/预算 |
| 模块静态链接（阶段 4） | `runtime/module/internal/ModuleExportResolver.kt`、`ModuleLinker.kt` | ResolveExport/星号/namespace 导出名/import 链接/TLA 拒绝 |
| 模块环境（阶段 5） | `runtime/module/internal/ModuleEnvironment.kt`、`ModuleDeclarationInstantiator.kt` | import 只读转发、函数/TDZ 绑定、`*default*` |
| 模块 namespace（阶段 6） | `runtime/module/internal/JSModuleNamespace.kt` | 实时数据描述符、排序键、Symbol.toStringTag、嵌套 namespace |
| 模块求值（阶段 7） | [Engine.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Engine.kt)、[Interpreter.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Interpreter.kt)、`ModuleEvaluator.kt` | runModule/DFS 求值/import.meta/顶层 this；TLA/动态 import 未实现 |
| 模块测试（阶段 0–8） | [ParserModuleTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ParserModuleTest.kt)、[ModuleApiTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ModuleApiTest.kt)、[ModuleGraphResolverTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ModuleGraphResolverTest.kt)、[ModuleExportResolverTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ModuleExportResolverTest.kt)、[ModuleLinkerTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ModuleLinkerTest.kt)、[ModuleEnvironmentTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ModuleEnvironmentTest.kt)、[ModuleNamespaceTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ModuleNamespaceTest.kt)、[ModuleExecutionTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ModuleExecutionTest.kt)、[ParserConformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/ParserConformance.kt) | 单元测试 + 262 模块语料回归 |
| 宿主生态（已完成） | [HostModule.kt](../vibe-js-host/src/commonMain/kotlin/com/muedsa/js/host/HostModule.kt)、`TextEncodingHost.kt`、`StructuredCloneHost.kt`、`UrlHost.kt`、`HostEcosystemSupport.kt` | L4.5 已完成；只做回归与边界扩展 |
| Test262 筛选/运行器 | [Test262Selection.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Selection.kt)、[Test262Conformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Conformance.kt) | S6.2 / S2.2 |
| 差分与真实库 | [DifferentialCases.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/DifferentialCases.kt)、[LSeriesDifferentialCases.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/LSeriesDifferentialCases.kt)、[Libraries.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Libraries.kt) | S2.2 |
| 性能 | `vibe-js-core/src/jvmBenchmark/kotlin/` 下基准源码、`vibe-js-core/build/reports/benchmarks/` | S5.2 |
| 资源约束 | [EngineLimits.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EngineLimits.kt)、[ResourceLimitsTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ResourceLimitsTest.kt) | R1.3 |
| 文档 | [README.md](../README.md)、[ARCHITECTURE.md](ARCHITECTURE.md)、[COMPATIBILITY.md](COMPATIBILITY.md)、[CONFORMANCE.md](CONFORMANCE.md)、本文件 | 口径维护 |

> 不建议现在创建 `runtime/module/`、`ModuleLoader` 等平台接口；这些属于无计划的模块方向，只有明确重启时才设计。

## 12. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-08 | 重写为“后续路线（未完成工作）”：移除已完成项清单，只保留 P0 模块执行与宿主生态、P1 质量项、长期/延后项；补充每项的范围、依赖、人日、风险、验收与触发条件 |
| 2026-11-09 | 重新排序：模块执行作为条件 P0，模块语法降为最低优先级；M2/B2/PKG1/L4.7 列为无计划 |
| 2026-11-09 | **L4.5 完成**：宿主模块新增 TextEncoder/TextDecoder（UTF-8）、structuredClone、URL/URLSearchParams 常用子集与 13 条测试；从按需队列移除 |
| 2026-11-09 | **R1.3 完成**：`JSON.stringify` 接入 `maxJsonDepth`；显式降低 `maxRegexSteps` 时嵌套量词正则路由到受预算约束的回退引擎；补资源约束测试与异常分类表；从按需队列移除 |
| 2026-11-09 | **S2.2 新一批**：新增 fflate/js-base64/long/loglevel 共 12 条真实库断言，库总数 90、341/341；差分新增 well-formed JSON、isWellFormed/toWellFormed、toSorted 空洞、with 负索引，548→552/552 |
| 2026-11-10 | **S2.2 新一批**：新增 base64-js/jmespath/jssha 共 8 条断言，覆盖 TypedArray/Base64、JSON 查询与 SHA-256；库总数 90→93，真实库 341→349/349；差分新增 9 条（toSpliced/toReversed/with 边界、Object/Map.groupBy、hasOwn、replaceAll、TypedArray），552→561/561 |
| 2026-11-10 | **S2.2 第二批**：新增 d3-array/d3-hierarchy/earcut 共 8 条断言，覆盖 d3 命名空间、层级遍历与三角剖分；库总数 93→96，真实库 349→357/357 |
| 2026-11-10 | **S2.2 第三批**：新增 d3-dsv/cbor-js/humanize-duration 共 9 条断言，覆盖 CSV、CBOR 二进制往返与时长格式化；库总数 96→99，真实库 357→366/366；差分新增 6 条，561→567/567 |
| 2026-11-10 | **S2.2 第四批**：新增 msgpack-lite/d3-delaunay/topojson-client 共 9 条断言，覆盖 MessagePack、Delaunay 与 TopoJSON；库总数 99→102，真实库 366→375/375；差分新增 7 条（toSpliced 无参/删到末尾、with -0、groupBy Symbol/-0、toSorted 比较器、TypedArray with），567→574/574 |
| 2026-11-10 | **S2.2-B5 运行时边界**：新增 using 释放顺序/异常释放、DisposableStack、RegExp.escape、Array.from mapper 关闭迭代器、Object.fromEntries Symbol 键共 6 条；差分 574→580/580 |
| 2026-11-10 | **S2.2-B6 完成语义/异步资源**：新增 await using 顺序/异常、AsyncDisposableStack 3 条异步用例，以及 for-let/for-of/for-in 每迭代闭包、嵌套 finally、finally 中 continue、switch let TDZ、catch 参数作用域 8 条同步用例；差分 580→591/591 |
| 2026-11-10 | **S6.2 门禁复跑（B6 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 591/591、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B5 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 580/580、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 574/574、真实库 375/375，strict 全绿 |
| 2026-11-10 | **DOC-2 完成**：推进列表只保留周期性 `S6.2`、`S2.2-B5` 与 `S5.2`；M1/M3 标记完成，M2 改为持续覆盖；S6.2 命令补 ES5 与真实库 |
| 2026-11-10 | **S5.2 复测**：完整运行八组 JVM 基准并新增 `ModuleBenchmark`（入口 + 一个依赖，avg 28.307 us/op）；环境为 Windows 10 / 12 核 / JDK 21.0.12.1 / 4 GB 堆 / G1；只记录，不做优化承诺 |
| 2026-11-10 | **模块执行设计完成（执行仍无计划）**：新增 [MODULE_DESIGN.md](MODULE_DESIGN.md)，冻结嵌入式静态模块 MVP 的 API、图生命周期、链接/求值、错误与预算决策，并列出阶段 0–8 详细任务 |
| 2026-11-10 | **模块阶段 0–1 完成**：`requestedModules` 补空子句、`parseModule(SourceInfo)`、Import/Export entry range、类型化 `ModuleProgram.bindings`、`hasTopLevelAwait`，并收紧普通函数内 `await` 与零参数 `import()`；仅 Parser 前置，执行入口仍未实现 |
| 2026-11-10 | **模块阶段 2 完成**：新增实验性 `ModuleProvider` / `ModuleSource` / `ModuleRequest` / `InMemoryModuleProvider` / `ModuleLinkException`；`EngineLimits` 增加 `maxModules` 与 `maxTotalModuleSourceLength`；`Engine.runModule` 推迟到 M7 |
| 2026-11-10 | **模块阶段 3 完成**：新增内部 `ModuleRecord`/`ModuleGraph`/`ModuleGraphResolver`，递归加载 requestedModules、按 `(specifier, referrer)` 缓存请求、按 id 复用 record，并处理缺失依赖、循环、菱形依赖、Provider 异常与图预算；仍无链接/求值/公开入口 |
| 2026-11-10 | **模块阶段 4 完成**：新增内部 `ModuleExportResolver` 与 `ModuleLinker`，实现 ResolveExport、`export *`/`export * as ns`、循环/歧义/缺失、namespace 导出名与链接期 TLA 拒绝；仍未创建 ModuleEnvironment 或求值 |
| 2026-11-10 | **模块阶段 5 完成**：`DeclarativeEnvironment` 改为 open；新增内部 `ModuleEnvironment` 与 `ModuleDeclarationInstantiator`，实现 import 只读转发、var/function/let/const/class/using 绑定、函数对象与 TDZ、匿名 default 的 `*default*` 绑定；namespace 对象、顶层 this、求值仍未实现 |
| 2026-11-10 | **模块阶段 6 完成**：新增内部 `JSModuleNamespace`，空原型/不可扩展、实时数据描述符、排序导出键、`Symbol.toStringTag="Module"`、写/删/重定义防护与嵌套 namespace；`import.meta`、顶层 this、求值/公开入口仍未实现 |
| 2026-11-10 | **模块阶段 7 完成**：新增 `ModuleEvaluator` 与 `Interpreter.runModule`，接通 `Engine.runModule`、Provider 图加载/链接、环境实例化、`import.meta`、顶层 this、同步 DFS 求值、TLA 链接期拒绝与动态 import 运行期错误；API 需 opt-in；加载器/TLA 执行仍无计划 |
| 2026-11-10 | **模块阶段 8 完成**：补齐链接错误的 `sourceName`/`range`、`ParseException.sourceName`、模块执行栈来源、runModule 取消/图预算/失败恢复测试；文档同步；不新增独立性能基准/优化承诺 |
| 2026-11-10 | **MOD-1/MOD-2 完成**：新增 Provider 契约测试（referrer/null/异常/sourceName）与执行矩阵（循环、依赖失败、namespace 描述符、import.meta 缓存）；CI 由 commonTest 自动覆盖；核心测试 1065/1049 |
| 2026-11-10 | **DOC-1 完成**：同步模块阶段 0–8、测试数字、任务顺序与实验性边界；移除 CLI 任务 |
| 2026-11-09 | **整个模块方向降级为无计划**：保留 `Engine.run()` 单脚本入口与现有 `parseModule` 回归；说明 KMP 核心不应承担文件/URL IO，未来若重启只能采用“核心抽象 + 宿主加载器/内存模块表” |
