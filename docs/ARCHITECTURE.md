# 架构设计

本文描述 Vibe JS 阶段 A 的内核设计，以及为后续语法/性能扩展预留的边界。

## 1. 目标与约束

- **通用 Kotlin 多平台库**：核心代码全部位于 `commonMain`，不引用任何平台原生 API。
- **单脚本执行起步**：`Engine.run(source)` 执行一段脚本，可重复调用共享全局环境。
- **语义优先**：遵循 ECMAScript 规范（ES5 为主，逐步覆盖 ES2015）。
- **可演进**：性能优化只替换内部实现（执行器、作用域查找、内建实现），不改变语义与公共 API。
- **异步预留**：所有执行方法均为 `suspend`，为 `async/await`、Promise 与宿主定时器预留。

## 2. 分层

```
Lexer ──► Parser ──► AST (Program)
                       │
                       ▼
                  Executor 接口
                       │
              AstInterpreter（当前实现）
                       │
     ┌─────────────────┼──────────────────┐
     ▼                 ▼                  ▼
 Environment      Conversions         Property access
（作用域链）      （类型转换）        （getV / putV）
     │                 │                  │
     └─────────────────┴──────────────────┘
                       │
                       ▼
                   EngineContext
     （GlobalObject / Intrinsics / BuiltinRegistry / EventLoop / HostEnvironment）
```

## 3. 关键类型

| 类型 | 职责 |
|---|---|
| `Engine` | 门面：创建全局对象、内建注册表、事件循环、解释器；执行脚本；安装宿主模块 |
| `EngineContext` | 运行时共享组件容器，避免全局单例，支持多引擎隔离 |
| `Executor` | 执行器接口：`suspend run(program, environment)`；后续可替换为编译执行器 |
| `Interpreter` | AST 解释器：语句/表达式求值、函数调用、构造、属性读写、类型转换入口 |
| `Completion` | 语句完成状态：Normal / Return / Break / Continue（throw 使用 `JSException`） |
| `Environment` | 作用域链：`DeclarativeEnvironment`（词法/函数）与 `ObjectEnvironment`（全局对象） |
| `Conversions` | ToPrimitive / ToNumber / ToString / ToBoolean / ToInt32 / ToUint32 / ToPropertyKey / ToObject / 相等比较 |
| `JSValue` | 值基类；原始值直接继承，不再继承 JSObject |
| `JSObject` | 属性描述符表 + 原型链；支持 getter/setter 与可枚举标志 |
| `PropertyDescriptor` | value/get/set/writable/enumerable/configurable |
| `PropertyKey` | 字符串键与 Symbol 键（Symbol 类型已预留） |
| `Intrinsics` | 每个引擎实例一份的原型集合（Object.prototype、Function.prototype 等） |
| `BuiltinRegistry` | 全局变量/内部对象注册；通过 `BuiltinModule` 安装扩展 |
| `HostEnvironment` | 宿主抽象：Console / Clock / Scheduler |
| `EventLoop` | 微任务队列 + 宏任务调度；scope 运行 async 函数体 |
| `JSPromise` / `PromiseSupport` | Promise 状态机、解析/拒绝、then 链与 async/await 基础 |
| `JSMap` / `JSSet` | Map/Set 存储；配合迭代器协议 |
| `ClassMember` | class 方法/访问器/构造器描述，由解释器创建原型与静态方法 |

## 4. 执行流程

1. `Engine.run(source)` → `Lexer.tokenize()` → `Parser.parseProgram()`。
2. Parser 在解析阶段完成：
   - 记录 `"use strict"` 指令；
   - 收集函数体内的 `var` / `function` 提升信息（避免运行时全量扫描 AST）。
3. `Interpreter.runProgram(program, globalEnvironment)`：
   - 创建全局执行上下文（this = 全局对象）；
   - 执行提升（函数声明优先，其次 var）；
   - 逐条执行语句，遇到 Return/Break/Continue 通过 `Completion` 向上传递；
   - 脚本结束后由 Engine 冲刷微任务队列。
