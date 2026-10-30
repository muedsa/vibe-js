# 兼容性矩阵

状态说明：✅ 支持　⚠️ 部分支持/有已知差异　❌ 尚未支持

> 当前覆盖 ES5 与 ES2015 的常用语法和内建；明确排除 Generator、模块系统、
> Proxy/Reflect、BigInt、Intl、TypedArray 与真实 Web API（DOM/fetch 等）。

## 1. 词法

| 特性 | 状态 | 说明 |
|---|---|---|
| 关键字（ES5 + class/super/extends/with/do/delete/void/in/instanceof/debugger） | ✅ | |
| 数字：整数/小数/科学计数法/前导小数点 | ✅ | |
| 数字：0x/0o/0b、数字分隔符 | ✅ | |
| 字符串：单/双引号与常见转义 | ✅ | `\n \t \r \b \f \v \0 \\ \' \"` |
| 字符串：`\xXX` / `\uXXXX` / `\u{...}` | ✅ | |
| 模板字符串 | ✅ | 支持 `${}` 嵌套；raw 文本暂与 cooked 相同 |
| 正则字面量 | ✅ | 通过"上一 Token"启发式区分除法/正则 |
| 注释：单行/多行 | ✅ | 未闭合报错 |
| 未闭合字符串/模板/正则 | ✅ | 抛出 `LexerException` |
| 标识符：Unicode 字母 | ✅ | 不支持 `\uXXXX` 标识符转义 |
| 自动分号插入（ASI） | ⚠️ | 覆盖 return/throw/break/continue/++/-- 换行与常规语句分隔；极少数边界未覆盖 |
| BigInt 字面量 | ❌ | 后续阶段 |

## 2. 语法

| 特性 | 状态 | 说明 |
|---|---|---|
| var/let/const、提升、块级作用域、TDZ | ✅ | |
| 空语句、块、if/else、while | ✅ | |
| do-while | ✅ | |
| for（三段式，let 每迭代作用域） | ✅ | |
| for-in / for-of | ✅ | 支持 var/let/const、解构左值、标签 break/continue |
| switch（default 任意位置） | ✅ | |
| 标签语句、带标签 break/continue | ✅ | |
| try/catch/finally、throw | ✅ | |
| 函数声明/表达式、闭包、arguments | ✅ | 命名函数表达式的内部名暂不绑定 |
| 箭头函数（块体/表达式体、词法 this、词法 arguments） | ✅ | |
| 默认参数、rest 参数、函数 length | ✅ | |
| 解构：声明/赋值/for-of/函数参数/catch、默认值、嵌套、数组 rest、对象 rest | ✅ | |
| spread：数组/调用/new/对象 | ✅ | |
| class/extends/super/new.target | ✅ | 实例/静态方法、getter/setter、默认派生构造器；类字段不支持 |
| 对象字面量：简写/方法/getter/setter/计算键 | ✅ | |
| 模板字符串 / 标签模板 | ✅ | |
| 正则字面量、String 正则方法 | ✅ | `s`/`u` 标志暂按普通模式处理，命名组有限 |
| 赋值/复合赋值/逻辑赋值（`??=`/`&&=`/`||=`/`**=` 等） | ✅ | |
| typeof、delete、void、in、instanceof | ✅ | 自定义 `Symbol.hasInstance` 未支持 |
| 可选链 `?.` | ❌ | ES2020，后续阶段 |
| class 字段 / 私有字段 | ❌ | 后续阶段 |
| Generator（`function*` / `yield`） | ❌ | 明确排除 |
| import / export | ❌ | 明确排除（单脚本目标） |
| Proxy / Reflect | ❌ | 明确排除 |
| 严格模式 `"use strict"` | ⚠️ | this/未声明赋值/只读属性/const/with/delete 已实现；其余细节（参数重复等）阶段后续补齐 |

## 3. 标准内建

| 对象 | 状态 | 已实现 |
|---|---|---|
| 全局常量/函数 | ✅ | undefined、NaN、Infinity、globalThis、parseInt、parseFloat、isNaN、isFinite |
| Object | ✅ | 构造器；keys/values/entries/assign/create/getPrototypeOf/setPrototypeOf/getOwnPropertyDescriptor/getOwnPropertyNames/getOwnPropertySymbols/defineProperty/defineProperties/is/freeze/seal/preventExtensions/isFrozen/isSealed/isExtensible |
| Object.prototype | ✅ | hasOwnProperty、isPrototypeOf、propertyIsEnumerable、valueOf、toString、toLocaleString、`__proto__` |
| Function | ✅ | call/apply/bind/toString、动态 Function 构造器 |
| Array | ✅ | 常用迭代/查找/增删/排序、fill/copyWithin/at、entries/keys/values、toLocaleString；静态 isArray/of/from |
| String | ✅ | 常用方法、toLocaleLowerCase/UpperCase、String.raw、fromCharCode/fromCodePoint；normalize 暂缺 |
| Number | ✅ | 常量、toString(radix)、toFixed/toExponential/toPrecision、静态判断与解析 |
| Boolean | ✅ | 构造器、toString、valueOf |
| Math | ✅ | 常量与 ES5/ES2015 常用函数 |
| JSON | ✅ | parse（reviver）、stringify（replacer/space） |
| Error | ✅ | Error/TypeError/RangeError/ReferenceError/SyntaxError/EvalError/URIError |
| RegExp | ⚠️ | 字面量/构造器、exec/test/toString、lastIndex；`s`/`u` 标志与命名组有限 |
| Date | ⚠️ | 构造、now/parse/UTC、常用 getter/setter、toISOString/toJSON；本地时间按 UTC 处理 |
| Symbol | ✅ | Symbol()、for/keyFor、知名 Symbol、属性键 |
| Map / Set / WeakMap / WeakSet | ✅ | 常用方法、size（Map/Set）、迭代器协议；Weak 版本不可迭代 |
| Promise | ✅ | then/catch/finally、resolve/reject/all/race/allSettled/any、thenable 与循环检测 |
| Generator | ❌ | 明确排除 |
| TypedArray / ArrayBuffer / DataView | ❌ | 后续阶段 |
| Intl | ❌ | 后续阶段 |

