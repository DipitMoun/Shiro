package eu.kanade.tachiyomi.data.telegram.saved

import android.content.Context
import eu.kanade.tachiyomi.data.media.EpisodeMediaValidator
import eu.kanade.tachiyomi.data.telegram.TelegramFileHeuristics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Persistance des œuvres validées manuellement.
 *
 * Un seul fichier JSON : la liste reste petite (une entrée par œuvre choisie
 * par l'utilisateur, pas par fichier de canal).
 */
class TelegramSavedWorkStore(context: Context) {

    private val file = File(context.noBackupFilesDir, "telegram-works.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    private val _works = MutableStateFlow(load())
    val works: StateFlow<List<TelegramSavedWork>> = _works.asStateFlow()

    fun snapshot(): List<TelegramSavedWork> = _works.value

    fun find(id: String): TelegramSavedWork? = _works.value.firstOrNull { it.id == id }

    fun save(work: TelegramSavedWork) {
        synchronized(lock) {
            val updated = _works.value.filterNot { it.id == work.id } + work
            persist(updated)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            persist(_works.value.filterNot { it.id == id })
        }
    }

    /** Retire une source précise (doublon) d'une œuvre. */
    fun removeFile(workId: String, messageId: Long) {
        synchronized(lock) {
            val updated = _works.value.map { work ->
                if (work.id != workId) {
                    work
                } else {
                    work.copy(files = work.files.filterNot { it.messageId == messageId })
                }
            }.filter { it.files.isNotEmpty() }
            persist(updated)
        }
    }

    /**
     * Retire les exports génériques (`VID_...`, `YouCut_...`) qui polluent les
     * œuvres avec des pubs ou des captures sans rapport. Les œuvres devenues
     * vides sont supprimées.
     */
    fun removeSuspectFiles(): Boolean {
        synchronized(lock) {
            var changed = false
            val updated = _works.value.mapNotNull { work ->
                val files = work.files.filterNot { file ->
                    TelegramFileHeuristics.isSuspect(file.fileName, file.caption, work.title) ||
                        (
                            work.kind == TelegramSavedKind.VIDEO &&
                                (
                                    !TelegramFileHeuristics.isVideoFile(file.fileName, file.mimeType) ||
                                        EpisodeMediaValidator.isTooSmall(file.sizeBytes)
                                    )
                            )
                }
                if (files.size != work.files.size) changed = true
                if (files.isEmpty()) {
                    changed = true
                    null
                } else {
                    work.copy(files = files)
                }
            }
            if (changed) persist(updated)
            return changed
        }
    }

    private fun persist(works: List<TelegramSavedWork>) {
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(works.sortedBy { it.title.lowercase() }))
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            _works.value = works.sortedBy { it.title.lowercase() }
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Impossible d'écrire les œuvres Telegram" }
        }
    }

    private fun load(): List<TelegramSavedWork> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<TelegramSavedWork>>(file.readText())
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Œuvres Telegram illisibles" }
            emptyList()
        }
    }
}
