package eu.kanade.presentation.library.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.library.components.CollectionCard
import eu.kanade.presentation.library.components.CollectionDetailHeader
import eu.kanade.presentation.library.components.CollectionEditDialog
import eu.kanade.presentation.library.components.LibraryTabs
import eu.kanade.presentation.library.components.RecentActivityCarousel
import eu.kanade.presentation.library.components.RecentCollection
import eu.kanade.presentation.library.components.RecentWork
import eu.kanade.presentation.library.components.SubCategorySquare
import eu.kanade.presentation.library.components.progressPercent
import eu.kanade.tachiyomi.data.collection.CollectionMetadataManager
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.material.PullRefresh
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration.Companion.seconds

@Composable
fun AnimeLibraryContent(
    categories: List<Category>,
    searchQuery: String?,
    selection: List<LibraryAnime>,
    contentPadding: PaddingValues,
    currentCategory: Category?,
    currentPage: () -> Int,
    hasActiveFilters: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    onCategoryClick: (Category) -> Unit,
    onNavigateBack: () -> Unit,
    onAnimeClicked: (Long) -> Unit,
    onContinueWatchingClicked: ((LibraryAnime) -> Unit)?,
    onToggleSelection: (LibraryAnime) -> Unit,
    onToggleRangeSelection: (LibraryAnime) -> Unit,
    onRefresh: (Category?) -> Boolean,
    onGlobalSearchClicked: () -> Unit,
    getNumberOfAnimeForCategory: (Category) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getAnimeLibraryForPage: (Int) -> List<AnimeLibraryItem>,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(
                top = contentPadding.calculateTopPadding(),
                start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
            ),
        ) {
            val scope = rememberCoroutineScope()
            var isRefreshing by remember(currentCategory) { mutableStateOf(false) }

            if (currentCategory != null) {
                val metadataManager: CollectionMetadataManager by injectLazy()
                var metadata by remember(currentCategory.id) {
                    mutableStateOf(metadataManager.getMetadata(currentCategory.id))
                }
                var showEditDialog by remember { mutableStateOf(false) }
                val entryCount = if (categories.indexOf(currentCategory) != -1) {
                    getAnimeLibraryForPage(categories.indexOf(currentCategory)).size
                } else {
                    0
                }

                CollectionDetailHeader(
                    title = currentCategory.name,
                    description = metadata.description,
                    bannerUrl = metadata.bannerUrl,
                    statsText = "$entryCount titres",
                    onBack = onNavigateBack,
                    onEdit = { showEditDialog = true },
                )

                if (showEditDialog) {
                    CollectionEditDialog(
                        initialBannerUrl = metadata.bannerUrl.orEmpty(),
                        initialDescription = metadata.description,
                        onDismiss = { showEditDialog = false },
                        onSave = { url, desc ->
                            metadataManager.updateMetadata(
                                categoryId = currentCategory.id,
                                bannerUrl = url.takeIf { it.isNotBlank() },
                                description = desc,
                            )
                            metadata = metadataManager.getMetadata(currentCategory.id)
                            showEditDialog = false
                        },
                    )
                }

                LaunchedEffect(currentCategory.id) {
                    metadataManager.markOpened(currentCategory.id)
                }
            } else {
                val metadataManager: CollectionMetadataManager by injectLazy()
                val recentColls = metadataManager.getRecentCollections()
                val currentEntries = categories
                    .filter { it.parentId == null }
                    .flatMap { getAnimeLibraryForPage(categories.indexOf(it)) }
                    .filter { it.libraryAnime.lastSeen > 0L }
                    .sortedByDescending { it.libraryAnime.lastSeen }
                    .take(3)

                RecentActivityCarousel(
                    recentWorks = currentEntries.map {
                        RecentWork(
                            id = it.libraryAnime.anime.id,
                            title = it.libraryAnime.anime.title,
                            coverUrl = it.libraryAnime.anime.thumbnailUrl,
                            progressPercent = it.progressPercent(),
                        )
                    },
                    recentCollections = recentColls.map { c ->
                        val cat = categories.find { it.id == c.categoryId }
                        RecentCollection(
                            id = c.categoryId,
                            name = cat?.name ?: "Collection",
                            bannerUrl = c.bannerUrl,
                        )
                    },
                    onWorkClick = { animeId -> onAnimeClicked(animeId) },
                    onCollectionClick = { catId ->
                        categories.find { it.id == catId }?.let { onCategoryClick(it) }
                    },
                )
            }

            val notSelectionMode = selection.isEmpty()
            val onClickAnimeInternal = { anime: LibraryAnime ->
                if (notSelectionMode) {
                    onAnimeClicked(anime.anime.id)
                } else {
                    onToggleSelection(anime)
                }
            }

            val childCategories = categories.filter { it.parentId == currentCategory?.id }

            val entries = if (currentCategory != null) {
                val pageIndex = categories.indexOf(currentCategory)
                if (pageIndex != -1) getAnimeLibraryForPage(pageIndex) else emptyList()
            } else {
                val defaultCategory = categories.find { it.id == 0L }
                if (defaultCategory != null && defaultCategory.parentId == null) {
                    val pageIndex = categories.indexOf(defaultCategory)
                    if (pageIndex != -1) getAnimeLibraryForPage(pageIndex) else emptyList()
                } else {
                    emptyList()
                }
            }

            val categoryCollections = remember(categories, currentCategory) {
                categories.filter { it.parentId == null || it.parentId == currentCategory?.id }
            }

            PullRefresh(
                refreshing = isRefreshing,
                onRefresh = {
                    val started = onRefresh(currentCategory)
                    if (!started) return@PullRefresh
                    scope.launch {
                        isRefreshing = true
                        delay(1.seconds)
                        isRefreshing = false
                    }
                },
                enabled = notSelectionMode,
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(categoryCollections) { cat ->
                        val pageIndex = categories.indexOf(cat)
                        val previewImages = if (pageIndex != -1) {
                            getAnimeLibraryForPage(pageIndex).take(4).map { it.libraryAnime.anime.thumbnailUrl }
                        } else {
                            emptyList()
                        }
                        val subCats = categories
                            .filter { it.parentId == cat.id }
                            .map { sub ->
                                SubCategorySquare(
                                    id = sub.id,
                                    name = sub.name,
                                    count = getNumberOfAnimeForCategory(sub) ?: 0,
                                )
                            }
                        CollectionCard(
                            title = cat.name,
                            subCategories = subCats,
                            previewImages = previewImages,
                            onClick = { onCategoryClick(cat) },
                            onSubCategoryClick = { subId ->
                                categories.find { it.id == subId }?.let { onCategoryClick(it) }
                            },
                        )
                    }
                }
            }
        }

        var showBottomSheet by remember { mutableStateOf(false) }
        val sheetState = rememberModalBottomSheetState()

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    categories.forEach { cat ->
                        Text(
                            text = cat.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .clickable {
                                    onCategoryClick(cat)
                                    showBottomSheet = false
                                }
                                .padding(vertical = 8.dp),
                        )
                        categories.filter { it.parentId == cat.id }.forEach { sub ->
                            Text(
                                text = "  • ${sub.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .clickable {
                                        onCategoryClick(sub)
                                        showBottomSheet = false
                                    }
                                    .padding(start = 16.dp, top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        if (selection.isEmpty()) {
            FloatingActionButton(
                onClick = { showBottomSheet = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Default.Menu, contentDescription = null)
            }
        }
    }
}