4. 函数调用：
   - 新建函数作用域（`DeclarativeEnvironment(isVarScope = true)`）；
   - 绑定参数与 this（严格/非严格规则在调用点处理）；
   - 执行提升与函数体；Return 转换为返回值。

## 5. 作用域模型

全局环境由两层组成：

```
DeclarativeEnvironment（全局 let/const）
        └── ObjectEnvironment（全局 var/function，委托给 GlobalObject）
```

- 普通块、catch、for 使用独立的 `DeclarativeEnvironment`；
- 函数体使用 `isVarScope = true` 的 `DeclarativeEnvironment`；
- `var` / 函数声明通过 `findVarScope()` 绑定到最近的 var 作用域；
- 全局 `var` / 函数声明成为全局对象属性，因此与 `globalThis.foo` 联动；
- 非严格模式下对未声明标识符赋值会创建可配置的全局对象属性。

## 6. 属性访问

属性读写集中在 `Interpreter.getV` / `putV`，内建对象通过公共方法复用同一套语义：

- 原型链查找（`getOwnProperty` 逐层向上）；
- 数据属性直接返回值；
- 访问器属性调用 getter/setter（`this` 为 receiver，且为 `suspend`）；
- 写入时遵循 setter / 只读属性 / 原型链规则；
- 原始值（String/Number/Boolean/Symbol）通过对应原型查找属性，字符串额外支持 `length` 与索引；
- `JSArray` 重写属性描述符接口，实现索引扩展、`length` 截断/扩展与数组空洞。

## 7. 严格模式

- Parser 在脚本/函数解析阶段识别 `"use strict"` 指令，并让嵌套函数继承；
- `ExecutionContext.strict` 记录当前严格状态；
- 严格模式影响：
  - 普通函数调用的 this 为 `undefined`（非严格为全局对象）；
  - 未声明标识符赋值抛 `ReferenceError`（非严格创建隐式全局）；
  - 只读属性赋值、const 赋值抛 `TypeError`；
  - 其余细节（delete/arguments/参数重复等）在阶段 B 补齐。

## 8. 扩展方式

### 8.1 使用宿主模块

宿主能力位于独立模块 `:vibe-js-host`，不绑定平台原生 API：

```kotlin
val engine = Engine()
engine.install(HostModule(engine.eventLoop, engine.host.console))
engine.run("""
    console.log('hello');
    setTimeout(function () { console.log('later'); }, 10);
""")
```

- `HostModule` 提供 `window`/`self`、`console`（含 time/timeEnd）、`setTimeout`/`clearTimeout`、
  `setInterval`/`clearInterval`、`queueMicrotask`、`atob`/`btoa`、
  `performance.now()`/`performance.timeOrigin`；
- 定时任务通过 `EventLoop` 的 `Scheduler` 执行，console 通过 `Console` 注入；解释器与全局环境非线程安全，`Engine.run`、`EventLoop.schedule` 与 `setInterval` 回调共享 `EventLoop.runtimeMutex` 串行执行，`clearTimeout`/`clearInterval` 可互相取消。

### 8.2 自定义内建模块

```kotlin
class MyModule : BuiltinModule {
    override fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        registry.defineGlobal(
            "hello",
            BuiltinHelpers.method(intrinsics, "hello", 0) { interpreter, _, _ ->
                JSString("hello")
            },
        )
    }
}

val engine = Engine()
engine.install(MyModule())
```

标准库（`CoreBuiltins`）与宿主模块使用同一机制；核心不出现宿主概念。

### 8.3 替换执行器

`Executor` 接口只有 `run(program, environment)`。
后续若引入"AST → 内部指令"的编译执行器，只需：

1. 实现 `Executor`；
2. 由 `Engine` 注入新实现；
3. 语义测试保持不变。

