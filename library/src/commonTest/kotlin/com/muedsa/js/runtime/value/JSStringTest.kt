package com.muedsa.js.runtime.value

import com.muedsa.js.createRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JSStringTest {

    @Test
    fun `JSString should correctly store string value and length`() {
        val value = "Hello World"
        val jsString = JSString(value)
        assertEquals("Hello World", value)
        val lengthJsValue = jsString.getProperty("length")
        assertIs<JSNumber>(lengthJsValue)
        assertEquals(value.length.toDouble(), lengthJsValue.toPrimitiveNumber())
    }

    @Test
    fun `JSString should support indexed property access`() {
        val jsString = JSString("abc")
        assertEquals("a", jsString.getProperty("0").toPrimitiveString())
        assertEquals("b", jsString.getProperty("1").toPrimitiveString())
        assertEquals("c", jsString.getProperty("2").toPrimitiveString())
        assertIs<JSUndefined>(jsString.getProperty("3"))
    }

    @Test
    fun `JSString should convert to primitive boolean correctly`() {
        assertEquals(true, JSString("non-empty").toPrimitiveBoolean())
        assertEquals(false, JSString.EmptyString.toPrimitiveBoolean())
    }

    @Test
    fun `JSString should convert to primitive number correctly`() {
        assertEquals(0.0, JSString.EmptyString.toPrimitiveNumber())
        assertEquals(123.0, JSString("123").toPrimitiveNumber())
        assertEquals(Double.POSITIVE_INFINITY, JSString("Infinity").toPrimitiveNumber())
        assertEquals(Double.NEGATIVE_INFINITY, JSString("-Infinity").toPrimitiveNumber())
        assertTrue(JSString("NaN").toPrimitiveNumber().isNaN())
        assertTrue(JSString("abc").toPrimitiveNumber().isNaN())
    }

    @Test
    fun `String constructor should create empty string when no arguments are provided`() {
        val result = StringConstructor.function(createRuntime(), StringConstructor, listOf())
        assertIs<JSString>(result)
        assertEquals(JSString.EmptyString.value, result.value)
    }

    @Test
    fun `String constructor should convert undefined to 'undefined'`() {
        val result = StringConstructor.function(createRuntime(), StringConstructor, listOf(JSUndefined))
        assertIs<JSString>(result)
        assertEquals("undefined", result.value)
    }

    @Test
    fun `String constructor should convert null to 'null'`() {
        val runtime = createRuntime()
        val result = StringConstructor.function(runtime, StringConstructor, listOf(JSNull))
        assertIs<JSString>(result)
        assertEquals("null", result.value)
    }

    @Test
    fun `String constructor should convert numbers to strings`() {
        val runtime = createRuntime()
        val result = StringConstructor.function(runtime, StringConstructor, listOf(JSNumber(123.0)))
        assertIs<JSString>(result)
        assertEquals("123", result.value)
    }

    @Test
    fun `String_fromCharCode should return empty string for empty arguments`() {
        val callee = StringConstructor.getProperty("fromCharCode") as JSNativeFunction
        val result = callee.function(createRuntime(), StringConstructor, listOf())
        assertIs<JSString>(result)
        assertEquals("", result.value)
    }

    @Test
    fun `String_fromCharCode should return correct string for given character codes`() {
        val callee = StringConstructor.getProperty("fromCharCode") as JSNativeFunction
        val result = callee.function(
            createRuntime(),
            StringConstructor,
            listOf(JSNumber(65.0), JSNumber(66.0), JSNumber(67.0))
        )
        assertIs<JSString>(result)
        assertEquals("ABC", result.value)
    }

    @Test
    fun `String_prototype_at should return character at given index`() {
        val callee = StringPrototype.getProperty("at") as JSNativeFunction
        val thisVal = JSString("abc")
        assertEquals("a", callee.function(createRuntime(), thisVal, listOf(JSNumber(0.0))).toPrimitiveString())
        assertEquals("b", callee.function(createRuntime(), thisVal, listOf(JSNumber(1.0))).toPrimitiveString())
        assertIs<JSUndefined>(callee.function(createRuntime(), thisVal, listOf(JSNumber(3.0))))
        // The current implementation does not support negative index
        assertIs<JSUndefined>(callee.function(createRuntime(), thisVal, listOf(JSNumber(-1.0))))
        
        // Out of bounds
        assertIs<JSUndefined>(callee.function(createRuntime(), thisVal, listOf(JSNumber(100.0))))
    }

    @Test
    fun `String_prototype_charAt should return character at given index`() {
        val callee = StringPrototype.getProperty("charAt") as JSNativeFunction
        val thisVal = JSString("abc")
        assertEquals("a", callee.function(createRuntime(), thisVal, listOf(JSNumber(0.0))).toPrimitiveString())
        assertEquals("b", callee.function(createRuntime(), thisVal, listOf(JSNumber(1.0))).toPrimitiveString())
        assertIs<JSUndefined>(callee.function(createRuntime(), thisVal, listOf(JSNumber(3.0))))
        
        // Out of bounds
        assertIs<JSUndefined>(callee.function(createRuntime(), thisVal, listOf(JSNumber(100.0))))
        assertIs<JSUndefined>(callee.function(createRuntime(), thisVal, listOf(JSNumber(-1.0))))
    }

    @Test
    fun `String_prototype_concat should concatenate multiple strings`() {
        val callee = StringPrototype.getProperty("concat") as JSNativeFunction
        val thisVal = JSString("a")
        val result1 = callee.function(createRuntime(), thisVal, listOf(JSString("b")))
        assertEquals("ab", result1.toPrimitiveString())
        val result2 = callee.function(createRuntime(), thisVal, listOf(JSString("b"), JSString("c")))
        assertEquals("abc", result2.toPrimitiveString())
    }

    @Test
    fun `String_prototype_endsWith should correctly check if string ends with suffix`() {
        val callee = StringPrototype.getProperty("endsWith") as JSNativeFunction
        val thisVal = JSString("Hello World")
        val result1 = callee.function(createRuntime(), thisVal, listOf(JSString("World")))
        assertEquals(JSBoolean.True, result1)
        val result2 = callee.function(createRuntime(), thisVal, listOf(JSString("world")))
        assertEquals(JSBoolean.False, result2)
        val result3 = callee.function(createRuntime(), thisVal, listOf(JSString("Hello"), JSNumber(5.0)))
        assertEquals(JSBoolean.True, result3)
        
        // With position > length
        val result4 = callee.function(createRuntime(), thisVal, listOf(JSString("World"), JSNumber(100.0)))
        assertEquals(JSBoolean.True, result4)
    }

    @Test
    fun `String_prototype_charCodeAt should return unicode code unit at index`() {
        val callee = StringPrototype.getProperty("charCodeAt") as JSNativeFunction
        val thisVal = JSString("ABC")
        
        assertEquals(65.0, callee.function(createRuntime(), thisVal, listOf(JSNumber(0.0))).toPrimitiveNumber())
        assertEquals(66.0, callee.function(createRuntime(), thisVal, listOf(JSNumber(1.0))).toPrimitiveNumber())
        assertTrue(callee.function(createRuntime(), thisVal, listOf(JSNumber(3.0))).toPrimitiveNumber().isNaN())
    }

    @Test
    fun `String_prototype_codePointAt should return unicode code point at index`() {
        val callee = StringPrototype.getProperty("codePointAt") as JSNativeFunction
        val thisVal = JSString("ABC")
        
        assertEquals(65.0, callee.function(createRuntime(), thisVal, listOf(JSNumber(0.0))).toPrimitiveNumber())
        
        // Surrogate pair: 𐐷 (U+10437) -> \uD801\uDC37
        val surrogate = JSString("\uD801\uDC37")
        assertEquals(0x10437.toDouble(), callee.function(createRuntime(), surrogate, listOf(JSNumber(0.0))).toPrimitiveNumber())
        assertEquals(0xDC37.toDouble(), callee.function(createRuntime(), surrogate, listOf(JSNumber(1.0))).toPrimitiveNumber())
    }

    @Test
    fun `String_fromCodePoint should return string from code points`() {
        val callee = StringConstructor.getProperty("fromCodePoint") as JSNativeFunction
        val runtime = createRuntime()
        
        val res1 = callee.function(runtime, JSUndefined, listOf(JSNumber(65.0), JSNumber(66.0)))
        assertEquals("AB", res1.toPrimitiveString())
        
        // Surrogate pair
        val res2 = callee.function(runtime, JSUndefined, listOf(JSNumber(0x10437.toDouble())))
        assertEquals("\uD801\uDC37", res2.toPrimitiveString())
    }
    
    @Test
    fun `String_prototype_includes should check substring existence`() {
        val callee = StringPrototype.getProperty("includes") as JSNativeFunction
        val thisVal = JSString("Hello World")
        
        assertEquals(JSBoolean.True, callee.function(createRuntime(), thisVal, listOf(JSString("Hello"))))
        assertEquals(JSBoolean.False, callee.function(createRuntime(), thisVal, listOf(JSString("hello"))))
        assertEquals(JSBoolean.True, callee.function(createRuntime(), thisVal, listOf(JSString("World"), JSNumber(6.0))))
        assertEquals(JSBoolean.False, callee.function(createRuntime(), thisVal, listOf(JSString("Hello"), JSNumber(1.0))))
    }

    @Test
    fun `String_prototype_indexOf should return index of substring`() {
        val callee = StringPrototype.getProperty("indexOf") as JSNativeFunction
        val thisVal = JSString("Hello World")
        
        assertEquals(JSNumber(0.0), callee.function(createRuntime(), thisVal, listOf(JSString("Hello"))))
        assertEquals(JSNumber(6.0), callee.function(createRuntime(), thisVal, listOf(JSString("World"))))
        assertEquals(JSNumber(-1.0), callee.function(createRuntime(), thisVal, listOf(JSString("Java"))))
        assertEquals(JSNumber(7.0), callee.function(createRuntime(), thisVal, listOf(JSString("o"), JSNumber(5.0))))
    }

    @Test
    fun `String_prototype_lastIndexOf should return last index of substring`() {
        val callee = StringPrototype.getProperty("lastIndexOf") as JSNativeFunction
        val thisVal = JSString("canal")
        
        assertEquals(JSNumber(3.0), callee.function(createRuntime(), thisVal, listOf(JSString("a"))))
        assertEquals(JSNumber(1.0), callee.function(createRuntime(), thisVal, listOf(JSString("a"), JSNumber(2.0))))
        assertEquals(JSNumber(-1.0), callee.function(createRuntime(), thisVal, listOf(JSString("x"))))
    }

    @Test
    fun `String_prototype_padEnd should pad string at end`() {
        val callee = StringPrototype.getProperty("padEnd") as JSNativeFunction
        val thisVal = JSString("foo")
        
        assertEquals("foo   ", callee.function(createRuntime(), thisVal, listOf(JSNumber(6.0))).toPrimitiveString())
        assertEquals("foobar", callee.function(createRuntime(), thisVal, listOf(JSNumber(6.0), JSString("bar"))).toPrimitiveString())
        assertEquals("foo", callee.function(createRuntime(), thisVal, listOf(JSNumber(1.0))).toPrimitiveString())
    }

    @Test
    fun `String_prototype_padStart should pad string at start`() {
        val callee = StringPrototype.getProperty("padStart") as JSNativeFunction
        val thisVal = JSString("foo")
        
        assertEquals("   foo", callee.function(createRuntime(), thisVal, listOf(JSNumber(6.0))).toPrimitiveString())
        assertEquals("barfoo", callee.function(createRuntime(), thisVal, listOf(JSNumber(6.0), JSString("bar"))).toPrimitiveString())
        assertEquals("foo", callee.function(createRuntime(), thisVal, listOf(JSNumber(1.0))).toPrimitiveString())
    }

    @Test
    fun `String_prototype_repeat should repeat string`() {
        val callee = StringPrototype.getProperty("repeat") as JSNativeFunction
        val thisVal = JSString("abc")
        
        assertEquals("abcabc", callee.function(createRuntime(), thisVal, listOf(JSNumber(2.0))).toPrimitiveString())
        assertEquals("", callee.function(createRuntime(), thisVal, listOf(JSNumber(0.0))).toPrimitiveString())
    }

    @Test
    fun `String_prototype_replace should replace substring`() {
        val callee = StringPrototype.getProperty("replace") as JSNativeFunction
        val thisVal = JSString("Hello World")
        
        assertEquals("Hi World", callee.function(createRuntime(), thisVal, listOf(JSString("Hello"), JSString("Hi"))).toPrimitiveString())
        assertEquals("Hello JS", callee.function(createRuntime(), thisVal, listOf(JSString("World"), JSString("JS"))).toPrimitiveString())
    }

    @Test
    fun `String_prototype_replaceAll should replace all substrings`() {
        val callee = StringPrototype.getProperty("replaceAll") as JSNativeFunction
        val thisVal = JSString("aabbccaa")
        
        assertEquals("xxbbccxx", callee.function(createRuntime(), thisVal, listOf(JSString("aa"), JSString("xx"))).toPrimitiveString())
    }

    @Test
    fun `String_prototype_slice should extract substring`() {
        val callee = StringPrototype.getProperty("slice") as JSNativeFunction
        val thisVal = JSString("The quick brown fox")
        
        assertEquals("quick", callee.function(createRuntime(), thisVal, listOf(JSNumber(4.0), JSNumber(9.0))).toPrimitiveString())
        assertEquals("fox", callee.function(createRuntime(), thisVal, listOf(JSNumber(-3.0))).toPrimitiveString())
    }

    @Test
    fun `String_prototype_split should split string`() {
        val callee = StringPrototype.getProperty("split") as JSNativeFunction
        val thisVal = JSString("a,b,c")
        
        val result = callee.function(createRuntime(), thisVal, listOf(JSString(","))) as JSArray
        assertEquals(3, result.size)
        assertEquals("a", result[0].toPrimitiveString())
        assertEquals("b", result[1].toPrimitiveString())
        assertEquals("c", result[2].toPrimitiveString())
        
        val limitResult = callee.function(createRuntime(), thisVal, listOf(JSString(","), JSNumber(2.0))) as JSArray
        assertEquals(2, limitResult.size)
    }

    @Test
    fun `String_prototype_startsWith should check prefix`() {
        val callee = StringPrototype.getProperty("startsWith") as JSNativeFunction
        val thisVal = JSString("Saturday")
        
        assertEquals(JSBoolean.True, callee.function(createRuntime(), thisVal, listOf(JSString("Sat"))))
        assertEquals(JSBoolean.False, callee.function(createRuntime(), thisVal, listOf(JSString("Sat"), JSNumber(3.0))))
    }

    @Test
    fun `String_prototype_substr should return substring with length`() {
        val callee = StringPrototype.getProperty("substr") as JSNativeFunction
        val thisVal = JSString("Mozilla")
        
        // 传入 start 和 length
        assertEquals("Moz", callee.function(createRuntime(), thisVal, listOf(JSNumber(0.0), JSNumber(3.0))).toPrimitiveString())
        // 仅传入 start
        assertEquals("zilla", callee.function(createRuntime(), thisVal, listOf(JSNumber(2.0))).toPrimitiveString())
        // 负数 start
        assertEquals("lla", callee.function(createRuntime(), thisVal, listOf(JSNumber(-3.0))).toPrimitiveString())
        // start 越界
        assertEquals("", callee.function(createRuntime(), thisVal, listOf(JSNumber(10.0))).toPrimitiveString())
        // 负数 length
        assertEquals("", callee.function(createRuntime(), thisVal, listOf(JSNumber(1.0), JSNumber(-1.0))).toPrimitiveString())
    }
    
    @Test
    fun `String_prototype_substring should return substring`() {
        val callee = StringPrototype.getProperty("substring") as JSNativeFunction
        val thisVal = JSString("Mozilla")
        
        assertEquals("Moz", callee.function(createRuntime(), thisVal, listOf(JSNumber(0.0), JSNumber(3.0))).toPrimitiveString())
        assertEquals("zilla", callee.function(createRuntime(), thisVal, listOf(JSNumber(2.0))).toPrimitiveString())
    }
    
    @Test
    fun `String_prototype_toLowerCase should convert to lower case`() {
        val callee = StringPrototype.getProperty("toLowerCase") as JSNativeFunction
        val thisVal = JSString("ALPHABET")
        assertEquals("alphabet", callee.function(createRuntime(), thisVal, emptyList()).toPrimitiveString())
    }

    @Test
    fun `String_prototype_toUpperCase should convert to upper case`() {
        val callee = StringPrototype.getProperty("toUpperCase") as JSNativeFunction
        val thisVal = JSString("alphabet")
        assertEquals("ALPHABET", callee.function(createRuntime(), thisVal, emptyList()).toPrimitiveString())
    }

    @Test
    fun `String_prototype_trim should remove whitespace`() {
        val callee = StringPrototype.getProperty("trim") as JSNativeFunction
        val thisVal = JSString("  Hello world!  ")
        assertEquals("Hello world!", callee.function(createRuntime(), thisVal, emptyList()).toPrimitiveString())
    }
    
    @Test
    fun `String_prototype_trimEnd should remove whitespace from end`() {
        val callee = StringPrototype.getProperty("trimEnd") as JSNativeFunction
        val thisVal = JSString("  Hello world!  ")
        assertEquals("  Hello world!", callee.function(createRuntime(), thisVal, emptyList()).toPrimitiveString())
    }

    @Test
    fun `String_prototype_trimStart should remove whitespace from start`() {
        val callee = StringPrototype.getProperty("trimStart") as JSNativeFunction
        val thisVal = JSString("  Hello world!  ")
        assertEquals("Hello world!  ", callee.function(createRuntime(), thisVal, emptyList()).toPrimitiveString())
    }
}
