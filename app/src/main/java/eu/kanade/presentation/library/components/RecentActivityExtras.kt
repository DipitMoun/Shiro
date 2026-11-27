package eu.kanade.presentation.library.components

import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Next thing to watch or read for a work of the carousel. */
@Immutable
data class NextEntryBadge(
    /** "Ép. 7" or "Ch. 312". */
    val label: String,
    /** Fetched in the last two days. */
    val isNew: Boolean,
    val downloaded: Boolean,
)

/** An episode that is announced but not out yet, for a work of the library. */
@Immutable
data class UpcomingWork(
    val id: Long,
    val title: String,
    val coverModel: Any?,
    val episode: Int,
    val airingAtSeconds: Long,
    /** "Ép." for an episode, "Ch." for a chapter. */
    val prefix: String = "Ép.",
    /** A chapter date worked out from the rhythm of the last ones, not announced: shown as "vers". */
    val estimated: Boolean = false,
)

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM 'à' HH:mm", Locale.FRENCH)

/** "42 min", "19 h", "Demain", "J‑3": how long until [airingAtSeconds], as short as it can be. */
internal fun countdownLabel(airingAtSeconds: Long, now: ZonedDateTime = ZonedDateTime.now()): String {
    val at = Instant.ofEpochSecond(airingAtSeconds).atZone(now.zone)
    val minutes = Duration.between(now, at).toMinutes()
    if (minutes < 0) return "Sorti"
    if (minutes < 60) return "${minutes.coerceAtLeast(1)} min"
    return when (val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())) {
        0L -> if (at.minute == 0) "${at.hour} h" else String.format(Locale.FRENCH, "%d:%02d", at.hour, at.minute)
        1L -> "Demain"
        else -> "J‑$days"
    }
}

internal fun airingDayLabel(airingAtSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochSecond(airingAtSeconds).atZone(zone).format(dayFormatter).replaceFirstChar { it.uppercase() }

private val shortDay = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)

/** "Ép. 11 · Demain 19:30", "Ch. 313 · vers Lun": what comes out and when, on one short line. */
internal fun releaseLabel(
    airingAtSeconds: Long,
    prefix: String,
    number: Int,
    estimated: Boolean,
    now: ZonedDateTime = ZonedDateTime.now(),
): String {
    val at = Instant.ofEpochSecond(airingAtSeconds).atZone(now.zone)
    val head = "$prefix $number"
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
    val weekday = at.format(shortDay).removeSuffix(".").replaceFirstChar { it.uppercase() }
    val day = when {
        days <= 0L -> "Auj."
        days == 1L -> "Demain"
        days <= 6L -> weekday
        else -> String.format(Locale.FRENCH, "%d/%d", at.dayOfMonth, at.monthValue)
    }
    if (estimated) return "$head · vers ${day.replaceFirstChar { it.lowercase() }}"
    val minutes = Duration.between(now, at).toMinutes()
    return when {
        minutes < 0 -> "$head · sorti"
        minutes < 60 -> "$head · dans ${minutes.coerceAtLeast(1)} min"
        else -> "$head · $day " + String.format(Locale.FRENCH, "%02d:%02d", at.hour, at.minute)
    }
}

/** How far along the wait is: 0 a week before, 1 at the moment it comes out. Never fully empty. */
internal fun ringProgress(airingAtSeconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000L, windowDays: Int = 7): Float {
    val window = windowDays * 24f * 3600f
    val remaining = (airingAtSeconds - nowSeconds).coerceAtLeast(0L)
    return (1f - remaining / window).coerceIn(0.04f, 1f)
}

/**
 * Round card of something that is coming: the poster in a circle, a ring around it that fills as
 * the day approaches, and under it the title and the date.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UpcomingCircleCard(
    work: UpcomingWork,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val progress = ringProgress(work.airingAtSeconds)
    val ring = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .width(104.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arc = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
                drawArc(ring, -90f, 360f * progress, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (work.coverModel != null) {
                    AsyncImage(
                        model = work.coverModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = work.title,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            text = releaseLabel(work.airingAtSeconds, work.prefix, work.episode, work.estimated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Slim vertical label that separates what to watch now from what is coming. */
@Composable
internal fun UpcomingDivider() {
    Box(
        modifier = Modifier
            .width(22.dp)
            .height(120.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Bientôt",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            // Measured wide, then turned: rotating alone leaves the 22 dp width to lay the word out in.
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity))
                    layout(placeable.height, placeable.width) {
                        placeable.placeWithLayer(
                            x = -(placeable.width - placeable.height) / 2,
                            y = (placeable.width - placeable.height) / 2,
                        ) { rotationZ = -90f }
                    }
                },
        )
    }
}

/** Small tag on the poster: number of the next episode or chapter, a dot when it is new. */
@Composable
internal fun NextEntryTag(badge: NextEntryBadge, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (badge.isNew) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Text(badge.label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        if (badge.downloaded) {
            Icon(
                imageVector = Icons.Outlined.DownloadDone,
                contentDescription = "Téléchargé",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** Long-press details of a card of the carousel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentCardSheet(
    title: String,
    detail: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onContinue: (() -> Unit)?,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (onContinue != null) {
                Button(onClick = { onDismiss(); onContinue() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reprendre")
                }
            }
            OutlinedButton(onClick = { onDismiss(); onOpen() }, modifier = Modifier.fillMaxWidth()) {
                Text("Ouvrir la fiche")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
