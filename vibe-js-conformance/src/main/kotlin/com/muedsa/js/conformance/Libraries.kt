package com.muedsa.js.conformance

/**
 * 固定版本的真实 JS 库清单（浏览器/UMD 构建）。
 */
internal data class LibrarySpec(
    val name: String,
    val url: String,
    val globalName: String,
    val cases: List<SmokeCase>,
)

internal data class SmokeCase(
    val name: String,
    val source: String,
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
)


private fun assertion(expression: String): String = """
    (function () {
        var value = ($expression);
        if (value !== true) {
            throw new Error('assertion failed; value=' + value);
        }
    })();
""".trimIndent()
