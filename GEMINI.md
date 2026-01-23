# Gemini 规则

## 核心规则

- 回答时必须使用**简体中文**进行回答
- **需求不明确时，或存在矛盾时**及时与用户确认
- **非代码操作**需用中文详细说明步骤，并等待用户确认后再继续

## 编码规则

- 编写的代码中，必须有详细的注释，且注释使用简体中文
- **重用现有组件**，避免创建功能相似的文件或场景
- 创建单元测试时，使用`org.jetbrains.kotlin:kotlin-test`依赖库，并且单元测试的方法命使用字符串详细描述要测试的场景，注意避免无法被**Kotlin**作为方法名称的字符
- 实现标准库（如 Array, String）方法时，应尽量遵循 ECMAScript 规范的行为
- 可以从 https://developer.mozilla.org/zh-CN/docs/Web/JavaScript 查询JavaScript的相关文档
- 可以用 https://kotlinlang.org/api/core/kotlin-stdlib/ 查询Kotlin标准库相关文档
- 项目是作为一个通用的Kotlin多平台依赖库，仅使用通用的Kotlin标准库实现代码而不要使用JVM等依赖其他平台的库。

## 常用命令

- **运行 JVM 单元测试**: `./gradlew :vibe-js:jvmTest` (Windows 下使用 `gradlew` 或 `.\gradlew`)，如果仅修改了部分测试文件则只需要运行单个测试文件`./gradlew :vibe-js:jvmTest --tests "com.muedsa.js.runtime.value.JSArrayTest"` (替换为具体的类名)

## 项目介绍

- **Vibe JS** 是一个使用 Kotlin 多平台从零开始实现的轻量级 JavaScript 运行时。
- `README.md`: 提供了项目功能、结构和使用示例的全面概述。
- `docs/TEST_PLAN.md`: 详细的测试计划和标准库实现进度表。
- `vibe-js/build.gradle.kts`: KMP 库的主要构建脚本。它定义了支持的目标平台（JVM、Android、iOS、Linux）、依赖项和发布配置。
- `settings.gradle.kts`: 根 Gradle 配置文件，其中包含了 `:library` 模块。
- `gradle/libs.versions.toml`: 版本目录，用于声明和管理项目的依赖项。

### 核心源码结构 (vibe-js/src/commonMain/kotlin/com/muedsa/js/)

- `lexer/Lexer.kt`: **词法分析器**，它扫描原始 JavaScript 源代码并将其转换为令牌 (Token) 序列。
- `parser/Parser.kt`: **解析器**，负责从词法分析器获取令牌流并构建抽象语法树 (AST)。
- `ast/`: **AST 节点定义**，包含所有语法树节点的结构定义（如 `IfStatement`, `BinaryOp` 等）。
- `runtime/Interpreter.kt`: **解释器核心**，包含了用于遍历解释 AST 和执行 JavaScript 代码的主要逻辑。
- `runtime/Environment.kt`: **环境与作用域**，负责管理变量的声明、赋值和查找（作用域链）。
- `runtime/value/`: **JS 值与对象**，包含 JS 运行时的数据类型实现（如 `JSValue`, `JSObject`, `JSArray`, `JSString`, `JSNumber` 等）。
- `runtime/exception/`: **运行时异常**，定义了 JS 运行期间可能抛出的错误类型。

### 测试结构 (vibe-js/src/commonTest/kotlin/)

- 包含公共代码的单元测试。
- 单元测试的方法使用 Kotlin 的特性用字符串命名方法，并详细描述要测试的内容。
