package co.uk.doverguitarteacher.superstepstracker

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

// These functions are now `internal` and can be used by any file in the app.

internal fun todayKey(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date())
}

internal fun dayLabelFromKey(key: String): String {
    return if (key == todayKey()) {
        "Today"
    } else {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        try {
            val d = sdf.parse(key)
            dayFmt.format(d!!)
        } catch (_: Throwable) {
            key
        }
    }
}

internal fun distanceFromSteps(steps: Int): Double = steps * 0.762 / 1000.0
internal fun caloriesFromSteps(steps: Int): Double = steps * 0.04

internal fun decodeHistory(raw: String?): MutableMap<String, DayStats> {
    val out = mutableMapOf<String, DayStats>()
    if (raw.isNullOrBlank()) return out
    val parts = raw.split("|").filter { it.isNotBlank() }
    parts.forEach { token ->
        val cols = token.split(":")
        if (cols.size >= 2) {
            val date = cols[0].trim()
            val steps = cols[1].trim().toIntOrNull() ?: 0
            val dist = cols.getOrNull(2)?.trim()?.toDoubleOrNull() ?: distanceFromSteps(steps)
            val cal = cols.getOrNull(3)?.trim()?.toDoubleOrNull() ?: caloriesFromSteps(steps)
            if (date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                out[date] = DayStats(
                    dateKey = date,
                    label = dayLabelFromKey(date),
                    steps = max(0, steps),
                    distanceKm = dist,
                    caloriesKcal = cal
                )
            }
        }
    }
    return out
}

internal fun last7Keys(): List<String> {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val keys = ArrayDeque<String>(7)
    for (i in 6 downTo 1) {
        val c = cal.clone() as Calendar
        c.add(Calendar.DAY_OF_YEAR, -i)
        keys.addLast(sdf.format(c.time))
    }
    keys.addLast(sdf.format(cal.time))
    return keys.toList()
}
