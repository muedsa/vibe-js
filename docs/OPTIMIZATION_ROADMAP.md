# Vibe JS 优化与完善路线

> 重写日期：2026-11-04。代码基线：`9c5f660`（C1–C3 之后）。
>
> 本文替代 2026-11-02 版本。旧版把 Generator、Reflect/Proxy、Weak 语义、资源约束、发布交付、模块系统与性能优化一并列为待办；本次按项目实际取舍**重新划定了范围**，并把"搁置"与"在办"分开记录，避免每次复审重复调研同一批事项。
>
> 证据口径：源码位置以当前基线为准（优先按符号定位，行号可能漂移）；测试与一致性数字来自 `vibe-js-conformance/build/reports/` 与测试源码统计，**本次未重跑全量测试**，各表均标注了生成时间。

## 0. 范围决策（先看这里）

本次确定四类状态。**"搁置"不等于"已支持"，"暂缓"不等于"永远不做"**，两者都必须如实披露在 README、[COMPATIBILITY.md](COMPATIBILITY.md) 与 [CONFORMANCE.md](CONFORMANCE.md) 中。

| 状态 | 项目 | 决策 | 对外的准确表述 |
|---|---|---|---|
| **搁置（无限期）** | L1 · Generator / 异步迭代 / async generator | 不实现 | "不支持 Generator：`function*` 与 `yield` 表达式在解析阶段报错，也没有 `for await`" |
| **搁置（无限期）** | L2 · Reflect / Proxy | 不实现 | "不支持 Reflect/Proxy；Test262 中的 Reflect 垫片只服务断言，不是实现" |
| **搁置（无限期）** | M2 · WeakMap/WeakSet 的真实弱引用语义 | 不实现 | "接口行为覆盖，底层是强引用存储；不能当作长期缓存的自动清理机制" |
| **暂缓** | R1 · 资源约束扩展 | 暂不做 | "执行配额只有步数与调用深度；解析、内建单次操作、内存不受约束" |
| **暂缓** | PKG1 · 公共 API 契约与制品发布 | 暂不做 | "仓库内以 project 依赖消费，未提供 maven-publish 制品" |
| **暂缓** | L4 · 模块系统与宿主生态 | 暂不做 | "`import`/`export` 为保留字并直接报错；无 CLI、URL、TextEncoder 等" |
| **暂缓** | B2 · 实测驱动的性能优化 | 暂不做 | "未做 profiler 分析，不承诺任何性能改进" |
| **暂缓（未排期）** | L3 · ArrayBuffer/TypedArray/BigInt | 暂不做（本次未点名，按"不在在办范围"处理） | "无二进制数据与 BigInt 运行时；`BigInt` 字面量仅语法识别，求值抛 TypeError" |
| **在办** | M1、H1、B1、A1、D1 | 见第 3 节 | 本路线剩余的全部交付内容 |

### 0.1 搁置/暂缓的后果（必须一起接受）

