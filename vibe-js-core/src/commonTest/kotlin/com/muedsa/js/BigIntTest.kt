package com.muedsa.js

import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * L3.3 BigInt 运行时语义测试。
 *
 * 覆盖：字面量/typeof、四则与幂运算、取模、位运算、移位、比较、BigInt 与 Number
 * 混用报错、asIntN/asUintN、大整数、各进制 toString、字符串解析、Number/BigInt
 * 转换、JSON 报错、包装对象、原型与 @@toStringTag、Map/Set 键、自增自减。
 */
class BigIntTest {

    /** 断言脚本抛出指定名称的 JS 错误。 */
    private suspend fun assertThrows(
        runtime: TestRuntime,
        code: String,
        errorName: String,
    ) {
        val error = assertFailsWith<JSException>(code) { runtime.eval(code) }
        assertEquals(errorName, (error.value as JSError).errorName, code)
    }

    @Test
    fun `BigInt 字面量解析并返回 bigint 类型`() = runTest {
        val runtime = TestRuntime()
        assertEquals("bigint", runtime.evalString("typeof 123n"))
        assertEquals("bigint", runtime.evalString("typeof 0n"))
        assertEquals("bigint", runtime.evalString("typeof 0x1Fn"))
        assertEquals("bigint", runtime.evalString("typeof 0o17n"))
        assertEquals("bigint", runtime.evalString("typeof 0b101n"))
        assertEquals("bigint", runtime.evalString("typeof BigInt(1)"))
        // 三种前缀字面量的数值都应为 255
        assertEquals(
            "255|255|255",
            runtime.evalString(
                "0xffn + '|' + 0o377n + '|' + 0b11111111n"
            )
        )
        // 字面量与 BigInt() 解析结果严格相等
        assertTrue(runtime.evalBoolean("0xffn === BigInt('0xff')"))
        assertTrue(runtime.evalBoolean("123n === BigInt(123)"))
        // 数字分隔符只允许出现在字面量中
        assertEquals("1000", runtime.evalString("String(1_000n)"))
        assertEquals("1000", runtime.evalString("String(0x3_e8n)"))
        assertThrows(runtime, "BigInt('1_000');", "SyntaxError")
    }

    @Test
    fun `BigInt 四则运算保持任意精度`() = runTest {
        val runtime = TestRuntime()
        assertEquals("579", runtime.evalString("String(123n + 456n)"))
        assertEquals("-333", runtime.evalString("String(123n - 456n)"))
        assertEquals("56088", runtime.evalString("String(123n * 456n)"))
        // 除法向零截断，取模符号与被除数一致
        assertEquals("3", runtime.evalString("String(7n / 2n)"))
        assertEquals("-3", runtime.evalString("String(-7n / 2n)"))
        assertEquals("1", runtime.evalString("String(7n % 2n)"))
        assertEquals("-1", runtime.evalString("String(-7n % 2n)"))
        assertEquals("1", runtime.evalString("String(7n % -2n)"))
        // 零的特殊结果
        assertEquals("0", runtime.evalString("String(0n / 5n)"))
        assertEquals("0", runtime.evalString("String(0n % 5n)"))
        assertEquals("-5", runtime.evalString("String(0n - 5n)"))
        assertEquals("5", runtime.evalString("String(5n + 0n)"))
    }

    @Test
    fun `BigInt 幂运算与零指数`() = runTest {
        val runtime = TestRuntime()
        assertEquals("8", runtime.evalString("String(2n ** 3n)"))
        assertEquals("1", runtime.evalString("String(0n ** 0n)"))
        assertEquals("0", runtime.evalString("String(0n ** 3n)"))
        assertEquals("1", runtime.evalString("String(1n ** 1000000n)"))
        // 负底数需要用变量规避 `**` 左操作数不能是一元表达式的语法限制
        assertEquals(
            "-1",
            runtime.evalString("var neg = -1n; String(neg ** 1000001n)")
        )
        assertEquals(
            "1",
            runtime.evalString("var neg = -1n; String(neg ** 1000000n)")
        )
        assertEquals(
            "-8",
            runtime.evalString("var negBase = -2n; String(negBase ** 3n)")
        )
        // 负指数按规范抛 RangeError
        assertThrows(runtime, "2n ** -1n;", "RangeError")
    }

