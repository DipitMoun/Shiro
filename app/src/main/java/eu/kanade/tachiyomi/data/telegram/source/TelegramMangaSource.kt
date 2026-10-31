package eu.kanade.tachiyomi.data.telegram.source

import eu.kanade.tachiyomi.data.telegram.TelegramUrls
import eu.kanade.tachiyomi.data.telegram.index.TelegramChannelIndex
import eu.kanade.tachiyomi.data.telegram.index.TelegramIndexStore
import eu.kanade.tachiyomi.data.telegram.index.TelegramIndexedFile
import eu.kanade.tachiyomi.data.telegram.index.TelegramLibraryImporter
import eu.kanade.tachiyomi.data.telegram.index.TelegramWorkEntry
import eu.kanade.tachiyomi.data.telegram.index.isUnresolved
import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedFile
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedKind
import eu.kanade.tachiyomi.data.telegram.saved.TelegramSavedWorkStore
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.Locale

/**
 * Source documents Telegram (CBZ, ZIP, PDF, EPUB).
 *
 * Les fichiers ne sont pas lisibles en streaming : ils doivent être téléchargés.
 * Le lecteur utilise l'archive téléchargée (ou le lecteur PDF dédié).
 */
class TelegramMangaSource : HttpSource() {

    override val id: Long = ID
    override val name: String = "[Telegram] Doc"
    override val baseUrl: String = "https://telegram.org"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    private val store: TelegramIndexStore by injectLazy()
    private val savedStore: TelegramSavedWorkStore by injectLazy()

    // ------------------------------------------------------------------
    // Catalogue
    // ------------------------------------------------------------------

    override fun getFilterList(): FilterList {
        val channels = channels()
        return FilterList(TelegramMangaChannelFilter(channels))
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val entries = buildEntries(channelId = null)
            .sortedBy { it.manga.title.lowercase(Locale.ROOT) }
        return paginate(entries, page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val entries = buildEntries(channelId = null)
            .sortedByDescending { it.lastDate }
        return paginate(entries, page)
    }

    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val channelId = filters.filterIsInstance<TelegramMangaChannelFilter>()
            .firstOrNull()
            ?.selectedChatId(channels())

        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        val entries = buildEntries(channelId)
            .filter { entry ->
                normalizedQuery.isEmpty() ||
                    entry.manga.title.lowercase(Locale.ROOT).contains(normalizedQuery)
            }
            .sortedBy { it.manga.title.lowercase(Locale.ROOT) }

        return paginate(entries, page)
    }

    // ------------------------------------------------------------------
    // Détails
    // ------------------------------------------------------------------

    override suspend fun getMangaDetails(manga: SManga): SManga {
        TelegramUrls.parseSaved(manga.url)?.let { ref ->
            if (!ref.manga) return manga
            val work = savedStore.find(ref.id) ?: return manga
            return SManga.create().apply {
                url = manga.url
                title = work.title
                description = "${work.files.size} fichier(s) validé(s) manuellement."
                initialized = true
            }
        }

        val ref = TelegramUrls.parseManga(manga.url) ?: return manga
        val channel = store.channel(ref.chatId) ?: return manga

        val resolved = if (ref.slug == TelegramLibraryImporter.UNKNOWN_SLUG) {
            unknownManga(channel)
        } else {
            channel.documentWorks.firstOrNull { it.id == workId(ref.chatId, ref.slug) }
                ?.toSManga(channel)
                ?: manga
        }

        return resolved.apply {
            url = manga.url
            initialized = true
        }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        TelegramUrls.parseSaved(manga.url)?.let { ref ->
            if (!ref.manga) return emptyList()
            val work = savedStore.find(ref.id) ?: return emptyList()
            return work.files
                .sortedWith(
                    compareBy(
                        { it.chapter ?: Int.MAX_VALUE },
                        { it.season ?: 0 },
                        { it.date },
                    ),
                )
                .map { it.toSChapter() }
        }

        val ref = TelegramUrls.parseManga(manga.url) ?: return emptyList()
        val channel = store.channel(ref.chatId) ?: return emptyList()

        val files = filesFor(channel, ref.chatId, ref.slug)
        if (files.isEmpty()) return emptyList()

        return files
            .sortedWith(compareBy({ it.chapterStart ?: Int.MAX_VALUE }, { it.date }))
            .map { file ->
                SChapter.create().apply {
                    url = TelegramUrls.file(
                        file.fileId,
                        file.sizeBytes,
                        file.chatId,
                        extensionOf(file.fileName),
                        messageId = file.messageId,
                    )
                    name = chapterName(file)
                    chapter_number = (file.chapterStart ?: 0).toFloat()
                    date_upload = file.date * 1000
                }
            }
    }

