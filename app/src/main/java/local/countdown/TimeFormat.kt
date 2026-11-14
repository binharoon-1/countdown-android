package local.countdown

import java.util.Calendar

internal enum class TimeUnit(val label: String, val suffix: String, val seconds: Long = 0) {
    YEARS("Years", "y"),
    MONTHS("Months", "mo"),
    DAYS("Days", "d", 86_400),
    HOURS("Hours", "h", 3_600),
    MINUTES("Minutes", "m", 60),
    SECONDS("Seconds", "s", 1),
    PRECISE("Precise", ""),
    AUTO("Auto", "");

    companion object {
        fun parse(name: String?) = entries.find { it.name == name }
    }
}

internal fun duration(timer: Timer, now: Long, format: TimeUnit): String {
    val start = if (timer.up) timer.time else now
    val end = if (timer.up) now else timer.time
    val elapsed = ((end - start).coerceAtLeast(0)) / 1000
    if (format.seconds > 0) return "${elapsed / format.seconds} ${format.suffix}"

    val cursor = Calendar.getInstance().apply { timeInMillis = start }
    val finish = Calendar.getInstance().apply { timeInMillis = maxOf(start, end) }
    val totalMonths = (finish.get(Calendar.YEAR) - cursor.get(Calendar.YEAR)) * 12 +
        finish.get(Calendar.MONTH) - cursor.get(Calendar.MONTH)
    val months = wholePeriods(cursor, Calendar.MONTH, totalMonths, finish.timeInMillis)
    val years = wholePeriods(cursor, Calendar.YEAR,
        finish.get(Calendar.YEAR) - cursor.get(Calendar.YEAR), finish.timeInMillis)
    if (format == TimeUnit.YEARS) return "$years y"
    if (format == TimeUnit.MONTHS) return "$months mo"

    cursor.add(Calendar.YEAR, years)
    val monthEstimate = (finish.get(Calendar.YEAR) - cursor.get(Calendar.YEAR)) * 12 +
        finish.get(Calendar.MONTH) - cursor.get(Calendar.MONTH)
    val remainingMonths = wholePeriods(cursor, Calendar.MONTH, monthEstimate, finish.timeInMillis)
    cursor.add(Calendar.MONTH, remainingMonths)
    val remainder = (finish.timeInMillis - cursor.timeInMillis) / 1000

    val parts = listOf(
        years.toLong() to "y",
        remainingMonths.toLong() to "mo",
        remainder / 86_400 to "d",
        remainder / 3_600 % 24 to "h",
        remainder / 60 % 60 to "m",
        remainder % 60 to "s"
    ).filter { it.first != 0L }
    val visible = if (format == TimeUnit.AUTO) parts.take(1) else parts
    return visible.joinToString(" ") { (value, unit) -> "$value $unit" }.ifEmpty { "0 s" }
}

private fun wholePeriods(start: Calendar, field: Int, estimate: Int, end: Long): Int {
    val count = estimate.coerceAtLeast(0)
    val candidate = (start.clone() as Calendar).apply { add(field, count) }
    return if (candidate.timeInMillis > end) (count - 1).coerceAtLeast(0) else count
}
