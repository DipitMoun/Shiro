package eu.kanade.tachiyomi.ui.browse.telegram

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.components.CollectionCard
import eu.kanade.presentation.library.components.collectionWorkCountLabel
import eu.kanade.presentation.telegram.TelegramAuthContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.telegram.index.TelegramMediaKind
import eu.kanade.tachiyomi.data.telegram.saved.TelegramMetadataProvider
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedKind
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWork
import eu.kanade.tachiyomi.data.telegram.saved.stableKey
import eu.kanade.tachiyomi.data.telegram.tdlib.TelegramAuthState
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed

/**
 * Page native de la source Telegram.
 *
 * Le rendu reprend celui d'une source d'animés classique :
 * barre d'outils, deux filtres « Œuvres » / « Canaux » et grille de couvertures.
 */
class TelegramSourceScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { TelegramScreenModel() }
        val state by model.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        var section by rememberSaveable { mutableIntStateOf(0) }
        var searchActive by rememberSaveable { mutableStateOf(false) }
        var showChannelSheet by rememberSaveable { mutableStateOf(false) }
        var showOmdbKey by rememberSaveable { mutableStateOf(false) }
        var actionWork by remember { mutableStateOf<TelegramWorkUi?>(null) }
        var mergeSource by remember { mutableStateOf<TelegramWorkUi?>(null) }
        var mergeQuery by remember { mutableStateOf("") }
        var renameSource by remember { mutableStateOf<TelegramWorkUi?>(null) }
        var renameText by remember { mutableStateOf("") }
        var selectionMode by rememberSaveable { mutableStateOf(false) }
        var selectedKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        var mergeSelection by remember { mutableStateOf<List<TelegramWorkUi>?>(null) }
        // Recherche : filtres Indexées / Non indexées, sélection multiple de
        // fichiers et cible (œuvre validée) pour l'ajout ou le retrait.
        var searchFilter by rememberSaveable { mutableIntStateOf(0) }
        var searchSelectionMode by rememberSaveable { mutableStateOf(false) }
        var searchSelectedGroupIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var searchTargetRequest by remember { mutableStateOf<SearchTargetRequest?>(null) }

        val selectedWorks = remember(state.works, selectedKeys) {
            state.works.filter { it.selectionKey() in selectedKeys }
        }
        val selectedSearchGroups = remember(state.searchGroups, searchSelectedGroupIds) {
            state.searchGroups.filter { it.id in searchSelectedGroupIds }
        }
        val selectedSearchFiles = remember(selectedSearchGroups) {
            selectedSearchGroups
                .flatMap { it.files }
                .distinctBy { it.key }
        }
        val visibleSearchGroups = remember(state.searchGroups, searchFilter) {
            when (searchFilter) {
                1 -> state.searchGroups.filter { it.indexed }
                2 -> state.searchGroups.filterNot { it.indexed }
                else -> state.searchGroups
            }
        }

        fun resetSearchSelection() {
            searchSelectionMode = false
            searchSelectedGroupIds = emptySet()
        }

        BackHandler(enabled = selectionMode || searchSelectionMode || searchActive) {
            when {
                searchSelectionMode -> resetSearchSelection()
                selectionMode -> {
                    selectionMode = false
                    selectedKeys = emptySet()
                }
                else -> {
                    searchActive = false
                    resetSearchSelection()
                    model.clearSearch()
                }
            }
        }

        val visibleChannels = remember(state.channels, state.enabledChatIds) {
            state.channels.filter { it.chatId in state.enabledChatIds }
        }
        val gridItems = if (section == 0) state.works else emptyList()

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                ) {
                    SearchToolbar(
                        searchQuery = if (searchActive) state.searchQuery else null,
                        onChangeSearchQuery = { query ->
                            if (query == null) {
                                searchActive = false
                                resetSearchSelection()
                                model.clearSearch()
                            } else {
                                selectionMode = false
                                selectedKeys = emptySet()
                                searchActive = true
                                model.setSearchQuery(query)
                            }
                        },
                        onSearch = { model.search() },
                        onClickCloseSearch = {
                            searchActive = false
                            resetSearchSelection()
                            model.clearSearch()
                        },
                        titleContent = {
                            AppBarTitle(
                                if (selectionMode) {
                                    "Sélection (${selectedWorks.size})"
                                } else {
                                    "Telegram"
                                },
                            )
                        },
                        navigateUp = navigator::pop,
                        actions = {
                            AppBarActions(
                                actions = persistentListOf<AppBar.AppBarAction>().builder()
                                    .apply {
                                        if (searchActive &&
                                            state.searchQuery.isNotBlank() &&
                                            !state.searchBusy
                                        ) {
                                            add(
                                                AppBar.Action(
                                                    title = "Rechercher",
                                                    icon = Icons.Outlined.Search,
                                                    onClick = model::search,
                                                ),
                                            )
                                        }
                                        if (searchActive &&
                                            !searchSelectionMode &&
                                            state.searchGroups.isNotEmpty()
                                        ) {
                                            add(
                                                AppBar.Action(
                                                    title = "Sélectionner",
                                                    icon = Icons.Outlined.SelectAll,
                                                    onClick = {
                                                        searchSelectionMode = true
                                                        searchSelectedGroupIds = emptySet()
                                                    },
                                                ),
                                            )
                                        }
                                        if (!searchActive &&
                                            section == 0 &&
                                            state.works.isNotEmpty() &&
                                            !selectionMode
                                        ) {
                                            add(
                                                AppBar.Action(
                                                    title = "Sélectionner",
                                                    icon = Icons.Outlined.SelectAll,
                                                    onClick = {
                                                        selectionMode = true
                                                        selectedKeys = emptySet()
                                                    },
                                                ),
                                            )
                                        }
                                        add(
                                            AppBar.Action(
                                                title = "Canaux",
                                                icon = Icons.Outlined.FilterList,
                                                onClick = {
                                                    if (state.authState is TelegramAuthState.Ready) {
                                                        showChannelSheet = true
                                                    } else {
                                                        navigator.push(TelegramSelectChannelsScreen(model))
                                                    }
                                                },
                                            ),
                                        )
                                        add(
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.telegram_sync),
                                                onClick = model::syncAll,
                                            ),
                                        )
                                        add(
                                            AppBar.OverflowAction(
                                                title = "Partager",
                                                onClick = {},
                                            ),
                                        )
                                        add(
                                            AppBar.OverflowAction(
                                                title = "Clé OMDb / IMDb",
                                                onClick = { showOmdbKey = true },
                                            ),
                                        )
                                        add(
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.telegram_logout),
                                                onClick = model::logout,
                                            ),
                                        )
                                    }
                                    .build(),
                            )
                        },
                    )

                    if (searchActive) {
                        TelegramSearchFilterRow(
                            groups = state.searchGroups,
                            selected = searchFilter,
                            onSelect = { searchFilter = it },
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = section == 0,
                                onClick = {
                                    section = 0
                                    selectionMode = false
                                    selectedKeys = emptySet()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = { Text("Œuvres") },
                            )
                            FilterChip(
                                selected = section == 1,
                                onClick = {
                                    section = 1
                                    selectionMode = false
                                    selectedKeys = emptySet()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = { Text("Canaux") },
                            )
                        }
                    }

                    HorizontalDivider()
                }
            },
            bottomBar = {
                if (selectionMode) {
                    SelectionActionBar(
                        count = selectedWorks.size,
                        onCancel = {
                            selectionMode = false
                            selectedKeys = emptySet()
                        },
                        onSelectAll = {
                            selectedKeys = state.works.map { it.selectionKey() }.toSet()
                        },
                        onMerge = {
                            if (selectedWorks.size >= 2) {
                                mergeSelection = selectedWorks
                            }
                        },
                    )
                } else if (searchActive && searchSelectionMode) {
                    SearchSelectionActionBar(
                        fileCount = selectedSearchFiles.size,
                        groupCount = selectedSearchGroups.size,
                        onCancel = { resetSearchSelection() },
                        onSelectAll = {
                            searchSelectedGroupIds = visibleSearchGroups.map { it.id }.toSet()
                        },
                        onAdd = {
                            val kinds = selectedSearchFiles.map { it.kind }.distinct()
                            if (selectedSearchFiles.isEmpty()) {
                                model.notify("Sélectionne au moins un fichier")
                            } else if (kinds.size > 1) {
                                model.notify(
                                    "Sélection mixte : ajoute séparément les vidéos et les documents.",
                                )
                            } else {
                                searchTargetRequest = SearchTargetRequest(
                                    mode = SearchTargetMode.ADD,
                                    files = selectedSearchFiles,
                                )
                            }
                        },
                        onRemove = {
                            if (selectedSearchFiles.isEmpty()) {
                                model.notify("Sélectionne au moins un fichier")
                            } else {
                                val keys = selectedSearchFiles.mapTo(mutableSetOf()) { it.key }
                                val targets = state.savedWorks.filter { work ->
                                    work.files.any { it.stableKey() in keys }
                                }
                                if (targets.isEmpty()) {
                                    model.notify(
                                        "Ces fichiers appartiennent à l'index du canal. " +
                                            "Le retrait n'est possible que sur une œuvre validée.",
                                    )
                                } else {
                                    searchTargetRequest = SearchTargetRequest(
                                        mode = SearchTargetMode.REMOVE,
                                        files = selectedSearchFiles,
                                    )
                                }
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            LaunchedEffect(state.message) {
                val message = state.message ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(message)
                model.consumeMessage()
            }

            when {
                state.authState !is TelegramAuthState.Ready -> {
                    TelegramAuthContent(
                        authState = state.authState,
                        loadingChats = state.loadingChats,
                        chats = state.chats,
                        onSendPhone = model::sendPhoneNumber,
                        onSendCode = model::sendCode,
                        onCheckPassword = model::checkPassword,
                        onResendCode = model::resendCode,
                        onRetry = model::retry,
                        onLoadChats = model::loadChats,
                    )
                }
                state.channels.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Aucun canal Telegram activé.",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        TextButton(onClick = { showChannelSheet = true }) {
                            Text("Ajouter des canaux")
                        }
                    }
                }
                searchActive -> {
                    TelegramSearchGrid(
                        state = state,
                        groups = visibleSearchGroups,
                        contentPadding = contentPadding,
                        selectionMode = searchSelectionMode,
                        selectedGroupIds = searchSelectedGroupIds,
                        onToggleGroup = { group ->
                            searchSelectedGroupIds = if (group.id in searchSelectedGroupIds) {
                                searchSelectedGroupIds - group.id
                            } else {
                                searchSelectedGroupIds + group.id
                            }
                        },
                        onLongPressGroup = { group ->
                            searchSelectionMode = true
                            searchSelectedGroupIds = if (group.id in searchSelectedGroupIds) {
                                searchSelectedGroupIds
                            } else {
                                searchSelectedGroupIds + group.id
                            }
                        },
                        onSearch = model::search,
                        onContinueSearch = model::continueSearch,
                        onCreateWork = model::createWork,
                    )
                }
                section == 0 -> {
                    TelegramWorkGrid(
                        works = gridItems,
                        contentPadding = contentPadding,
                        selectionMode = selectionMode,
                        selectedKeys = selectedKeys,
                        onClick = { work ->
                            if (selectionMode) {
                                toggleWorkSelection(work, selectedKeys) { selectedKeys = it }
                            } else {
                                model.openWork(work) { animeId ->
                                    navigator.push(AnimeScreen(animeId, fromSource = true))
                                }
                            }
                        },
                        onWorkLongClick = { work ->
                            if (selectionMode) {
                                toggleWorkSelection(work, selectedKeys) { selectedKeys = it }
                            } else {
                                selectionMode = true
                                selectedKeys = setOf(work.selectionKey())
                            }
                        },
                        onWorkMenu = { actionWork = it },
                    )
                }
                else -> {
                    TelegramChannelGrid(
                        channels = visibleChannels,
                        contentPadding = contentPadding,
                        onClick = { channel ->
                            navigator.push(TelegramChannelScreen(model, channel.chatId))
                        },
                        onSync = { channel -> model.syncChannelNow(channel.chatId) },
                        onImport = { channel ->
                            model.importChannel(channel.chatId, includeUnknown = false, includeDocuments = false)
                        },
                        onOpenUnresolved = { channel ->
                            navigator.push(
                                TelegramChannelScreen(model, channel.chatId, unresolvedOnly = true),
                            )
                        },
                        onScanFullHistory = { channel -> model.scanFullHistory(channel.chatId) },
                        onJoin = { channel -> model.joinChannel(channel.chatId) },
                        onDisable = { channel -> model.setChannelEnabled(channel.chatId, false) },
                        onRemove = { channel -> model.removeChannel(channel.chatId) },
                    )
                }
            }
        }

        if (showOmdbKey) {
            var apiKey by remember(state.omdbApiKey) { mutableStateOf(state.omdbApiKey) }
            AlertDialog(
                onDismissRequest = { showOmdbKey = false },
                title = { Text("Clé OMDb / IMDb") },
                text = {
                    Column {
                        Text(
                            text = "Clé gratuite disponible sur omdbapi.com. " +
                                "Utilisée uniquement pour les métadonnées IMDb.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            singleLine = true,
                            label = { Text("API key") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            model.setOmdbApiKey(apiKey)
                            showOmdbKey = false
                        },
                    ) {
                        Text("Enregistrer")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showOmdbKey = false }) {
                        Text("Annuler")
                    }
                },
            )
        }

        if (showChannelSheet) {
            ModalBottomSheet(
                onDismissRequest = { showChannelSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                AddChannelsSheet(
                    chats = state.chats,
                    loading = state.loadingChats,
                    selected = state.videoChats,
                    onReload = model::loadChats,
                    onConfirm = { selection ->
                        model.saveSelection(selection, state.docChats)
                        showChannelSheet = false
                    },
                )
            }
        }

        actionWork?.let { work ->
            ModalBottomSheet(
                onDismissRequest = { actionWork = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                WorkActionsSheet(
                    work = work,
                    onClose = { actionWork = null },
                    onMerge = {
                        mergeSource = work
                        mergeQuery = ""
                        actionWork = null
                    },
                    onSeparate = {
                        model.unmergeWork(work)
                        actionWork = null
                    },
                    onRename = {
                        renameSource = work
                        renameText = work.title
                        actionWork = null
                    },
                    onReset = {
                        model.resetWork(work)
                        actionWork = null
                    },
                    onHide = {
                        model.hideWork(work)
                        actionWork = null
                    },
                    onRecognize = { provider ->
                        model.recognizeWork(work, provider)
                        actionWork = null
                    },
                )
            }
        }

        mergeSource?.let { source ->
            val candidates = state.works
                .filter {
                    it.kind == source.kind &&
                        it.workKey != source.workKey &&
                        (mergeQuery.isBlank() || it.title.contains(mergeQuery, ignoreCase = true))
                }
                .sortedBy { it.title.lowercase() }
            ModalBottomSheet(
                onDismissRequest = { mergeSource = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "Fusionner « ${source.title} »",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mergeQuery,
                        onValueChange = { mergeQuery = it },
                        singleLine = true,
                        label = { Text("Rechercher une œuvre") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    if (candidates.isEmpty()) {
                        Text(
                            text = "Aucune autre œuvre compatible.",
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            itemsIndexed(
                                candidates,
                                key = { index, target -> "candidate-${target.id}-${target.workKey}-$index" },
                            ) { _, target ->
                                WorkPickerRow(
                                    work = target,
                                    onClick = {
                                        model.mergeWorks(source, target)
                                        mergeSource = null
                                    },
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { mergeSource = null },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Annuler")
                    }
                }
            }
        }

        mergeSelection?.let { works ->
            ModalBottomSheet(
                onDismissRequest = { mergeSelection = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Fusionner ${works.size} œuvres",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Choisis le titre et la couverture à conserver. " +
                            "Les fichiers identiques ne seront pas dupliqués.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    ) {
                        itemsIndexed(
                            works,
                            key = { index, work -> "merge-target-${work.selectionKey()}-$index" },
                        ) { _, target ->
                            WorkPickerRow(
                                work = target,
                                onClick = {
                                    model.mergeWorks(target, works)
                                    mergeSelection = null
                                    selectionMode = false
                                    selectedKeys = emptySet()
                                },
                            )
                        }
                    }
                    TextButton(
                        onClick = { mergeSelection = null },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Annuler")
                    }
                }
            }
        }

        renameSource?.let { work ->
            AlertDialog(
                onDismissRequest = { renameSource = null },
                title = { Text("Renommer l'œuvre") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Titre") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            model.renameWork(work, renameText)
                            renameSource = null
                        },
                    ) {
                        Text("Enregistrer")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameSource = null }) {
                        Text("Annuler")
                    }
                },
            )
        }

        searchTargetRequest?.let { request ->
            val keys = request.files.mapTo(mutableSetOf()) { it.key }
            val candidates = state.savedWorks.filter { work ->
                when (request.mode) {
                    SearchTargetMode.ADD -> {
                        val expected = if (request.files.any { it.kind == TelegramMediaKind.DOCUMENT }) {
                            TelegramSavedKind.DOCUMENT
                        } else {
                            TelegramSavedKind.VIDEO
                        }
                        work.kind == expected
                    }
                    SearchTargetMode.REMOVE -> work.files.any { it.stableKey() in keys }
                }
            }
            ModalBottomSheet(
                onDismissRequest = { searchTargetRequest = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (request.mode == SearchTargetMode.ADD) {
                            "Ajouter à une œuvre"
                        } else {
                            "Retirer de l'œuvre"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${request.files.size} fichier(s) sélectionné(s). " +
                            "Seules les œuvres validées sont modifiables : " +
                            "l'index des canaux est reconstruit à chaque synchronisation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (candidates.isEmpty()) {
                        Text(
                            text = if (request.mode == SearchTargetMode.ADD) {
                                "Aucune œuvre validée compatible. Crée d'abord une œuvre depuis un résultat."
                            } else {
                                "Aucune œuvre validée ne contient ces fichiers."
                            },
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                        ) {
                            items(candidates, key = { "search-target-${it.id}" }) { target ->
                                WorkTargetRow(
                                    work = target,
                                    onClick = {
                                        when (request.mode) {
                                            SearchTargetMode.ADD ->
                                                model.addFilesToSavedWork(target.id, request.files)
                                            SearchTargetMode.REMOVE ->
                                                model.removeFilesFromSavedWork(target.id, request.files)
                                        }
                                        searchTargetRequest = null
                                        resetSearchSelection()
                                    },
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { searchTargetRequest = null },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Annuler")
                    }
                }
            }
        }
    }
}

private enum class SearchTargetMode {
    ADD,
    REMOVE,
}

private data class SearchTargetRequest(
    val mode: SearchTargetMode,
    val files: List<TelegramSearchFileUi>,
)

private enum class WorkActionsPage {
    MAIN,
    ORGANISATION,
    RECOGNITION,
}

@Composable
private fun SelectionActionBar(
    count: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onMerge: () -> Unit,
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = "Annuler la sélection")
            }
            Text(
                text = "$count sélectionnée" + if (count > 1) "s" else "",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll) {
                Text("Tout")
            }
            TextButton(
                onClick = onMerge,
                enabled = count >= 2,
            ) {
                Text("Fusionner")
            }
        }
    }
}

