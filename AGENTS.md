# AGENTS.md

## 核心规则

- 回答使用简体中文。
- 需求不明确或存在矛盾时先确认，不自行假设。
- 非代码操作（文档、配置、发布等）先说明步骤并等待确认；用户已明确要求时按请求执行。

## 编码规则

- 代码注释使用简体中文，说明意图、边界与非显然行为；不要写无信息量注释。
- 优先复用现有组件，避免创建功能相似的文件或抽象。
- 单元测试使用 `org.jetbrains.kotlin:kotlin-test`；测试方法名用字符串详细描述场景，避免 Kotlin 非法字符。
- 实现 ECMAScript 标准库时以规范为准；可查阅 https://developer.mozilla.org/zh-CN/docs/Web/JavaScript。
- 项目是 Kotlin Multiplatform 通用库，只使用通用 Kotlin 标准库，不引入 JVM 等平台依赖。
- 新增类型/函数默认使用 `internal`；扩大 public API 必须有明确需求。PKG1/正式版 API 契约无计划，不得顺手承诺兼容性。
- 不通过删除既有用例、放宽断言、扩大 allowlist 或降低默认限制来换取通过。

## 架构约束

- 核心代码全部位于 `commonMain`，不得引用平台原生 API，不写 `@JsFun` / `external` 绑定。
- `commonMain` 禁止 `java.*`、`System.*`、`Thread.*`、`Locale` / `TimeZone`、`java.nio.file`、`kotlin.io.path`、`kotlinx.io`、`kotlinx.datetime` 等平台或半平台 API；新增依赖必须 KMP 兼容并说明用途，禁止 JVM-only 依赖（现有已声明的 `kotlinx-coroutines` 等 KMP 依赖除外）。
- `js` / `wasmJs` 目标只用于通用库可移植性验证，不假设浏览器运行环境。
- 浏览器/宿主能力必须通过 `BuiltinModule` 注册；核心不注册 console、timer、window、DOM 等。
- 核心不得直接读取系统时钟、时区、随机数或文件/网络；时间统一使用 `HostEnvironment` 注入的 Clock / `utcOffsetMillis`。
- 不新增全局可变单例；运行时状态挂在 `Engine` / `EngineContext` 下。同一 `Engine` 只保证串行执行，不跨线程共享。
- 执行相关公开方法保持 `suspend`，以支撑 async/await 与 Promise。
- 语句控制流使用 `Completion`；JS `throw` 使用 `JSException`。
- 属性读写必须走 `Interpreter.getV` / `putV`，类型转换必须走 `Conversions`；新增内建对象/方法也必须走属性描述符与 Proxy 内部方法，不得直接读写 `JSObject` 底层存储，不得绕过 Proxy trap、getter/setter 或 TypedArray 的整数索引语义。
- 新增内建对象优先拆到 `builtins/` 独立模块，并在 `CoreBuiltins` 中按依赖顺序安装。
- 变量提升信息由 Parser 预计算（`varDeclarations` / `functionDeclarations`），运行时不得全量扫描 AST。
- Promise/async 使用 `EventLoop` 的微任务队列与协程调度；不得用平台线程或平台定时器替代。
- 新增循环、迭代器消费、正则、JSON、集合操作、异步生成器泵或缓存时，必须接入现有执行预算/资源检查点；不得引入无界循环、无界缓存或无检查点的大批量操作。
- 模块方向整体无计划：`Parser.parseModule` 仅保留现状；`parseProgram` 脚本路径零改动；不得新增 `runModule`、加载器、CLI 或 module harness 脚手架；脚本目标继续拒绝 `import` / `export`。
- 核心不得引入文件/URL/网络 IO；`Engine.run(source)` 是唯一执行入口。若未来重启模块方向，核心只定义纯 Kotlin 抽象，IO 放在 `:vibe-js-host`，且不得假设浏览器/Wasm 有文件系统。
- 范围外与无计划项以 `docs/OPTIMIZATION_ROADMAP.md` 为准；不得顺手实现、承诺或写成“下一版本支持”。

## 验证与门禁

- 修改核心或宿主后运行对应测试；涉及值模型、容器或调度时补跑 JS/Wasm。
- 修改 parser、运行时语义或语料相关逻辑后运行对应的 strict 门禁。
- `commonTest` 使用 `runTest` 与虚拟时间；禁止 `Thread.sleep`、真实时钟、系统时区/locale 依赖或哈希遍历顺序断言。
- 门禁/allowlist 只能逐条、带原因地调整；禁止用目录、后缀或通配符扩大排除。
- 不得提交 `build/`、`corpus/`、生成的报告；真实库必须固定版本与 URL。
- 对外数字以 `docs/CONFORMANCE.md` 为准；AGENTS.md 不维护基线数字。

## 文档分工

- `README.md`：功能、用法与阶段说明。
- `docs/ARCHITECTURE.md`：内核设计与执行流程。
- `docs/COMPATIBILITY.md`：兼容性矩阵与已知差异。
- `docs/CONFORMANCE.md`：基线数字、门禁与推进顺序。
- `docs/OPTIMIZATION_ROADMAP.md`：后续任务、优先级与无计划项。
- `AGENTS.md`：只写规则约束，不重复功能清单、目录结构、命令列表、基线数字或实现细节。
