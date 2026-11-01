package com.github.sanex3339.worktreetabs.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelativeTimeTest {
    private val now = 1_800_000_000_000L
    private fun ago(millis: Long) = RelativeTime.ago(now - millis, now)

    @Test
    fun `recent times read in minutes and hours`() {
        assertEquals("just now", ago(30_000))
        assertEquals("1 minute ago", ago(60_000))
        assertEquals("59 minutes ago", ago(59 * 60_000L))
        assertEquals("1 hour ago", ago(60 * 60_000L))
        assertEquals("23 hours ago", ago(23 * 3_600_000L))
    }

    @Test
    fun `older times read in days, weeks, months and years`() {
        assertEquals("1 day ago", ago(24 * 3_600_000L))
        assertEquals("6 days ago", ago(6 * 86_400_000L))
        assertEquals("1 week ago", ago(7 * 86_400_000L))
        assertEquals("4 weeks ago", ago(29 * 86_400_000L))
        assertEquals("1 month ago", ago(30 * 86_400_000L))
        assertEquals("11 months ago", ago(364 * 86_400_000L))
        assertEquals("2 years ago", ago(2 * 365 * 86_400_000L))
    }

    @Test
    fun `times in the future (clock skew) read as just now`() {
        assertEquals("just now", RelativeTime.ago(now + 5_000, now))
    }
}