`Engine.parse(source)` 可在不接触全局环境时完成词法/语法分析并返回不可变 AST，
`Engine.run(program)` 复用该 AST 重复执行；每次执行仍会重新做全局声明实例化，
语义与重复执行同一 Script 一致。`Engine.run(source)` 等价于 `run(parse(source))`。
`Engine(parseCacheSize = N)` 可启用有界 AST LRU 缓存（默认关闭），
使相同源码的重复 `run(source)` 复用解析结果；含反引号的源码不缓存，
以保持标签模板站点对象按每次解析隔离。

### 8.4 自定义宿主能力

实现 `HostEnvironment`（Console / Clock / Scheduler）并传给 `Engine` 构造函数，
即可接管输出、时间与定时任务。另可覆写：

- `utcOffsetMillis(epochMillis)`：Date 本地时间使用的时区偏移（毫秒，东为正；默认 UTC）；
- `reportUnhandledRejection(reason)` / `reportRejectionHandled(reason)`：Promise 未处理拒绝与补挂处理的宿主通知（默认忽略）。

`DefaultHostEnvironment` 提供 `timeZoneOffset` / `unhandledRejectionHandler` / `rejectionHandledHandler` 构造参数，普通宿主无需实现整个接口。

### 8.5 协作式取消

`Engine.run(source)` 在解析前检查取消，`Engine.run(program)` 与
`Interpreter.runProgram` 在执行入口立即检查取消。解释器的语句、表达式、
循环迭代和尾调用共用计数器，每 1024 个检查点调用
`currentCoroutineContext().ensureActive()`；这是检查间隔，不是执行步数配额。
计数器在检查前复位，保证取消后再次执行仍有完整的检查能力。

`EventLoop.drainMicrotasks` 在入口、每个微任务之前及拒绝通知之前检查取消，
避免原生任务反复入队时绕过 AST 检查。`Engine.run` 对取消异常原样重抛，
不包装为 `InternalError`，也不进入 JS 的 catch/finally 或 using 释放方法。
Kotlin 的 finally 仍恢复调用帧、词法环境、释放作用域和运行时互斥锁。
取消时丢弃队列中尚未执行的微任务与待上报拒绝，防止下一次运行继续执行
本轮残留回调；已经生效的全局写入与 Promise 状态不会回滚，未完成 Promise
可能继续保持 pending。普通 JS 异常仍按原有语义冲刷微任务。

当前边界：

- 取消检查本身不调用 yield、不推进 JS 微任务；深层调用使用下述内部恢复队列分段，
  保留同步代码和 async 首次 await 前的执行顺序，不向宿主调度器让出执行权。
  纯计算超时要求取消能被独立调度；JVM 的验证使用 `Dispatchers.Default` 和真实
  `withTimeout`。JS/Wasm 单线程、被占满的单线程调度器及测试虚拟时钟无法仅靠
  此检查保证纯计算超时。
- 词法/语法分析以及没有回到解释器检查点的单次内建操作（如正则匹配）和宿主调用
  不可被这些检查抢占；宿主挂起函数应遵循 Kotlin 协程的取消契约。
- async 函数仍由 EventLoop 的独立 scope 启动，不是 `Engine.run` 调用方的子协程。
  它检查自身协程的取消，取消不会变成 Promise 的 `InternalError` 拒绝；
  `Engine.run` 的超时尚不负责取消这些独立任务或宿主定时器。
  任务归属、关闭与统一调度属于后续事件循环生命周期任务。
- 全局环境不是事务。取消可能留下已经创建的词法绑定（包括未初始化绑定）与副作用；
  “可复用”保证执行器内部状态和锁恢复，不保证重跑同名 `let/const` 声明一定成功。

跨平台回归位于 `RuntimeCancellationTest`；真正的死循环超时位于 JVM 专用的
`RuntimeTimeoutTest`，避免在单线程目标上阻塞整个测试进程。

### 8.6 执行配额

`Engine(limits = EngineLimits(maxSteps = ..., maxCallDepth = ...))` 提供两类限制：

