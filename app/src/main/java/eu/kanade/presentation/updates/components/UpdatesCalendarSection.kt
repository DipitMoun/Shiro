package eu.kanade.presentation.updates.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.ui.browse.catalog.AiringEvent
import kotlinx.collections.immutable.toImmutableMap
import mihon.feature.upcoming.components.calendar.Calendar
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bandeau calendrier de la page Mises à jour.
 *
 * - semaine courante avec compteur de sorties par jour ;
 * - calendrier complet dépliable sur la même page ;
 * - section « À venir » alimentée par le planning AniList (anime uniquement).
 */
@Composable
fun UpdatesCalendarSection(
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    releaseCounts: Map<LocalDate, Int>,
    upcoming: List<AiringEvent>,
    onClickUpcoming: (AiringEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var calendarExpanded by remember { mutableStateOf(false) }
    var selectedYearMonth by remember { mutableStateOf(YearMonth.now()) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { calendarExpanded = !calendarExpanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cette semaine",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (calendarExpanded) "Réduire" else "Calendrier",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        HorizontalWeekCalendar(
            selectedDate = selectedDate,
            onDateSelected = onDateSelected,
            releaseCounts = releaseCounts,
        )

        if (calendarExpanded) {
            Calendar(
                selectedYearMonth = selectedYearMonth,
                events = releaseCounts.toImmutableMap(),
                setSelectedYearMonth = { selectedYearMonth = it },
                onClickDay = onDateSelected,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (upcoming.isNotEmpty()) {
            UpcomingAiringList(
                events = upcoming,
                onClick = onClickUpcoming,
            )
        }
    }
}

@Composable
private fun UpcomingAiringList(
    events: List<AiringEvent>,
    onClick: (AiringEvent) -> Unit,
) {
    val now = remember { Instant.now() }
    val next = remember(events, now) {
        events
            .filter { it.airingAt >= now.epochSecond && it.episode > 0 }
            .sortedBy { it.airingAt }
            .take(20)
    }
    if (next.isEmpty()) return

    Column(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Text(
            text = "À venir",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(next, key = { "${it.mediaId}-${it.episode}-${it.airingAt}" }) { event ->
                UpcomingAiringCard(event = event, onClick = { onClick(event) })
            }
        }
    }
}

@Composable
private fun UpcomingAiringCard(
    event: AiringEvent,
    onClick: () -> Unit,
) {
    val airing = remember(event.airingAt) {
        Instant.ofEpochSecond(event.airingAt)
    }
    val dateLabel = remember(airing) {
        DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(airing)
    }
    val countdown = remember(airing) { countdownLabel(airing) }

    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp),
    ) {
        AsyncImage(
            model = event.coverUrl,
            contentDescription = event.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (event.episode > 0) "E${event.episode}" else "Prochain",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .width(1.dp)
                    .height(10.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Text(
                text = countdown,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private fun countdownLabel(airingAt: Instant): String {
    val duration = java.time.Duration.between(Instant.now(), airingAt)
    if (duration.isNegative) return "maintenant"
    val days = duration.toDays()
    val hours = duration.toHours() % 24
    val minutes = duration.toMinutes() % 60
    return when {
        days > 0 -> "dans $days j $hours h"
        hours > 0 -> "dans $hours h $minutes min"
        else -> "dans ${minutes.coerceAtLeast(1)} min"
    }
}