1. **Test262 口径长期不变**：ES2015 子集仍是"按已支持特性筛选"的 37,329 次执行（[报告](../vibe-js-conformance/build/reports/conformance/test262-es2015/test262-summary.md)），其中 540 个文件因 BigInt/Proxy/Generator 被排除。**不能对外宣称"完整 ES2015"**。
2. **解析门禁长期保留 85 条允许项**：[parser-pass-allowlist.txt](../vibe-js-conformance/src/main/resources/parser-pass-allowlist.txt) 的 85 个文件全部是 Generator 语法，`-Pstrict` 下它们不计为失败。这是**已知且被接受的缺口**，不是回归。
3. **Test262 筛选继续依赖源码文本匹配**：[Test262Conformance.kt:420-431](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Conformance.kt#L420-L431) 用 `source.contains("BigInt"/"Proxy"/"function*"/"for await")` 排除用例，可能因注释或字符串误排。T2 要求把这类筛选的统计与审计做出来。
4. **真实库覆盖面存在天花板**：Generator 与 Proxy 是当前最大的两个兼容缺口，搁置后 regenerator/co、redux-saga、Vue 3 响应式、immer、mobx 一类库无法运行。新增真实库用例时不要挑选依赖这两项的库。

### 0.2 重启条件

| 项目 | 重启触发条件 |
|---|---|
| L1 Generator | 出现明确的宿主/用户脚本依赖 Generator 的场景；或需要运行 Babel/regenerator 产物 |
| L2 Reflect/Proxy | 需要运行 Vue 3、immer、mobx 等以 Proxy 为核心的库；或需要 Reflect 元编程 |
| M2 真实弱引用 | 出现长期存活的宿主进程，且 WeakMap/WeakSet 成为可观测的内存增长点 |
| R1 资源约束 | 需要执行**不受信任**的脚本，或出现解析/OOM 类事故 |
| PKG1 发布 | 出现仓库外的消费方，或需要版本化交付 |
| L4 模块 | 需要执行多文件 ES Module 工程 |
| L3 二进制与 BigInt | 需要处理二进制数据（Buffer、WebAssembly 交互、图像/音视频）或大整数运算 |
| B2 性能优化 | B1 建立基线并定位到具体热点之后 |

## 1. 当前基线

### 1.1 能力概览

- 语法：ES5 全量 + ES2015 常用语法（class、箭头函数、解构、模板、默认/rest 参数、`let`/`const`、`for...of`、Symbol、async/await），并已扩展到部分后续年份语法（`using`/`await using`、class 公有/私有字段与静态块、可选 catch 绑定、hashbang）。
- 内建：Object/Function/Array/String/Number/Boolean/Math/JSON/Error/RegExp/Date/Symbol/Map/Set/WeakMap/WeakSet/Promise，以及 Iterator helpers、DisposableStack/AsyncDisposableStack、`Promise.withResolvers`/Keyed 组合、`RegExp.escape`、Set 的 7 个 ES2025 关系方法等。
- 明确排除：Generator、模块、Proxy/Reflect、BigInt 运行时、Intl、TypedArray/ArrayBuffer、真实 DOM/Web API、`for await`。
- 架构约束不变：核心全部位于 `commonMain`、不引用平台原生 API；执行方法均为 `suspend`；属性读写走 `Interpreter.getV`/`putV`；类型转换走 `Conversions`；宿主能力通过 `BuiltinModule` 注册。

### 1.2 验证基线

| 检查 | 结果 | 生成时间 | 说明 |
|---|---|---|---|
| core JVM 单元测试 | 596 | 2026-11-03 | commonTest 590 + jvmTest 6 |
| core JS / Wasm / Windows Native | 各 590 | 2026-11-03 | 与 JVM 的差集为 JVM 专属并发/超时用例 |
| host JVM 单元测试 | 25 | 2026-11-03 | |
| parser pass | 1,822 通过 / 85 允许（共 1,907） | 2026-11-02 | 允许项为 Generator 语法 |
| parser fail | 674 通过 / 8 允许（共 682） | 2026-11-02 | |
| parser early | 600 拒绝 / 5 接受（共 605） | 2026-11-02 | |
| Test262 ES5 子集 | 15,244 / 15,244 | 2026-11-03 | 选中 8,088 文件，跳过 2 |
| Test262 ES2015 子集 | 37,329 / 37,329 | 2026-11-03 | 选中 20,374 文件，跳过 836 |
| Node 差分 | 492 / 492 | 2026-11-03 | Node v24.12.0 |
| 真实库 UMD 冒烟 | 309 / 309 | 2026-11-03 | 80 个库 |

以上数字是"当前范围内全绿"，**不表示完整 ES2015 或全部库受支持**，口径见 0.1。

### 1.3 代码规模

| 区域 | 文件数 | 行数 |
|---|---:|---:|
| core + host `commonMain` | 99 | 35,273 |
| core + host `commonTest` / `jvmTest` | 32 | 12,122 |
| `vibe-js-conformance`（main） | 11 | 9,332 |

最大单文件：[Interpreter.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Interpreter.kt) 6,276 行、[Parser.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/parser/Parser.kt) 3,807、[JsRegexEngine.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/regex/JsRegexEngine.kt) 2,300、[Lexer.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/lexer/Lexer.kt) 1,829、[IteratorBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/IteratorBuiltins.kt) 1,680。

## 2. 已完成项与不可破坏的契约

### 2.1 已完成任务（C1–C3、T1、T2）

| 任务 | 提交 | 实施内容 | 后续不能破坏的行为 |
|---|---|---|---|
| C1 · Map/Set 的 clear 破坏进行中遍历 | `629d0bb` | 游标改为逻辑位置；`clear` 推进存储起点并释放旧槽位，旧游标恢复时跳过已清空区间 | 实时迭代语义：遍历中新增可见、删除跳过、重删重加再次访问、`done` 后不复活 |
| C2 · 固定上限治理 | `5f4daf7` | `Array.from` 使用共享 ToLength 与 Long 索引；移除迭代收集/全局正则/Set-like 消费的百万次 guard；原型链环检测改为快慢指针 | 规范长度上限（数组 2^32-1、安全整数）保留；资源耗尽必须抛异常而非静默截断 |
| C3 · Set 新方法遗漏 size 整数转换 | `3e22ec2` | `GetSetRecord` 先 ToNumber、查 NaN、再 ToIntegerOrInfinity，然后判负 | `size → has → keys` 的可观察读取顺序；七个 Set 关系方法共用同一转换 |

| T1 · 运行器生命周期与失败隔离 | `1f71e5b` | 三个运行器统一走 `RunSupport`：每用例独立引擎并在 `finally` 关闭（`NonCancellable` + 5 秒清理时限）、默认 60 秒协作式超时、失败分类、Node 输出重定向与超时强制终止、Test262 有界工作队列 | 用例超时/清理失败不得被记成普通断言失败；宿主取消必须原样传播；严格模式的零用例/缺少 Node/缺失库规则不变 |
| T2 · 报告可审计 | 本次改动 | 三份报告写入运行元数据（引擎/语料 commit、工具链、配置）；选择与跳过判定抽为 `Test262Selection` 纯函数，区分"筛选期排除"与"选中后跳过"；shim 单列；跳过明细落盘；CI 增加 PR 快速子集 | 筛选与跳过必须分开统计；文本筛选的每条跳过都要可追溯；报告 JSON 字段不得随意改名 |

回归位置：[ConformanceTest.kt:165-343](../vibe-js-core/src/commonTest/kotlin/com/muedsa/js/ConformanceTest.kt#L165-L343)（长度边界、Set size 转换、clear 实时迭代）与 [DifferentialCases.kt:139-207](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/DifferentialCases.kt#L139-L207)（Node 差分）；T1 的回归位于 [RunSupportTest.kt](../vibe-js-conformance/src/test/kotlin/com/muedsa/js/conformance/RunSupportTest.kt)。

### 2.2 P0 契约（更早完成，仍然有效）

| 能力 | 当前实现 | 必须保持 |
|---|---|---|
| 协作式取消与超时 | 解释器检查点观察取消，微任务边界检查取消，宿主取消原样传播 | JS `catch`/`finally` 不吞掉宿主中止；剩余微任务丢弃 |
| 执行配额 | `EngineLimits(maxSteps, maxCallDepth)`；**默认调用深度 16,384** | 步数超限抛宿主 `ExecutionLimitException`；深度超限抛 JS `RangeError` |
| 错误栈 | 创建/首次抛出快照，来源与行偏移，async 祖先帧链共享 | 重抛与 Promise 转发不覆盖原栈；保留万层同步/async 回归 |
| 事件循环生命周期 | `close()`、`pendingTasks`、`runUntilIdle()`、统一恢复队列 | 同一引擎串行执行；关闭等待清理且不取消宿主父 Job |

### 2.3 已验证的边界（不重复记为缺陷）

- 取消是协作式的，单线程目标不能靠同线程计时器抢占同步死循环。
- 步数预算覆盖当前 `Engine.run` 及其期间消费的工作，不跨 run 跟踪任务归属。
- **解析、单次内建/宿主操作、内存分配不受现有配额约束**（这是 R1 暂缓的直接后果，见 0.1）。
- `close()` 不回滚副作用；不响应取消的阻塞宿主操作会延长关闭等待。

## 3. 在办任务

> 排序即建议的执行顺序。人日为**工程工作量粗估**（1 人日 ≈ 6–8 小时有效投入），不是日历工期。

### 3.1 T1 · 一致性运行器接入生命周期与失败隔离（已完成 2026-11-04）

**实施说明。** 新增 [RunSupport.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/RunSupport.kt)：`CaseRuntime` 统一负责引擎创建、协作式超时、异常分类与受控关闭（`NonCancellable` + 独立清理时限），`runNodeProcess` 负责 Node 子进程的输出重定向、超时与强制终止。三个运行器全部接入：

- [Test262Conformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Conformance.kt)：每个变体独立引擎并在 `finally` 关闭；失败分类进入报告（`failureKinds`、失败样例 `kind`、`test262-failures.txt` 第 4 列）；工作队列改为固定并行度的 worker，不再为两万多个用例一次性创建协程。
- [DifferentialConformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/DifferentialConformance.kt)：同步用例共享引擎（部分用例刻意依赖前一个用例留下的全局状态），超时后重建引擎；异步用例独立引擎；Node 超时与临时脚本清理；清理失败记入报告并让任务以退出码 2 失败。
- [LibrarySmoke.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/LibrarySmoke.kt)：每个库独立引擎并关闭；单条断言区分 fail 与 crash。

**原始问题（已修复）。** 三个运行器创建 `Engine` 后都没有 `finally close`；全模块没有 `withTimeout` 也没有注入 `EngineLimits`；Node 先阻塞读输出再 `waitFor()` 且无超时；失败只有 PASS/FAIL/CRASH 三态，宿主取消与步数耗尽都被记成 CRASH；`Semaphore` 只限流不限制协程数量。

**验证结果。** 新增 10 个 conformance 单元测试（分类、超时、宿主取消、清理失败、参数解析、Node 超时）；ES5 15,244/15,244、ES2015 37,329/37,329、差分 492/492、真实库 309/309 全部通过；`-PmaxSteps=1` 正确输出"步数耗尽"分类，`-PnodeTimeoutMs=1` 正确输出"Node 超时"。core/host 未改动。

**已知边界。** 超时是协作式的：解析、正则回溯、超大字符串分配等非检查点工作不会被时限打断，硬隔离需要独立进程（未纳入本次范围）。


### 3.2 T2 · 让一致性报告可审计（已完成 2026-11-04）

**实施说明。** 报告从"只有计数"改为"可追溯"：

- 新增 [RunMetadata.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/RunMetadata.kt)：引擎 commit（`--engine-commit` → `GITHUB_SHA` → `git rev-parse` → unknown）、语料 commit（`.corpus-commit`）、Kotlin/Java/Gradle 版本、OS、本次筛选配置；三份报告（Test262/真实库/差分）的 Markdown 与 JSON 均写入。
- 抽出 [Test262Selection.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Selection.kt)：把子集选择与跳过判定从运行器中分离为纯函数，并区分**筛选期排除**（缺少 es5id、ES5 用例、特性未全部支持、路径排除、filter 不匹配）与**选中后跳过**（module/generator、未支持特性、`$262`、缺失 include）。
- 报告新增「筛选统计」「harness 替换与注入」两节；JSON 增加 `metadata`、`selectionExcluded`、`harnessShims`。
- 被跳过用例逐条写入 `test262-skipped.txt`，文本筛选误排可直接 grep 复核。
- 新增 `Test262SelectionTest`（12 例）与 `RunMetadataTest`（4 例），conformance 测试合计 26 个。
- CI：`conformance.yml` 增加 `pull_request` 触发，PR 只跑 parser + 差分快速子集（不需要 Test262 语料），完整基线仍在手动/每周任务。

**原始问题（已修复）。** 报告不含引擎/语料 commit 与工具链；筛选参数不进报告，"筛选期未纳入"被静默丢弃；harness shim 只写在文档里；文本筛选的误排无法审计；conformance 模块没有 `src/test`；CI 只有手动与每周触发。

**验证结果。** ES5 15,244/15,244（筛选期排除 39,592、跳过 2）、ES2015 37,329/37,329、差分 492/492、真实库 309/309；三份报告 JSON 均可解析，元数据完整（示例：引擎 `fecd579`、语料 `91c7075`、Kotlin 2.4.21 / Java 17.0.1 / Gradle 9.8.2）。

**已知边界。** 真实库语料没有 `.corpus-commit` 标记，`corpusCommit` 记为未知；非 git 检出且无 `GITHUB_SHA` 时引擎 commit 为 `unknown`。


### 3.3 M1 · Map/Set 查找复杂度与墓碑回收（P1，5–10 人日）

**现状（未开始，C1 只修了语义）。**

- 存储是插入顺序列表，查找为线性扫描：[JSMap.kt:59-64](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSMap.kt#L59-L64)、[JSSet.kt:61-66](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSSet.kt#L61-L66)。
- `liveSize` 每次 `count { it != null }`，即 O(n)：[JSMap.kt:34-35](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSMap.kt#L34-L35)。`size` 与 C3 涉及的 7 个 Set 关系方法每轮都要读它（[SetBuiltins.kt:209](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/SetBuiltins.kt#L209) 等 5 处）。
- `delete` 只把槽位置 null，**不缩短存储列表**：反复增删会让槽位无限增长（内存与扫描成本同时累积）。
- `clear` 通过推进 `iterationStartIndex` 保留实时游标，这条语义必须保留。

**目标（按此顺序，每步独立可验收）。**

1. 维护 O(1) 的有效计数，替换 `liveSize` 的 `count`。
2. 建立 SameValueZero 键索引：对象按身份、`NaN` 相等、`-0`/`+0` 同键；不能直接依赖 Kotlin `Map` 的默认相等性。
3. 墓碑压缩或分代回收，且不破坏 C1 的实时游标（压缩会改变物理下标，必须与逻辑游标解耦）。
4. WeakMap/WeakSet 直接继承这两个类（[JSWeakMap.kt:4](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSWeakMap.kt#L4)、[JSWeakSet.kt:4](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSWeakSet.kt#L4)），改动会同时影响它们，回归必须覆盖。

**范围。** `JSMap`/`JSSet` 150–300 行 + Map/Set/WeakMap/WeakSet 内建 100–300 行，共 5–6 个文件。属于**局部重写存储层**，不是全局重构。

**验收。** 与 C1 共用实时迭代回归；1k/10k/100k 规模的 get/set/has/delete/size 基准（并入 B1 集合组）记录时间与槽位；`size` 为 O(1) 可被基准证明。

### 3.4 H1 · 宿主计时与使用契约（P2，1–3 人日）

**现状（部分完成）。** `HostEnvironment.clock` 已可注入（[HostEnvironment.kt:78](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/HostEnvironment.kt#L78)），但宿主时间全部取自墙钟：`Interpreter.currentTimeMillis()` 读 `host.clock`，`performance.now`/`timeOrigin` 与 `console.time` 都用它（[HostModule.kt:157-171](../vibe-js-host/src/commonMain/kotlin/com/muedsa/js/host/HostModule.kt#L157-L171)、`:113/:129`）。`timeOrigin` 首次访问才创建，现有测试还把"首个 `now()` 恒为 0"固化了（[HostModuleTest.kt:392](../vibe-js-host/src/commonTest/kotlin/com/muedsa/js/host/HostModuleTest.kt#L392)）。

**目标。** 引入可注入的单调时间源（`TimeSource.Monotonic` 或宿主提供的 monotonic 毫秒），`performance.now`/`timeOrigin`/`console.time` 改用单调时钟；明确 `timeOrigin` 的建立时点（模块创建时而非首次访问）；补时钟回拨、精度、首次访问跨 tick 的约定测试。

**范围。** 4–5 个文件、几十行，非重构。难度低-中，主要成本是跨平台单调语义确认与改写既有断言。

### 3.5 B1 · 建立能解释成本的基准（P2，4–8 人日）

**现状（部分完成）。** 仅 10 个 JVM 基准（4 个 `run` + 4 个预解析 + 2 个缓存），`@TearDown` 只清空列表、**没有 `engine.close()`**（[InterpreterBenchmark.kt:54-58](../vibe-js-core/src/jvmBenchmark/kotlin/InterpreterBenchmark.kt#L54-L58)），不记录环境信息，没有集合/异步/生命周期分组。

**目标。** 按分组补齐：Engine 生命周期、解析与缓存、执行与 P0 开销、对象与环境、集合、异步与事件循环、标准库；记录 JDK/Kotlin/Gradle/CPU/GC/预热/原始 JSON；JS/Wasm/Native 各建轻量样本；teardown 关闭引擎。

**注意。** 先积累噪声范围再定回归阈值，不要用共享 CI 主机上的单次百分比做硬门禁。集合组与 M1 同批做，作为 M1 的验收依据。

### 3.6 A1 · 按职责拆分大文件（P3，5–10 人日，可选）

**现状。** 五个大文件见 1.3；词法/解析仍是 `LexerException` 与 `ParseException` 两套异常，没有结构化诊断适配层。

**目标。** 围绕属性内部方法、函数/类调用、迭代器协议、正则构建逐步拆分内部实现，保持 `getV/putV`、`Conversions`、`Completion` 与解析期提升信息为唯一语义入口；单独设计带 `sourceName`/`range`/类别的诊断适配层（保持原异常兼容）。

**取舍。** 原计划"随 L1/L2 顺带拆分"的机会已随二者搁置而消失，纯拆分的收益下降。建议只做低风险部分：诊断适配层与 `IteratorBuiltins` 拆分；`Interpreter`/`Parser` 的大改留到有功能需求时再做。

### 3.7 D1 · 消除文档中的过时或冲突信息（P1，1–2 人日）

以下问题在本次核实中**全部仍然存在**：

| 文档/位置 | 问题 | 处理 |
|---|---|---|
| [COMPATIBILITY.md:112-114](COMPATIBILITY.md#L112-L114) | 平台表仍写"核心 522 + 宿主 23" | 改为 596/590 与 25，并标注日期与验证方式 |
| 同文件 `:116-118` | 平台表被 blockquote 截断，渲染错乱 | 修复块引用结构 |
| 同文件 `:18` 与 `:47` | 词法模板 raw/cooked 描述自相矛盾 | 以当前 Lexer 与标签模板测试为准统一 |
| 同文件 `:50` 与 `:105` | `Symbol.hasInstance` 一处写未支持、一处写支持 | 与 `Interpreter.instanceOf` 及既有测试一致 |
| 同文件 `:80`、`:96` | Weak 集合未披露强存储；"复杂冲突校验有限"过于笼统 | 补 M2 的能力边界（见 0.1） |
| [ARCHITECTURE.md:3](ARCHITECTURE.md#L3) | 仍写"阶段 A" | 改为当前实现，保留真正未来项 |
| [CONFORMANCE.md](CONFORMANCE.md) `:43` 与 `:46` | fail 允许项概述与后文细分不一致 | 从当前 allowlist 与筛选逻辑生成/校正 |
| `Test262Conformance.kt:35` | 注释仍写"暂跳过 async/eval" | 与当前筛选逻辑对齐 |
| [generate_unicode_data.py](../tools/generate_unicode_data.py) 文档字符串 | 只列 3 个生成器，实际执行 6 个 | 同步完整流水线与 Unicode 版本 |
| 代码注释 | `Parser.kt:21`、`Lexer.kt:18`、`HostEnvironment.kt:37`、`Environment.kt:205`、`JSObject.kt:10`、`JSSymbol.kt:6`、`Engine.kt:187` 仍写"预留/后续阶段" | 逐处改为现状描述 |

另：旧版路线文档引用的 `context.md` 已不存在，本文不再引用。

## 4. 批次与投入

| 批次 | 任务 | 粗估 | 主要产物 | 完成标准 |
|---|---|---|---|---|
| **1 · 文档纠偏** | D1 | **1–2 人日** | 文档与注释纠偏 | 文档不再与实现冲突，排除项表述准确 |
| **2 · 集合与度量** | M1、B1（集合组 + 引擎生命周期组）、H1 | **10–21 人日** | 集合索引与回收、性能基线、单调计时 | 实时迭代语义不回归；有规模基准数据；计时单调性有测试 |
| **3 · 收尾（可选）** | B1 其余分组、A1 低风险部分 | **6–14 人日** | 完整基准分组、诊断适配层 | 一条命令重现基准；诊断信息可定位 |

**在办范围合计约 17–37 人日**：核心批次 1–2 约 11–23 人日，批次 3（6–14 人日）为可选项。T1、T2 已完成（见 2.1），剩余数字已扣除其 9–18 人日。

## 5. 搁置与暂缓项的现状记录

保留本节的目的：将来重启时不必重新调研，且能说清"为什么当时不做"。

| 项目 | 现状证据 | 重启成本 | 主要风险 |
|---|---|---|---|
| L1 Generator | `yield` 仅在关键字表与严格模式保留字中出现；`*` 是 `MULTIPLY`；AST 无 generator 标志；parser 允许项 85 条 | 25–45 人日（同步 Generator 10–18、委托/清理 5–9、`for await` 4–8、async generator 6–10） | 当前是递归 suspend 树遍历，无 CPS；每个 Generator 需要独立解释器 + 协程握手，且 `yield` 不能等同于 `await` |
| L2 Reflect/Proxy | 核心零实现，仅 Test262 垫片；`JSObject` 的 8 个属性内部方法全部非 suspend，全仓约 120–160 处调用点 | 25–45 人日（Reflect 5–10、内部方法整理 8–15、Proxy 12–20） | trap 可执行任意 JS ⇒ 内部方法必须 suspend，Kotlin 不允许非 suspend 调 suspend，无法只拦截 `getV/putV` |
| M2 真实弱引用 | `JSWeakMap : JSMap`、`JSWeakSet : JSSet`，底层列表强持有 | 可行性验证 3–5 人日，完整实现需重新估算 | `commonMain` 禁止平台 API，不能用 JVM `WeakHashMap` 或 JS 原生 `WeakMap`；需要自研 ephemeron/可达性，影响整个值模型 |
| R1 资源约束 | `EngineLimits` 仅步数与调用深度；`Engine.parse` 无源码长度/嵌套上限；`JsonParser` 纯递归；全仓仅 5 处 `checkExecution`；timer/async 无任务预算 | 第一批 5–10 人日，三批 15–30 人日 | 正则匹配器无硬预算；单次内建/内存分配无法用步数表达 |
| PKG1 发布 | 无 maven-publish/dokka/explicitApi/BCV/CHANGELOG；`implementation(kotlinx-coroutines-core)` 但公开面暴露 `CoroutineScope`；host 无 Android target | 6–12 人日 | 14 + 13 个变体的独立消费验证；explicit API 需逐处标注 |
| L4 模块与生态 | `import`/`export` 是 `ALWAYS_RESERVED`，直接报错；无 CLI/URL/TextEncoder/structuredClone | 模块 25–50 人日，CLI 2–4，其余 3–6 | 链接、live binding、循环依赖与宿主加载器 |
| L3 二进制与 BigInt | 无 ArrayBuffer/TypedArray/DataView；`BigInt` 仅语法（`BigIntLiteral` 求值抛 TypeError）；`NumberRadixFormatting` 的私有 `BigInt` 只服务数字格式化，不可复用 | 35–65 人日（ArrayBuffer/DataView 8–15、数值视图 12–22、BigInt 12–22、BigInt 视图 3–6） | 需自研任意精度整数并保证 JVM/JS/Wasm/Native 一致；TypedArray 是整数索引异质对象，涉及 subarray/slice/species/共享 buffer |
| B2 性能优化 | 无 profiler 数据；环境查找用字符串 Map + 父链，`ownPropertyKeys` 每次分类排序 | 每项 2–30 人日 | 改动会触及可观察语义（属性顺序、访问副作用、原型失效协议） |

## 6. 风险与约束

1. **不要把搁置项写成"后续阶段"**：现有文档多处使用"后续阶段/预留"，容易被读成"即将支持"。D1 要求改为明确的"不支持/已搁置"。
2. **Test262 全绿不等于兼容**：子集口径下 37,329 全绿，同时有 836 个文件被跳过、85 条 parser 允许项。任何对外表述都必须带上这两个数字。
3. **M1 会同时改动 WeakMap/WeakSet**：它们直接继承 `JSMap`/`JSSet`，压缩/索引改动必须用同一套回归覆盖四个内建。
4. **T1 不得绕过关闭契约**：`close()` 不能在自身事件循环任务内调用；需要可中止关闭时，应新增明确命名的入口，而不是让运行器持有内部状态强行绕过。
5. **新增能力时不要顺手扩大范围**：本路线已冻结语言范围；新增内建必须同步更新 [COMPATIBILITY.md](COMPATIBILITY.md) 与筛选逻辑，避免报告口径漂移。

## 7. 验证命令

在仓库根目录用 PowerShell 执行。新增行为优先写 commonTest；方法名使用中文字符串与 kotlin-test 断言。**不要通过删除既有用例、扩大无理由 allowlist 或降低默认调用深度来换取通过。**

```powershell
# 核心与宿主常规验证
.\gradlew :vibe-js-core:jvmTest :vibe-js-host:jvmTest --continue --console=plain

# 涉及 commonMain 语义、容器、调度改动时验证其他本机目标
.\gradlew :vibe-js-core:jsNodeTest :vibe-js-host:jsNodeTest :vibe-js-core:wasmJsNodeTest :vibe-js-host:wasmJsNodeTest :vibe-js-core:mingwX64Test :vibe-js-host:mingwX64Test --continue --console=plain

# 三项严格一致性检查
.\gradlew :vibe-js-conformance:parserConformance :vibe-js-conformance:test262Conformance :vibe-js-conformance:test262Es2015Conformance -Pstrict --continue --console=plain

# 真实库与 Node 差分
.\gradlew :vibe-js-conformance:librarySmoke :vibe-js-conformance:differentialConformance -Pstrict --continue --console=plain

# 基准（B1）
.\gradlew :vibe-js-core:jvmBenchmarkClasses
.\gradlew :vibe-js-core:jvmBenchmarkBenchmark
```

## 8. 关键文件入口

| 区域 | 文件 | 对应任务 |
|---|---|---|
| 引擎门面与限制 | [Engine.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Engine.kt)、[EngineLimits.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EngineLimits.kt) | P0 契约、R1（暂缓） |
| 事件循环 | [EventLoop.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EventLoop.kt)、[ExecutionTrampoline.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/ExecutionTrampoline.kt) | T1、P0 契约 |
| 集合存储 | [JSMap.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSMap.kt)、[JSSet.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSSet.kt)、[JSWeakMap.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSWeakMap.kt)、[JSWeakSet.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSWeakSet.kt) | M1、M2（搁置） |
| 集合内建 | [MapBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/MapBuiltins.kt)、[SetBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/SetBuiltins.kt)、[WeakMapBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/WeakMapBuiltins.kt)、[WeakSetBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/WeakSetBuiltins.kt) | M1 |
| 一致性运行器 | [Test262Conformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Conformance.kt)、[DifferentialConformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/DifferentialConformance.kt)、[LibrarySmoke.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/LibrarySmoke.kt)、[RunSupport.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/RunSupport.kt) | T1（已完成）、T2 |
| 基准 | [InterpreterBenchmark.kt](../vibe-js-core/src/jvmBenchmark/kotlin/InterpreterBenchmark.kt) | B1 |
| 宿主 | [HostModule.kt](../vibe-js-host/src/commonMain/kotlin/com/muedsa/js/host/HostModule.kt)、[HostEnvironment.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/HostEnvironment.kt) | H1 |
| 文档 | [README.md](../README.md)、[COMPATIBILITY.md](COMPATIBILITY.md)、[ARCHITECTURE.md](ARCHITECTURE.md)、[CONFORMANCE.md](CONFORMANCE.md) | D1 |

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-02 | 首次审查，确定 C1–C3 与 R1/M1/M2/T1/T2/H1/B1/B2/A1/L1–L4/PKG1/D1 全量清单 |
| 2026-11-03 | C1、C2、C3 完成并验证 |
| 2026-11-04 | 重写：Generator、Reflect/Proxy、Weak 真实弱语义列为无限期搁置；R1、PKG1、L4、B2 暂缓；L3 未在本次点名，按"不在在办范围"归入暂缓（未排期）；在办范围收敛为 T1/T2/M1/H1/B1/A1/D1；更新基线与验证数字；补充搁置项的现状与重启条件 |
| 2026-11-04 | T1 完成：新增 `RunSupport.kt`（用例超时、失败分类、受控关闭、Node 进程超时），三个运行器全部接入；新增 10 个 conformance 单元测试；ES5 15,244、ES2015 37,329、差分 492、真实库 309 全部通过 |
| 2026-11-04 | T2 完成：三份报告写入运行元数据（引擎/语料 commit、工具链、配置）；选择与跳过判定抽为 `Test262Selection` 并区分筛选排除与选中后跳过；shim 单列、跳过明细落盘；新增 16 个单元测试；CI 增加 PR 快速子集 |
