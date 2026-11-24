package eu.kanade.tachiyomi.data.multisource

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiSourceMangaMapperTest {

    private fun coverage(id: Long, name: String, vararg numbers: Int) = MangaSourceCoverage(
        sourceId = id,
        sourceName = name,
        sourceManga = SManga.create().apply {
            url = "/$name"
            title = name
        },
        chapters = numbers.associateWith { number ->
            SChapter.create().apply {
                url = "/$name/$number"
                this.name = "Chapitre $number"
                chapter_number = number.toFloat()
                date_upload = 1_000L * number
                scanlator = name
            }
        },
    )

    @Test
    fun `a chapter goes to the first source that has it`() {
        val result = assignChapters(
            listOf(coverage(1, "A", 1, 2, 3), coverage(2, "B", 2, 3, 4)),
            maxAlternatives = 3,
        )

        assertEquals(listOf(1, 2, 3, 4), result.primary.keys.toList())
        assertEquals(1L, result.primary.getValue(2).sourceId)
        assertEquals(2L, result.primary.getValue(4).sourceId)
    }

    @Test
    fun `later sources become fallbacks of the chapters they share`() {
        val result = assignChapters(
            listOf(coverage(1, "A", 1, 2), coverage(2, "B", 2, 3), coverage(3, "C", 2)),
            maxAlternatives = 3,
        )

        assertEquals(listOf(2L, 3L), result.alternatives.getValue(2).map { it.sourceId })
        assertTrue(1 !in result.alternatives)
        assertTrue(3 !in result.alternatives)
    }

    @Test
    fun `fallbacks are capped`() {
        val result = assignChapters(
            listOf(
                coverage(1, "A", 5),
                coverage(2, "B", 5),
                coverage(3, "C", 5),
                coverage(4, "D", 5),
            ),
            maxAlternatives = 2,
        )

        assertEquals(listOf(2L, 3L), result.alternatives.getValue(5).map { it.sourceId })
    }

    @Test
    fun `the chapter details survive in the reference`() {
        val ref = assignChapters(listOf(coverage(7, "Src", 12)), 3).primary.getValue(12)

        assertEquals("/Src/12", ref.episodeUrl)
        assertEquals("Chapitre 12", ref.episodeName)
        assertEquals(12_000L, ref.dateUpload)
        assertEquals("Src", ref.scanlator)
        assertEquals("/Src", ref.animeUrl)
    }

    @Test
    fun `no source gives an empty assignment`() {
        val result = assignChapters(emptyList(), 3)

        assertTrue(result.primary.isEmpty())
        assertTrue(result.alternatives.isEmpty())
    }
}
