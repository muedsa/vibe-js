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
| core JVM 单元测试 | **1158 / 1158** | 2026-11-11 本地运行 |
| core JS / Wasm（Node） | 各 **1142 / 1142** | 2026-11-11 复跑 |
| host JVM / JS / Wasm / Native | 各 **38 / 38** | 2026-11-09 新增 TextEncoder/TextDecoder、structuredClone、URL 测试；Windows Native 已复跑 |
| conformance 单元测试 | **39 / 39** | ParserConformanceTest 6 + RunSupportTest 10 + Test262SelectionTest 19 + RunMetadataTest 4 |
| parser 脚本 | **1,907 / 1,907**；fail 674 / 682（允许 8）；early 5 / 605（允许 5） | pass / pass-explicit 各 1,907，允许 0 |
| parser 模块 | pass-module **76 / 76**、pass-explicit-module **76 / 76**、fail-module **47 / 47**、early-module **63 / 63**（拒绝） | 262 个 `.module.js`，允许列表 0 条 |
| Test262 ES5 | **15,248 / 15,248**，失败 0，跳过 0 | 2026-11-10 复跑 |
| Test262 ES2015 | **61,786 / 61,786**，失败 0，崩溃 0 | 选中 32,293；跳过 **324**（module flag 322 + `$262`/`print` 2） |
| Node 差分 | **921 / 921** | 含 L 系列同步与异步用例 |
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
| 2026-11-10 | **S2.2-B7 错误与完成语义**：新增 SuppressedError 字段、DisposableStack 多重释放错误、for/while/do-while 每迭代绑定、break 穿过 finally、finally return 覆盖 throw、switch 重复 let 共 8 条；差分 591→599/599 |
| 2026-11-10 | **S2.2-B8 Proxy/toString 边界**：新增撤销 Proxy 的 `Array.isArray`/JSON/`Object.prototype.toString`/`instanceof`、`Symbol.toStringTag` getter 异常、`Error.toString` name getter 异常、bound function `toString` 共 7 条；差分 599→606/606；修复 bound function 误带目标函数名的问题 |
| 2026-11-10 | **S2.2-B9 Promise/Thenable 边界**：新增 thenable getter 抛错、executor resolve 后再抛错、Promise 子类与 species 共 4 条异步用例；差分 606→610/610 |
| 2026-11-10 | **S2.2-B10 新内建与异步生成器**：新增 `Error.isError`、`JSON.rawJSON`/非法文本、async generator return/throw/`for await` close/yield* return 共 7 条；差分 610→617/617 |
| 2026-11-10 | **S2.2-B11 class/super 边界**：新增 this-before-super、基类返回对象上的字段初始化、字段 super 调用、静态块 super、私有品牌/Proxy、super setter 接收者、计算键求值顺序共 8 条；差分 617→625/625 |
| 2026-11-10 | **S2.2-B12 Iterator 组合器**：Node 24 尚未提供 `Iterator.concat`/`zip`/`zipKeyed`，改用核心单测覆盖串联、shortest/longest/strict、padding、zipKeyed 与错误传播 7 条；核心 1072/1056 |
| 2026-11-10 | **S2.2-B13 异步生成器 pending await**：新增 `.return()`/`.throw()` 在生成器暂停于 `await` 时的排队、恢复与 finally 语义 2 条；差分 625→627/627 |
| 2026-11-10 | **S2.2-B14 Proxy 内建消费路径**：新增 `Object.assign`、解构、`Array.from`、对象 spread、`Object.keys`、`JSON.stringify` 的 Proxy trap 顺序共 6 条；差分 627→633/633 |
| 2026-11-10 | **S2.2-B15 模块图压力**：新增 128 层深依赖链与 128 个星号导出模块的核心单测，验证链接/求值顺序与导出完整性；核心 1074/1058 |
| 2026-11-10 | **S2.2-B16 模块预算/取消边界**：新增深链 maxModules 截断、跨模块总源码长度超限、取消后不再调用 Provider 3 条核心单测；核心 1077/1061 |
| 2026-11-10 | **S2.2-B17 namespace 与重复运行**：宽图 namespace 键排序/描述符/不可扩展、同一 Provider 连续 runModule 独立图、失败后新图恢复 3 条核心单测；核心 1079/1063 |
| 2026-11-10 | **S2.2-B18 Proxy 数组/迭代路径**：新增 `map`、`push`、数组展开、`join`、`setPrototypeOf` 5 条 Proxy trap 差分；差分 633→638/638 |
| 2026-11-10 | **S2.2-B19 循环星号 namespace**：新增循环星号导出实时绑定、同名歧义省略 2 条核心单测；修复 namespace 暴露歧义名的问题（GetModuleNamespace 只保留唯一解析名）；核心 1081/1065 |
| 2026-11-10 | **S2.2-B20 循环重命名/命名空间导出**：新增循环重命名别名实时绑定、循环 `export * as` 命名空间身份 2 条核心单测；核心 1083/1067 |
| 2026-11-10 | **S2.2-B21 循环 default 与预算边界**：新增循环 default 重导出别名、深链恰好达到 maxModules 预算成功 2 条核心单测；核心 1085/1069 |
| 2026-11-10 | **S2.2-B22 Proxy 迭代器关闭**：新增 `for-of` break/`Array.from` mapper 抛错的 Proxy 迭代器关闭 2 条差分；修复 `Array.from` 重复读取 `Symbol.iterator` 的问题；差分 638→640/640 |
| 2026-11-10 | **S2.2-B23 Array.fromAsync GetMethod**：新增 async iterator getter 单次读取、sync iterator 回退 getter 单次读取 2 条异步用例；差分 640→642/642 |
| 2026-11-10 | **S2.2-B24 Array.fromAsync 关闭路径**：新增同步迭代器 mapper 抛错的 return、异步 mapper reject 的 return 2 条异步用例；差分 642→644/644 |
| 2026-11-10 | **S2.2-B25 Array.fromAsync array-like 与源码预算**：新增 `length` getter 抛错、跨模块总源码长度恰好达预算成功 2 条用例；差分 644→645/645，核心 1086/1070 |
| 2026-11-10 | **S2.2-B26 模块执行预算**：新增模块体死循环触发 maxSteps、异常后同一 Engine 可复用 1 条核心单测；核心 1087/1071 |
| 2026-11-10 | **S2.2-B27 模块对象预算**：新增模块体大量对象分配触发 maxObjectCount、异常后同一 Engine 可复用 1 条核心单测；核心 1088/1072 |
| 2026-11-10 | **S2.2-B28 模块微任务预算**：新增模块体批量 Promise.then 触发 maxMicrotasksPerRun、异常后同一 Engine 可复用 1 条核心单测；核心 1089/1073 |
| 2026-11-10 | **S2.2-B29 多轮 namespace 与 Promise.all**：新增多轮 runModule 描述符互不影响、Promise.all 消费 Proxy 迭代器 2 条用例；差分 645→646/646，核心 1090/1074 |
| 2026-11-10 | **S2.2-B30 Promise.all 迭代异常**：新增 next 抛错、next 返回非对象 2 条异步用例，确认不调用迭代器 return；差分 646→648/648 |
| 2026-11-10 | **S2.2-B31 Promise.race/any 迭代异常**：新增 race/any 的 next 抛错 2 条异步用例，确认不调用迭代器 return；差分 648→650/650 |
| 2026-11-10 | **S2.2-B32 allSettled 与失败图重试**：新增 allSettled 的 next 抛错、失败模块图不跨 runModule 缓存 2 条用例；差分 650→651/651，核心 1091/1075 |
| 2026-11-10 | **S2.2-B33 Promise 子类组合器与重试 namespace**：新增 `Promise.all` 子类/species 构造 2 条异步用例、失败重试后 namespace 不复用旧图 1 条核心单测；差分 651→653/653，核心 1092/1076 |
| 2026-11-10 | **S2.2-B34 Promise.all 构造/capability 异常**：新增子类构造函数抛错同步传播、自定义 capability `resolve` 抛错转 rejection 2 条同步用例；差分 653→655/655 |
| 2026-11-10 | **S2.2-B35 组合器 capability 异常矩阵**：新增 race/any/allSettled 自定义 capability `resolve` 抛错转 rejection 3 条同步用例；差分 655→658/658 |
| 2026-11-10 | **S2.2-B36 组合器构造异常补全**：新增 race/any/allSettled 子类构造函数抛错同步传播 1 条同步用例；差分 658→659/659 |
| 2026-11-10 | **S2.2-B37 模块待处理任务预算**：新增模块体批量 Promise 微任务触发 maxPendingTasks、异常后同一 Engine 可复用 1 条核心单测；核心 1093/1077 |
| 2026-11-10 | **S2.2-B38 Promise.any 全拒绝**：新增全拒绝返回 AggregateError、`errors` 顺序与 `instanceof` 1 条异步用例；差分 659→660/660 |
| 2026-11-10 | **S2.2-B39 组合器空输入**：新增 all/allSettled/any/race 对空数组的完成语义 1 条异步用例；差分 660→661/661 |
| 2026-11-10 | **S2.2-B40 显式导出优先**：新增显式 `export { x }` 优先于同名星号导出 1 条核心单测；核心 1094/1078 |
| 2026-11-10 | **S2.2-B41 显式导出实时绑定**：新增显式导出覆盖同名星号导出后仍保持 live binding 1 条核心单测；核心 1095/1079 |
| 2026-11-10 | **S2.2-B42 allSettled 顺序**：新增延迟 rejection 后仍按输入顺序返回状态 1 条异步用例；差分 661→662/662 |
| 2026-11-10 | **S2.2-B43 菱形依赖去重**：新增共享依赖只求值一次、深度优先顺序正确 1 条核心单测；核心 1096/1080 |
| 2026-11-10 | **S2.2-B44 export star as default**：新增 `export * as default` 暴露嵌套 namespace 1 条核心单测；核心 1097/1081 |
| 2026-11-10 | **S2.2-B45 依赖失败隔离**：新增依赖求值失败后后续依赖不再执行 1 条核心单测；核心 1098/1082 |
| 2026-11-10 | **S2.2-B46 显式导出顺序无关**：新增显式导出位于星号导出之后仍优先 1 条核心单测；核心 1099/1083 |
| 2026-11-10 | **S2.2-B47 循环求值顺序**：新增循环依赖按深度优先逆序执行模块体 1 条核心单测；核心 1100/1084 |
| 2026-11-10 | **S2.2-B48 入口体失败隔离**：新增依赖失败后入口模块体不再执行 1 条核心单测；核心 1101/1085 |
| 2026-11-10 | **S2.2-B49 命名空间导出 default**：新增 `export * as ns` 的嵌套 namespace 同时包含 `default` 与普通导出 1 条核心单测；核心 1102/1086 |
| 2026-11-10 | **S2.2-B50 默认导出与实时绑定边界**：新增星号导出排除 default、命名空间导出 live binding、default 显式重导出 3 条核心单测；核心 1105/1089 |
| 2026-11-10 | **S2.2-B51 重复依赖加载去重**：新增同一 specifier 重复 import 只向 Provider 请求一次 1 条核心单测；核心 1106/1090 |
| 2026-11-10 | **S2.2-B52 同 id 多 specifier 去重**：新增不同 specifier 返回同一 module id 时只求值一次 1 条核心单测；核心 1107/1091 |
| 2026-11-10 | **S2.2-B53 嵌套命名空间不变量**：新增嵌套 namespace 的原型、不可扩展、描述符与二级嵌套 1 条核心单测；核心 1108/1092 |
| 2026-11-10 | **S2.2-B54 命名空间身份复用**：新增同一模块两次 `export * as` 得到同一 namespace 对象 1 条核心单测；核心 1109/1093 |
| 2026-11-10 | **S2.2-B55 Proxy 数组高阶方法**：新增 find/some/reduce 通过 Proxy 的读取与短路顺序 3 条同步差分；差分 662→665/665 |
| 2026-11-10 | **S2.2-B56 Proxy 数组遍历补全**：新增 filter/indexOf 通过 Proxy 的读取与短路顺序 2 条同步差分；差分 665→667/667 |
| 2026-11-10 | **S2.2-B57 重复星号导出去重**：新增同一模块重复 `export *` 不产生歧义 1 条核心单测；核心 1110/1094 |
| 2026-11-10 | **S2.2-B58 Array.fromAsync length 单次读取**：新增 array-like `length` getter 只读取一次 1 条异步用例；差分 667→668/668 |
| 2026-11-10 | **S2.2-B59 跨 specifier 命名空间身份**：新增不同 specifier 指向同一 module id 时命名空间身份一致 1 条核心单测；核心 1111/1095 |
| 2026-11-10 | **S2.2-B60 Array.fromAsync 索引顺序**：新增 array-like 索引 getter 按 0、1 顺序读取 1 条异步用例；差分 668→669/669 |
| 2026-11-10 | **S6.2 门禁复跑（B60 后里程碑）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 669/669、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S2.2-B62 Array.fromAsync 非法长度**：新增 array-like `length = Infinity` 拒绝为 RangeError 1 条异步用例；差分 669→670/670 |
| 2026-11-10 | **S2.2-B63 allSettled then getter 抛错**：新增元素 then getter 抛错只使该元素 rejected 1 条异步用例；差分 670→671/671 |
| 2026-11-10 | **S2.2-B64 Promise.all then getter 抛错**：新增元素 then getter 抛错使 Promise.all 拒绝 1 条异步用例；差分 671→672/672 |
| 2026-11-10 | **S2.2-B65 Promise.any then getter 抛错**：新增 getter 错误进入 AggregateError.errors 1 条异步用例；差分 672→673/673 |
| 2026-11-10 | **S2.2-B66 Promise.race then getter 抛错**：新增 getter 错误使 Promise.race 拒绝 1 条异步用例；差分 673→674/674 |
| 2026-11-10 | **S2.2-B67 非可调用 then**：新增 `then` 非函数时 thenable 按普通值 fulfilled 1 条异步用例；差分 674→675/675 |
| 2026-11-10 | **S2.2-B68 Array.fromAsync mapper Promise**：新增 mapper 返回 Promise 时按值等待 1 条异步用例；差分 675→676/676 |
| 2026-11-10 | **S2.2-B69 maxModules 唯一计数**：新增两个 specifier 同 id 只占一个 maxModules 名额 1 条核心单测；核心 1112/1096 |
| 2026-11-10 | **S2.2-B70 源码预算唯一计数**：新增两个 specifier 同 id 只计算一次源码长度 1 条核心单测；核心 1113/1097 |
| 2026-11-10 | **S2.2-B71 模块源码长度预算**：新增模块源码超过 maxSourceLength 抛 SOURCE_LENGTH 且 Engine 可复用 1 条核心单测；核心 1114/1098 |
| 2026-11-10 | **S2.2-B72 模块 AST 节点预算**：新增模块解析超过 maxAstNodes 抛 AST_NODES 且 Engine 可复用 1 条核心单测；核心 1115/1099 |
| 2026-11-10 | **S2.2-B73 模块嵌套深度预算**：新增模块解析超过 maxNestingDepth 抛 NESTING_DEPTH 且 Engine 可复用 1 条核心单测；核心 1116/1100 |
| 2026-11-10 | **S2.2-B74 源码长度边界成功**：新增模块源码长度恰好达到 maxSourceLength 时成功 1 条核心单测；核心 1117/1101 |
| 2026-11-10 | **S2.2-B75 源码长度少一字符**：新增 maxSourceLength 比源码少 1 时失败并校验 limit/actual 1 条核心单测；核心 1118/1102 |
| 2026-11-10 | **S2.2-B76 空模块命名空间**：新增空模块的 `export * as ns` 保持空 namespace exotic 语义 1 条核心单测；核心 1119/1103 |
| 2026-11-10 | **S2.2-B77 无导出入口模块**：新增无导出入口仍执行副作用并返回空 namespace 1 条核心单测；核心 1120/1104 |
| 2026-11-10 | **S2.2-B78 default namespace 描述符**：新增入口 default 导出的 descriptor 与 namespace 不变量 1 条核心单测；核心 1121/1105 |
| 2026-11-10 | **S2.2-B79 嵌套 namespace toStringTag**：新增嵌套 namespace 的 Symbol.toStringTag 仍为 Module 1 条核心单测；核心 1122/1106 |
| 2026-11-10 | **S2.2-B80 namespace __proto__ 导出**：新增 `__proto__` 导出保持普通数据属性且原型仍为 null 1 条核心单测；核心 1123/1107 |
| 2026-11-10 | **S2.2-B81 namespace toStringTag 描述符**：新增 toStringTag 只读、不可枚举、不可配置 1 条核心单测；核心 1124/1108 |
| 2026-11-10 | **S2.2-B82 namespace 写入拒绝**：新增 Reflect.set 返回 false、严格模式赋值抛 TypeError 1 条核心单测；核心 1125/1109 |
| 2026-11-10 | **S2.2-B83 namespace 删除拒绝**：新增 Reflect.deleteProperty 返回 false、严格模式 delete 抛 TypeError 1 条核心单测；核心 1126/1110 |
| 2026-11-10 | **S2.2-B84 namespace 定义拒绝**：新增 Reflect.defineProperty 对已有/新增属性均返回 false 1 条核心单测；核心 1127/1111 |
| 2026-11-10 | **S2.2-B85 namespace for-in 枚举**：新增按排序枚举导出且忽略标签符号 1 条核心单测；核心 1128/1112 |
| 2026-11-10 | **S2.2-B86 namespace freeze**：新增 Object.freeze 抛 TypeError、sealed 但未 frozen 1 条核心单测；核心 1129/1113 |
| 2026-11-10 | **S2.2-B87 namespace Object_toString**：新增 `[object Module]` 标签消费 1 条核心单测；核心 1130/1114 |
| 2026-11-10 | **S2.2-B88 namespace JSON 序列化**：新增 JSON.stringify 按可枚举导出输出 1 条核心单测；核心 1131/1115 |
| 2026-11-10 | **S2.2-B89 namespace 对象展开**：新增 `{ ...ns }` 复制可枚举导出 1 条核心单测；核心 1132/1116 |
| 2026-11-10 | **S2.2-B90 namespace Object.entries**：新增按排序输出导出键值 1 条核心单测；核心 1133/1117 |
| 2026-11-10 | **S2.2-B91 Array.fromAsync 字符串输入**：新增字符串按码点展开 1 条异步用例；差分 676→677/677 |
| 2026-11-10 | **S2.2-B92 Array.fromAsync 稀疏数组**：新增空位按 undefined 展开、结果稠密 1 条异步用例；差分 677→678/678 |
| 2026-11-10 | **S2.2-B93 Object.fromEntries 重复键**：新增后者覆盖、保留首次插入顺序 1 条同步用例；差分 678→679/679 |
| 2026-11-10 | **S2.2-B94 Object.fromEntries 非法条目关闭**：新增非对象条目抛 TypeError 并调用 iterator.return 1 条同步用例；差分 679→680/680 |
| 2026-11-10 | **S2.2-B95 Object.fromEntries 短条目**：新增空条目/单元素条目按 undefined 键值处理 1 条同步用例；差分 680→681/681 |
| 2026-11-10 | **S2.2-B96 Object.fromEntries 键 getter 抛错**：新增键 getter 异常传播并关闭迭代器 1 条同步用例；差分 681→682/682 |
| 2026-11-10 | **S2.2-B97 Object.fromEntries 值 getter 抛错**：新增值 getter 异常传播并关闭迭代器 1 条同步用例；差分 682→683/683 |
| 2026-11-10 | **S2.2-B98 Object.hasOwn Proxy 陷阱**：新增通过 getOwnPropertyDescriptor 陷阱检查 1 条同步用例；差分 683→684/684 |
| 2026-11-10 | **S2.2-B99 Object.hasOwn Proxy 不变量**：新增不可配置属性不能被报告为不存在 1 条同步用例；差分 684→685/685 |
| 2026-11-10 | **S6.2 门禁复跑（B100 里程碑）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 685/685、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S2.2-B101 Object.hasOwn 撤销 Proxy**：新增撤销后调用抛 TypeError 1 条同步用例；差分 685→686/686 |
| 2026-11-10 | **S2.2-B102 Object.hasOwn 非法描述符**：新增 Proxy 陷阱返回原始值时抛 TypeError 1 条同步用例；差分 686→687/687 |
| 2026-11-10 | **S2.2-B103 Object.hasOwn 不可扩展目标**：新增不可扩展目标不能凭空报告自有属性 1 条同步用例；差分 687→688/688 |
| 2026-11-10 | **S2.2-B104 Array.fromAsync length getter 抛错**：新增长度读取异常拒绝 1 条异步用例；差分 688→689/689 |
| 2026-11-10 | **S2.2-B105 Array.fromAsync 索引 getter 抛错**：新增索引读取异常拒绝 1 条异步用例；差分 689→690/690 |
| 2026-11-10 | **S2.2-B106 Array.fromAsync 非可调用 mapper**：新增非函数 mapper 拒绝为 TypeError 1 条异步用例；差分 690→691/691 |
| 2026-11-10 | **S2.2-B107 Array.fromAsync 生成器 return**：新增生成器 return 值被忽略 1 条异步用例；差分 691→692/692 |
| 2026-11-10 | **S2.2-B108 Array.fromAsync 迭代器 getter 抛错**：新增 Symbol.iterator getter 异常拒绝 1 条异步用例；差分 692→693/693 |
| 2026-11-10 | **S2.2-B109 Array.fromAsync 非可调用迭代器**：新增 Symbol.iterator 非函数时拒绝为 TypeError 1 条异步用例；差分 693→694/694 |
| 2026-11-10 | **S2.2-B110 Array.fromAsync 非对象迭代器**：新增 Symbol.iterator 返回原始值时拒绝为 TypeError 1 条异步用例；差分 694→695/695 |
| 2026-11-10 | **S2.2-B111 Array.fromAsync 非可调用 next**：新增 next 非函数时拒绝为 TypeError 1 条异步用例；差分 695→696/696 |
| 2026-11-10 | **S2.2-B112 Array.fromAsync 非对象 next 结果**：新增 next 返回原始值时拒绝为 TypeError 1 条异步用例；差分 696→697/697 |
| 2026-11-10 | **S2.2-B113 Array.fromAsync 空 next 结果**：新增空对象按 done=false、value=undefined 处理 1 条异步用例；差分 697→698/698 |
| 2026-11-10 | **S2.2-B114 Array.fromAsync done getter 抛错**：新增 done 读取异常拒绝 1 条异步用例；差分 698→699/699 |
| 2026-11-10 | **S2.2-B115 Array.fromAsync value getter 抛错**：新增 value 读取异常拒绝 1 条异步用例；差分 699→700/700 |
| 2026-11-10 | **S2.2-B116 Array.fromAsync done=true 不读 value**：核心单测固定规范行为；Node 24 会读取 value，记录为已知差异且不纳入差分；核心 1134/1118 |
| 2026-11-10 | **S2.2-B117 异步迭代器 done=true 不读 value**：新增 Node 与规范一致的异步迭代器路径 1 条异步用例；差分 700→701/701 |
| 2026-11-10 | **S2.2-B118 同 id 首次源码优先**：新增不同 specifier 返回同 id 不同源码时使用首次加载源码 1 条核心单测；核心 1135/1119 |
| 2026-11-10 | **S2.2-B119 同 id 首次 URL 优先**：新增同 id 不同 url 时 import.meta.url 取首次来源 1 条核心单测；核心 1136/1120 |
| 2026-11-10 | **S2.2-B120 Object.fromEntries next 抛错不关闭**：核心单测固定规范行为；Node 24 会调用 return，记录为已知差异且不纳入差分；核心 1137/1121 |
| 2026-11-10 | **S2.2-B121 Object.fromEntries __proto__ 键**：新增 CreateDataProperty 语义 1 条同步用例；差分 701→702/702 |
| 2026-11-10 | **S2.2-B122 Array.fromAsync 小数 length**：新增 ToLength 截断为 2 1 条异步用例；差分 702→703/703 |
| 2026-11-10 | **S2.2-B123 Array.fromAsync Symbol length**：新增 Symbol 转长度抛 TypeError 拒绝 1 条异步用例；差分 703→704/704 |
| 2026-11-10 | **S2.2-B124 Array.fromAsync 负数 length**：新增负数截断为 0 1 条异步用例；差分 704→705/705 |
| 2026-11-10 | **S2.2-B125 Array.fromAsync 缺失 length**：新增缺失 length 按 0 处理 1 条异步用例；差分 705→706/706 |
| 2026-11-10 | **S2.2-B126 Array.fromAsync BigInt length**：新增 BigInt 转长度抛 TypeError 拒绝 1 条异步用例；差分 706→707/707 |
| 2026-11-10 | **S2.2-B127 同 id 首次 sourceName 优先**：新增错误栈使用首次 sourceName 1 条核心单测；核心 1138/1122 |
| 2026-11-10 | **S2.2-B128 Array.fromAsync 超上限 length**：新增 2^32 长度拒绝为 RangeError 1 条异步用例；差分 707→708/708 |
| 2026-11-10 | **S2.2-B129 namespace toStringTag 自有性**：新增 tag 为自有属性但不参与枚举 1 条核心单测；核心 1139/1123 |
| 2026-11-10 | **S2.2-B130 namespace 自有符号唯一**：新增仅暴露 toStringTag 一个自有符号 1 条核心单测；核心 1140/1124 |
| 2026-11-10 | **S2.2-B131 namespace Reflect.ownKeys**：新增字符串导出在前、标签符号在后 1 条核心单测；核心 1141/1125 |
| 2026-11-10 | **S2.2-B132 namespace 名称与描述符反射**：新增 getOwnPropertyNames 与 getOwnPropertyDescriptors 只含导出 1 条核心单测；核心 1142/1126 |
| 2026-11-10 | **S2.2-B133~B135 Object.fromEntries 键顺序**：新增整数键排序、符号键顺序与 -0 规范化 3 条同步用例；差分 708→711/711 |
| 2026-11-10 | **S2.2-B136~B138 Object.fromEntries 键转换**：新增对象键 ToPropertyKey 一次、重复符号键覆盖与 nullish 键 3 条同步用例；差分 711→714/714 |
| 2026-11-10 | **S2.2-B139~B141 Array.fromAsync mapper 索引**：新增 array-like、同步迭代器与异步迭代器的 mapper 索引 3 条异步用例；差分 714→717/717 |
| 2026-11-10 | **S2.2-B142~B144 Object.fromEntries 描述符与读取顺序**：新增属性描述符、键 ToString 抛错关闭迭代器、键先于值读取 3 条同步用例；差分 717→720/720 |
| 2026-11-10 | **S2.2-B145~B146 namespace 普通对象互操作**：新增 Object.assign 复制与 Array.from 无 length 处理 2 条核心单测；核心 1144/1128 |
| 2026-11-10 | **S2.2-B147~B148 Array.fromAsync 自定义构造器**：新增构造器返回对象与抛错 2 条异步用例；差分 720→722/722 |
| 2026-11-10 | **S2.2-B149~B150 Array.fromAsync 构造器结果边界**：新增不可扩展结果与 length setter 抛错 2 条异步用例；差分 722→724/724 |
| 2026-11-10 | **S2.2-B151~B152 Array.fromAsync 构造器调用与冲突**：新增构造器仅调用一次、已有不可配置索引冲突 2 条异步用例；差分 724→726/726 |
| 2026-11-10 | **S2.2-B153~B154 Array.fromAsync 结果失败关闭迭代器**：新增元素定义失败与 length 失败均调用 return 2 条异步用例；差分 726→728/728 |
| 2026-11-10 | **S2.2-B155~B156 Object.fromEntries 空输入与符号输入**：新增空 iterable 与仅符号键 2 条同步用例；差分 728→730/730 |
| 2026-11-10 | **S2.2-B157~B159 Object.fromEntries 迭代器获取失败**：新增 iterator getter 抛错、迭代器非对象、next getter 抛错 3 条同步用例；差分 730→733/733 |
| 2026-11-10 | **S2.2-B160~B162 Object.fromEntries next 协议失败**：新增 next 非函数、next 结果非对象、done getter 抛错 3 条同步用例；差分 733→736/736 |
| 2026-11-10 | **S2.2-B163 Object.fromEntries done=true 不读 value**：新增 done 为 true 时不读取 value 1 条同步用例；差分 736→737/737 |
| 2026-11-10 | **S2.2-B164~B165 Object.fromEntries 结果缺省字段**：新增缺失 done 时按 false 处理、空条目按 undefined 键值处理 2 条同步用例；差分 737→739/739 |
| 2026-11-10 | **S6.2 门禁复跑（739 差分基线）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 739/739、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S2.2-B167~B168 Object.fromEntries 无效条目关闭**：新增 null 与字符串条目抛 TypeError 并调用 return 2 条同步用例；差分 739→741/741 |
| 2026-11-10 | **S2.2-B169~B170 Array.fromAsync 空输入不调用 mapper**：新增负数与缺失 length 时 mapper 调用次数为 0 2 条异步用例；差分 741→743/743 |
| 2026-11-10 | **S2.2-B171~B172 Array.fromAsync length 读取与迭代器优先级**：新增 length 只读一次、迭代器优先于 array-like 2 条异步用例；差分 743→745/745 |
| 2026-11-10 | **S2.2-B173~B174 Array.fromAsync 正常完成不调用 return**：新增同步与异步迭代器正常结束不调用 return 2 条异步用例；差分 745→747/747 |
| 2026-11-10 | **S2.2-B175~B176 Array.fromAsync 异步 next 结果形状**：新增异步 next 返回同步结果/原始值 2 条异步用例；差分 747→749/749 |
| 2026-11-10 | **S2.2-B177~B179 Array.fromAsync 异步迭代器获取失败**：新增 async iterator getter 抛错、迭代器非对象、next getter 抛错 3 条异步用例；差分 749→752/752 |
| 2026-11-10 | **S2.2-B180~B182 Array.fromAsync 异步迭代器可调用性与拒绝**：新增迭代器方法非函数、next 非函数、next 拒绝 3 条异步用例；差分 752→755/755 |
| 2026-11-10 | **S2.2-B183~B184 Array.fromAsync 异步 done/value getter**：新增异步 done 与 value getter 抛错 2 条异步用例；差分 755→757/757 |
| 2026-11-10 | **S2.2-B185~B186 Array.fromAsync 值 await 语义**：新增异步迭代器值不再 await、同步迭代器值会 await 2 条异步用例；差分 757→759/759 |
| 2026-11-10 | **S2.2-B187~B188 模块 URL 空值边界**：新增首次 URL 为空回退 `module:<id>`、入口 URL 为空字符串保留 2 条核心单测；核心 1146/1130 |
| 2026-11-10 | **S2.2-B189 异步迭代器 done=true 不调用 return**：新增异步迭代器 done=true 且 value getter 抛错时不读 value、不调用 return 1 条异步用例；差分 759→760/760 |
| 2026-11-10 | **S2.2-B190~B191 Array.fromAsync Proxy array-like**：新增 Proxy 读取顺序与 length 抛错 2 条异步用例；差分 760→762/762 |
| 2026-11-10 | **S2.2-B192~B193 Array.fromAsync Proxy 陷阱边界**：新增 has 陷阱不被调用、迭代器优先于 array-like 2 条异步用例；差分 762→764/764 |
| 2026-11-10 | **S2.2-B194~B195 Object.fromEntries 非可迭代输入**：新增普通 array-like 与 Symbol.iterator 为 undefined 2 条同步用例；差分 764→766/766 |
| 2026-11-10 | **S2.2-B196 Object.fromEntries nullish 输入**：新增 null 与 undefined 输入均抛 TypeError 1 条同步用例；差分 766→767/767 |
| 2026-11-10 | **S2.2-B197~B199 Array.fromAsync 非可迭代输入**：新增 null、undefined、原始值输入均拒绝为 TypeError 3 条异步用例；差分 767→770/770 |
| 2026-11-10 | **S2.2-B200~B201 空模块 id 校验**：新增入口与依赖 id 为空均进入 INVALID_MODULE 2 条核心单测；核心 1148/1132 |
| 2026-11-10 | **S2.2-B202~B203 模块 id 空白与内部空格**：新增纯空白 id 拒绝、内部空格 id 正常运行 2 条核心单测；核心 1150/1134 |
| 2026-11-10 | **S2.2-B204~B205 依赖空白 id 与空 sourceName**：新增依赖空白 id 进入 INVALID_MODULE、空 sourceName 保留在错误栈 2 条核心单测；核心 1152/1136 |
| 2026-11-10 | **S2.2-B206~B207 Object.fromEntries 关闭异常优先级**：新增 return getter 抛错与非函数时保留原始 TypeError 2 条同步用例；差分 770→772/772 |
| 2026-11-10 | **S2.2-B208~B209 Object.fromEntries 原错误优先**：新增键读取失败与 done 读取失败时 return 抛错不覆盖原错误 2 条同步用例；差分 772→774/774 |
| 2026-11-10 | **S2.2-B210~B211 Object.fromEntries 值/键错误与关闭错误**：新增值读取失败时 return 抛错、键读取失败时 return getter 抛错均保留原错误 2 条同步用例；差分 774→776/776 |
| 2026-11-10 | **S2.2-B212~B213 Object.fromEntries return 调用契约**：新增 return 只调用一次且无参、键错误关闭只一次 2 条同步用例；差分 776→778/778 |
| 2026-11-10 | **S2.2-B214~B215 Object.fromEntries return 原始值优先级**：新增键错误与 done 错误时 return 返回原始值仍保留原错误 2 条同步用例；差分 778→780/780 |
| 2026-11-10 | **S2.2-B216~B217 Object.fromEntries 不读取 return 结果**：新增 return 结果 done/value getter 抛错均不被读取 2 条同步用例；差分 780→782/782 |
| 2026-11-10 | **S2.2-B218 Object.fromEntries next getter 不关闭迭代器**：新增读取 next getter 失败时不调用 return 1 条同步用例；差分 782→783/783（next 调用抛错分支 Node 与规范不一致，继续由核心单测固定） |
| 2026-11-10 | **S2.2-B219~B220 Object.fromEntries next 非对象与 done 错误不关闭迭代器**：新增 2 条核心单测固定规范行为（Node 24 均会关闭，已记入已知差异）；核心 1154/1138 |
| 2026-11-10 | **S2.2-B221~B222 Object.fromEntries getter 与关闭错误回归锁**：新增 next getter 抛错不关闭、键错误时 return 抛错保留原错误 2 条核心单测；核心 1156/1140 |
| 2026-11-10 | **S6.2 门禁复跑（783 差分基线）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 783/783、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S2.2-B224~B225 Array.fromAsync 结果 length 与 Proxy**：新增不可写 length、Proxy 结果 set 陷阱 2 条异步用例；差分 783→785/785 |
| 2026-11-10 | **S2.2-B226~B227 Array.fromAsync length 零值与无 setter**：新增不可写 length=0 仍拒绝、仅有 getter 的 length 拒绝 2 条异步用例；差分 785→787/787 |
| 2026-11-10 | **S2.2-B228~B229 Array.fromAsync 构造器顺序与抛错**：新增迭代器先于构造器、构造器抛错不关闭迭代器 2 条异步用例；差分 787→789/789 |
| 2026-11-10 | **S2.2-B230~B231 Array.fromAsync 非构造器 this**：新增普通对象 this 得到数组、函数 this 得到普通对象 2 条异步用例；差分 789→791/791 |
| 2026-11-10 | **S2.2-B232~B233 Array.fromAsync 构造参数与 Proxy 构造**：新增构造器无参调用、Proxy construct 结果复用 2 条异步用例；差分 791→793/793 |
| 2026-11-10 | **S2.2-B234~B235 Object.fromEntries return getter 访问时机**：新增正常完成不读取、异常关闭只读取一次 2 条同步用例；差分 793→795/795 |
| 2026-11-10 | **S2.2-B236~B237 Object.fromEntries 迭代器调用契约**：新增 Symbol.iterator 与 next 的 this/参数契约 2 条同步用例；差分 795→797/797 |
| 2026-11-10 | **S2.2-B238~B239 Array.fromAsync 迭代器调用契约**：新增 Symbol.iterator 与 next 的 this/参数契约 2 条异步用例；差分 797→799/799 |
| 2026-11-11 | **S2.2-B240~B241 Array.fromAsync mapper 调用契约**：新增 mapper 双参数与 undefined 元素仍调用 2 条异步用例；差分 799→801/801 |
| 2026-11-11 | **S2.2-B242~B243 Array.fromAsync mapper 第二个元素失败**：新增第二个元素抛错/返回 rejected Promise 时关闭迭代器并拒绝 2 条异步用例；差分 801→803/803 |
| 2026-11-11 | **S2.2-B244~B245 Array.fromAsync array-like 读取边界**：新增映射期间删除索引、索引 getter 只读一次 2 条异步用例；差分 803→805/805 |
| 2026-11-11 | **S2.2-B246~B247 Array.fromAsync array-like length 变更**：新增映射期间删除 length、索引 getter 内缩短 length 2 条异步用例；差分 805→807/807 |
| 2026-11-11 | **S2.2-B248~B249 Array.fromAsync mapper thenable**：新增 thenable 解析、then getter 抛错 2 条异步用例；差分 807→809/809 |
| 2026-11-11 | **S2.2-B250~B251 Array.fromAsync thenable 边界**：新增 then 非函数保留原对象、thenable 多次 resolve 取首值 2 条异步用例；差分 809→811/811 |
| 2026-11-11 | **S2.2-B252~B253 Array.fromAsync 构造器检测边界**：新增 prototype getter 抛错、箭头函数 this 回退数组 2 条异步用例；差分 811→813/813 |
| 2026-11-11 | **S2.2-B254~B255 Array.fromAsync 构造器原始值返回**：新增返回数字、返回 null 时使用默认实例 2 条异步用例；差分 813→815/815 |
| 2026-11-11 | **S2.2-B256~B257 Array.fromAsync 已有索引属性**：新增已有访问器索引写入失败、已有可写索引被覆盖 2 条异步用例；差分 815→817/817 |
| 2026-11-11 | **S2.2-B258~B259 Array.fromAsync 索引描述符矩阵**：新增可配置不可写同值可重定义、不可配置可写索引写入失败 2 条异步用例；差分 817→819/819 |
| 2026-11-11 | **S2.2-B260~B261 Array.fromAsync length 描述符与 setter**：新增不可配置可写 length、length setter 接收最终长度 2 条异步用例；差分 819→821/821 |
| 2026-11-11 | **S2.2-B262~B263 Array.fromAsync 数组子类 this**：新增同步与异步迭代器输入均得到子类实例 2 条异步用例；差分 821→823/823 |
| 2026-11-11 | **S2.2-B264~B265 Array.fromAsync species 与 Array this**：新增子类 species 不读取、this=Array 得到普通数组 2 条异步用例；差分 823→825/825 |
| 2026-11-11 | **S2.2-B266~B267 Array.fromAsync 绑定函数 this**：新增可构造绑定函数使用目标结果、绑定箭头函数回退数组 2 条异步用例；差分 825→827/827 |
| 2026-11-11 | **S2.2-B268~B269 Array.fromAsync Proxy 构造失败**：新增 construct 陷阱返回原始值、陷阱抛错 2 条异步用例；差分 827→829/829 |
| 2026-11-11 | **S2.2-B270~B271 Array.fromAsync 撤销 Proxy 与原型陷阱**：新增撤销构造器、construct 内 prototype 读取抛错 2 条异步用例；差分 829→831/831 |
| 2026-11-11 | **S2.2-B272~B273 Array.fromAsync 内建构造器 this**：新增 Date 与 Map 作为 this 时写入 length/索引 2 条异步用例；差分 831→833/833 |
| 2026-11-11 | **S2.2-B274~B275 Array.fromAsync String 包装对象 length**：新增 String 作为 this 时 length 写入失败拒绝 TypeError 1 条核心单测；差分新增 Promise this 1 条至 834/834，核心 1157/1141，并记录 Node 静默完成差异 |
| 2026-11-11 | **S2.2-B276~B277 Array.fromAsync Boolean/Number 包装对象**：新增 Boolean 与 Number 作为 this 时写入 length/索引 2 条异步用例；差分 834→836/836 |
| 2026-11-11 | **S2.2-B278~B279 Array.fromAsync BigInt/Symbol this**：新增两者作为构造器时均以 TypeError 拒绝 2 条异步用例；差分 836→838/838 |
| 2026-11-11 | **S2.2-B280~B281 Array.fromAsync mapper thisArg**：新增显式 thisArg 与省略时默认全局 this 2 条异步用例；差分 838→840/840 |
| 2026-11-11 | **S2.2-B282~B283 Array.fromAsync 原始值 thisArg**：新增非严格包装 Number、严格模式保留原始值 2 条异步用例；差分 840→842/842 |
| 2026-11-11 | **S2.2-B284~B285 Array.fromAsync 多余参数与 null mapper**：新增多余参数忽略、mapper 为 null 时拒绝 TypeError 2 条异步用例；差分 842→844/844 |
| 2026-11-11 | **S2.2-B286~B287 Array.fromAsync mapper 可调用性**：新增对象非可调用拒绝、mapper 为 undefined 时直接收集 2 条异步用例；差分 844→846/846 |
| 2026-11-11 | **S2.2-B289~B290 Array.fromAsync 字符串与 -0 length**：新增 length 为字符串时 ToLength、length 为 -0 时为空 2 条异步用例；差分 846→848/848 |
| 2026-11-11 | **S2.2-B291~B292 Array.fromAsync NaN 与布尔 length**：新增 NaN 转为 0、true 转为 1 2 条异步用例；差分 848→850/850 |
| 2026-11-11 | **S2.2-B293~B294 Array.fromAsync 数组与对象 length**：新增 `[2]` 转为 2、`{}` 转为 0 2 条异步用例；差分 850→852/852 |
| 2026-11-11 | **S2.2-B295~B296 Array.fromAsync 多元素数组与十六进制 length**：新增 `[1,2]` 转为 NaN/0、`'0x2'` 转为 2 2 条异步用例；差分 852→854/854 |
| 2026-11-11 | **S2.2-B297~B298 Array.fromAsync 负数字符串与小数 length**：新增 `' -2 '` 转为 0、`'2.9'` 截断为 2 2 条异步用例；差分 854→856/856 |
| 2026-11-11 | **S2.2-B299~B300 Array.fromAsync 空白字符串 length**：新增空格与制表符字符串转为 0 2 条异步用例；差分 856→858/858 |
| 2026-11-11 | **S2.2-B301~B302 Array.fromAsync 空/含 null 数组 length**：新增 `[]` 与 `[null]` 均转为 0 2 条异步用例；差分 858→860/860 |
| 2026-11-11 | **S2.2-B303~B304 Array.fromAsync 二进制与八进制字符串 length**：新增 `'0b10'` 与 `'0o2'` 均转为 2 2 条异步用例；差分 860→862/862 |
| 2026-11-11 | **S2.2-B305~B306 Array.fromAsync 带空白十六进制与数字分隔符 length**：新增 `' 0x10 '` 转为 16、`'1_0'` 转为 NaN/0 2 条异步用例；差分 862→864/864 |
| 2026-11-11 | **S2.2-B307~B308 Array.fromAsync 非法十六进制 length**：新增 `'0x'` 与 `'-0x10'` 均转为 NaN/0 2 条异步用例；差分 864→866/866 |
| 2026-11-11 | **S2.2-B309~B310 Array.fromAsync 非法二进制与八进制 length**：新增 `'0b2'` 与 `'0o8'` 均转为 NaN/0 2 条异步用例；差分 866→868/868 |
| 2026-11-11 | **S2.2-B311~B312 Array.fromAsync 不完整二进制与八进制 length**：新增 `'0b'` 与 `'0o'` 均转为 NaN/0 2 条异步用例；差分 868→870/870 |
| 2026-11-11 | **S2.2-B313~B314 Array.fromAsync 带空白二进制与换行八进制 length**：新增 `' 0b10 '` 与 `'0o2\\n'` 均转为 2 2 条异步用例；差分 870→872/872 |
| 2026-11-11 | **S2.2-B315~B316 Array.fromAsync 数组索引 getter**：新增数组索引 getter 正常取值、getter 抛错拒绝 2 条异步用例；差分 872→874/874 |
| 2026-11-11 | **S2.2-B317~B318 Array.fromAsync 数组继承索引**：新增原型链索引与空洞继承索引 2 条异步用例；差分 874→876/876 |
| 2026-11-11 | **S2.2-B319~B320 Array.fromAsync 数组迭代器覆盖**：新增自有迭代器优先、Symbol.iterator 为 null 时回退 array-like 2 条异步用例；差分 876→878/878 |
| 2026-11-11 | **S2.2-B321~B322 Array.fromAsync 异步迭代器为 null 的回退**：新增回退同步迭代器、回退 array-like 2 条异步用例；差分 878→880/880 |
| 2026-11-11 | **S2.2-B323~B324 Array.fromAsync 异步迭代器为 undefined**：新增回退同步迭代器、同步迭代器 getter 抛错 2 条异步用例；差分 880→882/882 |
| 2026-11-11 | **S2.2-B325~B326 Array.fromAsync 迭代器 getter 优先级**：新增异步迭代器存在时不读同步 getter、异步 getter 抛错先于同步 2 条异步用例；差分 882→884/884 |
| 2026-11-11 | **S2.2-B327~B328 Array.fromAsync 异步 next 空结果**：新增解析为 undefined、null 均拒绝 TypeError 2 条异步用例；差分 884→886/886 |
| 2026-11-11 | **S2.2-B329~B330 Array.fromAsync 同步 next 空结果**：新增返回 undefined、null 均拒绝 TypeError 2 条异步用例；差分 886→888/888 |
| 2026-11-11 | **S2.2-B331~B332 Object.fromEntries 迭代器方法为 null/非函数**：新增两者均抛 TypeError 2 条同步用例；差分 888→890/890 |
| 2026-11-11 | **S2.2-B333~B334 Array.fromAsync null thisArg**：新增非严格回退全局、严格保留 null 2 条异步用例；差分 890→892/892 |
| 2026-11-11 | **S2.2-B335~B336 Array.fromAsync undefined thisArg**：新增非严格回退全局、严格保留 undefined 2 条异步用例；差分 892→894/894 |
| 2026-11-11 | **S2.2-B337~B338 Array.fromAsync async mapper**：新增 async mapper 正常返回与抛错 2 条异步用例；差分 894→896/896 |
| 2026-11-11 | **S2.2-B339~B340 Array.fromAsync async mapper thisArg 与拒绝**：新增 async mapper thisArg、返回 rejected Promise 2 条异步用例；差分 896→898/898 |
| 2026-11-11 | **S2.2-B341~B342 Array.fromAsync generator mapper**：新增同步与异步生成器对象不被 thenable 展开 2 条异步用例；差分 898→900/900 |
| 2026-11-11 | **S2.2-B343~B344 Array.fromAsync Symbol/BigInt mapper 结果**：新增两种原始值均原样保留 2 条异步用例；差分 900→902/902 |
| 2026-11-11 | **S2.2-B345~B346 Array.fromAsync 同步值 thenable 与拒绝关闭**：新增值 thenable 解析 1 条异步用例（差分 902→903/903）；新增同步值拒绝只关闭一次核心单测，核心 1158/1142，并记录 Node 调用两次 return 的差异 |
| 2026-11-11 | **S2.2-B347~B348 Array.fromAsync 异步值不再 await**：新增 rejected Promise 与 thenable 值均保留原对象 2 条异步用例；差分 903→905/905 |
| 2026-11-11 | **S6.2 门禁复跑（905 差分基线）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 905/905、真实库 375/375，strict 全绿 |
| 2026-11-11 | **S2.2-B350~B351 Array.fromAsync done 的 ToBoolean**：新增 `done: 0` 继续、`done: 'false'` 停止 2 条异步用例；差分 905→907/907 |
| 2026-11-11 | **S2.2-B352~B353 Array.fromAsync done 的 nullish 值**：新增 `done: null` 与 `done: undefined` 均继续 2 条异步用例；差分 907→909/909 |
| 2026-11-11 | **S2.2-B354~B355 Array.fromAsync done 的对象值**：新增 `{}` 与 `[]` 均为 truthy 并停止 2 条异步用例；差分 909→911/911 |
| 2026-11-11 | **S2.2-B356~B357 Array.fromAsync done 的 Symbol/BigInt**：新增 Symbol 为 truthy、0n 为 falsy 2 条异步用例；差分 911→913/913 |
| 2026-11-11 | **S2.2-B358~B359 Array.fromAsync done 的 NaN 与 -0**：新增两者均为 falsy 并继续 2 条异步用例；差分 913→915/915 |
| 2026-11-11 | **S2.2-B360~B361 Array.fromAsync done 的空串与 '0'**：新增空串继续、'0' 停止 2 条异步用例；差分 915→917/917 |
| 2026-11-11 | **S2.2-B362~B363 Array.fromAsync done 的函数值**：新增普通函数与箭头函数均为 truthy 并停止 2 条异步用例；差分 917→919/919 |
| 2026-11-11 | **S2.2-B364~B365 Array.fromAsync 缺失 value 与 getter 次数**：新增缺失 value 视为 undefined、value getter 只读一次 2 条异步用例；差分 919→921/921 |
| 2026-11-11 | **S6.2 门禁复跑（846 差分基线）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 846/846、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B22 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 640/640、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B19 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 638/638、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B18 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 638/638、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B14 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 633/633、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B13 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 627/627、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B11 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 625/625、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B10 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 617/617、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B9 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 610/610、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B8 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 606/606、真实库 375/375，strict 全绿 |
| 2026-11-10 | **S6.2 门禁复跑（B7 后）**：ES5 15,248/15,248、ES2015 61,786/61,786（跳过 324）、parser 脚本 1,907/1,907 + 模块 262/262、差分 599/599、真实库 375/375，strict 全绿 |
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
