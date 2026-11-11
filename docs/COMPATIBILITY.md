# 兼容性矩阵

状态说明：✅ 支持　⚠️ 部分支持/有已知差异　❌ 尚未支持　⏳ 仅由 CI 覆盖

> 当前覆盖 ES5 与 ES2015 的常用语法和内建，并已实现部分 ES2016+ 特性。
> Generator / async generator、`for await`、Proxy / Reflect、BigInt、
> TypedArray / ArrayBuffer / DataView（含 detach 语义）均已支持并纳入 Test262 基线（2026-11-09，`53fb71b`）。
> 模块侧已能通过 `Parser.parseModule` 解析 `import` / `export` 声明、`import.meta`、
> `import()` 表达式与模块顶层 `await` 语法；阶段 0–8 已实现实验性 `Engine.runModule`（Provider、链接、
> 环境、namespace、`import.meta` 与同步求值），但 **TLA、动态 `import()` 执行与宿主加载器尚未实现**，
> 且 API 需 `@ExperimentalModuleApi` opt-in。
> 真实剩余差异集中在：WeakRef / FinalizationRegistry、Intl、DOM/Web API、
> SharedArrayBuffer / Atomics、resizable / transfer ArrayBuffer；模块执行、
> 顶层 await、性能优化（B2）与 API 契约（PKG1）均为无计划；R1 资源约束已完成主要路径加固。
> 宿主生态已提供 TextEncoder/TextDecoder（UTF-8）、
> structuredClone 与 URL/URLSearchParams 常用子集（`:vibe-js-host`）。

## 1. 词法

