# 架构设计

本文描述 Vibe JS 当前实现的内核设计、执行流程与扩展点；历史阶段划分见第 10 节。

## 1. 目标与约束

- **通用 Kotlin 多平台库**：核心代码全部位于 `commonMain`，不引用任何平台原生 API。
- **单脚本执行起步**：`Engine.run(source)` 执行一段脚本，可重复调用共享全局环境；模块目前只提供 `Parser.parseModule()` 语法解析，尚未接入 `Engine` 的执行链路。
- **语义优先**：遵循 ECMAScript 规范（覆盖 ES5 与 ES2015 常用语法，并扩展到部分后续年份特性）。
- **可演进**：性能优化只替换内部实现（执行器、作用域查找、内建实现），不改变语义与公共 API。
- **异步**：所有执行方法均为 `suspend`，`async/await` 与 Promise 由 EventLoop 的微任务队列与协程调度驱动。

## 2. 分层

```
Lexer ──► Parser ──► AST (Program / ModuleProgram)
                       │
                       ├── Script ──► Executor 接口
                       │                 │
                       │        AstInterpreter（当前实现）
                       │                 │
                       │   ┌─────────────┼──────────────────┐
                       │   ▼             ▼                  ▼
                       │ Environment  Conversions      Property access
                       │（作用域链）  （类型转换）     （getV / putV）
                       │   │             │                  │
                       │   └─────────────┴──────────────────┘
                       │                 │
                       │                 ▼
                       │             EngineContext
                       │   （GlobalObject / Intrinsics / BuiltinRegistry / EventLoop / HostEnvironment）
                       │
                       └── Module ──► Parser.parseModule（仅解析）
                                         │
                                         ▼
                                   ModuleProgram / ModuleItem
                                   （静态表；链接/求值无计划）
```

## 3. 关键类型

| 类型 | 职责 |
|---|---|
| `Engine` | 门面：创建全局对象、内建注册表、事件循环、解释器；执行脚本；安装宿主模块 |
| `Parser.parseModule` / `ModuleProgram` | 模块目标解析入口与独立 AST 层次；`ModuleItem` 不继承 `Statement`，预计算 `requestedModules` / `ImportEntry` / `ExportEntry`；当前只解析，不参与求值 |
| `ModuleItem` / `ImportEntry` / `ExportEntry` | 模块项（`StatementItem` / `ImportDeclaration` / `Export*Declaration`）与规范静态表；供后续链接阶段直接使用 |
| `EngineContext` | 运行时共享组件容器，避免全局单例，支持多引擎隔离 |
| `Executor` | 执行器接口：`suspend run(program, environment)`；后续可替换为编译执行器 |
| `Interpreter` | AST 解释器：语句/表达式求值、函数调用、构造、属性读写、类型转换入口 |
| `Completion` | 语句完成状态：Normal / Return / Break / Continue（throw 使用 `JSException`） |
| `Environment` | 作用域链：`DeclarativeEnvironment`（词法/函数）与 `ObjectEnvironment`（全局对象） |
| `Conversions` | ToPrimitive / ToNumber / ToNumeric / ToBigInt / ToString / ToBoolean / ToInt32 / ToUint32 / ToIndex / ToPropertyKey / ToObject / 相等比较 |
| `JSValue` | 值基类；原始值直接继承，不再继承 JSObject |
| `JSObject` | 属性描述符表 + 原型链；支持 getter/setter 与可枚举标志 |
| `PropertyDescriptor` | value/get/set/writable/enumerable/configurable |
| `PropertyKey` | 字符串键与 Symbol 键（Symbol 已实现） |
| `Intrinsics` | 每个引擎实例一份的原型集合（Object.prototype、Function.prototype、%GeneratorPrototype%、%AsyncIteratorPrototype% 等） |
| `BuiltinRegistry` | 全局变量/内部对象注册；通过 `BuiltinModule` 安装扩展 |
| `HostEnvironment` | 宿主抽象：Console / Clock（墙钟）/ MonotonicClock（单调）/ Scheduler |
| `EventLoop` | 微任务队列、宏任务调度、串行恢复与任务关闭；自有 scope 运行 async 函数体 |
| `JSPromise` / `PromiseSupport` | Promise 状态机、解析/拒绝、then 链与 async/await 基础 |
| `JSGenerator` | Generator / async generator 对象：状态机与两条容量 1 的 Channel，函数体在首次 `next()` 启动 |
| `JSProxy` | Proxy exotic 对象：只保存 target / handler / 撤销状态，trap 与不变量由解释器内部方法分派 |
| `JSBigInt` | 任意精度整数：符号 + 32 位 limb；`MAX_RESULT_BITS = 2^24` |
| `JSArrayBuffer` / `JSDataView` / `JSTypedArray` | 二进制存储与视图；TypedArray 是整数索引 exotic 对象，固定小端序 |
| `JSMap` / `JSSet` | 集合存储：插入顺序数组 + SameValueZero 索引（get/set/has/delete 均摊 O(1)、size O(1)）；删除留墓碑，无活跃迭代器时压缩，配合实时迭代协议 |
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

