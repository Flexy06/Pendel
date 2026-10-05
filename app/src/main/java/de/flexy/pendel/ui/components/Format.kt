package de.flexy.pendel.ui.components

import de.flexy.pendel.core.stats.DayGroup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Fmt {
    private val de = Locale.GERMANY
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", de)
    private val dateFmt = DateTimeFormatter.ofPattern("EE, d. MMM", de)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("EE, d. MMM · HH:mm", de)

    /** 27:41 min / 1:02:05 h */
    fun duration(seconds: Double?): String {
        if (seconds == null || seconds.isNaN()) return "–"
        val s = seconds.roundToInt().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d h".format(h, m, sec) else "%d:%02d min".format(m, sec)
    }

    /** Compact without unit: 27:41 */
    fun mmss(seconds: Double): String {
        val s = abs(seconds).roundToInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    /** Wait times: "18,4 s" below a minute, "12 min 54 s" above. */
    fun wait(seconds: Double): String {
        if (seconds < 60) return String.format(de, "%.1f s", seconds)
        val s = seconds.roundToInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "$h h $m min" else "$m min ${s % 60} s"
    }

    fun distance(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else String.format(de, "%.1f km", m / 1000)

    fun km(m: Double): String = String.format(de, "%.1f", m / 1000)

    fun speedKmh(ms: Double): String = String.format(de, "%.1f km/h", ms * 3.6)

    fun percent(v: Double): String = "${(v * 100).roundToInt()} %"

    fun minuteOfDay(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)

    fun time(epochMs: Long): String = timeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    fun date(epochMs: Long): String = dateFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    fun dateTime(epochMs: Long): String = dateTimeFmt.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    fun dayGroup(g: DayGroup): String = when (g) {
        is DayGroup.Single -> DayGroup.DAY_NAMES[g.dow - 1]
        DayGroup.Weekdays -> "Werktags"
        DayGroup.Weekend -> "Am Wochenende"
        DayGroup.All -> "An allen Tagen"
    }

    fun trips(n: Int): String = if (n == 1) "1 Fahrt" else "$n Fahrten"
}
