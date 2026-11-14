package eu.kanade.presentation.updates.manga

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
import eu.kanade.presentation.entries.manga.components.ChapterDownloadAction
import eu.kanade.presentation.entries.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.updates.components.UpdateGroupCard
import eu.kanade.presentation.updates.components.UpdateGroupRow
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.ui.updates.manga.MangaUpdatesItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

internal fun LazyListScope.mangaUpdatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "mangaUpdates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun LazyListScope.mangaUpdatesUiItems(
    uiModels: List<MangaUpdatesUiModel>,
    selectionMode: Boolean,
    expandedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onSelectGroup: (List<MangaUpdatesItem>, Boolean) -> Unit,
    onUpdateSelected: (MangaUpdatesItem, Boolean, Boolean, Boolean) -> Unit,
    onClickCover: (MangaUpdatesItem) -> Unit,
    onClickUpdate: (MangaUpdatesItem) -> Unit,
    onDownloadChapter: (List<MangaUpdatesItem>, ChapterDownloadAction) -> Unit,
    onMarkGroupRead: (List<MangaUpdatesItem>, Boolean) -> Unit,
) {
    items(
        items = uiModels,
        contentType = {
            when (it) {
                is MangaUpdatesUiModel.Header -> "header"
                is MangaUpdatesUiModel.Group -> "group"
            }
        },
        key = {
            when (it) {
                is MangaUpdatesUiModel.Header -> "mangaUpdatesHeader-${it.date}"
                is MangaUpdatesUiModel.Group -> "mangaUpdatesGroup-${it.key}"
            }
        },
    ) { model ->
        when (model) {
            is MangaUpdatesUiModel.Header -> {
                ListGroupHeader(
                    modifier = Modifier.animateItemFastScroll(),
                    text = relativeDateText(model.date),
                )
            }
            is MangaUpdatesUiModel.Group -> {
                val group = model
                // Ordre de lecture : numéro croissant, puis date. Indispensable
                // quand plusieurs chapitres arrivent dans le même lot.
                val orderedItems = remember(group.items) {
                    group.items.sortedWith(
                        compareBy<MangaUpdatesItem> {
                            numberLabel(it.update.chapterName)?.toDoubleOrNull() ?: Double.MAX_VALUE
                        }.thenBy { it.update.dateFetch },
                    )
                }
                val resume = orderedItems.firstOrNull { !it.update.read } ?: orderedItems.last()
                val allRead = orderedItems.all { it.update.read }
                val groupSelected = orderedItems.all { it.selected }
                val expanded = group.key in expandedGroups

                UpdateGroupCard(
                    modifier = Modifier.animateItemFastScroll(),
                    title = orderedItems.first().update.mangaTitle,
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
                    onPrimaryClick = { onClickUpdate(resume) },
                    onClickCover = { onClickCover(orderedItems.first()) }.takeIf { !selectionMode },
                    trailingContent = {
                        if (!selectionMode) {
                            ChapterDownloadIndicator(
                                enabled = true,
                                modifier = Modifier.padding(start = 4.dp),
                                downloadStateProvider = resume.downloadStateProvider,
                                downloadProgressProvider = resume.downloadProgressProvider,
                                onClick = { onDownloadChapter(listOf(resume), it) },
                            )
                        }
                    },
                    expandedContent = {
                        orderedItems.forEach { item ->
                            UpdateGroupRow(
                                label = item.update.chapterName,
                                meta = relativeTimeSpanString(item.update.dateFetch),
                                isUnread = !item.update.read,
                                isSelected = item.selected,
                                onClick = {
                                    if (selectionMode) {
                                        onUpdateSelected(item, !item.selected, true, false)
                                    } else {
                                        onClickUpdate(item)
                                    }
                                },
                                onLongClick = {
                                    onUpdateSelected(item, !item.selected, true, true)
                                },
                                trailingContent = {
                                    ChapterDownloadIndicator(
                                        enabled = !selectionMode,
                                        modifier = Modifier.padding(start = 4.dp),
                                        downloadStateProvider = item.downloadStateProvider,
                                        downloadProgressProvider = item.downloadProgressProvider,
                                        onClick = { onDownloadChapter(listOf(item), it) },
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
                                onClick = { onMarkGroupRead(orderedItems, true) },
                                enabled = !allRead,
                            ) {
                                Text(text = "Tout marquer lu")
                            }
                            TextButton(
                                onClick = { onDownloadChapter(orderedItems, ChapterDownloadAction.START) },
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

private fun shortChapterLabel(name: String): String? =
    numberLabel(name)?.let { "Ch. $it" }

private fun chapterRange(items: List<MangaUpdatesItem>): String? {
    val first = numberLabel(items.firstOrNull()?.update?.chapterName.orEmpty()) ?: return null
    val last = numberLabel(items.lastOrNull()?.update?.chapterName.orEmpty()) ?: return null
    return when {
        items.size == 1 -> "Ch. $first"
        first == last -> "Ch. $first"
        else -> "Ch. $first–$last"
    }
}

private fun groupSummary(items: List<MangaUpdatesItem>): String {
    val unread = items.count { !it.update.read }
    val base = when (unread) {
        0 -> if (items.size == 1) "Lu" else "Tout est lu"
        1 -> "1 nouveau chapitre"
        else -> "$unread nouveaux chapitres"
    }
    return chapterRange(items)?.let { "$base · $it" } ?: base
}

private fun primaryLabel(item: MangaUpdatesItem): String {
    val short = shortChapterLabel(item.update.chapterName)
    return when {
        item.update.read -> short?.let { "Relire $it" } ?: "Relire"
        short != null -> "Reprendre $short"
        else -> "Reprendre"
    }
}
