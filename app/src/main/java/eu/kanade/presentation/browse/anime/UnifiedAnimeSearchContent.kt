package eu.kanade.presentation.browse.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.browse.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.GlobalSearchResultItem
import eu.kanade.presentation.browse.anime.components.GlobalAnimeSearchCardRow
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.ui.browse.anime.search.ApiSearchState
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSearchItemResult
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSearchScreenModel
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import eu.kanade.tachiyomi.ui.browse.catalog.preferredTitle
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.presentation.core.components.material.Scaffold

@Composable
fun UnifiedAnimeSearchContent(
    state: AnimeSearchScreenModel.State,
    apiState: ApiSearchState,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    getAnime: @Composable (Anime) -> State<Anime>,
    onClickSource: (AnimeSource) -> Unit,
    onClickItem: (Anime) -> Unit,
    onLongClickItem: (Anime) -> Unit,
    onClickApiWork: (CatalogWork) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            SearchToolbar(
                searchQuery = state.searchQuery,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                onClickCloseSearch = navigateUp,
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            contentPadding = paddingValues,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "catalog-header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Catalogue",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "AniList · MAL · Kitsu",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (apiState.loading) {
                item(key = "catalog-progress") {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            }

            when {
                apiState.results.isNotEmpty() -> {
                    item(key = "catalog-results") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(apiState.results, key = { it.id }) { work ->
                                CatalogApiResultCard(
                                    work = work,
                                    onClick = { onClickApiWork(work) },
                                )
                            }
                        }
                    }
                }
                apiState.loading -> {
                    item(key = "catalog-loading") {
                        GlobalSearchLoadingResultItem()
                    }
                }
                apiState.error != null -> {
                    item(key = "catalog-error") {
                        GlobalSearchErrorResultItem(apiState.error)
                    }
                }
                apiState.searched -> {
                    item(key = "catalog-empty") {
                        Text(
                            text = "Aucun résultat dans le catalogue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            item(key = "extensions-header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    HorizontalDivider()
                    Text(
                        text = "Extensions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }

            state.filteredItems.forEach { (source, result) ->
                item(key = "source-${source.id}") {
                    GlobalSearchResultItem(
                        title = source.name,
                        subtitle = LocaleHelper.getLocalizedDisplayName(source.lang),
                        onClick = { onClickSource(source) },
                    ) {
                        when (result) {
                            AnimeSearchItemResult.Loading -> GlobalSearchLoadingResultItem()
                            is AnimeSearchItemResult.Success -> {
                                GlobalAnimeSearchCardRow(
                                    titles = result.result,
                                    getAnime = getAnime,
                                    onClick = onClickItem,
                                    onLongClick = onLongClickItem,
                                )
                            }
                            is AnimeSearchItemResult.Error -> {
                                GlobalSearchErrorResultItem(message = result.throwable.message)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogApiResultCard(
    work: CatalogWork,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = work.coverUrl,
            contentDescription = work.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp)),
        )
        Text(
            text = work.preferredTitle(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        val meta = listOfNotNull(
            work.year?.toString(),
            work.format,
            work.providerLabel.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
