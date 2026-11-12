# 模块顶层 await（TLA）设计

> 状态：**核心 TLA 已实现**。范围仅限 ECMAScript Module goal；Script goal 明确不支持 TLA。
>
> 实施结果（2026-11-12）：
> - `ModuleStatus.EVALUATING_ASYNC`、异步 completion、异步依赖等待与 cycle root 已落地；
> - 新增内部 `EventLoop.runAsyncTask`，TLA 等待期间不长期持有 `runtimeMutex`，宿主定时器可继续推进；
> - Test262 `language/module-code/top-level-await` **249/249** 通过（module suite，strict 门禁）；
> - 完整 `language/module-code` **593/593**，`language` module 子集 **686/686**；3 条 source-phase-imports 等未支持特性逐条跳过；
> - 已确认决策：异步循环按规范级支持；事件循环采用新增异步宿主入口；module harness 纳入 TLA 验收。
>
> 相关文档：[MODULE_DESIGN.md](MODULE_DESIGN.md)、[ARCHITECTURE.md](ARCHITECTURE.md)、[COMPATIBILITY.md](COMPATIBILITY.md)。

## 1. 结论

模块 TLA 需要把当前同步 DFS 求值器升级为**异步模块求值**：

- 模块体本身仍由 `Interpreter.runModule` 执行，顶层 `await` 复用现有 `awaitPromiseValue`；
- `ModuleEvaluator` 的依赖调度必须允许异步依赖挂起，同时继续推进兄弟模块；
- `Engine.runModule` 等待入口模块图完成后再返回 namespace；
- 动态 `import()` 等待目标模块图的 TLA 完成后再 resolve；
- 脚本入口 `Engine.run` 保持同步 Script，不增加 TLA 语义。

这不是简单删除 `ModuleLinker` 中的 `UNSUPPORTED_TLA`。真正的工作量在异步求值状态机、循环依赖、事件循环入口和失败传播。

## 2. 当前实现与差距

### 2.1 已具备的部分

- Parser 已识别模块顶层 `await` 与顶层 `for await`，并记录 `ModuleProgram.hasTopLevelAwait`；
- `Interpreter.runModule` 已经是 `suspend`，模块体的 `UnaryOp("await")` 会走 `awaitPromiseValue`；
- `awaitPromiseValue` 已实现 `PromiseResolve(%Promise%, value)`、微任务让出与 Promise 拒绝传播；
- `Engine.runModule` / `ModuleRuntime.importModule` 已经是 `suspend`；
- 动态 `import()` 已在微任务中执行，并可等待异步调用；
- `ModuleEvaluator` 已有 `NEW / EVALUATING / EVALUATED / ERRORED` 和 `evaluationError` 缓存。

> 实施 spike（已落地）：移除链接期拒绝后，单模块 `await Promise.resolve()` 可复用现有
> `awaitPromiseValue`；进一步验证了 `await` 宿主定时器需要新的异步入口，最终以
> `EventLoop.runAsyncTask` 解决，详见 §6.3 与 §12 的实施记录。

### 2.2 设计时识别的阻塞（均已处理）

1. `ModuleLinker` 对 `hasTopLevelAwait` 直接抛 `UNSUPPORTED_TLA`；已移除；
2. `ModuleEvaluator` 是同步 DFS：`evaluateModule(dependency)` 会等待依赖**完整求值**；已改为 completion 等待；
3. 没有 async evaluation、pending async dependency、cycle root 等状态；已新增；
4. `Engine.executeModuleGraph` 用 `eventLoop.runTask` 包住整个求值过程；已改用 `runAsyncTask`；
5. `runTask` 在 `runtimeMutex.withLock` 内执行，TLA 若等待宿主定时器/异步任务，会持有锁跨挂起；已由 `runAsyncTask` 解决。

## 3. 语义目标

### 3.1 必须支持

- 模块顶层 `await expr` 与顶层 `for await...of`；
- 依赖先完成（含 TLA），再执行当前模块；
- 异步依赖挂起期间，兄弟依赖/无关模块继续推进；
- TLA 拒绝传播到 `runModule` 与动态 `import()`；
- 同一模块只求值一次，失败缓存；
- `import.meta`、namespace、live binding 在 TLA 前后保持可用；
- 取消、超时、步数/微任务预算跨 `await` 继续生效；
- 模块内 `await` 之后仍能创建并使用函数、类、`import()`、`import.meta.resolve`。

### 3.2 明确不做

- Script goal 的 TLA；
- `eval` / `Function` 的 TLA；
- 模块加载器（文件/URL/网络）继续由宿主 `ModuleProvider` 负责；
- 不把 TLA 实现成“异步脚本入口”。

