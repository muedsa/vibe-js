package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSBoolean
import com.muedsa.js.runtime.value.JSBoxedPrimitive
import com.muedsa.js.runtime.value.JSDate
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSSymbol
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.NumberFormatting
import com.muedsa.js.runtime.value.PropertyKey

/** ToPrimitive 的偏好类型。 */
enum class PreferredType {
    DEFAULT,
    STRING,
    NUMBER,
}

/**
 * ECMAScript 类型转换集中实现。
 *
 * 把这些规则集中在一个类里，避免像旧实现那样在 Interpreter、内建对象、
 * JSON、比较运算中各自实现一套导致行为不一致。
 */
class Conversions(private val interpreter: Interpreter) {

    /**
     * ToPrimitive：对象会依次调用 valueOf / toString（顺序取决于 hint）。
     */
    suspend fun toPrimitive(value: JSValue, hint: PreferredType = PreferredType.DEFAULT): JSValue {
        if (value.isPrimitive) return value

        // 原样把 hint 传给 @@toPrimitive；Date 自身的 @@toPrimitive 负责 default→string
        val effectiveHint = hint

        // Symbol.toPrimitive 优先
        if (value is JSObject) {
            val exotic = interpreter.getV(
                value,
                PropertyKey.Sym(interpreter.intrinsics.symbolToPrimitive),
            )
            if (exotic !is JSUndefined && exotic !is JSNull && !interpreter.isCallable(exotic)) {
                interpreter.typeError("@@toPrimitive must be a function")
            }
            if (interpreter.isCallable(exotic)) {
                val hintName = when (effectiveHint) {
                    PreferredType.STRING -> "string"
                    PreferredType.NUMBER -> "number"
                    PreferredType.DEFAULT -> "default"
                }
                val result = interpreter.callFunction(
                    exotic,
                    value,
                    listOf(JSString(hintName)),
                )
                if (result.isPrimitive) return result
                interpreter.typeError("Cannot convert object to primitive value")
            }
        }

        val objectValue = value as? JSObject
            ?: interpreter.typeError("Cannot convert value to primitive")

        val methodNames = if (effectiveHint == PreferredType.STRING) {
            listOf("toString", "valueOf")
        } else {
            listOf("valueOf", "toString")
        }

        for (name in methodNames) {
            val method = interpreter.getV(objectValue, PropertyKey.Str(name))
            if (interpreter.isCallable(method)) {
                val result = interpreter.callFunction(method, objectValue, emptyList())
                if (result.isPrimitive) return result
            }
        }

        interpreter.typeError("Cannot convert object to primitive value")
    }

    /**
     * ToNumber。
     */
    suspend fun toNumber(value: JSValue): Double = when (value) {
        is JSNumber -> value.value
        is JSString -> value.toPrimitiveNumber()
        is JSBoolean -> value.toPrimitiveNumber()
        is JSNull -> 0.0
        is JSUndefined -> Double.NaN
        is JSSymbol -> interpreter.typeError("Cannot convert a Symbol value to a number")
        is JSObject -> toNumber(toPrimitive(value, PreferredType.NUMBER))
    }

    /**
     * ToString。
     */
    suspend fun toString(value: JSValue): String = when (value) {
        is JSString -> value.value
        is JSNumber -> NumberFormatting.toString(value.value)
        is JSBoolean -> value.value.toString()
        is JSNull -> "null"
        is JSUndefined -> "undefined"
        is JSSymbol -> interpreter.typeError("Cannot convert a Symbol value to a string")
        is JSObject -> toString(toPrimitive(value, PreferredType.STRING))
    }

    /** ToBoolean 不需要挂起（对象恒为 true）。 */
    fun toBoolean(value: JSValue): Boolean = value.toPrimitiveBoolean()

    /** ToPropertyKey。 */
    suspend fun toPropertyKey(value: JSValue): PropertyKey {
        // 先 ToPrimitive(string)，Symbol 结果保留为 Symbol 键（不能 ToString）
        val key = toPrimitive(value, PreferredType.STRING)
        return if (key is JSSymbol) PropertyKey.Sym(key) else PropertyKey.Str(toString(key))
    }

    /**
     * ToObject：原始值包装为对象（用于 Object.prototype 方法等场景）。
     */
    suspend fun toObject(value: JSValue): JSObject {
        return when (value) {
            is JSObject -> value
            is JSString, is JSNumber, is JSBoolean, is JSSymbol ->
                interpreter.wrapPrimitive(value)

            is JSNull, is JSUndefined ->
                interpreter.typeError("Cannot convert undefined or null to object")
        }
    }

    /**
     * ToUint32：按规范对 2^32 取模。
     */
    suspend fun toUint32(value: JSValue): Long {
        val number = toNumber(value)
        if (number.isNaN() || number.isInfinite() || number == 0.0) return 0L
        val truncated = kotlin.math.truncate(number)
        val modulo = truncated % 4294967296.0
        val normalized = if (modulo < 0) modulo + 4294967296.0 else modulo
        return normalized.toLong() and 0xFFFFFFFFL
    }

    /** ToInt32：取 ToUint32 的低 32 位并按有符号解释。 */
    suspend fun toInt32(value: JSValue): Int = toUint32(value).toInt()

    /** 严格相等（===）。 */
    fun strictEquals(a: JSValue, b: JSValue): Boolean = when {
        a is JSUndefined -> b is JSUndefined
        a is JSNull -> b is JSNull
        a is JSBoolean -> b is JSBoolean && a.value == b.value
        a is JSNumber -> b is JSNumber && a.value == b.value // NaN !== NaN
        a is JSString -> b is JSString && a.value == b.value
        a is JSSymbol -> a === b
        a is JSObject -> a === b
        else -> false
    }

    /** SameValueZero（Array.prototype.includes / Map 键比较使用）。 */
    fun sameValueZero(a: JSValue, b: JSValue): Boolean {
        if (a is JSNumber && b is JSNumber && a.value.isNaN() && b.value.isNaN()) return true
        return strictEquals(a, b)
    }

    /**
     * 抽象相等（==）。
     */
    suspend fun abstractEquals(a: JSValue, b: JSValue): Boolean {
        // 类型相同：按严格相等处理（对象按引用比较）
        if (typeTag(a) == typeTag(b)) return strictEquals(a, b)

        return when {
            a is JSNull && b is JSUndefined -> true
            a is JSUndefined && b is JSNull -> true
            // 规范：null/undefined 与其他类型（含对象）宽松比较直接为 false，不做 ToPrimitive
            a is JSNull || a is JSUndefined || b is JSNull || b is JSUndefined -> false
            a is JSNumber && b is JSString -> a.value == b.toPrimitiveNumber()
            a is JSString && b is JSNumber -> a.toPrimitiveNumber() == b.value
            a is JSBoolean -> abstractEquals(JSNumber(if (a.value) 1.0 else 0.0), b)
            b is JSBoolean -> abstractEquals(a, JSNumber(if (b.value) 1.0 else 0.0))
            a is JSObject && b.isPrimitive -> abstractEquals(toPrimitive(a), b)
            b is JSObject && a.isPrimitive -> abstractEquals(a, toPrimitive(b))
            else -> false
        }
    }

    /** 类型标签，用于抽象相等的前置判断。 */
    private fun typeTag(value: JSValue): Int = when (value) {
        is JSUndefined -> 0
        is JSNull -> 1
        is JSBoolean -> 2
        is JSNumber -> 3
        is JSString -> 4
        is JSSymbol -> 5
        is JSObject -> 6
    }
}
