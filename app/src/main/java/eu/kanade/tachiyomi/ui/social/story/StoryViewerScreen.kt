package eu.kanade.tachiyomi.ui.social.story

import eu.kanade.tachiyomi.data.social.story.ThemeResolver
import eu.kanade.tachiyomi.data.social.story.ThemePlayback
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.social.friends.SocialFriendStore
import eu.kanade.tachiyomi.data.social.matrix.MatrixSessionManager
import eu.kanade.tachiyomi.data.social.matrix.SocialSessionState
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.story.SocialStoryService
import eu.kanade.tachiyomi.data.social.story.StoryContent
import eu.kanade.tachiyomi.data.social.story.StoryExpiry
import eu.kanade.tachiyomi.data.social.story.StoryItem
import eu.kanade.tachiyomi.ui.social.ChatPicture
import eu.kanade.tachiyomi.ui.social.ParticipantAvatar
import eu.kanade.tachiyomi.ui.social.PictureView
import eu.kanade.tachiyomi.ui.social.SocialChatFriend
import eu.kanade.tachiyomi.ui.social.decodeChatPicture
import eu.kanade.tachiyomi.ui.social.home.shortHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private const val PHOTO_MS = 6_000
private const val MUSIC_MS = 15_000
private const val DEFAULT_VIDEO_MS = 8_000

/**
 * Plays the stories of one person, full screen, like Instagram: segments at the top, tap the
 * right side for the next story and the left side for the previous one, hold to pause.
 */
