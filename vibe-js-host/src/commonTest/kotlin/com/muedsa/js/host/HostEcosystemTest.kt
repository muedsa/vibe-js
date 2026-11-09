package com.muedsa.js.host

import com.muedsa.js.runtime.Engine
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * L4.5 宿主生态：TextEncoder / TextDecoder、structuredClone 与 URL 子集。
 */
class HostEcosystemTest {

    private fun engine(): Engine = Engine().also { engine ->
        engine.install(HostModule(engine.eventLoop))
    }

    private suspend fun eval(engine: Engine, source: String): String =
        (engine.run(source) as JSString).value

    @Test
    fun `TextEncoder 编码 UTF-8 与孤立代理项`() = runTest {
        val engine = engine()
        assertEquals(
            "228,189,160,229,165,189",
            eval(engine, "Array.from(new TextEncoder().encode('你好')).join(',')"),
        )
        assertEquals("6", eval(engine, "String(new TextEncoder().encode('你好').length)"))
        assertEquals("utf-8", eval(engine, "new TextEncoder().encoding"))
        // 孤立代理项按 U+FFFD 编码为 EF BF BD
        assertEquals(
            "239,191,189",
            eval(engine, "Array.from(new TextEncoder().encode('\\uD83C')).join(',')"),
        )
    }

