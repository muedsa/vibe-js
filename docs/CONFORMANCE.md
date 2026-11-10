# 解析一致性与 Test262 基线

> 更新：2026-11-10。代码基线：见最近提交。
>
> 本文件记录 `test262-parser-tests`（脚本 + 模块）、Test262 ES5/ES2015 子集、真实库 UMD 冒烟与 Node 差分的当前口径。
> 最近一次全量运行的环境为 JDK 21、Kotlin 2.4.21、Gradle 9.8.2、Node v24.12.0、Windows 10/amd64；
> 本次已在当前基线复跑 parser、ES5、ES2015、差分与真实库。

## 1. 范围与判定

- **parser**：只验证 `Lexer → Parser`，不执行脚本。普通 `.js` 走 `Parser.parseProgram`，`.module.js` 走 `Parser.parseModule`；两类语料分别统计 `pass` / `pass-explicit` / `fail` / `early`，模块类别带 `-module` 后缀。
- **Test262**：`test/language` + `test/built-ins`（ES2015 另含 `test/annexB`）中的脚本，按 `es5id` 或 `features` / `esid` 筛选；strict/sloppy 变体分别执行。
- **筛选排除 vs 跳过**：前者是“没有纳入本次子集”（ES5 用例、特性未全部支持、无标注、路径排除），后者是“已选中但当前能力不支持”（module flag、`$262`、`print` 等）。两者不能混用。
- **门禁**：`-Pstrict` 下 parser 的脚本与模块、Test262、真实库、差分任一基线失败都会使任务失败；不允许用删除用例、扩大无理由 allowlist 或降低默认深度换绿。

## 2. 当前基线总览

| 检查 | 结果 |
|---|---|
| parser 脚本 | **1,907 / 1,907**；fail 674 / 682（允许 8）；early 5 / 605（允许 5） |
| parser 模块 | pass-module **76 / 76**、pass-explicit-module **76 / 76**、fail-module **47 / 47**、early-module **63 / 63**（拒绝）；允许 0 |
| Test262 ES5 | **15,248 / 15,248**，失败 0，崩溃 0，跳过 0 |
| Test262 ES2015 | **61,786 / 61,786**，失败 0，崩溃 0，跳过 **324**（module flag 322 + `$262`/`print` 2） |
| Node 差分 | **936 / 936** |
| 真实库 UMD 冒烟 | **375 / 375**（102 个库） |
| conformance 单元测试 | **39 / 39**（ParserConformanceTest 6 + RunSupportTest 10 + Test262SelectionTest 19 + RunMetadataTest 4） |
| host 单元测试 | **38 / 38**（JVM/JS/Wasm/Windows Native；含 HostEcosystemTest 13） |

## 3. Parser 基线

### 3.1 使用方式

```bash
# 首次运行自动下载固定 commit 的 test262-parser-tests
./gradlew :vibe-js-conformance:parserConformance

# 严格门禁：脚本与模块语料都必须通过
./gradlew :vibe-js-conformance:parserConformance -Pstrict
```

报告输出：

- `vibe-js-conformance/build/reports/conformance/parser-summary.md`
- `vibe-js-conformance/build/reports/conformance/parser-summary.json`
- `failures-pass.txt` / `failures-fail.txt` / `failures-early-*.txt` 及模块对应文件

`parserConformance` 默认只报告（退出码 0）；检测到崩溃时退出码 2；加 `-Pstrict` 后存在未允许的语法不一致时退出码 1。

### 3.2 脚本目标

语料版本：`tc39/test262-parser-tests@0e808c74fbec780646434cad17bb22dc52461003`。脚本合计 5,101 个用例（1,907 + 1,907 + 682 + 605）。

| 类别 | 总数 | 通过 | 失败 | 允许 | 通过率 | 崩溃 |
|---|---:|---:|---:|---:|---:|---:|
| pass | 1,907 | 1,907 | 0 | 0 | 100.00% | 0 |
| pass-explicit | 1,907 | 1,907 | 0 | 0 | 100.00% | 0 |
| fail | 682 | 674 | 0 | 8 | 98.82% | 0 |

