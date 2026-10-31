package eu.kanade.tachiyomi.data.telegram.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Cible de test demandée : « Avatar Seven Havens ».
 *
 * Aucun appareil n'est touché : on vérifie la logique d'état utilisée par
 * l'écran Canaux. Une œuvre inconnue est reconnue via un override persistant ;
 * elle quitte alors « Non reconnus » et le compteur décroît.
 */
class TelegramChannelRecognitionTest {

    @Test
    fun `avatar seven havens leaves the unresolved collection after recognition`() {
        val workKey = "avatar-seven-havens:0"
        val before = TelegramWorkOverrides()

        val unresolvedBefore = listOf(workKey).count { !before.isRecognized(it) }
        assertEquals(1, unresolvedBefore)

        val after = before.withMetadata(
            workKey,
            TelegramResolvedWork(
                query = "Avatar Seven Havens",
                anilistId = 197_000,
                provider = "ANILIST",
                title = "Avatar: Seven Havens",
                coverUrl = "https://example.invalid/avatar-seven-havens.jpg",
                score = 0.91,
            ),
        )

        assertTrue(after.isRecognized(workKey))
        assertEquals("Avatar: Seven Havens", after.titleFor(workKey))
        assertEquals(
            "https://example.invalid/avatar-seven-havens.jpg",
            after.coverFor(workKey),
        )

        val unresolvedAfter = listOf(workKey).count { !after.isRecognized(it) }
        val resolvedAfter = listOf(workKey).filter { after.isRecognized(it) }
        assertEquals(0, unresolvedAfter)
        assertEquals(listOf(workKey), resolvedAfter)
    }

    @Test
    fun `hidden work stays out of the parent collection`() {
        val workKey = "avatar-seven-havens:0"
        val hidden = TelegramWorkOverrides().hide(workKey)

        assertTrue(hidden.isHidden(workKey))
        assertFalse(TelegramWorkOverrides().isHidden(workKey))
    }

    @Test
    fun `recognizing a work decrements the channel unknown counter`() {
        val workKey = "avatar-seven-havens:0"
        val summary = TelegramChannelSummary(
            chatId = 42L,
            title = "Avatar",
            works = listOf(
                TelegramWorkSummary(
                    id = "work-1",
                    chatId = 42L,
                    workKey = workKey,
                    mediaKind = TelegramMediaKind.VIDEO,
                    title = "Avatar Seven Havens",
                    fileCount = 5,
                    episodeCount = 5,
                    resolved = false,
                ),
            ),
        )

        val before = summary.countsWith(TelegramWorkOverrides())
        assertEquals(0, before.videoWorks)
        assertEquals(1, before.unknownVideo)

        val recognized = TelegramWorkOverrides().withMetadata(
            workKey,
            TelegramResolvedWork(
                query = "Avatar Seven Havens",
                anilistId = 197_000,
                provider = "ANILIST",
                title = "Avatar: Seven Havens",
            ),
        )
        val after = summary.countsWith(recognized)

        assertEquals(1, after.videoWorks)
        assertEquals(0, after.unknownVideo)
    }

    @Test
    fun `hiding a work removes it from the channel counters`() {
        val summary = TelegramChannelSummary(
            chatId = 7L,
            title = "Canal",
            works = listOf(
                TelegramWorkSummary(
                    id = "work-1",
                    chatId = 7L,
                    workKey = "a:0",
                    mediaKind = TelegramMediaKind.VIDEO,
                    title = "A",
                    resolved = true,
                ),
                TelegramWorkSummary(
                    id = "work-2",
                    chatId = 7L,
                    workKey = "b:0",
                    mediaKind = TelegramMediaKind.VIDEO,
                    title = "B",
                    resolved = true,
                ),
            ),
        )

        val counts = summary.countsWith(TelegramWorkOverrides().hide("a:0"))

        assertEquals(1, counts.videoWorks)
        assertEquals(listOf("B"), counts.works.map { it.title })
    }
}
