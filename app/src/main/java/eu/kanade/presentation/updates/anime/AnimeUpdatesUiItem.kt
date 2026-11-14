package eu.kanade.presentation.updates.anime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadIndicator
import eu.kanade.presentation.updates.components.UpdateGroupCard
import eu.kanade.presentation.updates.components.UpdateGroupRow
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.updates.anime.AnimeUpdatesItem
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.concurrent.TimeUnit

internal fun LazyListScope.animeUpdatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "animeUpdates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun LazyListScope.animeUpdatesUiItems(
    uiModels: List<AnimeUpdatesUiModel>,
    selectionMode: Boolean,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onSelectGroup: (List<AnimeUpdatesItem>, Boolean) -> Unit,
    onUpdateSelected: (AnimeUpdatesItem, Boolean, Boolean, Boolean) -> Unit,
    onClickCover: (AnimeUpdatesItem) -> Unit,
    onClickUpdate: (AnimeUpdatesItem, altPlayer: Boolean) -> Unit,
    onDownloadEpisode: (List<AnimeUpdatesItem>, EpisodeDownloadAction) -> Unit,
    onMarkGroupSeen: (List<AnimeUpdatesItem>, Boolean) -> Unit,
) {
    items(
        items = uiModels,
        contentType = {
            when (it) {
                is AnimeUpdatesUiModel.Header -> "header"
                is AnimeUpdatesUiModel.Group -> "group"
            }
        },
        key = {
            when (it) {
                is AnimeUpdatesUiModel.Header -> "animeUpdatesHeader-${it.date}"
                is AnimeUpdatesUiModel.Group -> "animeUpdatesGroup-${it.key}"
            }
        },
    ) { model ->
        when (model) {
            is AnimeUpdatesUiModel.Header -> {
                ListGroupHeader(
                    modifier = Modifier.animateItemFastScroll(),
                    text = relativeDateText(model.date),
                )
            }
            is AnimeUpdatesUiModel.Group -> {
                val group = model
                // Ordre de visionnage : numéro croissant, puis date.
                val orderedItems = remember(group.items) {
                    group.items.sortedWith(
                        compareBy<AnimeUpdatesItem> {
                            numberLabel(it.update.episodeName)?.toDoubleOrNull() ?: Double.MAX_VALUE
                        }.thenBy { it.update.dateFetch },
                    )
                }
                val resume = orderedItems.firstOrNull { !it.update.seen } ?: orderedItems.last()
                val allSeen = orderedItems.all { it.update.seen }
                val groupSelected = orderedItems.all { it.selected }
                val expanded = group.key in expandedGroups

                UpdateGroupCard(
                    modifier = Modifier.animateItemFastScroll(),
                    title = orderedItems.first().update.animeTitle,
                    summary = groupSummary(orderedItems),
                    coverData = orderedItems.first().update.coverData,
                    isSelected = selectionMode && groupSelected,
                    isExpanded = expanded,
                    onToggleExpand = {
                        if (selectionMode) {
                            onSelectGroup(orderedItems, !groupSelected)
                        } else {
                            onToggleGroup(group.key)
                        }
                    },
                    onLongClick = { onSelectGroup(orderedItems, !groupSelected) },
                    primaryLabel = primaryLabel(resume).takeIf { !selectionMode },
                    onPrimaryClick = { onClickUpdate(resume, false) },
                    onClickCover = { onClickCover(orderedItems.first()) }.takeIf { !selectionMode },
                    trailingContent = {
                        if (!selectionMode) {
                            EpisodeDownloadIndicator(
                                enabled = true,
                                modifier = Modifier.padding(start = 4.dp),
                                downloadStateProvider = resume.downloadStateProvider,
                                downloadProgressProvider = resume.downloadProgressProvider,
                                onClick = { onDownloadEpisode(listOf(resume), it) },
                            )
                        }
                    },
                    expandedContent = {
                        orderedItems.forEach { item ->
                            UpdateGroupRow(
                                label = item.update.episodeName,
                                meta = episodeMeta(item),
                                isUnread = !item.update.seen,
                                isSelected = item.selected,
                                onClick = {
                                    if (selectionMode) {
                                        onUpdateSelected(item, !item.selected, true, false)
                                    } else {
                                        onClickUpdate(item, false)
                                    }
                                },
                                onLongClick = {
                                    onUpdateSelected(item, !item.selected, true, true)
                                },
                                trailingContent = {
                                    EpisodeDownloadIndicator(
                                        enabled = !selectionMode,
                                        modifier = Modifier.padding(start = 4.dp),
                                        downloadStateProvider = item.downloadStateProvider,
                                        downloadProgressProvider = item.downloadProgressProvider,
                                        onClick = { onDownloadEpisode(listOf(item), it) },
                                    )
                                },
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { onMarkGroupSeen(orderedItems, true) },
                                enabled = !allSeen,
                            ) {
                                Text(text = "Tout marquer vu")
                            }
                            TextButton(
                                onClick = { onDownloadEpisode(orderedItems, EpisodeDownloadAction.START) },
                            ) {
                                Text(text = "Tout télécharger")
                            }
                        }
                    },
                )
            }
        }
    }
}

private val TrailingNumber = Regex("""(\d+(?:[.,]\d+)?)\s*$""")

private fun numberLabel(name: String): String? =
    TrailingNumber.find(name.trim())
        ?.groupValues
        ?.getOrNull(1)
        ?.replace(',', '.')

private fun shortEpisodeLabel(name: String): String? =
    numberLabel(name)?.let { "E$it" }

private fun episodeRange(items: List<AnimeUpdatesItem>): String? {
    val first = numberLabel(items.firstOrNull()?.update?.episodeName.orEmpty()) ?: return null
    val last = numberLabel(items.lastOrNull()?.update?.episodeName.orEmpty()) ?: return null
    return when {
        items.size == 1 -> "E$first"
        first == last -> "E$first"
        else -> "E$first–E$last"
    }
}

private fun groupSummary(items: List<AnimeUpdatesItem>): String {
    val unseen = items.count { !it.update.seen }
    val base = when (unseen) {
        0 -> if (items.size == 1) "Vu" else "Tout est vu"
        1 -> "1 nouvel épisode"
        else -> "$unseen nouveaux épisodes"
    }
    return episodeRange(items)?.let { "$base · $it" } ?: base
}

private fun primaryLabel(item: AnimeUpdatesItem): String {
    val short = shortEpisodeLabel(item.update.episodeName)
    return when {
        item.update.seen -> short?.let { "Revoir $it" } ?: "Revoir"
        short != null -> "Reprendre $short"
        else -> "Reprendre"
    }
}

@androidx.compose.runtime.Composable
private fun episodeMeta(item: AnimeUpdatesItem): String {
    val progress = item.update.lastSecondSeen
        .takeIf { !item.update.seen && it > 0L }
        ?.let {
            stringResource(
                AYMR.strings.episode_progress,
                formatProgress(it),
                formatProgress(item.update.totalSeconds),
            )
        }
    return listOfNotNull(relativeTimeSpanString(item.update.dateFetch), progress).joinToString(" · ")
}

private fun formatProgress(milliseconds: Long): String {
    return if (milliseconds > 3600000L) {
        String.format(
            "%d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(milliseconds),
            TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds)),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    } else {
        String.format(
            "%d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(milliseconds),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    }
}
