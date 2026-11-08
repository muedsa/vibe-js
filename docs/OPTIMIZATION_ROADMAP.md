# 后续路线（未完成工作）

> 更新：2026-11-08。代码基线：`1f8fa3d`。
>
> 本文只列**尚未完成**的工作。已实现能力只作为后续工作的依赖与不可破坏契约出现在文中，不再逐项重复；已完成项不再作为任务列出。

## 1. 当前基线

### 1.1 验证数字

| 检查 | 当前值 | 说明 |
|---|---|---|
| core JVM 单元测试 | **990 / 990** | 2026-11-08 本地运行 |
| core JS / Wasm（Node） | **881 / 881**（上次记录） | 新增 commonTest 后未复跑；以 JVM 990 为当前参考 |
| host JVM / JS / Wasm | 各 **25 / 25** | 最近记录 |
| conformance 单元测试 | **39 / 39** | ParserConformanceTest 6 + RunSupportTest 10 + Test262SelectionTest 19 + RunMetadataTest 4 |
| parser 脚本 | **1,907 / 1,907**；fail 674 / 682（允许 8）；early 5 / 605（允许 5） | pass / pass-explicit 各 1,907，允许 0 |
| parser 模块 | pass-module **76 / 76**、pass-explicit-module **76 / 76**、fail-module **47 / 47**、early-module **63 / 63**（拒绝） | 262 个 `.module.js`，允许列表 0 条 |
| Test262 ES5 | **15,248 / 15,248**，失败 0，跳过 0 | 选中 8,088 个文件 |
| Test262 ES2015 | **61,786 / 61,786**，失败 0，崩溃 0 | 选中 32,293；跳过 **324**（module flag 322 + `$262`/`print` 2） |
| Node 差分 | **545 / 545** | 含 L 系列同步 45 + 异步 8 |
| 真实库 UMD 冒烟 | **320 / 320**（83 个库） | 含 mobx / immer / regenerator-runtime |

> 数字口径以 [CONFORMANCE.md](CONFORMANCE.md) 为准；本表只用于说明路线起点。
> JS/Wasm 的 881 是上次记录时点的旧值，新增 L4/R1/M2 用例后尚未复跑，不能据此宣称跨平台全绿。

### 1.2 已实现能力摘要

- **语言**：ES5 + ES2015 常用语法（class、箭头函数、解构、模板/标签模板、默认参数、rest、`let`/`const`、`for-of`、Symbol、`async/await`），以及 `using` / `await using`、严格模式尾调用优化。
- **生成器与异步迭代**：同步/异步 Generator、`yield` / `yield*`、`for await...of`、`%GeneratorPrototype%` / `%AsyncGeneratorPrototype%` / `%AsyncIteratorPrototype%`。
- **元编程**：Proxy 13 个 trap、`Proxy.revocable`、不变量校验；Reflect 13 个方法。
- **二进制与数值**：BigInt、11 种 TypedArray、ArrayBuffer、DataView、detach 语义；固定小端序。
- **集合与内建**：Iterator helpers、DisposableStack / AsyncDisposableStack、Promise 组合、`RegExp.escape`、ES2025 Set 关系方法等。
- **模块**：`Parser.parseModule()` 可解析 `import` / `export` 基础形式、`import.meta`、`import()` 表达式、模块顶层 `await` 语法，并预计算 `requestedModules`、`ImportEntry` / `ExportEntry` 等静态表；**不能链接、求值或加载**。
- **资源约束**：`EngineLimits` 已覆盖源码长度、嵌套深度、AST 节点、对象数量、JSON 深度、正则步数、排队任务、单轮微任务等预算；仍不是完整的进程级隔离。

### 1.3 当前边界（一句话）

模块执行（链接、求值、加载、CLI、Test262 module harness）未实现；顶层 await 只有语法没有执行；TextEncoder/TextDecoder、structuredClone、URL 子集等宿主生态未实现；WeakRef/FinalizationRegistry 未实现；Intl、真实 DOM/Web API、SharedArrayBuffer/Atomics、resizable/transfer ArrayBuffer 不在当前范围；性能优化 B2 与 API 契约 PKG1 延后。

## 2. 优先级总览

