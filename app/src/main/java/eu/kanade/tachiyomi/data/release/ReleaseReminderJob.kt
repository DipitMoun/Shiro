package eu.kanade.tachiyomi.data.release

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.presentation.library.components.AnimeUpcomingLoader
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.common.Constants
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.social.ActivityCoverBanner
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

enum class ReminderKind { EVE, DAY }

/** When a reminder is due for an episode: the evening before, and just before it comes out. */
object ReleaseReminderRules {

    const val EVE_FROM_HOUR = 18
    val DAY_LEAD_MS: Long = TimeUnit.MINUTES.toMillis(90)

    /** The reminder to send now for an episode airing at [airingAtSeconds], or null for none yet. */
    fun due(airingAtSeconds: Long, now: ZonedDateTime): ReminderKind? {
        val airing = Instant.ofEpochSecond(airingAtSeconds).atZone(now.zone)
        val untilMs = airing.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
        return when {
            untilMs < 0 -> null
            untilMs <= DAY_LEAD_MS -> ReminderKind.DAY
            airing.toLocalDate() == now.toLocalDate().plusDays(1) && now.hour >= EVE_FROM_HOUR -> ReminderKind.EVE
            else -> null
        }
    }

    fun key(kind: ReminderKind, animeId: Long, episode: Int) = "${kind.name}:$animeId:$episode"
}

/**
 * Every hour, looks at the episodes of the library that come out in the next two days and sends
 * two reminders for each: the evening before (from 18:00) and shortly before it airs, with the
 * cover. Each reminder is sent once. The schedule comes from the same source as the forecast cards
 * of the carousel, and is cached, so most runs do not touch the network.
 *
 * Only anime: there is no release calendar for chapters.
 */
class ReleaseReminderJob(private val context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            run()
            Result.success()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Release reminders failed" }
            Result.success()
        }
    }

    private suspend fun run() {
        val library = Injekt.get<GetLibraryAnime>().await().map { it.anime }
        if (library.isEmpty()) return
        val upcoming = AnimeUpcomingLoader().load(library, withinDays = 2, limit = MAX_WATCHED)
        if (upcoming.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sent = prefs.getStringSet(KEY_SENT, emptySet()).orEmpty().toMutableSet()
        val now = ZonedDateTime.now()
        var posted = 0

        for ((anime, event) in upcoming) {
            if (posted >= MAX_PER_RUN) break
            val kind = ReleaseReminderRules.due(event.airingAt, now) ?: continue
            val key = ReleaseReminderRules.key(kind, anime.id, event.episode)
            if (key in sent) continue
            val cover = ActivityCoverBanner.loadWithPoster(event.coverUrl ?: anime.thumbnailUrl)
            if (post(kind, anime.id, anime.title, event.episode, event.airingAt, cover)) {
                sent += key
                posted++
            }
        }
        if (posted > 0) prefs.edit { putStringSet(KEY_SENT, sent.toList().takeLast(MAX_REMEMBERED).toSet()) }
    }

    private fun post(
        kind: ReminderKind,
        animeId: Long,
        title: String,
        episode: Int,
        airingAtSeconds: Long,
        cover: ActivityCoverBanner.Rendered?,
    ): Boolean {
        val time = Instant.ofEpochSecond(airingAtSeconds).atZone(ZoneId.systemDefault()).format(TIME)
        val (heading, text) = when (kind) {
            ReminderKind.EVE -> "Demain · $title" to "L'épisode $episode sort demain à $time"
            ReminderKind.DAY -> title to "L'épisode $episode sort à $time"
        }
        val open = Intent(context, MainActivity::class.java)
            .setAction(Constants.SHORTCUT_ANIME)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Constants.ANIME_EXTRA, animeId)
        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_RELEASE_REMINDERS)
            .setSmallIcon(R.drawable.ic_ani)
            .setContentTitle(heading)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    (kind.name + animeId).hashCode(),
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .apply {
                if (cover != null) {
                    setLargeIcon(cover.poster)
                    setStyle(NotificationCompat.BigPictureStyle().bigPicture(cover.banner).bigLargeIcon(null as android.graphics.Bitmap?))
                }
            }
            .build()
        return try {
            NotificationManagerCompat.from(context).notify("release:${kind.name}:$animeId", Notifications.ID_RELEASE_REMINDERS, notification)
            true
        } catch (_: SecurityException) {
            // Notifications turned off for the app: nothing to send, and nothing to retry.
            true
        }
    }

    companion object {
        private const val TAG = "ReleaseReminder"
        private const val PREFS = "shiro_release_reminders"
        private const val KEY_SENT = "sent"
        private const val MAX_WATCHED = 60
        private const val MAX_PER_RUN = 6
        private const val MAX_REMEMBERED = 300
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")

        fun setupTask(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReleaseReminderJob>(1, TimeUnit.HOURS)
                .addTag(TAG)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            context.workManager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
