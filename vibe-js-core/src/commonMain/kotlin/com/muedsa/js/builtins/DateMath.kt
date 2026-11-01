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

    /**
     * MakeDay：由年/月/日字段计算自 1970-01-01 起的天数。
     *
     * 按规范使用 IEEE 754 双精度运算（大数值用例依赖精确的舍入顺序），
     * 任一参数非有限时返回 NaN。
     */
    fun makeDay(year: Double, month: Double, date: Double): Double {
        if (!year.isFinite() || !month.isFinite() || !date.isFinite()) return Double.NaN
        val y = kotlin.math.truncate(year)
        val m = kotlin.math.truncate(month)
        val dt = kotlin.math.truncate(date)
        val monthIndex = kotlin.math.floor(m / 12.0)
        val ym = y + monthIndex
        val mn = m - monthIndex * 12.0
        val dayFromYear = 365.0 * (ym - 1970.0) +
            kotlin.math.floor((ym - 1969.0) / 4.0) -
            kotlin.math.floor((ym - 1901.0) / 100.0) +
            kotlin.math.floor((ym - 1601.0) / 400.0)
        val monthIndexInt = mn.toInt()
        var dayWithinYear = DAYS_BEFORE_MONTH[monthIndexInt].toDouble()
        val leap = (ym % 4.0 == 0.0 && ym % 100.0 != 0.0) || ym % 400.0 == 0.0
        if (mn >= 2.0 && leap) dayWithinYear += 1.0
        return dayFromYear + dayWithinYear + dt - 1.0
    }

    /** MakeTime：按 `((h*msPerHour + m*msPerMinute) + s*msPerSecond) + ms` 的双精度顺序计算。 */
    fun makeTimeDouble(hour: Double, minute: Double, second: Double, millisecond: Double): Double {
        if (!hour.isFinite() || !minute.isFinite() ||
            !second.isFinite() || !millisecond.isFinite()
        ) {
            return Double.NaN
        }
        return ((kotlin.math.truncate(hour) * MS_PER_HOUR +
            kotlin.math.truncate(minute) * MS_PER_MINUTE) +
            kotlin.math.truncate(second) * MS_PER_SECOND) + kotlin.math.truncate(millisecond)
    }

    /** MakeDate：day × msPerDay + time。 */
    fun makeDate(day: Double, time: Double): Double = day * MS_PER_DAY + time

    /** Date.UTC/Date 构造器的 0..99 年映射（ToInteger 后判断）。 */
    fun normalizeYear(year: Double): Double {
        if (!year.isFinite()) return year
        val integer = kotlin.math.truncate(year)
        return if (integer >= 0.0 && integer <= 99.0) 1900.0 + integer else year
    }

    private val DAYS_BEFORE_MONTH =
        intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    /** TimeClip：截断为整数并限制在 ±8.64e15，非有限值或越界返回 NaN。 */
    fun timeClip(time: Double): Double {
        if (!time.isFinite()) return Double.NaN
        val truncated = if (time < 0) kotlin.math.ceil(time) else kotlin.math.floor(time)
        return if (kotlin.math.abs(truncated) > 8.64e15) Double.NaN else truncated
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
     * 小时允许 24 且仅在分秒毫秒为 0 时顺延到次日；小数秒超过 3 位按截断处理。
     * 其他格式返回 NaN（与浏览器对非 ISO 格式的宽容解析不同）。
     */
    fun parseDateString(text: String, localOffsetMillis: ((Double) -> Double)? = null): Double {
        val trimmed = text.trim()
        // 自身 toString/toUTCString 输出（"Thu, 01 Jan 1970 00:00:00 GMT"）
        val human = HUMAN_DATE_PATTERN.matchEntire(trimmed)
        if (human != null) {
            val monthIndex = MONTH_NAMES.indexOf(human.groupValues[2])
            if (monthIndex < 0) return Double.NaN
            val time = makeDate(
                makeDay(
                    human.groupValues[3].toInt().toDouble(),
                    monthIndex.toDouble(),
                    human.groupValues[1].toInt().toDouble(),
                ),
                makeTimeDouble(
                    human.groupValues[4].toDouble(),
                    human.groupValues[5].toDouble(),
                    human.groupValues[6].toDouble(),
                    0.0,
                ),
            )
            return timeClip(time)
        }
        // 自身 toString 输出（本地时间固定 UTC）："Thu Jan 01 1970 00:00:00 GMT+0000 (...)"
        val local = LOCAL_DATE_PATTERN.matchEntire(trimmed)
        if (local != null) {
            val monthIndex = MONTH_NAMES.indexOf(local.groupValues[1])
            if (monthIndex < 0) return Double.NaN
            var time = makeDate(
                makeDay(
                    local.groupValues[3].toInt().toDouble(),
                    monthIndex.toDouble(),
                    local.groupValues[2].toInt().toDouble(),
                ),
                makeTimeDouble(
                    local.groupValues[4].toDouble(),
                    local.groupValues[5].toDouble(),
                    local.groupValues[6].toDouble(),
                    0.0,
                ),
            )
            val zone = local.groupValues[7]
            if (zone.length == 5) {
                val sign = if (zone[0] == '-') -1 else 1
                val zoneMinutes = zone.substring(1, 3).toInt() * 60 + zone.substring(3, 5).toInt()
                time -= sign * zoneMinutes * MS_PER_MINUTE
            }
            return timeClip(time)
        }
        val regex = Regex(
            """^([+-]?\d{4,6})(?:-(\d{2})(?:-(\d{2}))?)?(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d+))?)?(Z|[+-]\d{2}:?\d{2})?)?$"""
        )
        val match = regex.matchEntire(trimmed) ?: return Double.NaN
        val yearText = match.groupValues[1]
        // 规范：扩展年份 0 必须写成 +000000，"-000000" 非法
        if (yearText.startsWith("-") && yearText.drop(1).all { it == '0' }) {
            return Double.NaN
        }
        val year = yearText.toIntOrNull() ?: return Double.NaN
        val month = match.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return Double.NaN
        val day = match.groupValues[3].ifEmpty { "1" }.toIntOrNull() ?: return Double.NaN
        if (month !in 1..12 || day !in 1..31) return Double.NaN
        // 时间部分仅在完整日期（YYYY-MM-DD）之后允许
        if (match.groupValues[4].isNotEmpty() && match.groupValues[3].isEmpty()) {
            return Double.NaN
        }
        val hours = match.groupValues[4].ifEmpty { "0" }.toInt()
        val minutes = match.groupValues[5].ifEmpty { "0" }.toInt()
        val seconds = match.groupValues[6].ifEmpty { "0" }.toInt()
        // 小数秒按前 3 位截断（V8 语义）；空小数部分不匹配正则
        val fraction = match.groupValues[7]
        val milliseconds = if (fraction.isEmpty()) 0 else fraction.take(3).padEnd(3, '0').toInt()
        if (minutes !in 0..59 || seconds !in 0..59) return Double.NaN
        // 24 点仅在分秒毫秒全为 0 时合法，makeDate 会自然顺延到次日；
        // 毫秒需看原始小数（V8 对 24:00:00.0009 仍判非法）
        val fractionIsZero = fraction.all { it == '0' }
        if (hours !in 0..23 && !(hours == 24 && minutes == 0 && seconds == 0 && fractionIsZero)) {
            return Double.NaN
        }

        // ISO 扩展年份按字面值使用（0..99 不映射到 1900+）
        var time = makeDate(
            makeDay(year.toDouble(), (month - 1).toDouble(), day.toDouble()),
            makeTimeDouble(
                hours.toDouble(),
                minutes.toDouble(),
                seconds.toDouble(),
                milliseconds.toDouble(),
            ),
        )
        val zone = match.groupValues[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val zoneHours = zone.substring(1, 3).toInt()
            val zoneMinutes = zone.substring(if (zone.contains(':')) 4 else 3).toInt()
            // ISO 时区偏移上限：小时 00..23、分钟 00..59
            if (zoneHours !in 0..23 || zoneMinutes !in 0..59) return Double.NaN
            time -= sign * (zoneHours * 60 + zoneMinutes) * MS_PER_MINUTE
        } else if (zone.isEmpty() && match.groupValues[4].isNotEmpty() && localOffsetMillis != null) {
            // 带时间但无时区的 ISO 字符串按本地时间解释（纯日期仍为 UTC）；
            // 两轮偏移修正与构造器的本地换算一致，覆盖偏移随时刻变化的场景
            val firstGuess = time - localOffsetMillis(time)
            time -= localOffsetMillis(firstGuess)
        }
        return timeClip(time)
    }

    private val HUMAN_DATE_PATTERN = Regex(
        """^[A-Za-z]{3}, (\d{2}) ([A-Za-z]{3}) (-?\d{4,6}) (\d{2}):(\d{2}):(\d{2}) GMT$"""
    )
    private val LOCAL_DATE_PATTERN = Regex(
        """^[A-Za-z]{3} ([A-Za-z]{3}) (\d{2}) (-?\d{4,6}) (\d{2}):(\d{2}):(\d{2}) GMT([+-]\d{4})(?: \(.+\))?$"""
    )
    private val MONTH_NAMES =
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