### 4.1 模块解析（仅解析）

模块走独立于 Script 的解析路径，`Engine` 当前不参与：

1. `Lexer(source, moduleContext = true)`：模块目标禁用 Annex B 的 `<!--` / `-->` HTML 注释；
2. `Parser.parseModule()`：返回 `ModuleProgram`，而不是 `Program`；
3. `ModuleProgram.items` 是源码顺序的 `ModuleItem` 列表；`import` / `export` 声明作为模块项建模，**不继承** `Statement`，因此解释器对 `Statement` 的穷尽 `when` 不需要为模块项增加分支；
4. Parser 在解析期预计算：
   - `requestedModules`：去重后的模块说明符，链接阶段按此顺序加载依赖；
   - `importEntries` / `exportEntries`：规范 Module Record 的静态表；
   - `varDeclarations` / `functionDeclarations` / `lexicallyDeclaredNames`：模块顶层声明表；
   - `hasUsingDeclaration`：顶层是否直接包含 `using`；
5. 模块恒为严格模式；顶层 `super` / `new.target`、Annex B 语法、重复顶层函数声明等按模块早期错误处理；
6. 表达式层：`import.meta` 解析为 `ImportMetaExpr`，`import(specifier[, options])` 解析为 `ImportCallExpr`；模块顶层 `await` 解析为 `UnaryOp("await", ...)` 并记录 `hasTopLevelAwait`，非 async 函数体中的 `await` 是语法错误；
7. `moduleContext` 会传播到模板字符串 `${...}` 的子解析器与子词法器，保证模块语境在嵌套表达式里一致；
8. 当前执行模型：`Engine.run` 接受 Script；`Engine.runModule`（实验性，需 opt-in）加载 Provider 模块图、链接、建模块环境/namespace 并同步求值，返回入口 namespace。模块求值帧 `this = undefined`、严格模式；TLA、动态 `import()` 执行、宿主文件/URL 加载器、module harness **尚未实现**。

已知限制：import attributes、`import.meta.resolve`、`export using` 尚未覆盖；模块执行与加载仍未排期。

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

### 6.1 Proxy 内部方法

`JSProxy` 不重写属性表，只保存 `[[ProxyTarget]]`、`[[ProxyHandler]]` 与撤销状态；
`Interpreter.getV` / `putV` 以及 `[[GetPrototypeOf]]`、`[[SetPrototypeOf]]`、`[[IsExtensible]]`、
`[[PreventExtensions]]`、`[[GetOwnProperty]]`、`[[DefineOwnProperty]]`、`[[HasProperty]]`、
`[[Delete]]`、`[[OwnPropertyKeys]]`、`[[Call]]`、`[[Construct]]` 在入口分派到 13 个 trap。
每个 trap 执行后按规范校验结果与 target 的不变量（不可配置属性、不可扩展 target、
重复/缺失键、`get`/`set` 的不可写不可配置属性等）；撤销后所有操作抛 TypeError。
Proxy 不进入普通属性表查询，因此 `with(proxy)`、`JSON.stringify`、`Array.isArray`、
`Function.prototype.toString` 等内建必须走同一内部方法入口。

