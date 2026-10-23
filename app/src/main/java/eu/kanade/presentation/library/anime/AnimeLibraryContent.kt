package eu.kanade.presentation.library.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import eu.kanade.presentation.library.components.RecentActivityCarousel
import eu.kanade.presentation.library.components.RecentCollection
import eu.kanade.presentation.library.components.RecentWork
import eu.kanade.presentation.library.components.progressPercent
import eu.kanade.presentation.library.components.statsText
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
    val scope = rememberCoroutineScope()
    var isRefreshing by remember(currentCategory) { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val metadataManager: CollectionMetadataManager by injectLazy()
    var metadata by remember(currentCategory?.id) {
        mutableStateOf(if (currentCategory != null) metadataManager.getMetadata(currentCategory.id) else null)
    }

    val notSelectionMode = selection.isEmpty()
    val childCategories = categories.filter { it.parentId == currentCategory?.id }
    val rootCategories = categories.filter { it.parentId == null }
    val works: List<AnimeLibraryItem> = if (currentCategory != null) {
        val pageIndex = categories.indexOf(currentCategory)
        if (pageIndex != -1) getAnimeLibraryForPage(pageIndex) else emptyList()
    } else {
        emptyList()
    }

    // Track opened collection
    if (currentCategory != null) {
        LaunchedEffect(currentCategory.id) {
            metadataManager.markOpened(currentCategory.id)
        }
    }

    val rootEntries = remember(categories) {
        rootCategories.flatMap { cat ->
            val pageIndex = categories.indexOf(cat)
            if (pageIndex != -1) getAnimeLibraryForPage(pageIndex) else emptyList()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // ---- HEADER ----
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (currentCategory != null && metadata != null) {
                        CollectionDetailHeader(
                            title = currentCategory.name,
                            description = metadata!!.description,
                            bannerUrl = metadata!!.bannerUrl,
                            statsText = works.statsText(),
                            onBack = onNavigateBack,
                            onEdit = { showEditDialog = true },
                        )
                    } else if (currentCategory == null) {
                        val recentColls = metadataManager.getRecentCollections()
                        val recentWorks = rootEntries
                            .filter { it.libraryAnime.lastSeen > 0L }
                            .sortedByDescending { it.libraryAnime.lastSeen }
                            .take(3)
                        RecentActivityCarousel(
                            recentWorks = recentWorks.map {
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
                }

                // ---- SOUS-COLLECTIONS (si currentCategory != null) ----
                if (currentCategory != null && childCategories.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "Sous-collections",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    items(childCategories) { sub ->
                        val subPageIndex = categories.indexOf(sub)
                        val subWorks = if (subPageIndex != -1) getAnimeLibraryForPage(subPageIndex) else emptyList()
                        val previewImages = subWorks.take(3).map { it.libraryAnime.anime.thumbnailUrl }
                        CollectionCard(
                            title = sub.name,
                            previewImages = previewImages,
                            onClick = { onCategoryClick(sub) },
                            onImageClick = { idx ->
                                subWorks.getOrNull(idx)?.let { onAnimeClicked(it.libraryAnime.anime.id) }
                            },
                        )
                    }
                    // Padding si nombre impair
                    if (childCategories.size % 2 == 1) {
                        item { Box(modifier = Modifier.padding(4.dp)) }
                    }
                }

                // ---- GRILLE DES ŒUVRES ----
                if (currentCategory != null && works.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "Œuvres",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    items(
                        items = works,
                        contentType = { "anime_work_item" },
                    ) { libraryItem ->
                        AnimeWorkCard(
                            item = libraryItem,
                            isSelected = selection.any { it.id == libraryItem.libraryAnime.id },
                            onClick = { onAnimeClicked(libraryItem.libraryAnime.anime.id) },
                            onLongClick = { onToggleRangeSelection(libraryItem.libraryAnime) },
                            onClickContinueWatching = onContinueWatchingClicked,
                        )
                    }
                }

                // ---- COLLECTIONS RACINES ----
                if (currentCategory == null) {
                    items(rootCategories) { cat ->
                        val pageIndex = categories.indexOf(cat)
                        val previewWorks = if (pageIndex != -1) getAnimeLibraryForPage(pageIndex) else emptyList()
                        val previewImages = previewWorks.take(3).map { it.libraryAnime.anime.thumbnailUrl }
                        CollectionCard(
                            title = cat.name,
                            previewImages = previewImages,
                            onClick = { onCategoryClick(cat) },
                            onImageClick = { idx ->
                                previewWorks.getOrNull(idx)?.let { onAnimeClicked(it.libraryAnime.anime.id) }
                            },
                        )
                    }
                }
            }
        }

        // ---- FAB ----
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

        // ---- BOTTOM SHEET ----
        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                ) {
                    categories.forEach { cat ->
                        Text(
                            text = cat.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .padding(vertical = 8.dp)
                                .then(
                                    Modifier.clickable {
                                        onCategoryClick(cat)
                                        showBottomSheet = false
                                    },
                                ),
                        )
                        categories.filter { it.parentId == cat.id }.forEach { sub ->
                            Text(
                                text = "  · ${sub.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .padding(start = 16.dp, top = 4.dp)
                                    .then(
                                        Modifier.clickable {
                                            onCategoryClick(sub)
                                            showBottomSheet = false
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }

        // ---- EDIT DIALOG ----
        if (showEditDialog && currentCategory != null && metadata != null) {
            CollectionEditDialog(
                initialBannerUrl = metadata!!.bannerUrl.orEmpty(),
                initialDescription = metadata!!.description,
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
    }
}

@Composable
private fun AnimeWorkCard(
    item: AnimeLibraryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickContinueWatching: ((LibraryAnime) -> Unit)?,
) {
    eu.kanade.presentation.library.components.EntryCompactGridItem(
        isSelected = isSelected,
        title = item.libraryAnime.anime.title,
        coverData = tachiyomi.domain.entries.anime.model.AnimeCover(
            animeId = item.libraryAnime.anime.id,
            sourceId = item.libraryAnime.anime.source,
            isAnimeFavorite = item.libraryAnime.anime.favorite,
            url = item.libraryAnime.anime.thumbnailUrl,
            lastModified = item.libraryAnime.anime.coverLastModified,
        ),
        onClick = onClick,
        onLongClick = onLongClick,
        onClickContinueViewing = if (onClickContinueWatching != null && item.unseenCount > 0) {
            { onClickContinueWatching(item.libraryAnime) }
        } else {
            null
        },
    )
}
