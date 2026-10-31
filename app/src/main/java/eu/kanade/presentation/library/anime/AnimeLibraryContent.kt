package eu.kanade.presentation.library.anime

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
import androidx.compose.runtime.produceState
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
import eu.kanade.presentation.library.components.computeAnimeStats
import eu.kanade.presentation.library.components.loadAnimeProgress
import eu.kanade.presentation.library.components.progressPercent
import eu.kanade.tachiyomi.data.collection.CollectionMetadataManager
import eu.kanade.tachiyomi.data.collection.CollectionType
import eu.kanade.tachiyomi.data.telegram.index.TelegramLibraryImporter
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.library.anime.LibraryAnime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.util.plus
import uy.kohesive.injekt.injectLazy
import java.io.File
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
    gridState: LazyGridState,
    showEditDialog: Boolean,
    onDismissEditDialog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isRefreshing by remember(currentCategory) { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var statsText by remember(currentCategory?.id) { mutableStateOf("") }
    val metadataManager: CollectionMetadataManager by injectLazy()
    var metadataVersion by remember { mutableStateOf(0) }
    var metadata by remember(currentCategory?.id) {
        mutableStateOf(
            if (currentCategory != null) {
                metadataManager.getMetadata(CollectionType.ANIME, currentCategory.id)
            } else {
                null
            },
        )
    }

    val telegramCategoryIds = remember(categories) {
        categories
            .filter { it.name.equals(TelegramLibraryImporter.TELEGRAM_CATEGORY, ignoreCase = true) }
            .map { it.id }
    }
    LaunchedEffect(telegramCategoryIds) {
        if (telegramCategoryIds.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            telegramCategoryIds.forEach {
                metadataManager.ensureTelegramBanner(CollectionType.ANIME, it)
            }
        }
        metadataVersion++
    }

    LaunchedEffect(currentCategory?.id, metadataVersion) {
        metadata = currentCategory?.let {
            metadataManager.getMetadata(CollectionType.ANIME, it.id)
        }
    }

    val notSelectionMode = selection.isEmpty()
    val childCategories = categories.filter { it.parentId == currentCategory?.id }
    val rootCategories = categories.filter { it.parentId == null }
    val childrenByParent = categories.groupBy { it.parentId }

    fun directWorks(category: Category): List<AnimeLibraryItem> {
        val pageIndex = categories.indexOf(category)
        return if (pageIndex != -1) getAnimeLibraryForPage(pageIndex) else emptyList()
    }

    /**
     * All the works of the tree starting at [category], deduplicated by anime id.
     */
    fun allWorksRecursive(category: Category): List<AnimeLibraryItem> {
        val result = LinkedHashMap<Long, AnimeLibraryItem>()
        val visited = mutableSetOf<Long>()

        fun walk(current: Category) {
            if (!visited.add(current.id)) return
            directWorks(current).forEach { result.putIfAbsent(it.libraryAnime.anime.id, it) }
            childrenByParent[current.id].orEmpty().forEach { walk(it) }
        }

        walk(category)
        return result.values.toList()
    }

    val worksByCategory = categories.associate { it.id to allWorksRecursive(it) }
    val works: List<AnimeLibraryItem> = if (currentCategory != null) {
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

    val getEpisodes: GetEpisodesByAnimeId by injectLazy()

    // Header statistics always include the works of the nested collections.
    val statsWorks = currentCategory?.let { worksByCategory[it.id].orEmpty() }.orEmpty()
    LaunchedEffect(statsWorks, currentCategory?.id) {
        statsText = computeAnimeStats(statsWorks, getEpisodes)
    }

    if (currentCategory != null) {
        LaunchedEffect(currentCategory.id) {
            metadataManager.markOpened(CollectionType.ANIME, currentCategory.id)
        }
    }

    // Recent works of the whole library, including works living in sub-collections.
    val recentWorks = categories
        .asSequence()
        .flatMap { directWorks(it).asSequence() }
        .distinctBy { it.libraryAnime.anime.id }
        .filter { it.libraryAnime.lastSeen > 0L }
        .sortedByDescending { it.libraryAnime.lastSeen }
        .take(3)
        .toList()

    var recentProgress by remember { mutableStateOf<Map<Long, EntryProgress>>(emptyMap()) }
    val recentWorkKeys = recentWorks.map { it.libraryAnime.anime.id to it.libraryAnime.lastSeen }
    LaunchedEffect(recentWorkKeys) {
        recentProgress = recentWorks.associate {
            it.libraryAnime.anime.id to loadAnimeProgress(it, getEpisodes)
        }
    }

    // The carousel mirrors the resume button of the grid: same setting, same condition.
    val carouselWorks = recentWorks.map {
        RecentWork(
            id = it.libraryAnime.anime.id,
            title = it.libraryAnime.anime.title,
            coverUrl = it.libraryAnime.anime.thumbnailUrl,
            progress = recentProgress[it.libraryAnime.anime.id]
                ?: EntryProgress(seriesPercent = it.progressPercent()),
            canContinue = it.libraryAnime.unseenCount > 0,
        )
    }
    val onCarouselContinueClick: ((Long) -> Unit)? = onContinueWatchingClicked?.let { onClick ->
        { animeId: Long ->
            recentWorks.firstOrNull { it.libraryAnime.anime.id == animeId }
                ?.let { onClick(it.libraryAnime) }
        }
    }

    // Recent collections, hiding the ones whose subtree hosts no work.
    val recentCollections = metadataManager.getRecentCollections(CollectionType.ANIME)
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
                // ---- HEADER ----
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (currentCategory != null && metadata != null) {
                        CollectionDetailHeader(
                            title = currentCategory.name,
                            description = metadata!!.description,
                            bannerUrl = metadata!!.bannerUrl,
                            bannerPath = metadata!!.bannerPath,
                            statsText = statsText,
                        )
                    } else if (currentCategory == null) {
                        RecentActivityCarousel(
                            recentWorks = carouselWorks,
                            recentCollections = recentCollections,
                            onWorkClick = { animeId -> onAnimeClicked(animeId) },
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
                            val worksWithCover = catWorks.filter { !it.libraryAnime.anime.thumbnailUrl.isNullOrBlank() }
                            val catMetadata = remember(cat.id, metadataVersion) {
                                metadataManager.getMetadata(CollectionType.ANIME, cat.id)
                            }
                            CollectionCard(
                                title = cat.name,
                                subtitle = collectionWorkCountLabel(catWorks.size),
                                previewImages = worksWithCover
                                    .mapNotNull { it.libraryAnime.anime.thumbnailUrl }
                                    .take(3),
                                bannerModel = collectionBannerModel(
                                    catMetadata.bannerPath,
                                    catMetadata.bannerUrl,
                                ),
                                onClick = { onCategoryClick(cat) },
                                onImageClick = { idx ->
                                    worksWithCover.getOrNull(idx)?.let { onAnimeClicked(it.libraryAnime.anime.id) }
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
                            key = { "anime_${it.libraryAnime.anime.id}" },
                            contentType = { "anime_work_item" },
                        ) { libraryItem ->
                            AnimeWorkCard(
                                item = libraryItem,
                                isSelected = selection.any { it.id == libraryItem.libraryAnime.id },
                                onClick = { onAnimeClicked(libraryItem.libraryAnime.anime.id) },
                                onLongClick = { onToggleRangeSelection(libraryItem.libraryAnime) },
                                onClickContinueWatching = onContinueWatchingClicked,
                                getEpisodes = getEpisodes,
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
                            val worksWithCover = subWorks.filter { !it.libraryAnime.anime.thumbnailUrl.isNullOrBlank() }
                            val subMetadata = remember(sub.id, metadataVersion) {
                                metadataManager.getMetadata(CollectionType.ANIME, sub.id)
                            }
                            CollectionCard(
                                title = sub.name,
                                subtitle = collectionWorkCountLabel(subWorks.size),
                                previewImages = worksWithCover
                                    .mapNotNull { it.libraryAnime.anime.thumbnailUrl }
                                    .take(3),
                                bannerModel = collectionBannerModel(
                                    subMetadata.bannerPath,
                                    subMetadata.bannerUrl,
                                ),
                                onClick = { onCategoryClick(sub) },
                                onImageClick = { idx ->
                                    worksWithCover.getOrNull(idx)?.let { onAnimeClicked(it.libraryAnime.anime.id) }
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
                            key = { "anime_${it.libraryAnime.anime.id}" },
                            contentType = { "anime_work_item" },
                        ) { libraryItem ->
                            AnimeWorkCard(
                                item = libraryItem,
                                isSelected = selection.any { it.id == libraryItem.libraryAnime.id },
                                onClick = { onAnimeClicked(libraryItem.libraryAnime.anime.id) },
                                onLongClick = { onToggleRangeSelection(libraryItem.libraryAnime) },
                                onClickContinueWatching = onContinueWatchingClicked,
                                getEpisodes = getEpisodes,
                            )
                        }
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
                Icon(Icons.Default.Folder, contentDescription = "Collections")
            }
        }

        // ---- COLLECTION TREE ----
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

        // ---- EDIT DIALOG ----
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
                                metadataManager.saveBannerFile(CollectionType.ANIME, currentCategory.id, uri)
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
                            type = CollectionType.ANIME,
                            categoryId = currentCategory.id,
                            bannerUrl = finalUrl,
                            bannerPath = finalPath,
                            description = desc,
                        )
                        metadata = metadataManager.getMetadata(CollectionType.ANIME, currentCategory.id)
                        onDismissEditDialog()
                    }
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
    getEpisodes: GetEpisodesByAnimeId,
) {
    val progress by produceState<EntryProgress?>(
        initialValue = null,
        item.libraryAnime.anime.id,
        item.libraryAnime.lastSeen,
        item.libraryAnime.seenCount,
    ) {
        value = loadAnimeProgress(item, getEpisodes)
    }

    val progressBadge: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
        progress?.let { entryProgress ->
            { ProgressRing(progress = entryProgress, size = 34.dp) }
        }

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
        onClickContinueViewing = if (onClickContinueWatching != null && item.libraryAnime.unseenCount > 0) {
            { onClickContinueWatching(item.libraryAnime) }
        } else {
            null
        },
        coverBadgeEnd = progressBadge,
    )
}
