package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * ECMAScript 全局对象上的标准函数与常量。
 *
 * 注意：console / window / setTimeout 等宿主能力不在这里，
 * 它们由独立的宿主模块注册。
 */
object GlobalBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics

        // 不可写、不可配置的全局常量
        registry.defineGlobal(
            name = "undefined",
            value = JSUndefined,
            writable = false,
            enumerable = false,
            configurable = false,
        )
        registry.defineGlobal(
            name = "NaN",
            value = JSNumber.NAN,
            writable = false,
            enumerable = false,
            configurable = false,
        )
        registry.defineGlobal(
            name = "Infinity",
            value = JSNumber.POSITIVE_INFINITY,
            writable = false,
            enumerable = false,
            configurable = false,
        )

        // globalThis 指向全局对象自身
        registry.defineGlobal(
            name = "globalThis",
            value = registry.globalObject,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        // eval：字符串原样返回，否则在当前上下文执行源码
        val evalFunction = BuiltinHelpers.method(intrinsics, "eval", length = 1) { interpreter, _, args ->
            val value = args.getOrElse(0) { JSUndefined }
            if (value is JSString) {
                interpreter.evaluateIndirectEval(value.value)
            } else {
                value
            }
        }
        intrinsics.evalFunction = evalFunction
        registry.defineGlobal(
            name = "eval",
            value = evalFunction,
        )

        // Annex B escape/unescape
        registry.defineGlobal(
            name = "escape",
            value = BuiltinHelpers.method(intrinsics, "escape", length = 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val builder = StringBuilder()
                for (c in text) {
                    if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                        c in "@*_+-./"
                    ) {
                        builder.append(c)
                    } else if (c.code < 256) {
                        builder.append('%')
                        builder.append(c.code.toString(16).uppercase().padStart(2, '0'))
                    } else {
                        builder.append("%u")
                        builder.append(c.code.toString(16).uppercase().padStart(4, '0'))
                    }
                }
                JSString(builder.toString())
            },
        )
        registry.defineGlobal(
            name = "unescape",
            value = BuiltinHelpers.method(intrinsics, "unescape", length = 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val builder = StringBuilder()
                var index = 0
                fun hexAt(position: Int): Int {
                    if (position >= text.length) return -1
                    return when (val c = text[position]) {
                        in '0'..'9' -> c - '0'
                        in 'a'..'f' -> c - 'a' + 10
                        in 'A'..'F' -> c - 'A' + 10
                        else -> -1
                    }
                }
                while (index < text.length) {
                    val c = text[index]
                    if (c == '%' && index + 5 < text.length && text[index + 1] == 'u') {
                        var value = 0
                        var valid = true
                        for (offset in 2..5) {
                            val digit = hexAt(index + offset)
                            if (digit < 0) {
                                valid = false
                                break
                            }
                            value = value * 16 + digit
                        }
                        if (valid) {
                            builder.append(value.toChar())
                            index += 6
                            continue
                        }
                    }
                    if (c == '%' && index + 2 < text.length) {
                        val high = hexAt(index + 1)
                        val low = hexAt(index + 2)
                        if (high >= 0 && low >= 0) {
                            builder.append((high * 16 + low).toChar())
                            index += 3
                            continue
                        }
                    }
                    builder.append(c)
                    index++
                }
                JSString(builder.toString())
            },
        )

        // parseInt / parseFloat
        // 与 Number.parseInt/Number.parseFloat 共享同一函数对象
        val parseIntFunction = intrinsics.parseIntFunction
            ?: BuiltinHelpers.method(intrinsics, "parseInt", length = 2) { interpreter, _, args ->
                NumberParsing.parseInt(interpreter, args)
            }
        val parseFloatFunction = intrinsics.parseFloatFunction
            ?: BuiltinHelpers.method(intrinsics, "parseFloat", length = 1) { interpreter, _, args ->
                NumberParsing.parseFloat(interpreter, args)
            }
        registry.defineGlobal(name = "parseInt", value = parseIntFunction)
        registry.defineGlobal(name = "parseFloat", value = parseFloatFunction)

        // 全局 isNaN / isFinite（会做 ToNumber 转换）
        registry.defineGlobal(
            name = "isNaN",
            value = BuiltinHelpers.method(intrinsics, "isNaN", length = 1) { interpreter, _, args ->
                JSBoolean.getJsBoolean(interpreter.toNumber(args.getOrElse(0) { JSUndefined }).isNaN())
            },
        )
        registry.defineGlobal(
            name = "isFinite",
            value = BuiltinHelpers.method(intrinsics, "isFinite", length = 1) { interpreter, _, args ->
                JSBoolean.getJsBoolean(interpreter.toNumber(args.getOrElse(0) { JSUndefined }).isFinite())
            },
        )

        // URI 处理函数
        registry.defineGlobal(
            name = "encodeURIComponent",
            value = BuiltinHelpers.method(intrinsics, "encodeURIComponent", 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSString(encodeUri(text, extraUnescaped = ""))
            },
        )
        registry.defineGlobal(
            name = "encodeURI",
            value = BuiltinHelpers.method(intrinsics, "encodeURI", 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSString(encodeUri(text, extraUnescaped = ";,/?:@&=+\$#"))
            },
        )
        registry.defineGlobal(
            name = "decodeURIComponent",
            value = BuiltinHelpers.method(intrinsics, "decodeURIComponent", 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSString(decodeUri(text, preserveReserved = false))
            },
        )
        registry.defineGlobal(
            name = "decodeURI",
            value = BuiltinHelpers.method(intrinsics, "decodeURI", 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSString(decodeUri(text, preserveReserved = true))
            },
        )

        // Math / JSON 对象（原型骨架已在 Intrinsics 创建，方法由对应模块安装）
        registry.defineGlobal("Math", intrinsics.mathObject)
        registry.defineGlobal("JSON", intrinsics.jsonObject)
    }

    private const val URI_UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
    private const val URI_RESERVED = ";/?:@&=+\$,#"

    /**
     * ECMAScript encodeURI/encodeURIComponent 的百分号编码。
     *
     * 按 UTF-16 码元转换为 UTF-8；孤立代理项抛 URIError（Kotlin 默认编码会静默替换）。
     */
    private fun encodeUri(text: String, extraUnescaped: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character.code < 0x80) {
                if (character in URI_UNRESERVED || character in extraUnescaped) {
                    builder.append(character)
                } else {
                    appendPercentByte(builder, character.code)
                }
                index++
                continue
            }
            val codePoint: Int
            when {
                character.isHighSurrogate() -> {
                    if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) {
                        throw uriError("URI malformed")
                    }
                    codePoint = 0x10000 +
                        ((character.code - 0xD800) shl 10) +
                        (text[index + 1].code - 0xDC00)
                    index += 2
                }

                character.isLowSurrogate() -> throw uriError("URI malformed")
                else -> {
                    codePoint = character.code
                    index++
                }
            }
            when {
                codePoint < 0x800 -> {
                    appendPercentByte(builder, 0xC0 or (codePoint shr 6))
                    appendPercentByte(builder, 0x80 or (codePoint and 0x3F))
                }

                codePoint < 0x10000 -> {
                    appendPercentByte(builder, 0xE0 or (codePoint shr 12))
                    appendPercentByte(builder, 0x80 or ((codePoint shr 6) and 0x3F))
                    appendPercentByte(builder, 0x80 or (codePoint and 0x3F))
                }

                else -> {
                    appendPercentByte(builder, 0xF0 or (codePoint shr 18))
                    appendPercentByte(builder, 0x80 or ((codePoint shr 12) and 0x3F))
                    appendPercentByte(builder, 0x80 or ((codePoint shr 6) and 0x3F))
                    appendPercentByte(builder, 0x80 or (codePoint and 0x3F))
                }
            }
        }
        return builder.toString()
    }

    /** 解码百分号编码；按 UTF-8 字符逐个验证，非法序列抛 URIError。 */
    private fun decodeUri(text: String, preserveReserved: Boolean): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] != '%') {
                builder.append(text[index])
                index++
                continue
            }
            val sequenceStart = index
            val first = readPercentByte(text, index) ?: throw uriError("URI malformed")
            index += 3
            // 依据 UTF-8 首字节确定后续字节数；后续必须紧邻百分号转义
            val continuationCount = when (first) {
                in 0x00..0x7F -> 0
                in 0xC2..0xDF -> 1
                in 0xE0..0xEF -> 2
                in 0xF0..0xF4 -> 3
                else -> throw uriError("URI malformed")
            }
            val bytes = ByteArray(continuationCount + 1)
            bytes[0] = first.toByte()
            for (offset in 1..continuationCount) {
                val byte = readPercentByte(text, index) ?: throw uriError("URI malformed")
                if (byte and 0xC0 != 0x80) throw uriError("URI malformed")
                bytes[offset] = byte.toByte()
                index += 3
            }
            val decoded = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: Throwable) {
                throw uriError("URI malformed")
            }
            // decodeURI 保留保留字符：输出原始转义文本（保留大小写），而不是重新编码
            if (preserveReserved && decoded.length == 1 && decoded[0] in URI_RESERVED) {
                builder.append(text, sequenceStart, index)
            } else {
                builder.append(decoded)
            }
        }
        return builder.toString()
    }

    /** 读取 "%XX" 的字节值；位置越界或非十六进制返回 null。 */
    private fun readPercentByte(text: String, index: Int): Int? {
        if (index + 2 >= text.length || text[index] != '%') return null
        val high = hexValue(text[index + 1])
        val low = hexValue(text[index + 2])
        if (high < 0 || low < 0) return null
        return (high shl 4) or low
    }

    /** 追加一个百分号编码的字节。 */
    private fun appendPercentByte(builder: StringBuilder, code: Int) {
        builder.append('%')
        builder.append(HEX_UPPER[code shr 4])
        builder.append(HEX_UPPER[code and 0x0F])
    }

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> -1
    }

    private fun uriError(message: String): Nothing = throw JSException(
        JSError("URIError", message)
    )

    private val HEX_UPPER = "0123456789ABCDEF".toCharArray()
}
