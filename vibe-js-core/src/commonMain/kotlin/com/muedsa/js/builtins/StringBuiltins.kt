package com.muedsa.js.builtins

import com.muedsa.js.runtime.regex.JsMatch
import com.muedsa.js.runtime.regex.JsRegex
import com.muedsa.js.runtime.value.JSBoxedPrimitive

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
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
            val firstArgument = args.getOrNull(0)
            // new String(Symbol) 非法；String(Symbol) 显式转换允许
            if (interpreter.nativeConstructCall &&
                firstArgument is com.muedsa.js.runtime.value.JSSymbol
            ) {
                interpreter.typeError("Cannot convert a Symbol value to a string")
            }
            when {
                args.isEmpty() -> JSString.EMPTY
                firstArgument is com.muedsa.js.runtime.value.JSSymbol ->
                    JSString(firstArgument.toPrimitiveString())

                else -> JSString(interpreter.toStringValue(firstArgument ?: com.muedsa.js.runtime.value.JSUndefined))
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
                stringReceiver(interpreter, thisValue, "String.prototype.toString")
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("valueOf"),
            value = BuiltinHelpers.method(intrinsics, "valueOf", 0) { interpreter, thisValue, _ ->
                stringReceiver(interpreter, thisValue, "String.prototype.valueOf")
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
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.at called on null or undefined")
                }
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
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.includes called on null or undefined")
                }
                val searchValue = args.getOrElse(0) { JSUndefined }
                if (interpreter.isRegExp(searchValue)) {
                    interpreter.typeError("First argument must not be a regular expression")
                }
                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(searchValue)
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
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.startsWith called on null or undefined")
                }
                val searchValue = args.getOrElse(0) { JSUndefined }
                if (interpreter.isRegExp(searchValue)) {
                    interpreter.typeError("First argument must not be a regular expression")
                }
                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(searchValue)
                val position = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: 0
                JSBoolean.getJsBoolean(text.startsWith(search, position.coerceIn(0, text.length)))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("endsWith"),
            value = BuiltinHelpers.method(intrinsics, "endsWith", 1) { interpreter, thisValue, args ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.endsWith called on null or undefined")
                }
                val searchValue = args.getOrElse(0) { JSUndefined }
                if (interpreter.isRegExp(searchValue)) {
                    interpreter.typeError("First argument must not be a regular expression")
                }
                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(searchValue)
                val endPosition = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: text.length
                JSBoolean.getJsBoolean(text.substring(0, endPosition.coerceIn(0, text.length)).endsWith(search))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("slice"),
            value = BuiltinHelpers.method(intrinsics, "slice", 2) { interpreter, thisValue, args ->
                val text = interpreter.toStringValue(thisValue)
                val length = text.length
                var start = args.getOrNull(0)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toNumber(it).toInt() } ?: 0
                var end = args.getOrNull(1)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toNumber(it).toInt() } ?: length
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
                var start = args.getOrNull(0)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toNumber(it).toInt() } ?: 0
                var end = args.getOrNull(1)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toNumber(it).toInt() } ?: length
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
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.split called on null or undefined")
                }
                val separatorValue = args.getOrNull(0)
                // 规范：仅对象才读取 @@split，未命中才 ToString(receiver)
                if (separatorValue is JSObject) {
                    val splitter = interpreter.getV(
                        separatorValue,
                        PropertyKey.Sym(intrinsics.symbolSplit),
                    )
                    if (splitter !is JSUndefined && splitter !is JSNull) {
                        if (!interpreter.isCallable(splitter)) {
                            interpreter.typeError("Symbol.split is not a function")
                        }
                        // 规范：@@split 接收原始 O（由 split 内部再 ToString）
                        return@method interpreter.callFunction(
                            splitter,
                            separatorValue,
                            listOf(thisValue, args.getOrNull(1) ?: JSUndefined),
                        )
                    }
                }
                val text = interpreter.toStringValue(thisValue)
                // 规范：limit 使用 ToUint32
                val limit = args.getOrNull(1)
                    ?.takeUnless { it is JSUndefined }
                    ?.let { interpreter.toUint32(it) }
                    ?: 4294967295L

                if (limit == 0L) {
                    JSArray(prototype = intrinsics.arrayPrototype)
                } else if (separatorValue == null || separatorValue is JSUndefined) {
                    JSArray(mutableListOf<JSValue?>(JSString(text)), prototype = intrinsics.arrayPrototype)
                } else if (separatorValue is JSRegExp) {
                    JSArray(
                        splitWithRegExp(
                            text,
                            separatorValue.matcher,
                            limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        ),
                        prototype = intrinsics.arrayPrototype,
                    )
                } else {
                    val separator = interpreter.toStringValue(separatorValue)
                    val parts = if (separator.isEmpty()) {
                        text.map { it.toString() }
                    } else {
                        text.split(separator)
                    }
                    val limited = parts.take(limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.trim called on null or undefined")
                }
                JSString(interpreter.toStringValue(thisValue).trim { isJsWhitespace(it) })
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("trimStart"),
            value = BuiltinHelpers.method(intrinsics, "trimStart", 0) { interpreter, thisValue, _ ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.trimStart called on null or undefined")
                }
                JSString(interpreter.toStringValue(thisValue).trimStart { isJsWhitespace(it) })
            },
        )

        // String.prototype.isWellFormed：检测孤立代理项
        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("isWellFormed"),
            value = BuiltinHelpers.method(intrinsics, "isWellFormed", 0) { interpreter, thisValue, _ ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.isWellFormed called on null or undefined")
                }
                JSBoolean.getJsBoolean(isWellFormedString(interpreter.toStringValue(thisValue)))
            },
        )

        // String.prototype.toWellFormed：孤立代理项替换为 U+FFFD
        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("toWellFormed"),
            value = BuiltinHelpers.method(intrinsics, "toWellFormed", 0) { interpreter, thisValue, _ ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.toWellFormed called on null or undefined")
                }
                JSString(toWellFormedString(interpreter.toStringValue(thisValue)))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("trimEnd"),
            value = BuiltinHelpers.method(intrinsics, "trimEnd", 0) { interpreter, thisValue, _ ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.trimEnd called on null or undefined")
                }
                JSString(interpreter.toStringValue(thisValue).trimEnd { isJsWhitespace(it) })
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("toLowerCase"),
            value = BuiltinHelpers.method(intrinsics, "toLowerCase", 0) { interpreter, thisValue, _ ->
                JSString(jsLowercase(interpreter.toStringValue(thisValue)))
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
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.match called on null or undefined")
                }
                val regexpValue = args.getOrNull(0)
                // 规范：仅对象才读取 @@match，未命中才 ToString(receiver)
                if (regexpValue is JSObject) {
                    val matcher = interpreter.getV(
                        regexpValue,
                        PropertyKey.Sym(intrinsics.symbolMatch),
                    )
                    if (matcher !is JSUndefined && matcher !is JSNull) {
                        if (!interpreter.isCallable(matcher)) {
                            interpreter.typeError("Symbol.match is not a function")
                        }
                        return@method interpreter.callFunction(
                            matcher,
                            regexpValue,
                            listOf(JSString(interpreter.toStringValue(thisValue))),
                        )
                    }
                }
                val text = interpreter.toStringValue(thisValue)
                // 回退：RegExpCreate 后调用其 @@match（允许原型方法被覆盖）
                val regexp = when (regexpValue) {
                    is JSUndefined, null -> interpreter.createRegExp("", "") as JSRegExp
                    is JSRegExp -> interpreter.createRegExp(regexpValue.pattern, regexpValue.flags) as JSRegExp
                    else -> interpreter.createRegExp(interpreter.toStringValue(regexpValue), "") as JSRegExp
                }
                val builtinMatcher = interpreter.getV(
                    regexp,
                    PropertyKey.Sym(intrinsics.symbolMatch),
                )
                if (!interpreter.isCallable(builtinMatcher)) {
                    interpreter.typeError("Symbol.match is not a function")
                }
                interpreter.callFunction(builtinMatcher, regexp, listOf(JSString(text)))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("matchAll"),
            value = BuiltinHelpers.method(intrinsics, "matchAll", 1) { interpreter, thisValue, args ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.matchAll called on null or undefined")
                }
                val regexpValue = args.getOrNull(0)
                // 规范：IsRegExp 为真时先校验 flags 必须包含 g（早于 @@matchAll 查找）
                val regexpIsRegExp = interpreter.isRegExp(regexpValue ?: JSUndefined)
                if (regexpIsRegExp) {
                    val flagsValue = interpreter.getV(
                        regexpValue as JSObject,
                        PropertyKey.Str("flags"),
                    )
                    if (flagsValue is JSUndefined || flagsValue is JSNull) {
                        interpreter.typeError("flags is not coercible")
                    }
                    if (!interpreter.toStringValue(flagsValue).contains('g')) {
                        interpreter.typeError(
                            "String.prototype.matchAll called with a non-global RegExp argument"
                        )
                    }
                }
                // 仅对象读取 @@matchAll，命中时直接以原始 O 调用
                if (regexpValue is JSObject) {
                    val matcher = interpreter.getV(
                        regexpValue,
                        PropertyKey.Sym(intrinsics.symbolMatchAll),
                    )
                    if (matcher !is JSUndefined && matcher !is JSNull) {
                        if (!interpreter.isCallable(matcher)) {
                            interpreter.typeError("Symbol.matchAll is not a function")
                        }
                        return@method interpreter.callFunction(
                            matcher,
                            regexpValue,
                            listOf(thisValue),
                        )
                    }
                }
                val text = interpreter.toStringValue(thisValue)
                // 非正则按 RegExpCreate(regexp, "g")
                val regexp = interpreter.construct(
                    intrinsics.regexpConstructor,
                    listOf(
                        regexpValue ?: JSUndefined,
                        if (regexpIsRegExp) JSUndefined else JSString("g"),
                    ),
                ) as? JSObject
                    ?: interpreter.typeError("RegExp constructor did not return an object")
                val matcher = interpreter.getV(
                    regexp,
                    PropertyKey.Sym(intrinsics.symbolMatchAll),
                )
                if (!interpreter.isCallable(matcher)) {
                    interpreter.typeError("Symbol.matchAll is not a function")
                }
                interpreter.callFunction(matcher, regexp, listOf(JSString(text)))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("search"),
            value = BuiltinHelpers.method(intrinsics, "search", 1) { interpreter, thisValue, args ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.search called on null or undefined")
                }
                val regexpValue = args.getOrNull(0)
                // 规范：仅对象才读取 @@search，未命中才 ToString(receiver)
                if (regexpValue is JSObject) {
                    val searcher = interpreter.getV(
                        regexpValue,
                        PropertyKey.Sym(intrinsics.symbolSearch),
                    )
                    if (searcher !is JSUndefined && searcher !is JSNull) {
                        if (!interpreter.isCallable(searcher)) {
                            interpreter.typeError("Symbol.search is not a function")
                        }
                        return@method interpreter.callFunction(
                            searcher,
                            regexpValue,
                            listOf(JSString(interpreter.toStringValue(thisValue))),
                        )
                    }
                }
                val text = interpreter.toStringValue(thisValue)
                // 回退：RegExpCreate 后调用其 @@search（允许原型方法被覆盖）
                val regexp = when (regexpValue) {
                    is JSUndefined, null -> interpreter.createRegExp("", "") as JSRegExp
                    is JSRegExp -> interpreter.createRegExp(regexpValue.pattern, regexpValue.flags) as JSRegExp
                    else -> interpreter.createRegExp(interpreter.toStringValue(regexpValue), "") as JSRegExp
                }
                val builtinSearcher = interpreter.getV(
                    regexp,
                    PropertyKey.Sym(intrinsics.symbolSearch),
                )
                if (!interpreter.isCallable(builtinSearcher)) {
                    interpreter.typeError("Symbol.search is not a function")
                }
                interpreter.callFunction(builtinSearcher, regexp, listOf(JSString(text)))
            },
        )

        stringPrototype.defineDataProperty(
            key = PropertyKey.Str("replace"),
            value = BuiltinHelpers.method(intrinsics, "replace", 2) { interpreter, thisValue, args ->
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.replace called on null or undefined")
                }
                val searchValue = args.getOrNull(0) ?: JSUndefined
                val replacement = args.getOrNull(1) ?: JSUndefined
                // 规范：仅对象才读取 @@replace，未命中才 ToString(receiver)
                if (searchValue is JSObject) {
                    val replacer = interpreter.getV(
                        searchValue,
                        PropertyKey.Sym(intrinsics.symbolReplace),
                    )
                    if (replacer !is JSUndefined && replacer !is JSNull) {
                        if (!interpreter.isCallable(replacer)) {
                            interpreter.typeError("Symbol.replace is not a function")
                        }
                        return@method interpreter.callFunction(
                            replacer,
                            searchValue,
                            listOf(
                                JSString(interpreter.toStringValue(thisValue)),
                                replacement,
                            ),
                        )
                    }
                }
                val text = interpreter.toStringValue(thisValue)

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
                if (thisValue is JSNull || thisValue is JSUndefined) {
                    interpreter.typeError("String.prototype.replaceAll called on null or undefined")
                }
                val searchValue = args.getOrNull(0) ?: JSUndefined
                val replaceValue = args.getOrNull(1) ?: JSUndefined

                // 规范顺序：IsRegExp → flags 全局校验 → @@replace 查找
                if (searchValue is JSObject && interpreter.isRegExp(searchValue)) {
                    val flagsValue = interpreter.getV(searchValue, PropertyKey.Str("flags"))
                    if (flagsValue is JSUndefined || flagsValue is JSNull) {
                        interpreter.typeError("flags is not coercible")
                    }
                    if (!interpreter.toStringValue(flagsValue).contains('g')) {
                        interpreter.typeError(
                            "String.prototype.replaceAll called with a non-global RegExp argument"
                        )
                    }
                }
                if (searchValue is JSObject) {
                    val replacer = interpreter.getV(
                        searchValue,
                        PropertyKey.Sym(intrinsics.symbolReplace),
                    )
                    if (replacer !is JSUndefined && replacer !is JSNull) {
                        if (!interpreter.isCallable(replacer)) {
                            interpreter.typeError("Symbol.replace is not a function")
                        }
                        return@method interpreter.callFunction(
                            replacer,
                            searchValue,
                            listOf(thisValue, replaceValue),
                        )
                    }
                }

                val text = interpreter.toStringValue(thisValue)
                val search = interpreter.toStringValue(searchValue)
                val functionalReplace = interpreter.isCallable(replaceValue)
                val replacementText = if (functionalReplace) {
                    ""
                } else {
                    interpreter.toStringValue(replaceValue)
                }

                // 收集互不重叠的匹配位置（空字符串在每个位置都匹配）
                val positions = ArrayList<Int>()
                if (search.isEmpty()) {
                    for (position in 0..text.length) positions.add(position)
                } else {
                    var cursor = 0
                    while (true) {
                        val index = text.indexOf(search, cursor)
                        if (index < 0) break
                        positions.add(index)
                        cursor = index + search.length
                    }
                }

                val builder = StringBuilder()
                var endOfLastMatch = 0
                for (position in positions) {
                    if (position < endOfLastMatch) continue
                    builder.append(text, endOfLastMatch, position)
                    val replacementValue = if (functionalReplace) {
                        interpreter.callFunction(
                            replaceValue,
                            JSUndefined,
                            listOf(
                                JSString(search),
                                JSNumber(position.toDouble()),
                                JSString(text),
                            ),
                        )
                    } else {
                        JSString(
                            expandReplaceAllSubstitution(replacementText, search, position, text)
                        )
                    }
                    builder.append(interpreter.toStringValue(replacementValue))
                    endOfLastMatch = position + search.length
                }
                builder.append(text, endOfLastMatch.coerceAtMost(text.length), text.length)
                JSString(builder.toString())
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
            key = PropertyKey.Sym(intrinsics.symbolIterator),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.iterator]", 0) { interpreter, thisValue, _ ->
                if (thisValue is com.muedsa.js.runtime.value.JSNull ||
                    thisValue is com.muedsa.js.runtime.value.JSUndefined
                ) {
                    interpreter.typeError("String.prototype[Symbol.iterator] called on null or undefined")
                }
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
                    val number = interpreter.toNumber(argument)
                    // ToUint16：NaN/Infinity → 0，其余按 2^16 取模
                    val code = if (!number.isFinite()) 0 else {
                        val modulo = number.toLong() % 65536
                        (if (modulo < 0) modulo + 65536 else modulo).toInt()
                    }
                    builder.append(code.toChar())
                }
                JSString(builder.toString())
            },
        )

        stringConstructor.defineDataProperty(
            key = PropertyKey.Str("fromCodePoint"),
            value = BuiltinHelpers.method(intrinsics, "fromCodePoint", 1) { interpreter, _, args ->
                val builder = StringBuilder()
                for (argument in args) {
                    val number = interpreter.toNumber(argument)
                    // 非整数（含 NaN）或越界都应抛 RangeError
                    if (number.isNaN() || number != kotlin.math.floor(number) ||
                        number < 0.0 || number > 0x10FFFF
                    ) {
                        interpreter.rangeError("Invalid code point $number")
                    }
                    val codePoint = number.toInt()
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
    private fun splitWithRegExp(text: String, regex: JsRegex, limit: Int): MutableList<JSValue?> {
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
            val matchEnd = match.end
            if (matchEnd == previousEnd) {
                // 上一位置已产生零长度匹配，前进一个字符避免死循环
                searchIndex = previousEnd + 1
                continue
            }
            result.add(JSString(text.substring(previousEnd, match.start)))
            for (groupIndex in 1 until match.groupCount) {
                val group = match.group(groupIndex)
                result.add(if (group == null) JSUndefined else JSString(group))
            }
            if (result.size >= limit) return ArrayList(result.take(limit))
            lastZeroLengthAtEnd = match.start == match.end && match.start == text.length
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

    /** 取 String 原始值；包装对象返回其内部原始值，其他接收者抛 TypeError。 */
    private fun stringReceiver(
        interpreter: Interpreter,
        thisValue: JSValue,
        name: String,
    ): JSString = when (thisValue) {
        is JSString -> thisValue
        // String.prototype 自身是一个字符串包装对象，内部值为 ""
        interpreter.intrinsics.stringPrototype -> JSString("")
        is JSBoxedPrimitive -> thisValue.primitive as? JSString
            ?: interpreter.typeError(
                "$name requires that 'this' be a String " +
                    "(got boxed ${thisValue.primitive::class.simpleName})"
            )

        else -> interpreter.typeError(
            "$name requires that 'this' be a String (got ${thisValue::class.simpleName})"
        )
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
            regexp.matcher.findAll(text).toList()
        } else {
            listOfNotNull(regexp.matcher.find(text))
        }
        if (matches.isEmpty()) return text
        if (global) regexp.setLastIndex(0)

        val builder = StringBuilder()
        var cursor = 0
        for (match in matches) {
            val start = match.start.coerceAtLeast(cursor)
            builder.append(text, cursor, start)
            builder.append(
                if (interpreter.isCallable(replacement)) {
                    val arguments = ArrayList<JSValue>()
                    arguments.add(JSString(match.value))
                    for (index in 1 until match.groupCount) {
                        val group = match.group(index)
                        arguments.add(if (group == null) JSUndefined else JSString(group))
                    }
                    arguments.add(JSNumber(match.start.toDouble()))
                    arguments.add(JSString(text))
                    interpreter.toStringValue(
                        interpreter.callFunction(replacement, JSUndefined, arguments)
                    )
                } else {
                    expandReplacement(interpreter.toStringValue(replacement), match, text)
                }
            )
            cursor = if (match.value.isEmpty()) {
                match.start
            } else {
                match.end
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
    private fun expandReplacement(template: String, match: JsMatch, input: String): String {
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
                    builder.append(input.substring(0, match.start))
                    index += 2
                }

                '\'' -> {
                    builder.append(input.substring(match.end.coerceAtMost(input.length)))
                    index += 2
                }

                in '0'..'9' -> {
                    var group = next - '0'
                    var consumed = 2
                    if (index + 2 < template.length && template[index + 2] in '0'..'9') {
                        val twoDigit = group * 10 + (template[index + 2] - '0')
                        if (twoDigit < match.groupCount) {
                            group = twoDigit
                            consumed = 3
                        }
                    }
                    if (group < match.groupCount) {
                        builder.append(match.group(group) ?: "")
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
    /**
     * 小写转换并应用希腊字母 Sigma 的上下文规则：
     * 词尾（前后均为 Case_Ignorable 包围时）使用 ς，否则使用 σ。
     */
    private fun jsLowercase(text: String): String {
        if ('\u03A3' !in text) return text.lowercase()
        val builder = StringBuilder(text.length)
        for (index in text.indices) {
            val c = text[index]
            if (c != '\u03A3') {
                builder.append(c.lowercaseChar())
                continue
            }
            // Unicode Final_Sigma 的常见实现行为：只要前面出现过有大小写的字母
            // 且后面没有字母，就使用词尾形式（标点不阻止词尾判断）
            var precededByCased = false
            for (cursor in 0 until index) {
                if (isCasedCharacter(text, cursor)) {
                    precededByCased = true
                    break
                }
            }
            var followedByCased = false
            for (cursor in index + 1 until text.length) {
                if (isCasedCharacter(text, cursor)) {
                    followedByCased = true
                    break
                }
            }
            builder.append(if (precededByCased && !followedByCased) '\u03C2' else '\u03C3')
        }
        return builder.toString()
    }

    /** 判断位置上的字符是否为有大小写的字母；代理对按一个字符处理。 */
    private fun isCasedCharacter(text: String, index: Int): Boolean {
        val c = text[index]
        if (c.isHighSurrogate() || c.isLowSurrogate()) return true // 近似：星体平面字母
        return c.isLetter()
    }

    /** Unicode Case_Ignorable 的近似判定（组合符号、格式字符与修饰符）。 */
    private fun isCaseIgnorable(c: Char): Boolean = when (c.category) {
        kotlin.text.CharCategory.NON_SPACING_MARK,
        kotlin.text.CharCategory.ENCLOSING_MARK,
        kotlin.text.CharCategory.COMBINING_SPACING_MARK,
        kotlin.text.CharCategory.FORMAT,
        -> true

        else -> c == '\'' || c == '\u2019'
    }

    /**
     * replaceAll 字符串路径的 GetSubstitution：无捕获组与命名组，
     * 因此 $1/$<name> 等保持字面量，仅展开 $$、$&、$`、$'。
     */
    private fun expandReplaceAllSubstitution(
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
                    builder.append(input, 0, position)
                    index += 2
                }

                '\'' -> {
                    val start = (position + matched.length).coerceIn(0, input.length)
                    builder.append(input, start, input.length)
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

    /** 是否为 well-formed UTF-16（无孤立代理项）。 */
    private fun isWellFormedString(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c.isHighSurrogate()) {
                if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return false
                index += 2
            } else if (c.isLowSurrogate()) {
                return false
            } else {
                index++
            }
        }
        return true
    }

    /** 把孤立代理项替换为 U+FFFD，其余字符保持原样。 */
    private fun toWellFormedString(text: String): String {
        if (isWellFormedString(text)) return text
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val c = text[index]
            if (c.isHighSurrogate()) {
                if (index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    builder.append(c).append(text[index + 1])
                    index += 2
                } else {
                    builder.append('\uFFFD')
                    index++
                }
            } else if (c.isLowSurrogate()) {
                builder.append('\uFFFD')
                index++
            } else {
                builder.append(c)
                index++
            }
        }
        return builder.toString()
    }

    /** ES5 WhiteSpace + LineTerminator；Kotlin 默认 trim 不包含 NBSP/BOM 等字符。 */
    private fun isJsWhitespace(c: Char): Boolean =
        c == '\u0009' || c == '\u000B' || c == '\u000C' || c == ' ' || c == '\u00A0' ||
            c == '\uFEFF' || c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029' ||
            c.category == kotlin.text.CharCategory.SPACE_SEPARATOR

}