| 顺序 | 编号 | 工作 | 人日 | 依赖 | 触发条件 |
|---:|---|---|---:|---|---|
| 1 | L4.2 | 模块链接（Module Record / 静态解析） | 8–14 | 模块解析与静态表已完成 | 需要多文件模块工程或启用 Test262 module 链接用例 |
| 2 | L4.3 | 模块求值（live binding / 命名空间） | 6–12 | L4.2 | 链接稳定后 |
| 3 | L4.4 | 模块加载器抽象与宿主加载器 | 3–5 | L4.3 | 需要从文件/URL 加载模块 |
| 4 | L4.4b | 模块 CLI | 2–4 | L4.4 | 需要命令行运行 `.mjs` |
| 5 | L4.6b | Test262 module harness | 3–5 | L4.2 / L4.3 | 模块执行稳定后纳入门禁 |
| 6 | L4.5 | 宿主生态（TextEncoder/TextDecoder、structuredClone、URL 子集） | 3–6 | 无（可与 L4.2 并行） | 宿主或真实库需要 |
| 7 | L4.7 | 顶层 await（可选） | +8–15 | L4.3 | 真实模块使用 TLA；否则延后 |

**P0 主体（L4.2–L4.6b）合计约 22–40 人日；L4.5 可并行；L4.7 为可选项，不计入默认排期。**

P1（按场景触发/质量）与长期项见第 4、5 节。

## 3. P0：模块执行与宿主生态

### 3.1 L4.2 模块链接

- **范围**：把 `ModuleProgram` 的静态表提升为可链接的 Module Record；按 `requestedModules` 递归解析依赖；实现 `ImportEntry` / `ExportEntry` 的解析与 `ResolveExport`；处理循环依赖、重复导入、导出名冲突与歧义；构造模块命名空间对象的链接元数据；补齐链接阶段早期错误。
- **依赖**：模块声明解析、模块表达式语法与模块语料门禁已完成；可先用内存中的测试模块集，不要求 L4.4 的文件加载器。
- **人日**：8–14。
- **风险**：循环依赖与 `ResolveExport` 的规范细节多；同一模块重复请求、`default` / 命名 / 星号导出的歧义容易漏；错误抛出顺序会直接影响 Test262；解析器尚未覆盖任意模块命名空间名、import attributes 等扩展。
- **验收标准**：多模块图可完成链接；循环依赖、重复导出、歧义导出有回归用例；脚本解析路径零改动；模块语料门禁保持全绿；Test262 module 链接用例可按子集启用。
- **触发条件**：出现多文件 ES Module 工程需求，或准备启用 Test262 module 执行用例。

### 3.2 L4.3 模块求值

- **范围**：模块环境（模块顶层 `this` 为 `undefined`、恒严格模式）、导入绑定的只读 live binding、导出绑定的初始化与更新、`export default` 的求值顺序、模块命名空间对象、`import.meta` 元数据对象；模块求值错误与部分求值后的状态；为 L4.7 预留异步求值边界。
- **依赖**：L4.2。
- **人日**：6–12。
- **风险**：live binding 与 TDZ 的交互；匿名默认导出的 NamedEvaluation；命名空间对象的 exotic 行为；模块求值失败后的缓存与重试语义；若同时做顶层 await，复杂度会显著上升。
- **验收标准**：可执行多模块图；导入绑定在导出方更新后可见；`default`、命名导出、命名空间导入有回归；模块执行不改变 Script 的 `this` / 严格模式语义；Test262 module 执行子集通过或显式跳过。
- **触发条件**：L4.2 链接稳定，且确实需要运行模块。

### 3.3 L4.4 模块加载器

- **范围**：核心提供 `ModuleLoader` / 模块解析（resolver）抽象，宿主注入实际 IO；实现模块缓存、说明符解析、循环加载与错误传播；官方文件/URL 加载器放在 `:vibe-js-host`，核心不引用平台 API。
- **依赖**：L4.3；`:vibe-js-host` 扩展点。
- **人日**：3–5。
- **风险**：路径/URL 规范化跨平台差异；缓存键与循环依赖；IO 错误如何映射为 JS 错误；安全边界（任意文件读取）需要宿主决定；核心不得引入平台 API。
- **验收标准**：核心只有抽象与纯 Kotlin 默认实现；宿主提供文件加载器；缓存、循环依赖、缺失模块、解析失败有测试；核心 `commonMain` 不出现平台 IO。
- **触发条件**：需要从磁盘/URL 加载模块，而不是只执行内存中的模块图。

### 3.4 L4.4b 模块 CLI