`pass` / `pass-explicit` 的允许列表为空：Generator、生成器方法、`async *method()`、`yield /re/` 等此前的语法缺口均已解析通过。

`fail` 的 8 条允许项为：

- 2 条过期公有字段负例（`class { a }` / `class { a = 0 }`，现行规范合法）；
- 4 条 `\8` / `\9` 负例（现行规范在非严格模式允许，Test262 亦要求接受）；
- 1 条 sloppy `CallExpression` 赋值目标（Annex B web-compat，运行时对非引用赋值抛 `ReferenceError`）；
- 1 条旧式 `for-in` 初始化器（Annex B B.3.5 允许）。

### 3.3 模块目标

模块语料共 262 个 `.module.js`：76 个 pass、76 个 pass-explicit、47 个 fail、63 个 early。模块代码按模块目标解析，恒为严格模式；Annex B HTML 注释与 Annex B 语法在模块中禁用。

| 类别 | 总数 | 通过 | 失败 | 允许 | 通过率 | 崩溃 |
|---|---:|---:|---:|---:|---:|---:|
| pass-module | 76 | 76 | 0 | 0 | 100.00% | 0 |
| pass-explicit-module | 76 | 76 | 0 | 0 | 100.00% | 0 |
| fail-module | 47 | 47 | 0 | 0 | 100.00% | 0 |
| early-module | 63 | 拒绝 63（解析成功 0） | 0 | 0 | 100.00% | 0 |

模块早期错误（early-module）共 63 条，解析成功 0、被拒绝 63、意外接受 0。模块允许列表 `parser-module-allowlist.txt` 当前为空；新增条目必须逐条写“相对语料根目录的 POSIX 路径 + 原因”，且只允许 `.module.js`，禁止目录/后缀/通配符。

> 模块语法已纳入 parser 门禁；阶段 0–8 已实现实验性 `Engine.runModule`（Provider/链接/环境/namespace/`import.meta`/同步求值/错误定位），但 Test262 module harness、宿主加载器与 TLA 执行仍未实现，模块能力仍需 opt-in。

### 3.4 早期错误（脚本）

早期错误用例共 605 条：解析成功 5、被拒绝 600、崩溃 0、意外接受 0。5 条允许项全部列入 `parser-early-allowlist.txt`，均为现行 Annex B / 宽松语义允许项：

- catch 参数与 catch 块内 `for (var ...)` 同名；
- 块级重复函数声明；
- 函数作用域与块级函数声明混用（含声明后调用、重复声明）。

Generator 形参 `yield`、async 形参 `await` 等早期错误不属于允许项，必须被拒绝。

## 4. Test262 ES5 语义基线

```bash
# 首次运行自动下载固定版本的 tc39/test262
./gradlew :vibe-js-conformance:test262Conformance

# 限制用例数 / 并行度（快速验证）
./gradlew :vibe-js-conformance:test262Conformance -Ptest262Limit=1000 -Ptest262Jobs=4
```

范围：`test/language` + `test/built-ins` 中带 `es5id` 的脚本；自动加载 `sta.js` / `assert.js` 与 metadata `includes`；支持 `negative` 的 parse/runtime、`onlyStrict` / `noStrict` / `raw` 变体；`flags: [async]` 通过注入 `$DONE` 驱动；跳过 module 标志、当前不支持的 features、其余 `$262` 宿主钩子、缺失 include。

当前基线（test262@91c70759363c46557b694792bf34a2551a7af5ba）：

| 指标 | 数值 |
|---|---:|
| 选中测试文件 | 8,088 |
| 执行次数（含 strict/sloppy） | 15,248 |
| 通过 | 15,248（100%） |
| 失败 | 0 |
| 崩溃 | 0 |
| 跳过测试文件 | 0 |

> 跳过项为 0：Reflect 已完整实现，原先 `features: [Reflect.construct]` 的跳过项已回归基线；直接/间接 eval 作用域、数组异质 length 与稀疏索引、参数映射、对象描述符不变量、严格模式早期错误与 RegExp JS-only 语法均已收敛。

