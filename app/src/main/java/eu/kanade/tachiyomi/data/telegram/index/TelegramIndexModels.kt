package eu.kanade.tachiyomi.data.telegram.index

import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class TelegramMediaKind {
    VIDEO,
    DOCUMENT,
}

/**
 * Index d'un canal Telegram.
 *
 * Le fichier JSON est la seule source de vérité : les sources Aniyomi le lisent
 * de façon synchrone, sans réseau. La synchronisation incrémentale ne réécrit
 * que ce document.
 */
@Serializable
data class TelegramChannelIndex(
    val chatId: Long,
    val title: String,
    val username: String? = null,
    val isChannel: Boolean = true,
    val lastMessageId: Long = 0L,
    val lastSyncAt: Long = 0L,
    /** Version du parseur qui a produit cet index ; 0 = index d'avant la refonte. */
    val indexVersion: Int = 0,
    /** Message le plus récent déjà examiné (y compris les posts sans fichier). */
    val newestScannedMessageId: Long = 0L,
    /** Curseur de rétro-remplissage : plus vieux message examiné. */
    val oldestScannedMessageId: Long = 0L,
    /** Messages examinés cumulés, fichiers et posts texte confondus. */
    val messagesScanned: Int = 0,
    /** Vrai si le début de l'historique a réellement été atteint. */
    val historyComplete: Boolean = false,
    /** Appartenance du compte au canal (null = inconnue). */
    val isMember: Boolean? = null,
    /** Canal de type forum (sujets). */
    val isForum: Boolean = false,
    /** Vrai si l'index doit être reconstruit depuis Telegram (fichiers ignorés
     *  par une ancienne classification, par exemple). */
    val needsRescan: Boolean = false,
    val files: List<TelegramIndexedFile> = emptyList(),
    val works: List<TelegramWorkEntry> = emptyList(),
) {
    val videoFiles: List<TelegramIndexedFile>
        get() = files.filter { it.mediaKind == TelegramMediaKind.VIDEO }

    val documentFiles: List<TelegramIndexedFile>
        get() = files.filter { it.mediaKind == TelegramMediaKind.DOCUMENT }

    val videoWorks: List<TelegramWorkEntry>
        get() = works.filter { it.mediaKind == TelegramMediaKind.VIDEO }

    val documentWorks: List<TelegramWorkEntry>
        get() = works.filter { it.mediaKind == TelegramMediaKind.DOCUMENT }
}

/**
 * Version légère d'un index, lue par l'écran Telegram pour afficher la liste
 * des canaux sans décoder les dizaines de milliers de fichiers de chaque JSON.
 */

/** Signature stable d'une œuvre, commune à tous les canaux qui la proposent. */
fun TelegramWorkEntry.aggregationKey(): String =
    "${TelegramTitleParser.signature(baseTitle)}:${season ?: 0}"

@Serializable
data class TelegramWorkSummary(
    val id: String,
    val chatId: Long,
    val workKey: String = "",
    val mediaKind: TelegramMediaKind,
    val title: String,
    val season: Int? = null,
    val coverUrl: String? = null,
    val fileCount: Int = 0,
    val episodeCount: Int = 0,
    val lastFileDate: Long = 0L,
    val resolved: Boolean = false,
    /** Identités des fichiers, utilisées pour dédupliquer une fusion. */
    val fileKeys: List<String> = emptyList(),
    /** Clés d'épisodes uniques, utilisées pour le compteur d'une fusion. */
    val episodeKeys: List<String> = emptyList(),
)

@Serializable
data class TelegramChannelSummary(
    val chatId: Long,
    val title: String,
    val videoWorks: Int = 0,
    val docWorks: Int = 0,
    val videoFiles: Int = 0,
    val docFiles: Int = 0,
    val unknownVideo: Int = 0,
    val unknownDoc: Int = 0,
    val lastSyncAt: Long = 0L,
    val messagesScanned: Int = 0,
    val historyComplete: Boolean = false,
    val isMember: Boolean? = null,
    val isForum: Boolean = false,
    /** 0 = ancien résumé sans la liste des œuvres. */
    val summaryVersion: Int = 0,
    val works: List<TelegramWorkSummary> = emptyList(),
)

fun TelegramChannelIndex.toSummary(): TelegramChannelSummary {
    val video = videoWorks
    val document = documentWorks
    val filesByWork = files.groupBy { it.workId }
    return TelegramChannelSummary(
        chatId = chatId,
        title = title,
        videoWorks = video.count { !it.isUnresolved },
        docWorks = document.count { !it.isUnresolved },
        videoFiles = videoFiles.size,
        docFiles = documentFiles.size,
        unknownVideo = video.count { it.isUnresolved },
        unknownDoc = document.count { it.isUnresolved },
        lastSyncAt = lastSyncAt,
        messagesScanned = messagesScanned,
        historyComplete = historyComplete,
        isMember = isMember,
        isForum = isForum,
        summaryVersion = SUMMARY_VERSION,
        works = works.map { work ->
            val workFiles = filesByWork[work.id].orEmpty()
            TelegramWorkSummary(
                id = work.id,
                chatId = work.chatId,
                workKey = work.aggregationKey(),
                mediaKind = work.mediaKind,
                title = work.displayTitle,
                season = work.season,
                coverUrl = work.coverUrl,
                fileCount = work.fileCount,
                episodeCount = work.episodeCount,
                lastFileDate = work.lastFileDate,
                resolved = !work.isUnresolved,
                fileKeys = workFiles.map { it.identityKey() }.distinct(),
                episodeKeys = workFiles.map { it.episodeKey() }.distinct(),
            )
        },
    )
}