### 6.2 TypedArray 整数索引 exotic

`JSTypedArray` 不把元素放进属性表，而是直接映射到 `JSArrayBuffer` 的
`[byteOffset, byteOffset + byteLength)`；`getOwnProperty` / `defineOwnProperty` /
`deleteOwnProperty` / `ownPropertyKeys` 按 CanonicalNumericIndexString 分流：
合法索引返回可写可枚举可配置的数据属性且不可删除；越界/非整数索引视为不存在，
写入静默失败；分离后 `IsValidIntegerIndex` 恒为 false，索引键消失、读取 undefined。
元素读写复用 `JSDataView` 的位模式组合，固定小端序；`BigInt64Array` /
`BigUint64Array` 的 `[[ContentType]]` 为 BigInt，读写分别经过 ToBigInt64 / ToBigUint64。

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
- 宿主生态补充 `TextEncoder`/`TextDecoder`（UTF-8、fatal/ignoreBOM/stream）、`structuredClone`
  （对象/数组/Map/Set/Date/RegExp/二进制/循环）与 `URL`/`URLSearchParams` 常用子集；
  这些能力只在 `:vibe-js-host`，核心仍不注册宿主概念；
- 时间原点在模块安装时确定（`timeOrigin` 取墙钟、`now()` 取单调时钟差值）；`performance.now()` 与
  `console.time` 都走 `HostEnvironment.monotonicClock`，墙钟回拨不会产生负值或错误差值，`Date` 仍使用墙钟；
- timeout 与 interval 都通过 `EventLoop` 的 `Scheduler` 执行，console 通过 `Console` 注入；执行入口和所有 async 恢复复用共享恢复队列，完整宿主入口再由运行时锁互斥。`clearTimeout`/`clearInterval` 可互相取消。

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

实现 `HostEnvironment`（Console / Clock / MonotonicClock / Scheduler）并传给 `Engine` 构造函数，
即可接管输出、时间与定时任务。另可覆写：

- `monotonicClock`：`performance.now()` 与 `console.time` 的差值基准（默认 `SystemMonotonicClock`，基于 `TimeSource.Monotonic`）；
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
  这些任务由事件循环统一拥有；`Engine.close()` / `EventLoop.close()` 负责取消并等待退出，见下文生命周期契约。
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

`Array.from`、迭代值收集、全局正则 match/replace 与 Set-like 迭代消费也在每轮
使用同一检查点，代替旧的十万长度/百万迭代 guard。未设 maxSteps 时按输入的实际
终止条件执行，不静默截断结果；需要有界执行时由宿主设置预算。普通数组长度仍遵循
2^32-1，array-like 长度使用 ToLength 的 2^53-1，Kotlin List 的 Int 表示上限明确抛
RangeError。JSArray 的同步宿主诊断输出保留截断并标注省略，不属于 JS 的 ToString。

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

**宿主栈分段。** `ExecutionTrampoline` 是共享的串行协程恢复队列，每 8 层逻辑
调用把 Kotlin 栈退回队列循环，保留堆上的协程状态与 JS 调用帧；不调用平台 API，
队列不主动切换到指定线程；内部挂起优先完成同步工作。async 启动时区分内部挂起与真正的宿主挂起/await，
调用方在同步前缀完成前不会提前取得 Promise；微任务或宿主回调返回前也会完成其触发的
内部恢复段，避免后续 JS 代码越过尚未执行完的同步工作。跨线程恢复先原子入队，
同一时刻只允许一个线程消费；队列空闲时由事件循环建立必要的微任务检查点。

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
- 词法/解析错误仍沿用 LexerException/ParseException 的诊断协议；需要结构化视图时使用
  `com.muedsa.js.diagnostics.toDiagnostic()`（类别、源码范围、来源文件名，异常类型与 message 不变）。