Test262 module harness 纳入验收范围，但作为独立运行器能力设计，见 §11；它不改变核心“无平台 IO”的边界。

## 4. 数据模型

### 4.1 ModuleRecord 扩展

在 [ModuleGraph.kt](../vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/module/internal/ModuleGraph.kt) 增加：

```kotlin
// 规范字段的精简映射
var dfsIndex: Int = -1
var dfsAncestorIndex: Int = -1
var cycleRoot: ModuleRecord? = null

var asyncEvaluationOrder: Int? = null
var pendingAsyncDependencies: Int = 0
val asyncParentModules: MutableList<ModuleRecord> = mutableListOf()

var topLevelCompletion: CompletableDeferred<Unit>? = null

/** 异步依赖完成后推进本模块；同步模块为 null。 */
var evaluationContinuation: Job? = null
```

`program.hasTopLevelAwait` 作为 `[[HasTLA]]`，不重复存字段。

### 4.2 ModuleStatus

建议增加独立状态而不是只用 `EVALUATING + flag`：

```kotlin
enum class ModuleStatus {
    NEW,
    LINKING,
    LINKED,
    EVALUATING,       // 同步执行中 / DFS 栈中
    EVALUATING_ASYNC, // 已挂起，等待 TLA 或异步依赖
    EVALUATED,
    ERRORED,
}
```

这样在循环依赖处理、动态导入和调试输出里可以直接区分“正在同步执行”和“异步等待中”。

### 4.3 状态不变量

- `EVALUATING`：当前模块在 DFS 栈中，模块体可能正在同步执行；
- `EVALUATING_ASYNC`：模块体已启动或等待异步依赖，不能再次执行；
- `pendingAsyncDependencies == 0` 且自身无 TLA：可以同步执行模块体；
- 只有 `EVALUATED` 的模块 namespace 才被视为完整；
- `ERRORED` 的 `evaluationError` 对所有父模块和动态导入一致重抛。

## 5. 求值算法

采用规范 Cyclic Module Record 的 `InnerModuleEvaluation` 思路，而不是简单的顺序 DFS。

### 5.1 入口

```kotlin
suspend fun evaluate(graph: ModuleGraph): JSObject {
    for (record in graph.records.values) {
        if (record.environment == null) instantiator.instantiate(record)
    }
    val completion = innerModuleEvaluation(graph.entry, stack = mutableListOf(), index = 0)
    completion.await()
    return graph.entry.namespace
        ?: error("Module namespace is not initialized: ${graph.entry.id}")
}
```

### 5.2 InnerModuleEvaluation

简化伪代码：

```text
inner(module):
  if module.status in (EVALUATING, EVALUATING_ASYNC):
      if module in current stack: 处理同步/异步循环
      return module.topLevelCompletion ?: completed
  if module.status == EVALUATED: return completed
  if module.status == ERRORED: throw module.evaluationError

  module.status = EVALUATING
  push module; assign dfsIndex/dfsAncestorIndex

  for each dependency in source order:
      depCompletion = inner(dependency)

      if dependency.status == EVALUATING:
          module.dfsAncestorIndex = min(module.dfsAncestorIndex, dependency.dfsAncestorIndex)

      if dependency.asyncEvaluationOrder != null:
          module.pendingAsyncDependencies += 1
          dependency.asyncParentModules += module

  // 同步、无异步依赖：立即执行
  if module.pendingAsyncDependencies == 0 && !module.hasTLA:
      executeModuleBody(module)
      module.status = EVALUATED
      pop; return completed

  // 否则异步执行
  module.status = EVALUATING_ASYNC
  module.asyncEvaluationOrder = nextOrder()
  module.topLevelCompletion = CompletableDeferred()

  launch(start = UNDISPATCHED) {
      if module.pendingAsyncDependencies > 0:
          await all pending async parent completions
      // 依赖完成后才执行自身模块体
      executeModuleBody(module)  // 内部 await 会挂起
      module.status = EVALUATED
      module.topLevelCompletion.complete(Unit)
      notifyAsyncParents(module)
  }

  return module.topLevelCompletion
```

关键点：

- `launch(start = UNDISPATCHED)` 让依赖的同步前缀立即执行到第一次 `await`，然后返回控制权；
- 父模块先启动所有依赖，再等待 pending 完成，避免“一个 TLA 依赖阻塞兄弟依赖”；
- 模块体中的 TLA 由 `Interpreter.runModule` 的 `awaitPromiseValue` 挂起；
- `notifyAsyncParents` 按完成顺序递减 pending，最后一个依赖完成时执行父模块体；
- 错误同样通过 completion 传播，不能只让异常从子协程逃逸。

### 5.3 循环依赖

