package eu.kanade.tachiyomi.data.telegram.tdlib

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

/**
 * Lit l'historique d'un canal Telegram et expose les fichiers téléchargeables.
 *
 * L'extension fournit une référence de canal :
 * - identifiant numérique TDLib ;
 * - @username ;
 * - https://t.me/username ;
 * - lien d'invitation privé si le compte est déjà membre.
 */
class TelegramChannelScraper(
    private val authManager: TelegramAuthManager,
) {

    /**
     * Résout une référence de canal en chatId TDLib.
     *
     * TODO(Extension) : brancher cette méthode sur le champ "channelId" fourni
     * par l'extension. Ne pas accepter un lien d'invitation si l'utilisateur
     * n'a pas explicitement rejoint le canal.
     */
    suspend fun resolveChannelId(channelRef: String): Long = withContext(Dispatchers.IO) {
        val ref = channelRef.trim()
        if (ref.isEmpty()) {
            throw TelegramTdlibException(0, "Référence de canal vide.")
        }

        if (NUMERIC_CHANNEL_ID.matches(ref)) {
            val chatId = try {
                ref.toLong()
            } catch (t: NumberFormatException) {
                throw TelegramTdlibException(0, "Identifiant de canal invalide: $ref")
            }

            // GetChat valide l'existence du canal et les droits d'accès du compte.
            authManager.requireClient().sendAwait<TdApi.Chat>(TdApi.GetChat(chatId))
            return@withContext chatId
        }

        if (isInviteLink(ref)) {
            val inviteLink = normalizeInviteLink(ref)
            val info = authManager.requireClient().sendAwait<TdApi.ChatInviteLinkInfo>(
                TdApi.CheckChatInviteLink(inviteLink),
            )

            if (info.chatId == 0L) {
                throw TelegramTdlibException(
                    0,
                    "Le compte n'est pas encore membre de ce canal privé. " +
                        "Rejoignez-le dans Telegram puis réessayez.",
                )
            }

            return@withContext info.chatId
        }

        val username = extractUsername(ref)
            ?: throw TelegramTdlibException(0, "Référence de canal invalide: $ref")

        val chat = authManager.requireClient().sendAwait<TdApi.Chat>(
            TdApi.SearchPublicChat(username),
        )
        chat.id
    }

    /**
     * Retourne les informations de base du canal, y compris l'appartenance du
     * compte et le type « forum ».
     */
    suspend fun getChannelInfo(channelId: Long): TelegramChannelInfo = withContext(Dispatchers.IO) {
        val client = authManager.requireClient()
        val chat = client.sendAwait<TdApi.Chat>(TdApi.GetChat(channelId))

        var isMember = true
        var isForum = false

        when (val type = chat.type) {
            is TdApi.ChatTypeSupergroup -> {
                val supergroup = runCatching {
                    client.sendAwait<TdApi.Supergroup>(TdApi.GetSupergroup(type.supergroupId))
                }.getOrNull()
                if (supergroup != null) {
                    isMember = supergroup.status.isActiveMember()
                    isForum = supergroup.isForum
                }
            }
            is TdApi.ChatTypeBasicGroup -> {
                val group = runCatching {
                    client.sendAwait<TdApi.BasicGroup>(TdApi.GetBasicGroup(type.basicGroupId))
                }.getOrNull()
                if (group != null) {
                    isMember = group.status.isActiveMember()
                }
            }
            else -> Unit
        }

        TelegramChannelInfo(
            id = chat.id,
            title = chat.title,
            isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel ?: false,
            isMember = isMember,
            isForum = isForum,
        )
    }

    /** Rejoint un canal public (ou un groupe) avec le compte connecté. */
    suspend fun joinChannel(channelId: Long) = withContext(Dispatchers.IO) {
        if (channelId == 0L) throw TelegramTdlibException(0, "chatId TDLib invalide.")
        authManager.requireClient().sendAwait<TdApi.ChatJoinResult>(TdApi.JoinChat(channelId))
        Unit
    }

    private fun TdApi.ChatMemberStatus.isActiveMember(): Boolean = when (this) {
        is TdApi.ChatMemberStatusMember,
        is TdApi.ChatMemberStatusAdministrator,
        is TdApi.ChatMemberStatusCreator,
        -> true
        is TdApi.ChatMemberStatusRestricted -> isMember
        else -> false
    }

    /**
     * Parcourt l'historique du canal et retourne les fichiers trouvés.
     *
     * @param messageLimit nombre maximum de messages examinés, pas nombre de fichiers.
     *        TDLib limite chaque appel à 100 messages ; la pagination est gérée ici.
     * @param fromMessageId point de départ ; 0 = message le plus récent. Sert de
     *        curseur pour reprendre un historique entamé.
     * @param sinceMessageId arrête le parcours dès qu'un message plus ancien que cet
     *        identifiant est rencontré (synchronisation incrémentale).
     */
    suspend fun scan(
        channelId: Long,
        messageLimit: Int = DEFAULT_MESSAGE_LIMIT,
        fromMessageId: Long = 0L,
        sinceMessageId: Long = 0L,
    ): TelegramScanResult = withContext(Dispatchers.IO) {
        if (channelId == 0L) {
            throw TelegramTdlibException(0, "chatId TDLib invalide.")
        }
        if (messageLimit <= 0) {
            return@withContext TelegramScanResult(
                files = emptyList(),
                messagesScanned = 0,
                oldestMessageId = fromMessageId,
                newestMessageId = fromMessageId,
                reachedStart = false,
            )
        }

        val client = authManager.requireClient()
        val files = mutableListOf<TelegramFileMetadata>()
        val seenMessageIds = mutableSetOf<Long>()

        // from_message_id = 0 ne renvoie que le dernier message dans cette
        // version de TDLib : on part explicitement de son identifiant.
        var cursor = fromMessageId
        if (cursor == 0L) {
            val chat = client.sendAwait<TdApi.Chat>(TdApi.GetChat(channelId))
            cursor = chat.lastMessage?.id ?: 0L
            if (cursor == 0L) {
                return@withContext TelegramScanResult(
                    files = emptyList(),
                    messagesScanned = 0,
                    oldestMessageId = 0L,
                    newestMessageId = 0L,
                    reachedStart = true,
                )
            }
        }

        var scannedMessages = 0
        var oldestMessageId = Long.MAX_VALUE
        var newestMessageId = 0L
        var reachedStart = false
        var reachedKnownHistory = false

        while (scannedMessages < messageLimit && !reachedKnownHistory) {
            val pageSize = minOf(MAX_PAGE_SIZE, messageLimit - scannedMessages)
            val page = client.sendAwait<TdApi.Messages>(
                TdApi.GetChatHistory(
                    channelId,
                    cursor,
                    0,
                    pageSize,
                    false,
                ),
            )

            if (page.messages.isEmpty()) {
                reachedStart = true
                break
            }

            var pageOldest = Long.MAX_VALUE

            for (message in page.messages) {
                pageOldest = minOf(pageOldest, message.id)
                oldestMessageId = minOf(oldestMessageId, message.id)
                newestMessageId = maxOf(newestMessageId, message.id)

                if (sinceMessageId > 0L && message.id <= sinceMessageId) {
                    reachedKnownHistory = true
                    break
                }

                if (!seenMessageIds.add(message.id)) {
                    continue
                }

                scannedMessages++
                message.toFileMetadata()?.let(files::add)
            }

            if (reachedKnownHistory) break

            // Ne pas conclure « début d'historique » sur une page incomplète :
            // TDLib peut renvoyer moins de messages que demandé alors qu'il en
            // reste. On continue tant qu'un message plus ancien est renvoyé.
            //
            // Sécurité anti-boucle : ne pas redemander indéfiniment le même message.
            if (pageOldest == Long.MAX_VALUE || pageOldest == cursor) {
                reachedStart = true
                break
            }

            cursor = pageOldest
        }

        TelegramScanResult(
            files = files.distinctBy { it.messageId },
            messagesScanned = scannedMessages,
            oldestMessageId = oldestMessageId.takeIf { it != Long.MAX_VALUE } ?: fromMessageId,
            newestMessageId = newestMessageId,
            reachedStart = reachedStart,
        )
    }

    /**
     * Recherche les messages contenant [query] dans un canal, via l'index de
     * recherche de Telegram (pas de parcours d'historique).
     *
     * Telegram ne renvoie que des messages correspondants : c'est ce qui
     * remplace le scan aveugle pour la recherche à la demande.
     */
    suspend fun searchFiles(
        channelId: Long,
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): TelegramSearchResult = withContext(Dispatchers.IO) {
        if (channelId == 0L || query.isBlank()) {
            return@withContext TelegramSearchResult(emptyList(), hasMore = false)
        }

        val client = authManager.requireClient()
        val files = mutableListOf<TelegramFileMetadata>()
        var fromMessageId = 0L
        var fetched = 0
        var hasMore = false

        while (fetched < limit) {
            val pageSize = minOf(MAX_PAGE_SIZE, limit - fetched)
            val page = client.sendAwait<TdApi.FoundChatMessages>(
                TdApi.SearchChatMessages(
                    channelId,
                    null,
                    query,
                    null,
                    fromMessageId,
                    0,
                    pageSize,
                    TdApi.SearchMessagesFilterEmpty(),
                ),
            )

            if (page.messages.isEmpty()) break

            fetched += page.messages.size
            page.messages.forEach { message ->
                message.toFileMetadata()?.let(files::add)
            }

            val next = page.nextFromMessageId
            if (next == 0L || next == fromMessageId) {
                hasMore = fetched >= limit
                break
            }
            fromMessageId = next
        }

        TelegramSearchResult(
            files = files.distinctBy { it.messageId },
            hasMore = hasMore,
        )
    }

    private fun TdApi.Message.toFileMetadata(): TelegramFileMetadata? {
        val file: TdApi.File
        val fileName: String
        val kind: TelegramFileKind
        val mimeType: String?
        val duration: Int?
        val width: Int?
        val height: Int?
        val caption: String?

        when (val content = content) {
            is TdApi.MessageVideo -> {
                file = content.video.video
                fileName = content.video.fileName
                kind = TelegramFileKind.VIDEO
                mimeType = content.video.mimeType
                duration = content.video.duration
                width = content.video.width
                height = content.video.height
                caption = content.caption.text
            }

            is TdApi.MessageDocument -> {
                file = content.document.document
                fileName = content.document.fileName
                kind = TelegramFileKind.DOCUMENT
                mimeType = content.document.mimeType
                duration = null
                width = null
                height = null
                caption = content.caption.text
            }

            is TdApi.MessageAnimation -> {
                file = content.animation.animation
                fileName = content.animation.fileName
                kind = TelegramFileKind.ANIMATION
                mimeType = content.animation.mimeType
                duration = content.animation.duration
                width = content.animation.width
                height = content.animation.height
                caption = content.caption.text
            }

            is TdApi.MessageAudio -> {
                file = content.audio.audio
                fileName = content.audio.fileName
                kind = TelegramFileKind.AUDIO
                mimeType = content.audio.mimeType
                duration = content.audio.duration
                width = null
                height = null
                caption = content.caption.text
            }

            is TdApi.MessagePhoto -> {
                val largest = content.photo.sizes.maxByOrNull {
                    it.width.toLong() * it.height.toLong()
                } ?: return null

                file = largest.photo
                fileName = "photo_$id.jpg"
                kind = TelegramFileKind.PHOTO
                mimeType = "image/jpeg"
                duration = null
                width = largest.width
                height = largest.height
                caption = content.caption.text
            }

            is TdApi.MessageVideoNote -> {
                file = content.videoNote.video
                fileName = "video_note_$id.mp4"
                kind = TelegramFileKind.VIDEO_NOTE
                mimeType = "video/mp4"
                duration = content.videoNote.duration
                width = content.videoNote.length
                height = content.videoNote.length
                caption = null
            }

            is TdApi.MessageVoiceNote -> {
                file = content.voiceNote.voice
                fileName = "voice_$id.ogg"
                kind = TelegramFileKind.VOICE_NOTE
                mimeType = "audio/ogg"
                duration = content.voiceNote.duration
                width = null
                height = null
                caption = content.caption.text
            }

            else -> return null
        }

        return TelegramFileMetadata(
            chatId = chatId,
            messageId = id,
            fileId = file.id,
            fileName = sanitizeFileName(
                fileName.ifBlank { defaultFileName(kind, id) },
            ),
            sizeBytes = maxOf(file.size, file.expectedSize),
            mimeType = mimeType?.takeIf { it.isNotBlank() },
            caption = caption?.takeIf { it.isNotBlank() },
            durationSeconds = duration?.takeIf { it > 0 },
            width = width?.takeIf { it > 0 },
            height = height?.takeIf { it > 0 },
            kind = kind,
            date = date.toLong(),
            messageThreadId = (topicId as? TdApi.MessageTopicForum)?.forumTopicId?.toLong() ?: 0L,
            isChannelPost = isChannelPost,
        )
    }

    private fun defaultFileName(kind: TelegramFileKind, messageId: Long): String {
        val extension = when (kind) {
            TelegramFileKind.VIDEO -> "mp4"
            TelegramFileKind.DOCUMENT -> "bin"
            TelegramFileKind.ANIMATION -> "mp4"
            TelegramFileKind.AUDIO -> "mp3"
            TelegramFileKind.PHOTO -> "jpg"
            TelegramFileKind.VIDEO_NOTE -> "mp4"
            TelegramFileKind.VOICE_NOTE -> "ogg"
        }
        return "${kind.name.lowercase()}_$messageId.$extension"
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .replace(INVALID_FILENAME_CHARS, "_")
            .trim()
            .trimEnd('.')
            .take(MAX_FILENAME_LENGTH)
            .ifBlank { "telegram_file" }
    }

    private fun isInviteLink(ref: String): Boolean {
        return ref.startsWith("+") ||
            ref.contains("t.me/+") ||
            ref.contains("t.me/joinchat/")
    }

    private fun normalizeInviteLink(ref: String): String {
        return when {
            ref.startsWith("https://") || ref.startsWith("http://") -> ref
            ref.startsWith("t.me/") -> "https://$ref"
            ref.startsWith("+") -> "https://t.me/$ref"
            else -> ref
        }
    }

    private fun extractUsername(ref: String): String? {
        TME_USERNAME.matchEntire(ref)?.groupValues?.getOrNull(1)?.let { return it }
        return AT_USERNAME.matchEntire(ref)?.groupValues?.getOrNull(1)
    }

    private companion object {
        const val DEFAULT_MESSAGE_LIMIT = 200
        const val MAX_PAGE_SIZE = 100
        const val SEARCH_LIMIT = 200
        const val MAX_FILENAME_LENGTH = 180

        val NUMERIC_CHANNEL_ID = Regex("""^-?\d+$""")
        val TME_USERNAME = Regex(
            """^(?:https?://)?(?:www\.)?t\.me/([A-Za-z0-9_]{4,32})(?:[/?#].*)?$""",
        )
        val AT_USERNAME = Regex("""^@?([A-Za-z0-9_]{4,32})$""")
        val INVALID_FILENAME_CHARS = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    }
    suspend fun getUserChats(limit: Int = 100): List<TelegramChatInfo> = withContext(Dispatchers.IO) {
        val client = authManager.requireClient()

        // TDLib API: GetChats(chatList, limit, offsetOrder, offsetChatId, offsetTopicId)
        val chats = client.sendAwait<TdApi.Chats>(
            TdApi.GetChats(TdApi.ChatListMain(), limit),
        )

        val result = mutableListOf<TelegramChatInfo>()
        for (chatId in chats.chatIds) {
            runCatching {
                val chat = client.sendAwait<TdApi.Chat>(TdApi.GetChat(chatId))
                if (chat.type is TdApi.ChatTypeSupergroup || chat.type is TdApi.ChatTypeBasicGroup) {
                    val isChannel = (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel ?: false
                    result.add(
                        TelegramChatInfo(
                            id = chat.id,
                            title = chat.title,
                            isChannel = isChannel,
                        ),
                    )
                }
            }
        }
        result.sortedBy { it.title }
    }
}

enum class TelegramFileKind {
    VIDEO,
    DOCUMENT,
    ANIMATION,
    AUDIO,
    PHOTO,
    VIDEO_NOTE,
    VOICE_NOTE,
}

data class TelegramFileMetadata(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val caption: String?,
    val durationSeconds: Int?,
    val width: Int?,
    val height: Int?,
    val kind: TelegramFileKind,
    val date: Long = 0L,
    val messageThreadId: Long = 0L,
    val isChannelPost: Boolean = false,
)

data class TelegramChannelInfo(
    val id: Long,
    val title: String,
    val username: String? = null,
    val isChannel: Boolean = false,
    val isMember: Boolean = true,
    val isForum: Boolean = false,
)

/** Résultat d'une passe de lecture d'historique. */
data class TelegramScanResult(
    val files: List<TelegramFileMetadata>,
    /** Messages réellement examinés (fichiers et posts texte). */
    val messagesScanned: Int,
    /** Plus vieux message atteint, à réutiliser comme curseur. */
    val oldestMessageId: Long,
    /** Message le plus récent vu (utile pour la synchro incrémentale). */
    val newestMessageId: Long,
    /** Vrai si le début de l'historique a été atteint (pas une limite). */
    val reachedStart: Boolean,
)

/** Résultat d'une recherche ciblée dans un canal. */
data class TelegramSearchResult(
    val files: List<TelegramFileMetadata>,
    /** Vrai si la limite a été atteinte et qu'il reste des résultats. */
    val hasMore: Boolean,
)

data class TelegramChatInfo(
    val id: Long,
    val title: String,
    val isChannel: Boolean,
)
