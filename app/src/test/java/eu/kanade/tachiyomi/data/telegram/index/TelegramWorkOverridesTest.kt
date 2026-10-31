package eu.kanade.tachiyomi.data.telegram.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramWorkOverridesTest {

    @Test
    fun mergeAndUnmergeRestoresVariants() {
        val base = "cheating-exiled-magician:0"
        val variant = "cheating-exiled-magician-mkv-non-convertie:0"

        val merged = TelegramWorkOverrides()
            .merge(variant, base)
            .rename(base, "The Exiled Cheating Magician")

        assertEquals(base, merged.resolve(variant))
        assertEquals(setOf(base, variant), merged.sourcesFor(base))
        assertEquals("The Exiled Cheating Magician", merged.titleFor(variant))

        val split = merged.unmerge(base)
        assertEquals(variant, split.resolve(variant))
        assertEquals(setOf(variant), split.sourcesFor(variant))
        assertEquals(null, split.titleFor(variant))
    }

    @Test
    fun theExiledActualVariantsMergeAndUnmerge() {
        val base = "cheating-exiled-magician:0"
        val variant = "cheating-exiled-magician-mkv-𝐂𝐎𝐍𝐕𝐄𝐑𝐓𝐈𝐄-𝐍𝐎𝐍:0"

        val merged = TelegramWorkOverrides()
            .merge(variant, base)
            .rename(base, "The Exiled Cheating Magician")

        assertEquals(base, merged.resolve(variant))
        assertEquals(setOf(base, variant), merged.sourcesFor(base))

        val split = merged.unmerge(base)
        assertEquals(variant, split.resolve(variant))
        assertEquals(setOf(variant), split.sourcesFor(variant))
    }

    @Test
    fun recognizedMetadataReplacesMergedTitle() {
        val base = "cheating-exiled-magician:0"
        val variant = "cheating-exiled-magician-mkv-𝐂𝐎𝐍𝐕𝐄𝐑𝐓𝐈𝐄-𝐍𝐎𝐍:0"

        val recognized = TelegramWorkOverrides()
            .merge(variant, base)
            .rename(base, "The Exiled Cheating Magician")
            .withMetadata(
                base,
                TelegramResolvedWork(
                    query = "The Exiled Cheating Magician",
                    anilistId = 207329,
                    provider = "ANILIST",
                    title = "The Laid-Off Cheat-Granting Mage Enjoys a Second Lease on Life",
                    coverUrl = "https://example.invalid/cover.png",
                    score = 0.24,
                ),
            )

        assertEquals("The Laid-Off Cheat-Granting Mage Enjoys a Second Lease on Life", recognized.titleFor(variant))
        assertEquals("https://example.invalid/cover.png", recognized.coverFor(variant))
        assertTrue(recognized.isRecognized(variant))
        assertEquals(null, recognized.reset(base).titleFor(base))
    }

    @Test
    fun hiddenFollowsMergedTarget() {
        val overrides = TelegramWorkOverrides()
            .merge("variant", "canonical")
            .hide("canonical")

        assertTrue(overrides.isHidden("variant"))
        assertTrue(overrides.isHidden("canonical"))
        assertFalse(TelegramWorkOverrides().isHidden("variant"))
    }

    @Test
    fun cyclesAreBoundedAndResetIsComplete() {
        val overrides = TelegramWorkOverrides(
            merges = mapOf("a" to "b", "b" to "a"),
            titles = mapOf("a" to "A"),
            hidden = setOf("a"),
        )

        assertEquals("a", overrides.resolve("a"))
        assertEquals("b", overrides.resolve("b"))

        val reset = overrides.reset("a")
        assertEquals("a", reset.resolve("a"))
        assertFalse(reset.isHidden("a"))
        assertEquals(null, reset.titleFor("a"))
    }
}
