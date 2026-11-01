package com.muedsa.js.conformance

/**
 * 固定版本的真实 JS 库清单（浏览器/UMD 构建）。
 */
internal data class LibrarySpec(
    val name: String,
    val url: String,
    val globalName: String,
    val cases: List<SmokeCase>,
    /** 需要先在同一引擎中加载的依赖库（按 name 对应语料文件） */
    val dependencies: List<String> = emptyList(),
)

internal data class SmokeCase(
    val name: String,
    val source: String,
    /** 异步用例：脚本在微任务中把布尔结果写入 globalThis.__libResult */
    val async: Boolean = false,
)

internal val librarySpecs: List<LibrarySpec> = listOf(
    LibrarySpec(
        name = "lodash",
        url = "https://cdn.jsdelivr.net/npm/lodash@4.17.21/lodash.min.js",
        globalName = "_",
        cases = listOf(
            SmokeCase(
                "chunk",
                assertion("_.chunk([1,2,3,4,5], 2).length === 3"),
            ),
            SmokeCase(
                "camelCase",
                assertion("_.camelCase('Foo Bar') === 'fooBar'"),
            ),
            SmokeCase(
                "cloneDeep",
                assertion(
                    "(function () { var source = { a: [1, 2] }; var copy = _.cloneDeep(source); " +
                        "return copy.a[0] === 1 && copy.a !== source.a; })()"
                ),
            ),
            SmokeCase(
                "escape",
                assertion("_.escape('<a>') === '&lt;a&gt;'"),
            ),
            SmokeCase(
                "sortBy",
                assertion("_.sortBy([3, 1, 2]).join(',') === '1,2,3'"),
            ),
            SmokeCase(
                "uniq",
                assertion("_.uniq([1, 1, 2]).length === 2"),
            ),
            SmokeCase(
                "template",
                assertion("_.template('hi <%= name %>')({ name: 'x' }) === 'hi x'"),
            ),
            SmokeCase(
                "merge",
                assertion("_.merge({ a: 1 }, { b: 2 }).b === 2"),
            ),
        ),
    ),
    LibrarySpec(
        name = "mustache",
        url = "https://cdn.jsdelivr.net/npm/mustache@4.2.0/mustache.min.js",
        globalName = "Mustache",
        cases = listOf(
            SmokeCase(
                "render",
                assertion("Mustache.render('Hello {{name}}', { name: 'World' }) === 'Hello World'"),
            ),
            SmokeCase(
                "section",
                assertion("Mustache.render('{{#list}}{{.}},{{/list}}', { list: [1, 2] }) === '1,2,'"),
            ),
            SmokeCase(
                "escape",
                assertion("Mustache.escape('<') === '&lt;'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "dayjs",
        url = "https://cdn.jsdelivr.net/npm/dayjs@1.11.10/dayjs.min.js",
        globalName = "dayjs",
        cases = listOf(
            SmokeCase(
                "year",
                assertion("dayjs(0).year() === 1970"),
            ),
            SmokeCase(
                "format",
                assertion("dayjs(0).format('YYYY-MM-DD') === '1970-01-01'"),
            ),
            SmokeCase(
                "add day",
                assertion("dayjs(0).add(1, 'day').date() === 2"),
            ),
        ),
    ),
    LibrarySpec(
        name = "qs",
        url = "https://cdn.jsdelivr.net/npm/qs@6.12.3/dist/qs.js",
        globalName = "Qs",
        cases = listOf(
            SmokeCase(
                "parse",
                assertion("Qs.parse('a=1&b=2').b === '2'"),
            ),
            SmokeCase(
                "stringify",
                assertion("Qs.stringify({ a: 'x' }) === 'a=x'"),
            ),
            SmokeCase(
                "parse array",
                assertion("Qs.parse('a[]=1&a[]=2').a.length === 2"),
            ),
            SmokeCase(
                "parse nested",
                assertion("Qs.parse('a[b]=1').a.b === '1'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "validator",
        url = "https://cdn.jsdelivr.net/npm/validator@13.12.0/validator.min.js",
        globalName = "validator",
        cases = listOf(
            SmokeCase(
                "isEmail true",
                assertion("validator.isEmail('a@b.com') === true"),
            ),
            SmokeCase(
                "isEmail false",
                assertion("validator.isEmail('not-an-email') === false"),
            ),
            SmokeCase(
                "isURL",
                assertion("validator.isURL('https://example.com') === true"),
            ),
            SmokeCase(
                "isNumeric",
                assertion("validator.isNumeric('123') === true"),
            ),
        ),
    ),
    LibrarySpec(
        name = "underscore",
        url = "https://cdn.jsdelivr.net/npm/underscore@1.13.6/underscore-umd-min.js",
        globalName = "_",
        cases = listOf(
            SmokeCase(
                "map",
                assertion("_.map([1, 2, 3], function (x) { return x * 2; }).join(',') === '2,4,6'"),
            ),
            SmokeCase(
                "groupBy",
                assertion("_.groupBy([1, 2, 3, 4], function (x) { return x % 2; })[0].length === 2"),
            ),
            SmokeCase(
                "template",
                assertion("_.template('hi <%= name %>')({ name: 'x' }) === 'hi x'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "moment",
        url = "https://cdn.jsdelivr.net/npm/moment@2.30.1/min/moment.min.js",
        globalName = "moment",
        cases = listOf(
            SmokeCase(
                "utc format",
                assertion("moment(0).utc().format('YYYY-MM-DD') === '1970-01-01'"),
            ),
            SmokeCase(
                "add day",
                assertion("moment('2020-01-02').add(1, 'day').date() === 3"),
            ),
            SmokeCase(
                "duration",
                assertion("moment.duration(90, 'minutes').asHours() === 1.5"),
            ),
        ),
    ),
    LibrarySpec(
        name = "semver",
        url = "https://cdn.jsdelivr.net/npm/semver@4.3.6/semver.browser.js",
        globalName = "semver",
        cases = listOf(
            SmokeCase(
                "valid",
                assertion("semver.valid('1.2.3') === '1.2.3'"),
            ),
            SmokeCase(
                "invalid",
                assertion("semver.valid('a.b.c') === null"),
            ),
            SmokeCase(
                "compare",
                assertion("semver.compare('1.2.3', '1.10.0') === -1"),
            ),
            SmokeCase(
                "satisfies",
                assertion("semver.satisfies('1.2.3', '>=1.0.0 <2.0.0') === true"),
            ),
            SmokeCase(
                "major",
                assertion("semver.major('1.2.3') === 1"),
            ),
            SmokeCase(
                "inc",
                assertion("semver.inc('1.2.3', 'minor') === '1.3.0'"),
            ),
            SmokeCase(
                "sort",
                assertion("semver.sort(['1.10.0', '1.2.3']).join(',') === '1.2.3,1.10.0'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "punycode",
        url = "https://cdn.jsdelivr.net/npm/punycode@1.4.1/punycode.js",
        globalName = "punycode",
        cases = listOf(
            SmokeCase(
                "toASCII",
                assertion("punycode.toASCII('mañana.com') === 'xn--maana-pta.com'"),
            ),
            SmokeCase(
                "toUnicode",
                assertion("punycode.toUnicode('xn--maana-pta.com') === 'mañana.com'"),
            ),
            SmokeCase(
                "encode",
                assertion("punycode.encode('例え') === 'r8jz45g'"),
            ),
            SmokeCase(
                "decode",
                assertion("punycode.decode('r8jz45g') === '例え'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "json5",
        url = "https://cdn.jsdelivr.net/npm/json5@2.2.3/dist/index.min.js",
        globalName = "JSON5",
        cases = listOf(
            SmokeCase(
                "parse relaxed",
                assertion("JSON5.parse('{a: 1,}').a === 1"),
            ),
            SmokeCase(
                "parse string",
                assertion("JSON5.parse(\"'x'\") === 'x'"),
            ),
            SmokeCase(
                "stringify",
                assertion("JSON5.stringify({ a: 1 }) === '{a:1}'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "big.js",
        url = "https://cdn.jsdelivr.net/npm/big.js@6.2.1/big.min.js",
        globalName = "Big",
        cases = listOf(
            SmokeCase(
                "plus",
                assertion("new Big('0.1').plus('0.2').toString() === '0.3'"),
            ),
            SmokeCase(
                "times toFixed",
                assertion("Big(123.456).times(2).toFixed(2) === '246.91'"),
            ),
            SmokeCase(
                "div",
                assertion("Big('1e9').div(3).toFixed(3) === '333333333.333'"),
            ),
            SmokeCase(
                "pow cmp",
                assertion("new Big(255).pow(2).eq(65025) && Big(1).cmp(2) === -1"),
            ),
            SmokeCase(
                "mod round",
                assertion("Big(10).mod(3).toNumber() === 1 && new Big('123.45').round(1).toString() === '123.5'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "diff",
        url = "https://cdn.jsdelivr.net/npm/diff@5.1.0/dist/diff.min.js",
        globalName = "Diff",
        cases = listOf(
            SmokeCase(
                "diffChars",
                assertion(
                    "(function () { var parts = Diff.diffChars('abc', 'abd'); " +
                        "return parts.length === 3 && parts[0].value === 'ab' && " +
                        "parts[1].removed === true && parts[1].value === 'c' && " +
                        "parts[2].added === true && parts[2].value === 'd'; })()"
                ),
            ),
            SmokeCase(
                "diffArrays",
                assertion(
                    "(function () { var parts = Diff.diffArrays([1, 2, 3], [1, 3]); " +
                        "return parts.length === 3 && parts[1].removed === true && " +
                        "parts[1].value.join(',') === '2'; })()"
                ),
            ),
            SmokeCase(
                "diffWords",
                assertion(
                    "(function () { var parts = Diff.diffWords('one two', 'one three'); " +
                        "return parts.length === 3 && parts[1].removed === true && " +
                        "parts[2].added === true; })()"
                ),
            ),
        ),
    ),

    LibrarySpec(
        name = "lz-string",
        url = "https://unpkg.com/lz-string@1.5.0/libs/lz-string.min.js",
        globalName = "LZString",
        cases = listOf(
            SmokeCase(
                "base64 roundtrip",
                assertion(
                    "(function () { var input = 'hello hello hello'; " +
                        "return LZString.decompressFromBase64(LZString.compressToBase64(input)) === input; })()"
                ),
            ),
            SmokeCase(
                "utf16 roundtrip",
                assertion(
                    "(function () { var input = '你好，世界'; " +
                        "return LZString.decompressFromUTF16(LZString.compressToUTF16(input)) === input; })()"
                ),
            ),
            SmokeCase(
                "uri component roundtrip",
                assertion(
                    "(function () { var input = 'a-b_c~d'; " +
                        "return LZString.decompressFromEncodedURIComponent(" +
                        "LZString.compressToEncodedURIComponent(input)) === input; })()"
                ),
            ),
            SmokeCase(
                "compress short",
                assertion(
                    "(function () { var input = 'abc'; " +
                        "return LZString.decompress(LZString.compress(input)) === input; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "marked",
        url = "https://unpkg.com/marked@4.3.0/marked.min.js",
        globalName = "marked",
        cases = listOf(
            SmokeCase(
                "heading",
                assertion("marked.parse('# Hi').indexOf('<h1 id=\"hi\">Hi</h1>') === 0"),
            ),
            SmokeCase(
                "strong",
                assertion("marked.parse('**bold**').indexOf('<strong>bold</strong>') >= 0"),
            ),
            SmokeCase(
                "list",
                assertion(
                    "marked.parse('- a\\n- b').indexOf('<li>a</li>') >= 0 && " +
                        "marked.parse('- a\\n- b').indexOf('<ul>') >= 0"
                ),
            ),
            SmokeCase(
                "inline code link",
                assertion(
                    "marked.parse('`x`').indexOf('<code>x</code>') >= 0 && " +
                        "marked.parse('[x](https://a)').indexOf('<a href=\"https://a\">x</a>') >= 0"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "bignumber.js",
        url = "https://unpkg.com/bignumber.js@9.1.2/bignumber.js",
        globalName = "BigNumber",
        cases = listOf(
            SmokeCase(
                "plus",
                assertion("new BigNumber('0.1').plus('0.2').toString() === '0.3'"),
            ),
            SmokeCase(
                "div toFixed",
                assertion("BigNumber('1e9').dividedBy(3).toFixed(3) === '333333333.333'"),
            ),
            SmokeCase(
                "pow and isBigNumber",
                assertion(
                    "new BigNumber(255).exponentiatedBy(2).isEqualTo(65025) && " +
                        "BigNumber.isBigNumber(new BigNumber(1))"
                ),
            ),
            SmokeCase(
                "max decimalPlaces",
                assertion(
                    "BigNumber.max('1.2', '3.4', '0.5').toString() === '3.4' && " +
                        "new BigNumber('123.456').decimalPlaces() === 3"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "decimal.js",
        url = "https://unpkg.com/decimal.js@10.4.3/decimal.js",
        globalName = "Decimal",
        cases = listOf(
            SmokeCase(
                "plus",
                assertion("new Decimal('0.1').plus('0.2').toString() === '0.3'"),
            ),
            SmokeCase(
                "div toFixed",
                assertion("Decimal.div(1, 3).toFixed(5) === '0.33333'"),
            ),
            SmokeCase(
                "pow toNumber",
                assertion("new Decimal(2).pow(10).toNumber() === 1024"),
            ),
            SmokeCase(
                "max",
                assertion("Decimal.max(1, 5, 3).toString() === '5'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "crypto-js",
        url = "https://unpkg.com/crypto-js@4.2.0/crypto-js.js",
        globalName = "CryptoJS",
        cases = listOf(
            SmokeCase(
                "sha256",
                assertion(
                    "CryptoJS.SHA256('abc').toString() === " +
                        "'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'"
                ),
            ),
            SmokeCase(
                "md5",
                assertion(
                    "CryptoJS.MD5('abc').toString() === '900150983cd24fb0d6963f7d28e17f72'"
                ),
            ),
            SmokeCase(
                "hmac sha1",
                assertion(
                    "CryptoJS.HmacSHA1('message', 'key').toString() === " +
                        "'2088df74d5f2146b48146caf4965377e9d0be3a4'"
                ),
            ),
            SmokeCase(
                "base64",
                assertion(
                    "CryptoJS.enc.Base64.stringify(CryptoJS.enc.Utf8.parse('hello')) === 'aGVsbG8='"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "papaparse",
        url = "https://cdn.jsdelivr.net/npm/papaparse@5.4.1/papaparse.min.js",
        globalName = "Papa",
        cases = listOf(
            SmokeCase(
                "parse",
                assertion("Papa.parse('a,b\\n1,2').data[1][1] === '2'"),
            ),
            SmokeCase(
                "parse header",
                assertion("Papa.parse('a,b\\n1,2', { header: true }).data[0].a === '1'"),
            ),
            SmokeCase(
                "unparse",
                assertion("Papa.unparse([[1, 2], [3, 4]]) === '1,2\\r\\n3,4'"),
            ),
            SmokeCase(
                "dynamicTyping",
                assertion("Papa.parse('a\\n1', { dynamicTyping: true }).data[1][0] === 1"),
            ),
        ),
    ),
    LibrarySpec(
        name = "js-yaml",
        url = "https://cdn.jsdelivr.net/npm/js-yaml@4.1.0/dist/js-yaml.min.js",
        globalName = "jsyaml",
        cases = listOf(
            SmokeCase(
                "load",
                assertion("jsyaml.load('a: 1\\nb: [2, 3]').b[1] === 3"),
            ),
            SmokeCase(
                "dump",
                assertion("jsyaml.dump({ a: 1 }) === 'a: 1\\n'"),
            ),
            SmokeCase(
                "anchor alias",
                assertion("jsyaml.load('a: &x 1\\nb: *x').b === 1"),
            ),
            SmokeCase(
                "loadAll",
                assertion("jsyaml.loadAll('---\\na: 1\\n---\\nb: 2').length === 2"),
            ),
        ),
    ),
    LibrarySpec(
        name = "ramda",
        url = "https://cdn.jsdelivr.net/npm/ramda@0.29.0/dist/ramda.min.js",
        globalName = "R",
        cases = listOf(
            SmokeCase(
                "map add",
                assertion("R.map(R.add(1), [1, 2, 3]).join(',') === '2,3,4'"),
            ),
            SmokeCase(
                "pipe",
                assertion("R.pipe(R.add(1), R.multiply(2))(3) === 8"),
            ),
            SmokeCase(
                "groupBy prop",
                assertion(
                    "R.groupBy(R.prop('a'), [{ a: 'x' }, { a: 'y' }, { a: 'x' }]).x.length === 2"
                ),
            ),
            SmokeCase(
                "zip assoc",
                assertion("R.zip([1, 2], ['a', 'b'])[1][0] === 2 && R.assoc('b', 2, { a: 1 }).b === 2"),
            ),
        ),
    ),
    LibrarySpec(
        name = "he",
        url = "https://cdn.jsdelivr.net/npm/he@1.2.0/he.js",
        globalName = "he",
        cases = listOf(
            SmokeCase(
                "encode decode",
                assertion("he.decode('&lt;&gt;&amp;') === '<>&'"),
            ),
            SmokeCase(
                "roundtrip",
                assertion("he.decode(he.encode('<script>')) === '<script>'"),
            ),
            SmokeCase(
                "escape unescape",
                assertion("he.escape('<') === '&lt;' && he.unescape('&amp;') === '&'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "numeral",
        url = "https://cdn.jsdelivr.net/npm/numeral@2.0.6/min/numeral.min.js",
        globalName = "numeral",
        cases = listOf(
            SmokeCase(
                "thousands",
                assertion("numeral(1234.56).format('0,0.00') === '1,234.56'"),
            ),
            SmokeCase(
                "percent",
                assertion("numeral('0.5').format('0.0%') === '50.0%'"),
            ),
            SmokeCase(
                "abbreviation",
                assertion("numeral(1000).format('0a') === '1k'"),
            ),
            SmokeCase(
                "round",
                assertion("numeral(12.345).format('0.0') === '12.3'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "showdown",
        url = "https://cdn.jsdelivr.net/npm/showdown@2.1.0/dist/showdown.min.js",
        globalName = "showdown",
        cases = listOf(
            SmokeCase(
                "heading",
                assertion("new showdown.Converter().makeHtml('# Hi').indexOf('<h1') >= 0"),
            ),
            SmokeCase(
                "bold",
                assertion(
                    "new showdown.Converter().makeHtml('**b**').indexOf('<strong>b</strong>') >= 0"
                ),
            ),
            SmokeCase(
                "link",
                assertion(
                    "new showdown.Converter().makeHtml('[x](https://a)').indexOf('href=\"https://a\"') >= 0"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "seedrandom",
        url = "https://cdn.jsdelivr.net/npm/seedrandom@3.0.5/seedrandom.min.js",
        globalName = "Math.seedrandom",
        cases = listOf(
            // 浏览器/全局模式下 seedrandom 覆盖 Math.random 并返回内部种子串
            SmokeCase(
                "deterministic",
                assertion(
                    "(function () { Math.seedrandom('hello'); var x = Math.random(); " +
                        "Math.seedrandom('hello'); var y = Math.random(); " +
                        "return x === y && x >= 0 && x < 1; })()"
                ),
            ),
            SmokeCase(
                "different seeds",
                assertion(
                    "(function () { Math.seedrandom('seed-a'); var x = Math.random(); " +
                        "Math.seedrandom('seed-b'); var y = Math.random(); return x !== y; })()"
                ),
            ),
            SmokeCase(
                "api",
                assertion("typeof Math.seedrandom === 'function'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "pluralize",
        url = "https://cdn.jsdelivr.net/npm/pluralize@8.0.0/pluralize.js",
        globalName = "pluralize",
        cases = listOf(
            SmokeCase(
                "plural",
                assertion("pluralize('cat') === 'cats'"),
            ),
            SmokeCase(
                "count",
                assertion("pluralize('cat', 1) === 'cat' && pluralize('cat', 2) === 'cats'"),
            ),
            SmokeCase(
                "irregular",
                assertion("pluralize('person') === 'people'"),
            ),
            SmokeCase(
                "singular",
                assertion("pluralize.singular('dogs') === 'dog'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "highlight.js",
        url = "https://cdn.jsdelivr.net/npm/@highlightjs/cdn-assets@11.9.0/highlight.min.js",
        globalName = "hljs",
        cases = listOf(
            SmokeCase(
                "highlight javascript",
                assertion(
                    "hljs.highlight('var x = 1;', { language: 'javascript' }).value" +
                        ".indexOf('hljs-keyword') >= 0"
                ),
            ),
            SmokeCase(
                "language registry",
                assertion("hljs.getLanguage('javascript') !== undefined"),
            ),
            SmokeCase(
                "highlightAuto",
                assertion("hljs.highlightAuto('const x = 1;').value.length > 0"),
            ),
        ),
    ),
    LibrarySpec(
        name = "tinycolor2",
        url = "https://cdn.jsdelivr.net/npm/tinycolor2@1.6.0/dist/tinycolor-min.js",
        globalName = "tinycolor",
        cases = listOf(
            SmokeCase(
                "hex",
                assertion("tinycolor('#ff0000').toHexString() === '#ff0000'"),
            ),
            SmokeCase(
                "rgb",
                assertion("tinycolor('rgb(0, 128, 255)').toRgb().g === 128"),
            ),
            SmokeCase(
                "alpha",
                assertion("tinycolor('rgba(0,0,0,0.5)').getAlpha() === 0.5"),
            ),
            SmokeCase(
                "hsl",
                assertion("tinycolor('hsl(120, 100%, 50%)').toHexString() === '#00ff00'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "deepmerge",
        url = "https://cdn.jsdelivr.net/npm/deepmerge@4.3.1/dist/umd.js",
        globalName = "deepmerge",
        cases = listOf(
            SmokeCase(
                "nested merge",
                assertion(
                    "(function () { var o = deepmerge({ a: { b: 1 } }, { a: { c: 2 } }); " +
                        "return o.a.b === 1 && o.a.c === 2; })()"
                ),
            ),
            SmokeCase(
                "overwrite",
                assertion("deepmerge({ a: 1 }, { a: 2 }).a === 2"),
            ),
            SmokeCase(
                "array concat",
                assertion("deepmerge([1, 2], [3]).join(',') === '1,2,3'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "big-integer",
        url = "https://cdn.jsdelivr.net/npm/big-integer@1.6.51/BigInteger.js",
        globalName = "bigInt",
        cases = listOf(
            SmokeCase(
                "multiply",
                assertion(
                    "bigInt('12345678901234567890').multiply('98765432109876543210').toString()" +
                        " === '1219326311370217952237463801111263526900'"
                ),
            ),
            SmokeCase(
                "pow",
                assertion("bigInt(2).pow(100).toString() === '1267650600228229401496703205376'"),
            ),
            SmokeCase(
                "beyond double precision",
                assertion("bigInt('9007199254740993').add(1).toString() === '9007199254740994'"),
            ),
            SmokeCase(
                "gcd and mod",
                assertion(
                    "bigInt.gcd(48, 18).toString() === '6' && bigInt(-7).mod(3).toString() === '-1'"
                ),
            ),
            SmokeCase(
                "radix and divide",
                assertion(
                    "bigInt('deadbeef', 16).toString(10) === '3735928559' && " +
                        "bigInt('12345678901234567890').divide('3').toString()" +
                        " === '4115226300411522630' && " +
                        "bigInt('12345678901234567890').remainder('7').toString() === '1'"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "rxjs",
        url = "https://cdn.jsdelivr.net/npm/rxjs@7.8.1/dist/bundles/rxjs.umd.min.js",
        globalName = "rxjs",
        cases = listOf(
            SmokeCase(
                "map",
                assertion(
                    "(function () { var out = ''; rxjs.of(1, 2, 3).pipe(rxjs.map(function (x) { " +
                        "return x * 2; })).subscribe(function (v) { out += v; }); return out === '246'; })()"
                ),
            ),
            SmokeCase(
                "filter",
                assertion(
                    "(function () { var out = ''; rxjs.of(1, 2, 3, 4).pipe(rxjs.filter(function (x) { " +
                        "return x % 2 === 0; })).subscribe(function (v) { out += v; }); " +
                        "return out === '24'; })()"
                ),
            ),
            SmokeCase(
                "scan and take",
                assertion(
                    "(function () { var out = ''; rxjs.of(1, 2, 3).pipe(" +
                        "rxjs.scan(function (a, b) { return a + b; }, 0), rxjs.take(2))" +
                        ".subscribe(function (v) { out += v + ','; }); return out === '1,3,'; })()"
                ),
            ),
            SmokeCase(
                "Observable instance",
                assertion(
                    "rxjs.of(1) instanceof rxjs.Observable && " +
                        "rxjs.of(1).pipe(rxjs.map(function (x) { return x; })) instanceof rxjs.Observable"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "eventemitter3",
        url = "https://cdn.jsdelivr.net/npm/eventemitter3@5.0.1/dist/eventemitter3.umd.js",
        globalName = "EventEmitter3",
        cases = listOf(
            SmokeCase(
                "on and emit",
                assertion(
                    "(function () { var e = new EventEmitter3(); var out = ''; " +
                        "e.on('x', function (v) { out += v; }); e.emit('x', 1); e.emit('x', 2); " +
                        "return out === '12'; })()"
                ),
            ),
            SmokeCase(
                "once",
                assertion(
                    "(function () { var e = new EventEmitter3(); var n = 0; " +
                        "e.once('a', function () { n++; }); e.emit('a'); e.emit('a'); return n === 1; })()"
                ),
            ),
            SmokeCase(
                "off and listenerCount",
                assertion(
                    "(function () { var e = new EventEmitter3(); function fn() {} " +
                        "e.on('a', fn); var before = e.listenerCount('a'); e.off('a', fn); " +
                        "e.emit('a'); return before === 1 && e.listenerCount('a') === 0; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "mitt",
        url = "https://cdn.jsdelivr.net/npm/mitt@3.0.1/dist/mitt.umd.js",
        globalName = "mitt",
        cases = listOf(
            SmokeCase(
                "on and emit",
                assertion(
                    "(function () { var m = mitt(); var out = ''; " +
                        "m.on('t', function (v) { out += v; }); m.emit('t', 1); m.emit('t', 2); " +
                        "return out === '12'; })()"
                ),
            ),
            SmokeCase(
                "off",
                assertion(
                    "(function () { var m = mitt(); var out = ''; " +
                        "function fn(v) { out += v; } m.on('t', fn); m.off('t', fn); m.emit('t', 1); " +
                        "return out === ''; })()"
                ),
            ),
            SmokeCase(
                "wildcard",
                assertion(
                    "(function () { var m = mitt(); var out = ''; " +
                        "m.on('*', function (type, v) { out += type + v; }); m.emit('a', 1); " +
                        "return out === 'a1'; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "immutable",
        url = "https://cdn.jsdelivr.net/npm/immutable@4.3.5/dist/immutable.min.js",
        globalName = "Immutable",
        cases = listOf(
            SmokeCase(
                "map set get",
                assertion("Immutable.Map({ a: 1 }).set('b', 2).get('b') === 2"),
            ),
            SmokeCase(
                "list map join",
                assertion(
                    "Immutable.List([1, 2, 3]).map(function (x) { return x * 2; }).join(',') === '2,4,6'"
                ),
            ),
            SmokeCase(
                "fromJS getIn",
                assertion("Immutable.fromJS({ a: [1, 2] }).getIn(['a', 1]) === 2"),
            ),
            SmokeCase(
                "equals",
                assertion("Immutable.Map({ a: 1 }).equals(Immutable.Map({ a: 1 }))"),
            ),
            SmokeCase(
                "iterator",
                assertion("Array.from(Immutable.List([1, 2])).join(',') === '1,2'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "redux",
        url = "https://cdn.jsdelivr.net/npm/redux@4.2.1/dist/redux.min.js",
        globalName = "Redux",
        cases = listOf(
            SmokeCase(
                "dispatch",
                assertion(
                    "(function () { var s = Redux.createStore(function (s, a) { s = s || 0; " +
                        "return a.type === 'inc' ? s + 1 : s; }); s.dispatch({ type: 'inc' }); " +
                        "return s.getState() === 1; })()"
                ),
            ),
            SmokeCase(
                "subscribe",
                assertion(
                    "(function () { var s = Redux.createStore(function (s, a) { s = s || 0; " +
                        "return a.type === 'inc' ? s + 1 : s; }); var calls = 0; " +
                        "s.subscribe(function () { calls++; }); s.dispatch({ type: 'inc' }); " +
                        "return calls === 1; })()"
                ),
            ),
            SmokeCase(
                "combineReducers",
                assertion(
                    "(function () { var root = Redux.combineReducers({ " +
                        "a: function (s, a) { s = s || 0; return a.type === 'inc' ? s + 1 : s; }, " +
                        "b: function (s, a) { s = s || 'x'; return a.type === 'set' ? a.v : s; } }); " +
                        "var s = Redux.createStore(root); s.dispatch({ type: 'inc' }); " +
                        "s.dispatch({ type: 'set', v: 'y' }); return JSON.stringify(s.getState())" +
                        " === '{\"a\":1,\"b\":\"y\"}'; })()"
                ),
            ),
            SmokeCase(
                "applyMiddleware",
                assertion(
                    "(function () { var seen = []; var mw = function (api) { " +
                        "return function (next) { return function (action) { " +
                        "seen.push(action.type); return next(action); }; }; }; " +
                        "var s = Redux.createStore(function (s, a) { s = s || 0; " +
                        "return a.type === 'inc' ? s + 1 : s; }, Redux.applyMiddleware(mw)); " +
                        "s.dispatch({ type: 'inc' }); " +
                        "return seen.join(',') === 'inc' && s.getState() === 1; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "markdown-it",
        url = "https://cdn.jsdelivr.net/npm/markdown-it@13.0.2/dist/markdown-it.min.js",
        globalName = "markdownit",
        cases = listOf(
            SmokeCase(
                "heading",
                assertion("markdownit().render('# Hi').indexOf('<h1>Hi</h1>') >= 0"),
            ),
            SmokeCase(
                "bold and code",
                assertion(
                    "markdownit().render('**bold**').indexOf('<strong>bold</strong>') >= 0 && " +
                        "markdownit().render('`x`').indexOf('<code>x</code>') >= 0"
                ),
            ),
            SmokeCase(
                "list",
                assertion("markdownit().render('- a\\n- b').indexOf('<li>a</li>') >= 0"),
            ),
            SmokeCase(
                "link and html option",
                assertion(
                    "markdownit().render('[x](https://a)').indexOf('href=\"https://a\"') >= 0 && " +
                        "markdownit({ html: true }).render('<b>x</b>').indexOf('<b>x</b>') >= 0"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "handlebars",
        url = "https://cdn.jsdelivr.net/npm/handlebars@4.7.8/dist/handlebars.min.js",
        globalName = "Handlebars",
        cases = listOf(
            SmokeCase(
                "interpolate",
                assertion("Handlebars.compile('Hello {{name}}')({ name: 'World' }) === 'Hello World'"),
            ),
            SmokeCase(
                "each and if",
                assertion(
                    "Handlebars.compile('{{#each list}}{{this}},{{/each}}')({ list: [1, 2] })" +
                        " === '1,2,' && Handlebars.compile('{{#if ok}}y{{else}}n{{/if}}')({ ok: true })" +
                        " === 'y'"
                ),
            ),
            SmokeCase(
                "escaping",
                assertion(
                    "Handlebars.compile('{{x}}')({ x: '<b>' }) === '&lt;b&gt;' && " +
                        "Handlebars.compile('{{{x}}}')({ x: '<b>' }) === '<b>'"
                ),
            ),
            SmokeCase(
                "helper",
                assertion(
                    "(function () { Handlebars.registerHelper('up', function (s) { " +
                        "return String(s).toUpperCase(); }); " +
                        "return Handlebars.compile('{{up name}}')({ name: 'ab' }) === 'AB'; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "esprima",
        url = "https://cdn.jsdelivr.net/npm/esprima@4.0.1/dist/esprima.js",
        globalName = "esprima",
        cases = listOf(
            SmokeCase(
                "parse script",
                assertion(
                    "esprima.parseScript('var x = 1;').type === 'Program' && " +
                        "esprima.parseScript('var x = 1;').body[0].declarations[0].id.name === 'x'"
                ),
            ),
            SmokeCase(
                "tokenize",
                assertion(
                    "esprima.tokenize('var x = 1;').length === 5 && " +
                        "esprima.tokenize('/a/g')[0].type === 'RegularExpression'"
                ),
            ),
            SmokeCase(
                "parse module",
                assertion("esprima.parseModule('export default 1;').type === 'Program'"),
            ),
            SmokeCase(
                "escaped identifier",
                assertion(
                    "esprima.parseScript('var \\u0061 = 1;').body[0].declarations[0].id.name === 'a'"
                ),
            ),
            SmokeCase(
                "syntax error location",
                assertion(
                    "(function () { try { esprima.parseScript('var ='); return false; } " +
                        "catch (e) { return e.name === 'Error' && e.index === 4 && e.lineNumber === 1; } })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "ajv",
        url = "https://cdn.jsdelivr.net/npm/ajv@6.12.6/dist/ajv.min.js",
        globalName = "Ajv",
        cases = listOf(
            SmokeCase(
                "compile object schema",
                assertion(
                    "(function () { var validator = new Ajv({ logger: false }); var validate = validator.compile({ " +
                        "type: 'object', properties: { a: { type: 'integer' } }, required: ['a'], " +
                        "additionalProperties: false }); " +
                        "return validate({ a: 1 }) === true && validate({ a: 'x' }) === false && " +
                        "validate.errors.length === 1 && validate.errors[0].keyword === 'type'; })()"
                ),
            ),
            SmokeCase(
                "validate type",
                assertion(
                    "new Ajv({ logger: false }).validate({ type: 'string' }, 'x') === true && " +
                        "new Ajv({ logger: false }).validate({ type: 'string' }, 1) === false"
                ),
            ),
            SmokeCase(
                "schema ref",
                assertion(
                    "(function () { var validator = new Ajv({ logger: false }); " +
                        "validator.addSchema({ \$id: 's', type: 'number' }); " +
                        "return validator.validate({ \$ref: 's' }, 5) === true && " +
                        "validator.validate({ \$ref: 's' }, 'x') === false; })()"
                ),
            ),
            SmokeCase(
                "custom keyword",
                assertion(
                    "(function () { var validator = new Ajv({ logger: false }); validator.addKeyword('even', { " +
                        "validate: function (schema, data) { " +
                        "return typeof data === 'number' && data % 2 === 0; } }); " +
                        "var validate = validator.compile({ type: 'object', " +
                        "properties: { n: { even: true } } }); " +
                        "return validate({ n: 2 }) === true && validate({ n: 3 }) === false; })()"
                ),
            ),
            SmokeCase(
                "array items",
                assertion(
                    "(function () { var validate = new Ajv({ logger: false }).compile({ " +
                        "type: 'array', items: { type: 'number' } }); " +
                        "return validate([1, 2]) === true && validate(['x']) === false; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "acorn",
        url = "https://cdn.jsdelivr.net/npm/acorn@8.11.3/dist/acorn.js",
        globalName = "acorn",
        cases = listOf(
            SmokeCase(
                "parse script",
                assertion("acorn.parse('var x = 1;', { ecmaVersion: 2020 }).type === 'Program'"),
            ),
            SmokeCase(
                "modern syntax",
                assertion(
                    "acorn.parse('const f = (a = 1) => a ** 2;', { ecmaVersion: 2020 })" +
                        ".body[0].type === 'VariableDeclaration' && " +
                        "acorn.parse('class A { #x = 1; m() { return this.#x; } }', " +
                        "{ ecmaVersion: 2022 }).body[0].type === 'ClassDeclaration'"
                ),
            ),
            SmokeCase(
                "parse expression",
                assertion(
                    "acorn.parseExpressionAt('1 + 2', 0, { ecmaVersion: 2020 }).type" +
                        " === 'BinaryExpression'"
                ),
            ),
            SmokeCase(
                "tokenizer",
                assertion(
                    "acorn.tokenizer('let x = 1;', { ecmaVersion: 2020 }).getToken()" +
                        ".type.label === 'name'"
                ),
            ),
            SmokeCase(
                "syntax error",
                assertion(
                    "(function () { try { acorn.parse('var =', { ecmaVersion: 2020 }); " +
                        "return false; } catch (e) { return e.pos === 4 && e.loc.line === 1; } })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "astring",
        url = "https://cdn.jsdelivr.net/npm/astring@1.8.6/dist/astring.min.js",
        globalName = "astring",
        // astring 只负责代码生成，借助 acorn 产出的 AST 验证真实链路
        dependencies = listOf("acorn"),
        cases = listOf(
            SmokeCase(
                "generate basic",
                assertion(
                    "astring.generate(acorn.parse('var x = 1;', { ecmaVersion: 2020 }))" +
                        " === 'var x = 1;\\n'"
                ),
            ),
            SmokeCase(
                "generate function",
                assertion(
                    "astring.generate(acorn.parse('function f(a, b) { return a + b; }', " +
                        "{ ecmaVersion: 2020 })) === " +
                        "'function f(a, b) {\\n  return a + b;\\n}\\n'"
                ),
            ),
            SmokeCase(
                "generate round trip",
                assertion(
                    "(function () { " +
                        "var code = astring.generate(acorn.parse('function f(a) { return a * 2; }', " +
                        "{ ecmaVersion: 2020 })); " +
                        "return astring.generate(acorn.parse(code, { ecmaVersion: 2020 })) === code; " +
                        "})()"
                ),
            ),
            SmokeCase(
                "generate keeps parentheses",
                assertion(
                    "astring.generate(acorn.parse('var x = (1 + 2) * 3;', { ecmaVersion: 2020 }))" +
                        " === 'var x = (1 + 2) * 3;\\n'"
                ),
            ),
            SmokeCase(
                "generate custom indent",
                assertion(
                    "astring.generate(acorn.parse('function f() { return 1; }', " +
                        "{ ecmaVersion: 2020 }), { indent: '    ' }) === " +
                        "'function f() {\\n    return 1;\\n}\\n'"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "acorn-walk",
        url = "https://cdn.jsdelivr.net/npm/acorn-walk@8.3.2/dist/walk.js",
        globalName = "acorn.walk",
        // walk 通过 globalThis.acorn.walk 挂载，必须先加载 acorn
        dependencies = listOf("acorn"),
        cases = listOf(
            SmokeCase(
                "full traversal",
                assertion(
                    "(function () { var types = []; " +
                        "acorn.walk.full(acorn.parse('const f = function (a) { return a * 2; };', " +
                        "{ ecmaVersion: 2020 }), function (node) { types.push(node.type); }); " +
                        "return types.join(',') === 'Identifier,Identifier,Identifier,Literal," +
                        "BinaryExpression,ReturnStatement,BlockStatement,FunctionExpression," +
                        "VariableDeclarator,VariableDeclaration,Program'; })()"
                ),
            ),
            SmokeCase(
                "simple identifiers",
                assertion(
                    "(function () { var names = []; " +
                        "acorn.walk.simple(acorn.parse('x + y * x;', { ecmaVersion: 2020 }), " +
                        "{ Identifier: function (node) { names.push(node.name); } }); " +
                        "return names.join(',') === 'x,y,x'; })()"
                ),
            ),
            SmokeCase(
                "ancestor chain",
                assertion(
                    "(function () { var chain = null; " +
                        "acorn.walk.ancestor(acorn.parse('const f = function (a) { return a * 2; };', " +
                        "{ ecmaVersion: 2020 }), " +
                        "{ ReturnStatement: function (node, state, ancestors) { " +
                        "chain = ancestors.map(function (a) { return a.type; }).join('>'); } }); " +
                        "return chain === 'Program>VariableDeclaration>VariableDeclarator>" +
                        "FunctionExpression>BlockStatement>ReturnStatement'; })()"
                ),
            ),
            SmokeCase(
                "find node at",
                assertion(
                    "(function () { var ast = acorn.parse('const f = function (a) { return a * 2; };', " +
                        "{ ecmaVersion: 2020 }); var found = acorn.walk.findNodeAt(ast, 20, 21); " +
                        "return found.node.type === 'Identifier' && found.node.name === 'a'; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "crc-32",
        url = "https://unpkg.com/crc-32@1.2.2/crc32.js",
        globalName = "CRC32",
        cases = listOf(
            SmokeCase(
                "version",
                assertion("CRC32.version === '1.2.2'"),
            ),
            SmokeCase(
                "utf8 strings",
                assertion(
                    "CRC32.str('') === 0 && CRC32.str('abc') === 891568578 && " +
                        "CRC32.str('Hello') === -137262718 && CRC32.str('你好') === 1352841281 && " +
                        "CRC32.str('😀a') === -1727826305"
                ),
            ),
            SmokeCase(
                "binary string and bytes",
                assertion(
                    "CRC32.bstr('ABC') === -1551695032 && CRC32.buf([65, 66, 67]) === -1551695032"
                ),
            ),
            SmokeCase(
                "incremental seed",
                assertion("CRC32.str('bc', CRC32.str('a')) === 891568578"),
            ),
        ),
    ),
    LibrarySpec(
        name = "blueimp-md5",
        url = "https://unpkg.com/blueimp-md5@2.19.0/js/md5.min.js",
        globalName = "md5",
        cases = listOf(
            SmokeCase(
                "ascii",
                assertion("md5('abc') === '900150983cd24fb0d6963f7d28e17f72'"),
            ),
            SmokeCase(
                "utf8",
                assertion("md5('你好') === '7eca689f0d3389d9dea66ae112e5cfd7'"),
            ),
            SmokeCase(
                "hmac",
                assertion("md5('key', 'abc') === '42a724aeb701cef74eee75871d6770cd'"),
            ),
            SmokeCase(
                "raw",
                assertion(
                    "(function () { var raw = md5('abc', null, true); " +
                        "return raw.length === 16 && raw.charCodeAt(0) === 144; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "ua-parser",
        url = "https://unpkg.com/ua-parser-js@1.0.37/dist/ua-parser.min.js",
        globalName = "UAParser",
        cases = listOf(
            SmokeCase(
                "version",
                assertion("UAParser.VERSION === '1.0.37' && typeof UAParser === 'function'"),
            ),
            SmokeCase(
                "chrome on windows",
                assertion(
                    "(function () { var r = UAParser('Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'); " +
                        "return r.browser.name === 'Chrome' && r.browser.version === '120.0.0.0' && " +
                        "r.os.name === 'Windows' && r.os.version === '10' && " +
                        "r.engine.name === 'Blink' && r.cpu.architecture === 'amd64'; })()"
                ),
            ),
            SmokeCase(
                "mobile safari",
                assertion(
                    "(function () { var r = UAParser('Mozilla/5.0 (iPhone; CPU iPhone OS 17_1 like Mac OS X) " +
                        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.1 Mobile/15E148 Safari/604.1'); " +
                        "return r.browser.name === 'Mobile Safari' && r.os.name === 'iOS' && " +
                        "r.os.version === '17.1' && r.device.type === 'mobile' && " +
                        "r.device.vendor === 'Apple' && r.device.model === 'iPhone'; })()"
                ),
            ),
            SmokeCase(
                "unknown ua",
                assertion(
                    "(function () { var r = UAParser('Mozilla/5.0 (compatible; Googlebot/2.1; " +
                        "+http://www.google.com/bot.html)'); " +
                        "return r.browser.name === undefined && r.os.name === undefined && " +
                        "r.device.type === undefined; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "fraction",
        url = "https://unpkg.com/fraction.js@4.3.7/fraction.min.js",
        globalName = "Fraction",
        cases = listOf(
            SmokeCase(
                "add",
                assertion("new Fraction(1, 3).add(new Fraction(1, 6)).toFraction() === '1/2'"),
            ),
            SmokeCase(
                "parse decimal",
                assertion(
                    "new Fraction('0.75').toFraction() === '3/4' && " +
                        "new Fraction(0.1).toFraction() === '1/10'"
                ),
            ),
            SmokeCase(
                "compare",
                assertion("new Fraction(3, 2).compare(new Fraction(4, 3)) === 1"),
            ),
            SmokeCase(
                "divide and mod",
                assertion(
                    "new Fraction(1.5).div('0.25').toFraction() === '6' && " +
                        "new Fraction('1/3').mod('1/6').toFraction() === '0'"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "jsep",
        url = "https://unpkg.com/jsep@0.3.4/build/jsep.js",
        globalName = "jsep",
        cases = listOf(
            SmokeCase(
                "version",
                assertion("jsep.version === '0.3.4'"),
            ),
            SmokeCase(
                "operator precedence",
                assertion(
                    "(function () { var ast = jsep('1 + 2 * 3'); " +
                        "return ast.operator === '+' && ast.right.operator === '*' && " +
                        "ast.right.left.value === 2; })()"
                ),
            ),
            SmokeCase(
                "member call",
                assertion(
                    "(function () { var ast = jsep('a.b[0](1)'); " +
                        "return ast.type === 'CallExpression' && " +
                        "ast.callee.type === 'MemberExpression' && ast.arguments[0].value === 1; })()"
                ),
            ),
            SmokeCase(
                "conditional",
                assertion(
                    "(function () { var ast = jsep('a ? b : c'); " +
                        "return ast.type === 'ConditionalExpression' && " +
                        "ast.consequent.name === 'b' && jsep('!a && b').type === 'LogicalExpression'; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "lunr",
        url = "https://unpkg.com/lunr@2.3.9/lunr.min.js",
        globalName = "lunr",
        cases = listOf(
            SmokeCase(
                "version",
                assertion("lunr.version === '2.3.9'"),
            ),
            SmokeCase(
                "search",
                assertion(
                    "(function () { var idx = lunr(function () { this.ref('id'); this.field('title'); " +
                        "this.add({ id: '1', title: 'hello world' }); " +
                        "this.add({ id: '2', title: 'hello there' }); " +
                        "this.add({ id: '3', title: 'goodbye' }); }); " +
                        "return idx.search('hello').map(function (r) { return r.ref; }).join(',') === '2,1'; })()"
                ),
            ),
            SmokeCase(
                "single hit",
                assertion(
                    "(function () { var idx = lunr(function () { this.ref('id'); this.field('title'); " +
                        "this.add({ id: '1', title: 'hello world' }); " +
                        "this.add({ id: '3', title: 'goodbye' }); }); " +
                        "return idx.search('goodbye').length === 1; })()"
                ),
            ),
            SmokeCase(
                "no match",
                assertion(
                    "(function () { var idx = lunr(function () { this.ref('id'); this.field('title'); " +
                        "this.add({ id: '1', title: 'hello' }); }); " +
                        "return idx.search('xyz').length === 0; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "fuse",
        url = "https://unpkg.com/fuse.js@6.6.2/dist/fuse.min.js",
        globalName = "Fuse",
        cases = listOf(
            SmokeCase(
                "version",
                assertion("Fuse.version === '6.6.2'"),
            ),
            SmokeCase(
                "fuzzy search",
                assertion(
                    "(function () { var fuse = new Fuse([{ title: 'hello' }, { title: 'world' }], " +
                        "{ keys: ['title'] }); " +
                        "return fuse.search('hel').length === 1 && " +
                        "fuse.search('hel')[0].item.title === 'hello'; })()"
                ),
            ),
            SmokeCase(
                "no match",
                assertion(
                    "(function () { var fuse = new Fuse([{ title: 'hello' }], { keys: ['title'] }); " +
                        "return fuse.search('zzzz').length === 0; })()"
                ),
            ),
            SmokeCase(
                "threshold option",
                assertion(
                    "(function () { var fuse = new Fuse([{ title: 'hello' }, { title: 'world' }], " +
                        "{ keys: ['title'], threshold: 0.1 }); " +
                        "return fuse.search('wor').length === 1; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "acorn-loose",
        url = "https://unpkg.com/acorn-loose@8.4.0/dist/acorn-loose.js",
        globalName = "acorn.loose",
        dependencies = listOf("acorn"),
        cases = listOf(
            SmokeCase(
                "exports",
                assertion(
                    "typeof acorn.loose.parse === 'function' && " +
                        "typeof acorn.loose.isDummy === 'function'"
                ),
            ),
            SmokeCase(
                "error recovery",
                assertion(
                    "(function () { var ast = acorn.loose.parse('var x = ; var y = 1;', " +
                        "{ ecmaVersion: 2020 }); " +
                        "return ast.type === 'Program' && ast.body.length === 2 && " +
                        "acorn.loose.isDummy(ast.body[0].declarations[0].init); })()"
                ),
            ),
            SmokeCase(
                "incomplete function",
                assertion(
                    "(function () { var ast = acorn.loose.parse('function f( { return 1; }', " +
                        "{ ecmaVersion: 2020 }); " +
                        "return ast.body.length === 1 && ast.body[0].type === 'FunctionDeclaration'; })()"
                ),
            ),
            SmokeCase(
                "dummy helper",
                assertion(
                    "acorn.loose.isDummy({ type: 'Identifier', name: '✖' }) && " +
                        "!acorn.loose.isDummy({ type: 'Identifier', name: 'x' })"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "complex",
        url = "https://unpkg.com/complex.js@2.1.1/complex.min.js",
        globalName = "Complex",
        cases = listOf(
            SmokeCase(
                "add",
                assertion("new Complex(1, 2).add(new Complex(3, 4)).toString() === '4 + 6i'"),
            ),
            SmokeCase(
                "imaginary square",
                assertion("new Complex(0, 1).mul(new Complex(0, 1)).re === -1"),
            ),
            SmokeCase(
                "abs and arg",
                assertion(
                    "new Complex(3, 4).abs() === 5 && new Complex(-1, 0).arg() === Math.PI"
                ),
            ),
            SmokeCase(
                "pow",
                assertion("new Complex(1, 1).pow(2).toString() === '2i'"),
            ),
        ),
    ),
    LibrarySpec(
        name = "decimal-light",
        url = "https://unpkg.com/decimal.js-light@2.5.1/decimal.js",
        globalName = "Decimal",
        cases = listOf(
            SmokeCase(
                "plus",
                assertion("new Decimal(0.1).plus(0.2).toString() === '0.3'"),
            ),
            SmokeCase(
                "divide",
                assertion("new Decimal(1).div(3).toString() === '0.33333333333333333333'"),
            ),
            SmokeCase(
                "toFixed",
                assertion("new Decimal('1.005').toFixed(2) === '1.01'"),
            ),
            SmokeCase(
                "sqrt and big integer",
                assertion(
                    "new Decimal(2).sqrt().toString() === '1.4142135623730950488' && " +
                        "new Decimal('12345678901234567890').plus(1).toString() === " +
                        "'12345678901234567891'"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "ohm",
        url = "https://unpkg.com/ohm-js@17.1.0/dist/ohm.min.js",
        globalName = "ohm",
        cases = listOf(
            SmokeCase(
                "version",
                assertion("ohm.version === '17.1.0' && typeof ohm.grammar === 'function'"),
            ),
            SmokeCase(
                "grammar match",
                assertion(
                    "(function () { var g = ohm.grammar('G { start = d+  d = \"0\"..\"9\" }'); " +
                        "return g.match('123').succeeded(); })()"
                ),
            ),
            SmokeCase(
                "grammar mismatch",
                assertion(
                    "(function () { var g = ohm.grammar('G { start = d+  d = \"0\"..\"9\" }'); " +
                        "return g.match('12a').failed(); })()"
                ),
            ),
            SmokeCase(
                "builtin letter rule",
                assertion(
                    "(function () { var g = ohm.grammar('G { start = letter+ }'); " +
                        "return g.match('abc').succeeded() && g.match('1').failed(); })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "jsonata",
        url = "https://unpkg.com/jsonata@2.1.0/jsonata.js",
        globalName = "jsonata",
        cases = listOf(
            SmokeCase(
                "exports",
                assertion("typeof jsonata === 'function'"),
            ),
            SmokeCase(
                "path ast",
                assertion(
                    "(function () { var ast = jsonata('a.b').ast(); " +
                        "return ast.type === 'path' && ast.steps.length === 2 && " +
                        "ast.steps[0].value === 'a' && ast.steps[1].value === 'b'; })()"
                ),
            ),
            SmokeCase(
                "binary ast",
                assertion(
                    "(function () { var ast = jsonata('1 + 2').ast(); " +
                        "return ast.type === 'binary' && ast.value === '+' && " +
                        "ast.lhs.value === 1 && ast.rhs.value === 2; })()"
                ),
            ),
            SmokeCase(
                "syntax error",
                assertion(
                    "(function () { try { jsonata('1 +'); return false; } catch (e) { " +
                        "return e.message === 'Unexpected end of expression'; } })()"
                ),
            ),
            SmokeCase(
                "evaluate path",
                "(function () { jsonata('a.b').evaluate({ a: { b: 42 } }).then(function (value) { " +
                    "globalThis.__libResult = value === 42; }); })();",
                async = true,
            ),
            SmokeCase(
                "evaluate aggregate",
                "(function () { jsonata('$" + "sum([1, 2, 3])').evaluate({}).then(function (value) { " +
                    "globalThis.__libResult = value === 6; }); })();",
                async = true,
            ),
            SmokeCase(
                "evaluate error",
                "(function () { jsonata('$" + "sum()').evaluate({}).then(function () { " +
                    "globalThis.__libResult = false; }, function (error) { " +
                    "globalThis.__libResult = error.code === 'T0410'; }); })();",
                async = true,
            ),
        ),
    ),
    LibrarySpec(
        name = "qrcode",
        url = "https://unpkg.com/qrcode-generator@1.4.4/qrcode.js",
        globalName = "qrcode",
        cases = listOf(
            SmokeCase(
                "module matrix",
                assertion(
                    "(function () { var qr = qrcode(0, 'M'); qr.addData('hello'); qr.make(); " +
                        "return qr.getModuleCount() === 21 && qr.isDark(0, 0); })()"
                ),
            ),
            SmokeCase(
                "long data",
                assertion(
                    "(function () { var qr = qrcode(0, 'L'); qr.addData('x'.repeat(200)); qr.make(); " +
                        "return qr.getModuleCount() === 53; })()"
                ),
            ),
            SmokeCase(
                "string bytes",
                assertion("qrcode.stringToBytes('hi').join(',') === '104,105'"),
            ),
            SmokeCase(
                "explicit type",
                assertion(
                    "(function () { var qr = qrcode(1, 'H'); qr.addData('hi'); qr.make(); " +
                        "return qr.getModuleCount() === 21; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "linkify",
        url = "https://unpkg.com/linkifyjs@4.1.1/dist/linkify.min.js",
        globalName = "linkify",
        cases = listOf(
            SmokeCase(
                "url",
                assertion(
                    "(function () { var found = linkify.find('Visit github.com now'); " +
                        "return found.length === 1 && found[0].type === 'url' && " +
                        "found[0].value === 'github.com' && found[0].href === 'http://github.com' && " +
                        "found[0].start === 6 && found[0].end === 16; })()"
                ),
            ),
            SmokeCase(
                "email",
                assertion(
                    "(function () { var found = linkify.find('mail me a@b.com'); " +
                        "return found.length === 1 && found[0].type === 'email' && " +
                        "found[0].href === 'mailto:a@b.com'; })()"
                ),
            ),
            SmokeCase(
                "test",
                assertion("!linkify.test('no link') && linkify.test('http://a.com')"),
            ),
            SmokeCase(
                "multiple links",
                assertion(
                    "linkify.find('a http://x.com b a@b.com').length === 2"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "simple-statistics",
        url = "https://unpkg.com/simple-statistics@7.8.3/dist/simple-statistics.min.js",
        globalName = "ss",
        cases = listOf(
            SmokeCase(
                "mean and median",
                assertion("ss.mean([1, 2, 3]) === 2 && ss.median([3, 1, 2]) === 2"),
            ),
            SmokeCase(
                "standard deviation",
                assertion("ss.standardDeviation([1, 2, 3, 4]) === 1.118033988749895"),
            ),
            SmokeCase(
                "quantile",
                assertion("ss.quantile([1, 2, 3, 4], 0.5) === 2.5"),
            ),
            SmokeCase(
                "linear regression",
                assertion(
                    "(function () { var model = ss.linearRegression([[0, 1], [1, 3], [2, 5]]); " +
                        "return model.m === 2 && model.b === 1 && " +
                        "ss.linearRegressionLine(model)(2) === 5; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "compare-versions",
        url = "https://unpkg.com/compare-versions@6.1.0/lib/umd/index.js",
        globalName = "compareVersions",
        cases = listOf(
            SmokeCase(
                "ordering",
                assertion(
                    "compareVersions.compareVersions('1.2.3', '1.2.4') === -1 && " +
                        "compareVersions.compareVersions('1.2.3', '1.2.3') === 0 && " +
                        "compareVersions.compareVersions('2.0.0', '1.9.9') === 1"
                ),
            ),
            SmokeCase(
                "compare operator",
                assertion(
                    "compareVersions.compare('1.2.3', '1.2.4', '<') && " +
                        "!compareVersions.compare('1.2.3', '1.2.4', '>=')"
                ),
            ),
            SmokeCase(
                "validate",
                assertion("compareVersions.validate('1.2.3') && compareVersions.validate('1.2') && !compareVersions.validate('foo')"),
            ),
            SmokeCase(
                "satisfies",
                assertion(
                    "compareVersions.satisfies('1.2.3', '>=1.2.0 <2.0.0') && " +
                        "!compareVersions.validateStrict('1.2')"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "moo",
        url = "https://unpkg.com/moo@0.5.2/moo.js",
        globalName = "moo",
        cases = listOf(
            SmokeCase(
                "token stream",
                assertion(
                    "(function () { var lexer = moo.compile({ ws: /[ ]+/, number: /[0-9]+/, word: /[a-zA-Z]+/ }); " +
                        "lexer.reset('foo 123 bar'); var a = lexer.next(), b = lexer.next(), c = lexer.next(), " +
                        "d = lexer.next(), e = lexer.next(); return a.type === 'word' && a.value === 'foo' && " +
                        "a.line === 1 && a.col === 1 && b.type === 'ws' && b.col === 4 && " +
                        "c.type === 'number' && c.value === '123' && c.col === 5 && d.type === 'ws' && " +
                        "e.type === 'word' && e.value === 'bar' && e.col === 9 && lexer.next() === undefined; })()"
                ),
            ),
            SmokeCase(
                "newline line tracking",
                assertion(
                    "(function () { var lexer = moo.compile({ nl: { match: /\\n/, lineBreaks: true }, " +
                        "ws: /[ ]+/, word: /[a-z]+/ }); lexer.reset('a\\nb'); " +
                        "var a = lexer.next(), b = lexer.next(), c = lexer.next(); " +
                        "return a.line === 1 && b.type === 'nl' && b.lineBreaks === 1 && " +
                        "c.line === 2 && c.col === 1 && c.value === 'b'; })()"
                ),
            ),
            SmokeCase(
                "keyword transform",
                assertion(
                    "(function () { var lexer = moo.compile({ ws: /[ ]+/, word: { match: /[a-z]+/, " +
                        "type: moo.keywords({ kw_if: 'if', kw_then: 'then' }) } }); " +
                        "lexer.reset('if then other'); var a = lexer.next(), b = lexer.next(), " +
                        "c = lexer.next(), d = lexer.next(), e = lexer.next(); " +
                        "return a.type === 'kw_if' && a.value === 'if' && b.type === 'ws' && " +
                        "c.type === 'kw_then' && d.type === 'ws' && e.type === 'word' && e.value === 'other'; })()"
                ),
            ),
            SmokeCase(
                "iterator",
                assertion(
                    "(function () { var lexer = moo.compile({ ws: /[ ]+/, num: /[0-9]+/, word: /[a-z]+/ }); " +
                        "lexer.reset('a 1 b'); var types = []; for (var token of lexer) { types.push(token.type); } " +
                        "return types.join(',') === 'word,ws,num,ws,word'; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "fecha",
        url = "https://unpkg.com/fecha@4.2.3/dist/fecha.min.js",
        globalName = "fecha",
        cases = listOf(
            SmokeCase(
                "format masks",
                assertion(
                    "(function () { var date = new Date(2015, 10, 1, 22, 15, 30, 123); " +
                        "return fecha.format(date, 'YYYY-MM-DD HH:mm:ss.SSS') === '2015-11-01 22:15:30.123' && " +
                        "fecha.format(date, 'ddd MMM D YYYY') === 'Sun Nov 1 2015' && " +
                        "fecha.format(date, 'Do') === '1st' && fecha.format(date, 'h:mm A') === '10:15 PM'; })()"
                ),
            ),
            SmokeCase(
                "parse fields",
                assertion(
                    "(function () { var parsed = fecha.parse('2015-11-01 22:15:30.123', " +
                        "'YYYY-MM-DD HH:mm:ss.SSS'); return parsed instanceof Date && " +
                        "parsed.getFullYear() === 2015 && parsed.getMonth() === 10 && parsed.getDate() === 1 && " +
                        "parsed.getHours() === 22 && parsed.getMinutes() === 15 && " +
                        "parsed.getSeconds() === 30 && parsed.getMilliseconds() === 123; })()"
                ),
            ),
            SmokeCase(
                "invalid input",
                assertion(
                    "(function () { var failed = false; " +
                        "try { fecha.format(new Date(NaN), 'YYYY'); } catch (e) { failed = e instanceof Error; } " +
                        "return failed && fecha.parse('nope', 'YYYY-MM-DD') === null; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "deep-diff",
        url = "https://unpkg.com/deep-diff@1.0.2/index.js",
        globalName = "DeepDiff",
        cases = listOf(
            SmokeCase(
                "object changes",
                assertion(
                    "(function () { var changes = DeepDiff({ a: 1, b: { c: 2 } }, { a: 2, b: { c: 3 } }); " +
                        "return changes.length === 2 && changes[0].kind === 'E' && " +
                        "changes[0].path.join('.') === 'a' && changes[0].lhs === 1 && changes[0].rhs === 2 && " +
                        "changes[1].path.join('.') === 'b.c'; })()"
                ),
            ),
            SmokeCase(
                "array changes",
                assertion(
                    "(function () { var changes = DeepDiff([1, 2, 3], [1, 4, 3, 5]); " +
                        "return changes.length === 2 && changes[0].kind === 'A' && changes[0].index === 3 && " +
                        "changes[1].kind === 'E' && changes[1].path[0] === 1; })()"
                ),
            ),
            SmokeCase(
                "apply and revert",
                assertion(
                    "(function () { var target = { a: 1, b: { c: 2 } }; " +
                        "var source = { a: 1, b: { c: 3, d: 4 } }; var changes = DeepDiff(target, source); " +
                        "DeepDiff.applyChange(target, source, changes[0]); var applied = target.b.c === 3; " +
                        "DeepDiff.revertChange(target, source, changes[0]); " +
                        "return applied && target.b.c === 2 && typeof DeepDiff.observableDiff === 'function'; })()"
                ),
            ),
            SmokeCase(
                "nested addition",
                assertion(
                    "(function () { var changes = DeepDiff({ a: { x: 1 }, b: 2 }, { a: { x: 1, y: 3 } }); " +
                        "return changes.length === 2 && changes[0].kind === 'N' && " +
                        "changes[0].path.join('.') === 'a.y' && changes[0].rhs === 3 && " +
                        "changes[1].kind === 'D' && changes[1].path[0] === 'b'; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "object-path",
        url = "https://unpkg.com/object-path@0.11.8/index.js",
        globalName = "objectPath",
        cases = listOf(
            SmokeCase(
                "get and set",
                assertion(
                    "(function () { var target = { a: { b: [{ c: 1 }] } }; " +
                        "objectPath.set(target, ['a', 'b', 1, 'd'], 2); " +
                        "return objectPath.get(target, 'a.b.0.c') === 1 && " +
                        "objectPath.get(target, ['a', 'b', 1, 'd']) === 2 && target.a.b.length === 2; })()"
                ),
            ),
            SmokeCase(
                "push and ensure",
                assertion(
                    "(function () { var target = {}; objectPath.push(target, 'list', 5); " +
                        "objectPath.push(target, 'list', 6); objectPath.ensureExists(target, 'deep.value', 3); " +
                        "return target.list.join(',') === '5,6' && target.deep.value === 3 && " +
                        "objectPath.get(target, 'list.length') === 2; })()"
                ),
            ),
            SmokeCase(
                "has and delete",
                assertion(
                    "(function () { var target = { a: { b: 1 }, c: 2 }; " +
                        "var had = objectPath.has(target, ['a', 'b']); objectPath.del(target, 'a.b'); " +
                        "objectPath.del(target, 'missing.path'); return had && " +
                        "!objectPath.has(target, 'a.b') && objectPath.has(target, 'c') && target.c === 2; })()"
                ),
            ),
            SmokeCase(
                "inherited props",
                assertion(
                    "(function () { var base = { x: { y: 7 } }; var child = Object.create(base); " +
                        "var inherited = objectPath.withInheritedProps; " +
                        "return objectPath.get(child, 'x.y') === undefined && " +
                        "inherited.get(child, 'x.y') === 7 && typeof objectPath.create === 'function'; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "validate",
        url = "https://unpkg.com/validate.js@0.13.1/validate.min.js",
        globalName = "validate",
        cases = listOf(
            SmokeCase(
                "sync errors",
                assertion(
                    "(function () { var errors = validate({ name: null, age: 15 }, " +
                        "{ name: { presence: true }, age: { presence: true, numericality: { greaterThan: 18 } } }); " +
                        "return errors.name[0] === \"Name can't be blank\" && " +
                        "errors.age[0] === 'Age must be greater than 18' && " +
                        "validate({ name: 'ok', age: 20 }, { name: { presence: true }, " +
                        "age: { numericality: { greaterThan: 18 } } }) === undefined; })()"
                ),
            ),
            SmokeCase(
                "validators and promise",
                assertion(
                    "validate.isDefined(null) === false && validate.isString('a') && validate.isNumber(3) && " +
                        "validate.isInteger(3) === true && validate.isInteger(3.5) === false && " +
                        "validate.isPromise(validate.async({}, {}))"
                ),
            ),
            SmokeCase(
                "custom validator",
                assertion(
                    "(function () { validate.validators.even = function (value) { " +
                        "return value % 2 === 0 ? undefined : 'must be even'; }; " +
                        "var errors = validate({ num: 3 }, { num: { even: true } }); " +
                        "return errors.num[0].indexOf('must be even') >= 0 && " +
                        "validate({ num: 4 }, { num: { even: true } }) === undefined; })()"
                ),
            ),
            SmokeCase(
                "async rejection",
                "(function () { validate.async({ name: null }, { name: { presence: true } }).then(function () { " +
                    "globalThis.__libResult = false; }, function (errors) { " +
                    "globalThis.__libResult = errors.name.length === 1; }); })();",
                async = true,
            ),
        ),
    ),
    LibrarySpec(
        name = "date-and-time",
        url = "https://unpkg.com/date-and-time@2.4.3/date-and-time.js",
        globalName = "date",
        cases = listOf(
            SmokeCase(
                "format masks",
                assertion(
                    "(function () { var dt = new Date(2015, 10, 1, 22, 15, 30, 123); " +
                        "return date.format(dt, 'YYYY/MM/DD HH:mm:ss.SSS') === '2015/11/01 22:15:30.123' && " +
                        "date.format(dt, 'ddd MMM DD YYYY') === 'Sun Nov 01 2015' && " +
                        "date.format(dt, 'hh:mm A') === '10:15 PM'; })()"
                ),
            ),
            SmokeCase(
                "parse fields",
                assertion(
                    "(function () { var parsed = date.parse('2015/11/01 22:15:30.123', " +
                        "'YYYY/MM/DD HH:mm:ss.SSS'); return parsed instanceof Date && " +
                        "parsed.getFullYear() === 2015 && parsed.getMonth() === 10 && " +
                        "parsed.getDate() === 1 && parsed.getHours() === 22 && parsed.getMinutes() === 15 && " +
                        "parsed.getSeconds() === 30 && parsed.getMilliseconds() === 123; })()"
                ),
            ),
            SmokeCase(
                "date arithmetic",
                assertion(
                    "(function () { var dt = new Date(2015, 10, 1, 22, 15, 30); var later = date.addDays(dt, 2); " +
                        "var month = date.addMonths(new Date(2015, 0, 31), 1); " +
                        "var diff = date.subtract(dt, new Date(2015, 10, 1, 20, 15, 30)); " +
                        "return later.getDate() === 3 && month.getMonth() === 2 && month.getDate() === 3 && " +
                        "diff.toHours() === 2 && date.addMilliseconds(dt, 500).getMilliseconds() === 500; })()"
                ),
            ),
            SmokeCase(
                "checks and transform",
                assertion(
                    "(function () { return date.isValid('2015-11-01 22:15:30', 'YYYY-MM-DD HH:mm:ss') && " +
                        "!date.isValid('2015-02-30', 'YYYY-MM-DD') && date.isLeapYear(2016) && " +
                        "!date.isLeapYear(2015) && date.isLeapYear(2000) && !date.isLeapYear(1900) && " +
                        "date.isSameDay(new Date(2015, 0, 1, 1), new Date(2015, 0, 1, 23)) && " +
                        "!date.isSameDay(new Date(2015, 0, 1), new Date(2015, 0, 2)) && " +
                        "date.transform('2015-11-01 22:15:30', 'YYYY-MM-DD HH:mm:ss', " +
                        "'MMM DD, YYYY hh:mm A') === 'Nov 01, 2015 10:15 PM'; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "mathjs",
        url = "https://unpkg.com/mathjs@11.12.0/lib/browser/math.js",
        globalName = "math",
        cases = listOf(
            SmokeCase(
                "evaluate",
                assertion(
                    "math.evaluate('2 + 3 * 4') === 14 && " +
                        "math.evaluate('sqrt(16) + pow(2, 10) + factorial(5)') === 1148 && " +
                        "math.evaluate('x^2 + y', { x: 3, y: 4 }) === 13 && " +
                        "math.evaluate('sum(range(1, 10))') === 55"
                ),
            ),
            SmokeCase(
                "matrix",
                assertion(
                    "math.det([[1, 2], [3, 4]]) === -2 && " +
                        "JSON.stringify(math.multiply([[1, 2], [3, 4]], [[5], [6]])) === '[[17],[39]]'"
                ),
            ),
            SmokeCase(
                "units",
                assertion("math.unit('5 cm').to('inch').toNumber('inch') === 1.9685039370078743"),
            ),
            SmokeCase(
                "parse",
                assertion("math.parse('a + b * c').toString() === 'a + b * c'"),
            ),
            SmokeCase(
                "format",
                assertion(
                    "math.format(math.complex(1, 2)) === '1 + 2i' && " +
                        "math.format(1234.5678, { precision: 4 }) === '1235' && " +
                        "math.format(math.fraction(1, 3)) === '1/3'"
                ),
            ),
            SmokeCase(
                "statistics and bignumber",
                assertion(
                    "math.mean([1, 2, 3]) === 2 && " +
                        "math.std([1, 2, 3, 4]) === 1.2909944487358056 && " +
                        "math.bignumber('0.1').plus('0.2').toString() === '0.3'"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "chevrotain",
        url = "https://unpkg.com/chevrotain@10.5.0/lib/chevrotain.min.js",
        globalName = "chevrotain",
        cases = listOf(
            SmokeCase(
                "lexer tokens",
                assertion(
                    "(function () { var I = chevrotain.createToken({ name: 'I', pattern: /[a-zA-Z][a-zA-Z0-9]*/ }); " +
                        "var W = chevrotain.createToken({ name: 'W', pattern: /\\s+/, " +
                        "group: chevrotain.Lexer.SKIPPED }); " +
                        "var P = chevrotain.createToken({ name: 'P', pattern: /\\+/ }); " +
                        "var lexer = new chevrotain.Lexer([W, P, I]); " +
                        "var result = lexer.tokenize('a + b + c'); " +
                        "return result.errors.length === 0 && " +
                        "result.tokens.map(function (t) { return t.image; }).join(',') === 'a,+,b,+,c' && " +
                        "result.tokens.map(function (t) { return t.startOffset; }).join(',') === '0,2,4,6,8'; })()"
                ),
            ),
            SmokeCase(
                "lexer errors",
                assertion(
                    "(function () { var I = chevrotain.createToken({ name: 'I', pattern: /[a-z]+/ }); " +
                        "var W = chevrotain.createToken({ name: 'W', pattern: /\\s+/, " +
                        "group: chevrotain.Lexer.SKIPPED }); " +
                        "var P = chevrotain.createToken({ name: 'P', pattern: /\\+/ }); " +
                        "var lexer = new chevrotain.Lexer([W, P, I]); " +
                        "var result = lexer.tokenize('a @ b'); " +
                        "return result.tokens.map(function (t) { return t.image; }).join(',') === 'a,b' && " +
                        "result.errors.length === 1 && result.errors[0].offset === 2 && " +
                        "result.errors[0].message.length > 0; })()"
                ),
            ),
            SmokeCase(
                "cst parser",
                assertion(
                    "(function () { var createToken = chevrotain.createToken, Lexer = chevrotain.Lexer, " +
                        "CstParser = chevrotain.CstParser; " +
                        "var I = createToken({ name: 'I', pattern: /[a-zA-Z][a-zA-Z0-9]*/ }); " +
                        "var W = createToken({ name: 'W', pattern: /\\s+/, group: Lexer.SKIPPED }); " +
                        "var P = createToken({ name: 'P', pattern: /\\+/ }); var all = [W, P, I]; " +
                        "var lexer = new Lexer(all); " +
                        "class Parser extends CstParser { constructor() { super(all); var self = this; " +
                        "self.RULE('expr', function () { self.SUBRULE(self.term); " +
                        "self.MANY(function () { self.CONSUME(P); self.SUBRULE2(self.term); }); }); " +
                        "self.RULE('term', function () { self.CONSUME(I); }); " +
                        "this.performSelfAnalysis(); } } " +
                        "var parser = new Parser(); parser.input = lexer.tokenize('a + b + c').tokens; " +
                        "var cst = parser.expr(); " +
                        "return cst.children.term.length === 3 && cst.children.P.length === 2; })()"
                ),
            ),
            SmokeCase(
                "embedded actions",
                assertion(
                    "(function () { var createToken = chevrotain.createToken, Lexer = chevrotain.Lexer, " +
                        "EmbeddedActionsParser = chevrotain.EmbeddedActionsParser; " +
                        "var N = createToken({ name: 'N', pattern: /[0-9]+/ }); " +
                        "var W = createToken({ name: 'W', pattern: /\\s+/, group: Lexer.SKIPPED }); " +
                        "var P = createToken({ name: 'P', pattern: /\\+/ }); var all = [W, P, N]; " +
                        "var lexer = new Lexer(all); " +
                        "class Calc extends EmbeddedActionsParser { constructor() { super(all); var self = this; " +
                        "self.RULE('expr', function () { var value = self.SUBRULE(self.term); " +
                        "self.MANY(function () { self.CONSUME(P); value += self.SUBRULE2(self.term); }); " +
                        "return value; }); " +
                        "self.RULE('term', function () { return Number(self.CONSUME(N).image); }); " +
                        "this.performSelfAnalysis(); } } " +
                        "var parser = new Calc(); parser.input = lexer.tokenize('1 + 2 + 3').tokens; " +
                        "return parser.expr() === 6; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "vue",
        url = "https://unpkg.com/vue@2.7.16/dist/vue.js",
        globalName = "Vue",
        cases = listOf(
            SmokeCase(
                "reactivity",
                assertion(
                    "(function () { var app = new Vue({ data: { count: 1, items: [1, 2] }, " +
                        "computed: { double: function () { return this.count * 2; } }, " +
                        "methods: { inc: function () { this.count++; } } }); var out = [app.double]; " +
                        "app.count = 5; out.push(app.double); app.inc(); " +
                        "out.push(app.count, app.double); return out.join(',') === '2,10,6,12'; })()"
                ),
            ),
            SmokeCase(
                "array mutations",
                assertion(
                    "(function () { var app = new Vue({ data: { items: [1, 2] } }); app.items.push(3); " +
                        "app.items.splice(0, 1); app.items.sort(function (a, b) { return b - a; }); " +
                        "return app.items.join(',') === '3,2'; })()"
                ),
            ),
            SmokeCase(
                "observable and set",
                assertion(
                    "(function () { var state = Vue.observable({ a: 1 }); Vue.set(state, 'b', 2); " +
                        "var ok = state.b === 2; Vue.delete(state, 'a'); " +
                        "return ok && !('a' in state); })()"
                ),
            ),
            SmokeCase(
                "watcher next tick",
                "globalThis.setTimeout = function (fn) { Promise.resolve().then(fn); }; " +
                    "(function () { var app = new Vue({ data: { count: 0 } }); " +
                    "app['${'$'}watch']('count', function (value, oldValue) { " +
                    "globalThis.__libResult = value === 2 && oldValue === 0; }); " +
                    "app.count = 2; })();",
                async = true,
            ),
        ),
    ),
    LibrarySpec(
        name = "preact",
        url = "https://unpkg.com/preact@10.19.3/dist/preact.umd.js",
        globalName = "preact",
        cases = listOf(
            SmokeCase(
                "vnode",
                assertion(
                    "(function () { var vnode = preact.h('div', { id: 'x' }, 'hi', " +
                        "preact.h('span', null, 'a')); return vnode.type === 'div' && " +
                        "vnode.props.id === 'x' && vnode.props.children.length === 2 && " +
                        "vnode.props.children[0] === 'hi' && vnode.props.children[1].type === 'span'; })()"
                ),
            ),
            SmokeCase(
                "clone and children",
                assertion(
                    "(function () { var base = preact.h('div', { id: 'a' }, 'x'); " +
                        "var clone = preact.cloneElement(base, { id: 'b' }, 'y'); " +
                        "return clone !== base && clone.props.id === 'b' && clone.props.children === 'y' && " +
                        "preact.isValidElement(base) && !preact.isValidElement({}) && " +
                        "preact.toChildArray([1, null, 2, undefined]).join(',') === '1,2'; })()"
                ),
            ),
            SmokeCase(
                "component render",
                assertion(
                    "(function () { class App extends preact.Component { " +
                        "constructor(props) { super(props); this.state = { n: 1 }; } " +
                        "render() { return preact.h('span', null, this.props.x + this.state.n); } } " +
                        "var app = new App({ x: 2 }); " +
                        "return app.render().props.children === 3; })()"
                ),
            ),
        ),
    ),
    LibrarySpec(
        name = "katex",
        url = "https://unpkg.com/katex@0.16.9/dist/katex.min.js",
        globalName = "katex",
        cases = listOf(
            SmokeCase(
                "render string",
                assertion(
                    "(function () { var html = katex.renderToString('x^2'); " +
                        "return html.indexOf('<span class=\"katex\">') === 0 && " +
                        "html.indexOf('katex-mathml') >= 0 && html.indexOf('x') >= 0; })()"
                ),
            ),
            SmokeCase(
                "fraction sqrt text",
                assertion(
                    "(function () { var bs = String.fromCharCode(92); " +
                        "return katex.renderToString(bs + 'frac{1}{2}').indexOf('mfrac') >= 0 && " +
                        "katex.renderToString(bs + 'sqrt{2}').indexOf('sqrt') >= 0 && " +
                        "katex.renderToString(bs + 'text{hi}').indexOf('hi') >= 0; })()"
                ),
            ),
            SmokeCase(
                "display mode",
                assertion(
                    "katex.renderToString('a+b', { displayMode: true, throwOnError: false })" +
                        ".indexOf('<span class=\"katex-display\">') === 0"
                ),
            ),
            SmokeCase(
                "parse error",
                assertion(
                    "(function () { var bs = String.fromCharCode(92); var thrown = false; " +
                        "try { katex.renderToString(bs + 'unknowncmd'); } " +
                        "catch (e) { thrown = e.name === 'ParseError'; } " +
                        "return thrown && katex.renderToString(bs + 'unknowncmd', " +
                        "{ throwOnError: false }).indexOf('katex') >= 0; })()"
                ),
            ),
        ),
    ),
)


private fun assertion(expression: String): String = """
    (function () {
        var value = ($expression);
        if (value !== true) {
            throw new Error('assertion failed; value=' + value);
        }
    })();
""".trimIndent()
