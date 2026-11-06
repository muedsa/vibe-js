# Vibe JS 路线图

> 重写日期：2026-11-06。代码基线：`6677c14`。
>
> 本文记录**全部在办工作**：原"暂缓/搁置"项已并入，不再有独立分区。P0–P4 只表示执行顺序与投入优先级，不是交付承诺。
> 已完成的 C1–C3、T1、T2、M1、B1、H1、A1、D1 不列入任务清单，其约束收敛到 §2。
>
> 证据口径：
> - 单元测试、parser、ES5、ES2015、差分、真实库：2026-11-06 本地复跑（基线 `6677c14`）；
> - 性能数字：2026-11-04 B1 全量基准（JDK 17、12 核、G1，预热 2×300ms，测量 3–10×300ms）。

## 0. 优先级总览

> 编号约定：顶层任务用字母前缀（`B2`/`PKG1`/`C4`/`R1`/`L1`…），子项统一为 `X.n`（如 `B2.1`、`PKG1.1`、`L1.3`）；顶层编号保持不变，与提交历史、已完成项引用连续。

### P0 · 立即可做（低风险、高杠杆）

| 顺序 | 编号 | 任务 | 人日 | 依据 |
|---:|---|---|---:|---|
| 1 | PKG1.1 | 协程依赖泄漏 | 0.5 | 消费方无法在不额外声明协程依赖的情况下编译 |
| 2 | C4 | 一致性筛选口径 | 1–2 | 一致性数字的可信度依赖筛选可审计 |
| 3 | B2.4 | 引擎创建与解析 | 3–6 | 低风险，基准可直接验证 |

### P1 · 性能主线（数据驱动，按风险从低到高）

| 顺序 | 编号 | 任务 | 人日 | 依据 |
|---:|---|---|---:|---|
| 4 | B2.3 | 事件循环与微任务 | 3–6 | 7.9 µs/微任务，单项成本高、风险中 |
| 5 | B2.5 | 内建热路径 | 4–8 | Array/RegExp/String/JSON 3–4 µs/次，风险中低 |
| 6 | B2.1 | 属性访问与对象存储 | 10–18 | 2.05 µs/次，最大单项收益，风险高 |
| 7 | B2.2 | 环境查找槽位化 | 5–10 | 2.49 µs/次，依赖 Parser 改造，风险高 |

### P2 · API 契约（正式版发布前）

| 顺序 | 编号 | 任务 | 人日 | 依据 |
|---:|---|---|---:|---|
| 8 | PKG1.2 | explicit API | 3–5 | 冻结公共面，防止 API 漂移 |
| 9 | PKG1.3 | Dokka | 1–2 | 公开 API 文档 |
| 10 | PKG1.4 | BCV 与 CHANGELOG | 1–2 | 二进制兼容与变更记录 |
| 11 | PKG1.5 | host Android 目标 | 1–2 | 与 core 目标对齐 |
| 12 | PKG1.6 | 发布冒烟 | 1–2 | 验证 GitHub Packages 消费与快照覆盖 |

> P2 必须在 P1 中会改公共 API 的部分（B2.1/B2.2）之后做，否则 `apiDump` 会反复变更。

### P3 · 语言能力扩展（按解锁面/成本排序）

| 顺序 | 编号 | 任务 | 人日 | 依据 |
|---:|---|---|---:|---|
| 13 | L1.3 | `for await` + AsyncIterator 原型 | 4–8 | 最便宜的语法补全，不需要 Generator |
| 14 | L2.2 + L2.3 | 内部方法 suspend 化 + Proxy | 20–35 | 解锁 Vue 3/immer/mobx；顺带完成内部方法重构 |
| 15 | L2.1 | Reflect 完整 API | 5–10 | Proxy 之后成本最低，避免返工 |
| 16 | L3.1 + L3.2 | ArrayBuffer/DataView + TypedArray | 20–37 | 解锁二进制库与 TypedArray 用例 |
| 17 | L3.3 | BigInt 运行时 | 12–22 | 独立，解锁大整数库 |
| 18 | L1.1 + L1.2 | 同步 Generator + 委托清理 | 15–27 | 解锁 regenerator/co；对解释器侵入最深 |
| 19 | L3.4 | BigInt64Array/BigUint64Array | 3–6 | 依赖 L3.1+L3.2 与 L3.3 |
| 20 | L1.4 | async generator | 6–10 | 依赖 L1.1+L1.2 与 L1.3 |

### P4 · 长期（按场景触发）