    @Test
    fun `TextEncoder encodeInto 返回读取与写入长度`() = runTest {
        val engine = engine()
        assertEquals(
            "1,3",
            eval(
                engine,
                """
                (function () {
                    var target = new Uint8Array(4);
                    var result = new TextEncoder().encodeInto('你', target);
                    return [result.read, result.written].join(',');
                })()
                """.trimIndent(),
            ),
        )
        assertEquals(
            "0,0",
            eval(
                engine,
                """
                (function () {
                    var target = new Uint8Array(2);
                    var result = new TextEncoder().encodeInto('你', target);
                    return [result.read, result.written].join(',');
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `TextDecoder 解码、fatal 与 BOM`() = runTest {
        val engine = engine()
        assertEquals(
            "你好",
            eval(engine, "new TextDecoder().decode(new TextEncoder().encode('你好'))"),
        )
        assertEquals(
            "a",
            eval(engine, "new TextDecoder().decode(new Uint8Array([239,187,191,97]))"),
        )
        assertEquals(
            "\uFEFFa",
            eval(
                engine,
                "new TextDecoder('utf-8', { ignoreBOM: true })" +
                    ".decode(new Uint8Array([239,187,191,97]))",
            ),
        )
        val failure = assertFailsWith<JSException> {
            engine.run("new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array([255]))")
        }
        assertEquals("TypeError", (failure.value as JSError).errorName)
    }

    @Test
    fun `TextDecoder stream 保留不完整序列`() = runTest {
        val engine = engine()
        assertEquals(
            "|你好",
            eval(
                engine,
                """
                (function () {
                    var bytes = new TextEncoder().encode('你好');
                    var decoder = new TextDecoder();
                    var first = decoder.decode(bytes.subarray(0, 2), { stream: true });
                    var second = decoder.decode(bytes.subarray(2));
                    return first + '|' + second;
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `structuredClone 复制对象、数组与循环引用`() = runTest {
        val engine = engine()
        assertEquals(
            "true,true,true,true",
            eval(
                engine,
                """
                (function () {
                    var source = { a: 1, list: [1, 2] };
                    source.self = source;
                    var copy = structuredClone(source);
                    return [
                        copy !== source,
                        copy.self === copy,
                        copy.a === 1,
                        copy.list !== source.list && copy.list[1] === 2
                    ].join(',');
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `structuredClone 复制 Map Set Date RegExp 与二进制`() = runTest {
        val engine = engine()
        assertEquals(
            "true,true,123,true",
            eval(
                engine,
                """
                (function () {
                    var map = new Map([['k', { n: 1 }]]);
                    var set = new Set([1, 2]);
                    var date = new Date(123);
                    var regexp = /a+/gi;
                    return [
                        structuredClone(map).get('k').n === 1,
                        structuredClone(set).has(2),
                        structuredClone(date).getTime(),
                        structuredClone(regexp).source === 'a+'
                    ].join(',');
                })()
                """.trimIndent(),
            ),
        )
        assertEquals(
            "true,true,7,2",
            eval(
                engine,
                """
                (function () {
                    var buffer = new ArrayBuffer(4);
                    var x = new Uint8Array(buffer);
                    x[0] = 7;
                    var y = new Uint8Array(buffer);
                    var view = new DataView(buffer, 0, 2);
                    var copy = structuredClone({ x: x, y: y, view: view });
                    return [
                        copy.x.buffer === copy.y.buffer,
                        copy.x !== x,
                        copy.x[0],
                        copy.view.byteLength
                    ].join(',');
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `structuredClone 拒绝函数与 Symbol`() = runTest {
        val engine = engine()
        val functionFailure = assertFailsWith<JSException> {
            engine.run("structuredClone(function () {})")
        }
        assertEquals("DataCloneError", (functionFailure.value as JSError).errorName)
        val symbolFailure = assertFailsWith<JSException> {
            engine.run("structuredClone(Symbol('x'))")
        }
        assertEquals("DataCloneError", (symbolFailure.value as JSError).errorName)
    }

    @Test
    fun `URL 解析绝对与相对地址`() = runTest {
        val engine = engine()
        assertEquals(
            "https://example.com/a/b?x=1#h",
            eval(engine, "new URL('https://example.com:443/a/b?x=1#h').href"),
        )
        assertEquals(
            "https://example.com/a/c?y=2",
            eval(engine, "new URL('../c?y=2', 'https://example.com/a/b/').href"),
        )
        assertEquals(
            "https://example.com",
            eval(engine, "new URL('https://example.com/a').origin"),
        )
        assertEquals(
            "example.com",
            eval(engine, "new URL('https://example.com/a').hostname"),
        )
    }

    @Test
    fun `URL searchParams 与 URL 双向同步`() = runTest {
        val engine = engine()
        assertEquals(
            "https://example.com/a?x=1&y=2",
            eval(
                engine,
                """
                (function () {
                    var url = new URL('https://example.com/a?x=1');
                    url.searchParams.set('y', '2');
                    return url.href;
                })()
                """.trimIndent(),
            ),
        )
        assertEquals(
            "2",
            eval(
                engine,
                """
                (function () {
                    var url = new URL('https://example.com/a?x=1');
                    url.searchParams.set('x', '2');
                    return url.searchParams.get('x');
                })()
                """.trimIndent(),
            ),
        )
        assertEquals(
            "https://example.com/a?z=3",
            eval(
                engine,
                """
                (function () {
                    var url = new URL('https://example.com/a?x=1');
                    url.search = '?z=3';
                    return url.href;
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `URLSearchParams 解析、编码与迭代`() = runTest {
        val engine = engine()
        assertEquals(
            "hello world",
            eval(engine, "new URLSearchParams('a=1&b=hello+world').get('b')"),
        )
        assertEquals(
            "你好",
            eval(engine, "new URLSearchParams('name=%E4%BD%A0%E5%A5%BD').get('name')"),
        )
        assertEquals(
            "a=1&a=2",
            eval(
                engine,
                """
                (function () {
                    var params = new URLSearchParams();
                    params.append('a', '1');
                    params.append('a', '2');
                    return params.toString();
                })()
                """.trimIndent(),
            ),
        )
        assertEquals(
            "a,b",
            eval(engine, "Array.from(new URLSearchParams('a=1&b=2').keys()).join(',')"),
        )
        assertEquals(
            "a=3",
            eval(
                engine,
                """
                (function () {
                    var params = new URLSearchParams('a=1&a=2');
                    params.set('a', '3');
                    return params.toString();
                })()
                """.trimIndent(),
            ),
        )
        assertEquals(
            "3",
            eval(engine, "String(new URLSearchParams('a=1&b=2&c=3').size)"),
        )
        assertEquals(
            "a:1,a:2,b:3|a=2&b=3",
            eval(
                engine,
                """
                (function () {
                    var params = new URLSearchParams('a=1&a=2&b=3');
                    var out = [];
                    params.forEach(function (value, name) { out.push(name + ':' + value); });
                    params.delete('a', '1');
                    return [out.join(','), params.toString()].join('|');
                })()
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `TextDecoder 对不完整序列只替换一次`() = runTest {
        val engine = engine()
        assertEquals(
            "\uFFFD",
            eval(engine, "new TextDecoder().decode(new Uint8Array([228,189]))"),
        )
        val failure = assertFailsWith<JSException> {
            engine.run(
                "new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array([228,189]))",
            )
        }
        assertEquals("TypeError", (failure.value as JSError).errorName)
    }

    @Test
    fun `URL 与 URLSearchParams 的品牌检查`() = runTest {
        val engine = engine()
        val urlFailure = assertFailsWith<JSException> {
            engine.run("URL.prototype.toString.call({})")
        }
        assertEquals("TypeError", (urlFailure.value as JSError).errorName)
        val paramsFailure = assertFailsWith<JSException> {
            engine.run("URLSearchParams.prototype.get.call({}, 'a')")
        }
        assertEquals("TypeError", (paramsFailure.value as JSError).errorName)
        val encoderFailure = assertFailsWith<JSException> {
            engine.run("TextEncoder.prototype.encode.call({})")
        }
        assertEquals("TypeError", (encoderFailure.value as JSError).errorName)
    }

    @Test
    fun `URL href setter 重新解析并同步 searchParams`() = runTest {
        val engine = engine()
        assertEquals(
            "https://example.org/b?z=1&y=2",
            eval(
                engine,
                """
                (function () {
                    var url = new URL('https://example.com/a');
                    url.href = 'https://example.org/b?z=1';
                    url.searchParams.set('y', '2');
                    return url.href;
                })()
                """.trimIndent(),
            ),
        )
    }
}
