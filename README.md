# Vibe JS

Vibe JS 是一个使用 Kotlin 多平台从零实现的轻量级 JavaScript 运行时。

- 目标：从单个脚本执行起步，覆盖 ES5 / ES2015 常用语法（含 `async/await`）；
- 定位：通用 Kotlin 库，核心不绑定任何浏览器/平台原生 API；
- 构建目标：JVM、Android、JS、Wasm、Linux、Windows、macOS、iOS、tvOS、watchOS；
- 宿主能力（console、定时器、window、performance 等）位于独立的 `:vibe-js-host` 模块。

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

### 安装宿主能力

```kotlin
import com.muedsa.js.host.HostModule

val engine = Engine()
engine.install(HostModule(engine.eventLoop, engine.host.console))
engine.run("console.log('hello'); setTimeout(function () { console.log('later'); }, 10);")
```

`HostModule` 提供 `window`/`self`、`console`、`setTimeout`/`clearTimeout`、
`setInterval`/`clearInterval`、`queueMicrotask`、`atob`/`btoa`、
`performance.now()`/`performance.timeOrigin`，
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
- 异步：`async` 函数/箭头函数、`await`、Promise 微任务
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
- **Symbol**：`Symbol()`、`Symbol.for/keyFor`、知名 Symbol（iterator/toStringTag 等）
- **集合**：`Map`、`Set`、`WeakMap`、`WeakSet`，配套迭代器协议
- **Promise**：`resolve/reject/all/race/allSettled/any/allKeyed/allSettledKeyed`、`then/catch/finally`、`withResolvers/try`
- **Iterator**：抽象构造器、`Iterator.from/concat/zip/zipKeyed` 与 helpers（map/filter/take/drop/flatMap/reduce/toArray 等）
- **其他**：Symbol 知名方法、WeakMap/WeakSet、JSON 源文本、`RegExp.escape`、ES2025 Set 方法集

### 尚未支持（明确排除或后续阶段）

- Generator（`function*` / `yield`）
- 异步迭代 `for await...of` / AsyncIterator 原型
- 模块系统（`import` / `export`）
- `Proxy` / `Reflect`
- BigInt、Intl、TypedArray/ArrayBuffer/DataView
- DOM / fetch / localStorage 等真实 Web API（宿主模块只提供 console/timer/window 等通用能力）
- `ShadowRealm`、`Atomics`
- 完整 ASI 边界规则

Unicode 属性/标识符/归一化/大小写映射数据表固定为 Unicode 17.0.0，可由 `tools/generate_unicode_data.py` 重新生成。

## 架构与扩展

```
lexer -> parser -> AST -> Executor(AstInterpreter) -> Engine
                         |
                         +-> Environment / Intrinsics / Builtins
                         +-> EventLoop / Promise / async-await
```

- `Engine`：门面，持有全局对象、内建注册表、事件循环与解释器；
- `Executor`：执行器接口，当前实现是 AST 解释器，后续可替换为编译执行器而不改语义；
- `BuiltinModule`：内建模块注册接口；标准库（`CoreBuiltins`）与宿主模块（`HostModule`）都通过它安装；
- `HostEnvironment`：宿主能力抽象（Console / Scheduler / Clock），并可注入 Date 时区偏移与 Promise 拒绝生命周期回调；
- `EventLoop`：微任务队列 + 宏任务调度，支撑 Promise 与定时器；
- `PropertyDescriptor` + `JSObject`：属性描述符与原型链模型。

详细设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，兼容性矩阵见 [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)，解析一致性基线见 [docs/CONFORMANCE.md](docs/CONFORMANCE.md)。

## 构建与测试

```bash
# 核心
./gradlew :vibe-js-core:jvmTest
./gradlew :vibe-js-core:jsNodeTest
./gradlew :vibe-js-core:wasmJsNodeTest

# 宿主模块
./gradlew :vibe-js-host:jvmTest
./gradlew :vibe-js-host:jsNodeTest
./gradlew :vibe-js-host:wasmJsNodeTest

# 基准测试编译/运行
./gradlew :vibe-js-core:jvmBenchmarkClasses
./gradlew :vibe-js-core:jvmBenchmarkBenchmark

# 解析一致性基线（首次运行自动下载 test262-parser-tests）
./gradlew :vibe-js-conformance:parserConformance
# 存在语法不一致时失败（后续门禁使用）
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

## 目录结构

- `vibe-js-core/`（Gradle 模块 `:vibe-js-core`）
  - `src/commonMain/kotlin/com/muedsa/js/`
    - `lexer/`：词法分析、Token、源码范围
    - `parser/`：递归下降解析器
    - `ast/`：不可变 AST 节点
    - `runtime/`：引擎、执行器、环境、类型转换、事件循环、Promise、扩展点
    - `runtime/value/`：JS 值、对象、函数、数组、集合、Promise、属性描述符
    - `builtins/`：ECMAScript 标准内建
  - `src/commonTest/`：核心单元测试
- `vibe-js-host/`（Gradle 模块 `:vibe-js-host`）
  - `src/commonMain/kotlin/com/muedsa/js/host/`：宿主模块（console/timer/window 等）
  - `src/commonTest/`：宿主模块测试
- `vibe-js-conformance/`（Gradle 模块 `:vibe-js-conformance`）
  - JVM-only 一致性基线：test262-parser-tests 运行器、语料下载与报告生成
  - `docs/CONFORMANCE.md`：基线数字、已知缺口与推进顺序

## 说明

- 核心模块不引用任何平台原生 API（不写 `@JsFun` / `external` 绑定）；
  js/wasmJs 目标仅用于通用库的可移植性验证。
- 宿主能力通过 `BuiltinModule` 注入，核心与浏览器/宿主概念解耦。
