package eu.kanade.tachiyomi.data.multisource

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.Hoster.Companion.toHosterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.data.media.EpisodeMediaValidator
import eu.kanade.tachiyomi.data.multisource.resolver.WebViewMediaResolver
import eu.kanade.tachiyomi.ui.browse.catalog.CatalogWork
import eu.kanade.tachiyomi.ui.browse.catalog.preferredTitle
import logcat.LogPriority
import okhttp3.Headers
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

/**
 * Source intégrée qui représente une œuvre multi-source.
 *
 * Elle fournit les métadonnées et la liste d'épisodes issue du catalogue, mais
 * ne fournit pas encore de vidéos : la résolution par extension sera branchée
 * sur cette source dans la prochaine étape.
 */
object MultiSourceAnimeSource : AnimeSource {

    const val ID = -2L

    override val id: Long = ID
    override val name: String = "Multi-source"
    override val lang: String = "all"
    override val supportsLatest: Boolean = false

    private val store: MultiSourceWorkStore
        get() = Injekt.get()

    private val mappingStore: MultiSourceMappingStore
        get() = Injekt.get()

    private val sourceManager: AnimeSourceManager
        get() = Injekt.get()

    private val webViewResolver: WebViewMediaResolver by lazy { WebViewMediaResolver() }

    override suspend fun getPopularAnime(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage = AnimesPage(emptyList(), false)

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val work = store.get(anime.url)
        return SAnimeEpisodeUpdate(
            anime = if (fetchDetails && work != null) work.toSAnime(anime.url) else anime,
            episodes = if (fetchEpisodes && work != null) work.toEpisodes(anime.url) else episodes,
        )
    }

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate {
        val work = store.get(anime.url)
        return SAnimeSeasonUpdate(
            anime = if (fetchDetails && work != null) work.toSAnime(anime.url) else anime,
            seasons = if (fetchSeasons) emptyList() else seasons,
        )
    }

    override suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val episodeNumber = episode.episode_number.roundToInt()
        val primary = mappingStore.getForEpisode(
            episodeUrl = episode.url,
            episodeNumber = episodeNumber,
        )
        if (primary == null) {
            logcat(LogPriority.WARN) {
                "Multi-source: aucun mapping pour ${episode.url} (épisode ${episode.episode_number})"
            }
            throw IllegalStateException(
                "Multi-source : aucune source associée à cet épisode. Relance le mapping.",
            )
        }

        val alternatives = mappingStore.getAlternativesForEpisode(episode.url, episodeNumber)
        val refs = (listOf(primary) + alternatives)
            .distinctBy { it.sourceId to it.episodeUrl }
            .take(MAX_RESOLUTION_SOURCES)

        MultiSourceDebugLog.log(
            "adapter",
            "episode=${episode.url} sources=${refs.joinToString(",") { it.sourceName }}",
        )

        for (ref in refs) {
            var hosters = resolveHosters(ref).filterPlayableHosters()
            if (hosters.isEmpty()) {
                hosters = resolveWithWebView(ref).filterPlayableHosters()
            }
            if (hosters.isNotEmpty()) {
                if (ref != primary) {
                    logcat(LogPriority.INFO) {
                        "Multi-source: fallback réussi sur ${ref.sourceName} pour l'épisode $episodeNumber"
                    }
                }
                return hosters
            }
        }