## 5. Test262 ES2015 特性子集

```bash
# 按已支持特性筛选的 ES2015 子集（CI 门禁使用 -Pstrict）
./gradlew :vibe-js-conformance:test262Es2015Conformance

# 定向调试
./gradlew :vibe-js-conformance:test262Es2015Conformance -Ptest262Filter=for-of
```

范围：`test/language` + `test/built-ins` + `test/annexB` 中非 `es5id` 且满足以下条件之一的脚本：`features` 全部落在已支持集合内；或无 `features` 标注但带 `es6id` / `esid`。默认纳入 esid 用例。

当前选中 **32,293** 个文件，执行 **61,786** 次（strict + sloppy）。

| 指标 | 数值 |
|---|---:|
| 选中测试文件 | 32,293 |
| 执行次数（含 strict/sloppy） | 61,786 |
| 通过 | 61,786（100%） |
| 失败 | 0 |
| 崩溃 | 0 |
| 跳过测试文件 | 324 |

### 5.1 跳过原因

| 原因 | 数量 |
|---|---:|
| unsupported flag（322 个全部是 `flags: [module]`） | 322 |
| unsupported harness: `$262` | 1 |
| unsupported harness: print | 1 |

`module` 仍跳过是因为 Test262 module harness 未实现（解析与同步模块执行已支持）；旧语料中的 `generator` 历史 flag 仅作兼容判定，现行语料已改用 `features: [generators]`，不影响已纳入基线的生成器用例。

### 5.2 筛选统计（未纳入本次口径）

| 原因 | 数量 |
|---|---:|
| ES5 用例（ES2015 子集排除） | 8,115 |
| 特性未全部支持 | 8,065 |
| 无特性标注且缺少 es6id/esid | 281 |
| 路径排除 | 12 |

另有 **2 条**仅由源码文本匹配排除（`test262-text-exclusions.txt`）：`test/built-ins/Proxy/revocable/tco-fn-realm.js`（其余 `$262` 钩子）与 `test/built-ins/RegExp/prototype/Symbol.replace/coerce-global.js`（`print`）。两者都是宿主钩子，不是已实现特性被误排。

路径排除的 12 个文件主要是：`SharedArrayBuffer` / `Atomics`、旧草案的 `RegExp.prototype.compile` 子类用例，以及 `Object.seal` 的 11 个 TypedArray 整数索引用例。

### 5.3 已启用并纳入基线的特性

`Reflect` / `Reflect.construct`、`Proxy` / `proxy-missing-checks`、`BigInt`、`TypedArray` / `ArrayBuffer` / `DataView`（含 BigInt 视图与 detach 语义）、`generators`、`async-iteration`，以及此前已支持的 ES2015 / ES2016+ 特性集合（class、解构、模板、默认/rest、for-of、Symbol、Promise、async/await、Iterator helpers、using、Set 方法等）。

### 5.4 harness 替换与注入

runner 当前只做以下注入/替换：

- `$262.evalScript`：同一 realm 内执行新 Script；
- `$262.detachArrayBuffer`：把 ArrayBuffer 置为分离状态；
- `$DONE`：驱动 `flags: [async]` 用例；
- `regExpUtils.buildString`：改由宿主构造大码点字符串，避免解释器逐码点循环；
- strict 变体前缀：在源码前插入 `"use strict"`。

运行器只注入上述 5 项；历史兼容垫片与生成器语料替换已删除，Generator 已原生执行。报告中的 harness 清单与上述一致。

### 5.5 已收敛的语义簇

类与 super、class 静态初始化块、公有/私有字段与方法、`using` / `await using`、Promise capability/species/组合器、async/await 与 thenable 时序、Array/Map/Set/RegExp/Promise 的 `@@species`、对象剩余/展开、数字分隔符、Error `cause`、ES2016+ 的 String/Array/Object 方法、change-array-by-copy、RegExp 的 `@@match` / `@@replace` / `@@search` / `@@split` / `@@matchAll`、`RegExp.escape`、可选链、`Symbol.unscopables`、Iterator helpers 与内建迭代器原型、`Array.fromAsync`、ES2025 Set 方法集、Proxy/Reflect、Generator/async generator、TypedArray/AB/DV/BigInt 等。

