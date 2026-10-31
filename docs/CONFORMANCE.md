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
生成时间：2026-10-30

| 类别 | 总数 | 通过 | 失败 | 允许 | 通过率 | 崩溃 |
|---|---:|---:|---:|---:|---:|---:|
| pass | 1907 | 1822 | 0 | 85 | 95.54% | 0 |
| pass-explicit | 1907 | 1822 | 0 | 85 | 95.54% | 0 |
| fail | 682 | 682 | 0 | 0 | 100% | 0 |

早期错误用例（early）：共 605，解析成功 5，被拒绝 600。  
其中 5 条解析成功项属于现行规范的 Annex B 语义（sloppy 块级函数同名重复、简单 catch 参数与块内 var 同名），Node 同样接受；本语料为 ES2015 早期版本，期望拒绝。
模块用例跳过：262。

> 剩余 85 条 `pass` 失败全部是 Generator / generator method（项目明确排除），已列入 `vibe-js-conformance/src/main/resources/parser-pass-allowlist.txt`，`-Pstrict` 门禁不计为失败；`fail` 已全部拒绝。

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
- 标识符中的 `\uXXXX` / `\u{...}` 转义、astral 标识符（常用范围）、转义保留字拒绝；
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
- Object.prototype.valueOf/toLocaleString 接收者检查、instanceof 的 prototype 校验、bind 函数的 [[Construct]]。

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
# 按已支持特性筛选的 ES2015 子集（报告模式）
./gradlew :vibe-js-conformance:test262Es2015Conformance

# 定向调试
./gradlew :vibe-js-conformance:test262Es2015Conformance -Ptest262Filter=for-of
```

范围：`test/language` + `test/built-ins` 中非 `es5id`、metadata `features` 全部落在已支持集合内的脚本；跳过 module/generator 标志、async 负数用例、`$262`/缺失 include 与源码或既有 harness 依赖 BigInt/Proxy 的用例（如 `TemporalHelpers.propertyBagObserver`）；排除未实现的宿主型内建（ArrayBuffer/DataView/TypedArray/SharedArrayBuffer/Atomics）与未实现的 AsyncIterator 原型，以及内容依赖 Generator 的计算属性名用例；另外暂排除依赖 `v` 标志的 RegExp 语料、孤立代理项匹配用例与 2^53 级 Array 长度边界用例。BigInt 字面量仅保留语法识别，求值仍抛 TypeError。`flags: [async]` 用例通过 runner 注入 `$DONE` 并等待落定后判定。该基线当前为报告模式，`-Pstrict` 已可全绿，尚未接入 CI 门禁。

当前基线（与 ES5 相同的 test262 提交）：

| 指标 | 数值 |
|---|---:|
| 选中测试文件 | 6657 |
| 执行次数（含 strict/sloppy） | 12340 |
| 通过 | 12340（100%） |
| 失败 | 0 |
| 崩溃 | 0 |
| 跳过测试文件 | 296（unsupported flag=13 + BigInt/Proxy/Generator=279 + harness Proxy=3 + print=1） |

已收敛的语义簇包括：类与 super（未初始化 this、动态父构造器、内建子类化）、Promise capability/SpeciesConstructor/NewPromiseCapability（all/race/resolve/reject/then/catch/allSettled/any/withResolvers/try/finally，含 thenable 采纳作业）、AggregateError、%AsyncFunction% 构造器与原型链、async/await（含 thenable 采纳的微任务交错与参数 TDZ）、Array/Map/Set/RegExp/Promise 的 `@@species` 与 Array 方法的 ArraySpeciesCreate、`@@isConcatSpreadable`、ES2019 `flat`/`flatMap`、幂运算与逻辑赋值、`globalThis`、对象剩余/展开、数字分隔符字面量、Map/WeakMap `getOrInsert`/`getOrInsertComputed`、Error `cause`、ES2016+ 的 String/Array/Object 方法（含 replaceAll：@@replace 分派、GetSubstitution、空串与函数替换）（includes/at/trimStart/trimEnd/fromCodePoint/findLast/findLastIndex/isWellFormed/toWellFormed/hasOwn/fromEntries）与 change-array-by-copy（toReversed/toSorted/toSpliced/with）、RegExp 的 `@@match`/`@@replace`/`@@search`/`@@split`/`@@matchAll` 协议、`RegExp.escape`、String 方法分派与 RegExp String Iterator、可选链（含短路、this 保留与 delete）、`Symbol.unscopables` 与 with 绑定解析、Object/Map.groupBy 与字符串码点迭代、ES2025 Set 方法集（union/intersection/difference/symmetricDifference/isSubsetOf/isSupersetOf/isDisjointFrom）、Iterator helpers（`Iterator.from`、map/filter/take/drop/flatMap/reduce/toArray/forEach/some/every/find，含参数校验先于读取 next、IfAbruptCloseIterator 与 helper 重入检测）、内建迭代器独立原型（Array/String/Map/Set/RegExp String Iterator）与 `Iterator.prototype` 的 constructor/@@toStringTag 访问器、for-in/for-of 继承细节、解构与迭代器关闭、Map/Set 实时迭代器、`__proto__` 与方法 HomeObject、RegExp/Date 原型访问器。剩余跳过项为 module/generator 标志、async 负数用例与 BigInt/Proxy/Generator（明确排除项）。

## 真实库冒烟与 Node 差分

```bash
# 下载固定版本的 UMD 构建并执行 API 级冒烟
./gradlew :vibe-js-conformance:librarySmoke