| 顺序 | 编号 | 任务 | 人日 | 依据 |
|---:|---|---|---:|---|
| 21 | R1 | 资源约束扩展 | 15–30 | 只有执行不受信任脚本时才成为硬需求 |
| 22 | M2 | 真实弱引用 | 3–5 + 未知 | 只有长期宿主出现内存增长时才需要 |
| 23 | L4 | 模块系统与宿主生态 | 30–60 | 从单脚本目标转向多文件工程 |

**排序依据。** 先修复"发布后立刻产生成本"的问题（P0），再用基准数据改善解释器核心（P1），随后在公共 API 稳定后补齐发布契约（P2），最后按"解锁面/成本"扩展语言能力（P3）；R1/M2/L4 只有在对应场景出现时才值得投入（P4）。

**总投入。** 约 **167–314 人日**（含全部原暂缓/搁置项；P0 4.5–8.5、P1 22–42、P2 7–13、P3 85–155、P4 48–95）。

### 0.1 当前已知缺口（必须如实披露）

1. **Test262 口径**：ES2015 子集是"按已支持特性筛选"的 37,329 次执行，836 个文件被跳过；**不能对外宣称"完整 ES2015"**。
2. **解析门禁保留 85 条允许项**：当前全部是 Generator 语法，`-Pstrict` 下不计为失败；L1.1 落地后应逐步移除。
3. **真实库覆盖面有天花板**：Generator 与 Proxy 是最大的两个兼容缺口，regenerator/co、redux-saga、Vue 3 响应式、immer、mobx 一类库当前无法运行；L1/L2 落地后解除。
4. **资源约束不完整**：不受信任脚本的解析、单次内建调用、正则回溯与内存分配都没有硬预算；R1 落地后解除。

### 0.2 P4 的触发条件

| 项目 | 触发条件 |
|---|---|
| R1 资源约束 | 需要执行**不受信任**的脚本，或出现解析/OOM 类事故 |
| M2 真实弱引用 | 长期存活的宿主进程中 WeakMap/WeakSet 成为可观测的内存增长点 |
| L4 模块 | 需要执行多文件 ES Module 工程 |

## 1. 当前基线

### 1.1 能力概览

- 语法：ES5 全量 + ES2015 常用语法（class、箭头函数、解构、模板、默认/rest、`let`/`const`、`for...of`、Symbol、async/await），并扩展到部分后续年份语法（`using`/`await using`、class 公有/私有字段与静态块、可选 catch 绑定、hashbang）。
- 内建：Object/Function/Array/String/Number/Boolean/Math/JSON/Error/RegExp/Date/Symbol/Map/Set/WeakMap/WeakSet/Promise，以及 Iterator helpers、DisposableStack/AsyncDisposableStack、`Promise.withResolvers`/Keyed 组合、`RegExp.escape`、Set 的 7 个 ES2025 关系方法等。
- 尚未实现（均在办，见 §0）：Generator 与 `for await`、模块系统、Proxy/Reflect、BigInt 运行时、TypedArray/ArrayBuffer。
- 不在范围：Intl、真实 DOM/Web API。
- 架构约束不变：核心全部位于 `commonMain`、不引用平台原生 API；执行方法均为 `suspend`；属性读写走 `Interpreter.getV`/`putV`；类型转换走 `Conversions`；宿主能力通过 `BuiltinModule` 注册。

### 1.2 验证基线

| 检查 | 结果 | 生成时间 | 说明 |
|---|---|---|---|
| core JVM 单元测试 | 610 | 2026-11-06 | 含 JVM 专属并发/超时 6 例 |
| core JS / Wasm / Windows Native | 各 604 | 2026-11-06 | 与 JVM 的差集为 JVM 专属用例 |
| host JVM / JS / Wasm / Native | 各 25 | 2026-11-06 | |
| conformance 单元测试 | 26 | 2026-11-06 | `RunSupportTest` / `Test262SelectionTest` / `RunMetadataTest` |
| parser pass | 1,822 通过 / 85 允许（共 1,907） | 2026-11-06 | 允许项为 Generator 语法 |
| parser fail | 674 通过 / 8 允许（共 682） | 2026-11-06 | |
| parser early | 600 拒绝 / 5 接受（共 605） | 2026-11-06 | 模块用例跳过 262 |
| Test262 ES5 子集 | 15,244 / 15,244 | 2026-11-06 | 选中 8,088 文件，跳过 2 |
| Test262 ES2015 子集 | 37,329 / 37,329 | 2026-11-06 | 选中 20,374 文件，跳过 836 |
| Node 差分 | 492 / 492 | 2026-11-06 | Node v24.12.0 |
| 真实库 UMD 冒烟 | 309 / 309 | 2026-11-06 | 80 个库 |

以上数字是"当前范围内全绿"，**不表示完整 ES2015 或全部库受支持**，口径见 §0.1。