## 6. 真实库冒烟与 Node 差分

```bash
# 下载固定版本的 UMD 构建并执行 API 级冒烟
./gradlew :vibe-js-conformance:librarySmoke

# 与 Node 对比同步/异步行为
./gradlew :vibe-js-conformance:differentialConformance
```

### 6.1 真实库

固定版本清单见 [Libraries.kt](../vibe-js-conformance/src/main/kotlin/com/muedsa/js/conformance/Libraries.kt)。当前 **90 个库、375/375 用例通过，0 失败**。最近一次新增 fflate 0.8.2、js-base64 3.7.7、long 5.2.3、loglevel 1.9.1，共 12 条断言。

| 库 | 状态 | 用例 |
|---|---|---:|
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
| ua-parser | 可加载 | 4/4 |
| fraction | 可加载 | 4/4 |
| jsep | 可加载 | 4/4 |
| lunr | 可加载 | 4/4 |
| fuse | 可加载 | 4/4 |
| acorn-loose | 可加载 | 4/4 |
| complex | 可加载 | 4/4 |
| decimal-light | 可加载 | 4/4 |
| ohm | 可加载 | 4/4 |
| jsonata | 可加载 | 7/7 |
| qrcode | 可加载 | 4/4 |
| linkify | 可加载 | 4/4 |
| simple-statistics | 可加载 | 4/4 |
| compare-versions | 可加载 | 4/4 |
| moo | 可加载 | 4/4 |
| fecha | 可加载 | 3/3 |
| deep-diff | 可加载 | 4/4 |
| object-path | 可加载 | 4/4 |
| validate | 可加载 | 4/4 |
| date-and-time | 可加载 | 4/4 |
| mathjs | 可加载 | 6/6 |
| chevrotain | 可加载 | 4/4 |
| vue | 可加载 | 4/4 |
| preact | 可加载 | 3/3 |
| katex | 可加载 | 4/4 |
| moment-timezone | 可加载 | 4/4 |
| d3-format | 可加载 | 3/3 |
| d3-time | 可加载 | 3/3 |
| d3-time-format | 可加载 | 3/3 |
| d3-color | 可加载 | 3/3 |
| d3-ease | 可加载 | 3/3 |
| d3-dispatch | 可加载 | 3/3 |
| d3-quadtree | 可加载 | 3/3 |
| d3-path | 可加载 | 2/2 |
| d3-shape | 可加载 | 2/2 |
| d3-random | 可加载 | 2/2 |
| d3-polygon | 可加载 | 2/2 |
| mobx | 可加载 | 4/4 |
| immer | 可加载 | 4/4 |
| regenerator-runtime | 可加载 | 3/3 |
| pako | 可加载 | 3/3 |
| prop-types | 可加载 | 3/3 |
| react-is | 可加载 | 3/3 |
| fflate | 可加载 | 3/3 |
| js-base64 | 可加载 | 3/3 |
| long | 可加载 | 3/3 |
| loglevel | 可加载 | 3/3 |
| base64-js | 可加载 | 3/3 |
| jmespath | 可加载 | 3/3 |
| jssha | 可加载 | 2/2 |
| d3-array | 可加载 | 3/3 |
| d3-hierarchy | 可加载 | 3/3 |
| earcut | 可加载 | 2/2 |
| d3-dsv | 可加载 | 3/3 |
| cbor-js | 可加载 | 3/3 |
| humanize-duration | 可加载 | 3/3 |
| msgpack-lite | 可加载 | 3/3 |
| d3-delaunay | 可加载 | 3/3 |
| topojson-client | 可加载 | 3/3 |

真实库冒烟支持异步用例：脚本把布尔结果写入 `globalThis.__libResult`，`Engine.run` 结束冲刷微任务后由运行器读取。`astring` 与 `acorn-walk` 通过 `dependencies` 先加载 `acorn`，再加载目标库。本轮新增 `d3-array`、`d3-hierarchy`、`earcut`、`d3-dsv`、`cbor-js`、`humanize-duration`、`msgpack-lite`、`d3-delaunay` 与 `topojson-client`，覆盖 d3 命名空间/层级/CSV/几何、三角剖分、CBOR/MessagePack 二进制、TopoJSON 与时长格式化。`luxon` 依赖 `Intl`，当前核心不提供，未纳入基线。

