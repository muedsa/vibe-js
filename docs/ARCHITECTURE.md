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

- `HostModule` 提供 `window`/`self`、`console`、`setTimeout`/`clearTimeout`、
  `setInterval`/`clearInterval`、`queueMicrotask`、`atob`/`btoa`；
- 定时任务通过 `EventLoop` 的 `Scheduler` 执行，console 通过 `Console` 注入。

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

### 8.4 自定义宿主能力

实现 `HostEnvironment`（Console / Clock / Scheduler）并传给 `Engine` 构造函数，
即可接管输出、时间与定时任务。

## 9. 性能优化的预留点

| 现状 | 后续替换方式 | 影响范围 |
|---|---|---|
| `Environment` 使用 Map 链式查找 | 预解析变量为槽位索引 / 扁平化闭包 | 仅 Environment 与 Interpreter 内部 |
| 每次函数调用执行预计算的 hoist 列表 | 已避免 AST 全量扫描；可进一步缓存函数对象 | 仅 Interpreter |
| 语句控制流使用 `Completion` | 保持不变 | 无 |
| AST 遍历执行 | 新增 `Executor` 实现（指令/闭包） | Parser/AST/内建/API 不变 |
| 属性表使用 LinkedHashMap | 可替换为更紧凑的存储或内联缓存 | JSObject 内部 |
| 内建集中安装 | 已按模块拆分 | 无 |

这些替换均不需要修改 Parser、AST、内建模块或公共 API。

## 10. 阶段状态

- **阶段 A（已完成）**：内核重构、严格模式框架、最小标准库、JVM/JS/Wasm 测试。
- **阶段 B（已完成）**：ES5 语法/内建补全（do-while、for-in、标签、delete/void/in/instanceof、
  with、对象字面量扩展、正则、Date、arguments、盒装原始值、freeze/seal、JSON 选项）。
- **阶段 C（已完成）**：ES2015 常用语法与内建（箭头、模板/标签模板、解构、默认/rest、
  spread、for-of、class/extends/super/new.target、Symbol、Map/Set/WeakMap/WeakSet、
  Promise、async/await）。
- **阶段 D（已完成）**：独立宿主模块 `:vibe-js-host`（console、定时器、window/self、
  queueMicrotask、atob/btoa）。
- **明确排除**：Generator、import/export、Proxy/Reflect、BigInt、Intl、TypedArray、
  DOM/fetch 等真实 Web API。