- 快照仅保存函数名、来源及源码范围，不保存 Interpreter、词法环境或完整源码。

`RuntimeErrorStackTest` 覆盖创建/重抛、精确行列、缓存/预解析来源、跨脚本函数、构造器与访问器、
eval/动态函数、async 恢复、Promise 转发、宿主错误、资源释放和 12,000 层同步/异步错误链。

### 8.8 事件循环生命周期与并发契约

`Engine.close()` 委托给 `EventLoop.close()`，两者均为 suspend 方法。关闭先停止接收新任务，
取消尚未触发的定时器、事件循环自有协程以及在途/排队的宿主执行入口，再等待清理结束。
正常返回后没有在途运行时代码或待执行微任务；重复关闭安全。调用方传入的父 scope 和
调用 `Engine.run` 的宿主父 Job 不会被整体取消。关闭后执行、排队与空闲等待抛
`EventLoopClosedException`（CancellationException 子类），不会变成 JS 错误或 Promise 拒绝。
解析是纯操作，`Engine.parse` 仍可使用。已经产生的全局写入和 Promise 结果不会回滚，
被中止的 async 所对应的 Promise 可能保持 pending。

关闭采用协作式取消：可以停止带检查点的 JS 死循环，也能取消挂起的宿主调用；不抢占
阻塞且不响应取消的宿主代码。非取消清理会延长等待。关闭等待本身可被宿主取消，
此时循环仍保持关闭状态，可再次调用 close 等待剩余清理。当前运行时任务内部调用
close/runUntilIdle 或重入同一循环的 runTask 会立即抛 IllegalStateException，避免自等待死锁。

`eventLoop.pendingTasks` 返回 `PendingTasks` 瞬时快照：

| 字段 | 含义 |
|---|---|
| `microtasks` | 尚未执行的微任务 |
| `timers` | 尚未开始执行的定时器；interval 表示下一次 tick |
| `asyncTasks` | 自有 scope 中未完成的协程，包括挂起的 async 和自动微任务检查点 |
| `executions` | 在途宿主入口，包括等待执行锁的 Engine.run / 定时器回调 |
| `rejectionNotifications` | 待确认并报告的 Promise 拒绝 |
| `total` / `isIdle` | 分类计数总和 / 是否全部为空 |

快照用于观测，不替代访问全局对象的同步边界；并发变化时各分类不保证来自同一个原子时刻。
`runUntilIdle()` 推进微任务并等待上述任务全部结束，包括未来定时器和仍在等待宿主的 async。
它不跳过真实时间，不主动触发自定义 Scheduler，也不自动取消 interval；宿主应按需要
使用 withTimeout，测试中则推进自己的调度器。取消这个等待不会关闭事件循环。
返回只表示该时刻空闲，不阻止其他线程随后提交新任务。

**串行化分两层。** Engine.run、EventLoop.runTask 和定时器回调的完整调用由 runtimeMutex
互斥，挂起时仍保留入口所有权；所有同步执行段、async 恢复和微任务还经过同一个
ExecutionTrampoline，禁止两个线程同时修改 JS 运行时。底层 RuntimeQueue 使用 Kotlin
通用标准库原子引用和不可变链，实现并发入队与摊销常数复杂度的 FIFO，不使用平台 API。

async 同步前缀仍执行到首次真实挂起；内部栈分段优先完成同步工作。已就绪的 async
在微任务/宿主调用检查点恢复，宿主异步返回后也会自动推进其微任务，无需额外 run 空脚本。
串行化不代表固定线程亲和性，宿主回调可能在完成等待的线程执行。需要 UI/特定线程的
宿主实现应自行切换上下文。异步函数、异步内建和延迟回调使用独立解释器帧，
共享全局环境与不可变来源链，防止恢复时覆盖尚未返回的词法环境。

**宿主扩展边界。** 并发执行应使用 Engine.run，低层操作使用 `eventLoop.runTask { ... }`；
仅获取旧的 runtimeMutex 或直接使用 Interpreter/globalObject/registry 不提供完整并发保证。
install 和全局初始化应在启动并发任务前完成。不得通过外部 scope/UNDISPATCHED 绕过执行入口。
独立定时器或 run 之外恢复的 async 仍没有独立步数预算，原有配额边界不变。

