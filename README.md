# Vibe JS

Vibe JS 是一个使用 Kotlin 多平台从零实现的轻量级 JavaScript 运行时。

- 目标：从单个脚本执行起步，覆盖 ES5 / ES2015 常用语法（含 `async/await`、Generator / async generator），并已实现 Proxy / Reflect、BigInt、TypedArray / ArrayBuffer / DataView；模块侧已支持 `Parser.parseModule` 语法解析（含 `import.meta` / `import()` / 顶层 `await` 语法），但尚不能链接或执行；
- 定位：通用 Kotlin 库，核心不绑定任何浏览器/平台原生 API；
- 构建目标：JVM、Android、JS、Wasm、Linux、Windows、macOS、iOS、tvOS、watchOS；
- 宿主能力（console、定时器、window、performance、TextEncoder/TextDecoder、structuredClone、URL 等）位于独立的 `:vibe-js-host` 模块。

## 使用方式

核心入口是 `Engine`，所有执行方法都是 `suspend`：

```kotlin
import com.muedsa.js.runtime.Engine
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val engine = Engine()
    val result = engine.run("""
        class Person {
            constructor(name) { this.name = name; }
            greet() { return "hi " + this.name; }
        }
        new Person("vibe").greet();
    """)
    println(result.toPrimitiveString()) // hi vibe
}
```

同一个 `Engine` 可以重复执行多段脚本，共享全局环境：

```kotlin
engine.run("let count = 1;")
engine.run("count += 1; count;") // 2
```

若同一段脚本需要反复执行，可先预解析再复用 AST，避免重复词法/语法分析：

```kotlin
val program = engine.parse("1 + 2 * 3")
engine.run(program) // 7
engine.run(program) // 每次执行都会重新实例化脚本声明
```

同一段源码需要反复执行时，也可开启有界 AST 缓存（默认关闭；含标签模板的源码不缓存）：

```kotlin
val engine = Engine(parseCacheSize = 16)
engine.run("1 + 2 * 3") // 首次解析并缓存
engine.run("1 + 2 * 3") // 复用 AST
```

### 引入依赖

项目暂不发布正式版，所有模块版本统一为 `0.0.0-SNAPSHOT`；每次分支推送由
[`.github/workflows/publish.yml`](.github/workflows/publish.yml) 构建并推送到 GitHub Packages。

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/muedsa/vibe-js")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("com.muedsa.js:vibe-js-core:0.0.0-SNAPSHOT")
    implementation("com.muedsa.js:vibe-js-host:0.0.0-SNAPSHOT")
}
```

发布目标为 JVM / JS / WasmJs / Android；Native（含 Apple 目标与 mingwX64）尚未发布到该仓库。

### 能力示例

以下 JavaScript 片段由 `engine.run(...)` 执行；detach 示例为宿主 Kotlin 代码。

生成器与异步迭代：

```js
function* range(start, end) {
    for (let i = start; i < end; i += 1) yield i;
}
[...range(1, 4)]; // [1, 2, 3]

async function sum() {
    let total = 0;
    for await (const value of [Promise.resolve(1), 2, 3]) total += value;
    return total;
}
sum().then(function (total) { globalThis.sumResult = total; });
// 脚本结束后微任务被冲刷，globalThis.sumResult === 6
```

Proxy / Reflect：

```js
const target = { answer: 41 };
const proxy = new Proxy(target, {
    get(object, key, receiver) {
        return Reflect.get(object, key, receiver) + 1;
    },
});
proxy.answer; // 42
```

TypedArray / ArrayBuffer / DataView：

```js
const buffer = new ArrayBuffer(8);
new DataView(buffer).setUint32(0, 0x01020304, true); // littleEndian = true
new Uint8Array(buffer).join(","); // "4,3,2,1"

const big = new BigInt64Array(buffer); // TypedArray 固定小端序
big[0] = -1n; // 补码写入
big[0]; // -1n
```

BigInt：

```js
const mask = (1n << 64n) - 1n;
mask.toString(16); // "ffffffffffffffff"
typeof mask; // "bigint"
```

分离（detach）语义由宿主触发，JS 没有公开 API；Test262 运行器使用 `$262.detachArrayBuffer`，
库内可直接调用 `JSArrayBuffer.detach()`：

```kotlin
import com.muedsa.js.runtime.value.JSArrayBuffer