@Composable
private fun WorkActionsSheet(
    work: TelegramWorkUi,
    onClose: () -> Unit,
    onMerge: () -> Unit,
    onSeparate: () -> Unit,
    onRename: () -> Unit,
    onReset: () -> Unit,
    onHide: () -> Unit,
    onRecognize: (TelegramMetadataProvider) -> Unit,
) {
    var page by remember { mutableStateOf(WorkActionsPage.MAIN) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        when (page) {
            WorkActionsPage.MAIN -> {
                WorkSheetHeader(work)
                Spacer(Modifier.height(12.dp))
                SheetActionRow(
                    icon = Icons.Outlined.Settings,
                    title = "Organisation",
                    subtitle = "Fusionner, séparer, renommer",
                    onClick = { page = WorkActionsPage.ORGANISATION },
                )
                SheetActionRow(
                    icon = Icons.Outlined.Search,
                    title = "Reconnaissance",
                    subtitle = "AniList, OMDb, MAL, Kitsu, Shikimori",
                    onClick = { page = WorkActionsPage.RECOGNITION },
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                    Text("Fermer")
                }
            }

            WorkActionsPage.ORGANISATION -> {
                WorkSheetPageHeader("Organisation") { page = WorkActionsPage.MAIN }
                SheetActionRow(
                    icon = Icons.Outlined.Add,
                    title = "Fusionner avec une autre œuvre",
                    subtitle = "Choisir une cible et ajouter ses fichiers",
                    onClick = onMerge,
                )
                SheetActionRow(
                    icon = Icons.Outlined.Close,
                    title = "Séparer les variantes fusionnées",
                    subtitle = "Restaurer les œuvres d'origine",
                    onClick = onSeparate,
                )
                SheetActionRow(
                    icon = Icons.Outlined.Edit,
                    title = "Renommer",
                    subtitle = "Conserver un titre manuel",
                    onClick = onRename,
                )
                SheetActionRow(
                    icon = Icons.Outlined.Refresh,
                    title = "Réinitialiser les corrections",
                    onClick = onReset,
                )
                SheetActionRow(
                    icon = Icons.Outlined.VisibilityOff,
                    title = "Masquer de la source",
                    onClick = onHide,
                )
            }

            WorkActionsPage.RECOGNITION -> {
                WorkSheetPageHeader("Reconnaissance") { page = WorkActionsPage.MAIN }
                RecognitionRow("AniList", "Métadonnées et couverture", TelegramMetadataProvider.ANILIST, onRecognize)
                RecognitionRow("OMDb / IMDb", "Fiche IMDb", TelegramMetadataProvider.OMDB, onRecognize)
                RecognitionRow("MyAnimeList", "MAL", TelegramMetadataProvider.MAL, onRecognize)
                RecognitionRow("Kitsu", "Kitsu", TelegramMetadataProvider.KITSU, onRecognize)
                RecognitionRow("Shikimori", "Shikimori", TelegramMetadataProvider.SHIKIMORI, onRecognize)
            }
        }
    }
}

