package com.muedsa.js.builtins

import com.muedsa.js.runtime.BuiltinRegistry
import com.muedsa.js.runtime.Interpreter
import com.muedsa.js.runtime.exception.JSException
import com.muedsa.js.runtime.value.JSDate
import com.muedsa.js.runtime.value.JSError
import com.muedsa.js.runtime.value.JSNull
import com.muedsa.js.runtime.value.JSNumber
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
                JSString(humanString(interpreter.currentTimeMillis()))
            } else {
                val time = when {
                    args.isEmpty() -> interpreter.currentTimeMillis()
                    args.size == 1 -> parseDateValue(interpreter, args[0])
                    else -> {
                        val year = interpreter.toNumber(args.getOrElse(0) { JSUndefined }).toInt()
                        val month = interpreter.toNumber(args.getOrElse(1) { JSUndefined }).toInt()
                        val day = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: 1
                        val hours = args.getOrNull(3)?.let { interpreter.toNumber(it).toInt() } ?: 0
                        val minutes = args.getOrNull(4)?.let { interpreter.toNumber(it).toInt() } ?: 0
                        val seconds = args.getOrNull(5)?.let { interpreter.toNumber(it).toInt() } ?: 0
                        val milliseconds = args.getOrNull(6)?.let { interpreter.toNumber(it).toInt() } ?: 0
                        DateMath.makeTime(year, month, day, hours, minutes, seconds, milliseconds)
                    }
                }
                JSDate(time, datePrototype)
            }
        }

        datePrototype.defineDataProperty(
            key = PropertyKey.CONSTRUCTOR,
            value = dateConstructor,
            writable = true,
            enumerable = false,
            configurable = true,
        )
        datePrototype.defineDataProperty(
            key = PropertyKey.Sym(intrinsics.symbolToStringTag),
            value = JSString("Date"),
            writable = false,
            enumerable = false,
            configurable = true,
        )

        installStaticMethods(intrinsics, dateConstructor)
        installPrototypeMethods(intrinsics, datePrototype)

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
                JSNumber(DateMath.parseDateString(text))
            },
        )

        dateConstructor.defineDataProperty(
            key = PropertyKey.Str("UTC"),
            value = BuiltinHelpers.method(intrinsics, "UTC", 7) { interpreter, _, args ->
                if (args.size < 2) return@method JSNumber.NAN
                val year = interpreter.toNumber(args[0]).toInt()
                val month = interpreter.toNumber(args[1]).toInt()
                val day = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: 1
                val hours = args.getOrNull(3)?.let { interpreter.toNumber(it).toInt() } ?: 0
                val minutes = args.getOrNull(4)?.let { interpreter.toNumber(it).toInt() } ?: 0
                val seconds = args.getOrNull(5)?.let { interpreter.toNumber(it).toInt() } ?: 0
                val milliseconds = args.getOrNull(6)?.let { interpreter.toNumber(it).toInt() } ?: 0
                JSNumber(DateMath.makeTime(year, month, day, hours, minutes, seconds, milliseconds))
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
        method("getTimezoneOffset", 0) { _, _, _ -> JSNumber.ZERO }

        fun getter(name: String, selector: (DateMath.Parts) -> Double) {
            method(name, 0) { _, thisValue, _ ->
                val date = requireDate(thisValue)
                val parts = DateMath.parts(date.timeValue) ?: return@method JSNumber.NAN
                JSNumber(selector(parts))
            }
        }

        getter("getFullYear") { it.year.toDouble() }
        getter("getUTCFullYear") { it.year.toDouble() }
        getter("getMonth") { it.month.toDouble() }
        getter("getUTCMonth") { it.month.toDouble() }
        getter("getDate") { it.day.toDouble() }
        getter("getUTCDate") { it.day.toDouble() }
        getter("getDay") { it.dayOfWeek.toDouble() }
        getter("getUTCDay") { it.dayOfWeek.toDouble() }
        getter("getHours") { it.hours.toDouble() }
        getter("getUTCHours") { it.hours.toDouble() }
        getter("getMinutes") { it.minutes.toDouble() }
        getter("getUTCMinutes") { it.minutes.toDouble() }
        getter("getSeconds") { it.seconds.toDouble() }
        getter("getUTCSeconds") { it.seconds.toDouble() }
        getter("getMilliseconds") { it.milliseconds.toDouble() }
        getter("getUTCMilliseconds") { it.milliseconds.toDouble() }

        // 遗留方法
        method("getYear", 0) { _, thisValue, _ ->
            val date = requireDate(thisValue)
            val parts = DateMath.parts(date.timeValue) ?: return@method JSNumber.NAN
            JSNumber((parts.year - 1900).toDouble())
        }

        method("setTime", 1) { interpreter, thisValue, args ->
            val date = requireDate(thisValue)
            date.timeValue = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            JSNumber(date.timeValue)
        }

        fun setter(name: String, body: suspend (Interpreter, List<JSValue>, DateMath.Parts) -> Double?) {
            method(name, 1) { interpreter, thisValue, args ->
                val date = requireDate(thisValue)
                val base = DateMath.parts(date.timeValue)
                    ?: DateMath.Parts(1970, 0, 1, 0, 0, 0, 0, 4)
                val time = body(interpreter, args, base) ?: return@method JSNumber.NAN
                date.timeValue = time
                JSNumber(time)
            }
        }

        setter("setFullYear") { interpreter, args, base ->
            val year = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (year.isNaN()) return@setter null
            val month = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.month
            val day = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: base.day
            DateMath.makeTime(year.toInt(), month, day, base.hours, base.minutes, base.seconds, base.milliseconds)
        }
        setter("setUTCFullYear") { interpreter, args, base ->
            val year = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (year.isNaN()) return@setter null
            val month = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.month
            val day = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: base.day
            DateMath.makeTime(year.toInt(), month, day, base.hours, base.minutes, base.seconds, base.milliseconds)
        }
        setter("setMonth") { interpreter, args, base ->
            val month = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (month.isNaN()) return@setter null
            val day = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.day
            DateMath.makeTime(base.year, month.toInt(), day, base.hours, base.minutes, base.seconds, base.milliseconds)
        }
        setter("setUTCMonth") { interpreter, args, base ->
            val month = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (month.isNaN()) return@setter null
            val day = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.day
            DateMath.makeTime(base.year, month.toInt(), day, base.hours, base.minutes, base.seconds, base.milliseconds)
        }
        setter("setDate") { interpreter, args, base ->
            val day = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (day.isNaN()) return@setter null
            DateMath.makeTime(base.year, base.month, day.toInt(), base.hours, base.minutes, base.seconds, base.milliseconds)
        }
        setter("setUTCDate") { interpreter, args, base ->
            val day = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (day.isNaN()) return@setter null
            DateMath.makeTime(base.year, base.month, day.toInt(), base.hours, base.minutes, base.seconds, base.milliseconds)
        }
        setter("setHours") { interpreter, args, base ->
            val hours = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (hours.isNaN()) return@setter null
            val minutes = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.minutes
            val seconds = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: base.seconds
            val milliseconds = args.getOrNull(3)?.let { interpreter.toNumber(it).toInt() } ?: base.milliseconds
            DateMath.makeTime(base.year, base.month, base.day, hours.toInt(), minutes, seconds, milliseconds)
        }
        setter("setUTCHours") { interpreter, args, base ->
            val hours = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (hours.isNaN()) return@setter null
            val minutes = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.minutes
            val seconds = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: base.seconds
            val milliseconds = args.getOrNull(3)?.let { interpreter.toNumber(it).toInt() } ?: base.milliseconds
            DateMath.makeTime(base.year, base.month, base.day, hours.toInt(), minutes, seconds, milliseconds)
        }
        setter("setMinutes") { interpreter, args, base ->
            val minutes = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (minutes.isNaN()) return@setter null
            val seconds = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.seconds
            val milliseconds = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: base.milliseconds
            DateMath.makeTime(base.year, base.month, base.day, base.hours, minutes.toInt(), seconds, milliseconds)
        }
        setter("setUTCMinutes") { interpreter, args, base ->
            val minutes = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (minutes.isNaN()) return@setter null
            val seconds = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.seconds
            val milliseconds = args.getOrNull(2)?.let { interpreter.toNumber(it).toInt() } ?: base.milliseconds
            DateMath.makeTime(base.year, base.month, base.day, base.hours, minutes.toInt(), seconds, milliseconds)
        }
        setter("setSeconds") { interpreter, args, base ->
            val seconds = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (seconds.isNaN()) return@setter null
            val milliseconds = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.milliseconds
            DateMath.makeTime(base.year, base.month, base.day, base.hours, base.minutes, seconds.toInt(), milliseconds)
        }
        setter("setUTCSeconds") { interpreter, args, base ->
            val seconds = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (seconds.isNaN()) return@setter null
            val milliseconds = args.getOrNull(1)?.let { interpreter.toNumber(it).toInt() } ?: base.milliseconds
            DateMath.makeTime(base.year, base.month, base.day, base.hours, base.minutes, seconds.toInt(), milliseconds)
        }
        setter("setMilliseconds") { interpreter, args, base ->
            val milliseconds = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (milliseconds.isNaN()) return@setter null
            DateMath.makeTime(base.year, base.month, base.day, base.hours, base.minutes, base.seconds, milliseconds.toInt())
        }
        setter("setUTCMilliseconds") { interpreter, args, base ->
            val milliseconds = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (milliseconds.isNaN()) return@setter null
            DateMath.makeTime(base.year, base.month, base.day, base.hours, base.minutes, base.seconds, milliseconds.toInt())
        }
        setter("setYear") { interpreter, args, base ->
            val year = interpreter.toNumber(args.getOrElse(0) { JSUndefined })
            if (year.isNaN()) return@setter null
            val normalizedYear = if (year in 0.0..99.0) 1900 + year.toInt() else year.toInt()
            DateMath.makeTime(normalizedYear, base.month, base.day, base.hours, base.minutes, base.seconds, base.milliseconds)
        }

        method("toISOString", 0) { interpreter, thisValue, _ ->
            val date = requireDate(thisValue)
            JSString(isoString(date.timeValue) ?: interpreter.rangeError("Invalid time value"))
        }

        method("toJSON", 1) { _, thisValue, _ ->
            val date = requireDate(thisValue)
            if (!date.isValid) JSNull else JSString(isoString(date.timeValue) ?: return@method JSNull)
        }

        method("toString", 0) { _, thisValue, _ ->
            JSString(humanString(requireDate(thisValue).timeValue))
        }
        datePrototype.getOwnDataValue(PropertyKey.Str("toString"))?.let { toStringFunction ->
            datePrototype.defineDataProperty(
                key = PropertyKey.Str("toLocaleString"),
                value = toStringFunction,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
        method("toUTCString", 0) { _, thisValue, _ ->
            JSString(humanString(requireDate(thisValue).timeValue))
        }
        method("toGMTString", 0) { _, thisValue, _ ->
            JSString(humanString(requireDate(thisValue).timeValue))
        }
        method("toDateString", 0) { _, thisValue, _ ->
            val date = requireDate(thisValue)
            val parts = DateMath.parts(date.timeValue) ?: return@method JSString("Invalid Date")
            JSString(
                "${WEEKDAYS[parts.dayOfWeek]} ${MONTHS[parts.month]} " +
                    "${pad(parts.day, 2)} ${pad(parts.year, 4)}"
            )
        }
        method("toTimeString", 0) { _, thisValue, _ ->
            val date = requireDate(thisValue)
            val parts = DateMath.parts(date.timeValue) ?: return@method JSString("Invalid Date")
            JSString("${pad(parts.hours, 2)}:${pad(parts.minutes, 2)}:${pad(parts.seconds, 2)} GMT")
        }

        datePrototype.getOwnDataValue(PropertyKey.Str("toDateString"))?.let { function ->
            datePrototype.defineDataProperty(
                key = PropertyKey.Str("toLocaleDateString"),
                value = function,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
        datePrototype.getOwnDataValue(PropertyKey.Str("toTimeString"))?.let { function ->
            datePrototype.defineDataProperty(
                key = PropertyKey.Str("toLocaleTimeString"),
                value = function,
                writable = true,
                enumerable = false,
                configurable = true,
            )
        }
    }

    private fun requireDate(thisValue: JSValue): JSDate =
        thisValue as? JSDate ?: throw JSException(
            JSError("TypeError", "Date method called on incompatible receiver", null)
        )

    private suspend fun parseDateValue(interpreter: Interpreter, value: JSValue): Double = when (value) {
        is JSDate -> value.timeValue
        is JSString -> DateMath.parseDateString(value.value)
        else -> interpreter.toNumber(value)
    }

    private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')

    private fun humanString(time: Double): String {
        val parts = DateMath.parts(time) ?: return "Invalid Date"
        return "${WEEKDAYS[parts.dayOfWeek]}, ${pad(parts.day, 2)} ${MONTHS[parts.month]} " +
            "${pad(parts.year, 4)} ${pad(parts.hours, 2)}:${pad(parts.minutes, 2)}:" +
            "${pad(parts.seconds, 2)} GMT"
    }

    private fun isoString(time: Double): String? {
        val parts = DateMath.parts(time) ?: return null
        val year = when {
            parts.year in 0..9999 -> pad(parts.year, 4)
            parts.year > 9999 -> "+${parts.year}"
            else -> "-${pad(-parts.year, 6)}"
        }
        return "$year-${pad(parts.month + 1, 2)}-${pad(parts.day, 2)}T" +
            "${pad(parts.hours, 2)}:${pad(parts.minutes, 2)}:${pad(parts.seconds, 2)}." +
            "${pad(parts.milliseconds, 3)}Z"
    }
}
