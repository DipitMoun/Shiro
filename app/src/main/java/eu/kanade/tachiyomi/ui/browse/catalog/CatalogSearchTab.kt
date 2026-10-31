package eu.kanade.tachiyomi.ui.browse.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.TabContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.plus

@Composable
fun catalogSearchTab(model: CatalogSearchScreenModel): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val state by model.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.action_search,
        content = { contentPadding, _ ->
            CatalogSearchContent(
                state = state,
                contentPadding = contentPadding,
                onQueryChange = model::setQuery,
                onSearch = model::search,
                onClear = model::clear,
                onOpenWork = { work ->
                    navigator.push(CatalogWorkScreen(Json.encodeToString(work)))
                },
            )
        },
    )
}

@Composable
private fun CatalogSearchContent(
    state: CatalogSearchState,
    contentPadding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onOpenWork: (CatalogWork) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Rechercher un animé…") },
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            Button(
                onClick = onSearch,
                enabled = !state.busy && state.query.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Text("Chercher")
            }
        }

        if (state.busy) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text(
                        text = "Catalogue · AniList / MAL / Kitsu",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.searched && state.results.isNotEmpty()) {
                        Text(
                            text = "${state.results.size} résultat(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(state.results, key = { it.id }) { work ->
                CatalogResultCard(
                    work = work,
                    onClick = { onOpenWork(work) },
                )
            }

            if (state.searched && state.results.isEmpty() && state.error == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = onClear) {
                        Text("Effacer la recherche")
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogResultCard(
    work: CatalogWork,
    onClick: () -> Unit,
) {
    val displayTitle = work.preferredTitle()
    Column(
        modifier = Modifier
            .width(96.dp)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = work.coverUrl,
            contentDescription = displayTitle,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp)),
        )
        Text(
            text = displayTitle,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 3,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
        val subtitle = listOfNotNull(
            work.year?.toString(),
            work.format,
            work.providerLabel.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}
