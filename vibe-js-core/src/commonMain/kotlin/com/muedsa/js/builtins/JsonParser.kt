package com.muedsa.js.builtins

import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSValue

/** JSON 文本不合法（由 JSON.parse 转换为 JS SyntaxError）。 */
internal class JsonParseException(message: String) : Exception(message)

/**
 * 严格 JSON 文本解析器（ES5 15.12.1.1 的 JSON 语法）。
 *
 * 不复用 JS 词法/语法分析器的原因：
 * - JSON 只接受双引号字符串，且字符串内不允许未转义的控制字符；
 * - JSON 只接受 4 种空白字符，不接受单引号、注释、尾随逗号、+号或十六进制数字。
 */
internal class JsonParser(
    private val text: String,
    private val objectPrototype: JSObject?,
    private val arrayPrototype: JSObject?,
) {

    private var index = 0

    /** 原始字面量：源码文本与解析得到的原始值。 */
    data class LiteralSource(val text: String, val value: JSValue)

    /** 原始字面量源码：按父对象记录可直接取源的原始 JSON 文本。 */
    private val sources = HashMap<JSObject, MutableMap<String, LiteralSource>>()

    /** 根值为原始字面量时的源码与值。 */
    var rootLiteral: LiteralSource? = null
        private set

    /** 查询父对象某个属性的原始字面量信息。 */
    fun sourceOf(parent: JSObject, key: String): LiteralSource? = sources[parent]?.get(key)

    fun parse(): JSValue {
        skipWhitespace()
        val start = index
        val value = parseValue()
        val end = index
        skipWhitespace()
        if (index != text.length) {
            fail("Unexpected token")
        }
        if (value !is JSObject) {
            rootLiteral = LiteralSource(text.substring(start, end), value)
        }
        return value
    }

    private fun parseValue(): JSValue {
        if (index >= text.length) fail("Unexpected end of JSON input")
        return when (val c = text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JSString(parseString())
            't' -> {
                expectLiteral("true")
                JSBoolean.TRUE
            }
            'f' -> {
                expectLiteral("false")
                JSBoolean.FALSE
            }
            'n' -> {
                expectLiteral("null")
                JSNull
            }
            else -> {
                if (c == '-' || c in '0'..'9') parseNumber() else fail("Unexpected token '$c'")
            }
        }
    }

    private fun parseObject(): JSObject {
        index++ // '{'
        val result = JSObject(prototype = objectPrototype)
        skipWhitespace()
        if (peek() == '}') {
            index++
            return result
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("Expected property name")
            val key = parseString()
            skipWhitespace()
            if (peek() != ':') fail("Expected ':' after property name")
            index++
            skipWhitespace()
            val valueStart = index
            val value = parseValue()
            val valueEnd = index
            if (value !is JSObject) {
                sources.getOrPut(result) { LinkedHashMap() }[key] =
                    LiteralSource(text.substring(valueStart, valueEnd), value)
            }
            // JSON 的重复键以后者为准
            result.defineDataProperty(
                com.muedsa.js.runtime.value.PropertyKey.Str(key),
                value,
                writable = true,
                enumerable = true,
                configurable = true,
            )
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    index++
                }
                '}' -> {
                    index++
                    return result
                }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): JSArray {
        index++ // '['
        val elements = mutableListOf<JSValue?>()
        val elementSources = LinkedHashMap<String, LiteralSource>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JSArray(elements, prototype = arrayPrototype)
        }
        while (true) {
            skipWhitespace()
            val valueStart = index
            val value = parseValue()
            val valueEnd = index
            if (value !is JSObject) {
                elementSources[elements.size.toString()] =
                    LiteralSource(text.substring(valueStart, valueEnd), value)
            }
            elements.add(value)
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    index++
                }
                ']' -> {
                    index++
                    val result = JSArray(elements, prototype = arrayPrototype)
                    if (elementSources.isNotEmpty()) sources[result] = elementSources
                    return result
                }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        if (peek() != '"') fail("Expected '\"'")
        index++
        val builder = StringBuilder()
        while (true) {
            if (index >= text.length) fail("Unterminated string")
            val c = text[index++]
            when {
                c == '"' -> return builder.toString()
                c == '\\' -> {
                    if (index >= text.length) fail("Unterminated escape")
                    when (val escaped = text[index++]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            if (index + 4 > text.length) fail("Invalid unicode escape")
                            var code = 0
                            repeat(4) {
                                val digit = text[index++].digitToIntOrNull(16)
                                    ?: fail("Invalid unicode escape")
                                code = code * 16 + digit
                            }
                            builder.append(code.toChar())
                        }
                        else -> fail("Invalid escape character '$escaped'")
                    }
                }
                // JSON 字符串内不允许未转义的控制字符
                c < ' ' -> fail("Unexpected control character in string")
                else -> builder.append(c)
            }
        }
    }

    private fun parseNumber(): JSNumber {
        val start = index
        if (peek() == '-') index++
        if (index >= text.length) fail("Invalid number")
        when {
            text[index] == '0' -> index++
            text[index] in '1'..'9' -> {
                while (index < text.length && text[index] in '0'..'9') index++
            }
            else -> fail("Invalid number")
        }
        if (index < text.length && text[index] == '.') {
            index++
            if (index >= text.length || text[index] !in '0'..'9') fail("Invalid number")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index >= text.length || text[index] !in '0'..'9') fail("Invalid number")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        val number = text.substring(start, index).toDoubleOrNull() ?: fail("Invalid number")
        return JSNumber(number)
    }

    private fun expectLiteral(literal: String) {
        if (index + literal.length > text.length ||
            text.substring(index, index + literal.length) != literal
        ) {
            fail("Unexpected token")
        }
        index += literal.length
    }

    /** JSON 空白仅包括空格、制表、换行、回车。 */
    private fun skipWhitespace() {
        while (index < text.length && text[index] in charArrayOf(' ', '\t', '\n', '\r')) {
            index++
        }
    }

    private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

    private fun fail(message: String): Nothing =
        throw JsonParseException("$message at position $index")
}
