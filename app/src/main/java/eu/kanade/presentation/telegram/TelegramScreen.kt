package eu.kanade.presentation.telegram

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.telegram.index.TelegramMediaKind
import eu.kanade.tachiyomi.data.telegram.index.TelegramSyncState
import eu.kanade.tachiyomi.data.telegram.saved.TelegramMetadataProvider
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedKind
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWork
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthState
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramChannelUi
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramSearchFileUi
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramSearchGroupUi
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramUiState
import eu.kanade.tachiyomi.ui.browse.telegram.TelegramWorkUi
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

@Composable
fun TelegramScreen(
    state: TelegramUiState,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
    onClickChannel: (Long) -> Unit,
    onClickIndexedWork: (TelegramWorkUi) -> Unit = {},
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onContinueSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onMergeGroups: (Set<String>) -> Unit,
    onSplitFiles: (String, Set<String>) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onRemoveFile: (String, String) -> Unit,
    onCreateWork: (String) -> Unit,
    onClickWork: (TelegramSavedWork) -> Unit,
    onRenameWork: (String, String) -> Unit,
    onResyncWork: (String) -> Unit,
    onDeleteWork: (String) -> Unit,
    onSetWatch: (String, Boolean, Int) -> Unit,
    onResolveWork: (String, String?, TelegramMetadataProvider) -> Unit,
) {
    val sync = state.syncState

    var renameWork by remember { mutableStateOf<TelegramSavedWork?>(null) }
    var deleteWork by remember { mutableStateOf<TelegramSavedWork?>(null) }
    var watchWork by remember { mutableStateOf<TelegramSavedWork?>(null) }
    var anilistWork by remember { mutableStateOf<TelegramSavedWork?>(null) }

    // L'onglet peut être mesuré sans hauteur maximale pendant une transition
    // du navigateur. Les LazyColumn ont besoin d'une hauteur finie.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val boundedHeight = LocalConfiguration.current.screenHeightDp.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(boundedHeight),
        ) {
            if (sync is TelegramSyncState.Syncing) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = "${stringResource(MR.strings.telegram_syncing)} ${sync.title}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { sync.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            when {
                state.authState !is TelegramAuthState.Ready -> {
                    TelegramConnectPrompt(onConnect = onConnect)
                }
                state.channels.isEmpty() -> {
                    TelegramEmptyPrompt(onConnect = onConnect)
                }
                else -> {
                    SearchBar(
                        query = state.searchQuery,
                        busy = state.searchBusy,
                        onQueryChange = onSearchQueryChange,
                        onSearch = onSearch,
                        onClear = onClearSearch,
                    )

                    if (state.searchBusy) {
                        SearchProgress(state)
                    }

                    val showResults = state.searchBusy || state.searchDone
                    if (showResults) {
                        SearchResults(
                            state = state,
                            contentPadding = contentPadding,
                            onMergeGroups = onMergeGroups,
                            onSplitFiles = onSplitFiles,
                            onRenameGroup = onRenameGroup,
                            onRemoveFile = onRemoveFile,
                            onCreateWork = onCreateWork,
                            onContinueSearch = onContinueSearch,
                        )
                    } else {
                        IdleContent(
                            state = state,
                            contentPadding = contentPadding,
                            onConnect = onConnect,
                            onClickChannel = onClickChannel,
                            onClickIndexedWork = onClickIndexedWork,
                            onClickWork = onClickWork,
                            onRenameWork = { renameWork = it },
                            onResyncWork = onResyncWork,
                            onDeleteWork = { deleteWork = it },
                            onWatchWork = { watchWork = it },
                            onAniListWork = { anilistWork = it },
                        )
                    }
                }
            }
        }

        renameWork?.let { work ->
            RenameWorkDialog(
                work = work,
                onDismiss = { renameWork = null },
                onConfirm = { title ->
                    renameWork = null
                    onRenameWork(work.id, title)
                },
            )
        }

        deleteWork?.let { work ->
            DeleteWorkDialog(
                work = work,
                onDismiss = { deleteWork = null },
                onConfirm = {
                    deleteWork = null
                    onDeleteWork(work.id)
                },
            )
        }

        watchWork?.let { work ->
            WatchWorkDialog(
                work = work,
                onDismiss = { watchWork = null },
                onConfirm = { enabled, minutes ->
                    watchWork = null
                    onSetWatch(work.id, enabled, minutes)
                },
            )
        }

        anilistWork?.let { work ->
            MetadataWorkDialog(
                work = work,
                onDismiss = { anilistWork = null },
                onConfirm = { query, provider ->
                    anilistWork = null
                    onResolveWork(work.id, query, provider)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Recherche
// ---------------------------------------------------------------------------

@Composable
private fun SearchBar(
    query: String,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Rechercher un fichier…") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Outlined.Close, contentDescription = "Effacer")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            shape = RoundedCornerShape(28.dp),
        )
        Button(
            onClick = onSearch,
            enabled = !busy && query.isNotBlank(),
            modifier = Modifier.padding(start = 8.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Text("Chercher")
        }
    }
}

@Composable
private fun SearchProgress(state: TelegramUiState) {
    val total = state.searchProgressTotal
    val done = state.searchProgressDone
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = if (state.searchProgressChannel.isNotBlank()) {
                "Recherche dans « ${state.searchProgressChannel} » ($done/$total)"
            } else {
                "Recherche… ($done/$total)"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SearchResults(
    state: TelegramUiState,
    contentPadding: PaddingValues,
    onMergeGroups: (Set<String>) -> Unit,
    onSplitFiles: (String, Set<String>) -> Unit,
    onRenameGroup: (String, String) -> Unit,
    onRemoveFile: (String, String) -> Unit,
    onCreateWork: (String) -> Unit,
    onContinueSearch: () -> Unit,
) {
    var selectedGroups by remember { mutableStateOf(emptySet<String>()) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.searchBusy) {
        if (state.searchBusy) listState.scrollToItem(0)
    }

    val groups = state.searchGroups
    val existingIds = groups.map { it.id }.toSet()
    val selection = selectedGroups.filter { it in existingIds }.toSet()
    val selectedGroupsData = groups.filter { it.id in selection }
    val sameKind = selectedGroupsData.map { it.kind }.distinct().size <= 1

    val videos = groups.filter { it.kind == TelegramMediaKind.VIDEO }

    LazyColumn(state = listState, contentPadding = contentPadding) {
        if (state.searchErrors.isNotEmpty()) {
            item("errors") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    state.searchErrors.forEach { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (state.searchHasMore) {
            item("limit") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Limite de ${state.searchLimit} fichiers par canal atteinte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onContinueSearch,
                        enabled = !state.searchBusy && state.searchLimit < 2000,
                    ) {
                        Text("Continuer")
                    }
                }
            }
        }

        if (state.searchDone) {
            item("search-stats") {
                Text(
                    text = "${state.searchIndexedChannels} canaux indexés · " +
                        "${state.searchLocalMatches} fichiers locaux · " +
                        "${state.searchServerMatches} résultats serveur",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        if (selection.size >= 2) {
            item("merge") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${selection.size} groupes sélectionnés",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { selectedGroups = emptySet() }) {
                        Text("Annuler")
                    }
                    Button(
                        onClick = {
                            onMergeGroups(selection)
                            selectedGroups = emptySet()
                        },
                        enabled = sameKind && state.searchGroups.none { it.id in selection && it.savedWorkId != null },
                    ) {
                        Text("Fusionner")
                    }
                }
            }
        }

        if (state.searchDone && state.searchBusy.not() && groups.isEmpty()) {
            item("empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Aucun fichier trouvé.",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Essayez un autre mot, ou vérifiez que le canal est ajouté.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (videos.isNotEmpty()) {
            item("header-video") {
                SectionHeader("Vidéos", videos.sumOf { it.fileCount })
            }
            itemsIndexed(videos, key = { index, group -> "video-$index-${group.id}" }) { _, group ->
                GroupCard(
                    group = group,
                    selected = group.id in selection,
                    onToggleSelected = {
                        selectedGroups = if (group.id in selection) {
                            selection - group.id
                        } else {
                            selection + group.id
                        }
                    },
                    onRename = { onRenameGroup(group.id, it) },
                    onSplit = { onSplitFiles(group.id, it) },
                    onRemoveFile = { onRemoveFile(group.id, it) },
                    onCreateWork = { onCreateWork(group.id) },
                )
            }
        }

        item("bottom-space") { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        text = "$title · $count fichier${if (count > 1) "s" else ""}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupCard(
    group: TelegramSearchGroupUi,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onRename: (String) -> Unit,
    onSplit: (Set<String>) -> Unit,
    onRemoveFile: (String) -> Unit,
    onCreateWork: () -> Unit,
) {
    var expanded by remember(group.id) { mutableStateOf(false) }
    var renaming by remember(group.id) { mutableStateOf(false) }
    var selectedFiles by remember(group.id) { mutableStateOf(emptySet<String>()) }
    val saved = group.savedWorkId != null
    val duplicates = group.duplicateKeys

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = { if (!saved) onToggleSelected() },
                enabled = !saved,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = group.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(
                    text = buildString {
                        append("${group.fileCount} fichier")
                        if (group.fileCount > 1) append("s")
                        if (group.episodeCount > 0) append(" · ${group.episodeCount} épisode")
                        if (group.episodeCount > 1) append("s")
                        val duplicateCount = group.files.size - group.files.distinctBy { it.duplicateKey }.size
                        if (duplicateCount > 0) append(" · $duplicateCount doublon(s)")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (saved) {
                Text(
                    text = "Ajoutée",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Button(onClick = onCreateWork, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Créer")
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Masquer les fichiers" else "Voir les fichiers")
            }
            TextButton(onClick = { renaming = true }, enabled = !saved) {
                Text("Renommer")
            }
        }

        if (expanded) {
            HorizontalDivider()
            val files = group.files
            val visibleSelection = selectedFiles.filter { key -> files.any { it.key == key } }.toSet()

            files.forEach { file ->
                FileRow(
                    file = file,
                    duplicate = file.key in duplicates,
                    showCheckbox = !saved,
                    checked = file.key in visibleSelection,
                    onCheckedChange = {
                        selectedFiles = if (file.key in visibleSelection) {
                            visibleSelection - file.key
                        } else {
                            visibleSelection + file.key
                        }
                    },
                    onRemove = { onRemoveFile(file.key) },
                )
                HorizontalDivider()
            }

            if (!saved && visibleSelection.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { selectedFiles = emptySet() }) {
                        Text("Annuler")
                    }
                    Button(
                        onClick = {
                            onSplit(visibleSelection)
                            selectedFiles = emptySet()
                        },
                        enabled = visibleSelection.size < files.size,
                    ) {
                        Text("Scinder la sélection")
                    }
                }
            }
        }
    }

    if (renaming) {
        var text by remember(group.id) { mutableStateOf(group.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renommer l'œuvre") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Titre") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(text)
                        renaming = false
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Text("Valider")
                }
            },
            dismissButton = {
                TextButton(onClick = { renaming = false }) {
                    Text("Annuler")
                }
            },
        )
    }
}

@Composable
private fun FileRow(
    file: TelegramSearchFileUi,
    duplicate: Boolean,
    showCheckbox: Boolean,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheckbox) {
            Checkbox(checked = checked, onCheckedChange = { onCheckedChange() })
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = file.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(
                text = buildString {
                    append(file.chatTitle)
                    val details = listOfNotNull(
                        file.language?.takeIf { it.isNotBlank() },
                        file.variant?.takeIf { it.isNotBlank() },
                        formatSize(file.sizeBytes),
                        if (duplicate) "doublon" else null,
                    )
                    if (details.isNotEmpty()) {
                        append(" · ")
                        append(details.joinToString(" · "))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (duplicate) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = if (duplicate) "Retirer ce doublon" else "Retirer le fichier",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Écran au repos
// ---------------------------------------------------------------------------

@Composable
private fun IdleContent(
    state: TelegramUiState,
    contentPadding: PaddingValues,
    onConnect: () -> Unit,
    onClickChannel: (Long) -> Unit,
    onClickIndexedWork: (TelegramWorkUi) -> Unit,
    onClickWork: (TelegramSavedWork) -> Unit,
    onRenameWork: (TelegramSavedWork) -> Unit,
    onResyncWork: (String) -> Unit,
    onDeleteWork: (TelegramSavedWork) -> Unit,
    onWatchWork: (TelegramSavedWork) -> Unit,
    onAniListWork: (TelegramSavedWork) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(0) }
    var showUnresolved by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val visibleChannels = remember(state.channels, state.enabledChatIds) {
        state.channels.filter { it.chatId in state.enabledChatIds }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = section) {
            Tab(
                selected = section == 0,
                onClick = { section = 0 },
                text = { Text("Œuvres (${state.works.size + state.savedWorks.size})") },
            )
            Tab(
                selected = section == 1,
                onClick = { section = 1 },
                text = { Text("Canaux (${visibleChannels.size})") },
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = contentPadding,
        ) {
            if (section == 0) {
                if (state.works.isEmpty() && state.unresolvedWorks.isEmpty() && state.savedWorks.isEmpty()) {
                    item("works-empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Aucune œuvre pour l'instant.",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ajoute un canal puis entre dans sa collection pour le synchroniser.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    if (state.unresolvedWorks.isNotEmpty()) {
                        item("unresolved-collection") {
                            UnresolvedWorksCard(
                                count = state.unresolvedWorks.size,
                                expanded = showUnresolved,
                                onClick = { showUnresolved = !showUnresolved },
                            )
                        }
                        if (showUnresolved) {
                            itemsIndexed(state.unresolvedWorks, key = { index, work ->
                                "unresolved-$index-${work.id}"
                            }) { _, work ->
                                IndexedWorkCard(work = work, onClick = { onClickIndexedWork(work) })
                            }
                        }
                    }

                    if (state.works.isNotEmpty()) {
                        item("works-header") {
                            Text(
                                text = "Œuvres reconnues",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(state.works, key = { index, work -> "indexed-$index-${work.id}" }) { _, work ->
                            IndexedWorkCard(work = work, onClick = { onClickIndexedWork(work) })
                        }
                    }

                    if (state.savedWorks.isNotEmpty()) {
                        item("saved-works-header") {
                            Text(
                                text = "Validées manuellement",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        itemsIndexed(state.savedWorks, key = { index, work -> "saved-$index-${work.id}" }) { _, work ->
                            SavedWorkCard(
                                work = work,
                                onClick = { onClickWork(work) },
                                onRename = { onRenameWork(work) },
                                onResync = { onResyncWork(work.id) },
                                onDelete = { onDeleteWork(work) },
                                onWatch = { onWatchWork(work) },
                                onMetadata = { onAniListWork(work) },
                            )
                        }
                    }
                }
            } else {
                if (visibleChannels.isEmpty()) {
                    item("channels-empty") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "Aucun canal ajouté.",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = onConnect) {
                                Text("Ajouter un canal")
                            }
                        }
                    }
                } else {
                    itemsIndexed(visibleChannels, key = { index, channel ->
                        "channel-$index-${channel.chatId}"
                    }) { _, channel ->
                        TelegramChannelRow(
                            channel = channel,
                            onClick = { onClickChannel(channel.chatId) },
                        )
                    }
                }
            }

            item("bottom-space") { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun UnresolvedWorksCard(
    count: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.telegram_baniere),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Œuvres non reconnues",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "$count œuvre${if (count > 1) "s" else ""} à vérifier",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun IndexedWorkCard(
    work: TelegramWorkUi,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = work.coverUrl,
            contentDescription = work.title,
            placeholder = painterResource(R.drawable.telegram_baniere),
            error = painterResource(R.drawable.telegram_baniere),
            fallback = painterResource(R.drawable.telegram_baniere),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
            )
            Text(
                text = "${work.kind} · ${work.files} fichiers" +
                    if (work.episodes > 0) " · ${work.episodes} épisodes" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (work.resolved) "Reconnue" else "À vérifier",
            style = MaterialTheme.typography.labelSmall,
            color = if (work.resolved) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

@Composable
private fun SavedWorkCard(
    work: TelegramSavedWork,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onResync: () -> Unit,
    onDelete: () -> Unit,
    onWatch: () -> Unit,
    onMetadata: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val details = buildString {
        append("${work.files.size} fichier")
        if (work.files.size > 1) append("s")
        if (work.kind == TelegramSavedKind.VIDEO) {
            if (work.episodeCount > 0) append(" · ${work.episodeCount} épisode")
            if (work.episodeCount > 1) append("s")
        } else {
            if (work.chapterCount > 0) append(" · ${work.chapterCount} chapitre")
            if (work.chapterCount > 1) append("s")
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = work.title.take(1).uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (work.kind == TelegramSavedKind.VIDEO) "Vidéo" else "Document",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (work.watchEnabled) {
                        Text(
                            text = "Veille ${formatInterval(work.watchIntervalMinutes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    work.resolution?.let { resolved ->
                        Text(
                            text = "${providerLabel(resolved.provider)} ${resolved.year?.let { "· $it" } ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Actions",
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Renommer") },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Resynchroniser") },
                        onClick = {
                            menuExpanded = false
                            onResync()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (work.watchEnabled) "Veille (activée)" else "Veille") },
                        onClick = {
                            menuExpanded = false
                            onWatch()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Métadonnées") },
                        onClick = {
                            menuExpanded = false
                            onMetadata()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer") },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun formatInterval(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)} j"
    else -> "${minutes / 60} h"
}

private fun providerLabel(provider: String?): String {
    if (provider == null) return TelegramMetadataProvider.ANILIST.label
    return runCatching { TelegramMetadataProvider.valueOf(provider).label }
        .getOrDefault(provider)
}

@Composable
private fun RenameWorkDialog(
    work: TelegramSavedWork,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember(work.id) { mutableStateOf(work.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Renommer l'œuvre") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Titre") },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank(),
            ) {
                Text("Renommer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun DeleteWorkDialog(
    work: TelegramSavedWork,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supprimer l'œuvre ?") },
        text = { Text("« ${work.title} » sera retirée de la bibliothèque et des œuvres Telegram.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Supprimer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun WatchWorkDialog(
    work: TelegramSavedWork,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Int) -> Unit,
) {
    var enabled by remember(work.id) { mutableStateOf(work.watchEnabled) }
    var interval by remember(work.id) { mutableStateOf(work.watchIntervalMinutes) }
    val intervals = listOf(15, 60, 6 * 60, 24 * 60)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Veille de l'œuvre") },
        text = {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Activer la veille", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    intervals.forEach { minutes ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { interval = minutes },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = interval == minutes,
                                onClick = { interval = minutes },
                            )
                            Text(formatInterval(minutes))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(enabled, interval) }) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun MetadataWorkDialog(
    work: TelegramSavedWork,
    onDismiss: () -> Unit,
    onConfirm: (String?, TelegramMetadataProvider) -> Unit,
) {
    var query by remember(work.id) {
        mutableStateOf(work.resolution?.title ?: work.title)
    }
    var provider by remember(work.id) {
        mutableStateOf(TelegramMetadataProvider.ANILIST)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Métadonnées") },
        text = {
            Column {
                work.resolution?.let { resolved ->
                    Text(
                        text = "Actuel : ${providerLabel(resolved.provider)} — " +
                            "${resolved.title.orEmpty()}${resolved.year?.let { " ($it)" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "Source",
                    style = MaterialTheme.typography.labelMedium,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TelegramMetadataProvider.values().toList().chunked(2).forEach { row ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { item ->
                                FilterChip(
                                    selected = provider == item,
                                    onClick = { provider = item },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Titre à rechercher") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "La fiche est mise à jour après la recherche, sans bloquer l'application.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(query.takeIf { it.isNotBlank() }, provider) },
            ) {
                Text("Chercher")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

// ---------------------------------------------------------------------------
// Connexion / canaux
// ---------------------------------------------------------------------------

@Composable
private fun TelegramConnectPrompt(onConnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(MR.strings.telegram_no_channels_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(MR.strings.telegram_no_channels_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onConnect) {
            Text(text = stringResource(MR.strings.telegram_connect))
        }
    }
}

@Composable
private fun TelegramEmptyPrompt(onConnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(MR.strings.telegram_no_channels_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(MR.strings.telegram_no_channels_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onConnect) {
            Text(text = stringResource(MR.strings.telegram_add_channel))
        }
    }
}

@Composable
private fun TelegramChannelRow(
    channel: TelegramChannelUi,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = channel.title.take(1).uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = "${channel.workCount} œuvres · ${channel.fileCount} fichiers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (channel.unknownCount > 0) {
                        Text(
                            text = "${channel.unknownCount} non reconnus",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        text = if (channel.lastSyncAt <= 0L) {
                            stringResource(MR.strings.telegram_never_synced)
                        } else {
                            DateUtils.getRelativeTimeSpanString(channel.lastSyncAt).toString()
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (channel.forcedWork != null) {
                        Text(
                            text = stringResource(MR.strings.telegram_force_work),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "taille inconnue"
    val mb = bytes / 1_048_576.0
    return if (mb >= 1024) {
        String.format(Locale.FRENCH, "%.2f Go", mb / 1024)
    } else {
        String.format(Locale.FRENCH, "%.1f Mo", mb)
    }
}
