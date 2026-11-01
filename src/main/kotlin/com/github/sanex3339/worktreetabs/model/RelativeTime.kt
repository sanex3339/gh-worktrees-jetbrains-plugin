package com.github.sanex3339.worktreetabs.model

/** "5 minutes ago", "3 weeks ago": how long ago a moment was, coarse enough to read at a glance. */
object RelativeTime {
    fun ago(then: Long, now: Long): String {
        val minutes = (now - then) / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            hours < 1 -> count(minutes, "minute")
            days < 1 -> count(hours, "hour")
            days < 7 -> count(days, "day")
            days < 30 -> count(days / 7, "week")
            days < 365 -> count((days / 30).coerceAtMost(11), "month")
            else -> count(days / 365, "year")
        }
    }

    private fun count(amount: Long, unit: String) = "$amount ${if (amount == 1L) unit else "${unit}s"} ago"
}
