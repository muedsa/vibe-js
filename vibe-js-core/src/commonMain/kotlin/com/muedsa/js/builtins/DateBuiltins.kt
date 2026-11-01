package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.PreferredType
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSDate
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
import com.muedsa.js.runtime.value.JSObject
import com.muedsa.js.runtime.value.JSString
import com.muedsa.js.runtime.value.JSUndefined
import com.muedsa.js.runtime.value.JSValue
import com.muedsa.js.runtime.value.PropertyKey

/**
 * Date 构造器与 Date.prototype。
 *
 * 本地时间方法当前按 UTC 实现（common 代码无法获取时区信息），
 * 时区偏移固定为 0；时间源来自宿主注入的 Clock。
 */
object DateBuiltins {

    fun install(registry: BuiltinRegistry) {
        val intrinsics = registry.intrinsics
        val datePrototype = intrinsics.datePrototype

        val dateConstructor = BuiltinHelpers.constructor(
            intrinsics = intrinsics,
            name = "Date",
            length = 7,
            prototypeObject = datePrototype,
        ) { interpreter, _, args ->
            if (!interpreter.nativeConstructCall) {
                // Date() 直接调用返回当前时间字符串
                JSString(localString(interpreter, interpreter.currentTimeMillis()))
            } else {
                val time = when {
                    args.isEmpty() -> interpreter.currentTimeMillis()
                    args.size == 1 -> parseDateValue(interpreter, args[0])
                    else -> {
                        val year = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
                        val month = interpreter.toNumber(args.getOrElse(1) { JSUndefined })
                        val day = args.getOrNull(2)?.let { interpreter.toNumber(it) } ?: 1.0
                        val hours = args.getOrNull(3)?.let { interpreter.toNumber(it) } ?: 0.0
                        val minutes = args.getOrNull(4)?.let { interpreter.toNumber(it) } ?: 0.0
                        val seconds = args.getOrNull(5)?.let { interpreter.toNumber(it) } ?: 0.0
                        val milliseconds = args.getOrNull(6)?.let { interpreter.toNumber(it) } ?: 0.0
                        val dayNumber = DateMath.makeDay(
                            DateMath.normalizeYear(year),
                            month,
                            day,
                        )
                        val wallTime = DateMath.makeDate(
                            dayNumber,
                            DateMath.makeTimeDouble(hours, minutes, seconds, milliseconds),
                        )
                        toUtcFromLocal(interpreter, DateMath.timeClip(wallTime))
                    }
                }
                JSDate(DateMath.timeClip(time), datePrototype)
            }
        }

        datePrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = dateConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )

        installStaticMethods(intrinsics, dateConstructor)
        installPrototypeMethods(intrinsics, datePrototype)

