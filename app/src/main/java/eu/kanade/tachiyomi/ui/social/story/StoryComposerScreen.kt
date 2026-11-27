package eu.kanade.tachiyomi.ui.social.story

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.social.matrix.media.SocialAvatarLoader
import eu.kanade.tachiyomi.data.social.story.PublishResult
import eu.kanade.tachiyomi.data.social.story.QuotaWindow
import eu.kanade.tachiyomi.data.social.story.SendVerdict
import eu.kanade.tachiyomi.data.social.story.SocialStoryService
import eu.kanade.tachiyomi.data.social.story.StoryLimits
import eu.kanade.tachiyomi.data.social.story.StoryUpload
import eu.kanade.tachiyomi.data.social.story.ThemeRef
import eu.kanade.tachiyomi.ui.social.ChatImagePreparer
import eu.kanade.tachiyomi.ui.social.ChatPicture
import eu.kanade.tachiyomi.ui.social.PictureView
import eu.kanade.tachiyomi.ui.social.SocialChatFriend
import eu.kanade.tachiyomi.ui.social.ParticipantAvatar
import eu.kanade.tachiyomi.ui.social.decodeChatPicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.DateFormat
import java.util.Date

/** What the user is about to publish. */
private sealed interface Draft {
    data class Media(val upload: StoryUpload) : Draft
    data class Music(val theme: ThemeRef) : Draft
}

/**
 * "Ajouter à la story": choose a photo, a short video or a music extract, then the full-screen
 * editor with a caption and the send button, as on Instagram.
 */
class StoryComposerScreen : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val service = remember { Injekt.get<SocialStoryService>() }
        val scope = rememberCoroutineScope()

        var draft by remember { mutableStateOf<Draft?>(null) }
        var caption by remember { mutableStateOf("") }
        var sending by remember { mutableStateOf(false) }
        var loadingPick by remember { mutableStateOf(false) }
        var showQuota by remember { mutableStateOf(false) }
        var showMusic by remember { mutableStateOf(false) }
        var blocked by remember { mutableStateOf<SendVerdict?>(null) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            loadingPick = true
            scope.launch {
                val upload = prepareUpload(context, uri, service.maxFileBytes())
                loadingPick = false
                when (upload) {
                    is Prepared.Ok -> draft = Draft.Media(upload.upload)
                    is Prepared.Refused -> Toast.makeText(context, upload.reason, Toast.LENGTH_LONG).show()
                }
            }
        }

        fun send(confirmed: Boolean = false) {
            val current = draft ?: return
            sending = true
            scope.launch {
                val result = when (current) {
                    is Draft.Media -> service.publishMedia(current.upload, caption, confirmed)
                    is Draft.Music -> service.publishMusic(current.theme)
                }
                sending = false
                when (result) {
                    PublishResult.Sent -> {
                        Toast.makeText(context, "Story publiée", Toast.LENGTH_SHORT).show()
                        navigator.pop()
                    }
                    is PublishResult.Blocked -> blocked = result.verdict
                    is PublishResult.Failed -> Toast.makeText(context, "Envoi impossible : ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        val current = draft
        if (current == null) {
            ChooseStep(
                loading = loadingPick,
                onClose = navigator::pop,
                onGallery = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onMusic = { showMusic = true },
                onQuota = { showQuota = true },
            )
        } else {
            EditorStep(
                draft = current,
                caption = caption,
                onCaption = { caption = it.take(280) },
                sending = sending,
                onBack = { draft = null; caption = "" },
                onSend = { send() },
            )
        }

        if (showQuota) QuotaSheet(service = service, onDismiss = { showQuota = false })
        if (showMusic) {
            StoryMusicSheet(
                onDismiss = { showMusic = false },
                onPicked = { theme ->
                    showMusic = false
                    draft = Draft.Music(theme)
                },
            )
        }
        blocked?.let { verdict ->
            VerdictDialog(
                verdict = verdict,
                onConfirm = { blocked = null; send(confirmed = true) },
                onDismiss = { blocked = null },
            )
        }
    }
}

@Composable
private fun ChooseStep(
    loading: Boolean,
    onClose: () -> Unit,
    onGallery: () -> Unit,
    onMusic: () -> Unit,
    onQuota: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Fermer", tint = Color.White) }
            Text(
                "Ajouter à la story",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onQuota) { Icon(Icons.Outlined.DataUsage, "Quota d'envoi", tint = Color.White) }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChooseCard(Icons.Outlined.PhotoLibrary, "Galerie", "Photo ou vidéo", onGallery, Modifier.weight(1f))
            ChooseCard(Icons.Outlined.MusicNote, "Musique", "Un OP ou un ED", onMusic, Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Text(
                    "Les stories durent 24 h. Une photo ou une vidéo compte dans ton quota d'envoi ; un extrait musical n'en consomme pas.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 36.dp),
                )
            }
        }
    }
}

