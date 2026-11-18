package eu.kanade.presentation.social

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.library.components.EntryCompactGridItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.multisource.MultiSourceWorkOpener
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.catalog.AuthorRoom
import eu.kanade.tachiyomi.data.social.matrix.catalog.SocialThreadCatalog
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogRepository
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FriendLibraryScreen(
    private val authorMxid: String,
    private val displayName: String?,
) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val catalog = remember { Injekt.get<SocialThreadCatalog>() }
        val catalogRepo = remember { CatalogRepository(Injekt.get<NetworkHelper>()) }
        val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
        val getAnimeFavorites = remember { Injekt.get<GetAnimeFavorites>() }
        val multiSourceOpener = remember { MultiSourceWorkOpener() }
        val scope = rememberCoroutineScope()

        var rooms by remember { mutableStateOf<List<AuthorRoom>?>(null) }
        var localAnimes by remember { mutableStateOf<List<Anime>>(emptyList()) }
        var resolved by remember { mutableStateOf<Map<String, ResolvedItem>>(emptyMap()) }
        var error by remember { mutableStateOf<String?>(null) }
        
        // État pour afficher un loader lors de la création de l'anime local
        var openingWork by remember { mutableStateOf(false) }

        LaunchedEffect(authorMxid) {
            val result = catalog.discoverRoomsFor(authorMxid)
            if (result is SocialResult.Success) {
                rooms = result.value
            } else if (result is SocialResult.Failure) {
                error = result.error.toString()
            }
        }

        LaunchedEffect(Unit) {
            localAnimes = getAnimeFavorites.await()
        }

        LaunchedEffect(rooms, localAnimes) {
            val list = rooms ?: return@LaunchedEffect
            val currentMap = mutableMapOf<String, ResolvedItem>()

            for (room in list) {
                val title = extractTitle(room.roomName)
                if (title.isNullOrBlank()) {
                    currentMap[room.roomId] = ResolvedItem.Unknown(room)
                    resolved = currentMap.toMap()
                    continue
                }

                val local = matchLocalTitle(title, localAnimes)
                if (local != null) {
                    currentMap[room.roomId] = ResolvedItem.Local(local)
                    resolved = currentMap.toMap()
                    continue
                }

                val cleaned = cleanTitleForSearch(title)
                val work = catalogRepo.search(cleaned).firstOrNull()
                    ?: if (cleaned != title) catalogRepo.search(title).firstOrNull() else null
                
                if (work != null) {
                    currentMap[room.roomId] = ResolvedItem.Network(work)
                } else {
                    currentMap[room.roomId] = ResolvedItem.Unknown(room)
                }
                resolved = currentMap.toMap()
            }
        }

        val avatarBitmap by produceState<ImageBitmap?>(initialValue = null, authorMxid) {
            value = avatarLoader.loadProfileAvatar(authorMxid)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val bmp = avatarBitmap
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.size(36.dp),
                                        )
                                    } else {
                                        Icon(Icons.Outlined.PeopleAlt, contentDescription = null)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = displayName ?: authorMxid.substringBefore(':').removePrefix("@"),
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "Bibliothèque",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = navigator::pop) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                    )
                },
            ) { padding ->
                when {
                    error != null -> ErrorState(error!!, Modifier.padding(padding))
                    rooms == null -> LoadingState(Modifier.padding(padding))
                    rooms.isNullOrEmpty() -> EmptyState(Modifier.padding(padding))
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().padding(padding),
                        ) {
                            items(rooms!!, key = { it.roomId }) { room ->
                                val r = resolved[room.roomId]
                                when (r) {
                                    is ResolvedItem.Local -> EntryCompactGridItem(
                                        coverData = AnimeCover(
                                            animeId = r.anime.id,
                                            sourceId = r.anime.source,
                                            isAnimeFavorite = r.anime.favorite,
                                            url = r.anime.thumbnailUrl,
                                            lastModified = r.anime.coverLastModified,
                                        ),
                                        title = r.anime.title,
                                        onClick = { navigator.push(AnimeScreen(animeId = r.anime.id, fromSource = false)) },
                                        onLongClick = {},
                                    )
                                    is ResolvedItem.Network -> EntryCompactGridItem(
                                        coverData = AnimeCover(
                                            animeId = -1L,
                                            sourceId = -1L,
                                            isAnimeFavorite = false,
                                            url = r.work.coverUrl,
                                            lastModified = 0L,
                                        ),
                                        title = r.work.title,
                                        onClick = {
                                            if (openingWork) return@EntryCompactGridItem
                                            openingWork = true
                                            scope.launch {
                                                // Crée l'anime local à la volée et récupère son ID
                                                val localId = multiSourceOpener.open(r.work)
                                                openingWork = false
                                                // Ouvre la fiche native avec le bouton Chat
                                                navigator.push(AnimeScreen(animeId = localId, fromSource = true))
                                            }
                                        },
                                        onLongClick = {},
                                    )
                                    is ResolvedItem.Unknown -> UnknownCard(room)
                                    null -> LoadingCard()
                                }
                            }
                        }
                    }
                }
            }
            
            // Overlay de chargement lors de l'ouverture d'une fiche réseau
            if (openingWork) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private sealed interface ResolvedItem {
    data class Local(val anime: Anime) : ResolvedItem
    data class Network(val work: CatalogWork) : ResolvedItem
    data class Unknown(val room: AuthorRoom) : ResolvedItem
}

