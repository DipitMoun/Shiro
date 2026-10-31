package eu.kanade.tachiyomi.data.social.identity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class WorkIdentityTest {

    @Test
    fun `work ids use the strongest available alias`() {
        val aliases = mapOf(
            "mal" to "52991",
            "anilist" to "154587",
            "anidb" to "17617",
        )

        assertEquals("anilist:154587", WorkId.fromAliases(aliases)?.value)
    }

    @Test
    fun `work ids reject malformed schemes and values`() {
        assertNull(WorkId.parse("anilist"))
        assertNull(WorkId.parse("unknown:154587"))
        assertNull(WorkId.parse("anilist:"))
        assertNull(WorkId.parse("anilist:bad value"))
        assertNull(WorkId.fromAliases(emptyMap()))
    }

    @Test
    fun `alias store resolves exact shared aliases and stays fail closed`() {
        val store = InMemoryWorkAliasStore()
        val canonical = WorkId.parse("anilist:154587")!!
        store.register(canonical, mapOf("mal" to "52991", "anidb" to "17617"))

        assertEquals(canonical, store.resolve(mapOf("mal" to "52991")))
        assertEquals(canonical, store.resolve(mapOf("anidb" to "17617")))
        assertNull(store.resolve(mapOf("mal" to "99999")))
        assertNull(store.resolve(mapOf("title" to "Sousou no Frieren")))

        val unrelated = InMemoryWorkAliasStore()
        unrelated.register(WorkId.parse("anilist:1")!!, mapOf("mal" to "1"))
        unrelated.register(WorkId.parse("anilist:2")!!, mapOf("mal" to "2"))
        assertNull(unrelated.resolve(mapOf("anilist" to "1", "mal" to "2")))
    }

    @Test
    fun `titles never merge two different works`() {
        val store = InMemoryWorkAliasStore()
        val first = WorkId.parse("anilist:100")!!
        val second = WorkId.parse("mal:200")!!
        store.register(first, mapOf("mal" to "100"))
        store.register(second, mapOf("mal" to "200"))

        assertNull(store.resolve(mapOf("title" to "Sousou no Frieren", "year" to "2023")))
        assertEquals(first, store.resolve(mapOf("mal" to "100")))
        assertEquals(second, store.resolve(mapOf("mal" to "200")))
    }

    @Test
    fun `episode keys format season and episode numbers`() {
        assertEquals("S01E003", EpisodeKey.of(1, 3.0).value)
        assertEquals("S00E001", EpisodeKey.of(0, 1.0).value)
        assertEquals("S01E003.5", EpisodeKey.of(1, 3.5).value)
        assertEquals("S02E003.05", EpisodeKey.of(2, 3.05).value)
        assertEquals("S01E1234", EpisodeKey.of(1, 1234.0).value)
        assertEquals("MAIN", EpisodeKey.MAIN.value)
    }

    @Test
    fun `episode keys parse canonical forms and reject invalid ones`() {
        assertEquals("S01E003.5", EpisodeKey.parse("S01E003.5")?.value)
        assertEquals("MAIN", EpisodeKey.parse("MAIN")?.value)
        assertNull(EpisodeKey.parse("S1E003"))
        assertNull(EpisodeKey.parse("S01E12"))
        assertNull(EpisodeKey.parse("S01E003."))
        assertThrows(IllegalArgumentException::class.java) { EpisodeKey.of(1, -1.0) }
    }
}
