package eu.kanade.tachiyomi.data.telegram.index

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Corrections manuelles appliquées à la source Telegram.
 *
 * - [merges] : clé source -> clé canonique (fusion de variantes) ;
 * - [titles] : titre manuel par clé canonique ;
 * - [hidden] : clés masquées de la source.
 *
 * Le stockage est séparé de l'index : une reconstruction ou une synchronisation
 * ne doit jamais perdre une correction manuelle.
 */
@Serializable
data class TelegramWorkOverrides(
    val merges: Map<String, String> = emptyMap(),
    val titles: Map<String, String> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    val metadata: Map<String, TelegramResolvedWork> = emptyMap(),
) {
    fun resolve(key: String): String {
        var current = key
        val seen = mutableSetOf<String>()
        while (seen.add(current)) {
            current = merges[current] ?: return current
        }
        return key
    }

    fun sourcesFor(target: String): Set<String> =
        merges.keys.filterTo(mutableSetOf()) { resolve(it) == target } + target

    fun metadataFor(key: String): TelegramResolvedWork? = metadata[resolve(key)]

    fun titleFor(key: String): String? =
        titles[resolve(key)]?.takeIf { it.isNotBlank() }
            ?: metadataFor(key)?.title?.takeIf { it.isNotBlank() }

    fun coverFor(key: String): String? =
        metadataFor(key)?.coverUrl?.takeIf { it.isNotBlank() }

    fun isRecognized(key: String): Boolean =
        metadataFor(key)?.let { it.anilistId != null || it.providerId != null } == true

    fun isHidden(key: String): Boolean = resolve(key) in hidden

    fun merge(source: String, target: String): TelegramWorkOverrides {
        if (source.isBlank() || target.isBlank() || source == target) return this
        return copy(merges = merges + (source to target))
    }

    /** Retire les fusions qui alimentent [key], et les éventuelles corrections associées. */
    fun unmerge(key: String): TelegramWorkOverrides {
        val canonical = resolve(key)
        val removed = sourcesFor(canonical) + canonical
        return copy(
            merges = merges.filterNot { (source, target) ->
                source in removed || resolve(target) == canonical
            },
            titles = titles - canonical,
            hidden = hidden - canonical,
            metadata = metadata - canonical,
        )
    }

    fun rename(key: String, title: String): TelegramWorkOverrides {
        val canonical = resolve(key)
        val clean = title.trim()
        if (canonical.isBlank() || clean.isBlank()) return this
        return copy(titles = titles + (canonical to clean))
    }

    fun withMetadata(key: String, resolved: TelegramResolvedWork): TelegramWorkOverrides {
        val canonical = resolve(key)
        if (canonical.isBlank()) return this
        val recognizedTitle = resolved.title?.takeIf { it.isNotBlank() }
        return copy(
            metadata = metadata + (canonical to resolved),
            titles = if (recognizedTitle != null) titles + (canonical to recognizedTitle) else titles,
        )
    }

    fun hide(key: String): TelegramWorkOverrides {
        val canonical = resolve(key)
        if (canonical.isBlank()) return this
        return copy(hidden = hidden + canonical)
    }

    fun reset(key: String): TelegramWorkOverrides {
        val canonical = resolve(key)
        if (canonical.isBlank()) return this
        return copy(
            merges = merges.filterNot { (source, target) ->
                source == canonical || resolve(target) == canonical
            },
            titles = titles - canonical,
            hidden = hidden - canonical,
            metadata = metadata - canonical,
        )
    }
}

class TelegramWorkOverrideStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "telegram-work-overrides.json")
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val lock = Any()

    @Volatile
    private var cached: TelegramWorkOverrides? = null

    fun snapshot(): TelegramWorkOverrides = synchronized(lock) {
        cached ?: read().also { cached = it }
    }

    fun update(transform: (TelegramWorkOverrides) -> TelegramWorkOverrides): TelegramWorkOverrides =
        synchronized(lock) {
            val updated = transform(snapshot())
            if (updated != snapshot()) {
                cached = updated
                runCatching {
                    file.parentFile?.mkdirs()
                    val temp = File(file.parentFile, "${file.name}.tmp")
                    temp.writeText(json.encodeToString(updated))
                    if (!temp.renameTo(file)) {
                        file.writeText(temp.readText())
                        temp.delete()
                    }
                }
            }
            updated
        }

    private fun read(): TelegramWorkOverrides {
        if (!file.isFile) return TelegramWorkOverrides()
        return runCatching {
            json.decodeFromString<TelegramWorkOverrides>(file.readText())
        }.getOrDefault(TelegramWorkOverrides())
    }
}
