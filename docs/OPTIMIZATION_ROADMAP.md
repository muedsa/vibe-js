# Vibe JS 优化与完善路线（P0 完成后复审）

> 审查日期：2026-11-02。代码基线：`74e3114`。
>
> 对照资料：根目录原有 `context.md`（基线 `1d9b9a0`），以及 README、架构、兼容性、一致性文档。
>
> 本文记录审查结论与后续任务，不表示这些后续任务已经实现。原 `context.md` 的结论已与本次代码基线对照；当前推进顺序以本文为准。

### 实施进度（2026-11-03）

下文审查证据与成本保留 `74e3114` 时的快照；已完成项以本表和各节的实施说明为准。

| 任务 | 状态 | 实施说明 |
|---|---|---|
| C1 | 已完成 | Map/Set 使用逻辑槽位衔接 clear 前后的游标，同时清除旧槽位及条目引用；新增 4 组 commonTest 与差分场景 |
| C2 | 已完成 | 统一 ToLength、取消任意长度/迭代上限并接通宿主预算，实际原型环检测及诊断截断标记 |
| C3 | 已完成 | 七个 Set 新方法统一先取整 size 再校验，覆盖数值边界、访问顺序与迭代器关闭 |

## 1. 结论与审查范围

原审查 5.2 的四项 P0 已全部实现并提交：协作式取消与超时、执行配额、错误栈、事件循环生命周期与串行调度。下一阶段建议先修复已支持能力中的具体缺陷，补齐资源限制与验证工具，再按可测量的收益推进性能和现代 JavaScript 能力。

本次发现并通过 JVM 引擎与 Node v24.12.0 对照确认了三类兼容性缺陷：

1. Map/Set 在遍历中 `clear()` 后新增元素，已有迭代器和 `forEach` 会漏读新增元素。
2. `Array.from` 的 array-like 路径将长度静默截断为 100,000。
3. Set 新方法的 `GetSetRecord` 未先将 `size` 转为整数，错误拒绝 `-0.5`。

这些问题未被现有全绿基线捕获。应将定向差分、变异场景和资源行为测试与 Test262 并用；旧文档中“继续扩大已实现范围语料收益有限”的判断需要修正。

审查覆盖 core 的词法/解析、AST、解释器、对象/集合、内建、Promise/事件循环，以及 host、conformance、Gradle/CI、基准、Unicode 生成工具和文档。重点阅读 P0 变更及高风险实现，检查已有测试与报告，并运行下述验证；没有对全部源码进行形式化验证，也没有测量新的性能数据。

本文区分三种证据：**已复现**表示执行了最小场景；**源码确认**表示代码直接显示该行为或边界；**待测量/设计**表示后续建议，不能当成已确认瓶颈。源码位置以本次基线为准，后续应优先按符号定位。

## 2. 已完成的 P0 与必须保留的契约

| 原任务 | 提交 | 当前实现 | 后续不能破坏的行为 |
|---|---|---|---|
| 取消与超时 | `652ecfe` | 解释器按检查点观察取消，微任务边界检查取消，宿主取消原样传播 | JS catch/finally 不吞掉宿主中止；剩余微任务丢弃；取消不是全局状态回滚 |
| 执行配额 | `24b2012` | `EngineLimits`、每次 run 的步数预算、逻辑调用深度和协程分段恢复 | **默认调用深度保持 16,384**；步数超限为宿主 `ExecutionLimitException`，深度超限为 JS `RangeError` |
| 错误栈 | `f4ea5ba` | 错误创建/首次抛出快照、来源与行偏移、共享 async 祖先帧链 | 重抛与 Promise 转发不覆盖原栈；跨脚本函数保留定义来源；保留万层同步/async 回归 |
| 事件循环 | `74e3114` | `close()`、`pendingTasks`、`runUntilIdle()`、统一恢复队列和宿主入口 | 同一引擎串行执行；关闭等待清理且不取消宿主父 Job；外部 async 恢复自动推进微任务 |

本次核对四个提交的 GPG 状态均为 `G`。这只确认本地提交与签名，不代表远端发布或部署。

以下属于已经说明的能力边界，不应重新记为“P0 尚未完成”：

- 取消是协作式的，检查点不主动让出宿主线程；单线程 JS/Wasm 不能依靠同线程计时器抢占同步死循环。
- 步数默认不限；预算覆盖当前 `Engine.run` 及其执行期间消费的工作，不跨 run 跟踪任务归属。独立定时器、run 外恢复的 async、直接调用低层 Interpreter 没有独立步数预算。
- 解析、单次内建/宿主操作内部工作量、内存分配不受现有步数配额完整约束。
- `close()` 不回滚副作用；不响应取消的阻塞宿主操作仍会延长关闭等待。被中止 async 的 Promise 可以保持 pending。
- `runUntilIdle()` 包含未来定时器与挂起 async，不快进 Scheduler、不自动取消 interval；取消等待本身不关闭引擎。
- 串行执行不等于固定线程亲和性。直接访问全局对象/解释器/注册表，或只持有旧 `runtimeMutex`，不构成完整并发保证；应从 `Engine.run` 或 `eventLoop.runTask` 进入。