> 依赖浏览器全局（如 `window` / `self` / `document`）的构建不纳入冒烟；seedrandom 在全局模式覆盖 `Math.random`，用例验证同种子确定性与不同种子差异；ajv 使用 `{ logger: false }` 适配裸 Engine（无 console）。

### 6.2 Node 差分

差分基线：**936 / 936**，不一致 0。Node 侧使用裸 vm 上下文（同步用例共享上下文并在求值后冲刷微任务，异步用例各自独立），与 Vibe 侧全局/独立 Engine 对齐。

覆盖范围包括：属性枚举/原型/符号键、解构/默认值/rest/计算键、类私有元素/静态块、函数 call/apply/bind/new、迭代器关闭、Unicode 规范化、异步微任务矩阵、UTF-16 字符串、Map/Set 实时迭代、数组描述符/稀疏、JSON 随机值树、正则（含 v 模式、属性转义、大小写折叠）、Date setter/解析、Number 格式化、URI 编解码、eval/with、Proxy/Reflect、Generator/async generator、TypedArray/ArrayBuffer/DataView/BigInt 等。

最近一次扩展分多批新增 **384 条同步/异步用例**（`toSpliced` 复制/无参/负起点/删到末尾、`toReversed` 空位、`with` NaN/小数/-0/越界、`Object.hasOwn` 符号键/Proxy 陷阱/陷阱不变量/撤销 Proxy/非法描述符/不可扩展目标、`Object/Map.groupBy`、`replaceAll` 的 `$&`/`$$`、`toSorted` 比较器、TypedArray `toReversed`/`with`、`using` 释放顺序、`DisposableStack`、`RegExp.escape`、`Array.from` mapper 关闭迭代器、`Object.fromEntries` Symbol 键/重复键/短条目/非法条目/键与值 getter 抛错并关闭迭代器/`__proto__` 数据属性/整数键排序与符号键顺序/-0 键规范化/对象键 ToPropertyKey 一次/重复符号键覆盖/nullish 键/属性描述符/键 ToString 抛错关闭迭代器/键先于值读取/空输入/仅符号键/迭代器 getter 与 next getter 抛错/迭代器非对象/next 非函数/next 结果非对象/done getter 抛错/done=true 不读 value/缺失 done/空条目/null 与字符串条目关闭迭代器、`await using`、`AsyncDisposableStack`、for/while/do-while 每迭代绑定、finally 完成语义、`SuppressedError` 嵌套、撤销 Proxy 的内建操作、bound function 的 `toString`、thenable getter/executor 异常、非可调用 then 与 Promise 子类/species、`Error.isError`、`JSON.rawJSON`、async generator 的 return/throw/for-await close/pending await、class 的 super/字段/私有品牌、Proxy 内建消费路径/数组方法（find/some/reduce/filter/indexOf）/迭代器关闭、Array.fromAsync 的 GetMethod/关闭/mapper Promise、非可调用 mapper、array-like/同步/异步迭代器的 mapper 索引、负数与缺失 length 时不调用 mapper、length 只读取一次、迭代器优先于 array-like、正常完成不调用 return（同步/异步迭代器）、异步迭代器 done=true 不调用 return、异步 next 返回同步结果/原始值、异步 iterator getter 抛错/迭代器非对象/next getter 抛错、异步迭代器方法非函数与 next 非函数、异步 next 拒绝、异步 done/value getter 抛错、异步值不再 await/同步值会 await、自定义构造器返回对象/抛错/不可扩展结果/length setter 抛错/仅调用一次/已有不可配置索引冲突/元素与 length 失败关闭迭代器、生成器 return 忽略、迭代器 getter 抛错、非可调用/非对象迭代器、非可调用/非对象 next、空 next 结果、done 与 value getter 抛错、字符串与稀疏数组输入、array-like 小数/Symbol/BigInt/负数/缺失/超上限 length、length 与索引 getter 抛错、非法长度、索引顺序、Promise.all/race/any/allSettled 的迭代与异常路径（含全部组合器的 then getter 抛错）、全部组合器的子类构造与 capability 抛错、Promise.any 全拒绝与空输入、allSettled 元素顺序等）；此前覆盖 **45 条同步 + 8 条异步 L 系列用例**，并已有 for-var-let 与 object rest 目标用例。完整用例见 `DifferentialCases.kt`、`AsyncDifferentialCases.kt`、`LSeriesDifferentialCases.kt`、`LSeriesAsyncDifferentialCases.kt`。

