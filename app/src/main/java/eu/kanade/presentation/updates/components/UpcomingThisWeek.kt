package eu.kanade.presentation.updates.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.ui.browse.catalog.AiringEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpcomingThisWeek(events: List<AiringEvent>, modifier: Modifier = Modifier) {
    if (events.isEmpty()) return
    val sorted = events.sortedBy { it.airingAt }

    Column(modifier = modifier.padding(vertical = 8.dp)) {
        Text(
            text = "Prévu cette semaine",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sorted, key = { "${it.mediaId}-${it.episode}" }) { event ->
                UpcomingCard(event)
            }
        }
    }
}

@Composable
private fun UpcomingCard(event: AiringEvent) {
    val dateFormat = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
    val dateStr = dateFormat.format(Date(event.airingAt * 1000))

    Column(modifier = Modifier.width(110.dp)) {
        AsyncImage(
            model = event.coverUrl,
            contentDescription = event.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "EP ${event.episode}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = dateStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
