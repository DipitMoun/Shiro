package eu.kanade.tachiyomi.ui.browse.manga.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.manga.MangaDiscoverScreen
import eu.kanade.presentation.browse.manga.components.MangaExtensionIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.data.multisource.MultiSourceMappingStore
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.discover.MangaDiscoverScreenModel
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.entries.manga.LocalMangaSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun Screen.mangaSourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { MangaDiscoverScreenModel() }
    val state by screenModel.state.collectAsState()

    val mappingStore: MultiSourceMappingStore = remember { Injekt.get() }
    val extensionManager: MangaExtensionManager = remember { Injekt.get() }
    val installedExtensions by extensionManager.installedExtensionsFlow.collectAsState(initial = emptyList())

    val allSourceIds = remember(installedExtensions) {
        installedExtensions.flatMap { it.sources }.map { it.id }.toSet()
    }
    var selectedSourceIds by remember(installedExtensions) {
        mutableStateOf(mappingStore.getSelectedSourceIds() ?: allSourceIds)
    }
    var showMappingSources by remember { mutableStateOf(false) }

    val toggleExtension: (MangaExtension.Installed) -> Unit = { extension ->
        val ids = extension.sources.map { it.id }.toSet()
        selectedSourceIds = if (selectedSourceIds.containsAll(ids)) {
            selectedSourceIds - ids
        } else {
            selectedSourceIds + ids
        }
        mappingStore.setSelectedSourceIds(selectedSourceIds)
    }

    val selectAllExtensions = {
        selectedSourceIds = allSourceIds
        mappingStore.setSelectedSourceIds(allSourceIds)
    }

    LaunchedEffect(Unit) {
        screenModel.load()
    }

    return TabContent(
        titleRes = AYMR.strings.label_manga_sources,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalMangaSearchScreen()) },
            ),
            AppBar.Action(
                title = "Extensions participantes",
                icon = Icons.Outlined.FilterList,
                onClick = { showMappingSources = true },
            ),
        ),
        content = { contentPadding, _ ->
            MangaDiscoverScreen(
                state = state,
                contentPadding = contentPadding,
                onRetry = screenModel::retry,
                onOpenTelegramSource = { /* TODO Phase B: navigator.push(TelegramSourceScreen()) */ },
                onOpenLocalSource = {
                    navigator.push(
                        BrowseMangaSourceScreen(
                            sourceId = LocalMangaSource.ID,
                            listingQuery = null,
                        ),
                    )
                },
                onOpenWork = { /* TODO Phase B: multisource manga */ },
            )

            if (showMappingSources) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showMappingSources = false },
                    sheetState = sheetState,
                ) {
                    MappingExtensionsSheetContent(
                        extensions = installedExtensions.filter { it.sources.isNotEmpty() },
                        selectedSourceIds = selectedSourceIds,
                        onToggle = toggleExtension,
                        onSelectAll = selectAllExtensions,
                    )
                }
            }
        },
    )
}

@Composable
private fun MappingExtensionsSheetContent(
    extensions: List<MangaExtension.Installed>,
    selectedSourceIds: Set<Long>,
    onToggle: (MangaExtension.Installed) -> Unit,
    onSelectAll: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Extensions participantes au mapping",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSelectAll) {
                Text("Tout activer")
            }
        }
        Text(
            text = "Seules les extensions activées seront interrogées pour attribuer les chapitres. " +
                "Les changements s'appliquent au prochain mapping ou via « Relancer ».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
            items(extensions, key = { it.pkgName }) { extension ->
                val sourceIds = extension.sources.map { it.id }
                val checked = sourceIds.isNotEmpty() && selectedSourceIds.containsAll(sourceIds)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MangaExtensionIcon(
                        extension = extension,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = extension.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (checked) "Participante" else "Exclue",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = { onToggle(extension) },
                    )
                }
                HorizontalDivider()
            }
        }
    }
}
