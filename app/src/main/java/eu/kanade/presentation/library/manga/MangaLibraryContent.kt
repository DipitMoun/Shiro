package eu.kanade.presentation.library.manga

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
import eu.kanade.presentation.library.components.mangaStatsText
import eu.kanade.presentation.library.components.progressPercent
import eu.kanade.tachiyomi.data.collection.CollectionMetadataManager
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.material.PullRefresh
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration.Companion.seconds

@Composable
fun MangaLibraryContent(
    categories: List<Category>,
    searchQuery: String?,
    selection: List<LibraryManga>,
    contentPadding: PaddingValues,
    currentCategory: Category?,
    currentPage: () -> Int,
    hasActiveFilters: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    onCategoryClick: (Category) -> Unit,
    onNavigateBack: () -> Unit,
    onMangaClicked: (Long) -> Unit,
    onContinueReadingClicked: ((LibraryManga) -> Unit)?,
    onToggleSelection: (LibraryManga) -> Unit,
    onToggleRangeSelection: (LibraryManga) -> Unit,
    onRefresh: (Category?) -> Boolean,
    onGlobalSearchClicked: () -> Unit,
    getNumberOfMangaForCategory: (Category) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getLibraryForPage: (Int) -> List<MangaLibraryItem>,
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
    val works: List<MangaLibraryItem> = if (currentCategory != null) {
        val pageIndex = categories.indexOf(currentCategory)
        if (pageIndex != -1) getLibraryForPage(pageIndex) else emptyList()
    } else {
        emptyList()
    }

    if (currentCategory != null) {
        LaunchedEffect(currentCategory.id) {
            metadataManager.markOpened(currentCategory.id)
        }
    }

    val rootEntries = remember(categories) {
        rootCategories.flatMap { cat ->
            val pageIndex = categories.indexOf(cat)
            if (pageIndex != -1) getLibraryForPage(pageIndex) else emptyList()
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
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (currentCategory != null && metadata != null) {
                        CollectionDetailHeader(
                            title = currentCategory.name,
                            description = metadata!!.description,
                            bannerUrl = metadata!!.bannerUrl,
                            statsText = works.mangaStatsText(),
                            onBack = onNavigateBack,
                            onEdit = { showEditDialog = true },
                        )
                    } else if (currentCategory == null) {
                        val recentColls = metadataManager.getRecentCollections()
                        val recentWorks = rootEntries
                            .filter { it.libraryManga.lastRead > 0L }
                            .sortedByDescending { it.libraryManga.lastRead }
                            .take(3)
                        RecentActivityCarousel(
                            recentWorks = recentWorks.map {
                                RecentWork(
                                    id = it.libraryManga.manga.id,
                                    title = it.libraryManga.manga.title,
                                    coverUrl = it.libraryManga.manga.thumbnailUrl,
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
                            onWorkClick = { mangaId -> onMangaClicked(mangaId) },
                            onCollectionClick = { catId ->
                                categories.find { it.id == catId }?.let { onCategoryClick(it) }
                            },
                        )
                    }
                }

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
                        val subWorks = if (subPageIndex != -1) getLibraryForPage(subPageIndex) else emptyList()
                        val previewImages = subWorks.take(3).map { it.libraryManga.manga.thumbnailUrl }
                        CollectionCard(
                            title = sub.name,
                            previewImages = previewImages,
                            onClick = { onCategoryClick(sub) },
                            onImageClick = { idx ->
                                subWorks.getOrNull(idx)?.let { onMangaClicked(it.libraryManga.manga.id) }
                            },
                        )
                    }
                    if (childCategories.size % 2 == 1) {
                        item { Box(modifier = Modifier.padding(4.dp)) }
                    }
                }

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
                        contentType = { "manga_work_item" },
                    ) { libraryItem ->
                        MangaWorkCard(
                            item = libraryItem,
                            isSelected = selection.any { it.id == libraryItem.libraryManga.id },
                            onClick = { onMangaClicked(libraryItem.libraryManga.manga.id) },
                            onLongClick = { onToggleRangeSelection(libraryItem.libraryManga) },
                            onClickContinueReading = onContinueReadingClicked,
                        )
                    }
                }

                if (currentCategory == null) {
                    items(rootCategories) { cat ->
                        val pageIndex = categories.indexOf(cat)
                        val previewWorks = if (pageIndex != -1) getLibraryForPage(pageIndex) else emptyList()
                        val previewImages = previewWorks.take(3).map { it.libraryManga.manga.thumbnailUrl }
                        CollectionCard(
                            title = cat.name,
                            previewImages = previewImages,
                            onClick = { onCategoryClick(cat) },
                            onImageClick = { idx ->
                                previewWorks.getOrNull(idx)?.let { onMangaClicked(it.libraryManga.manga.id) }
                            },
                        )
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

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState,
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    categories.forEach { cat ->
                        item(key = "cat_${cat.id}") {
                            Text(
                                text = cat.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onCategoryClick(cat)
                                        showBottomSheet = false
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                        categories.filter { it.parentId == cat.id }.forEach { sub ->
                            item(key = "sub_${sub.id}") {
                                Text(
                                    text = "  · ${sub.name}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onCategoryClick(sub)
                                            showBottomSheet = false
                                        }
                                        .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

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
private fun MangaWorkCard(
    item: MangaLibraryItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickContinueReading: ((LibraryManga) -> Unit)?,
) {
    eu.kanade.presentation.library.components.EntryCompactGridItem(
        isSelected = isSelected,
        title = item.libraryManga.manga.title,
        coverData = tachiyomi.domain.entries.manga.model.MangaCover(
            mangaId = item.libraryManga.manga.id,
            sourceId = item.libraryManga.manga.source,
            isMangaFavorite = item.libraryManga.manga.favorite,
            url = item.libraryManga.manga.thumbnailUrl,
            lastModified = item.libraryManga.manga.coverLastModified,
        ),
        onClick = onClick,
        onLongClick = onLongClick,
        onClickContinueViewing = if (onClickContinueReading != null && item.unreadCount > 0) {
            { onClickContinueReading(item.libraryManga) }
        } else {
            null
        },
    )
}