## 7. 运行器生命周期与失败隔离

三个运行器（Test262、真实库冒烟、Node 差分）统一使用 `RunSupport.kt` 的执行设施：

- **引擎生命周期**：每个用例（Test262 为每个 strict/sloppy 变体）独立创建引擎，并在 `finally` 中关闭；关闭在 `NonCancellable` 上下文里执行，另有独立清理时限（默认 5 秒）。
- **用例超时**：默认 60 秒。已知最慢用例是 `decodeURI` / `decodeURIComponent` 的四层嵌套循环（约 130 万次迭代），并行负载下实测 18–26 秒。超时是协作式的：解释器每 1024 个检查点观察取消；单次内建调用（正则回溯、超大字符串分配、解析）内部没有检查点。
- **失败分类**：`断言失败`、`解析失败`、`步数耗尽`、`用例超时`、`运行器内部异常`、`引擎清理失败`、`Node 超时`。分类写入 Markdown 报告、JSON 的 `failureKinds` 与 `test262-failures.txt` 第 4 列。
- **Node 子进程**：输出重定向到临时文件后再等待进程，避免管道死锁；超时后 `destroyForcibly` 并删除临时文件。
- **有界工作队列**：只为并行度数量的 worker 创建协程，按索引领取用例。

可通过 `-PcaseTimeoutMs`、`-PcleanupTimeoutMs`、`-PnodeTimeoutMs`、`-PmaxSteps` 覆盖默认值。运行器自身的分类/超时/关闭行为由 `RunSupportTest` 覆盖。

## 8. 报告可审计

三份报告（Test262 / 真实库 / 差分）的 Markdown 与 JSON 都包含运行元数据，JSON 中为 `metadata` 对象：

| 字段 | 说明 |
|---|---|
| `engineCommit` | 被测引擎提交：`--engine-commit` → `GITHUB_SHA` → `git rev-parse HEAD` → `unknown` |
| `corpusCommit` | 语料目录 `.corpus-commit` 标记的提交；真实库语料无标记时为空 |
| `kotlinVersion` / `javaVersion` / `gradleVersion` / `os` | 工具链与平台 |
| `runnerConfig` | suite/filter/limit/jobs/超时/步数等本次配置 |

Test262 报告额外区分两类“没有执行”的用例：

- **筛选统计（未纳入本次口径）**：`selectionExcluded`，按原因计数；
- **跳过原因**：`skipReasons`，已选中但当前能力不支持。

被跳过的用例逐条写入 `test262-skipped.txt`（路径 + 原因），文本筛选（`$262` / `print`）单独写入 `test262-text-exclusions.txt` 供 grep 复核。

parser 报告同时输出脚本与模块分组（`categories` / `moduleCategories` / `early` / `moduleEarly` / `moduleAllowlist`），模块允许列表为空时也会显式记录。报告还会列出本次生效的 harness 注入（`$262.evalScript`、`$262.detachArrayBuffer`、`$DONE`、`regExpUtils.buildString`、strict 前缀），不再列出已删除的历史 harness 改写。

## 9. 已知范围与缺口