private const val SUMMARY_VERSION = 5

/**
 * Compteurs d'un canal corrigés par les corrections manuelles.
 *
 * Les résumés sont calculés au moment du scan et ne connaissent pas les
 * overrides : sans cette correction, une œuvre reconnue continuerait
 * d'alimenter « Non reconnus » sur la carte du canal jusqu'au prochain scan.
 */
data class TelegramChannelWorkCounts(
    val videoWorks: Int,
    val docWorks: Int,
    val unknownVideo: Int,
    val unknownDoc: Int,
    val works: List<TelegramWorkSummary>,
    val hasWorkList: Boolean,
)

fun TelegramChannelSummary?.countsWith(overrides: TelegramWorkOverrides): TelegramChannelWorkCounts {
    val allWorks = this?.works.orEmpty()
    // Les anciens résumés n'exposent pas la liste : on garde leurs compteurs.
    if (allWorks.isEmpty()) {
        return TelegramChannelWorkCounts(
            videoWorks = this?.videoWorks ?: 0,
            docWorks = this?.docWorks ?: 0,
            unknownVideo = this?.unknownVideo ?: 0,
            unknownDoc = this?.unknownDoc ?: 0,
            works = emptyList(),
            hasWorkList = false,
        )
    }

    val works = allWorks.filterNot { overrides.isHidden(it.workKey) }
    val video = works.filter { it.mediaKind == TelegramMediaKind.VIDEO }
    val document = works.filter { it.mediaKind == TelegramMediaKind.DOCUMENT }
    val videoResolved = video.count { it.resolved || overrides.isRecognized(it.workKey) }
    val docResolved = document.count { it.resolved || overrides.isRecognized(it.workKey) }
    return TelegramChannelWorkCounts(
        videoWorks = videoResolved,
        docWorks = docResolved,
        unknownVideo = video.size - videoResolved,
        unknownDoc = document.size - docResolved,
        works = works,
        hasWorkList = true,
    )
}

/**
 * Identité stable d'un fichier pour une fusion : deux variantes qui pointent
 * exactement le même fichier ne doivent pas être ajoutées deux fois.
 *
 * On n'utilise pas le fileId seul : Telegram peut donner un identifiant
 * différent au même média forwardé dans deux canaux.
 */
fun TelegramIndexedFile.identityKey(): String = buildString {
    append(fileName.trim().lowercase(Locale.ROOT))
    append('|').append(sizeBytes)
    append('|').append(season ?: 1)
    append('|').append(episode ?: -1)
    append('|').append(episodeEnd ?: -1)
    append('|').append(language.orEmpty().lowercase(Locale.ROOT))
    append('|').append(variant.orEmpty().lowercase(Locale.ROOT))
}

/** Clé d'épisode unique ; un fichier non numéroté compte comme un épisode. */
fun TelegramIndexedFile.episodeKey(): String =
    if (episode != null) {
        "e:${season ?: 1}:$episode"
    } else {
        "f:${identityKey()}"
    }

@Serializable
data class TelegramIndexedFile(
    val fileId: Int,
    val messageId: Long,
    val chatId: Long,
    val fileName: String,
    val caption: String? = null,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val durationSeconds: Int? = null,
    val date: Long,
    val threadId: Long = 0L,
    val mediaKind: TelegramMediaKind,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val chapterStart: Int? = null,
    val chapterEnd: Int? = null,
    val language: String? = null,
    val variant: String? = null,
    val workId: String,
    val candidates: List<TelegramWorkCandidate> = emptyList(),
) {
    val episodeNumber: Int?
        get() = episode ?: chapterStart

    val displayLabel: String
        get() = buildString {
            if (!language.isNullOrBlank()) append(language)
            if (!variant.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(variant)
            }
        }.ifBlank { mediaKind.name.lowercase() }
}

@Serializable
data class TelegramWorkEntry(
    val id: String,
    val chatId: Long,
    val mediaKind: TelegramMediaKind,
    val title: String,
    val baseTitle: String,
    val season: Int? = null,
    val anilistId: Int? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val totalEpisodes: Int? = null,
    val status: String? = null,
    val matchScore: Double = 0.0,
    val candidates: List<TelegramWorkCandidate> = emptyList(),
    val fileCount: Int = 0,
    val episodeCount: Int = 0,
    val lastFileDate: Long = 0L,
) {
    val displayTitle: String
        get() {
            if (season == null || season < 2) return title
            val alreadyMentioned = title.contains("saison $season", ignoreCase = true) ||
                title.contains("season $season", ignoreCase = true)
            return if (alreadyMentioned) title else "$title — Saison $season"
        }
}

@Serializable
data class TelegramWorkCandidate(
    val anilistId: Int,
    val title: String,
    val score: Double,
    val year: Int? = null,
    val coverUrl: String? = null,
)

/**
 * Résultat de résolution AniList (persisté).
 */
@Serializable
data class TelegramResolvedWork(
    val query: String,
    val anilistId: Int? = null,
    /** Identifiant chez un fournisseur externe (MAL, Kitsu, Shikimori). */
    val providerId: String? = null,
    /** Fournisseur des métadonnées (ANILIST, MAL, KITSU, SHIKIMORI). */
    val provider: String? = null,
    val title: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val totalEpisodes: Int? = null,
    val status: String? = null,
    val score: Double = 0.0,
    val candidates: List<TelegramWorkCandidate> = emptyList(),
    val failed: Boolean = false,
    val resolvedAt: Long = 0L,
)