- **范围**：提供运行模块入口的最小 CLI（解析命令行参数、选择入口模块、设置退出码、输出未捕获错误）；CLI 与文件 IO 放在宿主侧，核心不新增平台依赖。
- **依赖**：L4.4。
- **人日**：2–4。
- **风险**：CLI 打包与发布方式；Windows 路径与编码；错误输出格式；不要为了 CLI 把 IO 塞进核心。
- **验收标准**：可从命令行运行 `.mjs` 入口；模块解析/求值错误有明确退出码；帮助与示例文档可用；核心依赖不增加。
- **触发条件**：需要开发者工具或演示入口。

### 3.5 L4.6b Test262 module harness

- **范围**：为 Test262 module 用例提供模块加载与 `$262` 模块钩子；按 `flags: [module]` 启用模块执行用例；把 module 链接/求值失败分类写入报告；用显式 allowlist 处理语料/规范差异，禁止宽泛文本排除。
- **依赖**：L4.2、L4.3；parser 模块语料门禁已完成。
- **人日**：3–5。
- **风险**：module harness 与 Script harness 的 `$262` 语义不同；模块用例依赖宿主加载器；启用后 322 个跳过项会变成真实失败面；容易用扩大跳过换取绿色。
- **验收标准**：module 用例真实执行；通过/失败/跳过分类可审计；module allowlist 逐条路径 + 原因；`-Pstrict` 下 module 门禁失败即红；已实现特性不再被文本排除。
- **触发条件**：L4.3 稳定，准备把模块执行纳入 CI 门禁。

### 3.6 L4.5 宿主生态

- **范围**：在 `:vibe-js-host` 提供 TextEncoder/TextDecoder（UTF-8）、structuredClone（结构化克隆的常用子集）、URL/URLSearchParams 的实用子集（解析、序列化、常用查询参数操作）；核心只保留通用值模型与扩展点。
- **依赖**：无，可与 L4.2 并行；需要宿主模块现有 `BuiltinModule` 机制。
- **人日**：3–6。
- **风险**：UTF-8 孤立代理项/BOM 等边界；structuredClone 的循环引用、不可克隆类型与 transfer 语义；URL 规范范围大，容易失控；平台差异必须留在宿主侧。
- **验收标准**：宿主测试覆盖编码/解码、循环克隆、URL 解析与序列化；核心无平台 API；文档明确支持的子集与不支持项。
- **触发条件**：真实库或宿主产品需要浏览器风格 API。

### 3.7 L4.7 顶层 await（可选）

- **范围**：模块顶层 `await` 的异步求值、`[[AsyncEvaluation]]` 排序、异步依赖图与循环、`import()` 等待模块求值完成；只在模块目标生效。
- **依赖**：L4.3。
- **人日**：+8–15（可选，不计入默认排期）。
- **风险**：异步模块图的求值顺序与死锁；与事件循环/微任务队列耦合；循环依赖 + TLA 的规范行为复杂；失败传播与缓存状态。
- **验收标准**：TLA 用例通过；同步模块行为不变；循环依赖与拒绝路径有回归；未实现时文档明确标注“仅语法”。
- **触发条件**：真实模块使用顶层 await；否则延后。

### 3.8 建议顺序

1. L4.2 链接（先内存模块图，不依赖 IO）。
2. L4.3 求值（先同步模块，TLA 留到 L4.7）。
3. L4.4 加载器 + L4.4b CLI（宿主侧）。
4. L4.6b Test262 module harness（与 L4.3 后期并行准备）。
5. L4.5 可全程并行；L4.7 按真实需求决定是否插入。

## 4. P1：按场景触发与质量项

### 4.1 R1 第三批（可选）

- **范围**：统一资源约束的计量口径（源码/AST/对象/微任务/正则/排队任务），补齐跨平台一致性（JVM/JS/Wasm 的计数与异常分类），完善 `ResourceLimitException` 的分类与文档；评估 `JSArguments`、解释器合成节点等未计数项。
- **依赖**：现有 `EngineLimits` 与 `ResourceLimitsTest` 设施。
- **人日**：5–10（待细化）。
- **风险**：默认限制可能误伤真实库；不同平台计数差异；异常分类变化影响宿主；计量开销影响性能。
- **验收标准**：每类限制都有文档、默认值与回归；跨平台计数一致或有明确说明；超限异常分类稳定；不降低现有默认能力。
- **触发条件**：需要执行不受信任脚本，或宿主需要可配置的资源边界。

