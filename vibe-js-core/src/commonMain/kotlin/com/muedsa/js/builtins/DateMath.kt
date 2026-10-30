package com.muedsa.js.builtins

/**
 * Date 相关的日期计算（UTC）。
 *
 * 说明：common 代码无法直接获取时区信息，阶段 B 的本地时间方法与 UTC 方法保持一致，
 * 时区偏移固定为 0；后续如需真实时区，可通过宿主抽象扩展。
 */
internal object DateMath {

    const val MS_PER_DAY = 86_400_000.0
    const val MS_PER_HOUR = 3_600_000.0
    const val MS_PER_MINUTE = 60_000.0
    const val MS_PER_SECOND = 1_000.0

    /** 日期组成部分。month 为 0 基。 */
    data class Parts(
        val year: Int,
        val month: Int,
        val day: Int,
        val hours: Int,
        val minutes: Int,
        val seconds: Int,
        val milliseconds: Int,
        val dayOfWeek: Int,
    )

    /**
     * 由日期字段构造时间值。
     *
     * month/day 允许越界（自动进位/借位），与 Date 构造器行为一致。
     */
    fun makeTime(
        year: Int,
        month: Int,
        day: Int,
        hours: Int,
        minutes: Int,
        seconds: Int,
        milliseconds: Int,
    ): Double {
        val normalizedYear = if (year in 0..99) 1900 + year else year
        var y = normalizedYear
        var m = month
        y += m / 12
        m %= 12
        if (m < 0) {
            m += 12
            y -= 1
        }
        val days = daysFromCivil(y, m, day)
        val timeOfDay = ((hours.toLong() * 60 + minutes) * 60 + seconds) * 1000L + milliseconds
        return days * MS_PER_DAY + timeOfDay
    }

    /** 把时间值拆分为日期字段；无效时间返回 null。 */
    fun parts(time: Double): Parts? {
        if (time.isNaN() || time.isInfinite()) return null
        val days = kotlin.math.floor(time / MS_PER_DAY).toLong()
        var remainder = time - days * MS_PER_DAY
        val civil = civilFromDays(days)
        val hours = kotlin.math.floor(remainder / MS_PER_HOUR).toInt()
        remainder -= hours * MS_PER_HOUR
        val minutes = kotlin.math.floor(remainder / MS_PER_MINUTE).toInt()
        remainder -= minutes * MS_PER_MINUTE
        val seconds = kotlin.math.floor(remainder / MS_PER_SECOND).toInt()
        remainder -= seconds * MS_PER_SECOND
        val milliseconds = kotlin.math.floor(remainder).toInt()
        val dayOfWeek = (((days + 4) % 7 + 7) % 7).toInt()
        return Parts(civil.year, civil.month, civil.day, hours, minutes, seconds, milliseconds, dayOfWeek)
    }

    /** 公历日期 -> 自 1970-01-01 起的天数。month 为 0 基。 */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        var y = year
        val m = month + 1
        y -= if (m <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** 自 1970-01-01 起的天数 -> 公历日期。 */
    private fun civilFromDays(days: Long): CivilDate {
        val z = days + 719468L
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = mp + (if (mp < 10) 3 else -9)
        return CivilDate(
            year = (y + if (m <= 2) 1 else 0).toInt(),
            month = (m - 1).toInt(),
            day = d.toInt(),
        )
    }

    data class CivilDate(val year: Int, val month: Int, val day: Int)

    /**
     * 解析 ISO 8601 日期字符串。
     *
     * 支持 YYYY-MM-DD、YYYY-MM-DDTHH:mm[:ss[.sss]][Z|±HH:MM]；
     * 其他格式返回 NaN（与浏览器对非 ISO 格式的宽容解析不同）。
     */
    fun parseDateString(text: String): Double {
        val trimmed = text.trim()
        val regex = Regex(
            """^([+-]?\d{4,6})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?(Z|[+-]\d{2}:?\d{2})?)?$"""
        )
        val match = regex.matchEntire(trimmed) ?: return Double.NaN
        val year = match.groupValues[1].toIntOrNull() ?: return Double.NaN
        val month = match.groupValues[2].toIntOrNull() ?: return Double.NaN
        val day = match.groupValues[3].toIntOrNull() ?: return Double.NaN
        if (month !in 1..12 || day !in 1..31) return Double.NaN
        val hours = match.groupValues[4].ifEmpty { "0" }.toInt()
        val minutes = match.groupValues[5].ifEmpty { "0" }.toInt()
        val seconds = match.groupValues[6].ifEmpty { "0" }.toInt()
        val milliseconds = match.groupValues[7].ifEmpty { "0" }.padEnd(3, '0').toInt()
        if (hours !in 0..23 || minutes !in 0..59 || seconds !in 0..59) return Double.NaN

        var time = makeTime(year, month - 1, day, hours, minutes, seconds, milliseconds)
        val zone = match.groupValues[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val zoneHours = zone.substring(1, 3).toInt()
            val zoneMinutes = zone.substring(if (zone.contains(':')) 4 else 3).toInt()
            time -= sign * (zoneHours * 60 + zoneMinutes) * MS_PER_MINUTE
        }
        return time
    }
}