| 配置 | 默认值 | 超限行为 |
|---|---|---|
| `maxSteps: Long?` | `null`，不限步数 | 抛宿主 `ExecutionLimitException`，携带本次 `maxSteps` |
| `maxCallDepth: Int?` | `16,384`；可设为 `null` 关闭 | 抛 JS `RangeError: Maximum call stack size exceeded` |

两种非空上限都必须大于零。默认深度采用 16,384，以容纳复杂库的正常递归；
例如 64 会阻止部分 highlight.js、ohm、qrcode 和 mathjs 用例。
宿主可以按需调高上限或关闭深度限制，但这不限制解析、内建操作和内存消耗，
也不表示与 V8 / SpiderMonkey 的物理栈容量相同。

**步数预算。** 每次 `Engine.run` 取得运行时锁后安装独立预算，在 finally 恢复，
解析缓存与预解析 AST 不携带预算。语句、表达式、循环迭代、函数调用/构造及
每个微任务分别消费检查点；允许恰好消费 `maxSteps` 步，在下一步执行前中止。
这是运行时检查点数量，不是 ECMAScript 指令数，具体脚本所需步数可能随实现优化变化。
取消仍每 1024 个解释器检查点检查一次，配额则逐点扣除，低于 1024 的预算同样有效。

主脚本、本轮冲刷的微任务和它们执行期间的 async 工作共享预算，不因创建新解释器、
Promise 链或 await 而重置。超限后预算保存同一个异常，async 启动边界停止当前任务，
`Engine.run` / 微任务边界向宿主重新抛出该异常，避免被包装为 `InternalError`、
Promise 拒绝或协程未捕获异常。宿主异常不取消调用方 Job，也不能由 JS catch/finally
或用户释放器消化；清理规则与宿主取消一致，丢弃队列残留、保留已完成的全局副作用。

**调用深度。** 复用解释器调用帧入口，在修改当前 frame 之前检查上限；函数、原生
回调、访问器、构造器、super 与 eval 都计入，全局脚本帧不计入。间接 eval 同时经过
原生 eval 函数与 eval 执行帧，因此占两层。所有退出路径均在 finally 归还深度。
深度错误采用普通 JS RangeError 语义，允许 catch/finally 正常执行，调用方环境保留。
严格模式尾调用先退出旧帧再进入新帧，因此不累计深度。

**宿主栈分段。** `ExecutionTrampoline` 是当前线程内的协程恢复队列，每 8 层逻辑
调用把 Kotlin 栈退回队列循环，保留堆上的协程状态与 JS 调用帧；不调用平台 API，
不切换线程，不执行额外的 JS 微任务。async 启动时区分内部挂起与真正的宿主挂起/await，
调用方在同步前缀完成前不会提前取得 Promise；微任务或宿主回调返回前也会完成其触发的
内部恢复段，避免后续 JS 代码越过尚未执行完的同步工作。恢复队列遵循引擎既有的
串行执行契约，不是多线程调度器或事件循环生命周期管理器。

async 解释器继承创建时的逻辑祖先深度，防止同步 async 递归通过新实例绕过限制；
这个逻辑深度在 await 后保留。因此很长的 async 递归链也可能触发深度错误，即使
物理栈没有持续增长；并列挂起的 async 函数各自计数，不把等待任务总数当作调用深度。

**尚未覆盖的工作量。** 步数只在 `Engine.run` 持锁执行期间统计。运行之外恢复的独立
async、定时器和直接调用 `Interpreter` API 不具有独立步数预算；配置了调用深度上限时，
它们仍受该深度限制。
本轮消费的任务使用本轮预算，预算不跨 run 追踪任务归属，也不关闭或取消所有宿主任务。
词法/语法分析、AST 嵌套遍历所需的宿主栈、单次内建操作（例如正则匹配）、内存分配及
阻塞宿主函数不受这两种配额完整约束。它们不构成进程级资源隔离或硬时限。

`RuntimeLimitsTest` 覆盖精确步数边界、死循环、长任务、微任务重入、async 共享预算、
多种递归入口、尾调用、超限/取消后复用以及预算在异步拒绝处理期间耗尽的边界。
此外验证 12,000 层普通递归、默认 16,384 层上限，以及内部栈分段下的 async/微任务顺序。

