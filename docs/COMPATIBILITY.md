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
| 注释：单行/多行 | ✅ | 未闭合报错；支持仅位于文件开头的 hashbang |
| 未闭合字符串/模板/正则 | ✅ | 抛出 `LexerException` |
| 标识符：Unicode 字母、`\uXXXX`、`\u{...}` 与 astral | ✅ | Unicode 17.0.0 ID_Start/ID_Continue（含 Other_ID_* 与 ZWNJ/ZWJ） |
| 自动分号插入（ASI） | ⚠️ | 覆盖 return/throw/break/continue/++/-- 换行与常规语句分隔；极少数边界未覆盖 |
| BigInt 字面量 | ⚠️ | 仅语法识别（含 0x/0o/0b 后缀），求值抛 TypeError；BigInt 运行时明确排除 |

## 2. 语法

| 特性 | 状态 | 说明 |
|---|---|---|
| var/let/const、提升、块级作用域、TDZ | ✅ | |
| 空语句、块、if/else、while | ✅ | |
| do-while | ✅ | |
| for（三段式，let 每迭代作用域） | ✅ | |
| for-in / for-of | ✅ | 支持 var/let/const、解构左值、标签 break/continue；for-in 顺序遵循 OwnPropertyKeys 并按原型链去重 |
| switch（default 任意位置） | ✅ | |
| 标签语句、带标签 break/continue | ✅ | |
| try/catch/finally、throw | ✅ | 支持可选 catch 绑定 `catch { }` | |
| 函数声明/表达式、闭包、arguments | ✅ | 命名函数表达式内部名、参数映射（sloppy）均已支持 |
| 箭头函数（块体/表达式体、词法 this、词法 arguments） | ✅ | |
| 默认参数、rest 参数（含解构 rest）、函数 length | ✅ | |
| 解构：声明/赋值/for-of/函数参数/catch、默认值、嵌套、数组 rest、对象 rest | ✅ | |
| spread：数组/调用/new/对象 | ✅ | |
| class/extends/super/new.target | ✅ | 实例/静态方法、getter/setter、静态初始化块 `static { }`、公有/私有实例与静态字段（含计算键与字段初始化器）、私有方法与访问器、`#x in obj`、默认派生构造器；类名 TDZ、`Get(superclass, "prototype")` 校验、构造器 prototype 不可写、静态 `prototype` 成员拒绝、super 访问器以实际 this 为接收者 |
| 块级函数声明 / Annex B | ✅ | strict 下仅在块作用域可见；sloppy 在块入口实例化并同步包围 var 绑定（块外执行前为 undefined） |
| 全局声明实例化 | ✅ | 顶层 let/const/class 不得遮蔽不可配置全局属性；跨脚本词法/var/function 冲突检查、CanDeclareGlobalFunction/Var、CreateGlobalFunctionBinding/CreateGlobalVarBinding 描述符与 eval 全局绑定可删除性均按规范 |
| 对象字面量：简写/方法/getter/setter/计算键 | ✅ | |
| 模板字符串 / 标签模板 | ✅ | 标签模板对象按 AST 站点缓存（每个 realm 一份，重复求值返回同一冻结对象，raw 冻结）；非法转义在标签模板中 cooked=undefined、raw 保留原文，普通模板仍为解析期 SyntaxError |
| 正则字面量、String 正则方法 | ✅ | `d`/`s`/`u`/`v`/`y` 标志；命名捕获组、`(?ims-ims:...)` 作用域修饰符、未定长后向断言 `(?<=...)`/`(?<!...)`、Unicode 17.0.0 属性转义（`\p{...}`/`\P{...}`、i 折叠与 astral 码点）、`v` 扩展字符类（并集/交集/差集、嵌套类、属性字符串与 `\q{...}`）、`indices` 捕获区间与 hasIndices、ES2025 互斥分支重复命名组、Annex B 遗留静态访问器；source 按 EscapeRegExpPattern 转义、原型访问器接收者校验；非 u 模式 Annex B IdentityEscape（\x/\u 缺位、\c 等）；忽略大小写按规范 Canonicalize（非 u 单码点大写并保留 U+212A/U+017F 等，u 模式 CaseFolding 简单折叠），字面量、字符类、范围、`\w`/`\b`/反向引用一致；`$` 非 m 模式按 JS 只在输入末尾匹配（JVM 回退规避 Java 的末尾换行差异）；m 模式 ^/$ 按 JS 行终止符集合（CRLF 视为两个终止符、不含 NEL）；Annex B 转义按 JS：字符类内控制转义支持数字/下划线，类外非法控制转义为字面反斜杠，非法十六进制/Unicode 转义按 IdentityEscape，垂直制表符按 U+000B；非 v 模式字符类中的 && 按字面 & 处理；/\B 按 JS 的 ASCII 单词判定处理非 ASCII 输入（JVM 回退）；命名组名按 Unicode 17 ID_Start/Continue 校验（含 $/_、astral 与 ZWNJ） |
| 赋值/复合赋值/逻辑赋值（`??=`/`&&=`/`||=`/`**=` 等） | ✅ | |
| typeof、delete、void、in、instanceof | ✅ | 自定义 `Symbol.hasInstance` 未支持 |
| 可选链 `?.` | ✅ | 含短路、this 保留与 delete |
| Iterator helpers | ✅ | `Iterator.from`、map/filter/take/drop/flatMap/reduce/toArray/forEach/some/every/find；内建迭代器独立原型与访问器 |
| 私有字段/方法/访问器 | ✅ | 品牌检查、不可扩展与重复初始化报错、直接 eval 可见性 |
| Generator（`function*` / `yield`） | ❌ | 明确排除 |
| import / export | ❌ | 明确排除（单脚本目标） |
| Proxy / Reflect | ❌ | 明确排除 |
| 严格模式 `"use strict"` | ✅ | 重复参数、eval/arguments 名、with、delete、legacy 八进制、直接 eval 继承与单语句声明限制均已覆盖 |
| 严格模式尾调用优化 | ✅ | return 尾位置（含条件/逻辑/逗号/标签模板），try/catch/finally 按待处理语义阻断 |