        val tried = refs.joinToString { it.sourceName }.ifBlank { "aucune source" }
        logcat(LogPriority.WARN) {
            "Multi-source: aucune vidéo exploitable pour ${episode.url}. Sources essayées : $tried"
        }
        throw IllegalStateException(
            "Multi-source : aucune vidéo pour l'épisode $episodeNumber. " +
                "Sources essayées : $tried.",
        )
    }

    /**
     * Dernier recours : charger la page de l'épisode dans le WebView système
     * et intercepter les URLs média. Cela couvre les lecteurs JavaScript/iframe
     * que les extensions ne savent plus extraire.
     */
    private suspend fun resolveWithWebView(ref: MultiSourceEpisodeRef): List<Hoster> {
        val source = sourceManager.get(ref.sourceId) as? AnimeHttpSource ?: return emptyList()
        val pageUrl = if (ref.episodeUrl.startsWith("http://") || ref.episodeUrl.startsWith("https://")) {
            ref.episodeUrl
        } else {
            source.baseUrl.trimEnd('/') + "/" + ref.episodeUrl.trimStart('/')
        }
        val headers = runCatching {
            source.headers.toMultimap().mapValues { (_, values) -> values.firstOrNull().orEmpty() }
        }.getOrDefault(emptyMap())

        logcat(LogPriority.INFO) {
            "Multi-source: résolution WebView pour ${ref.sourceName} -> $pageUrl"
        }
        val media = runCatching { webViewResolver.resolve(pageUrl, headers) }
            .onFailure {
                logcat(LogPriority.WARN, it) {
                    "Multi-source: WebView impossible pour ${ref.sourceName}"
                }
            }
            .getOrDefault(emptyList())
        MultiSourceDebugLog.log(
            "adapter",
            "webview ${ref.sourceName} media=${media.size} urls=${media.joinToString(" | ") { it.url }}",
        )
        if (media.isEmpty()) {
            logcat(LogPriority.WARN) {
                "Multi-source: WebView n'a trouvé aucune vidéo sur ${ref.sourceName}"
            }
            return emptyList()
        }

        val videos = media.mapIndexedNotNull { index, item ->
            runCatching {
                val videoHeaders = Headers.Builder().apply {
                    item.headers.forEach { (key, value) -> add(key, value) }
                }.build()
                Video(
                    videoUrl = item.url,
                    videoTitle = "WebView ${index + 1}",
                    headers = videoHeaders,
                    initialized = true,
                )
            }.getOrNull()
        }
        if (videos.isEmpty()) return emptyList()

        logcat(LogPriority.INFO) {
            "Multi-source: ${videos.size} vidéo(s) WebView trouvée(s) sur ${ref.sourceName}"
        }
        return videos.toHosterList()
    }

    /**
     * Une extension peut retourner une miniature ou un GIF dans sa liste de
     * vidéos. On les retire avant de choisir la source, afin de déclencher le
     * fallback vers une vraie source au lieu d'ouvrir une image.
     */
    private fun List<Hoster>.filterPlayableHosters(): List<Hoster> = mapNotNull { hoster ->
        val videos = hoster.videoList ?: return@mapNotNull hoster
        val playable = videos.filter { EpisodeMediaValidator.isAcceptableVideoUrl(it.videoUrl) }
        if (playable.isEmpty()) null else hoster.copy(videoList = playable)
    }

    private suspend fun resolveHosters(ref: MultiSourceEpisodeRef): List<Hoster> {
        val source = sourceManager.get(ref.sourceId)
        if (source == null) {
            logcat(LogPriority.WARN) {
                "Multi-source: source ${ref.sourceId} (${ref.sourceName}) indisponible"
            }
            return emptyList()
        }

        var sourceEpisode = SEpisode.create().apply {
            url = ref.episodeUrl
            name = ref.episodeName
            episode_number = ref.episodeNumber.toFloat()
        }

        var hosters = fetchResolvedHosters(source, sourceEpisode)
        if (hosters.isEmpty()) {
            // L'URL d'épisode enregistrée lors du mapping peut avoir changé
            // après une mise à jour de l'extension. On redemande la liste
            // actuelle à la source avant de passer à la source suivante.
            val currentEpisode = findCurrentEpisode(source, ref)
            if (currentEpisode != null && currentEpisode.url != sourceEpisode.url) {
                logcat(LogPriority.INFO) {
                    "Multi-source: épisode actualisé sur ${source.name} " +
                        "(${sourceEpisode.url} -> ${currentEpisode.url})"
                }
                sourceEpisode = currentEpisode
                hosters = fetchResolvedHosters(source, sourceEpisode)
            }
        }

        MultiSourceDebugLog.log(
            "adapter",
            "extension ${ref.sourceName} hosters=${hosters.size} " +
                "videos=${hosters.sumOf { it.videoList?.size ?: 0 }}",
        )
        if (hosters.isEmpty()) {
            logcat(LogPriority.WARN) {
                "Multi-source: aucune vidéo via ${source.name} pour ${ref.episodeUrl}"
            }
        }
        return hosters
    }

    private suspend fun fetchResolvedHosters(source: AnimeSource, sourceEpisode: SEpisode): List<Hoster> {
        // Certaines extensions (comme Voiranime) n'implémentent que
        // getVideoList(episode) et laissent getHosterList non supporté.
        // Dans ce cas, on va directement à la liste de vidéos.
        if (supportsHosters(source)) {
            val hosters = runCatching { source.getHosterList(sourceEpisode) }
                .onFailure {
                    logcat(LogPriority.WARN, it) {
                        "Multi-source: hosters indisponibles via ${source.name} pour ${sourceEpisode.url}"
                    }
                }
                .getOrNull()
                .orEmpty()

            val resolvedHosters = hosters.mapNotNull { hoster ->
                val videos = hoster.videoList ?: runCatching { source.getVideoList(hoster) }
                    .onFailure {
                        logcat(LogPriority.WARN, it) {
                            "Multi-source: vidéos indisponibles pour ${hoster.hosterName} via ${source.name}"
                        }
                    }
                    .getOrNull()
                    .orEmpty()
                val resolvedVideos = resolveVideos(source, videos)
                logcat(LogPriority.DEBUG) {
                    "Multi-source: ${source.name}/${hoster.hosterName} vidéos=" +
                        "${videos.size} résolues=${resolvedVideos.size}"
                }
                if (resolvedVideos.isEmpty()) null else hoster.copy(videoList = resolvedVideos)
            }

            if (resolvedHosters.isNotEmpty()) {
                return resolvedHosters
            }
        }

        val fallbackVideos = runCatching { source.getVideoList(sourceEpisode) }
            .onFailure {
                logcat(LogPriority.WARN, it) {
                    "Multi-source: fallback vidéo impossible via ${source.name} pour ${sourceEpisode.url}"
                }
            }
            .getOrNull()
            .orEmpty()
        val resolvedFallback = resolveVideos(source, fallbackVideos)
        logcat(LogPriority.DEBUG) {
            "Multi-source: ${source.name} fallback vidéos=${fallbackVideos.size} " +
                "résolues=${resolvedFallback.size}"
        }
        return if (resolvedFallback.isNotEmpty()) resolvedFallback.toHosterList() else emptyList()
    }

    /**
     * AnimeHttpSource fournit getHosterList() même si l'extension ne l'implémente
     * pas : l'appel par défaut fait une requête puis échoue. On inspecte la
     * hiérarchie pour éviter cet aller-retour inutile et passer directement à
     * getVideoList(episode) pour les anciennes extensions.
     */
    private fun supportsHosters(source: AnimeSource): Boolean {
        if (source !is AnimeHttpSource) return true

        var current: Class<*> = source.javaClass
        while (true) {
            if (current == ParsedAnimeHttpSource::class.java ||
                current == AnimeHttpSource::class.java
            ) {
                return false
            }
            if (current.declaredMethods.any { it.name in HOSTER_METHODS }) {
                return true
            }
            current = current.superclass ?: return false
        }
    }

    private suspend fun findCurrentEpisode(
        source: AnimeSource,
        ref: MultiSourceEpisodeRef,
    ): SEpisode? {
        return runCatching {
            val sourceAnime = SAnime.create().apply {
                url = ref.animeUrl
                title = ref.animeTitle ?: ref.animeUrl
                initialized = true
            }
            source.getAnimeEpisodeUpdate(
                anime = sourceAnime,
                episodes = emptyList(),
                fetchDetails = false,
                fetchEpisodes = true,
            ).episodes.firstOrNull { episode ->
                episode.episode_number > 0f &&
                    episode.episode_number.roundToInt() == ref.episodeNumber
            }
        }
            .onFailure {
                logcat(LogPriority.WARN, it) {
                    "Multi-source: impossible d'actualiser l'épisode ${ref.episodeNumber} sur ${source.name}"
                }
            }
            .getOrNull()
    }

    /**
     * Les extensions récentes peuvent renvoyer une URL "null" et attendre que
     * l'application appelle resolveVideo(). Comme l'adaptateur n'est pas une
     * AnimeHttpSource, cette résolution doit être faite ici avant de rendre les
     * vidéos au lecteur ou au gestionnaire de téléchargement.
     */
    private suspend fun resolveVideos(source: AnimeSource, videos: List<Video>): List<Video> {
        val sourceHeaders = (source as? AnimeHttpSource)?.headers
        return videos.mapNotNull { video ->
            val directUrl = video.videoUrl.takeIf { it.isNotBlank() && it != "null" }
            if (directUrl != null) {
                return@mapNotNull video.copy(
                    headers = video.headers ?: sourceHeaders,
                    initialized = true,
                )
            }

            if (source !is AnimeHttpSource) {
                return@mapNotNull null
            }

            val resolved = runCatching { source.resolveVideo(video) }
                .getOrNull()
                ?.takeIf { it.videoUrl.isNotBlank() && it.videoUrl != "null" }
            if (resolved != null) {
                return@mapNotNull resolved.copy(
                    headers = resolved.headers ?: sourceHeaders,
                    initialized = true,
                )
            }

            runCatching { source.getVideoUrl(video) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let {
                    video.copy(
                        videoUrl = it,
                        headers = video.headers ?: sourceHeaders,
                        initialized = true,
                    )
                }
        }
    }

    override fun toString(): String = name

    private val HOSTER_METHODS = setOf("getHosterList", "hosterListRequest", "hosterListParse")
    private const val MAX_RESOLUTION_SOURCES = 2
}

fun CatalogWork.toSAnime(url: String): SAnime {
    return SAnime.create().apply {
        this.url = url
        title = preferredTitle()
        thumbnail_url = coverUrl
        background_url = bannerUrl
        description = synopsis
        genre = genres.joinToString(", ")
        initialized = true
        fetch_type = FetchType.Episodes
    }
}

fun CatalogWork.toEpisodes(workUrl: String): List<SEpisode> {
    val count = episodes ?: return emptyList()
    if (count <= 0) return emptyList()

    val now = System.currentTimeMillis()
    return (1..count).map { number ->
        SEpisode.create().apply {
            url = "$workUrl${MultiSourceMappingStore.EPISODE_MARKER}$number"
            name = "Épisode $number"
            episode_number = number.toFloat()
            date_upload = now
        }
    }
}