    @Test
    fun `BigInt 位运算按补码语义`() = runTest {
        val runtime = TestRuntime()
        assertEquals("0", runtime.evalString("String(0xF0n & 0x0Fn)"))
        assertEquals("240", runtime.evalString("String(0xF0n & 0xFFn)"))
        assertEquals("255", runtime.evalString("String(0xF0n | 0x0Fn)"))
        assertEquals("255", runtime.evalString("String(0xF0n ^ 0x0Fn)"))
        // 负数参与位运算：-1n 在无限长补码中全为 1
        assertEquals("255", runtime.evalString("String(-1n & 0xFFn)"))
        assertEquals("-256", runtime.evalString("String(-1n ^ 0xFFn)"))
        assertEquals("-2", runtime.evalString("String(~1n)"))
        assertEquals("0", runtime.evalString("String(~(-1n))"))
        assertEquals("-6", runtime.evalString("String(~5n)"))
    }

    @Test
    fun `BigInt 移位支持负数与向下取整`() = runTest {
        val runtime = TestRuntime()
        assertEquals("18446744073709551616", runtime.evalString("String(1n << 64n)"))
        assertEquals("0", runtime.evalString("String(1n << -1n)"))
        assertEquals("2", runtime.evalString("String(1n << 1n)"))
        assertEquals("-10", runtime.evalString("String(-5n << 1n)"))
        // 右移是算术移位（向下取整）
        assertEquals("-3", runtime.evalString("String(-5n >> 1n)"))
        assertEquals("-1", runtime.evalString("String(-1n >> 128n)"))
        assertEquals("-1", runtime.evalString("String(-5n >> 100n)"))
        assertEquals("0", runtime.evalString("String(5n >> 100n)"))
        assertEquals("-2", runtime.evalString("String(-5n << -2n)"))
        // 超出实现上限的移位量：左移抛 RangeError，右移收敛到 0n/-1n
        assertThrows(runtime, "1n << (2n ** 100n);", "RangeError")
        assertEquals("0", runtime.evalString("String(1n >> (2n ** 100n))"))
        assertEquals("-1", runtime.evalString("String(-1n >> (2n ** 100n))"))
        assertEquals("0", runtime.evalString("String(1n << (-(2n ** 100n)))"))
    }

    @Test
    fun `BigInt 可与 Number 做值比较且严格相等要求同类型`() = runTest {
        val runtime = TestRuntime()
        assertTrue(runtime.evalBoolean("1n < 2n"))
        assertTrue(runtime.evalBoolean("1n <= 1n"))
        assertTrue(runtime.evalBoolean("2n > 1n"))
        assertTrue(runtime.evalBoolean("2n >= 2n"))
        assertTrue(runtime.evalBoolean("1n == 1"))
        assertTrue(runtime.evalBoolean("1n === 1n"))
        assertTrue(runtime.evalBoolean("1n !== 1"))
        assertTrue(runtime.evalBoolean("0n == -0"))
        // Number 的极值与小数按精确数学值比较
        assertTrue(runtime.evalBoolean("0n < 0.000000000001"))
        assertTrue(runtime.evalBoolean("9007199254740993n > 9007199254740992"))
        assertTrue(runtime.evalBoolean("0n < Number.MIN_VALUE"))
        assertTrue(runtime.evalBoolean("-10n < Number.MIN_VALUE"))
        assertTrue(runtime.evalBoolean("Number.MAX_VALUE > 1n"))
        // NaN 参与的所有关系比较均为 false
        assertTrue(runtime.evalBoolean("!(1n < NaN) && !(1n > NaN) && !(NaN < 1n) && !(1n <= NaN)"))
        // 字符串按 StringToBigInt 比较（"1.0" 不相等）
        assertTrue(runtime.evalBoolean("1n == '1'"))
        assertTrue(runtime.evalBoolean("0n == ''"))
        assertTrue(runtime.evalBoolean("0n == '-0'"))
        assertTrue(runtime.evalBoolean("!(1n == '1.0')"))
        assertTrue(runtime.evalBoolean("!(1n == 'foo')"))
        // 布尔先转 Number
        assertTrue(runtime.evalBoolean("1n == true"))
        assertTrue(runtime.evalBoolean("0n == false"))
    }