# 与 Node 对比 JSON/Number/RegExp/String/Date 等行为
./gradlew :vibe-js-conformance:differentialConformance
```

真实库基线（lodash 4.17.21 / mustache 4.2.0 / dayjs 1.11.10 / qs 6.12.3 / validator 13.12.0 / underscore 1.13.6 / moment 2.30.1 / semver 4.3.6 / punycode 1.4.1 / json5 2.2.3）：

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

合计：**42/42 用例通过，0 失败**。

Node 差分基线：**176 个用例**（数字格式化/转换、字符串 Unicode、数组空洞与写方法、对象描述符/枚举、RegExp 捕获与 lastIndex、Date、JSON reviver/replacer、Map/Set、函数 bind/arguments、eval 完成值与相等转换等）**全部一致**。

> 真实库结果说明：纯 Kotlin 回退正则引擎按 JS-only 构造精确启用（空字符类、`\0`/`\c`、字符类内 `\b`/嵌套 `[`、前向/ lookahead 反向引用、量词组捕获重置），Test262 基线保持 100% 通过。lodash 的加载问题最终定位为宽松相等 `null == object` 被错误地触发 ToPrimitive，修复后 8/8 通过。

## 已知缺口（按失败聚类）

- Generator / async generator：`function*`、`yield`、`*method(){}`（明确排除项）；
- class fields、private 字段、static block 等后续版本语法；
- 回退正则引擎仍不支持 lookbehind，`u`/`s` 标志的部分匹配语义与命名组细节有限；
- astral 标识符范围采用近似表（common 标准库缺少完整 Unicode 属性查询）；
- 模块相关早期错误尚未纳入基线；剩余 5 条 early 接受项为现行 Annex B 语义与该 ES2015 版本语料的差异。

## 推进顺序

1. Test262 ES5 子集与解析一致性基线已收敛；特性子集执行部分已全部通过（12340/12340，覆盖 ES2015 核心语义与已实现的 ES2016+ 语法/内建），后续可继续扩展特性选择范围（如更多 ES2022+ 方法）；
2. 继续扩大真实库覆盖（已加入 semver/punycode/json5，后续补充更多 UMD 构建与用例）；
3. 严格门禁已就绪：`-Pstrict` 在 parser / Test262 / 真实库 / 差分四个基线均通过（parser 的 Generator 项走显式允许列表）；接入 PR 触发需确认 CI 反馈成本后再启用。

## CI

独立 workflow `.github/workflows/conformance.yml`：

- `workflow_dispatch` 手动触发；
- 每周一 02:00 UTC 定时运行；
- 报告写入 Job Summary 并上传 artifact；
- 目前不阻塞日常 PR；严格门禁已就绪（四个基线均可通过 `-Pstrict`），扩展到 PR 触发需先确认数万级语料的反馈成本。