| 特性 | 状态 | 说明 |
|---|---|---|
| 关键字（ES5 + class/super/extends/with/do/delete/void/in/instanceof/debugger） | ✅ | |
| 数字：整数/小数/科学计数法/前导小数点 | ✅ | |
| 数字：0x/0o/0b、数字分隔符 | ✅ | |
| 字符串：单/双引号与常见转义 | ✅ | `\n \t \r \b \f \v \0 \\ \' \"` |
| 字符串：`\xXX` / `\uXXXX` / `\u{...}` | ✅ | |
| 模板字符串 | ✅ | 支持 `${}` 嵌套；raw 文本保留原始转义（标签模板行为见语法表） |
| 正则字面量 | ✅ | 通过"上一 Token"启发式区分除法/正则 |
| 注释：单行/多行 | ✅ | 未闭合报错；支持仅位于文件开头的 hashbang |
| 未闭合字符串/模板/正则 | ✅ | 抛出 `LexerException` |
| 标识符：Unicode 字母、`\uXXXX`、`\u{...}` 与 astral | ✅ | Unicode 17.0.0 ID_Start/ID_Continue（含 Other_ID_* 与 ZWNJ/ZWJ） |
| 自动分号插入（ASI） | ⚠️ | 覆盖 return/throw/break/continue/++/-- 换行与常规语句分隔；极少数边界未覆盖 |
| BigInt 字面量 | ✅ | 支持 `123n` 及 `0x`/`0o`/`0b` 前缀，求值得到 BigInt 原始值；任意精度上限见 §6 |

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
| try/catch/finally、throw | ✅ | 支持可选 catch 绑定 `catch { }` |
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
| 正则字面量、String 正则方法 | ✅ | `d`/`s`/`u`/`v`/`y` 标志；命名捕获组、`(?ims-ims:...)` 作用域修饰符、未定长后向断言 `(?<=...)`/`(?<!...)`、Unicode 17.0.0 属性转义（`\p{...}`/`\P{...}`、i 折叠与 astral 码点）、`v` 扩展字符类（并集/交集/差集、嵌套类、属性字符串与 `\q{...}`）、`indices` 捕获区间与 hasIndices、ES2025 互斥分支重复命名组、Annex B 遗留静态访问器；source 按 EscapeRegExpPattern 转义、原型访问器接收者校验；非 u 模式 Annex B IdentityEscape（\x/\u 缺位、\c 等）；忽略大小写按规范 Canonicalize（非 u 单码点大写并保留 U+212A/U+017F 等，u 模式 CaseFolding 简单折叠），字面量、字符类、范围、`\w`/`\b`/反向引用一致；`$` 非 m 模式按 JS 只在输入末尾匹配（JVM 回退规避 Java 的末尾换行差异）；m 模式 ^/$ 按 JS 行终止符集合（CRLF 视为两个终止符、不含 NEL）；Annex B 转义按 JS：字符类内控制转义支持数字/下划线，类外非法控制转义为字面反斜杠，非法十六进制/Unicode 转义按 IdentityEscape，垂直制表符按 U+000B；非 v 模式字符类中的 && 按字面 & 处理；/\B 按 JS 的 ASCII 单词判定处理非 ASCII 输入（JVM 回退）；命名组名按 Unicode 17 ID_Start/Continue 校验（含 $/_、astral 与 ZWNJ）；\p{...} 在 i+u 下按简单大小写与折叠正反向匹配（含 ß/ẞ 与 astral 字母）；字符类单点/范围在 i+u/i+v 下同样按简单折叠匹配（含 astral、Kelvin、ß）；astral 字面量与反向引用按完整码点折叠（含后向断言）；v 模式 q 字符串在 i 下按码点简单折叠（V8 仅折叠 ASCII，见已知差异） |
| 赋值/复合赋值/逻辑赋值（`??=`/`&&=`/`**=` 等） | ✅ | |
| typeof、delete、void、in、instanceof | ✅ | 支持自定义 `Symbol.hasInstance`（沿原型链查找 `@@hasInstance`） |
| 可选链 `?.` | ✅ | 含短路、this 保留与 delete |
| Iterator helpers | ✅ | `Iterator.from`、map/filter/take/drop/flatMap/reduce/toArray/forEach/some/every/find；内建迭代器独立原型与访问器 |
| 异步迭代（`for await...of` / AsyncIterator 原型） | ✅ | `for await`、`Symbol.asyncIterator`、同步迭代器回退（AsyncFromSync）与值/结果 Await；`%AsyncIteratorPrototype%`、`%AsyncGeneratorPrototype%`；break/throw 触发 `return` 关闭 |
| 私有字段/方法/访问器 | ✅ | 品牌检查、不可扩展与重复初始化报错、直接 eval 可见性 |
| Generator（`function*` / `yield`） | ✅ | 同步/异步生成器、`yield*` 委托、`return`/`throw`/`next`、生成器方法、`%GeneratorFunction%` / `%AsyncGeneratorFunction%` 原型链；参数绑定在调用时完成 |
| BigInt（字面量与运算） | ✅ | `123n`、算术/位运算/移位/比较/幂运算、`BigInt()` / `asIntN` / `asUintN`、与 Number 混用抛 TypeError；结果受 `MAX_RESULT_BITS = 2^24` 位限制 |
| 模块声明（import / export） | ⚠️ | `Parser.parseModule` 支持 `import` 四种基础形态、`export` 内联/子句/from/星号/default、静态 `ImportEntry`/`ExportEntry`、类型化 `ModuleProgram.bindings` 与模块早期错误；实验性 `Engine.runModule` 已支持 Provider 图加载、链接、声明实例化、同步求值与 namespace，需 opt-in；宿主加载器/TLA 执行未实现。脚本目标继续拒绝 `import` / `export` |
| 模块表达式（import.meta / import()） | ⚠️ | 模块目标解析为 `ImportMetaExpr` / `ImportCallExpr`；模块求值中 `import.meta` 返回缓存的 url 对象，`import()` 执行时抛 `Dynamic import is not supported`；零参数在解析期报错 |
| 模块顶层 await | ⚠️ | 模块语法层可解析 `await` 并记录 `hasTopLevelAwait`；非 async 函数体中的 `await` 在解析期报错；链接期直接拒绝 TLA，异步求值未实现 |
| Proxy / Reflect | ✅ | Proxy 13 个 trap、`Proxy.revocable`、撤销状态与完整不变量校验；Reflect 13 个方法；解释器属性/调用/构造内部方法统一分派 |
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
| Date | ⚠️ | 构造、now/parse/UTC（MakeDay/MakeTime 双精度与 TimeClip）、ISO 缺省字段与 0..99 年映射差异（构造器/UTC 映射，setFullYear 不映射）、24:00 顺延次日与超长小数秒截断、时区偏移范围校验、常用 getter/setter（无效日期参数顺序、参数无效时内部时间同步失效、规范 length）、toString 本地格式/toUTCString 与负年份补零、toISOString（扩展年份符号 + 6 位数字）/toJSON；本地时间通过宿主 utcOffsetMillis（默认 UTC）换算，构造与无时区 ISO 解析采用两轮偏移修正，本地 setter/toString 按该偏移处理，无效日期的 setFullYear/setYear 以 UTC +0 为基准 |
| Symbol | ✅ | Symbol()、for/keyFor、知名 Symbol、属性键 |
| BigInt | ✅ | `BigInt()`、`asIntN`/`asUintN`、任意精度算术/位运算/移位/比较、`toString(radix)`；与 Number 混用、`JSON.stringify` 直接序列化均抛 TypeError；结果受 `MAX_RESULT_BITS = 2^24` 位限制（超出抛 RangeError） |
| Proxy | ✅ | 13 个 trap、`Proxy.revocable`、撤销状态；`getOwnPropertyDescriptor`/`defineProperty`/`deleteProperty`/`ownKeys`/`get`/`set`/`has`/`isExtensible`/`preventExtensions` 等不变量校验；撤销后所有操作抛 TypeError |
| Reflect | ✅ | 13 个方法（apply/construct/defineProperty/deleteProperty/get/getOwnPropertyDescriptor/getPrototypeOf/has/isExtensible/ownKeys/preventExtensions/set/setPrototypeOf）；失败返回 false，target 参数错误抛 TypeError，支持 receiver |
| ArrayBuffer / DataView / TypedArray | ✅ | ArrayBuffer（`byteLength`/`slice`/`isView`/detach）；DataView 8 种数值 + 4 种 BigInt 读写（大小端由 `littleEndian` 参数控制）；11 种 TypedArray（9 种数值 + `BigInt64Array`/`BigUint64Array`，固定小端序），构造/`from`/`of`/species/迭代/`subarray`/`set`/排序查找；**不支持 resizable / transfer**（不在当前范围，见 §6） |
| Generator / AsyncGenerator | ✅ | 同步/异步生成器、`yield`/`yield*`、`next`/`return`/`throw`、生成器方法、`%GeneratorFunction%` / `%AsyncGeneratorFunction%` 原型链；参数绑定与默认参数在调用生成器函数时完成 |
| Map / Set / WeakMap / WeakSet | ✅ | 常用方法、迭代器协议、Map/WeakMap `getOrInsert`/`getOrInsertComputed`；SameValueZero 索引存储：get/set/has/delete 均摊 O(1)、size O(1)，墓碑在无活跃迭代器时压缩；forEach 与迭代器为实时语义（遍历期间新增可访问、删除跳过、重删重加再次访问）；Weak 版本不可迭代、支持非注册 Symbol 键，但**底层是强引用存储，未实现 GC 弱语义**（M2），不能当作长期缓存的自动清理机制 |
| Iterator | ✅ | 抽象构造器（可 extends）、`Iterator.from`/`concat`/`zip`/`zipKeyed`、helpers（含 chunks/windows/includes/join）、独立迭代器原型与 constructor/@@toStringTag 访问器 |
| Promise | ✅ | then/catch/finally、resolve/reject/all/race/allSettled/any、allKeyed/allSettledKeyed、withResolvers/try、thenable 与循环检测 |
| DisposableStack | ✅ | DisposableStack/AsyncDisposableStack、use/adopt/defer/move/dispose/disposeAsync、Symbol.dispose/asyncDispose 与 SuppressedError |
| using / await using 声明 | ✅ | 块/函数/for 头部资源作用域、逆序释放与 SuppressedError 聚合、for-of 每轮释放、异步释放 Await |
| Intl | ❌ | 未实现（不在当前路线） |
| SharedArrayBuffer / Atomics | ❌ | 未实现（不在当前范围）；相关 Test262 用例按路径/特性跳过 |
| WeakRef / FinalizationRegistry | ❌ | 未实现（M2 弱引用能力的一部分） |