## 4. 函数与对象语义

| 特性 | 状态 |
|---|---|
| 闭包、递归、this（方法/普通调用/构造函数） | ✅ |
| new 与 prototype 链、constructor 双向关联 | ✅ |
| 原型链继承（Object.create / setPrototypeOf / `__proto__`） | ✅ |
| getter/setter、属性描述符 | ⚠️ 基础实现；复杂描述符冲突校验有限 |
| instanceof、in、delete | ✅ |
| arguments | ⚠️ 类数组对象；未实现参数映射 |
| 箭头函数词法 this/arguments | ✅ |
| class 构造器调用限制、super、new.target | ✅ |
| Symbol.toPrimitive | ✅ |
| Symbol.hasInstance | ✅ |
| Symbol.isConcatSpreadable / species | ❌ |

## 5. 平台

| 目标 | 状态 | 验证方式 |
|---|---|---|
| JVM | ✅ | 核心 280 + 宿主 14 个测试 |
| JS（Node） | ✅ | 核心 280 + 宿主 14 个测试 |
| Wasm（Node） | ✅ | 核心 280 + 宿主 14 个测试 |
| Android | ⏳ | CI `testAndroidHostTest` |
| Linux / Windows / macOS / iOS / tvOS / watchOS | ⏳ | CI 各目标测试 |

## 6. 已知差异

- `Date` 的本地时间方法按 UTC 实现（common 代码无法获取时区），`getTimezoneOffset()` 恒为 0；
- `JSON.parse` 复用 JS Parser，会接受尾逗号等非严格 JSON；
- 类字段、可选链未实现；
- RegExp 的 `s`/`u` 标志暂不生效，命名捕获组有限；
- `String.prototype.normalize` 未实现；
- Promise 的微任务调度使用内置 EventLoop（脚本结束后冲刷），未实现未处理拒绝上报；
- 宿主模块不绑定 DOM/Web API；`setInterval` 基于协程调度。

## 7. 测试对应

| 测试文件 | 覆盖内容 |
|---|---|
| `LexerTest` / `ParserTest` | 词法、语法、错误位置、提升信息 |
| `BasicExecutionTest` | 基础运算、作用域、闭包、控制流 |
| `ConformanceTest` | 审查阶段 P0 问题回归 |
| `PropertyAndPrototypeTest` | 属性描述符、原型链、访问器、call/apply/bind |
| `StrictModeTest` | 严格/非严格模式差异 |
| `BuiltinsTest` | 基础标准库 |
| `PhaseBTest` / `PhaseBJsonTest` | do-while/for-in/标签/delete/void/in/instanceof/正则/Date/arguments/盒装值/freeze/Function/JSON 选项 |
| `PhaseCParameterTest` | 箭头函数、默认/rest 参数 |
| `PhaseCStructureTest` | 解构、spread、for-of |
| `PhaseCSymbolCollectionTest` | Symbol、Map/Set/WeakMap/WeakSet、迭代器 |
| `PhaseCPromiseTest` | Promise、async/await |
| `PhaseCClassTest` | class/extends/super/new.target、标签模板 |
| `PhaseCFinalTest` | 参数/catch 解构、Object 批量定义、Symbol.hasInstance、locale 别名 |
| `BuiltinArrayStringAdvancedTest` | Array 补全/稀疏语义、String 边界与 Unicode、Number/Math 补全 |
| `BuiltinObjectDateRegExpTest` | Object 描述符与原型 API、Date 分量/setter/序列化、RegExp 标志/lastIndex/捕获组 |
| `BuiltinCollectionSymbolTest` | Symbol 描述与标签、自定义迭代器、Map/Set 遍历、WeakMap/WeakSet 键校验、Error 层级 |
| `AsyncSemanticsTest` | Promise 组合器、thenable 吸收、微任务顺序、async/await 时序 |
| `ParserEdgeTest` | 正则/除号歧义、转义、非法语法、ASI、严格模式早期错误 |
| `RuntimeSemanticsTest` | TDZ、let 迭代绑定、this/arguments、枚举顺序、位运算、try/finally、JSON 边界 |
| `Es2015SemanticsTest` | 可迭代解构、对象剩余/展开 Symbol、类计算属性/async 方法、super、标签模板 raw、替换回调 |
| `ConversionAndAccessorTest` | Boolean/String/Number 构造器、valueOf/toString 优先级、访问器赋值、new 展开 |
| `HostModuleTest`（:vibe-js-host） | window/self、console、定时器/setInterval、queueMicrotask、atob/btoa、时钟注入 |
