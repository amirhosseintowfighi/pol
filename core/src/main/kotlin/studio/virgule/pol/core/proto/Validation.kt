package studio.virgule.pol.core.proto

/**
 * Input validation for anything that crosses the link or comes from the keyboard. The gateway re-validates every
 * request even though it comes from a paired device: a compromised or buggy client must not be able to send
 * arbitrary USSD/MMI strings, oversized messages or control characters to the telephony stack.
 */
object Validate {
    const val MAX_SMS_CHARS = 1600 // ~10 concatenated parts
    const val MAX_NUMBER_CHARS = 32
    private val numberChars = Regex("^\\+?[0-9*#]{1,32}$")
    private val ussd = Regex("^[*#][0-9*#]{1,30}#$")
    private val cmid = Regex("^[A-Za-z0-9-]{8,40}$")

    /** Strips formatting a person might type or paste ("+98 (912) 123-4567"); null if not a dialable number. */
    fun normalizeNumber(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.length > 64) return null
        val sb = StringBuilder()
        for ((i, ch) in trimmed.withIndex()) {
            when {
                ch in '0'..'9' || ch == '*' || ch == '#' -> sb.append(ch)
                ch in '۰'..'۹' -> sb.append('0' + (ch - '۰')) // Persian digits
                ch in '٠'..'٩' -> sb.append('0' + (ch - '٠')) // Arabic-Indic digits
                ch == '+' && i == 0 -> sb.append(ch)
                ch == ' ' || ch == '-' || ch == '(' || ch == ')' || ch == '.' || ch == ' ' -> {}
                else -> return null
            }
        }
        val s = sb.toString()
        return if (numberChars.matches(s)) s else null
    }

    /** A number that may be dialled as a call: no MMI/USSD strings (those go through [ussdCode]). */
    fun dialNumber(input: String): String? = normalizeNumber(input)?.takeIf { n -> n.none { it == '*' || it == '#' } }

    /** SMS recipients: digits only (with optional +), or a short alphanumeric-free service code. */
    fun smsRecipient(input: String): String? = dialNumber(input)

    fun smsBody(input: String): String? {
        if (input.isBlank() || input.length > MAX_SMS_CHARS) return null
        return input.filter { it == '\n' || it == '\t' || !it.isISOControl() }
    }

    /** USSD / carrier codes such as `*140#` or call forwarding `**21*0912...#`. */
    fun ussdCode(input: String): String? {
        val n = normalizeNumber(input) ?: return null
        return if (ussd.matches(n)) n else null
    }

    fun clientMessageId(input: String): Boolean = cmid.matches(input)

    /** Bounds strings received from the other device before they reach UI or storage. */
    fun displayText(input: String?, max: Int = 200): String = (input ?: "").filter { !it.isISOControl() || it == '\n' }.take(max)
}

/** Remembers recent client message ids so a request retried after a reconnect is executed at most once. */
class RecentIds(private val capacity: Int = 512, private val ttlMs: Long = 24 * 3600_000L) {
    private val seen = LinkedHashMap<String, Long>(capacity, 0.75f, false)

    /** True the first time [id] is seen within the TTL; false for duplicates. */
    @Synchronized
    fun firstSeen(id: String, now: Long): Boolean {
        val it = seen.entries.iterator()
        while (it.hasNext()) { val e = it.next(); if (now - e.value > ttlMs) it.remove() else break }
        if (seen.containsKey(id)) return false
        seen[id] = now
        while (seen.size > capacity) seen.remove(seen.keys.first())
        return true
    }
}