## 4. 函数与对象语义

| 特性 | 状态 | 说明 |
|---|---|---|
| 闭包、递归、this（方法/普通调用/构造函数） | ✅ | |
| new 与 prototype 链、constructor 双向关联 | ✅ | |
| 原型链继承（Object.create / setPrototypeOf / `__proto__`） | ✅ | |
| getter/setter、属性描述符 | ⚠️ | JS 路径（`Object.defineProperty`/`defineProperties`/`create`）按规范校验不可配置属性的字段冲突；低层存储 API（`JSObject.defineOwnProperty`）只做存储，不做不变量二次校验 |
| instanceof、in、delete | ✅ | |
| arguments | ✅ | 类数组对象；sloppy 参数映射与 defineProperty 联动（不可配置/不可枚举后仍保持映射，writable:false 或改为访问器时解除；描述符层面呈现为可写数据属性） |
| 参数环境与函数体 var 环境 | ✅ | 非简单参数下分离参数环境与 var 环境；参数初始化阶段 eval var 对参数闭包/函数体可见、同名冲突 SyntaxError、同名 var 取形参当前值 |
| Annex B B.3.3 块级函数 | ✅ | eval/function/global code 的 var 绑定预置与求值同步、同块重复函数、skip-early-err 词法/catch 解构冲突、function code 形参/arguments 跳过、if/else 单语句函数 |
| Annex B 其他 web-compat | ✅ | HTMLCloseComment（多行注释后/行首定界注释序列）、CallExpression 作为 sloppy 赋值/自增/for-in/of 目标（运行时 ReferenceError，逻辑赋值仍早期错误）、for-in 旧式初始化器（先于 RHS 求值一次）、非 u 正则旧式八进制数字转义 |
| 箭头函数词法 this/arguments | ✅ | |
| class 构造器调用限制、super、new.target | ✅ | |
| Symbol.toPrimitive | ✅ | |
| Symbol.hasInstance | ✅ | |
| Symbol.isConcatSpreadable / species | ✅ | |
| Proxy 内部方法与不变量 | ✅ | 13 个 trap 覆盖属性/原型/可扩展性/调用/构造内部方法；trap 结果按规范校验不可配置属性、不可扩展 target、键集合与 get/set 约束；撤销后全部抛 TypeError；`with(proxy)`、`JSON.stringify`、`Array.isArray` 等走同一内部方法入口 |
| TypedArray 整数索引 exotic | ✅ | 规范数字索引字符串（`"-0"` 除外）走异质路径；合法索引为可写可枚举可配置且不可删除；越界/非整数索引视为不存在、写入静默失败；分离后 `IsValidIntegerIndex` 恒为 false、索引键消失 |
| Generator 状态机 | ✅ | suspendedStart / suspendedYield / executing / completed；重入抛 TypeError；首次 `next` 前 `return`/`throw` 不执行函数体；async generator 按 FIFO 队列串行恢复 |
| BigInt 值模型与转换 | ✅ | BigInt 原始值、装箱、`typeof`、`===`/`==`、混用 TypeError、`ToBigInt64`/`ToBigUint64` 位模式读写 |

