package eu.kanade.presentation.entries.manga.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.manga.components.MangaExtensionIcon
import eu.kanade.tachiyomi.data.multisource.MangaMappingState
import eu.kanade.tachiyomi.data.multisource.MangaSourceCompatibility
import kotlin.math.roundToInt

/**
 * Sources of a multi-source manga: what each one covers, and which one is read first.
 * Same layout as the anime sheet; a tap on a source makes it the primary one.
 */
@Composable
fun MultiSourceMangaCompatibilityContent(
    state: MangaMappingState,
    onReload: (() -> Unit)? = null,
    onSelectSource: ((Long?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Compatibilité des sources",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = when {
                        state.loading -> "Recherche des sources…"
                        state.totalChapters <= 0 -> "Nombre de chapitres inconnu"
                        else -> "${state.assignedChapters}/${state.totalChapters} chapitres attribués"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.complete) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }

            IconButton(
                onClick = { onReload?.invoke() },
                enabled = onReload != null && !state.loading,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Relancer la recherche des sources",
                )
            }
        }

        if (state.loading) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.error?.let { error ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                items(state.sources, key = { it.sourceId }) { source ->
                    SourceRow(
                        source = source,
                        totalChapters = state.totalChapters,
                        preferred = source.sourceId == state.preferredSourceId,
                        onClick = onSelectSource?.let { select -> { select(source.sourceId) } },
                    )
                }
            }
        }

        if (state.preferredSourceId != null && onSelectSource != null) {
            TextButton(
                onClick = { onSelectSource(null) },
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Source automatique")
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: MangaSourceCompatibility,
    totalChapters: Int,
    preferred: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (source.extension != null) {
            MangaExtensionIcon(
                extension = source.extension,
                modifier = Modifier.size(40.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.extension?.name ?: source.sourceName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(source.sourceName)
                    append(" · ")
                    append("${source.coveredChapters}/$totalChapters couverts")
                    if (source.assignedChapters > 0) {
                        append(" · ${source.assignedChapters} attribués")
                    }
                    if (preferred) append(" · choisie")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (preferred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { source.compatibility.coerceIn(0f, 1f) },
                modifier = Modifier.size(44.dp),
                strokeWidth = 4.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = "${(source.compatibility * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
