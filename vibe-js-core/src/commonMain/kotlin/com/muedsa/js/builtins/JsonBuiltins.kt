package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBigInt
import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSFunction
import com.muedsa.js.runtime.value.JSNativeFunction
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.NumberFormatting
import com.muedsa.js.runtime.value.PropertyDescriptor
import com.muedsa.js.runtime.value.PropertyKey
import kotlin.math.min

/**
 * JSON 对象。
 *
 * - parse：复用 Lexer/Parser 解析为表达式后求值，并支持 reviver；
 * - stringify：自行序列化，支持 replacer（函数/数组）与 space，并处理
 *   undefined/function、NaN/Infinity、循环引用、数字格式与 toJSON。
 */
object JsonBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val json = intrinsics.jsonObject

        json.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("JSON"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        json.defineDataProperty(
            key = PropertyKey.Str("parse"),
            value = BuiltinHelpers.method(intrinsics, "parse", 2) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val parser = JsonParser(
                    text,
                    intrinsics.objectPrototype,
                    intrinsics.arrayPrototype,
                    maxDepth = interpreter.context.limits.maxJsonDepth,
                    interpreter = interpreter,
                )
                val value = try {
                    // 使用严格 JSON 语法，而不是宽松的 JS 表达式解析器
                    parser.parse()
                } catch (error: JsonDepthLimitException) {
                    // 与 V8 一致：深度超限按 RangeError 暴露，而不是文本语法错误。
                    interpreter.rangeError(error.message ?: "Maximum JSON nesting depth exceeded")
                } catch (error: JsonParseException) {
                    interpreter.syntaxError(error.message ?: "Invalid JSON")
                }

                val reviver = args.getOrNull(1)
                if (reviver != null && interpreter.isCallable(reviver)) {
                    val holder = JSObject(prototype = intrinsics.objectPrototype)
                    holder.defineDataProperty(
                        PropertyKey.Str(""),
                        value,
                        writable = true,
                        enumerable = true,
                        configurable = true,
                    )
                    internalize(interpreter, holder, "", reviver, parser, holder)
                } else {
                    value
                }
            },
        )

        json.defineDataProperty(
            key = PropertyKey.Str("rawJSON"),
            value = BuiltinHelpers.method(intrinsics, "rawJSON", 1) { interpreter, _, args ->
                val textValue = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                if (textValue.isEmpty() ||
                    textValue.first() in setOf('\t', '\n', '\r', ' ') ||
                    textValue.last() in setOf('\t', '\n', '\r', ' ')
                ) {
                    interpreter.syntaxError("Invalid raw JSON text")
                }
                val parsed = try {
                    JsonParser(
                        textValue,
                        intrinsics.objectPrototype,
                        intrinsics.arrayPrototype,
                        maxDepth = interpreter.context.limits.maxJsonDepth,
                        interpreter = interpreter,
                    ).parse()
                } catch (error: JsonDepthLimitException) {
                    interpreter.rangeError(error.message ?: "Maximum JSON nesting depth exceeded")
                } catch (error: JsonParseException) {
                    interpreter.syntaxError(error.message ?: "Invalid raw JSON text")
                }
                if (parsed is JSObject) {
                    interpreter.syntaxError("Raw JSON must be a primitive value")
                }
                val raw = JSObject(prototype = null)
                    .attachAllocationBudget(interpreter.context.objectAllocations)
                raw.isRawJson = true
                raw.defineDataProperty(
                    key = PropertyKey.Str("rawJSON"),
                    value = JSString(textValue),
                    writable = false,
                    enumerable = true,
                    configurable = false,
                )
                raw.preventExtensions()
                raw
            },
        )

        json.defineDataProperty(
            key = PropertyKey.Str("isRawJSON"),
            value = BuiltinHelpers.method(intrinsics, "isRawJSON", 1) { _, _, args ->
                val value = args.getOrElse(0) { JSUndefined }
                JSBoolean.getJsBoolean(value is JSObject && value.isRawJson)
            },
        )

        json.defineDataProperty(
            key = PropertyKey.Str("stringify"),
            value = BuiltinHelpers.method(intrinsics, "stringify", 3) { interpreter, _, args ->
                val value = args.getOrNull(0) ?: JSUndefined
                val replacer = args.getOrNull(1)
                val spaceValue = args.getOrNull(2)

                val propertyList = buildPropertyList(interpreter, replacer)
                val gap = buildGap(interpreter, spaceValue)

                val holder = JSObject(prototype = intrinsics.objectPrototype)
                holder.defineDataProperty(
                    PropertyKey.Str(""),
                    value,
                    writable = true,
                    enumerable = true,
                    configurable = true,
                )
                val seen = mutableSetOf<JSObject>()
                val text = serializeProperty(
                    interpreter = interpreter,
                    holder = holder,
                    key = "",
                    replacer = replacer,
                    propertyList = propertyList,
                    gap = gap,
                    indent = "",
                    seen = seen,
                )
                if (text == null) JSUndefined else JSString(text)
            },
        )
    }

    /** reviver 递归处理（ES5 JSON.parse 的 InternalizeJSONProperty）。 */
    private suspend fun internalize(
        interpreter: Interpreter,
        holder: JSObject,
        key: String,
        reviver: JSValue,
        parser: JsonParser,
        rootHolder: JSObject,
    ): JSValue {
        val value = interpreter.getV(holder, PropertyKey.Str(key))

        if (value is JSObject) {
            // 规范：IsArray 会穿透 Proxy（撤销的 Proxy 抛 TypeError）
            if (interpreter.isArray(value)) {
                val length = interpreter.lengthOfArrayLike(value)
                for (index in 0 until length) {
                    interpreter.checkExecution()
                    val elementKey = index.toString()
                    val elementKeyRef = PropertyKey.Str(elementKey)
                    val newElement = internalize(
                        interpreter, value, elementKey, reviver, parser, rootHolder,
                    )
                    if (newElement is JSUndefined) {
                        // 规范此处直接调用 [[Delete]]，删除失败不抛异常
                        interpreter.internalDeleteProperty(value, elementKeyRef)
                    } else {
                        createDataPropertyOrIgnore(interpreter, value, elementKeyRef, newElement)
                    }
                }
            } else {
                // 规范：先取 EnumerableOwnPropertyNames 快照，再逐个 Internalize；
                // reviver 删除属性后仍按快照继续（Get 可能读到原型链值）
                val keys = interpreter.internalOwnPropertyKeys(value)
                    .filterIsInstance<PropertyKey.Str>()
                    .filter { interpreter.internalGetOwnProperty(value, it)?.enumerable == true }
                for (propertyKey in keys) {
                    interpreter.checkExecution()
                    val newValue = internalize(
                        interpreter, value, propertyKey.value, reviver, parser, rootHolder,
                    )
                    if (newValue is JSUndefined) {
                        interpreter.internalDeleteProperty(value, propertyKey)
                    } else {
                        createDataPropertyOrIgnore(interpreter, value, propertyKey, newValue)
                    }
                }
            }
        }

        val context = JSObject(prototype = interpreter.intrinsics.objectPrototype)
        val literal = if (holder === rootHolder && key.isEmpty()) {
            parser.rootLiteral
        } else {
            parser.sourceOf(holder, key)
        }
        // 属性在 reviver 期间被改写后不再提供原始 source
        val source = literal?.takeIf { interpreter.strictEquals(value, it.value) }?.text
        if (source != null) {
            context.defineDataProperty(
                PropertyKey.Str("source"),
                JSString(source),
                writable = true,
                enumerable = true,
                configurable = true,
            )
        }
        return interpreter.callFunction(
            reviver,
            holder,
            listOf(JSString(key), value, context),
        )
    }

    /**
     * InternalizeJSONProperty 的 CreateDataProperty：
     * 完整数据描述符，定义失败（既有不可配置属性或对象不可扩展）时由调用方忽略。
     * 必须走 [[DefineOwnProperty]] 而不是直接改存储，Proxy 的 defineProperty trap 才可观察。
     */
    private suspend fun createDataPropertyOrIgnore(
        interpreter: Interpreter,
        objectValue: JSObject,
        key: PropertyKey,
        value: JSValue,
    ) {
        interpreter.internalDefineOwnProperty(
            objectValue,
            key,
            PropertyDescriptor.data(value, writable = true, enumerable = true, configurable = true),
        )
    }

    /** replacer 为数组时构造属性白名单（Proxy 数组同样按 IsArray 处理）。 */
    private suspend fun buildPropertyList(
        interpreter: Interpreter,
        replacer: JSValue?,
    ): List<String>? {
        if (replacer !is JSObject || !interpreter.isArray(replacer)) return null
        val keys = mutableListOf<String>()
        val length = interpreter.lengthOfArrayLike(replacer)
        for (index in 0 until length) {
            // 规范：通过 [[Get]] 读取（代理 replacer 可以观察到索引访问）
            val element = interpreter.getV(replacer, PropertyKey.Str(index.toString()))
            val text = when (element) {
                is JSString -> element.value
                is JSNumber -> NumberFormatting.toString(element.value)
                // 包装对象按内部槽通过 ToString 转成属性名
                is JSBoxedPrimitive -> when (element.primitive) {
                    is JSString, is JSNumber -> interpreter.toStringValue(element)
                    else -> continue
                }

                else -> continue
            }
            if (text !in keys) keys.add(text)
        }
        return keys
    }

    /** 构造缩进字符串。 */
    private suspend fun buildGap(interpreter: Interpreter, spaceValue: JSValue?): String {
        return when (spaceValue) {
            null, is JSUndefined -> ""
            is JSNumber -> {
                val count = min(10, spaceValue.value.toInt().coerceAtLeast(0))
                " ".repeat(count)
            }

            is JSString -> spaceValue.value.take(10)
            // 包装对象按内部槽转换：Number → ToNumber 后截断，String → ToString
            is JSBoxedPrimitive -> when (spaceValue.primitive) {
                is JSNumber -> {
                    val number = interpreter.toNumber(spaceValue)
                    val count = if (number.isFinite()) {
                        min(10, kotlin.math.truncate(number).toInt().coerceAtLeast(0))
                    } else {
                        0
                    }
                    " ".repeat(count)
                }

                is JSString -> interpreter.toStringValue(spaceValue).take(10)
                else -> ""
            }

            else -> ""
        }
    }

    /**
     * 序列化 holder[key]；返回 null 表示该值应被忽略。
     */
    private suspend fun serializeProperty(
        interpreter: Interpreter,
        holder: JSObject,
        key: String,
        replacer: JSValue?,
        propertyList: List<String>?,
        gap: String,
        indent: String,
        seen: MutableSet<JSObject>,
    ): String? {
        var value = interpreter.getV(holder, PropertyKey.Str(key))

        // 规范 SerializeJSONProperty 步骤 2：Object 或 BigInt 都要读取 toJSON；
        // BigInt 通过 GetV 从其原型读取，调用时 this 仍是 BigInt 原始值
        if (value is JSObject || value is JSBigInt) {
            val toJson = interpreter.getV(value, PropertyKey.Str("toJSON"))
            if (interpreter.isCallable(toJson)) {
                value = interpreter.callFunction(toJson, value, listOf(JSString(key)))
            }
        }

        // replacer 函数
        if (replacer != null && interpreter.isCallable(replacer)) {
            value = interpreter.callFunction(
                replacer,
                holder,
                listOf(JSString(key), value),
            )
        }

        // [[IsRawJSON]]：直接输出原始 JSON 文本
        if (value is JSObject && value.isRawJson) {
            return (value.getOwnDataValue(PropertyKey.Str("rawJSON")) as? JSString)?.value
                ?: "null"
        }

        return serializeValue(
            interpreter = interpreter,
            value = value,
            replacer = replacer,
            propertyList = propertyList,
            gap = gap,
            indent = indent,
            seen = seen,
        )
    }

    /** 序列化入口：包装对象先按内部槽展开（ToNumber/ToString/[[BooleanData]]）。 */
    private suspend fun serializeValue(
        interpreter: Interpreter,
        value: JSValue,
        replacer: JSValue?,
        propertyList: List<String>?,
        gap: String,
        indent: String,
        seen: MutableSet<JSObject>,
    ): String? {
        if (value is JSBoxedPrimitive) {
            when (val primitive = value.primitive) {
                is JSNumber -> return serializeValue(
                    interpreter,
                    JSNumber(interpreter.toNumber(value)),
                    replacer,
                    propertyList,
                    gap,
                    indent,
                    seen,
                )

                is JSString -> return serializeValue(
                    interpreter,
                    JSString(interpreter.toStringValue(value)),
                    replacer,
                    propertyList,
                    gap,
                    indent,
                    seen,
                )

                is JSBoolean -> return serializeValue(
                    interpreter,
                    primitive,
                    replacer,
                    propertyList,
                    gap,
                    indent,
                    seen,
                )

                // BigInt 包装对象按内部槽取出后与 BigInt 原始值一样抛 TypeError
                is JSBigInt -> interpreter.typeError("Do not know how to serialize a BigInt")

                else -> Unit
            }
        }
        return serializeUnboxedValue(
            interpreter,
            value,
            replacer,
            propertyList,
            gap,
            indent,
            seen,
        )
    }

    private suspend fun serializeUnboxedValue(
        interpreter: Interpreter,
        value: JSValue,
        replacer: JSValue?,
        propertyList: List<String>?,
        gap: String,
        indent: String,
        seen: MutableSet<JSObject>,
    ): String? = when (value) {
        is JSNull -> "null"
        is JSUndefined -> null
        is JSBoolean -> value.value.toString()
        is JSNumber -> if (value.value.isFinite()) NumberFormatting.toString(value.value) else "null"
        is JSString -> quote(interpreter, value.value)
        is JSSymbol -> null
        // JSON 文本无法无损表示 BigInt，规范要求抛 TypeError
        is JSBigInt -> interpreter.typeError("Do not know how to serialize a BigInt")
        is JSFunction, is JSNativeFunction -> null

        is JSArray -> serializeJsonArray(
            interpreter = interpreter,
            array = value,
            replacer = replacer,
            propertyList = propertyList,
            gap = gap,
            indent = indent,
            seen = seen,
        )

        is JSObject -> {
            // 规范：IsArray(value) 为真按数组序列化（Proxy 包数组也进入该分支）
            if (interpreter.isArray(value)) {
                return serializeJsonArray(
                    interpreter = interpreter,
                    array = value,
                    replacer = replacer,
                    propertyList = propertyList,
                    gap = gap,
                    indent = indent,
                    seen = seen,
                )
            }
            if (!seen.add(value)) {
                interpreter.typeError("Converting circular structure to JSON")
            }
            try {
                val keys = propertyList ?: interpreter.internalOwnPropertyKeys(value)
                    .filterIsInstance<PropertyKey.Str>()
                    .filter { interpreter.internalGetOwnProperty(value, it)?.enumerable == true }
                    .map { it.value }
                val childIndent = indent + gap
                val parts = ArrayList<String>()
                for (propertyKey in keys) {
                    interpreter.checkExecution()
                    val serialized = serializeProperty(
                        interpreter = interpreter,
                        holder = value,
                        key = propertyKey,
                        replacer = replacer,
                        propertyList = propertyList,
                        gap = gap,
                        indent = childIndent,
                        seen = seen,
                    ) ?: continue
                    val separator = if (gap.isEmpty()) ":" else ": "
                    parts.add("${quote(interpreter, propertyKey)}$separator$serialized")
                }
                if (gap.isEmpty() || parts.isEmpty()) {
                    "{${parts.joinToString(",")}}"
                } else {
                    "{\n$childIndent${parts.joinToString(",\n$childIndent")}\n$indent}"
                }
            } finally {
                seen.remove(value)
            }
        }
    }

    /**
     * SerializeJSONArray：通过 [[Get]] 读取 length 与每个索引。
     *
     * 使用通用于 JSArray 与 Proxy 的路径：Proxy 数组必须触发 get trap，
     * 且 length 按 LengthOfArrayLike 读取（可被 trap 覆盖）。
     */
    private suspend fun serializeJsonArray(
        interpreter: Interpreter,
        array: JSObject,
        replacer: JSValue?,
        propertyList: List<String>?,
        gap: String,
        indent: String,
        seen: MutableSet<JSObject>,
    ): String {
        if (!seen.add(array)) {
            interpreter.typeError("Converting circular structure to JSON")
        }
        try {
            val length = interpreter.lengthOfArrayLike(array)
            val childIndent = indent + gap
            val parts = ArrayList<String>(length.toInt().coerceAtMost(1024))
            for (index in 0 until length) {
                interpreter.checkExecution()
                val serialized = serializeProperty(
                    interpreter = interpreter,
                    holder = array,
                    key = index.toString(),
                    replacer = replacer,
                    propertyList = propertyList,
                    gap = gap,
                    indent = childIndent,
                    seen = seen,
                )
                parts.add(serialized ?: "null")
            }
            return if (gap.isEmpty() || parts.isEmpty()) {
                "[${parts.joinToString(",")}]"
            } else {
                "[\n$childIndent${parts.joinToString(",\n$childIndent")}\n$indent]"
            }
        } finally {
            seen.remove(array)
        }
    }

    /** JSON 字符串转义（孤立代理项按 well-formed JSON.stringify 转义）。 */
    private suspend fun quote(interpreter: Interpreter, text: String): String {
        val builder = StringBuilder(text.length + 2)
        builder.append('"')
        var index = 0
        while (index < text.length) {
            // 超长字符串按字符规模消费检查点，避免单次 stringify 长时间占用。
            if (index % 4096 == 0) interpreter.checkExecution()
            val c = text[index]
            when (c) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        builder.append("\\u")
                        builder.append(c.code.toString(16).padStart(4, '0'))
                    } else if (c.isHighSurrogate() && index + 1 < text.length &&
                        text[index + 1].isLowSurrogate()
                    ) {
                        builder.append(c).append(text[index + 1])
                        index++
                    } else if (c.isSurrogate()) {
                        builder.append("\\u")
                        builder.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(c)
                    }
                }
            }
            index++
        }
        builder.append('"')
        return builder.toString()
    }
}