- **模块执行（实验性）**：`Engine.runModule` 已支持 Provider、同步链接/求值、live binding、namespace 与 `import.meta`；TLA 在链接期拒绝，动态 `import()` 执行时抛 `SyntaxError`。Test262 module harness、宿主加载器与 TLA 执行仍未实现，322 个 `flags: [module]` 用例继续跳过；parser 的 262 个模块用例保留为现状回归门禁。
- **顶层 await**：只有语法解析和 `hasTopLevelAwait` 标记，没有异步模块求值；模块执行未排期。
- **模块扩展语法**：import attributes、`import.meta.resolve`、`export using` 未支持；`import()` 零参数与非 async 函数体中的 `await` 已在解析期拒绝。
- **宿主生态**：已实现 UTF-8 子集 TextEncoder/TextDecoder、structuredClone 与 URL/URLSearchParams 常用能力（`:vibe-js-host`）；不实现 IDNA、完整 WHATWG URL 状态机、DOM / fetch / localStorage 等 Web API。
- **弱引用**：WeakRef / FinalizationRegistry 未实现；WeakMap / WeakSet 仍是强引用存储（M2 无计划）。
- **范围外**：Intl、真实 DOM / Web API、SharedArrayBuffer / Atomics、resizable / transfer ArrayBuffer、Temporal、Float16Array、装饰器、ShadowRealm。
- **资源约束**：R1 已覆盖源码/嵌套/AST/对象/JSON/正则/排队任务/微任务；`JSON.stringify` 与 `JSON.parse` 共用 `maxJsonDepth`；`maxRegexSteps` 显式降到默认值以下时，常见嵌套量词正则路由到受该预算约束的回退引擎。Kotlin Regex 快路径不消耗该预算，是文档化边界；`EngineLimits` 不是进程级隔离。
- **性能与 API 契约**：B2（25–48 人日）与 PKG1.2–1.6（7–13 人日）无计划，见[后续路线](OPTIMIZATION_ROADMAP.md)。
- **parser early 5 条**：均为现行 Annex B / 宽松语义允许项，不是缺陷；已列入 `parser-early-allowlist.txt`。
- 当前 ES5 / ES2015 基线无失败、无崩溃；上述范围外项不是回归。

## 10. 推进顺序

1. **文档与门禁收口（已完成）**：同步 README/CONFORMANCE/路线图中的模块口径，保持 parser/Test262/真实库/差分 strict 全绿；模块阶段 0–8 标为 experimental，执行入口已存在但加载器/TLA/harness 仍无计划。
2. **覆盖扩展（P1，下一批）**：优先补运行时边界（Proxy/Reflect、Generator/async generator、TypedArray/AB detach、Iterator helpers、Set 方法等），其次才是有新增特性的 UMD 库；保持“无 DOM/浏览器全局”的加载条件。
3. **门禁维护（P0）**：每批覆盖或核心改动后重跑 parser、ES5、ES2015、差分与真实库 strict。
4. **性能观测（P2）**：按需做性能复测；B2 与 PKG1 无计划，不因复测恢复排期。

**模块阶段 0–8（实验性）已完成，模块后续能力（L4.4、L4.6a+、L4.6b、L4.7）仍未实现**：保留 `Parser.parseModule` 与现有 262 个模块语料回归；阶段 0–8 已补静态表、类型化声明、range、TLA 标记、语法收紧、公共 Provider/预算、内部图加载、ResolveExport/import 链接、声明实例化、namespace、`import.meta`、同步 `runModule` 与错误定位；不投入宿主加载器/module harness/TLA 执行。

## 11. CI

独立 workflow `.github/workflows/conformance.yml`：

- `pull_request`：只跑 **PR 快速子集**（parser 基线 + Node 差分，均不需要 Test262 语料，约 1–2 分钟）；
- `workflow_dispatch` 手动触发与每周一 02:00 UTC 定时：跑完整 parser / Test262 ES5 / ES2015 / 真实库 / 差分；
- 完整 Job 在 PR 事件下跳过，避免每个 PR 下载数万级语料；
- 报告写入 Job Summary 并上传 artifact。

> `Conformance` 工作流固定 Node 24，并以 `-Pstrict` 运行 parser / Test262 / 真实库 / 差分；严格模式下 parser/Test262 零用例、差分缺少 Node 或零用例、真实库存在缺失文件或零库均判定失败，避免静默跳过。
