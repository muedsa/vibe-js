package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Array / String / Number / Math 的补全测试。
 *
 * 覆盖阶段 B/C 实现但此前没有断言的 API，以及稀疏数组、Unicode
 * 与数字格式化等边界行为。
 */
class BuiltinArrayStringAdvancedTest {

    // ================================================================
    // Array
    // ================================================================

    @Test
    fun `Array_of 与 Array_isArray`() = runTest {
        val runtime = TestRuntime()
        assertEquals(1.0, runtime.evalNumber("Array.of(3).length"))
        assertEquals(3.0, runtime.evalNumber("Array.of(3)[0]"))
        assertEquals("1,2,3", runtime.evalString("Array.of(1, 2, 3).join(',')"))
        assertEquals(true, runtime.evalBoolean("Array.isArray([])"))
        assertEquals(true, runtime.evalBoolean("Array.isArray(new Array(2))"))
        assertEquals(false, runtime.evalBoolean("Array.isArray({ 0: 1, length: 1 })"))
        assertEquals(false, runtime.evalBoolean("Array.isArray('abc')"))
    }

    @Test
    fun `lastIndexOf 与 indexOf 的起点参数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("[1, 2, 1].lastIndexOf(1)"))
        assertEquals(0.0, runtime.evalNumber("[1, 2, 1].lastIndexOf(1, 1)"))
        assertEquals(-1.0, runtime.evalNumber("[1, 2, 3].lastIndexOf(4)"))
        assertEquals(-1.0, runtime.evalNumber("[NaN].lastIndexOf(NaN)"))
        assertEquals(1.0, runtime.evalNumber("[1, 2, 2].lastIndexOf(2, -2)"))
        assertEquals(1.0, runtime.evalNumber("[1, 2, 3].indexOf(2, -2)"))
        assertEquals(-1.0, runtime.evalNumber("[1, 2, 3].indexOf(2, -1)"))
    }

    @Test
    fun `reduceRight 从右向左聚合`() = runTest {
        val runtime = TestRuntime()
        assertEquals(0.0, runtime.evalNumber("[1, 2, 3].reduceRight(function (a, b) { return a - b; })"))
        assertEquals(16.0, runtime.evalNumber("[1, 2, 3].reduceRight(function (a, b) { return a + b; }, 10)"))
        assertEquals(
            "3,2,1",
            runtime.evalString(
                "var s = []; [1, 2, 3].reduceRight(function (a, b) { s.push(b); return a; }, 0); s.join(',')"
            )
        )
    }

    @Test
    fun `reduce 空数组缺少初始值时抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        val error = assertFailsWith<JSException> {
            runtime.eval("[].reduce(function (a, b) { return a + b; })")
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
        val errorRight = assertFailsWith<JSException> {
            runtime.eval("[].reduceRight(function (a, b) { return a + b; })")
        }
        assertEquals("TypeError", (errorRight.value as JSError).errorName)
    }