需要区分两类：

1. **同步循环**：DFS 栈中遇到 `EVALUATING`，按现有规则返回，依赖方继续；
2. **异步循环**：循环中的模块在 `EVALUATING_ASYNC`，需要 `cycleRoot` / async parent 关系，不能简单等待对方 completion，否则死锁。

设计建议：

- 记录 `dfsIndex` / `dfsAncestorIndex`，识别循环根；
- 采用当前规范的 `[[CycleRoot]]` / `[[AsyncEvaluationOrder]]` / `[[AsyncParentModules]]` 关系，支持规范级异步循环；
- 不再保留“异步循环一律拒绝”的 MVP 分支；
- 用 Test262 `pending-async-dep-from-cycle`、`async-module-does-not-block-sibling-modules` 等用例作为设计验收样本；
- 若实现过程中发现规范版本差异，固定到当前 TC39 草案算法，并在测试中记录版本假设。

## 6. 执行入口与事件循环

这是本设计最大的架构风险。

### 6.1 问题

`Engine.executeModuleGraph` 现在：

```kotlin
eventLoop.runTask {
    ...
    ModuleEvaluator(interpreter).evaluate(graph)
}
```

而 `runTask` 在 `runtimeMutex.withLock` 内执行。TLA 模块如果 `await` 一个由宿主定时器/异步任务解决的 Promise，模块求值会在锁内挂起；定时器回调需要进入事件循环执行时又需要同一把锁，可能死锁。

微任务可以靠 checkpoint 绕过锁，但宿主 `setTimeout` / 外部异步任务不能假设如此。

### 6.2 设计要求

模块 TLA 的执行入口必须满足：

- `Engine.runModule` 可以等待 TLA 完成；
- 等待期间事件循环可以推进宏任务/微任务；
- 同一时刻只有一段 JS 在执行；
- 其他宿主入口不能与模块恢复片段并发执行；
- 取消/超时/预算在等待期间仍然有效。

### 6.3 候选方案

**方案 A（已确认）：新增异步宿主入口**

在 `EventLoop` 增加内部 `runAsyncTask`：

- 与 `runTask` 共用 operation 生命周期、取消与微任务清理；
- 不持有 `runtimeMutex`，JS 执行仍由共享 `ExecutionTrampoline` 串行化；
- 预算由 `Engine` 在异步任务内安装，定时器回调通过 `executeTask` 取得同一预算；
- `Engine.runModule` 等待 completion，而不是在锁内阻塞。

方案 B/C 不采用：B 无法等待宿主定时器，C 会改变 `runModule` 的既有 API 语义。

Phase 0 结论：单模块 `await`、依赖 TLA、兄弟模块调度、异步循环与宿主 `setTimeout` 均已验证；`TLA 挂起期间宿主脚本仍可执行` 也有宿主测试覆盖。

## 7. 动态 import

`ModuleRuntime.importModule` 当前：

```kotlin
link(graph)
ModuleEvaluator(interpreter).evaluate(graph)
```

TLA 后必须：

- `import()` 的 Promise 等待目标图完成；
- 目标图 TLA 拒绝时，`import()` Promise 拒绝，不是同步抛出；
- 已求值模块直接返回 namespace，不重复执行；
- TLA 模块在 `import()` 调用期间保持 namespace 可用（live binding），但不要提前 resolve；
- 取消/超时从动态导入任务传播到事件循环，而不是变成未处理拒绝。

## 8. namespace、import.meta 与 TDZ

- namespace 仍在声明实例化阶段创建，TLA 不改变 namespace exotic 对象；
- 模块体执行前的绑定处于 TDZ；TLA 只是把“模块体完成”推迟；
- `import.meta` 在实例化时创建，TLA 期间可用；
- `import.meta.resolve` 只依赖 Provider，不等待模块求值；
- 循环模块读取未初始化绑定时仍按现有 TDZ 规则抛 `ReferenceError`。

## 9. 错误、取消与预算

### 9.1 错误

- 依赖 TLA 拒绝：父模块不执行，状态 `ERRORED`，缓存原始异常；
- 入口拒绝：`runModule` 抛出对应 `JSException`；
- 动态导入拒绝：Promise 拒绝，保留错误对象和 stack；
- 同一模块被多个父模块依赖：只执行一次，所有父模块看到同一错误；
- 取消：不缓存为求值错误，状态回退到可重新求值状态。

### 9.2 预算

- `maxSteps`：模块体执行期间继续使用现有 `ExecutionBudget`；
- `maxMicrotasksPerRun`：TLA 跨多轮微任务时，需要明确“每轮预算”还是“整次 runModule 预算”；
- `maxPendingTasks`：TLA 等待宿主任务时继续受队列预算约束；
- 超时/取消：挂起中的 TLA 必须可取消，不能留下未完成协程或未处理拒绝。

