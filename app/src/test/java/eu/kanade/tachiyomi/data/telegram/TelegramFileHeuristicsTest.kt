package eu.kanade.tachiyomi.data.telegram

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramFileHeuristicsTest {

    @Test
    fun `phone exports without the work title are suspect`() {
        assertTrue(
            TelegramFileHeuristics.isSuspect(
                "YouCut_20261003_105007875.mp4",
                "JoJo's Bizarre Adventure : Steel Ball Run",
            ),
        )
        assertTrue(
            TelegramFileHeuristics.isSuspect(
                "VID_20260930_182953.mkv",
                "JoJo's Bizarre Adventure : Steel Ball Run",
            ),
        )
    }

    @Test
    fun `generic work title is suspect even without extension`() {
        assertTrue(TelegramFileHeuristics.isGenericTitle("VID 20260330 193605"))
        assertTrue(TelegramFileHeuristics.isSuspect("random.mp4", "VID 20260330 193605"))
    }

    @Test
    fun `actual steel ball run export is suspect`() {
        assertTrue(
            TelegramFileHeuristics.isSuspect(
                "YouCut_20261023_185935454.mp4",
                "JoJo's Bizarre Adventure Part 7 : Steel Ball Run",
            ),
        )
        assertTrue(
            TelegramFileHeuristics.isSuspect(
                "VID_20260930_182953.mkv",
                "JoJo's Bizarre Adventure Part 7 : Steel Ball Run",
            ),
        )
    }

    @Test
    fun `generic filename is kept when caption names the jojo work`() {
        assertFalse(
            TelegramFileHeuristics.isSuspect(
                fileName = "YouCut_20261023_185935454.mp4",
                caption = "JoJo's Bizarre Adventure Part 7 : Steel Ball Run EP06 VF",
                title = "JoJo's Bizarre Adventure : Steel Ball Run",
            ),
        )
        assertFalse(
            TelegramFileHeuristics.isSuspect(
                fileName = "VID_20260930_182953.mkv",
                caption = "JoJo's Bizarre Adventure Part 7 : Steel Ball Run E01 VF Convertie",
                title = "JoJo's Bizarre Adventure Part 7 : Steel Ball Run",
            ),
        )
    }

    @Test
    fun `generic video placeholder titles are ignored`() {
        assertTrue(TelegramFileHeuristics.isGenericTitle("generic video expires 1787267635520&srcIp 102 207 8"))
        assertTrue(
            TelegramFileHeuristics.isSuspect(
                "generic_video_#expires=1787267702523&srcIp=102_207_8_16.mp4",
                "generic video expires 1787267702523&srcIp 102 207 8",
            ),
        )
        assertFalse(TelegramFileHeuristics.isGenericTitle("Kowloon Generic Romance"))
    }

    @Test
    fun `apk and executable files are unsupported`() {
        assertTrue(TelegramFileHeuristics.isUnsupportedFile("1xbet.apk", "application/octet-stream"))
        assertTrue(TelegramFileHeuristics.isUnsupportedFile("setup.exe", "application/x-msdownload"))
        assertFalse(TelegramFileHeuristics.isUnsupportedFile("episode.mp4", "video/mp4"))
        assertFalse(TelegramFileHeuristics.isUnsupportedFile("manga.cbz", "application/x-cbr"))
    }

    @Test
    fun `named episode is kept`() {
        assertFalse(
            TelegramFileHeuristics.isSuspect(
                "VID_20260930_182953 Black Clover EP01.mp4",
                "Black Clover",
            ),
        )
        assertFalse(
            TelegramFileHeuristics.isSuspect(
                "JoJo's Bizarre Adventure S03E01 VF.mp4",
                "JoJo's Bizarre Adventure : Stardust Crusaders",
            ),
        )
    }
}