setInterval 现在复用 Scheduler，在本次回调及微任务完成后安排下一次；tick 不重叠，
取消当前 interval 不截断正在执行的回调。关闭或取消后，迟到的调度唤醒会被忽略。
自定义 Scheduler 仍需兑现取消协议以释放自身资源；事件循环不会取消宿主共享的整个调度器。

回归覆盖 `EventLoopLifecycleTest`、`HostEventLoopLifecycleTest` 及 JVM 的
`EventLoopConcurrencyTest`，包括关闭等待清理、在途 run/定时器取消、迟到唤醒、空闲等待、
词法帧恢复、多线程竞争与关闭纯计算死循环；既有万层递归和微任务顺序回归保持生效。

### 8.9 生成器与异步生成器的暂停/恢复

生成器对象（`JSGenerator`）复用同一套状态机（suspendedStart / suspendedYield / executing /
completed），函数体与恢复方通过两条容量为 1 的 `Channel` 交换消息：请求通道投递
`Next` / `Return` / `Throw`，步骤通道回传 `Yielded` / `Returned` / `Threw` / `LimitReached`。
函数体在首次 `next()` 时启动，但 `FunctionDeclarationInstantiation`、默认参数、`this` 与
`arguments` 已在调用生成器函数时完成绑定，因此参数初始化异常在调用点同步抛出；在首次
`next()` 之前调用 `return()` / `throw()` 不会执行函数体。

同步生成器直接在调用方协程里等待函数体步骤。async generator 把每个请求变成带 Promise
capability 的队列项，由单一驱动协程按 FIFO 串行恢复函数体：`next` / `return` / `throw`
立即返回 Promise，capability 在驱动步骤中落定，用户注册的 `.then` 回调按微任务顺序执行；
函数体 `await` 挂起期间后续请求只排队，不会并发恢复。宿主取消与 `ExecutionLimitException`
不经过 JS 异常路径，由事件循环边界原样传播；生成器随后进入 completed，不能再次恢复。

## 9. 性能优化的观察点

> 这些是可替换的内部实现点，当前不排入 P0/P1；B2 属于长期项，恢复前需由基准证明热点。替换时不得改变语义与公共 API。

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

`./gradlew :vibe-js-core:jvmBenchmarkBenchmark` 运行 kotlinx-benchmark（JMH）基准，共七组：引擎生命周期、解析与缓存、解释器吞吐、对象与环境、异步与事件循环、标准库、集合。

- 只跑某一组：`-PbenchmarkFilter=<类名>`（例如 `CollectionBenchmark`）；
- 环境信息：`./gradlew :vibe-js-core:benchmarkEnvironment` 写入 `build/reports/benchmarks/environment.md`（JDK/OS/CPU/内存/GC/Gradle/Kotlin）；
- 原始 JSON：`build/reports/benchmarks/main/<时间戳>/jvmBenchmark.json`。

解释器吞吐（2 × 300ms 预热、10 × 300ms 测量，2026-11-04 本机 JDK 17、12 核、G1；这是 B1 历史基线）：

| 场景 | `Engine.run`（含解析） | 预解析执行 |
|---|---:|---:|
| 算术 `1 + 2 * 3` | ~142,000 ops/s | ~363,000 ops/s |
| 字符串/对象拼接 | ~95,000 ops/s | ~202,000 ops/s |
| 函数调用 | ~95,000 ops/s | ~271,000 ops/s |
| 100 次数组 push 循环 | ~6,200 ops/s | ~7,100 ops/s |

2026-11-08 的 S5 复测（相对上述 B1，仅纵向对比）记录了以下回归：

| 指标 | 变化 |
|---|---:|
| `propertyGet` | **+12.7%** |
| `propertySet` | **+9.6%** |
| 数组循环 | **−19%** |
| `engineCreate` | **+37%** |