    @Test
    fun `稀疏数组跳过空洞的遍历方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            0.0,
            runtime.evalNumber("var n = 0; new Array(3).forEach(function () { n++; }); n")
        )
        assertEquals(
            2.0,
            runtime.evalNumber("var n = 0; [1, , 3].map(function () { n++; }); n")
        )
        assertEquals("2,,6", runtime.evalString("[1, , 3].map(function (x) { return x * 2; }).join(',')"))
        assertEquals(-1.0, runtime.evalNumber("[1, , 3].indexOf(undefined)"))
        assertEquals("1,,3", runtime.evalString("[1, , 3].join(',')"))
    }

    @Test
    fun `includes 将空洞视为 undefined`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("new Array(3).includes(undefined)"))
        assertEquals(true, runtime.evalBoolean("[1, , 3].includes(undefined)"))
        assertEquals(true, runtime.evalBoolean("[1, NaN].includes(NaN)"))
        assertEquals(false, runtime.evalBoolean("[1, 2].includes(3)"))
    }

    @Test
    fun `展开运算符填充数组空洞`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("[...[1, , 3]].length"))
        assertEquals("1--3", runtime.evalString("[...[1, , 3]].join('-')"))
        assertEquals(3.0, runtime.evalNumber("Array.from(new Array(3)).length"))
    }

    @Test
    fun `slice 与 splice 的负数索引`() = runTest {
        val runtime = TestRuntime()
        assertEquals("2,3", runtime.evalString("[1, 2, 3].slice(-2).join(',')"))
        assertEquals("1,2", runtime.evalString("[1, 2, 3].slice(0, -1).join(',')"))
        assertEquals("1,4,3", runtime.evalString("var a = [1, 2, 3]; a.splice(-2, 1, 4); a.join(',')"))
        assertEquals(
            "2,3:1",
            runtime.evalString("var a = [1, 2, 3]; var removed = a.splice(1); removed.join(',') + ':' + a.length")
        )
    }

    @Test
    fun `Array_from 支持映射函数与类数组`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "aa,bb",
            runtime.evalString("Array.from('ab', function (x) { return x + x; }).join(',')")
        )
        assertEquals(
            "a,b",
            runtime.evalString("Array.from({ length: 2, 0: 'a', 1: 'b' }).join(',')")
        )
        assertEquals(
            "2,4",
            runtime.evalString("Array.from([1, 2], function (x) { return x * 2; }).join(',')")
        )
    }

    @Test
    fun `Array 只读方法支持 array-like 对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "2,4",
            runtime.evalString(
                "Array.prototype.map.call({ 0: 1, 1: 2, length: 2 }, function (x) { return x * 2; }).join(',')"
            )
        )
        assertEquals(
            "2",
            runtime.evalString(
                "Array.prototype.filter.call({ 0: 1, 1: 2, length: 2 }, function (x) { return x > 1; }).join(',')"
            )
        )
        assertEquals(
            "b",
            runtime.evalString("Array.prototype.slice.call({ 0: 'a', 1: 'b', length: 2 }, 1).join(',')")
        )
        assertEquals(
            // concat 是泛型方法：非数组 this 作为单个元素，而不是按 array-like 展开
            "[object Object],b",
            runtime.evalString("Array.prototype.concat.call({ 0: 'a', length: 1 }, ['b']).join(',')")
        )
        assertEquals(
            "a-b",
            runtime.evalString("Array.prototype.join.call({ 0: 'a', 1: 'b', length: 2 }, '-')")
        )
        assertEquals(
            false,
            runtime.evalBoolean(
                "Array.prototype.every.call({ 0: 9, length: 1 }, function (x) { return x > 10; })"
            )
        )
    }

    @Test
    fun `Array 写方法回写 array-like 对象`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "3:3",
            runtime.evalString(
                """
                var o = { 0: 1, 1: 2, length: 2 };
                var length = Array.prototype.push.call(o, 3);
                length + ':' + o[2];
                """.trimIndent()
            )
        )
        assertEquals(
            "2:3",
            runtime.evalString(
                """
                var o = { 0: 1, 1: 2, 2: 3, length: 3 };
                var popped = Array.prototype.pop.call(o);
                o.length + ':' + popped;
                """.trimIndent()
            )
        )
        assertEquals(
            true,
            runtime.evalBoolean(
                """
                var o = { 0: 1, length: 1 };
                var same = false;
                Array.prototype.map.call(o, function (value, index, obj) { same = (obj === o); return value; });
                same;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `数组迭代器的 done 与 value`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "1:false:2:true",
            runtime.evalString(
                """
                var it = [1, 2].values();
                var a = it.next();
                var b = it.next();
                var c = it.next();
                a.value + ':' + a.done + ':' + b.value + ':' + c.done;
                """.trimIndent()
            )
        )
        assertEquals(
            "0,1",
            runtime.evalString("var it = ['x', 'y'].keys(); var a = it.next().value; a + ',' + it.next().value")
        )
        assertEquals(
            "0,x:1,y",
            runtime.evalString(
                "var it = ['x', 'y'].entries(); var a = it.next().value; var b = it.next().value; a.join(',') + ':' + b.join(',')"
            )
        )
    }

    // ================================================================
    // String
    // ================================================================

    @Test
    fun `charCodeAt 与 codePointAt`() = runTest {
        val runtime = TestRuntime()
        assertEquals(65.0, runtime.evalNumber("'A'.charCodeAt(0)"))
        assertTrue(runtime.evalNumber("'A'.charCodeAt(1)").isNaN())
        assertEquals(97.0, runtime.evalNumber("'abc'.charCodeAt(0.9)"))
        assertEquals(128512.0, runtime.evalNumber("'\uD83D\uDE00'.codePointAt(0)"))
        assertEquals(2.0, runtime.evalNumber("'\uD83D\uDE00'.length"))
    }

    @Test
    fun `String_fromCharCode 与 fromCodePoint`() = runTest {
        val runtime = TestRuntime()
        assertEquals("AB", runtime.evalString("String.fromCharCode(65, 66)"))
        assertEquals("\uD83D\uDE00", runtime.evalString("String.fromCodePoint(128512)"))
        assertEquals("\u2603", runtime.evalString("String.fromCodePoint(9731)"))
        assertEquals("", runtime.evalString("String.fromCharCode()"))
    }

    @Test
    fun `startsWith 与 endsWith 的位置参数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("'hello'.startsWith('ell', 1)"))
        assertEquals(false, runtime.evalBoolean("'hello'.startsWith('he', 1)"))
        assertEquals(true, runtime.evalBoolean("'hello'.endsWith('ell', 4)"))
        assertEquals(false, runtime.evalBoolean("'hello'.endsWith('ell', 3)"))
        assertEquals(true, runtime.evalBoolean("'hello'.startsWith('')"))
        assertEquals(true, runtime.evalBoolean("'hello'.endsWith('')"))
    }

    @Test
    fun `slice substring substr 的边界处理`() = runTest {
        val runtime = TestRuntime()
        assertEquals("def", runtime.evalString("'abcdef'.slice(-3)"))
        assertEquals("bcd", runtime.evalString("'abcdef'.slice(1, -2)"))
        assertEquals("bcd", runtime.evalString("'abcdef'.substring(4, 1)"))
        assertEquals("cdef", runtime.evalString("'abcdef'.substr(2)"))
        assertEquals("de", runtime.evalString("'abcdef'.substr(-3, 2)"))
        assertEquals("", runtime.evalString("'abc'.slice(2, 1)"))
    }

    @Test
    fun `lastIndexOf 与 localeCompare`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("'banana'.lastIndexOf('an')"))
        assertEquals(1.0, runtime.evalNumber("'banana'.lastIndexOf('an', 2)"))
        assertEquals(-1.0, runtime.evalNumber("'banana'.lastIndexOf('x')"))
        assertEquals(0.0, runtime.evalNumber("'a'.localeCompare('a')"))
        assertEquals(true, runtime.evalBoolean("'a'.localeCompare('b') < 0"))
        assertEquals(true, runtime.evalBoolean("'b'.localeCompare('a') > 0"))
    }

    @Test
    fun `trimStart trimEnd 与 valueOf`() = runTest {
        val runtime = TestRuntime()
        assertEquals("x  ", runtime.evalString("'  x  '.trimStart()"))
        assertEquals("  x", runtime.evalString("'  x  '.trimEnd()"))
        assertEquals("string", runtime.evalString("typeof 'a'.valueOf()"))
        assertEquals("ab", runtime.evalString("'a'.concat('b')"))
    }

    @Test
    fun `split 限制数量与捕获组`() = runTest {
        val runtime = TestRuntime()
        assertEquals("a|b", runtime.evalString("'a,b,c'.split(',', 2).join('|')"))
        assertEquals("", runtime.evalString("'a,b,c'.split(',', 0).join('|')"))
        assertEquals("a,b,", runtime.evalString("'a1b2'.split(/\\d/).join(',')"))
        assertEquals("a|1|b|2|", runtime.evalString("'a1b2'.split(/(\\d)/).join('|')"))
        assertEquals("a,b", runtime.evalString("'ab'.split(/(?:)/).join(',')"))
        assertEquals("a|1", runtime.evalString("'a1b2'.split(/(\\d)/, 2).join('|')"))
        assertEquals("ab", runtime.evalString("'ab'.split(/$/).join(',')"))
        assertEquals("ab", runtime.evalString("'ab'.split(/(?=$)/).join(',')"))
        assertEquals("a,", runtime.evalString("'a1'.split(/1/).join(',')"))
    }

    @Test
    fun `replace 字符串模式支持替换占位符`() = runTest {
        val runtime = TestRuntime()
        assertEquals("[a]", runtime.evalString("'a'.replace('a', '[\$&]')"))
        assertEquals("\$a", runtime.evalString("'a'.replace('a', '\$\$a')"))
        assertEquals("ab1", runtime.evalString("'ab'.replace('b', '\$&1')"))
        assertEquals("-abc", runtime.evalString("'abc'.replace('', '-')"))
    }

    @Test
    fun `repeat 与 padStart padEnd 边界`() = runTest {
        val runtime = TestRuntime()
        assertEquals("", runtime.evalString("'ab'.repeat(0)"))
        assertEquals("abab", runtime.evalString("'ab'.repeat(2)"))
        assertEquals("    x", runtime.evalString("'x'.padStart(5)"))
        assertEquals("x    ", runtime.evalString("'x'.padEnd(5)"))
        assertEquals("x", runtime.evalString("'x'.padStart(1, 'ab')"))
        val error = assertFailsWith<JSException> { runtime.eval("'a'.repeat(-1)") }
        assertEquals("RangeError", (error.value as JSError).errorName)
    }

    @Test
    fun `indexOf 与 includes 的起点参数`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("'banana'.indexOf('an', 2)"))
        assertEquals(-1.0, runtime.evalNumber("'banana'.indexOf('an', 4)"))
        assertEquals(true, runtime.evalBoolean("'banana'.includes('na', 4)"))
        assertEquals(false, runtime.evalBoolean("'banana'.includes('na', 6)"))
    }

    @Test
    fun `String 包装对象的 toString 与 valueOf`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "abc:abc:abc",
            runtime.evalString(
                "var s = new String('abc'); s.toString() + ':' + s.valueOf() + ':' + String(s)"
            )
        )
        val error = assertFailsWith<JSException> {
            runtime.eval("String.prototype.toString.call(1)")
        }
        assertEquals("TypeError", (error.value as JSError).errorName)
    }

    @Test
    fun `内建方法不能被 new 调用`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "new (String.prototype.charAt)",
            "new (Array.prototype.map)",
            "new (Number.prototype.toFixed)",
            "new (Object.prototype.toString)",
        )) {
            val error = assertFailsWith<JSException> { runtime.eval(code) }
            assertEquals("TypeError", (error.value as JSError).errorName)
        }
        assertEquals(0.0, runtime.evalNumber("new Date(0).getTime()"))
    }

    @Test
    fun `Number 与 Boolean 原型的接收者语义`() = runTest {
        val runtime = TestRuntime()
        assertEquals("2", runtime.evalString("new Number(2).toString()"))
        assertEquals(2.0, runtime.evalNumber("new Number(2).valueOf()"))
        assertEquals(0.0, runtime.evalNumber("Number.prototype.valueOf()"))
        assertEquals(false, runtime.evalBoolean("Boolean.prototype.valueOf()"))
        assertEquals("false", runtime.evalString("new Boolean(false).toString()"))
        for (code in listOf(
            "Number.prototype.toString.call({})",
            "Number.prototype.valueOf.call('1')",
            "Boolean.prototype.toString.call({})",
            "Boolean.prototype.valueOf.call(0)",
        )) {
            val error = assertFailsWith<JSException> { runtime.eval(code) }
            assertEquals("TypeError", (error.value as JSError).errorName)
        }
    }

    @Test
    fun `URI 编解码函数`() = runTest {
        val runtime = TestRuntime()
        assertEquals("a%20b", runtime.evalString("encodeURIComponent('a b')"))
        assertEquals("%E4%B8%AD", runtime.evalString("encodeURIComponent('中')"))
        assertEquals(
            "https://example.com/?q=%E4%B8%AD&x=1",
            runtime.evalString("encodeURI('https://example.com/?q=中&x=1')")
        )
        assertEquals("中", runtime.evalString("decodeURIComponent('%E4%B8%AD')"))
        assertEquals(
            "https://example.com/?q=中&x=1",
            runtime.evalString("decodeURI('https://example.com/?q=%E4%B8%AD&x=1')")
        )
        assertEquals("?", runtime.evalString("decodeURIComponent('%3F')"))
        assertEquals("%3F", runtime.evalString("decodeURI('%3F')"))
        val error = assertFailsWith<JSException> {
            runtime.eval("decodeURIComponent('%E4')")
        }
        assertEquals("URIError", (error.value as JSError).errorName)
    }

    @Test
    fun `字符串 Unicode 代理对行为`() = runTest {
        val runtime = TestRuntime()
        assertEquals(2.0, runtime.evalNumber("'\uD83D\uDE00'.length"))
        assertEquals(55357.0, runtime.evalNumber("'\uD83D\uDE00'.charCodeAt(0)"))
        assertEquals("\uD83D\uDE00", runtime.evalString("'a\uD83D\uDE00b'.slice(1, 3)"))
        assertEquals(1.0, runtime.evalNumber("'a\uD83D\uDE00b'.indexOf('\uD83D\uDE00')"))
        assertEquals(true, runtime.evalBoolean("'\uD83D\uDE00' === String.fromCodePoint(128512)"))
    }

    // ================================================================
    // Number / parseInt / parseFloat
    // ================================================================

    @Test
    fun `Number 静态判断方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("Number.isFinite(1)"))
        assertEquals(false, runtime.evalBoolean("Number.isFinite('1')"))
        assertEquals(false, runtime.evalBoolean("Number.isFinite(Infinity)"))
        assertEquals(false, runtime.evalBoolean("Number.isFinite(NaN)"))
        assertEquals(true, runtime.evalBoolean("Number.isInteger(1)"))
        assertEquals(true, runtime.evalBoolean("Number.isInteger(-0)"))
        assertEquals(false, runtime.evalBoolean("Number.isInteger(1.5)"))
        assertEquals(false, runtime.evalBoolean("Number.isInteger('1')"))
        assertEquals(true, runtime.evalBoolean("Number.EPSILON > 0"))
        assertEquals(9007199254740991.0, runtime.evalNumber("Number.MAX_SAFE_INTEGER"))
        assertEquals(-9007199254740991.0, runtime.evalNumber("Number.MIN_SAFE_INTEGER"))
    }

    @Test
    fun `Number 构造函数转换规则`() = runTest {
        val runtime = TestRuntime()
        assertEquals(0.0, runtime.evalNumber("Number('')"))
        assertEquals(0.0, runtime.evalNumber("Number('   ')"))
        assertEquals(0.0, runtime.evalNumber("Number(null)"))
        assertTrue(runtime.evalNumber("Number(undefined)").isNaN())
        assertEquals(1.0, runtime.evalNumber("Number(true)"))
        assertEquals(0.0, runtime.evalNumber("Number(false)"))
        assertEquals(16.0, runtime.evalNumber("Number('0x10')"))
        assertEquals(0.0, runtime.evalNumber("Number([])"))
        assertEquals(5.0, runtime.evalNumber("Number([5])"))
        assertTrue(runtime.evalNumber("Number({})").isNaN())
        assertTrue(runtime.evalNumber("Number('12px')").isNaN())
    }

    @Test
    fun `Number_toString 进制与特殊值`() = runTest {
        val runtime = TestRuntime()
        assertEquals("11111111", runtime.evalString("(255).toString(2)"))
        assertEquals("377", runtime.evalString("(255).toString(8)"))
        assertEquals("73", runtime.evalString("(255).toString(36)"))
        assertEquals("0.1", runtime.evalString("(0.5).toString(2)"))
        assertEquals("NaN", runtime.evalString("NaN.toString()"))
        assertEquals("Infinity", runtime.evalString("Infinity.toString()"))
        assertEquals("0", runtime.evalString("(-0).toString()"))
    }

    @Test
    fun `toFixed toExponential toPrecision 基本格式`() = runTest {
        val runtime = TestRuntime()
        assertEquals("0.00", runtime.evalString("(0).toFixed(2)"))
        assertEquals("1.20", runtime.evalString("(1.2).toFixed(2)"))
        assertEquals("-1.20", runtime.evalString("(-1.2).toFixed(2)"))
        assertEquals("1.23456e+2", runtime.evalString("(123.456).toExponential()"))
        assertEquals("1.2e+2", runtime.evalString("(123.456).toPrecision(2)"))
        assertEquals("123.5", runtime.evalString("(123.456).toPrecision(4)"))
    }

    @Test
    fun `parseInt 与 parseFloat 边界`() = runTest {
        val runtime = TestRuntime()
        assertEquals(42.0, runtime.evalNumber("parseInt('  42px')"))
        assertEquals(-12.0, runtime.evalNumber("parseInt(' -12px')"))
        assertEquals(31.0, runtime.evalNumber("parseInt('0x1f')"))
        assertEquals(35.0, runtime.evalNumber("parseInt('z', 36)"))
        assertTrue(runtime.evalNumber("parseInt('')").isNaN())
        assertTrue(runtime.evalNumber("parseInt('px')").isNaN())
        assertEquals(3.14, runtime.evalNumber("parseFloat('3.14abc')"))
        assertEquals(1000.0, runtime.evalNumber("parseFloat('1e3')"))
        assertEquals(Double.POSITIVE_INFINITY, runtime.evalNumber("parseFloat('Infinity')"))
        assertTrue(runtime.evalNumber("parseFloat('')").isNaN())
    }

    // ================================================================
    // Math
    // ================================================================

    @Test
    fun `Math_min 与 max 的空参数和混合符号`() = runTest {
        val runtime = TestRuntime()
        assertEquals(Double.NEGATIVE_INFINITY, runtime.evalNumber("Math.max()"))
        assertEquals(Double.POSITIVE_INFINITY, runtime.evalNumber("Math.min()"))
        assertEquals(1.0, runtime.evalNumber("Math.min(2, 1, 3)"))
        assertEquals(-1.0, runtime.evalNumber("Math.max(-1, -2)"))
        assertEquals(-5.0, runtime.evalNumber("Math.min(-5, 2)"))
    }

    @Test
    fun `Math 扩展数值方法`() = runTest {
        val runtime = TestRuntime()
        assertEquals(3.0, runtime.evalNumber("Math.sqrt(9)"))
        assertEquals(-3.0, runtime.evalNumber("Math.cbrt(-27)"))
        assertEquals(-1.0, runtime.evalNumber("Math.sign(-3)"))
        assertEquals(0.0, runtime.evalNumber("Math.sign(0)"))
        assertEquals(-1.0, runtime.evalNumber("Math.trunc(-1.9)"))
        assertEquals(5.0, runtime.evalNumber("Math.hypot(3, 4)"))
        assertEquals(1.5, runtime.evalNumber("Math.fround(1.5)"))
        assertEquals(3.0, runtime.evalNumber("Math.log2(8)"))
        assertEquals(2.0, runtime.evalNumber("Math.log10(100)"))
        assertEquals(0.0, runtime.evalNumber("Math.log1p(0)"))
        assertEquals(0.0, runtime.evalNumber("Math.expm1(0)"))
        assertEquals(0.0, runtime.evalNumber("Math.sinh(0)"))
        assertEquals(1.0, runtime.evalNumber("Math.cosh(0)"))
        assertEquals(0.0, runtime.evalNumber("Math.tanh(0)"))
        assertEquals(0.0, runtime.evalNumber("Math.asinh(0)"))
        assertEquals(0.0, runtime.evalNumber("Math.acosh(1)"))
        assertEquals(0.0, runtime.evalNumber("Math.atanh(0)"))
        assertEquals(32.0, runtime.evalNumber("Math.clz32(0)"))
        assertEquals(1.0, runtime.evalNumber("Math.exp(0)"))
        assertEquals(1.0, runtime.evalNumber("Math.cos(0)"))
    }

    @Test
    fun `Math 常量与 random 范围`() = runTest {
        val runtime = TestRuntime()
        assertEquals(true, runtime.evalBoolean("Math.abs(Math.PI - 3.141592653589793) < 1e-12"))
        assertEquals(true, runtime.evalBoolean("Math.abs(Math.E - 2.718281828459045) < 1e-12"))
        assertEquals(true, runtime.evalBoolean("Math.abs(Math.SQRT2 - 1.4142135623730951) < 1e-12"))
        assertEquals(true, runtime.evalBoolean("Math.abs(Math.LN2 - 0.6931471805599453) < 1e-12"))
        assertEquals(true, runtime.evalBoolean("var r = Math.random(); r >= 0 && r < 1"))
    }
}