### 4.2 S2 扩展（持续覆盖）

- **范围**：随新特性/修复补充 Node 差分与真实库用例；每批增加 3–5 个无 DOM 依赖的 UMD 库；覆盖 Generator、BigInt、TypedArray/AB/DV、Proxy/Reflect、async generator/for-await 的新边界；保持报告元数据与用例数同步。
- **依赖**：当前 545/545、320/320 基线。
- **人日**：2–4 / 批。
- **风险**：Node/V8 与规范允许差异；库下载不稳定；把实现差异写成“期望行为”；用例膨胀导致运行时间上升。
- **验收标准**：`-Pstrict` 全绿；新增用例确实覆盖目标能力；失败分类可审计；不通过删除用例或放宽断言换绿。
- **触发条件**：新特性落地、线上回归、或每季度覆盖维护。

### 4.3 性能复测与 B2 恢复评估

- **范围**：复用 S5 流程重跑 B1 七组基准，记录 JDK/OS/CPU/内存/GC 与 commit；补充 Generator/BigInt/TypedArray/Proxy 专项基准；对比 B1/S5，判断是否恢复 B2。
- **依赖**：S5 已有复测记录；代码进入相对稳定期。
- **人日**：1–2 / 次复测（B2 本身见 §5.2）。
- **风险**：基准噪声与机器差异；只做纵向对比，不能跨环境下结论；优化前先确认热点。
- **验收标准**：报告落盘、环境可复现、结论标注“仅纵向对比”；专项基准覆盖缺失的四类能力；B2 是否恢复有明确结论。
- **触发条件**：B2 恢复前、重大运行时改动后、或性能问题反馈。

### 4.4 门禁维护（S6 后续项）

- **范围**：保持 parser（脚本 + 模块）、Test262 ES5/ES2015、真实库、差分的 `-Pstrict` 门禁；清理报告/注释中的历史文案；维护 allowlist 与失败分类；确保 CI 的 PR 快速子集与完整 Job 都使用当前命令。
- **依赖**：现有 `Conformance` 工作流与 allowlist。
- **人日**：1–2 / 批。
- **风险**：语料下载失败造成假红；allowlist 漂移；报告文案与代码能力再次脱节；PR 子集覆盖不足。
- **验收标准**：所有 strict 门禁通过；module allowlist 保持逐条可审计；报告不含已删除 harness 的描述；失败诊断能定位到用例。
- **触发条件**：CI/报告变更、语料升级、或发现文档与门禁不一致。

## 5. 长期/延后

### 5.1 M2 真实弱引用（暂缓）

- **范围**：采用 C2 方案——`HostEnvironment` 注入 `WeakKeySupport`，核心 WeakMap/WeakSet 委托给宿主实现，默认保持强引用降级；JVM 先做 MVP，其他平台后续补齐。
- **依赖**：M2 可行性验证已完成；需要扩展 `HostEnvironment` 与宿主模块。
- **人日**：JVM MVP **12–18**；全平台 **30–45**。
- **风险**：ephemeron 不动点与值→键循环；JS 目标需要 `external`，与“核心无平台 API”约束冲突，必须放宿主；API 兼容与默认降级；长期内存行为难测试。
- **验收标准**：JVM 上键不可达后条目可回收；WeakMap/WeakSet 的 get/set/has/delete/迭代语义不回归；无宿主实现时保持强引用并有文档；跨平台测试可重复。
- **触发条件**：长期宿主出现 WeakMap/WeakSet 内存增长，或产品明确要求 GC 弱语义。

### 5.2 B2 性能优化（延后）

- **范围**：针对 S5 确认的热点做优化：属性读写、数组循环、引擎创建；重点排查内部方法 suspend 化后的全局标识符双查、`getPropertyFrom` / `putProperty` 每层 Proxy/TypedArray 分支、`CoreBuiltins` 安装量；补齐 B1 七组中缺失的 Generator/BigInt/TypedArray/Proxy 专项基准。
- **依赖**：S5 复测数据；性能复测流程（§4.3）。
- **人日**：**25–48**。
- **风险**：语义回归（尤其 Proxy/TypedArray/Generator 热路径）；微优化不可泛化；基准噪声；为性能改变公共 API 或语义不可接受。
- **验收标准**：S5 记录的回归项恢复或有明确解释；新增专项基准；全部单元测试、Test262、真实库与差分保持绿色；不改变语义与公共 API。
- **触发条件**：性能敏感宿主、S5 复测确认回归、或 B2 恢复评估通过。