@Composable
private fun WorkSheetHeader(work: TelegramWorkUi) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = work.coverUrl,
            contentDescription = work.title,
            placeholder = painterResource(R.drawable.telegram_baniere),
            error = painterResource(R.drawable.telegram_baniere),
            fallback = painterResource(R.drawable.telegram_baniere),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(52.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = work.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${work.files} fichiers · ${work.episodes} épisodes uniques",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (work.resolved) "Reconnue" else "Non reconnue",
                style = MaterialTheme.typography.labelSmall,
                color = if (work.resolved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

@Composable
private fun WorkSheetPageHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text("‹ Retour")
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SheetActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecognitionRow(
    title: String,
    subtitle: String,
    provider: TelegramMetadataProvider,
    onRecognize: (TelegramMetadataProvider) -> Unit,
) {
    SheetActionRow(
        icon = Icons.Outlined.Search,
        title = title,
        subtitle = subtitle,
        onClick = { onRecognize(provider) },
    )
}

@Composable
private fun WorkPickerRow(
    work: TelegramWorkUi,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = work.coverUrl,
                contentDescription = work.title,
                placeholder = painterResource(R.drawable.telegram_baniere),
                error = painterResource(R.drawable.telegram_baniere),
                fallback = painterResource(R.drawable.telegram_baniere),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(42.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = work.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${work.files} fichiers · ${work.episodes} épisodes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TelegramSearchFilterRow(
    groups: List<TelegramSearchGroupUi>,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val indexedFiles = groups.filter { it.indexed }.sumOf { it.fileCount }
    val unindexedFiles = groups.filterNot { it.indexed }.sumOf { it.fileCount }
    val total = indexedFiles + unindexedFiles

    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == 0,
            onClick = { onSelect(0) },
            label = { Text("Tout ($total)") },
        )
        FilterChip(
            selected = selected == 1,
            onClick = { onSelect(1) },
            label = { Text("Indexés ($indexedFiles)") },
        )
        FilterChip(
            selected = selected == 2,
            onClick = { onSelect(2) },
            label = { Text("Non indexés ($unindexedFiles)") },
        )
    }
}

@Composable
private fun SearchSelectionActionBar(
    fileCount: Int,
    groupCount: Int,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = "Annuler la sélection")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = "$fileCount fichier" + if (fileCount > 1) "s" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "$groupCount résultat" + if (groupCount > 1) "s" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSelectAll) {
                Text("Tout")
            }
            TextButton(onClick = onRemove, enabled = fileCount > 0) {
                Text("Retirer")
            }
            TextButton(onClick = onAdd, enabled = fileCount > 0) {
                Text("Ajouter")
            }
        }
    }
}

