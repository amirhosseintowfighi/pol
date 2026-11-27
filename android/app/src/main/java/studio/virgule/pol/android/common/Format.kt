package studio.virgule.pol.android.common

import android.icu.text.DateFormat
import android.icu.text.NumberFormat
import android.icu.util.ULocale
import java.util.Locale

/** Locale-aware formatting. Persian uses the Solar Hijri calendar and Persian digits, as people there expect. */
object Format {
    private fun ulocale(): ULocale {
        val l = Locale.getDefault()
        return if (l.language == "fa") ULocale("fa_IR@calendar=persian") else ULocale.forLocale(l)
    }

    /** Wraps text that must stay left-to-right (phone numbers, codes) when shown inside right-to-left UI. */
    fun ltr(s: String) = LRI + s + PDI

    /** Wraps user text (a message body) so it takes the direction of its own first strong letter. */
    fun auto(s: String) = FSI + s + PDI

    // Unicode isolates (U+2066 left-to-right isolate, U+2068 first-strong isolate, U+2069 pop directional isolate), built from code points
    // so no invisible control characters sit in the source.
    private val LRI = Char(0x2066).toString()
    private val FSI = Char(0x2068).toString()
    private val PDI = Char(0x2069).toString()

    fun number(n: Long): String = NumberFormat.getInstance(ulocale()).format(n)

    fun digits(s: String): String {
        if (Locale.getDefault().language != "fa") return s
        return s.map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")
    }

    fun time(ts: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT, ulocale()).format(java.util.Date(ts))
    fun date(ts: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, ulocale()).format(java.util.Date(ts))
    fun dateTime(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, ulocale()).format(java.util.Date(ts))

    /** "14:05" today, weekday this week, else a short date. */
    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        val day = 86_400_000L
        val startOfToday = now - (now + java.util.TimeZone.getDefault().getOffset(now)) % day
        return when {
            ts >= startOfToday -> time(ts)
            ts >= startOfToday - 6 * day -> DateFormat.getPatternInstance(DateFormat.WEEKDAY, ulocale()).format(java.util.Date(ts))
            else -> DateFormat.getDateInstance(DateFormat.SHORT, ulocale()).format(java.util.Date(ts))
        }
    }

    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val txt = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        return digits(txt)
    }

    fun initials(name: String): String =
        name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "#" }
}
