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
        registry.defineGlobal(
            name = "eval",
            value = BuiltinHelpers.method(intrinsics, "eval", length = 1) { interpreter, _, args ->
                val value = args.getOrElse(0) { JSUndefined }
                if (value is JSString) {
                    interpreter.evaluateIndirectEval(value.value)
                } else {
                    value
                }
            },
        )

        // parseInt / parseFloat
        registry.defineGlobal(
            name = "parseInt",
            value = BuiltinHelpers.method(intrinsics, "parseInt", length = 2) { interpreter, _, args ->
                NumberParsing.parseInt(interpreter, args)
            },
        )
        registry.defineGlobal(
            name = "parseFloat",
            value = BuiltinHelpers.method(intrinsics, "parseFloat", length = 1) { interpreter, _, args ->
                NumberParsing.parseFloat(interpreter, args)
            },
        )

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

    /** ECMAScript encodeURI/encodeURIComponent 的百分号编码。 */
    private fun encodeUri(text: String, extraUnescaped: String): String {
        val builder = StringBuilder(text.length)
        for (byte in text.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val character = code.toChar()
            if (code < 0x80 && (character in URI_UNRESERVED || character in extraUnescaped)) {
                builder.append(character)
            } else {
                builder.append('%')
                builder.append(HEX_UPPER[code shr 4])
                builder.append(HEX_UPPER[code and 0x0F])
            }
        }
        return builder.toString()
    }

    /** 解码百分号编码；非法序列抛 URIError。 */
    private fun decodeUri(text: String, preserveReserved: Boolean): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] != '%') {
                builder.append(text[index])
                index++
                continue
            }
            val bytes = mutableListOf<Byte>()
            while (index < text.length && text[index] == '%') {
                if (index + 2 >= text.length) throw uriError("URI malformed")
                val high = hexValue(text[index + 1])
                val low = hexValue(text[index + 2])
                if (high < 0 || low < 0) throw uriError("URI malformed")
                bytes.add(((high shl 4) or low).toByte())
                index += 3
            }
            val decoded = try {
                bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (_: Throwable) {
                throw uriError("URI malformed")
            }
            for (character in decoded) {
                if (preserveReserved && character in URI_RESERVED) {
                    // decodeURI 不解码保留字符，保持原始百分号编码
                    for (byte in character.toString().encodeToByteArray()) {
                        val code = byte.toInt() and 0xFF
                        builder.append('%')
                        builder.append(HEX_UPPER[code shr 4])
                        builder.append(HEX_UPPER[code and 0x0F])
                    }
                } else {
                    builder.append(character)
                }
            }
        }
        return builder.toString()
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
