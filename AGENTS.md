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

## 架构约束（B/C/D 完成后）

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
- 明确排除：Generator、import/export、Proxy/Reflect、BigInt、Intl、TypedArray、真实 DOM/Web API

## 常用命令

- **运行 JVM 单元测试**: `./gradlew :vibe-js-core:jvmTest`（Windows 下使用 `gradlew` 或 `.\gradlew`）
- **运行宿主模块测试**: `./gradlew :vibe-js-host:jvmTest`
- **运行单个测试类**: `./gradlew :vibe-js-core:jvmTest --tests "com.muedsa.js.BasicExecutionTest"`
- **JS 目标测试**: `./gradlew :vibe-js-core:jsNodeTest`、`./gradlew :vibe-js-host:jsNodeTest`
- **Wasm 目标测试**: `./gradlew :vibe-js-core:wasmJsNodeTest`、`./gradlew :vibe-js-host:wasmJsNodeTest`
- **编译基准测试**: `./gradlew :vibe-js-core:jvmBenchmarkClasses`
- **解析一致性基线**: `./gradlew :vibe-js-conformance:parserConformance`（首次自动下载 test262-parser-tests）
- **解析一致性门禁**: `./gradlew :vibe-js-conformance:parserConformance -Pstrict`
- **Test262 ES5 语义基线**: `./gradlew :vibe-js-conformance:test262Conformance`（首次自动下载 test262）
- **Test262 ES2015 特性子集**: `./gradlew :vibe-js-conformance:test262Es2015Conformance`（报告模式）
- **真实库 UMD 冒烟**: `./gradlew :vibe-js-conformance:librarySmoke`
- **Node 差分基线**: `./gradlew :vibe-js-conformance:differentialConformance`
- **依赖变化后更新 JS/Wasm 锁文件**: `./gradlew kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock`

## 项目介绍

- **Vibe JS** 是一个使用 Kotlin 多平台从零开始实现的轻量级 JavaScript 运行时。
- 目标：从单个脚本执行起步，覆盖 ES5 / ES2015 常用语法（含 async/await）；严格模式支持 `"use strict"` 双模式。
- `README.md`: 功能、结构、用法与阶段说明。
- `docs/ARCHITECTURE.md`: 内核设计、执行流程与扩展点。
- `docs/COMPATIBILITY.md`: 兼容性矩阵与已知差异。
- `docs/CONFORMANCE.md`: test262-parser-tests 解析一致性基线、已知缺口与推进顺序。
- `vibe-js-core/build.gradle.kts`: 核心 KMP 库构建脚本（JVM/Android/JS/Wasm/Native 目标）。
- `vibe-js-host/build.gradle.kts`: 宿主模块构建脚本（console/timer/window 等）。
- `vibe-js-conformance/build.gradle.kts`: JVM-only 解析一致性基线模块（语料下载、报告生成）。
- `settings.gradle.kts`: 模块 `:vibe-js-core`、`:vibe-js-host` 与 `:vibe-js-conformance`（目录同名）。
- `gradle/libs.versions.toml`: 依赖版本目录。

### 核心源码结构 (`vibe-js-core/src/commonMain/kotlin/com/muedsa/js/`)

- `lexer/Lexer.kt`: 词法分析器；`SourceRange` / `Token` 携带源码位置；`LexerException` 统一词法错误
- `parser/Parser.kt`: 递归下降解析器；负责严格模式指令检测与变量提升信息收集
- `ast/`: 不可变 AST 节点（`Program` / `Statement` / `Expression`）
- `runtime/Engine.kt`: 引擎门面（全局对象、内建注册表、事件循环、解释器）
- `runtime/Interpreter.kt`: AST 解释器（suspend、Completion 控制流、属性访问、函数调用、class/async）
- `runtime/Environment.kt`: 声明式环境与全局对象环境
- `runtime/Conversions.kt`: ECMAScript 类型转换集中实现
- `runtime/Executor.kt` / `Completion.kt` / `ExecutionContext.kt`: 执行器抽象与控制流
- `runtime/BuiltinRegistry.kt` / `HostEnvironment.kt` / `EventLoop.kt`: 扩展点与宿主抽象
- `runtime/PromiseSupport.kt`: Promise 解析与 then 链调度
- `runtime/value/`: JS 值模型（JSValue/JSObject/JSFunction/JSArray/JSMap/JSSet/JSPromise/属性描述符等）
- `builtins/`: ECMAScript 标准内建（Object/Function/Array/String/Number/Boolean/Math/JSON/Error/RegExp/Date/Symbol/Map/Set/Promise 等）
- `vibe-js-host/src/commonMain/kotlin/com/muedsa/js/host/HostModule.kt`: 宿主模块（console/timer/window 等）

### 测试结构 (`vibe-js-core/src/commonTest/kotlin/com/muedsa/js/`)

- 使用 `kotlinx-coroutines-test` 的 `runTest` 执行 suspend API
- `TestRuntime` 提供 `eval` / `evalNumber` / `evalString` / `evalBoolean` / `global` 等辅助
- 测试方法使用字符串命名并详细描述场景
- 新增行为时，优先在 `ConformanceTest` 中添加规范导向的回归用例
