# 模块顶层 await（TLA）设计

> 状态：**设计提案，未实现**。范围仅限 ECMAScript Module goal；Script goal 明确不支持 TLA。
>
> 目标基线：ES2022 top-level await，并参考当前 Test262 `module-code/top-level-await` 的异步求值/循环语义。
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

> 初步 spike（未提交）：临时移除链接期拒绝后，单模块 `await Promise.resolve()` 可以在现有
> `runTask` 下完成，`await` 的执行路径可以复用。该 spike 没有覆盖宿主定时器、异步依赖并发、
> 循环依赖和取消，因此不能作为完整可行性结论。

### 2.2 当前的硬阻塞

1. `ModuleLinker` 对 `hasTopLevelAwait` 直接抛 `UNSUPPORTED_TLA`；
2. `ModuleEvaluator` 是同步 DFS：`evaluateModule(dependency)` 会等待依赖**完整求值**；
3. 没有 async evaluation、pending async dependency、cycle root 等状态；
4. `Engine.executeModuleGraph` 用 `eventLoop.runTask` 包住整个求值过程；
5. `runTask` 在 `runtimeMutex.withLock` 内执行，TLA 若等待宿主定时器/异步任务，可能持有锁跨挂起。

第 5 点是设计前必须先验证/解决的**前置风险**，不能等到模块求值器完成后再处理。上面的微任务 spike 只证明
“模块体中的 await 能让出并恢复”，没有证明“等待宿主定时器/外部异步任务时锁可释放”。

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
- Test262 module harness 不在本设计范围内；
- 不把 TLA 实现成“异步脚本入口”。

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
- 若异步循环无法按规范完成，明确以 JS `TypeError` 拒绝（消息包含模块 id），并在文档/测试中固定该限制；
- 规范完整实现作为后续增强，但必须在设计阶段明确 MVP 是“支持异步循环”还是“明确拒绝”。不能静默挂起。

Test262 已有 `pending-async-dep-from-cycle` 等用例；如果目标是规范级 TLA，异步循环不能作为永久限制。

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

**方案 A（推荐）：新增异步宿主入口**

在 `EventLoop` 增加类似 `runAsyncTask` 的入口：

- 任务以 `launchAsyncTask` 风格启动；
- 不把 `runtimeMutex` 持有到整个 TLA 生命周期；
- 用一个执行租约（lease）只在 JS 执行片段持有互斥；
- `runModule` 等待任务完成时驱动事件循环，而不是在锁内阻塞。

**方案 B：保留 runTask，只允许微任务 TLA**

实现简单，但 `await setTimeout` 会挂起/死锁，不符合 TLA 语义，不建议作为最终方案。

**方案 C：`runModule` 返回 Promise/Job**

把“等待 TLA”交给调用方，API 语义改变；不推荐，除非明确接受 breaking change。

建议先做方案 A 的最小 spike：一个模块 `await Promise.resolve()` 和一个 `await 宿主定时器`，验证锁、checkpoint、取消和预算行为，再进入模块求值器改造。

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
- 现有 `TLA 在链接期被拒绝` 测试必须改写为执行语义测试，不能删除；
- Test262 module harness 若后续接入，再增加 `flags: [module, async]` 与 `features: [top-level-await]` 的正式基线。

## 11. 分阶段实施

| 阶段 | 内容 | 估算 |
|---|---|---:|
| 0 | 事件循环异步入口 spike；确认 `runTask` 锁与宿主定时器行为 | 2–4 人日 |
| 1 | ModuleRecord 异步状态、移除链接期 TLA 拒绝、单模块/依赖链 TLA | 4–6 人日 |
| 2 | 兄弟模块异步调度、pending async dependency、cycle root | 4–7 人日 |
| 3 | 动态 import、错误/取消/预算、namespace/TDZ 边界 | 2–4 人日 |
| 4 | 测试矩阵、文档、strict 门禁、失败恢复 | 2–3 人日 |

不含 Test262 module harness。若要求正式 TLA 规范基线，module harness 需另立 10–20 人日。

## 12. 开放决策

1. 异步循环是首版支持，还是明确拒绝并固定为限制？
2. `Engine.runModule` 是否保持 `suspend` 返回 namespace，还是提供 Promise 风格的重载？
3. TLA 等待宿主定时器时，事件循环入口采用方案 A 的哪个粒度（整个 runModule / 每个 JS 执行片段）？
4. `maxMicrotasksPerRun` 在 TLA 跨轮次时按单轮还是整次 runModule 计算？
5. 是否把 Test262 module harness 纳入 TLA 的 Definition of Done？

在第 0 阶段 spike 完成前，不建议直接修改 `ModuleLinker` / `ModuleEvaluator` 的实现。