### 1.3 代码规模

| 区域 | 文件数 | 行数 |
|---|---:|---:|
| core + host `commonMain` | 102 | 38,276 |
| core + host `commonTest` / `jvmTest` | 37 | 13,412 |
| `vibe-js-conformance`（main + test） | 17 | 10,750 |

最大单文件：[Interpreter.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Interpreter.kt) 6,276 行、[Parser.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/parser/Parser.kt) 3,807、[JsRegexEngine.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/regex/JsRegexEngine.kt) 2,260、[ArrayBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/ArrayBuiltins.kt) 1,954、[Lexer.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/lexer/Lexer.kt) 1,758、[IteratorBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/IteratorBuiltins.kt) 1,094（helper 已拆到 [IteratorHelperBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/IteratorHelperBuiltins.kt) 616 行）。

### 1.4 性能基线（B1，2026-11-04）

解释器吞吐（`run` 含解析 / 预解析执行）：

| 场景 | `Engine.run` | 预解析 |
|---|---:|---:|
| 算术 `1 + 2 * 3` | ~142,000 ops/s | ~363,000 ops/s |
| 字符串/对象拼接 | ~95,000 ops/s | ~202,000 ops/s |
| 函数调用 | ~95,000 ops/s | ~271,000 ops/s |
| 100 次数组 push 循环 | ~6,200 ops/s | ~7,100 ops/s |

关键单项成本：

| 基准 | 结果 | 折算 |
|---|---:|---:|
| `propertyGet`（1000 次属性读） | 2,051 µs | **~2.05 µs/次** |
| `propertySet`（1000 次属性写） | 2,064 µs | ~2.06 µs/次 |
| `closureLookup`（1000 次闭包变量读） | 2,490 µs | ~2.49 µs/次 |
| `microtaskStorm`（1000 个微任务） | 7,944 µs | **~7.9 µs/个** |
| `promiseChain`（100 个 then） | 852 µs | ~8.5 µs/个 |
| `asyncAwait`（100 次 await） | 552 µs | ~5.5 µs/次 |
| `engineCreate` | 75.7 µs | 单引擎创建 |
| `parseOnly`（100 次循环脚本） | 9.9 µs | 单次解析 |
| `arrayMap`（1000 元素） | 3,001 µs | ~3.0 µs/元素 |
| `regexpExec`（1000 次） | 4,204 µs | ~4.2 µs/次 |
| `stringSlice`（1000 次） | 2,902 µs | ~2.9 µs/次 |
| `jsonParse`（100 次） | 557 µs | ~5.6 µs/文档 |

集合（M1 后）：单键 get 6–11 ns、has 4–6 ns、set 18–19 ns，1k/10k/100k 规模基本持平；`size` 约 0.02 ns/次。

### 1.5 发布状态