val buffer = engine.run("new ArrayBuffer(8)") as JSArrayBuffer
buffer.detach()
// 分离后 byteLength 为 0；slice、DataView/TypedArray 读写按规范抛 TypeError
```

### 模块语法与执行（experimental）

模块语法通过 `Parser.parseModule()` 解析，不会进入 `Engine.run`。可以读取静态表：

```kotlin
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.parser.Parser

val module = Parser(
    Lexer("import value from './value.js'; export default value;", moduleContext = true).tokenize(),
).parseModule()
module.requestedModules // ["./value.js"]
module.strict // true
```

实验性 `Engine.runModule` 由宿主实现 `ModuleProvider` 提供源码；核心不读取文件、URL 或网络：

```kotlin
import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.module.ExperimentalModuleApi
import com.muedsa.js.runtime.module.ModuleProvider
import com.muedsa.js.runtime.module.ModuleSource

@OptIn(ExperimentalModuleApi::class)
suspend fun runModuleExample() {
    val engine = Engine()
    val provider = ModuleProvider { request ->
        when (request.specifier) {
            "./dep.js" -> ModuleSource(
                id = "dep",
                source = "export const value = 1;",
                sourceName = "./dep.js",
            )
            else -> null
        }
    }
    val namespace = engine.runModule(
        ModuleSource("entry", "import { value } from './dep.js'; export { value };"),
        provider,
    )
}
```

模块目标恒为严格模式，禁用 Annex B HTML 注释与 Annex B 语法；脚本目标继续拒绝
`import` / `export`。阶段 0–8 已接通实验性 `Engine.runModule`（Provider、图加载/链接、环境、namespace、
`import.meta` 与同步求值，需 `@ExperimentalModuleApi` opt-in）；模块加载器、TLA 执行与动态
`import()` 执行尚未实现。详见
[兼容性矩阵](docs/COMPATIBILITY.md) 与[后续路线](docs/OPTIMIZATION_ROADMAP.md)。

### 取消与超时

`Engine.run` 支持协作式取消：解释器定期检查当前协程的取消状态，
`CancellationException`（含超时）原样交还 Kotlin 调用方。
在 JVM 上，可将纯计算脚本放到 `Dispatchers.Default`，让超时计时器独立发出取消：

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// 以下代码在协程中执行。
val engine = Engine()
try {
    withContext(Dispatchers.Default) {
        withTimeout(250) { engine.run("while (true) {}") }
    }
} catch (error: TimeoutCancellationException) {
    println("脚本执行超时")
}
engine.run("1 + 2") // 取消后可复用同一 Engine
```

