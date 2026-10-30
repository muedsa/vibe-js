package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSRegExp
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * String 构造器与 String.prototype。
 *
 * 阶段 A 覆盖常用字符串方法，并修正旧实现中的边界问题：
 * - charAt 越界返回空串；
 * - at 支持负数索引；
 * - split() 无分隔符时返回原串；
 * - padStart/padEnd 支持多字符填充与空填充串；
 * - repeat 对负数/Infinity 抛 RangeError。
 */
object StringBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val stringPrototype = intrinsics.stringPrototype

        val stringConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "String",
            length = 1,
            prototypeObject = stringPrototype,
        ) { interpreter, _, args ->
            if (args.isEmpty()) {
                JSString.EMPTY
            } else if (args[0] is com.muedsa.js.runtime.value.JSSymbol) {
                // String(Symbol) 是规范允许的显式转换
                JSString(args[0].toPrimitiveString())
            } else {
                JSString(interpreter.toStringValue(args[0]))
            }
        }

        stringPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = stringConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        installPrototypeMethods(registry)
        installStaticMethods(registry, stringConstructor)

        registry.defineGlobal("String", stringConstructor)
    }

    private fun installPrototypeMethods(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val stringPrototype = intrinsics.stringPrototype

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                JSString(interpreter.toStringValue(thisValue))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("valueOf"),
            value = BuiltinHelpers.method(intrinsics, "valueOf", 0) { interpreter, thisValue, _ ->
                JSString(interpreter.toStringValue(thisValue))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("charAt"),
            value = BuiltinHelpers.method(intrinsics, "charAt", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val index = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                JSString(if (index in text.indices) text[index].toString() else "")
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("charCodeAt"),
            value = BuiltinHelpers.method(intrinsics, "charCodeAt", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val index = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (index in text.indices) JSNumber(text[index].code.toDouble()) else JSNumber.NAN
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("codePointAt"),
            value = BuiltinHelpers.method(intrinsics, "codePointAt", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val index = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (index !in text.indices) {
                    JSUndefined
                } else {
                    val first = text[index]
                    if (first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                        val codePoint = (first.code - 0xD800) * 0x400 + (text[index + 1].code - 0xDC00) + 0x10000
                        JSNumber(codePoint.toDouble())
                    } else {
                        JSNumber(first.code.toDouble())
                    }
                }
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("at"),
            value = BuiltinHelpers.method(intrinsics, "at", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                var index = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (index < 0) index += text.length
                if (index in text.indices) JSString(text[index].toString()) else JSUndefined
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("concat"),
            value = BuiltinHelpers.method(intrinsics, "concat", 1) { interpreter, thisValue, args ->
                val builder = StringBuilder(interpreter.toStringValue(thisValue))
                for (argument in args) {
                    builder.append(interpreter.toStringValue(argument))
                }
                JSString(builder.toString())
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("includes"),
            value = BuiltinHelpers.method(intrinsics, "includes", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val position = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: 0
                JSBoolean.getJsBoolean(text.indexOf(search, position.coerceIn(0, text.length)) != -1)
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("indexOf"),
            value = BuiltinHelpers.method(intrinsics, "indexOf", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val position = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: 0
                JSNumber(text.indexOf(search, position.coerceIn(0, text.length)).toDouble())
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("lastIndexOf"),
            value = BuiltinHelpers.method(intrinsics, "lastIndexOf", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val positionValue = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: Double.NaN
                val position = if (positionValue.isNaN()) {
                    text.length
                } else {
                    positionValue.toInt().coerceIn(0, text.length)
                }
                JSNumber(text.lastIndexOf(search, position).toDouble())
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("startsWith"),
            value = BuiltinHelpers.method(intrinsics, "startsWith", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val position = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: 0
                JSBoolean.getJsBoolean(text.startsWith(search, position.coerceIn(0, text.length)))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("endsWith"),
            value = BuiltinHelpers.method(intrinsics, "endsWith", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val endPosition = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: text.length
                JSBoolean.getJsBoolean(text.substring(0, endPosition.coerceIn(0, text.length)).endsWith(search))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("slice"),
            value = BuiltinHelpers.method(intrinsics, "slice", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val length = text.length
                var start = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                var end = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: length
                if (start < 0) start += length
                if (start < 0) start = 0
                if (start > length) start = length
                if (end < 0) end += length
                if (end < 0) end = 0
                if (end > length) end = length
                if (end < start) end = start
                JSString(text.substring(start, end))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("substring"),
            value = BuiltinHelpers.method(intrinsics, "substring", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val length = text.length
                var start = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                var end = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: length
                if (start < 0) start = 0
                if (start > length) start = length
                if (end < 0) end = 0
                if (end > length) end = length
                if (start > end) {
                    val temp = start
                    start = end
                    end = temp
                }
                JSString(text.substring(start, end))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("substr"),
            value = BuiltinHelpers.method(intrinsics, "substr", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val size = text.length
                var start = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (start < 0) start = (size + start).coerceAtLeast(0)
                if (start > size) start = size
                val lengthArg = args.getOrNull(1)
                val requestedLength = if (lengthArg == null || lengthArg is JSUndefined) {
                    size - start
                } else {
                    interpreter.toNumber(lengthArg).toInt()
                }
                val actualLength = requestedLength.coerceIn(0, size - start)
                JSString(text.substring(start, start + actualLength))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("split"),
            value = BuiltinHelpers.method(intrinsics, "split", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val separatorValue = args.getOrNull(0)
                val limit = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: Int.MAX_VALUE

                if (limit == 0) {
                    JSArray(prototype = intrinsics.arrayPrototype)
                } else if (separatorValue == null || separatorValue is JSUndefined) {
                    JSArray(mutableListOf<JSValue?>(JSString(text)), prototype = intrinsics.arrayPrototype)
                } else if (separatorValue is JSRegExp) {
                    JSArray(
                        splitWithRegExp(text, separatorValue.regex, limit),
                        prototype = intrinsics.arrayPrototype,
                    )
                } else {
                    val separator = interpreter.toStringValue(separatorValue)
                    val parts = if (separator.isEmpty()) {
                        text.map { it.toString() }
                    } else {
                        text.split(separator)
                    }
                    val limited = if (limit >= 0) parts.take(limit) else parts
                    JSArray(
                        limited.map { JSString(it) as JSValue? }.toMutableList(),
                        prototype = intrinsics.arrayPrototype,
                    )
                }
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("trim"),
            value = BuiltinHelpers.method(intrinsics, "trim", 0) { interpreter, thisValue, _ ->
                JSString(interpreter.toStringValue(thisValue).trim())
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("trimStart"),
            value = BuiltinHelpers.method(intrinsics, "trimStart", 0) { interpreter, thisValue, _ ->
                JSString(interpreter.toStringValue(thisValue).trimStart())
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("trimEnd"),
            value = BuiltinHelpers.method(intrinsics, "trimEnd", 0) { interpreter, thisValue, _ ->
                JSString(interpreter.toStringValue(thisValue).trimEnd())
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("toLowerCase"),
            value = BuiltinHelpers.method(intrinsics, "toLowerCase", 0) { interpreter, thisValue, _ ->
                JSString(interpreter.toStringValue(thisValue).lowercase())
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("toUpperCase"),
            value = BuiltinHelpers.method(intrinsics, "toUpperCase", 0) { interpreter, thisValue, _ ->
                JSString(interpreter.toStringValue(thisValue).uppercase())
            },
        )

        stringPrototype.getOwnDataValue(PropertyKey.Str("toLowerCase"))?.let { lower ->
            stringPrototype.defineDataProperty(
                key = PropertyKey.Str("toLocaleLowerCase"),
                value = lower,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
        stringPrototype.getOwnDataValue(PropertyKey.Str("toUpperCase"))?.let { upper ->
            stringPrototype.defineDataProperty(
                key = PropertyKey.Str("toLocaleUpperCase"),
                value = upper,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("repeat"),
            value = BuiltinHelpers.method(intrinsics, "repeat", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val count = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                if (count < 0 || count == Int.MAX_VALUE) {
                    interpreter.rangeError("Invalid count value")
                }
                JSString(text.repeat(count))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("match"),
            value = BuiltinHelpers.method(intrinsics, "match", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val regexpValue = args.getOrNull(0)
                val regexp = when (regexpValue) {
                    is JSRegExp -> regexpValue
                    else -> interpreter.createRegExp(
                        if (regexpValue == null || regexpValue is JSUndefined) "" else interpreter.toStringValue(regexpValue),
                        "",
                    ) as JSRegExp
                }

                if (regexp.global) {
                    regexp.setLastIndex(0)
                    val values = ArrayList<JSValue?>()
                    for (match in regexp.regex.findAll(text)) {
                        values.add(JSString(match.value))
                    }
                    if (values.isEmpty()) JSNull else JSArray(values, prototype = intrinsics.arrayPrototype)
                } else {
                    val match = regexp.regex.find(text)
                    if (match == null) JSNull else matchToArray(interpreter, match, text)
                }
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("search"),
            value = BuiltinHelpers.method(intrinsics, "search", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val regexpValue = args.getOrNull(0)
                val regexp = when (regexpValue) {
                    is JSRegExp -> regexpValue
                    else -> interpreter.createRegExp(
                        if (regexpValue == null || regexpValue is JSUndefined) "" else interpreter.toStringValue(regexpValue),
                        "",
                    ) as JSRegExp
                }
                val match = regexp.regex.find(text)
                JSNumber(match?.range?.first?.toDouble() ?: -1.0)
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("replace"),
            value = BuiltinHelpers.method(intrinsics, "replace", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val searchValue = args.getOrNull(0) ?: JSUndefined
                val replacement = args.getOrNull(1) ?: JSUndefined

                if (searchValue is JSRegExp) {
                    JSString(
                        replaceWithRegExp(
                            interpreter,
                            text,
                            searchValue,
                            replacement,
                            global = searchValue.global,
                        )
                    )
                } else {
                    val search = interpreter.toStringValue(searchValue)
                    val index = text.indexOf(search)
                    when {
                        index < 0 -> JSString(text)
                        interpreter.isCallable(replacement) -> {
                            val replacementValue = interpreter.callFunction(
                                replacement,
                                JSUndefined,
                                listOf(JSString(search), JSNumber(index.toDouble()), JSString(text)),
                            )
                            JSString(
                                text.substring(0, index) +
                                    interpreter.toStringValue(replacementValue) +
                                    text.substring(index + search.length)
                            )
                        }

                        else -> {
                            val replacementText = interpreter.toStringValue(replacement)
                            JSString(
                                text.substring(0, index) +
                                    expandStringReplacement(replacementText, search, index, text) +
                                    text.substring(index + search.length)
                            )
                        }
                    }
                }
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("replaceAll"),
            value = BuiltinHelpers.method(intrinsics, "replaceAll", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val searchValue = args.getOrNull(0) ?: JSUndefined
                val replacement = args.getOrNull(1) ?: JSUndefined

                if (searchValue is JSRegExp) {
                    if (!searchValue.global) {
                        interpreter.typeError("String.prototype.replaceAll called with a non-global RegExp argument")
                    }
                    JSString(replaceWithRegExp(interpreter, text, searchValue, replacement, global = true))
                } else {
                    val search = interpreter.toStringValue(searchValue)
                    when {
                        search.isEmpty() -> {
                            val builder = StringBuilder()
                            for (position in 0..text.length) {
                                val replacementValue = if (interpreter.isCallable(replacement)) {
                                    interpreter.callFunction(
                                        replacement,
                                        JSUndefined,
                                        listOf(JSString(""), JSNumber(position.toDouble()), JSString(text)),
                                    )
                                } else {
                                    JSString(
                                        expandStringReplacement(
                                            interpreter.toStringValue(replacement),
                                            "",
                                            position,
                                            text,
                                        )
                                    )
                                }
                                builder.append(interpreter.toStringValue(replacementValue))
                                if (position < text.length) builder.append(text[position])
                            }
                            JSString(builder.toString())
                        }

                        interpreter.isCallable(replacement) -> {
                            val builder = StringBuilder()
                            var cursor = 0
                            while (true) {
                                val index = text.indexOf(search, cursor)
                                if (index < 0) {
                                    builder.append(text.substring(cursor))
                                    break
                                }
                                builder.append(text.substring(cursor, index))
                                val replacementValue = interpreter.callFunction(
                                    replacement,
                                    JSUndefined,
                                    listOf(JSString(search), JSNumber(index.toDouble()), JSString(text)),
                                )
                                builder.append(interpreter.toStringValue(replacementValue))
                                cursor = index + search.length
                            }
                            JSString(builder.toString())
                        }

                        else -> {
                            val builder = StringBuilder()
                            val replacementText = interpreter.toStringValue(replacement)
                            var cursor = 0
                            while (true) {
                                val index = text.indexOf(search, cursor)
                                if (index < 0) {
                                    builder.append(text.substring(cursor))
                                    break
                                }
                                builder.append(text.substring(cursor, index))
                                builder.append(expandStringReplacement(replacementText, search, index, text))
                                cursor = index + search.length
                            }
                            JSString(builder.toString())
                        }
                    }
                }
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("padStart"),
            value = BuiltinHelpers.method(intrinsics, "padStart", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val targetLength = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                val padding = args.getOrNull(1)?.let { interpreter.toStringValue(it) } ?: " "
                JSString(pad(text, targetLength, padding, start = true))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("padEnd"),
            value = BuiltinHelpers.method(intrinsics, "padEnd", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val targetLength = args.getOrNull(0)?.let { interpreter.toNumber(it).toInt() } ?: 0
                val padding = args.getOrNull(1)?.let { interpreter.toStringValue(it) } ?: " "
                JSString(pad(text, targetLength, padding, start = false))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("localeCompare"),
            value = BuiltinHelpers.method(intrinsics, "localeCompare", 1) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val other = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSNumber(text.compareTo(other).toDouble())
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("String"),
            writable = false,
            enumerable = false,
            configurable = true,
        )
        stringPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolIterator),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.iterator]", 0) { interpreter, thisValue, _ ->
                val text = interpreter.toStringValue(thisValue)
                IteratorBuiltins.createIterator(
                    intrinsics,
                    text.map { JSString(it.toString()) },
                    "String Iterator",
                )
            },
        )
    }

    private fun installStaticMethods(registry: BuiltinRegistry, stringConstructor: com.muedsa.js.runtime.value.JSNativeFunction) {
        val intrinsics = registry.intrinsics

        stringConstructor.defineDataProperty(
            key = PropertyKey.Str("fromCharCode"),
            value = BuiltinHelpers.method(intrinsics, "fromCharCode", 1) { interpreter, _, args ->
                val builder = StringBuilder(args.size)
                for (argument in args) {
                    builder.append(interpreter.toNumber(argument).toInt().toChar())
                }
                JSString(builder.toString())
            },
        )

        stringConstructor.defineDataProperty(
            key = PropertyKey.Str("fromCodePoint"),
            value = BuiltinHelpers.method(intrinsics, "fromCodePoint", 1) { interpreter, _, args ->
                val builder = StringBuilder()
                for (argument in args) {
                    val codePoint = interpreter.toNumber(argument).toInt()
                    if (codePoint < 0 || codePoint > 0x10FFFF) {
                        interpreter.rangeError("Invalid code point $codePoint")
                    }
                    if (codePoint <= 0xFFFF) {
                        builder.append(codePoint.toChar())
                    } else {
                        val value = codePoint - 0x10000
                        builder.append(((value shr 10) + 0xD800).toChar())
                        builder.append(((value and 0x3FF) + 0xDC00).toChar())
                    }
                }
                JSString(builder.toString())
            },
        )

        stringConstructor.defineDataProperty(
            key = PropertyKey.Str("raw"),
            value = BuiltinHelpers.method(intrinsics, "raw", 1) { interpreter, _, args ->
                val template = interpreter.toObject(args.getOrElse(0) { JSUndefined })
                val raw = interpreter.getV(template, PropertyKey.Str("raw"))
                val segments = interpreter.iterateValues(raw)
                val builder = StringBuilder()
                for (index in segments.indices) {
                    builder.append(interpreter.toStringValue(segments[index]))
                    if (index + 1 < segments.size && index + 1 < args.size) {
                        builder.append(interpreter.toStringValue(args[index + 1]))
                    }
                }
                JSString(builder.toString())
            },
        )
    }

    /**
     * 按正则分割字符串，遵循 String.prototype.split 的语义：
     * - 结果包含捕获组；
     * - 分隔符命中结尾时保留结尾空串；
     * - 零长度分隔符在字符之间分割。
     */
    private fun splitWithRegExp(text: String, regex: Regex, limit: Int): MutableList<JSValue?> {
        val result = ArrayList<JSValue?>()
        if (limit == 0) return result
        if (text.isEmpty()) {
            // 空串匹配零长度分隔符时结果为 []，否则为 [""]
            val emptyMatch = regex.find(text)
            if (emptyMatch == null) result.add(JSString(""))
            return result
        }
        var previousEnd = 0
        var searchIndex = 0
        var lastZeroLengthAtEnd = false
        while (searchIndex < text.length) {
            val match = regex.find(text, searchIndex) ?: break
            val matchEnd = match.range.last + 1
            if (matchEnd == previousEnd) {
                // 上一位置已产生零长度匹配，前进一个字符避免死循环
                searchIndex = previousEnd + 1
                continue
            }
            result.add(JSString(text.substring(previousEnd, match.range.first)))
            for (groupIndex in 1 until match.groups.size) {
                val group = match.groups[groupIndex]
                result.add(if (group == null) JSUndefined else JSString(group.value))
            }
            if (result.size >= limit) return ArrayList(result.take(limit))
            lastZeroLengthAtEnd = match.range.isEmpty() && match.range.first == text.length
            previousEnd = matchEnd
            searchIndex = matchEnd
        }
        result.add(JSString(text.substring(previousEnd)))
        // 结尾的零长度匹配不应再产生额外的空串
        if (lastZeroLengthAtEnd && result.size > 1 &&
            (result.last() as? JSString)?.value == ""
        ) {
            result.removeAt(result.lastIndex)
        }
        return if (result.size > limit) ArrayList(result.take(limit)) else result
    }

    /** 字符串填充（支持多字符 padding 与空 padding）。 */
    private fun pad(text: String, targetLength: Int, padding: String, start: Boolean): String {
        if (targetLength <= text.length || padding.isEmpty()) return text
        val padLength = targetLength - text.length
        val builder = StringBuilder(padLength)
        while (builder.length < padLength) {
            builder.append(padding)
        }
        val paddingText = builder.substring(0, padLength)
        return if (start) paddingText + text else text + paddingText
    }

    /** 正则匹配结果 -> JS 数组（含 index/input）。 */
    private fun matchToArray(interpreter: Interpreter, match: MatchResult, input: String): JSArray {
        val values = ArrayList<JSValue?>(match.groupValues.size)
        values.add(JSString(match.value))
        for (index in 1 until match.groupValues.size) {
            val group = match.groups[index]
            values.add(if (group == null) JSUndefined else JSString(group.value))
        }
        val array = JSArray(values, prototype = interpreter.intrinsics.arrayPrototype)
        array.defineDataProperty(
            key = PropertyKey.Str("index"),
            value = JSNumber(match.range.first.toDouble()),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        array.defineDataProperty(
            key = PropertyKey.Str("input"),
            value = JSString(input),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        return array
    }

    /**
     * 使用正则替换（支持全局与函数替换）。
     */
    private suspend fun replaceWithRegExp(
        interpreter: Interpreter,
        text: String,
        regexp: JSRegExp,
        replacement: JSValue,
        global: Boolean,
    ): String {
        val matches = if (global) {
            regexp.regex.findAll(text).toList()
        } else {
            listOfNotNull(regexp.regex.find(text))
        }
        if (matches.isEmpty()) return text
        if (global) regexp.setLastIndex(0)

        val builder = StringBuilder()
        var cursor = 0
        for (match in matches) {
            val start = match.range.first.coerceAtLeast(cursor)
            builder.append(text, cursor, start)
            builder.append(
                if (interpreter.isCallable(replacement)) {
                    val arguments = ArrayList<JSValue>()
                    arguments.add(JSString(match.value))
                    for (index in 1 until match.groupValues.size) {
                        val group = match.groups[index]
                        arguments.add(if (group == null) JSUndefined else JSString(group.value))
                    }
                    arguments.add(JSNumber(match.range.first.toDouble()))
                    arguments.add(JSString(text))
                    interpreter.toStringValue(
                        interpreter.callFunction(replacement, JSUndefined, arguments)
                    )
                } else {
                    expandReplacement(interpreter.toStringValue(replacement), match, text)
                }
            )
            cursor = if (match.value.isEmpty()) {
                match.range.first
            } else {
                match.range.last + 1
            }
            cursor = cursor.coerceAtLeast(start)
        }
        builder.append(text.substring(cursor.coerceAtMost(text.length)))
        return builder.toString()
    }

    /**
     * 展开字符串查找路径下的替换模板：`$$`、`$&`、``$` ``、`$'`。
     *
     * 字符串查找没有捕获组，`$1` 等保持字面量。
     */
    private fun expandStringReplacement(
        template: String,
        matched: String,
        position: Int,
        input: String,
    ): String {
        val builder = StringBuilder()
        var index = 0
        while (index < template.length) {
            val c = template[index]
            if (c != '$' || index + 1 >= template.length) {
                builder.append(c)
                index++
                continue
            }
            when (template[index + 1]) {
                '$' -> {
                    builder.append('$')
                    index += 2
                }

                '&' -> {
                    builder.append(matched)
                    index += 2
                }

                '`' -> {
                    builder.append(input.substring(0, position))
                    index += 2
                }

                '\'' -> {
                    builder.append(input.substring((position + matched.length).coerceAtMost(input.length)))
                    index += 2
                }

                else -> {
                    builder.append('$')
                    index++
                }
            }
        }
        return builder.toString()
    }

    /**
     * 展开替换字符串中的 `$$`、`$&`、``$` ``、`$'`、`$1..$99`。
     */
    private fun expandReplacement(template: String, match: MatchResult, input: String): String {
        val builder = StringBuilder()
        var index = 0
        while (index < template.length) {
            val c = template[index]
            if (c != '$' || index + 1 >= template.length) {
                builder.append(c)
                index++
                continue
            }
            val next = template[index + 1]
            when (next) {
                '$' -> {
                    builder.append('$')
                    index += 2
                }

                '&' -> {
                    builder.append(match.value)
                    index += 2
                }

                '`' -> {
                    builder.append(input.substring(0, match.range.first))
                    index += 2
                }

                '\'' -> {
                    builder.append(input.substring((match.range.last + 1).coerceAtMost(input.length)))
                    index += 2
                }

                in '0'..'9' -> {
                    var group = next - '0'
                    var consumed = 2
                    if (index + 2 < template.length && template[index + 2] in '0'..'9') {
                        val twoDigit = group * 10 + (template[index + 2] - '0')
                        if (twoDigit <= match.groupValues.lastIndex) {
                            group = twoDigit
                            consumed = 3
                        }
                    }
                    if (group <= match.groupValues.lastIndex) {
                        builder.append(match.groupValues[group])
                    } else {
                        builder.append('$').append(next)
                    }
                    index += consumed
                }

                else -> {
                    builder.append('$')
                    index++
                }
            }
        }
        return builder.toString()
    }
}
