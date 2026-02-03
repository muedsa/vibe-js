# Vibe JS 单元测试重构计划

本文档详细列出了 Vibe JS 项目单元测试重构的测试点，旨在确保 Lexer（词法分析器）、Parser（语法解析器）和 Interpreter（解释器）的正确性、稳定性和规范性。

## 1. Lexer 测试 (词法分析)

目标：验证源代码能否正确转换为 Token 序列。

### 1.1 基础 Token 识别
- [x] **关键字**: 验证所有关键字 (`var`, `let`, `if`, `function`, `return`, `true` 等) 能被正确识别。
- [x] **标识符**:
    - [x] 纯字母、字母数字混合。
    - [x] 包含 `_` 和 ` 的标识符。
    - [x] 边界测试：标识符紧挨着运算符（如 `a+b`）。
- [x] **字面量**:
    - [x] **数字**: 整数 (`123`)、小数 (`12.34`)。
    - [x] **字符串**: 双引号 (`"text"`)、单引号 (`'text'`)、转义字符 (`\n`, `\t`, `\"`, `\'`,`\\`)。
    - [x] **布尔值**: `true`, `false`。
    - [x] **Null**: `null`。

### 1.2 运算符与分隔符
- [x] **单字符运算符**: `+`, `-`, `*`, `/`, `%`, `<`, `>`, `=`, `!`, `&`, `|`, `^`, `~`。
- [x] **多字符运算符**:
    - [x] 赋值类: `+=`, `-=`, `*=`, `/=`, `%=`。
    - [x] 比较类: `==`, `===`, `!=`, `!==`, `<=`, `>=`。
    - [x] 逻辑类: `&&`, `||`, `??`。
    - [x] 自增/减: `++`, `--`。
    - [x] 移位: `<<`, `>>`, `>>>`。
    - [x] 箭头函数: `=>`。
- [x] **分隔符**: `(`, `)`, `{`, `}`, `[`, `]`, `,`, `;`, `:`, `.`。

### 1.3 忽略项处理
- [x] **空白字符**: 空格、制表符、换行符应被忽略（但换行符可能影响自动分号插入逻辑，需注意 Token 类型）。
- [x] **注释**:
    - [x] 单行注释 `// ...`。
    - [x] 多行注释 `/* ... */`。
    - [x] 混合注释。

### 1.4 错误处理
- [x] **非法字符**: 输入不支持的字符（如 `@`, `#` 等非法位置）应抛出异常或返回错误 Token。
- [x] **未闭合的字符串**: 字符串缺少结束引号。

---

## 2. Parser 测试 (语法解析)

目标：验证 Token 序列能否正确构建 AST（抽象语法树），并处理语法错误。

### 2.1 表达式解析 (优先级与结合性)
- [x] **基础运算**: 加减乘除模，验证优先级（如 `*` 高于 `+`）。
- [x] **括号优先级**: `(a + b) * c`。
- [x] **赋值表达式**: 右结合性 (`a = b = c`)。
- [x] **成员访问**: 点号 `obj.prop` 和下标 `obj['prop']`。
- [x] **函数调用**: `func()`, `func(arg1, arg2)`。
- [x] **一元运算**: `!`, `-`, `typeof`, `delete`, `++`, `--` (前缀与后缀)。
- [x] **逻辑运算**: `&&`, `||` 的短路逻辑结构。
- [x] **三元运算符**: `cond ? trueExpr : falseExpr`。
- [x] **逗号运算符**: `a, b` (序列表达式)。

### 2.2 语句解析
- [x] **变量声明**:
    - [x] `var a = 1;`
    - [x] `let b;` (无初始化)
    - [x] `const c = 3;`
- [x] **块语句**: `{ ... }` 嵌套结构。
- [x] **控制流**:
    - [x] `if`, `if-else`, `if-else if-else`。
    - [x] `while` 循环。
    - [x] `for` 循环 (包含 `init`, `condition`, `update` 各部分为空的情况)。
    - [x] `switch` 语句 (包含 `case` 和 `default`)。
    - [x] `break`, `continue` 语句。
    - [x] `return` 语句 (有值与无值)。
- [x] **函数声明**:
    - [x] `function name() {}`
    - [x] 带参数函数。
- [x] **异常处理**: `try-catch`, `try-finally`, `try-catch-finally`。

### 2.3 语法错误处理
- [x] **缺少符号**: 如缺少 `)`, `}`, `;` 等。
- [x] **关键字误用**: 如 `const` 缺少初始化，`break` 在循环外使用。

---

## 3. Interpreter 测试 (解释执行)

目标：验证代码执行结果是否符合 JavaScript 规范。

### 3.1 数据类型与类型转换
- [x] **类型判断**: 确保字面量解析为正确的运行时类型 (`JSNumber`, `JSString` 等)。
- [x] **类型强制转换**:
    - [x] 字符串拼接: `1 + "2" -> "12"`.
    - [x] 算术运算: `"5" - 1 -> 4`.
    - [x] 布尔转换: `!0 -> true`, `!"" -> true`.