    @Test
    fun `BigInt 与 Number 混用算术与位运算抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf(
            "1n + 1;",
            "1 + 1n;",
            "1n - 1;",
            "1n * 1;",
            "1n / 1;",
            "1n % 1;",
            "1n ** 1n ** 1;",
            "1n & 1;",
            "1n | 1;",
            "1n ^ 1;",
            "1n << 1;",
            "1n >> 1;",
            "1n >>> 0n;",
            "Object(1n) + 1;",
            "1n + Object(1);",
            "1n & Object(1);",
        )) {
            assertThrows(runtime, code, "TypeError")
        }
        // + 只有在任一侧是字符串时才做拼接
        assertEquals("12", runtime.evalString("1n + '2'"))
        assertEquals("21", runtime.evalString("'2' + 1n"))
        assertEquals("1", runtime.evalString("'' + 1n"))
        assertEquals("1", runtime.evalString($$"`${1n}`"))
    }

    @Test
    fun `BigInt asIntN 与 asUintN 按位宽截断`() = runTest {
        val runtime = TestRuntime()
        assertEquals("0", runtime.evalString("String(BigInt.asIntN(0, 123n))"))
        assertEquals("-1", runtime.evalString("String(BigInt.asIntN(8, 255n))"))
        assertEquals("127", runtime.evalString("String(BigInt.asIntN(8, 127n))"))
        assertEquals("-128", runtime.evalString("String(BigInt.asIntN(8, 128n))"))
        assertEquals("255", runtime.evalString("String(BigInt.asUintN(8, -1n))"))
        assertEquals("0", runtime.evalString("String(BigInt.asUintN(0, -1n))"))
        assertEquals("1", runtime.evalString("String(BigInt.asUintN(1, -1n))"))
        assertEquals(
            "3",
            runtime.evalString("String(BigInt.asIntN(4, 12345678901234567890003n))")
        )
        assertEquals(
            "3",
            runtime.evalString("String(BigInt.asIntN(4, '12345678901234567890003'))")
        )
        // 参数转换顺序：先 ToIndex(bits) 再 ToBigInt(bigint)
        assertEquals(
            "12",
            runtime.evalString(
                """
                var i = 0;
                var bits = { valueOf: function () { i = i * 10 + 1; return 0; } };
                var value = { valueOf: function () { i = i * 10 + 2; return 0n; } };
                BigInt.asIntN(bits, value);
                String(i);
                """.trimIndent()
            )
        )
        assertEquals("0", runtime.evalString("String(BigInt.asUintN(NaN, 1n))"))
        assertThrows(runtime, "BigInt.asIntN(-1, 1n);", "RangeError")
        assertThrows(runtime, "BigInt.asIntN(0n, 1n);", "TypeError")
    }

    @Test
    fun `BigInt 大整数运算超过 64 位`() = runTest {
        val runtime = TestRuntime()
        val left = "123456789012345678901234567890"
        val right = "987654321098765432109876543210"
        assertEquals(
            "1111111110111111111011111111100",
            runtime.evalString("String(${left}n + ${right}n)")
        )
        assertEquals(
            "-864197532086419753208641975320",
            runtime.evalString("String(${left}n - ${right}n)")
        )
        assertEquals(
            "121932631137021795226185032733622923332237463801111263526900",
            runtime.evalString("String(${left}n * ${right}n)")
        )
        // 2^200 的十进制展开
        assertEquals(
            "1606938044258990275541962092341162602522202993782792835301376",
            runtime.evalString("String(2n ** 200n)")
        )
        // 多 limb 除法的商与余数
        val dividend = "962944663178273124502802769822818774400680111923861441592118"
        val divisor = "303166357886952093676032941620550969279"
        assertEquals(
            "3176291293961271933610",
            runtime.evalString("String(${dividend}n / ${divisor}n)")
        )
        assertEquals(
            "7261694887382111438647024477404024928",
            runtime.evalString("String(${dividend}n % ${divisor}n)")
        )
        assertEquals(
            "291932426382434889806653845186322636123011886378563709248417266246650406610163676987521069866542922",
            runtime.evalString("String(${dividend}n * ${divisor}n)")
        )
        // 负数除法向零截断，余数与被除数同号
        val negativeDividend = "1121237102058999081957067883843418688456151441147428990525815"
        val negativeDivisor = "3032551925546891088745512954"
        assertEquals(
            "-369733851088731129835173508591921",
            runtime.evalString("String(-${negativeDividend}n / ${negativeDivisor}n)")
        )
        assertEquals(
            "-1258960435681954473585281181",
            runtime.evalString("String(-${negativeDividend}n % ${negativeDivisor}n)")
        )
    }

    @Test
    fun `BigInt toString 支持 2 到 36 进制`() = runTest {
        val runtime = TestRuntime()
        assertEquals("255", runtime.evalString("String(255n)"))
        assertEquals("ff", runtime.evalString("255n.toString(16)"))
        assertEquals("11111111", runtime.evalString("255n.toString(2)"))
        assertEquals("377", runtime.evalString("255n.toString(8)"))
        assertEquals("73", runtime.evalString("255n.toString(36)"))
        assertEquals("-ff", runtime.evalString("(-255n).toString(16)"))
        assertEquals("10", runtime.evalString("10n.toString()"))
        assertEquals("10", runtime.evalString("10n.toString(undefined)"))
        assertEquals("10", runtime.evalString("10n.toLocaleString()"))
        // 大数十进制/十六进制往返
        val big = "962944663178273124502802769822818774400680111923861441592118"
        assertEquals(
            "6zfb22ottgtbm95t169dmldt95vracwe40e4gmu",
            runtime.evalString("(${big}n).toString(36)")
        )
        assertThrows(runtime, "1n.toString(1);", "RangeError")
        assertThrows(runtime, "1n.toString(37);", "RangeError")
        assertThrows(runtime, "1n.toString(NaN);", "RangeError")
        // 非 BigInt 接收者抛 TypeError
        assertThrows(runtime, "BigInt.prototype.toString.call(1);", "TypeError")
        assertThrows(runtime, "BigInt.prototype.valueOf.call('1');", "TypeError")
    }

    @Test
    fun `BigInt 字符串解析遵循 StringToBigInt`() = runTest {
        val runtime = TestRuntime()
        assertEquals("42", runtime.evalString("String(BigInt('  42  '))"))
        assertEquals("255", runtime.evalString("String(BigInt('0xFF'))"))
        assertEquals("255", runtime.evalString("String(BigInt('0o377'))"))
        assertEquals("255", runtime.evalString("String(BigInt('0b11111111'))"))
        assertEquals("0", runtime.evalString("String(BigInt(''))"))
        assertEquals("0", runtime.evalString("String(BigInt('   '))"))
        assertEquals("0", runtime.evalString("String(BigInt('-0'))"))
        assertEquals("-12", runtime.evalString("String(BigInt(' -12 '))"))
        assertEquals("12", runtime.evalString("String(BigInt('+12'))"))
        assertEquals("12", runtime.evalString("String(BigInt('00012'))"))
        // 非法格式：小数、指数、BigInt 后缀、非十进制带符号、空进制数字
        for (text in listOf(
            "1.5", "1e3", "10n", "10x", "0x", "0o", "0b",
            "-0x1", "+0x1", "0oa", "000 12",
        )) {
            assertThrows(runtime, "BigInt('$text');", "SyntaxError")
        }
        // Number 非整数/非有限值抛 RangeError
        for (code in listOf("BigInt(1.5);", "BigInt(NaN);", "BigInt(Infinity);", "BigInt(-Infinity);")) {
            assertThrows(runtime, code, "RangeError")
        }
        // undefined/null/Symbol 抛 TypeError
        for (code in listOf("BigInt();", "BigInt(undefined);", "BigInt(null);", "BigInt(Symbol('1'));")) {
            assertThrows(runtime, code, "TypeError")
        }
        // Boolean 与包装对象按 ToBigInt 处理
        assertEquals("1", runtime.evalString("String(BigInt(true))"))
        assertEquals("0", runtime.evalString("String(BigInt(false))"))
        assertEquals("42", runtime.evalString("String(BigInt({ valueOf: function () { return 42n; } }))"))
        assertEquals("42", runtime.evalString("String(BigInt(Object(42n)))"))
    }

    @Test
    fun `BigInt 与 Number 显式转换`() = runTest {
        val runtime = TestRuntime()
        assertEquals(0.0, runtime.evalNumber("Number(0n)"))
        assertEquals(42.0, runtime.evalNumber("Number(42n)"))
        assertEquals(-42.0, runtime.evalNumber("Number(-42n)"))
        // 超出 2^53 的 BigInt 按最近偶数舍入
        assertEquals(9007199254740992.0, runtime.evalNumber("Number(2n ** 53n + 1n)"))
        assertEquals(9007199254740994.0, runtime.evalNumber("Number(2n ** 53n + 2n)"))
        assertEquals(9007199254740996.0, runtime.evalNumber("Number(2n ** 53n + 3n)"))
        assertEquals(Double.POSITIVE_INFINITY, runtime.evalNumber("Number(2n ** 2000n)"))
        assertTrue(runtime.evalBoolean("new Number(1n) instanceof Number"))
        assertEquals(1.0, runtime.evalNumber("Number(new Number(1n))"))
        // ToNumber(BigInt) 按规范抛 TypeError
        assertThrows(runtime, "+1n;", "TypeError")
        assertThrows(runtime, "1n * 1;", "TypeError")
        assertThrows(runtime, "Number.prototype.toString.call(1n);", "TypeError")
        // ToBoolean(0n) 为 false
        assertTrue(runtime.evalBoolean("Boolean(0n) === false"))
        assertTrue(runtime.evalBoolean("Boolean(1n) === true"))
        assertTrue(runtime.evalBoolean("if (1n) { true } else { false }"))
        // ToString(BigInt) 可用
        assertEquals("123", runtime.evalString("String(123n)"))
        assertEquals("123", runtime.evalString("'' + 123n"))
    }

    @Test
    fun `JSON stringify BigInt 抛 TypeError`() = runTest {
        val runtime = TestRuntime()
        assertThrows(runtime, "JSON.stringify(1n);", "TypeError")
        assertThrows(runtime, "JSON.stringify({ x: 1n });", "TypeError")
        assertThrows(runtime, "JSON.stringify([1n]);", "TypeError")
        assertThrows(runtime, "JSON.stringify(Object(1n));", "TypeError")
        // 其他值不受影响
        assertEquals("{\"x\":1}", runtime.evalString("JSON.stringify({ x: 1 })"))
    }

    @Test
    fun `Object 装箱 BigInt 并支持 valueOf 与 toStringTag`() = runTest {
        val runtime = TestRuntime()
        assertTrue(runtime.evalBoolean("Object(1n) instanceof BigInt"))
        assertTrue(runtime.evalBoolean("typeof Object(1n) === 'object'"))
        assertTrue(runtime.evalBoolean("Object(1n).valueOf() === 1n"))
        assertEquals("1", runtime.evalString("Object(1n).toString()"))
        assertEquals("1", runtime.evalString("String(Object(1n))"))
        // 包装对象的 ToPrimitive 默认取 valueOf
        assertTrue(runtime.evalBoolean("Object(2n) + 1n === 3n"))
        assertTrue(runtime.evalBoolean("Object(2n) < 3"))
        // Object.prototype.toString 的标签
        assertEquals("[object BigInt]", runtime.evalString("Object.prototype.toString.call(1n)"))
        assertEquals(
            "[object BigInt]",
            runtime.evalString("Object.prototype.toString.call(Object(1n))")
        )
        // @@toStringTag
        assertEquals("BigInt", runtime.evalString("BigInt.prototype[Symbol.toStringTag]"))
        assertTrue(
            runtime.evalBoolean(
                "Object.getOwnPropertyDescriptor(BigInt.prototype, Symbol.toStringTag).configurable"
            )
        )
        assertTrue(
            runtime.evalBoolean(
                "!Object.getOwnPropertyDescriptor(BigInt.prototype, Symbol.toStringTag).writable"
            )
        )
    }

    @Test
    fun `BigInt 构造器不可 new 且原型链正确`() = runTest {
        val runtime = TestRuntime()
        assertEquals("function", runtime.evalString("typeof BigInt"))
        assertEquals(1.0, runtime.evalNumber("BigInt.length"))
        assertEquals("BigInt", runtime.evalString("BigInt.name"))
        assertThrows(runtime, "new BigInt(1);", "TypeError")
        assertThrows(runtime, "Reflect.construct(BigInt, [1n]);", "TypeError")
        // BigInt.prototype 的 [[Prototype]] 是 Object.prototype
        assertTrue(runtime.evalBoolean("Object.getPrototypeOf(BigInt.prototype) === Object.prototype"))
        assertTrue(runtime.evalBoolean("BigInt.prototype.constructor === BigInt"))
        assertTrue(runtime.evalBoolean("Object.getPrototypeOf(BigInt) === Function.prototype"))
        assertEquals(2.0, runtime.evalNumber("BigInt.asIntN.length"))
        assertEquals(2.0, runtime.evalNumber("BigInt.asUintN.length"))
        assertTrue(runtime.evalBoolean("typeof BigInt.asIntN === 'function'"))
        // radix 是可选参数，toString 的 length 为 0
        assertEquals(0.0, runtime.evalNumber("BigInt.prototype.toString.length"))
        assertEquals(0.0, runtime.evalNumber("BigInt.prototype.valueOf.length"))
        // 原型方法不是构造器
        assertThrows(runtime, "new BigInt.prototype.toString();", "TypeError")
    }

    @Test
    fun `BigInt 一元运算与自增自减`() = runTest {
        val runtime = TestRuntime()
        assertTrue(runtime.evalBoolean("-1n === 0n - 1n"))
        assertTrue(runtime.evalBoolean("~0n === -1n"))
        assertTrue(runtime.evalBoolean("~(-1n) === 0n"))
        // 一元 -/~ 使用 ToNumeric：包装对象与 @@toPrimitive 返回的 BigInt 都可用
        assertTrue(runtime.evalBoolean("~Object(1n) === -2n"))
        assertTrue(runtime.evalBoolean("-Object(1n) === -1n"))
        assertTrue(
            runtime.evalBoolean(
                "-({ [Symbol.toPrimitive]: function () { return 3n; } }) === -3n"
            )
        )
        assertEquals(
            "1|3|3",
            runtime.evalString(
                """
                var x = 1n;
                var a = x++;
                var b = ++x;
                var c = x--;
                a + '|' + b + '|' + c;
                """.trimIndent()
            )
        )
        assertEquals(
            "-1|-2",
            runtime.evalString(
                """
                var y = 1n;
                y--; y--;
                y + '|' + (--y);
                """.trimIndent()
            )
        )
        // ToNumeric：包装对象自增后写回的是 BigInt 原始值
        assertEquals(
            "2|bigint",
            runtime.evalString(
                """
                var boxed = Object(1n);
                boxed++;
                String(boxed) + '|' + typeof boxed;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `BigInt 可作为 Map 与 Set 的键并按值比较`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "3|a|b",
            runtime.evalString(
                """
                var map = new Map();
                map.set(1n, 'a');
                map.set(9007199254740991n, 'b');
                map.set(9007199254740991, 'number');
                map.size + '|' + map.get(1n) + '|' + map.get(9007199254740991n);
                """.trimIndent()
            )
        )
        assertEquals(
            "1|true",
            runtime.evalString(
                """
                var set = new Set([1n, 1n, BigInt('1')]);
                set.size + '|' + set.has(1n);
                """.trimIndent()
            )
        )
        assertTrue(runtime.evalBoolean("1n !== 1"))
        assertTrue(runtime.evalBoolean("new Map([[1n, 'x']]).get(1n) === 'x'"))
    }

    @Test
    fun `BigInt 除零与取模零抛 RangeError`() = runTest {
        val runtime = TestRuntime()
        for (code in listOf("1n / 0n;", "1n % 0n;", "0n / 0n;", "0n % 0n;")) {
            assertThrows(runtime, code, "RangeError")
        }
    }

    @Test
    fun `BigInt valueOf 与 toString 的 this 检查`() = runTest {
        val runtime = TestRuntime()
        assertTrue(runtime.evalBoolean("BigInt.prototype.valueOf.call(0n) === 0n"))
        assertTrue(runtime.evalBoolean("BigInt.prototype.valueOf.call(Object(0n)) === 0n"))
        for (code in listOf(
            "BigInt.prototype.valueOf.call(BigInt.prototype);",
            "BigInt.prototype.valueOf.call(0);",
            "BigInt.prototype.toString.call(0);",
            "BigInt.prototype.toLocaleString.call(0);",
        )) {
            assertThrows(runtime, code, "TypeError")
        }
    }

    @Test
    fun `BigInt 作为属性键时转换为十进制字符串`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "a|a|a",
            runtime.evalString(
                """
                var o = {};
                o[1n] = 'a';
                o[1] + '|' + o['1'] + '|' + o[BigInt('1')];
                """.trimIndent()
            )
        )
        assertEquals(
            "1,2",
            runtime.evalString(
                """
                var o = {};
                o[1n] = 1;
                o[2n] = 2;
                Object.keys(o).join(',');
                """.trimIndent()
            )
        )
        // 大整数键使用完整十进制展开，而不是 Number 舍入后的值
        assertEquals(
            "true",
            runtime.evalString(
                """
                var o = {};
                o[9007199254740993n] = 'x';
                String(o['9007199254740993'] === 'x' && o[9007199254740992] === undefined);
                """.trimIndent()
            )
        )
        // 对象/类字面量中的 BigInt 属性名按 ToString 处理
        assertEquals(
            "true|bar|baz|foo",
            runtime.evalString(
                """
                var a = { 999999999999999999n: true };
                var b = { 1n: function () { return 'bar'; } };
                class C { 1n() { return 'baz'; } }
                var { 1n: d } = { '1': 'foo' };
                String(a['999999999999999999'] === true) + '|' + b['1']() + '|' +
                    new C()['1']() + '|' + d;
                """.trimIndent()
            )
        )
    }

    @Test
    fun `BigInt 宽松比较的边界情况`() = runTest {
        val runtime = TestRuntime()
        assertTrue(runtime.evalBoolean("!(1n == null)"))
        assertTrue(runtime.evalBoolean("!(1n == undefined)"))
        assertTrue(runtime.evalBoolean("!(0n == null)"))
        // Object(1) 先 ToPrimitive 得到 Number(1)，再与 BigInt 按数学值比较
        assertTrue(runtime.evalBoolean("1n == Object(1)"))
        assertTrue(runtime.evalBoolean("1n == { valueOf: function () { return 1; } }"))
        assertTrue(runtime.evalBoolean("1n == { toString: function () { return '1'; } }"))
        // 关系比较里字符串按 StringToBigInt 处理（与 == 一致）
        assertTrue(runtime.evalBoolean("1n < '2'"))
        assertTrue(runtime.evalBoolean("'0' < 1n"))
        assertTrue(runtime.evalBoolean("'' < 1n"))
        assertTrue(runtime.evalBoolean("'0x10' > 15n"))
        assertTrue(runtime.evalBoolean("'9007199254740993' > 9007199254740992n"))
        // 不是合法 BigInt 字面量的字符串视为不可比（与 ToNumber 的结果不同）
        assertTrue(runtime.evalBoolean("!(1n > '0.')"))
        assertTrue(runtime.evalBoolean("!(1n < '0.')"))
        assertTrue(runtime.evalBoolean("!(1n < '1e0')"))
        assertTrue(runtime.evalBoolean("!(1n >= 'Infinity')"))
        assertTrue(runtime.evalBoolean("!(1n < 'x')"))
        assertTrue(runtime.evalBoolean("!(1n >= 'x')"))
        assertTrue(runtime.evalBoolean("2 > '1'"))
        // Symbol 参与比较/转换抛 TypeError
        assertThrows(runtime, "1n < Symbol('1');", "TypeError")
        assertThrows(runtime, "1n + Symbol('1');", "TypeError")
    }

    @Test
    fun `BigInt 在数组查找中按值比较`() = runTest {
        val runtime = TestRuntime()
        assertEquals(0.0, runtime.evalNumber("[1n, 2n, 1n].indexOf(1n)"))
        assertEquals(2.0, runtime.evalNumber("[1n, 2n, 1n].lastIndexOf(1n)"))
        assertTrue(runtime.evalBoolean("[1n, 2n].includes(2n)"))
        assertTrue(runtime.evalBoolean("![1n, 2n].includes(3n)"))
        // Number 与 BigInt 不会被混为一谈
        assertEquals(-1.0, runtime.evalNumber("[1n, 2n].indexOf(1)"))
        assertTrue(runtime.evalBoolean("![1n].includes(1)"))
    }

    @Test
    fun `BigInt 在严格模式下语义一致`() = runTest {
        val runtime = TestRuntime()
        assertEquals(
            "bigint|1024|-1|0",
            runtime.evalString(
                """
                'use strict';
                var value = 2n ** 10n;
                typeof value + '|' + String(value) + '|' +
                    String(BigInt.asIntN(1, 1n)) + '|' + String(BigInt.asUintN(0, -1n));
                """.trimIndent()
            )
        )
    }
}
