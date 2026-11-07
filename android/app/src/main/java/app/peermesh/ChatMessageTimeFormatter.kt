package app.peermesh

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

fun formatChatMessageTime(
    timestampMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val timestamp = Instant.ofEpochMilli(timestampMs).atZone(zoneId)
    val now = Instant.ofEpochMilli(nowMs).atZone(zoneId)
    val messageDate = timestamp.toLocalDate()
    val today = now.toLocalDate()
    val diffMs = nowMs - timestampMs

    if (messageDate == today) {
        if (diffMs in 0 until 60_000L) {
            return "Just now"
        }
        if (diffMs in 60_000L until 3_600_000L) {
            val minutes = diffMs / 60_000L
            return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        }
        if (diffMs in 3_600_000L until 21_600_000L) {
            val hours = diffMs / 3_600_000L
            return if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
        return timestamp.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    }

    if (messageDate == today.minusDays(1)) {
        return "Yesterday"
    }

    val daysBetween = ChronoUnit.DAYS.between(messageDate, today)
    if (daysBetween in 2..6) {
        return timestamp.format(
            DateTimeFormatter.ofPattern("EEE", locale)
        )
    }

    return if (timestamp.year == now.year) {
        timestamp.format(DateTimeFormatter.ofPattern("MMM d", locale))
    } else {
        timestamp.format(DateTimeFormatter.ofPattern("MMM d, uuuu", locale))
    }
}
