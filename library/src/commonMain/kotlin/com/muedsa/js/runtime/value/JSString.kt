package com.muedsa.js.runtime.value

import com.muedsa.js.runtime.exception.JSException

data class JSString(val value: String) : JSObject(
    prototype = StringPrototype,
) {

    override val isPrimitive: Boolean = true

    override fun toPrimitiveBoolean() = value.isNotEmpty()

    override fun toPrimitiveNumber() = when {
        value.isEmpty() -> 0.0  // 空字符串转为 0
        value == "Infinity" -> Double.POSITIVE_INFINITY
        value == "-Infinity" -> Double.NEGATIVE_INFINITY
        value == "NaN" -> Double.NaN
        else -> value.toDoubleOrNull() ?: Double.NaN  // 无法转换则为 NaN
    }

    override fun toPrimitiveString() = value

    override fun getProperty(key: String): JSValue {
        // 特殊处理一下length
        if (key == "length") return JSNumber(value.length.toDouble())
        val index = key.toIntOrNull()
        if (index != null && index.toString() == key && index >= 0) {
            return if (index < value.length) {
                JSString(value.elementAt(index).toString())
            } else {
                JSUndefined
            }
        }
        return super.getProperty(key)
    }

    companion object {
        val EmptyString = JSString("")
    }
}

val StringPrototype = JSNativeFunction(
    name = "",
    lazyProperties = lazy {
        mutableMapOf(
            "length" to JSNumber(0.0),
            "at" to JSNativeFunction("String.prototype.at") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.at")
                val idx = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                thisString.value.elementAtOrNull(idx)?.let { JSString(it.toString()) } ?: JSUndefined
            },
            "charAt" to JSNativeFunction("String.prototype.charAt") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.charAt")
                val idx = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                thisString.value.elementAtOrNull(idx)?.let { JSString(it.toString()) } ?: JSUndefined
            },
            "charCodeAt" to JSNativeFunction("String.prototype.charCodeAt") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.charCodeAt")
                val idx = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                thisString.value.getOrNull(idx)?.let { JSNumber(it.code.toDouble()) } ?: JSNumber.NaN
            },
            "codePointAt" to JSNativeFunction("String.prototype.codePointAt") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.codePointAt")
                val idx = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                val str = thisString.value
                val size = str.length
                if (idx < 0 || idx >= size) {
                    return@JSNativeFunction JSUndefined
                }
                val first = str[idx]
                if (first.isHighSurrogate() && idx + 1 < size) {
                    val next = str[idx + 1]
                    if (next.isLowSurrogate()) {
                        val codePoint = (first.code - 0xD800) * 0x400 + (next.code - 0xDC00) + 0x10000
                        return@JSNativeFunction JSNumber(codePoint.toDouble())
                    }
                }
                JSNumber(first.code.toDouble())
            },
            "concat" to JSNativeFunction("String.prototype.concat") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.concat")
                JSString(args.joinToString("", thisString.value) { interpreter.getPrimitiveString(it) })
            },
            "endsWith" to JSNativeFunction("String.prototype.endsWith") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.endsWith")
                var source = thisString.value
                val searchString = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                val len = source.length
                val pos = args.getOrNull(1)?.toPrimitiveNumber()?.toInt() ?: len
                val endPosition = pos.coerceIn(0, len)
                source = source.slice(0 until endPosition)
                JSBoolean.getJsBoolean(source.endsWith(searchString))
            },
            "includes" to JSNativeFunction("String.prototype.includes") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.includes")
                val searchString = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                val position = args.getOrNull(1)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                JSBoolean.getJsBoolean(thisString.value.indexOf(searchString, position) != -1)
            },
            "indexOf" to JSNativeFunction("String.prototype.indexOf") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.indexOf")
                val searchString = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                val position = args.getOrNull(1)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                JSNumber(thisString.value.indexOf(searchString, position).toDouble())
            },
            "lastIndexOf" to JSNativeFunction("String.prototype.lastIndexOf") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.lastIndexOf")
                val searchString = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                val numPos = args.getOrNull(1)?.let { interpreter.getPrimitiveNumber(it) } ?: Double.NaN
                val position = if (numPos.isNaN()) thisString.value.length else numPos.toInt()
                JSNumber(thisString.value.lastIndexOf(searchString, position).toDouble())
            },
            "padEnd" to JSNativeFunction("String.prototype.padEnd") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.padEnd")
                val targetLength = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                val padString = args.getOrNull(1)?.let { interpreter.getPrimitiveString(it) } ?: " "
                JSString(thisString.value.padEnd(targetLength, padString[0])) // Kotlin's padEnd takes char, implementing simple version or need loop for string padding
                // Correct implementation for string padding
                if (thisString.value.length >= targetLength) {
                     thisString
                } else {
                    val padLen = targetLength - thisString.value.length
                    val repetitions = padLen / padString.length
                    val remaining = padLen % padString.length
                    JSString(thisString.value + padString.repeat(repetitions) + padString.substring(0, remaining))
                }
            },
            "padStart" to JSNativeFunction("String.prototype.padStart") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.padStart")
                val targetLength = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                val padString = args.getOrNull(1)?.let { interpreter.getPrimitiveString(it) } ?: " "
                
                if (thisString.value.length >= targetLength) {
                     thisString
                } else {
                    val padLen = targetLength - thisString.value.length
                    val repetitions = padLen / padString.length
                    val remaining = padLen % padString.length
                    JSString(padString.repeat(repetitions) + padString.substring(0, remaining) + thisString.value)
                }
            },
            "repeat" to JSNativeFunction("String.prototype.repeat") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.repeat")
                val count = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                if (count < 0) {
                     throw JSException(JSError("RangeError", "Invalid count value"))
                }
                if (count == Double.POSITIVE_INFINITY.toInt()) {
                    throw JSException(JSError("RangeError", "Invalid count value"))
                }
                JSString(thisString.value.repeat(count))
            },
            "replace" to JSNativeFunction("String.prototype.replace") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.replace")
                val searchValue = args.getOrNull(0) ?: JSUndefined
                val replaceValue = args.getOrNull(1) ?: JSUndefined
                
                // Simplified implementation: only string replacement
                val searchStr = interpreter.getPrimitiveString(searchValue)
                val replaceStr = interpreter.getPrimitiveString(replaceValue)
                
                JSString(thisString.value.replaceFirst(searchStr, replaceStr))
            },
            "replaceAll" to JSNativeFunction("String.prototype.replaceAll") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.replaceAll")
                val searchValue = args.getOrNull(0) ?: JSUndefined
                val replaceValue = args.getOrNull(1) ?: JSUndefined
                
                val searchStr = interpreter.getPrimitiveString(searchValue)
                val replaceStr = interpreter.getPrimitiveString(replaceValue)
                
                JSString(thisString.value.replace(searchStr, replaceStr))
            },
            "slice" to JSNativeFunction("String.prototype.slice") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.slice")
                val len = thisString.value.length
                var start = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                var end = args.getOrNull(1)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: len
                
                if (start < 0) start += len
                if (start < 0) start = 0
                if (start > len) start = len
                
                if (end < 0) end += len
                if (end < 0) end = 0
                if (end > len) end = len
                
                if (end < start) end = start
                
                JSString(thisString.value.substring(start, end))
            },
            "split" to JSNativeFunction("String.prototype.split") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.split")
                val separator = args.getOrNull(0)?.let { interpreter.getPrimitiveString(it) } ?: "undefined"
                val limit = args.getOrNull(1)?.let { interpreter.getPrimitiveNumber(it) }?.toInt()
                
                val resultList = thisString.value.split(separator)
                val finalResult = if (limit != null && limit >= 0) {
                    resultList.take(limit)
                } else {
                    resultList
                }
                
                JSArray(finalResult.map { JSString(it) }.toMutableList())
            },
            "startsWith" to JSNativeFunction("String.prototype.startsWith") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.startsWith")
                val searchString = interpreter.getPrimitiveString(args.getOrElse(0) { JSUndefined })
                val position = args.getOrNull(1)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                JSBoolean.getJsBoolean(thisString.value.startsWith(searchString, position.coerceAtLeast(0)))
            },
            "substring" to JSNativeFunction("String.prototype.substring") { interpreter, thisValue, args ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.substring")
                val len = thisString.value.length
                var start = args.getOrNull(0)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: 0
                var end = args.getOrNull(1)?.let { interpreter.getPrimitiveNumber(it) }?.toInt() ?: len
                
                if (start < 0) start = 0
                if (start > len) start = len
                if (end < 0) end = 0
                if (end > len) end = len
                
                if (start > end) {
                    val temp = start
                    start = end
                    end = temp
                }
                
                JSString(thisString.value.substring(start, end))
            },
            "toLowerCase" to JSNativeFunction("String.prototype.toLowerCase") { _, thisValue, _ ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.toLowerCase")
                JSString(thisString.value.lowercase())
            },
            "toUpperCase" to JSNativeFunction("String.prototype.toUpperCase") { _, thisValue, _ ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.toUpperCase")
                JSString(thisString.value.uppercase())
            },
            "trim" to JSNativeFunction("String.prototype.trim") { _, thisValue, _ ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.trim")
                JSString(thisString.value.trim())
            },
            "trimEnd" to JSNativeFunction("String.prototype.trimEnd") { _, thisValue, _ ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.trimEnd")
                JSString(thisString.value.trimEnd())
            },
            "trimStart" to JSNativeFunction("String.prototype.trimStart") { _, thisValue, _ ->
                val thisString = convertJSValueToJSString(thisValue, "String.prototype.trimStart")
                JSString(thisString.value.trimStart())
            },
            "toString" to JSNativeFunction("String.prototype.toString") { _, thisValue, _ ->
                convertJSValueToJSString(thisValue, "String.prototype.toString")
            },
            "[Symbol.toStringTag]" to JSString("String")
        )
    },
    prototype = ObjectPrototype,
)