class StoryViewerScreen(private val authorMxid: String) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val service = remember { Injekt.get<SocialStoryService>() }
        val session = remember { Injekt.get<MatrixSessionManager>() }
        val friends = remember { Injekt.get<SocialFriendStore>() }
        val avatars = remember { Injekt.get<SocialAvatarLoader>() }
        val scope = rememberCoroutineScope()

        val self = (session.state.value as? SocialSessionState.Authenticated)?.account?.userId
        val isOwn = authorMxid == self
        val reel = remember { service.reels.value.firstOrNull { it.authorMxid == authorMxid } }
        if (reel == null || reel.items.isEmpty()) {
            LaunchedEffect(Unit) { navigator.pop() }
            return
        }

        val name = remember(authorMxid) {
            if (isOwn) "Votre story" else friends.friends.value.firstOrNull { it.mxid == authorMxid }?.displayName
                ?: shortHandle(authorMxid)
        }
        val avatar by produceState<ByteArray?>(null, authorMxid) {
            value = runCatching { avatars.loadProfileAvatar(authorMxid, 96) }.getOrNull()
        }

        var items by remember { mutableStateOf(reel.items) }
        // Starts at the first story the viewer has not seen yet.
        var index by remember { mutableIntStateOf(0) }
        var paused by remember { mutableStateOf(false) }
        var showOptions by remember { mutableStateOf(false) }
        val progress = remember { Animatable(0f) }

        val current = items.getOrNull(index)
        if (current == null) {
            LaunchedEffect(Unit) { navigator.pop() }
            return
        }

        fun next() {
            if (index < items.lastIndex) index++ else navigator.pop()
        }

        fun previous() {
            if (index > 0) index-- else scope.launch { progress.snapTo(0f) }
        }

        LaunchedEffect(current.eventId) { service.markSeen(authorMxid, current.postedAtMillis) }

        var durationMs by remember(current.eventId) { mutableIntStateOf(defaultDuration(current)) }
        LaunchedEffect(current.eventId, paused, durationMs) {
            if (paused) return@LaunchedEffect
            val remaining = ((1f - progress.value) * durationMs).toInt().coerceAtLeast(1)
            progress.animateTo(1f, tween(remaining, easing = LinearEasing))
            next()
        }
        LaunchedEffect(current.eventId) { progress.snapTo(0f) }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(index, items.size) {
                    detectTapGestures(
                        onPress = {
                            paused = true
                            tryAwaitRelease()
                            paused = false
                        },
                        onTap = { offset -> if (offset.x < size.width / 3f) previous() else next() },
                    )
                },
        ) {
            StoryBody(
                item = current,
                service = service,
                paused = paused,
                onDuration = { durationMs = it },
                modifier = Modifier.fillMaxSize(),
            )

            // Dark veils so the header and the caption stay readable on any picture.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
            )

            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    items.forEachIndexed { i, _ ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.35f)),
                        ) {
                            val fill = when {
                                i < index -> 1f
                                i == index -> progress.value
                                else -> 0f
                            }
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ParticipantAvatar(
                        participant = SocialChatFriend(authorMxid, name, avatar),
                        size = 34.dp,
                    )
                    Text(
                        text = name,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                    Text(
                        text = StoryExpiry.ago(current.postedAtMillis, System.currentTimeMillis()),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                    Box(Modifier.weight(1f))
                    if (isOwn) {
                        IconButton(onClick = { showOptions = true; paused = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Options", tint = Color.White)
                        }
                    }
                }
            }

            current.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                ) {
                    Text(caption, color = Color.White, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }

        if (showOptions) {
            ModalBottomSheet(onDismissRequest = { showOptions = false; paused = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        text = "Disparaît dans ${hoursLeft(current)}. L'app la masque puis retire son contenu du message ; " +
                            "le fichier lui-même peut rester sur le serveur.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .pointerInput(current.eventId) {
                                detectTapGestures {
                                    scope.launch {
                                        if (service.delete(current)) {
                                            showOptions = false
                                            val remaining = items.filterNot { it.eventId == current.eventId }
                                            if (remaining.isEmpty()) {
                                                navigator.pop()
                                            } else {
                                                items = remaining
                                                index = index.coerceAtMost(remaining.lastIndex)
                                                paused = false
                                            }
                                        }
                                    }
                                }
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error)
                        Text(
                            "Supprimer",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }
            }
        }
    }

    private fun defaultDuration(item: StoryItem): Int = when (val c = item.content) {
        is StoryContent.Media -> if (c.isVideo) (c.durationMs?.toInt() ?: DEFAULT_VIDEO_MS).coerceIn(2_000, 30_000) else PHOTO_MS
        is StoryContent.Music -> MUSIC_MS
    }

    private fun hoursLeft(item: StoryItem): String {
        val left = (item.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val hours = left / 3_600_000
        return if (hours >= 1) "$hours h" else "${left / 60_000} min"
    }
}

/** What the story shows: the picture, the video, or the music card. */
@Composable
private fun StoryBody(
    item: StoryItem,
    service: SocialStoryService,
    paused: Boolean,
    onDuration: (Int) -> Unit,
    modifier: Modifier,
) {
    when (val content = item.content) {
        is StoryContent.Media -> if (content.isVideo) {
            StoryVideo(item, service, paused, onDuration, modifier)
        } else {
            StoryPhoto(item, service, modifier)
        }
        is StoryContent.Music -> StoryMusic(content, paused, modifier)
    }
}

@Composable
private fun StoryPhoto(item: StoryItem, service: SocialStoryService, modifier: Modifier) {
    val picture by produceState<ChatPicture?>(null, item.eventId) {
        value = withContext(Dispatchers.Default) {
            service.loadMedia(item)?.let { decodeChatPicture(it, 2400) } ?: ChatPicture.Broken
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when (val p = picture) {
            is ChatPicture.Ready -> PictureView(p.drawable, fit = true, modifier = Modifier.fillMaxSize())
            ChatPicture.Broken -> Icon(Icons.Outlined.BrokenImage, "Image indisponible", tint = Color.White.copy(alpha = 0.7f))
            null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun StoryVideo(item: StoryItem, service: SocialStoryService, paused: Boolean, onDuration: (Int) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val file by produceState<File?>(null, item.eventId) {
        value = withContext(Dispatchers.IO) {
            service.loadMedia(item)?.let { bytes ->
                File(context.cacheDir, "story_${item.eventId.hashCode()}.mp4").also { it.writeBytes(bytes) }
            }
        }
    }
    var view by remember { mutableStateOf<VideoView?>(null) }
    LaunchedEffect(paused, view) {
        val v = view ?: return@LaunchedEffect
        if (paused) v.pause() else v.start()
    }
    DisposableEffect(item.eventId) { onDispose { view?.stopPlayback(); file?.delete() } }
    Box(modifier, contentAlignment = Alignment.Center) {
        val ready = file
        if (ready == null) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(ready.absolutePath)
                        setOnPreparedListener { player ->
                            player.isLooping = false
                            if (duration > 0) onDuration(duration.coerceIn(2_000, 30_000))
                            start()
                        }
                        view = this
                    }
                },
            )
        }
    }
}

/**
 * A music story: the card (artwork, title, artist) and, when the stream can be found, the extract.
 * If AnimeThemes does not answer, the card still shows (artwork from AniList, texts from Jikan)
 * and only the sound is missing, with a clear message.
 */
@Composable
private fun StoryMusic(content: StoryContent.Music, paused: Boolean, modifier: Modifier) {
    val resolver = remember { ThemeResolver(Injekt.get()) }
    val theme = content.theme
    val playback by produceState<ThemePlayback?>(null, theme) { value = resolver.resolve(theme) }
    val player = remember { MediaPlayer() }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(playback) {
        val resolved = playback ?: return@LaunchedEffect
        val url = resolved.streamUrl
        if (url == null) {
            failed = true
            return@LaunchedEffect
        }
        runCatching {
            player.reset()
            player.setDataSource(url)
            player.setOnPreparedListener {
                it.seekTo(theme.startSeconds * 1000)
                it.start()
            }
            player.setOnErrorListener { _, _, _ -> failed = true; true }
            player.prepareAsync()
        }.onFailure { failed = true }
    }
    LaunchedEffect(paused) {
        runCatching { if (paused) player.pause() else if (playback?.streamUrl != null && !failed && !player.isPlaying) player.start() }
    }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }

    val meta = playback?.metadata
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF2A1B5E), Color(0xFF0B0B14)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(180.dp).clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (meta?.coverUrl != null) {
                    coil3.compose.AsyncImage(
                        model = meta.coverUrl,
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Outlined.MusicNote, null, tint = Color.White, modifier = Modifier.size(56.dp))
                }
            }
            Text(
                text = meta?.title ?: theme.label ?: "Extrait musical",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 32.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            val subtitle = listOfNotNull(meta?.artist, meta?.slug ?: theme.slug).joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
            }
            Text(
                text = when {
                    failed -> "Impossible de charger cet extrait"
                    playback == null -> "Chargement…"
                    else -> "Depuis ${theme.startSeconds / 60}:${"%02d".format(theme.startSeconds % 60)}"
                },
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
        }
    }
}