可疑路径：内部方法 suspend 化后的全局标识符双查、`getPropertyFrom` / `putProperty` 每层 Proxy/TypedArray 分支、`CoreBuiltins` 安装量。B1 七组基准尚未覆盖 Generator / BigInt / TypedArray / Proxy 专项，B2 恢复前需先补基准并复测；见[后续路线](OPTIMIZATION_ROADMAP.md)。

集合（单次操作，1k / 10k / 100k 规模基本持平；B1 历史值）：Map `get` 6–11 ns、`has` 4–6 ns、`set` 18–19 ns；`size` 读取约 0.02 ns/次。

数值仅用于纵向对比：空闲时波动约 ±10%，高负载时显著偏低，应择机复测。`Engine.run` 的小脚本吞吐主要受词法/语法解析限制（算术场景解析约占总成本 60%），重复执行应使用 `Engine.parse` 复用 AST；`arrayLoop` 的解析占比较低，主要受逐条语句执行与 `push` 调用开销影响，是后续优化（槽位化环境、内联缓存）的首要观察点。

`Engine(parseCacheSize = N)` 的重复 `run(source)` 在本次复测中算术约 366,000 ops/s、数组 push 循环约 7,100 ops/s，接近预解析执行；缓存查找与互斥开销使算术略低于纯预解析。

## 10. 阶段状态

- **阶段 A（已完成）**：内核重构、严格模式框架、最小标准库、JVM/JS/Wasm 测试。
- **阶段 B（已完成）**：ES5 语法/内建补全（do-while、for-in、标签、delete/void/in/instanceof、
  with、对象字面量扩展、正则、Date、arguments、盒装原始值、freeze/seal、JSON 选项）。
- **阶段 C（已完成）**：ES2015 常用语法与内建（箭头、模板/标签模板、解构、默认/rest、
  spread、for-of、class/extends/super/new.target、Symbol、Map/Set/WeakMap/WeakSet、
  Promise、async/await）。
- **阶段 D（已完成）**：独立宿主模块 `:vibe-js-host`（console、定时器、window/self、
  queueMicrotask、atob/btoa、performance）。
- **L4.5（已完成，2026-11）**：宿主生态补充 TextEncoder/TextDecoder（UTF-8）、structuredClone
  与 URL/URLSearchParams 常用子集。
- **L 系列（已完成，2026-11）**：同步/异步 Generator、`for await`、Proxy/Reflect、BigInt、
  ArrayBuffer/DataView/TypedArray（含 BigInt 视图与 detach）；对应特性已纳入 Test262 ES2015 基线。
- **模块（阶段 0–8 实验性实现）**：`Parser.parseModule` 支持 `import` / `export`
  基础形式、`import.meta`、`import()` 表达式与模块顶层 `await` 语法，并预计算静态表、类型化绑定、
  entry range 与 `hasTopLevelAwait`；公共 `ModuleProvider` / `InMemoryModuleProvider`、内部
  `ModuleGraphResolver`、`ModuleExportResolver` / `ModuleLinker`、`ModuleEnvironment` /
  `ModuleDeclarationInstantiator`、`JSModuleNamespace` 与 `Engine.runModule` 已提供；
  TLA、动态 `import()` 执行、宿主加载器与 Test262 module harness **尚未实现**，API 需 opt-in。
- **当前无计划/按需**：WeakRef / FinalizationRegistry、WeakMap/WeakSet 的真实弱引用（M2）、Intl、DOM/fetch 等真实 Web API、SharedArrayBuffer/Atomics、
  resizable/transfer ArrayBuffer 无计划；R1 资源约束已覆盖主要路径；性能优化（B2）与 API 契约（PKG1）无计划，
  S5.2 只做性能复测观测（见[后续路线](OPTIMIZATION_ROADMAP.md)）。
- **已发布**：`0.0.0-SNAPSHOT` 快照推送到 GitHub Packages（JVM/JS/Wasm/Android 与各 Native 目标，
  见 [publish.yml](../.github/workflows/publish.yml)），尚未发布正式版。
