package eu.kanade.tachiyomi.data.updater

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShiroUpdateTest {

    private val arm64 = ShiroUpdateApk(
        url = "https://example.org/shiro-arm64.apk",
        sha256 = "AAAA",
        size = 80_000_000,
        patches = listOf(
            ShiroUpdatePatch(fromSha256 = "1111", url = "https://example.org/patch-133.bin", size = 11_000_000),
            ShiroUpdatePatch(fromSha256 = "2222", url = "https://example.org/patch-132.bin", size = 30_000_000),
        ),
    )
    private val universal = ShiroUpdateApk(url = "https://example.org/shiro-universal.apk", sha256 = "BBBB")

    private val manifest = ShiroUpdateManifest(
        versionCode = 134,
        versionName = "0.19.1",
        notes = "Nouveautés",
        releasePage = "https://example.org/release",
        apks = mapOf("arm64-v8a" to arm64, "universal" to universal),
    )

    @Test
    fun `nothing to offer when the app is already up to date`() {
        assertNull(selectUpdate(manifest, 134, listOf("arm64-v8a")) { "1111" })
        assertNull(selectUpdate(manifest, 135, listOf("arm64-v8a")) { "1111" })
    }

    @Test
    fun `a patch is chosen when it starts from the installed apk`() {
        val plan = selectUpdate(manifest, 133, listOf("arm64-v8a", "armeabi-v7a")) { "1111" }!!

        assertEquals("https://example.org/patch-133.bin", plan.patchUrl)
        assertEquals(11_000_000, plan.patchSize)
        assertEquals("https://example.org/shiro-arm64.apk", plan.apkUrl)
        assertEquals("aaaa", plan.apkSha256)
        assertEquals("0.19.1", plan.versionName)
    }

    @Test
    fun `the installed hash is compared without regard to case`() {
        val plan = selectUpdate(manifest, 133, listOf("arm64-v8a")) { "2222".uppercase() }!!

        assertEquals("https://example.org/patch-132.bin", plan.patchUrl)
    }

    @Test
    fun `the full apk is used when no patch starts from the installed one`() {
        val plan = selectUpdate(manifest, 120, listOf("arm64-v8a")) { "9999" }!!

        assertNull(plan.patchUrl)
        assertEquals("https://example.org/shiro-arm64.apk", plan.apkUrl)
    }

    @Test
    fun `the full apk is used when the installed hash cannot be read`() {
        assertNull(selectUpdate(manifest, 133, listOf("arm64-v8a")) { null }!!.patchUrl)
    }

    @Test
    fun `the installed apk is only hashed when there are patches to compare`() {
        var hashed = 0
        selectUpdate(manifest.copy(apks = mapOf("universal" to universal)), 133, listOf("arm64-v8a")) {
            hashed++
            "1111"
        }

        assertEquals(0, hashed)
    }

    @Test
    fun `the first supported architecture the manifest has wins, universal is the last resort`() {
        val v7 = arm64.copy(url = "https://example.org/shiro-v7.apk")
        val both = manifest.copy(apks = mapOf("armeabi-v7a" to v7, "universal" to universal))

        assertEquals("https://example.org/shiro-v7.apk", selectUpdate(both, 133, listOf("arm64-v8a", "armeabi-v7a")) { null }!!.apkUrl)
        assertEquals("https://example.org/shiro-universal.apk", selectUpdate(both, 133, listOf("x86_64")) { null }!!.apkUrl)
    }

    @Test
    fun `no apk for this device means no update`() {
        assertNull(selectUpdate(manifest.copy(apks = mapOf("x86" to universal)), 133, listOf("arm64-v8a")) { null })
    }

    @Test
    fun `the manifest written by the release tool is understood`() {
        val raw = """
            {"versionCode":134,"versionName":"0.19.1","notes":"a\nb","releasePage":"https://x/r",
             "apks":{"arm64-v8a":{"url":"https://x/a.apk","sha256":"ab","size":5,
               "patches":[{"fromSha256":"cd","url":"https://x/p.bin","size":2}]}},"unknownField":1}
        """.trimIndent()

        val parsed = Json { ignoreUnknownKeys = true }.decodeFromString(ShiroUpdateManifest.serializer(), raw)

        assertEquals(134L, parsed.versionCode)
        assertEquals("a\nb", parsed.notes)
        assertTrue(parsed.apks.getValue("arm64-v8a").patches.single().url.endsWith("p.bin"))
    }
}