    /**
     * Aucune page en ligne : le chapitre doit être téléchargé. Une fois le
     * fichier présent, `DownloadPageLoader` (ou le lecteur PDF) prend le relais.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    private fun channels(): List<TelegramChannelIndex> =
        store.channels().filter { it.documentWorks.isNotEmpty() }

    private fun buildEntries(channelId: Long?): List<MangaEntry> {
        val entries = mutableListOf<MangaEntry>()

        for (channel in channels()) {
            if (channelId != null && channel.chatId != channelId) continue

            val works = channel.documentWorks
            works.filterNot { it.isUnresolved }.forEach { work ->
                entries += MangaEntry(work.toSManga(channel), work.lastFileDate)
            }

            val unresolved = works.filter { it.isUnresolved }
            if (unresolved.isNotEmpty()) {
                entries += MangaEntry(
                    manga = unknownManga(channel),
                    lastDate = unresolved.maxOfOrNull { it.lastFileDate } ?: 0L,
                )
            }
        }

        savedStore.snapshot()
            .filter { it.kind == TelegramSavedKind.DOCUMENT }
            .forEach { work ->
                entries += MangaEntry(
                    manga = SManga.create().apply {
                        url = TelegramUrls.savedManga(work.id)
                        title = work.title
                        description = "${work.files.size} fichier(s) validé(s) manuellement."
                        initialized = false
                    },
                    lastDate = work.lastDate,
                )
            }

        return entries
    }

    private fun paginate(entries: List<MangaEntry>, page: Int): MangasPage {
        val safePage = page.coerceAtLeast(1)
        val from = (safePage - 1) * PAGE_SIZE
        val slice = entries.drop(from).take(PAGE_SIZE)
        return MangasPage(slice.map { it.manga }, from + slice.size < entries.size)
    }

    private fun filesFor(
        channel: TelegramChannelIndex,
        chatId: Long,
        slug: String,
    ): List<TelegramIndexedFile> {
        if (slug == TelegramLibraryImporter.UNKNOWN_SLUG) {
            val unresolvedIds = channel.documentWorks
                .filter { it.isUnresolved }
                .map { it.id }
                .toSet()
            return channel.documentFiles.filter { it.workId in unresolvedIds }
        }
        val id = workId(chatId, slug)
        return channel.documentFiles.filter { it.workId == id }
    }

    private fun workId(chatId: Long, slug: String): String = "$chatId:$slug:0"

    private fun TelegramSavedFile.toSChapter(): SChapter = SChapter.create().apply {
        url = TelegramUrls.file(
            fileId,
            sizeBytes,
            chatId,
            extensionOf(fileName),
            messageId = messageId,
        )
        val number = chapter
        name = when {
            number == null -> displayName()
            (chapterEnd ?: number) > number -> "Chapitres $number-$chapterEnd"
            else -> "Chapitre $number"
        }
        chapter_number = (number ?: -1).toFloat()
        date_upload = date * 1000
    }

    private fun TelegramSavedFile.displayName(): String =
        fileName.substringBeforeLast('.', fileName).ifBlank { fileName }

    private fun chapterName(file: TelegramIndexedFile): String {
        val start = file.chapterStart ?: return file.title.ifBlank { file.fileName }
        val end = file.chapterEnd ?: start
        return if (end > start) "Chapitres $start-$end" else "Chapitre $start"
    }

    private fun extensionOf(name: String): String? =
        name.substringAfterLast('.', "").takeIf { it.length in 1..5 }

    private fun TelegramWorkEntry.toSManga(channel: TelegramChannelIndex): SManga {
        val slug = id.split(':').getOrNull(1).orEmpty()
        return SManga.create().apply {
            url = TelegramUrls.manga(channel.chatId, slug)
            title = displayTitle
            thumbnail_url = coverUrl
            description = this@toSManga.description
            genre = genres.joinToString(", ").takeIf { it.isNotBlank() }
            status = this@toSManga.status.toSMangaStatus()
            initialized = false
        }
    }

    private fun unknownManga(channel: TelegramChannelIndex): SManga {
        val cleaned = TelegramTitleParser.cleanChannelName(channel.title)
            .ifBlank { channel.title }
        return SManga.create().apply {
            url = TelegramUrls.manga(channel.chatId, TelegramLibraryImporter.UNKNOWN_SLUG)
            title = "Non reconnus — $cleaned"
            description = "Documents du canal « $cleaned » qui n'ont pas pu être associés à une œuvre."
            initialized = false
        }
    }

    private fun String?.toSMangaStatus(): Int = when (this?.uppercase(Locale.ROOT)) {
        "FINISHED" -> SManga.COMPLETED
        "RELEASING" -> SManga.ONGOING
        "CANCELLED" -> SManga.CANCELLED
        "HIATUS" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // ------------------------------------------------------------------
    // Endpoints HTTP factices (jamais appelés)
    // ------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular/$page")

    override fun popularMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest/$page")

    override fun latestUpdatesParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$baseUrl/search")

    override fun searchMangaParse(response: Response): MangasPage = MangasPage(emptyList(), false)

    override fun mangaDetailsParse(response: Response): SManga = SManga.create()

    override fun chapterListParse(response: Response): List<SChapter> = emptyList()

    override fun pageListParse(response: Response): List<Page> = emptyList()

    override fun imageUrlParse(response: Response): String = ""

    override fun chapterPageParse(response: Response): SChapter = SChapter.create()

    private data class MangaEntry(val manga: SManga, val lastDate: Long)

    companion object {
        const val ID = 987654322L
        private const val PAGE_SIZE = 50
    }
}

/**
 * Filtre « Canal » de la source documents.
 */
class TelegramMangaChannelFilter(
    channels: List<TelegramChannelIndex>,
) : Filter.Select<String>(
    name = "Canal",
    values = arrayOf("Tous") + channels.map { it.title }.toTypedArray(),
) {
    fun selectedChatId(channels: List<TelegramChannelIndex>): Long? {
        if (state <= 0) return null
        return channels.getOrNull(state - 1)?.chatId
    }
}
