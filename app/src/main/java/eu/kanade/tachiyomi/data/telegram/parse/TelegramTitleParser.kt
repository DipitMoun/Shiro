package eu.kanade.tachiyomi.data.telegram.parse

import java.text.Normalizer
import java.util.Locale

/**
 * Analyse locale des noms de fichiers et légendes Telegram.
 *
 * Aucune dépendance Android : testable en JVM.
 *
 * Ordre de priorité pour le texte analysé : la légende, puis le nom de fichier.
 * Un nom de fichier comme `[ @Canal ] Titre Saison 02 EP01 VOSTFR HD.mp4` donne
 * le titre, la saison, l'épisode, la langue et la variante.
 */
object TelegramTitleParser {

    enum class Kind {
        VIDEO,
        DOCUMENT,
    }

    enum class Confidence {
        /** Saison et épisode explicites. */
        HIGH,

        /** Épisode explicite sans saison. */
        MEDIUM,

        /** Numéro nu en fin de nom. */
        LOW,

        /** Aucun numéro reconnu. */
        NONE,
    }

    data class Parsed(
        val title: String,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeEnd: Int? = null,
        val chapterStart: Int? = null,
        val chapterEnd: Int? = null,
        val language: String? = null,
        val variant: String? = null,
        val confidence: Confidence = Confidence.NONE,
    ) {
        val hasNumber: Boolean
            get() = episode != null || chapterStart != null
    }

    /**
     * @param fileName nom réel TDLib (peut être vide pour une vidéo native)
     * @param caption légende du message
     * @param kind vidéo ou document
     * @param channelUsername nom du canal sans @, pour retirer les préfixes
     *        du type `@MangaGalleryFR_Tokyo_Revengers_EP02_S4`.
     */
    fun parse(
        fileName: String?,
        caption: String?,
        kind: Kind,
        channelUsername: String? = null,
    ): Parsed {
        val file = normalizeRaw(fileName.orEmpty()).withoutUploaderTags()
        val cap = normalizeRaw(caption.orEmpty()).withoutUploaderTags()

        val sources = listOf(cap, file).filter { it.isNotBlank() }
        if (sources.isEmpty()) {
            return Parsed(title = "")
        }

        // La légende est prioritaire, le nom de fichier complète les informations manquantes.
        val primary = sources.first()
        val secondary = sources.drop(1).firstOrNull().orEmpty()

        val language = detectLanguage(primary) ?: detectLanguage(secondary)
        val variant = detectVariant(primary) ?: detectVariant(secondary)

        val numbers = extractNumbers(primary, kind)
            ?: extractNumbers(secondary, kind)
            ?: Numbers()

        val title = cleanTitle(
            text = primary.ifBlank { secondary },
            channelUsername = channelUsername,
            numbers = numbers,
            kind = kind,
        ).ifBlank {
            cleanTitle(secondary, channelUsername = channelUsername, numbers = numbers, kind = kind)
        }

        // Certaines légendes ne contiennent qu'un tag technique (« #CVRT 12 VF ») :
        // le nom de fichier, plus descriptif, porte alors le vrai titre.
        val resolvedTitle = cleanResidualSuffixes(
            if (isNoiseTitle(title)) {
                cleanTitle(
                    text = secondary,
                    channelUsername = channelUsername,
                    numbers = numbers,
                    kind = kind,
                ).ifBlank { title }
            } else {
                title
            },
        )

        val canonical = canonicalizeJojo(resolvedTitle, numbers.season)
        val finalTitle = canonical?.first ?: resolvedTitle
        val finalSeason = canonical?.second ?: numbers.season

        val confidence = when {
            numbers.season != null && numbers.episode != null -> Confidence.HIGH
            numbers.episode != null -> Confidence.MEDIUM
            numbers.chapterStart != null -> Confidence.MEDIUM
            numbers.bareNumber != null -> Confidence.LOW
            else -> Confidence.NONE
        }

        val episode = numbers.episode ?: numbers.bareNumber.takeIf { kind == Kind.VIDEO }
        val chapterStart = numbers.chapterStart
            ?: numbers.bareNumber.takeIf { kind == Kind.DOCUMENT }

        return Parsed(
            title = finalTitle,
            season = finalSeason,
            episode = episode,
            episodeEnd = when {
                numbers.episode != null -> numbers.episodeEnd
                else -> null
            },
            chapterStart = chapterStart,
            chapterEnd = numbers.chapterEnd
                ?: chapterStart?.takeIf { numbers.chapterStart != null },
            language = language,
            variant = variant,
            confidence = confidence,
        )
    }

