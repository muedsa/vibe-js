package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSRegExp
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey

/**
 * RegExp 构造器与 RegExp.prototype。
 */
object RegExpBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val regexpPrototype = intrinsics.regexpPrototype

        val regexpConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "RegExp",
            length = 2,
            prototypeObject = regexpPrototype,
        ) constructorBody@ { interpreter, _, args ->
            val patternValue = args.getOrNull(0) ?: JSUndefined
            val flagsValue = args.getOrNull(1)
            val hasFlags = flagsValue != null && flagsValue !is JSUndefined
            val patternIsRegExp = interpreter.isRegExp(patternValue)
            // 作为函数调用且未提供 flags：同构造器的正则原样返回
            if (!interpreter.nativeConstructCall && patternIsRegExp && !hasFlags &&
                patternValue is JSObject
            ) {
                val patternConstructor = interpreter.getV(
                    patternValue,
                    PropertyKey.CONSTRUCTOR,
                )
                if (patternConstructor === intrinsics.regexpConstructor) {
                    return@constructorBody patternValue
                }
            }
            val pattern: String
            val flags: String
            if (patternValue is JSRegExp && patternIsRegExp) {
                // 内建正则直接使用原始 source/flags（不触发可覆盖属性）
                pattern = patternValue.pattern
                flags = if (hasFlags) {
                    interpreter.toStringValue(flagsValue ?: JSUndefined)
                } else {
                    patternValue.flags
                }
            } else if (patternIsRegExp) {
                // 正则样式对象：按规范读取 source/flags
                val patternObject = patternValue as JSObject
                pattern = interpreter.toStringValue(
                    interpreter.getV(patternObject, PropertyKey.Str("source"))
                )
                flags = if (hasFlags) {
                    interpreter.toStringValue(flagsValue ?: JSUndefined)
                } else {
                    interpreter.toStringValue(
                        interpreter.getV(patternObject, PropertyKey.Str("flags"))
                    )
                }
            } else {
                pattern = if (patternValue is JSUndefined) "" else interpreter.toStringValue(patternValue)
                flags = if (hasFlags) interpreter.toStringValue(flagsValue ?: JSUndefined) else ""
            }
            interpreter.createRegExp(pattern, flags)
        }

        intrinsics.regexpConstructor = regexpConstructor

        regexpPrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = regexpConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        // RegExp[@@species]：返回 this
        regexpConstructor.defineAccessorProperty(
            key = PropertyKey.Sym(intrinsics.symbolSpecies),
            get = BuiltinHelpers.method(intrinsics, "get [Symbol.species]", 0) { _, thisValue, _ ->
                thisValue
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        // Annex B RegExp.prototype.compile：原地重新初始化正则
        regexpPrototype.defineDataProperty(
            key = PropertyKey.Str("compile"),
            value = BuiltinHelpers.method(intrinsics, "compile", 2) { interpreter, thisValue, args ->
                val target = thisValue as? JSRegExp
                    ?: interpreter.typeError("RegExp.prototype.compile called on incompatible receiver")
                val patternValue = args.getOrElse(0) { JSUndefined }
                val flagsValue = args.getOrElse(1) { JSUndefined }
                val pattern: String
                val flags: String
                if (patternValue is JSRegExp) {
                    if (flagsValue !is JSUndefined) {
                        interpreter.typeError("Cannot supply flags when compiling a RegExp")
                    }
                    pattern = patternValue.pattern
                    flags = patternValue.flags
                } else {
                    pattern = if (patternValue is JSUndefined) {
                        ""
                    } else {
                        interpreter.toStringValue(patternValue)
                    }
                    flags = if (flagsValue is JSUndefined) {
                        ""
                    } else {
                        interpreter.toStringValue(flagsValue)
                    }
                }
                val compiled = interpreter.createRegExp(pattern, flags) as? JSRegExp
                    ?: interpreter.typeError("Invalid RegExp")
                target.pattern = compiled.pattern
                target.flags = compiled.flags
                target.matcher = compiled.matcher
                interpreter.putV(target, PropertyKey.Str("lastIndex"), JSNumber.ZERO, strict = true)
                target
            },
        )

        // ES2015 15.10.6.4：原型上的 source/global/ignoreCase/multiline 是访问器
        fun flagAccessor(name: String, read: (JSRegExp) -> JSValue): JSNativeFunction =
            JSNativeFunction(name = "get $name", length = 0, prototype = intrinsics.functionPrototype) { interpreter, thisValue, _ ->
                when {
                    thisValue is JSRegExp -> read(thisValue)
                    // 普通对象/RegExp.prototype 没有内部槽：按 ES2015 宽松语义返回 undefined
                    thisValue is JSObject -> JSUndefined
                    else -> interpreter.typeError(
                        "RegExp.prototype.$name getter called on non-object"
                    )
                }
            }
        regexpPrototype.defineAccessorProperty(
            key = PropertyKey.Str("source"),
            get = flagAccessor("source") { regexp ->
                JSString(if (regexp.pattern.isEmpty()) "(?:)" else regexp.pattern)
            },
            set = null,
            enumerable = false,
            configurable = true,
        )
        regexpPrototype.defineAccessorProperty(
            key = PropertyKey.Str("global"),
            get = flagAccessor("global") { regexp -> JSBoolean.getJsBoolean(regexp.global) },
            set = null,
            enumerable = false,
            configurable = true,
        )
        regexpPrototype.defineAccessorProperty(
            key = PropertyKey.Str("ignoreCase"),
            get = flagAccessor("ignoreCase") { regexp -> JSBoolean.getJsBoolean(regexp.ignoreCase) },
            set = null,
            enumerable = false,
            configurable = true,
        )
        regexpPrototype.defineAccessorProperty(
            key = PropertyKey.Str("multiline"),
            get = flagAccessor("multiline") { regexp -> JSBoolean.getJsBoolean(regexp.multiline) },
            set = null,
            enumerable = false,
            configurable = true,
        )
        regexpPrototype.defineAccessorProperty(
            key = PropertyKey.Str("sticky"),
            get = flagAccessor("sticky") { regexp -> JSBoolean.getJsBoolean(regexp.sticky) },
            set = null,
            enumerable = false,
            configurable = true,
        )
        regexpPrototype.defineAccessorProperty(
            key = PropertyKey.Str("unicode"),
            get = flagAccessor("unicode") { regexp -> JSBoolean.getJsBoolean(regexp.unicode) },
            set = null,
            enumerable = false,
            configurable = true,
        )
        // dotAll 访问器要求接收者具有 RegExp 内部槽
        regexpPrototype.defineAccessorProperty(
            key = PropertyKey.Str("dotAll"),
            get = JSNativeFunction(
                name = "get dotAll",
                length = 0,
                prototype = intrinsics.functionPrototype,
            ) { interpreter, thisValue, _ ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("RegExp.prototype.dotAll getter called on non-object")
                }
                if (thisValue === intrinsics.regexpPrototype) return@JSNativeFunction JSUndefined
                val regexp = thisValue as? JSRegExp
                    ?: interpreter.typeError("RegExp.prototype.dotAll getter called on incompatible receiver")
                JSBoolean.getJsBoolean(regexp.dotAll)
            },
            set = null,
            enumerable = false,
            configurable = true,
        )
        regexpPrototype.defineAccessorProperty(
            key = PropertyKey.Str("flags"),
            get = BuiltinHelpers.method(intrinsics, "get flags", 0) { interpreter, thisValue, _ ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("RegExp.prototype.flags getter called on non-object")
                }
                // 规范顺序：d g i m s u v y（引擎支持 g i m s u y）
                val builder = StringBuilder(6)
                if (interpreter.toBoolean(interpreter.getV(thisValue, PropertyKey.Str("global")))) {
                    builder.append('g')
                }
                if (interpreter.toBoolean(interpreter.getV(thisValue, PropertyKey.Str("ignoreCase")))) {
                    builder.append('i')
                }
                if (interpreter.toBoolean(interpreter.getV(thisValue, PropertyKey.Str("multiline")))) {
                    builder.append('m')
                }
                if (interpreter.toBoolean(interpreter.getV(thisValue, PropertyKey.Str("dotAll")))) {
                    builder.append('s')
                }
                if (interpreter.toBoolean(interpreter.getV(thisValue, PropertyKey.Str("unicode")))) {
                    builder.append('u')
                }
                if (interpreter.toBoolean(interpreter.getV(thisValue, PropertyKey.Str("sticky")))) {
                    builder.append('y')
                }
                JSString(builder.toString())
            },
            set = null,
            enumerable = false,
            configurable = true,
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Str("exec"),
            value = BuiltinHelpers.method(intrinsics, "exec", 1) { interpreter, thisValue, args ->
                val regexp = requireRegExp(interpreter, thisValue, "RegExp.prototype.exec")
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                exec(interpreter, regexp, input)
            },
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Str("test"),
            value = BuiltinHelpers.method(intrinsics, "test", 1) { interpreter, thisValue, args ->
                val regexp = requireRegExp(interpreter, thisValue, "RegExp.prototype.test")
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSBoolean.getJsBoolean(exec(interpreter, regexp, input) !is JSNull)
            },
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolMatch),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.match]", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("RegExp.prototype[@@match] called on non-object")
                }
                val regexp = thisValue
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                // 规范：先读取 flags（可能触发 getter 错误），global 仍来自属性
                val flags = interpreter.toStringValue(
                    interpreter.getV(regexp, PropertyKey.Str("flags"))
                )
                if (!interpreter.toBoolean(interpreter.getV(regexp, PropertyKey.Str("global")))) {
                    return@method regExpExec(interpreter, regexp, input)
                }
                val fullUnicode = flags.contains('u')
                interpreter.putV(regexp, PropertyKey.Str("lastIndex"), JSNumber.ZERO, strict = true)
                val result = JSArray(prototype = intrinsics.arrayPrototype)
                var count = 0
                var guard = 0
                while (guard++ < 1_000_000) {
                    val execResult = regExpExec(interpreter, regexp, input)
                    if (execResult is JSNull) {
                        return@method if (count == 0) JSNull else result
                    }
                    if (execResult !is JSObject) {
                        interpreter.typeError("RegExp exec result is not an object")
                    }
                    val matched = interpreter.toStringValue(
                        interpreter.getV(execResult, PropertyKey.Str("0"))
                    )
                    createDataPropertyOrThrow(interpreter, result, count.toLong(), JSString(matched))
                    count++
                    if (matched.isEmpty()) {
                        val thisIndex = toLengthValue(
                            interpreter,
                            interpreter.getV(regexp, PropertyKey.Str("lastIndex")),
                        )
                        val nextIndex = advanceStringIndex(input, thisIndex, fullUnicode)
                        interpreter.putV(
                            regexp,
                            PropertyKey.Str("lastIndex"),
                            JSNumber(nextIndex.toDouble()),
                            strict = true,
                        )
                    }
                }
                interpreter.typeError("RegExp match iteration did not terminate")
            },
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolSearch),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.search]", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("RegExp.prototype[@@search] called on non-object")
                }
                val regexp = thisValue
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val previousLastIndex = interpreter.getV(regexp, PropertyKey.Str("lastIndex"))
                if (!sameValue(previousLastIndex, JSNumber.ZERO)) {
                    interpreter.putV(
                        regexp,
                        PropertyKey.Str("lastIndex"),
                        JSNumber.ZERO,
                        strict = true,
                    )
                }
                val result = regExpExec(interpreter, regexp, input)
                val currentLastIndex = interpreter.getV(regexp, PropertyKey.Str("lastIndex"))
                if (!sameValue(currentLastIndex, previousLastIndex)) {
                    interpreter.putV(
                        regexp,
                        PropertyKey.Str("lastIndex"),
                        previousLastIndex,
                        strict = true,
                    )
                }
                if (result is JSNull) {
                    JSNumber(-1.0)
                } else {
                    if (result !is JSObject) {
                        interpreter.typeError("RegExp exec result is not an object")
                    }
                    interpreter.getV(result, PropertyKey.Str("index"))
                }
            },
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolReplace),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.replace]", 2) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("RegExp.prototype[@@replace] called on non-object")
                }
                val regexp = thisValue
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val replaceValue = args.getOrElse(1) { JSUndefined }
                val functionalReplace = interpreter.isCallable(replaceValue)
                val replaceText = if (functionalReplace) "" else interpreter.toStringValue(replaceValue)
                // 规范：flags 在 global 之前读取，用于 unicode 推断
                val flags = interpreter.toStringValue(
                    interpreter.getV(regexp, PropertyKey.Str("flags"))
                )
                val global = interpreter.toBoolean(interpreter.getV(regexp, PropertyKey.Str("global")))
                var fullUnicode = false
                if (global) {
                    fullUnicode = flags.contains('u')
                    interpreter.putV(regexp, PropertyKey.Str("lastIndex"), JSNumber.ZERO, strict = true)
                }

                val results = ArrayList<JSObject>()
                if (!global) {
                    val result = regExpExec(interpreter, regexp, input)
                    if (result is JSObject) results.add(result)
                } else {
                    var guard = 0
                    while (guard++ < 1_000_000) {
                        val result = regExpExec(interpreter, regexp, input)
                        if (result is JSNull) break
                        if (result !is JSObject) {
                            interpreter.typeError("RegExp exec result is not an object")
                        }
                        results.add(result)
                        val matched = interpreter.toStringValue(
                            interpreter.getV(result, PropertyKey.Str("0"))
                        )
                        if (matched.isEmpty()) {
                            val thisIndex = toLengthValue(
                                interpreter,
                                interpreter.getV(regexp, PropertyKey.Str("lastIndex")),
                            )
                            val nextIndex = advanceStringIndex(input, thisIndex, fullUnicode)
                            interpreter.putV(
                                regexp,
                                PropertyKey.Str("lastIndex"),
                                JSNumber(nextIndex.toDouble()),
                                strict = true,
                            )
                        }
                    }
                }

                val builder = StringBuilder()
                var nextSourcePosition = 0
                for (result in results) {
                    val resultLength = toLengthValue(
                        interpreter,
                        interpreter.getV(result, PropertyKey.Str("length")),
                    )
                    val captureCount = (resultLength - 1).coerceAtLeast(0)
                    val matched = interpreter.toStringValue(
                        interpreter.getV(result, PropertyKey.Str("0"))
                    )
                    val position = toIntegerValue(
                        interpreter,
                        interpreter.getV(result, PropertyKey.Str("index")),
                    ).coerceIn(0, input.length.toLong()).toInt()
                    val captures = ArrayList<JSValue?>(captureCount.toInt())
                    for (captureIndex in 1..captureCount) {
                        val capture = interpreter.getV(
                            result,
                            PropertyKey.Str(captureIndex.toString()),
                        )
                        captures.add(
                            if (capture is JSUndefined) JSUndefined
                            else JSString(interpreter.toStringValue(capture))
                        )
                    }
                    val namedCaptures = interpreter.getV(result, PropertyKey.Str("groups"))
                    val replacementValue: JSValue = if (functionalReplace) {
                        val replacerArgs = ArrayList<JSValue>()
                        replacerArgs.add(JSString(matched))
                        replacerArgs.addAll(captures.map { it ?: JSUndefined })
                        replacerArgs.add(JSNumber(position.toDouble()))
                        replacerArgs.add(JSString(input))
                        if (namedCaptures !is JSUndefined) replacerArgs.add(namedCaptures)
                        interpreter.callFunction(replaceValue, JSUndefined, replacerArgs)
                    } else {
                        val namedObject = if (namedCaptures is JSUndefined || namedCaptures is JSNull) {
                            null
                        } else {
                            interpreter.toObject(namedCaptures)
                        }
                        JSString(
                            getSubstitution(
                                interpreter,
                                matched,
                                input,
                                position,
                                captures,
                                namedObject,
                                replaceText,
                            )
                        )
                    }
                    val replacementString = interpreter.toStringValue(replacementValue)
                    if (position >= nextSourcePosition) {
                        builder.append(input, nextSourcePosition, position)
                        builder.append(replacementString)
                        nextSourcePosition = position + matched.length
                    }
                }
                val tailStart = nextSourcePosition.coerceAtMost(input.length)
                builder.append(input, tailStart, input.length)
                JSString(builder.toString())
            },
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolSplit),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.split]", 2) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("RegExp.prototype[@@split] called on non-object")
                }
                val regexp = thisValue
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val limitValue = args.getOrNull(1)
                val constructor = interpreter.speciesConstructor(regexp, intrinsics.regexpConstructor)
                val flags = interpreter.toStringValue(interpreter.getV(regexp, PropertyKey.Str("flags")))
                val unicodeMatching = flags.contains('u')
                val newFlags = if (flags.contains('y')) flags else flags + "y"
                val splitterValue = interpreter.construct(
                    constructor,
                    listOf(regexp, JSString(newFlags)),
                )
                val splitter = splitterValue as? JSObject
                    ?: interpreter.typeError("RegExp species constructor did not return an object")
                // 规范顺序：先构造 splitter，再转换 limit
                val limit = if (limitValue == null || limitValue is JSUndefined) {
                    4294967295L
                } else {
                    interpreter.toUint32(limitValue)
                }
                val result = JSArray(prototype = intrinsics.arrayPrototype)
                var resultLength = 0L
                if (limit == 0L) return@method result
                if (input.isEmpty()) {
                    val match = regExpExec(interpreter, splitter, input)
                    if (match !is JSNull) return@method result
                    createDataPropertyOrThrow(interpreter, result, 0, JSString(""))
                    return@method result
                }
                var lastMatchEnd = 0
                var searchIndex = 0
                while (searchIndex < input.length) {
                    interpreter.putV(
                        splitter,
                        PropertyKey.Str("lastIndex"),
                        JSNumber(searchIndex.toDouble()),
                        strict = true,
                    )
                    val match = regExpExec(interpreter, splitter, input)
                    if (match is JSNull) {
                        searchIndex = advanceStringIndex(
                            input,
                            searchIndex.toLong(),
                            unicodeMatching,
                        ).toInt()
                        continue
                    }
                    if (match !is JSObject) {
                        interpreter.typeError("RegExp exec result is not an object")
                    }
                    val endIndex = minOf(
                        toLengthValue(
                            interpreter,
                            interpreter.getV(splitter, PropertyKey.Str("lastIndex")),
                        ),
                        input.length.toLong(),
                    ).toInt()
                    if (endIndex == lastMatchEnd) {
                        searchIndex = advanceStringIndex(
                            input,
                            searchIndex.toLong(),
                            unicodeMatching,
                        ).toInt()
                        continue
                    }
                    createDataPropertyOrThrow(
                        interpreter,
                        result,
                        resultLength,
                        JSString(input.substring(lastMatchEnd, searchIndex)),
                    )
                    resultLength++
                    if (resultLength == limit) return@method result
                    lastMatchEnd = endIndex
                    val captureCount = toLengthValue(
                        interpreter,
                        interpreter.getV(match, PropertyKey.Str("length")),
                    ) - 1
                    var captureIndex = 1L
                    while (captureIndex <= captureCount) {
                        val capture = interpreter.getV(
                            match,
                            PropertyKey.Str(captureIndex.toString()),
                        )
                        createDataPropertyOrThrow(
                            interpreter,
                            result,
                            resultLength,
                            if (capture is JSUndefined) JSUndefined
                            else JSString(interpreter.toStringValue(capture)),
                        )
                        resultLength++
                        if (resultLength == limit) return@method result
                        captureIndex++
                    }
                    searchIndex = lastMatchEnd
                }
                createDataPropertyOrThrow(
                    interpreter,
                    result,
                    resultLength,
                    JSString(input.substring(lastMatchEnd)),
                )
                result
            },
        )

        // RegExp String Iterator 使用共享原型（@@toStringTag 与 @@iterator 由 Intrinsics/IteratorBuiltins 提供）

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolMatchAll),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.matchAll]", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("RegExp.prototype[@@matchAll] called on non-object")
                }
                val regexp = thisValue
                val input = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val constructor = interpreter.speciesConstructor(regexp, intrinsics.regexpConstructor)
                val flags = interpreter.toStringValue(
                    interpreter.getV(regexp, PropertyKey.Str("flags"))
                )
                val matcherValue = interpreter.construct(
                    constructor,
                    listOf(regexp, JSString(flags)),
                )
                val matcher = matcherValue as? JSObject
                    ?: interpreter.typeError("RegExp species constructor did not return an object")
                // 规范：缓存原对象 lastIndex 到新 matcher（不改写原对象）
                val lastIndex = toLengthValue(
                    interpreter,
                    interpreter.getV(regexp, PropertyKey.Str("lastIndex")),
                )
                interpreter.putV(
                    matcher,
                    PropertyKey.Str("lastIndex"),
                    JSNumber(lastIndex.toDouble()),
                    strict = true,
                )
                createRegExpStringIterator(
                    interpreter = interpreter,
                    prototype = intrinsics.regexpStringIteratorPrototype,
                    regexp = matcher,
                    input = input,
                    global = flags.contains('g'),
                    fullUnicode = flags.contains('u'),
                )
            },
        )

        regexpPrototype.defineDataProperty(
            key = PropertyKey.Str("toString"),
            value = BuiltinHelpers.method(intrinsics, "toString", 0) { interpreter, thisValue, _ ->
                when (thisValue) {
                    is JSRegExp -> JSString(thisValue.toPrimitiveString())
                    is JSObject -> {
                        val source = interpreter.toStringValue(
                            interpreter.getV(thisValue, PropertyKey.Str("source"))
                        )
                        val flags = interpreter.toStringValue(
                            interpreter.getV(thisValue, PropertyKey.Str("flags"))
                        )
                        JSString("/$source/$flags")
                    }

                    else -> interpreter.typeError("RegExp.prototype.toString requires that 'this' be a RegExp")
                }
            },
        )

        // RegExp.escape(string)：首字符为 ASCII 字母/数字时以 \xHH 转义，
        // 其余按 SyntaxCharacter、控制字符、空白/行终止符、代理项与 otherPunctuators 规则处理
        regexpConstructor.defineDataProperty(
            key = PropertyKey.Str("escape"),
            value = BuiltinHelpers.method(intrinsics, "escape", 1) { interpreter, _, args ->
                val value = args.getOrElse(0) { JSUndefined }
                if (value !is JSString) {
                    interpreter.typeError("RegExp.escape requires a string argument")
                }
                JSString(escapeRegExpString(value.value))
            },
        )

        registry.defineGlobal("RegExp", regexpConstructor)
    }

    private fun requireRegExp(interpreter: Interpreter, thisValue: JSValue, name: String): JSRegExp =
        thisValue as? JSRegExp ?: interpreter.typeError("$name requires that 'this' be a RegExp")

    /**
     * 执行正则匹配并构造结果数组（含 index/input）。
     */
    /** RegExp.escape 的字符串转换。 */
    private fun escapeRegExpString(text: String): String {
        if (text.isEmpty()) return ""
        val builder = StringBuilder(text.length + 8)
        var first = true
        var index = 0
        while (index < text.length) {
            val codePoint: Int
            if (text[index].isHighSurrogate() && index + 1 < text.length &&
                text[index + 1].isLowSurrogate()
            ) {
                val high = text[index].code - 0xD800
                val low = text[index + 1].code - 0xDC00
                codePoint = 0x10000 + (high shl 10) + low
                index += 2
            } else {
                codePoint = text[index].code
                index++
            }
            if (first && isAsciiAlphanumeric(codePoint)) {
                builder.append("\\x").append(codePoint.toString(16).padStart(2, '0'))
            } else {
                builder.append(encodeForRegExpEscape(codePoint))
            }
            first = false
        }
        return builder.toString()
    }

    private fun isAsciiAlphanumeric(codePoint: Int): Boolean =
        codePoint in '0'.code..'9'.code ||
            codePoint in 'a'.code..'z'.code ||
            codePoint in 'A'.code..'Z'.code

    /** EncodeForRegExpEscape：控制转义、语法字符、空白/代理项与其他标点。 */
    private fun encodeForRegExpEscape(codePoint: Int): String {
        when (codePoint) {
            9 -> return "\\t"
            10 -> return "\\n"
            11 -> return "\\v"
            12 -> return "\\f"
            13 -> return "\\r"
        }
        val char = codePoint.toChar()
        if (codePoint <= 0xFFFF && "\\^$.*+?()[]{}|/".contains(char)) {
            return "\\$char"
        }
        val isSurrogate = codePoint in 0xD800..0xDFFF
        val isLineTerminator = codePoint == 0x2028 || codePoint == 0x2029
        val isWhiteSpace = codePoint <= 0xFFFF && (
            codePoint == 0x20 || codePoint == 0xA0 || codePoint == 0xFEFF ||
                char.category == kotlin.text.CharCategory.SPACE_SEPARATOR
            )
        val otherPunctuators = ",-=<>#&!%:;@~'`\""
        val isOtherPunctuator = codePoint <= 0xFFFF && otherPunctuators.contains(char)
        if (isSurrogate || isLineTerminator || isWhiteSpace || isOtherPunctuator) {
            if (codePoint <= 0xFF) {
                return "\\x" + codePoint.toString(16).padStart(2, '0')
            }
            val unit = char.code
            return "\\u" + unit.toString(16).padStart(4, '0')
        }
        // 其余码点原样输出（astral 码点输出代理对）
        return if (codePoint <= 0xFFFF) {
            char.toString()
        } else {
            val value = codePoint - 0x10000
            buildString {
                append(((value shr 10) + 0xD800).toChar())
                append(((value and 0x3FF) + 0xDC00).toChar())
            }
        }
    }

    /** 创建 RegExp String Iterator；next 状态保存在实例内部槽。 */
    private fun createRegExpStringIterator(
        interpreter: Interpreter,
        prototype: JSObject,
        regexp: JSObject,
        input: String,
        global: Boolean,
        fullUnicode: Boolean,
    ): JSObject {
        var done = false
        fun result(value: JSValue, isDone: Boolean): JSObject {
            val resultObject = JSObject(prototype = interpreter.intrinsics.objectPrototype)
            resultObject.defineDataProperty(
                key = PropertyKey.Str("value"),
                value = value,
                writable = true,
                enumerable = true,
                configurable = true,
            )
            resultObject.defineDataProperty(
                key = PropertyKey.Str("done"),
                value = JSBoolean.getJsBoolean(isDone),
                writable = true,
                enumerable = true,
                configurable = true,
            )
            return resultObject
        }
        return IteratorBuiltins.createIteratorWithNext(
            interpreter.intrinsics,
            prototype,
        ) { inner, _, _ ->
            if (done) {
                return@createIteratorWithNext result(JSUndefined, true)
            }
            val match = regExpExec(inner, regexp, input)
            if (match is JSNull) {
                done = true
                result(JSUndefined, true)
            } else if (!global) {
                done = true
                result(match, false)
            } else {
                if (match !is JSObject) {
                    inner.typeError("RegExp exec result is not an object")
                }
                val matched = inner.toStringValue(
                    inner.getV(match, PropertyKey.Str("0"))
                )
                if (matched.isEmpty()) {
                    val thisIndex = toLengthValue(
                        inner,
                        inner.getV(regexp, PropertyKey.Str("lastIndex")),
                    )
                    val nextIndex = advanceStringIndex(input, thisIndex, fullUnicode)
                    inner.putV(
                        regexp,
                        PropertyKey.Str("lastIndex"),
                        JSNumber(nextIndex.toDouble()),
                        strict = true,
                    )
                }
                result(match, false)
            }
        }
    }

    /** RegExpExec：优先调用对象自定义 exec，否则回退内建执行。 */
    private suspend fun regExpExec(
        interpreter: Interpreter,
        regexp: JSObject,
        input: String,
    ): JSValue {
        val execFunction = interpreter.getV(regexp, PropertyKey.Str("exec"))
        if (interpreter.isCallable(execFunction)) {
            val result = interpreter.callFunction(execFunction, regexp, listOf(JSString(input)))
            if (result !is JSObject && result !is JSNull) {
                interpreter.typeError("RegExp exec result is not an object")
            }
            return result
        }
        if (regexp !is JSRegExp) {
            interpreter.typeError("RegExp.prototype.exec called on incompatible receiver")
        }
        return exec(interpreter, regexp, input)
    }

    /** CreateDataPropertyOrThrow：定义结果数组元素。 */
    private suspend fun createDataPropertyOrThrow(
        interpreter: Interpreter,
        target: JSObject,
        index: Long,
        value: JSValue,
    ) {
        val success = interpreter.defineOwnPropertyOn(
            target,
            PropertyKey.Str(index.toString()),
            PropertyDescriptor.data(value),
        )
        if (!success) {
            interpreter.typeError("Cannot define property $index")
        }
    }

    /** ToLength：限制到 2^53-1。 */
    private suspend fun toLengthValue(interpreter: Interpreter, value: JSValue): Long {
        val number = interpreter.toNumber(value)
        if (number.isNaN() || number <= 0.0) return 0L
        if (number >= 9007199254740991.0) return 9007199254740991L
        return kotlin.math.floor(number).toLong()
    }

    /** ToIntegerOrInfinity：向零截断。 */
    private suspend fun toIntegerValue(interpreter: Interpreter, value: JSValue): Long {
        val number = interpreter.toNumber(value)
        if (number.isNaN()) return 0L
        if (number >= Long.MAX_VALUE.toDouble()) return Long.MAX_VALUE
        if (number <= Long.MIN_VALUE.toDouble()) return Long.MIN_VALUE
        return number.toLong()
    }

    /** SameValue（区分 -0，NaN 相等）。 */
    private fun sameValue(left: JSValue, right: JSValue): Boolean {
        if (left is JSNumber && right is JSNumber) {
            if (left.value.isNaN() && right.value.isNaN()) return true
            if (left.value == 0.0 && right.value == 0.0) {
                return 1.0 / left.value == 1.0 / right.value
            }
            return left.value == right.value
        }
        return left === right
    }

    /** AdvanceStringIndex：unicode 模式下按码点前进（索引可超出 Int 范围）。 */
    private fun advanceStringIndex(input: String, index: Long, unicode: Boolean): Long {
        if (!unicode) return index + 1
        if (index < 0 || index + 1 >= input.length) return index + 1
        val position = index.toInt()
        val first = input[position]
        if (first.isHighSurrogate() && input[position + 1].isLowSurrogate()) return index + 2
        return index + 1
    }

    /** GetSubstitution：展开替换串中的 $ 模式。 */
    private suspend fun getSubstitution(
        interpreter: Interpreter,
        matched: String,
        input: String,
        position: Int,
        captures: List<JSValue?>,
        namedCaptures: JSObject?,
        template: String,
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
            when (val next = template[index + 1]) {
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
                    val suffixStart = (position + matched.length).coerceIn(0, input.length)
                    builder.append(input, suffixStart, input.length)
                    index += 2
                }

                '<' -> {
                    if (namedCaptures == null) {
                        builder.append('$')
                        index++
                        continue
                    }
                    val end = template.indexOf('>', index + 2)
                    if (end < 0) {
                        builder.append('$')
                        index++
                        continue
                    }
                    val name = template.substring(index + 2, end)
                    val capture = interpreter.getV(namedCaptures, PropertyKey.Str(name))
                    builder.append(
                        if (capture is JSUndefined) "" else interpreter.toStringValue(capture)
                    )
                    index = end + 1
                }

                // $0N（N 为 1-9）按 legacy 规则等价于 $N；$00 与其他越界形式保持字面量
                '0' -> {
                    val cursor = index + 2
                    if (cursor < template.length && template[cursor] in '0'..'9') {
                        val second = template[cursor] - '0'
                        if (second in 1..9 && second <= captures.size) {
                            val capture = captures[second - 1]
                            builder.append(
                                if (capture == null || capture is JSUndefined) ""
                                else interpreter.toStringValue(capture)
                            )
                        } else {
                            builder.append(template, index, cursor + 1)
                        }
                        index = cursor + 1
                    } else {
                        builder.append("$0")
                        index += 2
                    }
                }

                in '1'..'9' -> {
                    var cursor = index + 1
                    val first = template[cursor] - '0'
                    cursor++
                    if (cursor < template.length && template[cursor] in '0'..'9') {
                        val two = first * 10 + (template[cursor] - '0')
                        if (two <= captures.size) {
                            val capture = captures[two - 1]
                            builder.append(
                                if (capture == null || capture is JSUndefined) ""
                                else interpreter.toStringValue(capture)
                            )
                            index = cursor + 1
                            continue
                        }
                        // 两位越界：回退到一位（第二位留作普通字符）
                    }
                    if (first <= captures.size) {
                        val capture = captures[first - 1]
                        builder.append(
                            if (capture == null || capture is JSUndefined) ""
                            else interpreter.toStringValue(capture)
                        )
                        index = cursor
                    } else {
                        builder.append(template, index, cursor)
                        index = cursor
                    }
                }

                else -> {
                    builder.append('$')
                    index++
                }
            }
        }
        return builder.toString()
    }

    private suspend fun exec(interpreter: Interpreter, regexp: JSRegExp, input: String): JSValue {
        // 内建执行使用 [[OriginalFlags]]，不受 global/unicode 等属性覆盖影响
        val useLastIndex = regexp.global || regexp.sticky
        val lastIndexKey = PropertyKey.Str("lastIndex")
        // lastIndex 需要通过 ToNumber 转换（可能触发 valueOf 并抛错）
        val start = if (useLastIndex) {
            val lastIndexValue = interpreter.getV(regexp, lastIndexKey)
            val number = interpreter.toNumber(lastIndexValue)
            // ToLength：NaN/负数按 0 处理
            if (number.isNaN() || number <= 0.0) {
                0
            } else if (number >= Int.MAX_VALUE.toDouble()) {
                Int.MAX_VALUE
            } else {
                kotlin.math.truncate(number).toInt()
            }
        } else {
            0
        }
        if (start < 0 || start > input.length) {
            if (useLastIndex) {
                interpreter.putV(regexp, lastIndexKey, JSNumber.ZERO, strict = true)
            }
            return JSNull
        }

        val match = try {
            regexp.matcher.find(input, start)
        } catch (_: Throwable) {
            interpreter.typeError("Regular expression execution failed")
        }

        if (match == null || (regexp.sticky && match.start != start)) {
            if (useLastIndex) {
                interpreter.putV(regexp, lastIndexKey, JSNumber.ZERO, strict = true)
            }
            return JSNull
        }

        if (useLastIndex) {
            interpreter.putV(
                regexp,
                lastIndexKey,
                JSNumber(match.end.toDouble()),
                strict = true,
            )
        }

        val values = ArrayList<JSValue?>(match.groupCount)
        // 未参与匹配的捕获组按规范为 undefined
        values.add(JSString(match.value))
        for (index in 1 until match.groupCount) {
            val group = match.group(index)
            values.add(if (group == null) JSUndefined else JSString(group))
        }
        val result = JSArray(values, prototype = interpreter.intrinsics.arrayPrototype)
        result.defineDataProperty(
            key = PropertyKey.Str("index"),
            value = JSNumber(match.start.toDouble()),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        result.defineDataProperty(
            key = PropertyKey.Str("input"),
            value = JSString(input),
            writable = true,
            enumerable = false,
            configurable = true,
        )
        return result
    }
}