## 5. 平台

| 目标 | 状态 | 验证方式 |
|---|---|---|
| JVM | ✅ | 核心 **1169** + 宿主 38 个测试（2026-11-11 记录） |
| JS（Node） | ✅ | 核心 **1153** + 宿主 38 个测试 |
| Wasm（Node） | ✅ | 核心 **1153** + 宿主 38 个测试 |
| Android | ⏳ | CI `testAndroidHostTest`；宿主模块没有 Android 目标 |
| Linux / Windows / macOS / iOS / tvOS / watchOS | ⏳ | CI 各目标测试；2026-11-09 本地复跑 Windows mingwX64 宿主 38 通过（核心 Native 近期未全量重跑） |

> 数字来自各平台测试报告。JVM 的 1169 包含 L4/R1/M2/模块 Provider、图加载、链接、环境、namespace、执行、Iterator 组合器、模块图压力与错误定位新增的 commonTest 用例；JS/Wasm 已在当前基线复跑，均为 1153。"仅由 CI 覆盖"表示本次未在本地复跑。
>
> 构建使用 **JDK 21**（Gradle 9.8.2 要求运行 JVM 17–27）；发布 JVM 字节码 target 为 **1.8**，
> 因此 JVM 产物最低运行 **Java 8**，Android `minSdk` 为 **24**。升级构建 JDK 不改变产物运行门槛。

当前一致性基线（2026-11-10）：parser 脚本 **1,907/1,907**、模块 **76/76 + 76/76 + 47/47 + 63/63**（early 拒绝；early 接受 5/605，均为允许项）；Test262 ES5 **15,248/15,248**；ES2015 **61,786/61,786**，失败 0、跳过 **324**（module flag 322 + 宿主钩子 2）；Node 差分 **1038/1038**；真实库 **375/375**（102 个库）。详细口径见 [CONFORMANCE.md](CONFORMANCE.md)。

## 6. 已知差异

资源限制的异常分类如下（宿主异常均不能被 JS `catch` / `finally` 吞掉）：

