package eu.kanade.tachiyomi.data.telegram.parse

import eu.kanade.tachiyomi.data.telegram.parse.TelegramTitleParser.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TelegramTitleParserTest {

    @Test
    fun `avatar seven havens`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Avatar: Seven Havens [S01E04] [VF] @kinemavf.mp4",
            caption = "Avatar: Seven Havens [S01E04] [VF]",
            kind = Kind.VIDEO,
        )

        assertEquals("Avatar: Seven Havens", parsed.title)
        assertEquals(1, parsed.season)
        assertEquals(4, parsed.episode)
        assertEquals("VF", parsed.language)
        assertEquals(TelegramTitleParser.Confidence.HIGH, parsed.confidence)
    }

    @Test
    fun `black clover saison then episode`() {
        val name = "[ @MainstreamZone ] Black Clover Saison 02 EP01 VOSTFR NON CONVERTIE HD.mp4"

        val parsed = TelegramTitleParser.parse(
            fileName = name,
            caption = name,
            kind = Kind.VIDEO,
        )

        assertEquals("Black Clover", parsed.title)
        assertEquals(2, parsed.season)
        assertEquals(1, parsed.episode)
        assertEquals("VOSTFR", parsed.language)
        assertEquals("NON CONVERTIE · HD", parsed.variant)
        assertEquals(TelegramTitleParser.Confidence.HIGH, parsed.confidence)
    }

    @Test
    fun `tokyo revengers episode then season`() {
        val parsed = TelegramTitleParser.parse(
            fileName = null,
            caption = "[ @MangaGalleryFR ] Tokyo Revengers EP02 S4 VF 🇰🇷 CONVERTIE 🇫🇷.mp4",
            kind = Kind.VIDEO,
        )

        assertEquals("Tokyo Revengers", parsed.title)
        assertEquals(4, parsed.season)
        assertEquals(2, parsed.episode)
        assertEquals("VF", parsed.language)
        assertEquals("CONVERTIE", parsed.variant)
    }

    @Test
    fun `tokyo revengers underscored filename with channel prefix`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "@MangaGalleryFR_Tokyo_Revengers_EP02_S4_VF.mp4",
            caption = null,
            kind = Kind.VIDEO,
            channelUsername = "MangaGalleryFR",
        )

        assertEquals("Tokyo Revengers", parsed.title)
        assertEquals(4, parsed.season)
        assertEquals(2, parsed.episode)
    }

    @Test
    fun `jojo part 7 keeps part in the title`() {
        val parsed = TelegramTitleParser.parse(
            fileName = null,
            caption = "JoJo's Bizarre Adventure Part 7: Steel Ball Run EP02 VF CONVERTIE",
            kind = Kind.VIDEO,
        )

        assertEquals("JoJo's Bizarre Adventure : Steel Ball Run", parsed.title)
        assertEquals(7, parsed.season)
        assertEquals(2, parsed.episode)
        assertEquals("VF", parsed.language)
    }

    @Test
    fun `canonical title matches rebuilt jojo indexes`() {
        assertEquals(
            "JoJo's Bizarre Adventure : Steel Ball Run",
            TelegramTitleParser.canonicalTitle("JoJo's Bizarre Adventure Part 7 : Steel Ball Run"),
        )
    }

    @Test
    fun `part number is not an episode`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Steel Ball Run Part 7.mp4",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("JoJo's Bizarre Adventure : Steel Ball Run", parsed.title)
        assertEquals(7, parsed.season)
        assertNull(parsed.episode)
        assertEquals(TelegramTitleParser.Confidence.NONE, parsed.confidence)
    }

    @Test
    fun `webtoon chapter range`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "[ @WebtoonZone ]The Greatest Estate Developer.cbz",
            caption = "[ @WebtoonZone ]The Greatest Estate Developer 123-132",
            kind = Kind.DOCUMENT,
        )

        assertEquals("The Greatest Estate Developer", parsed.title)
        assertEquals(123, parsed.chapterStart)
        assertEquals(132, parsed.chapterEnd)
        assertNull(parsed.episode)
        assertEquals(TelegramTitleParser.Confidence.MEDIUM, parsed.confidence)
    }

    @Test
    fun `year range is not a chapter range`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Some Manga 2020-2021.cbz",
            caption = null,
            kind = Kind.DOCUMENT,
        )

        assertNull(parsed.chapterStart)
    }

    @Test
    fun `bare number after separator`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Black Clover - 12.mp4",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("Black Clover", parsed.title)
        assertEquals(12, parsed.episode)
        assertEquals(TelegramTitleParser.Confidence.LOW, parsed.confidence)
    }

    @Test
    fun `season only`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Tokyo Revengers Saison 4 VF.mp4",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("Tokyo Revengers", parsed.title)
        assertEquals(4, parsed.season)
        assertNull(parsed.episode)
    }

    @Test
    fun `cross season format`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "One Piece 21x1050 VOSTFR.mkv",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("One Piece", parsed.title)
        assertEquals(21, parsed.season)
        assertEquals(1050, parsed.episode)
    }

    @Test
    fun `caption wins over generic video filename`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "video_42.mp4",
            caption = "Naruto Shippuden EP42 VF",
            kind = Kind.VIDEO,
        )

        assertEquals("Naruto Shippuden", parsed.title)
        assertEquals(42, parsed.episode)
    }

    @Test
    fun `dirty title keeps meaningful punctuation`() {
        val parsed = TelegramTitleParser.parse(
            fileName = null,
            caption = "[ @Canal ] Kaguya-sama: Love Is War (Saison 3) EP04 VOSTFR 🎬.mp4",
            kind = Kind.VIDEO,
        )

        assertEquals("Kaguya-sama: Love Is War", parsed.title)
        assertEquals(3, parsed.season)
        assertEquals(4, parsed.episode)
        assertEquals("VOSTFR", parsed.language)
    }

    @Test
    fun `channel name is cleaned`() {
        assertEquals("BLACK CLOVER", TelegramTitleParser.cleanChannelName("BLACK CLOVER ( VF )"))
        assertEquals(
            "J.B.A : Steel Ball Run",
            TelegramTitleParser.cleanChannelName("J.B.A : Steel Ball Run VF"),
        )
        assertEquals(
            "Webtoon Zone",
            TelegramTitleParser.cleanChannelName("Webtoon Zone™ - Manhwa - Manhua - Webcomic"),
        )
    }

    @Test
    fun `slug is stable`() {
        assertEquals("the-greatest-estate-developer", TelegramTitleParser.slug("The Greatest Estate Developer"))
        assertEquals("jojo-s-bizarre-adventure", TelegramTitleParser.slug("JoJo's Bizarre Adventure"))
        assertEquals("black-clover", TelegramTitleParser.slug("  BLACK   CLOVER  "))
    }

    @Test
    fun `language attached to sxxexx is not part of the title`() {
        val name = "Jojo bizarre adventure S03E01VF"

        val parsed = TelegramTitleParser.parse(
            fileName = "YouCut_20261009_141438364.mp4",
            caption = name,
            kind = Kind.VIDEO,
        )

        assertEquals("JoJo's Bizarre Adventure : Stardust Crusaders", parsed.title)
        assertEquals(3, parsed.season)
        assertEquals(1, parsed.episode)
        assertEquals("VF", parsed.language)
    }

    @Test
    fun `language attached to episode marker`() {
        val name = "JoJo's Bizarre Adventure Part 7 : Steel Ball Run EP04VF"

        val parsed = TelegramTitleParser.parse(
            fileName = "video.mp4",
            caption = name,
            kind = Kind.VIDEO,
        )

        assertEquals("JoJo's Bizarre Adventure : Steel Ball Run", parsed.title)
        assertEquals(7, parsed.season)
        assertEquals(4, parsed.episode)
        assertEquals("VF", parsed.language)
    }

    @Test
    fun `quality label is removed from the title`() {
        val caption = "Phantom blood S01E09VF\nQualité : 480p"

        val parsed = TelegramTitleParser.parse(
            fileName = "JoJo's_Bizarre_Adventure_Version_française_480P_S01_E09.mp4",
            caption = caption,
            kind = Kind.VIDEO,
        )

        assertEquals("JoJo's Bizarre Adventure : Phantom Blood", parsed.title)
        assertEquals(1, parsed.season)
        assertEquals(9, parsed.episode)
        assertEquals("VF", parsed.language)
        assertEquals("480p", parsed.variant)
    }

    @Test
    fun `technical caption falls back to the file name`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "@AnimesZone_JoJo_no_Kimyou_na_Bouken_Stone_Ocean_JoJos_Bizarre_Adventure.mkv",
            caption = "#CVRT 12 VF",
            kind = Kind.VIDEO,
        )

        assertEquals("JoJo's Bizarre Adventure : Stone Ocean", parsed.title)
        assertEquals(6, parsed.season)
        assertEquals(12, parsed.episode)
        assertEquals("VF", parsed.language)
        assertEquals("CONVERTIE", parsed.variant)
    }

    @Test
    fun `uploader tag does not fake the season`() {
        val name = "[@HELLS_PARADISE_S02_FR] HELL'S PARADISE EP 01 VF.mp4"

        val parsed = TelegramTitleParser.parse(
            fileName = name,
            caption = name,
            kind = Kind.VIDEO,
        )

        assertEquals("HELL'S PARADISE", parsed.title)
        assertNull(parsed.season)
        assertEquals(1, parsed.episode)
        assertEquals("VF", parsed.language)
    }

    @Test
    fun `overlapping patterns never eat neighbouring letters`() {
        val names = listOf(
            "[@crunchyrolVO ] HELL'S PARADISE S02 EP12 VF CONVERTIE.mp4",
            "[@crunchyrolVO ] HELL'S PARADISE S02 EP12 VF HD.MP4",
            "[@CRUNCHYROLVO] HELL'S PARADISE S02 EP 10 VF CONVERTIE.MP4",
            "[@crunchyrolVO ] HELL'S PARADISE S02 EP09 VOSTFR CONVERTIE.MP4",
        )

        names.forEach { name ->
            val parsed = TelegramTitleParser.parse(name, name, Kind.VIDEO)

            assertEquals("HELL'S PARADISE", parsed.title, name)
            assertEquals(2, parsed.season, name)
        }
    }

    @Test
    fun `episode number before a language tag`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Black Clover 166 VF.mp4",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("Black Clover", parsed.title)
        assertEquals(166, parsed.episode)
    }

    @Test
    fun `document chapter number`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Blue Lock Chapitre 364.cbz",
            caption = null,
            kind = Kind.DOCUMENT,
        )

        assertEquals("Blue Lock", parsed.title)
        assertEquals(364, parsed.chapterStart)
        assertEquals(364, parsed.chapterEnd)
    }

    @Test
    fun `decimal chapter keeps the whole number out of the title`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "[@scanzone] Blue Lock Chapitre 364.2.cbz",
            caption = null,
            kind = Kind.DOCUMENT,
        )

        assertEquals("Blue Lock", parsed.title)
        assertEquals(364, parsed.chapterStart)
    }

    @Test
    fun `abbreviated chapter marker`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "[\uD83D\uDD25@ScanZone\uD83D\uDD25Wind Breaker c-215.cbz]",
            caption = null,
            kind = Kind.DOCUMENT,
        )

        assertEquals("Wind Breaker", parsed.title)
        assertEquals(215, parsed.chapterStart)
    }

    @Test
    fun `document chapter word without a number`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Dandadan Chapitre.cbz",
            caption = null,
            kind = Kind.DOCUMENT,
        )

        assertEquals("Dandadan", parsed.title)
        assertNull(parsed.chapterStart)
    }

    @Test
    fun `call to action is removed from captions`() {
        val parsed = TelegramTitleParser.parse(
            fileName = null,
            caption = "Avatar: Seven Havens S01E05 VF Abonne Toi.mp4",
            kind = Kind.VIDEO,
        )

        assertEquals("Avatar: Seven Havens", parsed.title)
        assertEquals(5, parsed.episode)
    }

    @Test
    fun `channel japanimation suffix is removed`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "16bit Sensation Another Layer Japanimation Épisode suivant.mp4",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("16bit Sensation Another Layer", parsed.title)
    }

    @Test
    fun `dangling parenthesis and technical suffix are removed`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Blue Orchestra (1 VOSTFRNON.mp4",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("Blue Orchestra", parsed.title)
    }

    @Test
    fun `extension and non converted suffix are removed`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Black Clover .mp4 NON CONVERTIE",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("Black Clover", parsed.title)
    }

    @Test
    fun `trailing episode number is recovered`() {
        val parsed = TelegramTitleParser.parse(
            fileName = "Black Clover 84 VF.mp4",
            caption = null,
            kind = Kind.VIDEO,
        )

        assertEquals("Black Clover", parsed.title)
        assertEquals(84, parsed.episode)
    }

    @Test
    fun `signature ignores articles and punctuation`() {
        assertEquals(
            TelegramTitleParser.signature("The Greatest Estate Developer"),
            TelegramTitleParser.signature("Greatest Estate Developer"),
        )
        assertEquals(
            TelegramTitleParser.signature("Hell's Paradise"),
            TelegramTitleParser.signature("HELL'S PARADISE"),
        )
        assertNotEquals(
            TelegramTitleParser.signature("Naruto"),
            TelegramTitleParser.signature("Naruto Shippuden"),
        )
        assertNotEquals(
            TelegramTitleParser.signature("JoJo Part 7"),
            TelegramTitleParser.signature("JoJo Part 8"),
        )
    }
}