@Composable
private fun ChooseCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(36.dp))
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun EditorStep(
    draft: Draft,
    caption: String,
    onCaption: (String) -> Unit,
    sending: Boolean,
    onBack: () -> Unit,
    onSend: () -> Unit,
) {
    val avatars = remember { Injekt.get<SocialAvatarLoader>() }
    val ownAvatar by produceState<ByteArray?>(null) { value = runCatching { avatars.loadOwnAvatar(64) }.getOrNull() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF14141C)),
        ) {
            when (draft) {
                is Draft.Media -> MediaPreview(draft.upload)
                is Draft.Music -> MusicPreview(draft.theme)
            }
            // Back button, round and dark like Instagram's.
            Box(
                Modifier
                    .padding(14.dp)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White)
            }
            if (draft is Draft.Media) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                        .padding(horizontal = 18.dp, vertical = 20.dp),
                ) {
                    BasicTextField(
                        value = caption,
                        onValueChange = onCaption,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (caption.isEmpty()) Text("Ajouter une légende…", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                            inner()
                        },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ParticipantAvatar(SocialChatFriend("@me", "Moi", ownAvatar), size = 32.dp)
                Text("Vos stories", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            }
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4A5CFF))
                    .clickable(enabled = !sending, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, "Publier", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MediaPreview(upload: StoryUpload) {
    if (upload.isVideo) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Vidéo · ${upload.durationMs?.div(1000) ?: 0} s", color = Color.White.copy(alpha = 0.8f))
        }
        return
    }
    val picture by produceState<ChatPicture?>(null, upload) {
        value = withContext(Dispatchers.Default) { decodeChatPicture(upload.bytes, 2400) }
    }
    when (val p = picture) {
        is ChatPicture.Ready -> PictureView(p.drawable, fit = true, modifier = Modifier.fillMaxSize())
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) }
    }
}

@Composable
private fun MusicPreview(theme: ThemeRef) {
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF2A1B5E), Color(0xFF0B0B14)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.MusicNote, null, tint = Color.White, modifier = Modifier.size(64.dp))
            Text(theme.label ?: "Extrait musical", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("À partir de ${theme.startSeconds / 60}:${"%02d".format(theme.startSeconds % 60)}", color = Color.White.copy(alpha = 0.7f))
        }
    }
}

// --------------------------------------------------------------------------- picking

private sealed interface Prepared {
    data class Ok(val upload: StoryUpload) : Prepared
    data class Refused(val reason: String) : Prepared
}

/** Reads the picked file. A photo is resized like in the chat; a video is kept as it is and must fit. */
private suspend fun prepareUpload(context: Context, uri: Uri, maxBytes: Long): Prepared = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri).orEmpty()
    if (mime.startsWith("video/")) {
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        } ?: -1L
        if (size > maxBytes) {
            return@withContext Prepared.Refused(
                "Cette vidéo fait ${size / StoryLimits.MB} Mo : la limite est de ${maxBytes / StoryLimits.MB} Mo. Choisis une vidéo plus courte.",
            )
        }
        val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return@withContext Prepared.Refused("Vidéo illisible")
        val retriever = MediaMetadataRetriever()
        val (w, h, duration) = try {
            retriever.setDataSource(context, uri)
            Triple(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } finally {
            retriever.release()
        }
        return@withContext Prepared.Ok(
            StoryUpload(bytes, "shiro-story-${System.currentTimeMillis()}.mp4", mime, w, h, duration),
        )
    }
    val image = ChatImagePreparer.prepare(context, uri)
        ?: return@withContext Prepared.Refused("Image illisible ou trop lourde")
    Prepared.Ok(StoryUpload(image.bytes, image.filename, image.mimeType, image.width, image.height))
}

// --------------------------------------------------------------------------- dialogs and sheets

@Composable
private fun VerdictDialog(verdict: SendVerdict, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val (title, text) = when (verdict) {
        is SendVerdict.FileTooBig ->
            "Fichier trop lourd" to "Ce fichier fait ${verdict.bytes / StoryLimits.MB} Mo, la limite est de ${verdict.maxBytes / StoryLimits.MB} Mo."
        is SendVerdict.QuotaExceeded -> {
            val window = if (verdict.reason == QuotaWindow.DAY) "des dernières 24 h (100 Mo)" else "des 28 derniers jours (500 Mo)"
            val back = verdict.retryAtMillis?.let { "Il revient vers le ${formatter.format(Date(it))} environ." }
                ?: "Ce fichier est plus gros que le quota entier."
            "Quota atteint" to "Ton envoi dépasse le quota $window. $back"
        }
        is SendVerdict.NeedsConfirmation ->
            "Gros envoi" to "Ce fichier fait ${verdict.bytes / StoryLimits.MB} Mo. L'envoyer quand même ?"
        SendVerdict.Allowed -> "" to ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            if (verdict is SendVerdict.NeedsConfirmation) TextButton(onClick = onConfirm) { Text("Envoyer") }
            else TextButton(onClick = onDismiss) { Text("OK") }
        },
        dismissButton = if (verdict is SendVerdict.NeedsConfirmation) {
            { TextButton(onClick = onDismiss) { Text("Annuler") } }
        } else {
            null
        },
    )
}

/** What was sent in the last 24 hours and 28 days, and when the quota comes back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuotaSheet(service: SocialStoryService, onDismiss: () -> Unit) {
    val status by service.quota.collectAsStateWithLifecycle()
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val next = remember { service.nextQuotaReturn() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Quota d'envoi", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            QuotaLine("Dernières 24 h", status.usedDay, StoryLimits.DAILY_BYTES)
            QuotaLine("28 derniers jours", status.usedMonth, StoryLimits.MONTHLY_BYTES)
            Text(
                text = next?.let { "Le plus ancien envoi cesse de compter vers le ${formatter.format(Date(it))} environ." }
                    ?: "Rien n'est compté pour le moment.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Seuls tes envois comptent, pas ce que tu reçois. Les extraits musicaux ne consomment rien. Les limites sont celles annoncées pour un compte gratuit ; l'app ne peut pas les vérifier auprès du serveur.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun QuotaLine(label: String, used: Long, limit: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
            Text("${"%.1f".format(used / StoryLimits.MB.toFloat())} / ${limit / StoryLimits.MB} Mo", fontSize = 14.sp)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                Modifier
                    .fillMaxWidth((used.toFloat() / limit).coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(if (used >= limit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
            )
        }
    }
}
