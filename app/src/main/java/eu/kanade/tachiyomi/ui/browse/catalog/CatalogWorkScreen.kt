package eu.kanade.tachiyomi.ui.browse.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import kotlinx.serialization.json.Json
import tachiyomi.presentation.core.components.material.Scaffold

private val CATALOG_JSON = Json { ignoreUnknownKeys = true }

class CatalogWorkScreen(
    private val workJson: String,
) : Screen() {

    @Composable
    override fun Content() {
        val work = remember(workJson) {
            runCatching { CATALOG_JSON.decodeFromString<CatalogWork>(workJson) }
                .getOrNull()
        } ?: return

        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { CatalogWorkScreenModel(work) }
        val state by model.state.collectAsState()

        LaunchedEffect(state.openAnimeId) {
            val animeId = state.openAnimeId ?: return@LaunchedEffect
            val fromSource = state.openFromSource
            model.consumeOpen()
            model.closeSheet()
            navigator.push(AnimeScreen(animeId, fromSource = fromSource))
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = work.title,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            LazyColumn(
                contentPadding = paddingValues,
                modifier = Modifier.fillMaxWidth(),
            ) {
                item("header") {
                    CatalogWorkHeader(work = work)
                }

                item("episodes-title") {
                    Text(
                        text = "Épisodes",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                val count = work.episodes ?: 0
                if (count <= 0) {
                    item("episodes-empty") {
                        Text(
                            text = "Nombre d'épisodes inconnu dans le catalogue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    items((1..count).toList(), key = { it }) { episode ->
                        ListItem(
                            headlineContent = { Text("Épisode $episode") },
                            trailingContent = {
                                TextButton(onClick = { model.findSources(episode) }) {
                                    Text("Lire")
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }

                item("bottom-space") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        val sheetEpisode = state.sheetEpisode
        if (sheetEpisode != null) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = model::closeSheet,
                sheetState = sheetState,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Épisode $sheetEpisode · sources",
                        style = MaterialTheme.typography.titleMedium,
                    )

                    if (state.sourcesBusy) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Recherche dans vos sources…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (state.opening) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Ouverture de la source…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        val matches = state.matches
                        if (matches.isEmpty()) {
                            Text(
                                text = state.sourcesError ?: "Aucune source pour cet épisode.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = model::retry) {
                                    Text("Réessayer")
                                }
                                TextButton(
                                    onClick = {
                                        model.closeSheet()
                                        navigator.push(GlobalAnimeSearchScreen(work.title))
                                    },
                                ) {
                                    Text("Recherche globale")
                                }
                            }
                        } else {
                            val sourceError = state.sourcesError
                            if (sourceError != null) {
                                Text(
                                    text = sourceError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                                items(matches) { match ->
                                    ListItem(
                                        headlineContent = { Text(match.sourceName) },
                                        supportingContent = {
                                            val details = listOfNotNull(
                                                match.animeTitle,
                                                match.episodeName,
                                            ).joinToString(" · ")
                                            if (details.isNotBlank()) {
                                                Text(
                                                    text = details,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        trailingContent = {
                                            TextButton(
                                                onClick = { model.open(match) },
                                                enabled = !state.opening,
                                            ) {
                                                Text("Ouvrir")
                                            }
                                        },
                                    )
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogWorkHeader(
    work: CatalogWork,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row {
            AsyncImage(
                model = work.coverUrl,
                contentDescription = work.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = work.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                val secondaryTitle = work.preferredTitle().takeIf { it != work.title }
                    ?: work.originalTitle?.takeIf { it != work.title }
                secondaryTitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val meta = listOfNotNull(
                    work.year?.toString(),
                    work.format,
                    work.providers.joinToString(" · ").takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Touchez un épisode pour chercher une source.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        work.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = synopsis,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (work.genres.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = work.genres.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
