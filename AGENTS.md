# AGENTS.md

## 核心规则

- 回答时必须使用**简体中文**进行回答
- **需求不明确时，或存在矛盾时**及时与用户确认
- **非代码操作**需用中文详细说明步骤，并等待用户确认后再继续

## 编码规则

- 编写的代码中，必须有详细的注释，且注释使用简体中文
- **重用现有组件**，避免创建功能相似的文件或场景
- 创建单元测试时，使用 `org.jetbrains.kotlin:kotlin-test` 依赖库，并且单元测试的方法命名使用字符串详细描述要测试的场景，注意避免无法被 **Kotlin** 作为方法名称的字符
- 实现标准库（如 Array, String）方法时，应尽量遵循 ECMAScript 规范的行为
- 可以从 https://developer.mozilla.org/zh-CN/docs/Web/JavaScript 查询 JavaScript 的相关文档
- 可以用 https://kotlinlang.org/api/core/kotlin-stdlib/ 查询 Kotlin 标准库相关文档
- 项目是作为一个通用的 Kotlin 多平台依赖库，仅使用通用的 Kotlin 标准库实现代码，而不要使用 JVM 等依赖其他平台的库

## 架构约束

- 核心代码全部位于 `commonMain`，**不引用任何平台原生 API**（不写 `@JsFun` / `external` 绑定）
- `js` / `wasmJs` 目标仅用于通用库的可移植性验证，不是"在浏览器中运行 JS 引擎"
- 浏览器/宿主能力（console、timer、window、DOM 等）必须通过 `BuiltinModule` 注册，核心不注册；
  官方宿主能力位于独立模块 `:vibe-js-host`
- 所有执行方法都是 `suspend`（支撑 async/await 与 Promise）
- 语句控制流使用 `Completion`（Normal/Return/Break/Continue）；`throw` 使用 `JSException`
- 属性读写必须走 `Interpreter.getV` / `putV`；类型转换必须走 `Conversions`
- 新增内建对象时，优先拆分为 `builtins/` 下的独立模块，并在 `CoreBuiltins` 中按依赖顺序安装
- 变量提升信息由 Parser 预计算（`varDeclarations` / `functionDeclarations`），不要在运行时全量扫描 AST
- Promise/async 使用 `EventLoop` 的微任务队列与协程调度；不要直接调用平台定时器
- **已支持能力**：Generator / async generator、`for await...of`、Proxy / Reflect、BigInt、
  TypedArray / ArrayBuffer / DataView（含 detach）；对应特性已纳入 Test262 ES2015 基线
- **模块**：`Parser.parseModule()` 支持 `import` / `export` 基础形式、`import.meta`、`import()` 表达式
  与模块顶层 `await` 语法，并预计算静态表；链接、求值、加载**无计划**，`Engine` 仍只执行 Script。
  模块解析必须保持脚本 `parseProgram` 路径零改动；`ModuleItem` 不继承 `Statement`；
  模块恒为严格模式，禁用 Annex B HTML 注释与 Annex B 语法
- **模块加载与 IO 边界**：模块方向整体无计划；当前唯一执行入口是单脚本 `Engine.run(source)`。
  若未来重启，`ModuleLoader` 抽象也只能是纯 Kotlin 接口，文件/URL 加载、CLI 与平台 IO
  必须放在 `:vibe-js-host`，核心不得引入平台 API；不得假设浏览器/Wasm 有文件系统
- **仍排除/范围外**：Intl、真实 DOM / Web API、SharedArrayBuffer / Atomics、
  resizable / transfer ArrayBuffer、Temporal、Float16Array、装饰器、ShadowRealm；
  WeakRef / FinalizationRegistry 未实现（M2 无计划），WeakMap/WeakSet 仍是强引用存储；
  顶层 await 只有语法解析，执行无计划（L4.7）

## 常用命令

