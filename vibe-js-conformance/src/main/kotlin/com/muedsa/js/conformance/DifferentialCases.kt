package com.muedsa.js.conformance

/**
 * Node 差分的扩展用例集。
 *
 * 每个 expression 必须返回可 JSON 序列化的值；抛错时比较错误名称。
 */
internal val extraDifferentialCases: List<DifferentialCase> = listOf(
    // 数字与格式化
    DifferentialCase("num-tofixed-float", "(1.005).toFixed(2)"),
    DifferentialCase("num-large-tofixed", "(1e21).toFixed(2)"),
    DifferentialCase("num-negzero-div", "1 / -0"),
    DifferentialCase("num-negzero-tofixed", "(-0).toFixed(2)"),
    DifferentialCase("num-radix-frac", "(10.5).toString(2)"),
    DifferentialCase("num-radix-error", "(255).toString(37)"),
    DifferentialCase("num-number-bin", "Number('0b101')"),
    DifferentialCase("num-number-legacy-octal-string", "Number('010')"),
    DifferentialCase("num-parseint-space", "parseInt('\\t\\n0x10')"),
    DifferentialCase("num-parsefloat-exp", "parseFloat('1.2e3abc')"),
    DifferentialCase("num-toprecision", "(1.005).toPrecision(2)"),
    DifferentialCase("num-toexponential", "(123.456).toExponential(2)"),
    DifferentialCase("num-is-finite", "Number.isFinite('1')"),
    DifferentialCase("num-min-safe", "Number.MIN_SAFE_INTEGER"),
    DifferentialCase("num-exp-format", "(1e-7).toString()"),
    DifferentialCase("num-epsilon", "Number.EPSILON"),
    DifferentialCase("num-negative-zero-string", "(-0).toString()"),
    DifferentialCase("num-tofixed-zero", "(0).toFixed(2)"),
    DifferentialCase("num-toprecision-infinity", "(Infinity).toPrecision(1000) + '|' + (-Infinity).toPrecision(1000) + '|' + NaN.toPrecision(2)"),
    DifferentialCase("num-toprecision-negzero", "(-0).toPrecision(2) + '|' + (-0).toPrecision(1)"),
    DifferentialCase("num-shortest-subnormal", "(5e-324).toString() + '|' + (-5e-324).toString() + '|' + JSON.stringify(5e-324)"),
    // Number 方法的接收者校验、参数转换顺序与范围检查
    DifferentialCase(
        "num-method-args-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { var log = []; var r = (1.005).toFixed({ valueOf: function () { " +
            "log.push('v'); return 2; } }); return r + '|' + log.join(','); }); " +
            "push(function () { return Number.prototype.toFixed.call('1.5', 2); }); " +
            "push(function () { var log = []; try { Number.prototype.toFixed.call({}, { " +
            "valueOf: function () { log.push('v'); return 2; } }); return 'none'; } " +
            "catch (e) { return e.name + '|' + log.join(','); } }); " +
            "push(function () { var r = []; " +
            "r.push((1.5).toFixed(undefined)); " +
            "try { (1.5).toFixed(101); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "try { (1.5).toFixed(-1); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "try { (1.5).toFixed(Infinity); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "return r.join(','); }); " +
            "push(function () { var r = []; " +
            "r.push((1.5).toExponential(undefined)); " +
            "try { (1.5).toExponential(101); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "try { (1.5).toExponential(-1); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "return r.join(','); }); " +
            "push(function () { var r = []; " +
            "r.push((1.5).toPrecision(undefined)); " +
            "try { (1.5).toPrecision(0); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "try { (1.5).toPrecision(101); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "return r.join(','); }); " +
            "push(function () { var r = []; " +
            "r.push((255).toString(2.9)); " +
            "try { (255).toString(37); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "try { (255).toString(NaN); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "try { Number.prototype.toString.call('5'); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "return r.join(','); }); " +
            "push(function () { return [Number.prototype.toFixed.length, " +
            "Number.prototype.toExponential.length, Number.prototype.toPrecision.length, " +
            "Number.prototype.toString.length].join(','); }); " +
            "push(function () { return (1.25).toFixed(1, 'extra') + '|' + (-1.5).toFixed(0); }); " +
            "push(function () { return (0).toExponential(undefined) + '|' + " +
            "(0).toPrecision(undefined) + '|' + String(Number.prototype.valueOf.call(new Number(3))); " +
            "}); " +
            "push(function () { try { Number.prototype.valueOf.call('3'); return 'ok'; } " +
            "catch (e) { return e.name; } }); " +
            "return out.join('|'); })()"
    ),

    // 字符串与 Unicode
    DifferentialCase("str-sharp-s-upper", "'ß'.toUpperCase()"),
    DifferentialCase("str-dotted-i-lower-length", "'İ'.toLowerCase().length"),
    // Unicode 完整大小写转换与词尾 Sigma 的上下文规则
    DifferentialCase(
        "str-case-matrix",
        "(function () { var values = ['ß', 'ẞ', 'İ', 'ı', 'ſ', 'K', 'Σ', 'σ', 'ς', 'AΣB', 'AΣ', 'ΣA', " +
            "'ΑΣ', 'ΑΣΑ', 'AΣ\\u0301', 'AΣ.', 'AΣ1', 'ǅ', 'Ǆ', 'ǆ', 'ﬀ', 'ﬄ', 'ᾀ', 'ᾈ', " +
            "'𐐀', '𐐨', 'Ꮳ', 'Ꮃ', 'ŉ', 'ǰ', 'ΐ', 'ΰ', 'Ω', 'Å']; " +
            "var out = []; for (var i = 0; i < values.length; i++) { " +
            "out.push(values[i] + '>' + values[i].toUpperCase() + '>' + values[i].toLowerCase()); } " +
            "return out.join('|'); })()"
    ),
    DifferentialCase("str-at-negative", "'abc'.at(-1)"),
    DifferentialCase("str-at-out", "'abc'.at(5)"),
    DifferentialCase("str-slice-negative", "'abc'.slice(1, -1)"),
    DifferentialCase("str-substring-swap", "'abc'.substring(3, 1)"),
    DifferentialCase("str-substr-negative", "'abc'.substr(-2, 1)"),
    DifferentialCase("str-padstart-default", "'x'.padStart(4)"),
    DifferentialCase("str-match-capture", "'a1b2'.match(/(\\d)/)[1]"),
    DifferentialCase("str-search-index", "'abc'.search(/b/)"),
    DifferentialCase("str-split-empty-parts", "'a,b,,c'.split(',').length"),
    DifferentialCase("str-split-empty-sep", "'abc'.split('').length"),
    DifferentialCase("str-split-empty-regex", "'abc'.split(/(?:)/).length"),
    DifferentialCase("str-fromcharcode-surrogate", "String.fromCharCode(55296, 57089).length"),
    DifferentialCase("str-locale-compare", "'ab'.localeCompare('ac')"),
    DifferentialCase("str-trim-unicode", "'\\u00A0x\\u2028'.trim().length"),
    DifferentialCase("str-sigma-context", "'A\\u03A3B'.toLowerCase()"),
    // String 原型方法的索引/填充/查找/归一化边界矩阵
    DifferentialCase(
        "str-methods-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return 'abcdef'.slice(-3) + '|' + 'abcdef'.slice(-3, -1) + '|' + " +
            "'abcdef'.slice(2, -1) + '|' + 'abcdef'.slice(4, 2); }); " +
            "push(function () { return 'abcdef'.substring(-2, 2) + '|' + 'abcdef'.substring(3, 1); }); " +
            "push(function () { return 'abcdef'.substr(-2, 1) + '|' + 'abcdef'.substr(2, -1); }); " +
            "push(function () { return 'abc'.at(-1) + '|' + 'abc'.at(3) + '|' + 'abc'.at(1.9); }); " +
            "push(function () { return ('abc'.charAt(5) === '') + '|' + String('abc'.charCodeAt(5)); }); " +
            "push(function () { return '\\uD842\\uDFB7a'.codePointAt(0) + '|' + " +
            "'\\uD842\\uDFB7a'.codePointAt(1) + '|' + '\\uD842\\uDFB7a'.length; }); " +
            "push(function () { return '5'.padStart(3, '0') + '|' + 'abc'.padEnd(5, '12') + '|' + " +
            "'x'.padStart(4) + '|' + 'x'.padEnd(3, ''); }); " +
            "push(function () { return 'x'.repeat(0) + '|' + 'ab'.repeat(3) + '|' + 'x'.repeat(2.9); }); " +
            "push(function () { try { 'x'.repeat(-1); return 'no'; } catch (e) { return e.name; } }); " +
            "push(function () { try { 'x'.repeat(Infinity); return 'no'; } catch (e) { return e.name; } }); " +
            "push(function () { return ' a b '.trim() + '|' + ' a b '.trimStart().length + '|' + " +
            "' a b '.trimEnd().length; }); " +
            "push(function () { return '\\u3000x\\u00A0'.trim().length; }); " +
            "push(function () { return 'abc'.includes('b', 2) + '|' + 'abc'.startsWith('b', 1) + '|' + " +
            "'abc'.endsWith('b', 2); }); " +
            "push(function () { return 'abc'.indexOf('') + '|' + 'abc'.lastIndexOf('') + '|' + " +
            "'abc'.indexOf('', 2) + '|' + 'abc'.lastIndexOf('', 2); }); " +
            "push(function () { return 'abc'.indexOf('b', NaN) + '|' + 'abc'.lastIndexOf('b', NaN); }); " +
            "push(function () { return 'a-b-c'.split('-', 0).length + '|' + 'a-b-c'.split('-', -1).length + " +
            "'|' + 'a-b-c'.split('-', 2).join('|'); }); " +
            "push(function () { return 'abc'.concat('d', 1, null, undefined); }); " +
            "push(function () { return String.raw({ raw: ['a', 'b', 'c'] }, 1, 2); }); " +
            "push(function () { return 'abc'.normalize('NFD').length + '|' + " +
            "'\\u00E9'.normalize('NFD').length; }); " +
            "push(function () { return 'abc'.replaceAll('b'); }); " +
            "push(function () { try { 'abc'.replaceAll(/(?:)/g, '-'); return 'ok'; } catch (e) { return e.name; } }); " +
            "push(function () { return Array.from('a\\uD842\\uDFB7b').join('|'); }); " +
            "return out.join('|'); })()"
    ),
    DifferentialCase("str-codepoint-low-surrogate", "String.fromCodePoint(128512).charCodeAt(1)"),
    DifferentialCase("str-repeat-zero", "'a'.repeat(0).length"),
    DifferentialCase("fn-tostring-source", "(function /*c*/ f(a) { return a; }).toString()"),
    DifferentialCase("fn-method-tostring", "({ m /*x*/ (a) { return a; } }).m.toString()"),
    DifferentialCase("fn-class-ctor-tostring", "(class A { constructor(x) { this.x = x; } }).toString()"),
    DifferentialCase("fn-arrow-tostring", "(a => a + 1).toString()"),
    DifferentialCase("fn-dynamic-tostring", "Function('a', 'b').toString()"),
    DifferentialCase("fn-bind-length-infinity", "'' + (function () { var f = function () {}; Object.defineProperty(f, 'length', { value: Infinity }); return f.bind().length; })()"),
    DifferentialCase("fn-bind-length-not-own", "(function () { function bar() {} Object.setPrototypeOf(bar, { length: 42 }); delete bar.length; return Function.prototype.bind.call(bar, null, 1).length; })()"),
    DifferentialCase("str-localecompare-canonical", "'o\u0308'.localeCompare('\u00F6') + ',' + '\u00E4\u0323'.localeCompare('a\u0323\u0308')"),
    DifferentialCase("str-pad-default-fill", "['abc'.padStart(5), 'abc'.padEnd(5), 'abc'.padStart(5, undefined)].join('|')"),
    DifferentialCase("str-null-this", "(function () { try { String.prototype.indexOf.call(null, 'a'); return 'none'; } catch (e) { return e.name; } })()"),
    DifferentialCase("str-replace-tostring-before-search", "(function () { var calls = 0; var rv = { toString: function () { calls += 1; return 'b'; } }; ''.replace('a', rv); return calls; })()"),
    DifferentialCase("str-split-limit-zero-separator", "(function () { function E() {} var s = { toString: function () { throw new E(); } }; try { 'foo'.split(s, 0); return 'none'; } catch (e) { return e.constructor.name; } })()"),
    DifferentialCase("str-split-limit-before-separator", "(function () { var log = 'none'; var lim = { valueOf: function () { log = 'limit'; throw new Error('x'); } }; var sep = { toString: function () { log = 'separator'; return ','; } }; try { 'a'.split(sep, lim); } catch (e) { } return log; })()"),

    // 数组与稀疏语义
    DifferentialCase("arr-sort-lexicographic", "[10, 9, 1].sort().join(',')"),
    DifferentialCase("arr-sort-numeric", "[10, 9, 1].sort(function (a, b) { return a - b; }).join(',')"),
    DifferentialCase("arr-sort-holes", "JSON.stringify([3, , 1].sort())"),
    DifferentialCase("arr-reverse-holes", "JSON.stringify([1, , 3].reverse())"),
    DifferentialCase("arr-splice-negative", "JSON.stringify([1, 2, 3, 4].splice(-3, 2))"),
    DifferentialCase("arr-splice-delete", "(function () { var a = [1, 2, 3]; a.splice(1); return a.join(','); })()"),
    DifferentialCase("arr-splice-insert", "(function () { var a = [1, 4]; a.splice(1, 0, 2, 3); return a.join(','); })()"),
    DifferentialCase("arr-concat-holes", "JSON.stringify([1, , 3].concat([4, , 6]))"),
    DifferentialCase("arr-fill-nan", "JSON.stringify([1, 2].fill(NaN))"),
    DifferentialCase("arr-copywithin-overlap", "[1, 2, 3, 4].copyWithin(1, 0, 3).join(',')"),
    DifferentialCase("arr-pop-inherited", "(function () { Array.prototype[1] = 1; var x = [0]; x.length = 2; var r = x.pop(); delete Array.prototype[1]; return r + ':' + x.length + ':' + x.hasOwnProperty('1'); })()"),
    DifferentialCase("arr-shift-inherited-holes", "JSON.stringify((function () { var a = [1, , 3]; var r = a.shift(); return [r, a]; })())"),
    DifferentialCase("arr-pop-nonwritable-length", "(function () { var a = [1]; Object.defineProperty(a, 'length', { writable: false }); try { a.pop(); return 'none'; } catch (e) { return e.name; } })()"),
    DifferentialCase("arr-splice-delete-throws", "(function () { var a = [1, 2]; Object.defineProperty(a, '1', { configurable: false }); try { a.splice(1); return 'none'; } catch (e) { return e.name; } })()"),
    DifferentialCase("arr-splice-inherited-value", "(function () { Array.prototype[1] = 'p'; var a = [0]; a.length = 2; var r = a.splice(1); delete Array.prototype[1]; return JSON.stringify(r) + ':' + a.join(','); })()"),
    DifferentialCase("arr-fill-return-this", "(function () { var o = { length: 2 }; var r = Array.prototype.fill.call(o, 7); return (r === o) + ':' + o[0] + o[1]; })()"),
    DifferentialCase("arr-fill-primitive-this", "(function () { var r = Array.prototype.fill.call(true, 1); return (r instanceof Boolean) + ':' + r.length; })()"),
    DifferentialCase("arr-fill-undefined-end", "JSON.stringify([0, 0].fill(1, 0, undefined))"),
    DifferentialCase("arr-copywithin-return-this", "(function () { var o = { 0: 1, 1: 2, length: 2 }; var r = Array.prototype.copyWithin.call(o, 0, 1); return (r === o) + ':' + o[0]; })()"),
    DifferentialCase("arr-copywithin-holes", "JSON.stringify((function () { var a = [1, , 3, 4]; a.copyWithin(1, 2); return a; })())"),
    DifferentialCase("arr-indexof-length-zero", "(function () { var called = false; var arg = { valueOf: function () { called = true; return 1; } }; [].indexOf(1, arg); return called; })()"),
    DifferentialCase("arr-lastindexof-length-zero", "(function () { var called = false; var arg = { valueOf: function () { called = true; return 1; } }; [].lastIndexOf(1, arg); return called; })()"),
    DifferentialCase("arr-find-holes", "(function () { var called = 0; [, , 'x'].find(function () { called++; }); return called; })()"),
    DifferentialCase("arr-find-mutation", "(function () { var a = ['a', 'b', 'c']; var seen = []; a.find(function (v) { if (seen.length === 0) a.splice(1, 1); seen.push(v); }); return seen.length + ':' + seen[1] + ':' + String(seen[2]); })()"),
    DifferentialCase("arr-at-inherited", "(function () { Array.prototype[1] = 'z'; var r = [0].at(1); delete Array.prototype[1]; return r; })()"),
    DifferentialCase("arr-indexof-nan", "[NaN].indexOf(NaN)"),
    DifferentialCase("arr-includes-nan", "[NaN].includes(NaN)"),
    DifferentialCase("arr-reduce-holes", "[1, , 3].reduce(function (a, b) { return a + b; })"),
    DifferentialCase("arr-from-string", "Array.from('abc').join(',')"),
    DifferentialCase("arr-from-set", "Array.from(new Set([1, 2, 2])).join(',')"),
    DifferentialCase("arr-apply-arraylike", "Array.apply(null, { length: 3 }).length"),
    DifferentialCase("arr-map-holes", "JSON.stringify(Array(3).map(function () { return 1; }))"),
    DifferentialCase("arr-slice-holes", "JSON.stringify([1, , 3].slice(0, 3))"),
    DifferentialCase("arr-length-max-push", "(function () { var a = []; a.length = 4294967295; try { a.push('x'); } catch (e) { return e.name + ':' + a.hasOwnProperty('4294967295'); } })()"),
    DifferentialCase("arr-sort-undefined", "JSON.stringify([undefined, 1, null].sort())"),
    DifferentialCase(
        "arr-sort-precise-accessor",
        "(function () { var a = [undefined, 'c', , 'b', undefined, , 'a', 'd'];" +
            " Object.defineProperty(a, '2', {" +
            "   get: function () { a.length += 2; return this.foo; }," +
            "   set: function (v) { this.foo = v; } });" +
            " a.sort();" +
            " return [a.length, a[0], a[1], a[3], 7 in a, a[2], a.length].join('|'); })()"
    ),
    DifferentialCase(
        "arr-sort-array-like",
        "(function () { var o = { 0: 'b', 1: 'a', length: 2 };" +
            " Array.prototype.sort.call(o); return o[0] + o[1] + o.length; })()"
    ),
    DifferentialCase(
        "arr-sort-nan-comparator",
        "[3, 1, 2].sort(function () { return NaN; }).join(',')"
    ),
    DifferentialCase("arr-lastindexof-negative", "[1, 2, 1].lastIndexOf(1, -2)"),
    DifferentialCase("arr-lastindexof-undefined", "[1, 2, 2].lastIndexOf(2, undefined)"),
    DifferentialCase(
        "arr-toString-nonarray",
        "[Array.prototype.toString.call(true), Array.prototype.toString.call({})].join('|')"
    ),
    DifferentialCase(
        "arr-reduce-getter-once",
        "(function () { var first = 0; var a = [, , ];" +
            " Object.defineProperty(a, '0', { get: function () { first++; return 1; }," +
            " configurable: true }); a.reduce(function (p, v) { return p + v; });" +
            " return first; })()"
    ),
    DifferentialCase(
        "obj-proto-name",
        "(function () { var o = { __proto__: function () {} };" +
            " return Object.getPrototypeOf(o).name !== '__proto__'; })()"
    ),
    DifferentialCase(
        "set-keys-values-identity",
        "Set.prototype.keys === Set.prototype.values"
    ),
    DifferentialCase("legacy-octal-sloppy", "070"),
    DifferentialCase(
        "switch-async-function-scope",
        "(function () { try { eval('switch (0) { default: async function x() {} } x;');" +
            " return 'none'; } catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "catch-destructuring-default-env",
        "(function () { var probeParam; var x = 'outside';" +
            " try { throw ['inside']; }" +
            " catch ([x, _ = probeParam = function () { return x; }]) {}" +
            " return probeParam(); })()"
    ),
    DifferentialCase(
        "eval-var-deletable",
        "(function () { var initial, post;" +
            " (function () { eval('initial = x; delete x;" +
            " post = function () { x; }; var x;'); })();" +
            " try { post(); return 'none'; } catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "fn-var-not-deletable",
        "(function () { var y = 1; return delete y; })()"
    ),
    DifferentialCase(
        "legacy-non-octal-escapes",
        "JSON.stringify(['\\8', '\\9', '\\08', '\\400'])"
    ),
    DifferentialCase(
        "regex-unicode-simple-fold",
        "[/[\\u0390]/ui.test('\\u1FD3'), /[\\uFB05]/ui.test('\\uFB06')," +
            " /\\u212A/ui.test('k')].join(',')"
    ),
    DifferentialCase(
        "regex-zero-width-quantifier",
        "(function () { var a = 'abc'.match(/(?:(?=(abc)))?a/);" +
            " var b = 'abc'.match(/(?:(?=(abc))){1,1}a/);" +
            " var c = /(a?b??)*/.exec('ab');" +
            " return [a[1] === undefined, b[1], c[0]].join('|'); })()"
    ),
    DifferentialCase(
        "regex-huge-quantifier",
        "(function () { var re = new RegExp('b' + Number.MAX_SAFE_INTEGER, 'u');" +
            " return [re.test(''), re.test('a'), re.test('b')].join(','); })()"
    ),
    DifferentialCase(
        "regex-lookbehind-quantifier",
        "(function () { try { eval('/.(?<=.)?/'); return 'none'; }" +
            " catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "for-async-of-arrow",
        "(function () { var counter = 0;" +
            " for (async of => {}; counter < 3; ++counter) {} return counter; })()"
    ),
    DifferentialCase(
        "for-of-async-lhs",
        "(function () { try { eval('var async; for (async of [1]) ;');" +
            " return 'none'; } catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "delete-unresolvable-base",
        "(function () { try { delete Object[0][0]; return 'none'; }" +
            " catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "member-null-key-order",
        "(function () { var base = null;" +
            " var key = { toString: function () { throw new Error('key'); } };" +
            " try { base[key]; return 'none'; } catch (e) { return e.name; } })()"
    ),
    DifferentialCase("delete-primitive-base", "delete true.x"),
    DifferentialCase(
        "super-assign-null-rhs-order",
        "(function () { var count = 0; class C { static m() { super.x = (count += 1); } }" +
            " Object.setPrototypeOf(C, null);" +
            " try { C.m(); return 'none'; } catch (e) { return e.name + ':' + count; } })()"
    ),
    DifferentialCase(
        "super-computed-key-after-rhs",
        "(function () { function DummyError() {}" +
            " var prop = { toString: function () { throw new Error('key'); } };" +
            " class C extends (class {}) { m() { super[prop] = (function () {" +
            "   throw new DummyError(); })(); } }" +
            " try { (new C()).m(); return 'none'; } catch (e) { return e instanceof DummyError; } })()"
    ),
    DifferentialCase(
        "super-increment-uninitialized-this",
        "(function () { class Base { constructor() { throw new Error('base'); } }" +
            " class Derived extends Base { constructor() { super[super()]++; } }" +
            " try { new Derived(); return 'none'; } catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "async-function-subclass",
        "(function () { async function foo() {} var AF = foo.constructor;" +
            " return Object.getPrototypeOf(AF) === Function && foo instanceof Function; })()"
    ),

    // 对象、描述符与枚举
    DifferentialCase("obj-keys-order", "Object.keys({ b: 1, 2: 2, 1: 3, a: 4 }).join(',')"),
    // 属性键序边界：数组索引上限、前导零与非规范数字字符串
    DifferentialCase(
        "obj-key-order-boundary",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { var o = { '4294967295': 1, '4294967294': 2, '1': 3, '01': 4, " +
            "'-1': 5, '0': 6, '1.5': 7, 'NaN': 8, 'Infinity': 9, '2147483647': 10 }; " +
            "return Object.keys(o).join(','); }); " +
            "push(function () { var o = {}; o['9007199254740993'] = 1; o['4294967294'] = 2; " +
            "o['4294967295'] = 3; o['-0'] = 4; o['00'] = 5; return Object.keys(o).join(','); }); " +
            "push(function () { var o = { '2': 'a', '01': 'b', '1': 'c', '4294967294': 'd' }; " +
            "return JSON.stringify(o); }); " +
            "push(function () { var o = { '10': 1, '9': 2, '1e2': 3, '0x10': 4 }; " +
            "return Object.getOwnPropertyNames(o).join(','); }); " +
            "push(function () { var o = {}; o[4294967294] = 1; o[4294967295] = 2; " +
            "return Object.keys(o).join(','); }); " +
            "return out.join('|'); })()"
    ),
    DifferentialCase("obj-array-own-names", "Object.getOwnPropertyNames([1, 2]).join(',')"),
    DifferentialCase("obj-descriptor-flags", "(function () { var d = Object.getOwnPropertyDescriptor([], 'length'); return [d.writable, d.enumerable, d.configurable].join(','); })()"),
    DifferentialCase("obj-define-length-string", "Object.defineProperty([], 'length', { value: '2' }).length"),
    DifferentialCase("obj-define-length-null", "(function () { var a = [1, 2]; Object.defineProperty(a, 'length', { value: null }); return a.length; })()"),
    DifferentialCase("obj-define-length-negative", "(function () { try { Object.defineProperty([], 'length', { value: -1 }); } catch (e) { return e.name; } })()"),
    DifferentialCase("obj-define-nonwritable-push", "(function () { var a = [1]; Object.defineProperty(a, 'length', { writable: false }); try { a.push(2); } catch (e) { return e.name; } })()"),
    DifferentialCase("obj-freeze-array-prop", "(function () { var a = []; a.foo = 1; Object.freeze(a); var d = Object.getOwnPropertyDescriptor(a, 'foo'); return [d.writable, d.configurable].join(','); })()"),
    DifferentialCase("obj-assign-getter", "(function () { var src = {}; Object.defineProperty(src, 'x', { get: function () { return this.y * 2; }, enumerable: true }); src.y = 3; return Object.assign({}, src).x; })()"),
    DifferentialCase("obj-create-null-proto", "Object.getPrototypeOf(Object.create(null)) === null"),
    DifferentialCase("obj-getproto-string", "Object.getPrototypeOf('x') === String.prototype"),
    DifferentialCase("obj-property-enumerable", "({ a: 1 }).propertyIsEnumerable('a')"),
    DifferentialCase("obj-forin-shadow", "(function () { var p = { x: 1 }; var o = Object.create(p); Object.defineProperty(o, 'x', { value: 2, enumerable: false }); var s = ''; for (var k in o) s += k; return s; })()"),
    DifferentialCase("obj-valueof-null", "(function () { try { Object.prototype.valueOf.call(null); } catch (e) { return e.name; } })()"),
    DifferentialCase("obj-tolocalestring-null", "(function () { try { Object.prototype.toLocaleString.call(undefined); } catch (e) { return e.name; } })()"),
    DifferentialCase("obj-keys-string", "Object.keys('ab').join(',')"),
    // 求值顺序矩阵：通过 getter/setter/日志观察规范规定的访问顺序
    DifferentialCase(
        "eval-order-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { var log = []; var o = {}; " +
            "Object.defineProperty(o, 'k', { set: function (v) { log.push('set' + v); } }); " +
            "function key() { log.push('key'); return 'k'; } " +
            "function val() { log.push('val'); return 1; } " +
            "o[key()] = val(); return log.join(','); }); " +
            "push(function () { var log = []; " +
            "function f(x) { log.push('f'); return function () { log.push('call'); return x; }; } " +
            "function g() { log.push('g'); return 1; } function h() { log.push('h'); return 2; } " +
            "f(g())(h()); return log.join(','); }); " +
            "push(function () { var log = []; var state = 1; var o = {}; " +
            "Object.defineProperty(o, 'a', { get: function () { log.push('get a'); return state; }, " +
            "set: function (v) { log.push('set a'); state = v; } }); " +
            "o.a += (log.push('rhs'), 2); return log.join(','); }); " +
            "push(function () { var log = []; " +
            "function make() { log.push('make'); return function () { log.push('call'); }; } " +
            "new (make())((log.push('arg'), 1)); return log.join(','); }); " +
            "push(function () { var log = []; " +
            "function tag(strings) { log.push('tag'); return strings.raw[0]; } " +
            "function part() { log.push('part'); return 'x'; } " +
            "tag`a\${part()}b`; return log.join(','); }); " +
            "push(function () { var log = []; var src = { " +
            "get a() { log.push('get a'); return 1; }, get b() { log.push('get b'); return 2; } }; " +
            "Object.assign({}, src); return log.join(','); }); " +
            "push(function () { var log = []; " +
            "JSON.stringify([{ toJSON: function (k) { log.push('toJSON ' + k); return 1; } }]); " +
            "return log.join(','); }); " +
            "push(function () { var log = []; var state = { x: 1, y: 2 }; var o = {}; " +
            "Object.defineProperty(o, 'x', { get: function () { log.push('get x'); return state.x; }, " +
            "set: function (v) { log.push('set x'); state.x = v; } }); " +
            "Object.defineProperty(o, 'y', { get: function () { log.push('get y'); return state.y; }, " +
            "set: function (v) { log.push('set y'); state.y = v; } }); " +
            "[o.x, o.y] = [o.y, o.x]; return log.join(',') + '|' + state.x + ',' + state.y; }); " +
            "push(function () { var log = []; var o = { " +
            "get a() { log.push('get a'); return 1; }, get b() { log.push('get b'); return 2; } }; " +
            "var { a, b } = o; return log.join(','); }); " +
            "push(function () { var log = []; var a = { " +
            "get length() { log.push('length'); return 2; }, " +
            "get 0() { log.push('0'); return 'x'; }, get 1() { log.push('1'); return 'y'; } }; " +
            "Array.prototype.join.call(a, '-'); return log.join(','); }); " +
            "push(function () { var log = []; " +
            "var o = { toString: function () { log.push('toString'); return 'k'; } }; " +
            "var target = {}; target[o] = 1; return log.join(','); }); " +
            "push(function () { var log = []; " +
            "var o = { valueOf: function () { log.push('valueOf'); return 1; } }; " +
            "var r = o + (log.push('rhs'), 2); return log.join(',') + '|' + r; }); " +
            "push(function () { var log = []; " +
            "var cond = { valueOf: function () { log.push('valueOf'); return 0; } }; " +
            "cond ? log.push('then') : log.push('else'); return log.join(','); }); " +
            "return out.join('|'); })()"
    ),
    // Array 方法在稀疏数组、原型链与 array-like 上的语义矩阵
    DifferentialCase(
        "array-methods-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return [1, , 3].map(function (x) { return x * 2; }).join('|'); }); " +
            "push(function () { return [1, , 3].filter(function () { return true; }).length; }); " +
            "push(function () { var n = 0; [1, , 3].forEach(function () { n++; }); return n; }); " +
            "push(function () { return [1, , 3].reduce(function (a, b) { return a + b; }, 0); }); " +
            "push(function () { return [1, , 3].indexOf(undefined) + '|' + [1, , 3].includes(undefined); }); " +
            "push(function () { return Array.prototype.slice.call({ 0: 'a', 1: 'b', length: 2 }).join('|'); }); " +
            "push(function () { return Array.from({ 0: 'x', length: 1 }).join('|'); }); " +
            "push(function () { return Array.from({ length: 3 }, function (_, i) { return i * 2; }).join('|'); }); " +
            "push(function () { return [].concat([1, , 3]).length; }); " +
            "push(function () { return Object.keys([1, , 3]).join(','); }); " +
            "push(function () { return [1, 2, 3].copyWithin(0, 1).join('|'); }); " +
            "push(function () { return [1, 2, 3].fill(0, 1).join('|'); }); " +
            "push(function () { var a = [1, 2, 3, 4]; var removed = a.splice(1, 2, 'x'); " +
            "return removed.join('|') + ';' + a.join('|'); }); " +
            "push(function () { return [10, 2, 1].sort().join('|') + ';' + [3, 1, 2].sort().join('|'); }); " +
            "push(function () { return [1, [2, [3]]].flat(2).join('|'); }); " +
            "push(function () { return [1, 2].flatMap(function (x) { return [x, x * 10]; }).join('|'); }); " +
            "push(function () { return [1, 2, 3].at(-2); }); " +
            "push(function () { return [1, 2, 3].findLast(function (x) { return x < 3; }); }); " +
            "push(function () { var a = [1, 2, 3]; return a.toReversed().join('|') + ';' + a.join('|'); }); " +
            "push(function () { var a = [1, 2, 3]; return a.with(1, 9).join('|') + ';' + a.join('|'); }); " +
            "push(function () { Array.prototype[1] = 9; var a = [1, , 3]; " +
            "var joined = a.join('|'); delete Array.prototype[1]; return joined; }); " +
            "push(function () { return [NaN].includes(NaN) + '|' + [NaN].indexOf(NaN); }); " +
            "push(function () { var o = { 0: 'a', length: 1 }; var v = Array.prototype.pop.call(o); " +
            "return v + '|' + o.length; }); " +
            "push(function () { return new Array(3).fill(1).join('|'); }); " +
            "push(function () { return Array.of(1, 2).join('|'); }); " +
            "push(function () { return [1, 2, 3].reduceRight(function (a, b) { return a - b; }); }); " +
            "return out.join('|'); })()"
    ),
    DifferentialCase("obj-arguments-descriptor", "(function (a) { var d = Object.getOwnPropertyDescriptor(arguments, '0'); return [d.value, d.writable, d.enumerable, d.configurable].join(','); })(7)"),
    DifferentialCase("obj-proto-set-nonextensible", "(function () { var x = Object.preventExtensions({}); x.__proto__ = { a: 1 }; return Object.getPrototypeOf(x) === Object.prototype; })()"),

    // 正则
    DifferentialCase("re-capture-reset", "JSON.stringify(/(z)((a+)?(b+)?(c))*/.exec('zaacbbbcac'))"),
    DifferentialCase("re-global-lastindex", "(function () { var r = /a/g; var a = r.exec('ba'); var b = r.exec('ba'); return [a.index, b, r.lastIndex].join(','); })()"),
    DifferentialCase("re-sticky", "(function () { var r = /a/y; r.lastIndex = 1; var m = r.exec('ba'); return m ? m.index : 'null'; })()"),
    DifferentialCase("re-split-captures", "JSON.stringify('a1b2'.split(/(\\d)/))"),
    DifferentialCase("re-replace-group", "'abc'.replace(/(b)/, '[$1]')"),
    DifferentialCase("re-replace-dollar", "'abc'.replace(/b/, '$$')"),
    DifferentialCase("re-match-global", "'a1a2'.match(/a/g).length"),
    DifferentialCase("re-negative-lastindex", "(function () { var r = /a/g; r.lastIndex = -1; var m = r.exec('ba'); return m.index + ':' + r.lastIndex; })()"),
    DifferentialCase("re-unicode-escape", "/\\u{1F600}/u.test(String.fromCodePoint(128512))"),
    DifferentialCase("re-class-any", "/[\\s\\S]/.test('\\n')"),
    DifferentialCase("re-source-empty", "new RegExp('').source"),
    DifferentialCase("re-test-empty", "/(?:)/.test('x')"),
    DifferentialCase("re-lookahead-backref", "JSON.stringify(/(.*?)a(?!(a+)b\\2c)\\2(.*)/.exec('baaabaac'))"),
    DifferentialCase("re-forward-backref", "/\\1(A)/.exec('AA')[0]"),

    // 日期
    DifferentialCase("date-utc-normalize", "Date.UTC(2020, 12, 1)"),
    // 非十进制 toString 的最短往返表示与超大整数
    DifferentialCase(
        "num-radix-matrix",
        "[(0.1).toString(2), (0.5).toString(2), (255.5).toString(16), (0.1).toString(3), " +
            "(123.456).toString(36), (1e21).toString(16), (1e21).toString(2).length, " +
            "(-0.1).toString(2), (2 ** 53).toString(16), (2 ** 53 + 1).toString(16)].join('|')"
    ),
    // 进制转换的边界矩阵：2 的幂进制下 V8 的输出可精确往返，可直接比较完整文本。
    // 非 2 的幂进制（3/5/7/36 等）的 V8 输出常无法解析回原 double，
    // 本项目按规范实现最短往返表示，差异记录于 docs/COMPATIBILITY.md。
    DifferentialCase(
        "num-radix-boundary-matrix",
        "(function () { var values = [0.1, 0.2, 1 / 3, 2 / 3, 1 / 7, 0.5, 1.5, 2.5, " +
            "1.0000000000000002, 0.9999999999999999, 123.456, 1e-7, 1e-6, 1e-5, 1e10, 1e15, " +
            "1e20, 1e21, 2 ** 53, 2 ** 53 - 1, 2 ** 53 + 2, 2 ** 52, 2 ** -52, 5e-324, " +
            "2.2250738585072014e-308, 1.7976931348623157e308, 6.02214076e23, 3.141592653589793, " +
            "1e-300, 4.9406564584124654e-324]; " +
            "var radices = [2, 4, 8, 16, 32]; var out = []; " +
            "for (var i = 0; i < values.length; i++) { " +
            "for (var j = 0; j < radices.length; j++) { out.push(values[i].toString(radices[j])); } } " +
            "return out.join('|'); })()"
    ),
    // 确定性随机模糊：LCG 生成 mantissa × 2^exp 的有限 normal，验证 2 的幂进制最短往返
    DifferentialCase(
        "num-radix-fuzz",
        "(function () { var out = []; var seed = 12345; " +
            "function next() { seed = (seed * 48271) % 2147483647; return seed; } " +
            "for (var i = 0; i < 30; i++) { " +
            "var mantissa = 1 + (next() % 1048576) / 1048576; " +
            "var exponent = (next() % 121) - 60; " +
            "var value = mantissa * Math.pow(2, exponent); " +
            "if (i % 2 === 1) value = -value; " +
            "out.push(value.toString(2)); " +
            "out.push(value.toString(8)); " +
            "out.push(value.toString(16)); " +
            "out.push(value.toString(32)); " +
            "} return out.join('|'); })()"
    ),
    // Date 的 UTC/TimeClip/ISO 解析与溢出矩阵（避免依赖本地时区的构造与 getter）
    DifferentialCase(
        "date-utc-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return Date.UTC(2020, 0, 1); }); " +
            "push(function () { return Date.UTC(99, 0, 1); }); " +
            "push(function () { return Date.UTC(0, 0, 1); }); " +
            "push(function () { return Date.UTC(2020, -1, 1); }); " +
            "push(function () { return Date.UTC(2020, 0, 0); }); " +
            "push(function () { return Date.UTC(2020, 1, 30, 25, 61, 61, 1001); }); " +
            "push(function () { return new Date(8640000000000000).getTime(); }); " +
            "push(function () { return new Date(8640000000000001).getTime(); }); " +
            "push(function () { return new Date(-8640000000000000).getTime(); }); " +
            "push(function () { return new Date(8.64e15).toISOString(); }); " +
            "push(function () { return new Date(-8.64e15).toISOString(); }); " +
            "push(function () { return new Date(NaN).toISOString(); }); " +
            "push(function () { return new Date(NaN).toUTCString(); }); " +
            "push(function () { return new Date(0).toUTCString(); }); " +
            "push(function () { return new Date(0).toJSON(); }); " +
            "push(function () { return Date.parse('2020-01-01T00:00:00.123Z'); }); " +
            "push(function () { return Date.parse('2020-01-01T08:00:00+08:00'); }); " +
            "push(function () { return Date.parse('2020-01-01'); }); " +
            "push(function () { return Date.parse('2020-02-30T00:00:00Z'); }); " +
            "push(function () { return new Date('2020-01-01T00:00:00.000+00:00').toISOString(); }); " +
            "push(function () { return new Date(0).getUTCFullYear() + ',' + new Date(0).getUTCMonth() + ',' + " +
            "new Date(0).getUTCDate() + ',' + new Date(0).getUTCDay(); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 31)); d.setUTCMonth(1); return d.toISOString(); }); " +
            "push(function () { var d = new Date(0); d.setUTCHours(25); return d.toISOString(); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); return [d.getUTCFullYear(), " +
            "d.getUTCMonth(), d.getUTCDate(), d.getUTCHours(), d.getUTCMinutes(), d.getUTCSeconds(), " +
            "d.getUTCMilliseconds()].join(','); }); " +
            "return out.join('|'); })()"
    ),
    DifferentialCase("date-local-constructor", "new Date(2020, 0, 1).getFullYear()"),
    DifferentialCase("date-parse-iso", "new Date('2020-01-01T00:00:00Z').toISOString()"),
    DifferentialCase("date-month-overflow", "(function () { var d = new Date(2020, 12, 1); return d.getFullYear() + '-' + d.getMonth(); })()"),
    DifferentialCase("date-weekday", "new Date(0).getUTCDay()"),
    DifferentialCase("date-invalid-string", "new Date(NaN).toString()"),
    DifferentialCase("date-invalid-json", "JSON.stringify(new Date(NaN))"),
    DifferentialCase("date-parse-epoch", "Date.parse('1970-01-01T00:00:00.000Z')"),
    DifferentialCase("date-set-month", "(function () { var d = new Date(0); d.setUTCMonth(13); return d.toISOString(); })()"),
    DifferentialCase("date-two-digit-year", "new Date(99, 0, 1).getFullYear()"),
    DifferentialCase("date-utc-default-month", "Date.UTC(1970)"),
    DifferentialCase("date-utc-defaults", "Date.UTC(2016, 6)"),
    DifferentialCase("date-utc-infinity-year", "Date.UTC(Infinity, 0)"),
    DifferentialCase("date-utc-infinity-hour", "Date.UTC(2016, 0, 1, Infinity)"),
    DifferentialCase("date-utc-fp-order", "Date.UTC(1970, 0, 1, 80063993375, 29, 1, -288230376151711740)"),
    DifferentialCase("date-utc-fp-date", "Date.UTC(1970, 0, 213503982336, 0, 0, 0, -18446744073709552000)"),
    DifferentialCase("date-utc-time-clip", "Date.UTC(275760, 8, 13, 0, 0, 0, 1)"),
    DifferentialCase("date-set-invalid-date", "(function () { var d = new Date(NaN); return String(d.setDate(1)); })()"),
    DifferentialCase(
        "date-setyear-nan",
        "(function () { var d = new Date(0); var r = d.setYear(NaN); return String(r) + '|' + String(d.getTime()); })()"
    ),
    DifferentialCase(
        "date-setyear-missing-arg",
        "(function () { var d = new Date(0); var r = d.setYear(); return String(r) + '|' + String(d.getTime()); })()"
    ),
    DifferentialCase(
        "date-setter-coerce-order",
        "(function () { var log = []; function arg(n) { return { valueOf: function () { " +
            "log.push(n); return 1; } }; } new Date(0).setMonth(NaN, arg('day')); " +
            "new Date(0).setFullYear(NaN, arg('m'), arg('d')); " +
            "new Date(0).setHours(NaN, arg('m'), arg('s'), arg('ms')); " +
            "new Date(0).setMinutes(NaN, arg('s'), arg('ms')); " +
            "new Date(0).setSeconds(NaN, arg('ms')); " +
            "new Date(0).setUTCMonth(NaN, arg('uday')); return log.join(','); })()"
    ),
    DifferentialCase(
        "date-utc-setter-fuzz",
        "(function () { " +
            "var methods = ['setUTCFullYear', 'setUTCMonth', 'setUTCDate', 'setUTCHours', " +
            "'setUTCMinutes', 'setUTCSeconds', 'setUTCMilliseconds', 'setTime']; " +
            "var values = [0, 1, 5, 12, 31, 99, 100, -1, NaN, Infinity, -Infinity, undefined, '7', -0]; " +
            "var times = [0, NaN, 123456789, -1, 8640000000000000, -8640000000000000, " +
            "8640000000000001, -8640000000000001, 123.456, -123.456, 1e20]; " +
            "var seed = 12345; function rnd(n) { seed = (seed * 48271) % 2147483647; return seed % n; } " +
            "var out = []; for (var i = 0; i < 150; i++) { " +
            "var t = times[rnd(times.length)]; var name = methods[rnd(methods.length)]; " +
            "var d = new Date(t); var args = []; " +
            "var count = name === 'setTime' ? 1 : rnd(5); " +
            "for (var j = 0; j < count; j++) { args.push(values[rnd(values.length)]); } " +
            "var result; try { result = String(d[name].apply(d, args)); } " +
            "catch (e) { result = 'THROW:' + e.name; } " +
            "out.push(i + ':' + name + ':' + t + ':' + count + ':' + result + ':' + String(d.getTime())); } " +
            "return out.join(';'); })()"
    ),
    DifferentialCase(
        "date-setter-boundary-matrix",
        "(function () { var out = []; " +
            "function t(label, fn) { try { out.push(label + '=' + fn()); } " +
            "catch (e) { out.push(label + '=THROW:' + e.name); } } " +
            "t('y99', function () { var d = new Date(0); d.setYear(99); return d.getTime(); }); " +
            "t('yinf', function () { var d = new Date(0); d.setYear(Infinity); return d.getTime(); }); " +
            "t('ynan', function () { var d = new Date(0); return d.setYear(NaN) + '|' + d.getTime(); }); " +
            "t('monthUndef', function () { var d = new Date(0); d.setMonth(1, undefined); return d.getTime(); }); " +
            "t('hoursInf', function () { var d = new Date(0); d.setHours(Infinity); return d.getTime(); }); " +
            "t('fullYearInf', function () { var d = new Date(0); d.setFullYear(Infinity); return d.getTime(); }); " +
            "t('fullYearUndefMonth', function () { var d = new Date(0); " +
            "d.setFullYear(2000, undefined); return d.getTime(); }); " +
            "t('utcFullYearUndefDay', function () { var d = new Date(0); " +
            "d.setUTCFullYear(2000, 1, undefined); return d.getTime(); }); " +
            "t('invalidSetHours', function () { var d = new Date(NaN); " +
            "return d.setHours(1) + '|' + d.getTime(); }); " +
            "return out.join(';'); })()"
    ),
    DifferentialCase("date-set-invalid-no-coerce", "(function () { var called = false; var arg = { valueOf: function () { called = true; return 1; } }; new Date(NaN).setMonth(arg); return called; })()"),
    DifferentialCase("date-setfullyear-invalid", "(function () { var d = new Date(NaN); d.setFullYear(2020); return d.getFullYear(); })()"),
    DifferentialCase("date-parse-tostring-roundtrip", "Date.parse(new Date(0).toString())"),
    DifferentialCase("date-parse-neg-zero-year", "Date.parse('-000000-03-31T00:45Z')"),
    DifferentialCase("date-parse-out-of-range", "Date.parse('-271821-04-19T23:59:59.999Z')"),
    DifferentialCase("date-parse-min-range", "Date.parse('-271821-04-20T00:00:00.000Z')"),
    DifferentialCase("date-toutcstring", "new Date(0).toUTCString()"),
    DifferentialCase("date-setfullyear-year-one", "(function () { var d = new Date(0); d.setFullYear(1); return d.getFullYear(); })()"),
    DifferentialCase("date-iso-year-only", "new Date('1970').toISOString()"),
    DifferentialCase("date-negative-year-utc", "new Date('-000001-07-01T00:00Z').toUTCString()"),
    DifferentialCase("err-tostring-undefined-props", "Error.prototype.toString.call({ name: undefined, message: undefined })"),
    DifferentialCase("obj-isprototypeof-null-this", "(function () { try { Object.prototype.isPrototypeOf.call(null, {}); return 'none'; } catch (e) { return e.name; } })()"),
    DifferentialCase("arr-slice-undefined-end", "JSON.stringify([0,1,2,3,4].slice(3, undefined))"),
    DifferentialCase("arr-from-constructor-length", "(function () { function C() { this.args = arguments; } return Array.from.call(C, { length: 3 }).args[0]; })()"),
    DifferentialCase("arr-of-length-setter", "(function () { var hits = 0; function P() { Object.defineProperty(this, 'length', { set: function () { hits++; } }); } Array.of.call(P, 1, 2); return hits; })()"),
    DifferentialCase("obj-entries-getter-deletes", "(function () { var o = { a: 'A', get b() { delete this.c; return 'B'; }, c: 'C' }; return JSON.stringify(Object.entries(o)); })()"),
    DifferentialCase("math-max-coerce-count", "(function () { var calls = 0; var n = { valueOf: function () { calls++; } }; Math.max(NaN, n); return calls; })()"),
    // Math 精确语义矩阵：符号零、ToInt32/ToUint32、特殊 pow/hypot 与 fround
    DifferentialCase(
        "math-exact-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return [Math.round(0.5), Math.round(-0.5), 1 / Math.round(-0.5), " +
            "Math.round(2.5), Math.round(-2.5)].join(','); }); " +
            "push(function () { return [1 / Math.sign(-0), Math.sign(0), Math.sign(-3), " +
            "String(Math.sign(NaN))].join(','); }); " +
            "push(function () { return [1 / Math.trunc(-0.9), Math.ceil(-0.5), " +
            "1 / Math.ceil(-0.5), Math.floor(-0.5)].join(','); }); " +
            "push(function () { return [Math.clz32(0), Math.clz32(1), Math.clz32(0xFFFFFFFF), " +
            "Math.clz32(NaN), Math.clz32(2 ** 32), Math.clz32(1.9)].join(','); }); " +
            "push(function () { return [Math.imul(0xFFFFFFFF, 5), Math.imul(2, 3), " +
            "Math.imul(-2, 3), Math.imul(NaN, 3), Math.imul(0x7FFFFFFF, 0x7FFFFFFF)].join(','); }); " +
            "push(function () { return [Math.fround(1.1), Math.fround(1e40), " +
            "1 / Math.fround(-0), Math.fround(5e-324)].join(','); }); " +
            "push(function () { return [1 / Math.max(-0, 0), 1 / Math.min(0, -0), " +
            "String(Math.max(-0, -0)), String(Math.min(0, 0))].join(','); }); " +
            "push(function () { return [Math.pow(-0, -3), Math.pow(-0, 2), Math.pow(NaN, 0), " +
            "String(Math.pow(1, NaN)), String(Math.pow(-1, Infinity)), String(Math.pow(-1, 0.5))].join(','); }); " +
            "push(function () { return [Math.pow(2, -1074), Math.pow(2, 1024), " +
            "Math.pow(-8, 1 / 3)].join(','); }); " +
            "push(function () { return [Math.hypot(3, 4), String(Math.hypot(NaN, Infinity)), " +
            "Math.hypot(), Math.hypot(1e308, 1e308)].join(','); }); " +
            "push(function () { return [1 / Math.abs(-0), 1 / Math.sqrt(-0), 1 / Math.expm1(-0), " +
            "1 / Math.log1p(-0), Math.cbrt(-8), 1 / Math.cbrt(-0)].join(','); }); " +
            "push(function () { return [1 / Math.atan2(-0, 1), Math.atan2(0, -1), " +
            "1 / Math.sin(-0), 1 / Math.tan(-0)].join(','); }); " +
            "return out.join('|'); })()"
    ),
    DifferentialCase("num-parse-identity", "Number.parseInt === parseInt && Number.parseFloat === parseFloat"),
    DifferentialCase("arguments-unmapped-callee", "(function () { function f(a = 0) { return arguments; } return Object.getOwnPropertyDescriptor(f(), 'callee').get === Object.getOwnPropertyDescriptor(Function.prototype, 'caller').get; })()"),
    DifferentialCase("num-toexponential-nonfinite", "[(Infinity).toExponential(1000), NaN.toExponential(2)].join('|')"),
    DifferentialCase("date-settime-value", "(function () { var d = new Date(0); d.setTime(1000); return d.valueOf(); })()"),

    // JSON
    DifferentialCase("json-replacer-array", "JSON.stringify({ a: 1, b: 2, c: 3 }, ['b', 'a'])"),
    DifferentialCase("json-space", "JSON.stringify({ a: 1, b: [2] }, null, 2)"),
    DifferentialCase("json-tojson", "JSON.stringify({ toJSON: function () { return { x: 1 }; } })"),
    DifferentialCase("json-undefined-prop", "JSON.stringify({ a: undefined, b: 1 })"),
    DifferentialCase("json-negative-zero", "JSON.stringify(-0)"),
    DifferentialCase("json-reviver-delete", "JSON.stringify(JSON.parse('{\"a\":1,\"b\":2}', function (k, v) { return k === 'a' ? undefined : v; }))"),
    DifferentialCase("json-parse-whitespace", "JSON.parse(' [ 1, 2 ] ').length"),
    DifferentialCase("json-key-order", "JSON.stringify({ 2: 'b', 1: 'a', x: 'c' })"),

    // 集合
    DifferentialCase("map-nan-key", "(function () { var m = new Map(); m.set(NaN, 1); return m.get(NaN); })()"),
    DifferentialCase("map-negative-zero-key", "(function () { var m = new Map(); m.set(-0, 1); return m.get(0); })()"),
    DifferentialCase("map-reinsert-order", "(function () { var m = new Map([['a', 1], ['b', 2]]); m.delete('a'); m.set('a', 3); return Array.from(m.keys()).join(','); })()"),
    DifferentialCase("set-iteration-order", "Array.from(new Set([3, 1, 3, 2])).join(',')"),
    DifferentialCase("map-spread", "[...new Map([['a', 1]])].join('|')"),
    DifferentialCase("map-size-replace", "(function () { var m = new Map(); m.set('a', 1); m.set('a', 2); return m.size; })()"),
    DifferentialCase("weakmap-nonobject-key", "(function () { try { new WeakMap().set(1, 2); } catch (e) { return e.name; } })()"),
    DifferentialCase("map-entries", "JSON.stringify(Array.from(new Map([['a', 1]]).entries()))"),
    DifferentialCase("set-nan-dedupe", "new Set([NaN, NaN]).size"),
    DifferentialCase(
        "set-for-each-live-add",
        "(function () { var s = new Set([1]); var seen = [];" +
            " s.forEach(function (v) {" +
            "   seen.push(v); if (v === 1) s.add(2); if (v === 2) s.add(3); });" +
            " return seen.join(','); })()"
    ),
    DifferentialCase(
        "set-for-each-delete-readd",
        "(function () { var s = new Set([1, 2, 3]); var seen = [];" +
            " s.forEach(function (v) {" +
            "   seen.push(v); if (v === 1) { s.delete(2); s.add(2); } });" +
            " return seen.join(','); })()"
    ),
    DifferentialCase(
        "map-for-each-live",
        "(function () { var m = new Map([['a', 1], ['b', 2]]); var seen = [];" +
            " m.forEach(function (v, k) { seen.push(k + v);" +
            "   if (k === 'a') { m.delete('b'); m.set('b', 3); } });" +
            " return seen.join(',') + ':' + m.size; })()"
    ),
    DifferentialCase(
        "map-iterator-live",
        "(function () { var m = new Map([['a', 1], ['b', 2]]); var it = m.entries();" +
            " var seen = []; seen.push(it.next().value[0]);" +
            " m.delete('b'); m.set('c', 3);" +
            " var n; while (!(n = it.next()).done) seen.push(n.value[0]);" +
            " return seen.join(','); })()"
    ),

    // 函数、eval 与控制流
    DifferentialCase("fn-bind-length", "(function (a, b) {}).bind(null, 1).length"),
    DifferentialCase("fn-bind-name", "(function foo() {}).bind(null).name"),
    DifferentialCase("fn-bind-construct", "(function (x) { this.x = x; }).bind(null, 7).prototype === undefined"),
    DifferentialCase("fn-apply-arraylike", "(function (a, b) { return a + b; }).apply(null, { 0: 1, 1: 2, length: 2 })"),
    DifferentialCase("fn-arguments-mapping", "(function (a) { arguments[0] = 9; return a; })(1)"),
    DifferentialCase(
        "fn-name-parenthesized-target",
        "(function () { var fn; (fn) = function () {}; return fn.name; })()"
    ),
    DifferentialCase(
        "fn-arguments-define-descriptor",
        "(function (a) { Object.defineProperty(arguments, '0', { configurable: false });" +
            " a = 2; var d = Object.getOwnPropertyDescriptor(arguments, '0');" +
            " return [arguments[0], d.value, d.writable, d.enumerable, d.configurable].join(',');" +
            " })(1)"
    ),
    DifferentialCase(
        "fn-arguments-unmap-writable",
        "(function (a) { Object.defineProperty(arguments, '0', { writable: false });" +
            " a = 2; return arguments[0] + ',' + a; })(1)"
    ),
    DifferentialCase("fn-strict-callee", "(function () { 'use strict'; try { return arguments.callee; } catch (e) { return e.name; } })()"),
    DifferentialCase("eval-direct-value", "(function () { return eval('1 + 2'); })()"),
    DifferentialCase(
        "template-cache-site",
        "(function () { var seen = []; function tag(t) { seen.push(t); }" +
            " function f() { tag`a\${1}b`; } f(); f(); tag`a\${1}b`;" +
            " return (seen[0] === seen[1]) + ':' + (seen[1] === seen[2]); })()"
    ),
    DifferentialCase(
        "async-method-newline",
        "(function () { try { eval('({ async\\nfoo() { } })'); return 'none'; }" +
            " catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "async-shorthand-invalid",
        "(function () { try { eval('({ async async })'); return 'none'; }" +
            " catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "sloppy-unresolvable-rhs-global",
        "(function () { sloppyProbe = (globalThis.sloppyProbe = 5);" +
            " return globalThis.sloppyProbe; })()"
    ),
    DifferentialCase(
        "template-invalid-escape-cooked",
        "(function () { var cooked, raw;" +
            " (function (s) { cooked = s[0]; raw = s.raw[0]; })`\\01`;" +
            " return JSON.stringify([cooked === undefined, raw]); })()"
    ),
    DifferentialCase(
        "template-invalid-escape-untagged",
        "(function () { try { eval('`\\\\01`'); return 'none'; }" +
            " catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "annexb-if-fn-var-update",
        "(function () { var init, after;" +
            " eval('init = f; f = 123; after = f;" +
            " if (true) function f() {} else function _f() {}');" +
            " return [init === undefined, typeof after].join(':'); })()"
    ),
    DifferentialCase(
        "annexb-block-fn-skip-param",
        "(function (f) { { function f() {} } return f; })(123)"
    ),
    DifferentialCase(
        "annexb-call-target-runtime-error",
        "(function () { var fc = 0, gc = 0;" +
            " function f() { fc++; return {}; } function g() { gc++; }" +
            " try { f() = g(); return 'none'; }" +
            " catch (e) { return e.name + ':' + fc + ':' + gc; } })()"
    ),
    DifferentialCase(
        "for-in-legacy-initializer",
        "(function () { var effects = 0; for (var a = ++effects in {});" +
            " return effects; })()"
    ),
    DifferentialCase(
        "let-asi-single-statement",
        "(function () { var y = 0; for (var x in null) let\n y = 1; return y; })()"
    ),
    DifferentialCase(
        "template-raw-frozen",
        "(function () { var obj; function tag(t) { obj = t; }" +
            " function f() { tag`a\${1}b`; } f();" +
            " return [obj[0], obj.raw[0], Object.isFrozen(obj), Object.isFrozen(obj.raw)]" +
            "   .join('|'); })()"
    ),
    DifferentialCase("eval-strict-scope", "(function () { 'use strict'; eval('var x = 1;'); return typeof x; })()"),
    // 参数初始化阶段的 VariableEnvironment：eval 的 var 声明对函数体与参数闭包可见
    DifferentialCase(
        "params-eval-var-body",
        "(function (p = eval('var created = 1')) { return created; })()"
    ),
    DifferentialCase(
        "params-eval-var-closure",
        "(function (p = eval('var created = 1'), q = function () { return created; })" +
            " { return q(); })()"
    ),
    DifferentialCase(
        "params-eval-var-conflict",
        "(function () { try { (function (a = 1, b = eval('var a = 2')) {})();" +
            " return 'none'; } catch (e) { return e.name; } })()"
    ),
    // 函数体 var 与形参同名时以形参当前值初始化
    DifferentialCase("params-var-same-name", "(function (a = 5) { var a; return a; })()"),
    DifferentialCase(
        "params-var-arguments",
        "(function (a = 1) { var arguments; return arguments.length; })(5)"
    ),
    DifferentialCase("with-assignment", "(function () { var o = { x: 1 }; var x = 2; with (o) { x = 3; } return o.x + ':' + x; })()"),
    DifferentialCase("fn-default-length", "(function () { var f = function (a, b = 1, c) {}; return f.length; })()"),
    DifferentialCase("fn-new-prototype", "(function () { function F() {} F.prototype = { tag: 1 }; return new F().tag; })()"),
    DifferentialCase("fn-caller-strict", "(function () { 'use strict'; try { (function f() {}).caller; } catch (e) { return e.name; } })()"),

    // 强制转换与相等
    DifferentialCase("coerce-null-undefined-eq", "null == undefined"),
    DifferentialCase("coerce-null-zero-eq", "null == 0"),
    DifferentialCase("coerce-array-false", "[] == false"),
    DifferentialCase("coerce-array-zero", "[] == 0"),
    DifferentialCase("coerce-array-number", "[1] == 1"),
    DifferentialCase("coerce-array-string", "[1, 2] == '1,2'"),
    DifferentialCase("coerce-object-string", "({}) == '[object Object]'"),
    DifferentialCase("coerce-symbol-string", "String(Symbol('x'))"),
    DifferentialCase("coerce-date-plus", "+new Date(0)"),
    DifferentialCase("coerce-number-array", "Number([1])"),
    DifferentialCase("coerce-boolean-object", "Boolean(new Boolean(false))"),

    // URI 编解码与数字解析边界
    DifferentialCase(
        "uri-encode-reserved",
        "[encodeURI(';/?:@&=+\$,#'), encodeURIComponent(';')].join('|')"
    ),
    DifferentialCase(
        "uri-encode-lone-surrogate",
        "(function () { try { encodeURI('\\uD800'); return 'none'; }" +
            " catch (e) { return e.name; } })()"
    ),
    DifferentialCase(
        "uri-decode-reserved-case",
        "decodeURI('%3b%2f') + '|' + decodeURIComponent('%3b%2f')"
    ),
    DifferentialCase(
        "uri-decode-invalid",
        "(function () { try { decodeURIComponent('%C0%AF'); return 'none'; }" +
            " catch (e) { return e.name; } })()"
    ),
    // String.fromCharCode / fromCodePoint 的 ToUint16/ToIntegerOrInfinity 与 early error
    DifferentialCase(
        "string-fromcodepoint-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return String.fromCharCode() === '' && String.fromCharCode(65, 66); }); " +
            "push(function () { return [String.fromCharCode(0x1F600).charCodeAt(0), " +
            "String.fromCharCode(-1).charCodeAt(0), String.fromCharCode(NaN, 65.9, Infinity).length]" +
            ".join(','); }); " +
            "push(function () { return String.fromCharCode({ valueOf: function () { return 66; } }); }); " +
            "push(function () { try { String.fromCharCode(Symbol('s')); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { return String.fromCodePoint() === ''; }); " +
            "push(function () { var s = String.fromCodePoint(0x1F600); " +
            "return [s.length, s.codePointAt(0)].join(','); }); " +
            "push(function () { return String.fromCodePoint(65, 0x10FFFF).length; }); " +
            "push(function () { return [String.fromCodePoint(NaN).charCodeAt(0), " +
            "String.fromCodePoint(65.9), String.fromCodePoint(-0).charCodeAt(0)].join(','); }); " +
            "push(function () { try { String.fromCodePoint(0x110000); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { String.fromCodePoint(-1); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { String.fromCodePoint(Infinity); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { String.fromCodePoint(Symbol('s')); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "return out.join('|'); })()"
    ),
    // Date ISO 序列化的扩展年份与接收者校验
    DifferentialCase(
        "date-iso-extended-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return new Date(-1).toISOString(); }); " +
            "push(function () { return new Date(-62167219200000).toISOString(); }); " +
            "push(function () { return new Date(-62135596800000).toISOString(); }); " +
            "push(function () { return new Date(253402300799999).toISOString(); }); " +
            "push(function () { return new Date(253402300800000).toISOString(); }); " +
            "push(function () { return new Date(-1).toJSON() + '|' + String(new Date(NaN).toJSON()); }); " +
            "push(function () { try { Date.prototype.toISOString.call({}); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "return out.join('|'); })()"
    ),
    // Date UTC setter：NaN 恢复、溢出归一、可选参数与返回值
    DifferentialCase(
        "date-setter-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { var d = new Date(NaN); d.setUTCFullYear(2020); " +
            "return d.toISOString(); }); " +
            "push(function () { var d = new Date(NaN); d.setUTCMonth(1); " +
            "return String(d.getTime()); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 31)); d.setUTCMonth(1); " +
            "return d.toISOString(); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); d.setUTCDate(0); " +
            "return d.toISOString(); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1, 12, 30, 45, 500)); " +
            "d.setUTCHours(1); return d.toISOString(); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1, 12, 30, 45, 500)); " +
            "d.setUTCHours(undefined); return String(d.getTime()); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); " +
            "d.setUTCSeconds(59, 999); return d.toISOString(); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); " +
            "d.setUTCMilliseconds(1001); return d.toISOString(); }); " +
            "push(function () { var d = new Date(0); var r = d.setTime(8640000000000001); " +
            "return String(r) + '|' + String(d.getTime()); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); " +
            "d.setUTCFullYear(2021, 5, 15); return d.toISOString(); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); " +
            "d.setUTCFullYear(2021, undefined); return String(d.getTime()); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); var r = d.setUTCMonth(1); " +
            "return r + '|' + (r === d.getTime()); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); d.setUTCDate(NaN); " +
            "return String(d.getTime()); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 11, 31, 23, 59, 59, 999)); " +
            "d.setUTCFullYear(2021); return d.toISOString(); }); " +
            "push(function () { var d = new Date(Date.UTC(2020, 0, 1)); " +
            "return typeof d.setUTCYear; }); " +
            "return out.join('|'); })()"
    ),
    // URI 编解码矩阵：保留字符、转义集合、非法 UTF-8 与孤立代理项
    DifferentialCase(
        "uri-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return encodeURIComponent(\"a b!~*'()\"); }); " +
            "push(function () { return encodeURI('a b;/?:@&=+$,#') + '|' + " +
            "encodeURIComponent('a b;/?:@&=+$,#'); }); " +
            "push(function () { return encodeURIComponent('\\u00E9\\u4F60\\uD83D\\uDE00') + '|' + " +
            "decodeURIComponent(encodeURIComponent('\\u00E9\\u4F60\\uD83D\\uDE00')); }); " +
            "push(function () { return decodeURIComponent('%c3%a9') + '|' + " +
            "decodeURIComponent('%41%00') + '|' + decodeURIComponent('%2525'); }); " +
            "push(function () { return decodeURI('%2F%2f') + '|' + decodeURIComponent('%2F%2f'); }); " +
            "push(function () { try { encodeURIComponent('\\uD800'); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { encodeURIComponent('\\uDC00'); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { decodeURIComponent('%FF'); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { decodeURIComponent('%C3'); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { decodeURIComponent('%C3%28'); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { decodeURI('%E4%BD%A0'); return 'ok'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { decodeURIComponent('%'); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { decodeURIComponent('%2'); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { try { decodeURIComponent('%GG'); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { return encodeURIComponent(undefined) + '|' + " +
            "encodeURIComponent(null) + '|' + encodeURIComponent(123) + '|' + " +
            "encodeURIComponent(true); }); " +
            "push(function () { return encodeURI('\\uD83D\\uDE00') + '|' + " +
            "decodeURI('%F0%9F%98%80'); }); " +
            "push(function () { return encodeURIComponent(\"AZaz09-_.!~*'()\"); }); " +
            "push(function () { return encodeURI(\"!~*'()\") + '|' + " +
            "encodeURIComponent('#$&+,/:;=?@'); }); " +
            "return out.join('|'); })()"
    ),
    DifferentialCase(
        "parse-int-int32-radix",
        "[parseInt('11', Infinity), parseInt('11', NaN), parseInt('11', 4294967306)]" +
            ".join(',')"
    ),
    // parseInt/parseFloat/Number() 的空白、进制、Infinity 与符号零矩阵
    DifferentialCase(
        "number-parse-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return [parseInt('  +12px'), parseInt('0x10'), parseInt('-0xF'), " +
            "parseInt('08'), parseInt('z', 36), parseInt('10', 1), parseInt('10', 37), " +
            "parseInt('', 16), parseInt('0x', 16)].join(','); }); " +
            "push(function () { return [parseInt('11', 2), parseInt('11', 36), parseInt('1e3'), " +
            "parseInt('.5'), parseInt('0b11'), parseInt('0b11', 2)].join(','); }); " +
            "push(function () { return [1 / parseInt('-0'), 1 / parseInt('  -0 '), " +
            "String(parseInt('Infinity')), String(parseInt('abc'))].join(','); }); " +
            "push(function () { return [parseFloat(' 1.5e3x'), parseFloat('.5'), " +
            "1 / parseFloat('-0'), parseFloat('Infinity'), parseFloat('-Infinity')].join(','); }); " +
            "push(function () { return [String(parseFloat('1e')), String(parseFloat('1e+')), " +
            "String(parseFloat('.')), String(parseFloat('0x10')), parseFloat('Infinityx'), " +
            "parseFloat('+.5'), parseFloat('1_000')].join(','); }); " +
            "push(function () { return [parseFloat('\\u00A01'), parseFloat('\\u30001'), " +
            "parseFloat('\\uFEFF1'), parseFloat('1.2.3')].join(','); }); " +
            "push(function () { return [Number(''), Number('  '), Number('0x10'), Number('0b11'), " +
            "Number('0o11'), String(Number('1_000')), String(Number('+'))].join(','); }); " +
            "push(function () { return [Number('\\u00A0'), String(Number('Infinity')), " +
            "String(Number('-Infinity')), String(Number('infinity'))].join(','); }); " +
            "push(function () { return [1 / parseFloat('-0'), 1 / Number('-0'), " +
            "1 / parseInt('-0')].join(','); }); " +
            "push(function () { return [parseInt('0x10', 10), parseInt('0x10', 16), " +
            "parseInt('  0x10  '), parseInt('\\u00A012')].join(','); }); " +
            "push(function () { return [String(parseInt(undefined)), String(parseInt(null)), " +
            "String(parseInt(true)), String(parseInt([])), parseInt([12]), parseInt(['0x10'])].join(','); }); " +
            "push(function () { return [parseInt([12]), parseInt(['0x10']), " +
            "parseFloat([1.5])].join(','); }); " +
            "return out.join('|'); })()"
    ),
    DifferentialCase(
        "parse-float-ascii-digits",
        "[String(parseFloat('\\u0661\\u0662\\u0663')), parseFloat('1\\u0661')].join(',')"
    ),

    // eval 完成值与异常控制流
    DifferentialCase("eval-do-completion", "eval('do 1; while (false)')"),
    DifferentialCase("eval-for-break-completion", "eval('for (var i = 0;;) { if (i === 1) break; i++; }')"),
    DifferentialCase("finally-return", "(function () { try { return 1; } finally { return 2; } })()"),
    DifferentialCase("finally-break", "(function () { var c = 0; while (true) { try { c++; throw 1; } finally { break; } } return c; })()"),
    DifferentialCase("labeled-break-value", "eval('var s = 0; outer: for (var i = 0; i < 3; i++) { for (var j = 0; j < 3; j++) { s = 1; break outer; } } s')"),
    DifferentialCase("switch-fallthrough", "(function () { var s = ''; switch (1) { case 1: s += 'a'; case 2: s += 'b'; break; default: s += 'c'; } return s; })()"),
    DifferentialCase("while-completion", "eval('var i = 0; while (i < 2) { i++; }')"),
    DifferentialCase("const-tdz", "(function () { try { x; let x = 1; } catch (e) { return e.name; } })()"),
    DifferentialCase("delete-with-object", "(function () { var o = { x: 1 }; with (o) { delete x; } return o.hasOwnProperty('x'); })()"),

    // RegExp v 标志、Unicode 属性与代理项码元语义
    DifferentialCase("json-stringify-boxed-values", "JSON.stringify([new Number(8.5), new String('s'), new Boolean(true)])"),
    DifferentialCase("json-stringify-boxed-space", "JSON.stringify({ a: 1 }, null, new Number(2))"),
    DifferentialCase("json-stringify-boxed-replacer", "(function () { var num = new Number(10); num.toString = function () { return 'toString'; }; return JSON.stringify({ 10: 1, toString: 2 }, [num]); })()"),
    DifferentialCase("json-parse-reviver-prototype", "(function () { Object.prototype.b = 3; var obj = JSON.parse('{\"a\":1,\"b\":2}', function (k, v) { if (k === 'a') { delete this.b; } return v; }); delete Object.prototype.b; return obj.a + '|' + obj.hasOwnProperty('b') + '|' + obj.b; })()"),
    DifferentialCase("json-parse-reviver-nonconfigurable", "(function () { var arr = JSON.parse('[1,2]', function (k, v) { if (k === '0') { Object.defineProperty(this, '1', { configurable: false }); } if (k === '1') return 22; return v; }); return arr.join(','); })()"),
    // JSON 序列化/解析矩阵：toJSON、replacer、space、包装对象与 well-formed 输出
    DifferentialCase(
        "json-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "var obj = { a: 1, b: [true, null, 'x'], c: undefined, d: function () {}, e: Symbol('s') }; " +
            "push(function () { return JSON.stringify(obj); }); " +
            "push(function () { return JSON.stringify([undefined, function () {}, Symbol('s')]); }); " +
            "push(function () { return JSON.stringify({ x: NaN, y: Infinity, z: -Infinity }); }); " +
            "push(function () { return JSON.stringify({ a: 1, b: 2 }, ['b']); }); " +
            "push(function () { return JSON.stringify({ a: 1, b: 2 }, function (k, v) { " +
            "return typeof v === 'number' ? v * 2 : v; }); }); " +
            "push(function () { return JSON.stringify({ toJSON: function () { return { x: 1 }; } }); }); " +
            "push(function () { return JSON.stringify({ a: 1 }, null, 2); }); " +
            "push(function () { return JSON.stringify({ a: 1 }, null, '\\t'); }); " +
            "push(function () { return JSON.stringify({ a: 1 }, null, 100); }); " +
            "push(function () { return JSON.stringify('\\uD800'); }); " +
            "push(function () { return JSON.stringify(new Number(8.5)) + '|' + " +
            "JSON.stringify(new String('s')) + '|' + JSON.stringify(new Boolean(true)); }); " +
            "push(function () { return JSON.stringify(new Date(0)); }); " +
            "push(function () { return JSON.stringify({ 2: 'b', 1: 'a', x: 'c' }); }); " +
            "push(function () { return JSON.parse('{\"a\":1}').a; }); " +
            "push(function () { return JSON.parse('{\"a\":1}', function (k, v) { " +
            "return typeof v === 'number' ? v + 1 : v; }).a; }); " +
            "push(function () { return JSON.stringify(JSON.parse('1e400')); }); " +
            "push(function () { return JSON.stringify(-0); }); " +
            "push(function () { return JSON.stringify({ u: '\\u2028\\u2029' }); }); " +
            "return out.join('|'); })()"
    ),
    // JSON 严格语法：数字/字符串/尾随逗号负例、空白、重复键、__proto__ 与 reviver
    DifferentialCase(
        "json-strict-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return [JSON.parse('0'), JSON.parse('-0'), 1 / JSON.parse('-0'), " +
            "JSON.parse('1e2'), JSON.parse('1E+2'), JSON.parse('1e-2')].join(','); }); " +
            "push(function () { var bad = ['01', '.5', '+1', '1.', '1e', '1e+', '-', '0x10', " +
            "'Infinity', 'NaN']; var r = []; for (var i = 0; i < bad.length; i++) { " +
            "try { JSON.parse(bad[i]); r.push('ok'); } catch (e) { r.push(e.name); } } " +
            "return r.join(','); }); " +
            "push(function () { var rawNewline = '[\"a' + String.fromCharCode(10) + 'b\"]'; " +
            "var bad = [\"'a'\", '{a:1}', '[1,]', '{\"a\":1,}', '[1 2]', '{\"a\" 1}', rawNewline, " +
            "'\"\\\\x41\"', '\"\\\\u12\"']; var r = []; for (var i = 0; i < bad.length; i++) { " +
            "try { JSON.parse(bad[i]); r.push('ok'); } catch (e) { r.push(e.name); } } " +
            "return r.join(','); }); " +
            "push(function () { return JSON.stringify(JSON.parse('\"\\\\u0041\\\\n\\\\t\\\\/\\\\b\\\\f\"'))" +
            " + '|' + JSON.parse('\"\\\\uD83D\\\\uDE00\"').length; }); " +
            "push(function () { var s = JSON.parse('\"\\\\uD800\"'); " +
            "return s.length + '|' + s.charCodeAt(0).toString(16); }); " +
            "push(function () { return JSON.parse(' \\t\\r\\n1 \\t\\r\\n') + '|'; }); " +
            "push(function () { try { JSON.parse('\\u00A01'); return 'ok'; } catch (e) { " +
            "return e.name; } }); " +
            "push(function () { return JSON.parse('{\"a\":1,\"a\":2}').a; }); " +
            "push(function () { var o = JSON.parse('{\"__proto__\":{\"x\":1},\"a\":2}'); " +
            "return Object.prototype.hasOwnProperty.call(o, '__proto__') + '|' + o.a + '|' + o.x + '|' + " +
            "(Object.getPrototypeOf(o) === Object.prototype); }); " +
            "push(function () { var log = []; " +
            "JSON.parse('{\"a\":1,\"b\":{\"c\":2}}', function (k, v) { log.push(k); return v; }); " +
            "return log.join(','); }); " +
            "push(function () { return JSON.stringify({ a: 1 }, null, 'abcdefghijklm'); }); " +
            "push(function () { return JSON.stringify({ a: 1, b: 2 }, ['b', 'b', 1]); }); " +
            "push(function () { return JSON.stringify({ x: Symbol('s'), y: 1 }); }); " +
            "push(function () { var log = []; var o = { " +
            "get a() { log.push('a'); return 1; }, get b() { log.push('b'); return 2; } }; " +
            "JSON.stringify(o); return log.join(','); }); " +
            "push(function () { return JSON.stringify(-0) + '|' + JSON.stringify({ v: -0 }) + '|' + " +
            "JSON.stringify([-0]); }); " +
            "push(function () { return JSON.stringify('\\u2028\\u2029').length + '|' + " +
            "JSON.stringify('\\uD800').length; }); " +
            "push(function () { var a = [1]; a.toJSON = function () { return 'A'; }; " +
            "return JSON.stringify(a); }); " +
            "push(function () { var o = { toJSON: function (key) { return key; } }; " +
            "return JSON.stringify({ x: o }); }); " +
            "return out.join('|'); })()"
    ),
    DifferentialCase("regexp-nonunicode-x-identity", "[/\\x/.test('x'), /[\\x]/.test('x'), /\\u/.test('u'), /\\xA0/.test('\\u00A0')].join(',')"),
    DifferentialCase("regexp-nonunicode-split-identity", "JSON.stringify('x'.split(/\\x/))"),
    DifferentialCase("regexp-source-empty", "new RegExp('').source"),
    DifferentialCase("regexp-source-prototype", "RegExp.prototype.source"),
    DifferentialCase(
        "array-methods-fuzz",
        """
        (function () {
          var values = [0, 1, 2, 'x', undefined, true, null];
          var methods = ['slice', 'splice', 'concat', 'map', 'filter', 'indexOf', 'lastIndexOf',
            'includes', 'join', 'reverse', 'copyWithin', 'fill', 'sort', 'push', 'pop',
            'shift', 'unshift'];
          var seed = 97531;
          function rnd(n) { seed = (seed * 48271) % 2147483647; return seed % n; }
          function snap(a) {
            var parts = [];
            for (var i = 0; i < a.length; i++) { parts.push((i in a) ? ('v' + String(a[i])) : 'h'); }
            return a.length + ':[' + parts.join(',') + ']';
          }
          var out = [];
          for (var i = 0; i < 100; i++) {
            var len = rnd(7);
            var array = new Array(len);
            for (var j = 0; j < len; j++) { if (rnd(3) > 0) { array[j] = values[rnd(values.length)]; } }
            var name = methods[rnd(methods.length)];
            var args = [];
            var argc = rnd(4);
            for (var k = 0; k < argc; k++) { args.push(values[rnd(values.length)]); }
            var result;
            try {
              if (name === 'map') {
                result = array.map(function (value, index) { return index; });
              } else if (name === 'filter') {
                result = array.filter(function (value) { return value !== undefined; });
              } else if (name === 'sort') {
                result = array.sort();
              } else if (name === 'join') {
                result = array.join(args.length > 0 ? args[0] : '-');
              } else {
                result = array[name].apply(array, args);
              }
            } catch (e) {
              out.push(i + ':' + name + ':THROW:' + e.name + ':' + snap(array));
              continue;
            }
            out.push(i + ':' + name + ':' + snap(Array.isArray(result) ? result : [result]) +
              ':' + snap(array));
          }
          return out.join(';');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "regexp-string-methods-fuzz",
        """
        (function () {
          var atoms = ['a', 'b', 'c', '.', '[ab]', '[^a]', '[a-c]', '\\d', '\\w', '\\s',
            '\\D', '(a)', '(b|c)', '(?:ab)', 'a*', 'b+', 'c?', 'a{2}', 'b{1,2}',
            '^', String.fromCharCode(36), '\\b', '\\B', '(a|b)', '(a*)'];
          var flagsPool = ['', 'g', 'i', 'm', 's', 'gi', 'gim', 'u', 'gu', 'iu', 'y', 'gy', 'd', 'gd'];
          var inputs = ['', 'a', 'ab', 'ba', 'abc', 'aaa', 'a b', 'A', '1a2', 'a\nb', ' ',
            'cab', 'bbb', 'ac'];
          var seed = 13579;
          function rnd(n) { seed = (seed * 48271) % 2147483647; return seed % n; }
          var out = [];
          function replacement() {
            return '[' + Array.prototype.slice.call(arguments, 0, arguments.length - 2).join('|') + ']';
          }
          for (var i = 0; i < 80; i++) {
            var parts = [];
            var count = 1 + rnd(4);
            for (var j = 0; j < count; j++) { parts.push(atoms[rnd(atoms.length)]); }
            var src = parts.join('');
            var flags = flagsPool[rnd(flagsPool.length)];
            var input = inputs[rnd(inputs.length)];
            try {
              var replaced = input.replace(new RegExp(src, flags), replacement);
              var split = input.split(new RegExp(src, flags), 5);
              var matched = input.match(new RegExp(src, flags));
              var searched = input.search(new RegExp(src, flags));
              out.push(i + ':' + src + ':' + flags + ':' + JSON.stringify(input) + ':' +
                JSON.stringify(replaced) + '|' + JSON.stringify(split) + '|' +
                JSON.stringify(matched) + '|' + searched);
            } catch (e) {
              out.push(i + ':' + src + ':' + flags + ':THROW:' + e.name);
            }
          }
          return out.join(';');
        })()
        """.trimIndent()
    ),
    DifferentialCase(
        "regexp-fuzz-ascii",
        """
        (function () {
          var atoms = ['a', 'b', 'c', '.', '[ab]', '[^a]', '[a-c]', '\\d', '\\w', '\\s',
            '\\D', '(a)', '(b|c)', '(?:ab)', 'a*', 'b+', 'c?', 'a{2}', 'b{1,2}',
            '^', String.fromCharCode(36), '\\b', '\\B', '(a|b)', '(a*)'];
          var flagsPool = ['', 'g', 'i', 'm', 's', 'gi', 'gim', 'u', 'gu', 'iu', 'y', 'gy', 'd', 'gd'];
          var inputs = ['', 'a', 'ab', 'ba', 'abc', 'aaa', 'a b', 'A', '1a2', 'a\nb', ' ',
            'cab', 'bbb', 'ac'];
          var seed = 24680;
          function rnd(n) { seed = (seed * 48271) % 2147483647; return seed % n; }
          var out = [];
          for (var i = 0; i < 120; i++) {
            var parts = [];
            var count = 1 + rnd(4);
            for (var j = 0; j < count; j++) { parts.push(atoms[rnd(atoms.length)]); }
            var src = parts.join('');
            var flags = flagsPool[rnd(flagsPool.length)];
            var input = inputs[rnd(inputs.length)];
            try {
              var re = new RegExp(src, flags);
              var match = re.exec(input);
              out.push(i + ':E:' + src + ':' + flags + ':' + JSON.stringify(input) + ':' +
                (match ? JSON.stringify(Array.prototype.slice.call(match)) + '@' + match.index +
                  ':' + re.lastIndex : 'null'));
            } catch (e) {
              out.push(i + ':E:' + src + ':' + flags + ':THROW:' + e.name);
            }
            if (flags.indexOf('g') >= 0 || flags.indexOf('y') >= 0) {
              try {
                var loopRe = new RegExp(src, flags);
                var found = [];
                var m;
                var guard = 0;
                while ((m = loopRe.exec(input)) && guard++ < 20) {
                  found.push(m[0] + '@' + m.index + ':' + loopRe.lastIndex);
                  if (m[0] === '') { loopRe.lastIndex++; }
                }
                out.push(i + ':L:' + found.join(','));
              } catch (e) {
                out.push(i + ':L:THROW:' + e.name);
              }
            }
          }
          return out.join(';');
        })()
        """.trimIndent()
    ),
    DifferentialCase("regexp-source-escape", "JSON.stringify(new RegExp('a/b' + String.fromCharCode(10)).source)"),
    DifferentialCase("regexp-source-escaped-slash", "JSON.stringify(new RegExp('\\/').source)"),
    DifferentialCase("regexp-source-invalid", "(function () { try { Object.getOwnPropertyDescriptor(RegExp.prototype, 'source').get.call({}); return 'none'; } catch (e) { return e.name; } })()"),
    DifferentialCase("regexp-flag-getters-prototype", "JSON.stringify([RegExp.prototype.global, RegExp.prototype.sticky, RegExp.prototype.unicode])"),
    DifferentialCase("regexp-v-intersection", "'abcdef'.match(/[[a-f]&&[^aeiou]]+/gv).join('')"),
    DifferentialCase("regexp-v-difference", "'abcdef'.replace(/[[a-f]--[aeiou]]/gv, '_')"),
    DifferentialCase("regexp-v-q", "'zab'.search(/[\\q{ab}]/v)"),
    DifferentialCase("regexp-v-string-difference", "'0123456789'.replace(/[\\d--\\q{0|2|4}]+/gv, '')"),
    DifferentialCase("regexp-basic-emoji", "/\\p{Basic_Emoji}/v.test('\\u231A')"),
    DifferentialCase("regexp-rgi-emoji", "/\\p{RGI_Emoji}/v.test('\\u{1F1E7}\\u{1F1EA}')"),
    DifferentialCase("regexp-property-i-fold", "/\\p{Lu}/iu.test('a')"),
    DifferentialCase("regexp-property-P-i-fold", "/\\P{Lu}/iu.test('A')"),
    DifferentialCase("regexp-unicode-space", "/\\s/u.test('\\u3000')"),
    DifferentialCase("regexp-unicode-space-class", "/[\\s]/u.test('\\u1680')"),
    DifferentialCase("regexp-surrogate-u", "/\\udf06/u.test('\\ud834\\udf06')"),
    DifferentialCase("regexp-surrogate-nonu", "/\\udf06/.test('\\ud834\\udf06')"),
    DifferentialCase("regexp-duplicate-named", "'b'.match(/(?<x>a)|(?<x>b)/).groups.x"),
    DifferentialCase("regexp-modifier-i", "/(?i:a)/.test('A')"),
    DifferentialCase("regexp-lookbehind-greedy", "'fooo'.match(/(?<=f(o+))o/)[1]"),
    // u/s 标志与 Unicode 简单大小写折叠的边界语义
    DifferentialCase("regexp-dotall-fallback", "[/a.b/s.exec('a\\nb')[0], /a.b/s.test('a\\rb'), /a.b/.test('a\\nb'), /(?<=a.b)/s.test('a\\nb')].join(',')"),
    DifferentialCase("regexp-astral-dot-u", "[/^(.)$/u.exec(String.fromCodePoint(128512))[0].length, /^.{1}$/u.test(String.fromCodePoint(128512)), /^.{2}$/u.test(String.fromCodePoint(128512))].join(',')"),
    DifferentialCase("regexp-astral-quantifier-u", "String.fromCodePoint(128512).repeat(2).match(/\\u{1F600}{2}/u)[0].length"),
    DifferentialCase("regexp-simple-fold-iu", "[/\\u017F/iu.test('s'), /\\u212A/iu.test('k'), /k/iu.test('\\u212A'), /s/iu.test('\\u017F'), /i/iu.test('\\u0130'), /\\u0130/iu.test('i')].join(',')"),
    DifferentialCase("regexp-canonicalize-nonu", "[/k/i.test('\\u212A'), /s/i.test('\\u017F')].join(',')"),
    DifferentialCase("regexp-sticky-lastindex", "(function () { var r = /a/y; r.lastIndex = 1; var m = r.exec('ba'); var first = r.lastIndex; var second = r.test('ba'); return [m[0], first, second, r.lastIndex].join(','); })()"),
    DifferentialCase("regexp-multiline-anchors", "['a\\nb'.match(/^b/m)[0], /a$/m.test('a\\nb'), 'a\\r\\nb'.match(/a$/m)[0]].join(',')"),
    DifferentialCase("regexp-backreference-i", "/(a)\\1/i.exec('aA')[0]"),
    DifferentialCase("regexp-boundary-B", "[/\\B/.test('a'), /a\\B/.test('a'), /\\Ba/.test('a'), /a\\B/.test('ab')].join(',')"),
    DifferentialCase("regexp-empty-negated-class", "[/[^]/.test('\\n'), /[^]/u.test('\\n'), /[^]/.test('')].join(',')"),
    DifferentialCase("regexp-property-astral", "[/\\p{Script=Deseret}/u.test(String.fromCodePoint(66560)), /\\p{Script=Deseret}/u.test('a')].join(',')"),
    DifferentialCase("regexp-fold-class-nonu", "[/[k]/i.test('\\u212A'), /[a-z]/i.test('\\u212A'), /[a-z]/i.test('\\u017F'), /[a-z]/i.test('K'), /[\\u00DF]/i.test('\\u1E9E')].join(',')"),
    DifferentialCase("regexp-fold-class-iu", "[/[k]/iu.test('\\u212A'), /[a-z]/iu.test('\\u212A'), /[a-z]/iu.test('\\u017F'), /[k]/iu.test('K'), /[\\u00DF]/iu.test('\\u1E9E')].join(',')"),
    DifferentialCase("regexp-fold-word-nonu", "[/\\w/i.test('\\u212A'), /\\w/i.test('\\u017F'), /\\w/i.test('\\u03C2'), /\\B/i.test('\\u212A'), /\\b/i.test('a\\u212A')].join(',')"),
    DifferentialCase("regexp-fold-word-iu", "[/\\w/iu.test('\\u212A'), /\\w/iu.test('\\u017F'), /\\w/iu.test('\\u03C2'), /\\B/iu.test('\\u212A'), /\\b/iu.test('a\\u212A')].join(',')"),
    // String.prototype.replace/GetSubstitution 的替换模板边界
    DifferentialCase(
        "replace-substitution-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return 'abc'.replace(/b/, '$&$&'); }); " +
            "push(function () { return 'abc'.replace(/b/, '$`'); }); " +
            "push(function () { return 'abc'.replace(/b/, \"$'\"); }); " +
            "push(function () { return 'abc'.replace(/b/, '$$'); }); " +
            "push(function () { return 'abc'.replace(/(b)/, '$1$1'); }); " +
            "push(function () { return 'abc'.replace(/(b)/, '$2'); }); " +
            "push(function () { return 'abc'.replace(/(b)/, '$10'); }); " +
            "push(function () { return 'abc'.replace(/(b)/, '$0'); }); " +
            "push(function () { return 'abc'.replace(/(?<x>b)/, '$<x>$<y>'); }); " +
            "push(function () { return 'abc'.replace(/(?<x>b)/, '$<x>'); }); " +
            "push(function () { return 'abc'.replace(/b/, '$<x>'); }); " +
            "push(function () { return 'abc'.replace(/x*/, '-'); }); " +
            "push(function () { return 'abc'.replace(/x*/g, '-'); }); " +
            "push(function () { return 'a-b'.replaceAll('-', '$&$&'); }); " +
            "push(function () { return 'a1b2'.replace(/(\\d)/g, function (m, p1, off) { " +
            "return p1 + '@' + off; }); }); " +
            "push(function () { return 'a1'.replace(/(?<d>\\d)/, function () { " +
            "var g = arguments[arguments.length - 1]; return g && g.d; }); }); " +
            "push(function () { try { 'a'.matchAll(/a/); return 'none'; } " +
            "catch (e) { return e.name; } }); " +
            "push(function () { return 'abc'.replace(/b/, '\\$1'); }); " +
            "return out.join('|'); })()"
    ),
    // String 与 RegExp 的替换/分割/matchAll/lastIndex 交互矩阵
    DifferentialCase(
        "regexp-replace-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "var s = 'a1b2c3'; " +
            "push(function () { return s.replace(/\\d/g, '[$&]'); }); " +
            "push(function () { return s.replace(/(\\d)/g, function (m, p1, off, str) { " +
            "return p1 + ':' + off + ':' + str.length; }); }); " +
            "push(function () { return s.replace(/(?<d>\\d)/g, '$<d>$<d>'); }); " +
            "push(function () { return s.replace(/(\\d)/g, '$1$`$\\'$$'); }); " +
            "push(function () { return s.replace('', 'X'); }); " +
            "push(function () { return 'abc'.replace(/b/g, '$$'); }); " +
            "push(function () { return s.split(/(\\d)/).join('|'); }); " +
            "push(function () { return s.split(/(\\d)/, 3).join('|'); }); " +
            "push(function () { return 'a,b;c'.split(/[,;]/).join('|'); }); " +
            "push(function () { return 'abc'.match(/(?<x>b)/).groups.x; }); " +
            "push(function () { return 'a1b2'.match(/\\d/g).join(','); }); " +
            "push(function () { var r = /\\d/g; r.lastIndex = 2; var m = r.exec('a1b2'); " +
            "return m[0] + '@' + m.index + ':' + r.lastIndex; }); " +
            "push(function () { var r = /\\d/y; r.lastIndex = 1; var m = r.exec('a1b2'); " +
            "return m ? m[0] : 'null'; }); " +
            "push(function () { var r = /\\d/gy; r.lastIndex = 1; var a = r.exec('a1b2'); " +
            "var b = r.exec('a1b2'); return a[0] + ':' + b[0] + ':' + r.lastIndex; }); " +
            "push(function () { return Array.from('a1b2'.matchAll(/\\d/g), function (m) { " +
            "return m[0] + '@' + m.index; }).join(','); }); " +
            "push(function () { return 'a1b2'.replaceAll(/\\d/g, '#'); }); " +
            "push(function () { return 'a1b2'.replaceAll('b', '#'); }); " +
            "push(function () { return 'aXbXc'.split(/x/i).join('|'); }); " +
            "push(function () { return 'a'.replace(/a/, function () { return '$&'; }); }); " +
            "push(function () { return 'abc'.replace(/(?:)/g, '-'); }); " +
            "push(function () { return 'a1b'.search(/\\d/); }); " +
            "push(function () { return 'abc'.replace(/(b)/, '$2'); }); " +
            "push(function () { return 'abc'.replace(/(b)/, '$0'); }); " +
            "return out.join('|'); })()"
    ),
    // RegExp d 标志：indices 捕获区间、命名组、可选组与全局重算
    DifferentialCase(
        "regexp-indices-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { var m = /(a)(?<n>b)/d.exec('xab'); return JSON.stringify([m.index, " +
            "m.indices[0], m.indices[1], m.indices[2], m.indices.groups.n]); }); " +
            "push(function () { var m = /(a)?(b)/d.exec('b'); " +
            "return JSON.stringify([m.indices[1] === undefined, m.indices[2]]); }); " +
            "push(function () { var r = /(?<x>a)|(?<x>b)/d; var m = r.exec('b'); " +
            "return JSON.stringify(m.indices.groups.x); }); " +
            "push(function () { var m = /(?=(a))/d.exec('a'); return JSON.stringify(m.indices); }); " +
            "push(function () { var r = /a/dg; r.exec('aa'); var first = r.indices[0]; r.exec('aa'); " +
            "return JSON.stringify([first, r.indices[0]]); }); " +
            "push(function () { return /a/d.hasIndices + '|' + /a/.hasIndices + '|' + /a/d.flags; }); " +
            "push(function () { var m = /(a)|(b)/d.exec('b'); " +
            "return JSON.stringify([m.indices[1], m.indices[2]]); }); " +
            "push(function () { var m = /(?<y>z)?(a)/d.exec('a'); " +
            "return (m.indices.groups.y === undefined) + '|' + " +
            "(Object.getPrototypeOf(m.indices.groups) === null); }); " +
            "push(function () { return JSON.stringify('ab'.match(/a(b)/d).indices); }); " +
            "push(function () { var r = /(a)/d; var m = r.exec('a'); return m.indices.length; }); " +
            "push(function () { return JSON.stringify(/(?<=a)(b)/d.exec('ab').indices); }); " +
            "push(function () { return String(/a/d.exec('b')); }); " +
            "push(function () { var m = /((a)(b))?/d.exec(''); return JSON.stringify(m.indices); }); " +
            "push(function () { var m = /a/d; var r = m.exec('a'); return m === m && r.index === 0; }); " +
            "return out.join('|'); })()"
    ),
    // 正则匹配矩阵：u/s/m 标志、astral、命名组、断言、转义与回溯
    DifferentialCase(
        "regexp-match-matrix",
        "(function () { var cases = [" +
            "[/./, '\\n'], [/./s, '\\n'], [/./u, '\uD83D\uDE00'], [/.{1}/u, '\uD83D\uDE00'], " +
            "[/.{2}/u, '\uD83D\uDE00'], [/^.$/u, '\uD83D\uDE00'], [/[^]/, '\\n'], [/\\s/u, '\\u3000'], " +
            "[/\\S/u, '\\u3000'], [/\\w/iu, '\\u212A'], [/\\W/iu, '\\u212A'], " +
            "[/\\b/iu, 'a\\u212A'], [/\\B/iu, 'a\\u212A'], [/\\u{1F600}/u, '\uD83D\uDE00'], " +
            "[/[\\u{1F600}-\\u{1F64F}]/u, '\uD83D\uDE00'], [/(?<x>a)\\k<x>/, 'aa'], " +
            "[/(?<=a+)b/, 'aaab'], [/(?<!a)b/, 'cb'], [/(?i:a)b/, 'Ab'], " +
            "[/(?s:.)/, '\\n'], [/(?m:^b)/, 'a\\nb'], [/a{2,3}/, 'aaa'], [/(a|ab)c/, 'abc'], " +
            "[/(?=(a+))a/, 'aaa'], [/[\\s\\S]/, '\\n'], [/\\0/, '\\0'], [/[\\b]/, '\\b'], " +
            "[/\\cA/, '\\u0001'], [/\\x41/, 'A'], [/\\u0041/, 'A'], [/$/m, 'a\\nb'], " +
            "[/^/m, 'a\\nb'], [/\\p{Script=Greek}/u, '\\u03B1'], [/\\p{Lu}/iu, 'a'], " +
            "[/[\\d]/, '5'], [/\\8/, '8'], [/[\\8]/, '8'], [/\\1/, 'a']]; " +
            "var out = []; " +
            "for (var i = 0; i < cases.length; i++) { var re = cases[i][0]; var m = re.exec(cases[i][1]); " +
            "out.push(re.source + '/' + re.flags + ':' + (m ? m[0] + '@' + m.index : 'null')); } " +
            "return out.join('|'); })()"
    ),

    // Unicode 标识符与尾调用结果
    DifferentialCase("ident-astral-escape", "(function () { var \\u{1D49C} = 1; return \\u{1D49C}; })()"),
    DifferentialCase("tco-call-count", "(function () { var c = 0; function f(n) { 'use strict'; if (n === 0) { c++; return; } return f(n - 1); } f(1000); return c; })()"),
    // String.normalize 与 Array.prototype.sort 的边界语义
    DifferentialCase(
        "misc-string-sort-matrix",
        "(function () { var out = []; " +
            "function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } } " +
            "push(function () { return ['\\u00E9'.normalize('NFD').length, " +
            "'\\u212B'.normalize('NFC') === '\\u00C5', '\\uFB01'.normalize('NFKC'), " +
            "'\\uAC00'.normalize('NFD').length].join(','); }); " +
            "push(function () { return '\\uD800'.normalize('NFC').charCodeAt(0); }); " +
            "push(function () { return ''.normalize(undefined) === ''; }); " +
            "push(function () { var r = []; " +
            "try { ''.normalize('XXX'); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "try { ''.normalize(null); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "r.push(String.prototype.normalize.call(5)); " +
            "try { String.prototype.normalize.call(null); r.push('ok'); } catch (e) { r.push(e.name); } " +
            "r.push(String.prototype.normalize.length); " +
            "return r.join(','); }); " +
            "push(function () { var log = []; " +
            "''.normalize({ toString: function () { log.push('t'); return 'NFC'; } }); " +
            "return log.join(','); }); " +
            "push(function () { return [10, 1, 2].sort().join(','); }); " +
            "push(function () { return JSON.stringify([3, undefined, 1, , 2].sort()); }); " +
            "push(function () { return [3, 1, 2].sort(function () { return NaN; }).join(','); }); " +
            "push(function () { var list = [{ k: 1, id: 'a' }, { k: 1, id: 'b' }, " +
            "{ k: 0, id: 'c' }]; list.sort(function (x, y) { return x.k - y.k; }); " +
            "return list.map(function (o) { return o.id; }).join(','); }); " +
            "push(function () { var a = [3, , 1]; a.sort(); " +
            "return Object.keys(a).join(',') + '|' + a.length; }); " +
            "push(function () { var obj = { length: 3, 0: 3, 1: 1, 2: 2 }; " +
            "Array.prototype.sort.call(obj); return obj[0] + ',' + obj[1] + ',' + obj[2]; }); " +
            "return out.join('|'); })()"
    ),

    // 数组 species、迭代器关闭、对象展开、属性描述符、类字段与 Map/Set 构造语义
    DifferentialCase(
        "array-species-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  class A extends Array {}
  class B extends Array { static get [Symbol.species]() { return Array; } }
  push(function () { return new A(1, 2, 3).map(function (x) { return x * 2; }).constructor === A; });
  push(function () { return new B(1, 2, 3).map(function (x) { return x * 2; }).constructor === Array; });
  push(function () { return new A(1, 2).concat([3]).constructor === A; });
  push(function () { return new B(1, 2).concat([3]).constructor === Array; });
  push(function () { return new A(1, 2, 3).slice(1).constructor === A; });
  push(function () { return new B(1, 2, 3).slice(1).constructor === Array; });
  push(function () { return new A(1, 2, 3).splice(1, 1).constructor === A; });
  push(function () { return new B(1, 2, 3).splice(1, 1).constructor === Array; });
  push(function () { return new A(1, 2, 3).filter(function () { return true; }).constructor === A; });
  push(function () { return Array.prototype.map.call({ length: 1, 0: 5 }, function (x) { return x; }).constructor === Array; });
  push(function () {
    var log = [];
    class C extends Array { static get [Symbol.species]() { log.push('s'); return Array; } }
    var arr = new C(1, 2);
    var r = arr.map(function (x) { log.push('m'); return x; });
    return (r.constructor === Array) + '|' + log.join(',');
  });
  push(function () {
    class D extends Array { static get [Symbol.species]() { return null; } }
    return new D(1).map(function (x) { return x; }).constructor === Array;
  });
  push(function () {
    class E extends Array { static get [Symbol.species]() { return 5; } }
    return new E(1).map(function (x) { return x; });
  });
  push(function () {
    class F extends Array { static get [Symbol.species]() { return function () { return { length: 0 }; }; } }
    var r = new F(1, 2).map(function (x) { return x; });
    return Object.prototype.toString.call(r) + ':' + r.length;
  });
  return out.join(';');
})()""",
    ),
    DifferentialCase(
        "iterator-close-matrix",
        """(function () {
  var out = [];
  function makeIterable(log, values, throwAt) {
    return {
      [Symbol.iterator]: function () {
        var i = 0;
        return {
          next: function () {
            log.push('n' + i);
            if (throwAt === i) throw new Error('next');
            if (i >= values.length) return { done: true };
            return { value: values[i++], done: false };
          },
          return: function () { log.push('r'); return { done: true }; }
        };
      }
    };
  }
  var log1 = [];
  try { var [a = (function () { throw new Error('def'); })(), b] = makeIterable(log1, [1, 2]); } catch (e) {}
  out.push('def:' + log1.join(','));
  var log2 = [];
  for (var x of makeIterable(log2, [1, 2, 3])) { if (x === 1) break; }
  out.push('break:' + log2.join(','));
  var log3 = [];
  try { for (var y of makeIterable(log3, [1, 2, 3])) { if (y === 1) throw new Error('body'); } } catch (e) {}
  out.push('throw:' + log3.join(','));
  var log4 = [];
  var [p, ...q] = makeIterable(log4, [1, 2, 3]);
  out.push('rest:' + p + ':' + q.join('') + ':' + log4.join(','));
  var log5 = [];
  var [h1, , h3] = makeIterable(log5, [9, 8, 7]);
  out.push('hole:' + h1 + ',' + h3 + ':' + log5.join(','));
  var log6 = [];
  try { var [z] = makeIterable(log6, [], 0); } catch (e) {}
  out.push('nextthrow:' + log6.join(','));
  var log7 = [];
  try { var [w] = makeIterable(log7, [1, 2]); } catch (e) {}
  out.push('ok:' + log7.join(','));
  var log8 = [];
  outer: for (var u of makeIterable(log8, [0, 1, 2])) {
    for (var v of makeIterable(log8, [0, 1, 2])) {
      if (v === 1) continue outer;
    }
  }
  out.push('labeled:' + log8.join(','));
  var [s1, s2, ...s3] = 'ab\uD83D\uDE00';
  out.push('string:' + s1 + s2 + s3.length + ':' + s3[0].length);
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "object-spread-matrix",
        """(function () {
  var out = [];
  var log = [];
  var src = {};
  Object.defineProperty(src, 'a', { enumerable: true, get: function () { log.push('a'); return 1; } });
  Object.defineProperty(src, 'b', { enumerable: false, value: 2 });
  var sym = Symbol('s');
  src[sym] = 3;
  var copy = { z: 0, ...src };
  out.push(JSON.stringify(copy) + ':' + copy.b + ':' + copy[sym] + ':' + log.join(','));
  var orderLog = [];
  var o1 = { get x() { orderLog.push('x'); return 1; } };
  var o2 = { get y() { orderLog.push('y'); return 2; } };
  var merged = { ...o1, ...o2 };
  out.push(merged.x + merged.y + ':' + orderLog.join(','));
  var arrLog = [];
  var ib = {
    [Symbol.iterator]: function () {
      var i = 0;
      return {
        next: function () {
          arrLog.push('n' + i);
          if (i >= 2) return { done: true };
          i++;
          return { value: i, done: false };
        },
        return: function () { arrLog.push('r'); return { done: true }; }
      };
    }
  };
  var spread = [...ib];
  out.push(spread.join(',') + ':' + arrLog.join(','));
  function rest(...args) { return args.length + ':' + args.join(''); }
  out.push(rest(1, 2, 3));
  function mixed(a, ...b) { return a + ':' + b.join(''); }
  out.push(mixed(...[9, 8, 7]));
  var inner = { p: 1, q: 2 };
  var nested = { ...inner, p: 3, ...{ r: 4 } };
  out.push(JSON.stringify(nested));
  var proto = { inherited: 1 };
  var base = Object.create(proto);
  base.own = 2;
  var fromProto = { ...base };
  out.push(JSON.stringify(fromProto) + ':' + fromProto.inherited);
  var nullProto = { ...Object.create(null) };
  out.push(Object.getPrototypeOf(nullProto) === Object.prototype);
  var strSpread = { ...'ab' };
  out.push(JSON.stringify(strSpread));
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "define-property-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { var o = {}; Object.defineProperty(o, 'a', { value: 1 }); return Object.keys(o).length + ':' + JSON.stringify(Object.getOwnPropertyDescriptor(o, 'a')); });
  push(function () { var o = {}; Object.defineProperty(o, 'x', { get: function () { return 5; }, enumerable: true }); var d = Object.getOwnPropertyDescriptor(o, 'x'); return d.get() + ':' + d.writable + ':' + d.enumerable + ':' + d.configurable; });
  push(function () { var o = {}; Object.defineProperty(o, 'a', { value: 1 }); Object.defineProperty(o, 'a', { value: 1 }); return 'ok'; });
  push(function () { var o = {}; Object.defineProperty(o, 'a', { value: 1 }); Object.defineProperty(o, 'a', { value: 2 }); return 'ok'; });
  push(function () { var o = {}; Object.defineProperty(o, 'a', { get: function () { return 1; } }); Object.defineProperty(o, 'a', { value: 2 }); return 'ok'; });
  push(function () { var o = {}; Object.defineProperty(o, 'a', { value: 1, configurable: true }); Object.defineProperty(o, 'a', { value: 2 }); return o.a; });
  push(function () { var proto = { set x(v) { this.seen = v; } }; var o = Object.create(proto); o.x = 9; return o.seen + ':' + Object.prototype.hasOwnProperty.call(o, 'x'); });
  push(function () { var o = {}; Object.preventExtensions(o); try { Object.defineProperty(o, 'a', { value: 1 }); return 'ok'; } catch (e) { return e.name; } });
  push(function () { var a = [1]; Object.defineProperty(a, 'length', { value: 0 }); return a.length + ':' + a[0]; });
  push(function () { var o = {}; Object.defineProperties(o, { a: { value: 1, enumerable: true }, b: { get: function () { return 2; }, enumerable: true, configurable: true } }); return Object.keys(o).join(',') + ':' + o.b; });
  push(function () { var o = { a: 1 }; var descs = Object.getOwnPropertyDescriptors(o); return Object.keys(descs).join(',') + ':' + descs.a.value + ':' + descs.a.writable; });
  push(function () { var o = {}; Object.defineProperty(o, 'a', { set: function (v) {}, get: undefined }); var d = Object.getOwnPropertyDescriptor(o, 'a'); return typeof d.get + ':' + typeof d.set; });
  push(function () { var o = Object.freeze({ a: 1 }); try { o.a = 2; return o.a; } catch (e) { return e.name; } });
  push(function () { var o = {}; Object.defineProperty(o, 'a', { value: 1, writable: true }); o.a = 2; return o.a; });
  push(function () { var o = {}; var log = []; Object.defineProperty(o, 'a', { get: function () { log.push('g'); return 1; }, set: function (v) { log.push('s' + v); } }); o.a = 2; return o.a + ':' + log.join(','); });
  push(function () { var o = { a: 1 }; Object.defineProperty(o, 'a', { get: function () { return 2; } }); return o.a; });
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "class-elements-matrix",
        """(function () {
  var out = [];
  var log = [];
  class A { constructor() { log.push('A'); this.x = 1; } m() { return 'm'; } static s() { return 's'; } }
  class B extends A { constructor() { super(); log.push('B'); } m() { return 'B' + super.m(); } }
  var b = new B(); out.push(log.join(',') + ':' + b.x + ':' + b.m() + ':' + B.s());
  class P { static #p = 1; static getP() { return P.#p; } }
  out.push(P.getP());
  class Q { #v = 3; get v() { return this.#v; } has(o) { return #v in o; } }
  var q = new Q(); out.push(q.v + ':' + q.has(q) + ':' + q.has({}));
  class R extends null { constructor() { return Object.create(null); } }
  var r = new R(); out.push(Object.getPrototypeOf(r) === null);
  try { new (class S extends null { constructor() { super(); } })(); out.push('ok'); } catch (e) { out.push(e.name); }
  var order = [];
  class T { a = order.push('a'); static b = order.push('b'); static { order.push('block'); } c = order.push('c'); constructor() { order.push('ctor'); } }
  new T(); out.push(order.join(','));
  class U { constructor() { out.push(new.target === U); } }
  new U();
  class V extends U {} new V();
  class W { static x = 1; static y = this.x + 1; } out.push(W.y);
  class X { ['m' + 1]() { return 2; } get [Symbol.toStringTag]() { return 'X'; } } out.push(new X().m1() + ':' + Object.prototype.toString.call(new X()));
  class Y { m() { return typeof this; } }
  var ym = new Y().m; out.push(ym());
  class Z { static { this.z = 5; } } out.push(Z.z);
  class AA { constructor() { this.a = 1; } b = this.a + 1; }
  out.push(new AA().b);
  class BB extends A { c = order.length; }
  order.length = 0; new BB(); out.push('derived:' + order.join(','));
  class CC { #x; get() { return this.#x; } set(v) { this.#x = v; } }
  var cc = new CC(); cc.set(4); out.push(cc.get());
  try { (new CC()).get(); out.push('ok'); } catch (e) { out.push(e.name); }
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "map-set-iterable-matrix",
        """(function () {
  var out = [];
  var log = [];
  function iterable(entries, badAt) {
    return {
      [Symbol.iterator]: function () {
        var i = 0;
        return {
          next: function () {
            log.push('n' + i);
            if (i === badAt) return { value: 1, done: false };
            if (i >= entries.length) return { done: true };
            return { value: entries[i++], done: false };
          },
          return: function () { log.push('r'); return { done: true }; }
        };
      }
    };
  }
  var m = new Map([[1, 'a'], [NaN, 'n'], [-0, 'z']]);
  out.push(m.size + ':' + m.get(NaN) + ':' + m.get(0) + ':' + [...m.keys()].map(String).join(','));
  log.length = 0;
  try { new Map(iterable([[1, 2]], 0)); } catch (e) { out.push('bad:' + e.name + ':' + log.join(',')); }
  log.length = 0;
  var s = new Set(iterable([1, 2], 5));
  out.push([...s].join(',') + ':' + log.join(','));
  var order = [];
  var set = new Set([3]); set.add(1); set.add(3); set.add(2);
  set.forEach(function (v, k, t) { order.push(v + ':' + (v === k) + ':' + (t === set)); });
  out.push(order.join('|'));
  var mm = new Map([['a', 1]]); mm.set('b', 2); mm.delete('a'); mm.set('a', 3);
  out.push([...mm].map(function (e) { return e.join('='); }).join(','));
  out.push(new Set([1, 2]).has(2) + ':' + new Set([1, 2]).size);
  out.push(JSON.stringify([...new Map([[undefined, 1], ['u', undefined]])]));
  var entries = new Map([[1, 2]]).entries();
  out.push(JSON.stringify(entries.next()) + ':' + JSON.stringify(entries.next()));
  var fit = new Set([1, 2, 3]).values();
  out.push(JSON.stringify(fit.next()) + ':' + JSON.stringify(fit.next()) + ':' + JSON.stringify(fit.next()) + ':' + JSON.stringify(fit.next()));
  out.push(new Map().get('x') + ':' + new Set().has('x'));
  return out.join(';');
})()""",
    ),

    // 新式数组方法、Iterator helpers、Object/Map.groupBy 与 String 现代方法
    DifferentialCase(
        "array-change-by-copy-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { return [3, 1, 2].toSorted().join(',') + ':' + [3, 1, 2].join(','); });
  push(function () { return JSON.stringify([, 2, , 1].toSorted()); });
  push(function () { return JSON.stringify([, 2, , 1].toReversed()); });
  push(function () { return JSON.stringify([1, 2, 3].toSpliced(1, 1, 9)); });
  push(function () { return JSON.stringify([1, 2, 3].toSpliced(-1, 0, 9)); });
  push(function () { return [1, 2, 3].with(1, 9).join(',') + ':' + [1, 2, 3].join(','); });
  push(function () { return [1, 2, 3].with(-1, 9).join(','); });
  push(function () { return [1, 2, 3].with(3, 9); });
  push(function () { return [1, 2, 3].with(-4, 9); });
  push(function () { return [1, 2, 3].with(1.9, 9).join(','); });
  push(function () { return JSON.stringify([1, , 3].toSorted(function (a, b) { return 0; })); });
  push(function () { var log = []; var a = { length: 3, 0: 3, 1: 1, 2: 2 }; var r = Array.prototype.toSorted.call(a, function (x, y) { log.push(x + ':' + y); return x - y; }); return r.join(',') + ':' + Array.isArray(r) + ':' + Object.keys(a).join(','); });
  push(function () { return [1, 2, 3].toSpliced(1, Infinity).length; });
  push(function () { return [1, 2, 3].toSpliced(1, -1).join(','); });
  push(function () { return [1, 2, 3].findLast(function (x) { return x < 3; }); });
  push(function () { return [1, 2, 3].findLastIndex(function (x) { return x < 3; }); });
  push(function () { var log = []; var r = [1, 2, 3].findLast(function (x, i, arr) { log.push(x + '@' + i + ':' + arr.length); return false; }); return r + ':' + log.join(','); });
  push(function () { return [1, , 3].findLast(function (x) { return x === undefined; }); });
  push(function () { return [1, 2, 3].at(-1) + ':' + [1, 2, 3].at(5) + ':' + [1, 2, 3].at(-4) + ':' + [1, 2, 3].at(1.7); });
  push(function () { return Array.prototype.at.call({ length: 2, 0: 'a', 1: 'b' }, -2); });
  push(function () { return Array.prototype.findLast.call({ length: 2, 0: 1 }, function (x) { return x === 1; }); });
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "iterator-helpers-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { return [1, 2, 3].values().map(function (x) { return x * 2; }).toArray().join(','); });
  push(function () { return [1, 2, 3, 4].values().filter(function (x) { return x % 2 === 0; }).toArray().join(','); });
  push(function () { return [1, 2, 3].values().take(2).toArray().join(','); });
  push(function () { return [1, 2, 3].values().drop(2).toArray().join(','); });
  push(function () { return [1, 2, 3].values().flatMap(function (x) { return [x, x]; }).toArray().join(','); });
  push(function () { return [1, 2, 3].values().reduce(function (a, b) { return a + b; }, 10); });
  push(function () { return [1, 2, 3].values().reduce(function (a, b) { return a + b; }); });
  push(function () { return [1, 2, 3].values().some(function (x) { return x > 2; }) + ':' + [1, 2, 3].values().every(function (x) { return x > 0; }); });
  push(function () { return [1, 2, 3].values().find(function (x) { return x > 1; }) + ':' + [1, 2, 3].values().find(function () { return false; }); });
  push(function () { var log = []; [1, 2, 3].values().forEach(function (x, i) { log.push(x + '@' + i); }); return log.join(','); });
  push(function () { var it = [1, 2, 3].values().map(function (x) { return x + 1; }); return it.next().value + ':' + it.next().value + ':' + it.next().done; });
  push(function () { var it = [1, 2, 3].values().map(function (x) { return x; }); return it[Symbol.iterator]() === it; });
  push(function () { return Array.from([1, 2].values().map(function (x) { return x * 3; })).join(','); });
  push(function () { return Iterator.from([1, 2]).map(function (x) { return x + 1; }).toArray().join(','); });
  push(function () { return Iterator.from(new Set([1, 2])).toArray().join(','); });
  push(function () { var log = []; var src = { [Symbol.iterator]: function () { var i = 0; return { next: function () { log.push('n' + i); return i < 5 ? { value: ++i, done: false } : { done: true }; } }; } }; var r = Iterator.from(src).take(2).toArray(); return r.join(',') + ':' + log.join(','); });
  push(function () { var log = []; var src = { [Symbol.iterator]: function () { var i = 0; return { next: function () { log.push('n' + i); return i < 5 ? { value: ++i, done: false } : { done: true }; }, return: function () { log.push('r'); return { done: true }; } }; } }; var r = Iterator.from(src).take(2).toArray(); return r.join(',') + ':' + log.join(','); });
  push(function () { try { Iterator.prototype.map.call({}, function (x) { return x; }); return 'ok'; } catch (e) { return e.name; } });
  push(function () { return [1, 2].values().take(0).toArray().join(',') + ':' + [1, 2].values().take(-1).toArray().join(','); });
  push(function () { return [1, 2].values().drop(-1).toArray().join(',') + ':' + [1, 2].values().drop(Infinity).toArray().length; });
  push(function () { return [1, 2].values().take(NaN).toArray().length + ':' + [1, 2].values().drop(NaN).toArray().join(','); });
  push(function () { var it = [1, 2, 3].values().filter(function (x) { return x > 1; }); return it.next().value + ':' + it.next().value + ':' + JSON.stringify(it.next()); });
  push(function () { return [1, 2, 3].values().map().toArray().join(','); });
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "groupby-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { var g = Object.groupBy([1, 2, 3, 4], function (x) { return x % 2 === 0 ? 'even' : 'odd'; }); return JSON.stringify(g) + ':' + (Object.getPrototypeOf(g) === null); });
  push(function () { var g = Map.groupBy([1, 2, 3], function (x) { return x % 2; }); return [...g.keys()].join(',') + ':' + g.get(1).join(','); });
  push(function () { var log = []; var g = Object.groupBy([1, 2], function (x, i) { log.push(x + '@' + i); return 'k'; }); return JSON.stringify(g) + ':' + log.join(','); });
  push(function () { var key = { toString: function () { return 'T'; } }; var g = Object.groupBy(['a'], function () { return key; }); return JSON.stringify(g); });
  push(function () { var sym = Symbol('s'); var g = Object.groupBy([1], function () { return sym; }); return g[sym].length; });
  push(function () { var g = Map.groupBy(new Set([1, 2, 3]), function (x) { return x > 1 ? 'big' : 'small'; }); return g.get('big').join(',') + ':' + g.get('small').join(','); });
  push(function () { var g = Map.groupBy([3], function (x) { return x; }); return g.get(3).join(',') + ':' + g.has(1); });
  push(function () { var g = Object.groupBy([], function (x) { return x; }); return Object.keys(g).length; });
  push(function () { var g = Object.groupBy([1, 2], function () { return '__proto__'; }); return Object.keys(g).join(',') + ':' + Array.isArray(g['__proto__']); });
  push(function () { return Object.hasOwn({ a: 1 }, 'a') + ':' + Object.hasOwn({ a: 1 }, 'b') + ':' + Object.hasOwn(Object.create({ a: 1 }), 'a'); });
  push(function () { return Object.hasOwn(null, 'a'); });
  push(function () { var log = []; var src = { [Symbol.iterator]: function () { var i = 0; return { next: function () { log.push('n' + i); return i < 2 ? { value: ++i, done: false } : { done: true }; }, return: function () { log.push('r'); return { done: true }; } }; } }; Map.groupBy(src, function (x) { return x; }); return log.join(','); });
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "string-modern-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { return 'abc'.at(1) + ':' + 'abc'.at(-1) + ':' + 'abc'.at(3) + ':' + 'abc'.at(-4); });
  push(function () { return 'a-b-c'.replaceAll('-', '+'); });
  push(function () { return 'a-b'.replaceAll('-', '[$&]'); });
  push(function () { return 'abc'.replaceAll('b', function (m, i, s) { return m + i + s.length; }); });
  push(function () { return 'aaa'.replaceAll(/a/g, 'b'); });
  push(function () { return 'aaa'.replaceAll(/a/, 'b'); });
  push(function () { return 'ab'.replaceAll('', '-'); });
  push(function () { return [...'a1b2'.matchAll(/([a-z])(\d)/g)].map(function (m) { return m[0] + ':' + m[1] + ':' + m.index; }).join(','); });
  push(function () { var it = 'a1'.matchAll(/([a-z])(\d)/g); return [...it].length + ':' + 'a1'.matchAll(/x/g).next().done; });
  push(function () { return 'a1'.matchAll(/a/); });
  push(function () { return String.raw({ raw: ['a', 'b', 'c'] }, 1, 2); });
  push(function () { return String.raw({ raw: { length: 2, 0: 'x', 1: 'y' } }, 'A'); });
  push(function () { return String.raw({ raw: [] }); });
  push(function () { return String.raw({ raw: ['a'] }, 1, 2); });
  push(function () { return 'abc'.at(0.9) + ':' + 'abc'.at(-0.9) + ':' + 'abc'.at(Infinity); });
  return out.join('|');
})()""",
    ),

    // eval/with 作用域、instanceof/in 与 Array.prototype.concat 的展开语义
    DifferentialCase(
        "eval-with-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { var x = 1; function f() { var x = 2; return eval('x'); } return f() + ':' + x; });
  push(function () { var x = 1; function f() { var x = 2; var e = eval; return e('x'); } return f(); });
  push(function () { function f() { eval('var y = 3;'); return y; } return f(); });
  push(function () { function f() { 'use strict'; eval('var z = 4;'); return typeof z; } return f(); });
  push(function () { var log = []; function f() { eval('var a = 1;'); log.push(a); } f(); return log.join(',') + ':' + typeof a; });
  push(function () { return eval('1 + 2'); });
  push(function () { return String(eval('var q = 5')); });
  push(function () { try { eval('var ='); } catch (e) { return e.name; } });
  push(function () { var s = 'x'; return eval('s + s'); });
  push(function () { function f(a) { eval('a = 9'); return a; } return f(1); });
  push(function () { function f() { eval('arguments[0] = 7'); return arguments[0]; } return f(1); });
  push(function () { var log = []; function f() { eval('var b = 1;'); function g() { return typeof b; } log.push(g()); } f(); return log.join(','); });
  push(function () { return typeof eval('function h() {}') + ':' + typeof h; });
  push(function () { var o = { x: 1 }; with (o) { return x; } });
  push(function () { var o = { x: 1 }; with (o) { x = 2; } return o.x; });
  push(function () { var o = { x: 1 }; var x = 9; with (o) { x = 2; } return o.x + ':' + x; });
  push(function () { function f() { return typeof this; } return f() + ':' + f.call(null); });
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "instanceof-in-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { class A { static [Symbol.hasInstance](x) { return x === 1; } } return (1 instanceof A) + ':' + (2 instanceof A); });
  push(function () { function F() {} var b = new F(); return (b instanceof F) + ':' + ({} instanceof F) + ':' + F[Symbol.hasInstance](b); });
  push(function () { var b = (function () {}).bind(null); return b[Symbol.hasInstance]({}) + ':' + ({} instanceof b); });
  push(function () { return ('x' in { x: 1 }) + ':' + ('x' in Object.create({ x: 1 })) + ':' + (0 in [1]); });
  push(function () { try { 'x' in 'str'; return 'ok'; } catch (e) { return e.name; } });
  push(function () { try { 'x' in 1; return 'ok'; } catch (e) { return e.name; } });
  push(function () { var s = Symbol('k'); var o = { [s]: 1 }; return (s in o) + ':' + ('k' in o); });
  push(function () { var proto = { [Symbol.hasInstance](x) { return typeof x === 'string'; } }; return 'a' instanceof proto; });
  push(function () { return 1 instanceof 5; });
  push(function () { var o = Object.create(null); return o instanceof Object; });
  push(function () { try { ({}).hasOwnProperty('x'); return 'ok'; } catch (e) { return e.name; } });
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "concat-spreadable-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { return [1].concat([2, 3], 4).join(','); });
  push(function () { return JSON.stringify([1, , 2].concat([3, , 4])); });
  push(function () { var o = { length: 2, 0: 'a', 1: 'b' }; return [0].concat(o).length + ':' + Array.isArray([0].concat(o)[1]); });
  push(function () { var o = { length: 1, 0: 'a', [Symbol.isConcatSpreadable]: true }; return [0].concat(o).join(','); });
  push(function () { var a = [1, 2]; a[Symbol.isConcatSpreadable] = false; return [0].concat(a).length; });
  push(function () { class A extends Array {} return new A(1, 2).concat([3]).length; });
  push(function () { return Array.prototype.concat.call({ length: 1, 0: 1 }, 2).length; });
  push(function () { var log = []; var o = { get length() { log.push('l'); return 1; }, get 0() { log.push('g'); return 'x'; } }; var r = [0].concat(o); return r.join(',') + ':' + log.join(','); });
  push(function () { return [].concat.call(null).length; });
  push(function () { return [1].concat(null, undefined, [2]).join(','); });
  return out.join('|');
})()""",
    ),

    // Number 三种格式化的确定性随机模糊（LCG 生成尾数 × 2^exp，覆盖 0..100 位）
    DifferentialCase(
        "num-tofixed-fuzz-100",
        """(function () {
  var out = [];
  var seed = 987654321;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  function nextValue() {
    var m = Math.floor(rnd() * 9007199254740992);
    var e = Math.floor(rnd() * 80) - 40;
    var v = m * Math.pow(2, e);
    if (!isFinite(v)) v = Number.MAX_VALUE;
    if (rnd() < 0.5) v = -v;
    return v;
  }
  for (var i = 0; i < 100; i++) {
    var v = nextValue();
    var d = Math.floor(rnd() * 101);
    if (rnd() < 0.2) d = rnd() < 0.5 ? 0 : 100;
    out.push(v.toFixed(d));
  }
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "num-toprecision-fuzz-100",
        """(function () {
  var out = [];
  var seed = 192837465;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  function nextValue() {
    var m = Math.floor(rnd() * 9007199254740992);
    var e = Math.floor(rnd() * 80) - 40;
    var v = m * Math.pow(2, e);
    if (!isFinite(v)) v = Number.MAX_VALUE;
    if (rnd() < 0.5) v = -v;
    return v;
  }
  for (var i = 0; i < 100; i++) {
    var v = nextValue();
    var d = 1 + Math.floor(rnd() * 100);
    if (rnd() < 0.2) d = rnd() < 0.5 ? 1 : 100;
    out.push(v.toPrecision(d));
  }
  return out.join('|');
})()""",
    ),
    DifferentialCase(
        "num-toexponential-fuzz-100",
        """(function () {
  var out = [];
  var seed = 564738291;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  function nextValue() {
    var m = Math.floor(rnd() * 9007199254740992);
    var e = Math.floor(rnd() * 80) - 40;
    var v = m * Math.pow(2, e);
    if (!isFinite(v)) v = Number.MAX_VALUE;
    if (rnd() < 0.5) v = -v;
    return v;
  }
  for (var i = 0; i < 100; i++) {
    var v = nextValue();
    var d = Math.floor(rnd() * 101);
    if (rnd() < 0.2) d = rnd() < 0.5 ? 0 : 100;
    out.push(v.toExponential(d));
  }
  return out.join('|');
})()""",
    ),

    // Date 的随机时间 ISO 往返、UTC 构造/解析与 setter 模糊
    DifferentialCase(
        "date-iso-fuzz",
        """(function () {
  var out = [];
  var seed = 246813579;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  for (var i = 0; i < 120; i++) {
    var t = (rnd() * 2 - 1) * 8.64e15;
    out.push(new Date(t).toISOString());
  }
  for (var j = 0; j < 60; j++) {
    var y = Math.floor(rnd() * 20000) - 10000;
    var mo = Math.floor(rnd() * 30) - 3;
    var day = Math.floor(rnd() * 60) - 10;
    var h = Math.floor(rnd() * 48) - 12;
    var mi = Math.floor(rnd() * 120) - 30;
    var s = Math.floor(rnd() * 120) - 30;
    var ms = Math.floor(rnd() * 2000) - 500;
    var utc = Date.UTC(y, mo, day, h, mi, s, ms);
    out.push(String(utc) + '|' + new Date(utc).toISOString());
  }
  var samples = ['1970-01-01T00:00:00.000Z', '0000-01-01T00:00:00.000Z', '-000001-12-31T23:59:59.999Z', '+010000-01-01T00:00:00.000Z', '2020-02-29T12:34:56.789+08:30', '2020-02-30T00:00:00Z', '2020-13-01T00:00:00Z', '2020-00-01T00:00:00Z', '2020-01-32T00:00:00Z', '2020-01-01T24:00:00Z', '2020-01-01T23:60:00Z', '2020-01-01T23:59:60Z', '2020-01-01T00:00:00.1234Z'];
  for (var k = 0; k < samples.length; k++) {
    var p = Date.parse(samples[k]);
    out.push(samples[k] + '=' + (isNaN(p) ? 'NaN' : new Date(p).toISOString()));
  }
  for (var n = 0; n < 80; n++) {
    var d = new Date(Math.floor((rnd() * 2 - 1) * 8.64e15));
    var pick = Math.floor(rnd() * 5);
    if (pick === 0) d.setUTCMonth(Math.floor(rnd() * 30) - 5, Math.floor(rnd() * 60) - 10);
    else if (pick === 1) d.setUTCDate(Math.floor(rnd() * 60) - 10);
    else if (pick === 2) d.setUTCHours(Math.floor(rnd() * 48) - 12, Math.floor(rnd() * 120) - 30, Math.floor(rnd() * 120) - 30, Math.floor(rnd() * 2000) - 500);
    else if (pick === 3) d.setUTCFullYear(Math.floor(rnd() * 20000) - 10000, Math.floor(rnd() * 30) - 5, Math.floor(rnd() * 60) - 10);
    else d.setUTCMinutes(Math.floor(rnd() * 120) - 30, Math.floor(rnd() * 120) - 30, Math.floor(rnd() * 2000) - 500);
    out.push(String(d.getTime()));
  }
  return out.join(';');
})()""",
    ),


    // BMP 全码点大小写映射审计（按 0x4000 分块，避免单用例输出过大）
    DifferentialCase(
        "string-upper-bmp-0",
        """(function () { var out = []; for (var cp = 0; cp < 16384; cp++) { if (cp === 0xA7CE || cp === 0xA7CF || cp === 0xA7D2 || cp === 0xA7D3 || cp === 0xA7D4 || cp === 0xA7D5) { out.push("<unicode17-only>"); continue; } out.push(String.fromCharCode(cp).toUpperCase()); } return out.join("|"); })()""",
    ),
    DifferentialCase(
        "string-upper-bmp-1",
        """(function () { var out = []; for (var cp = 16384; cp < 32768; cp++) { if (cp === 0xA7CE || cp === 0xA7CF || cp === 0xA7D2 || cp === 0xA7D3 || cp === 0xA7D4 || cp === 0xA7D5) { out.push("<unicode17-only>"); continue; } out.push(String.fromCharCode(cp).toUpperCase()); } return out.join("|"); })()""",
    ),
    DifferentialCase(
        "string-upper-bmp-2",
        """(function () { var out = []; for (var cp = 32768; cp < 49152; cp++) { if (cp === 0xA7CE || cp === 0xA7CF || cp === 0xA7D2 || cp === 0xA7D3 || cp === 0xA7D4 || cp === 0xA7D5) { out.push("<unicode17-only>"); continue; } out.push(String.fromCharCode(cp).toUpperCase()); } return out.join("|"); })()""",
    ),
    DifferentialCase(
        "string-upper-bmp-3",
        """(function () { var out = []; for (var cp = 49152; cp < 65536; cp++) { if (cp === 0xA7CE || cp === 0xA7CF || cp === 0xA7D2 || cp === 0xA7D3 || cp === 0xA7D4 || cp === 0xA7D5) { out.push("<unicode17-only>"); continue; } out.push(String.fromCharCode(cp).toUpperCase()); } return out.join("|"); })()""",
    ),
    DifferentialCase(
        "string-lower-bmp-0",
        """(function () { var out = []; for (var cp = 0; cp < 16384; cp++) { if (cp === 0xA7CE || cp === 0xA7CF || cp === 0xA7D2 || cp === 0xA7D3 || cp === 0xA7D4 || cp === 0xA7D5) { out.push("<unicode17-only>"); continue; } out.push(String.fromCharCode(cp).toLowerCase()); } return out.join("|"); })()""",
    ),
    DifferentialCase(
        "string-lower-bmp-1",
        """(function () { var out = []; for (var cp = 16384; cp < 32768; cp++) { if (cp === 0xA7CE || cp === 0xA7CF || cp === 0xA7D2 || cp === 0xA7D3 || cp === 0xA7D4 || cp === 0xA7D5) { out.push("<unicode17-only>"); continue; } out.push(String.fromCharCode(cp).toLowerCase()); } return out.join("|"); })()""",
    ),
    DifferentialCase(
        "string-lower-bmp-2",
        """(function () { var out = []; for (var cp = 32768; cp < 49152; cp++) { if (cp === 0xA7CE || cp === 0xA7CF || cp === 0xA7D2 || cp === 0xA7D3 || cp === 0xA7D4 || cp === 0xA7D5) { out.push("<unicode17-only>"); continue; } out.push(String.fromCharCode(cp).toLowerCase()); } return out.join("|"); })()""",
    ),
    DifferentialCase(
        "string-lower-bmp-3",
        """(function () { var out = []; for (var cp = 49152; cp < 65536; cp++) { if (cp === 0xA7CE || cp === 0xA7CF || cp === 0xA7D2 || cp === 0xA7D3 || cp === 0xA7D4 || cp === 0xA7D5) { out.push("<unicode17-only>"); continue; } out.push(String.fromCharCode(cp).toLowerCase()); } return out.join("|"); })()""",
    ),

    // 大小写特殊映射与 Final_Sigma 上下文（排除 V8 尚缺的 Unicode 17 新增配对）
    DifferentialCase(
        "string-case-special-matrix",
        """(function () {
  var out = [];
  var xs = ['A.Σ', 'A\u0301Σ', '\u0301Σ', 'A\u00ADΣ', '1Σ', 'AΣ\u0301', 'AΣ\u0301A', 'AΣ.', 'AΣ\u0301.', '\uD800Σ', 'A\uD800Σ', 'A😀Σ', 'Σ', 'ΑΣ', 'ΑΣΑ', '\u0345Σ', 'Α\u0345Σ', 'AΣ\u0345', 'AΣ\u0345Α'];
  for (var i = 0; i < xs.length; i++) {
    out.push(xs[i].toLowerCase());
  }
  var cs = ['ß', 'İ', 'ﬁ', 'ꟁ', 'Ꟁ', '𐐨', '𐐀'];
  for (var j = 0; j < cs.length; j++) {
    out.push(cs[j] + '>' + cs[j].toUpperCase() + '>' + cs[j].toLowerCase());
  }
  return out.join(';');
})()""",
    ),

    // 非 ASCII Unicode 数字不能作为 JS 数字字面量的起始（eval 均应抛 SyntaxError）
    DifferentialCase(
        "num-unicode-digit-literal",
        """(function () { var out = []; function p(src) { try { out.push(String(eval(src))); } catch (e) { out.push(e.name); } } p('\u0661'); p('.\u0665'); p('\u0661.\u0665'); p('\u06F1+1'); p('var \u0661x = 1'); return out.join('|'); })()""",
    ),

    // JSON 序列化随机值树与随机 UTF-16 码元转义（well-formed JSON.stringify）
    DifferentialCase(
        "json-stringify-fuzz",
        """(function () {
  var out = [];
  var seed = 135791357;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var atoms = ['', 'a', '"', '\\', '\n', '\u0000', '\u2028', '\uD800', '\uDC00', '😀', 'é', '1', '01'];
  function gen(depth) {
    var pick = rnd();
    if (depth <= 0 || pick < 0.45) {
      var kind = Math.floor(rnd() * 8);
      if (kind < 5) return atoms[Math.floor(rnd() * atoms.length)];
      if (kind === 5) return (rnd() * 2 - 1) * Math.pow(2, Math.floor(rnd() * 100) - 50);
      if (kind === 6) return rnd() < 0.5;
      return null;
    }
    if (pick < 0.72) {
      var arr = [];
      var count = Math.floor(rnd() * 5);
      for (var i = 0; i < count; i++) arr.push(gen(depth - 1));
      if (rnd() < 0.2) arr.length = count + 1;
      return arr;
    }
    var obj = {};
    var members = Math.floor(rnd() * 5);
    for (var j = 0; j < members; j++) {
      var key = atoms[Math.floor(rnd() * atoms.length)] + (rnd() < 0.5 ? String(Math.floor(rnd() * 5)) : '');
      obj[key] = gen(depth - 1);
    }
    return obj;
  }
  for (var i = 0; i < 80; i++) {
    out.push(JSON.stringify(gen(3)));
  }
  return out.join('|');
})()""",
    ),

    DifferentialCase(
        "json-escape-fuzz",
        """(function () {
  var out = [];
  var seed = 246802468;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  for (var i = 0; i < 120; i++) {
    var text = '';
    var length = Math.floor(rnd() * 8);
    for (var j = 0; j < length; j++) {
      text += String.fromCharCode(Math.floor(rnd() * 65536));
    }
    out.push(JSON.stringify(text));
  }
  return out.join('|');
})()""",
    ),

    // Number/parseFloat/parseInt 随机字符串模糊（符号、空白、进制前缀与非法尾部）
    DifferentialCase(
        "num-parse-fuzz",
        """(function () {
  var out = [];
  var seed = 975318642;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var alphabet = '0123456789abcdefxXoObB+-.eE \t\n\r_Infinity';
  var radices = [undefined, 2, 8, 10, 16, 36];
  for (var i = 0; i < 200; i++) {
    var length = Math.floor(rnd() * 11);
    var text = '';
    for (var j = 0; j < length; j++) {
      text += alphabet[Math.floor(rnd() * alphabet.length)];
    }
    var radix = radices[Math.floor(rnd() * radices.length)];
    out.push(Number(text) + '|' + String(Number.parseFloat(text)) + '|' + String(parseInt(text, radix)));
  }
  return out.join(';');
})()""",
    ),

    // JSON.parse 随机输入模糊（字符集覆盖结构、数字、转义与无效 token）
    DifferentialCase(
        "json-parse-fuzz",
        """(function () {
  var out = [];
  var seed = 112233445;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var alphabet = '{}[]",:0123456789.eE+-truefalsnul \t\n\r\\/u';
  for (var i = 0; i < 250; i++) {
    var length = Math.floor(rnd() * 16);
    var text = '';
    for (var j = 0; j < length; j++) {
      text += alphabet[Math.floor(rnd() * alphabet.length)];
    }
    try {
      out.push('OK:' + JSON.stringify(JSON.parse(text)));
    } catch (e) {
      out.push('ERR');
    }
  }
  return out.join('|');
})()""",
    ),

    // JSON.parse 近合法输入的随机变异（插入/删除/替换字符，覆盖数字与转义边界）
    DifferentialCase(
        "json-mutate-fuzz",
        """(function () {
  var out = [];
  var seed = 556677889;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var alphabet = '{}[]",:0123456789.eE+-truefalsnul \\/u\u0000';
  function base() {
    var kind = Math.floor(rnd() * 5);
    if (kind === 0) return String((rnd() * 2 - 1) * Math.pow(10, Math.floor(rnd() * 7) - 3));
    if (kind === 1) return JSON.stringify('ab' + String.fromCharCode(Math.floor(rnd() * 128)));
    if (kind === 2) return '[1,"a",null,true]';
    if (kind === 3) return '{"a":1,"b":[2,3]}';
    return '[]';
  }
  for (var i = 0; i < 200; i++) {
    var text = base();
    var mutations = 1 + Math.floor(rnd() * 2);
    for (var m = 0; m < mutations; m++) {
      var position = Math.floor(rnd() * (text.length + 1));
      var operation = Math.floor(rnd() * 3);
      var char = alphabet[Math.floor(rnd() * alphabet.length)];
      if (operation === 0) text = text.slice(0, position) + char + text.slice(position);
      else if (operation === 1 && position < text.length) text = text.slice(0, position) + text.slice(position + 1);
      else if (position < text.length) text = text.slice(0, position) + char + text.slice(position + 1);
    }
    try {
      out.push('OK:' + JSON.stringify(JSON.parse(text)));
    } catch (e) {
      out.push('ERR');
    }
  }
  return out.join('|');
})()""",
    ),

    // String.split/replace/match/matchAll 与正则组合的随机差分
    DifferentialCase(
        "string-regex-fuzz",
        """(function () {
  var out = [];
  var seed = 314159265;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var patterns = [
    ['a+', ''], ['b*', 'g'], ['(a|b)', 'g'], ['\\d+', ''], ['\\s', 'g'],
    ['[ab]+', 'gi'], ['(?:ab)', ''], ['(?<x>a+)', 'g'], ['.', 's'],
    ['[a-z]+', ''], ['^a', ''], ['b$', 'm'], ['a{1,2}', 'g'], ['c', ''],
    ['(a)(b)?', 'g'], ['a+', 'u'], ['\\b', 'g']
  ];
  var inputs = ['', 'a', 'aa', 'ab', 'abc', 'a b c', '123', 'a1b2', 'aaa', 'bbb', 'a\nb', '😀a', 'cab', 'ABC', 'ab ab'];
  var replacements = ['X', '[$&]', '$1', '$$', '', '$<x>'];
  for (var i = 0; i < 200; i++) {
    var pattern = patterns[Math.floor(rnd() * patterns.length)];
    var text = inputs[Math.floor(rnd() * inputs.length)];
    var operation = Math.floor(rnd() * 4);
    try {
      if (operation === 0) {
        out.push(JSON.stringify(text.split(new RegExp(pattern[0], pattern[1]), Math.floor(rnd() * 5))));
      } else if (operation === 1) {
        out.push(text.replace(new RegExp(pattern[0], pattern[1]), replacements[Math.floor(rnd() * replacements.length)]));
      } else if (operation === 2) {
        var match = text.match(new RegExp(pattern[0], pattern[1]));
        out.push(JSON.stringify(match));
      } else {
        var flags = pattern[1].indexOf('g') >= 0 ? pattern[1] : pattern[1] + 'g';
        var results = [];
        var iterator = text.matchAll(new RegExp(pattern[0], flags));
        for (var item of iterator) {
          results.push([item[0], item.index, item[1] === undefined ? null : item[1]]);
        }
        out.push(JSON.stringify(results));
      }
    } catch (e) {
      out.push('THROW:' + e.name);
    }
  }
  return out.join(';');
})()""",
    ),

    // 参数与函数体词法声明的早期错误探针
    DifferentialCase(
        "early-error-params-matrix",
        """(function () { var cases = ['([a]) => { let a; }', '({a}) => { let a; }', '!{ a({b}){ let b; } }', '!{ set a([b]){ let b; } }', '!{ a([b]){ let b; } }', 'try {} catch(a) { for(var a of 1); }']; var out = []; for (var i = 0; i < cases.length; i++) { try { eval(cases[i]); out.push('OK'); } catch (e) { out.push(e.name); } } return out.join('|'); })()""",
    ),

    // 长随机十进制字符串的 Number/parseFloat 解析与舍入
    DifferentialCase(
        "num-decimal-fuzz",
        """(function () {
  var out = [];
  var seed = 864209753;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  function digits(count) {
    var text = '';
    for (var i = 0; i < count; i++) text += String.fromCharCode(48 + Math.floor(rnd() * 10));
    return text;
  }
  for (var i = 0; i < 150; i++) {
    var text = '';
    if (rnd() < 0.3) text += '-';
    text += digits(1 + Math.floor(rnd() * 20));
    if (rnd() < 0.7) text += '.' + digits(1 + Math.floor(rnd() * 20));
    if (rnd() < 0.5) {
      text += (rnd() < 0.5 ? 'e' : 'E');
      if (rnd() < 0.5) text += (rnd() < 0.5 ? '+' : '-');
      text += digits(1 + Math.floor(rnd() * 4));
    }
    out.push(Number(text) + '|' + String(parseFloat(text)) + '|' + Number(text).toString());
  }
  return out.join(';');
})()""",
    ),

    // Symbol 注册表、包装对象、属性键与 JSON/ToString 行为
    DifferentialCase(
        "symbol-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { var a = Symbol('x'); var b = Symbol('x'); return (a === b) + ':' + String(a) + ':' + a.description; });
  push(function () { return typeof Symbol() + ':' + Symbol.keyFor(Symbol('k')); });
  push(function () { var r = Symbol.for('k'); return (Symbol.for('k') === r) + ':' + Symbol.keyFor(r) + ':' + Symbol.keyFor(Symbol('k')); });
  push(function () { return (Symbol.for(1) === Symbol.for('1')) + ':' + Symbol.for(1).description; });
  push(function () { var s = Symbol('s'); var o = {}; o[s] = 1; o.x = 2; return o[s] + ':' + Object.keys(o).join(',') + ':' + Object.getOwnPropertySymbols(o).length; });
  push(function () { var s = Symbol(); return String(s) + ':' + (s.description === undefined) + ':' + s.toString(); });
  push(function () { return '' + Symbol('s'); });
  push(function () { return new Symbol('s'); });
  push(function () { return Object(Symbol('w')).valueOf() === Object.prototype.valueOf.call(Symbol('w')); });
  push(function () { var w = Object(Symbol('w')); return typeof w + ':' + (w.valueOf() === w.valueOf()) + ':' + w.description; });
  push(function () { return JSON.stringify({ a: Symbol('x') }) + ':' + JSON.stringify([Symbol('x')]); });
  push(function () { return Object.prototype.toString.call(Symbol('t')); });
  push(function () { return +Symbol('n'); });
  push(function () { return Symbol.prototype.description.get.call({}); });
  push(function () { var s = Symbol('k'); var o = {}; o[s] = 1; Object.defineProperty(o, s, { enumerable: false }); return Object.getOwnPropertySymbols(o).length + ':' + Object.keys(o).length; });
  push(function () { return (Symbol.iterator === Symbol.iterator) + ':' + typeof Symbol.iterator + ':' + String(Symbol.asyncIterator).length; });
  push(function () { var s = Symbol('d'); return Object.getOwnPropertyDescriptor(Object(s), 'description').value; });
  push(function () { return String(Symbol.for('')); });
  return out.join('|');
})()""",
    ),

    // Date.parse 的 ISO 字段组合模糊（越界月份/日期/时分秒/小数秒/时区）
    DifferentialCase(
        "date-parse-fuzz",
        """(function () {
  var out = [];
  var seed = 424242424;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  function pad(value, width) {
    var text = String(value);
    while (text.length < width) text = '0' + text;
    return text;
  }
  function yearText(year) {
    if (year >= 0 && year <= 9999) return pad(year, 4);
    return (year < 0 ? '-' : '+') + pad(Math.abs(year), 6);
  }
  for (var i = 0; i < 150; i++) {
    var year = Math.floor(rnd() * 200000) - 100000;
    var month = Math.floor(rnd() * 15);
    var day = Math.floor(rnd() * 33);
    var text = yearText(year) + '-' + pad(month, 2) + '-' + pad(day, 2);
    if (rnd() < 0.7) {
      var hour = Math.floor(rnd() * 26);
      var minute = Math.floor(rnd() * 62);
      var second = Math.floor(rnd() * 62);
      text += 'T' + pad(hour, 2) + ':' + pad(minute, 2);
      if (rnd() < 0.8) {
        text += ':' + pad(second, 2);
        if (rnd() < 0.4) {
          var fractionLength = 1 + Math.floor(rnd() * 4);
          var fraction = '';
          for (var j = 0; j < fractionLength; j++) fraction += String(Math.floor(rnd() * 10));
          text += '.' + fraction;
        }
      }
      if (rnd() < 0.7) {
        text += 'Z';
      } else {
        text += (rnd() < 0.5 ? '+' : '-') + pad(Math.floor(rnd() * 25), 2) + ':' + pad(Math.floor(rnd() * 61), 2);
      }
    }
    out.push(text + '=' + String(Date.parse(text)));
  }
  return out.join(';');
})()""",
    ),

    // Date.UTC 随机构造参数模糊（负数/小数/NaN/Infinity/缺省参数）
    DifferentialCase(
        "date-utc-fuzz",
        """(function () {
  var out = [];
  var seed = 753159852;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var specials = [NaN, Infinity, -Infinity, undefined];
  for (var i = 0; i < 120; i++) {
    var args = [];
    var count = 1 + Math.floor(rnd() * 7);
    for (var j = 0; j < count; j++) {
      var kind = rnd();
      if (kind < 0.6) args.push(Math.floor(rnd() * 4000) - 2000);
      else if (kind < 0.8) args.push((rnd() * 2 - 1) * 1000.75);
      else args.push(specials[Math.floor(rnd() * specials.length)]);
    }
    out.push(Date.UTC.apply(null, args) + '');
  }
  return out.join(';');
})()""",
    ),

    // URI 编解码随机字符串模糊（保留字符、astral/孤立代理项、百分号与非法 UTF-8）
    DifferentialCase(
        "uri-encode-fuzz",
        """(function () {
  var out = [];
  var seed = 918273645;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var alphabet = ['a', 'Z', '0', ' ', '%', '&', '=', '?', '/', '#', '[', ']', ':', '@',
    '!', '$', "'", '(', ')', '*', '+', ',', ';', 'é', '中', '\uD800', '\uDC00', '😀'];
  for (var i = 0; i < 120; i++) {
    var text = '';
    var length = Math.floor(rnd() * 8);
    for (var j = 0; j < length; j++) text += alphabet[Math.floor(rnd() * alphabet.length)];
    var uri;
    try { uri = encodeURI(text); } catch (e) { uri = 'THROW:' + e.name; }
    var component;
    try { component = encodeURIComponent(text); } catch (e) { component = 'THROW:' + e.name; }
    out.push(uri + '|' + component);
  }
  return out.join('||');
})()""",
    ),

    DifferentialCase(
        "uri-decode-fuzz",
        """(function () {
  var out = [];
  var seed = 1928374655;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var alphabet = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    'A', 'B', 'C', 'D', 'E', 'F', '%', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P',
    'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', '+', ' ', '/', 'é'];
  for (var i = 0; i < 120; i++) {
    var text = '';
    var length = Math.floor(rnd() * 10);
    for (var j = 0; j < length; j++) text += alphabet[Math.floor(rnd() * alphabet.length)];
    var uri;
    try { uri = decodeURI(text); } catch (e) { uri = 'THROW:' + e.name; }
    var component;
    try { component = decodeURIComponent(text); } catch (e) { component = 'THROW:' + e.name; }
    out.push(uri + '|' + component);
  }
  return out.join('||');
})()""",
    ),

    DifferentialCase(
        "early-error-matrix",
        """(function () {
  var sources = [
    '{ let a; let a; }',
    '{ let a; var a; }',
    'let a; { var a; }',
    'switch (1) { case 1: let x; case 2: let x; }',
    'try {} catch (e) { let e; }',
    'try {} catch ([e]) { var e; }',
    'function f(a, a) {}',
    'function f(a, a) { "use strict"; }',
    '(a, a) => 0',
    'function f(...a, b) {}',
    'function f(...a,) {}',
    'function f(...a = 1) {}',
    'class A { constructor() {} constructor() {} }',
    'class A { static prototype() {} }',
    'class A { get constructor() {} }',
    'class A { #x; #x; }',
    'class A { static #x; #x; }',
    'class A { constructor() { super(); } }',
    'class A extends null { constructor() { super(); } }',
    'new.target;',
    'super.x;',
    'a: a: ;',
    'a: { break b; }',
    'a: { continue a; }',
    'return;',
    '{ function f() {} let f; }',
    'function f() { let arguments; }',
    'function f() { "use strict"; let arguments; }',
    'function f(eval) { "use strict"; }',
    'with ({}) {}',
    'function f() { "use strict"; with ({}) {} }',
    'function f() { "use strict"; 010; }',
    'function f() { "use strict"; delete x; }',
    'function f() { "use strict"; var yield; }',
    'function f() { "use strict"; var implements; }',
    'let [a];',
    'const {a};',
    '({a});',
    '({a} = {});',
    'for (let [a] in {}) {}',
    'for (const x of []) {}',
    'for (const x;;) {}',
    'async function f() { var await; }',
    'function f() { await; }',
    'async function f() { await; }',
    'function f() { for await (const x of []) {} }',
    '/(?<a>x)(?<a>y)/',
    '/(?<a>x)|(?<a>y)/',
    '/\\p{Unknown}/u',
    '/(?<=a)*/u',
    'tag`\\unicode`;',
    '`\\unicode`;',
    'var let = 1;',
    'let let = 1;',
    'let a; let a;',
    'var a; let a;',
    'function f() { function g() {} function g() {} }',
    'function f() { "use strict"; function g() {} function g() {} }',
    'class A { ["constructor"]() {} constructor() {} }',
    'class A { static constructor() {} }',
    '({ get a() {}, set a(v) {} });',
    '({ get a() {}, get a() {} });',
    'function f(a = 1) { "use strict"; }',
    'function f(a = 1) { let a; }',
    'function f({a}) { var a; }',
    'function f(a) { let a; }',
    'let {a: {b}} = {};',
    'let {a: [b] = 1} = {};',
    '({a: {b = 1}} = {});',
    'for (let let of []) {}'
  ];
  var out = [];
  for (var i = 0; i < sources.length; i++) {
    try {
      eval(sources[i]);
      out.push(i + '=OK');
    } catch (e) {
      out.push(i + '=' + e.name);
    }
  }
  return out.join(';');
})()""",
    ),

    // Set 集合方法（union/intersection/difference/symmetricDifference/isSubsetOf 等）
    DifferentialCase(
        "set-methods-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () {
    var a = new Set([1, 2, 3]);
    var b = new Set([3, 4]);
    return [[...a.union(b)].join(','), [...a.intersection(b)].join(','),
      [...a.difference(b)].join(','), [...a.symmetricDifference(b)].join(',')].join('|');
  });
  push(function () {
    return new Set([1, 2]).isSubsetOf(new Set([1, 2, 3])) + ':' +
      new Set([1, 2, 3]).isSupersetOf(new Set([1, 2])) + ':' +
      new Set([1]).isDisjointFrom(new Set([2]));
  });
  push(function () { return new Set([1]).union([1, 2]).size; });
  push(function () {
    var record = { size: 2, has: function (v) { return v === 1; }, keys: function () { return [1, 2][Symbol.iterator](); } };
    return [...new Set([1, 3]).intersection(record)].join(',');
  });
  push(function () {
    var s = new Set([1]);
    return [...s.union(s)].join(',') + ':' + [...s.symmetricDifference(s)].join(',');
  });
  push(function () {
    var log = [];
    var s = new Set([1, 2]);
    var record = {
      get size() { log.push('s'); return 1; },
      has: function (v) { log.push('h' + v); return false; },
      keys: function () { log.push('k'); return [3][Symbol.iterator](); }
    };
    s.union(record);
    return log.join(',');
  });
  push(function () { return Set.prototype.union.call(new Map(), new Set()); });
  push(function () { return [...new Set([1, 2]).union(new Set([3]))].join(','); });
  push(function () { return new Set([1, 2, 3]).isDisjointFrom({ size: 1, has: function (v) { return v === 4; }, keys: function () { return [4][Symbol.iterator](); } }); });
  return out.join(';');
})()""",
    ),

    // WeakMap/WeakSet：对象与 Symbol 键、构造迭代器关闭、接收者与键类型校验
    DifferentialCase(
        "weakmap-set-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { var k = {}; var wm = new WeakMap([[k, 1]]); wm.set(k, 2); return wm.get(k) + ':' + wm.has(k) + ':' + wm.delete(k) + ':' + wm.has(k); });
  push(function () { var wm = new WeakMap(); return wm.set(1, 1); });
  push(function () { return new WeakMap([[1, 1]]); });
  push(function () { var sym = Symbol('s'); var wm = new WeakMap(); wm.set(sym, 1); return wm.get(sym) + ':' + wm.has(sym) + ':' + wm.delete(sym); });
  push(function () { var ws = new WeakSet([{}, {}]); return ws.has({}); });
  push(function () { var k = {}; var ws = new WeakSet([k]); return ws.has(k) + ':' + ws.delete(k) + ':' + ws.has(k); });
  push(function () { var wm = new WeakMap(); return String(wm) + ':' + Object.prototype.toString.call(wm); });
  push(function () {
    var wm = new WeakMap();
    var log = [];
    var iterable = {};
    iterable[Symbol.iterator] = function () {
      var i = 0;
      return {
        next: function () {
          log.push('n' + i);
          if (i === 1) return { value: [{}, 1], done: false };
          i++;
          return { done: true };
        },
        return: function () { log.push('r'); return { done: true }; }
      };
    };
    try { new WeakMap(iterable); } catch (e) {}
    return log.join(',');
  });
  push(function () { return WeakMap.prototype.get.call({}, {}); });
  push(function () { return new WeakMap().get(1); });
  push(function () { return new WeakSet(1); });
  push(function () { var wm = new WeakMap(); return wm.get() + ':' + wm.has(); });
  push(function () { var ws = new WeakSet(); return ws.add(1); });
  return out.join('|');
})()""",
    ),

    // Error/AggregateError：cause、toString 缺省规则、子类与 errors 可迭代
    DifferentialCase(
        "error-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { var e = new Error('m'); return e.name + ':' + e.message + ':' + e.toString() + ':' + (e.cause === undefined) + ':' + ('cause' in e); });
  push(function () { var e = Error('m'); return (e instanceof Error) + ':' + e.message; });
  push(function () { return new Error(123).message + ':' + new Error(undefined).message + ':' + new Error().message; });
  push(function () { var e = new Error('m', { cause: 42 }); return e.cause + ':' + Object.getOwnPropertyDescriptor(e, 'cause').enumerable + ':' + e.toString(); });
  push(function () { var e = new Error('m', 'x'); return 'cause' in e; });
  push(function () { var e = new Error('m'); e.name = ''; return '[' + e.toString() + ']'; });
  push(function () { var e = new Error('m'); e.message = ''; return '[' + e.toString() + ']'; });
  push(function () { var e = new Error('m'); e.name = ''; e.message = ''; return '[' + e.toString() + ']'; });
  push(function () { return Error.prototype.toString.call({ name: 'N', message: 'M' }); });
  push(function () { return Error.prototype.toString.call({ name: 'N' }); });
  push(function () { return Error.prototype.toString.call({ message: 'M' }); });
  push(function () { return Error.prototype.toString.call({ name: 1, message: 2 }); });
  push(function () { return Error.prototype.toString.call(1); });
  push(function () { return typeof EvalError + ':' + typeof RangeError + ':' + typeof ReferenceError + ':' + typeof SyntaxError + ':' + typeof TypeError + ':' + typeof URIError; });
  push(function () { return (new TypeError('t') instanceof Error) + ':' + (new TypeError('t') instanceof TypeError); });
  push(function () { var e = new AggregateError([1, 2], 'agg'); return e.name + ':' + e.message + ':' + e.errors.join(',') + ':' + Array.isArray(e.errors) + ':' + (e instanceof Error); });
  push(function () { var e = new AggregateError([]); return e.errors.length + ':' + e.message + ':' + e.name; });
  push(function () { return new AggregateError(1); });
  push(function () { var errors = { [Symbol.iterator]: function () { var i = 0; return { next: function () { return i < 2 ? { value: ++i, done: false } : { done: true }; } }; } }; return new AggregateError(errors, 'x').errors.join(','); });
  push(function () { function MyError(message) { this.message = message; } MyError.prototype = Object.create(Error.prototype); MyError.prototype.constructor = MyError; MyError.prototype.name = 'MyError'; var e = new MyError('m'); return (e instanceof Error) + ':' + e.toString(); });
  push(function () { return Object.prototype.toString.call(new Error('m')); });
  push(function () { return String(new Error('m')) === 'Error: m'; });
  return out.join('|');
})()""",
    ),

    // 数组变更方法随机序列模糊（空洞/负索引/Infinity/NaN 参数）
    DifferentialCase(
        "array-mutate-fuzz",
        """(function () {
  var out = [];
  var seed = 618033988;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  function pick(list) { return list[Math.floor(rnd() * list.length)]; }
  var bases = [[], [1, 2, 3], [1, , 3], [3, 1, 2], new Array(3), [0, 0, 0, 0, 0]];
  var values = ['x', 'y', 0, 7, undefined, NaN, null];
  var indices = [-3, -1, 0, 1, 2, 5, 2.5, undefined, NaN, Infinity];
  var counts = [-2, -1, 0, 1, 2, 3, undefined, Infinity];
  for (var i = 0; i < 100; i++) {
    var arr = pick(bases).slice();
    for (var step = 0; step < 5; step++) {
      var operation = Math.floor(rnd() * 10);
      try {
        if (operation === 0) arr.push(pick(values));
        else if (operation === 1) arr.pop();
        else if (operation === 2) arr.shift();
        else if (operation === 3) arr.unshift(pick(values));
        else if (operation === 4) {
          var extra = Math.floor(rnd() * 3);
          if (extra === 0) arr.splice(pick(indices), pick(counts));
          else if (extra === 1) arr.splice(pick(indices), pick(counts), pick(values));
          else arr.splice(pick(indices), pick(counts), pick(values), pick(values));
        } else if (operation === 5) arr.sort();
        else if (operation === 6) arr.reverse();
        else if (operation === 7) arr.fill(pick(values), pick(indices), pick(indices));
        else if (operation === 8) arr.copyWithin(pick(indices), pick(indices), pick(indices));
        else arr.length = Math.floor(rnd() * 6);
        out.push(JSON.stringify(arr) + '|' + Object.keys(arr).join(','));
      } catch (e) {
        out.push('THROW:' + e.name);
      }
    }
  }
  return out.join(';');
})()""",
    ),

    // String 非正则方法随机参数模糊（负索引/Infinity/NaN/填充与重复）
    DifferentialCase(
        "string-methods-fuzz",
        """(function () {
  var out = [];
  var seed = 271828182;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  function pick(list) { return list[Math.floor(rnd() * list.length)]; }
  var bases = ['', 'abc', 'aXbXc', '😀ab', '  trim  ', 'AbC', 'aaa', 'a😀b'];
  var search = ['a', 'X', '', '😀', 'ab', 'z'];
  var positions = [-5, -1, 0, 1, 2, 3, 5, 2.5, undefined, NaN, Infinity, -Infinity];
  var counts = [-1, 0, 1, 3, 5, 2.5, NaN];
  var fills = ['', 'z', '12', '😀'];
  for (var i = 0; i < 150; i++) {
    var text = pick(bases);
    for (var step = 0; step < 3; step++) {
      var operation = Math.floor(rnd() * 13);
      try {
        var value;
        if (operation === 0) value = JSON.stringify(text.slice(pick(positions), pick(positions)));
        else if (operation === 1) value = JSON.stringify(text.substring(pick(positions), pick(positions)));
        else if (operation === 2) value = JSON.stringify(text.substr(pick(positions), pick(positions)));
        else if (operation === 3) value = text.indexOf(pick(search), pick(positions));
        else if (operation === 4) value = text.lastIndexOf(pick(search), pick(positions));
        else if (operation === 5) value = text.includes(pick(search), pick(positions));
        else if (operation === 6) value = text.startsWith(pick(search), pick(positions));
        else if (operation === 7) value = text.endsWith(pick(search), pick(positions));
        else if (operation === 8) value = JSON.stringify(text.padStart(pick(counts), pick(fills)));
        else if (operation === 9) value = JSON.stringify(text.padEnd(pick(counts), pick(fills)));
        else if (operation === 10) value = JSON.stringify(text.repeat(pick(counts)));
        else if (operation === 11) value = JSON.stringify(text.concat(pick(bases), pick(search)));
        else value = JSON.stringify(text.at(pick(positions)));
        out.push(String(value));
      } catch (e) {
        out.push('THROW:' + e.name);
      }
    }
  }
  return out.join(';');
})()""",
    ),

    // RegExp lastIndex 状态机模糊（g/y/u/s、零宽匹配、NaN/Infinity/负索引）
    DifferentialCase(
        "regexp-lastindex-fuzz",
        """(function () {
  var out = [];
  var seed = 161803398;
  function rnd() { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648; }
  var definitions = [
    ['a', 'g'], ['a*', 'g'], ['', 'g'], ['^', 'g'], ['$', 'g'], ['.', 'g'],
    ['ab', 'g'], ['a|b', 'g'], ['\\d*', 'g'], ['(?=a)', 'g'], ['\\b', 'g'],
    ['a+?', 'g'], ['a', 'y'], ['a*', 'y'], ['', 'y'], ['ab', 'y'],
    ['a', 'gy'], ['a', 'gu'], ['.', 'gs'], ['b', 'y']
  ];
  var inputs = ['', 'a', 'aa', 'ab', 'ba', 'a1a', '\n', '😀a'];
  var lastIndexes = [0, 1, 2, 5, -1, NaN, Infinity, 2.5];
  for (var i = 0; i < definitions.length; i++) {
    for (var j = 0; j < inputs.length; j++) {
      var re = new RegExp(definitions[i][0], definitions[i][1]);
      var states = [];
      for (var step = 0; step < 4; step++) {
        re.lastIndex = lastIndexes[Math.floor(rnd() * lastIndexes.length)];
        var before = re.lastIndex;
        var match = re.exec(inputs[j]);
        states.push(JSON.stringify([
          before,
          match ? match[0] : null,
          match ? match.index : null,
          re.lastIndex
        ]));
      }
      out.push(states.join(','));
    }
  }
  return out.join(';');
})()""",
    ),

    // 多行 ^/$ 在 CRLF、NEL、U+2028 等行终止符上的位置
    DifferentialCase(
        "regexp-multiline-anchor-positions",
        """(function () {
  var inputs = ['a\u0085b', 'a\r\nb', 'a\u2028b', 'a\nb', '\u0085'];
  var out = [];
  for (var i = 0; i < inputs.length; i++) {
    var caret = /^/gm;
    var dollar = /$/gm;
    var match;
    var guard = 0;
    var carets = [];
    while ((match = caret.exec(inputs[i])) !== null && guard++ < 10) {
      carets.push(match.index);
      caret.lastIndex = match.index + 1;
    }
    guard = 0;
    var dollars = [];
    while ((match = dollar.exec(inputs[i])) !== null && guard++ < 10) {
      dollars.push(match.index);
      dollar.lastIndex = match.index + 1;
    }
    out.push(carets.join(',') + '/' + dollars.join(','));
  }
  return out.join(';');
})()""",
    ),

    // Annex B IdentityEscape 与 Java 专属转义（\A/\Z/\G/\R/\h//\e//\Q）
    DifferentialCase(
        "regexp-identity-escapes",
        """(function () {
  var patterns = [
    ['\\Z', ''], ['\\A', ''], ['\\G', ''], ['\\R', ''], ['\\h', ''], ['\\v', ''],
    ['\\e', ''], ['\\a', ''], ['\\Qa.b\\E', ''], ['\\x{41}', ''],
    ['\\u{41}', 'u'], ['\\u{41}', ''], ['\\c', ''], ['\\k', ''], ['\\p{L}', ''],
    ['\\N{LATIN CAPITAL LETTER A}', ''], ['\\n', ''], ['\\t', '']
  ];
  var inputs = ['Z', 'A', 'G', 'R', 'h', 'H', 'v', 'V', 'e', 'a', '\x1b', '\x07',
    'a.b', 'Qa.bE', 'x{41}', 'u{41}', 'A', 'u{41}', '\n', ' ', '\t', 'p{L}', 'N{LATIN CAPITAL LETTER A}', 'c', 'k'];
  var out = [];
  for (var i = 0; i < patterns.length; i++) {
    try {
      var re = new RegExp(patterns[i][0], patterns[i][1]);
      var bits = '';
      for (var j = 0; j < inputs.length; j++) bits += re.test(inputs[j]) ? '1' : '0';
      out.push(bits);
    } catch (e) {
      out.push('THROW:' + e.name);
    }
  }
  return out.join(';');
})()""",
    ),

    // Annex B 控制转义：字符类内数字/_ 控制字符，类外非法 \c 为字面反斜杠 + c
    DifferentialCase(
        "regexp-annexb-control-escapes",
        """(function () {
  var patterns = [/[\c!]/, /[\c ]/, /[\c]/, /\c!/, /\c /, /\c0/, /[\c0]/, /[\c_]/, /[\cA]/, /[\c00]+/, /[\c10]+/];
  var inputs = ['!', ' ', 'c', '\\c', '\\c!', '\\c ', '\x10', '\x1f', '\x01', '0', '_',
    '\x0f0\x10\x11', '\x100\x11\x12'];
  var out = [];
  for (var i = 0; i < patterns.length; i++) {
    var bits = '';
    for (var j = 0; j < inputs.length; j++) bits += patterns[i].test(inputs[j]) ? '1' : '0';
    out.push(bits);
  }
  return out.join(';');
})()""",
    ),

    // 非 v 模式字符类中的 && 是字面 &，不是 Java 的集合交集
    DifferentialCase(
        "regexp-class-ampersand",
        """(function () {
  var patterns = [/[a&&b]/, /[\w&&\d]/, /[a&b]/, /[a&&&b]/, /[a-z&&b]/, /[^a&&b]/];
  var inputs = ['a', 'b', '&', 'c', '1', '_', 'z', '2', '&&'];
  var out = [];
  for (var i = 0; i < patterns.length; i++) {
    var bits = '';
    for (var j = 0; j < inputs.length; j++) bits += patterns[i].test(inputs[j]) ? '1' : '0';
    out.push(bits);
  }
  return out.join(';');
})()""",
    ),

    // 字符类内 Annex B 转义（\B/\A/\Z/\G/\R/\h// 等）
    DifferentialCase(
        "regexp-class-escapes",
        """(function () {
  var patterns = [/[\B]/, /[\A]/, /[\Z]/, /[\G]/, /[\R]/, /[\h]/, /[\v]/, /[\b]/,
    /[\cA]/, /[\x]/, /[\u]/, /[\e]/, /[\a]/];
  var inputs = ['B', 'A', 'Z', 'G', 'R', 'h', 'e', 'a', '\u000B', '\t', '\b',
    '\u0001', 'x', 'u', 'c'];
  var out = [];
  for (var i = 0; i < patterns.length; i++) {
    var bits = '';
    for (var j = 0; j < inputs.length; j++) bits += patterns[i].test(inputs[j]) ? '1' : '0';
    out.push(bits);
  }
  return out.join(';');
})()""",
    ),

    // 正则字面量/字符类中的 ] } { 与 \q{a}、\8 等边界字符
    DifferentialCase(
        "regexp-class-literals",
        """(function () {
  var patterns = [/]/, /}/, /{/, /a{/, /a{1/, /[a~~b]/, /[\q{a}]/,
    /[\8]/, /[\9]/, /[[]/, /[.^$]/];
  var inputs = [']', '}', '{', 'a', '-', 'b', '~', 'q', '{a}', '8', '9', '[', 'x', 'a{', '.', '^', '$'];
  var out = [];
  for (var i = 0; i < patterns.length; i++) {
    var bits = '';
    for (var j = 0; j < inputs.length; j++) bits += patterns[i].test(inputs[j]) ? '1' : '0';
    out.push(bits);
  }
  return out.join(';');
})()""",
    ),

    // 字符类范围边界（a-b-c、a-\d、\w- 等）通过构造函数验证
    DifferentialCase(
        "regexp-class-ranges",
        """(function () {
  var sources = ['[a-b-c]', '[a-]', '[-a]', '[--]', '[a-\\d]', '[\\d-a]', '[\\w-]', '[a-\\w]', '[\\s-]'];
  var inputs = ['a', 'b', 'c', '-', '1', '_', 'd', 'x', 'w', ' ', 'z'];
  var out = [];
  for (var i = 0; i < sources.length; i++) {
    try {
      var re = new RegExp(sources[i]);
      var bits = '';
      for (var j = 0; j < inputs.length; j++) bits += re.test(inputs[j]) ? '1' : '0';
      out.push(bits);
    } catch (e) {
      out.push('THROW:' + e.name);
    }
  }
  return out.join(';');
})()""",
    ),

    // ToPrimitive 提示顺序、抽象相等与关系比较的转换矩阵
    DifferentialCase(
        "coercion-matrix",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { return [([] == false), ([] == ''), ([0] == false), ([''] == 0), ([null] == ''), ([undefined] == ''), ([] == 0)].join(','); });
  push(function () { return [({} == '[object Object]'), (null == 0), (undefined == 0), (null == undefined), (null == false), (undefined == false)].join(','); });
  push(function () { return [('0' == false), ('0' == 0), ('' == 0), ('  ' == 0), ('1' == true)].join(','); });
  push(function () { var log = []; var o = { valueOf: function () { log.push('v'); return 1; }, toString: function () { log.push('s'); return 'x'; } }; var sum = o + ''; return sum + ':' + log.join(','); });
  push(function () { var log = []; var o = { valueOf: function () { log.push('v'); return 1; }, toString: function () { log.push('s'); return 'x'; } }; var sum = '' + o; return sum + ':' + log.join(','); });
  push(function () { var log = []; var o = { valueOf: function () { log.push('v'); return 1; }, toString: function () { log.push('s'); return 'x'; } }; var n = +o; return n + ':' + log.join(','); });
  push(function () { var log = []; var o = { [Symbol.toPrimitive]: function (hint) { log.push(hint); return 7; }, valueOf: function () { log.push('v'); return 1; }, toString: function () { log.push('s'); return 'x'; } }; var a = o + 1; var b = String(o); var c = o < 8; var d = o == 7; return a + ':' + b + ':' + c + ':' + d + ':' + log.join(','); });
  push(function () { var d = new Date(0); return (typeof (d + 1)) + ':' + (d < 1) + ':' + (d == 0); });
  push(function () { var o = { [Symbol.toPrimitive]: function () { return {}; } }; return String(o); });
  push(function () { var o = { valueOf: function () { throw new Error('v'); }, toString: function () { return 't'; } }; return String(o) + ':' + (o + ''); });
  push(function () { return [('2' < '10'), (2 < '10'), ('a' < 1), (null < 1), (undefined < 1), (true < 2), (null >= 0)].join(','); });
  push(function () { return [NaN == NaN, Object.is(NaN, NaN), +[], +[1], +[1, 2], +{}].join(','); });
  push(function () { return [!([]), !(''), !!(0), !!('0')].join(','); });
  push(function () { return [String([1, null, undefined, 3]), String([, , ]), String([]), String({})].join('|'); });
  push(function () { var a = [1]; a.toString = function () { return 'A'; }; return (a == 'A') + ':' + (a == 1); });
  push(function () { var log = []; var o = { valueOf: function () { log.push('v'); return {}; }, toString: function () { log.push('s'); return {}; } }; try { +o; return 'none'; } catch (e) { return e.name + ':' + log.join(','); } });
  return out.join(';');
})()""",
    ),

    // 非 ASCII 与 u 模式下 /\B 的词边界判定
    DifferentialCase(
        "regexp-word-boundary-unicode",
        """(function () {
  var cases = [
    [/\bé/, 'é'], [/\bé\u0020/, 'é '], [/\bé\b/, ' é '], [/\Bé/, 'é'],
    [/\bα/, 'α'], [/\bα\b/, ' α '], [/\Bα/, 'α'], [/\b😀/u, '😀'],
    [/\b\w/u, 'é'], [/\b\w/u, 'a'], [/\B\w/u, 'é'], [/\b\u00E9/u, 'é'],
    [/\b./u, 'é'], [/\B./u, 'é'], [/\b\s/, ' é'], [/\b\d/, '1']
  ];
  var out = [];
  for (var i = 0; i < cases.length; i++) {
    out.push(cases[i][0].test(cases[i][1]) ? '1' : '0');
  }
  return out.join('');
})()""",
    ),

    // 正则命名组：Unicode 17 ID_Start/Continue、$/_、astral 与 ZWNJ
    DifferentialCase(
        "regexp-group-names",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () { return new RegExp('(?<π>x)').exec('x').groups['π']; });
  push(function () { return new RegExp('(?<𐐀>x)').exec('x').groups['𐐀']; });
  push(function () { return new RegExp('(?<_$1x>a)').exec('a').groups['_$1x']; });
  push(function () { return new RegExp('(?<é>x)').exec('x').groups['é']; });
  push(function () { try { new RegExp('(?<1a>x)'); return 'ok'; } catch (e) { return e.name; } });
  push(function () { try { new RegExp('(?<a\u200Cb>x)'); return 'ok'; } catch (e) { return e.name; } });
  return out.join('|');
})()""",
    ),

    // \p{...} 在 i+u 下的大小写折叠（含 astral/ß/Kelvin 等）
    DifferentialCase(
        "regexp-property-casefold",
        """(function () {
  var patterns = [
    new RegExp('\\p{Lu}', 'iu'), new RegExp('\\p{Ll}', 'iu'),
    new RegExp('\\p{Lt}', 'iu'), new RegExp('\\P{Lu}', 'iu'),
    new RegExp('\\p{Alphabetic}', 'iu'), new RegExp('\\p{Script=Greek}', 'iu'),
    new RegExp('\\p{Script=Latin}', 'iu'), new RegExp('\\p{Lu}', 'u')
  ];
  var inputs = ['a', 'A', 'é', 'É', 'π', 'Π', 'ß', 'ẞ', 'K', 'k', 'K',
    '𐐀', '𐐨', '𐐩', '😀', '1', '_'];
  var out = [];
  for (var i = 0; i < patterns.length; i++) {
    var bits = '';
    for (var j = 0; j < inputs.length; j++) bits += patterns[i].test(inputs[j]) ? '1' : '0';
    out.push(bits);
  }
  return out.join(';');
})()""",
    ),

    // 字符类单点/范围在 i+u/i+v 下的 astral 与 ß/Kelvin 折叠
    DifferentialCase(
        "regexp-class-casefold-astral",
        """(function () {
  var patterns = [
    new RegExp('[\\u{10400}-\\u{10427}]', 'iu'),
    new RegExp('[\\u{10428}]', 'iu'),
    new RegExp('[A-Z]', 'iu'),
    new RegExp('[ß-ß]', 'iu'),
    new RegExp('[K-K]', 'iu'),
    new RegExp('[\\u{10400}-\\u{10427}]', 'iv'),
    new RegExp('[\\u{10428}]', 'iv')
  ];
  var inputs = ['𐐀', '𐐨', '𐐩', 'A', 'a', 'K', 'K', 'k', 'ẞ', 'ß', '😀'];
  var out = [];
  for (var i = 0; i < patterns.length; i++) {
    var bits = '';
    for (var j = 0; j < inputs.length; j++) bits += patterns[i].test(inputs[j]) ? '1' : '0';
    out.push(bits);
  }
  return out.join(';');
})()""",
    ),

    // 反向引用在 i+u 下的折叠（astral/Kelvin/ß、命名组与后向断言）
    DifferentialCase(
        "regexp-backref-casefold",
        """(function () {
  var out = [];
  out.push(/(a)\1/iu.test('aA') ? '1' : '0');
  out.push(/(K)\1/iu.test('KK') ? '1' : '0');
  out.push(/(ß)\1/iu.test('ßẞ') ? '1' : '0');
  out.push(/(𐐀)\1/iu.test('𐐀𐐨') ? '1' : '0');
  out.push(/(𐐨)\1/iu.test('𐐨𐐀') ? '1' : '0');
  out.push(/(𐐀)\1/iu.test('𐐀𐐩') ? '1' : '0');
  out.push(/(?<x>a)\k<x>/iu.test('aA') ? '1' : '0');
  out.push(/(?<=(a)\1)b/iu.test('aAb') ? '1' : '0');
  out.push(/(?<=(𐐀)\1)b/iu.test('𐐀𐐨b') ? '1' : '0');
  var match = /(a)\1/giu.exec('aA');
  out.push(match && match[0] === 'aA' ? '1' : '0');
  return out.join('');
})()""",
    ),

    // String.match/replace/search/split/matchAll 对自定义 Symbol 协议的委托
    DifferentialCase(
        "string-symbol-protocol",
        """(function () {
  var out = [];
  function push(fn) { try { out.push(String(fn())); } catch (e) { out.push('THROW:' + e.name); } }
  push(function () {
    var o = {};
    o[Symbol.match] = function (s) { return 'M:' + s + ':' + (this === o); };
    return 'abc'.match(o);
  });
  push(function () {
    var o = {};
    o[Symbol.replace] = function (s, r) { return 'R:' + s + ':' + r; };
    return 'abc'.replace(o, 'x');
  });
  push(function () {
    var o = {};
    o[Symbol.search] = function (s) { return 'Q:' + s; };
    return 'abc'.search(o);
  });
  push(function () {
    var o = {};
    o[Symbol.split] = function (s, limit) { return ['S', s, limit]; };
    return 'abc'.split(o, 3).join('|');
  });
  push(function () {
    var o = {};
    o[Symbol.matchAll] = function (s) { return [s]; };
    return Array.from('abc'.matchAll(o)).join(',');
  });
  push(function () { var o = {}; o[Symbol.match] = 1; return 'a'.match(o); });
  push(function () { var o = {}; o[Symbol.replace] = 1; return 'a'.replace(o, 'x'); });
  push(function () {
    var o = {};
    o[Symbol.match] = function () { return null; };
    return String('a'.match(o));
  });
  push(function () {
    var o = {};
    o[Symbol.split] = function () { return []; };
    return 'a'.split(o).length;
  });
  return out.join(';');
})()""",
    ),

    // v 模式 \q 字符串析取在 i 标志下的码点折叠
    DifferentialCase(
        "regexp-v-string-casefold",
        """(function () {
  var patterns = [
    new RegExp('[\\q{ab}]', 'iv'),
    new RegExp('[\\q{ab}]', 'v')
  ];
  var inputs = ['ab', 'AB', 'Ab', 'aB', '𐐨', '𐐀', 'x'];
  var out = [];
  for (var i = 0; i < patterns.length; i++) {
    var bits = '';
    for (var j = 0; j < inputs.length; j++) bits += patterns[i].test(inputs[j]) ? '1' : '0';
    out.push(bits);
  }
  return out.join(';');
})()""",
    ),

    // npm semver 风格正则（嵌套可选组/惰性星号/字符类）回退引擎回归
    DifferentialCase(
        "regexp-semver-pattern",
        """(function () {
  var re = /^[v^~<>=]*?(\d+)(?:\.([x*]|\d+)(?:\.([x*]|\d+)(?:\.([x*]|\d+))?(?:-([\da-z\-]+(?:\.[\da-z\-]+)*))?(?:\+[\da-z\-]+(?:\.[\da-z\-]+)*)?)?)?$/i;
  var inputs = ['1.2.3', '1.2', 'v1.2.3', '1.2.3-rc.1', '1.2.3+build', 'foo', '>=1.2.3'];
  var out = [];
  for (var i = 0; i < inputs.length; i++) out.push(re.test(inputs[i]) ? '1' : '0');
  return out.join('');
})()""",
    ),
)
