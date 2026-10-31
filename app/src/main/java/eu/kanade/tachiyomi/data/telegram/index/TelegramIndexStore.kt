package eu.kanade.tachiyomi.data.telegram.index

import android.content.Context
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Persistance de l'index Telegram : un JSON par canal + un cache de résolution.
 *
 * Les fichiers sont écrits de façon atomique. Un index corrompu est ignoré puis
 * reconstruit à la synchronisation suivante.
 */
class TelegramIndexStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "telegram-index")
    private val resolveCacheFile = File(root, "resolve-cache.json")
    private val summariesFile = File(root, "channel-summaries.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()

    /**
     * Cache partiel : un canal est chargé à la demande, sans décoder tous les
     * autres index. [allLoaded] indique si [memory] contient réellement tous
     * les canaux du disque.
     */
    @Volatile
    private var memory: Map<Long, TelegramChannelIndex>? = null

    @Volatile
    private var allLoaded = false

    @Volatile
    private var summaries: Map<Long, TelegramChannelSummary>? = null

    fun channels(): List<TelegramChannelIndex> {
        val snapshot = synchronized(lock) {
            if (!allLoaded) {
                memory = loadFromDisk()
                allLoaded = true
            }
            memory.orEmpty()
        }
        return snapshot.values.sortedBy { it.title.lowercase() }
    }

    fun channel(chatId: Long): TelegramChannelIndex? {
        synchronized(lock) {
            memory?.get(chatId)?.let { return it }
            if (allLoaded) return null

            val file = fileFor(chatId)
            if (!file.isFile) return null
            val index = runCatching {
                json.decodeFromString<TelegramChannelIndex>(file.readText())
            }.getOrElse {
                logcat(LogPriority.WARN, it) { "Index Telegram illisible : ${file.name}" }
                return null
            }
            memory = (memory.orEmpty() + (chatId to index))
            return index
        }
    }

    /**
     * Résumés prêts pour l'UI : quelques centaines d'octets par canal au lieu
     * de décoder tout le JSON des œuvres et des fichiers.
     */
    fun channelIds(): List<Long> {
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.name.endsWith(".json") &&
                    it.name != resolveCacheFile.name &&
                    it.name != summariesFile.name
            }
            .mapNotNull { it.nameWithoutExtension.toLongOrNull() }
    }

    fun channelSummaries(): List<TelegramChannelSummary> {
        val snapshot = synchronized(lock) {
            summaries ?: loadSummaries()
        }
        return snapshot.values.sortedBy { it.title.lowercase() }
    }

    fun writeChannel(index: TelegramChannelIndex, keepInMemory: Boolean = true) {
        synchronized(lock) {
            val updated = memory.orEmpty().toMutableMap()
            if (keepInMemory) {
                updated[index.chatId] = index
            } else {
                // Réparation complète : ne pas garder les 39 index décodés en RAM.
                updated.remove(index.chatId)
            }
            memory = updated
            writeAtomically(fileFor(index.chatId), json.encodeToString(index))
            updateSummaryLocked(index.toSummary())
        }
    }

    fun deleteChannel(chatId: Long) {
        synchronized(lock) {
            memory = memory.orEmpty() - chatId
            fileFor(chatId).delete()
            removeSummaryLocked(chatId)
        }
    }

    fun deleteAll() {
        synchronized(lock) {
            memory = emptyMap()
            allLoaded = true
            summaries = emptyMap()
            root.deleteRecursively()
            root.mkdirs()
        }
    }

    // ------------------------------------------------------------------
    // Cache AniList
    // ------------------------------------------------------------------

    fun readResolveCache(): Map<String, TelegramResolvedWork> {
        if (!resolveCacheFile.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, TelegramResolvedWork>>(resolveCacheFile.readText())
        }.getOrElse {
            logcat(LogPriority.WARN, it) { "Cache AniList Telegram illisible" }
            emptyMap()
        }
    }

    fun writeResolveCache(cache: Map<String, TelegramResolvedWork>) {
        runCatching {
            writeAtomically(resolveCacheFile, json.encodeToString(cache))
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Impossible d'écrire le cache AniList Telegram" }
        }
    }

    // ------------------------------------------------------------------
    // Interne
    // ------------------------------------------------------------------

    private fun loadSummaries(): Map<Long, TelegramChannelSummary> {
        if (summariesFile.isFile) {
            val cached = runCatching {
                json.decodeFromString<List<TelegramChannelSummary>>(summariesFile.readText())
                    .associateBy { it.chatId }
            }.getOrElse {
                logcat(LogPriority.WARN, it) { "Résumés Telegram illisibles" }
                null
            }
            if (cached != null && cached.values.all { it.summaryVersion >= 5 }) {
                summaries = cached
                return cached
            }
        }

        // Migration unique : on décode les index un par un pour ne jamais
        // garder les dizaines de Mo de tous les canaux en mémoire.
        val generated = loadSummariesFromDisk()
        summaries = generated
        writeSummariesLocked(generated)
        return generated
    }

    private fun loadSummariesFromDisk(): Map<Long, TelegramChannelSummary> {
        if (!root.isDirectory) return emptyMap()
        val result = LinkedHashMap<Long, TelegramChannelSummary>()
        root.listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.name.endsWith(".json") &&
                    it.name != resolveCacheFile.name &&
                    it.name != summariesFile.name
            }
            .forEach { file ->
                runCatching {
                    json.decodeFromString<TelegramChannelIndex>(file.readText())
                }.onSuccess { index ->
                    result[index.chatId] = index.toSummary()
                }.onFailure {
                    logcat(LogPriority.WARN, it) { "Index Telegram illisible : ${file.name}" }
                }
            }
        return result
    }

    private fun updateSummaryLocked(summary: TelegramChannelSummary) {
        val current = summaries ?: if (summariesFile.isFile) readSummariesFile() else loadSummaries()
        val updated = current.toMutableMap()
        updated[summary.chatId] = summary
        summaries = updated
        writeSummariesLocked(updated)
    }

    private fun removeSummaryLocked(chatId: Long) {
        val current = summaries ?: if (summariesFile.isFile) readSummariesFile() else return
        val updated = current - chatId
        summaries = updated
        writeSummariesLocked(updated)
    }

    private fun readSummariesFile(): Map<Long, TelegramChannelSummary> {
        if (!summariesFile.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<List<TelegramChannelSummary>>(summariesFile.readText())
                .associateBy { it.chatId }
        }.getOrElse { emptyMap() }
    }

    private fun writeSummariesLocked(summaries: Map<Long, TelegramChannelSummary>) {
        runCatching {
            writeAtomically(
                summariesFile,
                json.encodeToString(summaries.values.sortedBy { it.title.lowercase() }),
            )
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Impossible d'écrire les résumés Telegram" }
        }
    }

    private fun loadFromDisk(): Map<Long, TelegramChannelIndex> {
        if (!root.isDirectory) return emptyMap()
        return root.listFiles()
            .orEmpty()
            .filter {
                it.isFile &&
                    it.name.endsWith(".json") &&
                    it.name != resolveCacheFile.name &&
                    it.name != summariesFile.name
            }
            .mapNotNull { file ->
                runCatching {
                    json.decodeFromString<TelegramChannelIndex>(file.readText())
                }.getOrElse {
                    logcat(LogPriority.WARN, it) { "Index Telegram illisible : ${file.name}" }
                    null
                }
            }
            .associateBy { it.chatId }
    }

    private fun fileFor(chatId: Long): File {
        root.mkdirs()
        return File(root, "$chatId.json")
    }

    private fun writeAtomically(file: File, value: String) {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory) parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        try {
            temp.writeText(value)
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }
}