> S5 汇总（相对 2026-11-04 B1 基线）：`propertyGet` **+12.7%**、`propertySet` **+9.6%**、数组循环 **−19%**、`engineCreate` **+37%**。这些是当前 B2 的输入，不是已完成的优化结论。

### 5.3 PKG1.2–1.6 API 契约（延后）

- **范围**：explicitApi、Dokka、二进制兼容校验（BCV）、CHANGELOG、host Android 目标、发布冒烟。
- **依赖**：正式版发布决策。
- **人日**：**7–13**。
- **风险**：公开 API 面变化导致兼容性负担；Android 目标与宿主模块边界；文档与发布流程。
- **验收标准**：API dump/BCV 通过；Dokka 可生成；CHANGELOG 与版本策略落地；发布冒烟覆盖 core/host。
- **触发条件**：准备发布首个正式版。

### 5.4 不做/范围外

以下能力**不排期**；除非需求方明确改变范围，否则不进入路线图：

| 能力 | 状态 | 说明 |
|---|---|---|
| Intl | 不做 | 数字/日期/排序本地化不提供；`toLocaleString` 保持简化行为 |
| 真实 DOM / Web API | 不做 | fetch、localStorage、document 等由宿主自行提供；`:vibe-js-host` 只做通用能力 |
| SharedArrayBuffer / Atomics | 不做 | 单线程模型不引入共享内存 |
| resizable / transfer ArrayBuffer | 不做 | 只支持固定长度与宿主 detach |
| Temporal / Float16Array / 装饰器 / ShadowRealm | 不做 | 不在当前语言范围 |
| import attributes / `import.meta.resolve` / `export using` | 暂不排期 | 首批模块执行只承诺 ES2015 基础子集；出现真实用例再评估 |
| 顶层 await | 可选 | 见 L4.7；未实现前只有语法解析 |
| WeakRef / FinalizationRegistry | 暂缓 | 属于 M2 弱引用能力；当前只有可行性结论 |

## 6. 验证命令

在仓库根目录用 PowerShell 执行。新增行为优先写 commonTest；测试方法名使用中文字符串与 kotlin-test 断言。
**不要通过删除既有用例、扩大无理由 allowlist 或降低默认调用深度来换取通过。**

