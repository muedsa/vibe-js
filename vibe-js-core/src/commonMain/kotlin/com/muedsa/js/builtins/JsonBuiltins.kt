package com.muedsa.js.builtins

import com.muedsa.js.ast.ExpressionStatement
import com.muedsa.js.lexer.Lexer
import com.muedsa.js.lexer.LexerException
import com.muedsa.js.parser.ParseException
import com.muedsa.js.parser.Parser
import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.value.JSArray
import com.muedsa.js.runtime.value.JSBoolean
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
            value = BuiltinHelpers.method(intrinsics, "JSON.parse", 2) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                val value = try {
                    // 用括号包裹，确保顶层对象被解析为对象字面量而不是块语句
                    val program = Parser(Lexer("($text)").tokenize()).parseProgram()
                    val statement = program.body.singleOrNull()
                    if (statement !is ExpressionStatement) {
                        interpreter.syntaxError("Unexpected JSON token")
                    }
                    interpreter.evaluateStandalone(statement.expression)
                } catch (error: LexerException) {
                    interpreter.syntaxError(error.message ?: "Invalid JSON")
                } catch (error: ParseException) {
                    interpreter.syntaxError(error.message ?: "Invalid JSON")
                }

                val reviver = args.getOrNull(1)
                if (reviver != null && interpreter.isCallable(reviver)) {
                    val holder = JSObject(prototype = intrinsics.objectPrototype)
                    holder.defineDataProperty(PropertyKey.Str(""), value)
                    internalize(interpreter, holder, "", reviver)
                } else {
                    value
                }
            },
        )

        json.defineDataProperty(
            key = PropertyKey.Str("stringify"),
            value = BuiltinHelpers.method(intrinsics, "JSON.stringify", 3) { interpreter, _, args ->
                val value = args.getOrNull(0) ?: JSUndefined
                val replacer = args.getOrNull(1)
                val spaceValue = args.getOrNull(2)

                val propertyList = buildPropertyList(replacer)
                val gap = buildGap(interpreter, spaceValue)

                val holder = JSObject(prototype = intrinsics.objectPrototype)
                holder.defineDataProperty(PropertyKey.Str(""), value)
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
    ): JSValue {
        val value = interpreter.getV(holder, PropertyKey.Str(key))

        if (value is JSObject) {
            if (value is JSArray) {
                for (index in 0 until value.length) {
                    val elementKey = index.toString()
                    val newElement = internalize(interpreter, value, elementKey, reviver)
                    if (newElement is JSUndefined) {
                        value.deleteOwnProperty(PropertyKey.Str(elementKey))
                    } else {
                        value.defineDataProperty(PropertyKey.Str(elementKey), newElement)
                    }
                }
            } else {
                for (propertyKey in value.ownPropertyKeys()) {
                    if (propertyKey !is PropertyKey.Str) continue
                    if (value.getOwnProperty(propertyKey)?.enumerable != true) continue
                    val newValue = internalize(interpreter, value, propertyKey.value, reviver)
                    if (newValue is JSUndefined) {
                        value.deleteOwnProperty(propertyKey)
                    } else {
                        value.defineDataProperty(propertyKey, newValue)
                    }
                }
            }
        }

        return interpreter.callFunction(reviver, holder, listOf(JSString(key), value))
    }

    /** replacer 为数组时构造属性白名单。 */
    private suspend fun buildPropertyList(replacer: JSValue?): List<String>? {
        if (replacer !is JSArray) return null
        val keys = mutableListOf<String>()
        for (index in 0 until replacer.length) {
            val element = replacer.getElement(index) ?: continue
            val text = when (element) {
                is JSString -> element.value
                is JSNumber -> NumberFormatting.toString(element.value)
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

        // toJSON
        if (value is JSObject) {
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

    private suspend fun serializeValue(
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
        is JSString -> quote(value.value)
        is JSSymbol -> null
        is JSFunction, is JSNativeFunction -> null

        is JSArray -> {
            if (!seen.add(value)) {
                interpreter.typeError("Converting circular structure to JSON")
            }
            try {
                val childIndent = indent + gap
                val parts = ArrayList<String>(value.length)
                for (index in 0 until value.length) {
                    val serialized = serializeProperty(
                        interpreter = interpreter,
                        holder = value,
                        key = index.toString(),
                        replacer = replacer,
                        propertyList = propertyList,
                        gap = gap,
                        indent = childIndent,
                        seen = seen,
                    )
                    parts.add(serialized ?: "null")
                }
                if (gap.isEmpty() || parts.isEmpty()) {
                    "[${parts.joinToString(",")}]"
                } else {
                    "[\n$childIndent${parts.joinToString(",\n$childIndent")}\n$indent]"
                }
            } finally {
                seen.remove(value)
            }
        }

        is JSObject -> {
            if (!seen.add(value)) {
                interpreter.typeError("Converting circular structure to JSON")
            }
            try {
                val keys = propertyList ?: value.ownPropertyKeys()
                    .filterIsInstance<PropertyKey.Str>()
                    .filter { value.getOwnProperty(it)?.enumerable == true }
                    .map { it.value }
                val childIndent = indent + gap
                val parts = ArrayList<String>()
                for (propertyKey in keys) {
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
                    parts.add("${quote(propertyKey)}$separator$serialized")
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

    /** JSON 字符串转义。 */
    private fun quote(text: String): String {
        val builder = StringBuilder(text.length + 2)
        builder.append('"')
        for (c in text) {
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
                    } else {
                        builder.append(c)
                    }
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