## 3. 标准内建

| 对象 | 状态 | 已实现 |
|---|---|---|
| 全局常量/函数 | ✅ | undefined、NaN、Infinity、globalThis、parseInt、parseFloat、isNaN、isFinite、Annex B escape/unescape |
| URI 编解码 | ✅ | encodeURI/encodeURIComponent 按 UTF-16 码元做 UTF-8 百分号编码（孤立代理项 URIError）；decodeURI 保留保留字符的原始转义大小写；非法 UTF-8 序列 URIError |
| Object | ✅ | 构造器；keys/values/entries（快照键并逐个复查可枚举）/assign/create/getPrototypeOf/setPrototypeOf/getOwnPropertyDescriptor/getOwnPropertyNames/getOwnPropertySymbols/defineProperty/defineProperties/is/freeze/seal/preventExtensions/isFrozen/isSealed/isExtensible |
| Object.prototype | ✅ | hasOwnProperty、isPrototypeOf、propertyIsEnumerable、valueOf、toString、toLocaleString、`__proto__`、Annex B `__defineGetter__`/`__defineSetter__`/`__lookupGetter__`/`__lookupSetter__` |
| Function | ✅ | call/apply/bind/toString（函数声明/表达式/方法/类构造器/动态函数返回源码文本，无源码时 NativeFunction 形式）、bind 的 length 仅取自有 length 并支持 Infinity、caller/arguments 与严格或 unmapped arguments.callee 共用冻结的 %ThrowTypeError% |
| Array | ✅ | 常用迭代/查找/增删/排序、fill/copyWithin/at、entries/keys/values、toLocaleString、fromAsync；sort 按 SortIndexedProperties（HasProperty/Get/Set/Delete、原型链元素、undefined 末尾、NaN 比较视为相等、稳定）；静态 isArray/of/from（构造器可接收 length、CreateDataPropertyOrThrow 与 Set length 语义）；方法为泛型实现（ToObject/HasProperty/Get/Delete，遵循原型链与稀疏语义），array-like 支持 2^53-1 长度边界 |
| String | ✅ | 常用方法、toLocaleLowerCase/UpperCase（Unicode 17.0.0 UCD 完整映射与 Final_Sigma，跨平台一致）、String.raw、fromCharCode/fromCodePoint；normalize 支持 NFC/NFD/NFKC/NFKD（Unicode 17.0.0 规范化表）；原型方法统一 RequireObjectCoercible，pad 默认空格、localeCompare 规范等价、replace/split 按规范求值顺序 |
| Number | ✅ | 常量、toString(radix)（10 进制为最短十进制表示如 5e-324；2-36 进制用精确二进制大整数展开并按最短往返规则选择有效数字，支持 0.1 的完整二进制展开、超过 2^63 的整数与次正规数）、toFixed/toExponential/toPrecision（非有限值先于范围检查、负零格式化；三者均按 Double 的精确十进制值舍入，含次正规数、进位、负值舍入为 0 的符号；toExponential 无参数时使用最短往返表示，toPrecision 按舍入后的指数选择指数/定点形式）、静态判断与解析（parseInt/parseFloat 与全局同一函数对象；parseInt 的 radix 按 ToInt32，parseFloat 只接受 ASCII 数字；Number() 的字符串输入严格按 StrDecimalLiteral 整体校验，拒绝平台解析器额外接受的 8d/1f 等后缀） |
| Boolean | ✅ | 构造器、toString、valueOf |
| Math | ✅ | 常量与 ES5/ES2015 常用函数；max/min 对全部参数执行 ToNumber；hypot 按最大绝对值缩放避免中间溢出；sumPrecise 精确求和 |
| JSON | ✅ | well-formed 字符串转义（孤立代理项）、parse（reviver 快照键/原型链 Get/CreateDataProperty 不变量与 context.source）、stringify（replacer/space 的包装对象转换、值位置按内部槽展开）、`rawJSON`/`isRawJSON`；字符串字面量允许 U+2028/U+2029（JSON 超集） |
| Error | ✅ | Error/TypeError/RangeError/ReferenceError/SyntaxError/EvalError/URIError、SuppressedError（error/suppressed/可选 message）、`Error.isError`、Error.prototype.toString（name/message 缺省规则与不可强制转换接收者）、Error.prototype.stack 访问器 |
| RegExp | ⚠️ | 字面量/构造器、exec/test/toString、lastIndex、Annex B compile 与旧式八进制数字转义、超大 `{m,n}` 量词与零宽迭代/捕获回滚、u+i 基于 CaseFolding.txt 的简单折叠、s/d 标志、Unicode 属性转义、v 标志扩展字符类（含属性字符串与 `\q`）；Kotlin Regex 主路径 + 纯 Kotlin 回退引擎 |
| Date | ⚠️ | 构造、now/parse/UTC（MakeDay/MakeTime 双精度与 TimeClip）、ISO 缺省字段与 0..99 年映射差异（构造器/UTC 映射，setFullYear 不映射）、24:00 顺延次日与超长小数秒截断、时区偏移范围校验、常用 getter/setter（无效日期参数顺序、参数无效时内部时间同步失效、规范 length）、toString 本地格式/toUTCString 与负年份补零、toISOString（扩展年份符号 + 6 位数字）/toJSON；本地时间按 UTC 处理 |
| Symbol | ✅ | Symbol()、for/keyFor、知名 Symbol、属性键 |
| Map / Set / WeakMap / WeakSet | ✅ | 常用方法、size（Map/Set）、迭代器协议、Map/WeakMap `getOrInsert`/`getOrInsertComputed`；forEach 与迭代器为实时语义（墓碑存储：遍历期间新增可访问、删除跳过、重删重加再次访问）；Weak 版本不可迭代，支持非注册 Symbol 键 |
| Iterator | ✅ | 抽象构造器（可 extends）、`Iterator.from`/`concat`/`zip`/`zipKeyed`、helpers（含 chunks/windows/includes/join）、独立迭代器原型与 constructor/@@toStringTag 访问器 |
| Promise | ✅ | then/catch/finally、resolve/reject/all/race/allSettled/any、allKeyed/allSettledKeyed、withResolvers/try、thenable 与循环检测 |
| DisposableStack | ✅ | DisposableStack/AsyncDisposableStack、use/adopt/defer/move/dispose/disposeAsync、Symbol.dispose/asyncDispose 与 SuppressedError |
| using / await using 声明 | ✅ | 块/函数/for 头部资源作用域、逆序释放与 SuppressedError 聚合、for-of 每轮释放、异步释放 Await |
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
| arguments | ✅ | 类数组对象；sloppy 参数映射与 defineProperty 联动（不可配置/不可枚举后仍保持映射，writable:false 或改为访问器时解除；描述符层面呈现为可写数据属性） |
| 参数环境与函数体 var 环境 | ✅ | 非简单参数下分离参数环境与 var 环境；参数初始化阶段 eval var 对参数闭包/函数体可见、同名冲突 SyntaxError、同名 var 取形参当前值 |
| Annex B B.3.3 块级函数 | ✅ | eval/function/global code 的 var 绑定预置与求值同步、同块重复函数、skip-early-err 词法/catch 解构冲突、function code 形参/arguments 跳过、if/else 单语句函数 |
| Annex B 其他 web-compat | ✅ | HTMLCloseComment（多行注释后/行首定界注释序列）、CallExpression 作为 sloppy 赋值/自增/for-in/of 目标（运行时 ReferenceError，逻辑赋值仍早期错误）、for-in 旧式初始化器（先于 RHS 求值一次）、非 u 正则旧式八进制数字转义 |
| 箭头函数词法 this/arguments | ✅ |
| class 构造器调用限制、super、new.target | ✅ |
| Symbol.toPrimitive | ✅ |
| Symbol.hasInstance | ✅ |
| Symbol.isConcatSpreadable / species | ✅ | |

