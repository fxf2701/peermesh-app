package app.peermesh

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageTimeFormatterTest {
    private val zoneId: ZoneId = ZoneId.of("UTC")
    private val locale: Locale = Locale.US
    private val now = ZonedDateTime.of(2026, 6, 30, 19, 34, 0, 0, zoneId).toInstant().toEpochMilli()

    @Test
    fun formatsJustNowForRecentMessage() {
        val timestamp = now - 30_000L

        assertEquals("Just now", formatChatMessageTime(timestamp, now, zoneId, locale))
    }

    @Test
    fun formatsMinutesAgoForRecentSameDayMessage() {
        val timestamp = now - (15 * 60_000L)

        assertEquals("15 minutes ago", formatChatMessageTime(timestamp, now, zoneId, locale))
    }

    @Test
    fun formatsHoursAgoForRecentSameDayMessage() {
        val timestamp = now - (3 * 3_600_000L)

        assertEquals("3 hours ago", formatChatMessageTime(timestamp, now, zoneId, locale))
    }

    @Test
    fun formatsClockTimeForOlderSameDayMessage() {
        val timestamp = ZonedDateTime.of(2026, 6, 30, 10, 5, 0, 0, zoneId).toInstant().toEpochMilli()

        assertEquals("10:05 AM", formatChatMessageTime(timestamp, now, zoneId, locale))
    }

    @Test
    fun formatsYesterdayForPreviousDayMessage() {
        val timestamp = ZonedDateTime.of(2026, 6, 29, 23, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        assertEquals("Yesterday", formatChatMessageTime(timestamp, now, zoneId, locale))
    }

    @Test
    fun formatsWeekdayForRecentWeekMessage() {
        val timestamp = ZonedDateTime.of(2026, 6, 27, 12, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        assertEquals("Sat", formatChatMessageTime(timestamp, now, zoneId, locale))
    }

    @Test
    fun formatsMonthDayForOlderSameYearMessage() {
        val timestamp = ZonedDateTime.of(2026, 2, 14, 12, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        assertEquals("Feb 14", formatChatMessageTime(timestamp, now, zoneId, locale))
    }

    @Test
    fun formatsMonthDayYearForOlderYearMessage() {
        val timestamp = ZonedDateTime.of(2025, 12, 31, 12, 0, 0, 0, zoneId).toInstant().toEpochMilli()

        assertEquals("Dec 31, 2025", formatChatMessageTime(timestamp, now, zoneId, locale))
    }
}
