package eu.kanade.presentation.social

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.library.components.EntryCompactGridItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.multisource.MultiSourceWorkOpener
import eu.kanade.tachiyomi.data.social.activity.SocialActivityService
import eu.kanade.tachiyomi.data.social.friends.SocialFriendRemover
import eu.kanade.tachiyomi.data.social.matrix.SocialResult
import eu.kanade.tachiyomi.data.social.matrix.catalog.AuthorRoom
import eu.kanade.tachiyomi.data.social.matrix.catalog.SocialThreadCatalog
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogRepository
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.social.home.ActivityRow
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.anime.interactor.GetAnimeFavorites
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * A friend's profile: who they are, what you share, what they did lately and what they
 * watch. Their "library" is made of the works they opened a conversation about.
 */
class FriendLibraryScreen(
    private val authorMxid: String,
    private val displayName: String?,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val catalog = remember { Injekt.get<SocialThreadCatalog>() }
        val catalogRepo = remember { CatalogRepository(Injekt.get<NetworkHelper>()) }
        val avatarLoader = remember { Injekt.get<SocialAvatarLoader>() }
        val getAnimeFavorites = remember { Injekt.get<GetAnimeFavorites>() }
        val activityService = remember { Injekt.get<SocialActivityService>() }
        val friendRemover = remember { Injekt.get<SocialFriendRemover>() }
        val multiSourceOpener = remember { MultiSourceWorkOpener() }
        val workOpener = remember { SocialWorkOpener() }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        var rooms by remember { mutableStateOf<List<AuthorRoom>?>(null) }
        var localAnimes by remember { mutableStateOf<List<Anime>>(emptyList()) }
        var resolved by remember { mutableStateOf<Map<String, ResolvedItem>>(emptyMap()) }
        var error by remember { mutableStateOf<String?>(null) }
        var openingWork by remember { mutableStateOf(false) }
        var menuOpen by remember { mutableStateOf(false) }
        var confirmRemoval by remember { mutableStateOf(false) }
        var removing by remember { mutableStateOf(false) }

        val feed by activityService.feed.collectAsStateWithLifecycle()
        val friendActivity = remember(feed) { feed.filter { it.actorMxid == authorMxid }.take(MAX_ACTIVITY) }
        val name = displayName?.takeIf { it.isNotBlank() } ?: authorMxid.substringBefore(':').removePrefix("@")

        LaunchedEffect(authorMxid) {
            when (val result = catalog.discoverRoomsFor(authorMxid)) {
                is SocialResult.Success -> rooms = result.value
                is SocialResult.Failure -> error = result.error.toString()
            }
        }
        LaunchedEffect(Unit) { localAnimes = getAnimeFavorites.await() }

        LaunchedEffect(rooms, localAnimes) {
            val list = rooms ?: return@LaunchedEffect
            val currentMap = mutableMapOf<String, ResolvedItem>()
            for (room in list) {
                val title = SocialWorkMatching.extractTitle(room.roomName)
                currentMap[room.roomId] = when {
                    title.isNullOrBlank() -> ResolvedItem.Unknown(room)
                    else -> SocialWorkMatching.matchTitle(title, localAnimes) { it.title }
                        ?.let { ResolvedItem.Local(it) }
                        ?: run {
                            val cleaned = SocialWorkMatching.cleanTitleForSearch(title)
                            val work = runCatching {
                                catalogRepo.search(cleaned).firstOrNull()
                                    ?: if (cleaned != title) catalogRepo.search(title).firstOrNull() else null
                            }.getOrNull()
                            work?.let { ResolvedItem.Network(it) } ?: ResolvedItem.Unknown(room)
                        }
                }
                resolved = currentMap.toMap()
            }
        }

        val avatar by produceState<ImageBitmap?>(null, authorMxid) {
            value = avatarLoader.loadProfileAvatar(authorMxid, 256)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }

        val inCommon = resolved.values.count { it is ResolvedItem.Local }
        val bannerModel: Any? = resolved.values.firstNotNullOfOrNull {
            when (it) {
                is ResolvedItem.Local -> it.anime.thumbnailUrl
                is ResolvedItem.Network -> it.work.bannerUrl ?: it.work.coverUrl
                is ResolvedItem.Unknown -> null
            }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Box(Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(112.dp),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        ProfileHeader(
                            name = name,
                            handle = shortHandle(authorMxid),
                            avatar = avatar,
                            bannerModel = bannerModel,
                            worksCount = rooms?.size,
                            inCommon = inCommon,
                        )
                    }

                    if (friendActivity.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "activity-title") {
                            SectionLabel("Activité récente")
                        }
                        friendActivity.forEach { activity ->
                            item(span = { GridItemSpan(maxLineSpan) }, key = "activity-${activity.eventId}") {
                                ActivityRow(
                                    activity = activity,
                                    actorName = name,
                                    showActor = false,
                                    inLibrary = activityService.isInLibrary(activity.title),
                                    onClick = {
                                        if (openingWork) return@ActivityRow
                                        openingWork = true
                                        scope.launch {
                                            val screen = workOpener.open(
                                                activity.title,
                                                isManga = activity.kind ==
                                                    eu.kanade.tachiyomi.data.social.activity.SocialActivityKind.MANGA_ADDED,
                                            )
                                            openingWork = false
                                            if (screen != null) {
                                                navigator.push(screen)
                                            } else {
                                                snackbar.showSnackbar("Œuvre introuvable")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }, key = "library-title") {
                        SectionLabel("Bibliothèque")
                    }

                    when {
                        error != null -> item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                            CenteredText("Impossible de charger sa bibliothèque")
                        }
                        rooms == null -> item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        rooms.isNullOrEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                            CenteredText("$name n'a encore partagé aucune œuvre")
                        }
                        else -> items(rooms!!, key = { it.roomId }) { room ->
                            when (val r = resolved[room.roomId]) {
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
                                            val localId = multiSourceOpener.open(r.work)
                                            openingWork = false
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

                // Top bar floating over the banner.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    OverlayIconButton(onClick = navigator::pop) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        OverlayIconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "Plus d'options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Copier l'identifiant") },
                                onClick = {
                                    menuOpen = false
                                    context.getSystemService(ClipboardManager::class.java)
                                        ?.setPrimaryClip(ClipData.newPlainText("Identifiant", authorMxid))
                                    scope.launch { snackbar.showSnackbar("Identifiant copié") }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Retirer des amis", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    confirmRemoval = true
                                },
                            )
                        }
                    }
                }

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

        if (confirmRemoval) {
            AlertDialog(
                onDismissRequest = { if (!removing) confirmRemoval = false },
                title = { Text("Retirer $name ?") },
                text = {
                    Text(
                        "Tu quittes ses salons et il n'aura plus accès aux tiens. " +
                            "Pour redevenir amis, il faudra une nouvelle demande.",
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !removing,
                        onClick = {
                            removing = true
                            scope.launch {
                                friendRemover.unfriend(authorMxid)
                                removing = false
                                confirmRemoval = false
                                navigator.pop()
                            }
                        },
                    ) {
                        if (removing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Retirer", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmRemoval = false }, enabled = !removing) { Text("Annuler") }
                },
            )
        }
    }

    private companion object {
        const val MAX_ACTIVITY = 5
    }
}

private fun shortHandle(mxid: String): String =
    if (mxid.endsWith(":matrix.org")) mxid.substringBefore(':') else mxid

@Composable
private fun ProfileHeader(
    name: String,
    handle: String,
    avatar: ImageBitmap?,
    bannerModel: Any?,
    worksCount: Int?,
    inCommon: Int,
) {
    val surface = MaterialTheme.colorScheme.background
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BANNER_HEIGHT),
        ) {
            if (bannerModel != null) {
                AsyncImage(
                    model = bannerModel,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(18.dp),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to surface.copy(alpha = 0.15f),
                            0.6f to surface.copy(alpha = 0.55f),
                            1f to surface,
                        ),
                    ),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Overlap the banner by half the avatar, without leaving a gap below.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val shift = (AVATAR_SIZE / 2).roundToPx()
                    layout(placeable.width, placeable.height - shift) { placeable.place(0, -shift) }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(AVATAR_SIZE)
                    .clip(CircleShape)
                    .border(3.dp, surface, CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatChip(value = worksCount?.toString() ?: "…", label = "œuvres")
                StatChip(value = inCommon.toString(), label = "en commun")
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverlayIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        ),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun CenteredText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
    )
}

private sealed interface ResolvedItem {
    data class Local(val anime: Anime) : ResolvedItem
    data class Network(val work: CatalogWork) : ResolvedItem
    data class Unknown(val room: AuthorRoom) : ResolvedItem
}

@Composable
private fun UnknownCard(room: AuthorRoom) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = SocialWorkMatching.extractTitle(room.roomName) ?: room.roomName,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

private val BANNER_HEIGHT = 200.dp
private val AVATAR_SIZE = 104.dp