- [x] **相等性比较**:
    - [x] `==` (非严格): `1 == "1"`, `null == undefined`.
    - [x] `===` (严格): `1 !== "1"`.

### 3.2 作用域与变量 (Scope & Variables)
- [x] **作用域链**: 内部作用域访问外部变量。
- [x] **变量遮蔽 (Shadowing)**: 内部变量覆盖外部同名变量。
- [x] **变量提升 (Hoisting)**:
    - [x] `var` 声明提升（初始化为 `undefined`）。
    - [x] 函数声明提升（优先于变量）。
    - [x] `let`/`const` 的暂时性死区 (TDZ) 行为（如果实现支持）。
- [x] **块级作用域**: `let`/`const` 在 `if` 或 `for` 块内的作用域隔离。

### 3.3 控制流逻辑
- [x] **条件分支**: `if` 的真假值判断（包括 `0`, `""`, `null`, `undefined` 视为 false）。
- [x] **循环**: `while`, `for` 的正确执行次数。
- [x] **跳转**: `break` 跳出循环，`continue` 跳过当次迭代。
- [x] **Switch**: `case` 匹配逻辑，`default` 分支。

### 3.4 函数与闭包
- [x] **函数调用**: 参数传递，返回值。
- [x] **递归调用**: 计算斐波那契数列等。
- [x] **闭包 (Closure)**:
    - [x] 函数返回函数，并保留对外部变量的引用。
    - [x] 循环中的闭包陷阱（`var` vs `let`）。
- [x] **this 关键字**:
    - [x] 对象方法调用 (`obj.method()`) 中 `this` 指向 `obj`。
    - [x] 普通函数调用中 `this` 的指向（`undefined` 或全局对象）。
    - [x] `new` 构造函数调用中 `this` 指向新实例。

### 3.5 对象与数组
- [x] **对象**:
    - [x] 属性读写 (`obj.a`, `obj["a"]`)。
    - [x] 嵌套对象访问。
    - [x] 对象引用传递（修改引用影响原对象）。
- [x] **数组**:
    - [x] 元素读写 (`arr[0]`)。
    - [x] 数组长度。

### 3.6 异常处理
- [x] **Throw**: 抛出各种类型的值（Error对象, 字符串, 数字）。
- [x] **Try-Catch**: 捕获异常，验证错误对象。
- [x] **Finally**: 无论是否报错，`finally` 块必须执行。
- [x] **嵌套 Try-Catch**: 异常冒泡机制。

### 3.7 原生函数与标准库 (如果有)

- [ ] **Print/Log**: 验证输出功能。

- [x] **Math/Date**: Math 已经实现并进行测试。



### 3.8 标准库 (Standard Library)

目标：验证 JavaScript 标准内置对象的属性和方法是否按规范实现。



#### 3.8.1 Array (数组)

- [x] **属性**: `length`

- [x] **静态方法**: `from`, `isArray`, `of`

- [x] **原型方法 (迭代)**:

  - [x] `forEach`

  - [x] `map`

  - [x] `filter`

  - [x] `reduce`

  - [x] `reduceRight`

  - [x] `every`

  - [x] `some`

  - [x] `find`

  - [x] `findIndex`

- [x] **原型方法 (操作)**:

  - [x] `push`, `pop`

  - [x] `shift`, `unshift`

  - [x] `slice`

  - [x] `splice`

  - [x] `concat`

  - [x] `reverse`

  - [x] `sort`

  - [x] `join`

- [x] **原型方法 (查找/访问)**:

  - [x] `indexOf`

  - [x] `includes`

  - [x] `at`

  - [x] `toString`



#### 3.8.2 String (字符串)

- [x] **属性**: `length`

- [x] **静态方法**: `fromCharCode`, `fromCodePoint`

- [x] **原型方法 (字符访问)**:

  - [x] `at`

  - [x] `charAt`

  - [x] `charCodeAt`

  - [x] `codePointAt`

- [x] **原型方法 (搜索/匹配)**:

  - [x] `includes`

  - [x] `indexOf`

  - [x] `lastIndexOf`

  - [x] `startsWith`

  - [x] `endsWith`

- [x] **原型方法 (操作/变换)**:

  - [x] `concat`

  - [x] `slice`

  - [x] `substring`

  - [x] `substr`

  - [x] `split`

  - [x] `trim`, `trimStart`, `trimEnd`

  - [x] `toLowerCase`, `toUpperCase`

  - [x] `padEnd`, `padStart`

  - [x] `repeat`

  - [x] `replace`, `replaceAll`

  - [x] `toString`



#### 3.8.3 Math (数学)

- [x] **常量**: `PI`, `E`, `LN2`, `SQRT2` 等

- [x] **原型方法**:

  - [x] `abs`

  - [x] `ceil`, `floor`, `round`, `trunc`

  - [x] `max`, `min`

  - [x] `pow`, `sqrt`, `cbrt`

  - [x] `sin`, `cos`, `tan` 等三角函数

  - [x] `random`

  - [x] `imul`, `clz32`

  - [x] `hypot`

  - [x] `exp`, `log` 等指数对数函数
