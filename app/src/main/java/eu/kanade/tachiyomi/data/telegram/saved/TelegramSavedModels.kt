package eu.kanade.tachiyomi.data.telegram.saved

import eu.kanade.tachiyomi.data.telegram.index.TelegramResolvedWork
import kotlinx.serialization.Serializable

/** Type de média d'une œuvre validée manuellement. */
@Serializable
enum class TelegramSavedKind {
    VIDEO,
    DOCUMENT,
}

/**
 * Œuvre construite par l'utilisateur à partir des résultats de recherche.
 * Aucune heuristique de regroupement n'est appliquée ici : le titre et la
 * liste des fichiers sont ceux validés à l'écran.
 */
@Serializable
data class TelegramSavedWork(
    val id: String,
    val title: String,
    val kind: TelegramSavedKind,
    val files: List<TelegramSavedFile> = emptyList(),
    val createdAt: Long = 0L,
    /** Titre d'origine (avant renommage), utilisé pour retrouver les nouveaux fichiers. */
    val sourceTitle: String? = null,
    /** Entrée de bibliothèque associée (animé ou manga). */
    val libraryId: Long? = null,
    /** Veille automatique de cette œuvre. */
    val watchEnabled: Boolean = false,
    val watchIntervalMinutes: Int = 60,
    val lastWatchAt: Long = 0L,
    /** Métadonnées AniList (facultatives, jamais bloquantes). */
    val resolution: TelegramResolvedWork? = null,
) {
    val lastDate: Long
        get() = files.maxOfOrNull { it.date } ?: 0L

    val episodeCount: Int
        get() = files
            .filter { it.episode != null }
            .map { (it.season ?: 1) to it.episode!! }
            .distinct()
            .size

    val chapterCount: Int
        get() = files.filter { it.chapter != null }.mapNotNull { it.chapter }.distinct().size
}

/** Référence stable d'un fichier Telegram rattaché à une œuvre validée. */
@Serializable
data class TelegramSavedFile(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String? = null,
    val caption: String? = null,
    val date: Long = 0L,
    val threadId: Long = 0L,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeEnd: Int? = null,
    val chapter: Int? = null,
    val chapterEnd: Int? = null,
    val language: String? = null,
    val variant: String? = null,
)

/** Identité d'un fichier au sein d'une œuvre validée. */
fun TelegramSavedFile.stableKey(): String = "$chatId:$messageId"

/**
 * Ajoute des fichiers à une œuvre validée sans doublon.
 *
 * L'ordre d'épisode est conservé, ce qui garde les listes d'épisodes stables
 * même après un ajout manuel depuis la recherche.
 */
fun mergeSavedFiles(
    existing: List<TelegramSavedFile>,
    additions: List<TelegramSavedFile>,
): List<TelegramSavedFile> {
    val known = existing.mapTo(mutableSetOf()) { it.stableKey() }
    val extra = additions
        .filter { known.add(it.stableKey()) }
    if (extra.isEmpty()) return existing
    return (existing + extra).sortedWith(
        compareBy(
            { it.season ?: 0 },
            { it.episode ?: Int.MAX_VALUE },
            { it.chapter ?: Int.MAX_VALUE },
            { it.date },
        ),
    )
}

/** Retire des fichiers précis ; l'œuvre n'est jamais vidée par cette fonction. */
fun removeSavedFiles(
    existing: List<TelegramSavedFile>,
    keys: Set<String>,
): List<TelegramSavedFile> {
    if (keys.isEmpty()) return existing
    val remaining = existing.filterNot { it.stableKey() in keys }
    return if (remaining.size == existing.size) existing else remaining
}