- **运行 JVM 单元测试**: `./gradlew :vibe-js-core:jvmTest`（Windows 下使用 `gradlew` 或 `.\gradlew`）
- **运行宿主模块测试**: `./gradlew :vibe-js-host:jvmTest`
- **运行单个测试类**: `./gradlew :vibe-js-core:jvmTest --tests "com.muedsa.js.BasicExecutionTest"`
- **JS 目标测试**: `./gradlew :vibe-js-core:jsNodeTest`、`./gradlew :vibe-js-host:jsNodeTest`
- **Wasm 目标测试**: `./gradlew :vibe-js-core:wasmJsNodeTest`、`./gradlew :vibe-js-host:wasmJsNodeTest`
- **编译基准测试**: `./gradlew :vibe-js-core:jvmBenchmarkClasses`
- **解析一致性基线**: `./gradlew :vibe-js-conformance:parserConformance`（首次自动下载 test262-parser-tests；脚本 + 模块）
- **解析一致性门禁**: `./gradlew :vibe-js-conformance:parserConformance -Pstrict`（脚本与模块共用 strict 门禁）
- **Test262 ES5 语义基线**: `./gradlew :vibe-js-conformance:test262Conformance`（首次自动下载 test262）
- **Test262 ES2015 特性子集**: `./gradlew :vibe-js-conformance:test262Es2015Conformance`（报告模式）
- **真实库 UMD 冒烟**: `./gradlew :vibe-js-conformance:librarySmoke`
- **Node 差分基线**: `./gradlew :vibe-js-conformance:differentialConformance`
- **依赖变化后更新 JS/Wasm 锁文件**: `./gradlew kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock`

## 当前基线

> 2026-11-09，代码基线 `53fb71b`。文档数字必须与本节及
> `docs/CONFORMANCE.md` 保持一致；JS/Wasm 已在当前基线复跑。

- core JVM **999 / 999**；core JS / Wasm 各 **983 / 983**；host JVM / JS / Wasm 各 **25 / 25**
- parser 脚本 **1,907 / 1,907**，fail 674 / 682（允许 8），early 5 / 605（允许 5）
- parser 模块 **76 / 76 + 76 / 76 + 47 / 47 + 63 / 63**（early 拒绝），允许 0（262 个 `.module.js`）
- Test262 ES5 **15,248 / 15,248**（跳过 0）；ES2015 **61,786 / 61,786**（跳过 324 = module flag 322 + 宿主钩子 2）
- Node 差分 **548 / 548**；真实库 UMD 冒烟 **329 / 329**（86 个库）
- conformance 单元测试 **39 / 39**

## 项目介绍

- **Vibe JS** 是一个使用 Kotlin 多平台从零开始实现的轻量级 JavaScript 运行时。
- 目标：从单个脚本执行起步，覆盖 ES5 / ES2015 常用语法（含 async/await、Generator / async generator），
  已实现 Proxy / Reflect、BigInt、TypedArray / ArrayBuffer / DataView；模块目前只支持语法解析，
  不能链接或执行（模块方向无计划）；严格模式支持 `"use strict"` 双模式。
- `README.md`: 功能、结构、用法与阶段说明。
- `docs/ARCHITECTURE.md`: 内核设计、执行流程、模块解析与扩展点。
- `docs/COMPATIBILITY.md`: 兼容性矩阵与已知差异。
- `docs/CONFORMANCE.md`: parser 脚本/模块、Test262 ES5/ES2015、真实库与差分基线、已知缺口与推进顺序。
- `docs/OPTIMIZATION_ROADMAP.md`: 后续路线（只列未完成工作、优先级、人日、风险与验收）。
- `vibe-js-core/build.gradle.kts`: 核心 KMP 库构建脚本（JVM/Android/JS/Wasm/Native 目标）。
- `vibe-js-host/build.gradle.kts`: 宿主模块构建脚本（console/timer/window 等）。
- `vibe-js-conformance/build.gradle.kts`: JVM-only 一致性基线模块（语料下载、报告生成）。
- `settings.gradle.kts`: 模块 `:vibe-js-core`、`:vibe-js-host` 与 `:vibe-js-conformance`（目录同名）。
- `gradle/libs.versions.toml`: 依赖版本目录。