```powershell
# 核心与宿主常规验证
.\gradlew :vibe-js-core:jvmTest :vibe-js-host:jvmTest :vibe-js-conformance:test --continue --console=plain

# 其他目标（涉及值模型/容器/调度改动时）
.\gradlew :vibe-js-core:jsNodeTest :vibe-js-core:wasmJsNodeTest :vibe-js-host:jsNodeTest :vibe-js-host:wasmJsNodeTest --continue --console=plain

# parser 门禁（脚本 + 模块语料都在同一任务内）
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

## 7. 不可破坏契约

已完成能力留下的约束；后续任何改动都必须保持。

| 领域 | 约束 |
|---|---|
| 脚本/模块解析隔离 | `Parser.parseProgram()` 的脚本路径保持不变；`Parser.parseModule()` 独立；`ModuleItem` 不继承 `Statement`；脚本目标继续拒绝 `import` / `export` |
| 模块目标语义 | 模块恒为严格模式；禁用 Annex B HTML 注释与 Annex B 语法；`moduleContext` 必须传播到模板 `${...}` 子解析器；`import.meta` / `import()` 只在模块目标合法 |
| 模块执行边界 | 当前只有解析；`Engine` 没有模块执行入口；`ImportMetaExpr` / `ImportCallExpr` 执行期按未支持报错；在 L4.2/L4.3 完成前不得宣称可执行 |
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
| 文档口径 | 未实现能力必须标注“未实现”；已实现能力不得写成“明确排除”；数字必须与 §1.1 和 [CONFORMANCE.md](CONFORMANCE.md) 一致 |
| 发布 | `0.0.0-SNAPSHOT`；GitHub Packages 发布矩阵与 README 消费说明不变 |

## 8. 风险与约束

1. **模块执行是剩余工作中语义最重的一块**：链接/求值/加载会引入模块图、live binding、命名空间对象与循环依赖；先做 L4.2，再做 L4.3，不要用“能解析”代替“能执行”。
2. **模块解析仍有已知宽松点**：`import()` 零参数在语法层暂允许（执行期拒绝）；任意函数体 `await` 暂宽松；import attributes、`import.meta.resolve`、`export using` 未覆盖。启动 L4.2 前应决定“先修正语法”还是“在执行层显式拒绝”。
3. **Test262 规模**：ES2015 当前 61,786 次执行、本地约 110 秒；module 用例启用后还会增加；CI 需评估并行度与超时，但不能用扩大排除换绿。
4. **Generator / async generator 语义最复杂**：通道驱动、async 队列与 Promise 落定顺序耦合；任何改动必须跑 Generator/AsyncGenerator/ForAwait 与 Test262 三重回归。
5. **Proxy 不变量跨内建**：JSON、Array、`with`、`Function.prototype.toString` 等路径必须继续走内部方法；新增内建不得直接读 `JSObject` 存储。
6. **detach 语义跨 ArrayBuffer/DataView/TypedArray**：分离后所有视图的长度、读写、键集合与原型方法需一致。
7. **性能结论不能沿用**：S5 已显示属性读写、数组循环、引擎创建存在回归；B2 恢复前先补专项基准，且只做纵向对比。
8. **平台差异**：JS/Wasm 单线程；Native/Android/Apple 仅 CI 或历史记录，文档结论必须带“本地/CI”标注。
9. **BigInt 内存上限**：`MAX_RESULT_BITS = 2^24`；大数运算可能触发 RangeError，这是规范允许的实现定义限制。
10. **R1 不是进程级隔离**：源码/AST/对象/微任务预算覆盖了解释器内主要路径，但不限制宿主阻塞调用、单次内建内部工作与全部内存。
11. **WeakMap/WeakSet 仍是强引用**：不能当作长期缓存自动清理机制；M2 暂缓。
12. **范围外能力不要混入缺口**：Intl、DOM/Web API、SAB/Atomics、resizable/transfer 等应标注“未实现/不在范围”，不能作为回归处理。

## 9. 关键文件入口

| 区域 | 文件 | 对应工作 |
|---|---|---|
| 模块解析 | [Parser.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/parser/Parser.kt)、[Modules.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/ast/Modules.kt)、[Lexer.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/lexer/Lexer.kt) | L4.2 / L4.3 的输入 |
| 模块测试 | [ParserModuleTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ParserModuleTest.kt)、[ParserConformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/ParserConformance.kt)、`parser-module-allowlist.txt` | 模块语料门禁已接入；L4.6b 扩展 |
| 模块执行（建议新增） | `vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/module/`（Module Record / Link / Evaluate） | L4.2 / L4.3 / L4.4 |
| 模块加载与 CLI（建议新增） | `vibe-js-host/src/commonMain/kotlin/com/muedsa/js/host/`（文件/URL loader、CLI 入口） | L4.4 / L4.4b |
| 引擎与解释器接入 | [Engine.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Engine.kt)、[Interpreter.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Interpreter.kt) | L4.3 |
| Test262 筛选/运行器 | [Test262Selection.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Selection.kt)、[Test262Conformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Conformance.kt) | L4.6b / S2 / 门禁 |
| 差分与真实库 | [DifferentialCases.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/DifferentialCases.kt)、[LSeriesDifferentialCases.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/LSeriesDifferentialCases.kt)、[Libraries.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Libraries.kt) | S2 扩展 |
| 性能 | `vibe-js-core/src/jvmBenchmark/kotlin/` 下基准源码、`vibe-js-core/build/reports/benchmarks/` | 性能复测 / B2 |
| 资源约束 | [EngineLimits.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EngineLimits.kt)、[ResourceLimitsTest.kt](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ResourceLimitsTest.kt) | R1 第三批 |
| 文档 | [README.md](../README.md)、[ARCHITECTURE.md](ARCHITECTURE.md)、[COMPATIBILITY.md](COMPATIBILITY.md)、[CONFORMANCE.md](CONFORMANCE.md)、本文件 | 口径维护 |

## 10. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-08 | **重写为“后续路线（未完成工作）”**：移除已完成项清单，只保留 P0 模块执行与宿主生态、P1 质量项、长期/延后项；基线更新为 `1f8fa3d`；补充每项的范围、依赖、人日、风险、验收与触发条件；加入模块解析/执行边界与不做清单 |
