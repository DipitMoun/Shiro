package eu.kanade.tachiyomi.ui.social.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.social.matrix.SocialTimelineItem
import eu.kanade.tachiyomi.ui.social.formatTimecode

/**
 * P2-10d.2: Real Matrix overlay (minimalist).
 * Displays the actual timeline from SocialOverlayState.
 */
@Composable
fun SocialMomentOverlayMatrix(
    state: SocialOverlayState,
    onClose: () -> Unit,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F11)),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Matrix Social - " + (state.thread?.episodeKey?.value ?: "?"),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onClose) {
                    Text("Fermer", color = Color(0xFFEF5350))
                }
            }
            HorizontalDivider(color = Color(0xFF1E262A))

            // Timeline
            val items = state.timeline?.items ?: emptyList()
            if (items.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Aucun message pour le moment.", color = Color(0xFF90A4AE))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(items, key = { it.eventId }) { item ->
                        MessageRow(item)
                    }
                }
            }

            // Composer
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1E262A), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    maxLines = 3
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (draft.isNotBlank()) {
                            onSend(draft.trim())
                            draft = ""
                        }
                    },
                    enabled = draft.isNotBlank() && !state.isSending
                ) {
                    Text("Envoyer")
                }
            }
        }
    }
}

@Composable
private fun MessageRow(item: SocialTimelineItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF131A1E), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.sender,
                color = Color(0xFF64B5F6),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            when (item) {
                is SocialTimelineItem.MomentItem -> {
                    val tc = item.payload.moments.firstOrNull()?.positionMs
                    if (tc != null) {
                        Text(formatTimecode(tc), color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall)
                    }
                }
                else -> {}
            }
        }
        Spacer(Modifier.height(4.dp))
        when (item) {
            is SocialTimelineItem.MomentItem -> {
                Text(item.payload.note ?: "<Moment>", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            is SocialTimelineItem.ReplyItem -> {
                Text(item.body, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (item.reactions.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                item.reactions.forEach { rx ->
                    Text("${rx.key} ${rx.count}", color = Color(0xFF90A4AE), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
