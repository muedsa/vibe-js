package com.muedsa.js.runtime

import com.muedsa.js.runtime.value.JSBigInt
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
import com.muedsa.js.runtime.value.trimJsWhitespace

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
     *
     * 注意 BigInt 按规范不能隐式转换为 Number（`1n + 1` 等由 ToNumeric 处理），
     * 显式转换 `Number(1n)` 在 Number 构造器里单独实现。
     */
    suspend fun toNumber(value: JSValue): Double = when (value) {
        is JSNumber -> value.value
        is JSString -> value.toPrimitiveNumber()
        is JSBoolean -> value.toPrimitiveNumber()
        is JSNull -> 0.0
        is JSUndefined -> Double.NaN
        is JSSymbol -> interpreter.typeError("Cannot convert a Symbol value to a number")
        is JSBigInt -> interpreter.typeError("Cannot convert a BigInt value to a number")
        is JSObject -> toNumber(toPrimitive(value, PreferredType.NUMBER))
    }

    /**
     * ToNumeric：返回 Number 或 BigInt 原始值。
     *
     * 对象先按 number 提示 ToPrimitive，再对结果做 ToNumber；
     * BigInt 原样返回，供算术/位运算判断类型是否一致。
     */
    suspend fun toNumeric(value: JSValue): JSValue =
        toNumericFromPrimitive(toPrimitive(value, PreferredType.NUMBER))

    /** 已完成 ToPrimitive 的 ToNumeric 入口（关系比较等需要自行控制转换顺序）。 */
    suspend fun toNumericFromPrimitive(value: JSValue): JSValue = when (value) {
        is JSBigInt -> value
        is JSNumber -> value
        is JSObject -> toNumeric(value)
        else -> JSNumber.of(toNumber(value))
    }

    /**
     * ToBigInt：把值转换为 BigInt。
     *
     * - BigInt 原样返回；
     * - Boolean 转 1n/0n；
     * - String 按 StringToBigInt 解析（空串为 0n），非法格式抛 SyntaxError；
     * - Number 按规范抛 TypeError（显式转换应使用 `BigInt(n)`）；
     * - undefined/null/Symbol 抛 TypeError；对象先 ToPrimitive。
     */
    suspend fun toBigInt(value: JSValue): JSBigInt = when (value) {
        is JSBigInt -> value
        is JSBoolean -> if (value.value) JSBigInt.ONE else JSBigInt.ZERO
        is JSString -> stringToBigInt(value.value)
            ?: interpreter.syntaxError("Cannot convert ${value.value} to a BigInt")

        is JSNumber -> interpreter.typeError("Cannot convert a Number value to a BigInt")
        is JSNull -> interpreter.typeError("Cannot convert null to a BigInt")
        is JSUndefined -> interpreter.typeError("Cannot convert undefined to a BigInt")
        is JSSymbol -> interpreter.typeError("Cannot convert a Symbol value to a BigInt")
        is JSObject -> toBigInt(toPrimitive(value, PreferredType.NUMBER))
    }

    /**
     * StringToBigInt：先去除首尾空白，空串为 0n；无法解析返回 null。
     */
    fun stringToBigInt(text: String): JSBigInt? {
        val trimmed = text.trimJsWhitespace()
        if (trimmed.isEmpty()) return JSBigInt.ZERO
        return JSBigInt.parse(trimmed)
    }

    /** ToBigInt64：BigInt 模 2^64 后有符号解释。 */
    suspend fun toBigInt64(value: JSValue): JSBigInt = toBigInt(value).toInt64()

    /** ToBigUint64：BigInt 模 2^64 后无符号解释。 */
    suspend fun toBigUint64(value: JSValue): JSBigInt = toBigInt(value).toUint64()

    /**
     * 已完成 ToNumber 后的 ToIntegerOrInfinity：NaN/零归零，有限值向零截断。
     * 接收 Double 可避免调用方在检查 NaN 后重复触发用户 valueOf 的副作用。
     */
    internal fun toIntegerOrInfinity(number: Double): Double = when {
        number.isNaN() || number == 0.0 -> 0.0
        number.isInfinite() -> number
        else -> kotlin.math.truncate(number)
    }

    /** ToLength：仅执行一次数值转换，将长度限制在规范的 0..2^53-1。 */
    internal suspend fun toLength(value: JSValue): Long {
        val integer = toIntegerOrInfinity(toNumber(value))
        return when {
            integer <= 0.0 -> 0L
            integer >= 9007199254740991.0 -> 9007199254740991L
            else -> integer.toLong()
        }
    }

    /**
     * ToIndex：先 ToIntegerOrInfinity，再要求落在 0..2^53-1。
     *
     * 负数与超上限都抛 RangeError（ArrayBuffer 长度、DataView 的
     * byteOffset/byteLength 与逐次访问下标都使用该转换）。
     */
    internal suspend fun toIndex(value: JSValue): Long {
        val integer = toIntegerOrInfinity(toNumber(value))
        if (integer < 0.0 || integer > 9007199254740991.0) {
            interpreter.rangeError("Invalid index: $integer")
        }
        return integer.toLong()
    }

    /**
     * ToString。
     */
    suspend fun toString(value: JSValue): String = when (value) {
        is JSString -> value.value
        is JSNumber -> NumberFormatting.toString(value.value)
        is JSBigInt -> value.toString(10)
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
            is JSString, is JSNumber, is JSBoolean, is JSSymbol, is JSBigInt ->
                interpreter.wrapPrimitive(value)

            is JSNull, is JSUndefined ->
                interpreter.typeError("Cannot convert undefined or null to object")
        }
    }

    /**
     * ToUint32：按规范对 2^32 取模。
     */
    suspend fun toUint32(value: JSValue): Long = toUint32Number(toNumber(value))

    /** ToInt32：取 ToUint32 的低 32 位并按有符号解释。 */
    suspend fun toInt32(value: JSValue): Int = toUint32(value).toInt()

    /** 严格相等（===）。 */
    fun strictEquals(a: JSValue, b: JSValue): Boolean = when {
        a is JSUndefined -> b is JSUndefined
        a is JSNull -> b is JSNull
        a is JSBoolean -> b is JSBoolean && a.value == b.value
        a is JSNumber -> b is JSNumber && a.value == b.value // NaN !== NaN
        a is JSString -> b is JSString && a.value == b.value
        // BigInt 是原始值：按数值比较，而不是对象身份
        a is JSBigInt -> b is JSBigInt && a == b
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
            // BigInt 与 String：使用 StringToBigInt 而不是 ToNumber（"1.0" 不相等）
            a is JSBigInt && b is JSString -> stringToBigInt(b.value)?.let { strictEquals(a, it) } ?: false
            a is JSString && b is JSBigInt -> stringToBigInt(a.value)?.let { strictEquals(it, b) } ?: false
            // BigInt 与 Number：按数学值精确比较（NaN 恒为 false）
            a is JSBigInt && b is JSNumber -> JSBigInt.compareToDouble(a, b.value) == 0
            a is JSNumber && b is JSBigInt -> JSBigInt.compareToDouble(b, a.value) == 0
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
        is JSBigInt -> 6
        is JSObject -> 7
    }
}

/**
 * ToUint32 的数值入口（输入已是 ToNumber 结果）。
 *
 * TypedArray 的非挂起定义路径需要它：值可能已是 JSNumber，但取模规则
 * 必须与 JS 可见路径（[Conversions.toUint32]）完全一致，不能重复实现。
 */
internal fun toUint32Number(number: Double): Long {
    if (number.isNaN() || number.isInfinite() || number == 0.0) return 0L
    val truncated = kotlin.math.truncate(number)
    val modulo = truncated % 4294967296.0
    val normalized = if (modulo < 0) modulo + 4294967296.0 else modulo
    return normalized.toLong() and 0xFFFFFFFFL
}

/** ToInt32 的数值入口（输入已是 ToNumber 结果）。 */
internal fun toInt32Number(number: Double): Int = toUint32Number(number).toInt()