        // ES2015 Date.prototype[@@toPrimitive]：default 按 string 顺序转换
        datePrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToPrimitive),
            value = BuiltinHelpers.method(intrinsics, "[Symbol.toPrimitive]", 1) { interpreter, thisValue, args ->
                if (thisValue !is JSObject) {
                    interpreter.typeError("Date.prototype[Symbol.toPrimitive] called on non-object")
                }
                val hint = (args.getOrElse(0) { JSUndefined } as? JSString)?.value
                val order = when (hint) {
                    "string", "default" -> listOf("toString", "valueOf")
                    "number" -> listOf("valueOf", "toString")
                    else -> interpreter.typeError("Invalid hint for Date.prototype[Symbol.toPrimitive]")
                }
                for (name in order) {
                    val method = interpreter.getV(thisValue, PropertyKey.Str(name))
                    if (interpreter.isCallable(method)) {
                        val result = interpreter.callFunction(method, thisValue, emptyList())
                        if (result !is JSObject) return@method result
                    }
                }
                interpreter.typeError("Cannot convert object to primitive value")
            },
            writable = false,
        )

        registry.defineGlobal("Date", dateConstructor)
    }

    private fun installStaticMethods(
        intrinsics: com.muedsa.js.runtime.Intrinsics,
        dateConstructor: com.muedsa.js.runtime.value.JSNativeFunction,
    ) {
        dateConstructor.defineDataProperty(
            key = PropertyKey.Str("now"),
            value = BuiltinHelpers.method(intrinsics, "now", 0) { interpreter, _, _ ->
                JSNumber(interpreter.currentTimeMillis())
            },
        )

        dateConstructor.defineDataProperty(
            key = PropertyKey.Str("parse"),
            value = BuiltinHelpers.method(intrinsics, "parse", 1) { interpreter, _, args ->
                val text = interpreter.toStringValue(args.getOrElse(0) { JSUndefined })
                JSNumber(DateMath.parseDateString(text) { time -> offsetMillis(interpreter, time) })
            },
        )

        dateConstructor.defineDataProperty(
            key = PropertyKey.Str("UTC"),
            value = BuiltinHelpers.method(intrinsics, "UTC", 7) { interpreter, _, args ->
                val year = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
                // 规范：month 未提供时取 0，date 未提供时取 1，其余未提供取 0
                val month = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: 0.0
                val day = args.getOrNull(2)?.let { interpreter.toNumber(it) } ?: 1.0
                val hours = args.getOrNull(3)?.let { interpreter.toNumber(it) } ?: 0.0
                val minutes = args.getOrNull(4)?.let { interpreter.toNumber(it) } ?: 0.0
                val seconds = args.getOrNull(5)?.let { interpreter.toNumber(it) } ?: 0.0
                val milliseconds = args.getOrNull(6)?.let { interpreter.toNumber(it) } ?: 0.0
                val dayNumber = DateMath.makeDay(DateMath.normalizeYear(year), month, day)
                val timeValue = DateMath.makeDate(
                    dayNumber,
                    DateMath.makeTimeDouble(hours, minutes, seconds, milliseconds),
                )
                JSNumber(DateMath.timeClip(timeValue))
            },
        )
    }

    private fun installPrototypeMethods(
        intrinsics: com.muedsa.js.runtime.Intrinsics,
        datePrototype: com.muedsa.js.runtime.value.JSObject,
    ) {
        fun method(name: String, length: Int, body: suspend (Interpreter, JSValue, List<JSValue>) -> JSValue) {
            datePrototype.defineDataProperty(
                key = PropertyKey.Str(name),
                value = BuiltinHelpers.method(intrinsics, name, length, body),
            )
        }

        method("valueOf", 0) { _, thisValue, _ -> JSNumber(requireDate(thisValue).timeValue) }
        method("getTime", 0) { _, thisValue, _ -> JSNumber(requireDate(thisValue).timeValue) }
        method("getTimezoneOffset", 0) { interpreter, thisValue, _ ->
            val date = requireDate(thisValue)
            // 无效日期返回 NaN；返回分钟数（西为正）
            if (DateMath.parts(date.timeValue) == null) {
                JSNumber.NAN
            } else {
                val offset = offsetMillis(interpreter, date.timeValue)
                JSNumber(if (offset == 0.0) 0.0 else -offset / DateMath.MS_PER_MINUTE)
            }
        }

        fun getter(name: String, local: Boolean = false, selector: (DateMath.Parts) -> Double) {
            method(name, 0) { interpreter, thisValue, _ ->
                val date = requireDate(thisValue)
                val parts = if (local) {
                    localParts(interpreter, date.timeValue)
                } else {
                    DateMath.parts(date.timeValue)
                } ?: return@method JSNumber.NAN
                JSNumber(selector(parts))
            }
        }

        getter("getFullYear", local = true) { it.year.toDouble() }
        getter("getUTCFullYear") { it.year.toDouble() }
        getter("getMonth", local = true) { it.month.toDouble() }
        getter("getUTCMonth") { it.month.toDouble() }
        getter("getDate", local = true) { it.day.toDouble() }
        getter("getUTCDate") { it.day.toDouble() }
        getter("getDay", local = true) { it.dayOfWeek.toDouble() }
        getter("getUTCDay") { it.dayOfWeek.toDouble() }
        getter("getHours", local = true) { it.hours.toDouble() }
        getter("getUTCHours") { it.hours.toDouble() }
        getter("getMinutes", local = true) { it.minutes.toDouble() }
        getter("getUTCMinutes") { it.minutes.toDouble() }
        getter("getSeconds", local = true) { it.seconds.toDouble() }
        getter("getUTCSeconds") { it.seconds.toDouble() }
        getter("getMilliseconds", local = true) { it.milliseconds.toDouble() }
        getter("getUTCMilliseconds") { it.milliseconds.toDouble() }

        // 遗留方法
        method("getYear", 0) { interpreter, thisValue, _ ->
            val date = requireDate(thisValue)
            val parts = localParts(interpreter, date.timeValue) ?: return@method JSNumber.NAN
            JSNumber((parts.year - 1900).toDouble())
        }

        method("setTime", 1) { interpreter, thisValue, args ->
            val date = requireDate(thisValue)
            date.timeValue = DateMath.timeClip(interpreter.toNumber(args.getOrElse(0) { JSUndefined }))
            JSNumber(date.timeValue)
        }

        /**
         * 日期字段 setter 的公共包装。
         *
         * 规范顺序：先读取 date 内部时间，再强制转换参数；内部时间为 NaN 时，
         * 除 setFullYear/setUTCFullYear（以 +0 为基准）外结果一律为 NaN。
         * [useZeroBase] 对应 setFullYear/setUTCFullYear。
         */
        fun setter(
            name: String,
            length: Int = 1,
            useZeroBase: Boolean = false,
            local: Boolean = false,
            body: suspend (Interpreter, List<JSValue>, DateMath.Parts) -> Double?,
        ) {
            method(name, length) { interpreter, thisValue, args ->
                val date = requireDate(thisValue)
                val parts = if (local) {
                    localParts(interpreter, date.timeValue)
                } else {
                    DateMath.parts(date.timeValue)
                }
                val epochParts = DateMath.Parts(1970, 0, 1, 0, 0, 0, 0, 4)
                val base = parts ?: if (local) {
                    localParts(interpreter, 0.0) ?: epochParts
                } else {
                    epochParts
                }
                // 先完成参数的 ToNumber（可能抛错或有副作用），再按日期有效性决定结果。
                // 参数无效时：原时间有效（或 setFullYear 系以 +0 为基准）需把内部时间写为 NaN；
                // 原时间已为 NaN 的普通 setter 按规范直接返回 NaN，不覆盖 valueOf 副作用。
                val time = body(interpreter, args, base)
                if (time == null) {
                    if (parts != null || useZeroBase) {
                        date.timeValue = Double.NaN
                    }
                    return@method JSNumber.NAN
                }
                if (parts == null && !useZeroBase) return@method JSNumber.NAN
                val clipped = if (local) toUtcFromLocal(interpreter, time) else DateMath.timeClip(time)
                date.timeValue = clipped
                JSNumber(clipped)
            }
        }

        setter("setFullYear", length = 3, useZeroBase = true, local = true) { interpreter, args, base ->
            // 规范要求先完成全部可选参数的 ToNumber，再统一判断结果
            val year = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val month = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.month.toDouble()
            val day = args.getOrNull(2)?.let { interpreter.toNumber(it) } ?: base.day.toDouble()
            if (year.isNaN()) return@setter null
            DateMath.makeDate(
                // setFullYear 不应用 0..99 -> 1900+ 的构造器映射
                DateMath.makeDay(year, month, day),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    base.milliseconds.toDouble(),
                ),
            )
        }
        setter("setUTCFullYear", length = 3, useZeroBase = true) { interpreter, args, base ->
            val year = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val month = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.month.toDouble()
            val day = args.getOrNull(2)?.let { interpreter.toNumber(it) } ?: base.day.toDouble()
            if (year.isNaN()) return@setter null
            DateMath.makeDate(
                // setFullYear 不应用 0..99 -> 1900+ 的构造器映射
                DateMath.makeDay(year, month, day),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    base.milliseconds.toDouble(),
                ),
            )
        }
        setter("setMonth", length = 2, local = true) { interpreter, args, base ->
            val month = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val day = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.day.toDouble()
            if (month.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), month, day),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    base.milliseconds.toDouble(),
                ),
            )
        }
        setter("setUTCMonth", length = 2) { interpreter, args, base ->
            val month = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val day = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.day.toDouble()
            if (month.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), month, day),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    base.milliseconds.toDouble(),
                ),
            )
        }
        setter("setDate", length = 1, local = true) { interpreter, args, base ->
            val day = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (day.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), day),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    base.milliseconds.toDouble(),
                ),
            )
        }
        setter("setUTCDate", length = 1) { interpreter, args, base ->
            val day = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (day.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), day),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    base.milliseconds.toDouble(),
                ),
            )
        }
        setter("setHours", length = 4, local = true) { interpreter, args, base ->
            val hours = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val minutes = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.minutes.toDouble()
            val seconds = args.getOrNull(2)?.let { interpreter.toNumber(it) } ?: base.seconds.toDouble()
            val milliseconds =
                args.getOrNull(3)?.let { interpreter.toNumber(it) } ?: base.milliseconds.toDouble()
            if (hours.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(hours, minutes, seconds, milliseconds),
            )
        }
        setter("setUTCHours", length = 4) { interpreter, args, base ->
            val hours = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val minutes = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.minutes.toDouble()
            val seconds = args.getOrNull(2)?.let { interpreter.toNumber(it) } ?: base.seconds.toDouble()
            val milliseconds =
                args.getOrNull(3)?.let { interpreter.toNumber(it) } ?: base.milliseconds.toDouble()
            if (hours.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(hours, minutes, seconds, milliseconds),
            )
        }
        setter("setMinutes", length = 3, local = true) { interpreter, args, base ->
            val minutes = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val seconds = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.seconds.toDouble()
            val milliseconds =
                args.getOrNull(2)?.let { interpreter.toNumber(it) } ?: base.milliseconds.toDouble()
            if (minutes.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    minutes,
                    seconds,
                    milliseconds,
                ),
            )
        }
        setter("setUTCMinutes", length = 3) { interpreter, args, base ->
            val minutes = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val seconds = args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.seconds.toDouble()
            val milliseconds =
                args.getOrNull(2)?.let { interpreter.toNumber(it) } ?: base.milliseconds.toDouble()
            if (minutes.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    minutes,
                    seconds,
                    milliseconds,
                ),
            )
        }
        setter("setSeconds", length = 2, local = true) { interpreter, args, base ->
            val seconds = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val milliseconds =
                args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.milliseconds.toDouble()
            if (seconds.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    seconds,
                    milliseconds,
                ),
            )
        }
        setter("setUTCSeconds", length = 2) { interpreter, args, base ->
            val seconds = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            val milliseconds =
                args.getOrNull(1)?.let { interpreter.toNumber(it) } ?: base.milliseconds.toDouble()
            if (seconds.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    seconds,
                    milliseconds,
                ),
            )
        }
        setter("setMilliseconds", length = 1, local = true) { interpreter, args, base ->
            val milliseconds = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (milliseconds.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    milliseconds,
                ),
            )
        }
        setter("setUTCMilliseconds", length = 1) { interpreter, args, base ->
            val milliseconds = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (milliseconds.isNaN()) return@setter null
            DateMath.makeDate(
                DateMath.makeDay(base.year.toDouble(), base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    milliseconds,
                ),
            )
        }
        // Annex B setYear：t 为 NaN 时以 +0 为基准；参数为 NaN 时结果与内部时间均变为 NaN；
        // MakeFullYear 对 0..99 映射到 1900+
        setter("setYear", length = 1, useZeroBase = true, local = true) { interpreter, args, base ->
            val yearNumber = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (yearNumber.isNaN()) return@setter null
            val integer = kotlin.math.truncate(yearNumber)
            val fullYear = if (integer >= 0.0 && integer <= 99.0) 1900.0 + integer else integer
            DateMath.makeDate(
                DateMath.makeDay(fullYear, base.month.toDouble(), base.day.toDouble()),
                DateMath.makeTimeDouble(
                    base.hours.toDouble(),
                    base.minutes.toDouble(),
                    base.seconds.toDouble(),
                    base.milliseconds.toDouble(),
                ),
            )
        }

        method("toISOString", 0) { interpreter, thisValue, _ ->
            val date = requireDate(thisValue)
            JSString(isoString(date.timeValue) ?: interpreter.rangeError("Invalid time value"))
        }

        method("toJSON", 1) { interpreter, thisValue, _ ->
            if (thisValue is JSNull || thisValue is JSUndefined) {
                interpreter.typeError("Date.prototype.toJSON called on null or undefined")
            }
            val objectValue = interpreter.toObject(thisValue)
            val primitive = interpreter.conversions.toPrimitive(objectValue, PreferredType.NUMBER)
            if (primitive is JSNumber && !primitive.value.isFinite()) {
                JSNull
            } else {
                val toIsoString = interpreter.getV(objectValue, PropertyKey.Str("toISOString"))
                if (!interpreter.isCallable(toIsoString)) {
                    interpreter.typeError("toISOString is not a function")
                }
                interpreter.callFunction(toIsoString, objectValue, emptyList())
            }
        }

        method("toString", 0) { interpreter, thisValue, _ ->
            JSString(localString(interpreter, requireDate(thisValue).timeValue))
        }
        method("toLocaleString", 0) { interpreter, thisValue, _ ->
            JSString(localString(interpreter, requireDate(thisValue).timeValue))
        }
        method("toUTCString", 0) { _, thisValue, _ ->
            JSString(humanString(requireDate(thisValue).timeValue))
        }
        method("toGMTString", 0) { _, thisValue, _ ->
            JSString(humanString(requireDate(thisValue).timeValue))
        }
        method("toDateString", 0) { interpreter, thisValue, _ ->
            val date = requireDate(thisValue)
            val parts = localParts(interpreter, date.timeValue) ?: return@method JSString("Invalid Date")
            JSString(
                "${WEEKDAYS[parts.dayOfWeek]} ${MONTHS[parts.month]} " +
                    "${pad(parts.day, 2)} ${formatYear(parts.year)}"
            )
        }
        method("toTimeString", 0) { interpreter, thisValue, _ ->
            val date = requireDate(thisValue)
            val parts = localParts(interpreter, date.timeValue) ?: return@method JSString("Invalid Date")
            JSString(
                "${pad(parts.hours, 2)}:${pad(parts.minutes, 2)}:${pad(parts.seconds, 2)} " +
                    timeZoneText(interpreter, date.timeValue)
            )
        }

        method("toLocaleDateString", 0) { interpreter, thisValue, _ ->
            val parts = localParts(interpreter, requireDate(thisValue).timeValue)
                ?: return@method JSString("Invalid Date")
            JSString(
                "${WEEKDAYS[parts.dayOfWeek]} ${MONTHS[parts.month]} " +
                    "${pad(parts.day, 2)} ${formatYear(parts.year)}"
            )
        }
        method("toLocaleTimeString", 0) { interpreter, thisValue, _ ->
            val date = requireDate(thisValue)
            val parts = localParts(interpreter, date.timeValue)
                ?: return@method JSString("Invalid Date")
            JSString(
                "${pad(parts.hours, 2)}:${pad(parts.minutes, 2)}:${pad(parts.seconds, 2)} " +
                    timeZoneText(interpreter, date.timeValue)
            )
        }
    }

    private fun requireDate(thisValue: JSValue): JSDate =
        thisValue as? JSDate ?: throw JSException(
            JSError("TypeError", "Date method called on incompatible receiver", null)
        )

    private suspend fun parseDateValue(interpreter: Interpreter, value: JSValue): Double = when (value) {
        is JSDate -> value.timeValue
        is JSString -> DateMath.parseDateString(value.value) { time -> offsetMillis(interpreter, time) }
        is JSObject -> {
            // Date(value)：对象先按 default hint 做 ToPrimitive，字符串则解析日期
            val primitive = interpreter.conversions.toPrimitive(value, PreferredType.DEFAULT)
            if (primitive is JSString) {
                DateMath.parseDateString(primitive.value) { time -> offsetMillis(interpreter, time) }
            } else {
                interpreter.toNumber(primitive)
            }
        }

        else -> interpreter.toNumber(value)
    }

    private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

    /** DateString 的年份：负年份保留符号并至少补足 4 位。 */
    private fun formatYear(year: Int): String =
        if (year < 0) "-" + pad(-year, 4) else pad(year, 4)

    /** toUTCString/toGMTString 的格式：Wdy, DD Mon YYYY HH:mm:ss GMT。 */
    private fun humanString(time: Double): String {
        val parts = DateMath.parts(time) ?: return "Invalid Date"
        return "${WEEKDAYS[parts.dayOfWeek]}, ${pad(parts.day, 2)} ${MONTHS[parts.month]} " +
            "${formatYear(parts.year)} ${pad(parts.hours, 2)}:${pad(parts.minutes, 2)}:" +
            "${pad(parts.seconds, 2)} GMT"
    }

    /** toString 的本地时间格式（时区偏移来自宿主，默认 UTC）。 */
    private fun localString(interpreter: Interpreter, time: Double): String {
        val parts = localParts(interpreter, time) ?: return "Invalid Date"
        return "${WEEKDAYS[parts.dayOfWeek]} ${MONTHS[parts.month]} " +
            "${pad(parts.day, 2)} ${formatYear(parts.year)} " +
            "${pad(parts.hours, 2)}:${pad(parts.minutes, 2)}:${pad(parts.seconds, 2)} " +
            timeZoneText(interpreter, time)
    }

    /** 指定时刻的本地时区偏移（毫秒）；时间非有限值时视为 0。 */
    private fun offsetMillis(interpreter: Interpreter, time: Double): Double =
        if (time.isFinite()) {
            interpreter.context.host.utcOffsetMillis(time.toLong()).toDouble()
        } else {
            0.0
        }

    /** 本地墙钟时间对应的 UTC 分量。 */
    private fun localParts(interpreter: Interpreter, time: Double): DateMath.Parts? =
        DateMath.parts(time + offsetMillis(interpreter, time))

    /** 把“本地墙钟的 UTC 表示”转换为真实 UTC 时刻（两轮修正支持随时刻变化的偏移）。 */
    private fun toUtcFromLocal(interpreter: Interpreter, wallTime: Double): Double {
        var utc = wallTime - offsetMillis(interpreter, wallTime)
        utc = wallTime - offsetMillis(interpreter, utc)
        return DateMath.timeClip(utc)
    }

    /** toString 的时区后缀；默认宿主显示与旧行为一致的 UTC 名称。 */
    private fun timeZoneText(interpreter: Interpreter, time: Double): String {
        val offsetMinutes = (offsetMillis(interpreter, time) / DateMath.MS_PER_MINUTE).toInt()
        if (offsetMinutes == 0) return "GMT+0000 (Coordinated Universal Time)"
        val sign = if (offsetMinutes > 0) '+' else '-'
        val absMinutes = kotlin.math.abs(offsetMinutes)
        val hours = pad(absMinutes / 60, 2)
        val minutes = pad(absMinutes % 60, 2)
        return "GMT$sign$hours$minutes (UTC$sign$hours:$minutes)"
    }

    private fun isoString(time: Double): String? {
        val parts = DateMath.parts(time) ?: return null
        val year = when {
            parts.year in 0..9999 -> pad(parts.year, 4)
            // 扩展年份（ISO 8601）：正负号 + 至少 6 位数字
            parts.year > 9999 -> "+${pad(parts.year, 6)}"
            else -> "-${pad(-parts.year, 6)}"
        }
        return "$year-${pad(parts.month + 1, 2)}-${pad(parts.day, 2)}T" +
            "${pad(parts.hours, 2)}:${pad(parts.minutes, 2)}:${pad(parts.seconds, 2)}." +
            "${pad(parts.milliseconds, 3)}Z"
    }
}