private fun extractTitle(roomName: String): String? {
    val sep = " — "
    val idx = roomName.indexOf(sep)
    if (idx == -1) {
        if (roomName.startsWith("Shiro", ignoreCase = true)) return null
        return roomName.trim().takeIf { it.isNotBlank() }
    }
    val title = roomName.substring(0, idx).trim()
    if (title.equals("Shiro", ignoreCase = true)) return null
    return title.takeIf { it.isNotBlank() }
}

private fun matchLocalTitle(roomTitle: String, localAnimes: List<Anime>): Anime? {
    val normRoom = roomTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
    localAnimes.find {
        val normLocal = it.title.lowercase().replace(Regex("[^a-z0-9]"), "")
        normLocal == normRoom || normLocal.contains(normRoom) || normRoom.contains(normLocal)
    }?.let { return it }

    val roomWords = roomTitle.lowercase().split(Regex("\\s+"))
        .map { it.replace(Regex("[^a-z0-9]"), "") }
        .filter { it.length >= 3 }
    if (roomWords.isEmpty()) return null

    return localAnimes.find { anime ->
        val localWords = anime.title.lowercase().split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-z0-9]"), "") }
            .filter { it.length >= 3 }
        roomWords.any { rw -> localWords.any { lw -> lw == rw || lw.contains(rw) || rw.contains(lw) } }
    }
}

private fun cleanTitleForSearch(raw: String): String {
    var s = raw
    s = s.replace(
        Regex(
            """\s*[\(\[]\s*(vostfr|vf|vo|multi|1080p|720p|480p|hd|sd|web-?dl|bluray|bd)[^\)\]]*[\)\]]""",
            RegexOption.IGNORE_CASE,
        ),
        "",
    )
    s = s.replace(Regex("""\s+(saison|season|part|partie)\s*\d+""", RegexOption.IGNORE_CASE), "")
    s = s.replace(Regex("""\s+s\d+(?:\s|$)""", RegexOption.IGNORE_CASE), " ")
    s = s.replace(Regex("""\s+ep?\s*\d+""", RegexOption.IGNORE_CASE), "")
    s = s.replace(Regex("""\s+(vostfr|vf)\s*$""", RegexOption.IGNORE_CASE), "")
    return s.trim().replace(Regex("""\s{2,}"""), " ")
}

@Composable
private fun UnknownCard(room: AuthorRoom) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = room.roomName,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.PeopleAlt, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.size(8.dp))
            Text("Aucune room trouvée pour cet ami")
        }
    }
}

@Composable
private fun ErrorState(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Erreur : $message", color = MaterialTheme.colorScheme.error)
    }
}