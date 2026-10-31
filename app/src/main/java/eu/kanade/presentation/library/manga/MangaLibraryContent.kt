package eu.kanade.presentation.library.manga

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.presentation.library.components.CollectionCard
import eu.kanade.presentation.library.components.CollectionDetailHeader
import eu.kanade.presentation.library.components.CollectionEditDialog
import eu.kanade.presentation.library.components.CollectionTreeSheet
import eu.kanade.presentation.library.components.CommonEntryItemDefaults
import eu.kanade.presentation.library.components.EntryProgress
import eu.kanade.presentation.library.components.LibrarySectionHeader
import eu.kanade.presentation.library.components.ProgressRing
import eu.kanade.presentation.library.components.RecentActivityCarousel
import eu.kanade.presentation.library.components.RecentCollection
import eu.kanade.presentation.library.components.RecentWork
import eu.kanade.presentation.library.components.collectionBannerModel
import eu.kanade.presentation.library.components.collectionWorkCountLabel
import eu.kanade.presentation.library.components.mangaStatsText
import eu.kanade.presentation.library.components.progressPercent
import eu.kanade.tachiyomi.data.collection.CollectionMetadataManager
import eu.kanade.tachiyomi.data.collection.CollectionType
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.manga.LibraryManga
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.injectLazy
import java.io.File
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
    gridState: LazyGridState,
    showEditDialog: Boolean,
    onDismissEditDialog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember(currentCategory) { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val metadataManager: CollectionMetadataManager by injectLazy()
    var metadata by remember(currentCategory?.id) {
        mutableStateOf(
            if (currentCategory != null) {
                metadataManager.getMetadata(CollectionType.MANGA, currentCategory.id)
            } else {
                null
            },
        )
    }

    val notSelectionMode = selection.isEmpty()
    val childCategories = categories.filter { it.parentId == currentCategory?.id }
    val rootCategories = categories.filter { it.parentId == null }
    val childrenByParent = categories.groupBy { it.parentId }

    fun directWorks(category: Category): List<MangaLibraryItem> {
        val pageIndex = categories.indexOf(category)
        return if (pageIndex != -1) getLibraryForPage(pageIndex) else emptyList()
    }

    /**
     * All the works of the tree starting at [category], deduplicated by manga id.
     */
    fun allWorksRecursive(category: Category): List<MangaLibraryItem> {
        val result = LinkedHashMap<Long, MangaLibraryItem>()
        val visited = mutableSetOf<Long>()

        fun walk(current: Category) {
            if (!visited.add(current.id)) return
            directWorks(current).forEach { result.putIfAbsent(it.libraryManga.manga.id, it) }
            childrenByParent[current.id].orEmpty().forEach { walk(it) }
        }

        walk(category)
        return result.values.toList()
    }

    val worksByCategory = categories.associate { it.id to allWorksRecursive(it) }
    val works: List<MangaLibraryItem> = if (currentCategory != null) {
        directWorks(currentCategory)
    } else {
        emptyList()
    }

    // A collection is only displayed when its whole subtree hosts at least one work.
    val visibleChildCategories = childCategories.filter { worksByCategory[it.id].orEmpty().isNotEmpty() }
    val visibleRootCollections = rootCategories
        .filterNot { it.isSystemCategory }
        .filter { worksByCategory[it.id].orEmpty().isNotEmpty() }
    // Works without any category live in the system category and are shown as works, not as a card.
    val uncategorizedWorks = rootCategories
        .firstOrNull { it.isSystemCategory }
        ?.let { directWorks(it) }
        .orEmpty()

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns by remember(isLandscape) { getColumnsForOrientation(isLandscape) }

    // Opening or leaving a collection always starts from the top of the page.
    LaunchedEffect(currentCategory?.id) {
        gridState.scrollToItem(0)
    }

    if (currentCategory != null) {
        LaunchedEffect(currentCategory.id) {
            metadataManager.markOpened(CollectionType.MANGA, currentCategory.id)
        }
    }

    // Header statistics always include the works of the nested collections.
    val statsWorks = currentCategory?.let { worksByCategory[it.id].orEmpty() }.orEmpty()

    // Recent works of the whole library, including works living in sub-collections.
    val recentWorks = categories
        .asSequence()
        .flatMap { directWorks(it).asSequence() }
        .distinctBy { it.libraryManga.manga.id }
        .filter { it.libraryManga.lastRead > 0L }
        .sortedByDescending { it.libraryManga.lastRead }
        .take(3)
        .toList()

    // The carousel mirrors the resume button of the grid: same setting, same condition.
    val carouselWorks = recentWorks.map {
        RecentWork(
            id = it.libraryManga.manga.id,
            title = it.libraryManga.manga.title,
            coverUrl = it.libraryManga.manga.thumbnailUrl,
            progress = EntryProgress(seriesPercent = it.progressPercent()),
            canContinue = it.libraryManga.unreadCount > 0,
        )
    }
    val onCarouselContinueClick: ((Long) -> Unit)? = onContinueReadingClicked?.let { onClick ->
        { mangaId: Long ->
            recentWorks.firstOrNull { it.libraryManga.manga.id == mangaId }
                ?.let { onClick(it.libraryManga) }
        }
    }

    // Recent collections, hiding the ones whose subtree hosts no work.
    val recentCollections = metadataManager.getRecentCollections(CollectionType.MANGA)
        .mapNotNull { recent -> categories.firstOrNull { it.id == recent.categoryId }?.let { recent to it } }
        .filter { (_, category) -> worksByCategory[category.id].orEmpty().isNotEmpty() }
        .map { (recent, category) ->
            RecentCollection(
                id = category.id,
                name = category.name,
                bannerModel = collectionBannerModel(recent.bannerPath, recent.bannerUrl),
            )
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
                columns = if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns),
                state = gridState,
                contentPadding = contentPadding + PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(CommonEntryItemDefaults.GridHorizontalSpacer),
                verticalArrangement = Arrangement.spacedBy(CommonEntryItemDefaults.GridVerticalSpacer),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (currentCategory != null && metadata != null) {
                        CollectionDetailHeader(
                            title = currentCategory.name,
                            description = metadata!!.description,
                            bannerUrl = metadata!!.bannerUrl,
                            bannerPath = metadata!!.bannerPath,
                            statsText = statsWorks.mangaStatsText(),
                        )
                    } else if (currentCategory == null) {
                        RecentActivityCarousel(
                            recentWorks = carouselWorks,
                            recentCollections = recentCollections,
                            onWorkClick = { mangaId -> onMangaClicked(mangaId) },
                            onCollectionClick = { catId ->
                                categories.find { it.id == catId }?.let { onCategoryClick(it) }
                            },
                            onContinueClick = onCarouselContinueClick,
                        )
                    }
                }

                // ---- ROOT: COLLECTIONS THEN WORKS ----
                if (currentCategory == null) {
                    if (visibleRootCollections.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LibrarySectionHeader(title = "Collections")
                        }
                        items(visibleRootCollections, key = { "root_${it.id}" }) { cat ->
                            val catWorks = worksByCategory[cat.id].orEmpty()
                            val worksWithCover = catWorks.filter { !it.libraryManga.manga.thumbnailUrl.isNullOrBlank() }
                            CollectionCard(
                                title = cat.name,
                                subtitle = collectionWorkCountLabel(catWorks.size),
                                previewImages = worksWithCover
                                    .mapNotNull { it.libraryManga.manga.thumbnailUrl }
                                    .take(3),
                                bannerModel = collectionBannerModel(
                                    metadataManager.getMetadata(CollectionType.MANGA, cat.id).bannerPath,
                                    metadataManager.getMetadata(CollectionType.MANGA, cat.id).bannerUrl,
                                ),
                                onClick = { onCategoryClick(cat) },
                                onImageClick = { idx ->
                                    worksWithCover.getOrNull(idx)?.let { onMangaClicked(it.libraryManga.manga.id) }
                                },
                            )
                        }
                    }

                    if (uncategorizedWorks.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LibrarySectionHeader(title = "Œuvres")
                        }
                        items(
                            items = uncategorizedWorks,
                            key = { "manga_${it.libraryManga.manga.id}" },
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
                }

                // ---- INSIDE A COLLECTION: SUB-COLLECTIONS THEN WORKS ----
                if (currentCategory != null) {
                    if (visibleChildCategories.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LibrarySectionHeader(title = "Sous-collections")
                        }
                        items(visibleChildCategories, key = { "sub_${it.id}" }) { sub ->
                            val subWorks = worksByCategory[sub.id].orEmpty()
                            val worksWithCover = subWorks.filter { !it.libraryManga.manga.thumbnailUrl.isNullOrBlank() }
                            CollectionCard(
                                title = sub.name,
                                subtitle = collectionWorkCountLabel(subWorks.size),
                                previewImages = worksWithCover
                                    .mapNotNull { it.libraryManga.manga.thumbnailUrl }
                                    .take(3),
                                bannerModel = collectionBannerModel(
                                    metadataManager.getMetadata(CollectionType.MANGA, sub.id).bannerPath,
                                    metadataManager.getMetadata(CollectionType.MANGA, sub.id).bannerUrl,
                                ),
                                onClick = { onCategoryClick(sub) },
                                onImageClick = { idx ->
                                    worksWithCover.getOrNull(idx)?.let { onMangaClicked(it.libraryManga.manga.id) }
                                },
                            )
                        }
                    }

                    if (works.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LibrarySectionHeader(title = "Œuvres")
                        }
                        items(
                            items = works,
                            key = { "manga_${it.libraryManga.manga.id}" },
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
                Icon(Icons.Default.Folder, contentDescription = "Collections")
            }
        }

        if (showBottomSheet) {
            CollectionTreeSheet(
                categories = categories,
                currentCategoryId = currentCategory?.id,
                workCountForCategory = { category -> worksByCategory[category.id].orEmpty().size },
                onCategorySelected = {
                    onCategoryClick(it)
                    showBottomSheet = false
                },
                onDismissRequest = { showBottomSheet = false },
            )
        }

        if (showEditDialog && currentCategory != null && metadata != null) {
            CollectionEditDialog(
                initialBannerModel = collectionBannerModel(metadata!!.bannerPath, metadata!!.bannerUrl),
                initialDescription = metadata!!.description,
                onDismiss = onDismissEditDialog,
                onSave = { uri, removeBanner, desc ->
                    scope.launch {
                        var finalPath = if (removeBanner) null else metadata?.bannerPath
                        var finalUrl = if (removeBanner) null else metadata?.bannerUrl
                        if (uri != null) {
                            val saved = withContext(Dispatchers.IO) {
                                metadataManager.saveBannerFile(CollectionType.MANGA, currentCategory.id, uri)
                            }
                            if (saved != null) {
                                finalPath = saved
                                finalUrl = null
                            }
                        } else if (removeBanner) {
                            metadata?.bannerPath?.let { path ->
                                withContext(Dispatchers.IO) { File(path).delete() }
                            }
                        }
                        metadataManager.updateMetadata(
                            type = CollectionType.MANGA,
                            categoryId = currentCategory.id,
                            bannerUrl = finalUrl,
                            bannerPath = finalPath,
                            description = desc,
                        )
                        metadata = metadataManager.getMetadata(CollectionType.MANGA, currentCategory.id)
                        onDismissEditDialog()
                    }
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
        onClickContinueViewing = if (onClickContinueReading != null && item.libraryManga.unreadCount > 0) {
            { onClickContinueReading(item.libraryManga) }
        } else {
            null
        },
        coverBadgeEnd = {
            ProgressRing(
                progress = EntryProgress(seriesPercent = item.progressPercent()),
                size = 34.dp,
            )
        },
    )
}
