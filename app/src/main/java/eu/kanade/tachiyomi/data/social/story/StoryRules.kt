package eu.kanade.tachiyomi.data.social.story

import java.util.concurrent.TimeUnit

/**
 * The rules of the stories, with no Android or Matrix in them so they can be tested.
 *
 * Quotas are the ones announced for a free matrix.org account. Whether the windows slide or are
 * fixed days is not documented: they are treated as sliding (the stricter reading for the user),
 * and deleting a media is treated as giving nothing back, until both are tested on a real account.
 */
object StoryLimits {
    const val MB = 1024L * 1024L
    const val DAILY_BYTES = 100 * MB
    const val MONTHLY_BYTES = 500 * MB
    const val MONTH_DAYS = 28L
    const val DEFAULT_MAX_FILE_BYTES = 10 * MB

    /** One send above this asks for a confirmation. */
    const val CONFIRM_ABOVE_BYTES = 50 * MB

    val TTL_MS: Long = TimeUnit.HOURS.toMillis(24)
    val DAY_MS: Long = TimeUnit.DAYS.toMillis(1)
    val MONTH_MS: Long = TimeUnit.DAYS.toMillis(MONTH_DAYS)
}

/** A story sent by the user, as counted against the quota. */
data class SentRecord(val atMillis: Long, val bytes: Long)

data class QuotaStatus(
    val usedDay: Long,
    val usedMonth: Long,
    val remainingDay: Long,
    val remainingMonth: Long,
) {
    /** What can still be sent right now. */
    val remaining: Long get() = minOf(remainingDay, remainingMonth)
}

sealed interface SendVerdict {
    data object Allowed : SendVerdict

    /** Allowed, but big enough to ask first. */
    data class NeedsConfirmation(val bytes: Long) : SendVerdict

    data class FileTooBig(val bytes: Long, val maxBytes: Long) : SendVerdict

    /** [retryAtMillis] is approximate: when enough older sends have left the window. */
    data class QuotaExceeded(val reason: QuotaWindow, val retryAtMillis: Long?) : SendVerdict
}

enum class QuotaWindow { DAY, MONTH }

object StoryQuota {

    fun status(records: List<SentRecord>, now: Long): QuotaStatus {
        val usedDay = records.filter { now - it.atMillis < StoryLimits.DAY_MS }.sumOf { it.bytes }
        val usedMonth = records.filter { now - it.atMillis < StoryLimits.MONTH_MS }.sumOf { it.bytes }
        return QuotaStatus(
            usedDay = usedDay,
            usedMonth = usedMonth,
            remainingDay = (StoryLimits.DAILY_BYTES - usedDay).coerceAtLeast(0),
            remainingMonth = (StoryLimits.MONTHLY_BYTES - usedMonth).coerceAtLeast(0),
        )
    }

    /**
     * Whether [bytes] can be sent now. [maxFileBytes] is the smaller of the 10 MB rule and what the
     * server says it accepts.
     */
    fun verdict(
        records: List<SentRecord>,
        now: Long,
        bytes: Long,
        maxFileBytes: Long = StoryLimits.DEFAULT_MAX_FILE_BYTES,
    ): SendVerdict {
        if (bytes > maxFileBytes) return SendVerdict.FileTooBig(bytes, maxFileBytes)
        val status = status(records, now)
        if (bytes > status.remainingDay) {
            return SendVerdict.QuotaExceeded(
                QuotaWindow.DAY,
                releaseTime(records, now, StoryLimits.DAY_MS, StoryLimits.DAILY_BYTES, bytes),
            )
        }
        if (bytes > status.remainingMonth) {
            return SendVerdict.QuotaExceeded(
                QuotaWindow.MONTH,
                releaseTime(records, now, StoryLimits.MONTH_MS, StoryLimits.MONTHLY_BYTES, bytes),
            )
        }
        return if (bytes > StoryLimits.CONFIRM_ABOVE_BYTES) SendVerdict.NeedsConfirmation(bytes) else SendVerdict.Allowed
    }

    /**
     * When enough of the oldest sends of a window will have left it for [bytes] to fit, or null if
     * [bytes] can never fit in that window.
     */
    fun releaseTime(records: List<SentRecord>, now: Long, windowMs: Long, limit: Long, bytes: Long): Long? {
        if (bytes > limit) return null
        var used = records.filter { now - it.atMillis < windowMs }.sumOf { it.bytes }
        for (record in records.filter { now - it.atMillis < windowMs }.sortedBy { it.atMillis }) {
            used -= record.bytes
            if (limit - used >= bytes) return record.atMillis + windowMs
        }
        return null
    }
}

/** What a friend's story room holds, and for how long. */
object StoryExpiry {
    fun expiresAt(postedAtMillis: Long): Long = postedAtMillis + StoryLimits.TTL_MS

    fun isActive(postedAtMillis: Long, now: Long): Boolean = now < expiresAt(postedAtMillis)

    /** "22 s", "3 min", "5 h": how long ago a story was posted. */
    fun ago(postedAtMillis: Long, now: Long): String {
        val seconds = ((now - postedAtMillis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            else -> "${seconds / 3600} h"
        }
    }
}
