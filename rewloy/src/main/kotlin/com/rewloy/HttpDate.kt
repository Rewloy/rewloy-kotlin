package com.rewloy

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** The three date formats HTTP allows (RFC 9110 §5.6.7), read without `java.time` (not on older Android). */
internal object HttpDate {
    private val patterns = arrayOf(
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
        "EEEE, dd-MMM-yy HH:mm:ss 'GMT'",
        "EEE MMM d HH:mm:ss yyyy",
    )

    /** Milliseconds since the epoch, or `null` when [text] is not an HTTP date. */
    fun parse(text: String): Long? {
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.isLenient = false
            val position = ParsePosition(0)
            val date = format.parse(text, position)
            if (date != null && position.index == text.length) return date.time
        }
        return null
    }
}
