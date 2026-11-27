package eu.kanade.tachiyomi.ui.social.story

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.library.components.AnimeUpcomingLoader
import eu.kanade.tachiyomi.data.social.story.AnimeThemesClient
import eu.kanade.tachiyomi.data.social.story.ThemeOption
import eu.kanade.tachiyomi.data.social.story.ThemeRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.entries.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private sealed interface ThemesState {
    data object Loading : ThemesState
    data class Ready(val anilistId: Long, val themes: List<ThemeOption>) : ThemesState
    data class Message(val text: String) : ThemesState
}

/**
 * Picks an opening or an ending of an anime of the library, and where to start it. Only the
 * reference of the theme is kept; nothing of AnimeThemes is downloaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoryMusicSheet(onDismiss: () -> Unit, onPicked: (ThemeRef) -> Unit) {
    var anime by remember { mutableStateOf<Anime?>(null) }
    var theme by remember { mutableStateOf<ThemeOption?>(null) }
    var anilistId by remember { mutableStateOf<Long?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 320.dp, max = 560.dp)) {
            val selectedAnime = anime
            val selectedTheme = theme
            when {
                selectedAnime == null -> LibraryStep(onPick = { anime = it })
                selectedTheme == null -> ThemesStep(
                    anime = selectedAnime,
                    onBack = { anime = null },
                    onPick = { id, option -> anilistId = id; theme = option },
                )
                else -> StartStep(
                    anime = selectedAnime,
                    theme = selectedTheme,
                    onBack = { theme = null },
                    onConfirm = { start ->
                        onPicked(
                            ThemeRef(
                                anilistId = anilistId ?: return@StartStep,
                                themeId = selectedTheme.themeId,
                                startSeconds = start,
                                label = "${selectedAnime.title} · ${selectedTheme.slug}",
                                slug = selectedTheme.slug.takeIf { it.isNotBlank() },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryStep(onPick: (Anime) -> Unit) {
    var query by remember { mutableStateOf("") }
    val library by produceState<List<Anime>>(emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching { Injekt.get<GetAnimeFavorites>().await() }.getOrDefault(emptyList()) }
    }
    val shown = remember(library, query) {
        library.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }.sortedBy { it.title }
    }
    Text("Musique de la story", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Text("Choisis une œuvre de ta bibliothèque", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Rechercher…") },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
    )
    LazyColumn {
        items(shown, key = { it.id }) { item ->
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { onPick(item) }.padding(vertical = 13.dp),
            )
        }
    }
}

@Composable
private fun ThemesStep(anime: Anime, onBack: () -> Unit, onPick: (Long, ThemeOption) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<ThemesState>(ThemesState.Loading, anime.id, attempt) {
        value = ThemesState.Loading
        value = withContext(Dispatchers.IO) {
            val id = runCatching { AnimeUpcomingLoader().aniListIdFor(anime) }.getOrNull()
                ?: return@withContext ThemesState.Message("Cette œuvre n'est pas reliée à AniList, ses thèmes sont introuvables.")
            val themes = AnimeThemesClient(Injekt.get()).themesFor(id.toLong())
            when {
                themes == null -> ThemesState.Message("AnimeThemes ne répond pas pour le moment. Réessaie plus tard.")
                themes.isEmpty() -> ThemesState.Message("Aucun thème trouvé pour cette œuvre.")
                else -> ThemesState.Ready(id.toLong(), themes)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        Text(anime.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when (val s = state) {
        ThemesState.Loading -> CircularProgressIndicator(Modifier.padding(24.dp))
        is ThemesState.Message -> {
            Text(s.text, modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { attempt++ }) { Text("Réessayer") }
        }
        is ThemesState.Ready -> LazyColumn {
            items(s.themes, key = { it.themeId }) { option ->
                Column(Modifier.fillMaxWidth().clickable { onPick(s.anilistId, option) }.padding(vertical = 11.dp)) {
                    Text(option.slug.ifBlank { "Thème" }, fontWeight = FontWeight.SemiBold)
                    val detail = listOfNotNull(option.songTitle, option.artist).joinToString(" — ")
                    if (detail.isNotBlank()) {
                        Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun StartStep(anime: Anime, theme: ThemeOption, onBack: () -> Unit, onConfirm: (Int) -> Unit) {
    var start by remember { mutableFloatStateOf(0f) }
    val seconds = start.toInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
        Text("${anime.title} · ${theme.slug}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text("Début de l'extrait : $seconds s", modifier = Modifier.padding(top = 18.dp))
    Slider(value = start, onValueChange = { start = it }, valueRange = 0f..120f, steps = 119)
    Text(
        "L'extrait dure environ 15 s. Tes amis l'écoutent en direct depuis AnimeThemes, rien n'est copié.",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = { onConfirm(seconds) }, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) { Text("Choisir") }
}