### 8.7 错误栈

`Engine.parse(source, sourceName, lineOffset)` 和 `Engine.run(source, sourceName, lineOffset)`
将 `SourceInfo` 绑定到 Program。脚本名默认 `<anonymous>`，行偏移默认 0 且必须非负；
AST 中的 `SourceRange` 保留相对行列，仅显示时叠加偏移，列号不变。显示行号用 Long 相加，
避免较大偏移溢出。解析缓存键包含来源信息；`run(program)` 继续使用预解析时的来源。
函数保存定义时的来源，所以在另一个脚本调用旧函数不会把定义位置误报为调用文件。

解释器维护不可变祖先帧链及当前语句/表达式位置。进入函数时冻结父帧的调用点，
子表达式结束时恢复父位置；异常必须在恢复位置和退栈前补齐快照。只有错误发生时
才迭代展开为按外层到内层排列的 `List<StackFrame>`，不依赖平台物理栈。
async 解释器共享冻结的祖先链，并将创建点标记为异步边界，避免万层调用逐层复制祖先。
await 或宿主挂起恢复后，错误仍使用原调用链，不受之后的 `Engine.run` 影响。

Error 及其子类（含 AggregateError、SuppressedError 与资源释放合成错误）在内部槽
保存首次创建/抛出时的快照，不增加 JS 自有属性。`Error.prototype.stack` 按内层到外层输出：

```text
Error: 出错了
    at worker (task.js:23:9)
    at async start (entry.js:8:3)
    at <global> (entry.js:12:1)
```

第一行读取当前 name/message，帧始终来自快照。重复 throw、冻结错误、修改 name/message、
随后读取或覆写再删除 stack 都不改变内部快照；原有 stack getter/setter 与可枚举性保持不变。
环境层生成的错误由最近执行边界补齐帧与原型。宿主故障转换为 InternalError 时也保留
转换前的 JS 位置，取消和配额中止继续原样传播。

Promise 拒绝保存 `JSException` 诊断与 reason，原生拒绝函数转发诊断时仍经过普通调用、
深度/配额检查和 AlreadyResolved 守卫，不改变用户可见的 reason 或自定义 capability 参数。
Error 对象跨 then/catch/await 保留其创建位置；原始值的直接 throw、async 拒绝及原生 Promise
透明转发也保留抛出位置。用户回调重新 throw 原始值是新的抛出点。长拒绝链共享已有快照。

诊断边界：

- stack 是实现定义的诊断格式，不承诺与 V8/SpiderMonkey 逐字一致；严格模式尾调用省略旧帧。
- eval 使用 `<eval>`，Function/AsyncFunction 使用 `<Function>` / `<AsyncFunction>`；
  动态函数行号对应包含函数包装行的合成源码。没有有效范围的合成帧只显示函数名。
- 普通 Promise 回调显示自身定义位置，不自动拼接完整 then 注册链；自定义 thenable/capability
  若自行保存并重新拒绝原始值，只能从新的调用点记录诊断。原始值本身不携带 Error 内部槽。
- 词法/解析错误仍沿用 LexerException/ParseException 的诊断协议，本次来源参数用于运行时栈。
- 快照仅保存函数名、来源及源码范围，不保存 Interpreter、词法环境或完整源码。

`RuntimeErrorStackTest` 覆盖创建/重抛、精确行列、缓存/预解析来源、跨脚本函数、构造器与访问器、
eval/动态函数、async 恢复、Promise 转发、宿主错误、资源释放和 12,000 层同步/异步错误链。

## 9. 性能优化的预留点