| 限制 | 触发点 | 异常 | JS 可捕获 |
|---|---|---|---|
| `maxSteps` | 解释器/内建检查点 | `ExecutionLimitException` | 否 |
| `maxCallDepth` | 逻辑调用深度 | JS `RangeError` | 是 |
| `maxSourceLength` | parse/eval/Function | `ResourceLimitException` | 否 |
| `maxNestingDepth` | Lexer/Parser 递归 | `ResourceLimitException` | 否 |
| `maxAstNodes` | Parser 建节点 | `ResourceLimitException` | 否 |
| `maxJsonDepth` | JSON.parse/rawJSON/stringify | JS `RangeError` | 是 |
| `maxRegexSteps` | 回退引擎；显式降低预算时的高风险路由 | `ResourceLimitException` | 否 |
| `maxPendingTasks` | EventLoop 排队任务 | `ResourceLimitException` | 否 |
| `maxObjectCount` | 对象分配 | `ResourceLimitException` | 否 |
| `maxMicrotasksPerRun` | 单轮微任务清空 | `ResourceLimitException` | 否 |

- 模块执行（实验性）：`Engine.runModule` 已支持 Provider 图加载、链接、模块环境、live binding、namespace、`import.meta` 与同步求值；TLA 在链接期拒绝，动态 `import()` 执行到表达式时抛 `SyntaxError`。模块加载器、Test262 module harness 仍无计划；API 需 opt-in；
- 模块语法限制：import attributes、`import.meta.resolve`、`export using` 未支持；`import()` 零参数与非 async 函数体中的 `await` 已在解析期拒绝；
- 模块目标已按规范禁用 Annex B HTML 注释与 Annex B 语法；脚本目标仍允许 HTML 注释；
- 顶层 await 只有语法解析和 `hasTopLevelAwait` 标记，没有模块异步求值；
- 宿主生态已实现 UTF-8 子集：TextEncoder（encode/encodeInto）、TextDecoder（fatal/ignoreBOM/stream）、structuredClone（对象/数组/Map/Set/Date/RegExp/ArrayBuffer/视图/循环）、URL/URLSearchParams 常用能力；不实现 IDNA、完整 WHATWG URL 状态机、DOM / fetch / localStorage 等 Web API；
- WeakRef / FinalizationRegistry 未实现；WeakMap / WeakSet 使用强引用存储，没有 GC 弱语义，不能用于长期缓存的自动回收（M2 无计划）；
- Intl、SharedArrayBuffer / Atomics、Temporal / Float16Array / 装饰器 / ShadowRealm 不在当前范围或明确无计划；
- ArrayBuffer 不支持 resizable / transfer：`ArrayBuffer.prototype.resize` / `transfer` 不存在；分离（detach）状态只能由宿主调用 `JSArrayBuffer.detach()` 或 Test262 的 `$262.detachArrayBuffer` 触发，JS 语言本身没有公开 detach API；
- BigInt 是任意精度实现，但 `MAX_RESULT_BITS = 2^24`（16,777,216 位）；超过上限的 `pow` / 左移 / `asIntN` / `asUintN` 抛 RangeError，这是规范允许的实现定义上限；
- 其他已知行为/差异：TypedArray 固定小端序；`BigInt("")` 返回 `0n`；`toLocaleString` 使用十进制兜底；`Object.freeze` / `Object.seal` 对非空 TypedArray 不抛 TypeError（已知）；
- R1 资源约束已覆盖源码/嵌套/AST/对象/JSON/正则/排队任务/微任务；第三批补充 `JSON.stringify` 的 `maxJsonDepth` 检查，并在 `maxRegexSteps` 显式降到默认值以下时把常见嵌套量词正则路由到受该预算约束的回退引擎。Kotlin Regex 快路径仍由平台实现，不消耗该预算，这是文档化的边界；`EngineLimits` 不是进程级隔离；
- B2 性能优化与 PKG1 API 契约无计划；S5 已记录属性读写、数组循环与引擎创建的回归，S5.2 只做复测观测，不恢复 B2 排期；
- `Date` 默认 `HostEnvironment.utcOffsetMillis` 返回 0（UTC）；宿主可按时刻返回毫秒偏移以启用本地时间，内核不内置 DST 规则；`toString` 的时区后缀显示为 `UTC±HH:MM`；
- `Date.parse`/`new Date(string)` 仅接受 ISO 8601（含扩展年份）与自身 `toString`/`toUTCString` 格式；V8 额外接受的实现自定义本地格式（如 `2020/01/01`、`Jan 1 2020`）会返回 NaN，属规范允许的实现差异；
- `JSON.parse` 使用独立严格 JSON 语法，不再复用宽松的 JS Parser；
- RegExp 使用 Kotlin Regex 主路径 + 纯 Kotlin 回退引擎；回退路径按 JS-only 构造精确启用（空字符类、`\0`/`\c`、前向反向引用、lookbehind、量词组捕获重置、码元级 `.` 等）；忽略大小写且模式含字母时统一走回退引擎（规范的 Canonicalize），Kotlin 路径只处理不涉及大小写折叠的模式；
- `Math.cbrt` 等超越函数使用平台近似实现，完美立方体回退到精确整数根；
- `RegExp` v 模式 `\q{...}` 字符串在 i 下按规范对每个码点做简单折叠；V8 仅折叠 ASCII，非 ASCII（ß、Kelvin、Deseret）结果不同，差分仅比较 ASCII 串；
- `String` 大小写转换使用仓库内置 Unicode 17.0.0 UCD 表（UnicodeData 简单映射 + SpecialCasing 无条件完整映射 + Final_Sigma），JVM/JS/Wasm 行为一致；V8（Unicode 16）尚缺 A7CE/A7CF、A7D2/A7D3、A7D4/A7D5 三组 Unicode 17 新增配对，BMP 差分审计跳过这些码点，单元测试固定 17.0.0 行为；
- Promise 的微任务调度使用内置 EventLoop（脚本结束后冲刷）；未处理拒绝在每轮微任务清空后通过 `HostEnvironment.reportUnhandledRejection` 上报（默认无行为，已订阅或已上报的拒绝不会重复上报）；已上报的拒绝随后被订阅时通过 `reportRejectionHandled` 通知一次；
- 宿主模块不绑定 DOM/Web API；timeout/interval 共用 Scheduler，`clearTimeout`/`clearInterval` 可互相取消。Engine.run、定时器、async 恢复与微任务共用串行恢复队列，支持多线程提交执行入口；直接访问 Interpreter/全局对象仍须通过 EventLoop.runTask，详见[并发契约](ARCHITECTURE.md#88-事件循环生命周期与并发契约)；
- 参数初始化阶段的直接 eval 若声明与参数环境同名绑定（包括隐式 `arguments`），按规范与 Test262 期望抛 `SyntaxError`；Node/V8 对隐式 `arguments` 允许该声明，该分支不纳入差分用例；
- `++super[key]` 按规范在 ToPropertyKey 之前读取 GetSuperBase（Test262 期望）；Node/V8 在键转换后读取原型，二者结果不同，该分支不纳入差分用例；
- 严格模式下对 RHS 求值前已不可解析的标识符赋值（如 `x = (globalThis.x = 1)`），按规范/Test262 抛 `ReferenceError`；Node/V8 会重新解析并成功写入，该分支不纳入差分用例；
- `Array.fromAsync` 的同步迭代器在 `done: true` 时不读取 `value`（规范与 Test262 期望）；Node 24 会读取并可能抛出 getter 错误，该分支不纳入差分，由核心单测固定规范行为；
- `Object.fromEntries` 在迭代器 `next` 抛错、`next` 结果非对象或 `done` getter 抛错时不调用 `iterator.return`（规范与 Test262 期望）；Node 24 会调用 `return`，这些分支不纳入差分，由核心单测固定规范行为；
- `Array.fromAsync` 以 `String` 作为 `this` 时，写入 `length` 于 String 包装对象会按 `Set` 语义失败并拒绝 `TypeError`；Node 24 会静默保留 `length = 0` 并完成，该分支不纳入差分，由核心单测固定；
- `Array.fromAsync` 同步迭代器的值拒绝（Promise 或 thenable）时只调用一次 `iterator.return`；Node 24 会调用两次，该分支不纳入差分，由核心单测固定；
- `Array.fromAsync` 同步迭代器在 mapper 抛错且 `return` 非函数或 getter 抛错时，Vibe 按规范保留原错误并拒绝；Node 24 的 Promise 不会 settle（既不 resolve 也不 reject），该分支不纳入差分，由核心单测固定；
- `Number.prototype.toString(radix)` 在非 2 的幂进制下按规范生成最短往返表示（有效数字最短且能解析回同一 double）；Node/V8 的实现会截断/近似，部分输出无法解析回原 double（如 `(0.1).toString(5)`），因此差分仅对 2 的幂进制比较完整文本，其余进制由自校验与用例覆盖。

## 7. 测试对应

宿主取消属于 Kotlin 控制流：`Engine.run` 原样抛出 `CancellationException`，
JS 无法通过 catch/finally 拦截；已完成的全局写入保留，尚未执行的微任务丢弃。
协作式检查不主动让出宿主线程，单线程 JS/Wasm 的纯计算超时、解析/单次内建调用的抢占
仍不在保证范围内。独立 async/定时器由 Engine.close 统一取消并等待，单次 run 超时不关闭整个引擎。详见
[协作式取消](ARCHITECTURE.md#85-协作式取消)。

执行配额通过 `EngineLimits` 配置：步数默认不限，调用深度默认 16,384（可设为 null 关闭）；深度计入原生调用、
构造器和 eval，不含全局帧。步数超限抛宿主 `ExecutionLimitException`，
调用深度超限抛可捕获的 JS `RangeError`。async 继承创建时的逻辑深度，
严格尾调用不累计深度。内部协程恢复队列减少深层调用对 Kotlin 物理栈的占用，
并保留同步前缀和微任务顺序；逻辑调用上限不等同于其他 JS 引擎的物理栈容量。
计数范围与资源限制边界见[执行配额](ARCHITECTURE.md#86-执行配额)。

`Error.prototype.stack` 输出创建或首次抛出时的函数名与 `file:line:column`，并保留 async
创建调用链；Error 在 Promise 链中转发/重抛时不覆盖原快照。`Engine.parse/run` 的
`sourceName` 与 `lineOffset` 参数指定脚本来源和显示行偏移，跨脚本函数使用各自定义来源。
该格式属于实现定义诊断，不承诺与主流引擎完全一致；普通 Promise 回调不拼接完整 then 注册链，
严格尾调用省略旧帧。详见[错误栈](ARCHITECTURE.md#87-错误栈)。

| 测试文件 | 覆盖内容 |
|---|---|
| `LexerTest` / `ParserTest` | 词法、语法、错误位置、提升信息 |
| `BasicExecutionTest` | 基础运算、作用域、闭包、控制流 |
| `ConformanceTest` | 审查阶段 P0 问题回归 |
| `RuntimeCancellationTest` / `RuntimeTimeoutTest`（JVM） | 协作式取消、异常透传、清理与复用；JVM 真实死循环超时 |
| `RuntimeLimitsTest` / `ResourceLimitsTest` | 步数与逻辑调用深度、微任务及 async 预算、超限清理与复用；源码长度/嵌套深度/AST 节点/对象数量/JSON.parse 与 JSON.stringify 深度/正则步数（含显式降低预算时的嵌套量词路由）/排队任务/单轮微任务预算与异常分类 |
| `ParserModuleTest` | `Parser.parseModule` 的 import/export 基础形式、import.meta/import()、模块顶层 await、模块早期错误、脚本目标零影响 |
| `ModuleExecutionTest` 等模块测试 | Provider 图加载/链接、ModuleEnvironment、namespace、`import.meta`、顶层 `this`、TLA 拒绝、动态 import 运行期错误、执行/对象/微任务/待处理任务/源码长度（含边界成功）/AST 节点/嵌套深度预算与失败恢复 |
| `ModuleProviderContractTest` | 入口/依赖 referrer、null/异常、sourceName/url、精确 id、空与空白 id 校验、重复 specifier 去重、同 id 多 specifier 去重与首次源码/URL/sourceName 优先、URL 空值回退及空字符串保留、空 sourceName 错误栈、maxModules 与源码预算唯一计数 |
| `ModuleExecutionMatrixTest` | 循环依赖求值/执行顺序、菱形依赖去重、依赖失败隔离（含入口体）、namespace 描述符（含嵌套/空/无导出入口 exotic 不变量、`__proto__` 数据属性、toStringTag 自有性/唯一符号/ownKeys 与 getOwnPropertyNames 反射面、Object.assign/Array.from 互操作、跨 specifier 身份）、显式导出、`export * as default`/`export * as ns`、default 排除/重导出/live binding、import.meta 缓存 |
| `IteratorCombinatorsTest` | `Iterator.concat`/`zip`/`zipKeyed` 的顺序、长度模式、padding 与错误传播 |
| `ModuleGraphStressTest` | 128 层深依赖链、128 个星号导出模块、namespace 描述符、循环星号/重命名/default 导出、歧义省略、显式导出优先与实时绑定、重复 runModule、失败恢复与预算/取消边界 |
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
| `GeneratorTest` / `AsyncGeneratorTest` / `ForAwaitTest` | 同步/异步生成器状态机、`yield*` 委托与 finally、参数绑定时机、`for await` 的 AsyncFromSync/关闭/异常传播 |
| `ProxyTest` / `ProxyInvariantTest` / `ProxyReflectCompletionTest` | 13 个 trap、撤销、trap 结果不变量、跨内建边界（JSON/Array/Function.toString）与 Reflect 联动 |
| `ReflectTest` | 13 个方法、receiver、失败返回 false、Proxy trap 触发 |
| `BigIntTest` / `BigInt64ArrayTest` / `BigIntCrossCheckTest`（JVM） | BigInt 算术/转换/内建、BigInt64/BigUint64 视图与 DataView BigInt 读写、JVM 交叉校验 |
| `ArrayBufferTest` / `ArrayBufferDetachTest` / `TypedArrayTest` / `Float32PrecisionTest` | ArrayBuffer/DataView/TypedArray 构造与读写、detach 后视图语义、11 种类型与整数索引 exotic、Float32 位级舍入 |
| `ParserEdgeTest` | 正则/除号歧义、转义、非法语法、ASI、严格模式早期错误 |
| `RuntimeSemanticsTest` | TDZ、let 迭代绑定、this/arguments、枚举顺序、位运算、try/finally、JSON 边界 |
| `EventLoopLifecycleTest` / `EventLoopConcurrencyTest` / `HostEventLoopLifecycleTest` | 关闭、任务快照、空闲等待、在途取消、跨线程串行化、宿主定时器生命周期 |
| `RuntimeErrorStackTest` | 错误创建与重抛位置、脚本来源和偏移、跨脚本调用、async/Promise 拒绝转发、资源释放、万层栈快照 |
| `RuntimeSemanticsRegressionTest` | 数组异质 length/稀疏索引、array-like 迭代、2^53-1 长度边界、参数映射、eval 作用域、严格 JSON、正则回退、字符串边界、模板内正则扫描、hashbang、可选 catch 绑定、稳定排序、JSON 超集与 rawJSON/reviver source、属性枚举顺序、Annex B RegExp compile/escape/unescape、RegExp dotAll、Error.prototype.stack、Iterator helpers/concat/zip/chunks/windows/includes/join 顺序与关闭、内建迭代器共享原型与实时读取、Array.fromAsync 多输入路径与 done=true 不读取 value、String 包装对象 length 写入失败、同步值 Promise/thenable 拒绝只关闭一次、同步与异步 return 非函数/getter 抛错/拒绝/调用抛错/结果形态均保留原错误、Object.fromEntries 在 next getter/next 抛错/结果非对象/done getter 抛错时不关闭迭代器及键错误保留原错误、Math.sumPrecise 精确求和、WeakMap/WeakSet 符号键、DisposableStack/AsyncDisposableStack/SuppressedError、Annex B 访问器方法、Promise Keyed 组合、Error.isError |
| `Es2015SemanticsTest` | 可迭代解构、对象剩余/展开 Symbol、类计算属性/async 方法、super、标签模板 raw、替换回调 |
| `ConversionAndAccessorTest` | Boolean/String/Number 构造器、valueOf/toString 优先级、访问器赋值、new 展开 |
| `DiagnosticTest` | 词法/语法异常转换为结构化诊断（类别、纯描述、源码范围、来源文件名与行偏移），其他异常不产生诊断，原异常类型与 message 格式保持不变 |
| `ParserConformanceTest` / `Test262SelectionTest` / `RunSupportTest` / `RunMetadataTest`（:vibe-js-conformance） | parser 脚本/模块允许列表与 strict 判定、Test262 筛选与跳过分类、运行器超时/关闭/失败分类、报告元数据 |
| `HostModuleTest` / `HostEcosystemTest`（:vibe-js-host） | window/self 身份、console、定时器/setInterval、queueMicrotask、atob/btoa、宿主时钟、performance；TextEncoder/TextDecoder（UTF-8/fatal/BOM/stream）、structuredClone（对象/数组/Map/Set/Date/RegExp/二进制/循环/错误）、URL/URLSearchParams 常用能力 |