实现与使用契约见 [ARCHITECTURE.md 第 8 节](ARCHITECTURE.md#8-扩展方式)。现有 commonMain 与宿主分层保持成立，本次未发现 core/host commonMain 新增平台原生绑定。

## 3. 验证基线与证据边界

### 3.1 当前单元测试报告

| 模块 | JVM | JS Node | Wasm Node | Windows Native |
|---|---:|---:|---:|---:|
| core | 581 | 576 | 576 | 576 |
| host | 25 | 25 | 25 | 25 |

上述报告均为零失败、零跳过。本次检查现有 XML；清理临时审查探针后再次执行 core/host JVM 测试任务，core 结果由 Gradle 缓存恢复，host 为 UP-TO-DATE。JS/Wasm/Windows Native 数字来自 P0 完成时保留的报告，本次未重跑。

工作区还保留有较早的 Android Host/browser 测试产物，不能把这些旧产物当成本次完整验证。Linux、macOS/iOS 的支持依据是源码目标与 CI 配置，本次没有查询远端 CI 结果，也没有在这些平台实跑。

### 3.2 本次重新执行的严格一致性检查

| 检查 | 本次结果 | 范围说明 |
|---|---|---|
| parser pass | 1,822 通过，85 允许，总计 1,907 | 允许项为 Generator；pass-explicit 同样为 1,822 + 85 |
| parser fail | 674 通过，8 允许，总计 682 | 允许列表处理旧语料与现行语义差异 |
| parser early | 600 拒绝，5 接受，总计 605 | 现行宽松/Annex B 语义差异；module 另跳过 262 |
| Test262 ES5 子集 | **15,244 / 15,244** | 选中 8,088 文件，2 文件跳过；次数包含 strict/sloppy 变体 |
| Test262 ES2015 特性子集 | **37,329 / 37,329** | 选中 20,374 文件，836 文件跳过；包含已实现的后续年份特性 |

以上均以 `-Pstrict` 运行成功，代码为 P0 完成后的基线。语料固定为：

- parser：`0e808c74fbec780646434cad17bb22dc52461003`。
- Test262：`91c70759363c46557b694792bf34a2551a7af5ba`。

另核对 P0 完成时已有的真实库报告 **309/309（80 个库）**、Node 差分 **480/480**，本次没有重复运行这两项。

### 3.3 本次新增的审查探针

在 JVM 上临时运行 6 个小型 JS 场景，并用 Node v24.12.0 对照，结果见第 4 节和附录。另直接观察集合存储：插入并删除 1,000 次后，Map 有效条目为 0，存储槽仍为 1,000。临时探针已删除，未作为“预期错误行为”的正式回归测试留下。

这些验证确认的是当前缺陷，不表示已经修复。本次没有做灾难性正则、OOM 或平台栈溢出的破坏性压力测试；相关风险仅按实现边界记录。

## 4. 已复现的兼容性缺陷（建议首先修复）

### C1 · P1：Map/Set 的 clear 破坏进行中的遍历

**实施说明（2026-11-03）：** 已将游标改为逻辑位置；clear 推进存储起点并释放条目，旧游标在恢复时跳过已清空区间。覆盖 keys/values/entries、重复 clear、删除重加、嵌套 forEach、无新增项和永久 done 状态。普通 delete 的墓碑压缩仍属于 M1。

**完成验证：** core JVM 585、JS/Wasm/Windows Native 各 580、host JVM 25 全部通过；Node 差分 484/484、真实库 309/309、Test262 ES2015 子集 37,329/37,329。JS 首轮与重型任务并行时，原有万层递归用例超过 120 秒；相同代码单独复跑完整 JS 测试后通过，未调整深度或超时上限。

**证据：已复现。** `JSMap.clearEntries()`（54 行）、`JSSet.clearValues()`（56 行）直接 `storage.clear()`；`MapBuiltins.iteratorMethod/forEach` 与 `SetBuiltins.iteratorMethod/forEach` 使用递增的槽位下标。

消费一个元素后清空集合，再加入一个新元素，新记录落到下标 0，而旧游标已为 1，因此漏读。相同问题存在于回调内部 clear 后新增的 `forEach`。

| 场景 | 审查基线 Vibe JS | Node |
|---|---|---|
| Map 的 keys 迭代器在 clear + set 后 next | `{"done":true}` | `{"value":3,"done":false}` |
| Set 的 values 迭代器在 clear + add 后 next | `{"done":true}` | `{"value":3,"done":false}` |
| Map.forEach 首次回调中 clear + set | `[1]` | `[1,3]` |
| Set.forEach 首次回调中 clear + add | `[1]` | `[1,3]` |

**建议：** 将清空逻辑与正在使用的游标协调。可先采用逻辑删除保持位置，再设计与游标兼容的回收；不能简单把所有游标重置为 0，也不能仅为修复此问题永久保留所有墓碑。

**验收：** keys/values/entries、forEach、嵌套遍历、clear 后多次新增、删除再插入、已经 done 的迭代器不复活；覆盖 commonTest 与 Node 差分，并与 M1 的存储优化使用同一套语义回归。

现有 Test262 `Map/prototype/clear/map-data-list-is-preserved.js` 验证了 clear 后返回 done，却没有覆盖 clear 后再次新增。这说明需要补充操作组合，而不是只增加同类简单用例数量。

### C2 · P1：Array.from 与其他内建的固定上限需要统一治理

**实施说明（2026-11-03）：** Array.from 使用共享 ToLength 和 Long 索引；迭代值收集、全局正则 match/replace、Set-like 消费移除百万次 guard，逐轮检查现有预算/取消。规范数组/安全整数上限保留，Kotlin List 到达 Int 表示上限明确 RangeError；原型链用快慢指针检测实际环，不再按百万次猜测。JSArray 的同步宿主诊断输出仍限百万项并标注省略；NumberBuiltins 的百万常量是运算基数，均不属于脚本结果的任意截断。单次 Regex 匹配内部、未接入检查点的其他内建、内存及独立任务预算继续由 R1 推进。

**完成验证：** core JVM 592、JS/Wasm/Windows Native 各 586、host JVM 25 全部通过；Node 差分 488/488、真实库 309/309、Test262 ES5 15,244/15,244、ES2015 子集 37,329/37,329。包含实际收集 1,000,001 项且读取最终 done、小预算中止无限输入与中止后引擎复用；JS 全量测试独立运行，默认调用深度仍为 16,384。

**证据：Array.from 已复现，其余为源码确认。** `ArrayBuiltins.kt:29,1384` 将 array-like 长度钳制为 `MAX_ARRAY_LIKE_LENGTH = 100_000`，会静默改变结果。通过自定义构造器立即抛出收到的长度，可以在不分配大型数组的情况下观察：输入 100,001，Vibe 收到 100,000，Node 收到 100,001。

进一步检查发现独立的百万次 guard：

| 位置 | 当前处理 | 需要解决的问题 |
|---|---|---|
| `Interpreter.iterateValues`（4756 行附近） | 最多收集 1,000,000 项后返回 | 可返回不完整结果，且没有以异常说明资源上限 |
| `RegExpBuiltins` 的全局 match（379 行附近） | 达到 1,000,000 次后 TypeError | 与统一宿主配额及实际终止条件不一致 |
| `RegExpBuiltins` 的全局 replace（476 行附近） | 达到循环上限后继续拼接已收集结果 | 存在静默截断路径 |
| `SetBuiltins.iterateSetRecord`（391 行附近） | 超过固定次数后 TypeError | 有限的大输入也可能被当成未终止迭代 |

**建议：** 逐项区分 ECMAScript 语义上限、Kotlin 容器/索引表示上限、可配置的宿主资源预算。规范长度使用共享 ToLength/安全整数规则；资源耗尽明确中止，不能伪装成正常的部分结果。接通必要的预算/取消检查后再去掉遗留 guard，避免直接删除保护导致无限消耗。

**验收：** 用自定义构造器/getter/迭代器低成本验证边界与访问顺序；覆盖 99,999/100,000/100,001、Infinity、负数、NaN、分数、2^32 与安全整数附近的拒绝/传参行为；不要求实际分配这些极大容器。百万次路径用可配置的小测试预算与有限迭代器验证中止原因、IteratorClose 和结果完整性。

注意：本次确认的 100,000 截断是同步 `Array.from` 的 array-like 路径；`Array.fromAsync` 对应路径已使用安全整数长度规则，不应误列为同一缺陷。

### C3 · P1：Set 新方法遗漏 size 的整数转换

**实施说明（2026-11-03）：** GetSetRecord 在 ToNumber 与 NaN 校验后复用 C2 的 ToIntegerOrInfinity，再判断负数并保存整数 size。七个 Set 新方法统一接受 -0.5，按截断后的正分数选择分支，保留 Infinity；size 只转换一次，失败时不读取 has/keys。新增 4 组 commonTest 和 Node 差分，覆盖 -0.5/-0/0.5/1.5/-1/NaN/正负 Infinity、getter/valueOf 顺序、转换异常、正常耗尽与提前 IteratorClose（包括关闭时抛错和返回非对象）。

**完成验证：** 新增测试先在未修复版本复现 3 组失败，修复后 core JVM 596、JS/Wasm/Windows Native 各 590、host JVM 25 全部通过；Node 差分 492/492、Test262 ES2015 子集 37,329/37,329。JS 全量测试独立运行；C2 的 ES5 与真实库验证结果见上一节，本项未重复运行这两项。

**证据：已复现。** `SetBuiltins.getSetRecord()`（346–365 行）在 `ToNumber(size)` 后直接判断负数，并将原 Double 用于大小分支。规范要求先处理 NaN，再作 ToIntegerOrInfinity，然后判断是否为负。

`new Set().isSupersetOf({ size: -0.5, has(){ return false; }, keys(){ return [][Symbol.iterator](); } })` 在 Node 返回 true，在 Vibe 抛 RangeError；`-0.5` 应截断为负零。

**建议：** 复用数值转换入口，保留 size → has → keys 的可观察读取顺序。该辅助函数服务多个 Set 方法，修复需统一覆盖。

**验收：** -0.5、-1、-0、正分数、NaN、Infinity；使用有副作用的 getter 检查读取顺序，检查分支选择与迭代器关闭。

## 5. 资源、内存与运行工具

### R1 · P1：扩展资源约束的覆盖面

**证据：源码确认的既有边界，不是 P0 回退。** `Engine.parse()` 同步完成 tokenization 和递归下降解析；`JsonParser` 与纯 Kotlin 正则回退存在递归/内部循环；`KotlinJsRegex.find()` 一次调用平台实现的 Kotlin Regex；字符串 repeat/pad 等会在一次内建调用中分配结果。`maxSteps` 不能代表这些工作量，`maxCallDepth` 也不是语法或数据嵌套深度上限。

建议分三个独立增量推进：

1. **输入与结构边界：** 可选源码长度、token/AST 数量、语法/JSON 嵌套深度、字符串/集合结果规模限制。普通 run、eval、Function/AsyncFunction 的动态编译入口必须一致；解析缓存除条目数外考虑源码/AST 总规模。上限应可配置并有清晰计量单位。
2. **内部工作检查：** 长数组/字符串/JSON 循环与受控正则回退加入分批预算/取消检查；检查不能额外执行 JS 微任务、改变 getter 顺序或 async 同步前缀。
3. **任务预算归属：** 为独立 timer、run 外 async、低层 runTask 设计可选任务级策略；明确新预算、继承预算和引擎总额各自的语义，避免误将其他 run 的工作归属为当前调用。配套 pending 任务数量上限与背压策略。

Kotlin Regex 的单次匹配无法仅靠外围 ensureActive 获得硬时限。若要提供严格受控模式，需要评估匹配器选择/正则工作预算；进程或 Worker 隔离属于宿主层，不应引入 core 平台绑定。内存规模计数同样不等于精确的堆内存限额。

**验收：** 每类限制包含正常边界、超限原因、超限后清理、后续可复用性；保留默认 16,384 调用深度与 12,000 层同步/async 用例。通过受控大小输入验证，避免把 OOM 当正常验收手段。

### M1 · P1：Map/Set 查找复杂度与墓碑回收

**证据：源码确认，并验证墓碑累积。** `JSMap`/`JSSet` 使用列表存储，find 扫描列表，`liveSize` 每次 `count`。查询/插入/size 会受历史槽位数影响；连续插入不同值具有二次总工作量风险。删除会置 null、释放该槽对值的引用，但不缩短存储列表；这不是“被删对象仍全部保留”，而是槽位和扫描成本累积。

**建议顺序：** 先维护有效计数，再建立 SameValueZero 语义的键索引，最后处理稳定游标和压缩/分代回收。对象键按身份，NaN 相等，-0/+0 同键；不能直接假设 Kotlin Map 的默认 equality/hash 与 JS 完全一致。

**验收：** 与 C1 共用实时迭代测试；增加 1k/10k/100k 规模的 get/set/has/delete/size 和反复增删基准，记录时间、槽位和分配。当前没有性能测量数据，不能承诺固定提升倍数。

### M2 · P1 文档与契约，P2 实现：WeakMap/WeakSet 的弱引用语义

**证据：源码确认。** `JSWeakMap : JSMap`、`JSWeakSet : JSSet`，底层列表强持有键和值。当前实现有键类型校验、不可迭代等 API 行为，但没有真实弱引用/ephemeron 生命周期；长期存活的弱集合可能保留本应可回收的对象图。

应立即在兼容性说明中区分“接口行为覆盖”和“GC 弱语义未实现”，避免用户将其作为长期缓存的自动清理机制。生命周期很长的宿主应自行管理这类数据的释放。

实现前需要专门设计。仅把 key 换成普通 weak reference 不足以覆盖 value 反向引用 key 的情况；在当前 commonMain、无平台原生绑定约束下，不能直接使用 JVM WeakHashMap 或 JS 原生 WeakMap。应先确认可移植机制；若需要自有可达性管理，单独评估成本，不作为小修补承诺。

**验收：** 有明确能力说明与跨目标策略；GC 测试不能以一次 System.gc 或短时间内必然回收作为可靠断言。

### T1 · P1：一致性运行器接入生命周期与失败隔离

**证据：源码确认。** `Test262Runner.execute`、`LibrarySmoke`、`DifferentialRunner` 创建 Engine 后未统一 finally close，也没有统一的每用例步数/超时配置；Node 子进程读取输出和 `waitFor()` 没有超时。CI 有 Job 总超时，但单例失控仍可能占满整个 Job，且难以生成该用例的失败报告。

**建议：**

- 每用例统一包装执行、故障分类与 finally 清理；取消后的清理使用受控上下文/时限，保留原始故障。
- 分开记录 JS 断言失败、解析崩溃、宿主取消、步数耗尽、Node 超时和清理失败。
- 对纯计算使用能独立发出取消的调度方式；解析、平台 Regex 等非协作路径必要时采用 JVM 运行器中的子进程隔离。
- Node 超时同时处理输出读取、进程终止与资源回收，不能只在阻塞 readText 后给 waitFor 加超时。
- 大语料逐步改为有界工作队列；当前先为选中测试创建 async，再用 Semaphore 限制执行，可进一步减少等待协程数量。

**验收：** 注入死循环、未结束 async、未结束 Node 进程和清理异常，能输出明确失败且继续完成后续允许执行的用例；现有严格模式的零用例/缺少 Node/缺失库失败规则保持。

### T2 · P1：让一致性报告可审计，并补足操作组合

当前 Test262 是基于 metadata 的已支持特性子集；还有源码字符串过滤、Reflect 断言垫片、harness 替换（如 prefixes、buildString）。这是可用的验证方式，但不能据此宣称完整 ES2015、完整 Reflect 或所有库均受支持。

**建议：**

- 报告包含引擎 commit、语料 commit、工具链/Node 版本、筛选配置、选择/排除/执行/跳过数量及原因。
- 区分“筛选阶段未纳入”和“选中后跳过”；现有 836 不是全仓未支持用例总数。
- 单独列出 harness shim/替换。审计 `source.contains("Proxy")` 等文本筛选是否因注释或字符串误排测试；增加 runner 自身的筛选/报告测试。
- 把 C1–C3 纳入 commonTest 与差分；扩展变异操作序列、getter 副作用、IteratorClose、跨轮 async/关闭竞争。允许范围内采用固定 seed 的属性测试与失败样本缩减。
- PR 增加经过计时的 parser/差分或变更相关用例门禁；完整 Test262 保留定期/手动，按实际成本再扩大。覆盖率先出报告，再对关键模块设置有依据的门槛。

现有差分已经包含固定 seed 的集合操作序列，应扩展这些基础设施并增加定向反例；单个 seed 未触发 C1，不能据此认为不存在该问题。

**验收：** 空集、误排、shim、异常退出都可追溯；一条最小反例能直接定位到用例与版本；全绿基线不掩盖已知缺陷。

### H1 · P2：宿主计时与使用契约

**证据：源码确认，未做新宿主时钟探针。** `HostModule.performance.now()` 使用 `currentTimeMillis() - performanceOrigin()`，timeOrigin 首次访问才创建，依赖 wall clock；不能保证墙上时钟调整后的单调性，也不是高精度计时。

建议在宿主抽象中区分 wall clock 与可注入的 monotonic time source，明确 timeOrigin 的建立时点；优先使用 Kotlin 通用时间抽象。补充时钟后退、首次访问跨 tick、计时精度、console.time 的约定测试。HostModule 绑定传入的 EventLoop，示例应强调每引擎创建匹配的模块实例，并在使用结束后关闭引擎。

## 6. 性能与架构演进

### B1 · P1：先建立能够解释成本的基准

现有 `InterpreterBenchmark.kt` 已有 10 个 JVM 基准，区分解析+执行、预解析 AST、解析缓存，覆盖算术、对象拼接、函数调用和数组循环。这是起点，但没有覆盖新加入的预算、错误栈、共享调度器、集合规模和持久运行场景。CI 尚未建立基准趋势或覆盖率报告。

建议增加以下分组，先保留原始结果与环境信息，不立即用共享 CI 主机上的单次百分比作为硬门禁：

| 分组 | 需要区分的成本与指标 |
|---|---|
| Engine 生命周期 | 冷启动/内建安装、重复运行、关闭、多个引擎；报告延迟与分配 |
| 解析与缓存 | 无缓存/命中/淘汰、不同脚本规模、带模板源码、来源名/偏移；报告吞吐和保留体积 |
| 执行与 P0 开销 | 无步数预算/有预算、浅调用/万层调用、尾调用、正常返回/错误栈格式化 |
| 对象与环境 | 局部变量、闭包、原型链、getter/setter、数字键枚举、稀疏数组 |
| 集合 | 规模增长、反复增删、clear 与存活迭代器、size 查询 |
| 异步与事件循环 | Promise 链、async 同步前缀、外部恢复、微任务突发、timer/并发 run/close；报告队列峰值与尾延迟 |
| 标准库 | JSON、字符串、Unicode、Kotlin Regex 主路径与回退路径分开测量 |

JVM 基准记录 JDK/Kotlin/Gradle、CPU、GC、预热、fork、原始 JSON 与误差；跨提交使用同环境比较。JS/Wasm/Native 分别建立轻量性能样本，不能把 JVM 加速直接当作跨平台收益。JMH 的 GC/分配 profiling 只属于 JVM 测量层。

**验收：** 一条命令可以重现基准；基准 Engine 在 teardown 中关闭；报告能解释差异来自解析、调度、查找还是分配。积累噪声范围之后再确定回归阈值。

### B2 · P2：优化顺序与语义风险

| 优化 | 当前依据 | 前置条件/风险 |
|---|---|---|
| 集合有效计数、索引与回收 | M1 有明确的线性查找及槽位累积证据 | 先保障 C1；按 SameValueZero 与实时迭代验收 |
| 减少临时列表/描述符/排序分配 | `JSObject.ownPropertyKeys` 分类并排序，函数/内建频繁构建参数列表 | 先用 profiler 确认热点，保留属性顺序与访问副作用 |
| 解析缓存与正则编译缓存 | 已有按条目数的 AST LRU；正则存在主/回退双路径 | 测命中率与内存；不混淆标签模板站点和源码来源；缓存必须有界 |
| 环境槽位与绑定预解析 | `DeclarativeEnvironment` 使用字符串 Map 与父链查找 | direct eval、with、全局对象、TDZ、参数环境、闭包捕获需要回退路径 |
| 对象形状与属性缓存 | 属性存储及原型链访问有明确入口 | prototype 改动、delete、defineProperty、访问器和后续 Proxy 都需要失效协议 |
| async Interpreter 复用 | 当前 fork 已共享必要上下文及冻结祖先链 | 可变帧必须隔离；挂起任务/回调存活期间不能复用。只有分配收益明确后才考虑池化 |
| 字节码/编译执行器 | 存在 Executor 接口 | Engine 仍直接创建 Interpreter，内建也依赖具体解释器能力；不是只替换接口实现即可完成 |

旧文档把“槽位、shape/内联缓存、async 复用”并列为低风险优化，已经不够准确。它们都可能改变可观察语义；建议一项一分支、一组性能证据、一套兼容回归，不同时改动调度模型和对象模型。

### A1 · P2：按职责拆分大文件，保留集中语义入口

当前 `Interpreter.kt` 6,272 行、`Parser.kt` 3,807 行、`Lexer.kt` 1,829 行、`IteratorBuiltins.kt` 1,680 行、`JsRegexEngine.kt` 2,300 行。行数本身不是缺陷，但对扩展、审查和定位形成成本。

建议围绕属性内部方法、函数/类调用、迭代器协议、正则构建与错误诊断逐步拆分内部实现；保持 `getV/putV`、Conversions、Completion 与解析期提升信息为唯一语义入口。不要把一段逻辑复制到多个相似 helper，也不要在功能修复同一批次进行大规模机械迁移。

运行时错误栈已完整推进，词法/解析错误仍有独立异常协议。可以单独设计带 sourceName、range、错误类别的结构化诊断适配层，保持原异常兼容；这属于诊断统一，不应重新描述成“Error.stack 尚未实现”。

## 7. 更接近常用 JavaScript 引擎的能力路线

用户希望尽可能接近常用 JS 引擎；调用深度已按此方向提升。语言范围的扩大仍需按能力逐项落地，不能把旧文档中的建议当成已经支持的功能。当前 AGENTS/兼容性矩阵仍明确排除 Generator、模块、Proxy/Reflect、BigInt、TypedArray 等；决定实现相应里程碑时，需同步调整范围声明。

旧审查中 Date 默认 UTC/受限解析格式、Unicode 数据固定 17.0.0，以及部分 Number radix/RegExp 与 V8 的差异仍需按 [兼容性说明](COMPATIBILITY.md#6-已知差异) 单独管理。本次未重新验证这些既有差异；Node 对照是发现问题的工具，遇到实现定义行为或 Unicode 版本差异，仍需核对规范与数据版本，不能一律修改为 V8 的输出。

### L1 · P1 能力扩展：Generator → 异步迭代 → async generator

建议拆成可验收的增量：

1. **同步 Generator：** function*、generator method、yield、next/throw/return、suspendedStart/suspendedYield/executing/completed 状态。
2. **委托与清理：** yield*、finally、IteratorClose、重入错误、提前 return/throw；协调宿主取消/配额，避免将 yield 当成普通 await。
3. **异步迭代：** for await...of、AsyncFromSyncIterator、异步关闭与 AsyncIterator 原型。
4. **async generator：** 请求队列、产出值的 await、关闭与取消；接入现有串行队列、任务统计和错误来源。

现有 Kotlin 协程机制可以复用，但普通 Promise/async 支持不等于已经具备 Generator continuation。应先设计 JS 可暂停执行状态与宿主生命周期之间的关系。

**验收：** 对应 parser Generator 允许项逐批减少；启用 Test262 对应 features；增加 next/throw/return 顺序、深层委托、try/finally、关闭中挂起的 commonTest/差分和真实库样例。只有实际通过的范围才从排除列表移除。

### L2 · P1 能力扩展：Reflect → Proxy

先整理对象内部方法，再实现完整 Reflect；不能把当前 Test262 harness 的简易 Reflect 垫片直接移入核心。尤其需要正确处理 receiver、Boolean 返回值、construct 的 newTarget 与属性不变量。

Proxy 必须覆盖 get/set/has/delete/define/getOwnPropertyDescriptor/ownKeys/prototype/extensibility，以及可调用/可构造目标和 revocable。当前部分对象操作为非 suspend 的存储接口，而 trap 可以执行 JS；需要统一可执行的内部方法分派，不能只拦截 getV/putV。

**验收：** 不可配置属性、不扩展对象、原型、重复 ownKeys、访问器 receiver、撤销后访问、函数/构造代理等不变量；启用相应 Test262；在全部依赖满足后增加 Vue 3 响应式模块等实际场景。单有 Proxy 并不保证完整框架可运行。

L1 与 L2 没有必须彼此先后的关系。默认沿用旧路线先 L1；如果下一项真实使用目标依赖 Reflect/Proxy，可调整次序。无论哪条路线，C1–C3 和 T1/T2 都应优先形成闭环。

### L3 · P2：二进制数据与 BigInt

- ArrayBuffer/DataView：先明确字节存储、字节序、越界与 detached 状态；是否支持 resizable/transfer 单独列范围。
- 数值 TypedArray：统一元素转换、整数索引异质对象、subarray/slice、共享 buffer 视图、迭代与 species，不能仅实现包装 Kotlin 数组的构造器。
- BigInt：通用 Kotlin 任意精度整数、字面量求值、算术/位运算、Number 混用规则、比较、字符串与 JSON 行为；不能依赖 JVM BigInteger。
- BigInt64Array/BigUint64Array 在前两组能力完成后接入。

**验收：** 分功能开启 Test262，补充不同字节序、数值截断、共享视图、负数除法/移位及失败清理场景；跨 JVM/JS/Wasm/Native 结果一致。

### L4 · P3/按实际需求：模块与宿主生态

模块系统需包含解析、链接、模块环境、live binding、循环依赖与宿主加载器；不等价于拼接多个 Script。Intl、真实 DOM、fetch、localStorage 不纳入当前默认路线。URL、TextEncoder/Decoder、structuredClone、CLI/REPL 等可按使用需求作为 host 或独立模块推进，保持核心通用。

## 8. 发布、API 与文档

### PKG1 · P1 契约梳理，P2 发布：可作为依赖消费的交付闭环

当前有 `group = com.muedsa.js`、`version = 0.0.1`，但没有 maven-publish、制品签名、Dokka、explicit API、API 兼容检查或 CHANGELOG。Git GPG 签名不等于 Maven 制品签名。

建议先确定稳定公共面：Engine、配置、JSValue、宿主扩展、异常/源码位置、事件循环生命周期；将 Interpreter/globalObject/registry/scope 等低层接口的适用范围写清。当前公开面很大，不能直接把所有 public 都承诺为长期稳定 API。

交付步骤：

1. 梳理 API 与二进制兼容基线，逐步启用 explicit API/兼容校验；版本、实验接口与破坏性变更策略明确。
2. 配置 KMP 发布元数据、源码包、文档、POM 和制品签名。检查公开协程类型对应的消费端依赖，不只验证仓库内 project dependency。
3. 发布到本地测试仓库，用独立消费工程验证 core/host 的 JVM、Android、JS、Wasm 与目标 Native 变体；host 当前没有单独声明 Android target，需验证实际 Android 消费路径，不能用 core Android Host 测试代替。
4. 增加可运行示例：有界执行、来源错误栈、try/finally close、自定义 Scheduler/Console、跨线程入口与隔离的多个 Engine。
5. 准备版本记录、许可证/生成数据来源说明，再实施远端发布。远端发布应作为明确的交付任务，不由本审查自动执行。

**验收：** 独立工程通过真实制品坐标编译并运行，API diff 可审查，文档示例可执行；本地 publish 成功与远端可用分别记录。

### D1 · P1：消除现有文档中的过时或冲突信息

| 文档/位置 | 本次发现 | 建议更新 |
|---|---|---|
| `context.md` 4.2、4.3、5.2 | 仍描述没有取消/配额/完整栈/生命周期，以及未串行化的旧模型 | 保留历史背景；新任务引用本文，不继续将这些缺口列为未完成 |
| `COMPATIBILITY.md` 平台表 | 仍为 core 522、host 23 | 使用带日期和验证方式的当前数字，区分各平台与 JVM 专属测试 |
| 同文件词法模板行 | 写 raw 暂与 cooked 相同，语法表又写 raw 正确支持 | 以当前 Lexer、标签模板测试为准统一说明 |
| 同文件 instanceof 行 | 一处写自定义 Symbol.hasInstance 未支持，后文写支持 | 与 `Interpreter.instanceOf` 和既有测试一致 |
| 同文件属性描述符/Weak 集合 | “复杂冲突校验有限”过于笼统；Weak 未披露强存储 | 区分 JS 路径的校验与低层存储 API，列出精确剩余差异；补 M2 |
| `ARCHITECTURE.md` 开头、Parser/HostEnvironment 等注释 | 仍写阶段 A、async/Symbol/后续阶段预留 | 改为当前实现，保留真正未来项 |
| `CONFORMANCE.md` 与报告文字 | fail 允许项的概述与后文细分不一致；runner 注释仍写跳过 async/eval | 从当前 allowlist 与筛选逻辑生成/校正；明确子集、shim 与跳过口径 |
| `generate_unicode_data.py` 文档字符串 | 顶部只列出三种生成器，实际执行六种 | 同步完整流水线和 Unicode 版本；增加输入校验摘要与重生成差异检查 |

文档优先用符号和相对文件链接定位，测试数字保留时间与来源。避免将“配置了某 target”“CI 定义了某任务”“本地通过”“远端实际通过”混写成同一种支持证据。

## 9. 与旧审查的对应关系

| 旧内容 | 本次处理 |
|---|---|
| 5.2 P0 四项 | 全部关闭，保留第 2 节契约与回归；R1 是扩展覆盖面 |
| 4.3 发布/API 缺口 | 保留；稳定 API 梳理提前，实际发布独立验收 |
| 5.3 Generator/异步迭代、Proxy/Reflect | 保留为 P1 能力路线 L1/L2，拆出依赖与验收 |
| 5.4 TypedArray/BigInt | 保留为 L3，分离普通数值视图与 BigInt 视图依赖 |
| 5.4 性能 | 保留“先测量”；增加集合复杂度证据，纠正槽位/shape/async 复用的风险等级 |
| 5.5 Host/CLI/生态 | 保留为按需推进；增加单调计时和消费端制品验证 |
| 5.6 测试/CI | 从泛化覆盖率/性能门禁，细化为任务隔离、筛选可审计、操作组合与稳定趋势 |
| “已实现范围一致性收敛，继续扩大语料收益有限” | 修正：当前子集全绿，但本次已有 6 个差分反例，应继续补针对性组合场景 |

## 10. 推荐推进批次

这里的 P1 表示 P0 完成后的下一阶段优先事项，不表示以下所有任务必须在一个版本中完成。

### 10.1 成本估算口径

以下为基于当前代码和本文验收范围的**工程工作量粗估**，用于比较投入和安排顺序，不是已测量工时或交付日期。

- **单位：人日。** 以 1 名熟悉 Kotlin Multiplatform、现有代码与相关 ECMAScript 语义的开发者为前提，1 人日约为 6–8 小时有效工程投入。它不是 AI 运行时长、token 用量或费用报价。
- **包含：** 定位与必要设计、实现、针对性 commonTest/差分、相关一致性检查、跨目标回归修复、代码审查与文档更新。完成标准沿用各任务的验收要求。
- **不包含：** 初次熟悉项目、采购/搭建缺失平台、远端发布账号审批，以及无人值守构建的纯等待时间。默认现有 CI、语料与本地开发环境可用。
- 区间下界对应现有结构可以直接复用，上界考虑正常范围内的边界修复；涉及新执行状态、对象内部方法或可达性管理的任务，不确定性更大，先完成设计/原型再更新估算。
- **按增量核算并去重：** C1 的正确性修复先于 M1；C2 处理遗留固定 guard，R1 计算新增资源限制；M2 的能力说明计入 D1；B2 的集合优化计入 M1。不能将同一部分在多个表中重复相加。

### 10.2 分批投入

| 批次 | 任务 | 粗估投入 | 主要产物 | 完成标准 |
|---|---|---|---|---|
| 1 · 修正已支持语义 | C1、C2、C3，补对应 D1 | **5–11 人日** | 兼容性修复、正式 commonTest/差分反例 | 6 个已复现场景与 Node 一致；遗留固定 guard 有清单与明确处理策略 |
| 2 · 提高回归可信度 | T1、T2 | **9–18 人日** | 有界运行器、报告元数据、PR 快速子集 | 失控单例可定位且有清理；筛选/跳过/shim 可追溯；现有严格基线通过 |
| 3 · 资源与集合 | R1 第一批、M1、M2 契约、B1 | **14–28 人日** | 可选结构/结果限制、集合存储改进、性能基线 | 不静默截断、不破坏实时迭代；有边界测试与实测报告 |
| 4 · 消费与维护 | PKG1 契约/本地发布验证、D1、H1 | **8–16 人日** | 稳定 API 清单、可运行示例、文档与宿主计时约定 | 独立消费工程验证，现有能力描述无冲突 |
| 5 · 现代语言扩展 | L1 或由实际依赖决定先 L2 | **每条路线 25–45 人日** | 分步扩大语法/内建与真实库范围 | 对应 allowlist/feature 范围准确更新，原有 P0/一致性回归通过 |
| 后续 | L3、经 B1 证明的 B2、A1、L4 | **按选定范围估算，见下表** | 二进制/大整数、针对性性能与架构演进 | 每项有独立需求、性能或兼容证据，不以全面重写代替验收 |

前四批按上述限定范围合计约 **36–73 人日**；单人按每周 5 个投入日计算，约为 **7–15 人周**。这不包含完整 R1、真实弱引用、语言扩展和其他可选优化，不应解读为“完成全部路线”的成本。多名开发者也不能按人数直接等比例缩短周期，集合、对象模型和调度器的改动存在依赖与整合成本。

**C1–C3 完成后，建议下一项推进 T1。** 将一致性运行器接入引擎生命周期、每用例超时/预算和失败隔离，再推进 T2 的报告可审计性与操作组合。已新增的 C1–C3 回归作为后续资源和集合优化的语义基线；R1/M1、性能与语言扩展继续按上述批次推进。

### 10.3 各任务实现成本

| 任务 | 粗估投入 | 估算范围与主要成本因素 | 不确定性 |
|---|---|---|---|
| C1 · clear 与进行中的遍历 | **1–3 人日** | 修复 Map/Set 游标语义，补 4 个反例及嵌套/已结束迭代器回归；不包含存储索引与全面压缩 | 较低 |
| C2 · 固定上限治理 | **3–6 人日** | Array.from 长度转换、已列出的百万次 guard、统一中止方式与边界用例；复用现有预算，不含 R1 新增限制 | 中 |
| C3 · Set size 转换 | **0.5–1 人日** | 共享转换辅助函数、读取顺序与各 Set 方法回归 | 较低 |
| R1 · 资源约束扩展 | **第一批 5–10 人日；三批合计 15–30 人日** | 第一批先覆盖输入/结构/结果规模；另两批为内部工作检查和任务预算归属，详见 10.4 | 高 |
| M1 · 集合索引与回收 | **5–10 人日** | C1 完成后，维护有效计数、SameValueZero 索引、存活游标与墓碑回收，并进行规模基准 | 中高 |
| M2 · 弱集合能力说明 | **0.5–1 人日，已计入 D1** | 说明强存储边界、宿主使用约定与后续设计范围；不实现弱引用 | 较低 |
| M2 · 真实弱语义可行性验证 | **3–5 人日** | 验证 commonMain 约束下的弱引用/ephemeron 方案，输出原型和取舍；完整实现需重新估算 | 很高 |
| T1 · 运行器生命周期与隔离 | **5–10 人日** | Engine 关闭、单例预算/协作式超时、Node 进程超时、失败分类、受控清理与有界工作队列；Vibe 执行子进程隔离另计 **5–10 人日** | 中 |
| T2 · 报告与覆盖增强 | **4–8 人日** | 版本/选择/跳过/shim 元数据、筛选回归、定向操作组合、初步覆盖率报告与 PR 快速子集；自动样本缩减器另计 **3–6 人日** | 中 |
| H1 · 宿主计时 | **1–3 人日** | 注入单调时间源、timeOrigin 与 console 约定、确定性时钟测试和示例 | 中 |
| B1 · 性能基准 | **4–8 人日** | 扩展 JVM 分组、原始结果/分配分析、JS/Wasm/Native 轻量样本和复现说明；长期 CI 趋势系统另计 **3–6 人日** | 中 |
| B2 · 实测驱动优化 | **按候选项估算，见 10.4** | B1 确认热点后才选择；不同方案可以替代，不能默认全部实施 | 高 |
| A1 · 职责拆分 | **5–10 人日** | 分批提取内部组件、保持语义入口和 API、完成回归；结构化解析诊断适配另计 **2–4 人日** | 中 |
| L1 · Generator 与异步迭代 | **25–45 人日** | 包含同步 Generator、委托/清理、for await 和 async generator，以及对应语料收敛 | 高 |
| L2 · Reflect 与 Proxy | **25–45 人日** | 包含内部方法整理、Reflect、Proxy traps/不变量/revocable 和相应测试；不含完整框架其他依赖 | 高 |
| L3 · 二进制类型与 BigInt | **35–65 人日** | 固定长度 buffer、数值视图、BigInt、BigInt 视图；不含共享内存、Atomics、resizable/transfer 扩展 | 高 |
| L4 · 模块/宿主生态 | **按能力单独估算，见 10.4** | 模块、CLI 与不同 Host API 的投入差异大；不是单个交付包 | 高 |
| PKG1 · API 与制品消费 | **6–12 人日** | 契约/API 基线约 2–4 人日，发布配置、独立消费验证与示例约 4–8 人日；不含账号审批与服务费用 | 中 |
| D1 · 文档与注释纠偏 | **1–2 人日** | 含 M2 的能力说明；批次 1 约 0.5–1 人日，余量安排在批次 4，合计不重复计算 | 较低 |

批次 2 的 9–18 人日对应 T1/T2 基础范围；若决定使用 Vibe 子进程隔离或完整样本缩减器，应加入表中的增量。批次 3 只包含 R1 第一批，不包含 M2 可行性验证。跨平台测试结果不一致、公共 API 兼容要求扩大或新增支持特性时，应更新估算，不能靠缩小原验收范围维持工期数字。

### 10.4 大项拆分与可选投入

| 方向 | 子项 | 粗估投入 | 成本边界 |
|---|---|---|---|
| R1 | 输入、解析/JSON 嵌套与结果规模限制 | **5–10 人日** | 统一 run/eval/动态函数入口；这是批次 3 计入的部分 |
| R1 | 内建循环与可控回退匹配器的工作检查 | **5–10 人日** | 复用现有算法增加检查；若要重写匹配器以承诺严格正则预算，需另立原型并重估 |
| R1 | 独立任务预算、队列上限与背压 | **5–10 人日** | 明确 timer/async/runTask 的预算归属、失败传播与清理 |
| L1 | 同步 Generator 基础 | **10–18 人日** | 语法、AST、状态与 next/throw/return |
| L1 | yield*、finally 与委托关闭 | **5–9 人日** | 复杂恢复路径、异常与宿主中止交互 |
| L1 | for await 与异步迭代协议 | **4–8 人日** | AsyncFromSyncIterator、异步关闭及原型 |
| L1 | async generator | **6–10 人日** | 请求队列、产出 await、串行化及生命周期 |
| L2 | 对象内部方法分派整理 | **8–15 人日** | 让 trap 路径可执行 JS，同时保持普通对象行为 |
| L2 | 完整 Reflect | **5–10 人日** | receiver、Boolean 返回值、newTarget 与不变量 |
| L2 | Proxy | **12–20 人日** | 全部所需 traps、可调用/可构造目标、revocable 与测试 |
| L3 | ArrayBuffer/DataView | **8–15 人日** | 固定长度存储、字节序、越界、detached 状态 |
| L3 | 普通数值 TypedArray | **12–22 人日** | 整数索引异质对象、视图共享与原型方法 |
| L3 | BigInt | **12–22 人日** | 任意精度运算、转换、比较、位运算与规范边界 |
| L3 | BigInt64Array/BigUint64Array | **3–6 人日** | 依赖前三项，补转换与视图交互 |
| B2 | 已确认热点中的临时分配或有界缓存优化 | **每项 2–5 人日** | 一次选一个热点，包含前后测量与语义回归 |
| B2 | 环境槽位 | **10–20 人日** | 包含 eval/with/闭包等动态语义回退 |
| B2 | 对象形状与内联缓存 | **15–30 人日** | 需要失效协议；与 Proxy 设计协调后再实施 |
| B2 | async Interpreter 复用 | **5–12 人日** | 先确认生命周期与收益；若原型无收益可以停止，不保证一定值得引入 |
| B2 | 字节码/编译执行器 | **原型 5–10 人日；覆盖现有范围暂估 60–120 人日以上** | 原型包含在后续完整估算内；须验证热点与执行器抽象，不能将原型视为可替代现有引擎 |
| L4 | 静态模块系统 | **25–50 人日** | 解析、链接、live binding、循环依赖与宿主加载接口；不含 top-level await、动态 import 或网络加载实现 |
| L4 | CLI/REPL | **2–4 人日** | 基本文件/交互执行、错误输出、退出与关闭；高级调试另计 |
| L4 | TextEncoder/Decoder | **3–5 人日** | 先限定 UTF-8 与必要边界；其他编码单列范围 |
| L4 | URL | **5–10 人日** | 可移植常用子集及明确的差异清单；完整 WHATWG URL/IDNA 合规需重新估算 |
| L4 | structuredClone | **8–15 人日** | 按届时支持的值类型覆盖循环图、共享引用与错误；transferable 等依赖 L3 范围 |

真实弱引用没有给出完整实现的固定人日：如果 3–5 人日的验证表明当前约束下缺少可用机制，继续实现可能需要自有对象可达性/GC 管理，影响整个值模型。届时应独立设计和估算，不把它藏在一个小型 WeakMap 修复中。

建议每完成一个批次，用实际投入、失败聚类和测试耗时修订后续区间。优先执行 C1/C3 这类低不确定任务，可以较快校准本项目的实际交付节奏；语言扩展则先完成最小状态机或内部方法原型，再细排剩余工作。

## 11. 验证命令与关键文件

以下命令在仓库根目录用 PowerShell 执行。新行为优先写 commonTest；方法名使用中文字符串和 kotlin-test 断言。不要通过删除既有用例、扩大无理由 allowlist 或降低默认深度来换取通过。

```powershell
# 核心与宿主常规验证
.\gradlew :vibe-js-core:jvmTest :vibe-js-host:jvmTest --continue --console=plain

# 涉及 commonMain 语义、容器、调度改动时验证其他本机目标
.\gradlew :vibe-js-core:jsNodeTest :vibe-js-host:jsNodeTest :vibe-js-core:wasmJsNodeTest :vibe-js-host:wasmJsNodeTest :vibe-js-core:mingwX64Test :vibe-js-host:mingwX64Test --continue --console=plain

# 本次实际重新执行并通过的三项严格检查
.\gradlew :vibe-js-conformance:parserConformance :vibe-js-conformance:test262Conformance :vibe-js-conformance:test262Es2015Conformance -Pstrict --continue --console=plain

# 实现兼容性修复或能力扩展后验证真实库与差分
.\gradlew :vibe-js-conformance:librarySmoke :vibe-js-conformance:differentialConformance -Pstrict --continue --console=plain

# 基准编译与测量：本次仅审查代码，没有重新测量
.\gradlew :vibe-js-core:jvmBenchmarkClasses
.\gradlew :vibe-js-core:jvmBenchmarkBenchmark
```

| 文件入口 | 本文对应项 |
|---|---|
| [Engine.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Engine.kt)、[EngineLimits.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EngineLimits.kt) | P0、R1、公共 API |
| [EventLoop.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/EventLoop.kt)、[ExecutionTrampoline.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/ExecutionTrampoline.kt)、[RuntimeQueue.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/RuntimeQueue.kt) | 串行化、生命周期、队列与测量 |
| [JSMap.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSMap.kt)、[JSSet.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSSet.kt)、[JSWeakMap.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSWeakMap.kt)、[JSWeakSet.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSWeakSet.kt) | C1、M1、M2 |
| [ArrayBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/ArrayBuiltins.kt)、[SetBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/SetBuiltins.kt)、[RegExpBuiltins.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/builtins/RegExpBuiltins.kt) | C2、C3、R1 |
| [Interpreter.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Interpreter.kt)、[Environment.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/Environment.kt)、[JSObject.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/value/JSObject.kt) | C2、B2、A1、L1/L2 |
| [Test262Conformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Test262Conformance.kt)、[DifferentialConformance.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/DifferentialConformance.kt)、[LibrarySmoke.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/LibrarySmoke.kt) | T1、T2 |
| [InterpreterBenchmark.kt](../vibe-js-core/src/jvmBenchmark/kotlin/InterpreterBenchmark.kt)、[HostModule.kt](../vibe-js-host/src/commonMain/kotlin/com/muedsa/js/host/HostModule.kt) | B1、H1 |
| [core 构建配置](../vibe-js-core/build.gradle.kts)、[host 构建配置](../vibe-js-host/build.gradle.kts)、[测试 CI](../.github/workflows/gradle.yml)、[一致性 CI](../.github/workflows/conformance.yml) | 发布、目标验证与门禁 |

## 附录：最小复现场景

以下每段应在独立 Engine 或 Node 上下文中执行，取最后一个表达式的结果；均不需要 HostModule。Vibe 使用 `engine.run(source)`，完成后 `engine.close()`。结果见第 4 节；这些脚本是待修复缺陷的证据。

### A. Map 迭代器：clear 后新增

```javascript
var m = new Map([[1, 1], [2, 2]]);
var it = m.keys();
it.next();
m.clear();
m.set(3, 3);
JSON.stringify(it.next());
```

### B. Set 迭代器：clear 后新增

```javascript
var s = new Set([1, 2]);
var it = s.values();
it.next();
s.clear();
s.add(3);
JSON.stringify(it.next());
```

### C. Map.forEach：回调中 clear 后新增

```javascript
var m = new Map([[1, 1], [2, 2]]), seen = [];
m.forEach(function (value) {
  seen.push(value);
  if (value === 1) { m.clear(); m.set(3, 3); }
});
JSON.stringify(seen);
```

### D. Set.forEach：回调中 clear 后新增

```javascript
var s = new Set([1, 2]), seen = [];
s.forEach(function (value) {
  seen.push(value);
  if (value === 1) { s.clear(); s.add(3); }
});
JSON.stringify(seen);
```

### E. Array.from：只观察构造器长度，不分配大型数组

```javascript
try {
  Array.from.call(function (length) { throw String(length); }, { length: 100001 });
} catch (error) {
  String(error);
}
```

### F. Set 新方法：负小数 size 转换

```javascript
try {
  String(new Set().isSupersetOf({
    size: -0.5,
    has: function () { return false; },
    keys: function () { return [][Symbol.iterator](); }
  }));
} catch (error) {
  error.name;
}
```
