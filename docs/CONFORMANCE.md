# 解析一致性基线

本文件记录 `test262-parser-tests` 语料的解析一致性基线、已知缺口与推进方式。

## 目标与范围

- 只验证 `Lexer → Parser`，不执行脚本，因此可快速覆盖大量语法样本；
- 语料来自 [tc39/test262-parser-tests](https://github.com/tc39/test262-parser-tests)，固定 commit，不入库；
- 当前只统计脚本（`.js`），`*.module.js` 跳过；
- `pass` / `pass-explicit` 必须解析成功，`fail` 必须被拒绝；
- `early`（语法合法但存在早期错误）只统计“解析成功/被拒绝”，暂不计入通过率。

## 使用方式

```bash
# 首次运行会自动下载固定 commit 的语料
./gradlew :vibe-js-conformance:parserConformance

# 任何正/负向语法不一致都使任务失败（后续作为门禁使用）
./gradlew :vibe-js-conformance:parserConformance -Pstrict
```

报告输出：

- `vibe-js-conformance/build/reports/conformance/parser-summary.md`
- `vibe-js-conformance/build/reports/conformance/parser-summary.json`
- `failures-pass.txt` / `failures-fail.txt`：失败明细

`parserConformance` 默认只报告（退出码 0）；检测到崩溃时退出码 2；加 `-Pstrict` 后存在语法不一致时退出码 1。

## 当前基线

语料版本：`tc39/test262-parser-tests@0e808c74fbec780646434cad17bb22dc52461003`  
生成时间：2026-11-01

| 类别 | 总数 | 通过 | 失败 | 允许 | 通过率 | 崩溃 |
|---|---:|---:|---:|---:|---:|---:|
| pass | 1907 | 1822 | 0 | 85 | 95.54% | 0 |
| pass-explicit | 1907 | 1822 | 0 | 85 | 95.54% | 0 |
| fail | 682 | 674 | 0 | 8 | 98.82% | 0 |

早期错误用例（early）：共 605，解析成功 5，被拒绝 600。  
5 条解析成功项均为现行规范允许的宽松/Annex B 语义（sloppy 顶层函数重复声明、块级函数同名重复、简单 catch 参数与块内 var 同名）；Node 同样接受，本语料为 ES2015 早期版本。fail 类别另有 8 条 NonOctalDecimalEscapeSequence（`\8`/`\9`，非严格模式合法）由允许列表放行。
模块用例跳过：262。

> 剩余 85 条 `pass` 失败全部是 Generator / generator method（项目明确排除），已列入 `vibe-js-conformance/src/main/resources/parser-pass-allowlist.txt`；`fail` 的 8 条允许项为 2 条过期公有字段负例、4 条 `\8`/`\9` 负例，以及 2 条现行 Annex B 语义（sloppy `CallExpression` 赋值目标、旧式 for-in 初始化器），`-Pstrict` 门禁不计为失败。

> 注：`pass` 中包含 Generator、class fields、Unicode 标识符、BindingRestElement 解构等当前范围外的现代语法，原始通过率并不等同于 ES5/ES2015 支持度。

## 已修复（基线驱动）

- do-while 结尾分号可按规范省略；
- Annex B HTML 注释（`<!--`、行首 `-->`）；
- 转义形式的 `'use\x20strict'` 不再被误判为严格模式指令；
- 脚本级 `"use strict"` 指令在解析前预扫描，指令之前的 legacy 八进制等严格模式早期错误同样生效；
- 顶层 `return`、循环/switch 外 `break/continue`、未知标签与非法 continue 标签在解析期拒绝；
- for-of 声明形式禁止初始化器；
- 解构赋值/绑定模式目标校验（成员访问、rest 位置、getter/method 目标等）；
- 复合赋值不允许数组/对象解构目标；
- getter/setter 形参数量与 setter rest 参数校验；
- `Object.defineProperty/defineProperties/create` 的描述符合并、不变量与可枚举过滤；
- `new` 构造时读取当前 `F.prototype`，而不是创建函数时捕获的原型；
- `delete` 不再预读成员值；`with` 下复合赋值保持最初的对象基引用；
- Number/Boolean/String 原型方法的接收者校验与包装对象原始值；
- 无 `.prototype` 的原生内建方法不能被 `new`；
- 描述符 getter/setter 可调用性校验；
- Array 只读方法按 HasProperty/Get 实时读取（支持 array-like、原型链元素、迭代期间的删除与 length 变化），写方法回写原对象，回调第三参数保持原对象；
- 数组异质属性：length 的 ToUint32/RangeError、不可写 length、稀疏索引与 2^32-1 普通属性语义；
- sloppy arguments 的参数映射与 `Object.defineProperty` 联动（10.6）；
- 直接 eval 继承调用方严格模式，严格 eval 声明不泄漏；间接 eval 使用全局作用域；
- 严格模式函数参数/函数名早期错误（重复参数、eval/arguments、未来保留字）；
- 标识符中的 `\uXXXX` / `\u{...}` 转义、astral 标识符（Unicode 17.0.0 ID_Start/ID_Continue）、转义保留字拒绝；
- 更完整的 Unicode ID_Start/ID_Continue 近似（组合符号、连接标点、ZWNJ/ZWJ、Nl/Nd、Other_ID_Start）；
- sloppy 模式下 `let` 作为普通标识符（表达式、绑定、for 头部）；
- 严格模式拒绝 legacy 八进制字面量与字符串八进制转义；模板字符串拒绝八进制转义；`\8`/`\9` 字符串转义始终非法；
- 宽松相等 `==`/`!=` 对对象与 null/undefined 直接返回 false，不再触发 ToPrimitive（lodash 加载失败根因）；
- JSON.parse reviver 删除属性时保留其余属性的可枚举性；arguments 映射索引的 `getOwnPropertyDescriptor` 返回数据属性；不可扩展对象 `__proto__` 赋值抛 TypeError；`u` 正则支持 `\u{...}` 码点转义；
- 正则回退引擎扩展到空字符类、`\0`、`\c`、字符类内 `\b`/嵌套 `[`、前向反向引用、量词组捕获重置与 lookahead 反向引用；
- 正则/除号判定的控制语句与语句块上下文；`u` 标志的早期错误（悬空 `{`/`}`、量词化 lookahead、引用不存在的捕获组、IdentityEscape 空白）；
- rest 解构参数（`function f(...[a,b])`）、单语句上下文中的词法/函数声明限制、括号化解构模式的早期错误；
- ES2015 上下文早期错误：`super` 属性/调用仅限方法体与派生构造器（含参数默认值、箭头函数继承），`new.target` 仅限函数体与参数默认值，类内 `"constructor"` 字符串键识别，static `"prototype"` 名称拒绝；`(super())` 作为调用表达式合法，`(super)` 及其成员/调用仍拒绝；
- 声明作用域早期错误：同一作用域内的 let/const/class 重名、词法与 var/function 冲突、函数形参与函数体词法声明冲突、for 头部词法与循环体 var 冲突、switch 各 case 共享词法作用域；
- 解构绑定重名（let/const/参数/catch）与参数唯一性：解构参数内部重复始终拒绝，普通函数仅在 strict/非简单参数列表时拒绝重复，对象/类方法始终使用 UniqueFormalParameters；
- catch 参数规则（重复绑定、与块内词法声明冲突、解构参数与 var 冲突；简单参数 + var 按 Annex B 允许）；
- 标签重复与标签链位置：sloppy 语句列表位置的标签函数声明允许，单语句体中的标签函数声明拒绝；
- 对象字面量重复 `__proto__` 数据属性（延迟判定，赋值/绑定模式允许重复属性名）；
- 类构造器唯一性与合法性：重复构造器、getter/setter 构造器、async 构造器拒绝，static 与计算键不构成构造器；
- 非简单参数列表与 `"use strict"` 指令冲突、严格模式解构绑定名 eval/arguments；
- `u` 标志下 `\u{...}` 码点必须 ≤ 0x10FFFF；
- JSON.parse 使用独立严格 JSON 语法（拒绝单引号、控制字符、非 JSON 空白）；
- Object.prototype.valueOf/toLocaleString 接收者检查、instanceof 的 prototype 校验、bind 函数的 [[Construct]]；
- Number `toFixed`/`toExponential`/`toPrecision` 的精确十进制展开：把 Double 分解为 `m × 2^e` 后改写为十进制大整数（`m × 2^e` 或 `m × 5^(-e) × 10^e`），按保留位数做“并列取更大 n”的舍入；覆盖次正规数、舍入进位提升指数、负值舍入为 0 的符号保留、`toExponential` 无参数的最短往返表示，以及 `toPrecision` 按舍入后指数选择指数/定点形式。
- 正则忽略大小写的规范 Canonicalize：非 u 模式使用单码点大写映射并保留 U+212A/U+017F 等非 ASCII 字符，u 模式使用 CaseFolding 简单折叠；含字母的 i 模式统一由回退引擎处理，修正字面量、字符类、范围、`\w`/`\b` 与反向引用在特殊字符上的过度匹配。
- `Math.hypot` 按最大绝对值缩放后再求平方和（避免大数参数中间溢出），保持 Infinity 优先于 NaN 与符号零语义。
- `Date.prototype.toISOString` 的扩展年份按 ISO 8601 输出符号 + 至少 6 位数字（修复超过 9999 年时输出 5 位的问题）。
- Date 字段 setter 在参数无效（NaN/显式 undefined）时同步把内部时间写为 NaN；原时间已为 NaN 的普通 setter 按规范直接返回 NaN，保留 valueOf 副作用写入的时间。
- Number `toString(radix)` 的 2-36 进制规范化：以二进制大整数精确展开并按相邻 double 中点构成的最短往返区间选择有效数字（并列取偶数），覆盖 0.1 的完整二进制展开、非 2 的幂进制的舍入、超过 2^63 的整数与次正规数；10 进制仍使用 NumberFormatting。

## Test262 ES5 语义基线

```bash
# 首次运行会自动下载固定版本的 tc39/test262
./gradlew :vibe-js-conformance:test262Conformance

# 限制用例数（快速验证）
./gradlew :vibe-js-conformance:test262Conformance -Ptest262Limit=1000
# 控制并行度
./gradlew :vibe-js-conformance:test262Conformance -Ptest262Jobs=4
```

范围：`test/language` + `test/built-ins` 中带 `es5id` 的脚本；自动加载 `sta.js`/`assert.js` 与 metadata `includes`；支持 `negative` 的 parse/runtime、`onlyStrict`/`noStrict`/`raw` 变体；`flags: [async]` 通过注入 `$DONE` 驱动；跳过 module/generator 标志、当前不支持的 features、`$262`、缺失 include。

当前基线（test262@91c70759363c46557b694792bf34a2551a7af5ba）：

| 指标 | 数值 |
|---|---:|
| 选中测试文件 | 8088 |
| 执行次数（含 strict/sloppy） | 15244 |
| 通过 | 15244（100%） |
| 失败 | 0 |
| 崩溃 | 0 |
| 跳过测试文件 | 2 |

> 跳过项仅为 `features: [Reflect.construct]`（项目明确排除 Reflect）。直接/间接 eval 作用域、数组异质 length 与稀疏索引、参数映射、对象描述符不变量、严格模式早期错误与 RegExp JS-only 语法均已收敛到 ES5 子集基线。

## Test262 ES2015 特性子集

```bash
# 按已支持特性筛选的 ES2015 子集（CI 门禁使用 -Pstrict）
./gradlew :vibe-js-conformance:test262Es2015Conformance

# 定向调试
./gradlew :vibe-js-conformance:test262Es2015Conformance -Ptest262Filter=for-of
```

范围：`test/language` + `test/built-ins` + `test/annexB` 中非 `es5id` 且满足以下条件之一的脚本：`features` 全部落在已支持集合内；或无 `features` 标注但带 `es6id`/`esid`（ES2015+ 核心语义用例通常以 `esid` 标注）。默认纳入 esid 用例，使语义覆盖扩展到 20374 个文件。runner 提供最小 `$262.evalScript` 宿主钩子（在同一 realm 中执行新 Script，触发 GlobalDeclarationInstantiation），并放行仅使用该成员的用例（`language/global-code/script-decl-*`）。跳过 module/generator 标志、async 负数用例、其余 `$262` 宿主钩子、缺失 include 与源码或既有 harness 依赖 BigInt/Proxy/ArrayBuffer 的用例（如 `TemporalHelpers.propertyBagObserver`）；排除未实现的宿主型内建（ArrayBuffer/DataView/TypedArray/SharedArrayBuffer/Atomics，含 `class/subclass` 下的 ArrayBuffer 子类与 `builtins.js`）、AsyncIterator 原型，以及内容依赖 Generator 的计算属性名用例；joint-iteration 语料的 harness 需要 Reflect 观测与生成器语法：runner 注入仅用于断言的最小 Reflect 垫片，并把 `iteratorZipUtils.js` 的 prefixes 生成器替换为等价数组实现；核心库仍不提供 Reflect 与 Generator。属性转义生成用例需要构造上百万码点的字符串，runner 为 `regExpUtils.js` 的 `buildString` 注入语义等价的宿主实现，避免解释器逐码点循环成为瓶颈。BigInt 字面量仅保留语法识别，求值仍抛 TypeError。`flags: [async]` 用例通过 runner 注入 `$DONE` 并等待落定后判定。该基线已接入 `Conformance` 工作流（手动/每周定时）的 `test262-es2015` Job，并使用 `-Pstrict` 作为门禁。

当前基线（与 ES5 相同的 test262 提交）：

| 指标 | 数值 |
|---|---:|
| 选中测试文件 | 20374 |
| 执行次数（含 strict/sloppy） | 37329 |
| 通过 | 37329（100%） |
| 失败 | 0 |
| 崩溃 | 0 |
| 跳过测试文件 | 836（unsupported flag=283 + BigInt/Proxy/Generator=540 + ArrayBuffer 特性=7 + harness Proxy=5 + print=1） |

Unicode 数据表（`\p{...}` 属性、属性字符串、标识符 ID_Start/ID_Continue、`String.prototype.normalize` 的分解/组合数据与 String 大小写映射）由 `python tools/generate_unicode_data.py` 从 Unicode 17.0.0 UCD 自动下载并生成；对应源码文件为 `runtime/regex/UnicodePropertyData.kt`、`runtime/regex/UnicodeStringPropertyData.kt`、`lexer/UnicodeIdentifierData.kt`、`runtime/UnicodeNormalizationData.kt` 与 `runtime/UnicodeCaseMappingData.kt`（附 `runtime/UnicodeNormalizer.kt` 实现 NFC/NFD/NFKC/NFKD，`runtime/UnicodeCaseMapping.kt` 实现完整大小写映射与 Final_Sigma），请勿手工修改。

已收敛的语义簇包括：类与 super（未初始化 this、动态父构造器、内建子类化）、class 静态初始化块（`static { }` 按源码顺序执行、this 为类构造器、super 属性与 new.target 为 undefined；arguments/await/return/super() 早期错误与块级作用域隔离）、class 公有字段（实例/静态字段、计算键在类定义阶段求值、DefineField 语义与冻结/不可配置属性报错、派生类字段在 super() 返回后初始化、字段初始化器的 NamedEvaluation 与 arguments/await 限制）、class 私有元素（私有字段/方法/访问器与静态私有、品牌检查与 `#x in obj`、PrivateFieldAdd/PrivateMethodOrAccessorAdd 的不可扩展与重复初始化报错、直接 eval 的私有名可见性、计算属性名求值时的私有名预声明）、`using`/`await using` 声明（块/函数/for 头部的资源作用域、逆序释放与 SuppressedError 聚合、`@@asyncDispose` 优先与 Await 释放、for-of 每轮释放、解构模式与单语句体等早期错误）、Promise capability/SpeciesConstructor/NewPromiseCapability（all/race/resolve/reject/then/catch/allSettled/any/withResolvers/try/finally，含 thenable 采纳作业）、AggregateError、%AsyncFunction% 构造器与原型链、async/await（含 thenable 采纳的微任务交错与参数 TDZ）、Array/Map/Set/RegExp/Promise 的 `@@species` 与 Array 方法的 ArraySpeciesCreate、`@@isConcatSpreadable`、ES2019 `flat`/`flatMap`、幂运算与逻辑赋值、`globalThis`、对象剩余/展开、数字分隔符字面量、Map/WeakMap `getOrInsert`/`getOrInsertComputed`、Error `cause`、ES2016+ 的 String/Array/Object 方法（含 replaceAll：@@replace 分派、GetSubstitution、空串与函数替换）（includes/at/trimStart/trimEnd/fromCodePoint/findLast/findLastIndex/isWellFormed/toWellFormed/hasOwn/fromEntries）与 change-array-by-copy（toReversed/toSorted/toSpliced/with）、RegExp 的 `@@match`/`@@replace`/`@@search`/`@@split`/`@@matchAll` 协议、`RegExp.escape`、String 方法分派与 RegExp String Iterator、可选链（含短路、this 保留与 delete）、`Symbol.unscopables` 与 with 绑定解析、Object/Map.groupBy 与字符串码点迭代、ES2025 Set 方法集（union/intersection/difference/symmetricDifference/isSubsetOf/isSupersetOf/isDisjointFrom）、Iterator helpers（`Iterator.from`、`Iterator.concat`、map/filter/take/drop/flatMap/reduce/toArray/forEach/some/every/find，含参数校验先于读取 next、IfAbruptCloseIterator 与 helper 重入检测）、内建迭代器独立原型（Array/String/Map/Set/RegExp String Iterator，next 位于共享原型并通过内部槽做品牌校验、实时读取目标 length/元素且在耗尽后保持 done）与 `Iterator.prototype` 的 constructor/@@toStringTag 访问器、`Array.fromAsync`（同步迭代器逐值 Await、异步迭代器保留原值、array-like 空洞与映射结果 Await、自定义构造器与 Promise 拒绝语义）、数组泛型方法在 2^53-1 长度边界的 array-like 行为（pop/push/unshift/reverse/slice/splice 的 Long 索引与范围校验、includes 在 len 为 0 时不读取 fromIndex）、`Math.sumPrecise`（以 2^-1074 为单位的精确累加、中间溢出抵消与 roundTiesToEven）、`Iterator.zip`/`zipKeyed`（shortest/longest/strict 模式、padding 迭代器与属性包、逆序关闭与 strict 同轮结束检查）、WeakMap/WeakSet 的非注册 Symbol 键（注册符号与非对象键仍抛 TypeError）、DisposableStack 与 SuppressedError（use/adopt/defer/move/dispose、逆序释放与嵌套 SuppressedError、Symbol.dispose/asyncDispose 与 Iterator.prototype[Symbol.dispose]）、AsyncDisposableStack（@@asyncDispose 优先与 @@dispose 回退、逐个 Await 释放结果、move/disposed 与 Promise 拒绝语义）、Annex B 的 `__defineGetter__`/`__defineSetter__`/`__lookupGetter__`/`__lookupSetter__`（保留另一侧访问器、不可配置属性拒绝重定义）、`Iterator.prototype.chunks`/`windows`（整数尺寸校验、末尾部分块与 allow-partial 窗口、每次产出新数组）、`Promise.allKeyed`/`allSettledKeyed`（自有可枚举键遍历、null 原型结果对象与键顺序保持）、`Iterator.prototype.includes`/`join`（SameValueZero、skippedElements 校验与命中关闭、分隔符与内容 ToString 的关闭语义）、`Error.isError`（[[ErrorData]] 判定）、hashbang 注释（仅限源文件开头，runner 保留 metadata 前的可执行前缀）、Unicode 17.0.0 标识符表（ID_Start/ID_Continue 含 Other_ID_*、ZWNJ/ZWJ，BMP 与 astral 字面量/转义统一按表校验）、严格模式尾调用优化（return 尾位置经条件/逻辑/逗号/标签模板传播，尾调用信号回到调用循环复用栈帧；try/catch/finally 按待执行 handler/finalizer 精确阻断；直接 eval 判定改用内建 %eval% 身份）、Function.prototype.toString（Function/AsyncFunction 构造器返回规范化源码，其余函数返回 NativeFunction 形式，私有名省略）、可选 catch 绑定、Array.prototype.sort 稳定性、JSON 超集（字符串字面量允许 U+2028/U+2029）、Error.prototype.stack 访问器（仅对有 [[ErrorData]] 的接收者返回字符串；setter 仅接受字符串并通过特殊 Set 语义写入）、JSON 源文本提案（`JSON.rawJSON`/`isRawJSON`、reviver 的 context.source 与属性改写后不再提供源文本）、属性枚举顺序（整数键升序、字符串插入顺序、for-in 原型链顺序与遮蔽）、Annex B `RegExp.prototype.compile`（原地重编译、lastIndex 严格写入）与全局 `escape`/`unescape`、`String.prototype.trimLeft`/`trimRight` 别名、RegExp dotAll（s 标志；Kotlin 路径展开 `.` 为 `[\s\S]`，回退引擎原生支持）与空非捕获组 `(?:)` 编译、RegExp `d` 标志（indices 捕获区间数组、hasIndices 访问器与 flags 顺序；捕获位置依赖回退引擎）、RegExp 命名捕获组（RegExpIdentifierName 校验与 Unicode 转义、前向/自引用 `\k<name>`、groups 空原型对象、`$<name>` 替换与函数替换的 groups 参数、d 标志下的 indices.groups、ES2025 互斥分支重复命名组与候选动态解析）、RegExp 作用域修饰符（`(?ims-ims:...)` 的解析/早期错误与 i/m/s 局部覆盖、嵌套与 `(?-i:)` 反向覆盖、i 对 `\w`/`\b`/反向引用的 Canonicalize）、RegExp 后向断言（`(?<=...)`/`(?<!...)` 由回退引擎反向消费实现变长 lookbehind、反向贪婪与捕获覆盖顺序、数字反向引用统一走回退引擎以对齐前向/自引用语义、零宽量词迭代）、Unicode 属性转义（由 UCD 17.0.0 生成的二进制属性/General_Category/Script/Script_Extensions 码点表、属性别名精确匹配、`\P` 补集先于 i 模式大小写折叠、相邻代理项转义合并为 astral 码点、字符类内组合与范围端点早期错误）、RegExp `v` 标志的扩展字符类（并集/交集 `&&`/差集 `--`、嵌套类与嵌套否定、码点级集合求值、属性字符串 `\p{RGI_Emoji}` 等与 `\q{...}` 字符串析取、多码点字符串回溯匹配与空串、u/v 互斥与 `unicodeSets` 访问器）、`\s`/`\S` 覆盖完整 WhiteSpace/LineTerminator 集合并统一走回退引擎、孤立代理项与 `\uXXXX` 代理项转义按码元语义匹配、RegExp 遗留静态访问器（input/$_、lastMatch/$&、lastParen/$+、leftContext/$`、rightContext/$'、$1-$9 与构造器身份校验）、`__proto__` 访问器（ToObject 语义与原型环检测）、`??` 与未加括号 `&&`/`||` 混用的早期错误、AggregateError 的 message/cause 按需创建、well-formed JSON.stringify（孤立代理项转义）、U+180E 非空白、Function.caller 访问器、非 u 模式 `.` 的码元语义（统一走回退引擎）、for-in/for-of 继承细节、解构与迭代器关闭、Map/Set 实时迭代器、`__proto__` 与方法 HomeObject、RegExp/Date 原型访问器；本轮新增收敛：class 语义（类名在 heritage 求值前建立 TDZ、`Get(superclass, "prototype")` 可触发访问器且非对象/非 null 抛 TypeError、构造器 prototype 描述符不可写、静态 `prototype` 成员字面量早期错误与计算键 TypeError、class 绑定可变、super 属性访问器以实际 this 为接收者、派生构造器 super() 前访问 super 抛 ReferenceError、super(super()) 双重绑定检测）、块级函数声明（strict 块作用域实例化、sloppy Annex B 在块入口同步包围 var 绑定、闭包捕获块环境、同名后者生效）、函数体词法环境与参数/var 环境分离（非简单参数下 body `let arguments` 合法且仍创建 arguments 对象）、全局声明实例化（`let undefined` 等受限全局属性 SyntaxError）、EvalDeclarationInstantiation 全局可定义性检查（CanDeclareGlobalFunction/CanDeclareGlobalVar，失败先抛 TypeError 且不残留部分 var 绑定）、with 环境的标识符调用 this（WithBaseObject）与 var/for-in 引用解析顺序、标签模板成员接收者与 `new tag\`...\`` 构造链、模板行延续含 U+2028/U+2029、条件表达式 +In 分支的 for 头部判定、`String.prototype.normalize`（NFC/NFD/NFKC/NFKD、Hangul 算法、ToNumber 形式校验前的 ToString(this) 顺序）、JSON 序列化包装对象（Number/String/Boolean 内部槽展开）与 reviver 快照键/CreateDataProperty 不变量、Number::toString 最短十进制表示（5e-324）、RegExp source 的 EscapeRegExpPattern 转义与原型访问器接收者校验、GlobalDeclarationInstantiation 的跨脚本检查（词法名与既有不可配置全局属性/词法绑定冲突、var/function 与既有全局词法绑定冲突、CanDeclareGlobalFunction 需满足可配置或“可写且可枚举”）、CreateGlobalFunctionBinding/CreateGlobalVarBinding 的描述符语义（可配置同名属性重定义为不可配置数据属性、既有不可配置属性只更新值）以及 eval 全局绑定的 D=true 可删除性；本轮参数环境收敛：非简单参数下分离参数环境与函数体 var 环境（参数默认值中的闭包看不到函数体 var）、参数初始化阶段 VariableEnvironment 指向函数最外层环境（直接 eval 的 var/function 声明对参数闭包与函数体均可见）、函数体 var 与形参同名时以形参当前值初始化（解构形参与 arguments 同理）、eval 的 var/function 声明与参数环境同名绑定冲突抛 SyntaxError（含隐式 arguments，按 Test262 规范期望；Node 对隐式 arguments 允许，属已知引擎偏离）；Array.prototype.sort 按现行规范 SortIndexedProperties 收集与写回（HasProperty/Get/Set/Delete、原型链元素、undefined 末尾、比较函数返回 NaN 视为相等、稳定排序，array-like 直接读写原对象）、URI 编解码（encodeURI/encodeURIComponent 按 UTF-16 码元编码并对孤立代理项抛 URIError、decodeURI 保留保留字符的原始转义大小写、非法 UTF-8 序列抛 URIError）与 parseFloat/parseInt 边界（仅 ASCII 数字；radix 按 ToInt32，Infinity/NaN 归 0）、mapped arguments 的 defineProperty 语义（不可配置/不可枚举后仍保持映射并同步形参，writable:false 或改为访问器时解除映射，描述符层面呈现为可写数据属性）、Map/Set 的 forEach 与迭代器实时遍历（墓碑存储：期间新增可访问、删除跳过、重删重加作为新记录再次访问）、标签模板对象按 AST 站点缓存（每个 realm 一份，重复求值返回同一冻结对象，raw 冻结）、单语句上下文中的 `let` 退化（`let` 作为标识符表达式并触发 ASI；`let [` 仍按前瞻限制报早期错误）、对象字面量 async 方法的前瞻校验（`async` 与属性名间禁止换行、属性名后必须紧跟形参表，计算键需匹配 `]`）、reduce/reduceRight 的 HasProperty/Get 顺序（getter 不重复调用、按捕获 len 迭代）、Array.prototype.toString 的 join 不可调用回退、lastIndexOf 显式 undefined fromIndex、`__proto__` 冒号形式不参与 NamedEvaluation、Set.keys/values 同一函数对象与 AsyncFunction 继承 Function、delete/计算成员访问的 ToObject 先于 ToPropertyKey（null/undefined 基对象抛 TypeError，原始值按包装对象删除）、旧式八进制字面量与字符串转义的位宽/严格模式规则、Lookbehind 量化的解析期早期错误、`for (async of => {})` 箭头函数消歧与裸 `async` for-of 左值限制（转义写法与括号包裹仍可作为普通标识符）、sloppy eval 新建 var/function 绑定的可删除性（无初始化器的 `var` 语句不再重建已删除绑定）、switch/块内 async 函数声明的词法作用域（不参与 Annex B var 提升）、catch 参数环境与 catch 块词法环境分离（参数默认值闭包看不到块内 let/const）、对象解构赋值的求值顺序（源属性键先于目标引用，目标键 ToPropertyKey 延迟到 PutValue）、括号包裹的标识符赋值目标不参与 NamedEvaluation、super 属性赋值/自增的顺序（GetSuperBase 先于 ToPropertyKey、简单赋值 RHS 先于 null base 的 TypeError、未初始化 this 先抛 ReferenceError）、**Annex B B.3.3 块级函数 var 扩展全量语义**（eval/function/global code 的预置 undefined 绑定、条件执行后同步 var 绑定、同块重复函数、skip-early-err 的词法/catch 解构冲突与 function code 的形参/arguments 跳过、Annex B if/else 单语句函数）、Annex B HTMLCloseComment（多行注释后任意位置、行首定界注释序列）、CallExpression 作为 sloppy 赋值/自增/for-in/of 目标的运行时 ReferenceError（RHS 不求值、逻辑赋值仍为早期错误）、for-in 旧式初始化器（[~In] 解析、先于 RHS 求值一次、仅允许 var）、非 u 正则数字转义在无对应捕获组时按旧式八进制解释（含 4-7 开头最多 2 位与前向反向引用优先级）、标签模板非法转义的 cooked=undefined（raw 保留原文，普通模板仍在解析期报 SyntaxError 且 cooked 非空）、标识符引用的解析时机（LHS 在 RHS 前已不可解析时，严格模式即使 RHS 创建同名全局属性也抛 ReferenceError）、正则超大 `{m,n}` 量词（任意十进制数钳制到 Int 上限并回退到内置引擎，超过输入长度必然不匹配）、零宽量词迭代语义（可选迭代零宽时丢弃并回滚捕获，必要迭代允许零宽推进，修复 lookahead 量词捕获与 nullable quantifier）、Unicode 忽略大小写正则的简单折叠（由 CaseFolding.txt 状态 C/S 生成的 1512 条映射，替代 Kotlin 的完整大小写转换）。include-esid 用例已纳入默认基线，当前为 37329/37329（Number 精确十进制格式化已收敛）。剩余跳过项为 module/generator 标志、async 负数用例与 BigInt/Proxy/Generator（明确排除项）。

## 真实库冒烟与 Node 差分

```bash
# 下载固定版本的 UMD 构建并执行 API 级冒烟
./gradlew :vibe-js-conformance:librarySmoke

# 与 Node 对比 JSON/Number/RegExp/String/Date 等行为（含微任务顺序异步用例）
./gradlew :vibe-js-conformance:differentialConformance
```

真实库基线（lodash 4.17.21 / mustache 4.2.0 / dayjs 1.11.10 / qs 6.12.3 / validator 13.12.0 / underscore 1.13.6 / moment 2.30.1 / semver 4.3.6 / punycode 1.4.1 / json5 2.2.3 / big.js 6.2.1 / diff 5.1.0 / lz-string 1.5.0 / marked 4.3.0 / bignumber.js 9.1.2 / decimal.js 10.4.3 / crypto-js 4.2.0 / papaparse 5.4.1 / js-yaml 4.1.0 / ramda 0.29.0 / he 1.2.0 / numeral 2.0.6 / showdown 2.1.0 / seedrandom 3.0.5 / pluralize 8.0.0 / highlight.js 11.9.0 / tinycolor2 1.6.0 / deepmerge 4.3.1 / big-integer 1.6.51 / rxjs 7.8.1 / eventemitter3 5.0.1 / mitt 3.0.1 / immutable 4.3.5 / redux 4.2.1 / markdown-it 13.0.2 / handlebars 4.7.8 / ajv 6.12.6 / esprima 4.0.1 / acorn 8.11.3 / astring 1.8.6 / acorn-walk 8.3.2 / crc-32 1.2.2 / blueimp-md5 2.19.0）：

| 库 | 状态 | 用例 |
|---|---|---|
| lodash | 可加载 | 8/8 |
| mustache | 可加载 | 3/3 |
| dayjs | 可加载 | 3/3 |
| qs | 可加载 | 4/4 |
| validator | 可加载 | 4/4 |
| underscore | 可加载 | 3/3 |
| moment | 可加载 | 3/3 |
| semver | 可加载 | 7/7 |
| punycode | 可加载 | 4/4 |
| json5 | 可加载 | 3/3 |
| big.js | 可加载 | 5/5 |
| diff | 可加载 | 3/3 |
| lz-string | 可加载 | 4/4 |
| marked | 可加载 | 4/4 |
| bignumber.js | 可加载 | 4/4 |
| decimal.js | 可加载 | 4/4 |
| crypto-js | 可加载 | 4/4 |
| papaparse | 可加载 | 4/4 |
| js-yaml | 可加载 | 4/4 |
| ramda | 可加载 | 4/4 |
| he | 可加载 | 3/3 |
| numeral | 可加载 | 4/4 |
| showdown | 可加载 | 3/3 |
| seedrandom | 可加载 | 3/3 |
| pluralize | 可加载 | 4/4 |
| highlight.js | 可加载 | 3/3 |
| tinycolor2 | 可加载 | 4/4 |
| deepmerge | 可加载 | 3/3 |
| big-integer | 可加载 | 5/5 |
| rxjs | 可加载 | 4/4 |
| eventemitter3 | 可加载 | 3/3 |
| mitt | 可加载 | 3/3 |
| immutable | 可加载 | 5/5 |
| redux | 可加载 | 4/4 |
| markdown-it | 可加载 | 4/4 |
| handlebars | 可加载 | 4/4 |
| ajv | 可加载 | 5/5 |
| esprima | 可加载 | 5/5 |
| acorn | 可加载 | 5/5 |
| astring | 可加载 | 5/5 |
| acorn-walk | 可加载 | 4/4 |
| crc-32 | 可加载 | 4/4 |
| blueimp-md5 | 可加载 | 4/4 |

合计：**173/173 用例通过，0 失败**。

`astring` 与 `acorn-walk` 直接消费 `acorn` 产出的 ESTree：运行器支持 `dependencies`，先在同一引擎中加载 `acorn`，再加载目标库并执行用例（`acorn-walk` 挂在 `acorn.walk`）。

> 依赖浏览器全局（如 `window`/`self`/`document`）的构建（例如 sprintf-js、chance）不纳入冒烟；seedrandom 在全局模式覆盖 `Math.random`，用例验证同种子确定性与不同种子差异。ajv 使用 `{ logger: false }` 适配裸 Engine（无 console），其 schema 编译路径覆盖动态 Function 代码生成。

Node 差分基线：**452 个用例**（String 方法对自定义 Symbol 协议的委托矩阵、astral 字面量与反向引用在 i+u 下的折叠矩阵、字符类单点/范围在 i+u/i+v 下的 astral/Kelvin 折叠、\p{...} 在 i+u 下的大小写与 astral 折叠矩阵、正则组名 Unicode 17 标识符与 astral/ZWNJ 校验、/\B 在非 ASCII 与 u 模式下的词边界矩阵、ToPrimitive 提示顺序、抽象相等与关系比较转换矩阵、字符类字面量/范围边界与 ] } { 等处理、字符类内 Annex B 转义矩阵、非 v 模式字符类 && 字面量回归、Annex B 控制/IdentityEscape 与 Java 专属转义矩阵、多行 ^/$ 在 CRLF/NEL/U+2028 上的锚点位置、RegExp lastIndex 状态机与 `$` 锚点模糊、数组变更与 String 非正则方法的随机序列/参数模糊、Error/AggregateError 的 cause/toString/子类矩阵、WeakMap/WeakSet 与 Array.fromAsync 异步关闭/拒绝矩阵、Set 集合方法（union/intersection/difference/isSubsetOf 等）矩阵、解析早期错误组合矩阵（重复词法声明/try-catch/类私有名/super/new.target/strict/正则/模板）、URI 编解码随机字符串模糊（保留字符/代理项/非法 UTF-8）、Date.parse ISO 字段组合与 Date.UTC 随机参数模糊、长随机十进制解析与舍入、Symbol 语义矩阵、String 正则方法组合模糊、非简单参数与函数体词法声明早期错误矩阵、JSON.parse 随机输入与近合法变异模糊（结构/数字/转义负例）、Number/parseFloat/parseInt 随机字符串模糊（含 JVM 类型后缀回归）、JSON 随机值树与 UTF-16 转义模糊、BMP 全码点大小写映射审计（分四块 0x4000）与 Final_Sigma/SpecialCasing 特殊映射、Date 随机时间 ISO 往返/UTC 构造解析/setter/24:00 与长小数秒边界、Number 三种格式化 100 组确定性随机模糊、eval/with 作用域与间接 eval、instanceof/@@hasInstance/in 语义、concat 的 @@isConcatSpreadable 展开、新式数组方法与 Iterator helpers 矩阵（toSorted/toReversed/toSpliced/with/findLast/at、惰性取值与 take 关闭）、Object/Map.groupBy 与 Object.hasOwn、String.at/replaceAll/matchAll/String.raw、数组 species 与子类构造、迭代器关闭（默认值异常/break/throw/标签 continue/rest）、对象展开（getter 顺序/符号键/非枚举）、属性描述符默认值与重定义、类字段/私有字段/静态块/extends null/new.target、Map/Set 构造迭代器关闭、Array.fromAsync 异步用例、String.normalize 与 Array.prototype.sort 边界矩阵、确定性随机进制模糊（LCG 生成 mantissa × 2^exp，2/8/16/32 进制最短往返）、Number 方法参数转换顺序/范围校验/接收者矩阵、RegExp d 标志 indices 矩阵（捕获区间/命名组/可选组/全局重算）、JSON 严格语法矩阵（数字/字符串/尾逗号负例、空白、重复键、__proto__、reviver 顺序）、属性键序边界（数组索引上限/前导零/JSON 序列化顺序）、Date UTC setter 的 NaN/溢出/可选参数矩阵、String.replace 替换模板矩阵（$&/$1/$<name>/空匹配/函数替换）、异步微任务调度矩阵（24 例：Promise 链、async/await 交错、thenable 采纳、拒绝/catch、try/withResolvers/any、组合器、finally、嵌套 async 与多任务交错）、parseInt/parseFloat/Number() 的空白/进制/Infinity/符号零矩阵、String.fromCharCode/fromCodePoint 的 ToUint16/ToIntegerOrInfinity 与 early error、Date ISO 扩展年份与接收者校验、URI 编解码边界、Math 精确语义矩阵（符号零、ToInt32/ToUint32、特殊 pow/hypot）、Array 方法在稀疏数组/原型链/array-like 上的语义矩阵、求值顺序矩阵（getter/setter 日志观察赋值/调用/模板/解构/ToPrimitive 顺序）、String 原型方法的索引/填充/查找/归一化边界矩阵、JSON 序列化/解析矩阵（toJSON、replacer、space、包装对象与 well-formed 输出）、Date 的 UTC/TimeClip/ISO 解析与溢出矩阵、非十进制 `toString` 的最短往返矩阵（0.1 的 52 位二进制展开、非 2 的幂进制、1e21/2^53 等大整数与次正规数）与 2 的幂进制边界矩阵（V8 仅在 2 的幂进制下输出可精确往返；其余进制按规范自校验，差异见 `docs/COMPATIBILITY.md`）、String 与 RegExp 的替换/分割/matchAll/lastIndex 交互矩阵、正则匹配矩阵（u/s/m 标志、astral 码点、命名组、前后向断言、转义与回溯）、String 大小写转换的特殊映射与词尾 Sigma 上下文、Number 三种格式化方法的取值/位数矩阵（20 个代表值 × 0..100 位小数/1..100 位有效数字）、正则忽略大小写的 Canonicalize 边界（Kelvin/长 s/İ/ß 在非 u 与 u 模式、字符类、范围、`\w`/`\b`）、Number 三种格式化方法的宽范围取值（常规值、次正规数、极值、100 位小数与 0..21 位有效数字）、数字格式化/转换、字符串 Unicode、数组空洞与写方法、对象描述符/枚举、RegExp 捕获/lastIndex/source 转义、v 标志集合运算与属性字符串、Unicode 代理项码元语义、Array 泛型方法的原型链/稀疏/数组型对象语义、Array.from/of 的构造器 length 传递与 CreateDataPropertyOrThrow、Object.entries/values 的快照键与可枚举复查、Math.max/min 的全量 ToNumber、Number.parseInt/parseFloat 与全局同一函数对象、%ThrowTypeError% 冻结与 unmapped arguments.callee、Date 的 ISO 缺省字段/零年映射/setter length 与 toString 本地格式、Error.prototype.toString 的 name/message 缺省规则、isPrototypeOf 接收者检查、slice 显式 undefined end、toExponential 非有限值顺序、Function.prototype.toString 保留函数/方法/类构造器/动态函数源码文本（无源码时 NativeFunction 形式）、bind length 仅取自有 length 并保留 Infinity、%ThrowTypeError% 与严格 arguments.callee 共享、String 原型方法的 RequireObjectCoercible/pad 默认填充/localeCompare 规范等价/replace 与 split 求值顺序、非 u 正则 Annex B IdentityEscape（\x/\u 缺位）、Date UTC 双精度/TimeClip/无效日期设置器、JSON reviver/replacer、Map/Set、函数 bind/arguments、参数初始化阶段 eval var 的函数体/参数闭包可见性、函数体 var 与形参同名初始化、sort 的 precise 属性访问/array-like/NaN 比较、URI 代理项/保留字符转义大小写/非法 UTF-8、parseInt 的 ToInt32 radix 与 parseFloat 的 ASCII 数字、mapped arguments 的 defineProperty 映射保持/解除、Map/Set 的 forEach 与迭代器实时新增/删除/重加、标签模板站点缓存与冻结、单语句上下文的 let ASI、对象字面量 async 方法的换行/简写早期错误、reduce 初始元素的单次 Get、Array.toString 回退、lastIndexOf 显式 undefined、`__proto__` 名称推断、Set.keys 身份与 AsyncFunction 继承、delete/空基对象成员访问的 ToObject 顺序、旧式八进制字面量与 `\8`/`\9`/`\400` 转义、lookbehind 量化早期错误、for async-of 箭头消歧、sloppy eval var 绑定可删除性、switch async 函数作用域与 catch 解构默认值环境、keyed destructuring 求值顺序与括号目标名称推断、super 赋值/自增的 RHS 与 null base 顺序、Annex B if 分支函数 var 绑定、块级函数 skip-param、CallExpression 赋值目标运行时错误、for-in 旧式初始化器、Unicode 标识符、尾调用结果、eval 完成值与相等转换等）**全部一致**。

> 真实库结果说明：纯 Kotlin 回退正则引擎按 JS-only 构造精确启用（空字符类、`\0`/`\c`、字符类内 `\b`/嵌套 `[`、前向/ lookahead 反向引用、量词组捕获重置），Test262 基线保持 100% 通过。lodash 的加载问题最终定位为宽松相等 `null == object` 被错误地触发 ToPrimitive，修复后 8/8 通过。

## 已知缺口（按失败聚类）

- Generator / async generator：`function*`、`yield`、`*method(){}`（明确排除项）；
- `RegExp.prototype.compile` 对子类实例的 TypeError 用例基于旧草案（现行 V8 允许），已排除；
- 模块相关早期错误尚未纳入基线；剩余 5 条 early 接受项为现行 Annex B 语义与该 ES2015 版本语料的差异。

## 推进顺序

1. Test262 ES5 子集与解析一致性基线已收敛；特性子集（含 esid 核心语义用例）已全部通过（37329/37329，覆盖 ES2015 核心语义与已实现的 ES2016+ 语法/内建）。已实现特性均已纳入选择范围，剩余跳过项均为明确排除的 Generator/BigInt/Proxy/TypedArray/Temporal/模块等；进一步扩展需先实现这些排除项或扩大语料范围；
2. 继续扩大真实库覆盖（已加入 semver/punycode/json5/papaparse/js-yaml/ramda/he/numeral/showdown/seedrandom/pluralize/highlight.js/tinycolor2/deepmerge/big-integer/rxjs/eventemitter3/mitt/immutable/redux/markdown-it/handlebars/ajv/esprima/acorn/astring/acorn-walk/crc-32/blueimp-md5，后续补充更多 UMD 构建与用例）；
3. 严格门禁已就绪：`-Pstrict` 在 parser / Test262 / 真实库 / 差分四个基线均通过（parser 的 Generator 项走显式允许列表）；接入 PR 触发需确认 CI 反馈成本后再启用。

## CI

独立 workflow `.github/workflows/conformance.yml`：

- `workflow_dispatch` 手动触发；
- 每周一 02:00 UTC 定时运行；
- 报告写入 Job Summary 并上传 artifact；
- 目前不阻塞日常 PR；严格门禁已就绪（四个基线均可通过 `-Pstrict`），扩展到 PR 触发需先确认数万级语料的反馈成本。