    // ------------------------------------------------------------------
    // Extraction des numéros
    // ------------------------------------------------------------------

    private data class Numbers(
        val season: Int? = null,
        val episode: Int? = null,
        val episodeEnd: Int? = null,
        val chapterStart: Int? = null,
        val chapterEnd: Int? = null,
        val bareNumber: Int? = null,
    )

    private fun extractNumbers(raw: String, kind: Kind): Numbers? {
        if (raw.isBlank()) return null

        SXXEXX.find(raw)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            if (episode != null && episode > 0) {
                return Numbers(season = season?.takeIf { it > 0 }, episode = episode)
            }
        }

        SEASON_THEN_EPISODE.find(raw)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            if (episode != null && episode > 0) {
                return Numbers(season = season?.takeIf { it > 0 }, episode = episode)
            }
        }

        EPISODE_THEN_SEASON.find(raw)?.let { match ->
            val episode = match.groupValues[1].toIntOrNull()
            val season = match.groupValues[2].toIntOrNull()
            if (episode != null && episode > 0) {
                return Numbers(season = season?.takeIf { it > 0 }, episode = episode)
            }
        }

        CROSS_SEASON.find(raw)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull()
            if (episode != null && episode > 0) {
                return Numbers(season = season?.takeIf { it > 0 }, episode = episode)
            }
        }

        if (kind == Kind.DOCUMENT) {
            CHAPTER_RANGE.find(raw)?.let { match ->
                val start = chapterOf(match.groupValues[1])
                val end = chapterOf(match.groupValues[2])
                if (start != null && start > 0 && !isYearRange(start, end)) {
                    return Numbers(
                        chapterStart = start,
                        chapterEnd = end?.takeIf { it > start } ?: start,
                    )
                }
            }

            CHAPTER_NUMBER.find(raw)?.let { match ->
                val start = chapterOf(match.groupValues[1])
                if (start != null && start > 0) {
                    return Numbers(chapterStart = start, chapterEnd = start)
                }
            }
        }

        EPISODE_ONLY.find(raw)?.let { match ->
            val episode = match.groupValues[1].toIntOrNull()
            val season = SEASON_ONLY.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (episode != null && episode > 0) {
                if (kind == Kind.DOCUMENT) {
                    return Numbers(
                        chapterStart = episode,
                        chapterEnd = episode,
                        season = season?.takeIf { it > 0 },
                    )
                }
                return Numbers(episode = episode, season = season?.takeIf { it > 0 })
            }
        }

        // « Saison 02 » seule : pas d'épisode, mais la saison est utile.
        val seasonOnly = SEASON_ONLY.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()

        // Dernier recours : un numéro isolé après un séparateur, en fin de nom,
        // ou n'importe où avant un tag (« Black Clover 166 VF »).
        val bare = BARE_AFTER_SEPARATOR.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: TRAILING_NUMBER.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: standaloneNumber(raw)
        val candidate = bare
            ?.takeIf { it in 1..1999 }
            ?.takeIf { !isProtectedNumber(raw, it) }

        if (seasonOnly != null || candidate != null) {
            return Numbers(
                season = seasonOnly?.takeIf { it > 0 },
                bareNumber = candidate,
            )
        }

        return null
    }

    /**
     * Dernier nombre autonome du texte, hors résolution (`1080p`), codec (`x264`),
     * année (`2024`) et numéro protégé (`Part 7`, `Tome 3`).
     */
    private fun standaloneNumber(raw: String): Int? {
        return STANDALONE_NUMBER.findAll(raw)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..1999 && !isProtectedNumber(raw, it) }
            .lastOrNull()
    }

    private fun isYearRange(start: Int, end: Int?): Boolean {
        return start in 1900..2099 && (end == null || end in 1900..2099)
    }

    /** `364.2` (chapitre décimal) mais le modèle ne stocke que l'entier. */
    private fun chapterOf(raw: String): Int? = raw.substringBefore('.').toIntOrNull()

    /**
     * « Part 7 », « Film 2 », « Saison 2 » ne sont pas des numéros d'épisode.
     */
    private fun isProtectedNumber(raw: String, number: Int): Boolean {
        return PROTECTED_NUMBER.findAll(raw).any { match ->
            match.groupValues[1].toIntOrNull() == number
        }
    }

    // ------------------------------------------------------------------
    // Nettoyage du titre
    // ------------------------------------------------------------------

    private fun cleanTitle(
        text: String,
        channelUsername: String?,
        numbers: Numbers,
        kind: Kind,
    ): String {
        var title = text

        // Retire les blocs décoratifs : [ @Canal ], ( VF ), [S01E04]…
        title = BRACKET_BLOCK.replace(title) { match ->
            if (isTagBlock(match.groupValues[1])) " " else match.value
        }
        title = PAREN_BLOCK.replace(title) { match ->
            if (isTagBlock(match.groupValues[1])) " " else match.value
        }

        // Retire les URLs, pseudos et préfixes de canal.
        title = URL_TOKEN.replace(title, " ")
        title = AT_TOKEN.replace(title, " ")
        if (!channelUsername.isNullOrBlank()) {
            val username = Regex(
                """(?i)(?<![A-Za-z0-9_])${Regex.escape(channelUsername)}(?![A-Za-z0-9_])""",
            )
            title = username.replace(title, " ")
        }

        // Retire les appels à l'action des légendes.
        title = CALL_TO_ACTION.replace(title, " ")

        // Retire les numéros reconnus (saison / épisode / plage).
        val ranges = mutableListOf<IntRange>()
        SXXEXX.findAll(title).forEach { ranges += it.range }
        SEASON_THEN_EPISODE.findAll(title).forEach { ranges += it.range }
        EPISODE_THEN_SEASON.findAll(title).forEach { ranges += it.range }
        CROSS_SEASON.findAll(title).forEach { ranges += it.range }
        EPISODE_ONLY.findAll(title).forEach { ranges += it.range }
        SEASON_ONLY.findAll(title).forEach { ranges += it.range }
        CHAPTER_RANGE.findAll(title).forEach { ranges += it.range }
        if (kind == Kind.DOCUMENT) {
            CHAPTER_NUMBER.findAll(title).forEach { ranges += it.range }
        }
        if (numbers.bareNumber != null) {
            BARE_AFTER_SEPARATOR.findAll(title).forEach { ranges += it.range }
            TRAILING_NUMBER.findAll(title).forEach { ranges += it.range }
            STANDALONE_NUMBER.findAll(title).forEach { match ->
                if (match.groupValues[1].toIntOrNull() == numbers.bareNumber) {
                    ranges += match.range
                }
            }
        }
        title = removeRanges(title, ranges)

        // Retire les mots techniques.
        for (token in TECHNICAL_TOKENS) {
            title = token.replace(title, " ")
        }

        if (kind == Kind.DOCUMENT) {
            title = DOCUMENT_NOISE.replace(title, " ")
        }

        // Retire la variante « V » isolée issue d'un nom tronqué.
        title = title.replace(TRAILING_SINGLE_V, " ")

        return finalizeTitle(title)
    }

    private fun finalizeTitle(raw: String): String {
        var title = raw
            .replace('_', ' ')
            .replace(NON_TEXT, " ")
            .replace(MULTI_SPACE, " ")
            .trim()

        // Une année entre parenthèses est une métadonnée, pas le titre.
        PAREN_YEAR.findAll(title).forEach { match ->
            val candidate = title.removeRange(match.range).replace(MULTI_SPACE, " ").trim()
            if (candidate.isNotBlank()) title = candidate
        }

        // Séparateurs orphelins en bordure.
        title = title.trim('-', '–', ':', '|', '.', ',', '/', '(', ')', '[', ']', ' ')

        // Retire un suffixe de saison résiduel (« - Saison 2 »).
        title = TRAILING_SEASON.replace(title, " ").trim()

        return title.replace(MULTI_SPACE, " ").trim()
    }

    private fun isTagBlock(content: String): Boolean {
        val value = content.trim()
        if (value.isEmpty()) return true
        if (value.startsWith("@")) return true

        // « [ @Canal ] » ou « [🔥@Canal🔥] » : il ne reste que des décorations.
        val stripped = AT_TOKEN.replace(value, " ")
            .replace(NON_TEXT, " ")
            .trim()
        if (stripped.isBlank()) return true
        if (containsLanguage(value)) return true
        if (TECHNICAL_TOKEN_WHOLE.matches(value)) return true
        if (SXXEXX.containsMatchIn(value)) return true
        if (SEASON_ONLY.containsMatchIn(value)) return true
        if (EPISODE_ONLY.containsMatchIn(value)) return true
        if (CHAPTER_RANGE.containsMatchIn(value)) return true
        return false
    }

    // ------------------------------------------------------------------
    // Langue et variante
    // ------------------------------------------------------------------

    private fun detectLanguage(text: String): String? {
        val upper = text.uppercase(Locale.ROOT)
        return when {
            LANGUAGE_VOSTFR.containsMatchIn(upper) -> "VOSTFR"
            LANGUAGE_MULTI.containsMatchIn(upper) -> "MULTI"
            LANGUAGE_VF.containsMatchIn(upper) -> "VF"
            LANGUAGE_VO.containsMatchIn(upper) -> "VO"
            else -> null
        }
    }

    private fun containsLanguage(text: String): Boolean = detectLanguage(text) != null

    private fun detectVariant(text: String): String? {
        val upper = text.uppercase(Locale.ROOT)
        val parts = mutableListOf<String>()

        when {
            NON_CONVERTED.containsMatchIn(upper) -> parts += "NON CONVERTIE"
            CONVERTED.containsMatchIn(upper) || CONVERTED_SHORT.containsMatchIn(upper) ->
                parts += "CONVERTIE"
        }
        when {
            RESOLUTION_4K.containsMatchIn(upper) -> parts += "4K"
            RESOLUTION_1080.containsMatchIn(upper) -> parts += "1080p"
            RESOLUTION_720.containsMatchIn(upper) -> parts += "720p"
            RESOLUTION_480.containsMatchIn(upper) -> parts += "480p"
            RESOLUTION_HD.containsMatchIn(upper) -> parts += "HD"
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    // ------------------------------------------------------------------
    // Utilitaires publics
    // ------------------------------------------------------------------

    /**
     * Nettoie le nom d'un canal pour en faire une collection lisible.
     * « BLACK CLOVER ( VF ) » -> « Black Clover ».
     */
    fun cleanChannelName(raw: String): String {
        var name = normalizeRaw(raw)
        name = BRACKET_BLOCK.replace(name) { match ->
            if (isTagBlock(match.groupValues[1])) " " else match.value
        }
        name = PAREN_BLOCK.replace(name) { match ->
            if (isTagBlock(match.groupValues[1])) " " else match.value
        }
        name = name.replace('_', ' ')
        for (token in CHANNEL_SUFFIX_TOKENS) {
            name = token.replace(name, " ")
        }
        name = name.replace(NON_TEXT, " ")
        name = name.replace(MULTI_SPACE, " ").trim()
        name = name.trim('-', '–', ':', '|', '.', ',', '/', ' ')

        // Retire « - Manhwa - Manhua - Webcomic » et équivalents.
        val segments = name.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        val filtered = segments.filterNot { segment ->
            CHANNEL_NOISE_SEGMENTS.any { it.equals(segment, ignoreCase = true) }
        }
        if (filtered.isNotEmpty()) {
            name = filtered.joinToString(" - ")
        }

        return name.ifBlank { raw.trim() }
    }

    /** Clé stable pour regrouper des fichiers (minuscules, sans accents ni ponctuation). */
    fun slug(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
        return normalized
            .replace(SLUG_INVALID, "-")
            .trim('-')
            .replace(MULTI_DASH, "-")
    }

    /**
     * Signature de regroupement, plus tolérante que [slug] : articles, mots de
     * liaison et ponctuation sont ignorés, les tokens sont triés.
     *
     * « The Greatest Estate Developer » et « Greatest Estate Developer »
     * partagent donc la même signature, alors que « Part 7 » reste distinct.
     */

    /**
     * Titre canonique utilisé pour rapprocher un ancien libellé d'un index
     * reconstruit (par exemple « Part 7 : Steel Ball Run » -> « Steel Ball Run »).
     */
    fun canonicalTitle(raw: String, season: Int? = null): String {
        return canonicalizeJojo(raw, season)?.first ?: raw
    }

    fun signature(raw: String): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
        val tokens = normalized.split(NON_ALNUM)
            .filter { it.isNotBlank() }
            .filterNot { it in STOP_WORDS }
            // Un token d'une lettre est un reste d'apostrophe (« JoJo's » -> s).
            .filter { it.length > 1 || it.all(Char::isDigit) }
            .distinct()
            .sorted()

        return tokens.joinToString("-").ifBlank { slug(raw) }
    }

    private fun String.withoutUploaderTags(): String {
        return replace(BRACKETED_UPLOADER_TAG, " ")
            .replace(UPLOADER_TAG, " ")
            .replace(MULTI_SPACE, " ")
            .trim()
    }

    private fun isNoiseTitle(title: String): Boolean {
        val value = slug(title)
        return value.isBlank() || value in TITLE_NOISE
    }

    /**
     * Regroupe les nombreuses variantes de JoJo (titres anglais, « J.B.A »,
     * typos des canaux, suffixe Japanimation) sous une seule œuvre par partie.
     */
    private fun canonicalizeJojo(title: String, season: Int?): Pair<String, Int?>? {
        val text = slug(title).replace('-', ' ')
        val isJojo = text.contains("jojo") ||
            text.contains("j b a") ||
            text.contains("jba") ||
            JOJO_PART_MARKERS.any { (_, markers) -> markers.any { text.contains(it) } }
        if (!isJojo) return null

        val explicitPart = JOJO_PART_MARKERS
            .firstOrNull { (_, markers) -> markers.any { text.contains(it) } }
            ?.first
        val numberedPart = JOJO_PART_NUMBER.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val part = explicitPart ?: numberedPart ?: season?.takeIf { it in 1..9 }

        val name = part?.let { JOJO_PART_NAMES.getOrNull(it - 1) }
        val canonical = if (name != null) {
            "JoJo's Bizarre Adventure : $name"
        } else {
            "JoJo's Bizarre Adventure"
        }
        return canonical to (part ?: season)
    }

    private fun normalizeRaw(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(FILE_EXTENSION, " ")
            .replace('_', ' ')
            .replace(MULTI_SPACE, " ")
            .trim()
    }

    /**
     * Nettoie les résidus propres aux canaux : « Japanimation », « Épisode
     * suivant », « Version suivante », « VOSTFRNON », extension `.mp4` ou
     * parenthèse ouverte laissée par un ancien parseur.
     */
    private fun cleanResidualSuffixes(raw: String): String {
        var value = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (pattern in RESIDUAL_SUFFIXES) {
                val cleaned = value.replace(pattern, " ").trim()
                if (cleaned != value) {
                    value = cleaned
                    changed = true
                }
            }
        }
        return value
            .replace(MULTI_SPACE, " ")
            .trim()
            .trimEnd('-', '–', ':', '_', '|', '/')
            .trim()
    }

    private fun removeRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text

        // Les motifs se chevauchent (S02E12 contient EP12, qui contient 12).
        // Un masque évite de supprimer des lettres voisines en rétrécissant le texte.
        val mask = BooleanArray(text.length)
        ranges.forEach { range ->
            val from = range.first.coerceAtLeast(0)
            val to = range.last.coerceAtMost(text.length - 1)
            for (index in from..to) {
                if (index in text.indices) mask[index] = true
            }
        }

        val builder = StringBuilder(text.length)
        text.forEachIndexed { index, char ->
            builder.append(if (mask[index]) ' ' else char)
        }
        return builder.toString()
    }

    // ------------------------------------------------------------------
    // Expressions régulières
    // ------------------------------------------------------------------

    private val SXXEXX = Regex(
        """(?i)\bS\s*0*(\d{1,3})\s*[.\-_ ]?\s*E(?:P(?:ISODE)?)?\s*0*(\d{1,4})(?!\d)""",
    )
    private val SEASON_THEN_EPISODE = Regex(
        """(?i)\b(?:sai?son|season)\s*0*(\d{1,3})\s*(?:[-–:|]\s*)?""" +
            """(?:ep(?:isode)?|épisode|e)\s*0*(\d{1,4})(?!\d)""",
    )
    private val EPISODE_THEN_SEASON = Regex(
        """(?i)\b(?:ep(?:isode)?|épisode|e)\s*0*(\d{1,4})\s*(?:[-–:|]\s*)?""" +
            """(?:sai?son|season|s)\s*0*(\d{1,3})(?!\d)""",
    )
    private val CROSS_SEASON = Regex("""(?i)\b0*(\d{1,3})\s*[x×]\s*0*(\d{1,4})(?!\d)""")
    private val EPISODE_ONLY = Regex(
        """(?i)\b(?:ep(?:isode)?|épisode|e)\s*[.\-_ ]?\s*0*(\d{1,4})(?!\d)""",
    )
    private val SEASON_ONLY = Regex("""(?i)(?<!['’])\b(?:sai?son|season|s)\s*0*(\d{1,3})(?!\d)""")
    private val CHAPTER_RANGE = Regex(
        """(?i)\b(?:ch(?:apitre)?|chap)?\s*\.?\s*0*(\d{1,4}(?:\.\d{1,3})?)\s*[-–]\s*""" +
            """0*(\d{1,4}(?:\.\d{1,3})?)\b""",
    )
    private val CHAPTER_NUMBER = Regex(
        """(?i)(?:\b(?:chapitres?|chaps?|ch)\b|\bc\b)\s*\.?\s*[-–]?\s*""" +
            """0*(\d{1,4}(?:\.\d{1,3})?)\b""",
    )
    private val BARE_AFTER_SEPARATOR = Regex("""(?:^|\s)[-–:|]\s*0*(\d{1,4})(?:\s|$)""")
    private val TRAILING_NUMBER = Regex("""(?<![\p{L}\p{N}])0*(\d{1,4})\s*$""")
    private val STANDALONE_NUMBER = Regex("""(?<![\p{L}\p{N}])0*(\d{1,4})(?![\p{L}\p{N}])""")
    private val PROTECTED_NUMBER = Regex(
        """(?i)\b(?:part|partie|film|movie|oav|ova|sp[ée]cial|special|sai?son|season|s|""" +
            """tomes?|volumes?|vol)\s*0*(\d{1,4})\b""",
    )

    private val BRACKET_BLOCK = Regex("""\[([^\]]*)]""")
    private val PAREN_BLOCK = Regex("""\(([^)]*)\)""")
    private val URL_TOKEN = Regex("""(?i)\b(?:https?://|www\.)\S+|\bt\.me/\S+""")
    private val AT_TOKEN = Regex("""@[A-Za-z0-9_]{3,32}""")
    private val FILE_EXTENSION = Regex(
        """(?i)\.(?:mp4|mkv|avi|mov|webm|ts|m4v|cbz|zip|rar|7z|pdf|epub)(?=\s*[\]\)]?\s*$)""",
    )

    private val CALL_TO_ACTION = Regex(
        """(?i)\b(?:abonne[zr]?[-\s]?toi|abonnez[-\s]?vous|rejoignez[-\s]?nous|""" +
            """rejoins[-\s]?nous|suivez[-\s]?nous|partagez|partager|""" +
            """lien\s+(?:en\s+bio|en\s+description|dans\s+la\s+description|dans\s+les\s+commentaires))\b""",
    )

    private val DOCUMENT_NOISE = Regex(
        """(?i)\b(?:tomes?|volumes?|vol)\s*\.?\s*0*\d{1,4}\b|""" +
            """\b(?:chapitres?|chaps?|ch)\b\.?""",
    )

    private val PAREN_YEAR = Regex("""[\(\[]\s*(?:19|20)\d{2}\s*[\)\]]""")

    private val LANGUAGE_VOSTFR = Regex("""(?i)(?<![A-Z])(?:VOSTFR|VOSTA|VOST|SUBFR|SUB FR)(?![A-Z])""")
    private val LANGUAGE_MULTI = Regex("""(?i)(?<![A-Z])(?:MULTI|TRAD|MULTILINGUE)(?![A-Z])""")
    private val LANGUAGE_VF = Regex("""(?i)(?<![A-Z])(?:VFF|VF|FRENCH|FR)(?![A-Z])""")
    private val LANGUAGE_VO = Regex("""(?i)(?<![A-Z])(?:VOA|VO|ORIGINAL)(?![A-Z])""")

    private val NON_CONVERTED = Regex("""\bNON[\s-]*CONVERTIE?\b""")
    private val CONVERTED = Regex("""\bCONVERTIE?\b""")
    private val CONVERTED_SHORT = Regex("""\bCVRT\b""")
    private val RESOLUTION_4K = Regex("""\b(?:4K|2160P)\b""")
    private val RESOLUTION_1080 = Regex("""\b(?:1080P|FHD)\b""")
    private val RESOLUTION_720 = Regex("""\b(?:720P)\b""")
    private val RESOLUTION_480 = Regex("""\b(?:480P)\b""")
    private val RESOLUTION_HD = Regex("""\b(?:HD|HQ)\b""")

    private val TECHNICAL_TOKENS = listOf(
        Regex("""(?i)(?<![A-Z])(?:VOSTFR|VOSTA|VOST)(?![A-Z])"""),
        Regex("""(?i)(?<![A-Z])(?:VFF|VF)(?![A-Z])"""),
        Regex("""(?i)(?<![A-Z])(?:MULTI|TRAD|MULTILINGUE|FRENCH)(?![A-Z])"""),
        Regex("""(?i)\bNON[\s-]*CONVERTIE?\b"""),
        Regex("""(?i)\bCONVERTIE?\b"""),
        Regex("""(?i)\b(?:4K|2160P|1080P|720P|480P|360P|FHD|UHD|HD|HQ|SD)\b"""),
        Regex("""(?i)\b(?:WEB[\s-]?DL|WEBRIP|BLURAY|BLU[\s-]?RAY|BDRIP|HDRIP|DVDRIP|HDTV)\b"""),
        Regex("""(?i)\b(?:X264|X265|H264|H265|HEVC|AVC|AAC|AC3|DDP?)\b"""),
        Regex("""(?i)\b(?:AMZN|NF|CR|DSNP|ATVP)\b"""),
        Regex("""(?i)\bQUALIT\S*"""),
        Regex("""(?i)\b(?:COMPLET|COMPLETE|INTEGRALE|INTÉGRALE|UNCENSORED|UNCENSORED)\b"""),
    )

    private val TECHNICAL_TOKEN_WHOLE = Regex(
        """(?i)^(?:VF|VOSTFR|VOST|MULTI|TRAD|HD|FHD|UHD|4K|SD|HQ|1080P|720P|480P|""" +
            """CONVERTIE|NON CONVERTIE|WEB-DL|WEBRIP|BLURAY|BDRIP|HDRIP|DVDRIP|""" +
            """X264|X265|HEVC|AVC|AAC|COMPLET|INTEGRALE|INTÉGRALE)$""",
    )

    private val CHANNEL_SUFFIX_TOKENS = listOf(
        Regex("""(?i)\b(?:VOSTFR|VOST|VF|VO|MULTI|TRAD)\b"""),
        Regex("""(?i)\b(?:HD|FHD|4K|UHD)\b"""),
        Regex("""(?i)\b(?:OFFICIEL|OFFICIAL|OFFICIELLE)\b"""),
        Regex("""[™®©]"""),
    )

    private val CHANNEL_NOISE_SEGMENTS = listOf(
        "Manhwa",
        "Manhua",
        "Webcomic",
        "Webtoon",
        "Manga",
        "Anime",
        "Scantrad",
    )

    private val TRAILING_SEASON = Regex("""(?i)\s*[-–:|]?\s*(?:sai?son|season|s)\s*0*\d{1,3}\s*$""")
    private val TRAILING_SINGLE_V = Regex("""(?i)\s+V\s*$""")

    private val RESIDUAL_SUFFIXES = listOf(
        Regex("""(?iu)\s*[-–:|]?\s*japanimations?\s*$"""),
        Regex("""(?iu)\s*[-–:|]?\s*[ée]pisode\s+suivant\s*$"""),
        Regex("""(?iu)\s*[-–:|]?\s*version\s+suivante\s*$"""),
        Regex("""(?iu)\s*[-–:|]?\s*(?:vostfr|vf|vo)\s*non\s*$"""),
        Regex("""(?iu)\s*[-–:|]?\s*(?:vostfrnon|vfnon|vfconvertie|vostfrconvertie)\s*$"""),
        Regex("""(?iu)\s*[-–:|]?\s*non\s+convertie\s*$"""),
        Regex("""(?iu)\s*\.(?:mp4|mkv|avi|mov|webm|ts|m4v)\s*$"""),
        Regex("""\s*\(\s*\d*\s*$"""),
    )

    private val NON_TEXT = Regex("""[^\p{L}\p{N}\s'’:\-–&!?.,/+()]""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val MULTI_SPACE = Regex("""\s+""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val SLUG_INVALID = Regex("""[^\p{L}\p{N}]+""")
    private val MULTI_DASH = Regex("""-+""")

    /** Préfixes d'upload : `[@HELLS_PARADISE_S02_FR]` ou `@kinemavf`. */
    private val BRACKETED_UPLOADER_TAG = Regex("""\[\s*@[^\]]*]""")
    private val UPLOADER_TAG = Regex("""@[A-Za-z0-9_]{2,}""")

    /** Légendes qui ne portent pas de titre exploitable. */
    private val TITLE_NOISE = setOf(
        "cvrt", "cvt", "convertie", "vf", "vostfr", "vost", "hd", "qualite",
        "video", "film", "serie", "oav", "ova", "episode",
    )

    private val JOJO_PART_NAMES = listOf(
        "Phantom Blood",
        "Battle Tendency",
        "Stardust Crusaders",
        "Diamond is Unbreakable",
        "Golden Wind",
        "Stone Ocean",
        "Steel Ball Run",
        "JoJolion",
        "The JOJOLands",
    )

    private val JOJO_PART_MARKERS = listOf(
        1 to listOf("phantom blood"),
        2 to listOf("battle tendency"),
        3 to listOf("stardust crusaders", "stardust crusader"),
        4 to listOf("diamond is unbreakable", "diamond wa kudakenai"),
        5 to listOf("golden wind", "vento aureo"),
        6 to listOf("stone ocean"),
        7 to listOf("steel ball run"),
        8 to listOf("jojolion"),
        9 to listOf("jojolands"),
    )

    private val JOJO_PART_NUMBER = Regex("""(?:part|partie)\s*0*([1-9])""")

    /** Mots de liaison ignorés par [signature]. */
    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "or",
        "et", "ou", "de", "du", "des", "la", "le", "les", "un", "une", "aux", "au",
        "en", "dans", "sur", "par", "pour", "avec",
        "in", "on", "at", "to", "for", "with", "from",
        "is", "are", "est", "son", "sa", "ses", "its", "his", "her", "their",
        "no", "wa", "ga", "wo", "ni",
        "saison", "season",
    )
}