## 10. 测试矩阵

### 10.1 核心单元

- 单模块 `await` 后导出；
- `await` 前/后的副作用顺序；
- `for await` 顶层；
- 依赖 TLA、兄弟模块不被阻塞；
- 菱形依赖、多个 TLA 依赖；
- 同步循环、异步循环、cycle root；
- TLA 拒绝、依赖拒绝、错误缓存；
- `runModule` 取消与超时；
- 动态 `import()` 等待/拒绝；
- `import.meta`、namespace、live binding；
- 顶层 `this`、严格模式；
- 预算跨 `await`。

### 10.2 门禁

- 现有 parser / ES5 / ES2015 / 差分 / 真实库 strict 全绿；
- 原 `TLA 在链接期被拒绝` 测试已改写为执行语义测试（`模块顶层 await 执行完成后返回 namespace`），未删除覆盖；
- Test262 module TLA 子集纳入验收：`test262ModuleConformance` 249/249。

## 11. Test262 module harness

harness 放在 `vibe-js-conformance`，作为 JVM 运行器读取语料，不进入核心。

### 11.1 执行模型

- 每个测试使用独立 `Engine`/realm；
- 测试文件作为入口 `ModuleSource`，`id` 使用相对 POSIX 路径，`url` 用于 `import.meta.url`；
- `ModuleProvider` 按当前模块路径解析相对说明符，只允许语料根目录内文件；
- includes 按 Test262 语义作为经典脚本先执行，再执行模块入口；
- `$262.evalScript` / `$262.detachArrayBuffer` / `$DONE` / `print` 复用现有 harness 能力；
- `flags: [async]` 通过 `$DONE` 驱动，TLA 完成后必须继续冲刷微任务；
- `negative` 覆盖 parse / link / runtime 三个阶段；模块链接错误映射现有 `ModuleLinkException`；
- 异步用例设置独立超时，超时记为失败而不是挂死。

### 11.2 范围与门禁

已按两阶段落地：

- **Phase A（TLA 验收，已完成）**：`language/module-code/top-level-await`，249/249；
- **Phase B（模块回归，已完成）**：完整 `language` module 子集 686/686；module-code 593/593；未支持的 import-defer/source-phase/json/text/bytes modules 逐条跳过。

门禁要求：

- 新增 `test262ModuleConformance` 任务；
- baseline 记录通过数、失败数、跳过数；
- 不允许目录/后缀/通配符 allowlist；
- skip 必须逐条写原因；
- 每个 TLA 用例的失败必须可复现，不能以“未实现”统一跳过。

### 11.3 落地选择

- 复用 `Test262Conformance` 的 metadata/harness/`$DONE`/报告，新增 `--suite module` 分支，不另起运行器；
- includes 先在独立 Engine 的全局 realm 中作为经典脚本执行，再执行模块入口；
- 每个测试独立 Engine 与模块图，fixture 仅在单个测试内缓存；
- Phase A 接 TLA 目录；Phase B 的 module-code / language module 使用独立 strict 门禁，不混入 TLA 任务。
- 异步超时沿用 `RunnerSettings.caseTimeoutMillis`。

## 12. 分阶段实施

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 事件循环异步入口；确认 `runTask` 锁与宿主定时器行为 | 已完成（`runAsyncTask` + 宿主测试） |
| 1 | ModuleRecord 异步状态、移除链接期 TLA 拒绝、单模块/依赖链 TLA | 已完成 |
| 2 | 兄弟模块异步调度、pending async dependency、cycle root | 已完成（TLA 249/249） |
| 3 | 动态 import、错误/取消、namespace/TDZ 边界 | 核心已完成；取消/预算边界继续补测 |
| 4 | 核心测试矩阵、文档、strict 门禁、失败恢复 | 进行中 |
| 5 | Test262 module harness（Phase A） | 已完成：`test262ModuleConformance` 249/249 |
| 6 | 完整 module-code / language module 接入与 known-fail 分类（Phase B） | 已完成：module-code 593/593；language 686/686 |

核心 TLA 与 harness Phase A 已完成；Phase B 不属于 TLA 验收阻塞项。

## 13. 仍待决策

1. `export using` 与 import-defer/source-phase/json/text/bytes modules 是否单独立项；
2. TLA 后的 async stack trace 是否保留模块帧；
3. `runModule` 取消时，已排队的宿主定时器保留还是取消；
4. 顶层 `await using` / `for await` 的剩余边界测试；
5. module harness Phase B 的逐路径 known-fail 清单与 CI 时长策略。
