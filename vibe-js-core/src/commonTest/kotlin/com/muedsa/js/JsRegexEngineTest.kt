package com.muedsa.js

import com.muedsa.js.runtime.regex.JsRegexEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 纯 Kotlin 正则回退引擎的直接测试。
 */
class JsRegexEngineTest {

    @Test
    fun `lookahead 中的反向引用匹配成对引号`() {
        val pattern = """(["'])(?:(?!\1)[^\\]|\\.)*?\1"""
        val engine = JsRegexEngine(pattern)
        val match = engine.find("""x "a\"b" y""")
        assertNotNull(match)
        assertEquals(""""a\"b"""", match.value)
        assertEquals("\"", match.group(1))
        assertEquals(2, match.start)

        assertNotNull(engine.find("""'a'"""))
        assertNull(engine.find("""unquoted"""))
    }

    @Test
    fun `选择分支与贪婪量词`() {
        val engine = JsRegexEngine("a(b|c)+d")
        val match = engine.find("xxabcbcd")
        assertNotNull(match)
        assertEquals("abcbcd", match.value)
        assertEquals(2, match.start)
        assertEquals("c", match.group(1))
    }

    @Test
    fun `懒惰量词与字符类`() {
        val engine = JsRegexEngine("a.*?b")
        assertEquals("a1b", engine.find("a1b2b")?.value)

        val range = JsRegexEngine("[a-c]+")
        assertEquals("abc", range.find("xxabcx")?.value)

        val digits = JsRegexEngine("""\d{2,3}""")
        assertEquals("123", digits.find("x1234")?.value)

        val negated = JsRegexEngine("[^0-9]+")
        assertEquals("abc", negated.find("12abc34")?.value)
    }

    @Test
    fun `反向引用与单词边界`() {
        val repeated = JsRegexEngine("(ab)\\1")
        assertEquals("abab", repeated.find("xabab")?.value)

        val word = JsRegexEngine("""\bcat\b""")
        assertNotNull(word.find("a cat!"))
        assertNull(word.find("scatter"))
    }

    @Test
    fun `锚点与多行模式`() {
        val engine = JsRegexEngine("^b$", "m")
        val match = engine.find("a\nb\nc")
        assertNotNull(match)
        assertEquals(2, match.start)
        assertEquals("b", match.value)

        assertNull(JsRegexEngine("^b$").find("a\nb\nc"))
    }

    @Test
    fun `忽略大小写与 dotAll`() {
        assertEquals("AbC", JsRegexEngine("abc", "i").find("xAbCx")?.value)
        assertEquals("\n", JsRegexEngine(".", "s").find("\n")?.value)
        assertNull(JsRegexEngine(".").find("\n"))
    }

    @Test
    fun `lookahead 与否定 lookahead`() {
        assertEquals("foo", JsRegexEngine("foo(?=bar)").find("xxfoobar")?.value)
        assertNull(JsRegexEngine("foo(?=bar)").find("xxfoobaz"))
        assertEquals("foo", JsRegexEngine("foo(?!bar)").find("xxfoobaz")?.value)
        assertNull(JsRegexEngine("foo(?!bar)").find("xxfoobar"))
    }

    @Test
    fun `findAll 与空匹配推进`() {
        val engine = JsRegexEngine("a*")
        val values = engine.findAll("abaa").map { it.value }.toList()
        assertTrue(values.contains("a"))
        assertTrue(values.contains(""))
    }
}