| 现状 | 后续替换方式 | 影响范围 |
|---|---|---|
| `Environment` 使用 Map 链式查找 | 预解析变量为槽位索引 / 扁平化闭包 | 仅 Environment 与 Interpreter 内部 |
| 无词法声明的块/函数体、无词法循环头的 `for`/try/catch/finally 复用外层环境 | 已实现（Parser 预计算 `BlockStatement.requiresEnvironment`；箭头表达式体复用 var 环境） | 仅 Parser 与 Interpreter |
| 资源释放作用域仅在语句列表直接含 `using` 时创建 | 已实现（Parser 预计算 `hasUsingDeclaration`，普通块/函数体/循环不再分配 `DisposalScope`） | 仅 Parser 与 Interpreter |
| `JSNumber` 包装对象分配 | 已实现特殊值（±0/NaN/±Infinity）与 [-128, 1024] 小整数缓存；解释器字面量/算术/自增/长度路径复用实例 | 值模型与 Interpreter 热路径 |
| 每次函数调用执行预计算的 hoist 列表 | 已避免 AST 全量扫描；可进一步缓存函数对象 | 仅 Interpreter |
| 语句控制流使用 `Completion` | 保持不变 | 无 |
| AST 遍历执行 | 新增 `Executor` 实现（指令/闭包） | Parser/AST/内建/API 不变 |
| 属性表使用 LinkedHashMap | 可替换为更紧凑的存储或内联缓存 | JSObject 内部 |
| 内建集中安装 | 已按模块拆分 | 无 |

这些替换均不需要修改 Parser、AST、内建模块或公共 API。

### 9.1 基准现状（JVM，参考值）

`./gradlew :vibe-js-core:jvmBenchmarkBenchmark` 运行 kotlinx-benchmark（JMH）基准，同时衡量 `Engine.run`（解析 + 执行）与 `Engine.parse` 预解析后仅执行的吞吐：

| 场景 | `Engine.run`（含解析） | 预解析执行 |
|---|---:|---:|
| 算术 `1 + 2 * 3` | ~225,000 – 240,000 | ~1,900,000 – 1,970,000 |
| 字符串/对象拼接 | ~155,000 – 170,000 | ~530,000 – 550,000 |
| 函数调用 | ~150,000 – 156,000 | ~985,000 – 1,010,000 |
| 100 次数组 push 循环 | ~8,700 – 9,200 | ~9,200 – 9,800 |

数值为本机（JDK 17、单线程、10 × 300ms，2026-11-01 复测）的粗略量级，仅用于纵向对比；空闲时多次运行波动约 ±10%，系统高负载时可能显著偏低，应择机复测。小脚本的 `Engine.run` 吞吐主要受词法/语法解析限制，重复执行时应使用 `Engine.parse` 复用 AST（算术场景解析开销约 8 倍）；`arrayLoop` 的解析占比较低，主要受逐条语句执行与 `push` 调用开销影响，是后续优化（槽位化环境、内联缓存）的首要观察点。

`Engine(parseCacheSize = N)` 的重复 `run(source)` 在本机复测中算术约 1.5–1.6M ops/s、数组 push 循环约 8,500–9,000，接近预解析执行；缓存查找与互斥开销使算术略低于纯预解析（同轮纯预解析约 1.7M）。

## 10. 阶段状态

- **阶段 A（已完成）**：内核重构、严格模式框架、最小标准库、JVM/JS/Wasm 测试。
- **阶段 B（已完成）**：ES5 语法/内建补全（do-while、for-in、标签、delete/void/in/instanceof、
  with、对象字面量扩展、正则、Date、arguments、盒装原始值、freeze/seal、JSON 选项）。
- **阶段 C（已完成）**：ES2015 常用语法与内建（箭头、模板/标签模板、解构、默认/rest、
  spread、for-of、class/extends/super/new.target、Symbol、Map/Set/WeakMap/WeakSet、
  Promise、async/await）。
- **阶段 D（已完成）**：独立宿主模块 `:vibe-js-host`（console、定时器、window/self、
  queueMicrotask、atob/btoa、performance）。
- **明确排除**：Generator、import/export、Proxy/Reflect、BigInt、Intl、TypedArray、
  异步迭代（`for await` / AsyncIterator 原型）、DOM/fetch 等真实 Web API。