- 版本统一为 `0.0.0-SNAPSHOT`（根 [build.gradle.kts](../build.gradle.kts) 的 `allprojects`），`group = com.muedsa.js`。
- `vibe-js-core` / `vibe-js-host` 已接入 `maven-publish`，推送到 GitHub Packages（[publish.yml](../.github/workflows/publish.yml)）：每次分支推送触发，3 个 OS 矩阵覆盖 JVM/JS/Wasm/Android/Linux、Apple 目标、mingwX64。
- 消费方式与鉴权已写入 [README](../README.md#引入依赖)。
- **未发布正式版**；API 契约工具链（explicitApi/Dokka/BCV/CHANGELOG）尚未建立，见 §3.2。

## 2. 不可破坏的契约

已完成项留下的约束；后续任何改动都必须保持。

| 领域 | 约束 |
|---|---|
| 协作式取消与超时 | 解释器检查点观察取消；宿主取消原样传播；JS `catch`/`finally` 不吞宿主中止；剩余微任务丢弃 |
| 执行配额 | `EngineLimits(maxSteps, maxCallDepth = 16_384)`；步数超限抛宿主 `ExecutionLimitException`，深度超限抛 JS `RangeError` |
| 错误栈 | 创建/首次抛出时快照；重抛与 Promise 转发不覆盖原栈；保留万层同步/async 回归 |
| 事件循环生命周期 | `close()`、`pendingTasks`、`runUntilIdle()`、统一恢复队列；同一引擎串行执行；关闭等待清理且不取消宿主父 Job |
| 集合迭代 | 实时语义：遍历中新增可见、删除跳过、重删重加再次访问、`done` 后不复活；`size` 为 O(1)；迭代期间不压缩存储 |
| 宿主计时 | `Date` 用墙钟；`performance.now()` 与 `console.time` 用单调时钟；`timeOrigin` 在模块安装时固定 |
| 发布 | 版本 `0.0.0-SNAPSHOT`；`GITHUB_REPOSITORY` 缺失时不注册仓库；发布任务名与 `GITHUB_ACTOR`/`GITHUB_TOKEN` 约定不变 |
| 文档口径 | 未实现能力必须标注"未实现"；对外数字必须带子集/跳过口径，不得把在办项写成已支持 |

## 3. 任务详情

> 本节按项目组织；执行顺序见 §0。人日为工程工作量粗估（1 人日 ≈ 6–8 小时有效投入），不是日历工期。

### 3.1 B2 · 性能优化（P0–P1，25–48 人日，按子项独立验收）

**现状。** B1 已给出可复现的七组基准（§1.4）。当前解释器是递归 suspend AST 遍历：每次属性访问走 `Interpreter.getV`/`putV`（suspend）→ `JSObject` 属性表（`LinkedHashMap<PropertyKey, PropertyDescriptor>`）→ 原型链；环境查找用字符串 Map + 父链；每个 Promise 反应都经过协程恢复。上述结构直接对应 §1.4 的几类热点。

**建议顺序。** B2.4 → B2.3 → B2.5 → B2.1 → B2.2：风险从低到高，先用前两项积累基准与回归经验。

**总原则。** 每个子项独立提交、独立基准对比、独立验收；**任何子项不得让 ES5/ES2015/差分/真实库任一项基线回退**。不追求一次性重写解释器。

#### B2.1 属性访问与对象存储（10–18 人日，高风险）

- **证据**：`propertyGet` 2.05 µs/次、`propertySet` 2.06 µs/次，是单项成本最高的操作。
- **方案**：对象引入"形状（shape）+ 槽位数组"替代每对象 `LinkedHashMap`；字符串键驻留；`getV`/`putV` 增加单态内联缓存（shape + key → slot），未命中回退现有路径；数组索引属性走专用整数索引路径。
- **范围**：`runtime/value/JSObject.kt`、`PropertyKey`、`Interpreter.getV/putV`、`ArrayBuiltins` 索引路径，约 5–10 文件、800–1,500 行。
- **验收**：`propertyGet`/`propertySet` 至少 2×；属性枚举顺序、`delete`、`defineProperty`、原型链、getter/setter 副作用回归不变；四项一致性基线全绿。
- **风险**：属性顺序与描述符语义是可观察行为，缓存失效与原型变更最容易出错；建议先加"单次访问"微基准再动结构。

#### B2.2 环境查找槽位化（5–10 人日，中高风险）

- **证据**：`closureLookup` 2.49 µs/次（每次 4 个闭包变量读 + 算术）。
- **方案**：Parser 在解析期为每个函数/块预计算声明槽位（复用现有 `varDeclarations`/`functionDeclarations` 机制），运行时环境改为数组槽位 + 父链；`with`、直接 `eval`、全局动态声明回退到字符串 Map。
- **范围**：`runtime/Environment.kt`、`Parser`、`Interpreter`，4–8 文件。
- **验收**：`closureLookup` 至少 1.5×；`with`/`eval`/TDZ/块级作用域/catch 参数回归不变。
- **风险**：动态作用域与提升规则的边界多，建议以差分与 Test262 为门禁。

#### B2.3 事件循环与微任务（3–6 人日，中风险）

- **证据**：1000 个微任务 7.9 ms（7.9 µs/个）；100 个 then 852 µs；100 次 await 552 µs。
- **方案**：微任务批量 drain（一次恢复多个）、Promise 反应队列直接迭代、减少每任务 `launch`/闭包分配、async 恢复复用调度入口。
- **范围**：`runtime/EventLoop.kt`、`runtime/PromiseSupport.kt`，3–5 文件。
- **验收**：`microtaskStorm` 至少 2×；微任务顺序、取消边界与 `close()` 语义由差分异步用例与 Test262 async 用例守护。
- **风险**：调度顺序与取消时机是 P0 契约，改动必须逐条对照现有测试。

#### B2.4 引擎创建与解析（3–6 人日，低风险）

- **证据**：`engineCreate` 75.7 µs；`parseOnly` 9.9 µs；`Engine.run` 与预解析执行相差约 60%，解析占小脚本总成本的一半以上。
- **方案**：内建安装改为惰性/模板复制（共享不可变描述符模板，按引擎实例化）；Lexer token 分配与 Parser 节点构造减少临时对象。
- **范围**：`Engine.kt`、`CoreBuiltins`、`Lexer`、`Parser`。
- **验收**：`engineCreate` 至少 1.5×、`parseOnly` 至少 1.3×；解析一致性基线不变。
- **风险**：低；主要注意内建原型的可修改性与每引擎隔离。

#### B2.5 内建热路径（4–8 人日，中低风险）

- **证据**：`arrayMap` 3.0 µs/元素、`regexpExec` 4.2 µs/次、`stringSlice` 2.9 µs/次、`jsonParse` 5.6 µs/文档。
- **方案**：Array 迭代方法对密集数组走直接元素读取（跳过 `getV`）；RegExp 结果对象复用/减少包装；String `slice` 减少子串复制；JSON 解析器减少中间分配。
- **范围**：`ArrayBuiltins`、`RegExpBuiltins`、`StringBuiltins`、`JsonParser`。
- **验收**：对应基准至少 1.5×；稀疏数组、原型链元素、自定义 `Symbol` 协议、JSON reviver 语义回归不变。

**B2 的停止条件。** 每个子项完成后重新跑全量基准；若某项收益低于 20% 或回归成本过高，记录数据并停止该项，不为了"完成路线"强行合入。

### 3.2 PKG1 · 制品与 API 契约（P0 + P2，7.5–14.5 人日）

**已完成。** `0.0.0-SNAPSHOT` + `maven-publish` + GitHub Packages + 三 OS 矩阵发布 + README 消费说明（§1.5）。

**子项（PKG1.1 在 P0，其余在 P2）：**

| 编号 | 子项 | 证据 | 方案 | 人日 |
|---|---|---|---|---:|
| PKG1.1 | 协程依赖泄漏 | `EventLoop` 公开构造参数与 `scope` 属性暴露 `CoroutineScope`，但 `kotlinx-coroutines-core` 是 `implementation` | 改为 `api(libs.kotlinx.coroutines.core)`；校验发布 POM 的 compile scope | 0.5 |
| PKG1.2 | explicit API | 未启用 `explicitApi()`；公共面没有显式可见性 | 先 core 后 host，逐处补 `public`/`internal`，接入 `apiDump` 防漂移 | 3–5 |
| PKG1.3 | Dokka | 无 API 文档生成 | 接入 `org.jetbrains.dokka`，KDoc 作为公开 API 的说明来源 | 1–2 |
| PKG1.4 | 二进制兼容与变更记录 | 无 BCV、无 CHANGELOG | 接入 `binary-compatibility-validator`（`api/*.api`），建立 CHANGELOG 约定 | 1–2 |
| PKG1.5 | host Android 目标 | core 有 Android，host 没有 | 为 host 增加 Android target，与 core 对齐 | 1–2 |
| PKG1.6 | 发布冒烟 | 只验证过 `publishToMavenLocal`，未验证从 GitHub Packages 消费 | 独立消费工程解析 `0.0.0-SNAPSHOT`（JVM/JS/Wasm/Android），并验证重复发布的快照覆盖行为 | 1–2 |

**验收。** 消费工程能在不额外声明协程依赖的情况下编译；`apiDump` 通过；Dokka 可生成；快照重复发布有明确结论（若 GitHub Packages 拒绝覆盖，改用唯一快照后缀并更新文档）。

**风险。** `explicitApi()` 会一次性冻结公共面，必须排在 B2.1/B2.2 之后（见 §0 P2 注）。

### 3.3 C4 · 一致性筛选口径（P0，1–2 人日）

**现状。** [Test262Selection.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Selection.kt) 对 `$262`、`Array.print`、`propertyBagObserver`、`ArrayBuffer`、BigInt/Proxy/Generator 等仍使用**源码文本匹配**；注释或字符串中出现这些词会造成误排，T2 只能做到把跳过明细落盘供复核。

**方案。**
1. 优先使用 test262 元数据 `features`；仅当 features 缺失或不足以判定时才回退文本匹配；
2. 文本匹配命中但 features 不含对应项时，写入"疑似误排"审计列表，不直接排除；
3. 增加单元测试：注释/字符串中的 `Proxy`、`BigInt` 不触发排除。

**验收。** 选中用例数量变化必须有逐条解释；`test262-skipped.txt` 与审计列表可复核；不降低现有基线。

### 3.4 L1 · Generator / 异步迭代（P3，25–45 人日）

| 编号 | 子项 | 依赖 | 人日 | 交付后可用 | 主要风险 |
|---|---|---|---:|---|---|
| L1.1 | 同步 Generator 核心 | 无 | 10–18 | `function*`、`yield`、`%GeneratorPrototype%`、`return`/`throw` 完成语义 | 当前是递归 suspend 树遍历，需要独立的暂停/恢复执行模型 |
| L1.2 | 委托与清理 | L1.1 | 5–9 | `yield*`、`finally`、`return`/`throw` 传播 | 关闭语义与嵌套委托容易出错 |
| L1.3 | `for await` + AsyncIterator 原型 | 无 | 4–8 | 异步迭代语句与原型（不需要 Generator） | 与现有 `Symbol.asyncIterator` 消费、微任务顺序的交互 |
| L1.4 | async generator | L1.1 + L1.3 | 6–10 | `async function*`、`await` 与 `yield` 交错 | 组合项，不能先做 |

**最小实现集**：只要 `for await` → L1.3（4–8 人日）；要运行 regenerator/co → L1.1 + L1.2（15–27）。

**顺序。** L1.3 在 P3 首位；L1.1 + L1.2 在 P3 末段（对解释器侵入最深）；L1.4 最后。

### 3.5 L2 · Reflect / Proxy（P3，25–45 人日）

| 编号 | 子项 | 依赖 | 人日 | 交付后可用 | 主要风险 |
|---|---|---|---:|---|---|
| L2.1 | Reflect 完整 API | 无 | 5–10 | 13 个 Reflect 方法 | 若先于 Proxy 落地，之后需要 suspend 化或维护双入口 |
| L2.2 | 内部方法 suspend 化 | 无 | 8–15 | 8 个属性内部方法可执行任意 JS | 全仓约 120–160 处调用点，跨切面重构 |
| L2.3 | Proxy | L2.2 | 12–20 | 13 个 trap、可撤销代理、不变量校验 | trap 可执行任意 JS，所有内部方法必须 suspend |

**最小实现集**：只要 Reflect → L2.1（5–10 人日，接受将来改 API）；要运行 Vue 3/immer/mobx → L2.2 + L2.3（20–35）。

**顺序。** L2.2 → L2.3 → L2.1：先完成 suspend 化与 Proxy，再补 Reflect，避免"先 Reflect 后返工"。

### 3.6 L3 · 二进制与 BigInt（P3，35–65 人日）

| 编号 | 子项 | 依赖 | 人日 | 交付后可用 | 主要风险 |
|---|---|---|---:|---|---|
| L3.1 | ArrayBuffer + DataView | 无 | 8–15 | 二进制缓冲区、DataView 读写 | 可分离/可调整大小的缓冲区属于后续规范扩展，首版可不做 |
| L3.2 | TypedArray 数值视图 | L3.1 | 12–22 | 9 种非 BigInt 类型化数组、整数索引 exotic、species/迭代/subarray | 整数索引异质对象与 Array 的索引语义不同，需独立实现 |
| L3.3 | BigInt 运行时 | 无 | 12–22 | 任意精度整数、算术/位运算/转换 | 需自研大整数并保证 JVM/JS/Wasm/Native 一致 |
| L3.4 | BigInt64Array / BigUint64Array | L3.2 + L3.3 | 3–6 | 64 位整数视图 | 组合项，不能单独交付 |

**最小实现集**：只要二进制 → L3.1 + L3.2（20–37 人日）；只要大整数 → L3.3（12–22）；完整 L3 → 35–65。

**顺序。** L3.1 → L3.2；L3.3 可与 L3.2 并行；L3.4 最后。

### 3.7 R1 · 资源约束扩展（P4，15–30 人日）

**现状。** `EngineLimits` 只有 `maxSteps` 与 `maxCallDepth`（默认 16,384）；`Engine.parse` 没有源码大小/嵌套深度上限；`JsonParser` 是纯递归解析；正则引擎没有步数预算；全仓 17 处 `checkExecution`；timer/async 不限制排队任务数量（步数预算只约束执行工作量）。

**方案（三批）。**
1. 解析期：源码长度上限、词法/语法嵌套深度上限、`JsonParser` 深度上限，超限抛明确异常；
2. 执行期：正则匹配步数预算、单次内建调用的协作式检查点；
3. 任务期：事件循环排队任务数量上限、内存/对象数量预算（先确定可接受的计量方式）。

**验收。** 每类超限都有明确异常类型与测试；现有语义与配额行为不变；资源耗尽不得被记成普通断言失败。

**风险。** 正则回溯与内存分配无法用步数表达；预算过严会误伤正常脚本；跨平台内存计量不一致。

### 3.8 M2 · 真实弱引用（P4，可行性验证 3–5 人日 + 完整实现未知）

**现状。** `JSWeakMap : JSMap`、`JSWeakSet : JSSet`，底层与强集合共用数组 + 索引，键值强持有。

**方案。** 先做可行性验证：在 `commonMain` 内实现最小的 ephemeron/可达性模型（不能使用 JVM `WeakHashMap` 或 JS 原生 `WeakMap`），评估对值模型与 GC 时机的影响；验证通过后再估算完整实现。

**验收（验证阶段）。** 能证明"键不可达后条目可被回收"的最小场景；明确跨平台语义差异与 API 影响。

**风险。** 需要自研可达性分析，影响整个值模型；Kotlin 各平台没有统一的弱引用原语。

### 3.9 L4 · 模块系统与宿主生态（P4，30–60 人日）

**现状。** `import`/`export` 是保留字并直接报错；没有 CLI、URL、TextEncoder、structuredClone。

**方案（可独立交付）。** 模块加载与链接（25–50 人日）、CLI（2–4）、宿主生态内建（3–6）。

**验收。** 能执行多文件 ES Module 工程；live binding、循环依赖与错误定位有测试。

**风险。** 链接、live binding、循环依赖与宿主加载器；这是从"单脚本"到"工程运行时"的范围改变。

### 3.10 跨组依赖与返工

- **先 L3.2 后 L2.2**：TypedArray 的 `[[Get]]`/`[[Set]]`/`[[GetOwnProperty]]` 等内部方法需要返工，约 +2–5 人日；Array/String 的 exotic 行为同理。
- **先 L2.1 后 L2.3**：Reflect 的公开 API 需要 suspend 化（或维护同步/异步双入口），约 +1–3 人日。
- **L1 与 L2/L3 正交**：Generator 的核心是执行模型，不依赖属性内部方法；但 L1.1 对解释器侵入最深。
- **必须成组**：L1.1+L1.2、L1.1+L1.3（async generator）、L2.2+L2.3、L3.1+L3.2、L3.2+L3.3（BigInt 视图）；其余可独立交付。

## 4. 批次与投入

| 批次 | 优先级 | 内容 | 人日 | 主要产物 |
|---|---|---|---|---:|
| **1 · 低风险收尾** | P0 | PKG1.1、C4、B2.4 | **4.5–8.5** | 可编译的 API 依赖、可审计筛选、创建/解析基准提升 |
| **2 · 性能主线** | P1 | B2.3、B2.5、B2.1、B2.2 | **22–42** | 解释器核心成本下降 |
| **3 · API 契约** | P2 | PKG1.2–PKG1.6 | **7–13** | 可发布的稳定 API 面 |
| **4 · 语言能力** | P3 | L1.3、L2.2+L2.3、L2.1、L3.1+L3.2、L3.3、L1.1+L1.2、L3.4、L1.4 | **85–155** | 异步迭代、Proxy/Reflect、二进制、BigInt、Generator |
| **5 · 长期** | P4 | R1、M2、L4 | **48–95** | 资源约束、弱引用、模块系统 |

批次 1 可立即开始；批次 2 风险最高的是 B2.1/B2.2，建议在批次 1 积累基准与回归经验后再动；批次 3 必须等批次 2 的公共 API 改动完成。合计约 **167–314 人日**。

## 5. 验证命令

在仓库根目录用 PowerShell 执行。新增行为优先写 commonTest；方法名使用中文字符串与 kotlin-test 断言。**不要通过删除既有用例、扩大无理由 allowlist 或降低默认调用深度来换取通过。**

```powershell
# 核心与宿主常规验证
.\gradlew :vibe-js-core:jvmTest :vibe-js-host:jvmTest :vibe-js-conformance:test --continue --console=plain

# 涉及 commonMain 语义、容器、调度改动时验证其他本机目标
.\gradlew :vibe-js-core:jsNodeTest :vibe-js-host:jsNodeTest :vibe-js-core:wasmJsNodeTest :vibe-js-host:wasmJsNodeTest :vibe-js-core:mingwX64Test :vibe-js-host:mingwX64Test --continue --console=plain

# 三项严格一致性检查
.\gradlew :vibe-js-conformance:parserConformance :vibe-js-conformance:test262Conformance :vibe-js-conformance:test262Es2015Conformance -Pstrict --continue --console=plain

# 真实库与 Node 差分
.\gradlew :vibe-js-conformance:librarySmoke :vibe-js-conformance:differentialConformance -Pstrict --continue --console=plain

# 基准：环境记录 + 全量（约 1.5 分钟）或单组
.\gradlew :vibe-js-core:benchmarkEnvironment :vibe-js-core:jvmBenchmarkBenchmark
.\gradlew :vibe-js-core:jvmBenchmarkBenchmark -PbenchmarkFilter=ObjectEnvironmentBenchmark

# 发布任务校验（dry-run，不需要真实凭据）
$env:GITHUB_REPOSITORY='muedsa/vibe-js'; $env:GITHUB_ACTOR='local'; $env:GITHUB_TOKEN='dummy'
.\gradlew :vibe-js-core:publishJvmPublicationToGitHubPackagesRepository --dry-run
```

## 6. 风险与约束

1. **性能优化不得以语义换速度**：B2 每个子项都必须跑全量一致性基线；属性顺序、访问副作用、原型失效、微任务顺序都是可观察行为。
2. **explicitApi 必须在公共 API 稳定后做**：B2.1/B2.2 可能调整内部类型；先冻结 API 会带来反复的 `apiDump` 变更。
3. **快照发布存在覆盖风险**：GitHub Packages 对 Maven 快照通常允许覆盖，但未在真实仓库验证；若返回 409，改用唯一快照后缀并同步 README。
4. **Test262 文本筛选仍是已知弱点**：C4 只降低误排概率，不能完全消除；任何对外数字都要带"子集/跳过数"口径。
5. **新增能力必须同步口径**：L1/L2/L3 落地后，必须同步更新 [COMPATIBILITY.md](COMPATIBILITY.md)、筛选逻辑与 README，避免报告口径漂移。
6. **P3 每项独立验收**：不做大爆炸式合并；Generator/Proxy/TypedArray 各自完成后单独回归，失败可回退单项。

## 7. 关键文件入口

| 区域 | 文件 | 对应任务 |
|---|---|---|
| 引擎门面与限制 | [Engine.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Engine.kt)、[EngineLimits.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EngineLimits.kt) | B2.4、R1 |
| 解释器与属性访问 | [Interpreter.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Interpreter.kt)、[JSObject.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSObject.kt)、[Environment.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Environment.kt) | B2.1、B2.2、L2.2 |
| 事件循环与 Promise | [EventLoop.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EventLoop.kt)、[PromiseSupport.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/PromiseSupport.kt) | B2.3 |
| 解析与词法 | [Lexer.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/lexer/Lexer.kt)、[Parser.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/parser/Parser.kt) | B2.4、R1、L1.1、L1.3 |
| 内建热路径 | [ArrayBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/ArrayBuiltins.kt)、[RegExpBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/RegExpBuiltins.kt)、[StringBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/StringBuiltins.kt)、[JsonParser.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/JsonParser.kt) | B2.5 |
| 基准 | [jvmBenchmark](../vibe-js-core/src/jvmBenchmark/kotlin) 下的七个基准类 | B2 验收 |
| 发布与 API 契约 | [vibe-js-core/build.gradle.kts](../vibe-js-core/build.gradle.kts)、[vibe-js-host/build.gradle.kts](../vibe-js-host/build.gradle.kts)、[publish.yml](../.github/workflows/publish.yml) | PKG1 |
| 一致性筛选 | [Test262Selection.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Selection.kt)、[Test262Conformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Conformance.kt) | C4 |
| 语言能力（待新建） | Parser/Interpreter/JSObject 与 `builtins/` 下的新模块 | L1、L2、L3 |
| 文档 | [README.md](../README.md)、[COMPATIBILITY.md](COMPATIBILITY.md)、[ARCHITECTURE.md](ARCHITECTURE.md)、[CONFORMANCE.md](CONFORMANCE.md) | 口径同步 |

## 8. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-06 | 重写：移除全部已完成项（C1–C3、T1、T2、M1、B1、H1、A1、D1），其约束收敛到 §2；按重新审核的结果确定在办项 B2（五个子项）、PKG1（收尾六项）、C4（筛选口径）；更新基线（core 610/604、host 25、ES5 15,244、ES2015 37,329、差分 492、真实库 309）、代码规模、性能数据与发布状态（0.0.0-SNAPSHOT → GitHub Packages）；暂缓 R1/L4/L3、搁置 L1/L2/M2 保持原决策并更新证据 |
| 2026-11-06 | L1/L2/L3 补充原子拆分（§4.1、§5.1、§5.2）：按可独立交付的子项列出依赖、人日与返工成本，明确"主题分组 ≠ 必须同时实现"；给出最小实现集：`for await` 4–8、Reflect 5–10、二进制 20–37、大整数 12–22、Vue 3/immer 20–35 |
| 2026-11-06 | 子项编号统一为 `X.n`：`L1a–L1d` → `L1.1–L1.4`、`L2a–L2c` → `L2.1–L2.3`、`L3a–L3d` → `L3.1–L3.4`，PKG1 子项编号 `PKG1.1–PKG1.6`；顶层编号不变（与提交历史、已完成项引用保持连续） |
| 2026-11-06 | 全部项目改为在办：移除"暂缓/搁置"分区，原 R1/L4/L3/L1/L2/M2 并入任务详情；按 P0–P4 重排优先级（P0 立即可做、P1 性能主线、P2 API 契约、P3 语言能力、P4 长期）；新增 R1/M2/L4 的方案与验收；总投入约 167–314 人日 |