## 5. 平台

| 目标 | 状态 | 验证方式 |
|---|---|---|
| JVM | ✅ | 核心 501 + 宿主 18 个测试 |
| JS（Node） | ✅ | 核心 501 + 宿主 18 个测试 |
| Wasm（Node） | ✅ | 核心 501 + 宿主 18 个测试 |
| Android | ⏳ | CI `testAndroidHostTest` |
| Linux / Windows / macOS / iOS / tvOS / watchOS | ⏳ | CI 各目标测试 |

## 6. 已知差异

- `Date` 的本地时间方法按 UTC 实现（common 代码无法获取时区），`getTimezoneOffset()` 恒为 0；
- `JSON.parse` 使用独立严格 JSON 语法，不再复用宽松的 JS Parser；
- RegExp 使用 Kotlin Regex 主路径 + 纯 Kotlin 回退引擎；回退路径按 JS-only 构造精确启用（空字符类、`\0`/`\c`、前向反向引用、lookbehind、量词组捕获重置、码元级 `.` 等）；忽略大小写且模式含字母时统一走回退引擎（规范的 Canonicalize），Kotlin 路径只处理不涉及大小写折叠的模式；
- `Math.cbrt` 等超越函数使用平台近似实现，完美立方体回退到精确整数根；
- `String` 大小写转换使用仓库内置 Unicode 17.0.0 UCD 表（UnicodeData 简单映射 + SpecialCasing 无条件完整映射 + Final_Sigma），JVM/JS/Wasm 行为一致；V8（Unicode 16）尚缺 A7CE/A7CF、A7D2/A7D3、A7D4/A7D5 三组 Unicode 17 新增配对，BMP 差分审计跳过这些码点，单元测试固定 17.0.0 行为；
- Promise 的微任务调度使用内置 EventLoop（脚本结束后冲刷），未实现未处理拒绝上报；
- 宿主模块不绑定 DOM/Web API；`setInterval` 基于协程调度，定时器回调与 `Engine.run` 通过运行时互斥锁串行执行（解释器非线程安全），`clearTimeout`/`clearInterval` 可互相取消；`async` 函数体在独立解释器实例上以 `Dispatchers.Unconfined` 恢复执行，不持有 `runtimeMutex`，当前模型面向单线程事件循环，宿主不应在多线程并发调用执行入口；
- 参数初始化阶段的直接 eval 若声明与参数环境同名绑定（包括隐式 `arguments`），按规范与 Test262 期望抛 `SyntaxError`；Node/V8 对隐式 `arguments` 允许该声明，该分支不纳入差分用例；
- `++super[key]` 按规范在 ToPropertyKey 之前读取 GetSuperBase（Test262 期望）；Node/V8 在键转换后读取原型，二者结果不同，该分支不纳入差分用例；
- 严格模式下对 RHS 求值前已不可解析的标识符赋值（如 `x = (globalThis.x = 1)`），按规范/Test262 抛 `ReferenceError`；Node/V8 会重新解析并成功写入，该分支不纳入差分用例；
- `Number.prototype.toString(radix)` 在非 2 的幂进制下按规范生成最短往返表示（有效数字最短且能解析回同一 double）；Node/V8 的实现会截断/近似，部分输出无法解析回原 double（如 `(0.1).toString(5)`），因此差分仅对 2 的幂进制比较完整文本，其余进制由自校验与用例覆盖。

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
| `RuntimeSemanticsRegressionTest` | 数组异质 length/稀疏索引、array-like 迭代、2^53-1 长度边界、参数映射、eval 作用域、严格 JSON、正则回退、字符串边界、模板内正则扫描、hashbang、可选 catch 绑定、稳定排序、JSON 超集与 rawJSON/reviver source、属性枚举顺序、Annex B RegExp compile/escape/unescape、RegExp dotAll、Error.prototype.stack、Iterator helpers/concat/zip/chunks/windows/includes/join 顺序与关闭、内建迭代器共享原型与实时读取、Array.fromAsync 多输入路径、Math.sumPrecise 精确求和、WeakMap/WeakSet 符号键、DisposableStack/AsyncDisposableStack/SuppressedError、Annex B 访问器方法、Promise Keyed 组合、Error.isError |
| `Es2015SemanticsTest` | 可迭代解构、对象剩余/展开 Symbol、类计算属性/async 方法、super、标签模板 raw、替换回调 |
| `ConversionAndAccessorTest` | Boolean/String/Number 构造器、valueOf/toString 优先级、访问器赋值、new 展开 |
| `HostModuleTest`（:vibe-js-host） | window/self 身份、console、定时器/setInterval（额外参数与延迟钳制）、queueMicrotask（重入排序）、atob/btoa（WHATWG Latin-1 码元与宽松 base64 解码）、时钟注入 |