### 核心源码结构 (`vibe-js-core/src/commonMain/kotlin/com/muedsa/js/`)

- `lexer/Lexer.kt`: 词法分析器；`SourceRange` / `Token` 携带源码位置；`moduleContext` 控制模块目标的
  Annex B HTML 注释禁用；`LexerException` 统一词法错误
- `parser/Parser.kt`: 递归下降解析器；`parseProgram` 负责脚本目标、`parseModule` 负责模块目标；
  负责严格模式指令检测、变量提升信息收集、模块静态表与模块早期错误
- `ast/`: 不可变 AST 节点（`Program` / `Statement` / `Expression`）；`ast/Modules.kt` 提供
  `ModuleProgram` / `ModuleItem` / `ImportEntry` / `ExportEntry` / `ImportMetaExpr` / `ImportCallExpr`
- `runtime/Engine.kt`: 引擎门面（全局对象、内建注册表、事件循环、解释器；当前只执行 Script）
- `runtime/Interpreter.kt`: AST 解释器（suspend、Completion 控制流、属性访问、函数调用、class/async）
- `runtime/Environment.kt`: 声明式环境与全局对象环境
- `runtime/Conversions.kt`: ECMAScript 类型转换集中实现
- `runtime/Executor.kt` / `Completion.kt` / `ExecutionContext.kt`: 执行器抽象与控制流
- `runtime/BuiltinRegistry.kt` / `HostEnvironment.kt` / `EventLoop.kt`: 扩展点与宿主抽象
- `runtime/PromiseSupport.kt`: Promise 解析与 then 链调度
- `runtime/EngineLimits.kt` / `runtime/exception/ResourceLimitException.kt`: 执行与资源约束
- `runtime/value/`: JS 值模型（JSValue/JSObject/JSFunction/JSArray/JSMap/JSSet/JSPromise/属性描述符），
  以及 `JSGenerator`、`JSProxy`、`JSBigInt`、`JSArrayBuffer`、`JSDataView`、`JSTypedArray`
- `builtins/`: ECMAScript 标准内建（Object/Function/Array/String/Number/Boolean/Math/JSON/Error/RegExp/Date/Symbol/Map/Set/Promise 等），
  以及 `GeneratorBuiltins` / `AsyncGeneratorBuiltins` / `ProxyBuiltins` / `ReflectBuiltins` /
  `TypedArrayBuiltins` / `ArrayBufferBuiltins` / `DataViewBuiltins` / `BigIntBuiltins`
- `vibe-js-host/src/commonMain/kotlin/com/muedsa/js/host/HostModule.kt`: 宿主模块（console/timer/window 等；
  若未来重启模块方向，加载器与 CLI 也只能放在这里）

### 测试结构 (`vibe-js-core/src/commonTest/kotlin/com/muedsa/js/`)

- 使用 `kotlinx-coroutines-test` 的 `runTest` 执行 suspend API
- `TestRuntime` 提供 `eval` / `evalNumber` / `evalString` / `evalBoolean` / `global` 等辅助
- 测试方法使用字符串命名并详细描述场景
- 新增行为时，优先在 `ConformanceTest` 中添加规范导向的回归用例
- 重点测试类：`GeneratorTest` / `AsyncGeneratorTest` / `ForAwaitTest`、`ProxyTest` / `ProxyInvariantTest` /
  `ProxyReflectCompletionTest` / `ReflectTest`、`TypedArrayTest` / `ArrayBufferTest` / `ArrayBufferDetachTest` /
  `BigIntTest` / `BigInt64ArrayTest` / `Float32PrecisionTest`、`ParserModuleTest`、`ResourceLimitsTest`、
  `RuntimeLimitsTest`、`RuntimeSemanticsRegressionTest`、`RuntimeErrorStackTest`、`EventLoopLifecycleTest`
- `vibe-js-conformance/src/test`：`ParserConformanceTest`、`Test262SelectionTest`、`RunSupportTest`、`RunMetadataTest`
  覆盖 parser 脚本/模块允许列表、Test262 筛选与跳过分类、运行器超时/关闭/失败分类与报告元数据