取消不是事务回滚：已完成的全局写入保留，剩余微任务丢弃；JS 的 `catch`、
`finally` 和用户资源释放器不参与宿主取消处理。检查点不主动让出执行权，
因此 JS/Wasm 单线程上，`withTimeout` 不能保证打断纯计算死循环。
解析、单次耗时内建/宿主调用、独立 async 协程与定时器的生命周期边界见
[架构说明](docs/ARCHITECTURE.md#85-协作式取消)。

### 执行配额

通过 `EngineLimits` 设置每次执行的步数上限和逻辑调用深度：

```kotlin
import com.muedsa.js.runtime.EngineLimits
import com.muedsa.js.runtime.exception.ExecutionLimitException

val engine = Engine(limits = EngineLimits(maxSteps = 100_000, maxCallDepth = 16_384))
try {
    engine.run("for (;;) {}")
} catch (error: ExecutionLimitException) {
    println("执行步数超过 ${error.maxSteps}")
}
engine.run("1 + 2") // 每次 run 使用新预算
```

`maxSteps` 默认 `null`（不限步数），`maxCallDepth` 默认 **16,384**，也可设为 `null`
关闭深度限制；非空值必须大于零。步数耗尽抛 Kotlin `ExecutionLimitException`，JS 无法捕获或通过
finally 继续执行；调用深度超限则抛可被 JS 捕获的
`RangeError: Maximum call stack size exceeded`。严格模式尾调用不累计深度。
解释器通过内部协程恢复队列分段执行深层调用，减少 Kotlin 物理栈的累积。
`JSON.parse` / `JSON.stringify` 的 `maxJsonDepth` 超限同样转成可捕获的 `RangeError`；
将 `maxRegexSteps` 显式降到默认值以下时，常见嵌套量词正则会被路由到受该预算约束的回退引擎；
Kotlin Regex 快路径不消耗该预算（边界已记录在兼容性文档）。
不同 JS 引擎的栈上限由实现及运行环境决定，16,384 是本库的默认逻辑调用上限。

步数检查不依赖计时器，因此也能在 JS/Wasm 单线程上终止脚本死循环。
计数覆盖本次 `run` 中的脚本、本轮微任务及其执行期间的 async 工作，
Array.from、迭代值收集、全局正则 match/replace 与 Set-like 消费也逐轮检查。
解析及其他尚未接入检查点的内建/宿主内部工作仍不计入；它不是时间或内存配额。
详见[执行配额与边界](docs/ARCHITECTURE.md#86-执行配额)。

### 错误栈与脚本来源

`Engine.run` 和 `Engine.parse` 支持脚本名与非负行偏移。`Error.prototype.stack`
保存错误创建或首次抛出时的函数与源码位置；重抛、Promise 拒绝转发和延迟读取不会覆盖它：

```kotlin
val engine = Engine()
val stack = engine.run(
    "function fail() { return new Error('出错了'); }\nfail().stack;",
    sourceName = "plugin.js",
    lineOffset = 20, // 源码第 1 行显示为文件第 21 行
)
println(stack.toPrimitiveString())
// Error: 出错了
//     at fail (plugin.js:21:26)
//     at <global> (plugin.js:22:1)
```

函数保留定义脚本，跨脚本调用时各帧显示各自来源。async 恢复后仍保留创建调用链，
异步边界标为 `async`；Kotlin 捕获的 `JSException.stack` 提供结构化帧，
`toString()` 使用相同的格式。默认脚本名为 `<anonymous>`，行列从 1 开始。
详见[错误栈的捕获与边界](docs/ARCHITECTURE.md#87-错误栈)。

### 事件循环生命周期

使用完引擎后调用 `close()`，取消定时器和 async 协程，并等待在途脚本/回调完成清理：

```kotlin
import com.muedsa.js.host.HostModule
import kotlinx.coroutines.withTimeout

val engine = Engine()
engine.install(HostModule(engine.eventLoop, engine.host.console))
try {
    engine.run("setTimeout(() => console.log('完成'), 10);")
    println(engine.eventLoop.pendingTasks) // 分类计数，提供 total / isIdle
    withTimeout(1_000) { engine.eventLoop.runUntilIdle() }
} finally {
    engine.close() // suspend；也可直接调用 engine.eventLoop.close()
}
```

`runUntilIdle()` 包含未来定时器和等待中的 async；未清除的 interval 会使它持续等待。
取消等待不会关闭引擎。关闭可重复调用，关闭后的新任务抛 `EventLoopClosedException`；
阻塞宿主代码仍须自行响应取消。Engine.run、定时器与 async 恢复共享串行执行队列，
可以从多个线程提交，但不保证固定线程亲和性。低层全局操作需通过 `eventLoop.runTask`。
详见[生命周期与并发契约](docs/ARCHITECTURE.md#88-事件循环生命周期与并发契约)。

### 安装宿主能力

```kotlin
import com.muedsa.js.host.HostModule

val engine = Engine()
engine.install(HostModule(engine.eventLoop, engine.host.console))
engine.run("console.log('hello'); setTimeout(function () { console.log('later'); }, 10);")
```

`HostModule` 提供 `window`/`self`、`console`（含 time/timeEnd）、`setTimeout`/`clearTimeout`、
`setInterval`/`clearInterval`、`queueMicrotask`、`atob`/`btoa`、
`performance.now()`/`performance.timeOrigin`，以及 UTF-8 的
`TextEncoder`/`TextDecoder`、`structuredClone` 与 `URL`/`URLSearchParams` 常用子集，
全部由纯 Kotlin 实现，不绑定平台原生 API。

## 已支持

### 语法

- 声明：`var` / `let` / `const`、函数声明、`class` 声明/表达式
- 语句：块、`if/else`、`while`、`do-while`、`for`、`for-in`、`for-of`、`switch`、
  `break`/`continue`（含标签）、`return`、`throw`、`try/catch/finally`、`with`（非严格）、`debugger`、空语句
- 函数：箭头函数、默认参数、rest 参数、闭包、递归、`this`、构造函数、
  `call/apply/bind`、`name/length`、`arguments`、`new.target`
- 类：构造器、实例/静态方法、getter/setter、`extends`、`super()`、`super.method()`、静态初始化块、公有/私有字段与方法（含 `#x in obj`）
- 表达式：字面量、数组（含空洞/展开）、对象（简写/方法/getter/setter/计算键/展开）、
  模板字符串/标签模板、正则字面量、解构声明与赋值（含函数参数/catch）、spread、
  成员访问、调用、`new`、赋值/复合赋值/逻辑赋值、自增自减、一元/二元/三元、`??`、
  逗号、可选链 `?.`、`typeof`、`delete/void/in/instanceof`
- 严格模式：`"use strict"` 脚本/函数级切换
- 生成器：`function*`、`yield`、`yield*`、`return`/`throw`/`next`、生成器方法（含 `async *method()`）
- 异步：`async` 函数/箭头函数、`await`、Promise 微任务、async generator
- 异步迭代：`for await...of`、`Symbol.asyncIterator`、`%AsyncIteratorPrototype%` / `%AsyncGeneratorPrototype%`
- BigInt：`123n` 字面量（含 `0x`/`0o`/`0b` 前缀与 `n` 后缀）、算术/位运算/比较/幂运算与 BigInt 转换
- 模块语法（仅解析）：`import` / `export` 基础形式、`import.meta`、`import()` 表达式、模块顶层 `await` 语法；`Parser.parseModule()` 解析并预计算静态表，链接/求值/加载未实现
- 资源管理：`using` / `await using`、DisposableStack / AsyncDisposableStack
- 严格模式尾调用优化（含 try/catch/finally 的待处理语义阻断）

### 标准内建

- **全局**：`undefined`、`NaN`、`Infinity`、`globalThis`、`parseInt`、`parseFloat`、`isNaN`、`isFinite`
- **Object**：构造器；`keys/values/entries/assign/create/getPrototypeOf/setPrototypeOf/
  getOwnPropertyDescriptor/getOwnPropertyNames/getOwnPropertySymbols/defineProperty/
  defineProperties/is/freeze/seal/preventExtensions/isFrozen/isSealed/isExtensible`
- **Function**：`call/apply/bind/toString`、动态 `Function` 构造器
- **Array**：常用迭代/查找/增删/排序方法，`fill/copyWithin/at/entries/keys/values`；
  静态 `isArray/of/from`
- **String**：常用字符/查找/切片/替换/填充方法，`String.raw`、`fromCharCode/fromCodePoint`、
  `normalize`（NFC/NFD/NFKC/NFKD）
- **Number**：常量、`toString(radix)/toFixed/toExponential/toPrecision`、
  静态 `isNaN/isFinite/isInteger/parseInt/parseFloat`
- **Boolean**、**Math**、**JSON**（`parse`/`stringify` 支持 reviver/replacer/space）
- **RegExp**：字面量/构造器、`exec/test/toString`、`d/g/i/m/s/u/v/y` 标志、命名捕获组、后向断言、
  Unicode 17.0.0 属性转义（`\p{...}`/`\P{...}`）、`v` 扩展字符类（集合运算、属性字符串、`\q`）
- **Date**：构造、`Date.now/parse/UTC`、常用 getter/setter、`toISOString/toJSON`；本地时间通过宿主 `utcOffsetMillis`（默认 UTC）换算
- **Error**：`Error`、`TypeError`、`RangeError`、`ReferenceError`、`SyntaxError`、`EvalError`、`URIError`、`SuppressedError`、`AggregateError`、`Error.isError`
- **Symbol**：`Symbol()`、`Symbol.for/keyFor`、知名 Symbol（iterator/asyncIterator/toStringTag 等）
- **BigInt**：`BigInt()`、`asIntN/asUintN`、任意精度算术/位运算/移位/比较、`toString(radix)`；实现上限 `MAX_RESULT_BITS = 2^24` 位
- **Proxy**：13 个 trap（`get/set/has/deleteProperty/ownKeys/getOwnPropertyDescriptor/defineProperty/getPrototypeOf/setPrototypeOf/isExtensible/preventExtensions/apply/construct`）、`Proxy.revocable`、撤销状态与不变量校验
- **Reflect**：13 个方法（`apply/construct/defineProperty/deleteProperty/get/getOwnPropertyDescriptor/getPrototypeOf/has/isExtensible/ownKeys/preventExtensions/set/setPrototypeOf`）
- **Generator / AsyncGenerator**：`%GeneratorPrototype%` / `%AsyncGeneratorPrototype%`、`next/return/throw` 协议、`yield*` 委托、异步生成器的 Promise 队列
- **ArrayBuffer / DataView / TypedArray**：ArrayBuffer（`byteLength/slice/isView`、detach）、DataView 8 种数值 + 4 种 BigInt 读写（大小端由 `littleEndian` 参数控制）、11 种 TypedArray（9 种数值 + `BigInt64Array`/`BigUint64Array`，固定小端序）、构造/`from`/`of`/species/迭代/`subarray`/`set`/排序查找；不支持 resizable / transfer / SharedArrayBuffer / Atomics
- **集合**：`Map`、`Set`、`WeakMap`、`WeakSet`，配套迭代器协议
- **Promise**：`resolve/reject/all/race/allSettled/any/allKeyed/allSettledKeyed`、`then/catch/finally`、`withResolvers/try`
- **Iterator**：抽象构造器、`Iterator.from/concat/zip/zipKeyed` 与 helpers（map/filter/take/drop/flatMap/reduce/toArray 等）；`%AsyncIteratorPrototype%` 与 `for await` 消费
- **其他**：Symbol 知名方法、JSON 源文本、`RegExp.escape`、ES2025 Set 方法集

### 当前无计划与边界

当前能力边界（决策与排期见 [docs/OPTIMIZATION_ROADMAP.md](docs/OPTIMIZATION_ROADMAP.md)）：

- **模块（实验性）**：`Parser.parseModule` 已支持 `import` / `export` 基础形式、`import.meta`、`import()` 表达式、模块顶层 `await` 语法、类型化顶层绑定与 TLA 标记；`Engine.runModule` 已接通 Provider、链接、环境、namespace、`import.meta` 与同步求值，需 `@ExperimentalModuleApi` opt-in；模块加载器、TLA 执行、动态 `import()` 执行与 Test262 module harness **未实现/无计划**；
- **无计划**：顶层 await 的执行（L4.7）、WeakRef / FinalizationRegistry；
- **宿主生态（已实现子集）**：`TextEncoder` / `TextDecoder`（UTF-8、fatal/ignoreBOM/stream）、`structuredClone`（对象/数组/Map/Set/Date/RegExp/二进制/循环）、`URL` / `URLSearchParams` 常用能力，均位于 `:vibe-js-host`；
- **不在当前范围**：Intl、DOM / fetch / localStorage 等真实 Web API（宿主模块只提供通用宿主能力）、`ShadowRealm`、`SharedArrayBuffer` / `Atomics`、`Temporal`、`Float16Array`、装饰器、resizable / transfer ArrayBuffer；
- **已实现但有限制**：WeakMap/WeakSet 仍是强引用存储，未实现 GC 弱语义（M2 无计划）；BigInt 结果受 `MAX_RESULT_BITS` 限制；import attributes、`import.meta.resolve`、`export using` 未支持（随模块执行无计划）；
- **无计划/按需**：性能优化（B2）与 API 契约（PKG1）无计划；R1 资源约束已覆盖源码/AST/对象/JSON/正则/事件循环队列等主要路径；
- **已知不完整**：完整 ASI 边界规则。

Unicode 属性/标识符/归一化/大小写映射数据表固定为 Unicode 17.0.0，可由 `tools/generate_unicode_data.py` 重新生成。

## 架构与扩展

```
lexer -> parser -> AST (Program / ModuleProgram)
                         |
                         +-> Program -> Executor(AstInterpreter) -> Engine
                         |                +-> Environment / Intrinsics / Builtins
                         |                +-> EventLoop / Promise / async-await
                         |                +-> Generator channels / Proxy internal methods / binary views
                         |
                         +-> ModuleProgram -> Parser.parseModule（仅解析；链接/求值/加载无计划）
```

- `Engine`：门面，持有全局对象、内建注册表、事件循环与解释器；当前只执行 Script；
- `Parser.parseModule` / `ModuleProgram`：模块目标解析入口与独立 AST 层次（`ModuleItem` 不继承 `Statement`），预计算 `requestedModules` / `ImportEntry` / `ExportEntry`，不参与求值；
- `Executor`：执行器接口，当前实现是 AST 解释器，后续可替换为编译执行器而不改语义；
- `BuiltinModule`：内建模块注册接口；标准库（`CoreBuiltins`）与宿主模块（`HostModule`）都通过它安装；
- `HostEnvironment`：宿主能力抽象（Console / Scheduler / Clock），并可注入 Date 时区偏移与 Promise 拒绝生命周期回调；
- `JSGenerator`：Generator / async generator 对象，函数体与恢复方通过两条容量 1 的通道交换请求与步骤；
- `JSProxy`：Proxy exotic 对象，只保存 target/handler/撤销状态，trap 与不变量由解释器内部方法分派；
- `JSTypedArray` / `JSArrayBuffer` / `JSDataView`：二进制存储与视图，TypedArray 按整数索引 exotic 语义合成索引属性；
- `EventLoop`：微任务/定时器调度、串行恢复、任务快照、空闲等待和关闭；
- `PropertyDescriptor` + `JSObject`：属性描述符与原型链模型。

详细设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，兼容性矩阵见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)，解析一致性基线见 [docs/CONFORMANCE.md](docs/CONFORMANCE.md)。

## 构建与测试

构建使用 **JDK 21**（Gradle 9.8.2 要求运行 JVM 17–27）。发布的 JVM 字节码 target 为 **1.8**，因此
JVM 产物最低运行 **Java 8**；Android `minSdk` 为 **24**。本机或 CI 只升级构建 JDK 不会改变产物运行门槛。

```bash
# 核心
./gradlew :vibe-js-core:jvmTest
./gradlew :vibe-js-core:jsNodeTest
./gradlew :vibe-js-core:wasmJsNodeTest

# 宿主模块
./gradlew :vibe-js-host:jvmTest
./gradlew :vibe-js-host:jsNodeTest
./gradlew :vibe-js-host:wasmJsNodeTest

# Windows Native（CI 另覆盖 Linux/macOS/iOS Native）
./gradlew :vibe-js-core:mingwX64Test
./gradlew :vibe-js-host:mingwX64Test

# 基准测试：环境记录 + 全量运行（约 1.5 分钟）
./gradlew :vibe-js-core:benchmarkEnvironment
./gradlew :vibe-js-core:jvmBenchmarkBenchmark
# 只跑某一组（八组见 docs/ARCHITECTURE.md §9.1）
./gradlew :vibe-js-core:jvmBenchmarkBenchmark -PbenchmarkFilter=CollectionBenchmark
./gradlew :vibe-js-core:jvmBenchmarkBenchmark -PbenchmarkFilter=ModuleBenchmark

# 解析一致性基线（首次运行自动下载 test262-parser-tests；脚本 + 模块语料）
./gradlew :vibe-js-conformance:parserConformance
# 存在语法不一致时失败（脚本与模块共用 strict 门禁）
./gradlew :vibe-js-conformance:parserConformance -Pstrict

# Test262 ES5 语义基线（首次运行自动下载 test262）
./gradlew :vibe-js-conformance:test262Conformance
# 限制用例数/并行度
./gradlew :vibe-js-conformance:test262Conformance -Ptest262Limit=1000 -Ptest262Jobs=4

# Test262 ES2015 特性子集（默认报告模式；严格门禁使用 -Pstrict）
./gradlew :vibe-js-conformance:test262Es2015Conformance

# 真实库 UMD 冒烟（首次自动下载固定版本；严格门禁使用 -Pstrict）
./gradlew :vibe-js-conformance:librarySmoke
# 与 Node 的差分基线（需要 Node 24；严格门禁使用 -Pstrict）
./gradlew :vibe-js-conformance:differentialConformance
```

当前基线（2026-11-10）：

| 检查 | 结果 |
|---|---|
| core JVM | **1169 / 1169** |
| core JS（Node）/ Wasm（Node） | 各 **1153 / 1153** |
| host JVM / JS / Wasm / Native | 各 **38 / 38** |
| conformance 单元测试 | **39 / 39** |
| parser 脚本 | **1,907/1,907**；fail 674/682（允许 8）；early **5/605**（允许 5） |
| parser 模块 | **76/76 + 76/76 + 47/47 + 63/63**（early 拒绝）；允许 0（262 个 `.module.js`） |
| Test262 ES5 | **15,248/15,248**（跳过 0） |
| Test262 ES2015 | **61,786/61,786**，失败 0、崩溃 0，跳过 **324**（module flag 322 + 宿主钩子 2） |
| Node 差分 / 真实库冒烟 | **1108/1108**、**375/375**（102 个库） |

详细口径、跳过原因与推进顺序见 [docs/CONFORMANCE.md](docs/CONFORMANCE.md)。

## 目录结构

- `vibe-js-core/`（Gradle 模块 `:vibe-js-core`）
  - `src/commonMain/kotlin/com/muedsa/js/`
    - `lexer/`：词法分析、Token、源码范围
    - `parser/`：递归下降解析器（`parseProgram` 脚本目标 / `parseModule` 模块目标）
    - `ast/`：不可变 AST 节点（`Program` / `Statement` / `Expression`，以及 `Modules.kt` 的 `ModuleProgram` / `ModuleItem`）
    - `runtime/`：引擎、执行器、环境、类型转换、事件循环、Promise、资源约束、扩展点
    - `runtime/value/`：JS 值、对象、函数、数组、集合、Promise、Generator/Proxy/BigInt、二进制视图与属性描述符
    - `builtins/`：ECMAScript 标准内建（Object/Function/Array/String/Number/BigInt/Proxy/Reflect/Generator/ArrayBuffer/TypedArray 等）
    - `diagnostics/`：结构化诊断适配层（词法/语法异常 → 类别、范围、来源）
  - `src/commonTest/`：核心单元测试
- `vibe-js-host/`（Gradle 模块 `:vibe-js-host`）
  - `src/commonMain/kotlin/com/muedsa/js/host/`：宿主模块（console/timer/window、TextEncoder/TextDecoder、structuredClone、URL 等）
  - `src/commonTest/`：宿主模块测试
- `vibe-js-conformance/`（Gradle 模块 `:vibe-js-conformance`）
  - JVM-only 一致性基线：test262-parser-tests（脚本 + 模块）运行器、语料下载与报告生成
  - `docs/CONFORMANCE.md`：基线数字、已知缺口与推进顺序

## 阶段状态

- **已完成**：ES5 / ES2015 常用语法与内建、Generator / async generator、`for await`、Proxy / Reflect、BigInt、TypedArray / ArrayBuffer / DataView（含 detach）；对应特性已纳入 Test262 基线。
- **模块（实验性）**：`Parser.parseModule` 语法解析、阶段 0–8 前置（类型化绑定/TLA 标记/range、公共 `ModuleProvider`/`InMemoryModuleProvider`/模块图预算、内部模块图递归加载、ResolveExport/import 链接、`ModuleEnvironment`/声明实例化、namespace exotic、`import.meta`、`Engine.runModule` 同步求值与错误定位/预算/取消加固）已完成；模块加载器、TLA 执行、动态 `import()` 执行与 Test262 module harness **未实现/无计划**，API 需 opt-in。
- **未完成/无计划**：WeakRef / FinalizationRegistry、Intl / DOM / SharedArrayBuffer / resizable 等范围外项，以及 B2 性能优化与 PKG1 API 契约均为无计划；宿主生态已提供 TextEncoder/TextDecoder、structuredClone 与 URL 子集。

## 说明

- 核心模块不引用任何平台原生 API（不写 `@JsFun` / `external` 绑定）；
  js/wasmJs 目标仅用于通用库的可移植性验证。
- 宿主能力通过 `BuiltinModule` 注入，核心与浏览器/宿主概念解耦。
