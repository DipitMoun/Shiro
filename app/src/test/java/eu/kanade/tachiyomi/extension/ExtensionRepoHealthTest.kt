package eu.kanade.tachiyomi.extension

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtensionRepoHealthTest {

    private fun anime(vararg libs: Double, error: String? = null, stub: Boolean = false, unreachable: Boolean = false) =
        RepoHealthRules.classify(
            anime = true,
            name = "Dépôt",
            url = "https://example.org/repo",
            extensions = if (unreachable) null else libs.mapIndexed { i, lib -> RepoExtension("Ext$i", lib) },
            error = error,
            outdatedStub = stub,
        )

    private fun manga(vararg libs: Double) =
        RepoHealthRules.classify(
            anime = false,
            name = "Dépôt",
            url = "https://example.org/repo",
            extensions = libs.mapIndexed { i, lib -> RepoExtension("Ext$i", lib) },
            error = null,
            outdatedStub = false,
        )

    @Test
    fun `a repo whose extensions are all supported is fine`() {
        val health = anime(14.0, 16.0, 17.0)

        assertEquals(RepoStatus.OK, health.status)
        assertEquals(3, health.total)
        assertFalse(health.hasProblem)
    }

    @Test
    fun `an anime library the app does not know is reported with its extensions`() {
        val health = anime(16.0, 18.0, 15.0)

        assertEquals(RepoStatus.PARTLY_INCOMPATIBLE, health.status)
        assertEquals(listOf("Ext1 (lib 18)", "Ext2 (lib 15)"), health.incompatible)
    }

    @Test
    fun `manga extensions are checked against the 1_4 to 1_6 range`() {
        assertEquals(RepoStatus.OK, manga(1.4, 1.5, 1.6).status)
        assertEquals(listOf("Ext0 (lib 1.7)"), manga(1.7, 1.6).incompatible)
        assertEquals(listOf("Ext0 (lib 1.3)"), manga(1.3).incompatible)
    }

    @Test
    fun `a repo that does not answer is unreachable and says why`() {
        val health = anime(unreachable = true, error = "HTTP error 404")

        assertEquals(RepoStatus.UNREACHABLE, health.status)
        assertEquals("HTTP error 404", health.note)
        assertTrue(health.hasProblem)
    }

    @Test
    fun `a placeholder at the old address is an outdated format`() {
        assertEquals(RepoStatus.OUTDATED_FORMAT, anime(1.0, stub = true).status)
    }

    @Test
    fun `an empty catalogue is a problem`() {
        assertEquals(RepoStatus.EMPTY, anime().status)
    }

    @Test
    fun `the signature only changes when the problems change`() {
        fun report(vararg h: RepoHealth) = HealthReport(h.toList(), emptyList())

        assertEquals("", RepoHealthRules.signature(report(anime(16.0))))
        assertEquals(
            RepoHealthRules.signature(report(anime(18.0))),
            RepoHealthRules.signature(report(anime(18.0))),
        )
        assertFalse(
            RepoHealthRules.signature(report(anime(18.0))) == RepoHealthRules.signature(report(anime(18.0, 19.0))),
        )
    }

    private val installedSama = InstalledInfo("Anime-Sama", "pkg.animesama", "14.18", 18, signature = "AAAA")

    @Test
    fun `a newer build signed with another key is a blocked update`() {
        val blocked = RepoHealthRules.blockedUpdates(
            anime = true,
            installed = listOf(installedSama),
            available = listOf(AvailableInfo("pkg.animesama", "16.35", 35, "BBBB", "Yūzōnō")),
        )

        assertEquals(1, blocked.size)
        assertEquals("16.35", blocked[0].availableVersion)
        assertEquals("Yūzōnō", blocked[0].repoName)
    }

    @Test
    fun `an update the install accepts is not blocked`() {
        val blocked = RepoHealthRules.blockedUpdates(
            anime = true,
            installed = listOf(installedSama),
            available = listOf(
                AvailableInfo("pkg.animesama", "16.35", 35, "BBBB", "Autre"),
                AvailableInfo("pkg.animesama", "14.20", 20, "aaaa", "Même clé"),
            ),
        )

        assertTrue(blocked.isEmpty())
    }

    @Test
    fun `no newer version or an unknown key is never reported`() {
        val older = AvailableInfo("pkg.animesama", "14.10", 10, "BBBB", "R")
        val noKey = AvailableInfo("pkg.animesama", "16.35", 35, "NO_SIGNING_KEY", "R")

        assertTrue(RepoHealthRules.blockedUpdates(true, listOf(installedSama), listOf(older)).isEmpty())
        assertTrue(RepoHealthRules.blockedUpdates(true, listOf(installedSama), listOf(noKey)).isEmpty())
        assertTrue(
            RepoHealthRules.blockedUpdates(true, listOf(installedSama.copy(signature = null)), listOf(older.copy(versionCode = 99))).isEmpty(),
        )
    }

    @Test
    fun `a blocked update changes the signature`() {
        val blocked = BlockedUpdate(true, "Anime-Sama", "pkg.animesama", "14.18", "16.35", "Yūzōnō")

        assertEquals("blocked:pkg.animesama:16.35", RepoHealthRules.signature(HealthReport(emptyList(), listOf(blocked))))
    }
}