val StringConstructor = JSNativeFunction(
    name = "String",
    lazyProperties = lazy {
        mutableMapOf(
            "fromCharCode" to JSNativeFunction("fromCharCode") { interpreter, _, args ->
                JSString(args.map { interpreter.getPrimitiveNumber(it).toInt().toChar() }.joinToString(""))
            },
            "fromCodePoint" to JSNativeFunction("fromCodePoint") { interpreter, _, args ->
                val sb = StringBuilder()
                for (arg in args) {
                    val codePoint = interpreter.getPrimitiveNumber(arg).toInt()
                    if (codePoint <= 0xFFFF) {
                        sb.append(codePoint.toChar())
                    } else {
                        val high = ((codePoint - 0x10000) ushr 10) + 0xD800
                        val low = ((codePoint - 0x10000) and 0x3FF) + 0xDC00
                        sb.append(high.toChar())
                        sb.append(low.toChar())
                    }
                }
                JSString(sb.toString())
            },
            "raw" to JSNativeFunction("raw") { _, _, args ->
                TODO()
            },
        )
    },
) { interpreter, _, args ->
    if (args.isEmpty()) JSString.EmptyString else JSString(interpreter.getPrimitiveString(args[0]))
}

fun convertJSValueToJSString(value: JSValue, calleeName: String): JSString {
    return value as? JSString
        ?: throw JSException(JSError("TypeError", "$calleeName requires that 'this' be a String"))
}