@Composable
private fun WorkTargetRow(
    work: TelegramSavedWork,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = work.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "${work.files.size} fichiers · ${work.episodeCount} épisodes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TelegramSearchGrid(
    state: TelegramUiState,
    groups: List<TelegramSearchGroupUi>,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    selectedGroupIds: Set<String>,
    onToggleGroup: (TelegramSearchGroupUi) -> Unit,
    onLongPressGroup: (TelegramSearchGroupUi) -> Unit,
    onSearch: () -> Unit,
    onContinueSearch: () -> Unit,
    onCreateWork: (String) -> Unit,
) {
    val padding = PaddingValues(
        top = contentPadding.calculateTopPadding() + 8.dp,
        bottom = contentPadding.calculateBottomPadding() + 8.dp,
        start = 8.dp,
        end = 8.dp,
    )

    if (state.searchBusy && groups.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Recherche dans les canaux sélectionnés…",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = buildString {
                    append("${state.searchProgressDone}/${state.searchProgressTotal} canaux")
                    if (state.searchProgressChannel.isNotBlank()) {
                        append(" · ${state.searchProgressChannel}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (!state.searchBusy && !state.searchDone) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (state.searchQuery.isBlank()) {
                    "Rechercher dans les canaux Telegram"
                } else {
                    "Prêt à rechercher"
                },
                style = MaterialTheme.typography.titleSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                text = if (state.searchQuery.isBlank()) {
                    "Saisis un titre ou un nom de fichier. " +
                        "Seuls tes ${state.enabledChatIds.size} canaux ajoutés sont interrogés."
                } else {
                    "Appuie sur Entrée ou sur la loupe pour lancer la recherche."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }

    if (!state.searchBusy && state.searchDone && groups.isEmpty()) {
        val filteredOut = state.searchGroups.isNotEmpty()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (filteredOut) {
                    "Aucun résultat avec ce filtre"
                } else {
                    "Aucun résultat pour « ${state.searchQuery.trim()} »"
                },
                style = MaterialTheme.typography.titleSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                text = if (filteredOut) {
                    "Change de filtre ou relance la recherche sur les canaux sélectionnés."
                } else {
                    "Vérifie l'orthographe ou relance la recherche sur les canaux sélectionnés."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (state.searchErrors.isNotEmpty()) {
                Text(
                    text = state.searchErrors.first(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onSearch) {
                Text("Relancer")
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (state.searchBusy) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "search-progress") {
                Column(Modifier.padding(vertical = 8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = buildString {
                            append("${state.searchProgressDone}/${state.searchProgressTotal} canaux")
                            if (state.searchProgressChannel.isNotBlank()) {
                                append(" · ${state.searchProgressChannel}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.searchErrors.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "search-errors") {
                Text(
                    text = state.searchErrors.first(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        if (state.searchDone) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "search-stats") {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(
                        text = "${state.searchIndexedChannels} canaux indexés · " +
                            "${state.searchLocalMatches} fichiers locaux · " +
                            "${state.searchServerMatches} résultats serveur",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!selectionMode) {
                        Text(
                            text = "Appuie sur un résultat pour sélectionner ses fichiers, " +
                                "puis ajoute-les ou retire-les d'une œuvre.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        gridItemsIndexed(groups, key = { index, group -> "search-${group.id}-$index" }) { _, group ->
            TelegramSearchCard(
                group = group,
                selectionMode = selectionMode,
                selected = group.id in selectedGroupIds,
                onClick = {
                    if (selectionMode) {
                        onToggleGroup(group)
                    } else {
                        // Un simple appui sélectionne le résultat au lieu de
                        // créer immédiatement une œuvre dans la bibliothèque.
                        // La création reste explicite via « Créer l'œuvre ».
                        onLongPressGroup(group)
                    }
                },
                onLongClick = { onLongPressGroup(group) },
                onCreate = { onCreateWork(group.id) },
            )
        }
        if (state.searchHasMore) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "search-more") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "${state.searchLimit} fichiers par canal · résultats serveur",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onContinueSearch) {
                        Text("Afficher plus de résultats")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TelegramSearchCard(
    group: TelegramSearchGroupUi,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            AsyncImage(
                model = group.coverUrl,
                contentDescription = group.title,
                placeholder = painterResource(R.drawable.telegram_baniere),
                error = painterResource(R.drawable.telegram_baniere),
                fallback = painterResource(R.drawable.telegram_baniere),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Text(
                text = if (group.indexed) "Indexée" else "Non indexé",
                style = MaterialTheme.typography.labelSmall,
                color = if (group.indexed) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (group.indexed) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription = if (selected) "Sélectionné" else "Non sélectionné",
                        tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
        Text(
            text = "${group.fileCount} fichier(s) · ${group.episodeCount} épisode(s)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (!selectionMode && group.savedWorkId == null) {
            TextButton(onClick = onCreate) {
                Text("Créer l'œuvre")
            }
        } else if (group.savedWorkId != null) {
            Text(
                text = "Ajoutée",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun AddChannelsSheet(
    chats: List<TelegramChatItem>,
    loading: Boolean,
    selected: Set<Long>,
    onReload: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    var selection by remember(chats) { mutableStateOf(selected) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Canaux à ajouter",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Active les canaux vidéo à suivre dans la source Telegram.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(24.dp),
            )
        } else if (chats.isEmpty()) {
            TextButton(
                onClick = onReload,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Charger les canaux")
            }
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(chats, key = { it.id }) { chat ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = chat.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = chat.id in selection,
                            onCheckedChange = { checked ->
                                selection = if (checked) selection + chat.id else selection - chat.id
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }

            TextButton(
                onClick = { onConfirm(selection) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Valider")
            }
        }
    }
}

@Composable
private fun TelegramWorkGrid(
    works: List<TelegramWorkUi>,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    selectedKeys: Set<String>,
    onClick: (TelegramWorkUi) -> Unit,
    onWorkLongClick: (TelegramWorkUi) -> Unit,
    onWorkMenu: (TelegramWorkUi) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
            start = 8.dp,
            end = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        gridItemsIndexed(works, key = { index, work -> "work-${work.id}-${work.workKey}-$index" }) { _, work ->
            TelegramGridCard(
                title = work.title,
                coverUrl = work.coverUrl,
                selectionMode = selectionMode,
                selected = work.selectionKey() in selectedKeys,
                onClick = { onClick(work) },
                onLongClick = { onWorkLongClick(work) },
                onMenu = { onWorkMenu(work) },
            )
        }
    }
}

@Composable
private fun TelegramChannelGrid(
    channels: List<TelegramChannelUi>,
    contentPadding: PaddingValues,
    onClick: (TelegramChannelUi) -> Unit,
    onSync: (TelegramChannelUi) -> Unit,
    onImport: (TelegramChannelUi) -> Unit,
    onOpenUnresolved: (TelegramChannelUi) -> Unit,
    onScanFullHistory: (TelegramChannelUi) -> Unit,
    onJoin: (TelegramChannelUi) -> Unit,
    onDisable: (TelegramChannelUi) -> Unit,
    onRemove: (TelegramChannelUi) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 8.dp,
            start = 8.dp,
            end = 8.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(channels, key = { "channel-${it.chatId}" }) { channel ->
            TelegramChannelCard(
                channel = channel,
                onClick = { onClick(channel) },
                onSync = { onSync(channel) },
                onImport = { onImport(channel) },
                onOpenUnresolved = { onOpenUnresolved(channel) },
                onScanFullHistory = { onScanFullHistory(channel) },
                onJoin = { onJoin(channel) },
                onDisable = { onDisable(channel) },
                onRemove = { onRemove(channel) },
            )
        }
    }
}

@Composable
private fun TelegramChannelCard(
    channel: TelegramChannelUi,
    onClick: () -> Unit,
    onSync: () -> Unit,
    onImport: () -> Unit,
    onOpenUnresolved: () -> Unit,
    onScanFullHistory: () -> Unit,
    onJoin: () -> Unit,
    onDisable: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var removeConfirm by remember { mutableStateOf(false) }

    Box {
        CollectionCard(
            title = channel.title,
            subtitle = collectionWorkCountLabel(channel.videoWorks),
            previewImages = channel.previewCovers,
            bannerModel = null,
            onClick = onClick,
            onImageClick = { onClick() },
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreVert,
                    contentDescription = "Actions du canal",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Ouvrir la collection") },
                onClick = {
                    menuExpanded = false
                    onClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Synchroniser le canal") },
                onClick = {
                    menuExpanded = false
                    onSync()
                },
            )
            DropdownMenuItem(
                text = { Text("Importer dans la bibliothèque") },
                onClick = {
                    menuExpanded = false
                    onImport()
                },
            )
            if (channel.unknownVideo > 0) {
                DropdownMenuItem(
                    text = { Text("Non reconnus (${channel.unknownVideo})") },
                    onClick = {
                        menuExpanded = false
                        onOpenUnresolved()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Rescanner l'historique") },
                onClick = {
                    menuExpanded = false
                    onScanFullHistory()
                },
            )
            if (channel.isMember == false) {
                DropdownMenuItem(
                    text = { Text("Rejoindre le canal") },
                    onClick = {
                        menuExpanded = false
                        onJoin()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Masquer le canal") },
                onClick = {
                    menuExpanded = false
                    onDisable()
                },
            )
            DropdownMenuItem(
                text = { Text("Retirer le canal") },
                onClick = {
                    menuExpanded = false
                    removeConfirm = true
                },
            )
        }
    }

    if (removeConfirm) {
        AlertDialog(
            onDismissRequest = { removeConfirm = false },
            title = { Text("Retirer le canal ?") },
            text = {
                Text(
                    "« ${channel.title} » sera retiré du suivi. " +
                        "L'index local est supprimé ; aucune œuvre de la bibliothèque n'est effacée.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeConfirm = false
                        onRemove()
                    },
                ) {
                    Text("Retirer")
                }
            },
            dismissButton = {
                TextButton(onClick = { removeConfirm = false }) {
                    Text("Annuler")
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TelegramGridCard(
    title: String,
    coverUrl: String?,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenu: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (selected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(6.dp),
                    )
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = title,
            placeholder = painterResource(R.drawable.telegram_baniere),
            error = painterResource(R.drawable.telegram_baniere),
            fallback = painterResource(R.drawable.telegram_baniere),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0x99000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = if (selected) "Sélectionnée" else "Non sélectionnée",
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = "Actions de l'œuvre",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        1f to Color(0xAA000000),
                    ),
                ),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        )
    }
}

private fun TelegramWorkUi.selectionKey(): String = workKey.ifBlank { id }

private fun toggleWorkSelection(
    work: TelegramWorkUi,
    current: Set<String>,
    onChanged: (Set<String>) -> Unit,
) {
    val key = work.selectionKey()
    onChanged(if (key in current) current - key else current + key)
}
